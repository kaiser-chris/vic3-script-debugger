package de.bahmut.vic3debugger.debugger

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import de.bahmut.vic3debugger.mapping.FileMapper
import de.bahmut.vic3debugger.mapping.LoadedContent
import de.bahmut.vic3debugger.mapping.ModMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a VFS path from a stack frame into a file to show. Finds the copy the game uses, then prefers the
 * project's own copy of that mod when it has the same content, so the user lands in the file they edit.
 */
class SourceResolver(private val project: Project, private val content: LoadedContent) {

    /** Mod roots inside the project, by metadata name. Found once per session, a few levels deep. */
    private val projectModRoots: Map<String, List<Path>> by lazy { findProjectModRoots() }
    private val cache = ConcurrentHashMap<String, VirtualFileHolder>()

    private class VirtualFileHolder(val file: VirtualFile?)

    /** Blocking file system access: call off the EDT and outside read actions. */
    fun resolve(vfsPath: String): VirtualFile? = cache.getOrPut(vfsPath) { VirtualFileHolder(find(vfsPath)) }.file

    private fun find(vfsPath: String): VirtualFile? {
        if (!LoadedContent.isVfsPath(vfsPath)) return null
        val loaded = content.winningFile(vfsPath) ?: return null
        val modName = content.winningModName(vfsPath)
        val projectCopy = modName?.let { projectModRoots[it] }.orEmpty()
            .map { it.resolve(vfsPath) }
            .firstOrNull { Files.isRegularFile(it) && FileMapper.sameContent(it, loaded) }
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectCopy ?: loaded)
    }

    private fun findProjectModRoots(): Map<String, List<Path>> {
        val found = mutableMapOf<String, MutableList<Path>>()
        // The project model may only be read inside a read action. The file walk below must stay outside one.
        val contentRoots = ReadAction.computeBlocking<List<Path>, RuntimeException> {
            ProjectRootManager.getInstance(project).contentRoots.mapNotNull { it.fileSystem.getNioPath(it) }
        }
        fun visit(dir: Path, depth: Int) {
            val metadata = ModMetadata.read(dir)
            if (metadata != null) {
                found.getOrPut(metadata.name) { mutableListOf() }.add(dir)
                return // mods are never nested
            }
            if (depth == 0) return
            val children = runCatching { Files.newDirectoryStream(dir).use { stream -> stream.filter { Files.isDirectory(it) }.toList() } }.getOrDefault(emptyList())
            for (child in children) {
                val name = child.fileName.toString()
                if (name.startsWith(".") || name == "node_modules" || name == "build") continue
                visit(child, depth - 1)
            }
        }
        contentRoots.forEach { visit(it, MAX_DEPTH) }
        return found
    }

    companion object {
        private const val MAX_DEPTH = 3
    }
}
