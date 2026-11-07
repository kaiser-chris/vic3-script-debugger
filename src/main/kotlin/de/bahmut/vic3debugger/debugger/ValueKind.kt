package de.bahmut.vic3debugger.debugger

/**
 * What a variable holds. DAP gives no types, so this is read from how the game renders values:
 * objects as `Country Great Britain (3)` or `State Yorkshire (xB5BF4E)`, booleans as `yes`/`no`,
 * numbers as `0.00`, variable lists as `list:<name> = N entries` (docs/SCRIPT_DEBUGGER.md §8).
 */
enum class ValueKind {
    SCOPE, FLAG, BOOLEAN, NUMBER, LIST, NONE, TEXT;

    companion object {
        private val NUMBER_PATTERN = Regex("^[+-]?\\d+(\\.\\d+)?$")
        // A label followed by an id: `(3)`, `(x51A0A0)`, or a bare handle such as `TreatyArticle(4294967295)`.
        private val OBJECT_PATTERN = Regex("^\\S.*\\((x?[0-9A-Fa-f]+|\\s*\\.\\.\\.\\s*)\\)$")
        private val VERDICT_PATTERN = Regex("^(yes|no) — ")

        fun of(name: String, value: String, expandable: Boolean): ValueKind {
            val first = value.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return when {
                name.startsWith("list:") -> LIST
                first == "(none)" -> NONE
                first == "yes" || first == "no" || VERDICT_PATTERN.containsMatchIn(first) -> BOOLEAN
                NUMBER_PATTERN.matches(first) -> NUMBER
                first.startsWith("Flag ") -> FLAG
                // `Root: Country …` is a description that mentions an object, not an object.
                first.substringBefore(' ').endsWith(':') -> if (expandable) SCOPE else TEXT
                expandable || name.startsWith("scope:") || OBJECT_PATTERN.matches(first) -> SCOPE
                else -> TEXT
            }
        }
    }
}
