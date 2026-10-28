"""Generates the AnstelleTV launcher icon, TV banner and Play Store graphics.

Mark: the channel bug. An ink "A" on a rose tile, its crossbar running on past the right
leg like the guide's now line running out of frame. The same geometry draws the in-app mark
(`AnstelleMark` in core/theme). The wordmark is Barlow Condensed Bold (SIL OFL 1.1), the
app's own display face, read from core/theme/src/main/res/font.

    pip install pillow
    python tools/brand/generate_brand.py

Outputs (all under app/src/main/res):
    drawable/ic_launcher_foreground.xml, drawable/ic_launcher_monochrome.xml,
    values/ic_launcher_background.xml, mipmap-anydpi-v26/ic_launcher(_round).xml,
    mipmap-anydpi/ic_launcher(_round).xml (pre-Android 8 / Fire OS 6),
    drawable-xhdpi/tv_banner.png (a PNG: vectors over 200 dp are slow to draw).

Play Store graphics (under fastlane/metadata/android/en-US/images):
    icon.png (512 x 512), featureGraphic.png (1024 x 500), tvBanner.png (1280 x 720).
"""
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
STORE = os.path.join(ROOT, "fastlane", "metadata", "android", "en-US", "images")
FONT = os.path.join(ROOT, "core", "theme", "src", "main", "res", "font", "barlow_condensed_bold.ttf")

INK = "#130C0F"
ROSE = "#F978B6"
BONE = "#F9F1F4"

# The mark on a 108x108 adaptive-icon canvas (safe zone 21..87).
A_OUTLINE = [(45, 28), (55, 28), (73, 80), (63, 80), (50, 41.5), (37, 80), (27, 80)]
BAR = (38, 58, 82, 66)  # left, top, right, bottom


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


def polygon(points):
    head, *rest = points
    return f"M{head[0]},{head[1]} " + " ".join(f"L{x},{y}" for x, y in rest) + " Z"


def mark_paths(color):
    x0, y0, x1, y1 = BAR
    return [
        f'<path android:fillColor="{color}" android:pathData="{polygon(A_OUTLINE)}"/>',
        f'<path android:fillColor="{color}" android:pathData="M{x0},{y0} H{x1} V{y1} H{x0} Z"/>',
    ]


def in_safe_zone(paths):
    """Adaptive layers are masked to a circle as small as 66 dp: the mark shrinks to fit it."""
    return ['<group android:scaleX="0.85" android:scaleY="0.85" android:pivotX="54" android:pivotY="54">',
            *paths, '</group>']


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
    write("drawable/ic_launcher_foreground.xml", vector(108, 108, 108, 108, in_safe_zone(mark_paths(INK))))
    write("drawable/ic_launcher_monochrome.xml", vector(108, 108, 108, 108, in_safe_zone(mark_paths("#FFFFFFFF"))))
    write("values/ic_launcher_background.xml",
          f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
          f'    <color name="ic_launcher_background">{ROSE}</color>\n</resources>\n')
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/ic_launcher_background"/>\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n'
                '</adaptive-icon>\n')
    write("mipmap-anydpi-v26/ic_launcher.xml", adaptive)
    write("mipmap-anydpi-v26/ic_launcher_round.xml", adaptive)
    square = [f'<path android:fillColor="{ROSE}" android:pathData="{rrect(8, 8, 100, 100, 10)}"/>']
    round_ = [f'<path android:fillColor="{ROSE}" android:pathData="{circle(54, 54, 48)}"/>']
    write("mipmap-anydpi/ic_launcher.xml", vector(48, 48, 108, 108, square + mark_paths(INK)))
    write("mipmap-anydpi/ic_launcher_round.xml", vector(48, 48, 108, 108, round_ + mark_paths(INK)))


# ------------------------------------------------------------------ TV banner

def lockup(width, height, scale, left=None):
    """An ink width x height dp canvas at `scale` px per dp with the bug, then the wordmark,
    vertically centred and starting `left` dp in (centred when None). Drawn supersampled
    then downscaled."""
    k = scale * 4
    img = Image.new("RGBA", (width * k, height * k), rgba(INK))
    draw = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, 44 * k)
    tile, gap = 96, 16
    if left is None:
        left = (width - tile - gap - draw.textlength("AnstelleTV", font=font) / k) / 2
    mid = height / 2

    # The bug: a 96 dp tile, mark units scaled into it.
    top = mid - tile / 2
    s = tile / 108

    def point(x, y):
        return (left + x * s) * k, (top + y * s) * k

    draw.rounded_rectangle([point(0, 0), point(108, 108)], radius=6 * s * k, fill=rgba(ROSE))
    draw.polygon([point(x, y) for x, y in A_OUTLINE], fill=rgba(INK))
    x0, y0, x1, y1 = BAR
    draw.rectangle([point(x0, y0), point(x1, y1)], fill=rgba(INK))

    # Wordmark: "Anstelle" in bone, "TV" in rose, cap height on the tile's middle.
    text_x = (left + tile + gap) * k
    _, cap_top, _, cap_bottom = font.getbbox("A")
    text_y = mid * k - (cap_top + cap_bottom) / 2
    draw.text((text_x, text_y), "Anstelle", font=font, fill=rgba(BONE))
    draw.text((text_x + draw.textlength("Anstelle", font=font), text_y), "TV", font=font, fill=rgba(ROSE))

    return img.resize((width * scale, height * scale), Image.LANCZOS).convert("RGB")


def save_png(img, folder, name):
    os.makedirs(folder, exist_ok=True)
    img.save(os.path.join(folder, name), optimize=True)
    print("wrote", os.path.relpath(os.path.join(folder, name), ROOT), img.size)


def write_banner(density_scale=2, folder="drawable-xhdpi"):
    """320x180 dp banner: the bug centred on (72, 90) dp, then the wordmark."""
    save_png(lockup(320, 180, density_scale, left=24), os.path.join(RES, folder), "tv_banner.png")


# ------------------------------------------------------------ Play Store graphics

def write_store_graphics():
    """The listing's icon, feature graphic and TV banner."""
    n = 512 * 4
    icon = Image.new("RGBA", (n, n), rgba(ROSE))
    draw = ImageDraw.Draw(icon)

    def point(x, y):
        # As on the launcher: the middle 72 dp of the 108 dp canvas, the mark at 0.85 (in_safe_zone).
        return tuple((54 + (v - 54) * 0.85 - 18) * n / 72 for v in (x, y))

    draw.polygon([point(x, y) for x, y in A_OUTLINE], fill=rgba(INK))
    x0, y0, x1, y1 = BAR
    draw.rectangle([point(x0, y0), point(x1, y1)], fill=rgba(INK))
    save_png(icon.resize((512, 512), Image.LANCZOS), STORE, "icon.png")
    save_png(lockup(512, 250, 2), STORE, "featureGraphic.png")
    save_png(lockup(320, 180, 4, left=24), STORE, "tvBanner.png")


if __name__ == "__main__":
    write_icons()
    write_banner()
    write_store_graphics()
