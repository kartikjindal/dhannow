"""APK size gate (CLAUDE.md: under 12 MB per ABI). Also writes SHA256SUMS next to the files.

  python scripts/ci/apk_size_gate.py app/build/app/outputs/flutter-apk [max-mb=12]

"MB" is 1,000,000 bytes, the unit the download page shows (docs/brand.json app.apk.sizeMb).
"""
import hashlib
import sys
from pathlib import Path


def main(argv):
    folder = Path(argv[1])
    limit_mb = float(argv[2]) if len(argv) > 2 else 12.0
    apks = sorted(p for p in folder.glob("app-*-release.apk"))
    if not apks:
        print(f"::error::no split release APKs in {folder}")
        return 1
    failed = False
    sums = []
    for apk in apks:
        data = apk.read_bytes()
        mb = len(data) / 1_000_000
        digest = hashlib.sha256(data).hexdigest()
        sums.append(f"{digest}  {apk.name}")
        ok = mb < limit_mb
        failed |= not ok
        print(f"{'ok ' if ok else 'BIG'} {mb:6.2f} MB  {apk.name}  sha256={digest}")
        if not ok:
            print(f"::error::{apk.name} is {mb:.2f} MB, over the {limit_mb:.0f} MB budget")
    (folder / "SHA256SUMS").write_text("\n".join(sums) + "\n", encoding="utf-8")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
