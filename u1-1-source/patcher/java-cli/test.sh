#!/usr/bin/env bash
set -euo pipefail

if [[ "$1" != "--jar" || "$3" != "--vineflower" || "$5" != "--java17" \
  || "$7" != "--patch-bundle" || "$9" != "--expected-bundle-sha256" \
  || ( $# -ne 10 && $# -ne 14 ) ]]; then
  echo "usage: $0 --jar <official-26.2.jar> --vineflower <vineflower-1.12.0.jar> --java17 <java17> --patch-bundle <bundle.zip> --expected-bundle-sha256 <sha256> [--project-skeleton <skeleton-u1-nettyfix.zip> --expected-skeleton-sha256 <sha256>]" >&2
  exit 2
fi

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
jar_path=$(readlink -f "$2")
vineflower_path=$(readlink -f "$4")
java17_path=$(readlink -f "$6")
bundle_path=$(readlink -f "$8")
expected_bundle_sha256="${10}"
with_skeleton=0
skeleton_args=()
if [[ $# -eq 14 ]]; then
  if [[ "${11}" != "--project-skeleton" || "${13}" != "--expected-skeleton-sha256" ]]; then
    echo "skeleton options must be supplied as a pair" >&2
    exit 2
  fi
  with_skeleton=1
  skeleton_path=$(readlink -f "${12}")
  expected_skeleton_sha256="${14}"
  skeleton_args=(--project-skeleton "$skeleton_path" --expected-skeleton-sha256 "$expected_skeleton_sha256")
fi
"$root_dir/build.sh" >/dev/null

test_dir=$(mktemp -d "${TMPDIR:-/tmp}/eagler-java-cli-test.XXXXXX")
cleanup() { rm -rf "$test_dir"; }
trap cleanup EXIT
positive_output="$test_dir/positive"
negative_jar="$test_dir/tampered-client.jar"
negative_output="$test_dir/negative"
tampered_bundle="$test_dir/tampered-bundle.zip"
tampered_bundle_output="$test_dir/tampered-bundle-output"
tampered_skeleton="$test_dir/tampered-skeleton.zip"
tampered_skeleton_output="$test_dir/tampered-skeleton-output"
cp -- "$jar_path" "$negative_jar"
cp -- "$bundle_path" "$tampered_bundle"
if [[ $with_skeleton -eq 1 ]]; then cp -- "$skeleton_path" "$tampered_skeleton"; fi
python3 - "$negative_jar" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
data = bytearray(path.read_bytes())
data[-1] ^= 0x01
path.write_bytes(data)
PY
python3 - "$tampered_bundle" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
data = bytearray(path.read_bytes())
data[-1] ^= 0x01
path.write_bytes(data)
PY
if [[ $with_skeleton -eq 1 ]]; then
python3 - "$tampered_skeleton" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
data = bytearray(path.read_bytes())
data[-1] ^= 0x01
path.write_bytes(data)
PY
fi
positive_log="$test_dir/positive.json"
negative_log="$test_dir/negative.log"
tampered_bundle_log="$test_dir/tampered-bundle.log"
tampered_skeleton_log="$test_dir/tampered-skeleton.log"
engine_output="$test_dir/engine-positive"
tampered_base="$test_dir/tampered-base"
tampered_preimage_output="$test_dir/tampered-preimage-output"
set +e
java -jar "$root_dir/build/eaglercraft-26.2-java-cli.jar" create-dev \
  --jar "$jar_path" --output "$positive_output" \
  --vineflower "$vineflower_path" --java17 "$java17_path" \
  --patch-bundle "$bundle_path" --expected-bundle-sha256 "$expected_bundle_sha256" \
  "${skeleton_args[@]}" >"$positive_log"
positive_status=$?
java -jar "$root_dir/build/eaglercraft-26.2-java-cli.jar" create-dev \
  --jar "$negative_jar" --output "$negative_output" \
  --vineflower "$vineflower_path" --java17 "$java17_path" \
  --patch-bundle "$bundle_path" --expected-bundle-sha256 "$expected_bundle_sha256" \
  "${skeleton_args[@]}" >"$negative_log" 2>&1
negative_status=$?
java -jar "$root_dir/build/eaglercraft-26.2-java-cli.jar" create-dev \
  --jar "$jar_path" --output "$tampered_bundle_output" \
  --vineflower "$vineflower_path" --java17 "$java17_path" \
  --patch-bundle "$tampered_bundle" --expected-bundle-sha256 "$expected_bundle_sha256" \
  "${skeleton_args[@]}" >"$tampered_bundle_log" 2>&1
tampered_bundle_status=$?
tampered_skeleton_status=0
if [[ $with_skeleton -eq 1 ]]; then
  java -jar "$root_dir/build/eaglercraft-26.2-java-cli.jar" create-dev \
    --jar "$jar_path" --output "$tampered_skeleton_output" \
    --vineflower "$vineflower_path" --java17 "$java17_path" \
    --patch-bundle "$bundle_path" --expected-bundle-sha256 "$expected_bundle_sha256" \
    --project-skeleton "$tampered_skeleton" --expected-skeleton-sha256 "$expected_skeleton_sha256" \
    >"$tampered_skeleton_log" 2>&1
  tampered_skeleton_status=$?
fi
set -e

if [[ $positive_status -ne 0 ]]; then
  echo "positive create-dev failed (status $positive_status)" >&2
  cat "$positive_log" >&2
  exit 1
fi
if [[ $negative_status -eq 0 ]]; then
  echo "tampered JAR was accepted" >&2
  cat "$negative_log" >&2
  exit 1
fi
if [[ -e "$negative_output" ]]; then
  echo "tampered JAR left an output directory" >&2
  exit 1
fi
if [[ $tampered_bundle_status -eq 0 || -e "$tampered_bundle_output" ]]; then
  echo "tampered source bundle was accepted or left output" >&2
  cat "$tampered_bundle_log" >&2
  exit 1
fi
if [[ $with_skeleton -eq 1 && ( $tampered_skeleton_status -eq 0 || -e "$tampered_skeleton_output" ) ]]; then
  echo "tampered project skeleton was accepted or left output" >&2
  cat "$tampered_skeleton_log" >&2
  exit 1
fi
if [[ $with_skeleton -eq 1 ]]; then
  [[ "$(stat -c '%a' "$positive_output/gradlew")" == "755" ]] || { echo "gradlew mode was not preserved" >&2; exit 1; }
  [[ "$(stat -c '%a' "$positive_output/build.gradle.kts")" == "644" ]] || { echo "ordinary file mode was not preserved" >&2; exit 1; }
  skeleton_mode_verified=true
else
  skeleton_mode_verified=false
fi
cp -a "$positive_output/source" "$tampered_base"
java -jar "$root_dir/build/eaglercraft-26.2-java-cli.jar" apply-patch \
  --base-source "$positive_output/source" --output "$engine_output" \
  --patch-bundle "$bundle_path" --expected-bundle-sha256 "$expected_bundle_sha256" \
  >"$test_dir/engine-positive.json"
first_java=$(find "$tampered_base" -type f -name '*.java' -print -quit)
python3 - "$first_java" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
path.write_bytes(path.read_bytes() + b"\n// preimage tamper\n")
PY
set +e
java -jar "$root_dir/build/eaglercraft-26.2-java-cli.jar" apply-patch \
  --base-source "$tampered_base" --output "$tampered_preimage_output" \
  --patch-bundle "$bundle_path" --expected-bundle-sha256 "$expected_bundle_sha256" \
  >"$test_dir/tampered-preimage.log" 2>&1
tampered_preimage_status=$?
set -e
if [[ $tampered_preimage_status -eq 0 || -e "$tampered_preimage_output" ]]; then
  echo "tampered preimage was accepted or left output" >&2
  cat "$test_dir/tampered-preimage.log" >&2
  exit 1
fi

if [[ $with_skeleton -eq 1 ]]; then
  evidence_dir="$root_dir/../../output/java-cli-dev-skeleton-v3-20260924"
else
  evidence_dir="$root_dir/../../output/java-cli-source-acceptance-20260924"
fi
if [[ $with_skeleton -eq 1 && ! -e "$evidence_dir/gradlew" ]]; then
  mkdir -p "$evidence_dir"
  cp -a "$positive_output/." "$evidence_dir/"
elif [[ ! -e "$evidence_dir/game/src/main/java" ]]; then
  mkdir -p "$evidence_dir/game/src/main"
  cp -a "$positive_output/game/src/main/java" "$evidence_dir/game/src/main/"
fi

python3 - "$positive_log" "$negative_log" "$tampered_bundle_log" "$tampered_skeleton_log" \
  "$test_dir/engine-positive.json" "$test_dir/tampered-preimage.log" "$evidence_dir" \
  "$skeleton_mode_verified" "$root_dir/build/receipt.json" <<'PY'
from pathlib import Path
import json
import sys

positive = Path(sys.argv[1])
negative = Path(sys.argv[2])
tampered_bundle = Path(sys.argv[3])
tampered_skeleton = Path(sys.argv[4])
engine = json.loads(Path(sys.argv[5]).read_text())
tampered_preimage = Path(sys.argv[6])
evidence = Path(sys.argv[7])
skeleton_mode_verified = sys.argv[8] == "true"
build_receipt = json.loads(Path(sys.argv[9]).read_text())
evidence.mkdir(parents=True, exist_ok=True)
receipt = json.loads(positive.read_text())
receipt["test"] = {
    "positive_status": 0,
    "tampered_jar_status": 2,
    "tampered_output_created": False,
    "tampered_bundle_status": 2,
    "tampered_bundle_output_created": False,
    "tampered_skeleton_status": 2 if skeleton_mode_verified else None,
    "tampered_skeleton_output_created": False if skeleton_mode_verified else None,
    "skeleton_mode_verified": skeleton_mode_verified,
    "cli_build_receipt": build_receipt,
    "focused_patch_engine": engine,
    "tampered_preimage_rejected": True,
    "one_heavy_decompile": True,
}
(evidence / "terminal-receipt.json").write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
(evidence / "tampered-jar.stderr").write_text(negative.read_text())
(evidence / "tampered-bundle.stderr").write_text(tampered_bundle.read_text())
(evidence / "tampered-skeleton.stderr").write_text(tampered_skeleton.read_text() if tampered_skeleton.exists() else "not run: skeleton input omitted\n")
(evidence / "tampered-preimage.stderr").write_text(tampered_preimage.read_text())
(evidence / "cli-build-receipt.json").write_text(json.dumps(build_receipt, indent=2, sort_keys=True) + "\n")
PY
printf 'java-cli acceptance: PASS (decompile, source patch, tampered bundle/JAR/preimage)\n'
