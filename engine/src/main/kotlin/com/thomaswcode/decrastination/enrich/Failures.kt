package com.thomaswcode.decrastination.enrich

import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/** What a failed model call met (AC-04): how long the model rests after it goes by this. */
enum class Failure { RateLimit, Overloaded, Server, Timeout, Network, BadRequest, Key, Unknown }

/**
 * A failed call's kind, and the rest it earns (AC-04): busy (rate limit, overloaded) rests 5, 15
 * and then 60 minutes as they come in a row; the server or a timeout 15 then 60; no network none,
 * as the next try waits for one; a request the API won't take none, as asking again won't help
 * (that task isn't sent again till it changes); the key an hour, as before, till it's put right.
 * A `Retry-After` the API gives is kept to where it's longer. Pure but for the SDK's classes.
 */
object Failures {
    private const val MINUTE = 60_000L

    /** At most this many failures an hour: past it, an hour's rest whatever they were. */
    const val MAX_AN_HOUR = 3
    const val HOUR_MS = 60 * MINUTE

    fun classify(error: Throwable): Failure {
        val chain = generateSequence(error) { it.cause }.take(8).toList()
        chain.filterIsInstance<AnthropicServiceException>().firstOrNull()?.let { return classify(it.statusCode(), KeyProblem.of(error) != null) }
        return when {
            chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } -> Failure.Network
            chain.any { it is InterruptedIOException || it is AnthropicIoException } -> Failure.Timeout
            else -> Failure.Unknown
        }
    }

    /** By the HTTP status, and whether it showed a problem with the key or the account: pure, for the tests. */
    fun classify(status: Int, key: Boolean): Failure = when {
        key || status == 401 || status == 403 -> Failure.Key
        status == 429 -> Failure.RateLimit
        status == 529 -> Failure.Overloaded
        status in 500..599 -> Failure.Server
        status == 408 -> Failure.Timeout
        status in 400..499 -> Failure.BadRequest
        else -> Failure.Unknown
    }

    /** The rest after the [inRow]th failure in a row, this one of [failure]'s kind, at least [retryAfterMs]. */
    fun restMs(failure: Failure, inRow: Int, retryAfterMs: Long? = null): Long {
        val minutes = when (failure) {
            Failure.RateLimit, Failure.Overloaded -> when { inRow <= 1 -> 5; inRow == 2 -> 15; else -> 60 }
            Failure.Server, Failure.Timeout -> if (inRow <= 1) 15 else 60
            Failure.Key -> 60
            Failure.Network, Failure.BadRequest -> 0
            Failure.Unknown -> 15
        }
        return maxOf(minutes * MINUTE, retryAfterMs ?: 0L)
    }

    /** The `Retry-After` the API's answer carried, in seconds as it gives it, as milliseconds. */
    fun retryAfterMs(error: Throwable): Long? = generateSequence(error) { it.cause }.take(8).filterIsInstance<AnthropicServiceException>().firstOrNull()
        ?.let { runCatching { it.headers().values("retry-after") }.getOrNull() }?.firstOrNull()?.trim()?.toLongOrNull()?.takeIf { it in 1..86_400 }?.times(1_000L)
}
