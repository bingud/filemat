package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Statement


open class Migration : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        connection.createStatement().use { statement ->
            run(statement)

            val sql = execute()
            if (sql != null) statement.execute(sql)
        }
    }

    open fun run(st: Statement) {}
    open fun execute(): String? = null
}