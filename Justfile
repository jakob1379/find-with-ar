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
    uv run --project analysis ar-signal-analyse "{{trace}}"

install:
    ./gradlew --no-daemon :app:installDebug

capture: install
    adb shell am start -n dev.arsignallocator/.MainActivity

connected-check:
    ./gradlew --no-daemon :app:connectedDebugAndroidTest
