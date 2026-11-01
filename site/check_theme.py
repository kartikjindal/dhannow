"""Theme parity (PLAN Phase 10a W9): every colour variable in a site page's :root equals the app token it
stands for (app/lib/core/theme/tokens.dart, the F2 tokens in CLAUDE.md). Run: python site/check_theme.py

A new colour variable fails until it is mapped to an app token below.
"""
import re
import sys

import build_brand as bb

# site CSS variable -> DnTokens name
TOKENS = {
    "page": "frame", "surface": "surface", "field": "tint", "ink": "ink", "soft": "slate", "line": "line",
    "cobalt": "primary", "cobalt-dark": "primary600", "cobalt-press": "primary700",
    "ok": "ok", "ok-bg": "okBg", "warn": "warn", "warn-bg": "warnBg", "err": "err", "err-bg": "errBg",
}
# CLAUDE.md "Design system" values, so the app tokens cannot drift either.
CLAUDE_MD = {"frame": "F2F3F3", "surface": "FAFAFA", "paper": "FFFFFF", "ink": "0B0B0C", "slate": "4A4D52", "line": "E5E7E8",
             "primary": "1F5BDB", "primary600": "1A4BB8", "tint": "EEF3FF", "ok": "17804F", "okBg": "E3F7EC",
             "err": "C62A2F", "errBg": "FDECEC", "warn": "8A6200", "warnBg": "FEF6D8"}
COLOR = re.compile(r"^#([0-9A-Fa-f]{6})$")


def app_tokens():
    text = bb.read(bb.REPO / "app" / "lib" / "core" / "theme" / "tokens.dart")
    return {m.group(1): m.group(2).upper() for m in re.finditer(r"static const (\w+) = Color\(0xFF([0-9A-Fa-f]{6})\)", text)}


def check():
    app, errors, seen = app_tokens(), [], 0
    for name, hexv in CLAUDE_MD.items():
        if app.get(name) != hexv:
            errors.append(f"tokens.dart {name} is {app.get(name)}, CLAUDE.md says #{hexv}")
    for page in bb.site_pages() + [bb.SITE / "page_template.html"]:
        rel = page.relative_to(bb.SITE).as_posix()
        for block in re.findall(r":root\s*\{([^}]*)\}", bb.read(page)):
            for var, value in re.findall(r"--([\w-]+)\s*:\s*([^;]+)", block):
                m = COLOR.match(value.strip())
                if not m:
                    continue  # shadows and other non-colour values
                seen += 1
                token = TOKENS.get(var)
                if token is None:
                    errors.append(f"{rel}: --{var} has no app token mapping (site/check_theme.py TOKENS)")
                elif app.get(token) != m.group(1).upper():
                    errors.append(f"{rel}: --{var} is {value.strip()}, app token {token} is #{app.get(token)}")
    return errors, seen


def main():
    errors, seen = check()
    for e in errors:
        print("FAIL", e)
    if errors:
        return 1
    print(f"theme ok ({seen} colour variables match the app tokens)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
