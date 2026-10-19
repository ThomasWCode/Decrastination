// The app's pure rules and domain types (docs/plan_v4/02-target-architecture.md §The rules): plain
// Kotlin, with no Android and nothing of the app, so the compiler keeps core pure. Its tests run with
// the app's (the app's testDebugUnitTest depends on this module's test); one alone:
// ./gradlew :core:test --tests '*SlotterTest*'
// Fixtures, the helpers every test shares, are its test fixtures, which the app's tests use too.
plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    // The repository's fixtures (Fixtures.file): a change to them alone runs the tests again.
    inputs.dir(layout.projectDirectory.dir("../fixtures")).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    // Its types are stored as JSON (@Serializable), their serializers part of what the app calls.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
