"""Every site check (PLAN Phase 10a), for CI. Run: python site/check_all.py [--release]

  node site/calc.js                 calculator self-check
  node site/parity/calc.test.js     W6: calc.js = site/parity/ladder.json (= backend LoanMath)
  python site/check_brand.py        W5: one brand config, copy rules (--release: no placeholders)
  python site/doc_versions.py       W2: no legal page changed without a version bump
  python site/check_links.py        W1/W3/W8: no broken internal link, every app-referenced URL exists
  python site/check_theme.py        W9: site colours = app tokens
"""
import subprocess
import sys
from pathlib import Path

SITE = Path(__file__).resolve().parent


def main(argv):
    py = [sys.executable]
    steps = [
        ["node", str(SITE / "calc.js")],
        ["node", str(SITE / "parity" / "calc.test.js")],
        py + [str(SITE / "check_brand.py")] + (["--release"] if "--release" in argv else []),
        py + [str(SITE / "doc_versions.py")],
        py + [str(SITE / "check_links.py")],
        py + [str(SITE / "check_theme.py")],
    ]
    failed = [s for s in steps if subprocess.call(s, cwd=SITE.parent) != 0]
    if failed:
        print(f"{len(failed)} site check(s) failed")
        return 1
    print("all site checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
