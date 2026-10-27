import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)

    // The converter lives in ./javacli, which builds standalone with Maven; the plugin
    // compiles the same sources (see sourceSets) and needs its JSON dependency.
    implementation(libs.jackson.databind)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.6.1")
        testFramework(TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.modules.json")
    }
}

// Compile the shared .http -> Postman converter into the plugin, so the
// "Convert to Postman Format File" action can run it in-process.
sourceSets {
    main {
        java.srcDir("javacli/src/main/java")
    }
}
