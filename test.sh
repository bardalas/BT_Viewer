#!/usr/bin/env bash
# Plain-JVM tests for the alert decision (no device needed).
set -euo pipefail
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    SDKW="${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk"
    AJ=${AJ:-$(ls -d "$SDKW"/platforms/android-*/ | sort -V | tail -1)android.jar}
    command -v javac >/dev/null || export PATH="/c/Program Files/Android/Android Studio/jbr/bin:$PATH"
    SEP=";" ;;
  *) AJ=${AJ:-$PWD/.toolchain/android.jar}; SEP=":" ;;
esac
T=${TMPDIR:-/tmp}/btviewer-test
rm -rf "$T"; mkdir -p "$T"
javac -encoding UTF-8 -nowarn -cp "$AJ" -d "$T" \
  src/il/co/embeddit/btviewer/EchoFilter.java src/il/co/embeddit/btviewer/Config.java \
  src/il/co/embeddit/btviewer/Beeper.java src/il/co/embeddit/btviewer/Telemetry.java \
  src/il/co/embeddit/btviewer/Updater.java src/il/co/embeddit/btviewer/Diag.java \
  src/il/co/embeddit/btviewer/UpdateReceiver.java test/EchoFilterTest.java
command -v cygpath >/dev/null && T=$(cygpath -m "$T") && AJ=$(cygpath -m "$AJ")
java -cp "$T$SEP$AJ" il.co.embeddit.btviewer.EchoFilterTest
