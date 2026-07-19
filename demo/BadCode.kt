class UserService(val db: Database) {
    fun getUser(id: String): String {
        val user = db.query("SELECT * FROM users WHERE id = $id")
        return user.name.uppercase()
    }
}