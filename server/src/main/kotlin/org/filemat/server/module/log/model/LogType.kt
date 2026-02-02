package org.filemat.server.module.log.model

enum class LogType(val index: Int) {
    SECURITY    (0),
    AUTH        (1),
    AUDIT       (2),
    SYSTEM      (3);

    companion object {
        fun fromInt(value: Int) = LogType.entries.find { it.index == value } ?: throw IllegalStateException("LogType ENUM with index '$value' does not exist.")
    }
}