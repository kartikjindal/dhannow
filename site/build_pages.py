"""Turn the DhanNow Word documents into website pages. Run: python3 build_pages.py"""
import html
import re
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

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


def page(title, body):
    return (ROOT / "page_template.html").read_text().replace("{{title}}", html.escape(title)).replace("{{body}}", body)


if __name__ == "__main__":
    doc = list(blocks(DOCS / "Document (11).docx"))
    for out, title, marker, nth in SECTIONS:
        items = clean(section(doc, marker, nth))
        assert items and not any("MLB" in str(v) for _, v in items), out
        (OUT / out).write_text(page(title, render(items)))
    for out, title, src in WHOLE:
        items = [b for b in blocks(DOCS / src) if b[1] not in ("DHAN NOW",)]
        (OUT / out).write_text(page(title, render(clean(items))))
    print(f"wrote {len(SECTIONS) + len(WHOLE)} pages to {OUT}")
