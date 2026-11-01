package com.navix.loan.pdf;

import static com.navix.loan.pdf.PdfKit.apr;
import static com.navix.loan.pdf.PdfKit.body;
import static com.navix.loan.pdf.PdfKit.bullets;
import static com.navix.loan.pdf.PdfKit.date;
import static com.navix.loan.pdf.PdfKit.kv;
import static com.navix.loan.pdf.PdfKit.pct;
import static com.navix.loan.pdf.PdfKit.rs;
import static com.navix.loan.pdf.PdfKit.section;

import org.springframework.stereotype.Component;

/**
 * The Key Fact Statement (PLAN Phase 5): the RBI KFS facts for one offer, shown and acknowledged
 * before the loan agreement can be signed. Also renders the repayment schedule issued when the
 * disbursal is confirmed (CC-01), which is the KFS schedule with the final dates.
 */
@Component
public class KfsPdfRenderer {

    public byte[] render(DocumentFacts f) {
        return PdfKit.render(f, "KEY FACT STATEMENT", "KFS no. " + number(f) + "  ·  Date " + date(f.issuedOn())
                + "  ·  Application no. " + f.applicationId() + "  ·  Pricing version " + f.pricingVersion(), doc -> {
            doc.add(PdfKit.hero("You receive in your bank account", rs(f.netDisbursedPaise()),
                    "You repay on the due date", rs(f.totalRepayablePaise())));

            doc.add(section("Lender and borrower"));
            doc.add(kv(new String[][] {
                    {"Lender", PdfKit.lenderLine(f)},
                    {"Borrower", PdfKit.borrowerLine(f)},
                    {"Loan account no.", PdfKit.loanAccount(f)},
            }));

            doc.add(section("Part 1: Interest rate, fees and charges"));
            doc.add(kv(new String[][] {
                    {"Loan amount (principal)", rs(f.principalPaise())},
                    {"Loan term", f.tenureDays() + " days"},
                    {"Interest rate and type", PdfKit.interestLine(f)},
                    {"Processing fee (" + pct(f.pfRate()) + " of the principal)", rs(f.processingFeePaise())},
                    {"GST on the processing fee (" + pct(f.gstRate()) + ")", rs(f.gstPaise())},
                    {"Other charges", rs(0)},
                    {"Total deducted from the principal", rs(f.totalDeductionsPaise())},
                    {"Net amount disbursed to you", rs(f.netDisbursedPaise())},
                    {"Total amount you repay", rs(f.totalRepayablePaise())},
                    {"Annual percentage rate (APR)", apr(f.aprPercent())},
            }));

            doc.add(section("Repayment schedule"));
            doc.add(schedule(f));

            doc.add(section("Part 2: Other information"));
            doc.add(kv(new String[][] {
                    {"Late charge", PdfKit.penalLine(f)},
                    {"Prepayment", PdfKit.prepaymentLine(f)},
                    {"Cooling-off / look-up period", PdfKit.COOLING_OFF},
                    {"How to repay", PdfKit.beneficiaryLine(f)},
                    {"Offer valid until", date(f.offerValidUntil())},
            }));

            doc.add(section("Grievance redressal"));
            doc.add(body(PdfKit.grievanceLine(f)));

            doc.add(section("Please note"));
            doc.add(bullets(
                    PdfKit.platformLine(f),
                    "The APR is the yearly cost of this loan, worked out from the "
                            + rs(f.netDisbursedPaise()) + " you receive and the " + rs(f.totalRepayablePaise())
                            + " you repay after " + f.tenureDays() + " days (method: " + f.aprMethod() + ").",
                    "The due date is fixed when the disbursal is confirmed and is not moved for holidays.",
                    "Read this KFS before you sign the loan agreement. The loan agreement and sanction letter "
                            + "use the same figures."));
        });
    }

    /** The repayment schedule (CC-01): issued when the disbursal is confirmed, with the final due date. */
    public byte[] renderSchedule(DocumentFacts f) {
        return PdfKit.render(f, "REPAYMENT SCHEDULE", "Schedule v" + f.version() + "  ·  Loan account no. "
                + PdfKit.loanAccount(f) + "  ·  Application no. " + f.applicationId(), doc -> {
            doc.add(kv(new String[][] {
                    {"Borrower", PdfKit.borrowerLine(f)},
                    {"Lender", f.lender().legalName()},
                    {"Disbursed on", date(f.disbursedOn())},
                    {"Net amount disbursed", rs(f.netDisbursedPaise())},
            }));
            doc.add(section("Schedule"));
            doc.add(schedule(f));
            doc.add(section("Please note"));
            doc.add(bullets(
                    "Late charge: " + PdfKit.penalLine(f) + ".",
                    PdfKit.beneficiaryLine(f),
                    PdfKit.grievanceLine(f)));
        });
    }

    /** The closure receipt (PLAN Phase 6a): issued once when the loan closes, from the ledger. */
    public byte[] renderClosureReceipt(DocumentFacts f, ClosureFacts c) {
        return PdfKit.render(f, "LOAN CLOSURE RECEIPT", "Receipt v" + f.version() + "  ·  Loan account no. "
                + PdfKit.loanAccount(f) + "  ·  Application no. " + f.applicationId(), doc -> {
            doc.add(PdfKit.hero("Loan amount", rs(f.principalPaise()), "Closed on", date(c.closedOn())));
            doc.add(kv(new String[][] {
                    {"Borrower", PdfKit.borrowerLine(f)},
                    {"Lender", f.lender().legalName()},
                    {"Disbursed on", date(f.disbursedOn())},
                    {"Due date", PdfKit.dueLine(f)},
                    {"Status", c.settled() ? "Closed by an approved settlement" : "Repaid in full"},
            }));
            doc.add(section("Payments received"));
            String[][] rows = new String[c.payments().size() + 1][];
            for (int i = 0; i < c.payments().size(); i++) {
                ClosureFacts.PaidLine p = c.payments().get(i);
                rows[i] = new String[] {date(p.paidOn()) + (p.reference() == null ? "" : ", ref " + p.reference()),
                        rs(p.amountPaise())};
            }
            rows[c.payments().size()] = new String[] {"Total received", rs(c.totalPaise())};
            doc.add(kv(rows));
            doc.add(section("How your payments were applied"));
            doc.add(kv(new String[][] {
                    {"Late charge", rs(c.penalPaise())},
                    {"Principal", rs(c.principalPaise())},
                    {"Excess, to be refunded to you", rs(c.refundPaise())},
            }));
            doc.add(section("Please note"));
            doc.add(bullets(
                    c.settled() ? "This loan is closed on the settlement agreed with you. Nothing more is owed on it."
                            : "Nothing more is owed on this loan.",
                    c.refundPaise() > 0 ? "We will refund " + rs(c.refundPaise()) + " to your bank account."
                            : "No refund is due.",
                    PdfKit.grievanceLine(f)));
        });
    }

    private static com.lowagie.text.pdf.PdfPTable schedule(DocumentFacts f) throws com.lowagie.text.DocumentException {
        return kv(new String[][] {
                {"Number of instalments", "1"},
                {"Instalment", PdfKit.repaymentLine(f)},
                {"Due date", PdfKit.dueLine(f)},
        });
    }

    static String number(DocumentFacts f) {
        return "DN-KFS-" + f.applicationId() + "-" + f.version();
    }
}
