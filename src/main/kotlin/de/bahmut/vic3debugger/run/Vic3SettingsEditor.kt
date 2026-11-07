package de.bahmut.vic3debugger.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import de.bahmut.vic3debugger.Vic3Bundle
import de.bahmut.vic3debugger.discovery.LaunchOptions
import de.bahmut.vic3debugger.mapping.UserDataFolder
import javax.swing.JComponent

class Vic3SettingsEditor : SettingsEditor<Vic3RunConfiguration>() {
    private val autoDetect = JBCheckBox(Vic3Bundle.message("configuration.autoDetect"))
    private val port = JBIntSpinner(LaunchOptions.DEFAULT_PORT, 1, 65535)
    private val userDataFolder = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle(Vic3Bundle.message("configuration.userDataFolder.browse")))
        (textField as? com.intellij.ui.components.JBTextField)?.emptyText
            ?.setText(UserDataFolder.default()?.toString() ?: Vic3Bundle.message("configuration.userDataFolder.unknown"))
    }

    init {
        autoDetect.addActionListener { port.isEnabled = !autoDetect.isSelected }
    }

    override fun resetEditorFrom(configuration: Vic3RunConfiguration) {
        val options = configuration.options
        autoDetect.isSelected = options.autoDetect
        port.number = options.port
        port.isEnabled = !options.autoDetect
        userDataFolder.text = options.userDataFolder.orEmpty()
    }

    override fun applyEditorTo(configuration: Vic3RunConfiguration) {
        val options = configuration.options
        options.autoDetect = autoDetect.isSelected
        options.port = port.number
        options.userDataFolder = userDataFolder.text.trim()
    }

    override fun createEditor(): JComponent = panel {
        row { cell(autoDetect).comment(Vic3Bundle.message("configuration.autoDetect.comment")) }
        row(Vic3Bundle.message("configuration.port")) {
            cell(port).comment(Vic3Bundle.message("configuration.port.comment", LaunchOptions.DEFAULT_PORT))
        }
        row(Vic3Bundle.message("configuration.userDataFolder")) {
            cell(userDataFolder).align(AlignX.FILL).comment(Vic3Bundle.message("configuration.userDataFolder.comment"))
        }
    }
}
