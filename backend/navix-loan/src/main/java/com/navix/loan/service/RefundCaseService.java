package com.navix.loan.service;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The refund register ({@code refund_case}, V101; CC-08, CC-11): money taken that nothing was owed for.
 * It replaces the navix NACH-only register ({@code mandate_debit.overpayment}, kept as it was) with one
 * list for finance. Excess is never applied to a future loan.
 */
@Service
@RequiredArgsConstructor
public class RefundCaseService {

    public enum Reason { OVERPAYMENT, NACH_AFTER_CLOSE }

    public record RefundCaseView(Long id, Long loanId, Long customerId, Long paymentId, Long mandateDebitId,
                                 long amountPaise, String reason, String status, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    /** Open a case for one payment or one NACH debit; a second call for the same source is a no-op. */
    @Transactional
    public void open(Long loanId, Long paymentId, Long mandateDebitId, long amountPaise, Reason reason) {
        if (amountPaise <= 0) {
            return;
        }
        jdbc.update("""
                insert into refund_case (loan_id, customer_id, payment_id, mandate_debit_id, amount_paise, reason, created_by)
                select l.id, l.customer_id, ?, ?, ?, ?, 'system' from loan l where l.id = ?
                on conflict do nothing
                """, paymentId, mandateDebitId, amountPaise, reason.name(), loanId);
    }

    @Transactional(readOnly = true)
    public List<RefundCaseView> list(String status) {
        return jdbc.query("""
                select id, loan_id, customer_id, payment_id, mandate_debit_id, amount_paise, reason, status, created_at
                from refund_case where (cast(? as varchar) is null or status = ?) order by id desc
                """, (rs, i) -> new RefundCaseView(rs.getLong("id"), rs.getLong("loan_id"), rs.getLong("customer_id"),
                (Long) rs.getObject("payment_id"), (Long) rs.getObject("mandate_debit_id"), rs.getLong("amount_paise"),
                rs.getString("reason"), rs.getString("status"), rs.getTimestamp("created_at").toInstant()),
                status, status);
    }
}
