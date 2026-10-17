#!/usr/bin/env python3
"""Inject the recovered legacy Eagler mod map into the pre-TeaVM Java tree."""
from __future__ import annotations
import pathlib, sys

ROOT = pathlib.Path(sys.argv[1]).resolve()
MAP = pathlib.Path(sys.argv[2]).resolve()
if not ROOT.is_dir(): raise SystemExit(f"Generated project directory missing: {ROOT}")
if not MAP.is_file(): raise SystemExit(f"Recovered mod map missing: {MAP}")

game = None
for p in ROOT.rglob("Minecraft.java"):
    if "build" not in p.parts and p.parent.name == "client" and p.parent.parent.name == "minecraft":
        game = p.parents[3]; break
if game is None: raise SystemExit("Could not find generated Minecraft Java source root")

dest = game / "com/melonman106/vbvclient/VBVRecoveredLegacyMods.java"
dest.parent.mkdir(parents=True, exist_ok=True)
text = MAP.read_text(encoding="utf-8", errors="replace")
sections = {}
for name in ("Litematica", "MaLiLib", "Mod Menu"):
    start = text.find(f"## {name}")
    if start < 0: sections[name] = []; continue
    end = text.find("\n## ", start + 4)
    chunk = text[start:] if end < 0 else text[start:end]
    sections[name] = list(dict.fromkeys(line[2:].strip() for line in chunk.splitlines() if line.startswith("- ") and line[2:].strip()))

def js(s):
    return '"' + s.replace('\\','\\\\').replace('"','\\"').replace('\n','\\n').replace('\r','\\r') + '"'
lines = [
    "package com.melonman106.vbvclient;", "",
    "/** Generated from the reverse-mapped legacy Eaglercraft 26.2 client before TeaVM compilation. */",
    "public final class VBVRecoveredLegacyMods {", "    private VBVRecoveredLegacyMods() {}", ""
]
for field, section in (("LITEMATICA","Litematica"),("MALILIB","MaLiLib"),("MOD_MENU","Mod Menu")):
    lines.append(f"    public static final String[] {field} = new String[] {{")
    lines.extend(f"        {js(v)}," for v in sections[section])
    lines.extend(["    };", ""])
lines += [
    "    public static int recoveredCount() { return LITEMATICA.length + MALILIB.length + MOD_MENU.length; }", "",
    "    public static boolean contains(String symbol) {", "        if (symbol == null) return false;",
    "        for (String s : LITEMATICA) if (s.equals(symbol)) return true;",
    "        for (String s : MALILIB) if (s.equals(symbol)) return true;",
    "        for (String s : MOD_MENU) if (s.equals(symbol)) return true;",
    "        return false;", "    }", "}", ""
]
dest.write_text("\n".join(lines), encoding="utf-8")
print(f"Injected recovered legacy mod map into {dest}")
for name in ("Litematica","MaLiLib","Mod Menu"): print(f"{name}: {len(sections[name])} recovered entries")
print(f"Total recovered entries: {sum(map(len, sections.values()))}")
