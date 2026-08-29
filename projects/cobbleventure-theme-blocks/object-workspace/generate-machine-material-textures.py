#!/usr/bin/env python3
"""Generate deterministic 64x64 pixel-art materials for machine models."""

from pathlib import Path
import random

from PIL import Image


SIZE = 64
SEED = 0xC0BB1E
OUTPUT_DIR = (
    Path(__file__).resolve().parent
    / "assets/cobbleventure_theme_blocks/textures/block"
)


def clamp(value: int) -> int:
    return max(0, min(255, value))


def shifted(color: tuple[int, int, int], amount: int) -> tuple[int, int, int, int]:
    return tuple(clamp(channel + amount) for channel in color) + (255,)


def make_material(
    base: tuple[int, int, int],
    pixel_levels: tuple[int, ...],
    cluster_levels: tuple[int, ...],
    seed_offset: int,
) -> Image.Image:
    rng = random.Random(SEED + seed_offset)
    image = Image.new("RGBA", (SIZE, SIZE))
    pixels = image.load()

    clusters = [
        [rng.choice(cluster_levels) for _ in range(SIZE // 4)]
        for _ in range(SIZE // 4)
    ]
    for y in range(SIZE):
        for x in range(SIZE):
            amount = clusters[y // 4][x // 4] + rng.choice(pixel_levels)
            pixels[x, y] = shifted(base, amount)
    return image


def generate_iron_plate() -> Image.Image:
    image = make_material(
        # 참고 기계 본체의 따뜻한 밝은 회색 철판 팔레트.
        base=(173, 171, 169),
        pixel_levels=(-3, -2, -1, 0, 0, 0, 1, 2, 3),
        cluster_levels=(-3, -1, 0, 0, 0, 1, 3),
        seed_offset=11,
    )
    pixels = image.load()
    rng = random.Random(SEED + 12)

    # 격자나 체크로 보이지 않는 짧은 수평 금속 결만 드물게 둔다.
    for _ in range(34):
        x = rng.randrange(SIZE)
        y = rng.randrange(SIZE)
        length = rng.randrange(2, 6)
        amount = rng.choice((-6, -4, 4, 6))
        for offset in range(length):
            px = (x + offset) % SIZE
            current = pixels[px, y]
            pixels[px, y] = shifted(current[:3], amount)
    return image


def generate_dark_connector() -> Image.Image:
    image = make_material(
        # 참고 이미지 하단 연결부의 중성에 가까운 청흑색 팔레트.
        base=(54, 55, 57),
        pixel_levels=(-2, -1, 0, 0, 0, 1, 2),
        cluster_levels=(-2, -1, 0, 0, 1, 2),
        seed_offset=21,
    )
    pixels = image.load()
    rng = random.Random(SEED + 22)

    # 체크·격자 없이 눌린 금속/고무의 불규칙한 짧은 마찰 흔적만 추가한다.
    for _ in range(38):
        x = rng.randrange(SIZE)
        y = rng.randrange(SIZE)
        horizontal = rng.choice((True, False))
        length = rng.randrange(2, 6)
        amount = rng.choice((-7, -4, 4, 7))
        for offset in range(length):
            px = (x + offset) % SIZE if horizontal else x
            py = y if horizontal else (y + offset) % SIZE
            current = pixels[px, py]
            pixels[px, py] = shifted(current[:3], amount)
    return image


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    outputs = {
        "machine_iron_plate_64.png": generate_iron_plate(),
        "machine_dark_connector_64.png": generate_dark_connector(),
    }
    for filename, image in outputs.items():
        path = OUTPUT_DIR / filename
        image.save(path, format="PNG", optimize=True)
        print(f"generated: {path} ({image.width}x{image.height})")


if __name__ == "__main__":
    main()
