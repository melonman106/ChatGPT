#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/eagler-reuse-contract.XXXXXX")
cleanup() { rm -rf -- "$test_dir"; }
trap cleanup EXIT

classes_dir="$test_dir/classes"
mkdir -p "$classes_dir"
find "$root_dir/src/main/java" -name '*.java' -print0 \
  | xargs -0 javac --release 17 -d "$classes_dir"
javac --release 17 -cp "$classes_dir" -d "$classes_dir" \
  "$root_dir/src/test/java/com/eaglercraft/patcher/ProjectReuseContractTest.java" \
  "$root_dir/tests/BuildMemoryBudgetTest.java"

java -cp "$classes_dir" com.eaglercraft.patcher.ProjectReuseContractTest \
  "$test_dir/fixture" "$@"
java -cp "$classes_dir" com.eaglercraft.patcher.BuildMemoryBudgetTest
