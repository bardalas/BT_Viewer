#!/usr/bin/env bash
# Builds a signed, installable APK with no Gradle, no Maven, no Android Studio.
# Version comes from version.properties (see release.sh).
#
# Linux/macOS: tools are fetched from github.com into .toolchain/.
# Windows (Git Bash): uses the local Android SDK and Android Studio's JDK, so
# nothing is downloaded. Override with BT=, AJ= and JAVA_HOME=.
set -euo pipefail

ROOT=$PWD
MIN_API=26
TARGET_API=34
# Built outside the project: a synced folder (Google Drive) can lock a freshly
# deleted out/ and break the next build. Override with OUT=.
OUT=${OUT:-/tmp/btviewer-out}
VERSION_NAME=$(sed -n 's/^versionName=//p' version.properties | tr -d '\r')
VERSION_CODE=$(sed -n 's/^versionCode=//p' version.properties | tr -d '\r')

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    SDKW="${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk"
    BT=${BT:-$(ls -d "$SDKW"/build-tools/*/ | sort -V | tail -1)}; BT=${BT%/}
    AJ=${AJ:-$(ls -d "$SDKW"/platforms/android-*/ | sort -V | tail -1)android.jar}
    EXE=.exe
    if ! command -v javac >/dev/null; then
      JH=${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}
      export PATH="$JH/bin:$PATH"
    fi ;;
  *)
    SDK=${SDK:-$PWD/.toolchain}
    BT=$SDK/build-tools/34.0.4
    AJ=$SDK/android.jar
    EXE=
    mkdir -p "$SDK"
    if [ ! -x "$BT/aapt2" ]; then
      echo "[bootstrap] build-tools"
      curl -fsSL -A claude -o "$SDK/bt.tar.xz" \
        https://github.com/AndroidIDEOfficial/androidide-tools/releases/download/v34.0.4/build-tools-34.0.4-x86_64.tar.xz
      tar xJf "$SDK/bt.tar.xz" -C "$SDK"
    fi
    if [ ! -f "$AJ" ]; then
      echo "[bootstrap] android.jar (API 34)"
      curl -fsSL -A claude -o "$AJ" \
        https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar
    fi ;;
esac

rm -rf "$OUT"; mkdir -p "$OUT/c"

echo "[1/6] javac  (v$VERSION_NAME / $VERSION_CODE)"
javac -encoding UTF-8 --release 11 -nowarn -Xlint:-options \
  -classpath "$AJ" -d "$OUT/c" $(find src -name '*.java')

echo "[2/6] d8"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api $MIN_API --lib "$AJ" \
  --output "$OUT" $(find "$OUT/c" -name '*.class')

echo "[3/6] aapt2"
"$BT/aapt2$EXE" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2$EXE" link --manifest AndroidManifest.xml -I "$AJ" \
  --min-sdk-version $MIN_API --target-sdk-version $TARGET_API \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -o "$OUT/base.apk" "$OUT/res.zip"

echo "[4/6] package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python -c "import sys,zipfile;z=zipfile.ZipFile(sys.argv[1],'a');z.write(sys.argv[2],'classes.dex');z.close()" "$OUT/unsigned.apk" "$OUT/classes.dex"

echo "[5/6] zipalign"
"$BT/zipalign$EXE" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
# ONE keystore, kept out of git. Generating a fresh key per build gives every
# APK a different signature, and Android then refuses to install it over the
# previous one - and the in-app updater would fail the same way. Keep this file.
KS="${KS:-$ROOT/btviewer.keystore}"
if [ ! -f "$KS" ]; then
  echo "missing $KS - refusing to mint a throwaway key" >&2
  exit 1
fi
java -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:btviewer --key-pass pass:btviewer \
  --ks-key-alias btviewer --min-sdk-version $MIN_API \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/BTViewer-debug.apk" "$OUT/aligned.apk"

java -jar "$BT/lib/apksigner.jar" verify "$OUT/BTViewer-debug.apk"
echo "-> $OUT/BTViewer-debug.apk"
