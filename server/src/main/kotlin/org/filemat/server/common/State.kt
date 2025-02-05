package org.filemat.server.common

object State {
    object App {
        var isSetup: Boolean? = null
        var isInitialized: Boolean = false
        val isDev = env("FM_DEV_MODE")?.toBooleanStrictOrNull() ?: false
    }
}

private fun env(name: String): String? {
    val value = System.getenv(name)
    return value
}