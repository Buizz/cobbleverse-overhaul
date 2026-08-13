#!/usr/bin/env python3
"""Fetch Pokémon Wiki badge artwork and build the 32px trainer-card atlas.

The downloaded originals are kept only in a temporary directory. The generated
atlas and a source manifest are the project artifacts.
"""

from __future__ import annotations

import json
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "content-projects/cobbleventure-main/content/catalogs/badges.json"
ATLAS = ROOT / "projects/cobbleventure-player-menu/src/main/resources/assets/cobbleventure_player_menu/textures/gui/badges.png"
SOURCES = ROOT / "tools/content-manager/badge-image-sources.json"
API = "https://pokemon.fandom.com/api.php"
TILE = 32
CONTENT_SIZE = 28


def fandom_filename(badge: dict) -> str:
    english = badge["display_name"]["en_us"]
    if badge["generation"] == 9:
        badge_type = badge["type"].replace("_", " ").title()
        return f"{badge_type} Badge SV.png"
    if badge["generation"] == 6 and english == "Fairy Badge":
        return "Fairy Badge XY.png"
    if badge["generation"] == 6 and english == "Bug Badge":
        return "Bug Badge Viola.png"
    return f"{english}.png"


def image_info(titles: list[str]) -> dict[str, dict]:
    result: dict[str, dict] = {}
    for offset in range(0, len(titles), 40):
        params = urllib.parse.urlencode({
            "action": "query",
            "format": "json",
            "prop": "imageinfo",
            "iiprop": "url",
            "titles": "|".join(f"File:{title}" for title in titles[offset:offset + 40]),
        })
        request = urllib.request.Request(f"{API}?{params}", headers={"User-Agent": "CobbleventureBadgeBuilder/1.0"})
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.load(response)
        for page in payload["query"]["pages"].values():
            title = page["title"].removeprefix("File:")
            if "missing" not in page and page.get("imageinfo"):
                info = page["imageinfo"][0]
                result[title.casefold()] = {
                    "file": title,
                    "url": info["url"],
                    "description_url": info["descriptionurl"],
                }
    return result


def pixelate(source: Path) -> Image.Image:
    with Image.open(source) as opened:
        image = opened.convert("RGBA")
    alpha = image.getchannel("A")
    bounds = alpha.getbbox()
    if bounds is None:
        return Image.new("RGBA", (TILE, TILE), (0, 0, 0, 0))
    image = image.crop(bounds)
    scale = min(CONTENT_SIZE / image.width, CONTENT_SIZE / image.height)
    size = (max(1, round(image.width * scale)), max(1, round(image.height * scale)))
    # These icons are intentionally pixel art. Nearest-neighbour keeps hard
    # colour boundaries and avoids the translucent fringe introduced by
    # photographic downsampling filters such as LANCZOS.
    image = image.resize(size, Image.Resampling.NEAREST)
    image = image.quantize(colors=24, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE).convert("RGBA")
    canvas = Image.new("RGBA", (TILE, TILE), (0, 0, 0, 0))
    canvas.alpha_composite(image, ((TILE - image.width) // 2, (TILE - image.height) // 2))
    return canvas


def main() -> None:
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    badges = catalog["badges"]
    filenames = [fandom_filename(badge) for badge in badges]
    infos = image_info(filenames)
    missing = [name for name in filenames if name.casefold() not in infos]
    if missing:
        raise RuntimeError("Pokémon Wiki 파일을 찾을 수 없습니다: " + ", ".join(missing))

    atlas = Image.new("RGBA", (catalog["atlas"]["width"], catalog["atlas"]["height"]), (0, 0, 0, 0))
    records = []
    with tempfile.TemporaryDirectory(prefix="cobbleventure-badges-") as temporary:
        temporary_root = Path(temporary)
        for index, (badge, filename) in enumerate(zip(badges, filenames, strict=True)):
            info = infos[filename.casefold()]
            source = temporary_root / f"{index:02d}.png"
            request = urllib.request.Request(info["url"], headers={"User-Agent": "CobbleventureBadgeBuilder/1.0"})
            with urllib.request.urlopen(request, timeout=30) as response:
                source.write_bytes(response.read())
            icon = pixelate(source)
            atlas.alpha_composite(icon, ((index % 8) * TILE, (index // 8) * TILE))
            records.append({
                "badge_id": badge["id"],
                "source_file": info["file"],
                "source_page": info["description_url"],
                "transformation": "transparent crop, nearest-neighbour fit within 28px, 24-color quantization, 32x32 transparent tile",
            })

    ATLAS.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(ATLAS, optimize=True)
    SOURCES.write_text(json.dumps({
        "source": "Pokémon Wiki on Fandom",
        "article": "https://pokemon.fandom.com/ko/wiki/배지",
        "notice": "Non-text media may have separate copyright terms. Each source page is recorded below.",
        "badges": records,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(ATLAS.relative_to(ROOT))
    print(SOURCES.relative_to(ROOT))


if __name__ == "__main__":
    main()
