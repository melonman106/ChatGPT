#!/usr/bin/env python3
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "resourcepacks/ViaBackportVisuals-26.2-VBPlus-26.3-Companion"
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/vbv-eagler-pack"

COLORS = [
    "white", "orange", "magenta", "light_blue",
    "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue",
    "brown", "green", "red", "black",
]

def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

def emit_stairs(color, material):
    block = f"{color}_{material}"
    out_models = OUT / "assets/viabackportvisuals/models/display"
    out_items = OUT / "assets/viabackportvisuals/items/display"

    for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        for half in ("bottom", "top"):
            x = 0 if half == "bottom" else 180
            for shape in ("straight", "inner_left", "inner_right", "outer_left", "outer_right"):
                if shape == "straight":
                    parent = "minecraft:block/stairs"
                elif shape.startswith("inner"):
                    parent = "minecraft:block/inner_stairs"
                else:
                    parent = "minecraft:block/outer_stairs"

                model_id = f"{block}_stairs_{facing}_{half}_{shape}"
                wrapper = out_models / f"{model_id}.json"
                write_json(wrapper, {
                    "parent": parent,
                    "textures": {
                        "bottom": f"minecraft:block/{block}",
                        "side": f"minecraft:block/{block}",
                        "top": f"minecraft:block/{block}"
                    },
                    "x": x,
                    "y": y,
                    "uvlock": True
                })
                write_json(out_items / f"{model_id}.json", {
                    "model": {
                        "type": "minecraft:model",
                        "model": f"viabackportvisuals:display/{model_id}"
                    }
                })

def emit_slabs(color, material):
    block = f"{color}_{material}"
    out_models = OUT / "assets/viabackportvisuals/models/display"
    out_items = OUT / "assets/viabackportvisuals/items/display"

    for slab_type, parent in (
        ("bottom", "minecraft:block/slab"),
        ("top", "minecraft:block/slab_top"),
        ("double", "minecraft:block/cube_all")
    ):
        model_id = f"{block}_slab_{slab_type}"
        write_json(out_models / f"{model_id}.json", {
            "parent": parent,
            "textures": {
                "bottom": f"minecraft:block/{block}",
                "side": f"minecraft:block/{block}",
                "top": f"minecraft:block/{block}",
                "all": f"minecraft:block/{block}"
            }
        })
        write_json(out_items / f"{model_id}.json", {
            "model": {
                "type": "minecraft:model",
                "model": f"viabackportvisuals:display/{model_id}"
            }
        })

def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)

    # Copy only the textures needed by the isolated display models.
    textures = SOURCE / "assets/minecraft/textures"
    if textures.exists():
        shutil.copytree(textures, OUT / "assets/minecraft/textures")

    # Do NOT copy minecraft blockstates or placeholder models.
    # Those would globally alter genuine 26.2 blocks.
    for color in COLORS:
        for material in ("wool", "concrete"):
            emit_stairs(color, material)
            emit_slabs(color, material)

    print("Generated isolated display models for 16 wool + 16 concrete stair/slab families.")

if __name__ == "__main__":
    main()
