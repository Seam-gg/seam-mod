package gg.seam.mod.api

/**
 * Outcome of a Seam API call. Deliberately total — every caller has to decide what happens when the
 * server says no ([Error]) and when we never got an answer at all ([Failure]), because both are
 * routine for a mod running on someone's laptop mid-game.
 */
sealed interface ApiResult<out T> {

    /** 2xx with a parseable body. */
    data class Ok<out T>(val value: T) : ApiResult<T>

    /**
     * The server answered with a non-2xx status. [code] is the machine-readable `error` field
     * (`invalid_token`, `forbidden`, `authorization_pending`, …), falling back to `http_<status>`
     * when the body isn't the expected JSON shape.
     */
    data class Error(val status: Int, val code: String, val message: String? = null) : ApiResult<Nothing>

    /** Never reached the server, or the response body could not be parsed. */
    data class Failure(val cause: Throwable) : ApiResult<Nothing>

    companion object {
        /** Not-linked is modelled as a local 401 so callers handle "no token" exactly like a rejected one. */
        val NOT_LINKED: Error = Error(401, "not_linked", "No Seam account is linked")
    }
}

/** The value on success, or null on either failure mode. */
fun <T> ApiResult<T>.valueOrNull(): T? = (this as? ApiResult.Ok)?.value

/** Short, player-facing description of a non-success result — for chat lines and log messages. */
fun ApiResult<*>.describe(): String = when (this) {
    is ApiResult.Ok -> "ok"
    is ApiResult.Error -> message ?: "$code (HTTP $status)"
    is ApiResult.Failure -> cause.message ?: cause::class.simpleName ?: "network error"
}
