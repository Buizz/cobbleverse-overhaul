#!/usr/bin/env python3
"""Build the trainer-card badge atlas from credited, checked-in pixel art."""

from __future__ import annotations

import json
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "tools/content-manager/assets/badges"
CATALOG = ROOT / "content-projects/cobbleventure-main/content/catalogs/badges.json"
ATLAS = ROOT / "projects/cobbleventure-player-menu/src/main/resources/assets/cobbleventure_player_menu/textures/gui/badges.png"
SOURCES = ROOT / "tools/content-manager/badge-image-sources.json"
TILE = 32
COLS = 8

JCFERGGY_PAGE = "https://www.deviantart.com/jcferggy/art/16x16-Pokemon-Badge-Sprites-Gen-1-6-544204402"
PALDEA_PAGE = "https://www.deviantart.com/professormordbg/art/Paldea-Badges-demake-large-1142694862"

# The source sheet uses 16px cells separated by one transparent pixel.
# Rows 0-3: Kanto-Sinnoh. Row 5 and row 6 columns 0-1: Unova.
# Row 6 columns 2-7 and row 4 columns 6-7: Kalos.
JCFERGGY_CELLS = (
    [(column, row) for row in range(4) for column in range(8)]
    + [(column, 5) for column in range(8)]
    + [(0, 6), (1, 6)]
    + [(column, 6) for column in range(2, 8)]
    + [(6, 4), (7, 4)]
)

# The large sheet contains all 18 Paldea story badges. These are only the
# middle two rows: Bug, Grass, Electric, Water, Normal, Ghost, Psychic, Ice.
PALDEA_BOXES = (
    (100, 190, 260, 350),
    (300, 190, 460, 350),
    (490, 190, 650, 350),
    (690, 190, 850, 350),
    (100, 380, 260, 540),
    (300, 380, 460, 540),
    (490, 380, 650, 540),
    (690, 380, 850, 540),
)


def checked_image(path: Path, expected_size: tuple[int, int]) -> Image.Image:
    if not path.is_file():
        raise FileNotFoundError(f"뱃지 원본이 없습니다: {path.relative_to(ROOT)}")
    image = Image.open(path).convert("RGBA")
    if image.size != expected_size:
        raise ValueError(
            f"{path.name} 크기가 변경되었습니다: {image.size}, expected {expected_size}"
        )
    return image


def jcferggy_icons() -> list[Image.Image]:
    source = checked_image(ASSETS / "jcferggy-gen1-6.png", (692, 484))
    icons = []
    for column, row in JCFERGGY_CELLS:
        left, top = 1 + column * 17, 1 + row * 17
        icon = source.crop((left, top, left + 16, top + 16))
        icons.append(icon.resize((TILE, TILE), Image.Resampling.NEAREST))
    return icons


def galar_icons() -> list[Image.Image]:
    source = checked_image(ASSETS / "galar-custom.png", (TILE * 5, TILE * 2))
    return [
        source.crop(((index % 5) * TILE, (index // 5) * TILE,
                     (index % 5 + 1) * TILE, (index // 5 + 1) * TILE))
        for index in range(10)
    ]


def paldea_icons() -> list[Image.Image]:
    source = checked_image(ASSETS / "professormordbg-paldea.png", (950, 730))
    return [
        source.crop(box).resize((TILE, TILE), Image.Resampling.NEAREST)
        for box in PALDEA_BOXES
    ]


def source_record(badge_id: str, index: int) -> dict[str, object]:
    if index < 50:
        column, row = JCFERGGY_CELLS[index]
        return {
            "badge_id": badge_id,
            "author": "JcFerggy (Unova base credited to SoaringSkies0)",
            "source_file": "assets/badges/jcferggy-gen1-6.png",
            "source_page": JCFERGGY_PAGE,
            "source_cell": {"column": column, "row": row, "size": 16},
            "transformation": "16x16 source cell, nearest-neighbour 2x enlargement to 32x32",
        }
    if index < 60:
        local_index = index - 50
        return {
            "badge_id": badge_id,
            "author": "Cobbleverse Overhaul project",
            "source_file": "assets/badges/galar-custom.png",
            "source_page": None,
            "source_cell": {"column": local_index % 5, "row": local_index // 5, "size": 32},
            "transformation": "editable project-authored 32x32 source cell",
        }
    local_index = index - 60
    return {
        "badge_id": badge_id,
        "author": "ProfessorMorDBG",
        "source_file": "assets/badges/professormordbg-paldea.png",
        "source_page": PALDEA_PAGE,
        "source_box": list(PALDEA_BOXES[local_index]),
        "transformation": "160x160 pixel-art crop, nearest-neighbour 5x reduction to 32x32",
    }


def main() -> None:
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    badges = catalog["badges"]
    icons = jcferggy_icons() + galar_icons() + paldea_icons()
    if len(badges) != len(icons):
        raise ValueError(f"카탈로그 {len(badges)}개와 이미지 {len(icons)}개가 일치하지 않습니다")

    rows = (len(icons) + COLS - 1) // COLS
    atlas = Image.new("RGBA", (COLS * TILE, rows * TILE), (0, 0, 0, 0))
    for index, icon in enumerate(icons):
        if icon.getchannel("A").getbbox() is None:
            raise ValueError(f"빈 뱃지 셀입니다: {badges[index]['id']}")
        atlas.alpha_composite(icon, ((index % COLS) * TILE, (index // COLS) * TILE))

    expected_size = (catalog["atlas"]["width"], catalog["atlas"]["height"])
    if atlas.size != expected_size:
        raise ValueError(f"아틀라스 크기가 카탈로그와 다릅니다: {atlas.size}, expected {expected_size}")

    ATLAS.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(ATLAS, optimize=True)
    records = [source_record(badge["id"], index) for index, badge in enumerate(badges)]
    SOURCES.write_text(json.dumps({
        "notice": "Unofficial fan project. Pokemon-related names and designs remain the property of their respective rights holders.",
        "permission_record": "../../docs/asset-permissions/README.md",
        "badges": records,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(ATLAS.relative_to(ROOT))
    print(SOURCES.relative_to(ROOT))


if __name__ == "__main__":
    main()
