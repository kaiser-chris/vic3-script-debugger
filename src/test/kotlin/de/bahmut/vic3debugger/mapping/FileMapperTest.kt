package de.bahmut.vic3debugger.mapping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class FileMapperTest {
    @TempDir
    lateinit var tmp: Path

    private fun write(path: Path, text: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        return path
    }

    private fun mod(root: Path, name: String, extra: String = ""): Path {
        write(ModMetadata.metadataFile(root), """{"name":"$name"$extra}""")
        return root
    }

    @Test
    fun `metadata with a BOM, escapes and broken JSON still yields the name`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            """{"name":"[1.15] Manaflow: 汉化 \"x\"","game_custom_data":{"replace_paths":["common/history/countries"]}}""".toByteArray(StandardCharsets.UTF_8)
        assertEquals(ModMetadata("[1.15] Manaflow: 汉化 \"x\"", listOf("common/history/countries")), ModMetadata.parse(bom))
        val broken = """{"name":"Immersive","id":"","relationships":[]"game_custom_data":{}}""".toByteArray()
        assertEquals("Immersive", ModMetadata.parse(broken)?.name)
        assertNull(ModMetadata.parse("""{"id":"x"}""".toByteArray()))
    }

    @Test
    fun `an open working copy maps to the loaded mod by name, not by path`() {
        // The IDE has the repository open; the game loads a different folder with the same metadata name.
        val repoMod = mod(tmp.resolve("repo/gate/mod"), "Manaflow")
        val loadedMod = mod(tmp.resolve("profile/mod/manaflow"), "Manaflow")
        val open = write(repoMod.resolve("common/on_actions/403.txt"), "a = b")
        write(loadedMod.resolve("common/on_actions/403.txt"), "a = b")
        val content = LoadedContent(null, listOf(LoadedMod("Manaflow", loadedMod, emptyList())))

        val status = FileStatus.of(open, content)!!
        assertEquals(VfsLocation("Manaflow", "common/on_actions/403.txt"), status.location)
        assertTrue(status.matchesLoadedCopy)
        assertTrue(status.isInEffect)

        write(open, "a = c # edited after launch")
        assertFalse(FileStatus.of(open, content)!!.matchesLoadedCopy)
    }

    @Test
    fun `a mod that is not loaded does not map, and side-by-side mods resolve per file`() {
        val project = tmp.resolve("repo/gate")
        mod(project.resolve("mod"), "Manaflow")
        mod(project.resolve("cmf"), "Community Mod Framework")
        val cmfFile = write(project.resolve("cmf/common/scripted_triggers/x.txt"), "")
        val content = LoadedContent(null, listOf(LoadedMod("Manaflow", project.resolve("mod"), emptyList())))
        assertEquals("Community Mod Framework", (FileMapper.originOf(cmfFile) as FileMapper.Origin.Mod).name)
        assertNull(FileMapper.locate(cmfFile, content))
        assertNull(FileMapper.originOf(write(project.resolve("documentation/notes.txt"), "")))
    }

    @Test
    fun `later mods override earlier ones and replace_paths drops base game folders, not subfolders`() {
        val game = tmp.resolve("Victoria 3/game")
        write(tmp.resolve("Victoria 3/binaries/victoria3.exe"), "")
        val baseHistory = write(game.resolve("common/history/countries/gbr.txt"), "")
        val baseSub = write(game.resolve("common/history/countries/sub/x.txt"), "")
        val baseTrigger = write(game.resolve("common/scripted_triggers/t.txt"), "")
        val a = mod(tmp.resolve("a"), "A", ""","game_custom_data":{"replace_paths":["common/history/countries"]}""")
        val b = mod(tmp.resolve("b"), "B")
        val aTrigger = write(a.resolve("common/scripted_triggers/t.txt"), "")
        write(b.resolve("common/scripted_triggers/t.txt"), "")
        val content = LoadedContent(game, listOf(LoadedMod("A", a, listOf("common/history/countries")), LoadedMod("B", b, emptyList())))

        assertEquals("B", FileStatus.of(aTrigger, content)!!.overriddenBy)
        assertEquals("B", FileStatus.of(baseTrigger, content)!!.overriddenBy)
        assertTrue(FileStatus.of(baseHistory, content)!!.replacedByMod)
        assertFalse(FileStatus.of(baseSub, content)!!.replacedByMod)
        assertEquals(b.resolve("common/scripted_triggers/t.txt"), content.winningFile("common/scripted_triggers/t.txt"))
        assertNull(content.winningFile("common/history/countries/gbr.txt"))
    }

    @Test
    fun `placeholder frame names such as unknown are not paths`() {
        val content = LoadedContent(tmp, emptyList())
        for (name in listOf("<unknown>", "", "/abs/x.txt", "C:/x.txt", "../x.txt", "a/../../x.txt")) {
            assertFalse(LoadedContent.isVfsPath(name), name)
            assertNull(content.winningFile(name), name)
        }
        assertTrue(LoadedContent.isVfsPath("common/on_actions/403_on_action_misc.txt"))
    }

    @Test
    fun `reads content_load json in order, skipping mods without metadata`() {
        val first = mod(tmp.resolve("mods/first"), "First")
        val noMetadata = Files.createDirectories(tmp.resolve("mods/bare"))
        val second = mod(tmp.resolve("mods/second"), "Second")
        val esc = { p: Path -> p.toString().replace("\\", "\\\\") }
        write(tmp.resolve("user/content_load.json"), "﻿" + """{"enabledMods":[{"path":"${esc(first)}"},{"path":"${esc(noMetadata)}"},{"path":"${esc(second)}"}]}""")
        assertEquals(listOf("First", "Second"), LoadedContent.read(tmp.resolve("user"), null).mods.map { it.name })
    }
}
