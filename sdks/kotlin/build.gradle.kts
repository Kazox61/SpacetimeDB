plugins {
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        providers.gradleProperty("iosSimulatorId").orNull?.let { device.set(it) }
    }
    afterEvaluate {
        plugins.withId("org.jetbrains.kotlin.multiplatform") {
            extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
                jvmToolchain(21)
            }
        }
        plugins.withId("org.jetbrains.kotlin.jvm") {
            extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmExtension> {
                jvmToolchain(21)
            }
        }
    }
}
