#!/usr/bin/env bash
# Runs StremioStreamKindTest against the module's real compiled classes.
#
# The pure stream logic (kind detection, magnet building, header merge, default-addon
# invariants) is where a wrong answer silently mis-routes playback, and it needs no device.
# So compile the real module with Gradle, then run this on the JVM against those classes.
#
# Usage: tools/run_stream_checks.sh
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-arm64}"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
KOTLINC="${KOTLINC:-/tmp/kotlinc/bin/kotlinc}"
CLASSES="Stremio/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
CLOUDSTREAM_JAR="$HOME/.gradle/caches/cloudstream/cloudstream/cloudstream.jar"
ANDROID_JAR="${ANDROID_HOME:-/opt/android-sdk}/platforms/android-36/android.jar"
ORG_JSON_JAR="${ORG_JSON_JAR:-/tmp/json.jar}"
# Transitive runtime deps of the CloudStream jar that StreamKind touches.
RUNTIME_JARS="$(find "$HOME/.gradle/caches/modules-2" \
  \( -name 'kotlin-stdlib-2.3.20.jar' -o -name 'kotlinx-serialization-core-jvm-*.jar' \
     -o -name 'kotlinx-serialization-json-jvm-*.jar' -o -name 'kotlinx-coroutines-core-jvm-*.jar' \
     -o -name 'okhttp-4*.jar' -o -name 'okio-jvm-*.jar' \
     -o -name 'jackson-databind-*.jar' -o -name 'jackson-core-*.jar' \
     -o -name 'jackson-annotations-*.jar' -o -name 'jackson-module-kotlin-*.jar' \) \
  ! -name '*sources*' | sort -u | tr '\n' ':')"

# Recompile first. These checks run against Gradle's output, so without this they silently
# test the previous build whenever a source file changed.
echo "compiling..."
JAVA_HOME="$JAVA_HOME" ANDROID_HOME="$ANDROID_HOME" "$PWD/gradlew" -p "$PWD" :Stremio:compileDebugKotlin --no-daemon -q >&2
if [ ! -x "$KOTLINC" ]; then
  echo "missing kotlinc at $KOTLINC" >&2
  exit 1
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# A tiny main() so the check class is runnable without a test framework.
cat > "$OUT/Runner.kt" <<'EOF'
import com.stremio.StremioStreamKindTest
import com.stremio.runDateChecks
import com.stremio.runProfileChecks
import com.stremio.runSubtitleChecks

fun main() {
    StremioStreamKindTest().runAll()
    runProfileChecks()
    runDateChecks()
    runSubtitleChecks()
    println("OK: all stream checks passed")
}
EOF

# The Log shim compiles into checks.jar, which is placed ahead of android.jar so it wins over the
# android.util.Log stub that throws on every call.
"$KOTLINC" \
  -nowarn \
  -classpath "$CLASSES:$CLOUDSTREAM_JAR:$ORG_JSON_JAR:$ANDROID_JAR:$RUNTIME_JARS" \
  -d "$OUT/checks.jar" \
  tools/checkshim/android/util/Log.kt \
  Stremio/src/test/kotlin/com/stremio/*.kt "$OUT/Runner.kt"

"$JAVA_HOME/bin/java" \
  -classpath "$OUT/checks.jar:$CLASSES:$CLOUDSTREAM_JAR:$ORG_JSON_JAR:$ANDROID_JAR:$RUNTIME_JARS" \
  RunnerKt
