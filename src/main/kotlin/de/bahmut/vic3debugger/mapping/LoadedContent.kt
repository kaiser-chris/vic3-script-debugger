package de.bahmut.vic3debugger.mapping

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** A mod the game loads: its name from metadata, and the folder the game reads it from. */
data class LoadedMod(val name: String, val root: Path, val replacePaths: List<String>)

/** Where a file sits in the game's virtual file system: which content provides it, and the path below its root. */
data class VfsLocation(
    /** The mod's metadata name, or null for the base game. */
    val modName: String?,
    /** Root-relative with forward slashes, as the debugger reports it, e.g. `common/on_actions/x.txt`. */
    val vfsPath: String,
)

/**
 * What the game loaded: the base game folder plus the enabled mods in load order.
 * The order of `enabledMods` in `content_load.json` is the best available guess at load order (§9).
 */
data class LoadedContent(val gameDir: Path?, val mods: List<LoadedMod>) {

    fun modNamed(name: String): LoadedMod? = mods.firstOrNull { it.name == name }

    /** The file the game uses for a VFS path: the last mod providing it wins, then the base game. */
    fun winningFile(vfsPath: String): Path? {
        if (!isVfsPath(vfsPath)) return null
        for (mod in mods.asReversed()) {
            val candidate = mod.root.resolve(vfsPath)
            if (Files.isRegularFile(candidate)) return candidate
        }
        if (gameDir == null || isBaseGameReplaced(vfsPath)) return null
        return gameDir.resolve(vfsPath).takeIf { Files.isRegularFile(it) }
    }

    /** The name of the content whose copy of [vfsPath] is in effect, or null for the base game or nothing. */
    fun winningModName(vfsPath: String): String? =
        if (!isVfsPath(vfsPath)) null else mods.asReversed().firstOrNull { Files.isRegularFile(it.root.resolve(vfsPath)) }?.name

    /**
     * Whether a mod's `replace_paths` stops the base game's copy of this file from loading. An entry covers
     * files directly in that folder only, not subfolders, and never affects other mods' files.
     */
    fun isBaseGameReplaced(vfsPath: String): Boolean {
        val folder = vfsPath.substringBeforeLast('/', "")
        return mods.any { mod -> mod.replacePaths.any { it.trim('/') == folder } }
    }

    companion object {
        val EMPTY = LoadedContent(null, emptyList())
        private val lenientJson = Json { isLenient = true }
        private val INVALID_CHARS = Regex("[<>:\"|?*\\u0000-\\u001F]")

        /**
         * Whether a name from the game is a path inside the game or a mod. Frames for code with no file carry
         * placeholders such as `<unknown>`, which are not paths at all and must not reach the file system.
         */
        fun isVfsPath(name: String): Boolean =
            name.isNotBlank() && !INVALID_CHARS.containsMatchIn(name) && !name.startsWith("/") && !name.startsWith("\\") &&
                name.split('/', '\\').none { it == ".." }

        /** Reads `content_load.json` from the game's user data folder. Mods without readable metadata are skipped. */
        fun read(userDataDir: Path, gameDir: Path?): LoadedContent {
            val file = userDataDir.resolve("content_load.json")
            if (!Files.isRegularFile(file)) return LoadedContent(gameDir, emptyList())
            val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8).removePrefix("﻿")
            val json = runCatching { lenientJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            val mods = (json?.get("enabledMods") as? JsonArray).orEmpty().mapNotNull { entry ->
                val path = ((entry as? JsonObject)?.get("path") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val root = Paths.get(path)
                val metadata = ModMetadata.read(root) ?: return@mapNotNull null
                LoadedMod(metadata.name, root, metadata.replacePaths)
            }
            return LoadedContent(gameDir, mods)
        }

        /** The game's content folder for an executable at `<install>/binaries/victoria3.exe`. */
        fun gameDirForExecutable(executable: Path): Path? =
            executable.parent?.parent?.resolve("game")?.takeIf { Files.isDirectory(it) }
    }
}
