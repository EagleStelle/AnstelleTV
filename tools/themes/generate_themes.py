"""Generates AnstelleTV's built-in themes beside Rose.

Every theme is the Rose ladder moved to another hue: the same OKLCH lightness steps for the
ground, surfaces, line and text, one loud signal color for focus, and four dark (or light)
guide-run fills whose hues keep Rose's spacing around the theme's own. Rose itself
(rose.json, mirrored by DefaultTheme) is hand-tuned and only checked here.

No dependencies:

    python tools/themes/generate_themes.py

Writes core/theme/src/main/assets/themes/<key>.json and fails when a theme misses a
contrast floor (ThemeContrast in core/model applies the same floors in the designer and
the unit tests).
"""
import json
import math
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "core", "theme", "src", "main", "assets", "themes")

# Rose's run hues sit at these offsets from its own hue (teal, ochre, slate, moss).
RUN_OFFSETS = (-157, 79, -97, 148)

# The ladder, as (lightness, chroma) per role, from Rose's own values.
DARK = {
    "background": (0.165, 0.013),
    "surface": (0.214, 0.018),
    "surfaceHigh": (0.250, 0.019),
    "line": (0.290, 0.021),
    "text": (0.965, 0.009),
    "textMuted": (0.761, 0.026),
    "accentContainer": (0.310, 0.086),
    "onAccentContainer": (0.899, 0.061),
    "guideCellPast": (0.191, 0.011),
    "run": (0.309, 0.049),
}
LIGHT = {
    "background": (0.975, 0.008),
    "surface": (0.935, 0.014),
    "surfaceHigh": (0.905, 0.017),
    "line": (0.850, 0.020),
    "text": (0.200, 0.020),
    "textMuted": (0.450, 0.035),
    "accentContainer": (0.885, 0.060),
    "onAccentContainer": (0.300, 0.080),
    "guideCellPast": (0.955, 0.010),
    "run": (0.900, 0.045),
}

# key, name, ground hue, neutral chroma scale, accent (L, C, hue or None for the ground's),
# light?, display font, scanlines, text scale.
THEMES = [
    ("velvet", "Velvet", 22, 1.2, (0.70, 0.19, None), False, "CONDENSED", False, 1.0),
    ("ember", "Ember", 45, 1.1, (0.75, 0.16, None), False, "CONDENSED", False, 1.0),
    ("amber", "Amber", 75, 1.2, (0.82, 0.16, None), False, "MONO", True, 1.0),
    ("moss", "Moss", 125, 1.0, (0.84, 0.17, None), False, "CONDENSED", False, 1.0),
    ("phosphor", "Phosphor", 148, 1.3, (0.86, 0.22, None), False, "MONO", True, 1.0),
    ("lagoon", "Lagoon", 200, 1.2, (0.82, 0.12, None), False, "CONDENSED", False, 1.0),
    ("cobalt", "Cobalt", 262, 2.2, (0.86, 0.09, 225), False, "CONDENSED", False, 1.0),
    ("plum", "Plum", 312, 1.3, (0.78, 0.14, None), False, "CONDENSED", False, 1.0),
    ("mono", "Mono", 0, 0.0, (0.97, 0.0, None), False, "CONDENSED", False, 1.0),
    ("paper", "Paper", 352, 1.0, (0.55, 0.17, None), True, "CONDENSED", False, 1.0),
    ("sepia", "Sepia", 70, 2.0, (0.52, 0.13, 45), True, "SERIF", False, 1.0),
    ("mint", "Mint", 165, 1.4, (0.50, 0.11, None), True, "CONDENSED", False, 1.0),
    ("frost", "Frost", 245, 1.6, (0.50, 0.15, None), True, "CONDENSED", False, 1.0),
    ("lilac", "Lilac", 305, 1.5, (0.50, 0.16, None), True, "CONDENSED", False, 1.0),
]

# High contrast is set by hand: pure black and white, a yellow signal, larger type.
CONTRAST = {
    "key": "contrast",
    "name": "High contrast",
    "colors": {
        "background": "#000000",
        "surface": "#1C1C1C",
        "surfaceHigh": "#2E2E2E",
        "line": "#7A7A7A",
        "text": "#FFFFFF",
        "textMuted": "#D6D6D6",
        "accent": "#FFE14D",
        "onAccent": "#000000",
        "accentContainer": "#4A4000",
        "onAccentContainer": "#FFF3B0",
        "guideCellPast": "#0E0E0E",
        "guideRuns": ["#00363A", "#3F2A00", "#14294D", "#173D12"],
    },
    "display": "CONDENSED",
    "scanlines": False,
    "scale": 1.1,
}


# ---------------------------------------------------------------- color math

def oklch_to_linear(l, c, h):
    a = c * math.cos(math.radians(h))
    b = c * math.sin(math.radians(h))
    l_ = (l + 0.3963377774 * a + 0.2158037573 * b) ** 3
    m_ = (l - 0.1055613458 * a - 0.0638541728 * b) ** 3
    s_ = (l - 0.0894841775 * a - 1.2914855480 * b) ** 3
    return (
        4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_,
        -1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_,
        -0.0041960863 * l_ - 0.7034186147 * m_ + 1.7076147010 * s_,
    )


def in_gamut(rgb):
    return all(-1e-6 <= x <= 1 + 1e-6 for x in rgb)


def oklch(l, c, h):
    """The sRGB hex of an OKLCH color, its chroma reduced until it fits sRGB."""
    while c > 0 and not in_gamut(oklch_to_linear(l, c, h)):
        c -= 0.002
    rgb = oklch_to_linear(l, max(c, 0), h)

    def encode(x):
        x = min(max(x, 0.0), 1.0)
        v = 12.92 * x if x <= 0.0031308 else 1.055 * x ** (1 / 2.4) - 0.055
        return round(v * 255)

    return "#" + "".join(f"{encode(x):02X}" for x in rgb)


def luminance(hex_color):
    v = hex_color.lstrip("#")
    channels = [int(v[i:i + 2], 16) / 255 for i in (0, 2, 4)]
    lin = [x / 12.92 if x <= 0.04045 else ((x + 0.055) / 1.055) ** 2.4 for x in channels]
    return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2]


def contrast(a, b):
    la, lb = sorted((luminance(a), luminance(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


# The floors every theme meets: (foreground, background, minimum ratio). Same as ThemeContrast.
FLOORS = [
    ("text", "background", 7.0),
    ("text", "surface", 7.0),
    ("textMuted", "background", 4.5),
    ("textMuted", "surface", 4.5),
    ("onAccent", "accent", 4.5),
    ("accent", "background", 3.0),
    ("onAccentContainer", "accentContainer", 4.5),
]


def check(key, colors):
    problems = []
    for fg, bg, floor in FLOORS:
        ratio = contrast(colors[fg], colors[bg])
        if ratio < floor:
            problems.append(f"{fg} on {bg} is {ratio:.2f}:1, under {floor}:1")
    for run in colors["guideRuns"]:
        ratio = contrast(colors["text"], run)
        if ratio < 7.0:
            problems.append(f"text on run {run} is {ratio:.2f}:1, under 7:1")
    if problems:
        raise SystemExit(f"{key}: " + "; ".join(problems))


# ---------------------------------------------------------------- themes

def build(key, name, hue, k, accent, light, display, scanlines, scale):
    ladder = LIGHT if light else DARK

    def role(name):
        l, c = ladder[name]
        return oklch(l, c * k, hue)

    a_l, a_c, a_h = accent
    accent_hue = hue if a_h is None else a_h
    accent_hex = oklch(a_l, a_c, accent_hue)
    container_l, container_c = ladder["accentContainer"]
    on_container_l, on_container_c = ladder["onAccentContainer"]
    # The container steps keep the accent's own strength: a quiet accent gets a quiet container.
    strength = min(a_c / 0.17, 1.2) if a_c > 0 else 0
    run_l, run_c = ladder["run"]
    runs = [oklch(run_l, run_c, (hue + offset) % 360) for offset in RUN_OFFSETS]
    if k == 0:
        # Achromatic: the runs keep a trace of hue so neighbours still read apart.
        runs = [oklch(run_l, 0.03, (hue + offset) % 360) for offset in RUN_OFFSETS]
    colors = {
        "background": role("background"),
        "surface": role("surface"),
        "surfaceHigh": role("surfaceHigh"),
        "line": role("line"),
        "text": role("text"),
        "textMuted": role("textMuted"),
        "accent": accent_hex,
        "onAccent": oklch(0.985, 0.005 * k, hue) if light else role("background"),
        "accentContainer": oklch(container_l, container_c * strength, accent_hue),
        "onAccentContainer": oklch(on_container_l, on_container_c * strength, accent_hue),
        "guideCellPast": role("guideCellPast"),
        "guideRuns": runs,
    }
    return {"key": key, "name": name, "colors": colors, "display": display, "scanlines": scanlines, "scale": scale}


def spec_json(theme):
    colors = theme["colors"]
    lines = ["{"]
    lines.append(f'    "key": "{theme["key"]}",')
    lines.append(f'    "name": "{theme["name"]}",')
    lines.append('    "version": 1,')
    lines.append('    "colors": {')
    entries = []
    for role, value in colors.items():
        if role == "guideRuns":
            entries.append(f'        "guideRuns": [{", ".join(json.dumps(v) for v in value)}]')
        else:
            entries.append(f'        "{role}": "{value}"')
    lines.append(",\n".join(entries))
    lines.append("    },")
    lines.append('    "typography": {')
    lines.append(f'        "display": "{theme["display"]}",')
    lines.append('        "body": "SANS",')
    lines.append(f'        "scale": {theme["scale"]:.1f}')
    lines.append("    },")
    lines.append('    "guide": {')
    lines.append('        "density": "COMFORTABLE"')
    lines.append("    },")
    lines.append('    "effects": {')
    lines.append(f'        "scanlines": {"true" if theme["scanlines"] else "false"},')
    lines.append('        "scanlineAlpha": 0.14')
    lines.append("    }")
    lines.append("}")
    return "\n".join(lines) + "\n"


def main():
    with open(os.path.join(OUT, "rose.json"), encoding="utf-8") as f:
        rose = json.load(f)
    check("rose", rose["colors"])
    themes = [build(*recipe) for recipe in THEMES] + [CONTRAST]
    for theme in themes:
        check(theme["key"], theme["colors"])
        with open(os.path.join(OUT, f'{theme["key"]}.json'), "w", encoding="utf-8", newline="\n") as f:
            f.write(spec_json(theme))
        c = theme["colors"]
        print(f'{theme["key"]:10} ground {c["background"]}  text {c["text"]}  accent {c["accent"]}  '
              f'focus {contrast(c["onAccent"], c["accent"]):.1f}:1  muted {contrast(c["textMuted"], c["background"]):.1f}:1')


if __name__ == "__main__":
    main()
