package com.thomaswcode.decrastination.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP POST or GET. An interface so the API clients are tested against canned responses. */
fun interface Http {
    /** Blocking; call from an IO dispatcher. Throws [IOException] when there's no answer at all. */
    fun post(url: String, headers: Map<String, String>, body: String): HttpResponse

    /** As [post], with nothing sent. */
    fun get(url: String, headers: Map<String, String>): HttpResponse = post(url, headers, "")
}

data class HttpResponse(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}

/** [Http] over the platform's HttpURLConnection: no dependency for two JSON APIs. */
class UrlConnectionHttp(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 60_000,
) : Http {
    override fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.outputStream.use { it.write(body.encodeToByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().decodeToString() }.orEmpty()
            return HttpResponse(code, text)
        } finally {
            connection.disconnect()
        }
    }

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return HttpResponse(code, stream?.use { it.readBytes().decodeToString() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}
