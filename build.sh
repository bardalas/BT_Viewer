#!/usr/bin/env bash
# Builds a signed, installable APK with no Gradle, no Maven, no Android Studio.
# Every tool is fetched from github.com / raw.githubusercontent.com.
set -euo pipefail

ROOT=$PWD
SDK=${SDK:-$PWD/.toolchain}
BT=$SDK/build-tools/34.0.4
AJ=$SDK/android.jar
OUT=out
MIN_API=26
TARGET_API=34

bootstrap() {
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
  fi
}

bootstrap
rm -rf "$OUT"; mkdir -p "$OUT/c"

echo "[1/6] javac"
javac -encoding UTF-8 --release 11 -nowarn -Xlint:-options \
  -classpath "$AJ" -d "$OUT/c" $(find src -name '*.java')

echo "[2/6] d8"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api $MIN_API --lib "$AJ" \
  --output "$OUT" $(find "$OUT/c" -name '*.class')

echo "[3/6] aapt2"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link --manifest AndroidManifest.xml -I "$AJ" \
  --min-sdk-version $MIN_API --target-sdk-version $TARGET_API \
  -o "$OUT/base.apk" "$OUT/res.zip"

echo "[4/6] package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -qj unsigned.apk classes.dex)

echo "[5/6] zipalign"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
# ONE keystore, committed with the project. Generating a fresh key per build
# gives every APK a different signature, and Android then refuses to install it
# over the previous one — so the new build silently never runs and every fix
# looks like it did nothing. Keep this file.
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

java -jar "$BT/lib/apksigner.jar" verify -v "$OUT/BTViewer-debug.apk"
echo "-> $OUT/BTViewer-debug.apk"
