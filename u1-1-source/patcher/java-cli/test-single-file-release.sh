#!/usr/bin/env bash
set -euo pipefail

if (($# != 2)); then
  echo "Usage: $0 RELEASE_JAR RECEIPT_JSON" >&2
  exit 2
fi
jar_path=$(cd "$(dirname "$1")" && pwd -P)/$(basename "$1")
receipt=$2
[[ -f "$jar_path" && "$jar_path" == *.jar ]] || { echo "ERROR: release JAR is missing" >&2; exit 2; }
mkdir -p -- "$(dirname "$receipt")"
work=$(mktemp -d "$(dirname "$receipt")/.single-file-release-test.XXXXXXXX")
trap 'rm -rf -- "$work"' EXIT

jar tf "$jar_path" | grep -Fxq META-INF/MANIFEST.MF
manifest=$(unzip -p "$jar_path" META-INF/MANIFEST.MF)
grep -Fq 'Eagler-Distribution: release-packaging' <<<"$manifest"
grep -Fq 'Eagler-Content-Profile: Normal' <<<"$manifest"
grep -Fq 'Eagler-Official-Client-JAR: excluded' <<<"$manifest"
grep -Fq 'Eagler-Notices: bundled' <<<"$manifest"
! jar tf "$jar_path" | grep -Eiq 'minecraft(-|_)?26\.2.*\.jar|client.*\.jar|\.toolchain|setup-tools\.log|gui\.log'

zip_hash=$(unzip -p "$jar_path" portable-kit.zip | sha256sum | cut -d ' ' -f1)
embedded_hash=$(unzip -p "$jar_path" portable-kit.sha256 | tr -d '[:space:]')
[[ "$zip_hash" == "$embedded_hash" ]] || { echo "ERROR: embedded archive checksum mismatch" >&2; exit 1; }
java -jar "$jar_path" --install-dir "$work/installed"
(cd "$work/installed" && sha256sum -c SHA256SUMS)
! find "$work/installed" -type f \( -iname '*minecraft*client*.jar' -o -iname 'client*.jar' \) -print -quit | grep -q .
java -jar "$work/installed/eaglercraft-26.2-u1-patcher-gui.jar" --self-test > "$work/gui-self-test.txt"
grep -Fq 'gui-self-test: PASS' "$work/gui-self-test.txt"

python3 - "$jar_path" "$work/installed" "$receipt" "$zip_hash" "$embedded_hash" <<'PY'
import hashlib
import json
import pathlib
import sys
import zipfile

jar_path, installed, receipt, zip_hash, embedded_hash = sys.argv[1:]
installed = pathlib.Path(installed)
with zipfile.ZipFile(jar_path) as outer:
    with zipfile.ZipFile(outer.open('portable-kit.zip')) as payload:
        packaged = sorted(name for name in payload.namelist() if not name.endswith('/'))
extracted = sorted(str(path.relative_to(installed)).replace('\\', '/') for path in installed.rglob('*') if path.is_file())
if packaged != extracted:
    raise SystemExit(f'installed file list differs: packaged={len(packaged)} extracted={len(extracted)}')
gui = installed / 'eaglercraft-26.2-u1-patcher-gui.jar'
data = {
    'gate': 'eaglercraft-26.2-release-single-file-v1',
    'status': 'pass',
    'jar': {'path': str(pathlib.Path(jar_path).resolve()), 'sha256': hashlib.sha256(pathlib.Path(jar_path).read_bytes()).hexdigest()},
    'embedded_portable_kit_sha256': zip_hash,
    'embedded_digest_matches': zip_hash == embedded_hash,
    'extracted_file_count': len(extracted),
    'sha256sums_verified': True,
    'gui_wiring_self_test': True,
    'official_client_jar': 'excluded',
    'manifest_distribution': 'release-packaging',
    'content_profile': 'Normal',
    'notice_files_retained': True,
    'gui_sha256': hashlib.sha256(gui.read_bytes()).hexdigest(),
}
pathlib.Path(receipt).write_text(json.dumps(data, sort_keys=True, indent=2) + '\n', encoding='utf-8')
PY
echo "PASS: release manifest, embedded checksum, fresh extraction, SHA256SUMS, GUI wiring self-test, and official-JAR exclusion"
