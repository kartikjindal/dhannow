package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.extension.ExtensionQuote;
import com.navix.loan.entity.Loan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Money parity (PLAN Phase 10a W6): the website's {@code site/parity/ladder.json}, which {@code site/calc.js} is
 * checked against, must match {@link LoanMath} on the DhanNow seed terms paisa for paisa, and so must the extension
 * quote. One file, three surfaces: a pricing change that touches only one of them fails here.
 */
class SiteLadderParityTest {

    private static final LoanMath MATH = new LoanMath(LoanMath.DHANNOW_SEED_PRICING);

    private static JsonNode fixture() throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("site/parity/ladder.json"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("site/parity/ladder.json above " + System.getProperty("user.dir")).isNotNull();
        return new ObjectMapper().readTree(dir.resolve("site/parity/ladder.json").toFile());
    }

    @Test
    void everyLadderRowMatchesLoanMath() throws IOException {
        JsonNode rows = fixture().get("ladder");
        assertThat(rows).hasSize(7);
        int tenure = LoanMath.DHANNOW_SEED_PRICING.tenureDays();
        for (JsonNode r : rows) {
            long p = r.get("borrowPaise").asLong();
            long fee = MATH.processingFeePaise(p);
            long gst = MATH.gstPaise(p);
            long interest = MATH.interestPaise(p, tenure);
            String at = "level " + r.get("level").asInt();
            assertThat(fee).as(at + " fee").isEqualTo(r.get("feePaise").asLong());
            assertThat(gst).as(at + " gst").isEqualTo(r.get("gstPaise").asLong());
            assertThat(interest).as(at + " interest").isEqualTo(r.get("interestPaise").asLong());
            assertThat(MATH.upfrontInterestPaise(p)).as(at + " upfront interest").isEqualTo(interest);
            assertThat(fee + gst + interest).as(at + " deductions").isEqualTo(r.get("deductionsPaise").asLong());
            assertThat(MATH.netDisbursedPaise(p)).as(at + " net").isEqualTo(r.get("netPaise").asLong());
            assertThat(MATH.totalRepayablePaise(p, tenure)).as(at + " repay").isEqualTo(r.get("repayPaise").asLong());
            assertThat(MATH.latePenaltyPaise(p, 1)).as(at + " late per day").isEqualTo(r.get("latePerDayPaise").asLong());
            assertThat(quote(p, 0).totalPaise()).as(at + " extension").isEqualTo(r.get("extensionChargePaise").asLong());
        }
    }

    @Test
    void everyExtensionExampleMatchesTheQuote() throws IOException {
        for (JsonNode e : fixture().get("extensionExamples")) {
            ExtensionQuote q = quote(e.get("borrowPaise").asLong(), e.get("daysLate").asInt());
            assertThat(q.feePaise() + q.gstPaise() + q.interestPaise() + q.lateChargePaise()).as(e.toString())
                    .isEqualTo(q.totalPaise()).isEqualTo(e.get("totalPaise").asLong());
        }
    }

    /** The extension quote for a loan {@code daysLate} past due, nothing paid yet. */
    private static ExtensionQuote quote(long principalPaise, int daysLate) {
        Loan loan = new Loan();
        loan.setPrincipal(principalPaise);
        loan.setGraceDays(0);
        LocalDate due = LocalDate.of(2026, 11, 17);
        loan.setDueDate(due);
        return LoanExtensionLedger.quote(loan, MATH, due.plusDays(daysLate), 0L);
    }
}
