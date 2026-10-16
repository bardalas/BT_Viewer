#!/usr/bin/env bash
# One command to publish an update that installed apps pick up on next open.
#
#   ./release.sh            bump patch  5.7 -> 5.7.1
#   ./release.sh minor      5.7 -> 5.8
#   ./release.sh major      5.7 -> 6.0
#   ./release.sh 5.9        set an exact version
#
# Bumps version.properties, builds the signed APK, commits, tags vX.Y, pushes,
# and creates a GitHub release with the APK attached. Needs `gh` logged in and
# btviewer.keystore present.
set -euo pipefail

command -v gh >/dev/null || { echo "gh (GitHub CLI) not found" >&2; exit 1; }
[ -f btviewer.keystore ] || { echo "btviewer.keystore missing" >&2; exit 1; }

cur=$(sed -n 's/^versionName=//p' version.properties | tr -d '\r')
code=$(sed -n 's/^versionCode=//p' version.properties | tr -d '\r')
IFS=. read -r MA MI PA <<<"$cur"; MA=${MA:-0}; MI=${MI:-0}; PA=${PA:-0}

case "${1:-patch}" in
  patch) new="$MA.$MI.$((PA+1))" ;;
  minor) new="$MA.$((MI+1))" ;;
  major) new="$((MA+1)).0" ;;
  [0-9]*) new="$1" ;;
  *) echo "usage: release.sh [patch|minor|major|X.Y]" >&2; exit 1 ;;
esac
tag="v$new"
git rev-parse "$tag" >/dev/null 2>&1 && { echo "tag $tag already exists" >&2; exit 1; }

printf 'versionName=%s\nversionCode=%s\n' "$new" "$((code+1))" > version.properties
sed -i "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$((code+1))\"/; s/android:versionName=\"[^\"]*\"/android:versionName=\"$new\"/" AndroidManifest.xml

./build.sh

OUT=${OUT:-/tmp/btviewer-out}
apk="$OUT/BTViewer-$new.apk"
cp "$OUT/BTViewer-debug.apk" "$apk"

git add -A
git commit -m "Release $tag"
git tag "$tag"
git push origin HEAD "$tag"
gh release create "$tag" "$apk" --title "BT Viewer $new" --generate-notes
echo "Released $tag - apps update on next open."
