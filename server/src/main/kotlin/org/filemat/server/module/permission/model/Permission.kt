package org.filemat.server.module.permission.model

import kotlinx.serialization.json.Json


/**
 # DO NOT RE-ORDER ENUMS
 */
enum class Permission {
    READ,                                   // 0
    DELETE,                                 // 1
    WRITE,                                  // 2
    SHARE,                                  // 3
    RENAME,                                 // 4
    ACCESS_ALL_FILES,                       // 5
    MANAGE_OWN_FILE_PERMISSIONS,            // 6
    MANAGE_ALL_FILE_PERMISSIONS,            // 7
    MANAGE_USERS,                           // 8
    MANAGE_SYSTEM,                          // 9
    EDIT_ROLES,                             // 10
    EXPOSE_FOLDERS;                         // 11

    companion object {
        fun fromInt(int: Int): Permission {
            return Permission.entries.getOrNull(int) ?: throw IllegalArgumentException("Permission ENUM  -  integer index of enum does not exist.")
        }

        fun fromString(str: String): List<Permission> {
            val list = Json.decodeFromString<List<Int>>(str)
            return list.map { fromInt(it) }
        }
    }
}

fun List<Permission>.toIntList(): List<Int> {
    return this.map { p -> p.ordinal }
}

fun List<Permission>.serialize(): String = this.toIntList().toString()