plugins {
    alias(libs.plugins.kotlin)
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion").get())
        // The XDebugger API: breakpoints, debug sessions, frames and values.
        bundledModule("intellij.platform.debugger")

        pluginVerifier()
        zipSigner()
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

intellijPlatform {
    // Instrumentation adds runtime @NotNull checks to Java code and compiles .form files. This plugin is
    // Kotlin-only with no forms, so it has nothing to instrument.
    instrumentCode = false

    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
        // HTML for the Marketplace's "What's New". The release workflow passes the commits since the last release.
        changeNotes = providers.environmentVariable("PLUGIN_CHANGE_NOTES").filter(String::isNotBlank)
    }

    // Optional. Without a key signPlugin is skipped and publishPlugin uploads the unsigned zip, which the
    // Marketplace then signs with its own certificate.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN").filter(String::isNotBlank)
        privateKey = providers.environmentVariable("PRIVATE_KEY").filter(String::isNotBlank)
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD").filter(String::isNotBlank)
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN").filter(String::isNotBlank)
    }

    // gradlew verifyPlugin: binary compatibility against the IDE this targets.
    pluginVerification {
        ides {
            current()
        }
    }
}

tasks.test {
    useJUnitPlatform {
        // Tests tagged "live" talk to a running game. Opt in with: gradlew test -Plive
        if (!providers.gradleProperty("live").isPresent) excludeTags("live")
    }
}
