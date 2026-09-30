#!/usr/bin/env python3
import json
import pathlib
import sys

COLORS = [
    "white", "orange", "magenta", "light_blue",
    "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue",
    "brown", "green", "red", "black",
]

def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

def stair_models(root, color, material):
    tex = f"minecraft:block/{color}_{material}"
    base = root / "assets" / "viabackportvisuals" / "models" / "block"
    for suffix, parent in (
        ("stairs", "minecraft:block/stairs"),
        ("stairs_inner", "minecraft:block/inner_stairs"),
        ("stairs_outer", "minecraft:block/outer_stairs"),
    ):
        write(base / f"{color}_{material}_{suffix}.json", {
            "parent": parent,
            "textures": {"bottom": tex, "side": tex, "top": tex},
        })

def slab_models(root, color, material):
    tex = f"minecraft:block/{color}_{material}"
    base = root / "assets" / "viabackportvisuals" / "models" / "block"
    write(base / f"{color}_{material}_slab.json", {
        "parent": "minecraft:block/slab",
        "textures": {"bottom": tex, "side": tex, "top": tex},
    })
    write(base / f"{color}_{material}_slab_top.json", {
        "parent": "minecraft:block/slab_top",
        "textures": {"bottom": tex, "side": tex, "top": tex},
    })
    write(base / f"{color}_{material}_slab_double.json", {
        "parent": "minecraft:block/cube_all",
        "textures": {"all": tex},
    })

def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: generate_models.py OUTPUT_DIR")
    root = pathlib.Path(sys.argv[1])
    for color in COLORS:
        for material in ("wool", "concrete"):
            stair_models(root, color, material)
            slab_models(root, color, material)
    print("Generated 192 isolated VBV stair/slab models.")

if __name__ == "__main__":
    main()
