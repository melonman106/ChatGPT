#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
"$root_dir/build.sh" >/dev/null
test_classes=$(mktemp -d "${TMPDIR:-/tmp}/eagler-sounds-test.XXXXXX")
cleanup() { rm -rf "$test_classes"; }
trap cleanup EXIT
javac --release 17 -cp "$root_dir/build/eaglercraft-26.2-java-cli.jar" \
  -d "$test_classes" "$root_dir/src/test/java/com/eaglercraft/patcher/SoundsContractTest.java"
java -cp "$root_dir/build/eaglercraft-26.2-java-cli.jar:$test_classes" \
  com.eaglercraft.patcher.SoundsContractTest
