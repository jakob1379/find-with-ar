set shell := ["bash", "-euo", "pipefail", "-c"]

bootstrap-wrapper:
    curl --fail --location --output gradle/wrapper/gradle-wrapper.jar https://raw.githubusercontent.com/gradle/gradle/v9.4.1/gradle/wrapper/gradle-wrapper.jar
    echo "55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c  gradle/wrapper/gradle-wrapper.jar" | sha256sum --check -
    chmod +x gradlew

check:
    ./gradlew --no-daemon :localization:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
    uv sync --locked --project analysis
    uv run --project analysis pytest

test-localization:
    ./gradlew --no-daemon :localization:test

analyse trace:
    uv run --project analysis ar-signal-analyse "{{ trace }}"

install:
    ./gradlew --no-daemon :app:installDebug

capture: install
    adb shell am start -n dev.arsignallocator.debug/dev.arsignallocator.MainActivity

connected-check:
    ./gradlew --no-daemon :app:connectedDebugAndroidTest

release-check:
    #!/usr/bin/env bash
    set -euo pipefail
    export LC_ALL=C
    ./gradlew --no-daemon --no-configuration-cache :app:lintRelease :app:bundleRelease
    aab="app/build/outputs/bundle/release/app-release.aab"
    [[ -f "$aab" ]] || { echo "Missing release bundle: $aab" >&2; exit 1; }
    verification="$(jarsigner -verify -verbose -certs "$aab" 2>&1)"
    if ! grep -Fq "jar verified." <<<"$verification"; then
        printf '%s\n' "$verification" >&2
        echo "Release bundle is not JAR-signed." >&2
        exit 1
    fi
    grep -F "jar verified." <<<"$verification"
    task_dir="$(mktemp -d "${TMPDIR:-/tmp}/ar-device-finder-release-check.XXXXXX")"
    trap 'rm -rf -- "$task_dir"' EXIT
    trap 'exit 130' HUP INT TERM
    unzip -qq "$aab" 'base/dex/*.dex' -d "$task_dir"
    mapfile -d '' dex_files < <(find "$task_dir/base/dex" -type f -name '*.dex' -print0)
    ((${#dex_files[@]})) || { echo "Release bundle contains no DEX files." >&2; exit 1; }
    dex_strings="$task_dir/release-dex.txt"
    for dex in "${dex_files[@]}"; do
        strings -a "$dex"
    done >"$dex_strings"
    forbidden='ca-app-pub-3940256099942544|dev\.arsignallocator\.debug|DebugEntitlementOverride|Developer diagnostics|[Ss]how test ads|Landroidx/test/|Ldev/arsignallocator/[^;[:space:]]*Test([^;[:space:]]*)?;'
    if grep -aEn "$forbidden" "$dex_strings"; then
        echo "Release DEX contains a debug, test-ad, or test-class marker." >&2
        exit 1
    fi

ci-release-check:
    #!/usr/bin/env bash
    set -euo pipefail
    task_dir="$(mktemp -d "${TMPDIR:-/tmp}/ar-device-finder-ci-release.XXXXXX")"
    trap 'rm -rf -- "$task_dir"' EXIT
    trap 'exit 130' HUP INT TERM
    export ANDROID_KEYSTORE_PATH="$task_dir/upload.jks"
    export ANDROID_KEYSTORE_PASSWORD
    ANDROID_KEYSTORE_PASSWORD="$(openssl rand -base64 24)"
    export ANDROID_KEY_ALIAS="ephemeral-upload"
    export ANDROID_KEY_PASSWORD
    ANDROID_KEY_PASSWORD="$(openssl rand -base64 24)"
    keytool -genkeypair -noprompt -storetype JKS \
        -keystore "$ANDROID_KEYSTORE_PATH" \
        -storepass:env ANDROID_KEYSTORE_PASSWORD \
        -alias "$ANDROID_KEY_ALIAS" \
        -keypass:env ANDROID_KEY_PASSWORD \
        -keyalg RSA -keysize 2048 -validity 1 \
        -dname "CN=Ephemeral CI,OU=CI,O=AR Device Finder,C=DK" >/dev/null 2>&1
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
        -out "$task_dir/play-license.pem" >/dev/null 2>&1
    export ORG_GRADLE_PROJECT_RELEASE_PLAY_LICENSE_KEY
    ORG_GRADLE_PROJECT_RELEASE_PLAY_LICENSE_KEY="$(
        openssl pkey -in "$task_dir/play-license.pem" -pubout -outform DER 2>/dev/null |
            base64 | tr -d '\n'
    )"
    export ORG_GRADLE_PROJECT_RELEASE_APPLICATION_ID="com.northstar.devicefinder.ci"
    export ORG_GRADLE_PROJECT_RELEASE_ADMOB_APP_ID="ca-app-pub-7319465280417385~5820471936"
    export ORG_GRADLE_PROJECT_RELEASE_ADMOB_BANNER_ID="ca-app-pub-7319465280417385/5820471936"
    export ORG_GRADLE_PROJECT_RELEASE_PRIVACY_URL="https://privacy.northstar.dev/policy"
    export ORG_GRADLE_PROJECT_RELEASE_SUPPORT_EMAIL="support@northstar.dev"
    just release-check

connected-ci:
    #!/usr/bin/env bash
    set -euo pipefail
    task_dir="$(mktemp -d "${TMPDIR:-/tmp}/ar-device-finder-connected-ci.XXXXXX")"
    emulator_pid=""
    serial="emulator-5554"
    cleanup() {
        if [[ -n "$emulator_pid" ]] && kill -0 "$emulator_pid" 2>/dev/null; then
            adb -s "$serial" emu kill >/dev/null 2>&1 || kill "$emulator_pid" 2>/dev/null || true
            wait "$emulator_pid" 2>/dev/null || true
        fi
        rm -rf -- "$task_dir"
    }
    trap cleanup EXIT
    trap 'exit 130' HUP INT TERM
    if adb -s "$serial" get-state >/dev/null 2>&1; then
        echo "$serial is already in use." >&2
        exit 1
    fi
    export ANDROID_USER_HOME="$task_dir/android-user"
    export ANDROID_AVD_HOME="$task_dir/avd"
    mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME"
    printf 'no\n' | avdmanager create avd --force \
        --name "api-37-google-apis" \
        --package "system-images;android-37.0;google_apis_ps16k;x86_64"
    emulator -avd "api-37-google-apis" -port 5554 \
        -no-window -no-audio -no-boot-anim -no-snapshot -wipe-data \
        -gpu swiftshader_indirect >"$task_dir/emulator.log" 2>&1 &
    emulator_pid=$!
    booted=false
    for _ in {1..120}; do
        if ! kill -0 "$emulator_pid" 2>/dev/null; then
            tail -n 100 "$task_dir/emulator.log" >&2
            echo "Emulator exited before boot completed." >&2
            exit 1
        fi
        if [[ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
            booted=true
            break
        fi
        sleep 2
    done
    if [[ "$booted" != true ]]; then
        tail -n 100 "$task_dir/emulator.log" >&2
        echo "Emulator did not boot within 240 seconds." >&2
        exit 1
    fi
    adb -s "$serial" shell input keyevent 82
    export ANDROID_SERIAL="$serial"
    ./gradlew --no-daemon :app:connectedDebugAndroidTest
