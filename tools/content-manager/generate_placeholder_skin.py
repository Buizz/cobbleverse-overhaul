#!/usr/bin/env python3
"""Generate the deterministic 64x64 Minecraft skin used for unfinished trainers."""

from __future__ import annotations

import binascii
import struct
import zlib
from pathlib import Path


WIDTH = HEIGHT = 64
TRANSPARENT = (0, 0, 0, 0)
PURPLE = (170, 54, 214, 255)
DARK = (40, 27, 52, 255)
LIGHT = (244, 226, 255, 255)
SKIN = (224, 174, 139, 255)


def rectangle(pixels: list[list[tuple[int, int, int, int]]], x: int, y: int, width: int, height: int, color: tuple[int, int, int, int]) -> None:
    for row in range(y, y + height):
        for column in range(x, x + width):
            pixels[row][column] = color


def checker(pixels: list[list[tuple[int, int, int, int]]], x: int, y: int, width: int, height: int) -> None:
    for row in range(y, y + height):
        for column in range(x, x + width):
            pixels[row][column] = PURPLE if (row + column) % 2 == 0 else DARK


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFFFFFF)


def write_png(path: Path, pixels: list[list[tuple[int, int, int, int]]]) -> None:
    raw = b"".join(b"\x00" + b"".join(bytes(pixel) for pixel in row) for row in pixels)
    png = b"\x89PNG\r\n\x1a\n"
    png += png_chunk(b"IHDR", struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 6, 0, 0, 0))
    png += png_chunk(b"IDAT", zlib.compress(raw, 9))
    png += png_chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


def main() -> None:
    pixels = [[TRANSPARENT for _ in range(WIDTH)] for _ in range(HEIGHT)]

    # Head: top, bottom, right, front, left, back.
    for area in ((8, 0, 8, 8), (16, 0, 8, 8), (0, 8, 8, 8), (8, 8, 8, 8), (16, 8, 8, 8), (24, 8, 8, 8)):
        rectangle(pixels, *area, SKIN)
    rectangle(pixels, 8, 8, 8, 2, DARK)
    rectangle(pixels, 10, 11, 1, 1, DARK)
    rectangle(pixels, 13, 11, 1, 1, DARK)

    # Torso and limbs use a conspicuous purple checker so placeholders are unmistakable in game.
    for area in (
        (20, 16, 8, 4), (28, 16, 8, 4), (16, 20, 4, 12), (20, 20, 8, 12), (28, 20, 4, 12), (32, 20, 8, 12),
        (44, 16, 4, 4), (48, 16, 4, 4), (40, 20, 4, 12), (44, 20, 4, 12), (48, 20, 4, 12), (52, 20, 4, 12),
        (4, 16, 4, 4), (8, 16, 4, 4), (0, 20, 4, 12), (4, 20, 4, 12), (8, 20, 4, 12), (12, 20, 4, 12),
        (36, 48, 4, 4), (40, 48, 4, 4), (32, 52, 4, 12), (36, 52, 4, 12), (40, 52, 4, 12), (44, 52, 4, 12),
        (20, 48, 4, 4), (24, 48, 4, 4), (16, 52, 4, 12), (20, 52, 4, 12), (24, 52, 4, 12), (28, 52, 4, 12),
    ):
        checker(pixels, *area)

    # A white question mark on the torso front.
    for x, y in ((22, 22), (23, 21), (24, 21), (25, 22), (25, 23), (24, 24), (23, 25), (23, 28)):
        pixels[y][x] = LIGHT

    output = Path(__file__).parents[2] / "projects" / "cobbleventure-world-bootstrap" / "src" / "main" / "resources" / "assets" / "cobbleventure" / "textures" / "entity" / "trainer" / "unimplemented.png"
    write_png(output, pixels)
    print(output)


if __name__ == "__main__":
    main()
