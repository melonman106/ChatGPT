#!/usr/bin/env python3
"""Focused acceptance tests for the maintainer Java source patch bundle.

The test writes no repository files.  It verifies the current frozen checkout
counts, byte-stable exports, two clean reconstructions, failure atomicity for a
tampered preimage, and an independent add/modify/delete fixture.  A receipt is
written only when ``--receipt`` is supplied.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import export_source_patches as esp  # noqa: E402


EXPECTED_COUNTS = {"add": 90, "modify": 571, "delete": 3}
EXPECTED_BASE_MANIFEST = "ebf04b6c8f1c808bff5b6f7dc2924b3618dbb3c4059cd2030434d9879a50b9fe"
EXPECTED_FINAL_MANIFEST = "2b874cab3db5801b9cb8d5a59e4de3623cdff9a79fe43f1f9a9491b49d052994"


def tree_digest(root: Path) -> str:
    records: list[tuple[str, str, int]] = []
    for path in sorted(root.rglob("*.java")):
        if path.is_symlink() or not path.is_file():
            raise AssertionError(f"unexpected source-tree entry: {path}")
        rel = path.relative_to(root).as_posix()
        digest, size = esp.file_info(path)
        records.append((rel, digest, size))
    return hashlib.sha256(esp.canonical_json(records)).hexdigest()


def assert_same_tree(left: Path, right: Path) -> None:
    left_map = esp.scan_source(left)
    right_map = esp.scan_source(right)
    if left_map != right_map:
        raise AssertionError(f"source trees differ: {left} vs {right}")
    for rel in left_map:
        if (left / rel).read_bytes() != (right / rel).read_bytes():
            raise AssertionError(f"source bytes differ: {rel}")


def write_fixture(root: Path, files: dict[str, bytes]) -> None:
    for rel, data in files.items():
        path = root / Path(*rel.split("/"))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


def expect_error(label: str, callback) -> str:
    try:
        callback()
    except esp.BundleError as exc:
        return str(exc)
    raise AssertionError(f"{label} unexpectedly succeeded")


def copy_bundle(source: Path, destination: Path) -> None:
    shutil.copytree(source, destination)


def rewrite_bundle_json(bundle: Path, mutate) -> None:
    obj = esp.read_json(bundle / "operations.json")
    mutate(obj)
    esp.write_canonical_json(bundle / "operations.json", obj)
    metadata = esp.read_json(bundle / "bundle.json")
    metadata["operations_sha256"] = esp.sha256_file(bundle / "operations.json")
    esp.write_canonical_json(bundle / "bundle.json", metadata)


def make_zip(path: Path, entries: list[tuple[str, bytes, int | None]], *, compression=zipfile.ZIP_STORED) -> None:
    with zipfile.ZipFile(path, "w", compression=compression) as archive:
        for name, data, mode in entries:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = compression
            if mode is not None:
                info.create_system = 3
                info.external_attr = mode << 16
            archive.writestr(info, data)


def run(base: Path, final: Path, receipt: Path | None, require_frozen: bool = False) -> dict[str, object]:
    if not base.is_dir() or not final.is_dir():
        raise AssertionError("--base and --final must be source directories")
    with tempfile.TemporaryDirectory(prefix="eagler-source-patch-tests-") as temp_name:
        temp = Path(temp_name)
        bundle_a = temp / "bundle-a"
        archive_a = temp / "bundle-a.zip"
        bundle_b = temp / "bundle-b"
        archive_b = temp / "bundle-b.zip"
        first = esp.export_bundle(base, final, bundle_a, archive_a)
        second = esp.export_bundle(base, final, bundle_b, archive_b)
        if archive_a.read_bytes() != archive_b.read_bytes():
            raise AssertionError("double export archive is not byte-identical")
        if esp.sha256_file(archive_a) != esp.sha256_file(archive_b):
            raise AssertionError("double export archive SHA-256 differs")
        for path_a in sorted(bundle_a.rglob("*")):
            if not path_a.is_file():
                continue
            path_b = bundle_b / path_a.relative_to(bundle_a)
            if path_a.read_bytes() != path_b.read_bytes():
                raise AssertionError(f"double export bundle differs: {path_a.relative_to(bundle_a)}")

        archive_sha256 = esp.sha256_file(archive_a)
        verified = esp.verify_bundle(archive_a, base, archive_sha256)
        frozen_count_expectation_matches = verified["counts"] == EXPECTED_COUNTS
        frozen_manifests_match = (
            verified["base_manifest_sha256"] == EXPECTED_BASE_MANIFEST
            and verified["final_manifest_sha256"] == EXPECTED_FINAL_MANIFEST
        )
        if require_frozen and (not frozen_count_expectation_matches or not frozen_manifests_match):
            raise AssertionError("source trees do not match the frozen accepted bundle")
        operations_object = esp.read_json(bundle_a / "operations.json")
        payload_operations = [
            operation
            for operation in operations_object["operations"]
            if operation["op"] in {"add", "modify"}
        ]
        payload_bytes = sum(operation["payload_size"] for operation in payload_operations)
        final_source_bytes = sum(record["size"] for record in esp.read_json(bundle_a / "final-manifest.json")["records"])
        for operation in payload_operations:
            payload_path = bundle_a / operation["payload"]
            if operation["op"] == "modify":
                if payload_path.read_bytes() == (final / operation["path"]).read_bytes():
                    raise AssertionError("modify payload is a complete postimage, not a delta")
                if operation["payload_kind"] != "byte-spans-v1":
                    raise AssertionError("modify payload is missing byte-span delta kind")

        reconstructed_a = temp / "reconstructed-a"
        reconstructed_b = temp / "reconstructed-b"
        esp.apply_bundle(archive_a, base, reconstructed_a, archive_sha256)
        expanded_sha256 = esp._expanded_bundle_sha256(bundle_a)
        esp.apply_bundle(bundle_a, base, reconstructed_b, expanded_sha256)
        assert_same_tree(reconstructed_a, final)
        assert_same_tree(reconstructed_b, final)
        assert_same_tree(reconstructed_a, reconstructed_b)
        if tree_digest(reconstructed_a) != tree_digest(final):
            raise AssertionError("reconstructed tree digest differs from final tree")

        # Tampering the base must fail before promotion and leave no final-named
        # output.  The verifier/applicator uses a private staging directory.
        tampered_base = temp / "tampered-base"
        shutil.copytree(base, tampered_base)
        tampered_target = tampered_base / next(iter(sorted(verified_path for verified_path in esp.scan_source(tampered_base))))
        tampered_target.write_bytes(tampered_target.read_bytes() + b"\n// tampered preimage\n")
        tampered_output = temp / "tampered-output"
        try:
            esp.apply_bundle(bundle_a, tampered_base, tampered_output, expanded_sha256)
        except esp.BundleError as exc:
            tamper_message = str(exc)
            if tampered_output.exists():
                raise AssertionError("tampered preimage promoted an output")
        else:
            raise AssertionError("tampered preimage unexpectedly applied")
        if any(temp.glob(f".{tampered_output.name}.staging-*")):
            raise AssertionError("tampered apply left a staging directory")

        # Independent tiny fixture proves every typed operation and its full
        # pre/post hash behavior without relying on the large checkout diff.
        fixture_base = temp / "fixture-base"
        fixture_final = temp / "fixture-final"
        write_fixture(fixture_base, {"keep.java": b"class Keep {}\n", "remove.java": b"class Remove {}\n"})
        write_fixture(fixture_final, {"keep.java": b"class Keep { int changed; }\n", "new.java": b"class New {}\n"})
        fixture_bundle = temp / "fixture-bundle"
        fixture_result = esp.export_bundle(fixture_base, fixture_final, fixture_bundle)
        if fixture_result["counts"] != {"add": 1, "modify": 1, "delete": 1}:
            raise AssertionError(f"fixture operation counts wrong: {fixture_result['counts']}")
        fixture_ops = esp.read_json(fixture_bundle / "operations.json")["operations"]
        if {operation["op"] for operation in fixture_ops} != {"add", "modify", "delete"}:
            raise AssertionError("fixture does not contain all typed operations")
        fixture_output = temp / "fixture-output"
        fixture_sha256 = esp._expanded_bundle_sha256(fixture_bundle)
        esp.apply_bundle(fixture_bundle, fixture_base, fixture_output, fixture_sha256)
        assert_same_tree(fixture_output, fixture_final)

        # The path guard is exercised against both traversal and a symlink.
        try:
            esp.normalize_rel("payload/../escape", field="fixture path")
        except esp.BundleError:
            pass
        else:
            raise AssertionError("path traversal was accepted")
        symlink_root = temp / "symlink-root"
        symlink_root.mkdir()
        (symlink_root / "real.java").write_bytes(b"class Real {}\n")
        (symlink_root / "link.java").symlink_to(symlink_root / "real.java")
        try:
            esp.scan_source(symlink_root)
        except esp.BundleError:
            pass
        else:
            raise AssertionError("source symlink was accepted")

        # Closed-schema regressions: false sizes, modes, unknown fields, and
        # unrelated files must all fail even when the operations digest is
        # recomputed by the mutator.
        schema_cases: list[tuple[str, object]] = [
            ("false pre_size", lambda obj: obj["operations"][0].__setitem__("pre_size", 1)),
            ("invalid mode", lambda obj: obj["operations"][0].__setitem__("mode", "120777")),
            ("unknown operation key", lambda obj: obj["operations"][0].__setitem__("unexpected", True)),
        ]
        schema_errors: dict[str, str] = {}
        for label, mutate in schema_cases:
            candidate = temp / ("schema-" + label.replace(" ", "-"))
            copy_bundle(bundle_a, candidate)
            rewrite_bundle_json(candidate, mutate)
            schema_errors[label] = expect_error(label, lambda candidate=candidate: esp.verify_bundle(candidate, base))
        extra_root = temp / "extra-root"
        copy_bundle(bundle_a, extra_root)
        (extra_root / "unexpected-root-file.txt").write_bytes(b"not part of the bundle")
        extra_root_error = expect_error("unexpected root file", lambda: esp.verify_bundle(extra_root, base))

        # Payload mutation is rejected by the payload identity binding and
        # cannot promote an output.  The externally supplied archive identity
        # is checked before parsing any self-asserted metadata.
        payload_tampered = temp / "payload-tampered"
        copy_bundle(bundle_a, payload_tampered)
        modify_operation = next(op for op in esp.read_json(payload_tampered / "operations.json")["operations"] if op["op"] == "modify")
        with (payload_tampered / modify_operation["payload"]).open("ab") as stream:
            stream.write(b"tamper")
        payload_tamper_error = expect_error("tampered payload", lambda: esp.verify_bundle(payload_tampered, base))
        operations_hash_tampered = temp / "operations-hash-tampered"
        copy_bundle(bundle_a, operations_hash_tampered)
        operations_obj = esp.read_json(operations_hash_tampered / "operations.json")
        operations_obj["operations"][0]["pre_sha256"] = "0" * 64
        esp.write_canonical_json(operations_hash_tampered / "operations.json", operations_obj)
        operations_hash_error = expect_error("tampered operations hash", lambda: esp.verify_bundle(operations_hash_tampered, base))
        tampered_archive = temp / "tampered.zip"
        tampered_archive.write_bytes(archive_a.read_bytes() + b"tamper")
        identity_output = temp / "identity-output"
        identity_error = expect_error("external bundle identity", lambda: esp.apply_bundle(tampered_archive, base, identity_output, archive_sha256))
        if identity_output.exists():
            raise AssertionError("bundle identity failure promoted output")

        # ZIP hardening: symlink members, compression bombs, member-count
        # limits, and case/Unicode-normalized collisions are rejected before
        # a bundle can reach the manifest loader.
        symlink_zip = temp / "symlink.zip"
        make_zip(symlink_zip, [("bundle.json", b"{}", 0o120777)])
        symlink_error = expect_error("archive symlink", lambda: esp.verify_bundle(symlink_zip, base))
        bomb_zip = temp / "bomb.zip"
        make_zip(bomb_zip, [("bomb", b"A" * 500_000, None)], compression=zipfile.ZIP_DEFLATED)
        bomb_error = expect_error("archive compression ratio", lambda: esp.verify_bundle(bomb_zip, base))
        many_zip = temp / "many.zip"
        with zipfile.ZipFile(many_zip, "w", compression=zipfile.ZIP_STORED) as archive:
            for index in range(esp.MAX_ARCHIVE_MEMBERS + 1):
                archive.writestr(f"m{index}", b"")
        count_error = expect_error("archive member count", lambda: esp.verify_bundle(many_zip, base))
        collision_zip = temp / "collision.zip"
        make_zip(collision_zip, [("A", b"x", None), ("a", b"y", None)])
        collision_error = expect_error("archive case collision", lambda: esp.verify_bundle(collision_zip, base))
        unicode_root = temp / "unicode-root"
        (unicode_root / "e\u0301.java").parent.mkdir(parents=True)
        (unicode_root / "e\u0301.java").write_bytes(b"class E {}\n")
        (unicode_root / "\u00e9.java").write_bytes(b"class E2 {}\n")
        unicode_error = expect_error("source Unicode collision", lambda: esp.scan_source(unicode_root))

        # A failed export leaves no final-named output, because population is
        # staged in a sibling directory and promoted only after validation.
        invalid_final = temp / "invalid-final"
        write_fixture(invalid_final, {"ok.java": b"class Ok {}\n", "bad.txt": b"x"})
        failed_export_output = temp / "failed-export"
        expect_error("invalid export input", lambda: esp.export_bundle(fixture_base, invalid_final, failed_export_output))
        if failed_export_output.exists() or any(temp.glob(f".{failed_export_output.name}.staging-*")):
            raise AssertionError("failed export left a promoted/staging output")

        result = {
            "status": "passed",
            "counts": verified["counts"],
            "frozen_count_expectation": EXPECTED_COUNTS,
            "frozen_count_expectation_matches": frozen_count_expectation_matches,
            "frozen_manifests_match": frozen_manifests_match,
            "base_file_count": verified["base_file_count"],
            "final_file_count": verified["final_file_count"],
            "operation_count": verified["operation_count"],
            "payload_file_count": len(payload_operations),
            "payload_bytes": payload_bytes,
            "final_source_bytes": final_source_bytes,
            "base_manifest_sha256": verified["base_manifest_sha256"],
            "final_manifest_sha256": verified["final_manifest_sha256"],
            "export_archive_sha256": archive_sha256,
            "export_archive_size": archive_a.stat().st_size,
            "double_export_byte_identical": True,
            "double_reconstruction_byte_identical": True,
            "tampered_preimage_rejected": True,
            "tampered_preimage_error": tamper_message,
            "typed_add_modify_delete_fixture": True,
            "path_traversal_rejected": True,
            "symlink_rejected": True,
            "modify_delta_payloads": True,
            "schema_false_pre_size_rejected": True,
            "schema_invalid_mode_rejected": True,
            "schema_unknown_key_rejected": True,
            "unexpected_root_file_rejected": True,
            "tampered_payload_rejected": True,
            "tampered_operations_hash_rejected": True,
            "external_bundle_identity_rejected": True,
            "archive_symlink_rejected": True,
            "archive_ratio_limit_rejected": True,
            "archive_member_limit_rejected": True,
            "archive_case_collision_rejected": True,
            "source_unicode_collision_rejected": True,
            "failed_export_not_promoted": True,
            "schema_errors": schema_errors,
            "extra_root_error": extra_root_error,
            "payload_tamper_error": payload_tamper_error,
            "operations_hash_error": operations_hash_error,
            "identity_error": identity_error,
            "archive_symlink_error": symlink_error,
            "archive_ratio_error": bomb_error,
            "archive_member_error": count_error,
            "archive_collision_error": collision_error,
            "unicode_collision_error": unicode_error,
        }
    if receipt is not None:
        receipt.parent.mkdir(parents=True, exist_ok=True)
        receipt.write_bytes(esp.canonical_json(result))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--final", type=Path, required=True)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--require-frozen", action="store_true")
    args = parser.parse_args()
    try:
        print(json.dumps(run(args.base, args.final, args.receipt, args.require_frozen), ensure_ascii=False, sort_keys=True))
        return 0
    except (AssertionError, esp.BundleError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
