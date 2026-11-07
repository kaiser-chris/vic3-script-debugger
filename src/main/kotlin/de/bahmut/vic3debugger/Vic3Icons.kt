package de.bahmut.vic3debugger

import com.intellij.openapi.util.IconLoader
import de.bahmut.vic3debugger.debugger.ValueKind
import javax.swing.Icon

/** Plugin icons. Each SVG has a `_dark` sibling, which the IDE picks for dark themes. */
object Vic3Icons {
    private fun load(name: String): Icon = IconLoader.getIcon("/icons/$name.svg", Vic3Icons::class.java)

    /** The plugin icon: the run configuration and the editor indicator. META-INF/pluginIcon.svg is its 40 px form. */
    @JvmField val Plugin = load("plugin")

    @JvmField val Scope = load("value_scope")
    @JvmField val Flag = load("value_flag")
    @JvmField val Boolean = load("value_boolean")
    @JvmField val Number = load("value_number")
    @JvmField val List = load("value_list")
    @JvmField val None = load("value_none")
    @JvmField val Text = load("value_text")

    fun of(kind: ValueKind): Icon = when (kind) {
        ValueKind.SCOPE -> Scope
        ValueKind.FLAG -> Flag
        ValueKind.BOOLEAN -> Boolean
        ValueKind.NUMBER -> Number
        ValueKind.LIST -> List
        ValueKind.NONE -> None
        ValueKind.TEXT -> Text
    }
}
