package org.filemat.server.module.file.model

enum class FileType {
    FILE,
    FOLDER,
    FILE_LINK,
    FOLDER_LINK,
    OTHER
}

fun FileType.isSymLink() = this == FileType.FILE_LINK || this == FileType.FOLDER_LINK
fun FileType.isRegularFile() = this == FileType.FILE || this == FileType.FOLDER