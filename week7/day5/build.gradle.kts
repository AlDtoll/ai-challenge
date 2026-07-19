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
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.13.0")
    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.13.0")
    implementation("io.ktor:ktor-server-cio:3.2.3")
    implementation("io.ktor:ktor-client-cio:3.2.3")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.slf4j:slf4j-simple:2.0.13")
    // SQLite для quickai.db (счётчик потраченных токенов)
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    standardInput = System.`in`
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}
