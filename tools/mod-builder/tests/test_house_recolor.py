import gzip
import sys
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPOSITORY_ROOT / "tools/mod-builder"))

from starter_gym import recolor_house_roof_nbt  # noqa: E402


def nbt_string_tag(name: str, value: str) -> bytes:
    encoded_name = name.encode("utf-8")
    encoded_value = value.encode("utf-8")
    return (
        b"\x08"
        + len(encoded_name).to_bytes(2, "big") + encoded_name
        + len(encoded_value).to_bytes(2, "big") + encoded_value
    )


class HouseRecolorTests(unittest.TestCase):
    def test_recolors_copycat_material_and_consumed_item_together(self) -> None:
        source = (
            REPOSITORY_ROOT
            / "content-projects/cobbleventure-main/content/structures/houses"
            / "one_story_gambrel.nbt"
        ).read_bytes()

        recolored = gzip.decompress(recolor_house_roof_nbt(source, "orange"))

        self.assertIn(
            nbt_string_tag("Name", "minecraft:orange_concrete"), recolored
        )
        self.assertIn(
            nbt_string_tag("id", "minecraft:orange_concrete"), recolored
        )
        self.assertNotIn(
            nbt_string_tag("Name", "minecraft:white_concrete"), recolored
        )
        self.assertNotIn(
            nbt_string_tag("id", "minecraft:white_concrete"), recolored
        )


if __name__ == "__main__":
    unittest.main()
