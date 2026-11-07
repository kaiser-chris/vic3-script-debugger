package de.bahmut.vic3debugger.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.util.Disposer
import java.util.concurrent.CopyOnWriteArrayList
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import de.bahmut.vic3debugger.discovery.GameProcess
import de.bahmut.vic3debugger.discovery.GameProcessFinder
import de.bahmut.vic3debugger.mapping.LoadedContent
import de.bahmut.vic3debugger.mapping.UserDataFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the game loaded, for editor features that work without a debug session. Refreshed in the background
 * when read and older than [MAX_AGE_MILLIS]; readers always get the last snapshot immediately.
 */
@Service(Service.Level.APP)
class GameContentService(private val scope: CoroutineScope) {

    data class Snapshot(
        val content: LoadedContent,
        /** Running copies of the game. Empty when none is running; the mods are then from its last launch. */
        val games: List<GameProcess>,
        val takenAt: Long,
    ) {
        val isGameRunning: Boolean get() = games.isNotEmpty()
    }

    @Volatile
    private var snapshot: Snapshot? = null
    private val refreshing = AtomicBoolean(false)

    /** The last snapshot, triggering a refresh when it is missing or stale. Never blocks, so may return null at first. */
    fun current(): Snapshot? {
        val last = snapshot
        if (last == null || last.isStale()) refresh()
        return last
    }

    /**
     * A snapshot, waiting for the first one to be taken. Callers that decide something once, like whether an
     * editor gets the indicator at all, need this: [current] is null until the first refresh finishes.
     */
    suspend fun snapshot(): Snapshot {
        snapshot?.takeUnless { it.isStale() }?.let { return it }
        return mutex.withLock {
            snapshot?.takeUnless { it.isStale() } ?: take().also(::publish)
        }
    }

    /**
     * Takes a new snapshot in the background and tells every listener. Debug sessions call this when they
     * start and end, so open editors update as soon as a game is attached or detached.
     */
    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                mutex.withLock { publish(take()) }
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** Calls [listener] on a background thread with every new snapshot until [parent] is disposed, and now with the current one. */
    fun subscribe(parent: Disposable, listener: (Snapshot) -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
        val last = snapshot
        if (last != null && !last.isStale()) scope.launch(Dispatchers.IO) { listener(last) } else refresh()
    }

    private fun publish(taken: Snapshot) {
        snapshot = taken
        for (listener in listeners) {
            scope.launch(Dispatchers.IO) {
                runCatching { listener(taken) }.onFailure { thisLogger().error("Updating for new game content failed", it) }
            }
        }
    }

    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    private suspend fun take(): Snapshot = withContext(Dispatchers.IO) {
        val games = runCatching { GameProcessFinder().find() }
            .onFailure { thisLogger().info("Listing game processes failed", it) }
            .getOrDefault(emptyList())
        val gameDir = games.firstNotNullOfOrNull { game -> game.executable?.let { LoadedContent.gameDirForExecutable(Paths.get(it)) } }
        val content = UserDataFolder.default()?.let { LoadedContent.read(it, gameDir) } ?: LoadedContent(gameDir, emptyList())
        Snapshot(content, games, System.currentTimeMillis())
    }

    private val mutex = Mutex()

    private fun Snapshot.isStale() = System.currentTimeMillis() - takenAt > MAX_AGE_MILLIS

    companion object {
        private const val MAX_AGE_MILLIS = 15_000L

        fun getInstance(): GameContentService = service()
    }
}
