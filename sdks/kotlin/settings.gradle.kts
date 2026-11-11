@file:Suppress("UnstableApiUsage")

rootProject.name = "SpacetimedbKotlinSdk"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("spacetimedb-gradle-plugin")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":spacetimedb-sdk")
include(":integration-tests")
include(":codegen-tests")

// Device tests need a running local server and are deliberately opt-in.
if (providers.gradleProperty("platformSmokeTests").isPresent) {
    include(":platform-smoke-tests")
}

// Generating bindings from the TypeScript submodule fixture needs Node and pnpm, so this is opt-in.
if (providers.gradleProperty("submoduleCodegenTests").isPresent) {
    include(":submodule-codegen-tests")
}
