#!/usr/bin/env python3
"""Generates PTA's original logo and Android launcher icons (no third-party art)."""
from PIL import Image, ImageDraw, ImageFont
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FONT_BOLD = os.path.join(ROOT, "app/src/main/assets/fonts/DejaVuSans-Bold.ttf")
FONT = os.path.join(ROOT, "app/src/main/assets/fonts/DejaVuSans.ttf")

GOLD = (238, 194, 104, 255)
GOLD_DARK = (168, 124, 48, 255)
CREAM = (242, 238, 225, 255)
NIGHT = (12, 20, 28, 255)
TEAL = (22, 48, 62, 255)


def rounded(draw, box, radius, fill, outline=None, width=0):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def duomo_silhouette(draw, cx, base_y, scale, color):
    """Simplified Pistoia bell tower and dome, drawn as original vector shapes."""
    # tower
    tw = int(52 * scale)
    th = int(230 * scale)
    x0 = cx - tw
    x1 = cx + tw
    draw.rectangle([x0, base_y - th, x1, base_y], fill=color)
    # belfry openings
    for i in range(3):
        oy = base_y - th + int(28 * scale) + i * int(58 * scale)
        ow = int(16 * scale)
        draw.rectangle([cx - ow, oy, cx + ow, oy + int(30 * scale)], fill=(8, 12, 16, 255))
    # spire
    draw.polygon([(x0 - int(10 * scale), base_y - th),
                  (cx, base_y - th - int(72 * scale)),
                  (x1 + int(10 * scale), base_y - th)], fill=color)
    # dome to the right
    dw = int(96 * scale)
    draw.ellipse([cx + int(70 * scale), base_y - int(120 * scale),
                  cx + int(70 * scale) + dw * 2, base_y], fill=color)
    draw.rectangle([cx + int(70 * scale), base_y - int(60 * scale),
                    cx + int(70 * scale) + dw * 2, base_y], fill=color)
    draw.polygon([(cx + int(70 * scale) + dw, base_y - int(118 * scale)),
                  (cx + int(70 * scale) + dw, base_y - int(170 * scale)),
                  (cx + int(70 * scale) + dw + int(10 * scale), base_y - int(118 * scale))], fill=color)


def build_logo(size=1024):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = int(size * 0.035)
    rounded(d, [m, m, size - m, size - m], int(size * 0.10), NIGHT, GOLD_DARK, max(2, int(size * 0.008)))
    # background hills + sky band
    d.rectangle([m, int(size * 0.58), size - m, size - m], fill=(16, 30, 40, 255))
    d.polygon([(m, int(size * 0.62)), (int(size * 0.30), int(size * 0.46)),
               (int(size * 0.52), int(size * 0.60)), (int(size * 0.70), int(size * 0.44)),
               (size - m, int(size * 0.60)), (size - m, int(size * 0.72)), (m, int(size * 0.72))],
              fill=(20, 40, 50, 255))
    duomo_silhouette(d, int(size * 0.36), int(size * 0.72), size / 1024.0, (28, 52, 64, 255))
    # PTA monogram
    f = ImageFont.truetype(FONT_BOLD, int(size * 0.30))
    text = "PTA"
    bbox = d.textbbox((0, 0), text, font=f)
    tw = bbox[2] - bbox[0]
    d.text(((size - tw) / 2 - bbox[0], int(size * 0.09) - bbox[1]), text, font=f, fill=GOLD)
    f2 = ImageFont.truetype(FONT_BOLD, int(size * 0.058))
    sub = "PISTOIA TOWN ADVENTURES"
    bbox2 = d.textbbox((0, 0), sub, font=f2)
    d.text(((size - (bbox2[2] - bbox2[0])) / 2 - bbox2[0], int(size * 0.44)), sub, font=f2, fill=CREAM)
    # gold rule
    d.rectangle([int(size * 0.30), int(size * 0.53), int(size * 0.70), int(size * 0.535)], fill=GOLD)
    f3 = ImageFont.truetype(FONT, int(size * 0.036))
    city = "PISTOIA · TOSCANA"
    bbox3 = d.textbbox((0, 0), city, font=f3)
    d.text(((size - (bbox3[2] - bbox3[0])) / 2 - bbox3[0], int(size * 0.86)), city, font=f3, fill=(196, 206, 214, 255))
    return img


def build_icon(size):
    img = build_logo(1024).resize((size, size), Image.LANCZOS)
    return img


def main():
    assets_ui = os.path.join(ROOT, "app/src/main/assets/ui")
    os.makedirs(assets_ui, exist_ok=True)
    logo = build_logo(1024)
    logo.save(os.path.join(assets_ui, "logo.png"))
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, px in sizes.items():
        folder = os.path.join(ROOT, "app/src/main/res", "mipmap-" + name)
        os.makedirs(folder, exist_ok=True)
        build_icon(px).save(os.path.join(folder, "ic_launcher.png"))
    # Play Store style high-res icon for documentation
    docs = os.path.join(ROOT, "docs")
    os.makedirs(docs, exist_ok=True)
    build_icon(512).save(os.path.join(docs, "icon.png"))
    print("logo + launcher icons generated")


if __name__ == "__main__":
    main()
