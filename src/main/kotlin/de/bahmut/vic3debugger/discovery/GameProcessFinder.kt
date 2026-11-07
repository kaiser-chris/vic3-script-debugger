package de.bahmut.vic3debugger.discovery

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Finds running copies of the game and the script debugger port each was started with.
 *
 * Kept free of IntelliJ APIs so it can be tested, and checked against a live game, outside the IDE.
 * Blocking: call it off the EDT.
 */
class GameProcessFinder(private val timeoutSeconds: Long = 15) {

    fun find(): List<GameProcess> =
        if (isWindows()) findOnWindows() else findWithProcessHandles()

    /**
     * Java cannot read another process's arguments on Windows (`ProcessHandle.Info.arguments()` is empty
     * there), and `wmic` is gone from current Windows 11. Win32_Process through CIM has the full command
     * line, so ask PowerShell, which ships with every supported Windows version.
     */
    private fun findOnWindows(): List<GameProcess> {
        val script = $$"""
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            Get-CimInstance -ClassName Win32_Process -Filter "Name = '$$WINDOWS_EXECUTABLE'" |
                ForEach-Object { "$($_.ProcessId)`t$($_.ExecutablePath)`t$($_.CommandLine)" }
        """.trimIndent()
        // -EncodedCommand avoids every layer of quoting between here and PowerShell.
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("Listing processes timed out after $timeoutSeconds seconds")
        }
        if (process.exitValue() != 0) throw IOException("Listing processes failed with exit code ${process.exitValue()}")
        return parseWindowsListing(output)
    }

    private fun findWithProcessHandles(): List<GameProcess> =
        ProcessHandle.allProcesses()
            .filter { handle -> handle.info().command().map { executableName(it) in UNIX_EXECUTABLES }.orElse(false) }
            .map { handle ->
                val arguments = handle.info().arguments().map { it.toList() }.orElse(null)
                GameProcess(
                    pid = handle.pid(),
                    executable = handle.info().command().orElse(null),
                    arguments = arguments,
                    debuggerPort = arguments?.let(LaunchOptions::scriptDebuggerPort),
                )
            }
            .toList()

    companion object {
        const val WINDOWS_EXECUTABLE = "victoria3.exe"
        private val UNIX_EXECUTABLES = setOf("victoria3", WINDOWS_EXECUTABLE)

        /** Parses `pid<TAB>executable path<TAB>command line` lines. Empty fields mean access was denied. */
        internal fun parseWindowsListing(output: String): List<GameProcess> =
            output.lineSequence()
                .map { it.trimEnd('\r') }
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val fields = line.split('\t', limit = 3)
                    val pid = fields[0].trim().toLongOrNull() ?: return@mapNotNull null
                    val executable = fields.getOrNull(1)?.takeIf { it.isNotBlank() }
                    val commandLine = fields.getOrNull(2)?.takeIf { it.isNotBlank() }
                    val arguments = commandLine?.let { LaunchOptions.splitWindowsCommandLine(it).drop(1) }
                    GameProcess(pid, executable, arguments, arguments?.let(LaunchOptions::scriptDebuggerPort))
                }
                .toList()

        private fun executableName(path: String) = path.substringAfterLast('/').substringAfterLast('\\').lowercase()

        private fun isWindows() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }
}
