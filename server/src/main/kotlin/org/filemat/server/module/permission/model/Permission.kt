package org.filemat.server.module.permission.model

import kotlinx.serialization.json.Json
import kotlin.enums.EnumEntries

interface Permission {
    val index: Int

    companion object {
        fun <T> fromInt(int: Int, values: EnumEntries<T>): T where T : Enum<T>, T : Permission {
            return values.firstOrNull { it.index == int }
                ?: throw IllegalArgumentException("Permission ENUM - integer index of enum does not exist.")
        }

        fun <T> fromString(str: String, values: EnumEntries<T>): List<T> where T : Enum<T>, T : Permission {
            return Json.decodeFromString<List<Int>>(str).map { int ->
                values.firstOrNull { it.index == int }
                    ?: throw IllegalArgumentException("Permission ENUM - integer index of enum does not exist.")
            }
        }
    }
}



/**
 * # System permissions
 */
enum class SystemPermission(override val index: Int, val level: Int) : Permission {
    ACCESS_ALL_FILES(100, 2),
    MANAGE_OWN_FILE_PERMISSIONS(101, 1),
    MANAGE_ALL_FILE_PERMISSIONS(102, 1),
    MANAGE_USERS(103, 3),
    MANAGE_SYSTEM(104, 3),
    EDIT_ROLES(105, 3),
    EXPOSE_FOLDERS(106, 1),
    SUPER_ADMIN(107, 4),
    MANAGE_ALL_FILE_SHARES(108, 1),
    CHANGE_OWN_HOME_FOLDER(109, 1);

    companion object {
        fun fromInt(int: Int) = Permission.fromInt(int, entries)
        fun fromString(str: String) = Permission.fromString(str, entries)
    }
}

/**
 * # File permissions
 */
enum class FilePermission(override val index: Int) : Permission {
    READ    (index = 0),
    DELETE  (index = 1),
    WRITE   (index = 2),
    SHARE   (index = 3),
    RENAME  (index = 4),
    MOVE    (index = 5);

    companion object {
        fun fromInt(int: Int) = Permission.fromInt(int, entries)
        fun fromString(str: String) = Permission.fromString(str, entries)
    }
}


fun List<Permission>.toIntList(): List<Int> {
    return this.map { p -> p.index }
}

fun Collection<Permission>.serialize(): String {
    val list = this.map { it.index }
    return Json.encodeToString(list)
}

fun List<SystemPermission>.hasSufficientPermissionsFor(list: List<SystemPermission>): Boolean {
    val levels = this.map { it.level }
    val targetLevels = list.map { it.level }

    val highest = levels.maxOrNull() ?: 0
    val targetHighest = targetLevels.maxOrNull() ?: 0

    return (highest >= targetHighest)
}