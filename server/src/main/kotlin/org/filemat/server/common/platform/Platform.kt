package org.filemat.server.common.platform

import java.nio.file.FileSystems

object Platform {
    val isWindows: Boolean = System.getProperty("os.name")
        .lowercase()
        .contains("win")

    val isPosix: Boolean = FileSystems.getDefault()
        .supportedFileAttributeViews()
        .contains("posix")
}
