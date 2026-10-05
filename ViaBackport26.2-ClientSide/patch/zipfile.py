"""Eagler build bootstrap shim for apply_vbv_patch.py."""
from __future__ import annotations

import hashlib
import importlib.util
import os
from pathlib import Path
import sys


def _load_stdlib_zipfile():
    candidates = []

    try:
        import sysconfig
        candidates.append(Path(sysconfig.get_path("stdlib")) / "zipfile.py")
    except Exception:
        pass

    base = Path(sys.base_prefix)
    candidates.extend([
        base / "lib" / f"python{sys.version_info.major}.{sys.version_info.minor}" / "zipfile.py",
        base / f"lib/python{sys.version_info.major}.{sys.version_info.minor}/zipfile.py",
        Path(sys.executable).resolve().parent.parent / "lib" /
        f"python{sys.version_info.major}.{sys.version_info.minor}" / "zipfile.py",
    ])

    for entry in sys.path:
        if entry:
            candidates.append(Path(entry) / "zipfile.py")

    seen = set()
    for candidate in candidates:
        candidate = candidate.resolve()
        if candidate in seen:
            continue
        seen.add(candidate)
        if candidate.is_file() and candidate != Path(__file__).resolve():
            spec = importlib.util.spec_from_file_location("_stdlib_zipfile", candidate)
            if spec is not None and spec.loader is not None:
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)
                return module

    raise ImportError(
        "Could not locate the standard-library zipfile.py; searched: "
        + ", ".join(map(str, seen))
    )


_stdlib = _load_stdlib_zipfile()
globals().update({
    k: v for k, v in _stdlib.__dict__.items()
    if k not in {"__name__", "__spec__", "__loader__"}
})


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

    print(f"[VBV resource bootstrap] applying verified overlay: {overlay}")
    resources.mkdir(parents=True, exist_ok=True)

    with _stdlib.ZipFile(overlay) as zf:
        for info in zf.infolist():
            name = info.filename.replace("\\", "/")
            if not name or name.endswith("/"):
                continue
            target = (resources / name).resolve()
            if os.path.commonpath(
                (str(resources.resolve()), str(target))
            ) != str(resources.resolve()):
                raise SystemExit(f"::error::Unsafe resource-overlay path: {name}")
            target.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info, "r") as src, target.open("wb") as dst:
                dst.write(src.read())

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
    missing = [p for p in critical if not (resources / p).is_file()]
    if missing:
        raise SystemExit(
            "::error::Resource overlay extraction is incomplete; missing: "
            + ", ".join(missing)
        )

    count = sum(1 for p in resources.rglob("*") if p.is_file())
    if count < 19000:
        raise SystemExit(
            f"::error::Resource overlay extraction produced only {count} files; "
            "expected the full ~19,515-file tree."
        )

    print(
        f"[VBV resource bootstrap] verified {count} resource files and all "
        "critical shader/runtime assets."
    )


_materialize_eagler_overlay()
