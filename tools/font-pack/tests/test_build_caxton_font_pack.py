import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "build_caxton_font_pack.py"
SPEC = importlib.util.spec_from_file_location("build_caxton_font_pack", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class CaxtonFontPackTest(unittest.TestCase):
    def test_builds_msdf_default_font_with_vanilla_fallback(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary = Path(temporary_directory)
            source = temporary / "source.zip"
            output = temporary / "output.zip"
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("font.ttf", b"test-font")
                archive.writestr("license.txt", b"license")
                archive.writestr("readme.txt", b"readme")

            result = MODULE.build_pack(source, output)

            self.assertEqual(6, result["files"])
            with zipfile.ZipFile(output) as archive:
                definition = json.loads(archive.read("assets/minecraft/font/default.json"))
                metadata = json.loads(
                    archive.read("assets/minecraft/textures/font/pokemon_bw.ttf.json")
                )
                self.assertEqual("caxton", definition["caxton_providers"][0]["type"])
                self.assertEqual(
                    "minecraft:pokemon_bw.ttf",
                    definition["caxton_providers"][0]["regular"]["file"],
                )
                self.assertEqual("minecraft:uniform", definition["providers"][0]["id"])
                self.assertEqual("msdf", metadata["tech"])
                self.assertEqual(
                    b"test-font",
                    archive.read("assets/minecraft/textures/font/pokemon_bw.ttf"),
                )

    def test_output_is_deterministic(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary = Path(temporary_directory)
            source = temporary / "source.zip"
            first = temporary / "first.zip"
            second = temporary / "second.zip"
            with zipfile.ZipFile(source, "w") as archive:
                archive.writestr("font.ttf", b"test-font")
                archive.writestr("license.txt", b"license")
                archive.writestr("readme.txt", b"readme")

            MODULE.build_pack(source, first)
            MODULE.build_pack(source, second)

            self.assertEqual(first.read_bytes(), second.read_bytes())


if __name__ == "__main__":
    unittest.main()
