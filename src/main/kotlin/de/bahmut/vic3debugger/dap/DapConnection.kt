package de.bahmut.vic3debugger.dap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A failed DAP response: `success: false`, with the server's `message`. */
class DapException(val command: String, message: String) : IOException("$command failed: $message")

/**
 * One connection to the game's script debugger.
 *
 * Responses complete the futures returned by [request]; events go to [onEvent] on the reader thread.
 * The game answers unknown commands with an empty success, so a successful response proves nothing
 * about support: only the `initialize` capabilities do (docs/SCRIPT_DEBUGGER.md §3).
 */
class DapConnection private constructor(
    private val socket: Socket,
    private val onEvent: (event: String, body: JsonObject?) -> Unit,
    private val onClosed: (cause: Throwable?) -> Unit,
) : AutoCloseable {

    private val output = BufferedOutputStream(socket.getOutputStream())
    private val input = BufferedInputStream(socket.getInputStream())
    private val nextSeq = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, PendingRequest>()
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)

    private class PendingRequest(val command: String, val future: CompletableFuture<JsonObject>)

    val isOpen: Boolean get() = !closed.get()

    private val reader = Thread(::readLoop, "Victoria 3 script debugger reader").apply {
        isDaemon = true
    }

    /** Sends a request. The future completes with the response `body` (empty when absent) or fails with [DapException]. */
    fun request(command: String, arguments: JsonObject = EMPTY): CompletableFuture<JsonObject> {
        val future = CompletableFuture<JsonObject>()
        if (closed.get()) return future.apply { completeExceptionally(IOException("Connection is closed")) }
        val seq = nextSeq.getAndIncrement()
        pending[seq] = PendingRequest(command, future)
        val message = buildJsonObject {
            put("seq", JsonPrimitive(seq))
            put("type", JsonPrimitive("request"))
            put("command", JsonPrimitive(command))
            put("arguments", arguments)
        }
        try {
            synchronized(writeLock) { DapFraming.write(output, message.toString()) }
        } catch (e: IOException) {
            pending.remove(seq)
            future.completeExceptionally(e)
            shutdown(e)
        }
        return future
    }

    override fun close() = shutdown(null)

    private fun readLoop() {
        var cause: Throwable? = null
        try {
            while (!closed.get()) {
                val body = DapFraming.read(input) ?: break
                dispatch(Json.parseToJsonElement(body).jsonObject)
            }
        } catch (e: Throwable) {
            cause = e
        } finally {
            shutdown(if (closed.get()) null else cause)
        }
    }

    private fun dispatch(message: JsonObject) {
        when (message.string("type")) {
            "response" -> {
                val seq = message["request_seq"]?.jsonPrimitive?.intOrNull ?: return
                val request = pending.remove(seq) ?: return
                val success = message["success"]?.jsonPrimitive?.booleanOrNull == true
                if (success) {
                    request.future.complete(message["body"] as? JsonObject ?: EMPTY)
                } else {
                    request.future.completeExceptionally(DapException(request.command, message.string("message") ?: "no message"))
                }
            }
            "event" -> message.string("event")?.let { onEvent(it, message["body"] as? JsonObject) }
        }
    }

    private fun shutdown(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        val failure = cause ?: IOException("Connection closed")
        pending.values.forEach { it.future.completeExceptionally(failure) }
        pending.clear()
        onClosed(cause)
    }

    companion object {
        val EMPTY = JsonObject(emptyMap())

        /** Connects to the debugger on the loopback interface. It binds 127.0.0.1 only. */
        fun open(
            port: Int,
            connectTimeoutMillis: Int = 3000,
            onEvent: (event: String, body: JsonObject?) -> Unit = { _, _ -> },
            onClosed: (cause: Throwable?) -> Unit = {},
        ): DapConnection {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress("127.0.0.1", port), connectTimeoutMillis)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
            return DapConnection(socket, onEvent, onClosed).also { it.reader.start() }
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
