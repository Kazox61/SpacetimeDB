import java.util.Properties

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.spacetimedb)
}

// Generates bindings for the TypeScript test module, which mounts a submodule as `myLib`.
// Building it needs the repository's pnpm workspace installed, so this project is opt-in.
spacetimedb {
    modulePath.set(rootProject.layout.projectDirectory.dir("../../modules/module-test-ts"))
    val localProps = rootProject.file("local.properties").let { f ->
        if (f.exists()) Properties().also { it.load(f.inputStream()) } else null
    }
    (providers.environmentVariable("SPACETIMEDB_CLI").orNull
        ?: localProps?.getProperty("spacetimedb.cli"))
        ?.let { cli.set(file(it)) }
}

dependencies {
    implementation(project(":spacetimedb-sdk"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.client.okhttp)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}
