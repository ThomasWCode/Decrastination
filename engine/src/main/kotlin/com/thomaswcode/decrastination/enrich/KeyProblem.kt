package com.thomaswcode.decrastination.enrich

import com.anthropic.errors.AnthropicServiceException
import kotlinx.serialization.Serializable

/**
 * Why Claude can't be used with the key it has, from a failed call: the key, or the account
 * behind it, rather than the network or the model. It wants putting right, so it's alerted.
 */
@Serializable
enum class KeyProblem(val says: String, val fix: String) {
    Rejected("Claude's API key was rejected: it's been revoked, deleted or has expired, or was mistyped", "Enter a new key in Setup"),
    NotAllowed("Claude's API key isn't allowed to use the model", "Check its workspace's permissions, or enter another key in Setup"),
    NoCredit("The Anthropic account's credit has run out", "Top it up at console.anthropic.com, or enter another key in Setup"),
    ;

    companion object {
        private val CREDIT = Regex("""credit|balance""", RegexOption.IGNORE_CASE)

        /** The key's or the account's problem [error] shows, or null for any other failure (no network, the model busy). */
        fun of(error: Throwable): KeyProblem? {
            val failed = generateSequence(error) { it.cause }.take(8).filterIsInstance<AnthropicServiceException>().firstOrNull() ?: return null
            return of(failed.statusCode(), failed.errorType().map { it.asString() }.orElse(null), failed.message)
        }

        /** By the HTTP status, the API's error type and its message: pure, for the tests. */
        fun of(status: Int, type: String?, message: String?): KeyProblem? = when {
            type == "billing_error" || status == 402 -> NoCredit
            status == 401 || type == "authentication_error" -> Rejected
            status == 403 || type == "permission_error" -> NotAllowed
            status == 400 && message != null && CREDIT.containsMatchIn(message) -> NoCredit
            else -> null
        }
    }
}
