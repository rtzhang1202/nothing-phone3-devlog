#!/usr/bin/env python3
"""Generate Nothing-aesthetic launcher icons for Fullscreen AOD (black bg + white dot-matrix sun/ring)."""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

BLACK = (10, 10, 10, 255)
WHITE = (255, 255, 255, 255)
DIM_WHITE = (255, 255, 255, 110)

# 9x9 dot-matrix sun/ambient-ring glyph (Nothing dot-matrix style)
GLYPH = [
    "....X....",
    ".X..X..X.",
    "..X...X..",
    "...XXX...",
    "XX.X.X.XX",
    "...XXX...",
    "..X...X..",
    ".X..X..X.",
    "....X....",
]

# Subtle dot-matrix "A" badge for bottom-right or center accent
def draw_matrix(draw, grid, origin, spacing, radius, color):
    ox, oy = origin
    for r, row in enumerate(grid):
        for c, ch in enumerate(row):
            if ch == "X":
                x = ox + c * spacing
                y = oy + r * spacing
                draw.ellipse([x - radius, y - radius, x + radius, y + radius], fill=color)

def make_foreground(size=432):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    spacing = int(size * 0.062)
    radius = int(spacing * 0.34)
    cols, rows = len(GLYPH[0]), len(GLYPH)
    gw = (cols - 1) * spacing
    gh = (rows - 1) * spacing
    origin = ((size - gw) // 2, (size - gh) // 2)
    draw_matrix(d, GLYPH, origin, spacing, radius, WHITE)
    # Center glowing dot (red Nothing accent)
    cx, cy = origin[0] + 4 * spacing, origin[1] + 4 * spacing
    d.ellipse([cx - radius, cy - radius, cx + radius, cy + radius], fill=(215, 25, 33, 255))
    return img

def make_background(size=432):
    return Image.new("RGBA", (size, size), BLACK)

def make_legacy(size=192, round_mask=False):
    img = Image.new("RGBA", (size, size), BLACK)
    d = ImageDraw.Draw(img)
    spacing = int(size * 0.088)
    radius = int(spacing * 0.34)
    cols, rows = len(GLYPH[0]), len(GLYPH)
    gw = (cols - 1) * spacing
    gh = (rows - 1) * spacing
    origin = ((size - gw) // 2, (size - gh) // 2)
    draw_matrix(d, GLYPH, origin, spacing, radius, WHITE)
    cx, cy = origin[0] + 4 * spacing, origin[1] + 4 * spacing
    d.ellipse([cx - radius, cy - radius, cx + radius, cy + radius], fill=(215, 25, 33, 255))
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
    print("icons written to", RES)

if __name__ == "__main__":
    main()
