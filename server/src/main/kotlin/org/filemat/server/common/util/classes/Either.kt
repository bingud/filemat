package org.filemat.server.common.util.classes

sealed class Either<out A, out B> {
    /** Represents the first side of Either with a value of type A */
    data class First<A>(val value: A) : Either<A, Nothing>()

    /** Represents the second side of Either with a value of type B */
    data class Second<B>(val value: B) : Either<Nothing, B>()

    // Helper properties
    val isFirst: Boolean get() = this is First
    val isSecond: Boolean get() = this is Second

    // Safe accessors
    fun getLeftOrNull(): A? = when (this) {
        is First -> value
        else -> null
    }

    fun getRightOrNull(): B? = when (this) {
        is Second -> value
        else -> null
    }

    // Accessor that throws if called on wrong side
    val first: A get() = when (this) {
        is First -> value
        else -> throw NoSuchElementException("Either.first called on Second instance")
    }

    val second: B get() = when (this) {
        is Second -> value
        else -> throw NoSuchElementException("Either.second called on First instance")
    }

    // Fold function to handle both cases
    fun <C> fold(leftFn: (A) -> C, rightFn: (B) -> C): C = when (this) {
        is First -> leftFn(value)
        is Second -> rightFn(value)
    }

    // Map functions
    fun <C> mapLeft(fn: (A) -> C): Either<C, B> = when (this) {
        is First -> First(fn(value))
        is Second -> this as Either<C, B> // Safe because B doesn't change
    }

    fun <C> mapRight(fn: (B) -> C): Either<A, C> = when (this) {
        is First -> this as Either<A, C> // Safe because A doesn't change
        is Second -> Second(fn(value))
    }

    companion object {
        // Factory methods
        fun <A, B> left(value: A): Either<A, B> = First(value)
        fun <A, B> right(value: B): Either<A, B> = Second(value)
    }
}
