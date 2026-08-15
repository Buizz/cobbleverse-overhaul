from __future__ import annotations

import argparse
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "mod-builder"))

from starter_gym import build_forest_gate_nbt  # noqa: E402


DEFAULT_OUTPUT = (
    ROOT
    / "content-projects"
    / "cobbleventure-main"
    / "content"
    / "structures"
    / "forest_entrance"
    / "forest_gate.nbt"
)


def main() -> None:
    parser = argparse.ArgumentParser(description="ForestGate 숲관문 NBT를 생성합니다.")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(build_forest_gate_nbt())
    print(args.output)


if __name__ == "__main__":
    main()
