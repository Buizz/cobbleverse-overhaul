from __future__ import annotations

import argparse
from pathlib import Path

from starter_gym import build_power_plant_dungeon_nbt


DEFAULT_OUTPUT = Path(
    "content-projects/cobbleventure-main/content/structures/placeholder/power_plant.nbt"
)


def main() -> None:
    parser = argparse.ArgumentParser(description="로켓단 발전소 던전 NBT를 생성합니다.")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(build_power_plant_dungeon_nbt())
    print(args.output.resolve())


if __name__ == "__main__":
    main()
