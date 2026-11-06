"""Fails if any copy of the brand details differs from docs/brand.json (PLAN Phase 10a W5), or if site copy
breaks the copy rules. Run: python site/check_brand.py [--release]

- every file build_brand.py owns (site pages, assetlinks.json, web brand.ts, app brand.dart, the backend
  yml brand/grievance defaults) is exactly what build_brand.py would write now;
- the backend lender and contact-form defaults match docs/brand.json;
- no other phone number, dhannow.com address, CIN, company name or office address appears on the site, and
  no other DhanNow number appears in app/lib or web/src;
- copy rules: no NBFC claim about DhanNow, no "DhanBoost", salary-day, 25%-of-salary or "pay early" wording;
  the site still says to apply in the Android app and that there is no login on the website (W4).
--release also refuses placeholders: lender of record, APK details (while distribution is "apk") and the
app-signing fingerprint in assetlinks.json.
"""
import html
import re
import sys

import build_brand as bb

FINGERPRINT = re.compile(r"^([0-9A-F]{2}:){31}[0-9A-F]{2}$")
BANNED = re.compile(r"dhan\s?boost|salary[- ]day|25\s?% of (your |the )?salary|pay early|save interest|per day for the days held", re.I)
NBFC_CLAIM = re.compile(r"\b(dhan\s?now|we)\s+(is|are)\s+(also\s+)?(an?\s+)?((rbi[- ])?(registered|regulated|licensed)\s+)?(nbfc|non-banking financial)", re.I)


def squash(s):
    return re.sub(r"[\W_]+", "", s).lower()


def visible(page):
    """Page text without tags, scripts and styles (attribute values such as tel: links are checked by build_brand)."""
    page = re.sub(r"<(script|style)\b.*?</\1>", " ", page, flags=re.S)
    return html.unescape(re.sub(r"<[^>]+>", " ", page))


def check(release=False):
    brand, errors = bb.load(), []

    for path, expected in bb.outputs(brand).items():
        if not path.exists() or bb.read(path) != expected:
            errors.append(f"{path.relative_to(bb.REPO).as_posix()} differs from docs/brand.json (python site/build_brand.py)")

    yml = bb.read(bb.YML)
    for env, key in {**bb.YML_DEFAULTS, **bb.YML_CHECK_ONLY}.items():
        got, want = bb.yml_default(yml, env), str(bb.get(brand, key))
        if got != want:
            errors.append(f"application.yml ${{{env}}} default is {got!r}, docs/brand.json {key} is {want!r}")

    phones = {brand["support"]["phone"], brand["grievance"]["phone"]}
    emails = {brand["support"]["email"], brand["grievance"]["email"], brand["generalEmail"]}
    company = brand["company"]
    for path in bb.site_pages():
        rel = path.relative_to(bb.REPO).as_posix()
        page = bb.read(path)
        text = visible(page)
        for m in re.finditer(r"(?<!\d)(?:\+91[\s-]?)?(9\d{4})\s?(\d{5})(?!\d)", text):
            if m.group(1) + m.group(2) not in phones:
                errors.append(f"{rel}: phone {m.group(0)!r} is not in docs/brand.json")
        for m in re.finditer(r"[\w.+-]+@dhannow\.com", page):
            if m.group(0) not in emails:
                errors.append(f"{rel}: {m.group(0)} is not in docs/brand.json")
        for m in re.finditer(r"\b[UL]\d{5}[A-Z]{2}\d{4}[A-Z]{3}\d{6}\b", text):
            if m.group(0) != company["cin"]:
                errors.append(f"{rel}: CIN {m.group(0)} is not docs/brand.json company.cin")
        for m in re.finditer(r"\b\w+(?: \w+)? Finance Private Limited\b", text):
            if m.group(0) != company["legalName"] and not m.group(0).endswith(" " + company["legalName"]):
                errors.append(f"{rel}: company {m.group(0)!r} is not docs/brand.json company.legalName")
        for m in re.finditer(r"Plot No\.[^<\n]{0,120}?\d{6}", text):
            if squash(m.group(0)) != squash(company["registeredOffice"]):
                errors.append(f"{rel}: office {m.group(0)!r} is not docs/brand.json company.registeredOffice")
        for rx in (BANNED, NBFC_CLAIM):
            for m in rx.finditer(text):
                errors.append(f"{rel}: banned wording {m.group(0)!r}")

    for root, exts in ((bb.REPO / "app" / "lib", {".dart"}), (bb.REPO / "web" / "src", {".ts", ".tsx"})):
        for path in root.rglob("*"):
            if path.suffix in exts:
                for m in re.finditer(r"(?<!\d)(92113)\s?(\d{5})(?!\d)", bb.read(path)):
                    if m.group(1) + m.group(2) not in phones:
                        errors.append(f"{path.relative_to(bb.REPO).as_posix()}: DhanNow number {m.group(0)!r} is not in docs/brand.json")

    for page, phrases in {"index.html": ("Android app", "no login"), "download.html": ("Android app", "no login on the website")}.items():
        text = visible(bb.read(bb.SITE / page))
        for p in phrases:
            if p not in text:
                errors.append(f"{page}: lost the W4 wording {p!r}")

    if release:
        for key in ("lender.legalName", "lender.address", "lender.registration", "lender.contact", "company.cin"):
            if bb.is_placeholder(bb.get(brand, key)):
                errors.append(f"release: docs/brand.json {key} is still a placeholder")
        if brand["app"]["distribution"] == "apk":
            for k, v in brand["app"]["apk"].items():
                if bb.is_placeholder(v):
                    errors.append(f"release: docs/brand.json app.apk.{k} is still a placeholder")
        if brand["app"]["distribution"] not in ("apk", "play"):
            errors.append("release: app.distribution must be apk or play")
        for fp in brand["app"]["signingCertSha256"]:
            if not FINGERPRINT.match(fp):
                errors.append(f"release: assetlinks fingerprint {fp!r} is not a SHA-256 (AA:BB:...)")
    return errors


def main(argv):
    errors = check("--release" in argv)
    for e in errors:
        print("FAIL", e)
    if errors:
        return 1
    print("brand ok")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
