package org.filemat.server.common.model


data class Result<T>(
    private val inputValue: T? = null,
    private val inputError: String? = null,
    private val inputNotFound: Boolean = false,
    private val rejectInput: String? = null
) {

    companion object {
        fun <T> ok(value: T): Result<T> = Result(inputValue = value)
        fun ok(): Result<Unit> = Result()
        fun <T> error(message: String): Result<T> = Result(inputError = message)
        fun <T> notFound(): Result<T> = Result(inputNotFound = true)
        fun <T> reject(message: String): Result<T> = Result(rejectInput = message)
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
            if (inputValue == null) throw IllegalStateException("Tried to access empty value in result")
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

@Suppress("UNCHECKED_CAST")
fun <T, P> Result<P>.cast(): Result<T> = if (this.isNotSuccessful) this as Result<T> else throw IllegalStateException("Cannot cast type on a successful result.")