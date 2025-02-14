package org.filemat.server.config.database

import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration
import org.springframework.jdbc.core.JdbcTemplate
import java.util.*


@Configuration
class DatabaseConfig(
    private val jdbcTemplate: JdbcTemplate,
) {



}

@Configuration
class JdbcConfig : AbstractJdbcConfiguration() {
    override fun userConverters(): List<*> {
        return listOf(
            BooleanToIntConverter(),
            IntToBooleanConverter(),

            UlidToStringConverter(),
            StringToUlidConverter(),

            StringListToStringConverter(),
            StringToStringListConverter(),

            PermissionListToStringConverter(),
            StringToPermissionListConverter(),

//            PermissionToStringConverter(),
//            StringToPermissionConverter(),
        )
    }
}