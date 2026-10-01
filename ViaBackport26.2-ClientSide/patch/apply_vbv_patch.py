#!/usr/bin/env python3
import pathlib
import re
import shutil
import sys

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

def java_files():
    for p in TARGET.rglob("*.java"):
        try:
            yield p, p.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue

# Initialize the registry once when Minecraft is constructed.
minecraft = None
for p, s in java_files():
    if re.search(r"class\s+Minecraft\b", s) and "static Minecraft" in s:
        minecraft = (p, s)
        break

if minecraft:
    p, s = minecraft
    if "VBVClient.init();" not in s:
        m = re.search(r"(?:public|private|protected)\s+Minecraft\s*\([^)]*\)\s*\{", s)
        if m:
            s = s[:m.end()] + "\n        com.melonman106.vbvclient.VBVClient.init();\n" + s[m.end():]
            p.write_text(s, encoding="utf-8")
            print("Inserted VBV initialization into", p)
        else:
            raise SystemExit("Minecraft found, but its constructor hook was not identified")
    else:
        print("VBV initialization hook already present.")
else:
    raise SystemExit("Minecraft class was not identified; VBV would never initialize")

# Add a native Mods button to the title screen when the generated source uses
# the modern Screen/Button API. This does not depend on Fabric or ModMenu.
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
            button -> net.minecraft.client.Minecraft.getInstance().setScreen(
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
