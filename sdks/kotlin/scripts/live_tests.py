#!/usr/bin/env python3
"""Run Kotlin live tests against an isolated stock server; never use the user's config."""
import argparse
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("platform", choices=("jvm", "ios", "android", "submodules"))
    parser.add_argument("--ios-id")
    args = parser.parse_args()
    sdk = Path(__file__).resolve().parents[1]
    root = sdk.parents[1]
    cli = root / "target/debug/spacetimedb-cli"
    server = root / "target/debug/spacetimedb-standalone"
    if not cli.is_file() or not server.is_file():
        parser.error("Run cargo build -p spacetimedb-cli -p spacetimedb-standalone first")
    # Device tests use this fixed, loopback-only port. Refuse to interfere with an existing listener.
    # SO_REUSEADDR ignores TIME_WAIT leftovers from a previous run but still fails on a live listener.
    with socket.socket() as probe:
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(("127.0.0.1", 57875))
    with tempfile.TemporaryDirectory(prefix="spacetimedb-kotlin-") as directory:
        temp = Path(directory)
        # LibreSSL's genpkey writes explicit curve parameters, which the server's
        # JWT signer rejects with InvalidEcdsaKey. ecparam keeps the named curve.
        private, public = temp / "private.pem", temp / "public.pem"
        subprocess.run(["openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", str(temp / "sec1.pem")], check=True)
        subprocess.run(["openssl", "pkcs8", "-topk8", "-nocrypt", "-in", str(temp / "sec1.pem"), "-out", str(private)], check=True)
        subprocess.run(["openssl", "ec", "-in", str(temp / "sec1.pem"), "-pubout", "-out", str(public)], check=True)
        with (temp / "server.log").open("w+") as log:
            process = subprocess.Popen([
                str(server), "start", "--listen-addr", "127.0.0.1:57875",
                "--data-dir", str(temp / "data"), "--jwt-priv-key-path", str(private),
                "--jwt-pub-key-path", str(public),
            ], stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 30
                while True:
                    if process.poll() is not None:
                        raise RuntimeError("Isolated server exited during startup")
                    try:
                        with socket.create_connection(("127.0.0.1", 57875), timeout=1):
                            break
                    except OSError:
                        if time.monotonic() >= deadline:
                            raise TimeoutError("Isolated server did not start within 30 seconds")
                        time.sleep(0.1)
                # The submodule tests use the TypeScript fixture, which mounts a submodule;
                # building it needs the pnpm workspace installed.
                if args.platform == "submodules":
                    module, database = root / "modules/module-test-ts", "kotlin-submodule-test"
                else:
                    module, database = sdk / "integration-tests/spacetimedb", "kotlin-integration-test"
                subprocess.run([
                    str(cli), "--config-path", str(temp / "config.toml"), "publish",
                    "--server", "http://127.0.0.1:57875", "--no-config", "-y",
                    "--module-path", str(module), database,
                ], check=True, cwd=root)
                env = dict(os.environ, SPACETIMEDB_CLI=str(cli), SPACETIMEDB_HOST="ws://127.0.0.1:57875", SPACETIMEDB_DB_NAME=database)
                gradle = [str(sdk / "gradlew"), "--no-configuration-cache", "--console=plain", "--rerun-tasks",
                          "-Dorg.gradle.internal.http.connectionTimeout=120000", "-Dorg.gradle.internal.http.socketTimeout=120000"]
                if args.platform == "jvm":
                    tasks = [":codegen-tests:test", ":integration-tests:test", "-PintegrationTests"]
                elif args.platform == "submodules":
                    tasks = ["-PsubmoduleCodegenTests", ":submodule-codegen-tests:test"]
                elif args.platform == "ios":
                    tasks = ["-PplatformSmokeTests", ":platform-smoke-tests:iosSimulatorArm64Test"]
                    if args.ios_id:
                        tasks.append("-PiosSimulatorId=" + args.ios_id)
                else:
                    tasks = ["-PplatformSmokeTests", ":platform-smoke-tests:assembleAndroidDeviceTest"]
                subprocess.run(gradle + tasks, cwd=sdk, env=env, check=True, timeout=1800)
                if args.platform == "android":
                    android_sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
                    if not android_sdk:
                        raise RuntimeError("Set ANDROID_HOME to run Android emulator tests")
                    adb = str(Path(android_sdk) / "platform-tools/adb")
                    devices = subprocess.check_output([adb, "devices"], text=True)
                    serials = re.findall(r"^(emulator-\d+)\s+device$", devices, re.MULTILINE)
                    selected = os.environ.get("ANDROID_SERIAL")
                    if selected:
                        serials = [serial for serial in serials if serial == selected]
                    if len(serials) != 1:
                        raise RuntimeError("Start exactly one emulator or set ANDROID_SERIAL to an emulator serial")
                    command = [adb, "-s", serials[0]]
                    apk = sdk / "platform-smoke-tests/build/outputs/apk/androidTest/platform-smoke-tests-androidTest.apk"
                    subprocess.run(command + ["install", "-r", str(apk)], check=True, timeout=60)
                    result = subprocess.run(command + ["shell", "am", "instrument", "-w", "com.clockworklabs.spacetimedb.smoketest.test/androidx.test.runner.AndroidJUnitRunner"], capture_output=True, text=True, timeout=120)
                    print(result.stdout, result.stderr)
                    if result.returncode or not re.search(r"OK \([1-9]\d* tests?\)", result.stdout):
                        raise RuntimeError("Android instrumentation did not report passing tests")
            except BaseException:
                log.flush()
                log.seek(0)
                print(log.read())
                raise
            finally:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    main()
