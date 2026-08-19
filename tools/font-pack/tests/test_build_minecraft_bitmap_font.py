import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

from PIL import Image


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "tools/font-pack/build_minecraft_bitmap_font.py"
SPEC = importlib.util.spec_from_file_location("build_minecraft_bitmap_font", SCRIPT)
FONT_PACK = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = FONT_PACK
SPEC.loader.exec_module(FONT_PACK)


class MinecraftBitmapFontTest(unittest.TestCase):
    def test_builds_native_binary_global_font_deterministically(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            temporary = Path(temporary)
            source = temporary / "PokemonBW.zip"
            first = temporary / "first.zip"
            second = temporary / "second.zip"
            font = (
                ROOT
                / "projects/cobbleventure-world-bootstrap/src/main/resources/"
                "assets/cobbleventure/font/pokemon_bw.ttf"
            )
            with zipfile.ZipFile(source, "w") as archive:
                archive.write(font, "Pokemon-BW.ttf")
                archive.writestr("license.txt", "CC BY-SA 3.0")
                archive.writestr("readme.txt", "Pokemon BW")

            result = FONT_PACK.build_pack(source, first)
            FONT_PACK.build_pack(source, second)

            self.assertEqual(first.read_bytes(), second.read_bytes())
            self.assertEqual(15, result["cell_height"])
            self.assertEqual(13, result["ascent"])
            with zipfile.ZipFile(first) as archive:
                definition = json.loads(
                    archive.read("assets/minecraft/font/default.json")
                )
                bitmap = next(
                    provider for provider in definition["providers"]
                    if provider["type"] == "bitmap"
                )
                fallback = definition["providers"][-1]
                self.assertEqual("minecraft:font/pokemon_bw.png", bitmap["file"])
                self.assertEqual(9, bitmap["height"])
                self.assertEqual(8, bitmap["ascent"])
                self.assertEqual("minecraft:uniform", fallback["id"])
                atlas = Image.open(io.BytesIO(archive.read(
                    "assets/minecraft/textures/font/pokemon_bw.png"
                )))
                alpha_values = {
                    value for value, count
                    in enumerate(atlas.getchannel("A").histogram()) if count
                }
                self.assertEqual({0, 255}, alpha_values)


if __name__ == "__main__":
    unittest.main()
