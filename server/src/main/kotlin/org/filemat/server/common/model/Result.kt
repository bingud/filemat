package org.filemat.server.common.model



data class Result<T>(
    private val inputValue: T? = null,
    private val inputError: String? = null,
    private val inputNotFound: Boolean = false,
    private val rejectInput: String? = null,
//    var source: String? = null,
) {

    companion object {
        fun <T> ok(value: T): Result<T> = Result(inputValue = value)
        fun ok(): Result<Unit> = Result()
        fun <T> error(message: String): Result<T> = Result(inputError = message)
        fun <T> notFound(): Result<T> = Result(inputNotFound = true)
        fun <T> reject(message: String): Result<T> = Result(rejectInput = message)

//        fun <T> error(message: String, source: String? = null): Result<T> = Result(inputError = message, source = source)
//        fun <T> notFound(source: String? = null): Result<T> = Result(inputNotFound = true, source = source)
//        fun <T> reject(message: String, source: String? = null): Result<T> = Result(rejectInput = message, source = source)
    }

    val hasError
        get() = inputError != null

    val isSuccessful
        get() = inputError == null && !inputNotFound && rejectInput == null

    val isNotSuccessful
        get() = !isSuccessful

    val notFound
        get() = inputNotFound

    val rejected
        get() = rejectInput != null

    val value: T
        get() {
            if (inputValue == null) throw IllegalStateException("Tried to access empty value in result;  notFound $notFound, rejected $rejected, hasError $hasError")
            return inputValue
        }

    val error: String
        get() {
            return inputError ?: rejectInput ?: throw IllegalStateException("Tried to access null error or rejection message in Result class.")
        }

    val errorOrNull: String?
        get() = inputError ?: rejectInput

    val valueOrNull: T?
        get() = inputValue
}

fun <T> T.toResult(): Result<T> {
    return Result.ok(this)
}

/**
 * Changes the result type to inferred type so that result can be returned directly
 */
@Suppress("UNCHECKED_CAST")
fun <T, P> Result<P>.cast(): Result<T> = this as Result<T>

//@Suppress("UNCHECKED_CAST")
//fun <T, P> Result<P>.cast(source: String?): Result<T> = (this as Result<T>).also { this.source = source }

inline fun <T, R> Result<T>.onFailure(block: (Result<T>) -> R) {
    if (this.isNotSuccessful) block(this)
}

inline fun <T, R> Result<T>.handle(block: (Result<T>) -> R): T {
    block(this)
    return this.value
}