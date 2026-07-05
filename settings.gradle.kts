rootProject.name = "ai-challenge"

// На ветке week5/day2 включаем только модули пятой недели —
// чтобы IDE Gradle Sync не пытался сконфигурировать week1/day1 (kotlin 2.1.0),
// который несовместим с root Kotlin 2.2.21 (нужен для MCP-недели и RAG).
include(":week5:day1")
include(":week5:day2")
