from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image


PIPELINE_PATH = Path(__file__).parents[1] / "skin-pipeline" / "assemble_skin.py"
COMMUNITY_IMPORT_PATH = Path(__file__).parents[1] / "skin-pipeline" / "import_community_skin.py"
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
COMMUNITY_SPEC = importlib.util.spec_from_file_location("import_community_skin", COMMUNITY_IMPORT_PATH)
assert COMMUNITY_SPEC is not None and COMMUNITY_SPEC.loader is not None
import_community_skin = importlib.util.module_from_spec(COMMUNITY_SPEC)
sys.modules[COMMUNITY_SPEC.name] = import_community_skin
COMMUNITY_SPEC.loader.exec_module(import_community_skin)


class SkinPipelineTests(unittest.TestCase):
    def test_community_skin_arm_conversion_preserves_nearest_uv(self) -> None:
        source = Image.new("RGBA", (64, 64), (12, 34, 56, 255))
        slim = import_community_skin.convert_arm_model(source, "classic", "slim")
        restored = import_community_skin.convert_arm_model(slim, "slim", "classic")
        self.assertEqual((64, 64), slim.size)
        self.assertEqual((64, 64), restored.size)
        self.assertEqual((12, 34, 56, 255), slim.getpixel((44, 20)))
        self.assertEqual((12, 34, 56, 255), restored.getpixel((47, 20)))

    def test_attributed_community_skins_are_present_and_64px(self) -> None:
        root = Path(__file__).parents[3]
        project_root = root / "content-projects" / "cobbleventure-main"
        catalog = json.loads((project_root / "content/catalogs/trainer-skin-sources.json").read_text(encoding="utf-8"))
        texture_root = root / "projects/cobbleventure-world-bootstrap/src/main/resources/assets/cobbleventure/textures/entity/trainer"
        self.assertEqual(26, len(catalog["skins"]))
        for entry in catalog["skins"]:
            slug = entry["resource"].rsplit("/", 1)[-1]
            with Image.open(texture_root / f"{slug}.png") as skin:
                self.assertEqual((64, 64), skin.size, slug)
            self.assertIn(entry["source_model"], {"classic", "slim"})
            self.assertIn(entry["target_model"], {"classic", "slim"})

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

    def test_tall_head_crop_discards_hair_from_top_without_squeezing_face(self) -> None:
        atlas = Image.new("RGBA", (8, 12), (180, 40, 40, 255))
        for y in range(4, 12):
            for x in range(8):
                atlas.putpixel((x, y), (230, 170, 120, 255))

        head = assemble_skin.crop_face(
            atlas, [0, 0, 8, 12], (8, 8), (1, 2, 3),
            outline_threshold=0, vertical_anchor="bottom",
        )

        self.assertEqual({(230, 170, 120, 255)}, set(head.get_flattened_data()))

    def test_youngster_manifest_covers_every_uv_face(self) -> None:
        manifest_path = PIPELINE_PATH.parent / "work" / "youngster" / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.assertEqual("classic", manifest["model"])
        self.assertEqual("four_row_atlas_v1", manifest["auto_layout"])
        self.assertEqual(["head"], manifest["overlay_parts"])

    def test_four_view_head_reuses_hair_back_for_top_and_bottom(self) -> None:
        atlas = Image.new("RGBA", (400, 600))
        for row in range(6):
            for column in range(4):
                x0, y0 = 10 + column * 95, 10 + row * 95
                color = (20 + column * 30, 30 + row * 20, 80, 255)
                for y in range(y0, y0 + 60):
                    for x in range(x0, x0 + 60):
                        atlas.putpixel((x, y), color)
        parts = assemble_skin.auto_detect_parts(atlas)
        self.assertEqual(parts["head"]["back"], parts["head"]["top"])
        self.assertEqual(parts["head"]["back"], parts["head"]["bottom"])

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
        project_root = root / "content-projects" / "cobbleventure-main"
        catalog = json.loads(
            (project_root / "content" / "catalogs" / "trainer-outfits.json").read_text(encoding="utf-8")
        )
        preset = generate_easy_npc_presets.preset_snbt(catalog["outfits"][0])
        self.assertIn('ArmorItems:[{},{},{},{Count:1b,id:"cobbleventure_bootstrap:youngster_cap"}]', preset)
        self.assertIn('SkinData:{Type:"CUSTOM",UUID:[I;', preset)
        self.assertIn('ModelData:{Root:{Scale:[0.780f,0.780f,0.780f]}}', preset)

    def test_gate_teleport_action_targets_player_or_npc(self) -> None:
        player = generate_easy_npc_presets.easy_npc_action({
            "type": "teleport_to_gate", "gate": "route_01_gate",
            "subject": "player", "side": "front",
        }, {})
        npc = generate_easy_npc_presets.easy_npc_action({
            "type": "teleport_to_gate", "gate": "route_01_gate",
            "subject": "npc", "side": "center",
        }, {})
        self.assertIn("cobbleventure_gate teleport @initiator route_01_gate front", player)
        self.assertIn("cobbleventure_gate teleport @npc-uuid route_01_gate center", npc)

    def test_missing_rocket_executives_build_as_valid_named_skins(self) -> None:
        root = Path(__file__).parents[3]
        project_root = root / "content-projects" / "cobbleventure-main"
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
            (project_root / "content" / "catalogs" / "trainer-roster.json").read_text(encoding="utf-8")
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
        models = {
            "preschooler": "slim", "backpacker": "slim", "boarder": "slim",
            "hex_maniac": "slim", "bug_maniac": "classic", "kindler": "classic",
            "office_worker": "slim", "cook": "slim", "waiter": "slim",
            "musician": "slim", "maid": "slim",
            "pokemon_ranger_male": "classic", "pokemon_ranger_female": "slim",
            "old_couple_male": "classic", "old_couple_female": "slim",
            "interviewers_male": "classic", "interviewers_female": "slim",
        }
        for slug, model in models.items():
            manifest_path = PIPELINE_PATH.parent / "work" / slug / "manifest.json"
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual("four_row_atlas_v1", manifest["auto_layout"])
            self.assertEqual(model, manifest["model"])
            with self.subTest(slug=slug), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / f"{slug}.png"
                assemble_skin.assemble(manifest_path, output)
                with Image.open(output) as skin:
                    self.assertEqual((64, 64), skin.size)
                    self.assertEqual("RGBA", skin.mode)
                    self.assertIsNotNone(skin.getbbox())


if __name__ == "__main__":
    unittest.main()
