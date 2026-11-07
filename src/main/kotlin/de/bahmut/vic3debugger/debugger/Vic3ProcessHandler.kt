package de.bahmut.vic3debugger.debugger

import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.application.ApplicationManager
import java.io.OutputStream

/**
 * Stands in for the game process, which the IDE did not start and must not end. Stopping the session
 * detaches: breakpoints are cleared, a halted game is resumed, and the game keeps running.
 */
class Vic3ProcessHandler(private val cleanUp: () -> Unit) : ProcessHandler() {

    override fun destroyProcessImpl() = finish { notifyProcessTerminated(0) }

    override fun detachProcessImpl() = finish { notifyProcessDetached() }

    override fun detachIsDefault(): Boolean = true

    override fun getProcessInput(): OutputStream? = null

    /** The connection ended from the game's side, so there is nothing left to clean up. */
    fun connectionLost() {
        if (!isProcessTerminated && !isProcessTerminating) notifyProcessTerminated(1)
    }

    private fun finish(notify: () -> Unit) {
        // Cleaning up sends requests with timeouts, so keep it off the thread that pressed Stop.
        ApplicationManager.getApplication().executeOnPooledThread {
            try { cleanUp() } finally { notify() }
        }
    }
}
