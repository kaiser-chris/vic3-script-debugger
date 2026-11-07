package de.bahmut.vic3debugger.discovery

/**
 * Reads the script debugger port from a game's launch arguments, the same way the game does.
 *
 * The game (CGameApplication::InitInstance, see docs/SCRIPT_DEBUGGER.md §2) enables the debugger only
 * when the `script_debugger` option is present. It then defaults to 6111, and replaces that with the
 * option's argument when `atoi` of it lies in 1..65535. An out-of-range or non-numeric argument
 * silently falls back to 6111 rather than failing, so this does too.
 */
object LaunchOptions {
    const val DEFAULT_PORT = 6111

    private val SCRIPT_DEBUGGER = Regex("^--?script_debugger(?:=(.*))?$")
    private val LEADING_INTEGER = Regex("^\\s*([+-]?\\d+)")

    /** The port the game listens on, or null when it was not started with the script debugger. */
    fun scriptDebuggerPort(arguments: List<String>): Int? {
        // The first occurrence is used. Which one the game picks when the option repeats is unverified.
        val match = arguments.firstNotNullOfOrNull { SCRIPT_DEBUGGER.matchEntire(it) } ?: return null
        val value = match.groups[1]?.value ?: return DEFAULT_PORT
        val port = LEADING_INTEGER.find(value)?.groupValues?.get(1)?.toLongOrNull() ?: return DEFAULT_PORT
        return if (port in 1..65535) port.toInt() else DEFAULT_PORT
    }

    /**
     * Splits a Windows command line into arguments using the rules of CommandLineToArgvW, which is how
     * the C runtime gives the game its argv:
     * - whitespace outside quotes separates arguments;
     * - `"` toggles quoting, and `""` inside quotes is a literal quote;
     * - 2n backslashes before a quote become n backslashes and the quote toggles quoting;
     * - 2n+1 backslashes before a quote become n backslashes and a literal quote;
     * - backslashes not followed by a quote are literal.
     */
    fun splitWindowsCommandLine(commandLine: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var hasToken = false
        var i = 0
        while (i < commandLine.length) {
            val c = commandLine[i]
            when {
                c == '\\' -> {
                    var backslashes = 0
                    while (i < commandLine.length && commandLine[i] == '\\') { backslashes++; i++ }
                    if (i < commandLine.length && commandLine[i] == '"') {
                        repeat(backslashes / 2) { current.append('\\') }
                        if (backslashes % 2 == 1) { current.append('"'); i++ }
                    } else {
                        repeat(backslashes) { current.append('\\') }
                    }
                    hasToken = true
                    continue
                }
                c == '"' -> {
                    if (inQuotes && i + 1 < commandLine.length && commandLine[i + 1] == '"') {
                        current.append('"'); i++
                    } else {
                        inQuotes = !inQuotes
                    }
                    hasToken = true
                }
                !inQuotes && c.isWhitespace() -> {
                    if (hasToken) { result += current.toString(); current.clear(); hasToken = false }
                }
                else -> { current.append(c); hasToken = true }
            }
            i++
        }
        if (hasToken) result += current.toString()
        return result
    }
}
