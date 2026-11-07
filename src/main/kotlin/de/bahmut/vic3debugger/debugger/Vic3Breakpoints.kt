package de.bahmut.vic3debugger.debugger

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import de.bahmut.vic3debugger.Vic3Bundle
import de.bahmut.vic3debugger.mapping.FileMapper

/** Line breakpoints in Jomini script: any line of a .txt or .gui file inside a mod root or a game folder. */
class Vic3LineBreakpointType : XLineBreakpointType<Vic3BreakpointProperties>(ID, Vic3Bundle.message("breakpoint.type.title")) {

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean {
        val path = file.fileSystem.getNioPath(file) ?: return false
        return FileMapper.isScriptFile(path) && FileMapper.originOf(path) != null
    }

    override fun createBreakpointProperties(file: VirtualFile, line: Int): Vic3BreakpointProperties = Vic3BreakpointProperties()

    override fun getEditorsProvider(
        breakpoint: com.intellij.xdebugger.breakpoints.XLineBreakpoint<Vic3BreakpointProperties>,
        project: Project,
    ): XDebuggerEditorsProvider = Vic3EditorsProvider

    companion object {
        const val ID = "vic3-script-line"
    }
}

/** No per-breakpoint settings beyond what every line breakpoint has: condition, log expression, suspend policy. */
class Vic3BreakpointProperties : XBreakpointProperties<Vic3BreakpointProperties>() {
    override fun getState(): Vic3BreakpointProperties = this
    override fun loadState(state: Vic3BreakpointProperties) = Unit
}

/**
 * Conditions, log expressions and Evaluate are Jomini script, which this plugin does not parse yet, so
 * they are edited as plain text and sent to the game as typed.
 */
object Vic3EditorsProvider : XDebuggerEditorsProvider() {
    override fun getFileType(): FileType = PlainTextFileType.INSTANCE

    // The base class leaves document creation abstract at runtime; the other overloads delegate to this one.
    override fun createDocument(
        project: Project,
        expression: XExpression,
        sourcePosition: XSourcePosition?,
        mode: EvaluationMode,
        modeName: String?,
    ): Document = EditorFactory.getInstance().createDocument(expression.expression)
}
