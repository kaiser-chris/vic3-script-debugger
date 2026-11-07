package de.bahmut.vic3debugger.dap

import de.bahmut.vic3debugger.discovery.GameProcess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.TimeUnit

/**
 * An attached game: a connection that has completed the handshake the game requires,
 * `initialize` → `launch` → `configurationDone` (docs/SCRIPT_DEBUGGER.md §3).
 *
 * Attaching never halts the game. Blocking: call off the EDT.
 */
class GameSession private constructor(
    val game: GameProcess,
    val port: Int,
    val connection: DapConnection,
    /** The `initialize` response body: the only reliable statement of what the server supports. */
    val capabilities: JsonObject,
) : AutoCloseable {

    @Volatile
    var isHalted: Boolean = false
        private set

    internal fun onEvent(event: String) {
        when (event) {
            "stopped" -> isHalted = true
            "continued" -> isHalted = false
        }
    }

    /**
     * Leaves the game running and the connection closed. A client that disconnects while the game is
     * halted leaves it frozen, so resume first (docs/SCRIPT_DEBUGGER.md §10). The harness sets no
     * breakpoints; once it does, they must be cleared here too, or they persist for the next client.
     */
    override fun close() {
        if (connection.isOpen) {
            runCatching {
                if (isHalted) connection.request("continue", threadArgs()).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                connection.request("disconnect").get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        connection.close()
    }

    companion object {
        private const val REQUEST_TIMEOUT_SECONDS = 5L

        // The game reports one thread, id 1. Stopped events may name other ids; requests with 1 work (§3).
        private fun threadArgs() = buildJsonObject { put("threadId", JsonPrimitive(1)) }

        fun attach(
            game: GameProcess,
            onEvent: (event: String, body: JsonObject?) -> Unit = { _, _ -> },
            onClosed: (cause: Throwable?) -> Unit = {},
        ): GameSession {
            val port = requireNotNull(game.debuggerPort) { "Game ${game.pid} was not started with -script_debugger" }
            var session: GameSession? = null
            val connection = DapConnection.open(
                port,
                onEvent = { event, body -> session?.onEvent(event); onEvent(event, body) },
                onClosed = onClosed,
            )
            try {
                val capabilities = connection.request("initialize", buildJsonObject {
                    put("clientID", JsonPrimitive("vic3-script-debugger"))
                    put("clientName", JsonPrimitive("Victoria 3 Script Debugger for IntelliJ"))
                    put("adapterID", JsonPrimitive("jomini"))
                    put("linesStartAt1", JsonPrimitive(true))
                    put("columnsStartAt1", JsonPrimitive(true))
                    put("pathFormat", JsonPrimitive("path"))
                }).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                connection.request("launch").get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                connection.request("configurationDone").get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                return GameSession(game, port, connection, capabilities).also { session = it }
            } catch (e: Throwable) {
                connection.close()
                throw e
            }
        }
    }
}
