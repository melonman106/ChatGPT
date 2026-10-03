#!/usr/bin/env bash
set -euo pipefail

if (($# != 2)); then
  echo "Usage: $0 PACKAGE_DIR ABSENT_RESULT_DIR" >&2
  exit 2
fi

package_dir=$(realpath -- "$1")
result_dir=$2
if [[ ! -d "$package_dir" || ! -f "$package_dir/SHA256SUMS" || -e "$result_dir" ]]; then
  echo "ERROR: package must exist and result directory must be absent" >&2
  exit 2
fi

started=$(date +%s)
mkdir -p -- "$result_dir"
result_dir=$(realpath -- "$result_dir")
status=failed
detail=uncompleted
finish() {
  code=$?
  ended=$(date +%s)
  printf '{"status":"%s","exit_code":%d,"elapsed_seconds":%d,"detail":"%s"}\n' \
    "$status" "$code" "$((ended - started))" "$detail" > "$result_dir/receipt.json"
}
trap finish EXIT

cp -a -- "$package_dir" "$result_dir/app"
(cd "$result_dir/app" && sha256sum -c SHA256SUMS > "$result_dir/package-check.log")
detail=bootstrap_failed
"$result_dir/app/bootstrap/bootstrap-linux-x86_64.sh" \
  > "$result_dir/bootstrap.log" 2>&1
detail=toolchain_manifest_missing
test -f "$result_dir/app/toolchain.properties"
java17=$(sed -n 's/^java17=//p' "$result_dir/app/toolchain.properties" | head -1)
java25=$(sed -n 's/^java25=//p' "$result_dir/app/toolchain.properties" | head -1)
node=$(sed -n 's/^node=//p' "$result_dir/app/toolchain.properties" | head -1)
test -x "$java17" && test -x "$java25" && test -x "$node"
detail=gui_self_test_failed
"$java17" -jar "$result_dir/app/eaglercraft-26.2-u1-patcher-gui.jar" --self-test \
  > "$result_dir/gui-self-test.log" 2>&1
grep -Fq 'gui-self-test: PASS' "$result_dir/gui-self-test.log"
detail=package_hash_after_install_failed
(cd "$result_dir/app" && sha256sum -c SHA256SUMS > "$result_dir/package-check-after.log")
status=passed
detail=verified_linux_vendor_toolchain_and_gui
