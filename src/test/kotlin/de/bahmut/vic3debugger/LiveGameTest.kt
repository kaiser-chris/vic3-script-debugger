package de.bahmut.vic3debugger

import de.bahmut.vic3debugger.dap.GameSession
import de.bahmut.vic3debugger.discovery.GameProcessFinder
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Needs a running game started with -script_debugger. Excluded by default; run with `gradlew test -Plive`.
 * Only discovers, attaches, lists threads and detaches: it never halts the game or evaluates anything.
 */
@Tag("live")
class LiveGameTest {

    @Test
    fun `discovers a running game and attaches without halting it`() {
        val games = GameProcessFinder().find()
        println("Found: $games")
        val game = games.firstOrNull { it.isDebuggable }
        assumeTrue(game != null, "No running game started with -script_debugger")

        GameSession.attach(game!!).use { session ->
            println("Capabilities: ${session.capabilities}")
            assertTrue("exceptionBreakpointFilters" in session.capabilities)
            val threads = session.connection.request("threads").get(5, TimeUnit.SECONDS)
            println("Threads: $threads")
            assertTrue(threads["threads"]!!.jsonArray.isNotEmpty())
            assertTrue(!session.isHalted)
        }
    }
}
