package org.filemat.server.config.database

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.json.Json
import org.filemat.server.module.permission.model.FilePermission
import org.filemat.server.module.permission.model.PermissionType
import org.filemat.server.module.permission.model.SystemPermission
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.stereotype.Component

// Boolean - Int

@Component
@WritingConverter
class BooleanToIntConverter : Converter<Boolean, Int> {
    override fun convert(source: Boolean): Int {
        return if (source) 1 else 0
    }
}

@Component
@ReadingConverter
class IntToBooleanConverter : Converter<Int, Boolean> {
    override fun convert(source: Int): Boolean {
        return source == 1
    }
}

// Ulid - String

@Component
@WritingConverter
class UlidToStringConverter : Converter<Ulid, String> {
    override fun convert(source: Ulid): String {
        return source.toString()
    }
}

@Component
@ReadingConverter
class StringToUlidConverter : Converter<String, Ulid> {
    override fun convert(source: String): Ulid {
        return Ulid.from(source)
    }
}

// List<String> - String

@Component
@WritingConverter
class StringListToStringConverter : Converter<List<String>, String> {
    override fun convert(source: List<String>): String {
        return Json.encodeToString(source)
    }
}

@Component
@ReadingConverter
class StringToStringListConverter : Converter<String, List<String>> {
    override fun convert(source: String): List<String> {
        return Json.decodeFromString<List<String>>(source)
    }
}

/**
 * # Permission List
 */
@Component
@WritingConverter
class SystemPermissionListToStringConverter : Converter<List<SystemPermission>, String> {
    override fun convert(source: List<SystemPermission>): String {
        return Json.encodeToString(source.map { it.index })
    }
}

@Component
@ReadingConverter
class StringToSystemPermissionListConverter : Converter<String, List<SystemPermission>> {
    override fun convert(source: String): List<SystemPermission> {
        println("to multiple permissions")
        return Json.decodeFromString<List<Int>>(source).map { SystemPermission.fromInt(it) }
    }
}


@Component
@WritingConverter
class FilePermissionListToStringConverter : Converter<List<FilePermission>, String> {
    override fun convert(source: List<FilePermission>): String {
        return Json.encodeToString(source.map { it.index })
    }
}

@Component
@ReadingConverter
class StringToFilePermissionListConverter : Converter<String, List<FilePermission>> {
    override fun convert(source: String): List<FilePermission> {
        println("to multiple permissions")
        return Json.decodeFromString<List<Int>>(source).map { FilePermission.fromInt(it) }
    }
}

/**
 * # Permission
 */
@Component
@WritingConverter
class SystemPermissionToStringConverter : Converter<SystemPermission, String> {
    override fun convert(source: SystemPermission): String {
        return Json.encodeToString(source)
    }
}

@Component
@ReadingConverter
class StringToSystemPermissionConverter : Converter<String, SystemPermission> {
    override fun convert(source: String): SystemPermission {
        return SystemPermission.fromInt(source.toInt())
    }
}

@Component
@WritingConverter
class FilePermissionToStringConverter : Converter<FilePermission, String> {
    override fun convert(source: FilePermission): String {
        return Json.encodeToString(source)
    }
}

@Component
@ReadingConverter
class StringToFilePermissionConverter : Converter<String, FilePermission> {
    override fun convert(source: String): FilePermission {
        return FilePermission.fromInt(source.toInt())
    }
}

@Component
@WritingConverter
class PermissionTypeToStringConverter : Converter<PermissionType, String> {
    override fun convert(source: PermissionType): String {
        return source.ordinal.toString()
    }
}

@Component
@ReadingConverter
class StringToPermissionTypeConverter : Converter<String, PermissionType> {
    override fun convert(source: String): PermissionType {
        return PermissionType.fromInt(source.toInt())
    }
}