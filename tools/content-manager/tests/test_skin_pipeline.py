from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image


PIPELINE_PATH = Path(__file__).parents[1] / "skin-pipeline" / "assemble_skin.py"
PRESET_GENERATOR_PATH = Path(__file__).parents[1] / "generate_easy_npc_presets.py"
SPEC = importlib.util.spec_from_file_location("assemble_skin", PIPELINE_PATH)
assert SPEC is not None and SPEC.loader is not None
assemble_skin = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = assemble_skin
SPEC.loader.exec_module(assemble_skin)
PRESET_SPEC = importlib.util.spec_from_file_location("generate_easy_npc_presets", PRESET_GENERATOR_PATH)
assert PRESET_SPEC is not None and PRESET_SPEC.loader is not None
generate_easy_npc_presets = importlib.util.module_from_spec(PRESET_SPEC)
sys.modules[PRESET_SPEC.name] = generate_easy_npc_presets
PRESET_SPEC.loader.exec_module(generate_easy_npc_presets)


class SkinPipelineTests(unittest.TestCase):
    def test_slim_layout_uses_three_pixel_front_and_back_arm_faces(self) -> None:
        for arm in ("right_arm", "left_arm"):
            sizes = assemble_skin.SLIM_UV_LAYOUT[arm]["sizes"]
            self.assertEqual((3, 12), sizes["front"])
            self.assertEqual((3, 12), sizes["back"])
            self.assertEqual((4, 12), sizes["left"])
            self.assertEqual((4, 12), sizes["right"])

    def test_head_side_cleanup_removes_front_edge_features(self) -> None:
        face = Image.new("RGBA", (8, 8), (40, 60, 80, 255))
        for y in range(2, 6):
            for x in (1, 2, 5, 6):
                face.putpixel((x, y), (250, 250, 250, 255))

        left = assemble_skin.remove_head_side_features(face, "left")
        right = assemble_skin.remove_head_side_features(face, "right")

        self.assertNotIn((250, 250, 250, 255), [left.getpixel((x, y)) for x in range(4) for y in range(8)])
        self.assertNotIn((250, 250, 250, 255), [right.getpixel((x, y)) for x in range(4, 8) for y in range(8)])

    def test_manual_retouch_requires_a_64_by_64_png(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            valid = Path(directory) / "valid.png"
            invalid = Path(directory) / "invalid.png"
            Image.new("RGBA", (64, 64), (10, 20, 30, 255)).save(valid)
            Image.new("RGBA", (32, 64), (10, 20, 30, 255)).save(invalid)

            self.assertEqual((64, 64), assemble_skin.load_manual_retouch(valid).size)
            with self.assertRaisesRegex(ValueError, "64x64 PNG"):
                assemble_skin.load_manual_retouch(invalid)

    def test_chroma_cleanup_removes_magenta_antialias_fringe_but_keeps_dark_purple(self) -> None:
        atlas = Image.new("RGBA", (3, 1))
        atlas.putdata([
            (255, 0, 255, 255),
            (205, 24, 214, 255),
            (92, 45, 126, 255),
        ])

        cleaned = assemble_skin.remove_chroma(atlas, (255, 0, 255), 45)

        self.assertEqual([0, 0, 255], [pixel[3] for pixel in cleaned.get_flattened_data()])
        quantized_fringe = Image.new("RGBA", (3, 1))
        quantized_fringe.putdata([(20, 40, 80, 255), (199, 73, 121, 255), (20, 40, 80, 255)])
        repaired = assemble_skin.extend_edge_pixels(
            assemble_skin.remove_chroma(quantized_fringe, (255, 0, 255), 45),
            (1, 2, 3),
        )
        self.assertEqual([(20, 40, 80, 255)] * 3, list(repaired.get_flattened_data()))

    def test_face_cleanup_removes_outline_extends_corners_and_never_blends_colors(self) -> None:
        atlas = Image.new("RGBA", (12, 12))
        pixels = atlas.load()
        for y in range(2, 10):
            for x in range(2, 10):
                if 3 <= x <= 8 and 3 <= y <= 8:
                    pixels[x, y] = (240, 80, 40, 255) if x < 6 else (40, 120, 240, 255)
                else:
                    pixels[x, y] = (8, 8, 8, 255)

        face = assemble_skin.crop_face(atlas, [0, 0, 12, 12], (4, 4), (1, 2, 3), 58)

        face_pixels = list(face.get_flattened_data()) if hasattr(face, "get_flattened_data") else list(face.getdata())
        self.assertTrue(all(pixel[3] == 255 for pixel in face_pixels))
        self.assertNotIn((8, 8, 8, 255), set(face_pixels))
        self.assertLessEqual(set(face_pixels), {(240, 80, 40, 255), (40, 120, 240, 255)})

    def test_youngster_manifest_covers_every_uv_face(self) -> None:
        manifest_path = PIPELINE_PATH.parent / "work" / "youngster" / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.assertEqual("slim", manifest["model"])
        self.assertEqual(set(assemble_skin.UV_LAYOUT), set(manifest["parts"]))
        for part_name, layout in assemble_skin.UV_LAYOUT.items():
            self.assertEqual(set(layout["sizes"]), set(manifest["parts"][part_name]))

    def test_youngster_build_is_valid_modern_skin_with_overlay(self) -> None:
        manifest_path = PIPELINE_PATH.parent / "work" / "youngster" / "manifest.json"
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "youngster.png"
            assemble_skin.assemble(manifest_path, output)
            with Image.open(output) as skin:
                self.assertEqual((64, 64), skin.size)
                self.assertEqual("RGBA", skin.mode)
                alpha = skin.getchannel("A")
                self.assertEqual(255, alpha.getpixel((8, 8)))
            self.assertGreater(alpha.crop((32, 0, 64, 48)).getbbox()[2], 0)

    def test_equipment_skin_removes_hat_and_exports_it_as_armor(self) -> None:
        manifest_path = PIPELINE_PATH.parent / "work" / "youngster" / "manifest.json"
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "youngster.png"
            assemble_skin.assemble(manifest_path, output)
            with Image.open(output) as skin:
                equipment_skin = assemble_skin.equipment_base_skin(skin)
                armor = assemble_skin.armor_texture(skin)
                self.assertIsNone(equipment_skin.crop((32, 0, 64, 16)).getbbox())
                self.assertIsNotNone(armor.getbbox())
                self.assertEqual((64, 32), armor.size)

    def test_easy_npc_preset_uses_head_slot_custom_skin_and_root_scale(self) -> None:
        root = Path(__file__).parents[3]
        catalog = json.loads(
            (root / "content" / "catalogs" / "trainer-outfits.json").read_text(encoding="utf-8")
        )
        preset = generate_easy_npc_presets.preset_snbt(catalog["outfits"][0])
        self.assertIn('ArmorItems:[{},{},{},{Count:1b,id:"cobbleventure_bootstrap:youngster_cap"}]', preset)
        self.assertIn('SkinData:{Type:"CUSTOM",UUID:[I;', preset)
        self.assertIn('ModelData:{Root:{Scale:[0.780f,0.780f,0.780f]}}', preset)

    def test_missing_rocket_executives_build_as_valid_named_skins(self) -> None:
        root = Path(__file__).parents[3]
        for slug in ("proton", "petrel"):
            manifest_path = PIPELINE_PATH.parent / "work" / slug / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual("slim", manifest["model"])
            with self.subTest(slug=slug), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / f"{slug}.png"
                assemble_skin.assemble(manifest_path, output)
                with Image.open(output) as skin:
                    self.assertEqual((64, 64), skin.size)
                    self.assertEqual("RGBA", skin.mode)
                    self.assertIsNotNone(skin.getbbox())
        roster = json.loads(
            (root / "content" / "catalogs" / "trainer-roster.json").read_text(encoding="utf-8")
        )
        rocket = next(
            organization
            for organization in roster["organizations"]
            if organization["id"].endswith("/team_rocket")
        )
        named = {
            character["id"].rsplit("/", 1)[-1]: character
            for character in rocket["named_characters"]
        }
        self.assertEqual(
            "cobbleventure:trainer_skin/proton",
            named["proton"]["appearance"]["resource"],
        )
        self.assertEqual(
            "cobbleventure:trainer_skin/petrel",
            named["petrel"]["appearance"]["resource"],
        )

    def test_general_trainer_auto_uv_manifests_build_valid_skins(self) -> None:
        slugs = (
            "preschooler", "backpacker", "boarder", "hex_maniac", "bug_maniac",
            "kindler", "office_worker", "cook", "waiter", "musician", "maid",
            "old_couple",
        )
        for slug in slugs:
            manifest_path = PIPELINE_PATH.parent / "work" / slug / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual("four_row_atlas_v1", manifest["auto_layout"])
            self.assertEqual("slim", manifest["model"])
            with self.subTest(slug=slug), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / f"{slug}.png"
                assemble_skin.assemble(manifest_path, output)
                with Image.open(output) as skin:
                    self.assertEqual((64, 64), skin.size)
                    self.assertEqual("RGBA", skin.mode)
                    self.assertIsNotNone(skin.getbbox())


if __name__ == "__main__":
    unittest.main()
