from __future__ import annotations

import importlib.util
import json
import struct
import sys
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "pack_builder.py"
SPEC = importlib.util.spec_from_file_location("pack_builder", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
pack_builder = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = pack_builder
SPEC.loader.exec_module(pack_builder)


class PackBuilderTests(unittest.TestCase):
    def _write_png(self, path: Path, width: int = 400, height: int = 400) -> None:
        def chunk(name: bytes, data: bytes) -> bytes:
            payload = name + data
            return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload))

        rows = b"".join(b"\x00" + b"\x20\x80\xc0" * width for _ in range(height))
        png = b"\x89PNG\r\n\x1a\n"
        png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        png += chunk(b"IDAT", zlib.compress(rows))
        png += chunk(b"IEND", b"")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(png)

    def _fixture(self, root: Path) -> Path:
        overrides = root / "pack" / "overrides" / "smoke" / "config"
        overrides.mkdir(parents=True)
        (overrides / "marker.txt").write_text("smoke\n", encoding="utf-8")
        self._write_png(root / "pack" / "assets" / "icon.png")
        profile_path = root / "pack" / "profiles" / "smoke.json"
        profile_path.parent.mkdir(parents=True)
        profile = {
            "schema_version": 1,
            "profile_id": "smoke",
            "name": "Smoke",
            "version": "0.0.1",
            "author": "Test",
            "purpose": "test-fixture",
            "production_ready": False,
            "notice": "Test only",
            "icon": "pack/assets/icon.png",
            "image_url": "https://example.invalid/icon.png",
            "minecraft": {
                "version": "1.21.1",
                "mod_loader": {
                    "id": "neoforge-21.1.248",
                    "primary": True,
                },
            },
            "files": [],
            "overrides_directory": "pack/overrides/smoke",
            "output": "dist/smoke.zip",
        }
        profile_path.write_text(json.dumps(profile), encoding="utf-8")
        return profile_path.relative_to(root)

    def test_builds_and_validates_minimal_curseforge_zip(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            profile_path = self._fixture(root)
            output = pack_builder.build_pack(root, profile_path)
            manifest = pack_builder.validate_pack(
                output,
                root=root,
                profile_path=profile_path,
            )
            with zipfile.ZipFile(output) as archive:
                names = archive.namelist()
                pack_info = json.loads(
                    archive.read("overrides/cobbleventure-pack-info.json")
                )
                root_icon = archive.read("icon.png")
                override_icon = archive.read("overrides/icon.png")
            self.assertEqual("minecraftModpack", manifest["manifestType"])
            self.assertEqual("https://example.invalid/icon.png", manifest["image"])
            self.assertEqual([], manifest["files"])
            self.assertIn("manifest.json", names)
            self.assertIn("icon.png", names)
            self.assertIn("overrides/", names)
            self.assertIn("overrides/icon.png", names)
            self.assertIn("overrides/config/marker.txt", names)
            self.assertEqual(root_icon, override_icon)
            self.assertEqual("test-fixture", pack_info["purpose"])
            self.assertFalse(pack_info["production_ready"])
            self.assertTrue(output.with_name(output.name + ".sha256").is_file())

    def test_rejects_output_outside_dist(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            profile_path = self._fixture(root)
            absolute_profile = root / profile_path
            profile = json.loads(absolute_profile.read_text(encoding="utf-8"))
            profile["output"] = "smoke.zip"
            absolute_profile.write_text(json.dumps(profile), encoding="utf-8")
            with self.assertRaises(pack_builder.PackError):
                pack_builder.load_profile(root, profile_path)

    def test_rejects_small_or_non_square_icon(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            profile_path = self._fixture(root)
            self._write_png(root / "pack" / "assets" / "icon.png", 399, 400)
            with self.assertRaises(pack_builder.PackError):
                pack_builder.load_profile(root, profile_path)

    def test_rejects_non_https_image_url(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            profile_path = self._fixture(root)
            absolute_profile = root / profile_path
            profile = json.loads(absolute_profile.read_text(encoding="utf-8"))
            profile["image_url"] = "overrides/icon.png"
            absolute_profile.write_text(json.dumps(profile), encoding="utf-8")
            with self.assertRaises(pack_builder.PackError):
                pack_builder.load_profile(root, profile_path)


if __name__ == "__main__":
    unittest.main()
