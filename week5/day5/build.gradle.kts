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
    implementation("com.google.code.gson:gson:2.11.0")
}

tasks.named<JavaExec>("run") {
    // Рабочий каталог = каталог модуля (иначе не найдутся src/main/resources при обращении через File("src/main/resources/...")).
    workingDir = projectDir
    standardInput = System.`in`
    // UTF-8 вывод — кириллица без кракозябр (в паре с chcp 65001 на Windows / UTF-8 в IDE-консоли).
    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
    )
}
