"""App contract check (PLAN Phase 12 "Contract"): every backend call the Flutter client makes must
exist, with the same HTTP method, in the backend's OpenAPI document.

  python scripts/ci/app_contract_check.py [openapi=docs/api/openapi.json] [--list]

The client builds its paths by hand (app/lib/core/api/http_api.dart, client.dart), so this reads
the Dart source: each `c.get|post|put|delete|patch('<path>' …)`, `send('<METHOD>', '<path>' …)` and
`_poll('<path>' …)` (GET). The `_a` / `_ext` getters are expanded, `${…}` / `$x` interpolations
become a wildcard segment, and `${cond ? 'a' : 'b'}` gives both paths. A missing OpenAPI file is a
warning (the backend workflow exports it; commit it as docs/api/openapi.json).
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCES = [ROOT / "app/lib/core/api/http_api.dart", ROOT / "app/lib/core/api/client.dart"]
WILD = "\0"


def read_literal(src, i):
    """Read a Dart single- or double-quoted literal starting at src[i]; return (raw body, end)."""
    q = src[i]
    j = i + 1
    out = []
    while j < len(src):
        ch = src[j]
        if ch == "\\":
            out.append(src[j:j + 2])
            j += 2
            continue
        if ch == q:
            return "".join(out), j + 1
        if ch == "$" and src[j + 1:j + 2] == "{":
            depth, k = 1, j + 2
            while k < len(src) and depth:
                if src[k] in "'\"":
                    _, k = read_literal(src, k)
                    continue
                depth += {"{": 1, "}": -1}.get(src[k], 0)
                k += 1
            out.append(src[j:k])
            j = k
            continue
        out.append(ch)
        j += 1
    raise ValueError("unterminated literal")


def expand(body, getters):
    """All concrete path shapes for one literal body; interpolations become WILD."""
    results = [""]
    i = 0
    while i < len(body):
        if body.startswith("${", i):
            depth, k = 1, i + 2
            while k < len(body) and depth:
                if body[k] in "'\"":
                    _, k = read_literal(body, k)
                    continue
                depth += {"{": 1, "}": -1}.get(body[k], 0)
                k += 1
            expr = body[i + 2:k - 1]
            m = re.fullmatch(r"\s*[^?]+\?\s*'([^']*)'\s*:\s*'([^']*)'\s*", expr)
            if m:
                results = [r + alt for r in results for alt in (m.group(1), m.group(2))]
            else:
                results = [r + WILD for r in results]
            i = k
        elif body[i] == "$":
            m = re.match(r"\$([A-Za-z_]\w*)", body[i:])
            name = m.group(1)
            if name in getters:
                results = [r + g for r in results for g in getters[name]]
            else:
                results = [r + WILD for r in results]
            i += len(m.group(0))
        else:
            results = [r + body[i] for r in results]
            i += 1
    return results


def client_calls():
    src = "\n".join(p.read_text(encoding="utf-8") for p in SOURCES)
    getters = {}
    for m in re.finditer(r"String get (_\w+)\s*(?:=>\s*|\{[^}]*?return\s*)(['\"])", src, re.S):
        body, _ = read_literal(src, m.end() - 1)
        getters[m.group(1)] = expand(body, getters)
    calls = set()
    patterns = [
        (r"\bc\.(get|post|put|delete|patch)\(\s*(['\"])", lambda m: m.group(1).upper()),
        (r"\b_poll\(\s*(['\"])", lambda m: "GET"),
        (r"\bsend\(\s*'([A-Z]+)'\s*,\s*(['\"])", lambda m: m.group(1)),
    ]
    for rx, method in patterns:
        for m in re.finditer(rx, src):
            body, _ = read_literal(src, m.end() - 1)
            for path in expand(body, getters):
                path = path.split("?")[0]
                if path.startswith("/api/"):
                    calls.add((method(m), path))
    # A path picked into a local first: `final path = cond ? '/api/a' : '/api/b'; … c.post(path …)`.
    # The local is matched only in the few lines after it, so another function's `path` is not.
    for m in re.finditer(r"\bfinal\s+(\w+)\s*=\s*([^;]*?'/api/[^;]*);", src):
        lits, rest, i = [], m.group(2), 0
        while i < len(rest):
            if rest[i] in "'\"":
                body, i = read_literal(rest, i)
                lits.extend(p for p in expand(body, getters) if p.startswith("/api/"))
            else:
                i += 1
        window = src[m.end():m.end() + 600]
        name = re.escape(m.group(1))
        use = (r"\bc\.(get|post|put|delete|patch)\(\s*" + name + r"\b"
               r"|\bsend\(\s*'([A-Z]+)'\s*,\s*" + name + r"\b")
        for u in re.finditer(use, window):
            method = (u.group(1) or u.group(2)).upper()
            for path in lits:
                calls.add((method, path.split("?")[0]))
    return sorted(calls)


def matches(client, spec):
    a, b = client.strip("/").split("/"), spec.strip("/").split("/")
    if len(a) != len(b):
        return False
    for x, y in zip(a, b):
        if x == y or (y.startswith("{") and y.endswith("}")):
            continue
        if WILD in x:
            rx = "^" + ".+".join(re.escape(part) for part in x.split(WILD)) + "$"
            if re.match(rx, y):
                continue
        return False
    return True


def main(argv):
    spec_path = Path(next((a for a in argv[1:] if not a.startswith("--")), ROOT / "docs/api/openapi.json"))
    calls = client_calls()
    if "--list" in argv:
        for method, path in calls:
            print(f"{method:6} {path.replace(WILD, '{*}')}")
    if not spec_path.exists():
        print(f"::warning::{spec_path} is not committed yet; the app contract check is off "
              f"({len(calls)} client calls found).")
        return 0
    spec = json.loads(spec_path.read_text(encoding="utf-8")).get("paths", {})
    missing = []
    for method, path in calls:
        ok = any(matches(path, p) and method.lower() in ops for p, ops in spec.items())
        if not ok:
            missing.append((method, path))
    for method, path in missing:
        print(f"::error::app calls {method} {path.replace(WILD, '{*}')}, which is not in {spec_path.name}")
    print(f"app contract: {len(calls) - len(missing)}/{len(calls)} client calls found in the API")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
