from __future__ import annotations

import importlib.util
import shutil
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "cave_road_anchor.py"
SPEC = importlib.util.spec_from_file_location("cave_road_anchor", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
cave_road_anchor = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = cave_road_anchor
SPEC.loader.exec_module(cave_road_anchor)


class CaveRoadAnchorTests(unittest.TestCase):
    def test_all_cave_entrances_have_one_authored_road_anchor(self) -> None:
        paths = sorted(cave_road_anchor.DEFAULT_CAVE_ROOT.glob("*.nbt"))

        self.assertEqual(5, len(paths))
        for path in paths:
            with self.subTest(path=path.name):
                anchors = cave_road_anchor.road_anchors(path)
                self.assertEqual(1, len(anchors))
                self.assertEqual("north_up", anchors[0][1])

    def test_adding_anchor_is_idempotent(self) -> None:
        source = cave_road_anchor.DEFAULT_CAVE_ROOT / "stone_mountain.nbt"
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / source.name
            shutil.copy2(source, target)

            cave_road_anchor.add_road_anchor(target)
            first = target.read_bytes()
            cave_road_anchor.add_road_anchor(target)

            self.assertEqual(first, target.read_bytes())
            self.assertEqual(
                cave_road_anchor.road_anchors(source),
                cave_road_anchor.road_anchors(target),
            )

    def test_moving_anchor_removes_the_previous_anchor(self) -> None:
        source = cave_road_anchor.DEFAULT_CAVE_ROOT / "stone_mountain.nbt"
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / source.name
            shutil.copy2(source, target)

            cave_road_anchor.add_road_anchor(
                target, (1, 0, 1), "west_up", "minecraft:stone"
            )

            self.assertEqual(
                [([1, 0, 1], "west_up")],
                cave_road_anchor.road_anchors(target),
            )


if __name__ == "__main__":
    unittest.main()
