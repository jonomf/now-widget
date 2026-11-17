#!/bin/bash
# Runs the pure-Kotlin unit tests (app/src/test, core package) without the Android SDK, using the
# Kotlin compiler and JUnit that ship inside a Gradle 8.x install. CI runs the same tests through
# `gradle :app:testDebugUnitTest`; this is for workspaces with no SDK and no Maven access.
#   tools/run_core_tests.sh [/path/to/gradle/lib]
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
L="${1:-$(dirname "$(dirname "$(readlink -f "$(command -v gradle || echo /opt/gradle/bin/gradle)")")")/lib}"
jar() { ls "$L"/$1-[0-9]*.jar 2>/dev/null | head -1; }
KC="$(jar kotlin-compiler-embeddable)"; STD="$(jar kotlin-stdlib)"; JU="$(jar junit)"; HC="$(jar hamcrest-core)"
for j in "$KC" "$STD" "$JU" "$HC"; do [ -n "$j" ] || { echo "Missing a jar in $L"; exit 2; }; done
CP="$KC:$STD:$(jar kotlin-script-runtime):$(jar kotlin-reflect):$(jar kotlin-daemon-embeddable):$(jar trove4j):$(jar annotations):$(jar kotlinx-coroutines-core-jvm)"
CORE="$REPO/app/src/main/java/com/jono/nowwidget/core"
if grep -l "^import android" "$CORE"/*.kt; then echo "core/ must not import android"; exit 1; fi
OUT="$(mktemp -d)"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-reflect -no-stdlib -nowarn \
  -cp "$STD:$JU" -jvm-target 17 -d "$OUT" "$CORE"/*.kt "$REPO"/app/src/test/java/com/jono/nowwidget/core/*.kt
CLASSES=$(cd "$OUT" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
java -cp "$OUT:$STD:$JU:$HC:$REPO/app/src/test/resources" org.junit.runner.JUnitCore $CLASSES
