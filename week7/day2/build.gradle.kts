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
    // JSON — Gson (тот же стек что day31, чтобы не тянуть kotlinx.serialization).
    implementation("com.google.code.gson:gson:2.11.0")
    // Тихий slf4j — чтобы CI-логи не засирались INFO-строками.
    implementation("org.slf4j:slf4j-simple:2.0.13")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir  // корень репозитория — чтобы RAG видел README + docs/**
    standardInput = System.`in`
    jvmArgs("-Dfile.encoding=UTF-8")
}
