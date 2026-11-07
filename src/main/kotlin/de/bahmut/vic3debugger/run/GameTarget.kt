package de.bahmut.vic3debugger.run

import com.intellij.execution.ExecutionException
import de.bahmut.vic3debugger.Vic3Bundle
import de.bahmut.vic3debugger.discovery.GameProcess
import de.bahmut.vic3debugger.discovery.GameProcessFinder
import de.bahmut.vic3debugger.discovery.LaunchOptions
import de.bahmut.vic3debugger.mapping.LoadedContent
import de.bahmut.vic3debugger.mapping.UserDataFolder
import java.nio.file.Paths

/** The game a run configuration resolved to: where to connect, and what the game loaded. */
data class GameTarget(val process: GameProcess?, val port: Int, val content: LoadedContent) {

    companion object {
        /**
         * Resolves the configuration to a game, or fails with a message for the user. Failures are run errors,
         * which is how the IDE reports a configuration that cannot start. Blocking: call off the EDT.
         */
        fun resolve(options: Vic3RunConfigurationOptions): GameTarget {
            val games = try {
                GameProcessFinder().find()
            } catch (e: Exception) {
                if (options.autoDetect) throw ExecutionException(Vic3Bundle.message("run.error.discovery", e.message ?: e.javaClass.simpleName), e)
                emptyList()
            }
            val process: GameProcess?
            val port: Int
            if (options.autoDetect) {
                val debuggable = games.filter { it.isDebuggable }
                process = when {
                    games.isEmpty() -> throw ExecutionException(Vic3Bundle.message("run.error.noGame"))
                    debuggable.isEmpty() -> throw ExecutionException(
                        Vic3Bundle.message("run.error.noFlag", games.joinToString { it.pid.toString() }, LaunchOptions.DEFAULT_PORT),
                    )
                    debuggable.size > 1 -> throw ExecutionException(
                        Vic3Bundle.message("run.error.several", debuggable.joinToString { "${it.pid} (${it.debuggerPort})" }),
                    )
                    else -> debuggable.single()
                }
                port = process.debuggerPort!!
            } else {
                port = options.port
                process = games.firstOrNull { it.debuggerPort == port }
            }
            val gameDir = process?.executable?.let { LoadedContent.gameDirForExecutable(Paths.get(it)) }
            val userData = UserDataFolder.resolve(options.userDataFolder)
            val content = userData?.let { LoadedContent.read(it, gameDir) } ?: LoadedContent(gameDir, emptyList())
            return GameTarget(process, port, content)
        }
    }
}
