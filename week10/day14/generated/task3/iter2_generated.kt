import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class Profile(
    val id: String,
    val name: String,
    val email: String
)

fun fetchUserProfile(userId: String): Profile? {
    val client = OkHttpClient()
    val url = "https://api.example.com/users/$userId"
    val request = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer ${BuildConfig.API_KEY}")
        .build()

    return try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = response.body?.string() ?: return null
            Gson().fromJson(json, Profile::class.java)
        }
    } catch (e: IOException) {
        null
    }
}