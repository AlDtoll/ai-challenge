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
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            buffer.readUtf8()
        } else {
            "null"
        }

        Log.d(TAG, "REQUEST: ${request.method} ${request.url}")
        request.headers.forEach { header ->
            Log.d(TAG, "REQUEST HEADER: ${header.first}: ${header.second}")
        }
        Log.d(TAG, "REQUEST BODY: $requestBodyString")

        val response = chain.proceed(request)
        val responseBody = response.body
        val responseBodyString = if (responseBody != null) {
            val source = responseBody.source()
            source.request(Long.MAX_VALUE)
            source.buffer.clone().readUtf8()
        } else {
            "null"
        }

        Log.d(TAG, "RESPONSE: ${response.code} ${response.message}")
        response.headers.forEach { header ->
            Log.d(TAG, "RESPONSE HEADER: ${header.first}: ${header.second}")
        }
        Log.d(TAG, "RESPONSE BODY: $responseBodyString")

        return response.newBuilder()
            .body(ResponseBody.create(responseBody?.contentType(), responseBodyString))
            .build()
    }

    companion object {
        private const val TAG = "HTTP"
    }
}