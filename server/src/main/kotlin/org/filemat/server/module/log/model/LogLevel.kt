package org.filemat.server.module.log.model


enum class LogLevel(val index: Int) {
    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3),
    FATAL(4);

    companion object {
        fun fromInt(value: Int) = entries.find { it.index == value } ?: throw IllegalStateException("LogLevel ENUM with index '$value' does not exist.")
    }
}