package org.filemat.server.module.file.service.component

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.config.Props
import org.filemat.server.module.file.model.FilePath
import org.springframework.stereotype.Service
import java.io.IOException
import java.lang.UnsupportedOperationException
import java.nio.file.*

@Service
class FileMoveService {

    fun moveFile(source: FilePath, destination: FilePath, overwriteDestination: Boolean = false): Result<Unit> {
        // Block move if editing data folder is blocked
        val isProtected = source.path.startsWith(Props.dataFolderPath)
        val destinationIsProtected = destination.startsWith(Props.dataFolderPath)
        // Move recursively without touching data folder
        val containsProtected = Props.dataFolderPath.startsWith(source.path)

        if (!State.App.allowWriteDataFolder) {
            if (isProtected) return Result.reject("Cannot move ${Props.appName} data folder.")
            if (destinationIsProtected) return Result.reject("Cannot move into ${Props.appName} data folder.")
        }

        return try {
            Files.move(
                source.path,
                destination.path,
                *if (overwriteDestination) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
            )
            Result.ok()
        } catch (e: NoSuchFileException) {
            Result.notFound()
        } catch (e: FileAlreadyExistsException) {
            Result.error("This file already exists.")
        } catch (e: DirectoryNotEmptyException) {
            moveRecursivelyIfDifferentPartition(source, destination, overwriteDestination, "Failed to move file because folder is not empty.")
        } catch (e: UnsupportedOperationException) {
            Result.error("This move operation failed because it is unsupported.")
        } catch (e: AccessDeniedException) {
            Result.error("Missing permission to move file.")
        } catch (e: IOException) {
            moveRecursivelyIfDifferentPartition(source, destination, overwriteDestination, "Failed to move file.")
        } catch (e: Exception) {
            Result.error("Failed to move file.")
        }
    }

    private fun moveRecursivelyIfDifferentPartition(
        source: FilePath,
        destination: FilePath,
        overwriteDestination: Boolean,
        originalError: String,
    ): Result<Unit> {
        if (!Files.isDirectory(source.path, LinkOption.NOFOLLOW_LINKS)) return Result.error(originalError)

        moveFolderRecursively(source, destination, overwriteDestination).let {
            if (it.hasError) return Result.reject("Failed to move folder to a different partition (${it.error}).")
            return it
        }
    }

    private fun moveFolderRecursively(source: FilePath, destination: FilePath, overwriteDestination: Boolean): Result<Unit> {
        var errors = 0
        try {
            if (Files.notExists(destination.path)) {
                Files.createDirectories(destination.path)
            }

            Files.newDirectoryStream(source.path).use { stream ->
                for (entry in stream) {
                    try {
                        val entrySource = FilePath(entry)
                        val entryDestination = FilePath(destination.path.resolve(entry.fileName))

                        val result = if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                            moveFolderRecursively(entrySource, entryDestination, overwriteDestination)
                        } else {
                            moveFile(entrySource, entryDestination, overwriteDestination)
                        }

                        if (!result.isSuccessful) {
                            errors++
                        }
                    } catch (e: Exception) {
                        errors++
                    }
                }
            }

            if (errors > 0) {
                val w = if (errors > 1) "files" else "file"
                return Result.error("$errors $w failed to move.")
            } else {
                Files.delete(source.path)
            }


            return Result.ok()
        } catch (e: Exception) {
            return Result.error("Failed to move folder recursively.")
        }
    }

}