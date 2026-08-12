data class Profile(
    val id: String,
    val name: String,
    val email: String
)

private val client = OkHttpClient()
private const val API_KEY = "YOUR_API_KEY_HERE"

fun fetchUserProfile(userId: String): Profile? {
    val url = "http://api.example.com/users/$userId"
    val request = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer $API_KEY")
        .build()

    return try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = response.body?.string() ?: return null
            Gson().fromJson(json, Profile::class.java)
        }
    } catch (e: Exception) {
        null
    }
}