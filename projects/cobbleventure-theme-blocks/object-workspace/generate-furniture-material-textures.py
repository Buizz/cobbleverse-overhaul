#!/usr/bin/env python3
"""Generate deterministic pixel-art materials for furniture models."""

from math import pi, sin
from pathlib import Path
import random

from PIL import Image


SIZE = 64
SEED = 0xF01217
OUTPUT_DIR = (
    Path(__file__).resolve().parent
    / "assets/cobbleventure_theme_blocks/textures/block"
)


def clamp(value: int) -> int:
    return max(0, min(255, value))


def shade(color: tuple[int, int, int], amount: int) -> tuple[int, int, int, int]:
    return tuple(clamp(channel + amount) for channel in color) + (255,)


def generate_olive_wood() -> Image.Image:
    rng = random.Random(SEED)
    base = (171, 147, 63)
    image = Image.new("RGBA", (SIZE, SIZE))
    pixels = image.load()

    # 1픽셀 노이즈와 4픽셀 단위 색 덩어리로 원본 프레임 팔레트를 재현한다.
    clusters = [
        [rng.choice((-5, -2, 0, 0, 0, 2, 4)) for _ in range(SIZE // 4)]
        for _ in range(SIZE // 4)
    ]
    for y in range(SIZE):
        for x in range(SIZE):
            amount = clusters[y // 4][x // 4] + rng.choice((-3, -1, 0, 0, 0, 1, 3))
            pixels[x, y] = shade(base, amount)

    # 수평으로 이어지는 완만한 나뭇결. 64픽셀 경계에서 자연스럽게 반복된다.
    grain_rows = (5, 13, 22, 31, 41, 51, 59)
    for index, row in enumerate(grain_rows):
        phase = index * 0.73
        for x in range(SIZE):
            wave = round(sin((x / SIZE) * 2 * pi + phase) * 1.5)
            y = (row + wave) % SIZE
            current = pixels[x, y]
            pixels[x, y] = shade(current[:3], -13 if index % 2 == 0 else -9)
            if x % 3 != 0:
                highlight_y = (y + 1) % SIZE
                current = pixels[x, highlight_y]
                pixels[x, highlight_y] = shade(current[:3], 5)

    # 작고 픽셀화된 옹이 두 개. UV를 돌리면 세로 프레임에도 사용할 수 있다.
    for center_x, center_y in ((18, 18), (49, 46)):
        for radius, amount in ((5, -8), (3, 7), (1, -20)):
            for step in range(16):
                x = (center_x + round(radius * sin(step * pi / 8))) % SIZE
                y = (center_y + round((radius / 2) * sin(step * pi / 8 + pi / 2))) % SIZE
                current = pixels[x, y]
                pixels[x, y] = shade(current[:3], amount)

    return image


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    output = OUTPUT_DIR / "furniture_olive_wood_64.png"
    image = generate_olive_wood()
    image.save(output, format="PNG", optimize=True)
    print(f"generated: {output} ({image.width}x{image.height})")


if __name__ == "__main__":
    main()
