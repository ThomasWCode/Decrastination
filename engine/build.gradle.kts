// What both the app and the server run (docs/plan_web/architecture.md §The modules): every line of the
// app that needs nothing of Android, in plain Kotlin, so the compiler keeps it so. Where it needs a
// platform it takes a port (ports/), which the app implements with Android. Its tests run with the app's
// (the app's testDebugUnitTest depends on this module's test); one alone:
// ./gradlew :engine:test --tests '*StoresTest*'
// Android's lint, the one thing of Android here, lets the app's lintDebug check this code as its own
// (checkDependencies): against the app's minSdk, as a JDK call Android lacks would compile and then fail.
plugins {
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.lint)
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

// The golden prompts written again (AR-01, GoldenPromptsTest): ./gradlew :engine:test -PupdateGolden=true --tests '*GoldenPromptsTest*'
// The scored eval run through the API (AR-02, EvalRunTest, docs/development.md), only when asked:
// ./gradlew :engine:test --tests '*EvalRunTest*' -PrunEval=true [-Peffort=high]  (the chat's set: -PrunEval=chat --rerun)
// Every schema compiled by the API, after a schema changes (SchemaCompileTest), only when asked:
// ./gradlew :engine:test --tests '*SchemaCompileTest*' -PcheckSchemas=true
// docs/spec/felix-inputs.md written again from what the app sends (FelixInputsDocTest):
// ./gradlew :engine:test --tests '*FelixInputsDocTest*' -PupdateDocs=true
// The plan snapshot written again (PlanSnapshotTest), only on purpose:
// ./gradlew :engine:test --tests '*PlanSnapshotTest*' -PupdateSnapshots=true
// A phone backup's stores read and migrated (RehearsalTest), as scripts/rehearse.sh runs it:
// ./gradlew :engine:test --tests '*RehearsalTest*' --rerun -PrehearseFrom=<the unpacked files/ folder>
tasks.withType<Test>().configureEach {
    systemProperty("updateGolden", providers.gradleProperty("updateGolden").getOrElse("false"))
    systemProperty("runEval", providers.gradleProperty("runEval").getOrElse("false"))
    systemProperty("checkSchemas", providers.gradleProperty("checkSchemas").getOrElse("false"))
    systemProperty("effort", providers.gradleProperty("effort").getOrElse(""))
    systemProperty("updateDocs", providers.gradleProperty("updateDocs").getOrElse("false"))
    systemProperty("updateSnapshots", providers.gradleProperty("updateSnapshots").getOrElse("false"))
    systemProperty("rehearseFrom", providers.gradleProperty("rehearseFrom").getOrElse(""))
    // The stores' shapes and the plan's snapshot: a change to them alone runs the tests again.
    inputs.dir(layout.projectDirectory.dir("../fixtures")).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    // The doc FelixInputsDocTest holds to the code: a change to it alone runs the tests again.
    inputs.file(layout.projectDirectory.file("../docs/spec/felix-inputs.md")).withPropertyName("felixInputsDoc").withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.core)
    api(libs.anthropic.java)
    // Every Jackson module the SDK brings at one patched version (gradle/libs.versions.toml).
    api(platform(libs.jackson.bom))

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
