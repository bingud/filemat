package org.filemat.server.common.util.classes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger

class CounterStateFlow(initialValue: Int = 0) {
    private val atomicInt = AtomicInteger(initialValue)
    private val _stateFlow = MutableStateFlow(atomicInt.get())
    val stateFlow: StateFlow<Int> get() = _stateFlow.asStateFlow()

    /** Atomically increments the counter */
    fun increment() {
        _stateFlow.value = atomicInt.incrementAndGet()
    }

    /** Atomically decrements the counter */
    fun decrement() {
        _stateFlow.value = atomicInt.decrementAndGet()
    }

    /** Suspends until the counter reaches zero */
    suspend fun awaitZero() {
        stateFlow.first { it == 0 }
    }
}
