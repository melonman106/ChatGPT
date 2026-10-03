#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd "$root_dir/../.." && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/eagler-patcher-portable.XXXXXX")
cleanup() { rm -rf "$test_dir"; }
trap cleanup EXIT

dist="$test_dir/dist"
# Compile only into the temporary test directory so this focused check never
# rebuilds or overwrites the checked-in/local patcher artifacts.
classes="$test_dir/classes"
mkdir -p "$classes"
find "$root_dir/src/main/java" -name '*.java' -print0 \
  | xargs -0 javac --release 17 -d "$classes"
printf 'Manifest-Version: 1.0\nMain-Class: com.eaglercraft.patcher.Main\nCreated-By: eaglercraft-26.2-java-cli-u1\n' \
  > "$test_dir/manifest.txt"
mkdir -p "$dist"
jar --create --file "$dist/eaglercraft-26.2-java-cli.jar" \
  --date 2020-01-01T00:00:00Z --manifest "$test_dir/manifest.txt" -C "$classes" .
cp -- "$root_dir/eagler-patcher" "$root_dir/eagler-patcher.cmd" \
  "$root_dir/eagler-patcher-gui" "$root_dir/eagler-patcher-gui.cmd" \
  "$root_dir/PORTABLE.md" "$root_dir/GUI.md" "$dist/"
chmod 755 "$dist/eagler-patcher" "$dist/eagler-patcher-gui"
(cd "$dist" && sha256sum eaglercraft-26.2-java-cli.jar eagler-patcher eagler-patcher.cmd \
  eagler-patcher-gui eagler-patcher-gui.cmd PORTABLE.md GUI.md > SHA256SUMS)
"$dist/eagler-patcher" --help > "$test_dir/help.txt"
grep -Fq 'build-standalone' "$test_dir/help.txt"
grep -Fq 'project-skeleton-u1-nettyfix.zip' "$test_dir/help.txt"
"$dist/eagler-patcher-gui" --self-test > "$test_dir/gui-self-test.txt"
grep -Fq 'gui-self-test: PASS' "$test_dir/gui-self-test.txt"
if grep -Fq 'build.sh' "$dist/eagler-patcher-gui.cmd"; then
  echo "GUI Windows launcher must use the prebuilt JAR and never invoke build.sh" >&2
  exit 1
fi
(cd "$dist" && sha256sum -c SHA256SUMS >/dev/null)

workspace="$test_dir/workspace"
html="$test_dir/game.html"
v5_skeleton="$repo_dir/output/u1-netty-link-fix-20261001/project-skeleton-accepted-baseline-nettyfix.zip"
v5_sha256=a9172bdd903ea65afd4c87cdb29b500c237c823fdd94789c2e90561b1b049469
bundle="$repo_dir/output/u1-server-pack-fix-20260930/source-patch-bundle.zip"
bundle_sha256=fd944e9cabbebbf4bddce8a39e1b233b3c7d0cd4f860f9cae37550a3fcfa8b02
media_fixture_repo="$test_dir/media-repo"
portable_source_fixture="$media_fixture_repo/patcher/java-cli"
mkdir -p "$portable_source_fixture"
mkdir -p "$media_fixture_repo/iwa"
mkdir -p "$media_fixture_repo/patcher/release-inputs"
cp -- "$repo_dir/patcher/release-inputs/zh-cn-delta-26.2.json" \
  "$media_fixture_repo/patcher/release-inputs/zh-cn-delta-26.2.json"
cp -- "$repo_dir/patcher/release-inputs/audio_matrix.py" \
  "$repo_dir/patcher/release-inputs/epk_rebuild.py" \
  "$repo_dir/patcher/release-inputs/fetch_audio_objects.py" \
  "$repo_dir/patcher/release-inputs/node_audio_rebuild.mjs" \
  "$repo_dir/patcher/release-inputs/audio-epk-metadata-26.2.json" \
  "$media_fixture_repo/patcher/release-inputs/"
cp -a -- "$repo_dir/patcher/release-inputs/vendor" \
  "$media_fixture_repo/patcher/release-inputs/"
cp -- "$repo_dir/iwa/README.md" "$media_fixture_repo/iwa/README.md"
cp -- "$root_dir/build.sh" "$root_dir/make-portable.sh" \
  "$root_dir/eagler-patcher" "$root_dir/eagler-patcher.cmd" \
  "$root_dir/eagler-patcher-gui" "$root_dir/eagler-patcher-gui.cmd" \
  "$root_dir/PORTABLE.md" "$root_dir/GUI.md" \
  "$root_dir/START-HERE-LOCAL.md" "$root_dir/local-media-verify.sh" \
  "$portable_source_fixture/"
cp -a -- "$root_dir/src" "$portable_source_fixture/"
cp -a -- "$root_dir/third_party" "$portable_source_fixture/"
mkdir -p "$portable_source_fixture/bootstrap"
cp -- "$root_dir/bootstrap/bootstrap-linux-x86_64.sh" \
  "$root_dir/bootstrap/launch-gui-linux-x86_64.sh" \
  "$root_dir/bootstrap/README.md" "$portable_source_fixture/bootstrap/"
cp -a -- "$root_dir/bootstrap/macos" "$root_dir/bootstrap/windows" "$portable_source_fixture/bootstrap/"

mkdir -p "$media_fixture_repo/output/u1-chunk-caller-diagnostic-20260924/web" \
  "$media_fixture_repo/game/src/main/resources" \
  "$media_fixture_repo/target_teavm/build/web"
mkdir -p "$media_fixture_repo/output/normal-clean-source-20260929"
cp -- "$repo_dir/output/normal-clean-source-20260929/resource-overlay-normal.zip" \
  "$media_fixture_repo/output/normal-clean-source-20260929/resource-overlay-normal.zip"
cp -- "$repo_dir/output/u1-chunk-caller-diagnostic-20260924/web/sounds.epk" \
  "$media_fixture_repo/output/u1-chunk-caller-diagnostic-20260924/web/sounds.epk"
cp -- "$repo_dir/target_teavm/build/web/music.epk" \
  "$media_fixture_repo/target_teavm/build/web/music.epk"
media_resource_paths=(
  assets/minecraft/font/eagler_server_symbols.hex
  assets/minecraft/font/eagler_server_symbols.zip
  assets/minecraft/font/unifont.zip
  assets/minecraft/font/unifont_pua.zip
  assets/minecraft/lang/zh_cn.json
  assets/minecraft/sounds.json
)
for relative in "${media_resource_paths[@]}"; do
  mkdir -p "$media_fixture_repo/game/src/main/resources/$(dirname -- "$relative")"
  cp -- "$repo_dir/game/src/main/resources/$relative" \
    "$media_fixture_repo/game/src/main/resources/$relative"
done
portable_local="$test_dir/portable-local-bundle"
vineflower="$repo_dir/output/eaglercraft-26.2-u1-patcher-local-complete-20260924/inputs/vineflower-1.12.0.jar"
"$portable_source_fixture/make-portable.sh" "$portable_local" \
  --local-bundle "$bundle" "$v5_skeleton" --local-vineflower "$vineflower" \
  --local-media \
  > "$test_dir/local-bundle-package.txt" 2> "$test_dir/local-bundle-package.err"
grep -Fq 'LOCAL-ONLY: NOT CLEARED FOR REDISTRIBUTION' "$test_dir/local-bundle-package.err"
[[ -f "$portable_local/source-patch-bundle.zip" \
  && -f "$portable_local/project-skeleton-u1-nettyfix.zip" ]]
[[ -f "$portable_local/eaglercraft-26.2-u1-patcher-gui.jar" ]]
[[ -f "$portable_local/inputs/vineflower-1.12.0.jar" \
  && -f "$portable_local/inputs/Vineflower-LICENSE.md" ]]
[[ -f "$portable_local/START-HERE-LOCAL.md" \
  && -x "$portable_local/local-media-verify.sh" ]]
cmp -s "$repo_dir/iwa/README.md" "$portable_local/iwa/README.md"
[[ -f "$portable_local/inputs/resource-overlay-normal.zip" \
  && -f "$portable_local/inputs/sounds.epk" \
  && -f "$portable_local/inputs/sounds.epk.sha256" ]]
[[ -f "$portable_local/inputs/music.epk" \
  && -f "$portable_local/inputs/music.epk.sha256" ]]
[[ ! -e "$portable_local/inputs/minecraft-26.2-client.jar" ]]
[[ "$(sha256sum "$portable_local/inputs/vineflower-1.12.0.jar" | cut -d ' ' -f 1)" \
  = 1dfcfe974395734fa467ce620661c7623d05ba83670de0529b1fbd63ff548b9d ]]
cmp -s "$root_dir/third_party/Vineflower-LICENSE.md" "$portable_local/inputs/Vineflower-LICENSE.md"
cmp -s "$root_dir/third_party/Vineflower-NOTICE.md" "$portable_local/inputs/Vineflower-NOTICE.md"
[[ -x "$portable_local/bootstrap/bootstrap-linux-x86_64.sh" \
  && -x "$portable_local/bootstrap/launch-gui-linux-x86_64.sh" ]]
[[ -x "$portable_local/bootstrap/macos/launch-gui-macos.command" \
  && -f "$portable_local/bootstrap/windows/eagler-patcher-windows-x86_64.cmd" ]]
[[ -f "$portable_local/bootstrap/windows/test-bootstrap-windows.ps1" ]]
(cd "$portable_local" && sha256sum -c SHA256SUMS >/dev/null)
[[ "$(wc -l < "$portable_local/SHA256SUMS")" -eq \
  "$(find "$portable_local" -type f ! -name SHA256SUMS | wc -l)" ]]
"$portable_local/local-media-verify.sh" "$portable_local" \
  > "$test_dir/local-media-verify.txt"
grep -Fq '"status":"pass"' "$test_dir/local-media-verify.txt"
grep -Fq 'Normal only' \
  "$test_dir/local-media-verify.txt"
cmp -s "$media_fixture_repo/output/normal-clean-source-20260929/resource-overlay-normal.zip" \
  "$portable_local/inputs/resource-overlay-normal.zip"
cmp -s "$media_fixture_repo/output/u1-chunk-caller-diagnostic-20260924/web/sounds.epk" \
  "$portable_local/inputs/sounds.epk"
[[ "$(<"$portable_local/inputs/sounds.epk.sha256")" \
  = 94bc8bfcf4132c52c6d5f61f3f92e50532a6fad1f5bc901ee25a462db8ba4fc6 ]]
for relative in "${media_resource_paths[@]}"; do
  cmp -s "$media_fixture_repo/game/src/main/resources/$relative" \
    "$portable_local/inputs/resources/$relative"
done
grep -Fq 'inputs/resource-overlay-normal.zip' "$portable_local/SHA256SUMS"
grep -Fq 'inputs/sounds.epk.sha256' "$portable_local/SHA256SUMS"
grep -Fq 'inputs/resources/assets/minecraft/sounds.json' "$portable_local/SHA256SUMS"
grep -Fq 'local-media-verify.sh' "$portable_local/SHA256SUMS"
grep -Fq 'iwa/README.md' "$portable_local/SHA256SUMS"
if find "$portable_local" -type f \( -iname 'minecraft-26.2-client.jar' \
  -o -iname 'minecraft-client*.jar' \) \
  -print -quit | grep -q .; then
  echo "local media kit must not contain an official Minecraft client JAR" >&2
  exit 1
fi
java -jar "$portable_local/eaglercraft-26.2-u1-patcher-gui.jar" --self-test \
  > "$test_dir/direct-gui-jar-self-test.txt"
grep -Fq 'gui-self-test: PASS' "$test_dir/direct-gui-jar-self-test.txt"
"$root_dir/bootstrap/test-bootstrap.sh" "$portable_local/eaglercraft-26.2-u1-patcher-gui.jar"
grep -Fq "$bundle_sha256  source-patch-bundle.zip" "$portable_local/SHA256SUMS"
grep -Fq "$v5_sha256  project-skeleton-u1-nettyfix.zip" "$portable_local/SHA256SUMS"
grep -Fq 'inputs/vineflower-1.12.0.jar' "$portable_local/SHA256SUMS"
grep -Fq 'inputs/zh-cn-delta-26.2.json' "$portable_local/SHA256SUMS"
grep -Fq 'tools/audio/audio-epk-metadata-26.2.json' "$portable_local/SHA256SUMS"
grep -Fq 'tools/audio/fetch_audio_objects.py' "$portable_local/SHA256SUMS"
grep -Fq 'tools/audio/node_audio_rebuild.mjs' "$portable_local/SHA256SUMS"
grep -Fq 'tools/audio/vendor/pako-2.1.0/ZLIB-LICENSE' "$portable_local/SHA256SUMS"

portable_default="$test_dir/portable-default"
"$portable_source_fixture/make-portable.sh" "$portable_default" \
  > "$test_dir/default-package.txt" 2> "$test_dir/default-package.err"
! grep -Fq 'LOCAL-ONLY' "$test_dir/default-package.err"
portable_bundle_only="$test_dir/portable-bundle-only"
"$portable_source_fixture/make-portable.sh" "$portable_bundle_only" \
  --local-bundle "$bundle" "$v5_skeleton" \
  > "$test_dir/bundle-only-package.txt" 2> "$test_dir/bundle-only-package.err"
grep -Fq 'LOCAL-ONLY: NOT CLEARED FOR REDISTRIBUTION' "$test_dir/bundle-only-package.err"
[[ ! -e "$portable_default/source-patch-bundle.zip" \
  && ! -e "$portable_default/project-skeleton-u1-nettyfix.zip" \
  && ! -e "$portable_default/inputs/vineflower-1.12.0.jar" \
  && ! -e "$portable_default/inputs/resource-overlay-normal.zip" \
  && ! -e "$portable_default/inputs/sounds.epk" \
  && ! -e "$portable_default/START-HERE-LOCAL.md" \
  && ! -e "$portable_default/local-media-verify.sh" ]]
[[ -f "$portable_default/eaglercraft-26.2-u1-patcher-gui.jar" ]]
[[ -f "$portable_default/tools/audio/epk_rebuild.py" ]]
[[ -f "$portable_default/tools/audio/node_audio_rebuild.mjs" ]]
[[ -x "$portable_default/bootstrap/bootstrap-linux-x86_64.sh" \
  && -x "$portable_default/bootstrap/launch-gui-linux-x86_64.sh" ]]
[[ -x "$portable_default/bootstrap/macos/launch-gui-macos.command" \
  && -f "$portable_default/bootstrap/windows/eagler-patcher-windows-x86_64.cmd" ]]
[[ -f "$portable_default/bootstrap/windows/test-bootstrap-windows.ps1" ]]
! grep -Eq 'source-patch-bundle|project-skeleton-u1-nettyfix' "$portable_default/SHA256SUMS"
(cd "$portable_default" && sha256sum -c SHA256SUMS >/dev/null)

cp -- "$media_fixture_repo/output/normal-clean-source-20260929/resource-overlay-normal.zip" \
  "$test_dir/tampered-local-overlay.zip"
python3 - "$test_dir/tampered-local-overlay.zip" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
data = bytearray(path.read_bytes())
data[-1] ^= 1
path.write_bytes(data)
PY
cp -- "$test_dir/tampered-local-overlay.zip" \
  "$media_fixture_repo/output/normal-clean-source-20260929/resource-overlay-normal.zip"
set +e
"$portable_source_fixture/make-portable.sh" "$test_dir/rejected-local-media-package" \
  --local-media > "$test_dir/rejected-local-media.stdout" \
  2> "$test_dir/rejected-local-media.stderr"
tampered_local_media_status=$?
set -e
[[ $tampered_local_media_status -eq 2 && ! -e "$test_dir/rejected-local-media-package" ]]
grep -Fq 'local media SHA-256 mismatch' "$test_dir/rejected-local-media.stderr"

cp -- "$bundle" "$test_dir/tampered-package-bundle.zip"
python3 - "$test_dir/tampered-package-bundle.zip" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
data = bytearray(path.read_bytes())
data[-1] ^= 1
path.write_bytes(data)
PY
set +e
"$portable_source_fixture/make-portable.sh" "$test_dir/rejected-package" \
  --local-bundle "$test_dir/tampered-package-bundle.zip" "$v5_skeleton" \
  > "$test_dir/rejected-package.stdout" 2> "$test_dir/rejected-package.stderr"
tampered_package_status=$?
set -e
[[ $tampered_package_status -eq 2 && ! -e "$test_dir/rejected-package" ]]
grep -Fq 'source patch bundle SHA-256 mismatch' "$test_dir/rejected-package.stderr"

cp -- "$vineflower" "$test_dir/tampered-vineflower.jar"
python3 - "$test_dir/tampered-vineflower.jar" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
data = bytearray(path.read_bytes())
data[-1] ^= 1
path.write_bytes(data)
PY
set +e
"$portable_source_fixture/make-portable.sh" "$test_dir/rejected-vineflower-package" \
  --local-vineflower "$test_dir/tampered-vineflower.jar" \
  > "$test_dir/rejected-vineflower.stdout" 2> "$test_dir/rejected-vineflower.stderr"
tampered_vineflower_status=$?
set -e
[[ $tampered_vineflower_status -eq 2 && ! -e "$test_dir/rejected-vineflower-package" ]]
grep -Fq 'Vineflower SHA-256 mismatch' "$test_dir/rejected-vineflower.stderr"

java17=/usr/lib/jvm/java-17-openjdk-amd64/bin/java
node=${NODE_EXECUTABLE:-$(command -v node)}
test_classes="$test_dir/test-classes"
mkdir -p "$test_classes"
javac --release 17 -cp "$dist/eaglercraft-26.2-java-cli.jar" -d "$test_classes" \
  "$root_dir/src/test/java/com/eaglercraft/patcher/ProjectSkeletonContractTest.java" \
  "$root_dir/src/test/java/com/eaglercraft/patcher/JavaRuntimeContractTest.java"
java -cp "$dist/eaglercraft-26.2-java-cli.jar:$test_classes" \
  com.eaglercraft.patcher.ProjectSkeletonContractTest "$v5_skeleton" "$test_dir/skeleton-contract"
java -cp "$dist/eaglercraft-26.2-java-cli.jar:$test_classes" \
  com.eaglercraft.patcher.JavaRuntimeContractTest "$repo_dir/mojang-source"

patched_source="$test_dir/patched-source"
java -jar "$dist/eaglercraft-26.2-java-cli.jar" apply-patch \
  --base-source "$repo_dir/mojang-source" --output "$patched_source" \
  --patch-bundle "$bundle" --expected-bundle-sha256 "$bundle_sha256" \
  > "$test_dir/apply-patch.json"
grep -Fq '"status":"applied"' "$test_dir/apply-patch.json"
grep -Fq '"final_file_count":7142' "$test_dir/apply-patch.json"
grep -Fq '"final_manifest_sha256":"a5924750314f5de9316e9f67b28ce1decee31e27d67b260760b8176cd269243a"' \
  "$test_dir/apply-patch.json"

set +e
java -jar "$dist/eaglercraft-26.2-java-cli.jar" apply-patch \
  --base-source "$repo_dir/mojang-source" --output "$test_dir/wrong-bundle-pin-output" \
  --patch-bundle "$bundle" --expected-bundle-sha256 0000000000000000000000000000000000000000000000000000000000000000 \
  > "$test_dir/wrong-bundle-pin.stdout" 2> "$test_dir/wrong-bundle-pin.stderr"
wrong_bundle_pin_status=$?
cp -- "$bundle" "$test_dir/tampered-source-patch-bundle.zip"
python3 - "$test_dir/tampered-source-patch-bundle.zip" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
data = bytearray(path.read_bytes())
data[-1] ^= 1
path.write_bytes(data)
PY
java -jar "$dist/eaglercraft-26.2-java-cli.jar" apply-patch \
  --base-source "$repo_dir/mojang-source" --output "$test_dir/tampered-bundle-output" \
  --patch-bundle "$test_dir/tampered-source-patch-bundle.zip" \
  --expected-bundle-sha256 "$bundle_sha256" \
  > "$test_dir/tampered-bundle.stdout" 2> "$test_dir/tampered-bundle.stderr"
tampered_bundle_status=$?
set -e
[[ $wrong_bundle_pin_status -eq 2 && $tampered_bundle_status -eq 2 ]]
grep -Fq 'not the accepted bundle identity' "$test_dir/wrong-bundle-pin.stderr"
grep -Fq 'source patch bundle SHA-256 mismatch' "$test_dir/tampered-bundle.stderr"
[[ ! -e "$test_dir/wrong-bundle-pin-output" && ! -e "$test_dir/tampered-bundle-output" ]]

set +e
java -jar "$dist/eaglercraft-26.2-java-cli.jar" build-standalone \
  --jar /etc/hosts --output "$workspace" --vineflower /etc/hosts \
  --java17 "$java17" \
  --patch-bundle "$bundle" --expected-bundle-sha256 "$bundle_sha256" \
  --project-skeleton "$v5_skeleton" --expected-skeleton-sha256 "$v5_sha256" \
  --resource-overlay "$repo_dir/output/normal-clean-source-20260929/resource-overlay-normal.zip" \
  --expected-resource-overlay-sha256 2ba7e3376891c64f8bf57f3687e05b8dbe1971a75475b6825449e5e5f96d71f3 \
  --external-resource-root "$repo_dir/game/src/main/resources" \
  --java25 /usr/lib/jvm/java-25-openjdk-amd64/bin/java --node "$node" \
  --npm /etc/hosts \
  --sounds-epk /etc/hosts --expected-sounds-epk-sha256 0000000000000000000000000000000000000000000000000000000000000000 \
  --music-epk /etc/hosts --expected-music-epk-sha256 0000000000000000000000000000000000000000000000000000000000000000 \
  --standalone-output "$html" >"$test_dir/stdout" 2>"$test_dir/stderr"
status=$?
set -e
[[ $status -eq 2 ]]
if ! grep -Fq 'sounds EPK input SHA-256 mismatch' "$test_dir/stderr"; then
  sed -n '1,40p' "$test_dir/stderr" >&2
  exit 1
fi
[[ ! -e "$workspace" && ! -e "$html" ]]

# Pin the v5 archive itself, independent of later JAR/version checks. The
# accepted archive reaches the deliberately invalid client-JAR size check;
# incorrect caller pins and byte-tampered copies must stop at the skeleton gate.
set +e
java -jar "$dist/eaglercraft-26.2-java-cli.jar" create-dev \
  --jar /etc/hosts --output "$test_dir/accepted-skeleton-output" --vineflower /etc/hosts \
  --java17 "$java17" --patch-bundle "$bundle" --expected-bundle-sha256 "$bundle_sha256" \
  --project-skeleton "$v5_skeleton" --expected-skeleton-sha256 "$v5_sha256" \
  >"$test_dir/accepted-skeleton.stdout" 2>"$test_dir/accepted-skeleton.stderr"
accepted_status=$?
java -jar "$dist/eaglercraft-26.2-java-cli.jar" create-dev \
  --jar /etc/hosts --output "$test_dir/wrong-pin-output" --vineflower /etc/hosts \
  --java17 "$java17" --patch-bundle "$bundle" --expected-bundle-sha256 "$bundle_sha256" \
  --project-skeleton "$v5_skeleton" --expected-skeleton-sha256 0000000000000000000000000000000000000000000000000000000000000000 \
  >"$test_dir/wrong-pin.stdout" 2>"$test_dir/wrong-pin.stderr"
wrong_pin_status=$?
cp -- "$v5_skeleton" "$test_dir/tampered-skeleton.zip"
python3 - "$test_dir/tampered-skeleton.zip" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
data = bytearray(path.read_bytes())
data[-1] ^= 1
path.write_bytes(data)
PY
java -jar "$dist/eaglercraft-26.2-java-cli.jar" create-dev \
  --jar /etc/hosts --output "$test_dir/tampered-skeleton-output" --vineflower /etc/hosts \
  --java17 "$java17" --patch-bundle "$bundle" --expected-bundle-sha256 "$bundle_sha256" \
  --project-skeleton "$test_dir/tampered-skeleton.zip" --expected-skeleton-sha256 "$v5_sha256" \
  >"$test_dir/tampered-skeleton.stdout" 2>"$test_dir/tampered-skeleton.stderr"
tampered_status=$?
set -e
[[ $accepted_status -eq 2 ]]
grep -Fq 'official client JAR size mismatch' "$test_dir/accepted-skeleton.stderr"
[[ $wrong_pin_status -eq 2 ]]
grep -Fq 'not the accepted v5 archive identity' "$test_dir/wrong-pin.stderr"
[[ $tampered_status -eq 2 ]]
grep -Fq 'project skeleton SHA-256 mismatch' "$test_dir/tampered-skeleton.stderr"
[[ ! -e "$test_dir/accepted-skeleton-output" && ! -e "$test_dir/wrong-pin-output" \
  && ! -e "$test_dir/tampered-skeleton-output" ]]
printf 'portable launcher: PASS (opt-in local sidecars, fixed hashes, SHA256SUMS, default unchanged, tamper rejection, no failed promotion)\n'
