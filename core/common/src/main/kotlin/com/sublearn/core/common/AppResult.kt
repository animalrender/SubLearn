package com.sublearn.core.common

/**
 * Small functional result type used across module boundaries instead of throwing.
 *
 * Rationale: a media player touches IO, network and native decoders; every public API in SubLearn
 * returns [AppResult] for fallible operations so UI can show a reason instead of crashing.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>

    data class Failure(val error: SubLearnError) : AppResult<Nothing>

    val isSuccess: Boolean get() = this is Success

    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = (this as? Success)?.value

    fun errorOrNull(): SubLearnError? = (this as? Failure)?.error

    /** Throws the original cause when there is one, so callers can use Kotlin's own error flow. */
    fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw error.cause ?: IllegalStateException(error.message)
    }

    // The three combinators below take T in an argument position, which the declared-site `out`
    // variance forbids. `@UnsafeVariance` is the standard exemption and is safe here: nothing that
    // accepts one of these lambdas can put a value back into the result.

    fun <R> map(transform: @UnsafeVariance (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    fun onSuccess(action: @UnsafeVariance (T) -> Unit): AppResult<T> {
        if (this is Success) action(value)
        return this
    }

    fun onFailure(action: (SubLearnError) -> Unit): AppResult<T> {
        if (this is Failure) action(error)
        return this
    }

    fun <R> fold(onSuccess: @UnsafeVariance (T) -> R, onFailure: (SubLearnError) -> R): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(error)
    }

    fun getOrElse(fallback: @UnsafeVariance (SubLearnError) -> T): T = when (this) {
        is Success -> value
        is Failure -> fallback(error)
    }

    companion object {
        fun <T> success(value: T): AppResult<T> = Success(value)
        fun failure(error: SubLearnError): AppResult<Nothing> = Failure(error)

        fun <T> of(block: () -> T): AppResult<T> =
            try {
                Success(block())
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Failure(SubLearnError.from(t))
            }
    }
}

/** Categorized failures; the message is user presentable, [cause] is for logs (never secrets). */
data class SubLearnError(
    val kind: Kind,
    val message: String,
    val cause: Throwable? = null,
) {
    enum class Kind {
        NotFound,
        PermissionDenied,
        UnsupportedFormat,
        MalformedInput,
        NetworkUnavailable,
        RateLimited,
        Unauthorized,
        ModelMissing,
        NotImplemented,
        Unknown,
    }

    companion object {
        fun from(t: Throwable): SubLearnError = when (t) {
            is java.net.UnknownHostException, is java.io.IOException -> SubLearnError(
                Kind.NetworkUnavailable,
                t.message ?: "Network request failed",
                t,
            )
            is SecurityException -> SubLearnError(Kind.PermissionDenied, t.message ?: "Permission denied", t)
            is NotImplementedError -> SubLearnError(Kind.NotImplemented, t.message ?: "Not implemented", t)
            else -> SubLearnError(Kind.Unknown, t.javaClass.simpleName + ": " + (t.message ?: ""), t)
        }
    }
}
