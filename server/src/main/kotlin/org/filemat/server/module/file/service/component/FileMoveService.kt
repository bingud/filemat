package org.filemat.server.module.file.service.component

import org.filemat.server.common.State
import org.filemat.server.common.model.Result
import org.filemat.server.common.model.cast
import org.filemat.server.common.util.PathRelationship
import org.filemat.server.common.util.getPathRelationship
import org.filemat.server.common.util.walkFilesWithExcludedFile
import org.filemat.server.config.Props
import org.filemat.server.module.file.model.FilePath
import org.springframework.stereotype.Service
import java.io.IOException
import java.lang.UnsupportedOperationException
import java.nio.file.*
import kotlin.io.path.isDirectory

@Service
class FileMoveService {

    fun moveFile(source: FilePath, destination: FilePath, overwriteDestination: Boolean = false): Result<Unit> {
        if (destination.startsWith(source)) return Result.reject("Cannot move file into itself.")

        getPathRelationship(destination.path, Props.dataFolderPath)
            .let { destinationRelation ->
                if (destinationRelation.isInsideTarget) return Result.reject("File cannot be moved into ${Props.appName} data folder.")
            }

        val relation = getPathRelationship(source.path, Props.dataFolderPath)

        if (State.App.allowWriteDataFolder == false) {
            if (relation.isInsideTarget) return Result.reject("Cannot modify ${Props.appName} data folder.")
            if (relation.containsTarget) {
                if (!source.path.isDirectory(LinkOption.NOFOLLOW_LINKS)) return Result.reject("Cannot move ${Props.appName} data folder.")
                return moveRecursively(
                    source = source,
                    destination = destination,
                    relation = relation,
                    overwriteDestination = overwriteDestination
                )
            }
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
            moveRecursivelyIfDifferentPartition(source, destination, relation, overwriteDestination, "Failed to move file because folder is not empty.")
        } catch (e: UnsupportedOperationException) {
            Result.error("This move operation failed because it is unsupported.")
        } catch (e: AccessDeniedException) {
            Result.error("Missing permission to move file.")
        } catch (e: IOException) {
            moveRecursivelyIfDifferentPartition(source, destination, relation, overwriteDestination, "Failed to move file.")
        } catch (e: Exception) {
            Result.error("Failed to move file.")
        }
    }

    private fun moveRecursivelyIfDifferentPartition(
        source: FilePath,
        destination: FilePath,
        relation: PathRelationship,
        overwriteDestination: Boolean,
        originalError: String,
    ): Result<Unit> {
        if (!Files.isDirectory(source.path, LinkOption.NOFOLLOW_LINKS)) return Result.error(originalError)

        moveRecursively(
            source = source,
            destination = destination,
            relation = relation,
            overwriteDestination = overwriteDestination
        ).let {
            if (it.hasError) return Result.reject("Failed to move folder to a different partition (${it.error}).")
            return it
        }
    }

    private fun moveRecursively(source: FilePath, destination: FilePath, relation: PathRelationship, overwriteDestination: Boolean): Result<Unit> {
        if (Files.notExists(destination.path)) {
            Files.createDirectories(destination.path)
        }

        val moveResult = if (relation.containsTarget) {
            internal_moveRecursivelyManually(source.path, destination.path, overwriteDestination)
        } else {
            internal_moveRecursively(source.path, destination.path, overwriteDestination)
        }

        if (moveResult.hasError) return moveResult.cast()

        return try {
            Files.delete(source.path)
            Result.ok()
        } catch (e: Exception) {
            Result.error("Moved files but failed to delete source folder.")
        }
    }

    private fun internal_moveRecursively(source: Path, destination: Path, overwriteDestination: Boolean): Result<Int> {
        var errors = 0
        Files.newDirectoryStream(source).use { stream ->
            for (entry in stream) {
                try {
                    val entryDestination = FilePath(destination.resolve(entry.fileName))
                    val result = moveFile(FilePath(entry), entryDestination, overwriteDestination)
                    if (!result.isSuccessful) errors++
                } catch (e: Exception) {
                    errors++
                }
            }
        }
        if (errors > 0) {
            val w = if (errors > 1) "files" else "file"
            return Result.error("$errors $w failed to move.")
        }
        return Result.ok(0)
    }

    private fun internal_moveRecursivelyManually(
        source: Path,
        destination: Path,
        overwriteDestination: Boolean,
    ): Result<Int> {
        return walkFilesWithExcludedFile(
            path = source,
            excludedPath = Props.dataFolderPath,
            recursive = false,
            failedResult = { count -> Result.error("$count files could not be moved.") },
        ) { child ->
            val relativePath = source.relativize(child)
            val childDestination = destination.resolve(relativePath)

            childDestination.parent?.let { parent ->
                if (Files.notExists(parent)) {
                    Files.createDirectories(parent)
                }
            }

            moveFile(FilePath(child), FilePath(childDestination), overwriteDestination).isSuccessful
        }
    }
}