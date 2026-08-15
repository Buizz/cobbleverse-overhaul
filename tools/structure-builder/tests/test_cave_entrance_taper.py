from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "tools" / "structure-builder"))

import cave_entrance_taper  # noqa: E402


class CaveEntranceTaperTests(unittest.TestCase):
    def test_opening_and_shell_grow_toward_the_back(self) -> None:
        front = cave_entrance_taper._opening(2, 16, 6)
        back = cave_entrance_taper._opening(5, 16, 6)
        self.assertEqual((14, 18, 7, 11), self._bounds(front))
        self.assertEqual((11, 21, 7, 17), self._bounds(back))

        front_outer = front | cave_entrance_taper._shell(4, front, 2, 16, 6, 4, 29)
        back_outer = back | cave_entrance_taper._shell(29, back, 5, 16, 6, 4, 29)
        self.assertEqual((12, 20, 0, 13), self._bounds(front_outer))
        self.assertEqual((5, 27, 0, 22), self._bounds(back_outer))

    def test_all_authored_caves_follow_the_taper_contract(self) -> None:
        paths = sorted(cave_entrance_taper.DEFAULT_CAVE_ROOT.glob("*.nbt"))
        self.assertEqual(5, len(paths))
        for path in paths:
            with self.subTest(path=path.name):
                self.assertEqual([], cave_entrance_taper.validate_taper(path))

    def test_anchor_is_five_blocks_above_the_authored_floor(self) -> None:
        for path in sorted(cave_entrance_taper.DEFAULT_CAVE_ROOT.glob("*.nbt")):
            with self.subTest(path=path.name):
                root = cave_entrance_taper._read_minecraft_structure_root(path.read_bytes())
                anchor = cave_entrance_taper._authored_anchor(root)
                floor_y = cave_entrance_taper._tunnel_floor(root, anchor[2])
                self.assertEqual(floor_y + 5, anchor[1])

    @staticmethod
    def _bounds(points: set[tuple[int, int]]) -> tuple[int, int, int, int]:
        return (
            min(point[0] for point in points),
            max(point[0] for point in points),
            min(point[1] for point in points),
            max(point[1] for point in points),
        )


if __name__ == "__main__":
    unittest.main()
