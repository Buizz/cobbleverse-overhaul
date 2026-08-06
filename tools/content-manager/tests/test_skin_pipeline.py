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
    def test_youngster_manifest_covers_every_uv_face(self) -> None:
        manifest_path = PIPELINE_PATH.parent / "work" / "youngster" / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
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


if __name__ == "__main__":
    unittest.main()
