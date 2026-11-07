package de.bahmut.vic3debugger.dap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DapConnectionTest {

    @Test
    fun `frames round-trip, including multi-byte UTF-8`() {
        val out = ByteArrayOutputStream()
        DapFraming.write(out, """{"a":"汉化 한국어"}""")
        DapFraming.write(out, "{}")
        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals("""{"a":"汉化 한국어"}""", DapFraming.read(input))
        assertEquals("{}", DapFraming.read(input))
        assertNull(DapFraming.read(input))
    }

    @Test
    fun `a stream cut inside a body is an error, not a clean end`() {
        val input = ByteArrayInputStream("Content-Length: 10\r\n\r\n{}".toByteArray())
        assertThrows<EOFException> { DapFraming.read(input) }
    }

    @Test
    fun `responses complete their requests and events reach the listener`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val events = mutableListOf<String>()
            val eventSeen = CountDownLatch(1)
            val serverThread = thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    repeat(2) {
                        val request = Json.parseToJsonElement(DapFraming.read(input)!!).jsonObject
                        val seq = request["seq"]!!.jsonPrimitive.int
                        when (request["command"]!!.jsonPrimitive.content) {
                            "initialize" -> {
                                DapFraming.write(output, """{"seq":1,"type":"response","request_seq":$seq,"command":"initialize","success":true,"body":{"supportsConfigurationDoneRequest":true}}""")
                                DapFraming.write(output, """{"seq":2,"type":"event","event":"initialized"}""")
                            }
                            else -> DapFraming.write(output, """{"seq":3,"type":"response","request_seq":$seq,"command":"stackTrace","success":false,"message":"not stopped"}""")
                        }
                    }
                }
            }
            val connection = DapConnection.open(server.localPort, onEvent = { event, _ -> events += event; eventSeen.countDown() })
            connection.use {
                val capabilities: JsonObject = it.request("initialize").get(5, TimeUnit.SECONDS)
                assertEquals(true, capabilities["supportsConfigurationDoneRequest"]?.jsonPrimitive?.content?.toBoolean())
                val failure = assertThrows<ExecutionException> { it.request("stackTrace").get(5, TimeUnit.SECONDS) }
                assertEquals("stackTrace failed: not stopped", failure.cause?.message)
                assertTrue(eventSeen.await(5, TimeUnit.SECONDS))
                assertEquals(listOf("initialized"), events)
            }
            serverThread.join(5000)
        }
    }

    @Test
    fun `the server closing fails pending requests and reports the close`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val closed = CountDownLatch(1)
            thread { server.accept().use { DapFraming.read(it.getInputStream()) } } // read one request, then hang up
            val connection = DapConnection.open(server.localPort, onClosed = { closed.countDown() })
            val pending = connection.request("initialize")
            assertThrows<ExecutionException> { pending.get(5, TimeUnit.SECONDS) }
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertFalse(connection.isOpen)
        }
    }
}
