plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.spacetimedb)
}

spacetimedb {
    modulePath.set(layout.projectDirectory.dir("../integration-tests/spacetimedb"))
    providers.environmentVariable("SPACETIMEDB_CLI").orNull?.let { cli.set(file(it)) }
}

kotlin {
    android {
        namespace = "com.clockworklabs.spacetimedb.smoketest"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":spacetimedb-sdk"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.core)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation("androidx.test:runner:1.7.0")
        }
        iosTest.dependencies {
            implementation("io.ktor:ktor-client-darwin:${libs.versions.ktor.get()}")
        }
        all {
            languageSettings.optIn("com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.InternalSpacetimeApi")
        }
    }
}
