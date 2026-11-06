package com.navix.loan.pdf;

import static com.navix.loan.pdf.PdfKit.apr;
import static com.navix.loan.pdf.PdfKit.body;
import static com.navix.loan.pdf.PdfKit.bullets;
import static com.navix.loan.pdf.PdfKit.date;
import static com.navix.loan.pdf.PdfKit.kv;
import static com.navix.loan.pdf.PdfKit.pct;
import static com.navix.loan.pdf.PdfKit.rs;
import static com.navix.loan.pdf.PdfKit.safe;
import static com.navix.loan.pdf.PdfKit.section;

import org.springframework.stereotype.Component;

/**
 * The loan sanction letter, on the DhanNow template ({@code docs/product/DHAN_NOW_Sanction_Letter_Template.docx}):
 * letter and account numbers, validity and acceptance deadline, lender, the sanctioned terms with the
 * APR, conditions precedent, key disclosures and the borrower's acceptance. The borrower accepts it by
 * signing the loan agreement it is bundled with; {@link DocumentFacts#acceptedAt()} is that moment.
 */
@Component
public class SanctionLetterPdfRenderer {

    public byte[] render(DocumentFacts f) {
        String number = "DN-SL-" + f.applicationId() + "-" + f.version();
        return PdfKit.render(f, "LOAN SANCTION LETTER", "Sanction letter no. " + number + "  ·  Date of issue "
                + date(f.issuedOn()), doc -> {
            doc.add(kv(new String[][] {
                    {"Sanction letter no.", number},
                    {"Date of issue", date(f.issuedOn())},
                    {"Application no.", String.valueOf(f.applicationId())},
                    {"Loan account no.", PdfKit.loanAccount(f)},
                    {"Validity of offer", date(f.offerValidUntil())},
                    {"Offer acceptance deadline", date(f.offerValidUntil())},
                    {"Lender's legal name", f.lender().legalName()},
                    {"Lender's registered address", f.lender().address()},
            }));

            doc.add(section("To"));
            doc.add(body(safe(f.borrowerName(), "Borrower")
                    + (PdfKit.blank(f.borrowerMobile()) ? "" : ", " + f.borrowerMobile())
                    + (PdfKit.blank(f.borrowerEmail()) ? "" : ", " + f.borrowerEmail())
                    + (PdfKit.blank(f.borrowerAddress()) ? "" : ", " + f.borrowerAddress())));
            doc.add(body("Subject: Sanction of personal loan, application no. " + f.applicationId()));
            doc.add(body("Dear customer, the lender named in this letter has approved the loan below, subject to "
                    + "the terms and conditions in this letter, the Key Fact Statement (KFS) and the loan agreement. "
                    + "This sanction is valid until the date above and can be acted on only after you accept it "
                    + "and the conditions below are met."));

            doc.add(section("1. Sanctioned loan details"));
            doc.add(kv(new String[][] {
                    {"Borrower name", safe(f.borrowerName(), "Borrower")},
                    {"Approved principal", rs(f.principalPaise())},
                    {"Loan purpose", safe(f.purpose(), "Personal use")},
                    {"Loan tenure", f.tenureDays() + " days"},
                    {"Interest rate and calculation method", PdfKit.interestLine(f)},
                    {"Annual percentage rate (APR)", apr(f.aprPercent())},
                    {"Processing fee", rs(f.processingFeePaise()) + " (" + pct(f.pfRate()) + " of the principal)"},
                    {"GST on the processing fee", rs(f.gstPaise()) + " (" + pct(f.gstRate()) + ")"},
                    {"Other charges", rs(0)},
                    {"Total deductions from disbursal", rs(f.totalDeductionsPaise())},
                    {"Net amount to be disbursed", rs(f.netDisbursedPaise())},
                    {"Disbursal account", safe(f.disbursalAccountMasked(), "As confirmed by you")},
                    {"Total amount payable by you", rs(f.totalRepayablePaise())},
                    {"Repayment frequency and instalment", PdfKit.repaymentLine(f)},
                    {"First / single repayment due date", PdfKit.dueLine(f)},
                    {"Final repayment due date", PdfKit.dueLine(f)},
                    {"Late charges", PdfKit.penalLine(f)},
                    {"Cooling-off / look-up period", PdfKit.COOLING_OFF},
                    {"Prepayment / foreclosure", PdfKit.prepaymentLine(f)},
                    {"Repayment method and beneficiary", PdfKit.beneficiaryLine(f)},
            }));

            doc.add(section("2. Conditions before disbursal"));
            doc.add(bullets(
                    "KYC, identity, bank account and other verification checks are complete.",
                    "The lender's credit, affordability and fraud checks are complete.",
                    "You have read and accepted the KFS, the loan agreement, the repayment schedule and the "
                            + "required consents.",
                    "The loan agreement has been signed.",
                    "The disbursal account belongs to you.",
                    "There is no misrepresentation, fraud alert or material change in the information you gave."));

            doc.add(section("3. Key disclosures"));
            doc.add(bullets(
                    "This letter records the sanction and does not replace the KFS or the signed loan agreement. "
                            + "If any figure differs, the lender corrects it and gives you new documents before "
                            + "you accept or the loan is disbursed.",
                    "Check the APR, fees, taxes, net disbursal, total repayment and due date before you accept.",
                    "No amount or charge is collected unless it is shown in your loan documents.",
                    PdfKit.platformLine(f)));

            doc.add(section("4. Acceptance by borrower"));
            doc.add(body("I confirm that I have received and read this sanction letter and the KFS. I understand "
                    + "that my obligations arise from the accepted loan documents and that the loan is disbursed "
                    + "only after the conditions above are met."));
            doc.add(kv(new String[][] {
                    {"Borrower's name", safe(f.borrowerName(), "Borrower")},
                    {"Signature / electronic acceptance", "By e-signing the loan agreement in this pack"},
                    {"Date and time of acceptance", f.acceptedAt() == null ? "On e-sign" : PdfKit.dateTime(f.acceptedAt())},
            }));

            doc.add(section("5. For and on behalf of the lender"));
            doc.add(body("For " + f.lender().legalName() + ". Contact: " + f.lender().contact() + "."));
            doc.add(body(PdfKit.grievanceLine(f)));
        });
    }
}
