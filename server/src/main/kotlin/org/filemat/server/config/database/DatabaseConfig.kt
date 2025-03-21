package org.filemat.server.config.database

import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration
import java.util.*


/**
 * Custom JDBC type converters
 */
@Configuration
class JdbcConfig() : AbstractJdbcConfiguration() {
    override fun userConverters(): List<*> {
        return listOf(
            BooleanToIntConverter(),
            IntToBooleanConverter(),

            UlidToStringConverter(),
            StringToUlidConverter(),

            StringListToStringConverter(),
            StringToStringListConverter(),

            // permission list
            SystemPermissionListToStringConverter(),
            StringToSystemPermissionListConverter(),

            FilePermissionListToStringConverter(),
            StringToFilePermissionListConverter(),

            // permission
            SystemPermissionToStringConverter(),
            StringToFilePermissionListConverter(),

            FilePermissionToStringConverter(),
            StringToFilePermissionConverter()
        )
    }
}