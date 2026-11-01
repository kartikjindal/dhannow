package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ConflictException;
import com.navix.common.exception.ResourceNotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
                                 long amountPaise, String reason, String status, Instant createdAt, String refundUtr,
                                 LocalDate refundedOn, String cancelReason) {
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
        return jdbc.query(SELECT + " where (cast(? as varchar) is null or status = ?) order by id desc", ROW, status, status);
    }

    /** Finance paid the money back: the refund's own UTR and bank date. OPEN only; a second call is 409. */
    @Transactional
    public RefundCaseView markRefunded(Long id, String utr, LocalDate refundedOn, Long by) {
        String ref = RepaymentService.normaliseUtr(utr);
        if (ref == null) {
            throw new BusinessException("UTR_REQUIRED", "Enter the UTR of the refund transfer.");
        }
        decide(id, "update refund_case set status = 'REFUNDED', refund_utr = ?, refunded_on = ?, decided_by = ?,"
                + " updated_at = now(), updated_by = ? where id = ? and status = 'OPEN'",
                ref, refundedOn != null ? refundedOn : LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")), by,
                String.valueOf(by), id);
        adjust(id, "REFUND_PAID", null, by);
        return get(id);
    }

    /** Nothing is owed back after all (a duplicate case, money applied by a correction): a reason is required. */
    @Transactional
    public RefundCaseView cancel(Long id, String reason, Long by) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REASON_REQUIRED", "Say why this refund case is cancelled.");
        }
        decide(id, "update refund_case set status = 'CANCELLED', cancel_reason = ?, decided_by = ?, updated_at = now(),"
                + " updated_by = ? where id = ? and status = 'OPEN'", reason.trim(), by, String.valueOf(by), id);
        adjust(id, "REFUND_CANCELLED", reason.trim(), by);
        return get(id);
    }

    /** PLAN Phase 7: every refund decision is a {@code balance_adjustment} row (money owed back, now settled). */
    private void adjust(Long id, String reasonCode, String note, Long by) {
        jdbc.update("""
                insert into balance_adjustment (loan_id, customer_id, kind, before, after, approver_id, reason_code, note)
                select loan_id, customer_id, 'REFUND',
                       jsonb_build_object('refundCaseId', id, 'status', 'OPEN', 'amountPaise', amount_paise),
                       jsonb_build_object('refundCaseId', id, 'status', status, 'refundUtr', refund_utr,
                                          'refundedOn', refunded_on),
                       ?, ?, ? from refund_case where id = ?
                """, by, reasonCode, note, id);
    }

    private void decide(Long id, String sql, Object... args) {
        RefundCaseView current = get(id);
        if (jdbc.update(sql, args) == 0) {
            throw new ConflictException("STALE_STATE", "This refund case is " + current.status().toLowerCase() + ".",
                    current.status());
        }
    }

    private RefundCaseView get(Long id) {
        return jdbc.query(SELECT + " where id = ?", ROW, id).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("RefundCase", String.valueOf(id)));
    }

    private static final String SELECT = "select id, loan_id, customer_id, payment_id, mandate_debit_id, amount_paise,"
            + " reason, status, created_at, refund_utr, refunded_on, cancel_reason from refund_case";

    private static final RowMapper<RefundCaseView> ROW = (rs, i) -> new RefundCaseView(rs.getLong("id"),
            rs.getLong("loan_id"), rs.getLong("customer_id"), (Long) rs.getObject("payment_id"),
            (Long) rs.getObject("mandate_debit_id"), rs.getLong("amount_paise"), rs.getString("reason"),
            rs.getString("status"), rs.getTimestamp("created_at").toInstant(), rs.getString("refund_utr"),
            rs.getObject("refunded_on", LocalDate.class), rs.getString("cancel_reason"));
}
