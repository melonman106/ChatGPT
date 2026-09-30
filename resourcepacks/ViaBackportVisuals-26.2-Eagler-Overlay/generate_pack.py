#!/usr/bin/env python3
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/vbv-eagler-pack"

COLORS = [
    "white", "orange", "magenta", "light_blue",
    "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue",
    "brown", "green", "red", "black",
]

WOOL_STAIR_PLACEHOLDERS = [
    "waxed_cut_copper_stairs", "waxed_exposed_cut_copper_stairs",
    "waxed_weathered_cut_copper_stairs", "waxed_oxidized_cut_copper_stairs",
    "cut_copper_stairs", "exposed_cut_copper_stairs",
    "weathered_cut_copper_stairs", "oxidized_cut_copper_stairs",
    "mud_red_nether_brick_stairs", "tuff_red_nether_brick_stairs", "polished_tuff_stairs",
    "bamboo_mosaic_stairs", "end_dark_prismarine_stairs", "resin_red_nether_brick_stairs",
    "cinnabar_red_nether_brick_stairs", "sulfur_red_nether_brick_stairs",
]

WOOL_SLAB_PLACEHOLDERS = [x.replace("_stairs", "_slab") for x in WOOL_STAIR_PLACEHOLDERS]

CONCRETE_STAIR_PLACEHOLDERS = [
    "smooth_quartz_stairs", "smooth_red_sandstone_stairs", "red_sandstone_stairs",
    "quartz_stairs", "crimson_stairs",
    "warped_stairs", "mangrove_stairs", "cherry_stairs",
    "bamboo_stairs", "tuff_stairs", "sandstone_stairs",
    "smooth_sandstone_stairs", "red_smooth_sandstone_stairs",
    "polished_blackdark_prismarine_stairs", "dark_prismarine_stairs", "red_nether_brick_stairs",
]

CONCRETE_SLAB_PLACEHOLDERS = [x.replace("_stairs", "_slab") for x in CONCRETE_STAIR_PLACEHOLDERS]

def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

def emit_stair_family(placeholder, texture):
    models = OUT / "assets/minecraft/models/block"
    for suffix, parent in (
        ("", "minecraft:block/stairs"),
        ("_inner", "minecraft:block/inner_stairs"),
        ("_outer", "minecraft:block/outer_stairs"),
    ):
        write_json(models / f"{placeholder}{suffix}.json", {
            "parent": parent,
            "textures": {
                "bottom": f"minecraft:block/{texture}",
                "side": f"minecraft:block/{texture}",
                "top": f"minecraft:block/{texture}",
            },
        })

def emit_slab_family(placeholder, texture):
    models = OUT / "assets/minecraft/models/block"
    blockstates = OUT / "assets/minecraft/blockstates"
    write_json(models / f"{placeholder}.json", {
        "parent": "minecraft:block/slab",
        "textures": {
            "bottom": f"minecraft:block/{texture}",
            "side": f"minecraft:block/{texture}",
            "top": f"minecraft:block/{texture}",
        },
    })
    write_json(models / f"{placeholder}_top.json", {
        "parent": "minecraft:block/slab_top",
        "textures": {
            "bottom": f"minecraft:block/{texture}",
            "side": f"minecraft:block/{texture}",
            "top": f"minecraft:block/{texture}",
        },
    })
    write_json(models / f"{placeholder}_double.json", {
        "parent": "minecraft:block/cube_all",
        "textures": {"all": f"minecraft:block/{texture}"},
    })
    blockstates = OUT / "assets/minecraft/blockstates"
    write_json(blockstates / f"{placeholder}.json", {
        "variants": {
            "type=bottom": {"model": f"minecraft:block/{placeholder}"},
            "type=double": {"model": f"minecraft:block/{placeholder}_double"},
            "type=top": {"model": f"minecraft:block/{placeholder}_top"},
        }
    })

def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    write_json(OUT / "pack.mcmeta", {"pack": {"pack_format": 88, "description": "ViaBackportVisuals 26.2 Eagler visual pack"}})

    source_textures = ROOT / "resourcepacks/ViaBackportVisuals-26.2-VBPlus-26.3-Companion/assets/minecraft/textures"
    if source_textures.exists():
        shutil.copytree(source_textures, OUT / "assets/minecraft/textures")

    # No item_display entities, custom item models, or client marker code.
    # The server sends ordinary 26.2 stair/slab block states; the pack simply
    # gives the reserved placeholder block models the corresponding wool or
    # concrete texture while retaining the native stair/slab collision shape.
    for color, placeholder in zip(COLORS, WOOL_STAIR_PLACEHOLDERS):
        emit_stair_family(placeholder, f"{color}_wool")
    for color, placeholder in zip(COLORS, WOOL_SLAB_PLACEHOLDERS):
        emit_slab_family(placeholder, f"{color}_wool")
    for color, placeholder in zip(COLORS, CONCRETE_STAIR_PLACEHOLDERS):
        emit_stair_family(placeholder, f"{color}_concrete")
    for color, placeholder in zip(COLORS, CONCRETE_SLAB_PLACEHOLDERS):
        emit_slab_family(placeholder, f"{color}_concrete")

    print("Generated Eagler 26.2 pack: standard placeholder blockstates/models for 16 wool + 16 concrete stair/slab families.")
    print("No item_display entities or custom client code are required.")

if __name__ == "__main__":
    main()
