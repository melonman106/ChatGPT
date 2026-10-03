#!/usr/bin/env bash
set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd "$root_dir/../.." && pwd)
accepted_source_bundle_sha256=fd944e9cabbebbf4bddce8a39e1b233b3c7d0cd4f860f9cae37550a3fcfa8b02
accepted_v5_skeleton_sha256=a9172bdd903ea65afd4c87cdb29b500c237c823fdd94789c2e90561b1b049469
accepted_vineflower_sha256=1dfcfe974395734fa467ce620661c7623d05ba83670de0529b1fbd63ff548b9d
accepted_resource_overlay_sha256=2ba7e3376891c64f8bf57f3687e05b8dbe1971a75475b6825449e5e5f96d71f3
accepted_sounds_epk_sha256=94bc8bfcf4132c52c6d5f61f3f92e50532a6fad1f5bc901ee25a462db8ba4fc6
accepted_music_epk_sha256=f01cdaf62a9686438998b11ffed5407a1b890e14960c63f71b57401d02216a4e
output_dir="$root_dir/dist/eaglercraft-26.2-u1-patcher"
source_bundle=""
v5_skeleton=""
vineflower=""
local_media=0
local_media_sources=()
local_media_targets=()
local_media_hashes=()
output_seen=0

usage() {
  echo "Usage: $0 [OUTPUT_DIR] [--local-bundle SOURCE_PATCH_BUNDLE V5_SKELETON] [--local-vineflower VINEFLOWER_JAR] [--local-media]" >&2
}

while (($#)); do
  case "$1" in
    --local-bundle)
      if [[ -n "$source_bundle" || $# -lt 3 ]]; then
        usage
        exit 2
      fi
      if [[ -z "$2" || -z "$3" ]]; then
        usage
        exit 2
      fi
      source_bundle=$2
      v5_skeleton=$3
      shift 3
      ;;
    --local-vineflower)
      if [[ -n "$vineflower" || $# -lt 2 || -z "$2" ]]; then
        usage
        exit 2
      fi
      vineflower=$2
      shift 2
      ;;
    --local-media)
      if ((local_media)); then
        usage
        exit 2
      fi
      local_media=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    -* )
      usage
      exit 2
      ;;
    *)
      if ((output_seen)); then
        usage
        exit 2
      fi
      output_dir=$1
      output_seen=1
      shift
      ;;
  esac
done

if ((local_media)) || [[ -n "$source_bundle" ]]; then
  printf '\n*** LOCAL-ONLY: NOT CLEARED FOR REDISTRIBUTION. This package contains unresolved build inputs. ***\n\n' >&2
fi
if [[ -n "$source_bundle" ]]; then
  for input in "$source_bundle" "$v5_skeleton"; do
    if [[ ! -f "$input" || ! -r "$input" ]]; then
      echo "ERROR: local-bundle input must be a readable regular file: $input" >&2
      exit 2
    fi
  done
  actual_source_bundle_sha256=$(sha256sum -- "$source_bundle" | cut -d ' ' -f 1)
  if [[ "$actual_source_bundle_sha256" != "$accepted_source_bundle_sha256" ]]; then
    echo "ERROR: source patch bundle SHA-256 mismatch (expected $accepted_source_bundle_sha256, got $actual_source_bundle_sha256)" >&2
    exit 2
  fi
  actual_v5_skeleton_sha256=$(sha256sum -- "$v5_skeleton" | cut -d ' ' -f 1)
  if [[ "$actual_v5_skeleton_sha256" != "$accepted_v5_skeleton_sha256" ]]; then
    echo "ERROR: project skeleton SHA-256 mismatch (expected $accepted_v5_skeleton_sha256, got $actual_v5_skeleton_sha256)" >&2
    exit 2
  fi
fi

if [[ -n "$vineflower" ]]; then
  if [[ ! -f "$vineflower" || ! -r "$vineflower" ]]; then
    echo "ERROR: Vineflower input must be a readable regular file: $vineflower" >&2
    exit 2
  fi
  actual_vineflower_sha256=$(sha256sum -- "$vineflower" | cut -d ' ' -f 1)
  if [[ "$actual_vineflower_sha256" != "$accepted_vineflower_sha256" ]]; then
    echo "ERROR: Vineflower SHA-256 mismatch (expected $accepted_vineflower_sha256, got $actual_vineflower_sha256)" >&2
    exit 2
  fi
fi

if ((local_media)); then
  local_media_sources+=(
    "$repo_dir/output/normal-clean-source-20260929/resource-overlay-normal.zip"
    "$repo_dir/output/u1-chunk-caller-diagnostic-20260924/web/sounds.epk"
    "$repo_dir/target_teavm/build/web/music.epk"
    "$repo_dir/game/src/main/resources/assets/minecraft/font/eagler_server_symbols.hex"
    "$repo_dir/game/src/main/resources/assets/minecraft/font/eagler_server_symbols.zip"
    "$repo_dir/game/src/main/resources/assets/minecraft/font/unifont.zip"
    "$repo_dir/game/src/main/resources/assets/minecraft/font/unifont_pua.zip"
    "$repo_dir/game/src/main/resources/assets/minecraft/lang/zh_cn.json"
    "$repo_dir/game/src/main/resources/assets/minecraft/sounds.json"
  )
  local_media_targets+=(
    "inputs/resource-overlay-normal.zip"
    "inputs/sounds.epk"
    "inputs/music.epk"
    "inputs/resources/assets/minecraft/font/eagler_server_symbols.hex"
    "inputs/resources/assets/minecraft/font/eagler_server_symbols.zip"
    "inputs/resources/assets/minecraft/font/unifont.zip"
    "inputs/resources/assets/minecraft/font/unifont_pua.zip"
    "inputs/resources/assets/minecraft/lang/zh_cn.json"
    "inputs/resources/assets/minecraft/sounds.json"
  )
  local_media_hashes+=(
    "$accepted_resource_overlay_sha256"
    "$accepted_sounds_epk_sha256"
    "$accepted_music_epk_sha256"
    "29d147c67366f6ccc9ea1efbc2a63b72c3c886769c34ec057fb17d2bcea21f8e"
    "15a5363bc8762eff093f99e7a81a7575c81477145684a3da5b82468ab6d43989"
    "aea3e9918b0d31de6f94623080f04c31c8c16a5b1a2e8d99ab39f1acdddd30f7"
    "65388145333f6ceffe2be67790183a77d45b97236248ac1eab3befe6a17979d7"
    "47d66d5b25a5ff1c40a4a6a179b44b165517af1863617ecac5cd03e751f49ff2"
    "84fe52cea79f67441ac00df61836e15d5c9ae98c406cebba557213b80f59c3b5"
  )
  for index in "${!local_media_sources[@]}"; do
    input=${local_media_sources[$index]}
    expected=${local_media_hashes[$index]}
    if [[ ! -f "$input" || ! -r "$input" || -L "$input" ]]; then
      echo "ERROR: --local-media requires a readable regular file (not a symlink): $input" >&2
      exit 2
    fi
    actual=$(sha256sum -- "$input" | cut -d ' ' -f 1)
    if [[ "$actual" != "$expected" ]]; then
      echo "ERROR: local media SHA-256 mismatch for $input (expected $expected, got $actual)" >&2
      exit 2
    fi
  done
fi

if [[ -e "$output_dir" ]]; then
  echo "ERROR: output must be absent: $output_dir" >&2
  exit 2
fi
final_output_dir=$output_dir
mkdir -p -- "$(dirname -- "$final_output_dir")"
staging_dir=$(mktemp -d "$(dirname -- "$final_output_dir")/.eagler-patcher.XXXXXXXX")
trap 'if [[ -d "$staging_dir" ]]; then rm -rf -- "$staging_dir"; fi' EXIT
output_dir=$staging_dir
"$root_dir/build.sh" >/dev/null
cp -- "$root_dir/build/eaglercraft-26.2-java-cli.jar" "$output_dir/"
cp -- "$root_dir/build/eaglercraft-26.2-u1-patcher-gui.jar" "$output_dir/"
mkdir -p "$output_dir/tools/audio"
audio_metadata="$repo_dir/patcher/release-inputs/audio-epk-metadata-26.2.json"
audio_metadata_sha256=69cc6cfeccf235d71ffb307aa9150c784690c48f510be3036dd42f7e3050ce98
if [[ ! -f "$audio_metadata" || -L "$audio_metadata" \
    || "$(sha256sum -- "$audio_metadata" | cut -d ' ' -f 1)" != "$audio_metadata_sha256" ]]; then
  echo "ERROR: pinned audio metadata is missing or changed" >&2
  exit 2
fi
cp -- "$repo_dir/patcher/release-inputs/audio_matrix.py" \
  "$repo_dir/patcher/release-inputs/epk_rebuild.py" \
  "$repo_dir/patcher/release-inputs/fetch_audio_objects.py" \
  "$repo_dir/patcher/release-inputs/node_audio_rebuild.mjs" \
  "$audio_metadata" \
  "$output_dir/tools/audio/"
mkdir -p "$output_dir/tools/audio/vendor/pako-2.1.0"
pako_source="$repo_dir/patcher/release-inputs/vendor/pako-2.1.0/pako.min.js"
pako_sha256=ede2693a4a6a5126b9d35669062b358ecab6ae7b9b86a1cf302feb45a8514907
if [[ ! -f "$pako_source" || -L "$pako_source" \
    || "$(sha256sum -- "$pako_source" | cut -d ' ' -f 1)" != "$pako_sha256" ]]; then
  echo "ERROR: pinned audio compressor is missing or changed" >&2
  exit 2
fi
cp -- "$pako_source" \
  "$repo_dir/patcher/release-inputs/vendor/pako-2.1.0/package.json" \
  "$repo_dir/patcher/release-inputs/vendor/pako-2.1.0/LICENSE" \
  "$repo_dir/patcher/release-inputs/vendor/pako-2.1.0/ZLIB-LICENSE" \
  "$repo_dir/patcher/release-inputs/vendor/pako-2.1.0/SOURCE.md" \
  "$output_dir/tools/audio/vendor/pako-2.1.0/"
cp -- "$root_dir/eagler-patcher" "$root_dir/eagler-patcher.cmd" \
  "$root_dir/eagler-patcher-gui" "$root_dir/eagler-patcher-gui.cmd" \
  "$root_dir/PORTABLE.md" "$root_dir/GUI.md" "$output_dir/"
mkdir -p "$output_dir/iwa"
cp -- "$repo_dir/iwa/README.md" "$output_dir/iwa/README.md"
mkdir -p "$output_dir/bootstrap"
cp -- "$root_dir/bootstrap/bootstrap-linux-x86_64.sh" \
  "$root_dir/bootstrap/launch-gui-linux-x86_64.sh" \
  "$root_dir/bootstrap/README.md" "$output_dir/bootstrap/"
mkdir -p "$output_dir/bootstrap/macos" "$output_dir/bootstrap/windows"
cp -- "$root_dir/bootstrap/macos/bootstrap-macos.sh" \
  "$root_dir/bootstrap/macos/launch-gui-macos.sh" \
  "$root_dir/bootstrap/macos/launch-gui-macos.command" \
  "$root_dir/bootstrap/macos/README.md" "$output_dir/bootstrap/macos/"
cp -- "$root_dir/bootstrap/windows/bootstrap-windows-x86_64.ps1" \
  "$root_dir/bootstrap/windows/launch-gui-windows-x86_64.ps1" \
  "$root_dir/bootstrap/windows/eagler-patcher-windows-x86_64.cmd" \
  "$root_dir/bootstrap/windows/test-bootstrap-windows.ps1" \
  "$root_dir/bootstrap/windows/README.md" "$output_dir/bootstrap/windows/"
if [[ -n "$source_bundle" ]]; then
  cp -- "$source_bundle" "$output_dir/source-patch-bundle.zip"
  cp -- "$v5_skeleton" "$output_dir/project-skeleton-u1-nettyfix.zip"
  mkdir -p "$output_dir/inputs"
  delta_source="$repo_dir/patcher/release-inputs/zh-cn-delta-26.2.json"
  delta_sha256=7a62ab0fa8c8eda019dbbf56c271383bf7a7dd7b0b24d6c7cf8eda300e7b1b4d
  if [[ ! -f "$delta_source" || -L "$delta_source" \
      || "$(sha256sum -- "$delta_source" | cut -d ' ' -f 1)" != "$delta_sha256" ]]; then
    echo "ERROR: pinned Chinese catalog delta is missing or changed" >&2
    exit 2
  fi
  cp -- "$delta_source" "$output_dir/inputs/zh-cn-delta-26.2.json"
fi
if [[ -n "$vineflower" ]]; then
  mkdir -p "$output_dir/inputs"
  cp -- "$vineflower" "$output_dir/inputs/vineflower-1.12.0.jar"
  cp -- "$root_dir/third_party/Vineflower-LICENSE.md" \
    "$root_dir/third_party/Vineflower-NOTICE.md" "$output_dir/inputs/"
fi
verify_packaged_copy() {
  local actual
  actual=$(sha256sum -- "$1" | cut -d ' ' -f 1)
  if [[ "$actual" != "$2" ]]; then
    echo "ERROR: packaged $3 changed after input validation (expected $2, got $actual)" >&2
    exit 2
  fi
}
if ((local_media)); then
  cp -- "$root_dir/START-HERE-LOCAL.md" "$output_dir/START-HERE-LOCAL.md"
  cp -- "$root_dir/local-media-verify.sh" "$output_dir/local-media-verify.sh"
  chmod 755 "$output_dir/local-media-verify.sh"
  for index in "${!local_media_sources[@]}"; do
    input=${local_media_sources[$index]}
    target=${local_media_targets[$index]}
    expected=${local_media_hashes[$index]}
    mkdir -p -- "$output_dir/$(dirname -- "$target")"
    cp -- "$input" "$output_dir/$target"
    verify_packaged_copy "$output_dir/$target" "$expected" "$target"
    if ! cmp -s -- "$input" "$output_dir/$target"; then
      echo "ERROR: packaged local media bytes differ from input: $target" >&2
      exit 2
    fi
    verify_packaged_copy "$input" "$expected" "source input $target after copy"
  done
  printf '%s\n' "$accepted_sounds_epk_sha256" > "$output_dir/inputs/sounds.epk.sha256"
  printf '%s\n' "$accepted_music_epk_sha256" > "$output_dir/inputs/music.epk.sha256"
  if [[ "$(<"$output_dir/inputs/sounds.epk.sha256")" != "$accepted_sounds_epk_sha256" ]]; then
    echo "ERROR: generated sounds EPK pin differs from the accepted SHA-256" >&2
    exit 2
  fi
fi
if [[ -n "$source_bundle" ]]; then
  verify_packaged_copy "$output_dir/source-patch-bundle.zip" "$accepted_source_bundle_sha256" "source patch bundle"
  verify_packaged_copy "$output_dir/project-skeleton-u1-nettyfix.zip" "$accepted_v5_skeleton_sha256" "project skeleton"
  verify_packaged_copy "$output_dir/inputs/zh-cn-delta-26.2.json" "$delta_sha256" "Chinese catalog delta"
fi
if [[ -n "$vineflower" ]]; then
  verify_packaged_copy "$output_dir/inputs/vineflower-1.12.0.jar" "$accepted_vineflower_sha256" "Vineflower"
fi
chmod 755 "$output_dir/eagler-patcher"
chmod 755 "$output_dir/eagler-patcher-gui"
chmod 755 "$output_dir/bootstrap/bootstrap-linux-x86_64.sh"
chmod 755 "$output_dir/bootstrap/launch-gui-linux-x86_64.sh"
chmod 755 "$output_dir/bootstrap/macos/bootstrap-macos.sh" \
  "$output_dir/bootstrap/macos/launch-gui-macos.sh" \
  "$output_dir/bootstrap/macos/launch-gui-macos.command"
(
  cd "$output_dir"
  find . -type f ! -name SHA256SUMS -printf '%P\0' | sort -z | xargs -0 sha256sum > SHA256SUMS
  sha256sum -c SHA256SUMS >/dev/null
)
if [[ -e "$final_output_dir" ]]; then
  echo "ERROR: output appeared during packaging: $final_output_dir" >&2
  exit 2
fi
mv -T --no-clobber -- "$output_dir" "$final_output_dir"
if [[ -d "$output_dir" ]]; then
  echo "ERROR: output appeared during package promotion: $final_output_dir" >&2
  exit 2
fi
printf 'portable directory: %s\n' "$final_output_dir"
