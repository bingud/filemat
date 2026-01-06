package org.filemat.server.module.file.service.component

import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.getNoFollowLinksOption
import org.filemat.server.common.util.resolvePath
import org.filemat.server.module.file.model.FilePath
import org.springframework.stereotype.Service
import java.io.IOException
import java.nio.file.*
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink

@Service
class FileCopyService {
    fun copyFile(source: FilePath, canonicalSource: FilePath? = null, destination: FilePath): Result<Unit> {
        return try {
            if (destination == source) return Result.reject("Copied file cannot have the same filename.")
            if (destination.startsWith(source)) return Result.reject("File cannot be copied into itself.")
            if (destination.path.exists(LinkOption.NOFOLLOW_LINKS)) return Result.reject("This file already exists.")

            val isSymlink = source.path.isSymbolicLink()

            // Check if symlink points to a parent of itself
            if (isSymlink) {
                val resolvedSource = canonicalSource ?: resolvePath(source).let {
                    val result = it.first
                    if (result.isNotSuccessful) return result.cast()
                    result.value
                }

                if (source.path.startsWith(resolvedSource.path)) return Result.ok()
            }

            val symlinkCopyOption = getNoFollowLinksOption()

            if (Files.isDirectory(source.path, *symlinkCopyOption)) {
                copyFolderRecursively(
                    source = source,
                    destination = destination,
                )
            } else {
                Files.copy(
                    source.path,
                    destination.path,
                    *symlinkCopyOption,
                )
                Result.ok()
            }
        } catch (e: NoSuchFileException) {
            Result.notFound()
        } catch (e: FileAlreadyExistsException) {
            Result.error("This file already exists.")
        } catch (e: AccessDeniedException) {
            Result.error("Missing permission to copy file.")
        } catch (e: IOException) {
            Result.error("Failed to copy file due to I/O error.")
        } catch (e: Exception) {
            Result.error("Failed to copy file.")
        }
    }

    private fun copyFolderRecursively(
        source: FilePath,
        destination: FilePath,
    ): Result<Unit> {
        var errors = 0
        try {
            if (Files.notExists(destination.path)) {
                Files.createDirectories(destination.path)
            }

            Files.newDirectoryStream(source.path).use { stream ->
                for (entry in stream) {
                    val entrySource = FilePath(entry)
                    val entryDestination = FilePath(destination.path.resolve(entry.fileName))

                    val result = copyFile(
                        source = entrySource,
                        destination = entryDestination,
                    )
                    if (!result.isSuccessful) {
                        errors++
                    }
                }
            }

            if (errors > 0) {
                val w = if (errors > 1) "files" else "file"
                return Result.error("$errors $w failed to copy.")
            }

            return Result.ok()
        } catch (e: Exception) {
            return Result.error("Failed to copy folder.")
        }
    }

}