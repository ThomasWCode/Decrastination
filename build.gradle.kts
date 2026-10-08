// The same toolchain as the Teams Assignments widget. AGP 9 compiles Kotlin itself ("built-in
// Kotlin"), so there is no kotlin-android plugin here; the Compose compiler plugin still comes
// from the Kotlin Gradle plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
