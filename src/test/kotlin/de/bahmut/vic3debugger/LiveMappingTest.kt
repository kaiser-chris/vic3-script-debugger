package de.bahmut.vic3debugger

import de.bahmut.vic3debugger.discovery.GameProcessFinder
import de.bahmut.vic3debugger.mapping.FileStatus
import de.bahmut.vic3debugger.mapping.LoadedContent
import de.bahmut.vic3debugger.mapping.UserDataFolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Maps a file from a mod's working copy against what the game loaded. Reads files only; sends nothing to
 * the game. Set VIC3_WORKING_COPY_FILE to a script file inside a working copy of a mod in the current
 * playset, ideally opened from somewhere other than the folder the game loads it from.
 */
@Tag("live")
class LiveMappingTest {

    @Test
    fun `a working copy file maps to the copy the game loaded`() {
        val configured = System.getenv("VIC3_WORKING_COPY_FILE")
        assumeTrue(!configured.isNullOrBlank(), "VIC3_WORKING_COPY_FILE is not set")
        val workingCopy = Paths.get(configured!!)
        assumeTrue(Files.isRegularFile(workingCopy), "VIC3_WORKING_COPY_FILE does not exist: $workingCopy")

        val game = GameProcessFinder().find().firstOrNull()
        val gameDir = game?.executable?.let { LoadedContent.gameDirForExecutable(Paths.get(it)) }
        val content = LoadedContent.read(UserDataFolder.default()!!, gameDir)
        println("Loaded mods: ${content.mods.map { it.name }}")

        val status = FileStatus.of(workingCopy, content)
        println("Status: $status")
        assertNotNull(status, "The file is not part of a loaded mod or the base game")
        assertNotNull(status!!.loadedCopy)
        assertEquals(status.loadedCopy, content.winningFile(status.location.vfsPath))
        assertTrue(status.isInEffect)
    }
}
