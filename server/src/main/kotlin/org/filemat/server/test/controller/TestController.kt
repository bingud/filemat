package org.filemat.server.test.controller

import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.Serializable
import org.filemat.server.common.State
import org.filemat.server.common.util.getPrincipal
import org.filemat.server.config.auth.Authenticated
import org.filemat.server.config.auth.Unauthenticated
import org.filemat.server.module.log.repository.LogRepository
import org.filemat.server.module.log.service.LogService
import org.filemat.server.module.permission.model.Permission
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.UserDefinedFileAttributeView

@RestController
class TestController(private val logService: LogService, private val logRepository: LogRepository) {

    @Unauthenticated
    @GetMapping("/test")
    fun sus(request: HttpServletRequest): String {
        val auth = request.getPrincipal()
        println(auth?.roles)
        println(State.Auth.roleMap)
        return "bong"
    }

    @Authenticated([Permission.MANAGE_SYSTEM])
    @GetMapping("/test/auth")
    fun susta(request: HttpServletRequest): String {
        val auth = request.getPrincipal()!!
        println(auth.roles)
        println(State.Auth.roleMap)
        return "bong"
    }

}


fun setXattr() {
    val path = Paths.get("/usr/bin/zcat")
    val view = Files.getFileAttributeView(path, UserDefinedFileAttributeView::class.java)
    val buffer = ByteBuffer.wrap("this is a certified XATTR".toByteArray())

    view.write("user.shit", buffer)

}



@Serializable
data class FileInfo(
    val name: String,
    val type: String, // "file" or "directory"
    val inode: Long,
    val xattr: String? // Replace with actual type of xattr
)

fun listDirectoryWithMetadata(dir: Path, xattrKey: String): List<FileInfo> {
    return Files.newDirectoryStream(dir).use { stream ->
        stream.mapNotNull { path ->
            try {
                val attrs = Files.readAttributes<BasicFileAttributes>(path, BasicFileAttributes::class.java)
                val inode = Files.getAttribute(path, "unix:ino") as? Long ?: return@mapNotNull null
                val xattr = try {
                    getXattr(path, xattrKey)
                } catch (e: Exception) {
                    null
                }
                FileInfo(
                    name = path.fileName.toString(),
                    type = if (attrs.isDirectory) "directory" else "file",
                    inode = inode,
                    xattr = xattr
                )
            } catch (e: Exception) {
                null // Ignore errors and continue
            }
        }
    }
}

fun getXattr(path: Path, rawKey: String): String? {
    val key = "user.$rawKey"
    val view = Files.getFileAttributeView(path, UserDefinedFileAttributeView::class.java)
    val size = view.size(key)
    val buffer = ByteBuffer.allocate(size)

    view.read(key, buffer)
    buffer.flip()

    return String(buffer.array(), Charsets.UTF_8) // Convert bytes to String
}
