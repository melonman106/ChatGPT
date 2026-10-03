#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/eagler-gui-contract.XXXXXX")
cleanup() { rm -rf -- "$test_dir"; }
trap cleanup EXIT
classes_dir="$test_dir/classes"
mkdir -p "$classes_dir"
find "$root_dir/src/main/java" -name '*.java' -print0 \
  | xargs -0 javac --release 17 -d "$classes_dir"
output=$(java -Djava.awt.headless=true -cp "$classes_dir" \
  com.eaglercraft.patcher.GuiMain --self-test)
grep -Fq 'gui-self-test: PASS' <<<"$output"
grep -Fq 'modes: 3' <<<"$output"
grep -Fq 'content-profile: Normal in Source project, Standalone, and IWA; PASS' <<<"$output"
grep -Fq 'project reuse: automatic recognized project, stale probe guard, and CLI validation PASS' <<<"$output"
grep -Fq 'output defaults: project, HTML, SWBN, and key paths blank PASS' <<<"$output"
grep -Fq 'open folder: successful source, standalone, and IWA destinations PASS' <<<"$output"
grep -Fq 'existing com.eaglercraft.patcher.Main subprocess' <<<"$output"
echo "GUI launcher: PASS (headless compile and wiring self-test)"
