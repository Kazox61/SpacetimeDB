# Community Kotlin SDK

This fork targets JVM desktop, Android and iOS. It is not an official Clockwork Labs
release. Existing Kotlin packages and the legacy Gradle plugin ID remain unchanged
for source compatibility; published SDK coordinates use the community namespace.

## Use it from source

Build the CLI from this fork (an installed upstream CLI does not provide Kotlin
codegen):

```sh
cargo build --locked -p spacetimedb-cli
```

In a consumer's `settings.gradle.kts`, include the plugin and substitute the SDK:

```kotlin
pluginManagement {
    includeBuild("/absolute/path/to/SpacetimeDB/sdks/kotlin/spacetimedb-gradle-plugin")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
includeBuild("/absolute/path/to/SpacetimeDB/sdks/kotlin")
```

In the consumer's module:

```kotlin
plugins {
    kotlin("multiplatform")
    id("io.github.kazox61.spacetimedb")
}
spacetimedb {
    cli.set(file("/absolute/path/to/SpacetimeDB/target/debug/spacetimedb-cli"))
    modulePath.set(file("../spacetimedb"))
}
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("io.github.kazox61.spacetimedb:spacetimedb-sdk:0.1.0-dev")
    }
}
```

Supply Ktor's OkHttp engine on Android/JVM and Darwin on iOS, with matching Ktor
versions. The application owns its `HttpClient` and must close it at final shutdown.
Do not close a shared client just because an individual connection disconnects.

## Local Maven artifacts

From `sdks/kotlin`:

```sh
./gradlew :spacetimedb-sdk:publishToMavenLocal --no-configuration-cache
./gradlew -p spacetimedb-gradle-plugin publishToMavenLocal --no-configuration-cache
```

Then add `mavenLocal()` to the consumer's dependency and plugin repositories,
remove the `includeBuild` entries, and specify plugin version `0.1.0-dev`.
The SDK is `io.github.kazox61.spacetimedb:spacetimedb-sdk:0.1.0-dev`.
Use an Apple Silicon Mac to publish all Apple targets. Linux can publish JVM,
Android, and common metadata, but cannot compile Apple targets.

Verify an independent consumer (no project dependency or composite build):

```sh
./gradlew -p examples/local-consumer run --no-configuration-cache
```

This uses only Maven-local artifacts plus the local Kotlin-capable CLI, generates
typed bindings from the sample schema and compiles them in a separate project.

Versions/group can be overridden using `-PkotlinSdkVersion=...` and
`-PkotlinSdkGroup=...` (pass the same values to the plugin's separate build).

## Remote publishing

No public Maven release is made by these commands unless you explicitly configure
a remote repository. To publish to a Maven repository you control, supply
`-PkotlinSdkRepository=https://YOUR_REPOSITORY` and `MAVEN_USERNAME` /
`MAVEN_PASSWORD` in your environment, then run the corresponding
`publishAllPublicationsToCommunityRepository` tasks in both builds.
Never put credentials in tracked Gradle files.

Maven Central onboarding, namespace verification, signing and release approval
are separate steps. This configuration is not a claim that the namespace has
been verified or that a Maven Central release already exists.
The legacy `com.clockworklabs.spacetimedb` plugin marker is retained for local
compatibility; publish only the community marker and implementation when your
remote repository enforces plugin namespace ownership.

## Mobile lifecycle and identity

`ConnectionSession` is opt-in; plain `DbConnection` behavior is unchanged.

```kotlin
val session = ConnectionSession(
    tokenStore = mySecureTokenStore,
    builder = {
        DbConnection.Builder()
            .withHttpClient(httpClient)
            .withUri("https://your-server.example")
            .withRequireSecureTransport()
            .withDatabaseName("your-database")
            .withModuleBindings()
    },
    onConnected = { conn ->
        // Re-register row callbacks and subscriptions on EVERY fresh connection.
        conn.subscriptionBuilder().subscribe(listOf("SELECT * FROM message"))
    },
)
// Launch in an application-owned scope, not an unbounded global scope.
// appForeground is a StateFlow<Boolean> driven by the application's lifecycle.
launch { session.run(appForeground) }
```

`false` pauses and disconnects; `true` reconnects. Cancelling the owning coroutine
releases the active connection and cancels pending retries. A successful handshake
resets the bounded exponential backoff. Permanent validation, credential-storage,
or setup failures surface as `SessionState.Failed`; the application decides whether
and when to restart the session. Observe `session.state` for the current connection.
Do not retain table handles across reconnects: each connection has a fresh cache.

Implement `TokenStore.load/save` with app-owned secure persistence: iOS Keychain or
Android Keystore-backed encryption. The SDK deliberately does not write bearer
tokens to plaintext preferences/files. Tokens are saved before the new connection
is exposed; storage errors stop the session instead of silently creating a new
identity. Scope credentials by server and database/account. Logout should clear
the application's store and cancel the running session before starting another.

Drive foreground state from Android lifecycle events and iOS scene/app lifecycle
notifications in the app layer. This library does not request background execution
privileges or imply that the OS permits a persistent background socket.

Reducer/procedure calls are never automatically replayed. A dropped response does
not prove that a transaction failed; use application-level idempotency for retries.

Production should use `https://` or `wss://` with normal platform certificate
validation. Do not install trust-all TLS handlers. Local simulator tests use
plaintext loopback only; those tests do not certify a production TLS deployment.

## Checks

```sh
./gradlew :spacetimedb-sdk:jvmTest --no-configuration-cache
python3 scripts/live_tests.py jvm
python3 scripts/live_tests.py ios --ios-id SIMULATOR_UDID
python3 scripts/live_tests.py android
```

Live tests require the locally built CLI/server. Android requires a running
emulator and `ANDROID_HOME`; iOS requires Xcode and Apple Silicon. Each runner
creates isolated keys, CLI config and database data, refuses an occupied port,
and stops its own server on exit. See [platform smoke tests](platform-smoke-tests/README.md).

`.github/workflows/kotlin-sdk.yml` runs JVM/codegen/live tests and simulator tests
on GitHub-hosted runners, rather than upstream's private self-hosted runners.
Remote CI results are separate from local test results; runner image changes or
dependency download failures may still need investigation.
