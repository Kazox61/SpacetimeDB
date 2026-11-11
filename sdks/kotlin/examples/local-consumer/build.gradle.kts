plugins {
    kotlin("jvm") version "2.3.10"
    application
    id("io.github.kazox61.spacetimedb") version "0.1.0-dev"
}

repositories {
    mavenLocal()
    mavenCentral()
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation("io.github.kazox61.spacetimedb:spacetimedb-sdk:0.1.0-dev")
    implementation("io.ktor:ktor-client-core:3.4.1")
}

spacetimedb {
    cli.set(file(providers.environmentVariable("SPACETIMEDB_CLI")
        .getOrElse("../../../../target/debug/spacetimedb-cli")))
    modulePath.set(layout.projectDirectory.dir("../../codegen-tests/spacetimedb"))
}

application { mainClass.set("MainKt") }
