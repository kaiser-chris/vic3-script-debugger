package de.bahmut.vic3debugger.debugger

import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import de.bahmut.vic3debugger.dap.DapConnection
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Sends expressions to the game's `evaluate` in the frame's context. Script values return a number and
 * triggers return a verdict with a reason tree (§5).
 *
 * Expressions are sent as typed. Malformed input has crashed the game (§5), so there is deliberately no
 * hover evaluation: only explicit Evaluate and watches reach the game.
 */
class Vic3Evaluator(private val connection: DapConnection, private val frameId: Int) : XDebuggerEvaluator() {

    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) {
        val arguments = buildJsonObject {
            put("expression", JsonPrimitive(expression))
            put("frameId", JsonPrimitive(frameId))
            put("context", JsonPrimitive("watch"))
        }
        connection.request("evaluate", arguments).handleOnPool { body, error ->
            if (error != null) return@handleOnPool callback.errorOccurred(error.message?.removePrefix("evaluate failed: ") ?: error.toString())
            callback.evaluated(Vic3Value(connection, "result", body!!.string("result") ?: "", body.int("variablesReference") ?: 0))
        }
    }
}
