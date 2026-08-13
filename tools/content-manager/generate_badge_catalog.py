#!/usr/bin/env python3
"""Generate the item-independent main-series Gym Badge catalog and icon atlas."""

from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / "content-projects/cobbleventure-main"
CATALOG = PROJECT / "content/catalogs/badges.json"
ATLAS = ROOT / "projects/cobbleventure-player-menu/src/main/resources/assets/cobbleventure_player_menu/textures/gui/badges.png"
TILE = 32
COLS = 8

REGIONS = {
    "kanto": (1, "관동", "Kanto"), "johto": (2, "성도", "Johto"),
    "hoenn": (3, "호연", "Hoenn"), "sinnoh": (4, "신오", "Sinnoh"),
    "unova": (5, "하나", "Unova"), "kalos": (6, "칼로스", "Kalos"),
    "galar": (8, "가라르", "Galar"), "paldea": (9, "팔데아", "Paldea"),
}

# slug, Korean in-game name, English name, leader, type
BADGES = {
    "kanto": [
        ("boulder", "회색배지", "Boulder Badge", "웅", "rock"),
        ("cascade", "블루배지", "Cascade Badge", "이슬", "water"),
        ("thunder", "오렌지배지", "Thunder Badge", "마티스", "electric"),
        ("rainbow", "무지개배지", "Rainbow Badge", "민화", "grass"),
        ("soul", "핑크배지", "Soul Badge", "독수", "poison"),
        ("marsh", "골드배지", "Marsh Badge", "초련", "psychic"),
        ("volcano", "크림슨배지", "Volcano Badge", "강연", "fire"),
        ("earth", "그린배지", "Earth Badge", "비주기", "ground"),
    ],
    "johto": [
        ("zephyr", "윙배지", "Zephyr Badge", "비상", "flying"),
        ("hive", "인섹트배지", "Hive Badge", "호일", "bug"),
        ("plain", "레귤러배지", "Plain Badge", "꼭두", "normal"),
        ("fog", "팬텀배지", "Fog Badge", "유빈", "ghost"),
        ("storm", "쇼크배지", "Storm Badge", "사도", "fighting"),
        ("mineral", "스틸배지", "Mineral Badge", "규리", "steel"),
        ("glacier", "아이스배지", "Glacier Badge", "류옹", "ice"),
        ("rising", "라이징배지", "Rising Badge", "이향", "dragon"),
    ],
    "hoenn": [
        ("stone", "스톤배지", "Stone Badge", "원규", "rock"),
        ("knuckle", "너클배지", "Knuckle Badge", "철구", "fighting"),
        ("dynamo", "다이나모배지", "Dynamo Badge", "암페어", "electric"),
        ("heat", "히트배지", "Heat Badge", "민지", "fire"),
        ("balance", "밸런스배지", "Balance Badge", "종길", "normal"),
        ("feather", "페더배지", "Feather Badge", "은송", "flying"),
        ("mind", "마인드배지", "Mind Badge", "풍&란", "psychic"),
        ("rain", "레인배지", "Rain Badge", "윤진", "water"),
    ],
    "sinnoh": [
        ("coal", "콜배지", "Coal Badge", "강석", "rock"),
        ("forest", "포레스트배지", "Forest Badge", "유채", "grass"),
        ("cobble", "코블배지", "Cobble Badge", "자두", "fighting"),
        ("fen", "펜배지", "Fen Badge", "맥실러", "water"),
        ("relic", "레릭배지", "Relic Badge", "멜리사", "ghost"),
        ("mine", "마인배지", "Mine Badge", "동관", "steel"),
        ("icicle", "아이시클배지", "Icicle Badge", "무청", "ice"),
        ("beacon", "비컨배지", "Beacon Badge", "전진", "electric"),
    ],
    "unova": [
        ("trio", "트라이배지", "Trio Badge", "덴트·팟·콘", "grass_fire_water"),
        ("basic", "베이직배지", "Basic Badge", "알로에·체렌", "normal"),
        ("insect", "비틀배지", "Insect Badge", "아티", "bug"),
        ("bolt", "볼트배지", "Bolt Badge", "카밀레", "electric"),
        ("quake", "퀘이크배지", "Quake Badge", "야콘", "ground"),
        ("jet", "제트배지", "Jet Badge", "풍란", "flying"),
        ("freeze", "아이스배지", "Freeze Badge", "담죽", "ice"),
        ("legend", "레전드배지", "Legend Badge", "사간·아이리스", "dragon"),
        ("toxic", "톡식배지", "Toxic Badge", "보미카", "poison"),
        ("wave", "웨이브배지", "Wave Badge", "시즈", "water"),
    ],
    "kalos": [
        ("bug", "버그배지", "Bug Badge", "비올라", "bug"),
        ("cliff", "월배지", "Cliff Badge", "자크로", "rock"),
        ("rumble", "파이트배지", "Rumble Badge", "코르니", "fighting"),
        ("plant", "플랜트배지", "Plant Badge", "후쿠지", "grass"),
        ("voltage", "볼티지배지", "Voltage Badge", "시트론", "electric"),
        ("fairy", "페어리배지", "Fairy Badge", "마슈", "fairy"),
        ("psychic", "사이킥배지", "Psychic Badge", "고지카", "psychic"),
        ("iceberg", "아이스버그배지", "Iceberg Badge", "우르프", "ice"),
    ],
    "galar": [
        ("grass", "풀배지", "Grass Badge", "아킬", "grass"),
        ("water", "물배지", "Water Badge", "야청", "water"),
        ("fire", "불꽃배지", "Fire Badge", "순무", "fire"),
        ("fighting", "격투배지", "Fighting Badge", "채두", "fighting"),
        ("ghost", "고스트배지", "Ghost Badge", "어니언", "ghost"),
        ("fairy", "페어리배지", "Fairy Badge", "포플러", "fairy"),
        ("rock", "바위배지", "Rock Badge", "마쿠와", "rock"),
        ("ice", "얼음배지", "Ice Badge", "멜론", "ice"),
        ("dark", "악배지", "Dark Badge", "두송", "dark"),
        ("dragon", "드래곤배지", "Dragon Badge", "금랑", "dragon"),
    ],
    "paldea": [
        ("bug", "벌레배지", "Bug Badge", "단풍", "bug"),
        ("grass", "풀배지", "Grass Badge", "콜사", "grass"),
        ("electric", "전기배지", "Electric Badge", "모야모", "electric"),
        ("water", "물배지", "Water Badge", "곤포", "water"),
        ("normal", "노말배지", "Normal Badge", "청목", "normal"),
        ("ghost", "고스트배지", "Ghost Badge", "라임", "ghost"),
        ("psychic", "에스퍼배지", "Psychic Badge", "리파", "psychic"),
        ("ice", "얼음배지", "Ice Badge", "그루샤", "ice"),
    ],
}

COLORS = {
    "normal": (196, 190, 176), "fire": (238, 91, 57), "water": (69, 145, 222),
    "electric": (247, 199, 43), "grass": (86, 176, 90), "ice": (91, 202, 220),
    "fighting": (190, 68, 63), "poison": (157, 91, 181), "ground": (195, 147, 76),
    "flying": (126, 158, 218), "psychic": (222, 86, 142), "bug": (151, 179, 52),
    "rock": (160, 135, 72), "ghost": (99, 83, 145), "dragon": (93, 91, 198),
    "dark": (76, 70, 78), "steel": (143, 157, 170), "fairy": (230, 145, 190),
    "grass_fire_water": (109, 172, 132),
}
TYPE_KO = {
    "normal": "노말", "fire": "불꽃", "water": "물", "electric": "전기", "grass": "풀",
    "ice": "얼음", "fighting": "격투", "poison": "독", "ground": "땅", "flying": "비행",
    "psychic": "에스퍼", "bug": "벌레", "rock": "바위", "ghost": "고스트", "dragon": "드래곤",
    "dark": "악", "steel": "강철", "fairy": "페어리", "grass_fire_water": "풀·불꽃·물",
}


def draw_icon(draw: ImageDraw.ImageDraw, x: int, y: int, badge_type: str, seed: int) -> None:
    color = COLORS[badge_type]
    dark = tuple(max(0, value - 75) for value in color)
    light = tuple(min(255, value + 65) for value in color)
    cx, cy = x + 16, y + 16
    points = []
    rays = 4 + seed % 5
    for index in range(rays * 2):
        import math
        angle = -math.pi / 2 + index * math.pi / rays
        radius = 13 if index % 2 == 0 else 8 + seed % 3
        points.append((round(cx + math.cos(angle) * radius), round(cy + math.sin(angle) * radius)))
    draw.polygon(points, fill=dark + (255,))
    inset = [(round(cx + (px - cx) * .76), round(cy + (py - cy) * .76)) for px, py in points]
    draw.polygon(inset, fill=color + (255,))
    draw.ellipse((cx - 5, cy - 5, cx + 5, cy + 5), fill=light + (255,), outline=(255, 244, 205, 255), width=1)
    motif = seed % 4
    if motif == 0:
        draw.line((cx - 4, cy, cx + 4, cy), fill=dark + (255,), width=2)
        draw.line((cx, cy - 4, cx, cy + 4), fill=dark + (255,), width=2)
    elif motif == 1:
        draw.polygon(((cx, cy - 4), (cx + 4, cy + 3), (cx - 4, cy + 3)), fill=dark + (255,))
    elif motif == 2:
        draw.ellipse((cx - 3, cy - 3, cx + 3, cy + 3), outline=dark + (255,), width=2)
    else:
        draw.line((cx - 3, cy - 3, cx + 3, cy + 3), fill=dark + (255,), width=2)
        draw.line((cx + 3, cy - 3, cx - 3, cy + 3), fill=dark + (255,), width=2)


def generate() -> None:
    entries = []
    flat = [(region, badge) for region, badges in BADGES.items() for badge in badges]
    rows = (len(flat) + COLS - 1) // COLS
    atlas = Image.new("RGBA", (COLS * TILE, rows * TILE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(atlas)
    for index, (region, badge) in enumerate(flat):
        slug, ko_name, en_name, leader, badge_type = badge
        generation, ko_region, en_region = REGIONS[region]
        order = BADGES[region].index(badge) + 1
        u, v = index % COLS * TILE, index // COLS * TILE
        draw_icon(draw, u, v, badge_type, index)
        entries.append({
            "id": f"cobbleventure:badge/{region}/{slug}",
            "generation": generation, "region": region, "order": order,
            "display_name": {"ko_kr": ko_name, "en_us": en_name},
            "leader_name": {"ko_kr": leader}, "type": badge_type,
            "tooltip": {
                "ko_kr": f"{generation}세대 {order}번째 {TYPE_KO[badge_type]} 타입 관장 · {ko_region} · {leader}",
                "en_us": f"Generation {generation} Gym {order} · {en_region} · {leader} · {badge_type}",
            },
            "icon": {"texture": "cobbleventure_player_menu:textures/gui/badges.png", "u": u, "v": v, "size": TILE},
        })
    CATALOG.parent.mkdir(parents=True, exist_ok=True)
    CATALOG.write_text(json.dumps({
        "$schema": "../schemas/badge-catalog.schema.json", "schema_version": 1,
        "atlas": {"width": atlas.width, "height": atlas.height, "tile_size": TILE},
        "regions_without_gym_badges": [{"generation": 7, "region": "alola", "reason": {"ko_kr": "알로라는 체육관 배지 대신 섬 순례와 큰 시련을 사용합니다.", "en_us": "Alola uses the island challenge instead of Gym Badges."}}],
        "badges": entries,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    ATLAS.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(ATLAS)


if __name__ == "__main__":
    generate()
    print(CATALOG.relative_to(ROOT).as_posix())
    print(ATLAS.relative_to(ROOT).as_posix())
