package de.bahmut.vic3debugger.mapping

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps files the user has open to the game's virtual file system and back, by mod name and root-relative
 * path rather than by absolute path. Junctions, working copies elsewhere and Workshop duplicates all break
 * absolute paths (docs/SCRIPT_DEBUGGER.md §9, "Mapping editor files to loaded files").
 */
object FileMapper {
    private val SCRIPT_EXTENSIONS = setOf("txt", "gui")

    private val metadataCache = ConcurrentHashMap<Path, CachedMetadata>()

    private data class CachedMetadata(val modified: Long, val metadata: ModMetadata?)

    /** The source of a file on disk: a mod root (the first ancestor with `.metadata/metadata.json`) or a game folder. */
    sealed interface Origin {
        val root: Path
        val vfsPath: String

        data class Mod(override val root: Path, override val vfsPath: String, val name: String) : Origin
        data class BaseGame(override val root: Path, override val vfsPath: String) : Origin
    }

    fun isScriptFile(file: Path): Boolean =
        file.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() in SCRIPT_EXTENSIONS

    /**
     * Finds what a file belongs to, without needing the game. Mods are never nested inside each other, so
     * the first ancestor with mod metadata is the root. A file under no mod root but inside a Victoria 3
     * install's `game` folder is a base game file.
     */
    fun originOf(file: Path): Origin? {
        val absolute = file.toAbsolutePath().normalize()
        var dir = absolute.parent
        while (dir != null) {
            val metadata = cachedMetadata(dir)
            if (metadata != null) return Origin.Mod(dir, relative(dir, absolute), metadata.name)
            if (dir.fileName?.toString()?.equals("game", ignoreCase = true) == true && isGameInstall(dir)) {
                return Origin.BaseGame(dir, relative(dir, absolute))
            }
            dir = dir.parent
        }
        return null
    }

    /** Maps an open file to where the running game has it, or null when the game does not load it. */
    fun locate(file: Path, content: LoadedContent): VfsLocation? {
        return when (val origin = originOf(file) ?: return null) {
            is Origin.Mod -> content.modNamed(origin.name)?.let { VfsLocation(origin.name, origin.vfsPath) }
            // Any install's game folder maps by relative path; whether a mod overrides it is a separate question.
            is Origin.BaseGame -> VfsLocation(null, origin.vfsPath)
        }
    }

    /** The copy of [location] the game actually loaded, which may be a different file from the one open. */
    fun loadedCopy(location: VfsLocation, content: LoadedContent): Path? = when (val mod = location.modName) {
        null -> content.gameDir?.resolve(location.vfsPath)
        else -> content.modNamed(mod)?.root?.resolve(location.vfsPath)
    }?.takeIf { Files.isRegularFile(it) }

    /** Whether two files have the same bytes. Used to tell a working copy from the copy the game loaded. */
    fun sameContent(a: Path, b: Path): Boolean = runCatching {
        if (samePath(a, b)) return true
        Files.size(a) == Files.size(b) && Files.mismatch(a, b) == -1L
    }.getOrDefault(false)

    fun samePath(a: Path, b: Path): Boolean = runCatching {
        Files.isSameFile(a, b)
    }.getOrElse { a.toAbsolutePath().normalize().toString().equals(b.toAbsolutePath().normalize().toString(), ignoreCase = true) }

    private fun cachedMetadata(dir: Path): ModMetadata? {
        val file = ModMetadata.metadataFile(dir)
        val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull()
        if (modified == null) { metadataCache.remove(dir); return null }
        val cached = metadataCache[dir]
        if (cached != null && cached.modified == modified) return cached.metadata
        return ModMetadata.read(dir).also { metadataCache[dir] = CachedMetadata(modified, it) }
    }

    private fun isGameInstall(gameDir: Path): Boolean {
        val binaries = gameDir.parent?.resolve("binaries") ?: return false
        return Files.isRegularFile(binaries.resolve("victoria3.exe")) || Files.isRegularFile(binaries.resolve("victoria3"))
    }

    private fun relative(root: Path, file: Path): String = root.relativize(file).joinToString("/")
}
