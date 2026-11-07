package de.bahmut.vic3debugger.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import de.bahmut.vic3debugger.Vic3Bundle
import de.bahmut.vic3debugger.discovery.LaunchOptions

/** The "Victoria 3 Script Debugger" entry in Run/Debug Configurations: attaches to a running game, like Remote JVM Debug. */
class Vic3ConfigurationType : SimpleConfigurationType(
    ID,
    Vic3Bundle.message("configuration.type.name"),
    Vic3Bundle.message("configuration.type.description"),
    NotNullLazyValue.createValue { AllIcons.Actions.StartDebugger },
) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        Vic3RunConfiguration(project, this, Vic3Bundle.message("configuration.type.name"))

    override fun getOptionsClass(): Class<Vic3RunConfigurationOptions> = Vic3RunConfigurationOptions::class.java

    companion object {
        const val ID = "Vic3ScriptDebugger"
    }
}

class Vic3RunConfigurationOptions : RunConfigurationOptions() {
    /** Find the game from running processes and its `-script_debugger` option, instead of using [port]. */
    var autoDetect by property(true)
    var port by property(LaunchOptions.DEFAULT_PORT)
    /** The game's user data folder, holding `content_load.json`. Empty means the standard location. */
    var userDataFolder by string("")
}

class Vic3RunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<Vic3RunConfigurationOptions>(project, factory, name) {

    public override fun getOptions(): Vic3RunConfigurationOptions = super.getOptions() as Vic3RunConfigurationOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = Vic3SettingsEditor()

    override fun checkConfiguration() {
        if (!options.autoDetect && options.port !in 1..65535) {
            throw RuntimeConfigurationError(Vic3Bundle.message("configuration.error.port"))
        }
    }

    /** Only the debug runner executes this configuration; the state just carries it there. */
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        RunProfileState { _, _ -> null }
}
