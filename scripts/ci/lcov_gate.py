"""Line-coverage gate over an lcov file (PLAN Phase 12: app `core/` >= 70%).

  python scripts/ci/lcov_gate.py app/coverage/lcov.info lib/core/ 70 [--exclude lib/core/api/demo_api.dart]
"""
import sys
from pathlib import Path


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("--")]
    excludes = [argv[i + 1] for i, a in enumerate(argv) if a == "--exclude" and i + 1 < len(argv)]
    args = [a for a in args if a not in excludes]
    if len(args) < 3:
        print(__doc__)
        return 2
    lcov, prefix, minimum = args[0], args[1].replace("\\", "/"), float(args[2])
    found = hit = 0
    files = []
    current = None
    for line in Path(lcov).read_text(encoding="utf-8").splitlines():
        if line.startswith("SF:"):
            current = line[3:].replace("\\", "/")
            keep = prefix in current and not any(e in current for e in excludes)
            current = current if keep else None
            f_found = f_hit = 0
        elif line.startswith("DA:") and current:
            f_found += 1
            if int(line[3:].split(",")[1]) > 0:
                f_hit += 1
        elif line == "end_of_record" and current:
            found += f_found
            hit += f_hit
            files.append((current, f_hit, f_found))
            current = None
    if not found:
        print(f"::error::no lines under {prefix} in {lcov}")
        return 1
    pct = 100.0 * hit / found
    for name, h, f in sorted(files, key=lambda t: t[1] / max(t[2], 1)):
        print(f"{100.0 * h / max(f, 1):6.1f}%  {h:5}/{f:<5} {name}")
    print(f"{prefix}: {pct:.1f}% of {found} lines (minimum {minimum:.0f}%)")
    if pct < minimum:
        print(f"::error::{prefix} line coverage {pct:.1f}% is under {minimum:.0f}%")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
