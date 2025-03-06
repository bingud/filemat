package org.filemat.server.common.util.classes

import org.filemat.server.common.model.Result
import org.filemat.server.common.model.toResult
import java.util.concurrent.atomic.AtomicBoolean

class Token {
    private val isConsumed = AtomicBoolean(false)

    fun <T> consume(block: () -> T): Result<T> {
        if (isConsumed.getAndSet(true)) return Result.reject("")
        return block().toResult()
    }

    fun reset() = isConsumed.set(false)

}