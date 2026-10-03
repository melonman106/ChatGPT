#!/usr/bin/env bash
set -euo pipefail

patcher_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/eagler-normal-mode-contract.XXXXXX")
cleanup() { rm -rf -- "$test_dir"; }
trap cleanup EXIT

classes_dir="$test_dir/classes"
mkdir -p "$classes_dir"
find "$patcher_dir/src/main/java" -name '*.java' -print0 \
  | xargs -0 javac --release 17 -d "$classes_dir"
javac --release 17 -cp "$classes_dir" -d "$classes_dir" \
  "$patcher_dir/tests/PatcherModeProfileTest.java"
java -cp "$classes_dir" com.eaglercraft.patcher.PatcherModeProfileTest
if grep -Fq -- '--build-timeout-seconds' "$patcher_dir/src/main/java/com/eaglercraft/patcher/GuiMain.java"; then
  printf 'GUI should use the shared CLI standalone timeout default\n' >&2
  exit 1
fi

assert_cli_rejects() {
  local mode=$1
  local output_path="$test_dir/$mode-output"
  local output_file="$test_dir/$mode.log"
  local status=0
  local args=(
    --jar "$test_dir/unused-client.jar"
    --output "$output_path"
    --vineflower "$test_dir/unused-vineflower.jar"
    --java17 "$test_dir/unused-java17"
    --patch-bundle "$test_dir/unused-source.zip"
    --expected-bundle-sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  )
  if [[ "$mode" == "build-standalone" || "$mode" == "build-iwa" ]]; then
    args+=(
      --project-skeleton "$test_dir/unused-skeleton.zip"
      --expected-skeleton-sha256 "a9172bdd903ea65afd4c87cdb29b500c237c823fdd94789c2e90561b1b049469"
      --resource-overlay "$test_dir/unused-overlay.zip"
      --expected-resource-overlay-sha256 "2ba7e3376891c64f8bf57f3687e05b8dbe1971a75475b6825449e5e5f96d71f3"
      --external-resource-root "$test_dir/unused-resources"
      --java25 "$test_dir/unused-java25"
      --node "$test_dir/unused-node"
      --npm "$test_dir/unused-npm"
      --sounds-epk "$test_dir/unused-sounds.epk"
      --expected-sounds-epk-sha256 "94bc8bfcf4132c52c6d5f61f3f92e50532a6fad1f5bc901ee25a462db8ba4fc6"
      --music-epk "$test_dir/unused-music.epk"
      --expected-music-epk-sha256 "f01cdaf62a9686438998b11ffed5407a1b890e14960c63f71b57401d02216a4e"
    )
    if [[ "$mode" == "build-standalone" ]]; then
      args+=(--standalone-output "$test_dir/unused.html")
    else
      args+=(--iwa-output "$test_dir/unused.swbn")
    fi
  fi
  java -cp "$classes_dir" com.eaglercraft.patcher.Main "$mode" "${args[@]}" \
    >"$output_file" 2>&1 || status=$?
  if [[ $status -ne 2 || -e "$output_path" ]]; then
    printf 'CLI Normal-only contract failed for %s (status %s)\n' "$mode" "$status" >&2
    cat "$output_file" >&2
    exit 1
  fi
}

for mode in create-dev build-standalone build-iwa; do
  assert_cli_rejects "$mode"
done
reuse_client_log="$test_dir/reuse-client-without-project.log"
reuse_client_status=0
java -cp "$classes_dir" com.eaglercraft.patcher.Main build-standalone \
  --reuse-client \
  --jar "$test_dir/unused-client.jar" \
  --output "$test_dir/reuse-client-project" \
  --vineflower "$test_dir/unused-vineflower.jar" \
  --java17 "$test_dir/unused-java17" \
  --patch-bundle "$test_dir/unused-source.zip" \
  --expected-bundle-sha256 "0000000000000000000000000000000000000000000000000000000000000000" \
  --project-skeleton "$test_dir/unused-skeleton.zip" \
  --expected-skeleton-sha256 "a9172bdd903ea65afd4c87cdb29b500c237c823fdd94789c2e90561b1b049469" \
  --resource-overlay "$test_dir/unused-overlay.zip" \
  --expected-resource-overlay-sha256 "2ba7e3376891c64f8bf57f3687e05b8dbe1971a75475b6825449e5e5f96d71f3" \
  --external-resource-root "$test_dir/unused-resources" \
  --java25 "$test_dir/unused-java25" \
  --node "$test_dir/unused-node" \
  --npm "$test_dir/unused-npm" \
  --sounds-epk "$test_dir/unused-sounds.epk" \
  --expected-sounds-epk-sha256 "94bc8bfcf4132c52c6d5f61f3f92e50532a6fad1f5bc901ee25a462db8ba4fc6" \
  --music-epk "$test_dir/unused-music.epk" \
  --expected-music-epk-sha256 "f01cdaf62a9686438998b11ffed5407a1b890e14960c63f71b57401d02216a4e" \
  --standalone-output "$test_dir/reuse-client.html" \
  >"$reuse_client_log" 2>&1 || reuse_client_status=$?
if [[ $reuse_client_status -ne 2 ]] || ! grep -Fq -- '--reuse-client requires --reuse-project' "$reuse_client_log"; then
  printf 'CLI should reject client artifact reuse without project reuse\n' >&2
  cat "$reuse_client_log" >&2
  exit 1
fi
printf 'CLI modes: PASS (all three require the Normal source identity)\n'
