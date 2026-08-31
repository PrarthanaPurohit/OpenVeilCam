package com.openveil.domain.model

/**
 * Typed result used at repository and client boundaries.
 *
 * Technical failures (HTTP status codes, native C2PA errors, socket timeouts) are
 * translated into [PublishError] here rather than being allowed to propagate as
 * exceptions into use cases and UI.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>

    data class Failure(
        val error: PublishError,
        /** Diagnostic detail for logs. Never rendered to the user verbatim. */
        val detail: String? = null,
        val cause: Throwable? = null,
    ) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.value
