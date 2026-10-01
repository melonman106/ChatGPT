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
