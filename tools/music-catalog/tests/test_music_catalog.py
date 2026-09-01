from __future__ import annotations

import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
PROJECT_ROOT = ROOT / "content-projects" / "cobbleventure-main"
MODULE_PATH = ROOT / "tools/music-catalog/music_catalog.py"
SPEC = importlib.util.spec_from_file_location("music_catalog", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
music_catalog = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(music_catalog)


class MusicCatalogTest(unittest.TestCase):
    def setUp(self) -> None:
        self.catalog = music_catalog.load_catalog(
            PROJECT_ROOT / "content/catalogs/music-tracks.json"
        )

    def test_catalog_uses_only_selected_ogg_tracks(self) -> None:
        self.assertEqual(30, len(self.catalog["tracks"]))
        self.assertFalse(self.catalog["datapack_required"])
        self.assertFalse(self.catalog["source"]["audio_tracked_by_git"])
        self.assertTrue(
            all(track["source_file"].lower().endswith(".ogg") for track in self.catalog["tracks"])
        )
        track_ids = {track["id"] for track in self.catalog["tracks"]}
        self.assertTrue(set(self.catalog["defaults"].values()).issubset(track_ids))
        self.assertFalse(any(track_id.startswith("local.") for track_id in track_ids))

    def test_sounds_manifest_uses_catalog_entries_only(self) -> None:
        manifest = music_catalog.build_sounds_manifest(self.catalog)
        self.assertEqual(len(self.catalog["tracks"]), len(manifest))
        self.assertIn("music.kanto.pallet_town", manifest)
        self.assertIn("music.battle.victory_trainer", manifest)
        self.assertIn("music.facility.pokemon_center", manifest)
        self.assertIn("music.facility.building", manifest)
        self.assertFalse(manifest["music.event.item_acquired"]["sounds"][0]["stream"])
        self.assertIn("music.event.key_item_acquired", manifest)
        self.assertIn("music.event.machine_acquired", manifest)
        self.assertNotIn("Title.ogg", json.dumps(manifest, ensure_ascii=False))

    def test_kanto_settlements_and_gym_interior_have_authored_music(self) -> None:
        expected = {
            "celadon_city": "kanto.celadon_city",
            "cerulean_city": "kanto.cerulean_city",
            "crimson_town": "kanto.pewter_city",
            "fuchsia_city": "kanto.cerulean_city",
            "lavender_town": "kanto.lavender_town",
            "route_01_town": "kanto.pewter_city",
            "saffron_city": "kanto.pewter_city",
            "starter_town": "kanto.pallet_town",
            "tidehaven_town": "kanto.cinnabar_island",
            "vermilion_city": "kanto.vermilion_city",
        }
        settlements = PROJECT_ROOT / "content/settlements/generation_1"
        for name, track in expected.items():
            document = json.loads((settlements / f"{name}.json").read_text(encoding="utf-8"))
            self.assertEqual(track, document["music_track"], name)

        settings = json.loads(
            (PROJECT_ROOT / "content/catalogs/building-settings.json").read_text(encoding="utf-8")
        )
        gym = settings["buildings"]["cobbleventure:interiors/gyms/base_gym_interior"]
        self.assertEqual("facility.gym", gym["music_track"])

    def test_added_kanto_facilities_and_rocket_dungeons_use_named_tags(self) -> None:
        tracks = {track["id"]: track["source_file"] for track in self.catalog["tracks"]}
        self.assertEqual("download/1-42. Pokémon Gym.ogg", tracks["facility.gym"])
        self.assertEqual(
            "download/36. Rocket Game Corner.ogg", tracks["facility.game_corner"]
        )
        self.assertEqual(
            "download/1-77. Team Rocket HQ.ogg", tracks["dungeon.team_rocket_hq"]
        )
        self.assertEqual(
            "download/34. Pokémon Tower.ogg", tracks["dungeon.pokemon_tower"]
        )
        self.assertEqual(
            "download/37. Rocket Hideout.ogg", tracks["dungeon.rocket_hideout"]
        )
        self.assertEqual(
            "download/39. Silph Co.ogg", tracks["dungeon.silph_company"]
        )

        settings = json.loads(
            (PROJECT_ROOT / "content/catalogs/building-settings.json").read_text(encoding="utf-8")
        )["buildings"]
        self.assertEqual(
            "facility.game_corner",
            settings["cobbleventure:interiors/casino"]["music_track"],
        )
        self.assertEqual(
            "facility.game_corner",
            settings["cobbleventure:placeholder/casino"]["music_track"],
        )

        expected_dungeons = {
            "rocket_casino_hideout": "dungeon.rocket_hideout",
            "rocket_pokemon_tower": "dungeon.pokemon_tower",
            "rocket_power_plant": "dungeon.team_rocket_hq",
            "rocket_silph_company": "dungeon.silph_company",
        }
        dungeons = PROJECT_ROOT / "content/dungeons/generation_1"
        for name, track in expected_dungeons.items():
            document = json.loads((dungeons / f"{name}.json").read_text(encoding="utf-8"))
            self.assertEqual(track, document["music_track"], name)

    def test_music_notification_uses_full_sound_event_ids(self) -> None:
        manifest = music_catalog.build_music_notification_manifest(self.catalog)
        entry = manifest["cobbleventure_music:music.kanto.pallet_town"]
        self.assertEqual("태초마을", entry["title"])
        self.assertTrue(entry["album"])
        self.assertTrue(entry["author"])
        encounter = manifest["cobbleventure_music:music.encounter.trainer_boy"]
        self.assertIn("Let's Go", encounter["album"])
        self.assertEqual(
            "Hiroaki Tsutsumi, Kon Shirasu, Shota Kageyama",
            encounter["author"],
        )

    def test_external_audio_check_does_not_copy_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            first = self.catalog["tracks"][0]
            source_file = source / first["source_file"]
            source_file.parent.mkdir(parents=True, exist_ok=True)
            source_file.touch()
            missing = music_catalog.check_external_audio(self.catalog, source)
            self.assertEqual(len(self.catalog["tracks"]) - 1, len(missing))
            self.assertNotIn(first["source_file"], missing)

    def test_resource_pack_contains_only_selected_audio(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            source.mkdir()
            for track in self.catalog["tracks"]:
                source_file = source / track["source_file"]
                source_file.parent.mkdir(parents=True, exist_ok=True)
                source_file.write_bytes(b"OggS-test")
            (source / "unused.ogg").write_bytes(b"must-not-be-copied")

            staging = root / "staging"
            music_catalog.stage_resource_pack(self.catalog, source, staging)

            copied = list(
                (staging / "assets/cobbleventure_music/sounds").rglob("*.ogg")
            )
            self.assertEqual(len(self.catalog["tracks"]), len(copied))
            self.assertFalse(any(path.name == "unused.ogg" for path in copied))
            self.assertTrue(
                (staging / "assets/musicnotification/musics.json").is_file()
            )

    def test_resource_pack_supports_track_source_directory_override(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            catalog = copy.deepcopy(self.catalog)
            catalog["tracks"] = catalog["tracks"][:2]
            overridden = catalog["tracks"][1]
            overridden["source_file"] = "override.ogg"
            overridden["source_directory"] = "local-assets/music/download"

            default_source = root / catalog["source"]["local_directory"]
            override_source = root / overridden["source_directory"]
            default_source.mkdir(parents=True)
            override_source.mkdir(parents=True)
            default_file = default_source / catalog["tracks"][0]["source_file"]
            default_file.parent.mkdir(parents=True, exist_ok=True)
            default_file.write_bytes(b"default")
            (override_source / overridden["source_file"]).write_bytes(b"override")

            staging = root / "staging"
            music_catalog.stage_resource_pack(
                catalog, default_source, staging, root
            )

            copied = staging / "assets/cobbleventure_music/sounds"
            self.assertEqual(
                b"override",
                (copied / f"{overridden['resource']}.ogg").read_bytes(),
            )

    def test_build_selection_contains_defaults_and_authored_assignments_only(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            catalog = copy.deepcopy(self.catalog)
            catalog["tracks"].append({
                "id": "special.victory",
                "sound_event": "music.special.victory",
                "resource": "music/special/victory",
                "source_file": "special/Victory.ogg",
                "category": "victory",
                "usage": "특수 승리",
            })
            world = project / "content/worlds/generation_1.json"
            world.parent.mkdir(parents=True)
            world.write_text(
                json.dumps({"music_overrides": [{"music_track": "special.victory"}]}),
                encoding="utf-8",
            )

            selected = music_catalog.select_used_tracks(catalog, project)
            selected_ids = {track["id"] for track in selected["tracks"]}

            self.assertEqual(
                set(self.catalog["defaults"].values()) | {"special.victory"}, selected_ids
            )
            self.assertIn("encounter.trainer_boy", selected_ids)
            self.assertIn("encounter.trainer_girl", selected_ids)
            self.assertIn("encounter.trainer_bad_guys", selected_ids)

    def test_build_selection_rejects_direct_audio_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            world = project / "content/worlds/generation_1.json"
            world.parent.mkdir(parents=True)
            world.write_text(
                json.dumps({"music_track": "another-red-bgm/Battle trainer.ogg"}),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                music_catalog.MusicCatalogError, "음원 태그"
            ):
                music_catalog.select_used_tracks(self.catalog, project)


if __name__ == "__main__":
    unittest.main()
