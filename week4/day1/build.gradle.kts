plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    // Официальный MCP Kotlin SDK (Anthropic + JetBrains). Клиентская часть.
    implementation("io.modelcontextprotocol:kotlin-sdk:0.9.0")
    // Ktor HTTP-клиент (движок CIO) — нужен для remote-транспорта (Streamable HTTP/SSE).
    implementation("io.ktor:ktor-client-cio:3.2.3")
    // Тихий логгер, чтобы SDK/Ktor не сыпали WARN "no SLF4J provider".
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

tasks.named<JavaExec>("run") {
    // Подхватываем переменные из корневого .env (как в прошлых днях).
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.contains("=") && !it.startsWith("#") }
            .forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                environment(key.trim(), value.trim())
            }
    }
    standardInput = System.`in`
}
