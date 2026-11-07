package de.bahmut.vic3debugger

import de.bahmut.vic3debugger.dap.GameSession
import de.bahmut.vic3debugger.discovery.GameProcessFinder
import de.bahmut.vic3debugger.mapping.LoadedContent
import de.bahmut.vic3debugger.mapping.UserDataFolder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Runs the debug process's breakpoint sequence against a running game: set, halt, read the stack and scopes,
 * resume, clear. It halts the game for a moment, so it is tagged live and excluded by default.
 */
@Tag("live")
class LiveBreakpointTest {
    private val hotFile = "gfx/map/spline_network/route_graphics/01_road_vehicles.txt"
    private val hotLine = 62

    @Test
    fun `a breakpoint halts the game, frames map to files, scopes are readable, and the game resumes`() {
        val game = GameProcessFinder().find().firstOrNull { it.isDebuggable }
        assumeTrue(game != null, "No running game started with -script_debugger")
        val gameDir = LoadedContent.gameDirForExecutable(Paths.get(game!!.executable!!))
        val content = LoadedContent.read(UserDataFolder.default()!!, gameDir)
        println("Loaded: game=$gameDir mods=${content.mods.map { it.name }}")

        GameSession.attach(game).use { session ->
            val stopped = CompletableFuture<JsonObject?>()
            session.eventListener = { event, body -> if (event == "stopped") stopped.complete(body) }
            fun req(command: String, args: JsonObject = JsonObject(emptyMap())) = session.connection.request(command, args).get(5, TimeUnit.SECONDS)
            fun breakpoints(vararg lines: Int) = buildJsonObject {
                put("source", buildJsonObject { put("path", JsonPrimitive(hotFile)) })
                put("breakpoints", JsonArray(lines.map { line -> buildJsonObject { put("line", JsonPrimitive(line)) } }))
            }
            try {
                val set = req("setBreakpoints", breakpoints(hotLine))
                assertEquals("true", (set["breakpoints"] as JsonArray)[0].jsonObject["verified"]!!.jsonPrimitive.content)

                val stop = stopped.get(10, TimeUnit.SECONDS)
                println("Stopped: $stop")
                assertEquals("breakpoint", stop!!["reason"]!!.jsonPrimitive.content)
                assertTrue(session.isHalted)

                val frames = req("stackTrace", buildJsonObject { put("threadId", JsonPrimitive(1)) })["stackFrames"] as JsonArray
                val top = frames[0].jsonObject
                val vfsPath = top["source"]!!.jsonObject["name"]!!.jsonPrimitive.content
                println("Top frame: $top")
                assertEquals(hotFile, vfsPath)
                assertEquals(hotLine, top["line"]!!.jsonPrimitive.int)
                // The frame maps back to the file the game uses.
                val winner = content.winningFile(vfsPath)
                println("Frame file: $winner")
                assertNotNull(winner)

                val scopes = req("scopes", buildJsonObject { put("frameId", top["id"]!!) })["scopes"] as JsonArray
                println("Scopes: ${scopes.map { it.jsonObject["name"] }}")
                val ref = scopes[0].jsonObject["variablesReference"]!!.jsonPrimitive.int
                val variables = req("variables", buildJsonObject { put("variablesReference", JsonPrimitive(ref)) })["variables"] as JsonArray
                println("Variables: ${variables.map { "${it.jsonObject["name"]?.jsonPrimitive?.content} = ${it.jsonObject["value"]?.jsonPrimitive?.content}" }}")
                assertTrue(variables.any { it.jsonObject["name"]!!.jsonPrimitive.content == "this" })

                req("setBreakpoints", breakpoints())
                req("continue", buildJsonObject { put("threadId", JsonPrimitive(1)) })
                session.markResumed()
                // Running again: reads now fail with "not stopped".
                val afterResume = runCatching { req("stackTrace", buildJsonObject { put("threadId", JsonPrimitive(1)) }) }
                println("After resume: ${afterResume.exceptionOrNull()?.cause?.message ?: afterResume.getOrNull()}")
                assertTrue((afterResume.exceptionOrNull() as? ExecutionException)?.cause?.message?.contains("not stopped") == true)
                assertFalse(session.isHalted)
            } finally {
                runCatching { req("setBreakpoints", breakpoints()) }
            }
        }
    }
}
