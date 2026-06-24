package com.krypt.app.common

/**
 * Lightweight Result type used by deep-link parsers / consumers to return a
 * success value or a typed error value (distinct from exception throwing).
 *
 * Intentionally not using `kotlin.Result`, which only wraps `Throwable` — our
 * errors are sealed interfaces (e.g. `RequestParseError`, `ApprovalError`).
 */
sealed class Outcome<out T, out E> {

    data class Ok<out T>(val value: T) : Outcome<T, Nothing>()
    data class Err<out E>(val error: E) : Outcome<Nothing, E>()

    inline fun <R> fold(ok: (T) -> R, err: (E) -> R): R = when (this) {
        is Ok  -> ok(value)
        is Err -> err(error)
    }

    inline fun <U> map(transform: (T) -> U): Outcome<U, E> = when (this) {
        is Ok  -> Ok(transform(value))
        is Err -> this
    }

    inline fun <U> flatMap(transform: (T) -> Outcome<U, @UnsafeVariance E>): Outcome<U, E> = when (this) {
        is Ok  -> transform(value)
        is Err -> this
    }

    fun getOrNull(): T? = (this as? Ok<T>)?.value
    fun errorOrNull(): E? = (this as? Err<E>)?.error

    companion object {
        fun <T> ok(value: T): Outcome<T, Nothing> = Ok(value)
        fun <E> err(error: E): Outcome<Nothing, E> = Err(error)
    }
}
