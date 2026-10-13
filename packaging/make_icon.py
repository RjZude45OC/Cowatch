"""Draws the cowatch app icon and writes every size the app and installers use.

Run from the repository root:  python packaging/make_icon.py   (needs Pillow)

Two play triangles, one a step behind the other: two viewers on the same frame.
"""
from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path("src/main/resources/icons")
S = 1024  # master size; everything else is downscaled from it

TOP = (138, 120, 255)     # lighter than the app accent #7c6af7
BOTTOM = (76, 58, 196)    # darker than the pressed accent #5848c8


def master() -> Image.Image:
    # Vertical gradient clipped to a rounded square.
    grad = Image.new("RGBA", (S, S))
    px = grad.load()
    for y in range(S):
        t = y / (S - 1)
        c = tuple(round(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3)) + (255,)
        for x in range(S):
            px[x, y] = c
    mask = Image.new("L", (S, S), 0)
    margin = round(S * 0.06)
    ImageDraw.Draw(mask).rounded_rectangle(
        (margin, margin, S - margin, S - margin), radius=round(S * 0.22), fill=255)
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    img.paste(grad, (0, 0), mask)

    def triangle(cx, cy, h):
        w = h * 0.866
        return [(cx - w / 3, cy - h / 2), (cx - w / 3, cy + h / 2), (cx + 2 * w / 3, cy)]

    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    h = S * 0.46
    d.polygon(triangle(S * 0.42, S * 0.5, h), fill=(255, 255, 255, 110))
    d.polygon(triangle(S * 0.56, S * 0.5, h), fill=(255, 255, 255, 255))
    return Image.alpha_composite(img, layer)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    big = master()
    for size in (16, 24, 32, 48, 64, 128, 256):
        big.resize((size, size), Image.LANCZOS).save(OUT / f"cowatch-{size}.png")
    big.resize((512, 512), Image.LANCZOS).save(OUT / "icon.png")
    big.save(OUT / "icon.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48),
                                      (64, 64), (128, 128), (256, 256)])
    big.save(OUT / "icon.icns")


if __name__ == "__main__":
    main()
