#!/usr/bin/env python3
"""One-shot resource and EPK gate for the 26.2 Java CLI workspace.

This intentionally starts Gradle once and waits for its final result.  The
receipt is the useful output; this script does not poll the build process.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import zlib
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_WORKSPACE = ROOT / "output/java-cli-resource-local-20260924/workspace"
DEFAULT_OVERLAY = ROOT / "output/normal-clean-source-20260929/resource-overlay-normal.zip"
DEFAULT_OUTPUT = ROOT / "output/java-cli-epk-gate-20260924"
EXPECTED_OVERLAY_SHA256 = "2ba7e3376891c64f8bf57f3687e05b8dbe1971a75475b6825449e5e5f96d71f3"
EXPECTED_RESOURCE_COUNT = 19515
EXPECTED_RESOURCE_BYTES = 17582611
EXPECTED_PRODUCTION_COUNT = 19510
EXPECTED_AUDIT_COUNT = 19515
EXPECTED_RESOURCE_TREE_SHA256 = "ced3f0610dbfb18f8ecebfee5501df0da0036b8ad64ba7beb93f592aa9a1a8df"
UNREACHABLE = {
    "assets/minecraft/textures/gui/sprites/icon/new_realm.png",
    "assets/minecraft/textures/gui/sprites/realm_status/closed.png",
    "assets/minecraft/textures/gui/sprites/realm_status/expired.png",
    "assets/minecraft/textures/gui/sprites/realm_status/expires_soon.png",
    "assets/minecraft/textures/gui/sprites/realm_status/expires_soon.png.mcmeta",
    "assets/minecraft/textures/gui/sprites/realm_status/open.png",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def records(root: Path) -> list[dict[str, object]]:
    result = []
    paths = sorted((p for p in root.rglob("*") if p.is_file()),
                   key=lambda p: p.relative_to(root).as_posix())
    for path in paths:
        data = path.read_bytes()
        result.append({
            "path": path.relative_to(root).as_posix(),
            "sha256": hashlib.sha256(data).hexdigest(),
            "size": len(data),
        })
    return result


def canonical_json(value: object) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def check_overlay(overlay: Path, resource_root: Path) -> dict[str, object]:
    overlay_sha = sha256(overlay)
    if overlay_sha != EXPECTED_OVERLAY_SHA256:
        raise RuntimeError(f"overlay SHA256 mismatch: {overlay_sha}")
    with ZipFile(overlay) as archive:
        bundle = json.loads(archive.read("bundle.json"))
        final_manifest = json.loads(archive.read("final-manifest.json"))
        provenance = json.loads(archive.read("provenance.json"))
        actual = records(resource_root)
        expected = final_manifest["records"]
        if actual != expected:
            for index, (got, want) in enumerate(zip(actual, expected)):
                if got != want:
                    raise RuntimeError(f"resource manifest mismatch at {index}: {got} != {want}")
            raise RuntimeError(f"resource manifest length mismatch: {len(actual)} != {len(expected)}")
        if len(actual) != EXPECTED_RESOURCE_COUNT:
            raise RuntimeError(f"resource count mismatch: {len(actual)}")
        total_bytes = sum(int(record["size"]) for record in actual)
        if total_bytes != EXPECTED_RESOURCE_BYTES:
            raise RuntimeError(f"resource byte count mismatch: {total_bytes}")
        external = sorted(entry["path"] for entry in provenance["entries"]
                          if entry.get("kind") == "external-required")
        if len(external) != 6:
            raise RuntimeError(f"expected six external identities, found {len(external)}")
        by_path = {str(record["path"]): record for record in actual}
        external_identities = []
        for path in external:
            if path not in by_path:
                raise RuntimeError(f"external resource is missing from final tree: {path}")
            external_identities.append({"path": path, **by_path[path]})
        if bundle["final_file_count"] != EXPECTED_RESOURCE_COUNT:
            raise RuntimeError("bundle final count disagrees with expected resource count")
        if bundle["final_total_bytes"] != EXPECTED_RESOURCE_BYTES:
            raise RuntimeError("bundle final byte count disagrees with expected resource bytes")
        if bundle["final_tree_sha256"] != EXPECTED_RESOURCE_TREE_SHA256:
            raise RuntimeError("bundle final tree identity mismatch")
        return {
            "overlay_sha256": overlay_sha,
            "bundle_final_tree_sha256": bundle["final_tree_sha256"],
            "final_manifest_sha256_field": final_manifest.get("manifest_sha256"),
            "resource_file_count": len(actual),
            "resource_total_bytes": total_bytes,
            "external_identities": external_identities,
            "overlay_member_count": len(archive.infolist()),
        }


def parse_epk(path: Path) -> dict[str, object]:
    raw = path.read_bytes()
    if not raw.startswith(b"EAGPKG$$") or not raw.endswith(b":::YEE:>"):
        raise RuntimeError(f"invalid EPK framing: {path}")
    offset = 8
    version_len = raw[offset]
    offset += 1
    version = raw[offset:offset + version_len].decode("ascii")
    offset += version_len
    name_len = raw[offset]
    offset += 1
    archive_name = raw[offset:offset + name_len].decode("utf-8")
    offset += name_len
    comment_len = int.from_bytes(raw[offset:offset + 2], "big")
    offset += 2 + comment_len + 8
    declared_count = int.from_bytes(raw[offset:offset + 4], "big")
    offset += 4
    compression = chr(raw[offset])
    offset += 1
    if compression != "G":
        raise RuntimeError(f"unsupported EPK compression {compression!r}")
    stream = gzip.decompress(raw[offset:-8])
    cursor = 0
    entries = []
    while cursor + 4 <= len(stream):
        tag = stream[cursor:cursor + 4]
        cursor += 4
        if tag == b"END$":
            break
        if tag not in (b"HEAD", b"FILE"):
            raise RuntimeError(f"unknown EPK record {tag!r} in {path}")
        entry_name_len = stream[cursor]
        cursor += 1
        entry_name = stream[cursor:cursor + entry_name_len].decode("utf-8")
        cursor += entry_name_len
        payload_len = int.from_bytes(stream[cursor:cursor + 4], "big")
        cursor += 4
        expected_crc = None
        if tag == b"FILE":
            if payload_len < 5:
                raise RuntimeError(f"invalid FILE payload length for {entry_name}")
            expected_crc = int.from_bytes(stream[cursor:cursor + 4], "big")
            cursor += 4
            data = stream[cursor:cursor + payload_len - 5]
            cursor += payload_len - 5
        else:
            data = stream[cursor:cursor + payload_len]
            cursor += payload_len
        if tag == b"FILE":
            if stream[cursor:cursor + 1] != b":":
                raise RuntimeError(f"bad EPK record terminator for {entry_name}")
            cursor += 1
        if stream[cursor:cursor + 1] != b">":
            raise RuntimeError(f"bad EPK record terminator for {entry_name}")
        cursor += 1
        entries.append({
            "kind": tag.decode("ascii"),
            "path": entry_name,
            "size": len(data),
            "crc32": f"{zlib.crc32(data) & 0xffffffff:08x}",
            "declared_crc32": None if expected_crc is None else f"{expected_crc:08x}",
            "sha256": hashlib.sha256(data).hexdigest(),
        })
    if cursor > len(stream) or not stream[cursor - 4:cursor] == b"END$":
        raise RuntimeError(f"EPK END record missing in {path}")
    files = [entry for entry in entries if entry["kind"] == "FILE"]
    for entry in files:
        if entry["crc32"] != entry["declared_crc32"]:
            raise RuntimeError(f"EPK CRC mismatch for {entry['path']} in {path}")
    return {
        "path": str(path),
        "sha256": sha256(path),
        "size": len(raw),
        "version": version,
        "archive_name": archive_name,
        "declared_record_count": declared_count,
        "record_count": len(entries),
        "member_count": len(files),
        "members": files,
    }


def tree_equal(source: Path, target: Path) -> bool:
    return records(source) == records(target)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workspace", type=Path, default=DEFAULT_WORKSPACE)
    parser.add_argument("--overlay", type=Path, default=DEFAULT_OVERLAY)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--parse-existing", action="store_true",
                        help="reuse the archives from the previous one-shot Gradle run")
    args = parser.parse_args()
    workspace = args.workspace.resolve()
    overlay = args.overlay.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    log_out = output / "gradle.stdout.log"
    log_err = output / "gradle.stderr.log"
    receipt_path = output / "receipt.json"
    started = time.monotonic()
    receipt: dict[str, object] = {
        "gate": "java-cli-epk-gate-u1",
        "status": "precheck-failed",
        "workspace": str(workspace),
        "overlay": str(overlay),
        "started_epoch": int(time.time()),
    }
    try:
        source = workspace / "game/src/main/resources"
        if not source.is_dir():
            raise RuntimeError(f"resource source missing: {source}")
        receipt["resource_manifest"] = check_overlay(overlay, source)
    except Exception as exc:  # noqa: BLE001 - receipt must include precheck error
        receipt["error"] = str(exc)
        receipt["elapsed_seconds"] = round(time.monotonic() - started, 3)
        receipt_path.write_text(json.dumps(receipt, indent=2) + "\n")
        print(json.dumps(receipt, indent=2), file=sys.stderr)
        return 2

    gradle_home = output / ".gradle-home"
    env = os.environ.copy()
    env.update({
        "JAVA_HOME": "/usr/lib/jvm/java-25-openjdk-amd64",
        "GRADLE_USER_HOME": str(gradle_home),
        "LANG": "C",
        "LC_ALL": "C",
        "TZ": "UTC",
    })
    for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"):
        env.pop(name, None)
    command = [
        "./gradlew", "--no-daemon", "--console=plain", "--stacktrace",
        ":game:processResources",
        ":target_lwjgl_desktop:buildAssetsEPK",
        ":target_lwjgl_desktop:buildAssetsEPKWithWebUnreachable",
    ]
    receipt["command"] = command
    receipt["java_home"] = env["JAVA_HOME"]
    receipt["gradle_user_home"] = str(gradle_home)
    receipt["resource_overlay_sha256"] = sha256(overlay)
    if args.parse_existing:
        receipt["reused_gradle_outputs"] = True
        receipt["return_code"] = 0
    else:
        try:
            with log_out.open("w") as out_stream, log_err.open("w") as err_stream:
                completed = subprocess.run(command, cwd=workspace, env=env,
                                           stdout=out_stream, stderr=err_stream,
                                           timeout=args.timeout, check=False)
            receipt["return_code"] = completed.returncode
        except subprocess.TimeoutExpired:
            receipt["return_code"] = None
            receipt["timed_out"] = True
        except Exception as exc:  # noqa: BLE001 - preserve process setup failure
            receipt["return_code"] = None
            receipt["process_error"] = str(exc)

    process_tree = workspace / "game/build/resources/main"
    try:
        receipt["process_resources_tree_exact"] = tree_equal(workspace / "game/src/main/resources", process_tree)
        receipt["process_resources_tree_count"] = len(records(process_tree))
    except Exception as exc:  # noqa: BLE001
        receipt["process_resources_error"] = str(exc)
        receipt["process_resources_tree_exact"] = False

    epk_dir = workspace / "target_lwjgl_desktop/build/epk"
    for key, filename in (("production_epk", "assets.epk"), ("audit_epk", "assets-with-web-unreachable.epk")):
        path = epk_dir / filename
        if path.is_file():
            try:
                receipt[key] = parse_epk(path)
                archived = output / filename
                shutil.copy2(path, archived)
                receipt[key]["archived_copy"] = str(archived)
            except Exception as exc:  # noqa: BLE001
                receipt[key] = {"path": str(path), "parse_error": str(exc), "sha256": sha256(path)}
        else:
            receipt[key] = {"path": str(path), "missing": True}
    try:
        prod = receipt["production_epk"]
        audit = receipt["audit_epk"]
        receipt["epk_membership_checks"] = {
            "production_exact_count": isinstance(prod, dict) and prod.get("member_count") == EXPECTED_PRODUCTION_COUNT,
            "audit_exact_count": isinstance(audit, dict) and audit.get("member_count") == EXPECTED_AUDIT_COUNT,
            "audit_minus_production_is_exact_unreachable": (
                isinstance(prod, dict) and isinstance(audit, dict)
                and {x["path"] for x in audit.get("members", [])}
                - {x["path"] for x in prod.get("members", [])} == UNREACHABLE
            ),
        }
    except Exception as exc:  # noqa: BLE001
        receipt["epk_membership_checks_error"] = str(exc)
    for key in ("production_epk", "audit_epk"):
        value = receipt.get(key)
        if isinstance(value, dict) and "members" in value:
            members = value.pop("members")
            manifest = [{
                "path": member["path"],
                "size": member["size"],
                "crc32": member["crc32"],
                "sha256": member["sha256"],
            } for member in members]
            value["member_total_bytes"] = sum(int(member["size"]) for member in members)
            value["member_names_sha256"] = hashlib.sha256(
                canonical_json([member["path"] for member in members])).hexdigest()
            value["member_manifest_sha256"] = hashlib.sha256(canonical_json(manifest)).hexdigest()
            value["member_crc_sha256"] = hashlib.sha256(canonical_json([
                [member["path"], member["size"], member["crc32"]] for member in members
            ])).hexdigest()
    receipt["status"] = "passed" if (
        receipt.get("return_code") == 0
        and receipt.get("process_resources_tree_exact") is True
        and receipt.get("epk_membership_checks", {}).get("production_exact_count") is True
        and receipt.get("epk_membership_checks", {}).get("audit_exact_count") is True
        and receipt.get("epk_membership_checks", {}).get("audit_minus_production_is_exact_unreachable") is True
    ) else "failed"
    receipt["elapsed_seconds"] = round(time.monotonic() - started, 3)
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt, indent=2))
    return 0 if receipt["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
