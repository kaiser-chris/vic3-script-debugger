package de.bahmut.vic3debugger.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import de.bahmut.vic3debugger.session.GameSessionService

class AttachToGameAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && GameSessionService.getInstance(project).canAttach
    }

    override fun actionPerformed(e: AnActionEvent) {
        GameSessionService.getInstance(e.project ?: return).discoverAndAttach()
    }
}

class DetachFromGameAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && GameSessionService.getInstance(project).isAttached
    }

    override fun actionPerformed(e: AnActionEvent) {
        GameSessionService.getInstance(e.project ?: return).detach()
    }
}
