// Root build file for the OwnTV core library. Both modules are published together under one
// version — two artifacts that must be used as a pair should not have independent numbers, or the
// apps end up on combinations nobody ever built.
plugins {
    // com.android.library ships inside AGP and is already on the build classpath by the time a
    // module asks for it by version, so it is declared here and applied without a version there.
    alias(libs.plugins.android.library) apply false
    // Kotlin comes from AGP 9's built-in Kotlin support. Compose compiler is pinned to that Kotlin
    // version; KSP supports built-in Kotlin, so no kotlin-android plugin is needed.
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
}

extra["coreVersion"] = "1.0.72-rayge.2"
