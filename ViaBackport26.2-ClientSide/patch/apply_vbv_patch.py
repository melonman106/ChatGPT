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

candidates = []
for p in TARGET.rglob("*.java"):
    try:
        s = p.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        continue
    if re.search(r"class\s+Minecraft\b", s) and "static Minecraft" in s:
        candidates.append(p)

if not candidates:
    print("VBV classes copied; no Minecraft entry class matched. Build stopped only if integration is required by the target source.")
    sys.exit(0)

minecraft = candidates[0]
text = minecraft.read_text(encoding="utf-8")

if "VBVClient.init();" not in text:
    m = re.search(r"(?:public|private|protected)\s+Minecraft\s*\([^)]*\)\s*\{", text)
    if m:
        text = text[:m.end()] + "\n        com.melonman106.vbvclient.VBVClient.init();\n" + text[m.end():]
        minecraft.write_text(text, encoding="utf-8")
        print("Inserted VBV init into", minecraft)
    else:
        print("VBV classes copied; Minecraft constructor was not found.")
else:
    print("VBV init hook already present.")
