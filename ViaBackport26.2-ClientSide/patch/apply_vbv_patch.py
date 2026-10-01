#!/usr/bin/env python3
import pathlib
import re
import shutil
import sys

ROOT = pathlib.Path(sys.argv[1]).resolve()
SOURCE = ROOT / "ViaBackport26.2-ClientSide/src/main/java/com/melonman106/vbvclient"
TARGET = ROOT / "Eaglercraft-26.2-u1"

if not SOURCE.exists():
    raise SystemExit("VBV source directory missing")
if not TARGET.exists():
    raise SystemExit("Generated Eaglercraft-26.2-u1 directory missing")

DEST = TARGET / "src/main/java/com/melonman106/vbvclient"
DEST.mkdir(parents=True, exist_ok=True)
for src in SOURCE.glob("*.java"):
    shutil.copy2(src, DEST / src.name)

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
            print("Minecraft found, but constructor hook was not identified.")
    else:
        print("VBV initialization hook already present.")
else:
    print("Minecraft class was not identified; registry sources were still copied.")

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
