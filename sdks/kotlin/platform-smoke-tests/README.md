# Simulator networking smoke tests

Opt-in tests for the SDK's real Android (OkHttp) and iOS (Darwin) WebSocket
transports. Both run the same scenario with generated common Kotlin bindings:
connect, subscribe, insert a message through a reducer, observe the cached row,
delete it through a reducer, and observe its removal. Network waits time out
after 30 seconds. No physical devices are required.

The module is included only when `-PplatformSmokeTests` is supplied, so ordinary
SDK builds and unit tests do not require emulators or a running server.

## Local server

From the repository root, build the Kotlin-capable CLI and stock server:

```sh
cargo build -p spacetimedb-cli -p spacetimedb-standalone
export SPACETIMEDB_CLI="$PWD/target/debug/spacetimedb-cli"
export SMOKE_DIR="$(mktemp -d)"
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$SMOKE_DIR/private.pem"
openssl pkey -in "$SMOKE_DIR/private.pem" -pubout -out "$SMOKE_DIR/public.pem"
```

Start an isolated server (leave it running in a separate terminal):

```sh
./target/debug/spacetimedb-standalone start \
  --listen-addr 127.0.0.1:57875 --data-dir "$SMOKE_DIR/data" \
  --jwt-priv-key-path "$SMOKE_DIR/private.pem" \
  --jwt-pub-key-path "$SMOKE_DIR/public.pem"
```

Publish the existing integration-test module once the server is listening:

```sh
"$SPACETIMEDB_CLI" --config-path "$SMOKE_DIR/config.toml" publish \
  --server http://127.0.0.1:57875 --no-config -y \
  --module-path sdks/kotlin/integration-tests/spacetimedb kotlin-integration-test
```

This uses a separate data directory, authentication keys, CLI config, and port;
it does not replace an installed CLI or use an existing database.

## Run on simulators

Start an Android AVD through Android Studio Device Manager or `emulator -avd NAME`.
The test uses `10.0.2.2` to reach the Mac host; iOS uses `127.0.0.1`.
Ensure `ANDROID_HOME` points to the Android SDK and Xcode is selected.

List available iOS simulators with `xcrun simctl list devices available`, then run
from `sdks/kotlin` (with `SPACETIMEDB_CLI` still exported):

```sh
./gradlew -PplatformSmokeTests -PiosSimulatorId=SIMULATOR_UDID \
  :platform-smoke-tests:iosSimulatorArm64Test \
  :platform-smoke-tests:connectedAndroidDeviceTest --console=plain --no-configuration-cache
```

Run either task separately to test just one platform. The iOS target requires an
Apple Silicon Mac. Android tests permit cleartext traffic only in this test
module; this does not change the SDK or production app security settings.

Stop the isolated server with Ctrl+C when finished. If Gradle reports a test
up-to-date when you need a new network run, add `--rerun-tasks`.

### Android test-launcher download failures

If Google's Maven repository times out downloading Gradle's Unified Test Platform
launcher, build the test APK and run its instrumentation directly instead:

```sh
./gradlew -PplatformSmokeTests :platform-smoke-tests:assembleAndroidDeviceTest \
  --no-configuration-cache
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 install -r \
  platform-smoke-tests/build/outputs/apk/androidTest/platform-smoke-tests-androidTest.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5554 shell am instrument -w \
  com.clockworklabs.spacetimedb.smoketest.test/androidx.test.runner.AndroidJUnitRunner
```

Use the emulator serial shown by `adb devices` if it differs. This runs the same
on-device test; success is reported as `OK (1 test)` rather than a Gradle report.
