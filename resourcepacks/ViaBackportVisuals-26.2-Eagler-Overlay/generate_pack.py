#!/usr/bin/env python3
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "resourcepacks/ViaBackportVisuals-26.2-VBPlus-26.3-Companion"
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/vbv-eagler-pack"

WOOL = [
    "white","orange","magenta","light_blue","yellow","lime","pink","gray",
    "light_gray","cyan","purple","blue","brown","green","red","black"
]

def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")

def model_id_from_key(block, key):
    props = {}
    if key:
        for part in key.split(","):
            k, v = part.split("=")
            props[k] = v
    if block.endswith("_stairs"):
        return (
            block[:-7] + "_stairs_" +
            props.get("facing","north") + "_" +
            props.get("half","bottom") + "_" +
            props.get("shape","straight")
        )
    return block[:-5] + "_slab_" + props.get("type","bottom")

def emit_block_family(block):
    src = SOURCE / "assets/minecraft/blockstates" / f"{block}.json"
    if not src.exists():
        return 0
    data = json.loads(src.read_text(encoding="utf-8"))
    variants = data.get("variants", {})
    count = 0

    for key, variant in variants.items():
        entries = variant if isinstance(variant, list) else [variant]
        for entry in entries:
            model = entry["model"]
            model_path = model if ":" in model else "minecraft:" + model
            model_id = model_id_from_key(block, key)
            safe_model_id = model_id.replace("/", "_")
            wrapper = OUT / "assets/viabackportvisuals/models/display" / f"{safe_model_id}.json"
            item = OUT / "assets/viabackportvisuals/items/display" / f"{safe_model_id}.json"

            model_json = {
                "parent": model_path
            }
            for k in ("x", "y", "z", "uvlock"):
                if k in entry:
                    model_json[k] = entry[k]

            write_json(wrapper, model_json)
            write_json(item, {
                "model": {
                    "type": "minecraft:model",
                    "model": f"viabackportvisuals:display/{safe_model_id}"
                }
            })
            count += 1
    return count

def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)

    shutil.copy2(SOURCE / "pack.mcmeta", OUT / "pack.mcmeta")
    shutil.copy2(Path(__file__).with_name("README.txt"), OUT / "README.txt")

    # Copy only model/texture assets. Deliberately do not copy blockstates:
    # the overlay must never globally replace a vanilla 26.2 blockstate.
    src_models = SOURCE / "assets/minecraft/models"
    src_textures = SOURCE / "assets/minecraft/textures"
    dst_assets = OUT / "assets/minecraft"
    shutil.copytree(src_models, dst_assets / "models")
    shutil.copytree(src_textures, dst_assets / "textures")

    total = 0
    for color in WOOL:
        total += emit_block_family(f"{color}_wool_stairs")
        total += emit_block_family(f"{color}_wool_slab")
        total += emit_block_family(f"{color}_concrete_stairs")
        total += emit_block_family(f"{color}_concrete_slab")

    print(f"Generated {total} Eagler display-model variants in {OUT}")

if __name__ == "__main__":
    main()
