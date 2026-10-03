#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
import unittest
import zipfile

import export_skeleton


def validate_archive_modes(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if info.filename == "skeleton-manifest.json":
                continue
            mode = (info.external_attr >> 16) & 0o777
            expected = 0o755 if info.filename == "gradlew" or info.filename.endswith(".sh") else 0o644
            if mode != expected:
                raise ValueError(f"unexpected mode for {info.filename}: {mode:o}, expected {expected:o}")


class SkeletonExportTest(unittest.TestCase):
    def test_allowlist_and_deterministic_clean_extract(self) -> None:
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(prefix="eagler-skeleton-test-") as directory:
            work = Path(directory)
            first = work / "first.zip"
            second = work / "second.zip"
            first_manifest = export_skeleton.write_archive(root, first)
            second_manifest = export_skeleton.write_archive(root, second)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertEqual(first_manifest, second_manifest)
            validate_archive_modes(first)
            self.assertEqual(first_manifest["files"][0]["mode"], "100644")
            self.assertEqual(next(item for item in first_manifest["files"] if item["path"] == "gradlew")["mode"], "100755")

            with zipfile.ZipFile(first) as archive:
                names = archive.namelist()
                self.assertEqual(names, sorted(names))
                self.assertIn("skeleton-manifest.json", names)
                self.assertNotIn("game/src/main/java/net/minecraft/client/Minecraft.java", names)
                self.assertFalse(any(name.startswith("game/src/main/resources/") for name in names))
                self.assertFalse(any(name.startswith("output/") for name in names))
                self.assertFalse(any(name.startswith("patcher/") for name in names))
                notice_names = {name for name in names if name.startswith("third_party/skeleton/")}
                expected_notices = {
                    f"third_party/skeleton/{path.relative_to(root / export_skeleton.NOTICE_SOURCE).as_posix()}"
                    for path in (root / export_skeleton.NOTICE_SOURCE).rglob("*") if path.is_file()
                }
                self.assertEqual(notice_names, expected_notices)
                for name in notice_names:
                    self.assertEqual(
                        archive.read(name),
                        (root / export_skeleton.NOTICE_SOURCE
                         / name.removeprefix("third_party/skeleton/")).read_bytes(),
                    )
                self.assertIn("third_party/skeleton/THIRD_PARTY_NOTICES.md", notice_names)
                self.assertIn("third_party/skeleton/UNRESOLVED-UnsafeMemcpy.md", notice_names)
                self.assertTrue(any(name.endswith("GRADLE-9.6.1-LICENSE.txt") for name in notice_names))
                self.assertTrue(any(name.endswith("TEAVM-0.13.1-LICENSE.txt") for name in notice_names))
                self.assertFalse(any("/build/" in f"/{name}" for name in names))
                required_standalone = {
                    item for item in export_skeleton.EXACT_FILES
                    if item.startswith("wasm-toolchain/")
                }
                self.assertTrue(required_standalone.issubset(names))
                top_level_toolchain_files = {
                    name for name in names
                    if name.startswith("wasm-toolchain/") and name.count("/") == 1
                }
                self.assertEqual(top_level_toolchain_files, required_standalone)
                self.assertIn("package.json", names)
                self.assertIn("package-lock.json", names)
                package = json.loads(archive.read("package.json"))
                lock = json.loads(archive.read("package-lock.json"))
                self.assertEqual(package["scripts"]["build:single"], "node wasm-toolchain/build-single-html.js")
                self.assertEqual(package["dependencies"], {"brotli-dec-wasm": "2.3.2"})
                self.assertEqual(lock["lockfileVersion"], 3)
                self.assertEqual(lock["packages"][""]["dependencies"], package["dependencies"])
                self.assertEqual(
                    set(lock["packages"]), {"", "node_modules/brotli-dec-wasm"}
                )
                manifest = json.loads(archive.read("skeleton-manifest.json"))
                self.assertEqual(manifest["file_count"], len(names) - 1)
                self.assertEqual(manifest["schema"], export_skeleton.SCHEMA)
                self.assertEqual(manifest["capability"]["entrypoint"],
                                 "npm run build:single -- --output <path>; node iwa/build-iwa.mjs --source <hosted-web-dir> --output <local.swbn> --key <local.pem>")
                self.assertIn("iwa/build-iwa.mjs", names)
                self.assertIn("iwa/package-lock.json", names)
                self.assertIn("iwa/README.md", names)
                iwa_readme = archive.read("iwa/README.md").decode("utf-8")
                self.assertIn("chrome://web-app-internals/", iwa_readme)
                self.assertIn("Enable Isolated Web Apps", iwa_readme)
                self.assertIn("Install IWA from Signed Web Bundle", iwa_readme)
                self.assertEqual(iwa_readme, (root / "iwa/README.md").read_text(encoding="utf-8"))
                self.assertIn("cloudflare-client/brotli-decoder-worker.js", names)
                self.assertIn("wasm-toolchain/generate-iwa-csp-runtime.mjs", names)
                self.assertTrue(any("sounds.epk" in item
                                    for item in manifest["capability"]["external_inputs_required"]))
                self.assertTrue(any("no fresh standalone" in item
                                    for item in manifest["capability"]["known_blockers"]))
                deploy = archive.read("wasm-toolchain/deploy_wasm_web.sh").decode("utf-8")
                self.assertNotIn(str(root), deploy)
                self.assertIn('BASH_SOURCE[0]', deploy)
                builder = archive.read("wasm-toolchain/build-single-html.js").decode("utf-8")
                deploy_step = builder.index('"wasm-toolchain/deploy_wasm_web.sh"')
                runtime_gate = builder.index("client linker did not install the required TeaVM 0.13.1 runtime JS")
                self.assertLess(runtime_gate, deploy_step)
                self.assertIn('"classes.wasm-runtime.js"', builder)
                self.assertIn("1e80092312d7bfe6efa74f8bb372d5521bc0549dcfbf384f25a145261a80d124", builder)
                self.assertIn("WASM_GC_RUNTIME_BYTES = 13984", builder)
                client_linker = archive.read("wasm-toolchain/link-web-target-standalone.js").decode("utf-8")
                self.assertIn("org/teavm/backend/wasm/wasm-gc-runtime.min.js", client_linker)
                self.assertIn("1e80092312d7bfe6efa74f8bb372d5521bc0549dcfbf384f25a145261a80d124", client_linker)
                self.assertIn("13984", client_linker)
                self.assertIn("installClientRuntime(toolClasspath", client_linker)
                self.assertTrue(any("authenticated TeaVM 0.13.1 tool classpath" in item
                                    for item in manifest["capability"]["generated_inputs"]))
                self.assertTrue(any(item.startswith("unzip ")
                                    for item in manifest["capability"]["host_tools"]))
                self.assertIn('target_teavm_wasm_gc/build/generated/teavm/wasm-gc/classes.wasm-runtime.js', deploy)
                self.assertNotIn('target_teavm_spike/build/generated', deploy)
                self.assertIn('ERROR: missing authenticated TeaVM 0.13.1 client runtime', deploy)
                for item in manifest["files"]:
                    payload = archive.read(item["path"])
                    self.assertEqual(len(payload), item["size"])
                    self.assertEqual(hashlib.sha256(payload).hexdigest(), item["sha256"])
                    expected_mode = 0o755 if item["path"] == "gradlew" or item["path"].endswith(".sh") else 0o644
                    self.assertEqual(item["mode"], f"100{expected_mode & 0o777:03o}")

            extracted = work / "extracted"
            extracted.mkdir()
            self.assertIsNotNone(shutil.which("unzip"))
            subprocess.run(["unzip", "-q", str(first), "-d", str(extracted)], check=True)
            self.assertTrue((extracted / "gradlew").stat().st_mode & stat.S_IXUSR)
            subprocess.run([str(extracted / "gradlew"), "--version"], cwd=extracted, check=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            deploy_result = subprocess.run(
                ["bash", str(extracted / "wasm-toolchain/deploy_wasm_web.sh")],
                cwd=extracted, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            )
            self.assertEqual(deploy_result.returncode, 1)
            self.assertIn("ERROR: missing authenticated TeaVM 0.13.1 client runtime", deploy_result.stdout)
            self.assertFalse((extracted / "target_teavm_wasm_gc/build/web").exists())
            self.assertEqual((extracted / "settings.gradle.kts").read_bytes(), (root / "settings.gradle.kts").read_bytes())
            self.assertFalse((extracted / "game/src/main/java").exists())
            self.assertFalse((extracted / "game/src/main/resources").exists())
            self.assertFalse((extracted / "mojang-source").exists())
            self.assertFalse((extracted / "patcher").exists())
            self.assertTrue((extracted / "third_party/skeleton/THIRD_PARTY_NOTICES.md").is_file())

            included = {item["path"] for item in first_manifest["files"]}
            local_require = re.compile(r"require\([\"'](\./[^\"']+)[\"']\)")
            for relative in sorted(included):
                if not relative.startswith("wasm-toolchain/") or not relative.endswith(".js"):
                    continue
                source = (root / relative).read_text(encoding="utf-8")
                for match in local_require.finditer(source):
                    required = (Path(relative).parent / match.group(1)).as_posix()
                    if required not in included and required + ".js" in included:
                        required += ".js"
                    self.assertIn(required, included,
                                  f"local require from {relative} is outside the allowlist")

            tampered = work / "tampered-mode.zip"
            with zipfile.ZipFile(first) as source, zipfile.ZipFile(tampered, "w", compression=zipfile.ZIP_STORED) as destination:
                for original in source.infolist():
                    info = zipfile.ZipInfo(original.filename)
                    info.date_time = original.date_time
                    info.compress_type = zipfile.ZIP_STORED
                    info.create_system = original.create_system
                    info.external_attr = (0o100644 << 16) if original.filename == "gradlew" else original.external_attr
                    destination.writestr(info, source.read(original.filename))
            with self.assertRaises(ValueError):
                validate_archive_modes(tampered)

    def test_denies_symlink_in_allowlisted_tree(self) -> None:
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(prefix="eagler-skeleton-link-") as directory:
            copy = Path(directory) / "root"
            copy.mkdir()
            for relative in export_skeleton.ROOT_FILES:
                source = root / relative
                (copy / relative).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, copy / relative)
            for relative in export_skeleton.EXACT_FILES:
                source = root / relative
                (copy / relative).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, copy / relative)
            (copy / "gradle/wrapper").mkdir(parents=True)
            for source in (root / "gradle/wrapper").iterdir():
                shutil.copy2(source, copy / "gradle/wrapper" / source.name)
            for relative in export_skeleton.DIRECTORIES[1:]:
                (copy / relative).mkdir(parents=True, exist_ok=True)
            notice_copy = copy / export_skeleton.NOTICE_SOURCE
            shutil.copytree(root / export_skeleton.NOTICE_SOURCE, notice_copy)
            notice_link = notice_copy / "outside.txt"
            notice_link.symlink_to(root / "settings.gradle.kts")
            with self.assertRaises(ValueError):
                export_skeleton.selected_paths(copy)
            notice_link.unlink()
            (copy / "platform/src").mkdir(parents=True, exist_ok=True)
            (copy / "platform/src/main.java").symlink_to(root / "settings.gradle.kts")
            with self.assertRaises(ValueError):
                export_skeleton.selected_paths(copy)


if __name__ == "__main__":
    unittest.main()
