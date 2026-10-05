"""Eagler build bootstrap shim for apply_vbv_patch.py.

The build workflow invokes apply_vbv_patch.py after the U1 portable kit has
been unpacked, but the workflow currently does not pass the verified resource
overlay to create-dev.  apply_vbv_patch.py imports the standard-library
``zipfile`` module, so this local module is loaded first and materializes the
pinned Eagler resource overlay before the VBV patch runs.

After bootstrapping, the real Python 3 zipfile module is exported unchanged so
apply_vbv_patch.py continues to use the normal API.
"""
from __future__ import annotations

import hashlib
import importlib.util
import os
from pathlib import Path
import sys

_STDLIB_ZIPFILE = Path(sys.base_prefix) / "lib" / f"python{sys.version_info.major}.{sys.version_info.minor}" / "zipfile.py"
if not _STDLIB_ZIPFILE.is_file():
    # Ubuntu GitHub runners normally use /usr/lib/pythonX.Y. Keep a second
    # location for installations where sys.base_prefix/lib is not populated.
    _STDLIB_ZIPFILE = Path("/usr/lib") / f"python{sys.version_info.major}" / "zipfile.py"

_spec = importlib.util.spec_from_file_location("_stdlib_zipfile", _STDLIB_ZIPFILE)
if _spec is None or _spec.loader is None:
    raise ImportError(f"Could not load the standard-library zipfile module: {_STDLIB_ZIPFILE}")
_stdlib = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_stdlib)

globals().update({k: v for k, v in _stdlib.__dict__.items() if k not in {"__name__", "__spec__", "__loader__"}})


def _materialize_eagler_overlay() -> None:
    if len(sys.argv) < 2:
        return

    generated = Path(sys.argv[1]).resolve()
    if not generated.is_dir():
        return

    repo = Path.cwd().resolve()
    overlay = repo / "legacy-u1-kit" / "inputs" / "resource-overlay-normal.zip"
    resources = generated / "game" / "src" / "main" / "resources"
    expected = "2ba7e3376891c64f8bf57f3687e05b8dbe1971a75475b6825449e5e5f96d71f3"

    if not overlay.is_file():
        print(f"[VBV resource bootstrap] overlay not found: {overlay}")
        return

    digest = hashlib.sha256(overlay.read_bytes()).hexdigest()
    if digest != expected:
        raise SystemExit(
            "::error::VBV resource overlay SHA-256 mismatch: "
            f"expected {expected}, got {digest}"
        )

    critical = [
        "assets/minecraft/shaders/core/text.vsh",
        "assets/minecraft/shaders/core/text.fsh",
        "assets/minecraft/shaders/core/gui.vsh",
        "assets/minecraft/shaders/core/gui.fsh",
        "assets/minecraft/shaders/core/position_tex_color.vsh",
        "assets/minecraft/shaders/core/position_tex_color.fsh",
        "assets/minecraft/textures/gui/title/edition.png",
        "assets/minecraft/textures/gui/title/minecraft.png",
        "assets/minecraft/textures/block/lava_flow.png",
        "assets/minecraft/textures/block/lava_still.png",
        "assets/minecraft/textures/block/water_flow.png",
        "assets/minecraft/textures/block/water_still.png",
        "assets/minecraft/textures/block/water_overlay.png",
        "assets/minecraft/gpu_warnlist.json",
        "assets/minecraft/regional_compliancies.json",
    ]

    print(f"[VBV resource bootstrap] applying verified overlay: {overlay}")
    resources.mkdir(parents=True, exist_ok=True)

    with _stdlib.ZipFile(overlay) as zf:
        extracted = 0
        for info in zf.infolist():
            name = info.filename.replace("\\", "/")
            if not name or name.endswith("/"):
                continue
            # Reject traversal before writing anything.
            target = (resources / name).resolve()
            if os.path.commonpath((str(resources.resolve()), str(target))) != str(resources.resolve()):
                raise SystemExit(f"::error::Unsafe resource-overlay path: {name}")
            target.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info, "r") as src, target.open("wb") as dst:
                dst.write(src.read())
            extracted += 1

    missing = [p for p in critical if not (resources / p).is_file()]
    if missing:
        raise SystemExit(
            "::error::Resource overlay extraction is incomplete; missing: " + ", ".join(missing)
        )

    count = sum(1 for p in resources.rglob("*") if p.is_file())
    if count < 19000:
        raise SystemExit(
            f"::error::Resource overlay extraction produced only {count} files; expected the full ~19,515-file tree."
        )

    print(f"[VBV resource bootstrap] verified {count} resource files and all critical shader/runtime assets.")


_materialize_eagler_overlay()
