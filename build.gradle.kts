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
