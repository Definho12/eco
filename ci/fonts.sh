#!/bin/bash
# Inter (Open Font License), static TTFs packaged on npm
set -e
out="$(pwd)/src/main/resources/fonts"
mkdir -p "$out" /tmp/f
cd /tmp/f
npm pack @expo-google-fonts/inter > /dev/null
for t in *.tgz; do mkdir -p "${t%.tgz}"; tar xzf "$t" -C "${t%.tgz}"; done
pick() { f=$(find /tmp/f -name "$1" | head -1); [ -n "$f" ] || { echo "::error::font $1 not found"; exit 1; }; cp "$f" "$out/$2"; }
pick Inter_400Regular.ttf inter_regular.ttf
pick Inter_500Medium.ttf inter_medium.ttf
pick Inter_600SemiBold.ttf inter_semibold.ttf
ls -la "$out"
