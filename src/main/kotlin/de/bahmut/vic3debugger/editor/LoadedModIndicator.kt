package de.bahmut.vic3debugger.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.toolbar.floating.AbstractFloatingToolbarProvider
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.awt.RelativePoint
import com.intellij.openapi.util.text.StringUtil
import de.bahmut.vic3debugger.Vic3Bundle
import de.bahmut.vic3debugger.mapping.FileMapper
import de.bahmut.vic3debugger.mapping.FileStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.event.MouseEvent

/** Shows the indicator in the editor's top-right floating toolbar, only for files the game loads. */
class LoadedModToolbarProvider : AbstractFloatingToolbarProvider(ACTION_GROUP) {
    override val autoHideable: Boolean = false

    override suspend fun isApplicableAsync(dataContext: DataContext): Boolean {
        val file = CommonDataKeys.VIRTUAL_FILE.getData(dataContext) ?: return false
        // Wait for the first snapshot: this is decided once per editor, and an answer of no is final.
        val snapshot = GameContentService.getInstance().snapshot()
        return withContext(Dispatchers.IO) { LoadedModIndicatorAction.statusOf(file, snapshot) != null }
    }

    companion object {
        const val ACTION_GROUP = "Vic3ScriptDebugger.EditorFloatingToolbar"
    }
}

/**
 * Says which loaded mod (or the base game) an open file belongs to, and whether the game would use this
 * copy: it may be overridden by a later mod, or differ from the copy the game loaded.
 */
class LoadedModIndicatorAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val (status, running) = file?.let { statusOf(it) } ?: run { e.presentation.isEnabledAndVisible = false; return }
        val owner = status.location.modName ?: Vic3Bundle.message("indicator.baseGame")
        e.presentation.isEnabledAndVisible = true
        e.presentation.icon = when {
            !status.isInEffect || !status.matchesLoadedCopy -> AllIcons.General.Warning
            running -> AllIcons.Actions.StartDebugger
            else -> AllIcons.Nodes.Module
        }
        e.presentation.text = Vic3Bundle.message(if (running) "indicator.text.running" else "indicator.text.enabled", owner)
        e.presentation.description = describe(status, running)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val (status, running) = statusOf(file) ?: return
        val type = if (status.isInEffect && status.matchesLoadedCopy) MessageType.INFO else MessageType.WARNING
        val balloon = JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(describe(status, running), type, null)
            .setHideOnClickOutside(true)
            .createBalloon()
        val mouse = e.inputEvent as? MouseEvent
        if (mouse != null) balloon.show(RelativePoint(mouse), Balloon.Position.below)
        else e.project?.let { balloon.showInCenterOf(com.intellij.openapi.wm.WindowManager.getInstance().getFrame(it)?.rootPane ?: return) }
    }

    private fun describe(status: FileStatus, running: Boolean): String {
        val lines = mutableListOf<String>()
        val owner = status.location.modName ?: Vic3Bundle.message("indicator.baseGame")
        lines += Vic3Bundle.message(if (running) "indicator.detail.running" else "indicator.detail.enabled", StringUtil.escapeXmlEntities(owner))
        lines += Vic3Bundle.message("indicator.detail.path", StringUtil.escapeXmlEntities(status.location.vfsPath))
        when {
            status.overriddenBy != null -> lines += Vic3Bundle.message("indicator.detail.overridden", StringUtil.escapeXmlEntities(status.overriddenBy))
            status.replacedByMod -> lines += Vic3Bundle.message("indicator.detail.replaced")
            status.loadedCopy == null -> lines += Vic3Bundle.message("indicator.detail.missing")
            !status.matchesLoadedCopy -> lines += Vic3Bundle.message("indicator.detail.differs", StringUtil.escapeXmlEntities(status.loadedCopy.toString()))
            else -> lines += Vic3Bundle.message("indicator.detail.same")
        }
        return lines.joinToString("<br>")
    }

    companion object {
        /** The file's status against what the game loaded, and whether a game is running; null when the game does not load it. */
        fun statusOf(
            file: VirtualFile,
            snapshot: GameContentService.Snapshot? = GameContentService.getInstance().current(),
        ): Pair<FileStatus, Boolean>? {
            val path = file.fileSystem.getNioPath(file) ?: return null
            if (!FileMapper.isScriptFile(path)) return null
            if (snapshot == null) return null
            if (FileMapper.originOf(path) == null) return null
            val status = FileStatus.of(path, snapshot.content) ?: return null
            // Base game files only count once the game's install is known, from a running game.
            if (status.location.modName == null && snapshot.content.gameDir == null) return null
            return status to snapshot.isGameRunning
        }
    }
}
