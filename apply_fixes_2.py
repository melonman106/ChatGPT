#!/usr/bin/env python3
"""Run from the repo root AFTER apply_fixes.py:  python3 apply_fixes2.py [repo_root]
Fixes the 'dry_foliage.png / core/text shader missing' crash at its root and makes the
build caches actually hit. Every edit asserts what it expects; re-running is safe."""
import pathlib, re, sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
WF = ROOT / ".github/workflows/build-eagler-vbv-client.yml"
if not WF.is_file():
    raise SystemExit(f"MISSING FILE: {WF}")
t = WF.read_text(encoding="utf-8")
notes = []

def once(old, new, label, marker=None):
    global t
    if marker and marker in t:
        return
    if t.count(old) != 1:
        raise SystemExit(f"EDIT FAILED ({label}): expected 1 match, found {t.count(old)}")
    t = t.replace(old, new)
    notes.append(label)

def regex(pattern, repl, label, expect, flags=0):
    global t
    t, n = re.subn(pattern, repl, t, flags=flags)
    if n != expect:
        raise SystemExit(f"EDIT FAILED ({label}): expected {expect} matches, found {n}")
    notes.append(label)

OVERLAY_SHA = "2ba7e3376891c64f8bf57f3687e05b8dbe1971a75475b6825449e5e5f96d71f3"

# ---- 1. ROOT CAUSE: pass the patcher's resource overlay to create-dev ------------------
once('          test "$(sha256sum "$resolved_skeleton" | cut -d\' \' -f1)" = "$resolved_skeleton_sha"\n',
     '          test "$(sha256sum "$resolved_skeleton" | cut -d\' \' -f1)" = "$resolved_skeleton_sha"\n'
     '\n'
     '          # The Eagler resource overlay rebuilds the full 19,515-file resource tree from the\n'
     '          # official jar (textures, colormaps, vanilla shaders + Eagler shader deltas).\n'
     '          # Without it the game has no dry_foliage.png and no core/text shader sources.\n'
     '          overlay="$legacy_root/inputs/resource-overlay-normal.zip"\n'
     f'          overlay_sha="{OVERLAY_SHA}"\n'
     '          external_root="$legacy_root/inputs/resources"\n'
     '          test -f "$overlay"\n'
     '          test "$(sha256sum "$overlay" | cut -d\' \' -f1)" = "$overlay_sha"\n'
     '          test -f "$external_root/assets/minecraft/lang/zh_cn.json"\n',
     "overlay inputs validated", marker='overlay_sha="')
once('            echo "EAGLER_PATCHER_MODE=$resolved_mode"\n',
     '            echo "EAGLER_PATCHER_MODE=$resolved_mode"\n'
     '            echo "EAGLER_RESOURCE_OVERLAY=$overlay"\n'
     '            echo "EAGLER_RESOURCE_OVERLAY_SHA256=$overlay_sha"\n'
     '            echo "EAGLER_EXTERNAL_RESOURCE_ROOT=$external_root"\n',
     "overlay env exported", marker="EAGLER_RESOURCE_OVERLAY=$overlay")
once('--expected-skeleton-sha256 "$EAGLER_EXPECTED_SKELETON_SHA256"',
     '--expected-skeleton-sha256 "$EAGLER_EXPECTED_SKELETON_SHA256"'
     '             --resource-overlay "$EAGLER_RESOURCE_OVERLAY"'
     '             --expected-resource-overlay-sha256 "$EAGLER_RESOURCE_OVERLAY_SHA256"'
     '             --external-resource-root "$EAGLER_EXTERNAL_RESOURCE_ROOT"',
     "create-dev passes the resource overlay", marker="--resource-overlay")

# verify the tree right after create-dev so a bad overlay fails in minutes, not after an hour
once("      - name: Verify Eagler u1 rendering patch inputs\n",
     "      - name: Verify reconstructed Minecraft resources\n"
     "        if: steps.repo_checkpoint.outputs.found != 'true' && inputs.compiled_zip_url == ''\n"
     "        run: |\n"
     "          set -euo pipefail\n"
     "          resources=\"$(find generated-client -type d -path '*/game/src/main/resources' -print -quit)\"\n"
     "          test -n \"$resources\"\n"
     "          count=\"$(find \"$resources\" -type f | wc -l)\"\n"
     "          echo \"Resource files after create-dev: $count\"\n"
     "          if [ \"$count\" -lt 19000 ]; then\n"
     "            echo \"::error::Only $count resource files; the resource overlay was not applied.\"\n"
     "            exit 1\n"
     "          fi\n"
     "          for f in \\\n"
     "            assets/minecraft/textures/colormap/dry_foliage.png \\\n"
     "            assets/minecraft/textures/colormap/foliage.png \\\n"
     "            assets/minecraft/textures/colormap/grass.png \\\n"
     "            assets/minecraft/shaders/core/text.vsh \\\n"
     "            assets/minecraft/shaders/core/text.fsh \\\n"
     "            assets/minecraft/shaders/core/gui.vsh \\\n"
     "            assets/minecraft/lang/en_us.json; do\n"
     "            test -f \"$resources/$f\" || { echo \"::error::Missing $f after overlay\"; exit 1; }\n"
     "          done\n"
     "          echo \"Resource overlay applied: textures, colormaps and shader sources present.\"\n"
     "      - name: Verify Eagler u1 rendering patch inputs\n",
     "post-create-dev resource verification", marker="Verify reconstructed Minecraft resources")

# the old curated restore must never fight the overlay (it re-added files the overlay removed
# and replaced Eagler-modified lang files with vanilla ones)
t_before = t
t = t.replace('cp -f "$temp_resources/assets/minecraft/lang/en_us.json" "$resources/assets/minecraft/lang/en_us.json"',
              'cp -n "$temp_resources/assets/minecraft/lang/en_us.json" "$resources/assets/minecraft/lang/en_us.json"')
if t != t_before:
    notes.append("en_us.json restore is no-clobber")
once('          for path in "${required_runtime_assets[@]}"; do\n',
     '          for path in "${required_runtime_assets[@]}"; do\n'
     '            # With the resource overlay the tree is authoritative; never re-add files it removed.\n'
     '            if [ -n "${EAGLER_RESOURCE_OVERLAY:-}" ] && [ ! -f "$resources/$path" ]; then\n'
     '              echo "Overlay tree intentionally omits $path; not restoring."\n'
     '              continue\n'
     '            fi\n',
     "curated restore skips overlay-removed files", marker="Overlay tree intentionally omits")
once('            cp -f "$temp_resources/$path" "$resources/$path"\n',
     '            cp -n "$temp_resources/$path" "$resources/$path"\n',
     "curated restore is no-clobber", marker='cp -n "$temp_resources/$path"')

# ---- 2. CACHES: one hash, computed once, used for every save AND restore -------------------
# (restore used a hash with patch/** and the save did not, and the checkpoint was restored from
#  v9 but saved to v8, so neither cache could ever hit)
HASH = ("hashFiles('ViaBackport26.2-ClientSide/src/main/java/**/*.java', "
        "'ViaBackport26.2-ClientSide/src/main/resources/**', "
        "'ViaBackport26.2-ClientSide/patch/**', 'ViaBackport26.2-ClientSide/pack.png', "
        "'ModsPack/**/*.zip', 'u1-1-source/**', 'U1-Patcher/portable-kit.zip')")
once("      - name: Restore root checkpoint\n",
     "      - name: Compute cache keys\n"
     "        id: keys\n"
     "        env:\n"
     f"          VBV_HASH: ${{{{ {HASH} }}}}\n"
     "        run: |\n"
     "          set -euo pipefail\n"
     "          echo \"hash=$VBV_HASH\" >> \"$GITHUB_OUTPUT\"\n"
     "          echo \"Cache hash: $VBV_HASH\"\n"
     "\n"
     "      - name: Restore root checkpoint\n",
     "single computed cache hash", marker="id: keys")
regex(r"key: eagler-vbv-root-checkpoint-v\d+-u1-1-\$\{\{ hashFiles\([^}]*\) \}\}",
      "key: eagler-vbv-root-checkpoint-v10-${{ steps.keys.outputs.hash }}",
      "checkpoint save/restore keys unified (v10)", 2) if "steps.keys.outputs.hash }}" not in t.split("Restore root checkpoint")[1][:600] else None
regex(r"key: vbv-teavm-wasm-v\d+-\$\{\{ hashFiles\([^}]*\) \}\}",
      "key: vbv-teavm-wasm-v8-${{ steps.keys.outputs.hash }}",
      "wasm cache save/restore keys unified (v8)", 2) if "vbv-teavm-wasm-v8-${{ steps.keys.outputs.hash }}" not in t else None
t = re.sub(r"vbv-teavm-wasm-v\d+-partial-", "vbv-teavm-wasm-v8-partial-", t)

# let a changed source tree still reuse Gradle dependencies and TeaVM's incremental state
once("          # Exact-key restore only. Never fall back to the old v3 cache.\n",
     "          # Fall back to the newest cache of this epoch so Gradle dependencies and TeaVM's\n"
     "          # incremental state are reused when only Java sources changed. A prefix match is\n"
     "          # not a cache-hit, so the WASM build still runs.\n"
     "          restore-keys: |\n"
     "            vbv-teavm-wasm-v8-\n",
     "wasm cache restore-keys prefix", marker="restore-keys: |\n            vbv-teavm-wasm-v8-")

# ---- 3. SPEED: no history clone, no --info log flood --------------------------------------
once("          fetch-depth: 0\n", "          fetch-depth: 1\n", "shallow checkout", marker="fetch-depth: 1\n")
t2 = t.replace("            --stacktrace \\\n            --info\n", "            --stacktrace\n")
if t2 != t:
    t = t2
    notes.append("TeaVM Gradle run without --info")

WF.write_text(t, encoding="utf-8")
print("Applied to", WF.relative_to(ROOT))
for n in notes:
    print("  -", n)
