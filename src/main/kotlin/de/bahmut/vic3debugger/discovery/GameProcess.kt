package de.bahmut.vic3debugger.discovery

/**
 * A running copy of the game.
 *
 * @property arguments the launch arguments without the executable, or null when the operating system
 *   would not reveal them (for example an elevated game seen from a non-elevated IDE).
 * @property debuggerPort the script debugger port from those arguments, or null when the game was not
 *   started with `-script_debugger` or its arguments are unknown.
 */
data class GameProcess(
    val pid: Long,
    val executable: String?,
    val arguments: List<String>?,
    val debuggerPort: Int?,
) {
    val isDebuggable: Boolean get() = debuggerPort != null
}
