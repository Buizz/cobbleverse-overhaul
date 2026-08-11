import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "build_custom_spawns.py"
SPEC = importlib.util.spec_from_file_location("build_custom_spawns", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def row(**overrides):
    value = {
        "관리키": "0001_bulbasaur.json::bulbasaur-1::001",
        "적용여부_편집": "사용",
        "허용세대월드_편집": "cobbleventure:generation_1",
        "지역배정_편집": None,
        "바이옴_편집": None,
        "희귀도": "ultra-rare",
        "원본가중치": 6,
        "가중치배율_편집": 1,
        "원본레벨": "5-32",
        "레벨_편집": None,
        "위치유형": "grounded",
        "원본상태": "Cobblemon 동일",
        "원본파일": "data/cobblemon/spawn_pool_world/0001_bulbasaur.json",
        "스폰ID": "bulbasaur-1",
        "포켓몬선택자": "bulbasaur",
        "스폰타입": "pokemon",
        "프리셋": "natural",
        "조건JSON": json.dumps({"biomes": ["#cobblemon:is_jungle"], "minSkyLight": 8}),
        "제외조건JSON": None,
        "가중치보정JSON": json.dumps({
            "weightMultiplier": {"multiplier": 2, "condition": {"isRaining": True}}
        }),
        "기타조건": json.dumps({"spawn": {"canSeeSky": True}}),
    }
    value.update(overrides)
    return value


class CustomSpawnBuildTest(unittest.TestCase):
    def test_reads_repository_spawn_edit_sheet(self):
        workbook = SCRIPT.parents[2] / "코블몬_바이옴_스폰_정리.xlsx"
        rows = MODULE.read_sheet_rows(workbook)

        self.assertEqual(3121, len(rows))
        self.assertEqual("사용", rows[1]["적용여부_편집"])
        self.assertEqual("bulbasaur-1", rows[1]["스폰ID"])

    def test_preserves_original_conditions_and_adds_dimension(self):
        documents, report = MODULE.build_documents([row()])
        spawn = documents["0001_bulbasaur.json"]["spawns"][0]

        self.assertEqual(["#cobblemon:is_jungle"], spawn["condition"]["biomes"])
        self.assertEqual(["cobbleventure:generation_1"], spawn["condition"]["dimensions"])
        self.assertEqual(8, spawn["condition"]["minSkyLight"])
        self.assertTrue(spawn["canSeeSky"])
        self.assertEqual(2, spawn["weightMultiplier"]["multiplier"])
        self.assertEqual(1, report["summary"]["active_spawns"])

    def test_applies_biome_weight_and_level_edits(self):
        documents, report = MODULE.build_documents([row(
            바이옴_편집="minecraft:plains; #cobblemon:is_forest",
            가중치배율_편집=0.5,
            레벨_편집="10-20",
        )])
        spawn = documents["0001_bulbasaur.json"]["spawns"][0]

        self.assertEqual(["minecraft:plains", "#cobblemon:is_forest"], spawn["condition"]["biomes"])
        self.assertEqual(3, spawn["weight"])
        self.assertEqual("10-20", spawn["level"])
        self.assertEqual(1, report["summary"]["biome_overrides"])
        self.assertEqual(1, report["summary"]["weight_overrides"])
        self.assertEqual(1, report["summary"]["level_overrides"])

    def test_excludes_rows_and_rejects_unresolved_region_assignment(self):
        documents, report = MODULE.build_documents([row(적용여부_편집="제외")])
        document = documents["0001_bulbasaur.json"]
        self.assertFalse(document["enabled"])
        self.assertEqual([], document["spawns"])
        self.assertEqual(1, report["summary"]["excluded_spawns"])

        with self.assertRaisesRegex(MODULE.SpawnBuildError, "지역배정"):
            MODULE.build_documents([row(지역배정_편집="region_01")])

    def test_renames_duplicate_spawn_ids_without_dropping_rules(self):
        documents, report = MODULE.build_documents([
            row(),
            row(관리키="duplicate", 포켓몬선택자="bulbasaur shiny=true"),
        ])
        spawns = documents["0001_bulbasaur.json"]["spawns"]

        self.assertEqual(["bulbasaur-1", "bulbasaur-1-cobbleventure-2"], [
            spawn["id"] for spawn in spawns
        ])
        self.assertEqual(1, report["summary"]["renamed_duplicate_ids"])

    def test_writes_deterministic_datapack_layout(self):
        documents, report = MODULE.build_documents([row()])
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "spawns.zip"
            MODULE.write_datapack(output, documents, report)
            with zipfile.ZipFile(output) as archive:
                names = archive.namelist()
                self.assertIn("pack.mcmeta", names)
                self.assertIn(
                    "data/cobblemon/spawn_pool_world/0001_bulbasaur.json", names
                )
                document = json.loads(archive.read(names[-1]))
                self.assertEqual("bulbasaur-1", document["spawns"][0]["id"])


if __name__ == "__main__":
    unittest.main()
