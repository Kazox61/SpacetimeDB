plugins {
    alias(libs.plugins.kotlinJvm)
    `java-gradle-plugin`
    `maven-publish`
}

group = providers.gradleProperty("kotlinSdkGroup").getOrElse("io.github.kazox61.spacetimedb")
version = providers.gradleProperty("kotlinSdkVersion").getOrElse("0.1.0-dev")

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
}

gradlePlugin {
    plugins {
        create("spacetimedb") {
            id = "com.clockworklabs.spacetimedb"
            implementationClass = "com.clockworklabs.spacetimedb.SpacetimeDbPlugin"
        }
        create("communitySpacetimedb") {
            id = "io.github.kazox61.spacetimedb"
            implementationClass = "com.clockworklabs.spacetimedb.SpacetimeDbPlugin"
        }
    }
}

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
}
