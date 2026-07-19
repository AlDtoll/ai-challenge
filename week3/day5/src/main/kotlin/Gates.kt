// Гейты — отдельные boolean-флаги, описывающие «что готово»,
// в отличие от стейджа («где мы»). Идея заимствована у swanden (Go).
//
// Это позволяет:
//   • не лезть в EXECUTION пока не одобрен план
//   • не лезть в DONE пока не пройдена валидация
//   • аудитор/мастер может явно «защёлкнуть» гейт командой

data class Gates(
    val planApproved: Boolean = false,        // план PLANNING подтверждён
    val executionComplete: Boolean = false,   // EXECUTION дошёл до конца плана
    val validationPassed: Boolean = false     // VALIDATION прошла успешно
) {
    fun describe(): String = buildString {
        append(if (planApproved) "✓" else "·"); append(" planApproved   ")
        append(if (executionComplete) "✓" else "·"); append(" executionComplete   ")
        append(if (validationPassed) "✓" else "·"); append(" validationPassed")
    }
}
