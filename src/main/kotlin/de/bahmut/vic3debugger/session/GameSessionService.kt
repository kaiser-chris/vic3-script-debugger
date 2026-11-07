package de.bahmut.vic3debugger.session

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import de.bahmut.vic3debugger.Vic3Bundle
import de.bahmut.vic3debugger.dap.GameSession
import de.bahmut.vic3debugger.discovery.GameProcess
import de.bahmut.vic3debugger.discovery.GameProcessFinder
import de.bahmut.vic3debugger.discovery.LaunchOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.util.concurrent.ExecutionException

/**
 * Owns this project's connection to the game. One session at a time: the game delivers events to only
 * one client, so a second connection is no use (docs/SCRIPT_DEBUGGER.md §10).
 */
@Service(Service.Level.PROJECT)
class GameSessionService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    @Volatile
    var session: GameSession? = null
        private set

    @Volatile
    private var busy = false

    val isAttached: Boolean get() = session?.connection?.isOpen == true
    val canAttach: Boolean get() = !busy && !isAttached

    /** Finds running games and attaches, asking which one when there are several. */
    fun discoverAndAttach() {
        if (!canAttach) return
        busy = true
        scope.launch {
            try {
                val games = withBackgroundProgress(project, Vic3Bundle.message("progress.discovering")) {
                    withContext(Dispatchers.IO) { GameProcessFinder().find() }
                }
                val debuggable = games.filter { it.isDebuggable }
                when {
                    games.isEmpty() -> notify(NotificationType.WARNING, "notification.noGame.title", "notification.noGame.content")
                    debuggable.isEmpty() -> notify(
                        NotificationType.WARNING, "notification.noFlag.title", "notification.noFlag.content",
                        games.joinToString { it.pid.toString() }, LaunchOptions.DEFAULT_PORT,
                    )
                    debuggable.size == 1 -> attach(debuggable.single())
                    else -> withContext(Dispatchers.EDT) { choose(debuggable) }
                }
            } catch (e: Exception) {
                thisLogger().warn("Discovering games failed", e)
                notify(NotificationType.ERROR, "notification.discoveryFailed.title", "notification.error.content", e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    private fun choose(games: List<GameProcess>) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(games)
            .setTitle(Vic3Bundle.message("popup.chooseGame.title"))
            .setRenderer(textListCellRenderer { game: GameProcess ->
                Vic3Bundle.message("popup.chooseGame.item", game.pid, game.debuggerPort ?: 0)
            })
            .setItemChosenCallback { game -> busy = true; scope.launch { try { attach(game) } finally { busy = false } } }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    private suspend fun attach(game: GameProcess) {
        try {
            val attached = withBackgroundProgress(project, Vic3Bundle.message("progress.attaching", game.pid)) {
                withContext(Dispatchers.IO) {
                    GameSession.attach(game, onClosed = { cause -> onConnectionClosed(cause) })
                }
            }
            session = attached
            notify(NotificationType.INFORMATION, "notification.attached.title", "notification.attached.content", game.pid, attached.port)
        } catch (e: Exception) {
            val cause = (e as? ExecutionException)?.cause ?: e
            thisLogger().info("Attaching to game ${game.pid} failed", cause)
            if (cause is ConnectException) {
                notify(NotificationType.ERROR, "notification.refused.title", "notification.refused.content", game.pid, game.debuggerPort ?: 0)
            } else {
                notify(NotificationType.ERROR, "notification.attachFailed.title", "notification.error.content", cause.message ?: cause.javaClass.simpleName)
            }
        }
    }

    fun detach() {
        val current = session ?: return
        session = null
        scope.launch(Dispatchers.IO) {
            current.close()
            notify(NotificationType.INFORMATION, "notification.detached.title", "notification.detached.content", current.game.pid)
        }
    }

    private fun onConnectionClosed(cause: Throwable?) {
        val current = session ?: return // closed on purpose by detach()
        session = null
        thisLogger().info("Connection to game ${current.game.pid} closed", cause)
        notify(NotificationType.WARNING, "notification.lost.title", "notification.lost.content", current.game.pid)
    }

    private fun notify(type: NotificationType, titleKey: String, contentKey: String, vararg params: Any) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(Vic3Bundle.message(titleKey), Vic3Bundle.message(contentKey, *params), type)
            .notify(project)
    }

    /** Runs when the project closes: never leave the game frozen behind us. */
    override fun dispose() {
        val current = session ?: return
        session = null
        current.close()
    }

    companion object {
        const val NOTIFICATION_GROUP = "Victoria 3 Script Debugger"

        fun getInstance(project: Project): GameSessionService = project.service()
    }
}
