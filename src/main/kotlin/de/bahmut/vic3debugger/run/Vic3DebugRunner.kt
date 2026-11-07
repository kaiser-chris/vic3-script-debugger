package de.bahmut.vic3debugger.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import de.bahmut.vic3debugger.Vic3Bundle
import de.bahmut.vic3debugger.dap.GameSession
import de.bahmut.vic3debugger.debugger.Vic3DebugProcess
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.net.ConnectException
import java.util.concurrent.ExecutionException as FutureExecutionException

/**
 * Runs [Vic3RunConfiguration] under the Debug executor only, the way Remote JVM Debug does: there is
 * nothing to "run", only a game to attach to.
 */
class Vic3DebugRunner : AsyncProgramRunner<RunnerSettings>() {

    override fun getRunnerId(): String = "Vic3ScriptDebuggerRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is Vic3RunConfiguration

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        val configuration = environment.runProfile as Vic3RunConfiguration
        val promise = AsyncPromise<RunContentDescriptor?>()
        val application = ApplicationManager.getApplication()
        application.executeOnPooledThread {
            val attached = try {
                val target = GameTarget.resolve(configuration.options)
                target to connect(target)
            } catch (e: Throwable) {
                promise.setError(e)
                return@executeOnPooledThread
            }
            application.invokeLater({
                try {
                    val (target, session) = attached
                    val result = XDebuggerManager.getInstance(environment.project)
                        .newSessionBuilder(object : XDebugProcessStarter() {
                            override fun start(xSession: XDebugSession): XDebugProcess = Vic3DebugProcess(xSession, session, target)
                        })
                        .environment(environment)
                        .startSession()
                    promise.setResult(result.runContentDescriptor)
                } catch (e: Throwable) {
                    attached.second.close()
                    promise.setError(e)
                }
            }, ModalityState.defaultModalityState())
        }
        return promise
    }

    private fun connect(target: GameTarget): GameSession = try {
        GameSession.attach(target.process, target.port)
    } catch (e: ConnectException) {
        val pid = target.process?.pid?.toString() ?: "?"
        throw ExecutionException(Vic3Bundle.message("run.error.refused", target.port, pid), e)
    } catch (e: FutureExecutionException) {
        throw ExecutionException(Vic3Bundle.message("run.error.handshake", e.cause?.message ?: e.message.orEmpty()), e.cause ?: e)
    }
}
