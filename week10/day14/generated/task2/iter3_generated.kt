package com.example.interceptor

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.GzipSource
import okio.buffer

class RequestLoggingInterceptor(
    private val tag: String = "OkHttp",
    private val logBody: Boolean = false
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestBody = request.body

        val sb = StringBuilder()
        sb.append("--> ${request.method} ${request.url}\n")
        request.headers.forEach { (name, value) ->
            sb.append("$name: ${redactHeader(name, value)}\n")
        }
        if (logBody && requestBody != null) {
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            sb.append("Body: ${buffer.readUtf8()}\n")
        }
        sb.append("--> END ${request.method}")
        Log.d(tag, sb.toString())

        val startTime = System.nanoTime()
        val response = chain.proceed(request)
        val durationMs = (System.nanoTime() - startTime) / 1_000_000

        val responseBody = response.body
        val responseBodyString = if (logBody && responseBody != null) {
            responseBody.string()
        } else null

        val responseSb = StringBuilder()
        responseSb.append("<-- ${response.code} ${response.message} ${response.request.url} (${durationMs}ms)\n")
        response.headers.forEach { (name, value) ->
            responseSb.append("$name: ${redactHeader(name, value)}\n")
        }
        if (responseBodyString != null) {
            responseSb.append("Body: $responseBodyString\n")
        }
        responseSb.append("<-- END HTTP")
        Log.d(tag, responseSb.toString())

        if (responseBodyString != null) {
            val newBody = ResponseBody.create(
                responseBody?.contentType(),
                responseBodyString
            )
            return response.newBuilder().body(newBody).build()
        }
        return response
    }

    private fun redactHeader(name: String, value: String): String {
        return when (name.lowercase()) {
            "authorization", "cookie", "set-cookie", "x-api-key", "proxy-authorization" -> "[REDACTED]"
            else -> value
        }
    }
}