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
    /** The process, when it could be identified; null when attaching to a port with no matching process. */
    val game: GameProcess?,
    val port: Int,
    val connection: DapConnection,
    /** The `initialize` response body: the only reliable statement of what the server supports. */
    val capabilities: JsonObject,
) : AutoCloseable {

    @Volatile
    var isHalted: Boolean = false
        private set

    /** Receives events once someone owns the session. Events that arrive before then are dropped. */
    @Volatile
    var eventListener: (event: String, body: JsonObject?) -> Unit = { _, _ -> }

    /** Called once when the connection ends, with the cause when it was not closed on purpose. */
    @Volatile
    var closeListener: (cause: Throwable?) -> Unit = {}

    internal fun onEvent(event: String, body: JsonObject?) {
        when (event) {
            "stopped" -> isHalted = true
            "continued" -> isHalted = false
        }
        eventListener(event, body)
    }

    /** Marks the game as running again after a request that resumes it (continue, next, stepIn, stepOut). */
    fun markResumed() {
        isHalted = false
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

        fun attach(game: GameProcess): GameSession = attach(game, requireNotNull(game.debuggerPort) {
            "Game ${game.pid} was not started with -script_debugger"
        })

        fun attach(game: GameProcess?, port: Int): GameSession {
            var session: GameSession? = null
            val connection = DapConnection.open(
                port,
                onEvent = { event, body -> session?.onEvent(event, body) },
                onClosed = { cause -> session?.closeListener?.invoke(cause) },
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
