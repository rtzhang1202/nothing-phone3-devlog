#!/usr/bin/env python3
"""Generate Nothing-aesthetic KeyPilot launcher icons (black bg + white dot-matrix K + red key dot).

Outputs (deterministic):
  app/src/main/res/drawable/ic_launcher_background.png   432x432 solid Nothing black
  app/src/main/res/drawable/ic_launcher_foreground.png   432x432 dot-matrix K + red accent dot
  app/src/main/res/mipmap-xxhdpi/ic_launcher.png         192x192 legacy fallback
  app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png   192x192 legacy fallback (round)
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

BLACK = (10, 10, 10, 255)
WHITE = (255, 255, 255, 255)
NOTHING_RED = (215, 25, 33, 255)
DIM_WHITE = (255, 255, 255, 38)

# 5x7 dot-matrix 'K' glyph with red accent dot 'R' symbolizing Essential Key
K_GLYPH = [
    "X...X",
    "X..X.",
    "X.X..",
    "XX...",
    "X.X..",
    "X..X.",
    "X...R",
]

# 4 vertical side-key dots on the left margin (Power, Vol+, Vol-, Essential)
SIDE_KEYS = [
    "W",
    "W",
    "W",
    ".",
    "R",
]


def draw_dot_matrix(draw, glyph, origin, spacing, radius):
    ox, oy = origin
    for r, row in enumerate(glyph):
        for c, ch in enumerate(row):
            if ch in ("X", "W", "R"):
                color = NOTHING_RED if ch == "R" else WHITE
                x = ox + c * spacing
                y = oy + r * spacing
                draw.ellipse([x - radius, y - radius, x + radius, y + radius], fill=color)


def make_foreground(size=432):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    spacing = int(size * 0.076)   # ~32px at 432
    radius = int(spacing * 0.34)
    cols, rows = 5, 7
    gw = (cols - 1) * spacing
    gh = (rows - 1) * spacing

    # Subtle sparse dot-matrix grid outside the center safe zone (Nothing aesthetic)
    small = max(2, radius // 3)
    grid_step = int(size * 0.076)
    margin = int(size * 0.12)
    for gx in range(11):
        for gy in range(11):
            x = margin + gx * grid_step
            y = margin + gy * grid_step
            if x > size - margin or y > size - margin:
                continue
            if abs(x - size / 2) > size * 0.24 or abs(y - size / 2) > size * 0.27:
                d.ellipse([x - small, y - small, x + small, y + small], fill=DIM_WHITE)

    # Center dot-matrix K
    origin = ((size - gw) // 2 + int(spacing * 0.25), (size - gh) // 2)
    draw_dot_matrix(d, K_GLYPH, origin, spacing, radius)

    # Four subtle hardware-key dots on the left of K
    side_x = origin[0] - int(spacing * 1.15)
    side_sp = int(spacing * 0.72)
    side_r = max(3, int(radius * 0.48))
    side_y0 = (size - (len(SIDE_KEYS) - 1) * side_sp) // 2
    for idx, ch in enumerate(SIDE_KEYS):
        if ch == ".":
            continue
        col = NOTHING_RED if ch == "R" else (255, 255, 255, 165)
        cy = side_y0 + idx * side_sp
        d.ellipse([side_x - side_r, cy - side_r, side_x + side_r, cy + side_r], fill=col)

    return img


def make_background(size=432):
    return Image.new("RGBA", (size, size), BLACK)


def make_legacy(size=192, round_mask=False):
    img = Image.new("RGBA", (size, size), BLACK)
    d = ImageDraw.Draw(img)
    spacing = int(size * 0.102)
    radius = int(spacing * 0.34)
    cols, rows = 5, 7
    gw = (cols - 1) * spacing
    gh = (rows - 1) * spacing
    origin = ((size - gw) // 2 + int(spacing * 0.2), (size - gh) // 2)
    draw_dot_matrix(d, K_GLYPH, origin, spacing, radius)

    # 4 hardware-key indicator dots on the left edge
    side_x = origin[0] - int(spacing * 1.1)
    side_sp = int(spacing * 0.72)
    side_r = max(2, int(radius * 0.48))
    side_y0 = (size - (len(SIDE_KEYS) - 1) * side_sp) // 2
    for idx, ch in enumerate(SIDE_KEYS):
        if ch == ".":
            continue
        col = NOTHING_RED if ch == "R" else (255, 255, 255, 175)
        cy = side_y0 + idx * side_sp
        d.ellipse([side_x - side_r, cy - side_r, side_x + side_r, cy + side_r], fill=col)

    if round_mask:
        mask = Image.new("L", (size, size), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.paste(img, (0, 0), mask)
        return out
    return img


def main():
    os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)
    os.makedirs(os.path.join(RES, "mipmap-xxhdpi"), exist_ok=True)
    os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)

    make_background().save(os.path.join(RES, "drawable", "ic_launcher_background.png"))
    make_foreground().save(os.path.join(RES, "drawable", "ic_launcher_foreground.png"))
    make_legacy().save(os.path.join(RES, "mipmap-xxhdpi", "ic_launcher.png"))
    make_legacy(round_mask=True).save(os.path.join(RES, "mipmap-xxhdpi", "ic_launcher_round.png"))
    print("KeyPilot icons written to", RES)


if __name__ == "__main__":
    main()
