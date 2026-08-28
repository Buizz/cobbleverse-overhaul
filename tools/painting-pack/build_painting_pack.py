from __future__ import annotations

import json
import shutil
import urllib.request
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[2]
WORK_DIR = Path(__file__).resolve().parent
SPRITES_DIR = WORK_DIR / "source-sprites"
GENERATED_DIR = WORK_DIR / "generated"
PACK_DIR = GENERATED_DIR / "Cobbleventure-Pokemon-Paintings"
TEXTURE_DIR = PACK_DIR / "assets" / "minecraft" / "textures" / "painting"
OUTPUT_ZIP = ROOT / "local-assets" / "Cobbleventure-Pokemon-Paintings.zip"
SPRITE_URL = (
    "https://raw.githubusercontent.com/PokeAPI/sprites/master/"
    "sprites/pokemon/versions/generation-ii/crystal/{number}.png"
)

SQUARE_PAINTINGS = {
    "kebab": (25, (245, 200, 46), (35, 62, 115)),
    "aztec": (1, (79, 155, 88), (24, 68, 55)),
    "alban": (4, (226, 103, 48), (108, 36, 42)),
    "aztec2": (7, (74, 161, 204), (25, 65, 112)),
    "bomb": (133, (186, 139, 85), (72, 46, 51)),
    "plant": (10, (135, 190, 69), (38, 89, 53)),
    "wasteland": (92, (115, 78, 160), (35, 25, 66)),
    "meditative": (39, (222, 126, 165), (79, 48, 97)),
}

POSTER_SPRITES = (3, 6, 9, 144, 145, 146, 150, 152, 155, 158, 249, 250)


def download_missing_sprites() -> None:
    SPRITES_DIR.mkdir(parents=True, exist_ok=True)
    numbers = sorted({entry[0] for entry in SQUARE_PAINTINGS.values()} | set(POSTER_SPRITES))
    for number in numbers:
        target = SPRITES_DIR / f"{number}.png"
        if not target.exists():
            print(f"Downloading Pokemon Crystal sprite #{number}")
            urllib.request.urlretrieve(SPRITE_URL.format(number=number), target)


def sprite(pokedex_number: int, max_size: tuple[int, int]) -> Image.Image:
    source = Image.open(SPRITES_DIR / f"{pokedex_number}.png").convert("RGBA")
    for corner in ((0, 0), (source.width - 1, 0), (0, source.height - 1), (source.width - 1, source.height - 1)):
        ImageDraw.floodfill(source, corner, (255, 255, 255, 0), thresh=24)
    alpha_box = source.getchannel("A").getbbox()
    if alpha_box is None:
        raise ValueError(f"Sprite #{pokedex_number} has no visible pixels")
    source = source.crop(alpha_box)
    scale = min(max_size[0] / source.width, max_size[1] / source.height)
    return source.resize(
        (max(1, round(source.width * scale)), max(1, round(source.height * scale))),
        Image.Resampling.NEAREST,
    )


def paste_centered(
    canvas: Image.Image,
    artwork: Image.Image,
    center: tuple[int, int],
    shadow: bool = True,
) -> None:
    x = center[0] - artwork.width // 2
    y = center[1] - artwork.height // 2
    if shadow:
        mask = artwork.getchannel("A")
        silhouette = Image.new("RGBA", artwork.size, (18, 16, 28, 255))
        silhouette.putalpha(mask.point(lambda alpha: alpha * 3 // 5))
        canvas.alpha_composite(silhouette, (x + 4, y + 4))
    canvas.alpha_composite(artwork, (x, y))


def draw_checker(draw: ImageDraw.ImageDraw, colors: tuple[tuple[int, ...], tuple[int, ...]]) -> None:
    for y in range(0, 128, 16):
        for x in range(0, 128, 16):
            if (x // 16 + y // 16) % 2:
                draw.rectangle((x, y, x + 15, y + 15), fill=colors[1])


def draw_pokeball_mark(draw: ImageDraw.ImageDraw, x: int, y: int) -> None:
    draw.ellipse((x, y, x + 23, y + 23), fill=(238, 235, 213), outline=(28, 30, 42), width=3)
    draw.pieslice((x, y, x + 23, y + 23), 180, 360, fill=(194, 57, 65))
    draw.rectangle((x, y + 10, x + 23, y + 13), fill=(28, 30, 42))
    draw.ellipse((x + 8, y + 8, x + 15, y + 15), fill=(238, 235, 213), outline=(28, 30, 42), width=2)


def build_square(name: str, pokedex_number: int, light: tuple[int, ...], dark: tuple[int, ...]) -> None:
    canvas = Image.new("RGBA", (128, 128), (*dark, 255))
    draw = ImageDraw.Draw(canvas)
    draw_checker(draw, ((*dark, 255), (*light, 255)))
    draw.polygon(((0, 92), (128, 44), (128, 128), (0, 128)), fill=(*dark, 255))
    draw.rectangle((4, 4, 123, 123), outline=(245, 232, 190, 255), width=4)
    draw_pokeball_mark(draw, 96, 8)
    paste_centered(canvas, sprite(pokedex_number, (96, 96)), (62, 67))
    canvas.convert("RGB").save(TEXTURE_DIR / f"{name}.png", optimize=True)


def build_kanto_poster() -> None:
    canvas = Image.new("RGBA", (128, 256), (36, 52, 91, 255))
    draw = ImageDraw.Draw(canvas)
    for y, color in ((0, (32, 44, 82)), (64, (84, 74, 126)), (128, (205, 109, 93)), (192, (239, 181, 91))):
        draw.rectangle((0, y, 127, min(y + 63, 255)), fill=(*color, 255))
    draw.polygon(((0, 177), (32, 126), (55, 158), (83, 105), (128, 170), (128, 256), (0, 256)), fill=(45, 52, 70, 255))
    draw.rectangle((4, 4, 123, 251), outline=(242, 225, 177, 255), width=4)
    for number, x in ((144, 22), (145, 64), (146, 106)):
        paste_centered(canvas, sprite(number, (40, 40)), (x, 52), shadow=False)
    paste_centered(canvas, sprite(150, (108, 108)), (64, 165))
    draw_pokeball_mark(draw, 52, 220)
    canvas.convert("RGB").save(TEXTURE_DIR / "graham.png", optimize=True)


def build_johto_poster() -> None:
    canvas = Image.new("RGBA", (128, 256), (20, 61, 74, 255))
    draw = ImageDraw.Draw(canvas)
    draw.rectangle((0, 0, 127, 127), fill=(39, 91, 105, 255))
    draw.rectangle((0, 128, 127, 255), fill=(119, 48, 68, 255))
    for x, y in ((16, 18), (100, 30), (24, 104), (108, 142), (18, 190)):
        draw.rectangle((x, y, x + 3, y + 3), fill=(244, 215, 129, 255))
    draw.rectangle((4, 4, 123, 251), outline=(238, 214, 161, 255), width=4)
    paste_centered(canvas, sprite(250, (100, 100)), (68, 73))
    paste_centered(canvas, sprite(249, (100, 100)), (61, 169))
    for number, x in ((152, 24), (155, 64), (158, 104)):
        paste_centered(canvas, sprite(number, (34, 34)), (x, 231), shadow=False)
    canvas.convert("RGB").save(TEXTURE_DIR / "prairie_ride.png", optimize=True)


def build_type_poster() -> None:
    canvas = Image.new("RGBA", (128, 256), (34, 44, 50, 255))
    draw = ImageDraw.Draw(canvas)
    bands = (
        (0, 84, (60, 126, 70), (184, 221, 111), 3),
        (85, 169, (157, 55, 47), (239, 145, 65), 6),
        (170, 255, (40, 91, 143), (91, 181, 211), 9),
    )
    for top, bottom, dark, light, number in bands:
        draw.rectangle((0, top, 127, bottom), fill=(*dark, 255))
        for x in range(-64, 160, 24):
            draw.polygon(((x, top), (x + 12, top), (x + 56, bottom), (x + 44, bottom)), fill=(*light, 255))
        paste_centered(canvas, sprite(number, (78, 72)), (64, (top + bottom) // 2))
    draw.rectangle((4, 4, 123, 251), outline=(245, 232, 190, 255), width=4)
    draw.rectangle((4, 83, 123, 87), fill=(245, 232, 190, 255))
    draw.rectangle((4, 168, 123, 172), fill=(245, 232, 190, 255))
    canvas.convert("RGB").save(TEXTURE_DIR / "wanderer.png", optimize=True)


def build_pack_metadata() -> None:
    metadata = {
        "pack": {
            "pack_format": 34,
            "description": "Cobbleventure: Kanto and Johto pixel paintings (1x1 and 1x2)",
        }
    }
    (PACK_DIR / "pack.mcmeta").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    shutil.copy2(WORK_DIR / "THIRD_PARTY.md", PACK_DIR / "THIRD_PARTY.md")


def build_preview() -> None:
    names = list(SQUARE_PAINTINGS) + ["graham", "prairie_ride", "wanderer"]
    preview = Image.new("RGB", (640, 600), (24, 27, 34))
    draw = ImageDraw.Draw(preview)
    font = ImageFont.load_default()
    for index, name in enumerate(names[:8]):
        x = 20 + (index % 4) * 155
        y = 20 + (index // 4) * 170
        image = Image.open(TEXTURE_DIR / f"{name}.png").convert("RGB")
        preview.paste(image, (x, y))
        draw.text((x, y + 134), name, font=font, fill=(235, 235, 225))
    for index, name in enumerate(names[8:]):
        x = 56 + index * 196
        y = 350
        image = Image.open(TEXTURE_DIR / f"{name}.png").convert("RGB").resize((96, 192), Image.Resampling.NEAREST)
        preview.paste(image, (x, y))
        draw.text((x, y + 198), name, font=font, fill=(235, 235, 225))
    preview.save(GENERATED_DIR / "preview.png", optimize=True)


def write_zip() -> None:
    OUTPUT_ZIP.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUTPUT_ZIP, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for source in sorted(PACK_DIR.rglob("*")):
            if source.is_file():
                archive.write(source, source.relative_to(PACK_DIR).as_posix())


def main() -> None:
    download_missing_sprites()
    if PACK_DIR.exists():
        shutil.rmtree(PACK_DIR)
    TEXTURE_DIR.mkdir(parents=True)
    for name, (number, light, dark) in SQUARE_PAINTINGS.items():
        build_square(name, number, light, dark)
    build_kanto_poster()
    build_johto_poster()
    build_type_poster()
    build_pack_metadata()
    build_preview()
    write_zip()
    print(f"Built {OUTPUT_ZIP}")


if __name__ == "__main__":
    main()
