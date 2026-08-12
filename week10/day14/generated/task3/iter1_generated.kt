import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request

data class Profile(
    val id: String,
    val name: String,
    val email: String
)

private const val API_KEY = "YOUR_API_KEY_HERE"
private const val BASE_URL = "http://api.example.com/users/"

private val client = OkHttpClient()
private val gson = Gson()

fun fetchUserProfile(userId: String): Profile? {
    return try {
        val request = Request.Builder()
            .url(BASE_URL + userId)
            .addHeader("Authorization", "Bearer $API_KEY")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = response.body?.string() ?: return null
            gson.fromJson(json, Profile::class.java)
        }
    } catch (e: Exception) {
        null
    }
}