#!/usr/bin/env bash
# =============================================================================
# KeyPilot — reproducible build script (git-bash / MSYS on Windows)
#
# Pipeline:
#   1. gen_icons.py (PIL Nothing dot-matrix adaptive icon)
#   2. aapt2 compile / link (resources + assets + manifest)
#   3. javac Stage 1: compile Xposed API 82 stubs -> out/stubs (isolated dir)
#   4. javac Stage 2: compile module sources against android.jar + out/stubs -> out/classes
#   5. d8: dex ONLY out/classes (zero stub classes enter classes.dex)
#   6. inject classes.dex -> zipalign -> keystore (once) -> apksigner (v2+v3)
#   7. verification: apksigner verify + aapt2 badging + dexdump audit + sha256
#   8. optional: cross-compile magisk/src/keypilotd.c -> magisk/system/bin/keypilotd (arm64)
#
# Output: release/KeyPilot.apk (signed v2+v3)
# Run:    bash build.sh
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cygpath -m "$SCRIPT_DIR" 2>/dev/null || echo "$SCRIPT_DIR")"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-C:/Users/25451/AppData/Local/Android/Sdk}}"
BT="$SDK/build-tools/37.0.0"
ANDROID_JAR="$SDK/platforms/android-37.0/android.jar"

JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
JAVA="$JAVA_HOME/bin/java.exe"
JAVAC="$JAVA_HOME/bin/javac.exe"
JAR="$JAVA_HOME/bin/jar.exe"
KEYTOOL="$JAVA_HOME/bin/keytool.exe"

AAPT2="$BT/aapt2.exe"
ZIPALIGN="$BT/zipalign.exe"
DEXDUMP="$BT/dexdump.exe"
D8_JAR="$BT/lib/d8.jar"
APKSIGNER_JAR="$BT/lib/apksigner.jar"

PY="${PYTHON:-C:/Users/25451/AppData/Local/Programs/Python/Python312/python.exe}"

KS="$ROOT/keystore/release.jks"
KS_ALIAS="keypilot"
KS_PASS="keypilot_release_key"

APP_SRC="$ROOT/app/src/main"
STUB_SRC="$ROOT/app/src/stubs/java"
OUT="$ROOT/out"
RELEASE="$ROOT/release"

for f in "$ANDROID_JAR" "$AAPT2" "$ZIPALIGN" "$DEXDUMP" "$D8_JAR" "$APKSIGNER_JAR" \
         "$JAVA" "$JAVAC" "$JAR" "$KEYTOOL" "$PY"; do
  [ -e "$f" ] || { echo "MISSING: $f" >&2; exit 1; }
done

# ---- 1. adaptive launcher icons (deterministic PIL Nothing dot-matrix) ----
echo "== [1/9] generating launcher icons"
"$PY" "$ROOT/scripts/gen_icons.py"

# ---- 2. clean output dirs ----
echo "== [2/9] clean out/"
rm -rf "$OUT"
mkdir -p "$OUT/stubs" "$OUT/classes" "$OUT/dex" "$RELEASE"

# ---- 3. aapt2 compile + link ----
echo "== [3/9] aapt2 compile + link"
"$AAPT2" compile --dir "$APP_SRC/res" -o "$OUT/res.zip"
"$AAPT2" link -o "$OUT/base.apk" \
  --manifest "$APP_SRC/AndroidManifest.xml" \
  -I "$ANDROID_JAR" \
  --min-sdk-version 26 --target-sdk-version 37 \
  --version-code 1 --version-name 1.0.0 \
  -A "$APP_SRC/assets" \
  "$OUT/res.zip"

# ---- 4. javac two-stage compilation (stubs isolated from module classes) ----
echo "== [4/9] javac Stage 1 (Xposed API 82 stubs -> out/stubs)"
( cd "$ROOT" \
  && find app/src/stubs/java -name '*.java' | sed 's/^/"/; s/$/"/' > out/stub_sources.txt \
  && "$JAVAC" -encoding UTF-8 --release 17 \
       -classpath "$ANDROID_JAR" \
       -d "$OUT/stubs" \
       @out/stub_sources.txt )

echo "== [4/9] javac Stage 2 (module sources -> out/classes)"
( cd "$ROOT" \
  && find app/src/main/java -name '*.java' | sed 's/^/"/; s/$/"/' > out/app_sources.txt \
  && "$JAVAC" -encoding UTF-8 --release 17 -Xlint:unchecked \
       -classpath "$ANDROID_JAR;$OUT/stubs" \
       -d "$OUT/classes" \
       @out/app_sources.txt )

# ---- 5. d8 (module classes ONLY — stubs NEVER enter classes.dex) ----
echo "== [5/9] d8 (module classes only)"
"$JAR" cf "$OUT/module-classes.jar" -C "$OUT/classes" .
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api 26 \
  --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  "$OUT/module-classes.jar"

# ---- 6. inject classes.dex + zipalign ----
echo "== [6/9] add classes.dex & zipalign"
"$PY" - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'PYEOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", compression=zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
print("classes.dex injected into", apk)
PYEOF

"$ZIPALIGN" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

# ---- 7. keystore (generated once, retained) + apksigner v2+v3 ----
echo "== [7/9] keystore & apksigner (v2+v3)"
mkdir -p "$ROOT/keystore"
if [ ! -f "$KS" ]; then
  echo "  generating new keystore: $KS"
  "$KEYTOOL" -genkeypair -v -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=KeyPilot, OU=NothingTools, O=rtzhang, C=CN"
fi

"$JAVA" -jar "$APKSIGNER_JAR" sign \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$RELEASE/KeyPilot.apk" \
  "$OUT/aligned.apk"

"$JAVA" -jar "$APKSIGNER_JAR" verify --verbose "$RELEASE/KeyPilot.apk"

# ---- 8. verification: aapt2 badging + dexdump audit + sha256 ----
echo "== [8/9] aapt2 badging & dexdump audit"
"$AAPT2" dump badging "$RELEASE/KeyPilot.apk" > "$OUT/badging.txt" || true
grep -E "package:|application-label|application-icon|sdkVersion|targetSdkVersion|launchable-activity" \
  "$OUT/badging.txt" || true

"$PY" - "$RELEASE/KeyPilot.apk" "$OUT/dex_check" <<'PYEOF'
import sys, zipfile, os
apk, dest = sys.argv[1], sys.argv[2]
os.makedirs(dest, exist_ok=True)
with zipfile.ZipFile(apk) as z:
    z.extract("classes.dex", dest)
print("classes.dex size:", os.path.getsize(os.path.join(dest, "classes.dex")), "bytes")
PYEOF

"$DEXDUMP" "$OUT/dex_check/classes.dex" > "$OUT/dexdump.txt"
grep "Class descriptor" "$OUT/dexdump.txt" | sed "s/.*'\(.*\)'.*/\1/" > "$OUT/dex_classes.txt"

REQUIRED_CLASSES=(
  "Lio/github/rtzhang/keypilot/MainHook;"
  "Lio/github/rtzhang/keypilot/MainActivity;"
  "Lio/github/rtzhang/keypilot/StatusProvider;"
  "Lio/github/rtzhang/keypilot/config/KeyPilotConfig;"
  "Lio/github/rtzhang/keypilot/hook/ConfigBridge;"
  "Lio/github/rtzhang/keypilot/hook/EssentialKeyHook;"
  "Lio/github/rtzhang/keypilot/hook/StandardKeysHook;"
  "Lio/github/rtzhang/keypilot/hook/GestureStateMachine;"
  "Lio/github/rtzhang/keypilot/hook/ActionExecutor;"
)

for cls in "${REQUIRED_CLASSES[@]}"; do
  if grep -q "^${cls}$" "$OUT/dex_classes.txt"; then
    echo "  OK   class in dex: $cls"
  else
    echo "  FAIL missing class: $cls" >&2
    exit 1
  fi
done

if grep -q "^Lde/robv/android/xposed" "$OUT/dex_classes.txt"; then
  echo "  FAIL Xposed stub classes leaked into classes.dex:" >&2
  grep "^Lde/robv/android/xposed" "$OUT/dex_classes.txt" >&2
  exit 1
else
  echo "  OK   de/robv/android/xposed stub definitions in dex: 0 matches"
fi

SHA=$(sha256sum "$RELEASE/KeyPilot.apk" | awk '{print $1}')
echo "$SHA  KeyPilot.apk" > "$RELEASE/KeyPilot.apk.sha256"
echo "  APK   : $RELEASE/KeyPilot.apk"
echo "  SHA256: $SHA"

# ---- 9. optional: cross-compile Magisk companion native daemon keypilotd (arm64) ----
echo "== [9/9] optional arm64 build for magisk/src/keypilotd.c"
NDK_CLANG="$SDK/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
if [ -f "$NDK_CLANG" ] && [ -f "$ROOT/magisk/src/keypilotd.c" ]; then
  mkdir -p "$ROOT/magisk/system/bin"
  if "$NDK_CLANG" --target=aarch64-linux-android35 -O2 -Wall -Wextra -fPIE -pie \
      -o "$ROOT/magisk/system/bin/keypilotd" "$ROOT/magisk/src/keypilotd.c"; then
    echo "  OK   built optional arm64 binary: magisk/system/bin/keypilotd ($(wc -c < "$ROOT/magisk/system/bin/keypilotd") bytes)"
  else
    echo "  WARN optional keypilotd cross-compile skipped (source retained)"
  fi
else
  echo "  SKIP NDK clang not found (optional keypilotd remains source-only)"
fi

echo "== build complete =="
