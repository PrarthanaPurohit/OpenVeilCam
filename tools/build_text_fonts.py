"""
Pins the Hanken Grotesk and JetBrains Mono variable fonts into the static weights the
OpenVeil type scale actually uses, and subsets them to Latin.

Why static instances rather than shipping the variable font once: Compose selects a face
from a FontFamily by FontWeight, and only applies a `wght` axis value when given explicit
variation settings, which the multiplatform resources Font() overload does not take. A
variable font registered under three weights therefore renders all three at its default
instance (or synthetically emboldened). Pinning removes that whole class of bug.

Usage:  python tools/build_text_fonts.py
Requires: pip install fonttools brotli
"""
from __future__ import annotations

import subprocess
import sys
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
FONT_DIR = REPO / "composeApp" / "src" / "commonMain" / "composeResources" / "font"

SOURCES = {
    "hankengrotesk": (
        "https://github.com/google/fonts/raw/main/ofl/hankengrotesk/"
        "HankenGrotesk%5Bwght%5D.ttf",
        # (weight, output suffix) -- matches the OpenVeil type scale
        [(400, "regular"), (600, "semibold"), (700, "bold")],
    ),
    "jetbrainsmono": (
        "https://github.com/google/fonts/raw/main/ofl/jetbrainsmono/"
        "JetBrainsMono%5Bwght%5D.ttf",
        [(400, "regular"), (500, "medium")],
    ),
}

# Latin-1 plus the punctuation and symbols the UI uses (bullet, middot, multiplication
# sign for "4032 x 3024", degree, ellipsis, arrows). Dropping the rest of the Unicode
# coverage is where most of the size saving comes from.
UNICODES = "U+0020-007E,U+00A0-00FF,U+2010-2015,U+2018-201F,U+2022,U+2026,U+00B7,U+00D7,U+00B0,U+2192,U+2713,U+2717"


def run(cmd: list[str]) -> None:
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"command failed: {' '.join(cmd)}\n{result.stdout}\n{result.stderr}")


def main() -> None:
    FONT_DIR.mkdir(parents=True, exist_ok=True)
    cache_dir = REPO / "tools" / ".fontcache"
    cache_dir.mkdir(exist_ok=True)

    for family, (url, weights) in SOURCES.items():
        source = cache_dir / f"{family}_variable.ttf"
        if not source.exists():
            print(f"downloading {family} ...")
            urllib.request.urlretrieve(url, source)

        for weight, suffix in weights:
            instanced = cache_dir / f"_{family}_{weight}.ttf"
            run([
                sys.executable, "-m", "fontTools.varLib.instancer", str(source),
                f"wght={weight}", f"--output={instanced}",
            ])
            run([
                sys.executable, "-m", "fontTools.subset", str(instanced),
                f"--unicodes={UNICODES}",
                "--no-hinting", "--desubroutinize",
                f"--output-file={FONT_DIR / f'{family}_{suffix}.ttf'}",
            ])
            instanced.unlink()

        # The variable original must not ship -- only the pinned instances above.
        stale = FONT_DIR / f"{family}_variable.ttf"
        stale.unlink(missing_ok=True)

    total = 0
    for f in sorted(FONT_DIR.glob("*.ttf")):
        size = f.stat().st_size
        total += size
        print(f"  {f.name}: {size / 1024:.1f} KB")
    print(f"  total bundled fonts: {total / 1024:.1f} KB")


if __name__ == "__main__":
    main()
