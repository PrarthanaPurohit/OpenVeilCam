"""
Builds the tiny Material Symbols font OpenVeil ships, plus the Kotlin codepoint map.

The upstream variable font is ~10.6 MB and carries >4000 icons. OpenVeil uses about
thirty, so shipping it whole would dwarf the rest of the APK. This subsets it down to
just the glyphs we reference and pins the variable axes into two static instances --
outlined (FILL=0) and filled (FILL=1) -- because the design uses both.

Addressing icons by codepoint rather than by ligature keeps the font's GSUB table out of
the picture entirely, which is what makes the subset this small.

Usage:  python tools/subset_icons.py
Requires: pip install fonttools brotli
"""
from __future__ import annotations

import subprocess
import sys
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FONT_DIR = REPO / "composeApp" / "src" / "commonMain" / "composeResources" / "font"
KOTLIN_OUT = (
    REPO / "composeApp" / "src" / "commonMain" / "kotlin" / "com" / "openveil" / "ui"
    / "components" / "OpenVeilIcon.kt"
)

SOURCE_FONT = FONT_DIR / "materialsymbols_outlined.ttf"
_VARIABLE_FONT_BASE = (
    "https://github.com/google/material-design-icons/raw/master/variablefont/"
    "MaterialSymbolsOutlined%5BFILL%2CGRAD%2Copsz%2Cwght%5D"
)
CODEPOINTS_URL = f"{_VARIABLE_FONT_BASE}.codepoints"
SOURCE_FONT_URL = f"{_VARIABLE_FONT_BASE}.ttf"

# Every icon the OpenVeil screens reference. Keep this list in sync with usage --
# an icon not listed here will render as a blank box.
ICONS = [
    # camera screen
    "close", "flash_off", "flash_on", "flash_auto", "flip_camera_ios",
    # review / provenance
    "verified", "shield_lock", "hourglass_empty", "check", "check_circle", "replay",
    "publish",
    # publishing pipeline
    "sync", "cloud_done", "public", "cloud_off",
    # home / nav
    "photo_camera", "home", "person", "camera",
    # details
    "expand_more", "content_copy", "arrow_back", "ios_share", "image", "share", "info",
    "vpn_key", "key",
    # nostr links -- open_in_new marks a row that leaves the app for a browser
    "open_in_new", "link", "arrow_forward",
    # states
    "error", "warning", "refresh", "settings", "info_i",
]


def load_codepoints() -> dict[str, int]:
    cache = REPO / "tools" / ".codepoints.cache"
    if not cache.exists():
        print("downloading codepoints ...")
        urllib.request.urlretrieve(CODEPOINTS_URL, cache)
    mapping: dict[str, int] = {}
    for line in cache.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        name, _, hexcode = line.partition(" ")
        mapping[name] = int(hexcode, 16)
    return mapping


def run(cmd: list[str]) -> None:
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"command failed: {' '.join(cmd)}\n{result.stdout}\n{result.stderr}")


def main() -> None:
    if not SOURCE_FONT.exists():
        # The 10.6 MB original is deleted at the end of every run so it never reaches the
        # APK, which means a re-run has to fetch it again. Without this the script works
        # exactly once, and adding an icon later fails with a confusing error.
        print(f"downloading source font ({SOURCE_FONT.name}) ...")
        urllib.request.urlretrieve(SOURCE_FONT_URL, SOURCE_FONT)

    all_codepoints = load_codepoints()
    missing = [name for name in ICONS if name not in all_codepoints]
    if missing:
        sys.exit(f"unknown icon names (not in Material Symbols): {missing}")

    resolved = {name: all_codepoints[name] for name in ICONS}
    unicodes = ",".join(f"U+{cp:04X}" for cp in sorted(set(resolved.values())))

    subset_tmp = FONT_DIR / "_subset_variable.ttf"
    run([
        sys.executable, "-m", "fontTools.subset", str(SOURCE_FONT),
        f"--unicodes={unicodes}",
        "--layout-features=",           # codepoint addressing: no ligatures needed
        "--no-hinting",
        "--desubroutinize",
        f"--output-file={subset_tmp}",
    ])

    # Pin the variable axes. FILL is the only axis the design actually varies.
    for fill, out_name in ((0, "symbols_outlined.ttf"), (1, "symbols_filled.ttf")):
        run([
            sys.executable, "-m", "fontTools.varLib.instancer", str(subset_tmp),
            f"FILL={fill}", "wght=400", "GRAD=0", "opsz=24",
            f"--output={FONT_DIR / out_name}",
        ])

    subset_tmp.unlink()
    SOURCE_FONT.unlink(missing_ok=True)  # the 10.6 MB original must not ship

    emit_kotlin(resolved)

    for f in sorted(FONT_DIR.glob("*.ttf")):
        print(f"  {f.name}: {f.stat().st_size / 1024:.1f} KB")


def emit_kotlin(resolved: dict[str, int]) -> None:
    def const_name(icon: str) -> str:
        return "".join(part.capitalize() for part in icon.split("_"))

    lines = [
        "package com.openveil.ui.components",
        "",
        "/**",
        " * Codepoints for the subset Material Symbols font shipped in composeResources.",
        " *",
        " * GENERATED by tools/subset_icons.py -- do not edit by hand. To add an icon, add",
        " * its name to ICONS in that script and re-run it, otherwise the glyph will not be",
        " * present in the subset font and will render as a blank box.",
        " */",
        "enum class OpenVeilIcon(val code: Char) {",
    ]
    for icon in ICONS:
        lines.append(f"    {const_name(icon)}('\\u{resolved[icon]:04x}'),")
    lines.append("}")
    lines.append("")

    KOTLIN_OUT.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN_OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {KOTLIN_OUT.relative_to(REPO)} ({len(ICONS)} icons)")


if __name__ == "__main__":
    main()
