package org.filemat.server.common.util.classes

import java.util.concurrent.atomic.AtomicBoolean

class Locker {
    private val bool = AtomicBoolean(false)

    fun <T> run(default: T, func: () -> T): T {
        if (!bool.compareAndSet(false, true)) return default
        try {
            return func()
        } finally {
            bool.set(false)
        }
    }
}