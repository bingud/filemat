package org.filemat.server.config.database

import java.sql.Connection

/**
 * Runs [block] in a transaction of its own, and commits it. When the connection is already in one (inside a Flyway migration),
 * the caller commits or rolls back, and this only runs [block].
 */
internal fun <T> inOwnTransaction(connection: Connection, block: () -> T): T {
    val ownTransaction = connection.autoCommit
    if (ownTransaction) connection.autoCommit = false

    try {
        val result = block()
        if (ownTransaction) connection.commit()
        return result
    } catch (e: Exception) {
        if (ownTransaction) connection.rollback()
        throw e
    } finally {
        if (ownTransaction) connection.autoCommit = true
    }
}
