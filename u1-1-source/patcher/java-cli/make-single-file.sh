#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if (($# != 4)) || [[ "$1" != --release || "$2" != --kit ]]; then
  echo "Usage: $0 --release --kit EXISTING_PORTABLE_KIT OUTPUT_JAR" >&2
  echo "The release path preserves bundled notices, excludes the official client JAR, and requires a sealed portable kit." >&2
  exit 2
fi
kit_dir=$3
output_jar=$4
[[ -d "$kit_dir" && ! -L "$kit_dir" ]] || { echo "ERROR: kit must be a real directory" >&2; exit 2; }
[[ ! -e "$output_jar" ]] || { echo "ERROR: output already exists: $output_jar" >&2; exit 2; }
kit_dir=$(cd "$kit_dir" && pwd -P)
output_parent=$(dirname -- "$output_jar")
mkdir -p -- "$output_parent"
output_parent=$(cd "$output_parent" && pwd -P)
output_name=$(basename -- "$output_jar")
[[ "$kit_dir" != "$output_parent" && "$output_name" == *.jar ]] || { echo "ERROR: invalid output location/name" >&2; exit 2; }

tmp_dir=$(mktemp -d "$output_parent/.single-file-build.XXXXXXXX")
trap 'if [[ -d "$tmp_dir" ]]; then rm -rf -- "$tmp_dir"; fi' EXIT

# Validate the passed kit first, then derive a release-facing documentation
# layer in a temporary directory. The source kit is never modified.
python3 - "$kit_dir" "$tmp_dir/release-kit" "$root_dir/release-package" <<'PY'
import hashlib
import pathlib
import shutil
import sys

root = pathlib.Path(sys.argv[1])
staging = pathlib.Path(sys.argv[2])
templates = pathlib.Path(sys.argv[3])
manifest = root / 'SHA256SUMS'
if not manifest.is_file() or manifest.is_symlink():
    raise SystemExit('ERROR: kit SHA256SUMS is absent or a symlink')
expected = {}
for line in manifest.read_text(encoding='utf-8').splitlines():
    digest, sep, name = line.partition('  ')
    if len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest) or not sep or name in expected:
        raise SystemExit('ERROR: malformed kit SHA256SUMS')
    expected[name] = digest
actual = {}
for path in sorted(root.rglob('*')):
    if path.is_symlink():
        raise SystemExit(f'ERROR: kit symlink is forbidden: {path}')
    if path.is_dir():
        continue
    if not path.is_file():
        raise SystemExit(f'ERROR: unsupported kit entry: {path}')
    relative = path.relative_to(root).as_posix()
    parts = pathlib.PurePosixPath(relative).parts
    lower = relative.lower()
    if (lower.endswith(('.pem', '.p12', '.pfx', '.jks', '.keystore'))
            or any(token in pathlib.PurePosixPath(lower).name for token in ('secret', 'credential', 'token', 'private-key'))):
        raise SystemExit(f'ERROR: secret or private-key material may not be embedded: {relative}')
    if ('.toolchain' in parts or 'logs' in parts or 'toolchain.properties' in parts or relative.endswith('.log')
            or relative.lower().endswith('.jar') and relative not in {
                'eaglercraft-26.2-u1-patcher-gui.jar',
                'eaglercraft-26.2-java-cli.jar',
                'inputs/vineflower-1.12.0.jar',
            }):
        raise SystemExit(f'ERROR: runtime or unapproved JAR file may not be embedded: {relative}')
    if relative != 'SHA256SUMS':
        actual[relative] = hashlib.sha256(path.read_bytes()).hexdigest()
if actual != {name: digest for name, digest in expected.items() if name != 'SHA256SUMS'}:
    raise SystemExit('ERROR: kit files differ from SHA256SUMS')
required = {'eaglercraft-26.2-u1-patcher-gui.jar', 'eaglercraft-26.2-java-cli.jar'}
if not required <= actual.keys():
    raise SystemExit('ERROR: kit is missing the GUI or CLI JAR')

# Replace only assistant-authored provisional entry docs. License/notice
# files, source inputs, media, launchers, and every executable are copied.
shutil.copytree(root, staging, symlinks=False)
(staging / 'START-HERE-LOCAL.md').unlink()
shutil.copy2(templates / 'START-HERE.md', staging / 'START-HERE.md')
shutil.copy2(templates / 'RELEASE-NOTICES.md', staging / 'RELEASE-NOTICES.md')
portable = staging / 'PORTABLE.md'
text = portable.read_text(encoding='utf-8')
replacements = {
'''The default distribution contains no Minecraft JAR, decompiled
Minecraft source or Minecraft assets. The explicit
`--local-media` opt-in below adds local-test-only media and six unresolved
Minecraft resource files; that package is for this PC only and is not cleared
for redistribution. No option packages the official Minecraft client JAR.
Supply the exact official 26.2 client JAR and the source-patch,
verified project-skeleton, and resource-overlay inputs. Vineflower can be included
using `--local-vineflower` below, with its Apache 2.0 license and exact JAR hash
checked first. The accepted project-skeleton archive SHA-256 is
''': '''The release package contains the maintainer-authorized Normal patcher inputs,
including the resource overlay and media files used by its GUI workflow. It
contains no official Minecraft client JAR; supply the exact official 26.2
client JAR that you are authorized to use. The source-patch, verified
project-skeleton, and resource-overlay inputs are pinned below. Vineflower is
included with its Apache 2.0 license, notice, and exact JAR hash. The accepted
project-skeleton archive SHA-256 is
''',
'''The extracted tree includes the source-side notice inventory at
`third_party/skeleton/THIRD_PARTY_NOTICES.md`; its presence does not clear the
remaining provenance or redistribution review.
''': '''The extracted tree includes the source-side notice inventory at
`third_party/skeleton/THIRD_PARTY_NOTICES.md`; keep it with the package and
preserve all applicable third-party terms.
''',
'''To add the local-only overlay, sounds and music EPKs, and six external resource inputs,
also pass `--local-media`:
''': '''This release package includes the Normal overlay, sounds and music EPKs,
and six external resource inputs used by the GUI's automatic discovery:
''',
'''`START-HERE-LOCAL.md` explains where to place the official
client JAR and is included only in a `--local-media` package; the client JAR
itself is intentionally not included. The package includes
''': '''`START-HERE.md` explains where to place the official client JAR; the client
JAR itself is intentionally not included. The package includes
''',
'''Do not share or redistribute a `--local-media` package or its outputs.

All three modes use the Normal profile. The local-media package contains no
mod source archives and remains local-only; do not share or redistribute it.
''': '''Keep the package's license and notice files with any copy or derivative.
The official client JAR and any user-supplied private keys remain outside this
package. All three modes use the Normal profile.
''',
}
for old, new in replacements.items():
    if old not in text:
        raise SystemExit('ERROR: expected release documentation block is missing')
    text = text.replace(old, new, 1)
portable.write_text(text, encoding='utf-8')
gui = staging / 'GUI.md'
gui_text = gui.read_text(encoding='utf-8')
old_gui = '''The **Content profile** selector offers **Normal** in Source project,
Standalone HTML, and Isolated Web App modes. The patcher does not include the
official Minecraft client JAR. Local-only kits may include other game resources
and audio archives; check `START-HERE-LOCAL.md` before sharing a kit. Java and
Node are installed separately by the launcher when needed.
'''
new_gui = '''The **Content profile** selector offers **Normal** in Source project,
Standalone HTML, and Isolated Web App modes. The patcher does not include the
official Minecraft client JAR. This release package includes the Normal build
inputs and audio archives used by its workflow; see `START-HERE.md` and
`RELEASE-NOTICES.md`, and keep all notices with the package. Java and Node are
installed separately by the launcher when needed.
'''
if old_gui not in gui_text:
    raise SystemExit('ERROR: expected release GUI documentation block is missing')
gui.write_text(gui_text.replace(old_gui, new_gui, 1), encoding='utf-8')

# Recreate the manifest after the release-doc transformation. SHA256SUMS is
# intentionally self-excluding, matching the portable-kit contract.
files = {}
for path in sorted(staging.rglob('*')):
    if path.is_symlink():
        raise SystemExit(f'ERROR: generated kit symlink: {path}')
    if path.is_file() and path.name != 'SHA256SUMS':
        files[path.relative_to(staging).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
(staging / 'SHA256SUMS').write_text(''.join(f'{digest}  {name}\n' for name, digest in sorted(files.items())), encoding='utf-8')
print(f'release kit files: {len(files) + 1}')
PY

python3 - "$tmp_dir/release-kit" "$tmp_dir/portable-kit.zip" <<'PY'
import hashlib
import pathlib
import sys
import zipfile

root = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2])
manifest = root / 'SHA256SUMS'
expected = {}
for line in manifest.read_text(encoding='utf-8').splitlines():
    digest, sep, name = line.partition('  ')
    if len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest) or not sep or name in expected:
        raise SystemExit('ERROR: malformed release SHA256SUMS')
    expected[name] = digest
actual = {}
for path in sorted(root.rglob('*')):
    if path.is_symlink():
        raise SystemExit(f'ERROR: release kit symlink: {path}')
    if path.is_file() and path.name != 'SHA256SUMS':
        actual[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
if actual != {name: digest for name, digest in expected.items() if name != 'SHA256SUMS'}:
    raise SystemExit('ERROR: release kit files differ from SHA256SUMS')
with zipfile.ZipFile(out, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for relative in sorted(actual.keys() | {'SHA256SUMS'}):
        archive.write(root / relative, relative)
print(f'kit files: {len(actual) + 1}')
PY
sha256sum "$tmp_dir/portable-kit.zip" | cut -d ' ' -f 1 > "$tmp_dir/portable-kit.sha256"
mkdir -p "$tmp_dir/classes"
javac --release 17 -d "$tmp_dir/classes" "$root_dir/src/main/java/com/eaglercraft/patcher/SingleFileLauncher.java"
printf 'Manifest-Version: 1.0\nMain-Class: com.eaglercraft.patcher.SingleFileLauncher\nEagler-Distribution: release-packaging\nEagler-Content-Profile: Normal\nEagler-Official-Client-JAR: excluded\nEagler-Notices: bundled\n' > "$tmp_dir/manifest.txt"
jar --create --file "$tmp_dir/single-file.jar" --date 2020-01-01T00:00:00Z \
  --manifest "$tmp_dir/manifest.txt" -C "$tmp_dir/classes" . \
  -C "$tmp_dir" portable-kit.zip -C "$tmp_dir" portable-kit.sha256
mv -T --no-clobber -- "$tmp_dir/single-file.jar" "$output_parent/$output_name"
[[ ! -e "$tmp_dir/single-file.jar" ]] || { echo "ERROR: output promotion failed" >&2; exit 2; }
printf 'release single-file JAR: %s\n' "$output_parent/$output_name"
sha256sum -- "$output_parent/$output_name"
