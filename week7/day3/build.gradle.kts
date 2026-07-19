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
    // MCP Kotlin SDK — свой embedded support-сервер + агент как MCP-клиент (та же схема что day31).
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.13.0")
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.13.0")
    // Ktor server для MCP + client для DeepSeek/Ollama.
    implementation("io.ktor:ktor-server-cio:3.2.3")
    implementation("io.ktor:ktor-client-cio:3.2.3")
    // JSON — Gson (стек челленджа).
    implementation("com.google.code.gson:gson:2.11.0")
    // Тихий slf4j — чтобы консоль в видео была чистой.
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

tasks.named<JavaExec>("run") {
    workingDir = projectDir
    standardInput = System.`in`
    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
    )
}
