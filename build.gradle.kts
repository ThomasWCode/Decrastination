// The same toolchain as the Teams Assignments widget. AGP 9 compiles Kotlin itself ("built-in
// Kotlin"), so there is no kotlin-android plugin here; the Compose compiler plugin still comes
// from the Kotlin Gradle plugin, as does the JVM one :core is built with.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
}
