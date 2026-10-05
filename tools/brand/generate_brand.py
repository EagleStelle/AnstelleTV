"""Generates the AnstelleTV launcher icon and TV banner.

Mark: an "A"-frame antenna (the letter A) on a retro TV with fading scanlines.
The wordmark uses DejaVu Sans Bold (Bitstream Vera license), which ships with matplotlib.

    pip install fonttools pillow matplotlib
    python tools/brand/generate_brand.py

Outputs (all under app/src/main/res):
    drawable/ic_launcher_foreground.xml, drawable/ic_launcher_monochrome.xml,
    values/ic_launcher_background.xml, mipmap-anydpi-v26/ic_launcher(_round).xml,
    mipmap-anydpi/ic_launcher(_round).xml (pre-Android 8 / Fire OS 6),
    drawable-xhdpi/tv_banner.png (a PNG: vectors over 200 dp are slow to draw).
"""
import os

import matplotlib
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
FONT = os.path.join(matplotlib.get_data_path(), "fonts", "ttf", "DejaVuSans-Bold.ttf")

BG = "#0E1116"
BLUE = "#4FB3FF"
AMBER = "#FFB547"
TEXT = "#ECEFF4"


def rgba(hex_color, alpha=255):
    value = hex_color.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4)) + (alpha,)


# ---------------------------------------------------------------- vector icon

def rrect(x0, y0, x1, y1, r):
    return (f"M{x0 + r},{y0} H{x1 - r} A{r},{r} 0 0 1 {x1},{y0 + r} V{y1 - r} "
            f"A{r},{r} 0 0 1 {x1 - r},{y1} H{x0 + r} A{r},{r} 0 0 1 {x0},{y1 - r} "
            f"V{y0 + r} A{r},{r} 0 0 1 {x0 + r},{y0} Z")


def circle(cx, cy, r):
    return f"M{cx - r},{cy} A{r},{r} 0 1 1 {cx + r},{cy} A{r},{r} 0 1 1 {cx - r},{cy} Z"


SCANLINES = [(40, 68), (40, 62), (40, 56)]


def mark_paths(mono=None):
    """The mark on a 108x108 adaptive-icon canvas (safe zone 21..87)."""
    blue = mono or BLUE
    amber = mono or AMBER
    paths = []
    if mono:
        # Monochrome: cut the screen out (even-odd) so the system tint reads as an outline.
        paths.append(f'<path android:fillColor="{blue}" android:fillType="evenOdd" '
                     f'android:pathData="{rrect(30, 44, 78, 82, 8)} {rrect(35, 49, 73, 77, 5)}"/>')
    else:
        paths.append(f'<path android:fillColor="{blue}" android:pathData="{rrect(30, 44, 78, 82, 8)}"/>')
        paths.append(f'<path android:fillColor="{BG}" android:pathData="{rrect(35, 49, 73, 77, 5)}"/>')
    for i, (x0, x1) in enumerate(SCANLINES):
        y = 55 + i * 6
        paths.append(f'<path android:fillColor="{blue}" android:fillAlpha="{0.85 - i * 0.2:.2f}" '
                     f'android:pathData="{rrect(x0, y, x1, y + 2.6, 1.3)}"/>')
    paths.append(f'<path android:strokeColor="{amber}" android:strokeWidth="4.5" '
                 f'android:strokeLineCap="round" android:strokeLineJoin="round" '
                 f'android:pathData="M38,44 L54,27 L70,44"/>')
    paths.append(f'<path android:fillColor="{amber}" android:pathData="{circle(54, 25, 3.6)}"/>')
    return paths


def vector(width, height, viewport_w, viewport_h, body):
    inner = "\n    ".join(body)
    return (f'<?xml version="1.0" encoding="utf-8"?>\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{width}dp"\n    android:height="{height}dp"\n'
            f'    android:viewportWidth="{viewport_w}"\n    android:viewportHeight="{viewport_h}">\n'
            f'    {inner}\n</vector>\n')


def write(rel, text):
    path = os.path.join(RES, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("wrote", rel)


def write_icons():
    write("drawable/ic_launcher_foreground.xml", vector(108, 108, 108, 108, mark_paths()))
    write("drawable/ic_launcher_monochrome.xml", vector(108, 108, 108, 108, mark_paths(mono="#FFFFFFFF")))
    write("values/ic_launcher_background.xml",
          f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
          f'    <color name="ic_launcher_background">{BG}</color>\n</resources>\n')
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/ic_launcher_background"/>\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n'
                '</adaptive-icon>\n')
    write("mipmap-anydpi-v26/ic_launcher.xml", adaptive)
    write("mipmap-anydpi-v26/ic_launcher_round.xml", adaptive)
    square = [f'<path android:fillColor="{BG}" android:pathData="{rrect(8, 8, 100, 100, 20)}"/>']
    round_ = [f'<path android:fillColor="{BG}" android:pathData="{circle(54, 54, 48)}"/>']
    write("mipmap-anydpi/ic_launcher.xml", vector(48, 48, 108, 108, square + mark_paths()))
    write("mipmap-anydpi/ic_launcher_round.xml", vector(48, 48, 108, 108, round_ + mark_paths()))


# ------------------------------------------------------------------ TV banner

def write_banner(density_scale=2, folder="drawable-xhdpi"):
    """320x180 dp banner, drawn supersampled then downscaled."""
    supersample = 4
    k = density_scale * supersample
    width, height = 320 * k, 180 * k
    img = Image.new("RGBA", (width, height), rgba(BG))
    draw = ImageDraw.Draw(img)

    # Mark: icon units scaled 1.25 and centered at (64, 90) dp.
    s, cx, cy = 1.25, 54, 52
    ox, oy = 64 - cx * s, 90 - cy * s

    def point(x, y):
        return (ox + x * s) * k, (oy + y * s) * k

    def rounded(x0, y0, x1, y1, r, fill):
        draw.rounded_rectangle([point(x0, y0), point(x1, y1)], radius=r * s * k, fill=fill)

    rounded(30, 44, 78, 82, 8, rgba(BLUE))
    rounded(35, 49, 73, 77, 5, rgba(BG))
    for i, (x0, x1) in enumerate(SCANLINES):
        y = 55 + i * 6
        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        ImageDraw.Draw(layer).rounded_rectangle(
            [point(x0, y), point(x1, y + 2.6)], radius=1.3 * s * k,
            fill=rgba(BLUE, int(255 * (0.85 - i * 0.2))))
        img.alpha_composite(layer)
    stroke = int(4.5 * s * k)
    antenna = [point(38, 44), point(54, 27), point(70, 44)]
    draw.line(antenna, fill=rgba(AMBER), width=stroke, joint="curve")
    for x, y in (antenna[0], antenna[2]):
        draw.ellipse([x - stroke / 2, y - stroke / 2, x + stroke / 2, y + stroke / 2], fill=rgba(AMBER))
    x, y = point(54, 25)
    r = 3.6 * s * k
    draw.ellipse([x - r, y - r, x + r, y + r], fill=rgba(AMBER))

    # Wordmark: "Anstelle" + "TV" in the accent color.
    font = ImageFont.truetype(FONT, 30 * k)
    ascent, _ = font.getmetrics()
    text_x = 122 * k
    text_y = (90 + 30 * 0.36) * k - ascent
    draw.text((text_x, text_y), "Anstelle", font=font, fill=rgba(TEXT))
    draw.text((text_x + draw.textlength("Anstelle", font=font), text_y), "TV", font=font, fill=rgba(BLUE))

    out = img.resize((320 * density_scale, 180 * density_scale), Image.LANCZOS).convert("RGB")
    path = os.path.join(RES, folder, "tv_banner.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out.save(path, optimize=True)
    print("wrote", f"{folder}/tv_banner.png", out.size)


if __name__ == "__main__":
    write_icons()
    write_banner()
