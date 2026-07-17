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
    // MCP Kotlin SDK — свой embedded git-сервер + агент как MCP-клиент (по образцу week4/day2).
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.13.0")
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.13.0")
    // Ktor: серверный движок для MCP + клиентский для DeepSeek/Ollama.
    implementation("io.ktor:ktor-server-cio:3.2.3")
    implementation("io.ktor:ktor-client-cio:3.2.3")
    // JSON: MCP-SDK — kotlinx; наш RAG-индекс + DeepSeek/Ollama JSON — Gson (унифицируем со стеком челленджа).
    implementation("com.google.code.gson:gson:2.11.0")
    // Тихий логгер (уровень warn задан в resources/simplelogger.properties — чтобы консоль в видео была чистой).
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

tasks.named<JavaExec>("run") {
    workingDir = projectDir
    standardInput = System.`in`
    // Для чистого вывода в консоль на Windows: forced UTF-8 (иначе кракозябры кириллицы).
    // В паре с `chcp 65001; [Console]::OutputEncoding = [System.Text.Encoding]::UTF8` в PowerShell.
    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
    )
}
