#!/usr/bin/env python3
"""Patch official Termux bootstrap archives for the independent app identity."""

from __future__ import annotations

import pathlib
import sys
import tempfile
import zipfile


OLD = b"com.termux"
NEW = b"com.logbox"
OLD_PREFIX = b"/data/data/com.termux/files/usr"
NEW_PREFIX = b"/data/data/com.logbox/files/usr"


def patch_archive(path: pathlib.Path) -> int:
    patched_entries = 0
    with tempfile.NamedTemporaryFile(dir=path.parent, suffix=".zip", delete=False) as tmp:
        output = pathlib.Path(tmp.name)

    try:
        with zipfile.ZipFile(path, "r") as source, zipfile.ZipFile(
            output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
        ) as target:
            for info in source.infolist():
                data = source.read(info.filename)
                patched = data.replace(OLD, NEW)
                if patched != data:
                    patched_entries += 1
                if OLD_PREFIX in patched:
                    raise RuntimeError(f"unpatched prefix remains in {path}:{info.filename}")
                target.writestr(info, patched)
        output.replace(path)
    except Exception:
        output.unlink(missing_ok=True)
        raise
    return patched_entries


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    archives = sorted((root / "app/src/main/cpp").glob("bootstrap-*.zip"))
    if len(archives) != 4:
        raise SystemExit(f"expected 4 bootstrap archives, found {len(archives)}")

    for archive in archives:
        count = patch_archive(archive)
        print(f"patched {archive.name}: {count} entries; {OLD_PREFIX.decode()} -> {NEW_PREFIX.decode()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())