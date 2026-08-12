from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "tools/music-catalog/music_catalog.py"
SPEC = importlib.util.spec_from_file_location("music_catalog", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
music_catalog = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(music_catalog)


class MusicCatalogTest(unittest.TestCase):
    def setUp(self) -> None:
        self.catalog = music_catalog.load_catalog(
            ROOT / "content/catalogs/music-tracks.json"
        )

    def test_catalog_uses_only_selected_ogg_tracks(self) -> None:
        self.assertEqual(28, len(self.catalog["tracks"]))
        self.assertFalse(self.catalog["datapack_required"])
        self.assertFalse(self.catalog["source"]["audio_tracked_by_git"])
        self.assertTrue(
            all(track["source_file"].lower().endswith(".ogg") for track in self.catalog["tracks"])
        )

    def test_sounds_manifest_uses_catalog_entries_only(self) -> None:
        manifest = music_catalog.build_sounds_manifest(self.catalog)
        self.assertEqual(len(self.catalog["tracks"]), len(manifest))
        self.assertIn("music.kanto.pallet_town", manifest)
        self.assertNotIn("Title.ogg", json.dumps(manifest, ensure_ascii=False))

    def test_music_notification_uses_full_sound_event_ids(self) -> None:
        manifest = music_catalog.build_music_notification_manifest(self.catalog)
        entry = manifest["cobbleventure_music:music.kanto.pallet_town"]
        self.assertEqual("태초마을", entry["title"])
        self.assertTrue(entry["album"])
        self.assertTrue(entry["author"])

    def test_external_audio_check_does_not_copy_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            first = self.catalog["tracks"][0]
            (source / first["source_file"]).touch()
            missing = music_catalog.check_external_audio(self.catalog, source)
            self.assertEqual(len(self.catalog["tracks"]) - 1, len(missing))
            self.assertNotIn(first["source_file"], missing)

    def test_resource_pack_contains_only_selected_audio(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            source.mkdir()
            for track in self.catalog["tracks"]:
                (source / track["source_file"]).write_bytes(b"OggS-test")
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


if __name__ == "__main__":
    unittest.main()
