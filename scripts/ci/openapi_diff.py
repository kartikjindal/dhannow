"""Contract gate (PLAN Phase 12): the exported OpenAPI document must equal the committed one.

  python scripts/ci/openapi_diff.py <exported.json> [committed=docs/api/openapi.json]

Both files are normalised (sorted keys, servers dropped) before comparing, so only a real API
change shows up. A missing committed file is a warning, not a failure: commit the exported
artifact as docs/api/openapi.json to turn the gate on. To accept a reviewed change, replace the
committed file with the exported one in the same PR.
"""
import difflib
import json
import sys
from pathlib import Path


def normalise(path):
    doc = json.loads(Path(path).read_text(encoding="utf-8"))
    doc.pop("servers", None)  # carries the CI host and port
    return json.dumps(doc, indent=2, sort_keys=True, ensure_ascii=False).splitlines()


def paths(lines):
    return set(json.loads("\n".join(lines)).get("paths", {}))


def main(argv):
    exported = argv[1]
    committed = argv[2] if len(argv) > 2 else "docs/api/openapi.json"
    if not Path(committed).exists():
        print(f"::warning::{committed} is not committed yet, so the contract gate is off. "
              f"Commit the openapi artifact as {committed} to turn it on.")
        return 0
    new, old = normalise(exported), normalise(committed)
    if new == old:
        print("openapi: no change")
        return 0
    added, removed = paths(new) - paths(old), paths(old) - paths(new)
    for p in sorted(added):
        print(f"+ path {p}")
    for p in sorted(removed):
        print(f"- path {p}")
    diff = list(difflib.unified_diff(old, new, committed, exported, lineterm="", n=2))
    print("\n".join(diff[:400]))
    if len(diff) > 400:
        print(f"... {len(diff) - 400} more diff lines (see the openapi artifact)")
    print(f"::error::The API changed. Review it, then commit the exported document as {committed}.")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
