#!/usr/bin/env python3
"""Export the small, project-owned 26.2 build skeleton.

The exporter deliberately knows less than a general backup tool.  Every input
is selected by an allowlist, and generated/source-cache/game material is not
eligible for inclusion even if it happens to sit under an allowed directory.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tempfile
import unicodedata
import zipfile


SCHEMA = "eaglercraft-26.2-project-skeleton-v5"
ROOT_FILES = (
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    "package.json",
    "package-lock.json",
)
EXACT_FILES = (
    "game/build.gradle.kts",
    # Deliberately bounded closure of package.json's build:single entrypoint.
    # Do not replace this with the whole wasm-toolchain directory: it contains
    # historical logs, diagnostics, and unrelated mod/test utilities.
    "wasm-toolchain/build-single-html.js",
    "wasm-toolchain/content-verified-brotli.js",
    "wasm-toolchain/run-memory-capped.js",
    "wasm-toolchain/link-web-target-standalone.js",
    "wasm-toolchain/link-server-standalone.js",
    "wasm-toolchain/standalone-classpaths.js",
    "wasm-toolchain/field-overlay-link.js",
    "wasm-toolchain/standalone-link-cache.js",
    "wasm-toolchain/build-server-fastutil-slice.js",
    "wasm-toolchain/StandaloneTeaVMLinker.java",
    "wasm-toolchain/deploy_wasm_web.sh",
    "wasm-toolchain/precompress-web.sh",
    "wasm-toolchain/precompress-web.js",
    "wasm-toolchain/export-music-resource-pack.js",
    "wasm-toolchain/generate-iwa-csp-runtime.mjs",
    "cloudflare-client/brotli-decoder-worker.js",
    "iwa/build-iwa.mjs",
    "iwa/brotli-loader-iwa.js",
    "iwa/direct-socket-loopback.js",
    "iwa/package.json",
    "iwa/package-lock.json",
    "iwa/README.md",
)
DIRECTORIES = (
    "gradle/wrapper",
    "platform",
    "platform-teavm",
    "platform-lwjgl",
    "teavm-compat",
    "target_teavm",
    "target_teavm_spike",
    "target_teavm_wasm_gc",
    "target_teavm_wasm_gc_mesh",
    "target_teavm_wasm_gc_server",
    "target_lwjgl_desktop",
    "wasm-toolchain/teavm-eagler-patch",
)
DENIED_COMPONENTS = frozenset({
    ".git", ".gradle", "build", "output", "node_modules", "__pycache__", "parked-phase2",
})
DENIED_SUFFIXES = (".bak", ".class", ".log", ".pyc", ".wasm")
DENIED_NAMES = frozenset({"classes-debug-D8w.wasm"})
NOTICE_SOURCE = "patcher/project-skeleton/third_party"
NOTICE_DESTINATION = "third_party/skeleton"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def archive_key(path: str) -> str:
    return unicodedata.normalize("NFKC", path).casefold()


def safe_archive_path(path: str) -> None:
    pure = PurePosixPath(path)
    if not path or "\\" in path or pure.is_absolute() or ".." in pure.parts:
        raise ValueError(f"unsafe archive path: {path!r}")
    if any(not part or part in (".", "..") for part in pure.parts):
        raise ValueError(f"unsafe archive path: {path!r}")


def denied(path: Path) -> bool:
    if any(component in DENIED_COMPONENTS for component in path.parts):
        return True
    if path.name in DENIED_NAMES:
        return True
    return path.suffix.lower() in DENIED_SUFFIXES


def selected_paths(root: Path) -> list[tuple[str, Path]]:
    candidates: list[Path] = []
    for relative in ROOT_FILES + EXACT_FILES:
        candidates.append(root / relative)
    for relative in DIRECTORIES:
        directory = root / relative
        if not directory.is_dir():
            raise FileNotFoundError(f"allowlisted directory missing: {relative}")
        for path in directory.rglob("*"):
            if denied(path.relative_to(root)):
                continue
            candidates.append(path)

    entries: list[tuple[str, Path]] = []
    seen: dict[str, str] = {}
    for path in sorted(set(candidates), key=lambda item: item.relative_to(root).as_posix()):
        relative = path.relative_to(root).as_posix()
        safe_archive_path(relative)
        if denied(path.relative_to(root)):
            continue
        if path.is_symlink():
            raise ValueError(f"symlink is not allowed: {relative}")
        if path.is_dir():
            continue
        if not path.exists():
            raise FileNotFoundError(f"allowlisted input missing: {relative}")
        if not path.is_file():
            raise ValueError(f"non-regular allowlisted input: {relative}")
        key = archive_key(relative)
        if key in seen and seen[key] != relative:
            raise ValueError(f"case/unicode collision: {seen[key]!r} vs {relative!r}")
        seen[key] = relative
        entries.append((relative, path))

    notices = root / NOTICE_SOURCE
    if not notices.is_dir() or notices.is_symlink():
        raise FileNotFoundError(f"skeleton notices missing: {NOTICE_SOURCE}")
    for path in sorted(notices.rglob("*")):
        source_relative = path.relative_to(notices)
        if path.is_symlink():
            raise ValueError(f"symlink is not allowed in skeleton notices: {source_relative}")
        if path.is_dir():
            continue
        if not path.is_file() or denied(source_relative):
            raise ValueError(f"unsupported skeleton notice: {source_relative}")
        relative = f"{NOTICE_DESTINATION}/{source_relative.as_posix()}"
        safe_archive_path(relative)
        key = archive_key(relative)
        if key in seen:
            raise ValueError(f"case/unicode collision: {seen[key]!r} vs {relative!r}")
        seen[key] = relative
        entries.append((relative, path))
    if not entries:
        raise ValueError("allowlist selected no files")
    return sorted(entries, key=lambda item: item[0])


def manifest_for(entries: list[tuple[str, Path]]) -> dict:
    files = [
        {
            "path": relative,
            "size": path.stat().st_size,
            "sha256": sha256(path),
            "mode": "100755" if relative == "gradlew" or relative.endswith(".sh") else "100644",
        }
        for relative, path in entries
    ]
    return {
        "schema": SCHEMA,
        "archive_format": "zip-stored-deterministic-v1",
        "capability": {
            "entrypoint": "npm run build:single -- --output <path>; node iwa/build-iwa.mjs --source <hosted-web-dir> --output <local.swbn> --key <local.pem>",
            "classification": "standalone-and-local-iwa-build-input-skeleton; not a completed or tested build",
            "node_install": "the Java CLI runs npm ci --ignore-scripts against this authenticated lockfile",
            "external_inputs_required": [
                "patched game/src/main/java reconstructed from the verified official client JAR and source patch bundle",
                "patched game/src/main/resources reconstructed from the verified official client JAR, resource overlay, and six separately supplied external additions",
                "target_teavm/build/web/sounds.epk from a separately authorized, hash-pinned source",
            ],
            "known_blockers": [
                "no fresh standalone Wasm/HTML build has been executed from this skeleton",
            ],
            "generated_inputs": [
                "the standalone client linker extracts classes.wasm-runtime.js from the authenticated TeaVM 0.13.1 tool classpath and verifies its 13,984-byte SHA-256 before installation",
            ],
            "host_tools": [
                "bash and POSIX core utilities used by deploy_wasm_web.sh",
                "unzip for extracting the pinned TeaVM runtime resource from the authenticated tool classpath",
                "Node.js plus npm",
                "OpenSSL for a locally generated IWA signing key",
                "IWA npm dependencies installed separately from iwa/package-lock.json",
                "the fully pinned Java runtime required by the Java patcher/build gate",
            ],
        },
        "files": files,
        "file_count": len(files),
        "provenance_review": {
            "classification": "project-owned browser compatibility/shadow sources; no blanket third-party-rights claim",
            "release_gate": "retain source provenance and complete legal/license review before redistribution",
            "same_fqn_paths": [
                "platform-teavm/src/main/java/com/mojang/blaze3d/platform/MacosUtil.java",
                "platform-teavm/src/main/java/com/mojang/blaze3d/platform/NativeLibrariesBootstrap.java",
                "platform-teavm/src/main/java/com/mojang/blaze3d/platform/DebugMemoryUntracker.java",
                "platform-teavm/src/main/java/com/mojang/blaze3d/opengl/GlBackend.java",
                "platform-teavm/src/main/java/com/mojang/blaze3d/vulkan/VulkanBackend.java",
                "platform-teavm/src/main/java/net/minecraft/client/FramerateLimiter.java",
                "teavm-compat/src/main/java/com/mojang/logging/LogListeners.java",
                "teavm-compat/src/main/java/com/mojang/logging/LogUtils.java",
                "teavm-compat/src/main/java/com/mojang/logging/LogQueues.java",
                "teavm-compat/src/main/java/com/mojang/text2speech/Narrator.java",
            ],
        },
        "omitted": {
            "paths": [
                "game/src/main/java",
                "game/src/main/resources",
                "mojang-source",
                "assets",
                "patcher",
                "build",
                ".gradle",
                "output",
                "mod-support",
                "node_modules",
                "wasm-toolchain (except the explicit standalone and IWA build closures recorded in files)",
                "wasm-toolchain/*.log",
                "wasm-toolchain/classes-debug-D8w.wasm",
            ],
            "reason": "Mojang-derived inputs, runtime assets, external launcher/trust roots, caches, historical workspaces, or generated outputs",
        },
    }


def write_archive(root: Path, output: Path) -> dict:
    if output.exists():
        raise FileExistsError(f"refusing to overwrite existing archive: {output}")
    entries = selected_paths(root)
    manifest = manifest_for(entries)
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkstemp(prefix=f".{output.name}.", suffix=".tmp", dir=output.parent)[1])
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
            records = [(relative, path) for relative, path in entries]
            records.append(("skeleton-manifest.json", None))
            for relative, path in sorted(records, key=lambda item: item[0]):
                info = zipfile.ZipInfo(relative)
                info.date_time = (1980, 1, 1, 0, 0, 0)
                info.compress_type = zipfile.ZIP_STORED
                info.create_system = 3
                mode = 0o755 if relative == "gradlew" or relative.endswith(".sh") else 0o644
                info.external_attr = ((0o100000 | mode) << 16)
                payload = (json.dumps(manifest, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("utf-8") + b"\n") if path is None else path.read_bytes()
                archive.writestr(info, payload)
        os.replace(temporary, output)
    finally:
        if temporary.exists():
            temporary.unlink()
    return manifest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest-output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = args.root.resolve()
    output = args.output.resolve()
    if not root.is_dir():
        raise SystemExit(f"root is not a directory: {root}")
    if output == root:
        raise SystemExit("output must not be the source root")
    if root in output.parents:
        relative_output = output.relative_to(root)
        if not relative_output.parts or relative_output.parts[0] != "output":
            raise SystemExit("output inside the source root is allowed only below output/")
    manifest = write_archive(root, output)
    if args.manifest_output:
        destination = args.manifest_output.resolve()
        if destination.exists():
            raise SystemExit(f"refusing to overwrite manifest: {destination}")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({"archive": str(output), "file_count": manifest["file_count"], "sha256": sha256(output)}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
