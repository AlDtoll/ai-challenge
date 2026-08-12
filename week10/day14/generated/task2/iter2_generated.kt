import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import okio.Buffer
import android.util.Log

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestBody = request.body
        val requestBodyString = if (requestBody != null) {
            try {
                val buffer = Buffer()
                requestBody.writeTo(buffer)
                buffer.readUtf8()
            } catch (e: Exception) {
                "[body read error: ${e.message}]"
            }
        } else {
            "null"
        }

        Log.d(TAG, "--> ${request.method} ${request.url}")
        request.headers.forEach { (name, value) ->
            Log.d(TAG, "$name: ${redactHeader(name, value)}")
        }
        Log.d(TAG, "Request Body: $requestBodyString")
        Log.d(TAG, "--> END ${request.method}")

        val startTime = System.nanoTime()
        val response = chain.proceed(request)
        val elapsedTime = (System.nanoTime() - startTime) / 1_000_000

        val responseBody = response.body
        val responseBodyString = if (responseBody != null) {
            try {
                val source = responseBody.source()
                source.request(Long.MAX_VALUE)
                val buffer = source.buffer.clone()
                buffer.readUtf8()
            } catch (e: Exception) {
                "[body read error: ${e.message}]"
            }
        } else {
            "null"
        }

        Log.d(TAG, "<-- ${response.code} ${response.message} ${response.request.url} (${elapsedTime}ms)")
        response.headers.forEach { (name, value) ->
            Log.d(TAG, "$name: ${redactHeader(name, value)}")
        }
        Log.d(TAG, "Response Body: $responseBodyString")
        Log.d(TAG, "<-- END HTTP")

        return response.newBuilder()
            .body(ResponseBody.create(responseBody?.contentType(), responseBodyString))
            .build()
    }

    private fun redactHeader(name: String, value: String): String {
        return if (name.equals("Authorization", ignoreCase = true) ||
            name.equals("Cookie", ignoreCase = true) ||
            name.equals("Set-Cookie", ignoreCase = true)
        ) {
            "[REDACTED]"
        } else {
            value
        }
    }

    companion object {
        private const val TAG = "HTTP"
    }
}