#!/usr/bin/env python3
"""Stage verified Minecraft 26.2 resources for the patcher's overlay.

The official client JAR, custom Eagler font, sounds EPK, and music EPK are separate
inputs. This tool stages only objects present in the pinned official asset index.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
from urllib.request import urlopen
import zipfile

from rebuild_zh_cn import rebuild as rebuild_chinese

INDEX_URL = "https://piston-meta.mojang.com/v1/packages/981aab8147520cdc1f0d4a84f46c161929021fee/32.json"
INDEX_SHA1 = "981aab8147520cdc1f0d4a84f46c161929021fee"
ASSET_ROOT = "https://resources.download.minecraft.net"
FILES = {
    "minecraft/font/unifont.zip": ("ccd5ac4767ce0a9c71d1dd62f2dc25449789b5dd", 1559654,
                                   "aea3e9918b0d31de6f94623080f04c31c8c16a5b1a2e8d99ab39f1acdddd30f7"),
    "minecraft/font/unifont_pua.zip": ("d7caa0e3aa5eb656c51817ae4bfbf3c4d72cdaad", 100360,
                                       "65388145333f6ceffe2be67790183a77d45b97236248ac1eab3befe6a17979d7"),
    "minecraft/lang/zh_cn.json": ("0d1c720356c912eba263f7898728e4cf109121c5", 532215,
                                 "5389c7aa92675c87480e4cbac48f1e3590550c954bb09a379a74007c614f1094"),
    "minecraft/sounds.json": ("9ac006d5537ed0fa4a7bcd1eccfc505155847686", 626160,
                              "84fe52cea79f67441ac00df61836e15d5c9ae98c406cebba557213b80f59c3b5"),
}
SYMBOL_CODES = ("25AA", "26C3", "2708", "270C", "2716", "2719", "2726", "2733", "27A5", "27E1")
SYMBOL_HEX_SHA256 = "29d147c67366f6ccc9ea1efbc2a63b72c3c886769c34ec057fb17d2bcea21f8e"
SYMBOL_ZIP_SHA256 = "15a5363bc8762eff093f99e7a81a7575c81477145684a3da5b82468ab6d43989"


def make_symbols(unifont_zip: bytes) -> tuple[bytes, bytes]:
    with zipfile.ZipFile(io.BytesIO(unifont_zip)) as archive:
        source = archive.read("unifont_all_no_pua-17.0.01.hex")
    if len(source) != 7_722_308:
        raise ValueError("official Unifont archive member size mismatch")
    by_code = {line.split(b":", 1)[0].decode("ascii"): line for line in source.splitlines()
               if line[:4].decode("ascii") in SYMBOL_CODES}
    if set(by_code) != set(SYMBOL_CODES):
        raise ValueError("official Unifont archive is missing symbol glyphs")
    symbol_hex = b"\n".join(by_code[code] for code in SYMBOL_CODES) + b"\n"
    if digest(symbol_hex, "sha256") != SYMBOL_HEX_SHA256:
        raise ValueError("reconstructed symbol HEX SHA-256 mismatch")
    info = zipfile.ZipInfo("eagler_server_symbols.hex", (2026, 8, 20, 22, 19, 36))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_version = 30
    info.external_attr = 0o100664 << 16
    info.internal_attr = 1
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr(info, symbol_hex, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    symbol_zip = buffer.getvalue()
    if digest(symbol_zip, "sha256") != SYMBOL_ZIP_SHA256:
        raise ValueError("reconstructed symbol ZIP SHA-256 mismatch")
    return symbol_hex, symbol_zip


def reject_symlink_ancestors(path: Path) -> None:
    for entry in (path.absolute(), *path.absolute().parents):
        if entry.is_symlink():
            raise ValueError(f"symlink in path: {entry}")


def read_bounded(path: Path, limit: int) -> bytes:
    reject_symlink_ancestors(path)
    if not path.is_file():
        raise ValueError(f"missing file: {path}")
    with path.open("rb") as handle:
        data = handle.read(limit + 1)
    if len(data) > limit:
        raise ValueError(f"file exceeds size limit: {path}")
    return data


def digest(data: bytes, algorithm: str) -> str:
    return hashlib.new(algorithm, data).hexdigest()


def verified_index(data: bytes) -> dict:
    if digest(data, "sha1") != INDEX_SHA1:
        raise ValueError("official index SHA-1 mismatch")
    index = json.loads(data)
    objects = index.get("objects")
    if not isinstance(objects, dict):
        raise ValueError("official index has no objects map")
    for name, (sha1, size, _) in FILES.items():
        entry = objects.get(name)
        if entry != {"hash": sha1, "size": size}:
            raise ValueError(f"official index entry mismatch: {name}")
    return objects


def fetch(url: str, limit: int) -> bytes:
    with urlopen(url, timeout=60) as response:
        if response.url.split(":", 1)[0] != "https":
            raise ValueError("asset request redirected off HTTPS")
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("asset response exceeded size limit")
    return data


def stage(index_data: bytes, destination: Path, asset_store: Path | None) -> dict:
    verified_index(index_data)
    destination = destination.absolute()
    parent = destination.parent
    if destination.exists() or destination.is_symlink():
        raise ValueError(f"destination already exists: {destination}")
    reject_symlink_ancestors(parent)
    if not parent.is_dir() or parent.is_symlink():
        raise ValueError(f"parent must be an existing real directory: {parent}")
    staging = Path(tempfile.mkdtemp(prefix=".vanilla-assets-", dir=parent))
    completed = False
    try:
        records = []
        unifont_data = None
        for name, (sha1, size, sha256) in FILES.items():
            if asset_store is None:
                data = fetch(f"{ASSET_ROOT}/{sha1[:2]}/{sha1}", size)
            else:
                source = asset_store / sha1[:2] / sha1
                data = read_bounded(source, size)
            if len(data) != size or digest(data, "sha1") != sha1 or digest(data, "sha256") != sha256:
                raise ValueError(f"asset object hash/size mismatch: {name}")
            if name == "minecraft/font/unifont.zip":
                unifont_data = data
            if name == "minecraft/lang/zh_cn.json":
                catalog_patch = json.loads(read_bounded(Path(__file__).with_name("zh-cn-delta-26.2.json"), 1_000_000))
                data = rebuild_chinese(json.loads(data), catalog_patch)
                sha256 = digest(data, "sha256")
            target = staging / "assets" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            records.append({"path": f"assets/{name}", "source_sha1": sha1,
                            "sha256": sha256, "size": len(data)})
        if unifont_data is None:
            raise ValueError("official Unifont object missing")
        symbol_hex, symbol_zip = make_symbols(unifont_data)
        for filename, data in (("eagler_server_symbols.hex", symbol_hex),
                               ("eagler_server_symbols.zip", symbol_zip)):
            target = staging / "assets" / "minecraft" / "font" / filename
            target.write_bytes(data)
            records.append({"path": f"assets/minecraft/font/{filename}",
                            "sha256": digest(data, "sha256"), "size": len(data)})
        if destination.exists() or destination.is_symlink():
            raise ValueError("destination appeared during download")
        staging.rename(destination)
        completed = True
        return {"status": "pass", "index_sha1": INDEX_SHA1, "files": records,
                "note": "Six exact overlay resources reconstructed from verified official objects and the Chinese catalog delta; EPK files remain required."}
    finally:
        if not completed:
            shutil.rmtree(staging)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path, help="new output directory")
    parser.add_argument("--index", type=Path, help="cached 32.json with pinned SHA-1")
    parser.add_argument("--asset-store", type=Path, help="cached objects/<prefix>/<sha1> directory")
    args = parser.parse_args()
    try:
        index_data = read_bounded(args.index, 2_000_000) if args.index else fetch(INDEX_URL, 2_000_000)
        receipt = stage(index_data, args.destination, args.asset_store)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    print(json.dumps(receipt, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
