rootProject.name = "ai-challenge"

// На ветке week6/day1 включаем только модули шестой недели —
// чтобы IDE Gradle Sync не пытался сконфигурировать week1/day1 (kotlin 2.1.0),
// который несовместим с root Kotlin 2.2.21.
include(":week6:day1")
