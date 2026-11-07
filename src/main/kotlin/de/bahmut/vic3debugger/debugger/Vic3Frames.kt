package de.bahmut.vic3debugger.debugger

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueGroup
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import de.bahmut.vic3debugger.Vic3Bundle
import de.bahmut.vic3debugger.Vic3Icons
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CompletableFuture
import javax.swing.Icon
import de.bahmut.vic3debugger.dap.DapConnection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull

/** One halt. Variable references are only valid until the game resumes, so nothing here outlives it. */
class Vic3SuspendContext(private val stack: Vic3ExecutionStack) : XSuspendContext() {
    override fun getActiveExecutionStack(): XExecutionStack = stack
    override fun getExecutionStacks(): Array<XExecutionStack> = arrayOf(stack)
}

/** The game reports a single script thread (docs/SCRIPT_DEBUGGER.md §3). */
class Vic3ExecutionStack(private val frames: List<Vic3StackFrame>) :
    XExecutionStack(Vic3Bundle.message("frames.thread"), AllIcons.Debugger.ThreadSuspended) {

    override fun getTopFrame(): XStackFrame? = frames.firstOrNull()

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
        container.addStackFrames(frames.drop(firstFrameIndex), true)
    }
}

/**
 * A script frame. Frames are named after the trigger or effect being evaluated, or `untyped` and
 * `script value`, and only carry a VFS-relative file name (§8, §9).
 */
class Vic3StackFrame(
    private val connection: DapConnection,
    val frameId: Int,
    private val name: String,
    val vfsPath: String?,
    val line: Int,
    private val position: XSourcePosition?,
) : XStackFrame() {

    override fun getSourcePosition(): XSourcePosition? = position

    override fun getEqualityObject(): Any = frameId

    override fun getEvaluator(): XDebuggerEvaluator = Vic3Evaluator(connection, frameId)

    override fun customizePresentation(component: ColoredTextContainer) {
        component.append(name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        component.append("  ${vfsPath ?: "?"}:$line", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        if (position == null) component.append("  ${Vic3Bundle.message("frames.unmapped")}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
        component.setIcon(AllIcons.Debugger.Frame)
    }

    /**
     * Shows the game's scopes: the "Scope" scope's entries (this, root, prev, top scope) at the top level,
     * and every other scope, such as "Saved Scopes", as a group.
     */
    override fun computeChildren(node: XCompositeNode) {
        connection.request("scopes", buildJsonObject { put("frameId", JsonPrimitive(frameId)) }).handleOnPool { body, error ->
            if (error != null) return@handleOnPool node.setErrorMessage(error.message ?: error.toString())
            val scopes = (body!!["scopes"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val primary = scopes.firstOrNull()
            val groups = XValueChildrenList()
            for (scope in scopes.drop(1)) {
                val ref = scope.int("variablesReference") ?: continue
                groups.addTopGroup(Vic3ScopeGroup(connection, scope.string("name") ?: "?", ref))
            }
            val primaryRef = primary?.int("variablesReference")
            if (primaryRef == null || primaryRef <= 0) {
                node.addChildren(groups, true)
                return@handleOnPool
            }
            loadVariables(connection, primaryRef) { values, failure ->
                if (failure != null) return@loadVariables node.setErrorMessage(failure)
                values.forEach { groups.add(it) }
                node.addChildren(groups, true)
            }
        }
    }
}

class Vic3ScopeGroup(private val connection: DapConnection, name: String, private val ref: Int) : XValueGroup(name) {
    override fun isAutoExpand(): Boolean = true
    override fun getIcon(): Icon = Vic3Icons.Scope
    override fun computeChildren(node: XCompositeNode) = loadVariables(connection, ref) { values, failure ->
        if (failure != null) return@loadVariables node.setErrorMessage(failure)
        node.addChildren(XValueChildrenList().apply { values.forEach { add(it) } }, true)
    }
}

/**
 * A variable as the game renders it: a script variable's value, or an object such as `Country Great Britain (3)`.
 *
 * Some values span lines: `top scope` lists saved event targets, and a trigger's evaluation result is a reason
 * tree. Those show their first line, a View link with the whole text, and one child per further line.
 */
class Vic3Value(
    private val connection: DapConnection,
    name: String,
    private val value: String,
    private val ref: Int,
) : XNamedValue(name) {

    private val lines = value.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
    private val isMultiLine = lines.size > 1

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        val icon = Vic3Icons.of(ValueKind.of(name, value, ref > 0))
        if (!isMultiLine) return node.setPresentation(icon, null, value, ref > 0)
        node.setPresentation(icon, null, "${lines.first()} …", true)
        node.setFullValueEvaluator(object : XFullValueEvaluator(Vic3Bundle.message("value.viewAll", lines.size)) {
            override fun startEvaluation(callback: XFullValueEvaluationCallback) = callback.evaluated(lines.joinToString("\n"))
        })
    }

    override fun computeChildren(node: XCompositeNode) {
        if (ref <= 0) {
            val children = XValueChildrenList()
            if (isMultiLine) lines.drop(1).forEach { children.add(Vic3LineValue(it)) }
            return node.addChildren(children, true)
        }
        loadVariables(connection, ref) { values, failure ->
            if (failure != null) return@loadVariables node.setErrorMessage(failure)
            node.addChildren(XValueChildrenList().apply { values.forEach { add(it) } }, true)
        }
    }
}

/**
 * One line of a multi-line value, shown as text only. A reason tree line such as
 * `No: has_modifier; MODIFIER_NAME: …` splits into the verdict and the rest.
 */
class Vic3LineValue(private val line: String) : XNamedValue(line.substringBefore(':', "").takeIf { it in VERDICTS } ?: "") {

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        val text = if (name.isEmpty()) line else line.substringAfter(':').trim()
        val icon = when (name) {
            "Yes" -> AllIcons.General.InspectionsOK
            "No" -> AllIcons.General.Error
            else -> null
        }
        node.setPresentation(icon, object : XValuePresentation() {
            override fun getSeparator(): String = if (name.isEmpty()) "" else ": "
            override fun renderValue(renderer: XValueTextRenderer) = renderer.renderValue(text)
        }, false)
    }

    private companion object {
        val VERDICTS = setOf("Yes", "No")
    }
}

/** Variable references are allocated as things are expanded, so they are always fetched fresh (§8). */
internal fun loadVariables(connection: DapConnection, ref: Int, done: (List<Vic3Value>, String?) -> Unit) {
    connection.request("variables", buildJsonObject { put("variablesReference", JsonPrimitive(ref)) }).handleOnPool { body, error ->
        if (error != null) return@handleOnPool done(emptyList(), error.message ?: error.toString())
        val values = (body!!["variables"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map {
            Vic3Value(connection, it.string("name") ?: "?", it.string("value") ?: "", it.int("variablesReference") ?: 0)
        }
        done(values, null)
    }
}

/**
 * Handles a response on an IDE pool thread instead of the connection's reader thread, and logs anything that
 * throws: exceptions in CompletableFuture callbacks otherwise vanish without a trace.
 */
internal fun <T> CompletableFuture<T>.handleOnPool(handler: (T?, Throwable?) -> Unit) {
    whenCompleteAsync({ result, error ->
        try {
            handler(result, error)
        } catch (e: Throwable) {
            logger<Vic3StackFrame>().error("Handling a response from the game failed", e)
        }
    }, AppExecutorUtil.getAppExecutorService())
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
