package de.bahmut.vic3debugger.mapping

import java.nio.file.Path

/** How an open file relates to what the game loaded. Drives the editor indicator and breakpoint validity. */
data class FileStatus(
    val location: VfsLocation,
    /** The copy the game loaded, or null when that content has no file at this path. */
    val loadedCopy: Path?,
    /** Whether the open file has the same bytes as [loadedCopy]. Line numbers only line up when it does. */
    val matchesLoadedCopy: Boolean,
    /** The mod whose copy wins instead, when this file is overridden; null when this file's content wins. */
    val overriddenBy: String?,
    /** A base game file whose folder a mod's `replace_paths` removes. */
    val replacedByMod: Boolean,
) {
    val isInEffect: Boolean get() = overriddenBy == null && !replacedByMod && loadedCopy != null

    companion object {
        fun of(file: Path, content: LoadedContent): FileStatus? {
            val location = FileMapper.locate(file, content) ?: return null
            val loaded = FileMapper.loadedCopy(location, content)
            val winner = content.winningModName(location.vfsPath)
            val overriddenBy = when {
                winner == null || winner == location.modName -> null
                location.modName == null -> winner
                // A later mod wins only if it comes after this one in load order.
                content.mods.indexOfFirst { it.name == winner } > content.mods.indexOfFirst { it.name == location.modName } -> winner
                else -> null
            }
            return FileStatus(
                location = location,
                loadedCopy = loaded,
                matchesLoadedCopy = loaded != null && FileMapper.sameContent(file, loaded),
                overriddenBy = overriddenBy,
                replacedByMod = location.modName == null && content.isBaseGameReplaced(location.vfsPath),
            )
        }
    }
}
