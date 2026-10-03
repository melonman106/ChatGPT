#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 11 || "$1" != "--jar" || "$3" != "--overlay" || "$5" != "--expected-overlay-sha256" \
  || "$7" != "--external-root" || "$9" != "--patch-bundle" ]]; then
  echo "usage: $0 --jar <official-26.2.jar> --overlay <resource-overlay.zip> --expected-overlay-sha256 <sha256> --external-root <resource-root> --patch-bundle <bundle.zip> <expected-bundle-sha256>" >&2
  exit 2
fi

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
jar_path=$(readlink -f "$2")
overlay_path=$(readlink -f "$4")
expected_overlay_sha256="$6"
external_root=$(readlink -f "$8")
bundle_path=$(readlink -f "${10}")
expected_bundle_sha256="${11}"
"$root_dir/build.sh" >/dev/null

test_dir=$(mktemp -d "${TMPDIR:-/tmp}/eagler-java-resource-test.XXXXXX")
cleanup() { rm -rf "$test_dir"; }
trap cleanup EXIT
positive_output="$test_dir/resources"
result=$(/usr/lib/jvm/java-17-openjdk-amd64/bin/jshell -q \
  --class-path "$root_dir/build/eaglercraft-26.2-java-cli.jar" <<EOF
import java.lang.reflect.*; import java.nio.file.*;
var type = Class.forName("com.eaglercraft.patcher.ResourceOverlay");
var method = type.getDeclaredMethod("apply", Path.class, Path.class, Path.class, String.class, Path.class);
method.setAccessible(true);
System.out.println(method.invoke(null, Path.of("$overlay_path"), Path.of("$jar_path"), Path.of("$positive_output"), "$expected_overlay_sha256", Path.of("$external_root")));
EOF
)
if [[ "$result" != *"finalFileCount=19515"* || "$result" != *"finalTreeSha256=ced3f0610dbfb18f8ecebfee5501df0da0036b8ad64ba7beb93f592aa9a1a8df"* ]]; then
  echo "resource overlay reconstruction failed" >&2
  printf '%s\n' "$result" >&2
  exit 1
fi
if [[ "$(find "$positive_output" -type f | wc -l)" -ne 19515 ]]; then
  echo "resource overlay file count mismatch" >&2
  exit 1
fi

tampered_overlay="$test_dir/tampered-overlay.zip"
cp -- "$overlay_path" "$tampered_overlay"
python3 - "$tampered_overlay" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
data = bytearray(path.read_bytes())
data[-1] ^= 1
path.write_bytes(data)
PY
set +e
java -jar "$root_dir/build/eaglercraft-26.2-java-cli.jar" create-dev \
  --jar /etc/hosts --output "$test_dir/tampered-output" --vineflower /etc/hosts \
  --java17 /usr/lib/jvm/java-17-openjdk-amd64/bin/java \
  --patch-bundle "$bundle_path" --expected-bundle-sha256 "$expected_bundle_sha256" \
  --resource-overlay "$tampered_overlay" --expected-resource-overlay-sha256 "$expected_overlay_sha256" \
  --external-resource-root "$external_root" >"$test_dir/tampered.stderr" 2>&1
tampered_status=$?
set -e
if [[ $tampered_status -eq 0 || -e "$test_dir/tampered-output" ]]; then
  cat "$test_dir/tampered.stderr" >&2
  exit 1
fi

evidence_dir="$root_dir/../../output/java-cli-resource-local-20260924"
mkdir -p "$evidence_dir"
cat > "$evidence_dir/resource-overlay-focused-receipt.json" <<EOF
{
  "status":"focused-apply-passed",
  "overlay_sha256":"$expected_overlay_sha256",
  "base_file_count":19497,
  "base_total_bytes":14915354,
  "base_tree_sha256":"c340d58c3759c0a2596c423441ce29cdc40c27fab3cb833641d3d6b03fd9b04a",
  "final_file_count":19515,
  "final_total_bytes":17582611,
  "final_tree_sha256":"ced3f0610dbfb18f8ecebfee5501df0da0036b8ad64ba7beb93f592aa9a1a8df",
  "operation_count":132,
  "counts":{"add":65,"modify":21,"delete":46},
  "external_zh_cn_sha256":"47d66d5b25a5ff1c40a4a6a179b44b165517af1863617ecac5cd03e751f49ff2",
  "external_count":6,
  "tampered_overlay_status":$tampered_status,
  "tampered_output_created":false,
  "python_runtime_used_by_converter":false,
  "release_status":"local-test-only"
}
EOF
cp "$test_dir/tampered.stderr" "$evidence_dir/tampered-overlay.stderr"
printf 'resource overlay focused acceptance: PASS (reconstruction, six external hashes, tampered ZIP rejection)\n'
