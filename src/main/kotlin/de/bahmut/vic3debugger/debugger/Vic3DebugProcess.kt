package de.bahmut.vic3debugger.debugger

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import de.bahmut.vic3debugger.Vic3Bundle
import de.bahmut.vic3debugger.dap.GameSession
import de.bahmut.vic3debugger.editor.GameContentService
import de.bahmut.vic3debugger.mapping.FileStatus
import de.bahmut.vic3debugger.run.GameTarget
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One debug session against the game: breakpoints, halts, stepping, frames and scopes, evaluation, and the
 * game's script output and errors in the console.
 */
class Vic3DebugProcess(
    session: XDebugSession,
    private val game: GameSession,
    private val target: GameTarget,
) : XDebugProcess(session) {

    private val connection = game.connection
    private val sources = SourceResolver(session.project, target.content)
    private val processHandler = Vic3ProcessHandler(::cleanUp)
    private val cleanedUp = AtomicBoolean(false)

    /**
     * Breakpoints by VFS path. The game keeps one breakpoint list per file whatever path spelling sets it,
     * so every copy that maps to a path shares that path's list and the full list is always sent (§9).
     */
    private val breakpoints = ConcurrentHashMap<String, MutableList<XLineBreakpoint<Vic3BreakpointProperties>>>()

    init {
        game.eventListener = ::onEvent
        game.closeListener = { cause ->
            if (!cleanedUp.get()) {
                print(Vic3Bundle.message("console.lost", cause?.message ?: Vic3Bundle.message("console.lost.noCause")), ProcessOutputTypes.SYSTEM)
                processHandler.connectionLost()
            }
        }
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider = Vic3EditorsProvider

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(LineBreakpointHandler())

    override fun doGetProcessHandler(): ProcessHandler = processHandler

    override fun createConsole(): ExecutionConsole =
        TextConsoleBuilderFactory.getInstance().createBuilder(session.project).console.also { it.attachToProcess(processHandler) }

    override fun sessionInitialized() {
        processHandler.startNotify()
        GameContentService.getInstance().refresh() // re-check every open editor against the attached game
        val where = target.process?.let { Vic3Bundle.message("console.attached.pid", it.pid) } ?: ""
        print(Vic3Bundle.message("console.attached", target.port, where), ProcessOutputTypes.SYSTEM)
        if (target.content.mods.isNotEmpty()) {
            print(Vic3Bundle.message("console.mods", target.content.mods.joinToString { it.name }), ProcessOutputTypes.SYSTEM)
        }
    }

    // --- execution control. The game reports one thread, id 1; requests with 1 work whatever stopped events say (§3).

    override fun startPausing() = command("pause")
    override fun resume(context: XSuspendContext?) = resuming("continue")
    override fun startStepOver(context: XSuspendContext?) = resuming("next")
    override fun startStepInto(context: XSuspendContext?) = resuming("stepIn")
    override fun startStepOut(context: XSuspendContext?) = resuming("stepOut")

    override fun stop() = cleanUp()

    /**
     * Run to Cursor. The game has no such request, so this sets a temporary breakpoint on the target line,
     * alongside that file's own breakpoints, and continues. The next halt removes it, wherever the game stopped.
     *
     * By the time this is called the IDE already shows the session as running. When the line cannot be mapped
     * to a file the game uses, the game is still halted, so the current halt is shown again rather than
     * leaving the IDE and the game disagreeing.
     */
    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        val path = position.file.fileSystem.getNioPath(position.file)
        val status = path?.let { FileStatus.of(it, target.content) }
        if (status == null || !status.isInEffect) {
            print(Vic3Bundle.message("console.runToUnmapped"), ProcessOutputTypes.STDERR)
            context?.let { session.positionReached(it) }
            return
        }
        runTarget = RunTarget(status.location.vfsPath, position.line + 1)
        sync(status.location.vfsPath).whenComplete { _, _ -> resuming("continue") }
    }

    private data class RunTarget(val vfsPath: String, val line: Int)

    @Volatile
    private var runTarget: RunTarget? = null

    private fun resuming(command: String) {
        game.markResumed()
        command(command)
    }

    private fun command(command: String) {
        connection.request(command, buildJsonObject { put("threadId", JsonPrimitive(1)) }).exceptionally { error ->
            print(Vic3Bundle.message("console.commandFailed", command, error.message.orEmpty()), ProcessOutputTypes.STDERR)
            null
        }
    }

    /**
     * Leaves the game as it was found: no breakpoints left behind for the next client, and never frozen.
     * Idempotent, since Stop, detach and the session ending can all reach it.
     */
    private fun cleanUp() {
        if (!cleanedUp.compareAndSet(false, true)) return
        GameContentService.getInstance().refresh()
        if (!connection.isOpen) return
        runCatching {
            for (path in breakpoints.keys) {
                connection.request("setBreakpoints", setBreakpointsArgs(path, JsonArray(emptyList()))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }.onFailure { thisLogger().info("Clearing breakpoints failed", it) }
        game.close()
    }

    // --- events

    private fun onEvent(event: String, body: JsonObject?) {
        when (event) {
            "stopped" -> onStopped(body)
            "output" -> {
                val text = body?.string("output") ?: return
                val type = if (body.string("category") == "stderr") ProcessOutputTypes.STDERR else ProcessOutputTypes.STDOUT
                print(text, type)
            }
        }
    }

    /**
     * The game is halted, so the IDE must hear about it whatever goes wrong here: a halt the IDE does not
     * show leaves the game frozen with no Resume button. Anything that fails while building the frames
     * falls back to frames without source positions, and the failure is logged.
     */
    private fun onStopped(body: JsonObject?) {
        val reason = body?.string("reason")
        val request = connection.request("stackTrace", buildJsonObject {
            put("threadId", JsonPrimitive(1))
            put("startFrame", JsonPrimitive(0))
            put("levels", JsonPrimitive(MAX_FRAMES))
        })
        request.handleOnPool { trace, error ->
            if (error != null) {
                print(Vic3Bundle.message("console.commandFailed", "stackTrace", error.message.orEmpty()), ProcessOutputTypes.STDERR)
                return@handleOnPool session.positionReached(Vic3SuspendContext(Vic3ExecutionStack(emptyList())))
            }
            val rawFrames = (trace!!["stackFrames"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            // Each frame on its own: one that cannot be mapped must not cost the others their source position.
            val frames = rawFrames.map { raw ->
                try {
                    frame(raw, resolve = true)
                } catch (e: Exception) {
                    thisLogger().error("Mapping a stack frame to a file failed: $raw", e)
                    print(Vic3Bundle.message("console.mappingFailed", e.message ?: e.javaClass.simpleName), ProcessOutputTypes.STDERR)
                    frame(raw, resolve = false)
                }
            }
            // Any halt ends a Run to Cursor, whether it reached the line or stopped somewhere first.
            runTarget?.let { run ->
                runTarget = null
                sync(run.vfsPath)
            }
            val context = Vic3SuspendContext(Vic3ExecutionStack(frames))
            val top = frames.firstOrNull()
            val hit = if (reason == "breakpoint" && top?.vfsPath != null) {
                breakpoints[top.vfsPath]?.let { list -> synchronized(list) { list.firstOrNull { it.line + 1 == top.line } } }
            } else null
            if (hit == null) return@handleOnPool session.positionReached(context)
            reportBreakpoint(hit, top!!, context)
        }
    }

    private fun frame(frame: JsonObject, resolve: Boolean): Vic3StackFrame {
        val vfsPath = (frame["source"] as? JsonObject)?.string("name")
        val line = frame.int("line") ?: 1
        val file = if (resolve) vfsPath?.let { sources.resolve(it) } else null
        val position = file?.let { XDebuggerUtil.getInstance().createPosition(it, line - 1) }
        return Vic3StackFrame(connection, frame.int("id") ?: 0, frame.string("name") ?: "?", vfsPath, line, position)
    }

    /** Evaluates the breakpoint's log expression at the halt, so the console shows it like any other debugger. */
    private fun reportBreakpoint(breakpoint: XLineBreakpoint<Vic3BreakpointProperties>, top: Vic3StackFrame, context: Vic3SuspendContext) {
        val expression = breakpoint.logExpressionObject?.expression?.takeIf { it.isNotBlank() }
        val suspend = { logged: String? -> if (!session.breakpointReached(breakpoint, logged, context)) resuming("continue") }
        if (expression == null) return suspend(null)
        val request = connection.request("evaluate", buildJsonObject {
            put("expression", JsonPrimitive(expression))
            put("frameId", JsonPrimitive(top.frameId))
            put("context", JsonPrimitive("watch"))
        })
        request.handleOnPool { result, error -> suspend(if (error != null) error.message else result?.string("result")) }
    }

    private fun print(text: String, type: com.intellij.openapi.util.Key<*>) {
        processHandler.notifyTextAvailable(if (text.endsWith("\n")) text else "$text\n", type)
    }

    // --- breakpoints

    private inner class LineBreakpointHandler :
        XBreakpointHandler<XLineBreakpoint<Vic3BreakpointProperties>>(Vic3LineBreakpointType::class.java) {

        override fun registerBreakpoint(breakpoint: XLineBreakpoint<Vic3BreakpointProperties>) {
            val path = VfsUtilCore.urlToPath(breakpoint.fileUrl)
            val status = FileStatus.of(Paths.get(path), target.content)
            when {
                status == null -> session.setBreakpointInvalid(breakpoint, Vic3Bundle.message("breakpoint.notLoaded"))
                status.overriddenBy != null -> session.setBreakpointInvalid(breakpoint, Vic3Bundle.message("breakpoint.overridden", status.overriddenBy))
                status.replacedByMod -> session.setBreakpointInvalid(breakpoint, Vic3Bundle.message("breakpoint.replaced"))
                status.loadedCopy == null -> session.setBreakpointInvalid(breakpoint, Vic3Bundle.message("breakpoint.missingInLoaded"))
                else -> {
                    val vfsPath = status.location.vfsPath
                    val list = breakpoints.computeIfAbsent(vfsPath) { mutableListOf() }
                    synchronized(list) { list.removeIf { it == breakpoint }; list += breakpoint }
                    if (status.matchesLoadedCopy) warnings.remove(vfsPath) else warnings[vfsPath] = Vic3Bundle.message("breakpoint.differs")
                    sync(vfsPath)
                }
            }
        }

        override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<Vic3BreakpointProperties>, temporary: Boolean) {
            for ((path, list) in breakpoints) {
                val removed = synchronized(list) { list.remove(breakpoint) }
                if (removed) sync(path)
            }
        }
    }

    /** Why breakpoints in a file may not line up, by VFS path: the open copy differs from the loaded one. */
    private val warnings = ConcurrentHashMap<String, String>()

    /**
     * Sends a file's complete breakpoint list, plus a pending Run to Cursor target in that file, then reports
     * each breakpoint's verification back to the gutter.
     */
    private fun sync(vfsPath: String): CompletableFuture<JsonObject> {
        val list = breakpoints[vfsPath] ?: mutableListOf()
        val sent = synchronized(list) { list.filter { toDap(it) != null } }
        val skipped = synchronized(list) { list.filter { toDap(it) == null } }
        skipped.forEach { session.setBreakpointInvalid(it, Vic3Bundle.message("breakpoint.nothingToDo")) }
        val dap = sent.mapNotNull(::toDap).toMutableList()
        runTarget?.takeIf { it.vfsPath == vfsPath && sent.none { bp -> bp.line + 1 == it.line } }?.let { run ->
            dap += buildJsonObject { put("line", JsonPrimitive(run.line)) } // after the real ones, so indexes still match
        }
        val warning = warnings[vfsPath]
        val request = connection.request("setBreakpoints", setBreakpointsArgs(vfsPath, JsonArray(dap)))
        request.handleOnPool { body, error ->
            if (error != null) {
                sent.forEach { session.setBreakpointInvalid(it, error.message) }
                return@handleOnPool
            }
            val results = (body!!["breakpoints"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            sent.forEachIndexed { index, breakpoint ->
                val result = results.getOrNull(index)
                when {
                    result?.bool("verified") != true -> session.setBreakpointInvalid(breakpoint, result?.string("message") ?: Vic3Bundle.message("breakpoint.notVerified"))
                    warning != null -> session.updateBreakpointPresentation(breakpoint, AllIcons.General.Warning, warning)
                    else -> session.setBreakpointVerified(breakpoint)
                }
            }
        }
        return request
    }

    /**
     * A suspending breakpoint halts the game. A non-suspending one becomes a logpoint, which the game
     * evaluates and prints without halting (§7); with nothing to print it would do nothing, so it is not sent.
     */
    private fun toDap(breakpoint: XLineBreakpoint<Vic3BreakpointProperties>): JsonObject? {
        val condition = breakpoint.conditionExpression?.expression?.takeIf { it.isNotBlank() }
        val logExpression = breakpoint.logExpressionObject?.expression?.takeIf { it.isNotBlank() }
        val suspends = breakpoint.suspendPolicy != SuspendPolicy.NONE
        val logMessage = if (suspends) null else listOfNotNull(
            if (breakpoint.isLogMessage) Vic3Bundle.message("breakpoint.hitMessage", breakpoint.shortFilePath, breakpoint.line + 1) else null,
            logExpression?.let { "{$it}" },
        ).joinToString(" ").takeIf { it.isNotEmpty() }
        if (!suspends && logMessage == null) return null
        return buildJsonObject {
            put("line", JsonPrimitive(breakpoint.line + 1))
            condition?.let { put("condition", JsonPrimitive(it)) }
            logMessage?.let { put("logMessage", JsonPrimitive(it)) }
        }
    }

    private fun setBreakpointsArgs(vfsPath: String, list: JsonArray) = buildJsonObject {
        put("source", buildJsonObject { put("path", JsonPrimitive(vfsPath)) })
        put("breakpoints", list)
    }

    private val XLineBreakpoint<*>.shortFilePath: String get() = fileUrl.substringAfterLast('/')

    companion object {
        private const val TIMEOUT_SECONDS = 5L
        private const val MAX_FRAMES = 100
    }
}

