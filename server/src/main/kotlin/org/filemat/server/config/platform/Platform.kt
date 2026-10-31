package org.filemat.server.config.platform

import java.io.File

/**
 * The operating system Filemat runs on.
 */
object Platform {
    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /**
     * Style of native file paths, sent to the frontend ("windows" or "posix")
     */
    val pathStyle: String = if (isWindows) "windows" else "posix"

    /**
     * Separator of native file paths
     */
    val separator: String = File.separator

    /**
     * Separator of list environment variables (`;` on Windows, `:` elsewhere)
     */
    val listSeparator: String = File.pathSeparator
}
