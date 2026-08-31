#!/usr/bin/env python3
"""Fail if any 64-bit native library in an APK is not 16 KB page aligned.

Android 15 introduced 16 KB memory pages, and from 1 November 2025 Google Play rejects
apps targeting Android 15+ whose native libraries have smaller LOAD segment alignment.
This reproduces that check by reading the ELF program headers directly, so a dependency
bump that quietly reintroduces 4 KB alignment fails in CI rather than at submission.

It is not hypothetical: secp256k1-kmp below 0.19.0 shipped 4 KB-aligned libraries and
this project was affected. See docs/SECURITY.md.

32-bit ABIs are exempt -- the requirement applies only to 64-bit devices.

Usage:  python3 tools/check_16kb_alignment.py <path-to-apk>
"""
import struct
import sys
import zipfile

REQUIRED_ALIGNMENT = 16 * 1024
PT_LOAD = 1
ELF_MAGIC = b"\x7fELF"
ELFCLASS64 = 2


def load_segment_alignments(data: bytes):
    """Return p_align for every PT_LOAD segment, or None if not a 64-bit ELF."""
    if data[:4] != ELF_MAGIC or data[4] != ELFCLASS64:
        return None
    endian = "<" if data[5] == 1 else ">"
    e_phoff = struct.unpack_from(endian + "Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from(endian + "H", data, 0x36)[0]
    e_phnum = struct.unpack_from(endian + "H", data, 0x38)[0]

    alignments = []
    for i in range(e_phnum):
        offset = e_phoff + i * e_phentsize
        p_type = struct.unpack_from(endian + "I", data, offset)[0]
        if p_type == PT_LOAD:
            alignments.append(struct.unpack_from(endian + "Q", data, offset + 48)[0])
    return alignments


def main(apk_path: str) -> int:
    aligned, misaligned, skipped = [], [], []

    with zipfile.ZipFile(apk_path) as apk:
        for name in sorted(n for n in apk.namelist() if n.endswith(".so")):
            alignments = load_segment_alignments(apk.read(name))
            if alignments is None:
                skipped.append(name)          # 32-bit ABI: not subject to the requirement
                continue
            worst = min(alignments) if alignments else 0
            (aligned if worst >= REQUIRED_ALIGNMENT else misaligned).append((name, worst))

    for name, align in aligned:
        print(f"  ok    {align // 1024:>3} KB  {name}")
    for name, align in misaligned:
        print(f"  FAIL  {align // 1024:>3} KB  {name}")

    print(
        f"\n{len(aligned)} of {len(aligned) + len(misaligned)} 64-bit libraries aligned "
        f"({len(skipped)} 32-bit skipped)"
    )

    if misaligned:
        print(
            "\nNot 16 KB aligned. Google Play rejects this for Android 15+ targets.\n"
            "Usually the fix is upgrading the dependency that ships the library; if none\n"
            "exists, it must be rebuilt with -Wl,-z,max-page-size=16384.",
            file=sys.stderr,
        )
        return 1

    print("All native libraries are 16 KB page aligned.")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
