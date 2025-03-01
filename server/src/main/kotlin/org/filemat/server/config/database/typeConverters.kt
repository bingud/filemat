package org.filemat.server.config.database

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.json.Json
import org.filemat.server.module.permission.model.Permission
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


// List<Permission> - String

// this type converter is worthless because JDBC devs made the decision to throw their brain down the drain
@Component
@WritingConverter
class PermissionListToStringConverter : Converter<List<Permission>, String> {
    override fun convert(source: List<Permission>): String {
        return Json.encodeToString(source.map { it.ordinal })
    }
}

@Component
@ReadingConverter
class StringToPermissionListConverter : Converter<String, List<Permission>> {
    override fun convert(source: String): List<Permission> {
        println("to multiple permissions")
        return Json.decodeFromString<List<Int>>(source).map { Permission.fromInt(it) }
    }
}

// Permission

@Component
@WritingConverter
class PermissionToStringConverter : Converter<Permission, String> {
    override fun convert(source: Permission): String {
        return Json.encodeToString(source)
    }
}

@Component
@ReadingConverter
class StringToPermissionConverter : Converter<String, Permission> {
    override fun convert(source: String): Permission {
        println("to one permission")
        return Permission.fromInt(source.toInt())
    }
}