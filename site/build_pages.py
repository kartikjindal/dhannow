"""Turn the DhanNow Word documents into website pages. Run: python site/build_pages.py

Each page gets its dn-doc-version from site/doc-versions.json (W2), the brand details from docs/brand.json
(W5, via build_brand.inject), the "Extending your loan" section from site/parity/ladder.json (the W6
fixture) and, on the sample documents, the "illustrative" banner and lender placeholders (W7). After a
deliberate content change run `python site/doc_versions.py --bump <page>`.
"""
import html
import json
import re
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

import build_brand
import doc_versions

ROOT = Path(__file__).parent
OUT = ROOT
DOCS = ROOT.parent / "docs" / "product"
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"

# (output file, page title, exact title paragraph that starts it in Document (11).docx, which occurrence)
SECTIONS = [
    ("about.html", "About us", "ABOUT US", 1),
    ("loan-information.html", "Loan information", "LOAN INFORMATION", 1),
    ("how-it-works.html", "How it works", "HOW IT WORKS", 1),
    ("eligibility.html", "Loan eligibility", "LOAN ELIGIBILITY", 3),  # the doc repeats this section; the last copy is the fullest
    ("faq.html", "Frequently asked questions", "POINT 7: FREQUENTLY ASKED QUESTIONS (FAQ)", 1),
    ("contact.html", "Contact us", "POINT 8: CONTACT US", 1),
    ("terms.html", "Terms & conditions", "POINT 9: TERMS & CONDITIONS", 1),
    ("privacy.html", "Privacy policy", "POINT 10: PRIVACY POLICY", 1),
    ("repayment-policy.html", "Loan information & repayment policy", "POINT 11: LOAN INFORMATION & REPAYMENT POLICY", 1),
    ("consent.html", "Customer consent information", "12. CUSTOMER CONSENT INFORMATION", 1),
    ("grievance.html", "Grievance redressal policy", "13. GRIEVANCE REDRESSAL POLICY", 1),
    ("fair-practices.html", "Fair practices code", "14. FAIR PRACTICES CODE", 1),
    ("governance.html", "Corporate governance policy", "15. CORPORATE GOVERNANCE POLICY", 1),
    ("risk.html", "Risk management policy", "16. RISK MANAGEMENT POLICY", 1),
    ("payment-refund-policy.html", "Payment, refund & transaction dispute policy", "Payment, Refund & Transaction Dispute Policy", 1),
    ("cookie-policy.html", "Cookie policy", "Cookie Policy", 1),
    ("disclaimer.html", "Website disclaimer", "Website Disclaimer", 1),
    ("kfs.html", "Key Facts Statement (KFS) information", "Key Fact Statement (KFS) Information", 1),
    ("loan-agreement.html", "Loan agreement access", "Loan Agreement Access Policy", 1),
]
# Whole documents that become one page each.
WHOLE = [
    ("kfs-policy.html", "KFS information policy", "DHAN_NOW_KFS_Information_Policy.docx"),
    ("sanction-letter.html", "Sample sanction letter", "DHAN_NOW_Sanction_Letter_Template.docx"),
]
# Every other section title, so a section stops where the next one begins ("Actual Lender Disclosure" is not published).
STOPS = {s[2] for s in SECTIONS} | {"Actual Lender Disclosure", "DHAN NOW"}
# Internal notes, publishing checklists and the NBFC partner (removed at the business's request).
DROP_LINE = re.compile(r"^(Developer instruction|Verification note|Primary Button|Button:|PUBLICATION CONTROL|Website:)|MLB Securities|B-14\.00526", re.I)
SKIP_REST = re.compile(r"developer|checklist", re.I)


def blocks(docx):
    body = ET.fromstring(zipfile.ZipFile(docx).read("word/document.xml")).find(W + "body")
    for el in body:
        if el.tag == W + "p":
            yield para(el)
        elif el.tag == W + "tbl":
            rows = [[" ".join(t for t in (para(p)[1] for p in tc.iter(W + "p")) if t) for tc in tr.findall(W + "tc")]
                    for tr in el.findall(W + "tr")]
            yield ("table", rows)


def para(p):
    parts = []
    for node in p.iter():
        if node.tag == W + "t":
            parts.append(node.text or "")
        elif node.tag in (W + "tab", W + "br"):
            parts.append(" ")
    style = p.find(f"{W}pPr/{W}pStyle")
    style = style.get(W + "val") if style is not None else ""
    is_list = p.find(f"{W}pPr/{W}numPr") is not None or style.startswith("List")
    kind = "h2" if style in ("Heading1", "Heading2") else "h3" if style in ("Heading3", "Heading4") else "li" if is_list else "p"
    text = re.sub(r"\s+", " ", "".join(parts)).strip()
    # Many headings are plain numbered paragraphs ("3. Loan Charges", "3.2 GST", "Q4. ...") rather than Word headings.
    if kind in ("p", "li") and len(text) < 90 and not text.endswith((".", ":", ";")):
        if re.match(r"^\d+\.\s+\S", text):
            kind = "h2"
        elif re.match(r"^(\d+\.\d+\.?|Q\d+\.)\s+\S", text):
            kind = "h3"
    return (kind, text)


def render(items):
    out, in_list = [], False
    for kind, val in items:
        if kind == "li" and not in_list:
            out.append("<ul>")
            in_list = True
        if kind != "li" and in_list:
            out.append("</ul>")
            in_list = False
        if kind == "table":
            head, *rest = val
            out.append('<div class="tbl"><table><thead><tr>' + "".join(f"<th>{html.escape(c)}</th>" for c in head) + "</tr></thead><tbody>"
                       + "".join("<tr>" + "".join(f"<td>{html.escape(c)}</td>" for c in r) + "</tr>" for r in rest) + "</tbody></table></div>")
        else:
            out.append(f"<{kind}>{html.escape(val)}</{kind}>")
    if in_list:
        out.append("</ul>")
    return "\n".join(out)


def clean(items):
    """Drop internal notes; stop at the first developer/checklist heading (always the tail of a section)."""
    kept = []
    for kind, val in items:
        if kind == "table":
            if not any("MLB Securities" in c for r in val for c in r):
                kept.append((kind, val))
            continue
        if not val or DROP_LINE.search(val) or val.startswith("END OF POINT"):
            continue
        if SKIP_REST.search(val) and (kind in ("h2", "h3") or len(val) < 60):
            break
        kept.append((kind, val))
    return kept


def section(all_blocks, title, nth):
    seen, grab = 0, None
    for b in all_blocks:
        text = b[1] if b[0] not in ("table", "li") else ""  # list items repeat titles (footer link lists)
        if grab is not None:
            if text in STOPS or text.startswith("END OF POINT"):
                break
            grab.append(b)
        elif text == title:
            seen += 1
            if seen == nth:
                grab = []
    if grab is None:
        raise SystemExit(f"section not found: {title}")
    return grab


# Section ids the app links to (privacy.html#retention from Delete account, D8).
ANCHORS = {("privacy.html", "13. DATA RETENTION"): "retention"}

# W7: public samples, never a borrower's own document. The lender is the W5 config value.
ILLUSTRATIVE = {"kfs.html", "sanction-letter.html", "loan-agreement.html"}
BANNER = ('<p class="note warn"><strong>Illustrative sample.</strong> This page only shows what the document '
          'covers. Your own Key Facts Statement, sanction letter and loan agreement are made for your loan in the '
          'DhanNow app, and only those apply to you. Lender of record: <span data-brand="lender.legalName"></span>.</p>\n')
LENDER = {
    "kfs.html": [
        ("Actual Lender: [Insert the verified legal name of the lender for the relevant loan product.]", "lender.legalName"),
        ("Actual Lender&#x27;s Customer Support: [Insert verified details.]", "lender.contact"),
        ("Actual Lender&#x27;s Grievance Officer: [Insert verified details.]", "lender.contact"),
    ],
    "sanction-letter.html": [
        ("[Insert verified lender-of-record legal name]", "lender.legalName"),
        ("[Insert verified address]", "lender.address"),
    ],
    "loan-agreement.html": [
        ("Actual Lender&#x27;s Legal Name: [Insert verified legal name of the actual lender.]", "lender.legalName"),
        ("Registered Office: [Insert verified address.]", "lender.address"),
        ("Customer Support: [Insert verified contact details.]", "lender.contact"),
        ("Grievance Redressal Contact: [Insert verified details.]", "lender.contact"),
    ],
}

# The loan extension (PLAN Phase 6, collections-led) on the two pages that explain charges, inserted before
# the given heading. Figures come from the parity fixture, so they match calc.js and LoanMath.
EXTENSION_BEFORE = {
    "repayment-policy.html": "<h2>14. EMI CONVERSION",
    "loan-information.html": "<h3>12. EMI Conversion Option</h3>",
}


def inr(paise):
    r = str(paise // 100)
    head, tail = r[:-3], r[-3:]
    head = re.sub(r"(\d)(?=(\d\d)+$)", r"\1,", head)
    return "₹" + (head + "," + tail if head else tail)


def extension_section():
    fx = json.loads((ROOT / "parity" / "ladder.json").read_text(encoding="utf-8"))
    t, one = fx["terms"], fx["ladder"][0]
    ex = next(e for e in fx["extensionExamples"] if e["borrowPaise"] == one["borrowPaise"] and e["daysLate"] == 3)
    rows = "".join(f"<tr><td>{inr(r['borrowPaise'])}</td><td>{inr(r['extensionChargePaise'])}</td><td>{inr(r['latePerDayPaise'])}</td>"
                   f"<td>{inr(r['extensionChargePaise'] + 3 * r['latePerDayPaise'])}</td></tr>" for r in fx["ladder"])
    return f"""<h2 id="extending-your-loan">Extending your loan</h2>
<p class="note warn">Pending Legal and tax sign-off. These details may change before they apply.</p>
<p>If you can’t repay on your due date, our collections team may offer to extend your loan by {t['extensionDays']} days. You can’t extend a loan yourself in the app, and an extension is never automatic.</p>
<h3>How it works</h3>
<ul>
<li>A collections officer speaks to you and offers the extension.</li>
<li>A Collection Head approves it.</li>
<li>You confirm it in the DhanNow app with an OTP.</li>
<li>You pay the extension charge in the app, or the officer records your payment.</li>
<li>Our accounts team checks the payment. Your new due date then applies.</li>
</ul>
<h3>What it costs</h3>
<p>The extension charge is the same as the charges on a new {t['tenureDays']}-day loan: the processing fee (10% of the loan), GST on the fee (18% of the fee) and {t['tenureDays']} days of interest (1% a day). That is {inr(one['extensionChargePaise'])} for every {inr(one['borrowPaise'])} you borrowed.</p>
<p>If your loan is already late, the late charge up to the day you pay is added: 2% of the loan amount for each day late, for at most {t['lateCapDays']} days. For example, on a {inr(one['borrowPaise'])} loan that is 3 days late you pay {inr(one['extensionChargePaise'])} + {inr(3 * one['latePerDayPaise'])} = {inr(ex['totalPaise'])}.</p>
<p>The extension charge does not reduce your loan. You still repay the full loan amount on the new due date.</p>
<div class="tbl"><table><thead><tr><th>Loan amount</th><th>Extension charge</th><th>Late charge per day</th><th>To extend when 3 days late</th></tr></thead><tbody>{rows}</tbody></table></div>
<h3>Good to know</h3>
<ul>
<li>Your new due date is your previous due date plus {t['extensionDays']} days, not {t['extensionDays']} days from the day you pay.</li>
<li>A loan can be extended up to {t['extensionCap']} times. The app shows how many you have used, for example “Extended 1 of {t['extensionCap']}”.</li>
<li>Repaying an extended loan does not move you up to the next loan amount. You stay at the same level.</li>
</ul>
"""


def finish(out, body):
    """Page-specific additions on top of the document text."""
    if out in EXTENSION_BEFORE:
        marker = EXTENSION_BEFORE[out]
        assert body.count(marker) == 1, (out, marker)
        body = body.replace(marker, extension_section() + marker)
    for old, key in LENDER.get(out, []):
        assert old in body, (out, old)
        label = old[: old.index("[")]
        body = body.replace(old, f'{label}<span data-brand="{key}"></span>')
    for (page_name, heading), anchor in ANCHORS.items():
        if page_name == out:
            for tag in ("h2", "h3"):
                body = body.replace(f"<{tag}>{html.escape(heading)}</{tag}>", f'<{tag} id="{anchor}">{html.escape(heading)}</{tag}>')
            assert f'id="{anchor}"' in body, (out, heading)
    return body


def page(out, title, body):
    text = ((ROOT / "page_template.html").read_text(encoding="utf-8")
            .replace("{{title}}", html.escape(title))
            .replace("{{version}}", doc_versions.version_of(out))
            .replace("{{banner}}", BANNER if out in ILLUSTRATIVE else "")
            .replace("{{body}}", finish(out, body)))
    return build_brand.inject(text, build_brand.load())


def save(out, text):
    with open(OUT / out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


if __name__ == "__main__":
    doc = list(blocks(DOCS / "Document (11).docx"))
    for out, title, marker, nth in SECTIONS:
        items = clean(section(doc, marker, nth))
        assert items and not any("MLB" in str(v) for _, v in items), out
        save(out, page(out, title, render(items)))
    for out, title, src in WHOLE:
        items = [b for b in blocks(DOCS / src) if b[1] not in ("DHAN NOW",)]
        save(out, page(out, title, render(clean(items))))
    print(f"wrote {len(SECTIONS) + len(WHOLE)} pages to {OUT}")
