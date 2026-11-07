package de.bahmut.vic3debugger.editor

import com.intellij.openapi.components.Service
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
            snapshot?.takeUnless { it.isStale() } ?: take().also { snapshot = it }
        }
    }

    fun refresh() {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                mutex.withLock { snapshot = take() }
            } finally {
                refreshing.set(false)
            }
        }
    }

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
