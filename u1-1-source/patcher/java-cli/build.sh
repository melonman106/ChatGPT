#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
build_dir="$root_dir/build"
classes_dir="$build_dir/classes"
rm -rf "$build_dir"
mkdir -p "$classes_dir"

find "$root_dir/src/main/java" -name '*.java' -print0 \
  | xargs -0 javac --release 17 -d "$classes_dir"

manifest="$build_dir/manifest.txt"
printf 'Manifest-Version: 1.0\nMain-Class: com.eaglercraft.patcher.Main\nCreated-By: eaglercraft-java-cli-u1\n' > "$manifest"
jar --create --file "$build_dir/eaglercraft-26.2-java-cli.jar" \
  --date 2020-01-01T00:00:00Z --manifest "$manifest" -C "$classes_dir" .
gui_manifest="$build_dir/gui-manifest.txt"
printf 'Manifest-Version: 1.0\nMain-Class: com.eaglercraft.patcher.GuiMain\nCreated-By: eaglercraft-java-cli-u1\n' > "$gui_manifest"
jar --create --file "$build_dir/eaglercraft-26.2-u1-patcher-gui.jar" \
  --date 2020-01-01T00:00:00Z --manifest "$gui_manifest" -C "$classes_dir" .
source_manifest_sha256=$(find "$root_dir/src/main/java" -type f -name '*.java' -print \
  | LC_ALL=C sort \
  | while IFS= read -r source; do
      relative=${source#"$root_dir/"}
      printf '%s ' "$relative"
      sha256sum "$source" | cut -d' ' -f1
    done \
  | sha256sum | cut -d' ' -f1)
jar_sha256=$(sha256sum "$build_dir/eaglercraft-26.2-java-cli.jar" | cut -d' ' -f1)
gui_jar_sha256=$(sha256sum "$build_dir/eaglercraft-26.2-u1-patcher-gui.jar" | cut -d' ' -f1)
printf '{\n  "format":"eaglercraft-java-cli-build-v1",\n  "jar_sha256":"%s",\n  "gui_jar_sha256":"%s",\n  "source_manifest_sha256":"%s",\n  "jar_entry_date":"2020-01-01T00:00:00Z",\n  "javac_release":"17"\n}\n' \
  "$jar_sha256" "$gui_jar_sha256" "$source_manifest_sha256" > "$build_dir/receipt.json"
printf 'built %s\n' "$build_dir/eaglercraft-26.2-java-cli.jar"
