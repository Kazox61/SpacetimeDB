plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    `maven-publish`
}

group = providers.gradleProperty("kotlinSdkGroup").getOrElse("io.github.kazox61.spacetimedb")
version = providers.gradleProperty("kotlinSdkVersion").getOrElse("0.1.0-dev")

// Lint annotation extraction pulls lint-gradle from Google Maven and only feeds lint
// analysis, not the published artifacts; skip it so publishing survives an unavailable
// download and behaves identically on hosts without network access to dl.google.com.
// Disabling alone is not enough: storing the configuration cache still serializes the
// task's lint classpath, which resolves lint-gradle, so keep the task out of the cache too.
tasks.matching { it.name == "extractAndroidMainAnnotations" }.configureEach {
    enabled = false
    notCompatibleWithConfigurationCache("Serializing the lint classpath downloads lint-gradle")
}
// The AAR jar task requires the skipped task's typedef recipe; an empty one strips nothing.
val emptyTypedefRecipe = tasks.register("emptyAndroidMainTypedefRecipe") {
    val recipe = layout.buildDirectory.file("intermediates/annotations_typedef_file/androidMain/extractAndroidMainAnnotations/typedefs.txt")
    outputs.file(recipe)
    doLast { recipe.get().asFile.writeText("") }
}
tasks.matching { it.name == "syncAndroidMainLibJars" }.configureEach { dependsOn(emptyTypedefRecipe) }

publishing {
    repositories {
        maven {
            name = "community"
            url = uri(providers.gradleProperty("kotlinSdkRepository").getOrElse(layout.buildDirectory.dir("repository").get().asFile.toURI().toString()))
            if (providers.gradleProperty("kotlinSdkRepository").orNull?.startsWith("https://") == true) {
                credentials {
                    username = providers.environmentVariable("MAVEN_USERNAME").orNull
                    password = providers.environmentVariable("MAVEN_PASSWORD").orNull
                }
            }
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("SpacetimeDB Community Kotlin SDK")
            description.set("Community-maintained Kotlin Multiplatform client for SpacetimeDB")
            url.set("https://github.com/Kazox61/SpacetimeDB")
            scm { url.set("https://github.com/Kazox61/SpacetimeDB") }
        }
    }
}

kotlin {
    explicitApi()

    android {
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        namespace = "com.clockworklabs.spacetimedb_kotlin_sdk.shared_client"
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SpacetimeDBSdk"
            isStatic = true
        }
    }

    jvm()

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.atomicfu)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
        }

        jvmMain.dependencies {
            implementation(libs.brotli.dec)
        }

        androidMain.dependencies {
            implementation(libs.brotli.dec)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmTest.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        all {
            languageSettings {
                optIn("kotlin.uuid.ExperimentalUuidApi")
                optIn("com.clockworklabs.spacetimedb_kotlin_sdk.shared_client.InternalSpacetimeApi")
            }
        }

        compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}
