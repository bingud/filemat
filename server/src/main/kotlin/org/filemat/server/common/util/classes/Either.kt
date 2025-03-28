package org.filemat.server.common.util.classes

import java.util.Optional

data class Either<T : Any, Y : Any>(
    private val first: Optional<T>,
    private val second: Optional<Y>,
) {
    companion object {
        fun <T  : Any, Y  : Any> first(value: T): Either<T, Y> = Either<T, Y>(Optional.ofNullable(value), Optional.empty())
        fun <T  : Any, Y  : Any> second(value: Y): Either<T, Y> = Either<T, Y>(Optional.empty(), Optional.ofNullable(value))
    }
}