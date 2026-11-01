package com.navix.loan.pdf;

import static com.navix.loan.pdf.PdfKit.apr;
import static com.navix.loan.pdf.PdfKit.body;
import static com.navix.loan.pdf.PdfKit.date;
import static com.navix.loan.pdf.PdfKit.kv;
import static com.navix.loan.pdf.PdfKit.pct;
import static com.navix.loan.pdf.PdfKit.rs;
import static com.navix.loan.pdf.PdfKit.safe;
import static com.navix.loan.pdf.PdfKit.section;

import com.lowagie.text.Element;
import com.lowagie.text.Image;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The loan agreement the borrower e-signs (PLAN Phase 5): parties, the loan, repayment, charges,
 * and electronic execution. Signed in one pack with the sanction letter; the signed copy is stored as
 * the {@code AGREEMENT} loan document.
 */
@Component
public class LoanAgreementPdfRenderer {

    public byte[] render(DocumentFacts f) {
        return renderDocument(f, null, null, null);
    }

    /** The signed copy for a drawn signature: the borrower's signature with the server timestamp. */
    public byte[] renderSigned(DocumentFacts f, byte[] signaturePng, String signerName, Instant signedAt) {
        if (signaturePng == null || signaturePng.length == 0 || signedAt == null) {
            throw new IllegalArgumentException("Signature bytes and signing time are required");
        }
        return renderDocument(f, signaturePng, signerName, signedAt);
    }

    /** The signing pack the borrower e-signs: the sanction letter followed by this agreement. */
    public byte[] renderPack(byte[] sanctionLetter, DocumentFacts f) {
        return PdfKit.merge(sanctionLetter, render(f));
    }

    /** The signed pack for a drawn signature. */
    public byte[] renderSignedPack(byte[] sanctionLetter, DocumentFacts f, byte[] signaturePng, String signerName,
                                   Instant signedAt) {
        return PdfKit.merge(sanctionLetter, renderSigned(f, signaturePng, signerName, signedAt));
    }

    private byte[] renderDocument(DocumentFacts f, byte[] signaturePng, String signerName, Instant signedAt) {
        String number = "DN-LA-" + f.applicationId() + "-" + f.version();
        return PdfKit.render(f, "LOAN AGREEMENT", "Agreement no. " + number + "  ·  Document version "
                + f.version() + "  ·  Date " + date(f.issuedOn()), doc -> {
            doc.add(section("Parties"));
            doc.add(kv(new String[][] {
                    {"Lender", PdfKit.lenderLine(f)},
                    {"Borrower", PdfKit.borrowerLine(f)},
                    {"Platform", f.brand().name() + " (" + f.brand().website() + "), the lender's digital "
                            + "platform for this loan"},
                    {"Application no.", String.valueOf(f.applicationId())},
                    {"Loan account no.", PdfKit.loanAccount(f)},
            }));

            doc.add(section("1. The loan"));
            doc.add(body("The lender lends you " + rs(f.principalPaise()) + ". From it the lender keeps a processing "
                    + "fee of " + rs(f.processingFeePaise()) + " (" + pct(f.pfRate()) + "), GST of "
                    + rs(f.gstPaise()) + (f.interestUpfront() ? " and interest of " + rs(f.interestPaise()) : "")
                    + ", a total of " + rs(f.totalDeductionsPaise()) + ", and pays " + rs(f.netDisbursedPaise())
                    + " into your account " + safe(f.disbursalAccountMasked(), "confirmed by you")
                    + " once the conditions in the sanction letter are met."));

            doc.add(section("2. Repayment"));
            doc.add(body("You repay " + rs(f.totalRepayablePaise()) + " in a single payment. Due date: "
                    + PdfKit.dueLine(f) + ". Day 0 is the date the lender confirms the disbursal; the due date is "
                    + "not moved for holidays. A payment counts on the date it is credited to the lender's account."));
            doc.add(body(PdfKit.beneficiaryLine(f)));

            doc.add(section("3. Interest and APR"));
            doc.add(body("Interest: " + PdfKit.interestLine(f) + ". Annual percentage rate (APR): "
                    + apr(f.aprPercent()) + "."));

            doc.add(section("4. Late charge"));
            doc.add(body("If you do not repay by the due date: " + PdfKit.penalLine(f) + "."));

            doc.add(section("5. Prepayment"));
            doc.add(body(PdfKit.prepaymentLine(f)));

            doc.add(section("6. Cooling-off / look-up period"));
            doc.add(body(PdfKit.COOLING_OFF + "."));

            doc.add(section("7. Key Fact Statement and sanction letter"));
            doc.add(body("The Key Fact Statement you acknowledged and the sanction letter for this loan are part "
                    + "of this agreement and state the same figures."));

            doc.add(section("8. Recovery"));
            doc.add(body("If you do not repay, the lender, or an agent it names to you, may contact you to recover "
                    + "the amount due, only in the ways the law allows."));

            doc.add(section("9. Grievances"));
            doc.add(body(PdfKit.grievanceLine(f)));

            doc.add(section("10. Electronic execution"));
            doc.add(body("You sign this agreement electronically (Aadhaar e-sign or a signature drawn in the "
                    + f.brand().name() + " app). The record of your signature, with its date and time, is kept "
                    + "with this document version."));

            if (signaturePng != null) {
                doc.add(section("Borrower signature"));
                doc.add(signatureBlock(f, signaturePng, safe(signerName, f.borrowerName()), signedAt));
            }
        });
    }

    private static PdfPTable signatureBlock(DocumentFacts f, byte[] png, String signer, Instant signedAt)
            throws com.lowagie.text.DocumentException, java.io.IOException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[] {1.1f, 1.4f});
        Image signature = Image.getInstance(png);
        signature.scaleToFit(170, 55);
        PdfPCell imageCell = new PdfPCell(signature, false);
        imageCell.setBorder(Rectangle.BOX);
        imageCell.setPadding(8);
        imageCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(imageCell);
        PdfPCell meta = new PdfPCell();
        meta.setBorder(Rectangle.BOX);
        meta.setPadding(8);
        meta.addElement(new Paragraph("Signed by " + safe(signer, "Borrower"), PdfKit.VALUE));
        meta.addElement(new Paragraph("Signed at " + PdfKit.dateTime(signedAt), PdfKit.BODY));
        meta.addElement(new Paragraph("Application #" + f.applicationId(), PdfKit.BODY));
        table.addCell(meta);
        return table;
    }
}
