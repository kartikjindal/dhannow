"""Spread docs/brand.json (the one brand config, PLAN Phase 10a W5) to every copy. Run: python site/build_brand.py

- site/*.html, site/app/**/*.html: elements marked data-brand="key" get the value as their text,
  links marked data-brand-href="key" get it as their href (tel:/mailto: for phones and emails), and
  blocks marked data-dist="apk|play" are shown only for the current app.distribution (W4).
- site/.well-known/assetlinks.json (W3) from app.package + app.signingCertSha256.
- web/src/lib/brand.ts and app/lib/core/brand.dart (same exported names and shape as before).
- backend navix-app application.yml: the navix.brand.* / navix.grievance.* defaults.

check_brand.py runs the same functions and fails if any file differs from what this writes.
"""
import html
import json
import re
import sys
import urllib.parse
from pathlib import Path

SITE = Path(__file__).resolve().parent
REPO = SITE.parent
BRAND_JSON = REPO / "docs" / "brand.json"
WEB_TS = REPO / "web" / "src" / "lib" / "brand.ts"
APP_DART = REPO / "app" / "lib" / "core" / "brand.dart"
YML = REPO / "backend" / "navix-app" / "src" / "main" / "resources" / "application.yml"
ASSETLINKS = SITE / ".well-known" / "assetlinks.json"

# navix-app application.yml env var -> brand.json key (only the brand, support and grievance block).
YML_DEFAULTS = {
    "NAVIX_BRAND_NAME": "name",
    "NAVIX_BRAND_WEBSITE": "documentWebsite",
    "NAVIX_SUPPORT_PHONE": "support.phone",
    "NAVIX_SUPPORT_EMAIL": "support.email",
    "NAVIX_GRIEVANCE_NAME": "grievance.name",
    "NAVIX_GRIEVANCE_PHONE": "grievance.phone",
    "NAVIX_GRIEVANCE_EMAIL": "grievance.email",
}
# Defaults that live outside that block: checked by check_brand.py, never written here.
YML_CHECK_ONLY = {
    "NAVIX_CONTACT_RECIPIENT": "support.email",
    "NAVIX_LENDER_LEGAL_NAME": "lender.legalName",
    "NAVIX_LENDER_ADDRESS": "lender.address",
    "NAVIX_LENDER_REGISTRATION": "lender.registration",
    "NAVIX_LENDER_CONTACT": "lender.contact",
}


def read(path):
    return path.read_text(encoding="utf-8")


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def load():
    return json.loads(read(BRAND_JSON))


def get(brand, key):
    value = brand
    for part in key.split("."):
        if not isinstance(value, dict) or part not in value:
            raise KeyError(f"docs/brand.json has no '{key}'")
        value = value[part]
    return value


def is_placeholder(value):
    return isinstance(value, str) and value.startswith("[")


def phone_display(p):
    return f"{p[:5]} {p[5:]}" if len(p) == 10 else p


def text_value(brand, key):
    value = get(brand, key)
    if key.endswith(".phone"):
        return phone_display(value)
    return str(value)


def href_value(brand, key):
    value = get(brand, key)
    if is_placeholder(value):
        return "#"  # e.g. no APK URL yet: the button stays on the page
    if key.endswith(".phone"):
        return f"tel:+91{value}"
    if key.endswith(".email") or key == "generalEmail":
        return f"mailto:{value}"
    return value


TEXT_RE = re.compile(r'<(\w+)([^>]*\sdata-brand="([\w.]+)"[^>]*)>(.*?)</\1>', re.S)
HREF_RE = re.compile(r'<a\s[^>]*\bdata-brand-href="([\w.]+)"[^>]*>')
DIST_RE = re.compile(r'<(\w+)(\s[^>]*\bdata-dist="(\w+)"[^>]*)>')
INTENT_RE = re.compile(r'<a\s[^>]*\bdata-app-intent="([^"]+)"[^>]*>')


def app_intent(brand, path, fallback=None):
    """An Android intent:// link that opens the app on https://<site><path> (W3/W8); with a fallback page
    Chrome goes there when the app is not installed."""
    extra = ""
    if fallback:
        extra = "S.browser_fallback_url=" + urllib.parse.quote(f"{brand['website']}/{fallback}", safe="") + ";"
    return f"intent://{brand['site']}{path}#Intent;scheme=https;package={brand['app']['package']};{extra}end"


def inject(page, brand):
    """The page with every data-brand / data-brand-href / data-dist marker filled from brand.json."""
    def text(m):
        return f"<{m.group(1)}{m.group(2)}>{html.escape(text_value(brand, m.group(3)), quote=False)}</{m.group(1)}>"

    def href(m):
        tag, url = m.group(0), html.escape(href_value(brand, m.group(1)))
        return re.sub(r'\shref="[^"]*"', f' href="{url}"', tag) if ' href="' in tag else tag[:-1] + f' href="{url}">'

    def dist(m):
        attrs = re.sub(r"\shidden(?=[\s>]|$)", "", m.group(2))
        if m.group(3) != brand["app"]["distribution"]:
            attrs += " hidden"
        return f"<{m.group(1)}{attrs}>"

    def intent(m):
        tag = m.group(0)
        fb = re.search(r'\sdata-fallback="([^"]+)"', tag)
        url = html.escape(app_intent(brand, m.group(1), fb.group(1) if fb else None))
        return re.sub(r'\shref="[^"]*"', f' href="{url}"', tag) if ' href="' in tag else tag[:-1] + f' href="{url}">'

    return DIST_RE.sub(dist, INTENT_RE.sub(intent, HREF_RE.sub(href, TEXT_RE.sub(text, page))))


def site_pages():
    return sorted(p for p in SITE.rglob("*.html") if p.name != "page_template.html")


def assetlinks(brand):
    return json.dumps([{
        "relation": ["delegate_permission/common.handle_all_urls"],
        "target": {
            "namespace": "android_app",
            "package_name": brand["app"]["package"],
            "sha256_cert_fingerprints": brand["app"]["signingCertSha256"],
        },
    }], indent=2) + "\n"


def web_ts(b):
    s = json.dumps
    return f"""/**
 * GENERATED from docs/brand.json by site/build_brand.py (PLAN Phase 10a W5). Do not edit: change
 * docs/brand.json, run `python site/build_brand.py`; `python site/check_brand.py` fails if this differs.
 * The one brand config for the staff console. The lender of record is a backend config value and is
 * never named here.
 */
export const BRAND = {{
  name: {s(b["name"])},
  wordmark: {s(b["wordmark"])},
  fileSlug: {s(b["fileSlug"])},
  website: {s(b["website"])},
  staffUrl: {s(b["staffUrl"])},
  support: {{ phone: {s(b["support"]["phone"])}, email: {s(b["support"]["email"])} }},
  grievanceOfficer: {{ name: {s(b["grievance"]["name"])}, phone: {s(b["grievance"]["phone"])}, email: {s(b["grievance"]["email"])} }},
}} as const;
"""


def dart_str(v):
    return "'" + v.replace("\\", "\\\\").replace("'", "\\'").replace("$", "\\$") + "'"


def app_dart(b):
    rows = [
        ("name", b["name"]), ("wordmark", b["wordmark"]), ("tagline", b["tagline"]), ("site", b["site"]),
        ("supportPhone", b["support"]["phone"]), ("supportEmail", b["support"]["email"]),
        ("supportHours", b["support"]["hours"]), ("grievanceName", b["grievance"]["name"]),
        ("grievancePhone", b["grievance"]["phone"]), ("grievanceEmail", b["grievance"]["email"]),
    ]
    consts = "\n".join(f"  static const {k} = {dart_str(v)};" for k, v in rows)
    return f"""/// GENERATED from docs/brand.json by site/build_brand.py (PLAN Phase 10a W5). Do not edit: change
/// docs/brand.json, run `python site/build_brand.py`; `python site/check_brand.py` fails if this differs.
/// One brand config (CLAUDE.md "One brand config"). Every screen that shows a contact detail reads it
/// from here (the fallback for GET /api/app/config); never copy these by hand. The lender of record is
/// a server config value.
abstract final class Brand {{
{consts}
}}
"""


def yml_default(text, env):
    m = re.search(r"\$\{" + env + r":([^}]*)\}", text)
    return m.group(1) if m else None


def yml(text, brand):
    for env, key in YML_DEFAULTS.items():
        value = str(get(brand, key))
        if any(c in value for c in "}{#") or value != value.strip():
            raise SystemExit(f"{key} cannot be a yml default as is: {value!r}")
        text, n = re.subn(r"(\$\{" + env + r":)[^}]*(\})", lambda m: m.group(1) + value + m.group(2), text)
        if n != 1:
            raise SystemExit(f"application.yml: expected one ${{{env}:...}}, found {n}")
    return text


def outputs(brand):
    """Every file this script owns, mapped to its expected content."""
    out = {p: inject(read(p), brand) for p in site_pages()}
    out[ASSETLINKS] = assetlinks(brand)
    out[WEB_TS] = web_ts(brand)
    out[APP_DART] = app_dart(brand)
    out[YML] = yml(read(YML), brand)
    return out


def main():
    brand = load()
    changed = 0
    for path, text in outputs(brand).items():
        if not path.exists() or read(path) != text:
            write(path, text)
            changed += 1
            print("wrote", path.relative_to(REPO).as_posix())
    print(f"brand: {changed} file(s) updated")


if __name__ == "__main__":
    sys.exit(main())
