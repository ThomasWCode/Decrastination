plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.thomaswcode.decrastination"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.thomaswcode.decrastination"
        minSdk = 26
        targetSdk = 36
        versionCode = 104
        versionName = "5.2.0"
    }

    signingConfigs {
        // A copy of the Teams Assignments widget's committed debug key. Sharing its key is what
        // grants this app the widget's signature-level READ_ASSIGNMENTS permission (see
        // docs/spec/sources.md); being committed, it also lets APKs built by CI and on any
        // machine install over each other. It only signs debug builds of this sideloaded app.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // The Anthropic SDK's Apache HTTP jars each carry these; nothing reads them at run time.
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // core's and the engine's code linted with the app's, against its minSdk (they're plain Kotlin, with Android's lint applied).
    lint {
        checkDependencies = true
    }
}

tasks.withType<Test>().configureEach {
    // core's build, which ArchitectureTest holds free of Android and of the app: a change to it alone runs the tests again.
    inputs.file(layout.projectDirectory.file("../core/build.gradle.kts")).withPropertyName("coreBuild").withPathSensitivity(PathSensitivity.RELATIVE)
    // The engine's, held the same way.
    inputs.file(layout.projectDirectory.file("../engine/build.gradle.kts")).withPropertyName("engineBuild").withPathSensitivity(PathSensitivity.RELATIVE)
    // core's, the engine's and the server's own tests run with the app's, so one command (CI's) runs every test.
    if (name == "testDebugUnitTest") dependsOn(":core:test", ":engine:test", ":server:test")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.zxing.core)
    implementation(libs.anthropic.java)
    // Every Jackson module the SDK brings at one patched version (gradle/libs.versions.toml).
    implementation(platform(libs.jackson.bom))

    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":engine")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
