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
    // MCP Kotlin SDK — серверная и клиентская части (для нашего сервера и для агента).
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.13.0")
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.13.0")
    // Ktor: серверный движок (embedded server) + клиентский (агент и запрос к Open-Meteo).
    implementation("io.ktor:ktor-server-cio:3.2.3")
    implementation("io.ktor:ktor-client-cio:3.2.3")
    // Тихий логгер (уровень warn задан в resources/simplelogger.properties — чтобы консоль в видео была чистой).
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
