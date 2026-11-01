"""Versioned legal pages (PLAN Phase 10a W2).

Every page build_pages.py writes carries <meta name="dn-doc-version" content="N">. site/doc-versions.json
records each page's version and the SHA-256 of its content (the version tag itself left out). When the
user accepts Terms, Consent or the KFS, the app sends {key, version}, so a page must never change under
the same version.

  python site/doc_versions.py                  check: fails if a page changed without a version bump
  python site/doc_versions.py --bump terms.html [more.html ...]
                                               after a deliberate change: version + 1, new hash, tag rewritten
  python site/doc_versions.py --add            record pages not yet in the manifest at version 1
"""
import hashlib
import json
import re
import sys
from pathlib import Path

SITE = Path(__file__).resolve().parent
MANIFEST = SITE / "doc-versions.json"
META_RE = re.compile(r'<meta name="dn-doc-version" content="([^"]*)">\n?')
README = ("Legal page versions (PLAN Phase 10a W2). Do not edit by hand: `python site/doc_versions.py --bump <page>` "
          "after changing a page. The backend reads `version` into GET /api/app/config legal[] and stores it with consent.")


def legal_pages():
    from build_pages import SECTIONS, WHOLE  # the generated pages are the legal pages
    return sorted([s[0] for s in SECTIONS] + [w[0] for w in WHOLE])


def read(path):
    return path.read_text(encoding="utf-8")


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def content_hash(text):
    return hashlib.sha256(META_RE.sub("", text.replace("\r\n", "\n")).encode("utf-8")).hexdigest()


def load():
    if not MANIFEST.exists():
        return {}
    return json.loads(read(MANIFEST))["pages"]


def save(pages):
    write(MANIFEST, json.dumps({"_readme": README, "pages": dict(sorted(pages.items()))}, indent=2) + "\n")


def version_of(page):
    """The recorded version of a page ('1' for a page not recorded yet); build_pages writes it into the tag."""
    return load().get(page, {}).get("version", "1")


def set_meta(text, version):
    tag = f'<meta name="dn-doc-version" content="{version}">'
    return META_RE.sub(tag + "\n", text, count=1) if META_RE.search(text) else text.replace("<head>\n", "<head>\n" + tag + "\n", 1)


def check():
    pages, errors = load(), []
    for page in legal_pages():
        path = SITE / page
        if not path.exists():
            errors.append(f"{page}: missing")
            continue
        text = read(path)
        meta = META_RE.findall(text)
        rec = pages.get(page)
        if len(meta) != 1:
            errors.append(f"{page}: needs exactly one <meta name=\"dn-doc-version\">, found {len(meta)}")
        if rec is None:
            errors.append(f"{page}: not in doc-versions.json (python site/doc_versions.py --add)")
            continue
        if meta and meta[0] != rec["version"]:
            errors.append(f"{page}: tag says version {meta[0]}, doc-versions.json says {rec['version']}")
        if content_hash(text) != rec["sha256"]:
            errors.append(f"{page}: content changed but version {rec['version']} was not bumped "
                          f"(python site/doc_versions.py --bump {page})")
    for page in pages:
        if page not in legal_pages():
            errors.append(f"doc-versions.json lists {page}, which is not a legal page")
    return errors


def bump(names, add_only=False):
    pages = load()
    for page in names:
        path = SITE / page
        if page not in legal_pages() or not path.exists():
            raise SystemExit(f"not a legal page: {page}")
        if add_only and page in pages:
            continue
        version = "1" if page not in pages else str(int(pages[page]["version"]) + 1)
        text = set_meta(read(path), version)
        write(path, text)
        pages[page] = {"version": version, "sha256": content_hash(text)}
        print(f"{page}: version {version}")
    save(pages)


def main(argv):
    if argv[:1] == ["--bump"]:
        bump(argv[1:])
    elif argv[:1] == ["--add"]:
        bump(legal_pages(), add_only=True)
    errors = check()
    for e in errors:
        print("FAIL", e)
    if errors:
        return 1
    print(f"doc versions ok ({len(legal_pages())} legal pages)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
