package de.bahmut.vic3debugger.mapping

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** The game's per-user folder, which holds `content_load.json`, logs and the generated script docs. */
object UserDataFolder {
    fun default(): Path? {
        val home = System.getProperty("user.home") ?: return null
        val os = System.getProperty("os.name").lowercase()
        val candidates = when {
            os.startsWith("windows") -> listOfNotNull(
                System.getenv("USERPROFILE")?.let { Paths.get(it, "Documents") },
                Paths.get(home, "Documents"),
            )
            os.contains("mac") -> listOf(Paths.get(home, "Documents"))
            else -> listOf(Paths.get(home, ".local", "share"), Paths.get(home, "Documents"))
        }
        return candidates.map { it.resolve("Paradox Interactive").resolve("Victoria 3") }
            .firstOrNull { Files.isDirectory(it) }
    }

    fun resolve(override: String?): Path? =
        override?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: default()
}
