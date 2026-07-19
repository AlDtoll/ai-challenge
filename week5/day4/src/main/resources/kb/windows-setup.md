# Windows-setup: как запускать модули

## Только `gradlew.bat`, не `gradlew`

Данил собирает челлендж на Windows (PowerShell, проект в `C:\D\Develop\ai-challenge`). Все команды запуска — через `.\gradlew.bat`, а НЕ `./gradlew`. Второй вариант на Windows пытается «открыть файл приложением по умолчанию» и не выполняет ничего полезного.

Пример правильного запуска модуля дня:

```
.\gradlew.bat :week4:day2:run --console=plain -q
```

## `gradlew.bat` в каждой ветке

Изначально в git был только Unix `gradlew` — `gradlew.bat` отсутствовал. Пришлось создать стандартный `gradlew.bat` от Gradle 8.x. Так как ветки НЕ мёржатся друг в друга, при создании новой ветки дня `gradlew.bat` нужно копировать явно (или брать через `git checkout <ветка_дня> -- gradlew.bat`).

## Кириллица в консоли — UTF-8 связка

Чтобы русский текст в консольном выводе не превращался в кракозябры, нужно две настройки:

1. **В `build.gradle.kts` модуля** — прописать `jvmArgs` для JavaExec:

```
tasks.named<JavaExec>("run") {
    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "-Dstdout.encoding=UTF-8",
        "-Dstderr.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
    )
}
```

2. **В PowerShell перед запуском** — установить кодировку вывода:

```
chcp 65001
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
```

Одного только `chcp 65001` недостаточно: PowerShell декодирует вывод дочернего процесса по `[Console]::OutputEncoding`, и без второй строки Вы получите ??? вместо кириллицы. Альтернатива — запуск из Run-консоли IDE (IntelliJ IDEA, Cursor), там UTF-8 по умолчанию.
