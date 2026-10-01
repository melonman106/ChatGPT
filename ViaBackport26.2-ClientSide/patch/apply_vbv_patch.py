#!/usr/bin/env python3
import json
import pathlib
import re
import shutil
import sys
import zipfile

ROOT = pathlib.Path(sys.argv[1]).resolve()

# The VBV sources live in THIS repository (next to this script), not inside
# the generated project that ROOT points at.
CLIENT_SIDE = pathlib.Path(__file__).resolve().parent.parent
SOURCE = CLIENT_SIDE / "src/main/java/com/melonman106/vbvclient"

if not SOURCE.is_dir():
    raise SystemExit(f"VBV source directory missing: {SOURCE}")
if not ROOT.is_dir():
    raise SystemExit(f"Generated project directory missing: {ROOT}")

# create-dev writes a multi-module Gradle project straight into ROOT (the
# decompiled game code is in a module such as game/). Find the real source root
# by locating Minecraft.java instead of assuming a fixed layout.
def find_source_root():
    for p in sorted(ROOT.rglob("Minecraft.java")):
        parts = p.parts
        if "build" in parts:
            continue
        if p.parent.name == "client" and p.parent.parent.name == "minecraft":
            # .../<source root>/net/minecraft/client/Minecraft.java
            return p.parents[3]
    return None

TARGET = find_source_root()
if TARGET is None:
    print("Project tree (top levels) for debugging:")
    for p in sorted(ROOT.glob("*")) + sorted(ROOT.glob("*/*")):
        print("  ", p.relative_to(ROOT))
    raise SystemExit("Could not find net/minecraft/client/Minecraft.java in the generated project")

print("Using game source root:", TARGET)
DEST = TARGET / "com/melonman106/vbvclient"
DEST.mkdir(parents=True, exist_ok=True)
for src in SOURCE.glob("*.java"):
    shutil.copy2(src, DEST / src.name)
print("Copied", len(list(SOURCE.glob('*.java'))), "VBV files to", DEST)

# ---------------------------------------------------------------------------
# Import resource-pack mods from ModsPack/.
#
# The user only needs to upload ZIP resource packs. Each ZIP is unpacked into
# the generated client's private vbvclient/mods/<id>/ directory and a small
# Java registry is generated so the native Mods screen can discover it.
# ---------------------------------------------------------------------------
REPO_ROOT = CLIENT_SIDE.parent
MODS_PACK = REPO_ROOT / "ModsPack"
GENERATED_PACK_CLASS = DEST / "VBVGeneratedPackMods.java"
GENERATED_PACK_RESOURCE_ROOT = TARGET.parent / "resources" / "vbvclient" / "mods"

def java_string(value):
    return json.dumps(str(value), ensure_ascii=False)

def text_from_component(value):
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return "".join(text_from_component(v) for v in value)
    if isinstance(value, dict):
        if "text" in value:
            return text_from_component(value["text"])
        if "translate" in value:
            return str(value["translate"])
        if "extra" in value:
            return text_from_component(value["extra"])
    return str(value)

def safe_mod_id(stem):
    value = re.sub(r"[^a-zA-Z0-9._-]+", "-", stem.strip().lower())
    value = re.sub(r"-+", "-", value).strip("-._")
    return value or "resource-pack-mod"

def display_name(stem):
    value = re.sub(r"[_-]+", " ", stem).strip()
    return value or "Resource Pack Mod"

def find_zip_root(names):
    normalized = [n.replace("\\", "/").lstrip("/") for n in names]
    for name in normalized:
        if name == "pack.mcmeta":
            return ""
    for prefix in sorted({n.split("/", 1)[0] for n in normalized if "/" in n}):
        if f"{prefix}/pack.mcmeta" in normalized:
            return prefix + "/"
    return ""

def read_pack_metadata(zf, root):
    description = None
    meta_name = root + "pack.mcmeta"
    try:
        raw = zf.read(meta_name).decode("utf-8")
        meta = json.loads(raw)
        pack = meta.get("pack", {}) if isinstance(meta, dict) else {}
        if isinstance(pack, dict) and "description" in pack:
            description = text_from_component(pack["description"]).strip()
    except (KeyError, UnicodeDecodeError, json.JSONDecodeError):
        pass
    return description

def extract_resource_pack(zippath, mod_id):
    destination = GENERATED_PACK_RESOURCE_ROOT / mod_id
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(zippath) as zf:
        names = [n.replace("\\", "/") for n in zf.namelist()]
        root = find_zip_root(names)
        description = read_pack_metadata(zf, root)

        extracted = 0
        for raw_name in names:
            name = raw_name.lstrip("/")
            if not name.startswith(root):
                continue
            relative = name[len(root):]
            if not relative or relative.endswith("/"):
                continue

            # Keep the parts a client-side resource-pack mod can actually use.
            if not (
                relative == "pack.png"
                or relative == "pack.mcmeta"
                or relative == "assets"
                or relative.startswith("assets/")
                or relative == "data"
                or relative.startswith("data/")
            ):
                continue

            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            try:
                with zf.open(raw_name) as src, target.open("wb") as dst:
                    shutil.copyfileobj(src, dst)
                extracted += 1
            except KeyError:
                continue

        if extracted == 0:
            raise SystemExit(f"{zippath.name}: ZIP contains no supported resource-pack files")
        return description

pack_entries = []
if MODS_PACK.is_dir():
    zip_files = sorted(
        p for p in MODS_PACK.rglob("*.zip")
        if p.is_file() and "__MACOSX" not in p.parts
    )
    print("ModsPack ZIPs found:", len(zip_files))

    for zippath in zip_files:
        mod_id = safe_mod_id(zippath.stem)
        description = extract_resource_pack(zippath, mod_id)
        if not description:
            description = f"Resource-pack client mod imported from {zippath.name}."
        name = display_name(zippath.stem)

        icon = GENERATED_PACK_RESOURCE_ROOT / mod_id / "pack.png"
        if not icon.is_file():
            # Resource packs do not have to contain pack.png. Use the project's
            # standard icon so every imported mod still has an icon.
            fallback = CLIENT_SIDE / "pack.png"
            if fallback.is_file():
                shutil.copy2(fallback, icon)

        mod_json = GENERATED_PACK_RESOURCE_ROOT / mod_id / "mod.json"
        mod_json.write_text(
            json.dumps({
                "id": mod_id,
                "name": name,
                "version": "1.0.0-26.2",
                "authors": "Resource pack",
                "description": description,
                "pack": True
            }, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8"
        )

        pack_entries.append((mod_id, name, description))
        print(f"Imported resource-pack mod: {name} ({mod_id}) from {zippath.name}")
else:
    print("ModsPack/ does not exist; no uploaded resource-pack mods to import.")

generated_lines = [
    "package com.melonman106.vbvclient;",
    "",
    "/** Generated by apply_vbv_patch.py from ModsPack/*.zip. */",
    "public final class VBVGeneratedPackMods {",
    "    private VBVGeneratedPackMods() {}",
    "",
    "    public static void register() {",
]
for mod_id, name, description in pack_entries:
    generated_lines.extend([
        "        VBVClient.registerMod(new VBVModInfo(",
        f"            {java_string(mod_id)},",
        f"            {java_string(name)},",
        '            "1.0.0-26.2",',
        '            "Resource pack",',
        f"            {java_string(description)},",
        f"            {java_string('vbvclient/mods/' + mod_id + '/pack.png')},",
        "            true",
        "        ));",
    ])
generated_lines.extend([
    "    }",
    "}",
    "",
])
GENERATED_PACK_CLASS.write_text("\n".join(generated_lines), encoding="utf-8")
print("Generated pack-mod registry:", GENERATED_PACK_CLASS)
print("Imported pack mods:", len(pack_entries))

def java_files():
    for p in TARGET.rglob("*.java"):
        try:
            yield p, p.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue

# ---------------------------------------------------------------------------
# 1) Discover how this 26.2 decompile opens a screen. Mojang/Eagler renamed
#    Minecraft.setScreen, so read the real name from Minecraft.java.
# ---------------------------------------------------------------------------
minecraft = None
for p, s in java_files():
    if re.search(r"class\s+Minecraft\b", s) and "static Minecraft" in s:
        minecraft = (p, s)
        break
if not minecraft:
    raise SystemExit("Minecraft class was not identified; VBV would never initialize")

mc_path, mc_src = minecraft
SCREEN_METHOD_RE = re.compile(
    r"(?:public|protected|private)?\s*(?:final\s+)?void\s+(\w+)\(\s*(?:@Nullable\s+)?(?:final\s+)?Screen\s+\w+\s*\)"
)
screen_methods = [m.group(1) for m in SCREEN_METHOD_RE.finditer(mc_src)]
open_name = None
for preferred in ("setScreen", "setScreenAndShow", "openScreen", "showScreen"):
    if preferred in screen_methods:
        open_name = preferred
        break
if open_name is None and screen_methods:
    open_name = screen_methods[0]
if open_name is None:
    print("Methods in Minecraft.java that mention Screen:")
    for line in mc_src.splitlines():
        if "Screen" in line and "(" in line and ("void" in line or "public" in line):
            print("   ", line.strip())
    raise SystemExit("Could not find a method on Minecraft that takes a single Screen")
print("Screen-opening method on Minecraft:", open_name, "(candidates:", screen_methods, ")")

# ---------------------------------------------------------------------------
# 4) Native Armor HUD. Minecraft 26.2 uses the extraction-based Hud API, so
#    inject our renderer into Hud.extractRenderState.
# ---------------------------------------------------------------------------
HUD_CALL = "com.melonman106.vbvclient.VBVArmorHud.render(graphics);"

def find_method_end(text, method_start):
    brace = text.find("{", method_start)
    if brace < 0:
        return None
    depth = 0
    i = brace
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return None

hud_path = None
hud_src = None
for p, s in java_files():
    if re.search(r"class\s+Hud\b", s) and "GuiGraphicsExtractor" in s and "extractRenderState" in s:
        hud_path, hud_src = p, s
        break

if hud_path is None:
    raise SystemExit("Hud.java with GuiGraphicsExtractor/extractRenderState was not identified")

if HUD_CALL in hud_src:
    print("VBV Armor HUD hook already present.")
else:
    hud_match = re.search(
        r"(?:public|private|protected)\s+void\s+extractRenderState\s*"
        r"\(\s*GuiGraphicsExtractor\s+(\w+)\s*,\s*DeltaTracker\s+\w+\s*\)",
        hud_src
    )
    if not hud_match:
        raise SystemExit("Hud.extractRenderState signature was not identified")
    end = find_method_end(hud_src, hud_match.start())
    if end is None:
        raise SystemExit("Could not find end of Hud.extractRenderState")
    graphics_name = hud_match.group(1)
    hud_src = hud_src[:end] + "\n        com.melonman106.vbvclient.VBVArmorHud.render(" + graphics_name + ");\n    " + hud_src[end:]
    hud_path.write_text(hud_src, encoding="utf-8")
    print("Inserted Armor HUD hook into", hud_path)


nav = DEST / "VBVNav.java"
nav_src = nav.read_text(encoding="utf-8")
nav.write_text(nav_src.replace(".setScreen(screen)", f".{open_name}(screen)"), encoding="utf-8")

# ---------------------------------------------------------------------------
# 2) Initialize the registry from the Minecraft constructor.
#    The call MUST come after super(...)/this(...): the skeleton compiles with
#    --release 24, which forbids statements before a constructor call.
# ---------------------------------------------------------------------------
INIT_CALL = "com.melonman106.vbvclient.VBVClient.init();"

def end_of_ctor_call(text, start):
    """text[start:] begins with super(/this( ; return index just past the ';'."""
    i = text.index("(", start)
    depth = 0
    while i < len(text):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    j = text.index(";", i)
    return j + 1

if INIT_CALL in mc_src:
    print("VBV initialization hook already present.")
else:
    ctor_re = re.compile(r"(?:public|private|protected)\s+Minecraft\s*\([^)]*\)\s*(?:throws\s+[\w.,\s]+)?\{")
    inserted = False
    for m in ctor_re.finditer(mc_src):
        rest = mc_src[m.end():]
        call = re.match(r"\s*(super|this)\s*\(", rest)
        if call and call.group(1) == "this":
            continue  # delegating constructor; the target constructor will run init
        if call:
            pos = m.end() + end_of_ctor_call(rest, call.start(1))
        else:
            pos = m.end()
        mc_src = mc_src[:pos] + "\n        " + INIT_CALL + "\n" + mc_src[pos:]
        mc_path.write_text(mc_src, encoding="utf-8")
        print("Inserted VBV initialization into", mc_path)
        inserted = True
        break
    if not inserted:
        raise SystemExit("Minecraft found, but its constructor hook was not identified")

# ---------------------------------------------------------------------------
# 3) Native Mods button on the title screen.
# ---------------------------------------------------------------------------
title = None
for p, s in java_files():
    if re.search(r"class\s+TitleScreen\b", s) and "extends Screen" in s:
        title = (p, s)
        break

if title:
    p, s = title
    if "VBVModsScreen" not in s:
        m = re.search(r"protected\s+void\s+init\s*\([^)]*\)\s*\{", s)
        if not m:
            m = re.search(r"public\s+void\s+init\s*\([^)]*\)\s*\{", s)
        if m:
            button = """
        this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(
            net.minecraft.network.chat.Component.literal("Mods"),
            button -> com.melonman106.vbvclient.VBVNav.open(
                new com.melonman106.vbvclient.VBVModsScreen(this)
            )
        ).bounds(this.width / 2 - 100, this.height / 4 + 120, 200, 20).build());
"""
            s = s[:m.end()] + button + s[m.end():]
            p.write_text(s, encoding="utf-8")
            print("Inserted native Mods button into", p)
        else:
            print("TitleScreen found, but init hook was not identified.")
    else:
        print("Native Mods button already present.")
else:
    print("TitleScreen was not identified; the mod browser remains available as a native screen.")
