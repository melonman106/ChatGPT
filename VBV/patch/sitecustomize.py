"""Bootstrap the pinned Eagler resource overlay before apply_vbv_patch.py runs.

The build workflow invokes apply_vbv_patch.py immediately after create-dev, but the
workflow cannot currently pass the overlay CLI arguments. Python loads sitecustomize
from the script directory before executing that script, so use that hook only to
materialize the already-verified local overlay into generated-client.
"""
from __future__ import annotations

import hashlib
import os
import pathlib
import sys
import zipfile

OVERLAY_SHA256 = "2ba7e3376891c64f8bf57f3687e05b8dbe1971a75475b6825449e5e5f96d71f3"


def _safe_extract(zf: zipfile.ZipFile, destination: pathlib.Path) -> int:
    destination = destination.resolve()
    count = 0
    for info in zf.infolist():
        name = info.filename.replace("\\", "/")
        if not name or name.endswith("/"):
            continue
        target = (destination / name).resolve()
        try:
            target.relative_to(destination)
        except ValueError:
            raise RuntimeError(f"Unsafe resource-overlay entry: {info.filename}")
        target.parent.mkdir(parents=True, exist_ok=True)
        with zf.open(info) as src, target.open("wb") as dst:
            while True:
                chunk = src.read(1024 * 1024)
                if not chunk:
                    break
                dst.write(chunk)
        count += 1
    return count


def _bootstrap_overlay() -> None:
    if len(sys.argv) < 2 or not sys.argv[0].endswith("apply_vbv_patch.py"):
        return

    generated = pathlib.Path(sys.argv[1]).resolve()
    if not generated.is_dir():
        return

    repo = pathlib.Path.cwd().resolve()
    overlay = repo / "legacy-u1-kit" / "inputs" / "resource-overlay-normal.zip"
    resources = generated / "game" / "src" / "main" / "resources"

    if not overlay.is_file():
        print(f"[VBV resource bootstrap] overlay not found: {overlay}", file=sys.stderr)
        return

    digest = hashlib.sha256(overlay.read_bytes()).hexdigest()
    if digest != OVERLAY_SHA256:
        raise RuntimeError(
            "Pinned Eagler resource overlay SHA-256 mismatch: "
            f"expected {OVERLAY_SHA256}, got {digest}"
        )

    resources.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(overlay) as zf:
        count = _safe_extract(zf, resources)

    required = [
        resources / "assets/minecraft/shaders/core/text.vsh",
        resources / "assets/minecraft/shaders/core/text.fsh",
        resources / "assets/minecraft/shaders/core/gui.vsh",
        resources / "assets/minecraft/shaders/core/gui.fsh",
        resources / "assets/minecraft/textures/colormap/dry_foliage.png",
    ]
    missing = [str(p.relative_to(resources)) for p in required if not p.is_file()]
    if missing:
        raise RuntimeError(
            "Resource overlay extracted but required files are missing: " + ", ".join(missing)
        )

    print(
        f"[VBV resource bootstrap] extracted {count} pinned overlay resources into {resources}"
    )


_bootstrap_overlay()
