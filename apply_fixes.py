#!/usr/bin/env python3
"""Run from the repo root:  python3 apply_fixes.py [repo_root]
Applies the remaining VBV fixes. Every edit asserts that the text it expects is
present (or already fixed), so nothing is silently skipped. Re-running is safe."""
import pathlib, re, sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
changed = []

def load(rel):
    p = ROOT / rel
    if not p.is_file():
        raise SystemExit(f"MISSING FILE: {rel}")
    return p, p.read_text(encoding="utf-8")

def save(p, text, note):
    p.write_text(text, encoding="utf-8")
    changed.append(f"{p.relative_to(ROOT)}: {note}")

def replace_once(text, old, new, label, done_marker=None):
    if done_marker and done_marker in text:
        return text
    if text.count(old) != 1:
        raise SystemExit(f"EDIT FAILED ({label}): expected exactly 1 match, found {text.count(old)}")
    return text.replace(old, new)

# ---------------------------------------------------------------- apply_vbv_patch.py
p, t = load("ViaBackport26.2-ClientSide/patch/apply_vbv_patch.py")

# 1. HUD hook: use the real GuiGraphicsExtractor parameter name instead of hardcoded "graphics".
start = t.find('HUD_CALL = "com.melonman106.vbvclient.VBVSimpleHud.render(graphics);"')
end_marker = 'print("Inserted Armor HUD hook at the end of", hud_path)'
if start >= 0:
    end = t.index(end_marker, start) + len(end_marker)
    old_head = t[start:end]
    # Keep the "find Hud.java" loop that sits between HUD_CALL and the hook.
    loop_start = old_head.index("hud_path = None")
    loop_end = old_head.index("if HUD_CALL in hud_src:")
    find_loop = old_head[loop_start:loop_end]
    new_block = (
        'HUD_CALL_PREFIX = "com.melonman106.vbvclient.VBVSimpleHud.render("\n\n'
        + find_loop +
        '''if HUD_CALL_PREFIX in hud_src:
    print("VBV HUD hook already present.")
else:
    hud_match = re.search(
        r"(?:public|private|protected)\\s+void\\s+extractItemHotbar\\s*\\([^)]*\\)\\s*\\{",
        hud_src
    )
    if not hud_match:
        raise SystemExit("Hud.extractItemHotbar method was not identified")

    param = re.search(r"GuiGraphicsExtractor\\s+(\\w+)", hud_match.group(0))
    if not param:
        raise SystemExit("Hud.extractItemHotbar has no GuiGraphicsExtractor parameter")
    HUD_CALL = f"{HUD_CALL_PREFIX}{param.group(1)});"

    end = find_method_end(hud_src, hud_match.start())
    if end is None:
        raise SystemExit("Could not find end of Hud.extractItemHotbar")

    hud_src = hud_src[:end] + "\\n        " + HUD_CALL + "\\n    " + hud_src[end:]
    hud_path.write_text(hud_src, encoding="utf-8")
    print("Inserted HUD hook at the end of", hud_path, "using parameter", param.group(1))'''
    )
    t = t[:start] + new_block + t[end:]
elif "HUD_CALL_PREFIX" not in t:
    raise SystemExit("EDIT FAILED (HUD hook): block not found")

# 2. Title-screen Mods button: top-left corner, cannot overlap vanilla button rows.
t = replace_once(
    t,
    ".bounds(this.width / 2 - 100, this.height / 4 + 120, 200, 20).build());",
    ".bounds(6, 6, 80, 20).build());",
    "title button", done_marker=".bounds(6, 6, 80, 20).build());")

# 3. Identify Minecraft.java by file name, not by a regex over every source file.
t = replace_once(
    t,
    'if re.search(r"class\\s+Minecraft\\b", s) and "static Minecraft" in s:',
    'if p.name == "Minecraft.java" and re.search(r"class\\s+Minecraft\\b", s):',
    "Minecraft detection", done_marker='p.name == "Minecraft.java"')
save(p, t, "HUD param name, title button position, Minecraft.java detection")

# ---------------------------------------------------------------- build workflow
p, t = load(".github/workflows/build-eagler-vbv-client.yml")

# Bump cache version: older runs may have saved *partial* output under the exact key.
t = t.replace("vbv-teavm-wasm-v3-", "vbv-teavm-wasm-v4-")

# Task detection: read the saved tasks file instead of piping gradlew into grep -q
# (pipefail + SIGPIPE made the condition unreliable).
pat = re.compile(
    r"(if|elif) \./gradlew :target_teavm_wasm_gc:tasks --all --console=plain --no-daemon 2>/dev/null \|\n\s+grep -qE ('[^']+'); then")
t, n = pat.subn(r"\1 grep -qE \2 wasm-gc-tasks.txt; then", t)
if n not in (0, 3):
    raise SystemExit(f"EDIT FAILED (task detection): expected 3 matches, got {n}")
if n == 0 and "wasm-gc-tasks.txt; then" not in t:
    raise SystemExit("EDIT FAILED (task detection): block not found")

# Remove the redundant second restore (same key as the first restore).
t, n = re.subn(
    r"      - name: Restore Gradle and TeaVM incremental cache\n.*?(?=      - name: Apply WASM-GC Netty transport source fix)",
    "", t, flags=re.S)
if n == 0 and "Restore Gradle and TeaVM incremental cache" in t:
    raise SystemExit("EDIT FAILED (duplicate restore)")

# Cache saving: exact key only after a successful build; partial progress under a unique key.
HASH = ("hashFiles('ViaBackport26.2-ClientSide/src/main/java/**/*.java', "
        "'ViaBackport26.2-ClientSide/src/main/resources/**/*.json', "
        "'ViaBackport26.2-ClientSide/patch/**', 'ModsPack/**/*.zip', "
        "'u1-1-source/**', 'U1-Patcher/portable-kit.zip')")
new_saves = f'''      - name: Save Gradle and WASM-GC cache (complete build)
        if: success() && inputs.compiled_zip_url == '' && steps.wasm_web_cache_restore.outputs.cache-hit != 'true'
        uses: actions/cache/save@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
            ModdedClientSource/.gradle
            ModdedClientSource/target_teavm_wasm_gc/build
          key: vbv-teavm-wasm-v4-${{{{ {HASH} }}}}

      - name: Save Gradle and WASM-GC cache (partial progress after failure)
        if: failure() && inputs.compiled_zip_url == '' && steps.wasm_web_cache_restore.outputs.cache-hit != 'true'
        uses: actions/cache/save@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
            ModdedClientSource/.gradle
            ModdedClientSource/target_teavm_wasm_gc/build
          # Unique key: a partial build must never occupy the exact key, or the
          # next run would see a cache hit and skip the WASM build entirely.
          key: vbv-teavm-wasm-v4-partial-${{{{ github.run_id }}}}

'''
t, n = re.subn(
    r"      - name: Save Gradle and WASM-GC cache, including partial progress\n.*?(?=      - name: Normalize imported precompiled web output)",
    lambda m: new_saves, t, flags=re.S)
if n == 0 and "(complete build)" not in t:
    raise SystemExit("EDIT FAILED (cache save)")

t = t.replace("%s %p\\\\n", "%s %p\\n")   # literal "\\n" -> real "\n" in printf formats
save(p, t, "cache v4 + safe saves, reliable task detection, redundant restore removed, printf fix")

# ---------------------------------------------------------------- package workflow
p, t = load(".github/workflows/package-eagler-vbv-single-html.yml")
marker = "      - name: Package standalone single HTML\n"
first = t.find(marker)
second = t.find(marker, first + 1)
if first >= 0 and second > first:
    t = t[:first] + t[second:]          # drop the duplicated first copy of the 5 packaging steps
elif first < 0:
    raise SystemExit("EDIT FAILED (package duplicates): step not found")
t = t.replace('Artifact name: `Eaglercraft-26.2-VBV-Single-HTML`',
              'Artifact name: \\`Eaglercraft-26.2-VBV-Single-HTML\\`')
save(p, t, "duplicate packaging steps removed, backticks escaped")

# ---------------------------------------------------------------- server mod
p, t = load("server-mods/ViaBackportVisuals/src/main/java/com/melonman106/vbfix/ViaBackportVisuals.java")
t = replace_once(
    t,
    "    private static final Map<Integer, Integer> ORIGINAL_MAPPINGS = new HashMap<>();",
    "    private static final Map<Block, BlockState> MINING_REFERENCE_CACHE = new HashMap<>();\n"
    "    private static final Map<Integer, Integer> ORIGINAL_MAPPINGS = new HashMap<>();",
    "MINING_REFERENCE_CACHE", done_marker="MINING_REFERENCE_CACHE = new HashMap")
for old, new in [
    ('"mud_red_nether_brick_stairs"', '"mud_brick_stairs"'),
    ('"tuff_red_nether_brick_stairs"', '"tuff_brick_stairs"'),
    ('"end_dark_prismarine_stairs"', '"end_stone_brick_stairs"'),
    ('"resin_red_nether_brick_stairs"', '"resin_brick_stairs"'),
    ('"cinnabar_red_nether_brick_stairs"', '"cinnabar_brick_stairs"'),
    ('"sulfur_red_nether_brick_stairs"', '"sulfur_brick_stairs"'),
    ('"red_smooth_sandstone_stairs"', '"mossy_stone_brick_stairs"'),
    ('"polished_blackdark_prismarine_stairs"', '"polished_blackstone_brick_stairs"'),
    ('"pale_oak_slab", "deepslate_brick_slab"', '"smooth_quartz_slab", "deepslate_brick_slab"'),
]:
    t = t.replace(old, new)
save(p, t, "missing cache field, mangled placeholder names, pale_oak_slab overlap")

print("Applied:")
for c in changed:
    print("  -", c)