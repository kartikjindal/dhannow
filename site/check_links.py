"""Link checker for site/ (PLAN Phase 10a tests, W1, W3, W8). Run: python site/check_links.py

- every internal href/src (and location.href target) in every site page resolves to a file, and every
  #fragment to an id on that page; extensionless paths (/app/cb/esign, /app/open) are served as .html;
- every page the app links to (app/lib: 'terms.html', '${Brand.site}/privacy.html#retention', ...) exists;
- every callback path the app sends to a provider ('$callbackBase/digilocker', ...) has a page under
  site/app/cb/ and sits inside the backend's redirect allowlist;
- the backend navix.mobile.legal map (GET /api/app/config legal[]) only names existing pages, its keys are
  the page names the app looks up, and it covers every page the app opens;
- assetlinks.json names the app's applicationId and the app-link host is the site.
"""
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

import build_brand as bb
import doc_versions

SITE, REPO = bb.SITE, bb.REPO
SKIP = re.compile(r"^(https?:|mailto:|tel:|intent:|data:|javascript:|//)", re.I)
REF = re.compile(r'(?<![\w-])(?:href|src)="([^"]*)"|location\.href\s*=\s*\'([^\']+)\'')


def resolve(page_path, url):
    """The site file a relative or root-relative URL lands on, or None for a link that leaves the site."""
    parts = urlsplit(url)
    path = parts.path
    if not path:
        return page_path, parts.fragment
    target = (SITE / path.lstrip("/")) if path.startswith("/") else (page_path.parent / path)
    target = Path(*[p for p in target.resolve().parts])
    if target.is_dir():
        target = target / "index.html"
    elif not target.suffix:
        target = target.with_suffix(".html")
    return target, parts.fragment


def ids(path, cache={}):
    if path not in cache:
        cache[path] = set(re.findall(r'\bid="([^"]+)"', bb.read(path))) if path.suffix == ".html" else set()
    return cache[path]


def check_site(errors):
    for page in bb.site_pages():
        rel = page.relative_to(SITE).as_posix()
        for m in REF.finditer(bb.read(page)):
            url = m.group(1) if m.group(1) is not None else m.group(2)
            if url in ("", "#") or SKIP.match(url):
                continue
            target, frag = resolve(page, url)
            if not str(target).startswith(str(SITE.resolve())):
                errors.append(f"{rel}: {url} leaves site/")
            elif not target.exists():
                errors.append(f"{rel}: broken link {url}")
            elif frag and frag not in ids(target):
                errors.append(f"{rel}: {url} has no id=\"{frag}\"")


def app_pages():
    found = {}
    for path in (REPO / "app" / "lib").rglob("*.dart"):
        for m in re.finditer(r"(?:'|Brand\.site\}/)([a-z0-9-]+\.html(?:#[\w-]+)?)", bb.read(path)):
            found.setdefault(m.group(1), path.relative_to(REPO).as_posix())
    return found


def check_app(errors):
    pages = app_pages()
    for url, src in sorted(pages.items()):
        target, frag = resolve(SITE / "index.html", url)
        if not target.exists():
            errors.append(f"{src}: links to {url}, which site/ does not have")
        elif frag and frag not in ids(target):
            errors.append(f"{src}: links to {url}, which has no id=\"{frag}\"")

    http_api = bb.read(REPO / "app" / "lib" / "core" / "api" / "http_api.dart")
    base = re.search(r"const callbackBase = '([^']+)'", http_api).group(1)
    allow = bb.yml_default(bb.read(bb.YML), "NAVIX_REDIRECT_ALLOWLIST") or ""
    if not any(f"{base}/".startswith(a) for a in allow.split(",")):
        errors.append(f"app callbackBase {base} is outside the backend redirect allowlist {allow!r}")
    site = bb.load()["website"]
    if not base.startswith(site + "/"):
        errors.append(f"app callbackBase {base} is not on {site}")
    for name in sorted(set(re.findall(r"\$callbackBase/(\w+)", http_api))):
        if not (SITE / "app" / "cb" / f"{name}.html").exists():
            errors.append(f"app returns providers to {base}/{name}, but site/app/cb/{name}.html is missing")
    return pages


def legal_map():
    """navix.mobile.legal entries from the backend yml: [(key, path, version or None)]."""
    out = []
    for m in re.finditer(r"^\s*- \{ key: ([\w-]+), title: [^,]+, path: (/[^ ,}]+)(?:, version: ['\"]?([^'\" }]+)['\"]?)? \}", bb.read(bb.YML), re.M):
        out.append((m.group(1), m.group(2), m.group(3)))
    return out


def check_backend(errors, app_links):
    legal = legal_map()
    if not legal:
        errors.append("application.yml: navix.mobile.legal not found")
    site_base = bb.yml_default(bb.read(bb.YML), "NAVIX_SITE_BASE_URL")
    if site_base != bb.load()["website"]:
        errors.append(f"application.yml NAVIX_SITE_BASE_URL default {site_base!r} is not {bb.load()['website']}")
    keys = set()
    recorded = doc_versions.load()
    for key, path, version in legal:
        keys.add(key)
        want = recorded.get(path.lstrip("/"), {}).get("version")
        if version is not None and version != want:
            errors.append(f"navix.mobile.legal {key}: version {version} but site/doc-versions.json says {want}")
        if not (SITE / path.lstrip("/")).exists():
            errors.append(f"navix.mobile.legal {key}: {path} does not exist in site/")
        if Path(path).stem != key:
            errors.append(f"navix.mobile.legal {key}: the app looks pages up by name, so the key must be {Path(path).stem}")
    for url in app_links:
        stem = url.split("#")[0][:-5]
        if stem not in keys:
            errors.append(f"the app opens {url}, but navix.mobile.legal has no '{stem}' entry")


def check_assetlinks(errors):
    brand = bb.load()
    try:
        data = json.loads(bb.read(bb.ASSETLINKS))
        pkg = data[0]["target"]["package_name"]
    except (ValueError, KeyError, IndexError) as e:
        errors.append(f".well-known/assetlinks.json is not a valid statement list: {e}")
        return
    gradle = next((REPO / "app" / "android" / "app").glob("build.gradle*"))
    app_id = re.search(r'applicationId\s*=?\s*"([^"]+)"', bb.read(gradle)).group(1)
    if pkg != app_id or pkg != brand["app"]["package"]:
        errors.append(f"assetlinks package {pkg} vs app applicationId {app_id} vs brand.json {brand['app']['package']}")
    manifest = bb.read(REPO / "app" / "android" / "app" / "src" / "main" / "AndroidManifest.xml")
    for host in set(re.findall(r'android:host="([^"]+)"', manifest)):
        if host != brand["site"]:
            errors.append(f"AndroidManifest app-link host {host} is not {brand['site']}")


def main():
    errors = []
    check_site(errors)
    app_links = check_app(errors)
    check_backend(errors, app_links)
    check_assetlinks(errors)
    for e in errors:
        print("FAIL", e)
    if errors:
        return 1
    print(f"links ok ({len(bb.site_pages())} pages, {len(app_links)} app links, {len(legal_map())} legal entries)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
