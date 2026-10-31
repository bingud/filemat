package org.filemat.server.config.database

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.DriverManager

class TransactionsTest {

    private fun connection(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        connection.createStatement().use { it.execute("CREATE TABLE t (v INTEGER)") }
        return connection
    }

    private fun insert(connection: Connection) = connection.createStatement().use { it.execute("INSERT INTO t VALUES (1)") }

    private fun count(connection: Connection) = connection.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM t").use { it.next(); it.getInt(1) }
    }

    @Test
    fun `commits its own transaction and turns auto-commit back on`() {
        connection().use { connection ->
            val result = inOwnTransaction(connection) { insert(connection); 7 }

            assertEquals(7, result)
            assertTrue(connection.autoCommit)
            assertEquals(1, count(connection))
        }
    }

    @Test
    fun `rolls back its own transaction when the block throws`() {
        connection().use { connection ->
            assertThrows<IllegalStateException> {
                inOwnTransaction(connection) {
                    insert(connection)
                    throw IllegalStateException("failed")
                }
            }

            assertTrue(connection.autoCommit)
            assertEquals(0, count(connection))
        }
    }

    @Test
    fun `inside the caller's transaction, the caller commits or rolls back`() {
        connection().use { connection ->
            connection.autoCommit = false

            inOwnTransaction(connection) { insert(connection) }
            assertFalse(connection.autoCommit)
            connection.rollback()
            assertEquals(0, count(connection))

            insert(connection)
            assertThrows<IllegalStateException> { inOwnTransaction(connection) { throw IllegalStateException("failed") } }
            // Not rolled back: that is the caller's decision
            assertEquals(1, count(connection))
            connection.rollback()
        }
    }
}
