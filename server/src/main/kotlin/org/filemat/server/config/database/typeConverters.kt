package org.filemat.server.config.database

import com.github.f4b6a3.ulid.Ulid
import kotlinx.serialization.json.Json
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

// Ulid - String

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

