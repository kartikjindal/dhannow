package com.navix.loan.pdf;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfCopy;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Layout and wording shared by the DhanNow document renderers (KFS, sanction letter, agreement,
 * schedule), so the same fact reads the same way in every document. Inter is embedded so the ₹ sign
 * renders (the base-14 PDF fonts have no ₹ glyph).
 */
final class PdfKit {

    private static final Color INK = new Color(0x0B, 0x0B, 0x0C);
    private static final Color SLATE = new Color(0x4A, 0x4D, 0x52);
    private static final Color LINE = new Color(0xE5, 0xE7, 0xE8);
    private static final Color PRIMARY = new Color(0x1F, 0x5B, 0xDB);
    private static final Color TINT = new Color(0xEE, 0xF3, 0xFF);

    private static final BaseFont REGULAR = font("Inter-Regular.ttf");
    private static final BaseFont BOLD = font("Inter-Bold.ttf");

    static final Font WORDMARK = new Font(BOLD, 16, Font.NORMAL, PRIMARY);
    static final Font TITLE = new Font(BOLD, 12, Font.NORMAL, INK);
    static final Font META = new Font(REGULAR, 8, Font.NORMAL, SLATE);
    static final Font SECTION = new Font(BOLD, 9.5f, Font.NORMAL, INK);
    static final Font LABEL = new Font(REGULAR, 8.5f, Font.NORMAL, SLATE);
    static final Font VALUE = new Font(BOLD, 8.5f, Font.NORMAL, INK);
    static final Font BODY = new Font(REGULAR, 8.5f, Font.NORMAL, INK);
    static final Font HERO_LABEL = new Font(REGULAR, 8.5f, Font.NORMAL, SLATE);
    static final Font HERO_VALUE = new Font(BOLD, 13, Font.NORMAL, INK);
    static final Font FOOTER = new Font(REGULAR, 7.5f, Font.NORMAL, SLATE);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm:ss a 'IST'", Locale.ENGLISH);
    private static final DateTimeFormatter CUTOFF = DateTimeFormatter.ofPattern("HH:mm");

    private PdfKit() {
    }

    private static BaseFont font(String file) {
        try (InputStream in = PdfKit.class.getResourceAsStream("/fonts/" + file)) {
            if (in == null) {
                throw new IllegalStateException("Missing font /fonts/" + file);
            }
            return BaseFont.createFont(file, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, in.readAllBytes(), null);
        } catch (IOException | DocumentException e) {
            throw new IllegalStateException("Cannot load font " + file, e);
        }
    }

    // ---------------------------------------------------------------- document

    interface Body {
        void write(Document doc) throws DocumentException, IOException;
    }

    /** A4 document with the brand header and the support footer around {@code body}. */
    static byte[] render(DocumentFacts f, String title, String reference, Body body) {
        Document doc = new Document(PageSize.A4, 44, 44, 40, 40);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(doc, out);
            doc.addTitle(title);
            doc.open();
            doc.add(header(f.brand().name(), title));
            doc.add(rule());
            doc.add(meta(reference));
            body.write(doc);
            doc.add(spaced(new Paragraph(" ", FOOTER), 8, 0));
            doc.add(rule());
            Paragraph footer = new Paragraph(f.brand().name() + "  ·  " + f.brand().website()
                    + "  ·  Support " + f.brand().supportPhone() + ", " + f.brand().supportEmail()
                    + "  ·  Lender: " + f.lender().legalName(), FOOTER);
            footer.setAlignment(Element.ALIGN_CENTER);
            doc.add(footer);
            doc.close();
            return out.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Failed to render " + title, e);
        }
    }

    /** Concatenate PDFs (the signing pack: sanction letter + agreement). */
    static byte[] merge(byte[]... pdfs) {
        Document doc = new Document();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfCopy copy = new PdfCopy(doc, out);
            doc.open();
            for (byte[] pdf : pdfs) {
                PdfReader reader = new PdfReader(pdf);
                for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                    copy.addPage(copy.getImportedPage(reader, page));
                }
                copy.freeReader(reader);
                reader.close();
            }
            doc.close();
            return out.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Failed to merge PDFs", e);
        }
    }

    // ---------------------------------------------------------------- blocks

    static PdfPTable header(String brand, String title) throws DocumentException {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[] {1f, 1.6f});
        t.addCell(borderless(new Phrase(brand, WORDMARK), Element.ALIGN_LEFT));
        t.addCell(borderless(new Phrase(title, TITLE), Element.ALIGN_RIGHT));
        return t;
    }

    static Paragraph meta(String text) {
        return spaced(new Paragraph(text, META), 6, 0);
    }

    static Paragraph section(String text) {
        return spaced(new Paragraph(text, SECTION), 12, 4);
    }

    static Paragraph body(String text) {
        Paragraph p = new Paragraph(text, BODY);
        p.setSpacingAfter(4);
        return p;
    }

    static PdfPTable kv(String[][] rows) throws DocumentException {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[] {1.2f, 1.5f});
        for (String[] row : rows) {
            t.addCell(kvCell(new Phrase(row[0], LABEL)));
            t.addCell(kvCell(new Phrase(safe(row[1], "—"), VALUE)));
        }
        return t;
    }

    /** The two numbers the borrower cares about, banded so they cannot be missed. */
    static PdfPTable hero(String leftLabel, String leftValue, String rightLabel, String rightValue)
            throws DocumentException {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[] {1f, 1f});
        t.setSpacingBefore(10);
        t.addCell(heroCell(leftLabel, leftValue));
        t.addCell(heroCell(rightLabel, rightValue));
        return t;
    }

    static Paragraph bullets(String... lines) {
        Paragraph block = new Paragraph();
        for (String line : lines) {
            Paragraph p = new Paragraph("•  " + line, BODY);
            p.setSpacingAfter(3);
            p.setIndentationLeft(4);
            block.add(p);
        }
        return block;
    }

    static Paragraph rule() {
        Paragraph p = new Paragraph();
        p.add(new Chunk(new LineSeparator(0.8f, 100, LINE, Element.ALIGN_CENTER, -2)));
        p.setSpacingBefore(4);
        return p;
    }

    private static PdfPCell heroCell(String label, String value) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setBackgroundColor(TINT);
        c.setPadding(10);
        Paragraph l = new Paragraph(label, HERO_LABEL);
        l.setSpacingAfter(2);
        c.addElement(l);
        c.addElement(new Paragraph(value, HERO_VALUE));
        return c;
    }

    private static PdfPCell kvCell(Phrase p) {
        PdfPCell c = new PdfPCell(p);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(LINE);
        c.setBorderWidth(0.4f);
        c.setPaddingTop(4);
        c.setPaddingBottom(4);
        return c;
    }

    private static PdfPCell borderless(Phrase p, int align) {
        PdfPCell c = new PdfPCell(p);
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(align);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    static Paragraph spaced(Paragraph p, float before, float after) {
        p.setSpacingBefore(before);
        p.setSpacingAfter(after);
        return p;
    }

    // ---------------------------------------------------------------- wording shared by every document

    static String interestLine(DocumentFacts f) {
        return pct(f.dailyRate()) + " per day on the principal, fixed, simple interest"
                + (f.interestUpfront()
                        ? " for " + f.tenureDays() + " days (" + rs(f.interestPaise()) + "), deducted upfront"
                        : ", payable with the principal");
    }

    static String dueLine(DocumentFacts f) {
        String by = "by " + CUTOFF.format(f.dueCutoff()) + " IST";
        if (f.disbursedOn() != null) {
            return date(f.dueDate()) + ", " + by;
        }
        return f.tenureDays() + " days from the disbursal date, " + by
                + (f.dueDate() == null ? "" : " (indicative: " + date(f.dueDate()) + ")");
    }

    static String repaymentLine(DocumentFacts f) {
        return "Single repayment of " + rs(f.totalRepayablePaise()) + " on the due date";
    }

    static String penalLine(DocumentFacts f) {
        if (!f.penalEnabled()) {
            return "None";
        }
        return pct(f.penalRate()) + " per day on the principal from the day after the due date, for at most "
                + f.penalCapDays() + " days (at most " + rs(f.maxPenalPaise()) + ")";
    }

    static String prepaymentLine(DocumentFacts f) {
        return f.prepayRebate()
                ? "You can repay any time before the due date, with no prepayment charge. Unused interest is refunded."
                : "You can repay any time before the due date, with no prepayment charge. The amount due stays "
                        + rs(f.totalRepayablePaise()) + ": the fee, GST and interest deducted upfront are not refunded.";
    }

    static final String COOLING_OFF = "Not applicable to this loan";

    static String beneficiaryLine(DocumentFacts f) {
        return "Pay by UPI or bank transfer only to the " + f.lender().legalName()
                + " account shown on the Repay screen of the " + f.brand().name()
                + " app. Never pay into a person's own account, and never share an OTP, password or UPI PIN.";
    }

    static String platformLine(DocumentFacts f) {
        return f.brand().name() + " is the digital platform through which this loan is offered. The lender of "
                + "this loan is " + f.lender().legalName() + ".";
    }

    static String grievanceLine(DocumentFacts f) {
        return "Grievance officer (" + f.brand().name() + "): " + f.grievance().officerName() + ", "
                + f.grievance().officerPhone() + ", " + f.grievance().officerEmail() + ". Lender: "
                + f.lender().contact() + ". If your complaint is not resolved within 30 days, or you are not "
                + "satisfied with the reply, you can complain to the RBI at cms.rbi.org.in.";
    }

    static String lenderLine(DocumentFacts f) {
        return f.lender().legalName() + ", " + f.lender().address() + ". Registration: " + f.lender().registration();
    }

    static String borrowerLine(DocumentFacts f) {
        StringBuilder b = new StringBuilder(safe(f.borrowerName(), "Borrower"));
        if (!blank(f.borrowerPan())) {
            b.append(", PAN ").append(f.borrowerPan());
        }
        if (!blank(f.borrowerMobile())) {
            b.append(", mobile ").append(f.borrowerMobile());
        }
        if (!blank(f.borrowerAddress())) {
            b.append(", ").append(f.borrowerAddress());
        }
        return b.toString();
    }

    static String loanAccount(DocumentFacts f) {
        return f.loanId() == null ? "Not yet assigned" : com.navix.common.util.CustomerIds.loanAccount(f.loanId());
    }

    // ---------------------------------------------------------------- formatting

    /** Integer paise as rupees: 74200 → "₹742", 74250 → "₹742.50" (Java groups in threes, even for en-IN). */
    static String rs(long paise) {
        NumberFormat nf = NumberFormat.getNumberInstance(new Locale("en", "IN"));
        int decimals = paise % 100 == 0 ? 0 : 2;
        nf.setMinimumFractionDigits(decimals);
        nf.setMaximumFractionDigits(decimals);
        return "₹" + nf.format(BigDecimal.valueOf(paise, 2));
    }

    /** A rate as a percentage: 0.10 → "10%", 0.0125 → "1.25%". */
    static String pct(BigDecimal rate) {
        return rate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    /** An APR already in percent: 239096.78 → "239,096.78%". */
    static String apr(BigDecimal percent) {
        NumberFormat nf = NumberFormat.getNumberInstance(new Locale("en", "IN"));
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return nf.format(percent) + "%";
    }

    static String date(LocalDate d) {
        return d == null ? "—" : DATE.format(d);
    }

    static String dateTime(Instant at) {
        return at == null ? "—" : DATE_TIME.format(at.atZone(IST));
    }

    static String safe(String v, String fallback) {
        return blank(v) ? fallback : v;
    }

    static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
