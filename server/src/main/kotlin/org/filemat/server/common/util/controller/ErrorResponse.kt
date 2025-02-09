package org.filemat.server.common.util.controller

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


@Serializable
data class ErrorResponse(
    val message: String,
    val error: String
) {
    fun serialize(): String {
        return Json.encodeToString(this)
    }
}