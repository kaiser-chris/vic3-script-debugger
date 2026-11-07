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

/** Maps real working-copy files against the running game's loaded content. Reads files only; sends nothing to the game. */
@Tag("live")
class LiveMappingTest {

    @Test
    fun `a mod file a frame names resolves to the loaded copy, and the working copy maps to it`() {
        val game = GameProcessFinder().find().firstOrNull()
        val gameDir = game?.executable?.let { LoadedContent.gameDirForExecutable(Paths.get(it)) }
        val content = LoadedContent.read(UserDataFolder.default()!!, gameDir)
        val vfsPath = "common/journal_entries/412_gate_projects.txt"

        val winner = content.winningFile(vfsPath)
        println("Winning copy: $winner (from ${content.winningModName(vfsPath)})")
        assertNotNull(winner)
        assertTrue(content.winningModName(vfsPath)!!.contains("Manaflow"))

        val workingCopy = Paths.get("D:/Development/projects/victoria3/gate/mod", vfsPath)
        assumeTrue(Files.isRegularFile(workingCopy))
        val status = FileStatus.of(workingCopy, content)
        println("Working copy: $status")
        assertEquals(vfsPath, status!!.location.vfsPath)
        assertTrue(status.isInEffect)
    }
}
