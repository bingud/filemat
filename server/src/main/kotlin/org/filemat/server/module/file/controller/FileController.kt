package org.filemat.server.module.file.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.filemat.server.common.State
import org.filemat.server.common.util.FileType
import org.filemat.server.common.util.FileUtils
import org.filemat.server.common.util.classes.CounterStateFlow
import org.filemat.server.common.util.controller.AController
import org.filemat.server.common.util.getFileType
import org.filemat.server.common.util.measureNano
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.pathString

@Serializable
data class FileMeta(
    val filename: String,
    val modificationTime: Long,
    val creationTime: Long,
    val fileType: FileType,
    val size: Long,
    val inode: Long,
)

@RestController
@RequestMapping("/v1/folder")
class FileController : AController() {

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @PostMapping("/list")
    fun listFolderItemsMapping(
        request: HttpServletRequest,
        @RequestParam("path") rawPath: String
    ): ResponseBodyEmitter {
        val p = Paths.get(rawPath.trim())

        val emitter = ResponseBodyEmitter()
        val semaphore = Semaphore(4)
        val first = AtomicBoolean(true)
        val totalNano = AtomicLong(0)
        val counter = CounterStateFlow()

        scope.launch {
            try {
                emitter.send("[")
                Files.list(p).use { paths ->
                    paths.toList().forEach {
                        counter.increment()
                        scope.launch {
                            semaphore.withPermit {
                                delay(1000)
                                val (meta, nano) = measureNano { getMeta(it.pathString) }
                                totalNano.addAndGet(nano)

                                val json = Json.encodeToString(meta)
                                if (!first.getAndSet(false)){
                                    emitter.send(",$json")
                                } else {
                                    emitter.send(json)
                                }

                                counter.decrement()
                            }
                        }
                    }
                }
                counter.awaitZero()
                emitter.send("]")
                emitter.complete()
            } catch (e: Exception) {
                emitter.completeWithError(Exception("Failed to stream files"))
            }
        }
        return emitter
    }


}



fun getMeta(_path: String): FileMeta {
    val path = Paths.get(_path)

    val attributes = if (!State.App.followSymLinks) {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } else {
        val realPath = path.toRealPath()
        Files.readAttributes(realPath, BasicFileAttributes::class.java)
    }

    val type = attributes.getFileType()
    val creationTime = attributes.creationTime().toMillis()
    val modificationTime = attributes.lastModifiedTime().toMillis()

    val inode = attributes.fileKey()?.toString().orEmpty()
        .substringAfter("ino=").let {
                val inode = StringBuilder()
                it.forEach { char ->
                    if (char.isDigit()) {
                        inode.append(char)
                    } else {
                        return@forEach
                    }
                }
                inode.toString().toLongOrNull() ?: throw IllegalStateException("File doesnt have inode.")
            }

    return FileMeta(
        filename = path.fileName.toString(),
        modificationTime = modificationTime,
        creationTime = creationTime,
        fileType = type,
        size = attributes.size(),
        inode = inode
    )
}