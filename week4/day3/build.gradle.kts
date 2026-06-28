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
    // MCP Kotlin SDK — сервер и клиент.
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.13.0")
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.13.0")
    // Ktor: embedded server + client (агент и запрос к wttr.in).
    implementation("io.ktor:ktor-server-cio:3.2.3")
    implementation("io.ktor:ktor-client-cio:3.2.3")
    // Корутины — для фонового планировщика (CoroutineScope/launch/delay).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Тихий логгер (уровень warn в resources/simplelogger.properties — чистая консоль для видео).
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    // UTF-8 для вывода — иначе на Windows кириллица = кракозябры (в паре с chcp 65001 / UTF-8 в IDE-консоли).
    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
    )
}
