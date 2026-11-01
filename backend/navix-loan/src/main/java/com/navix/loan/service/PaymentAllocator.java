package com.navix.loan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.loan.entity.Payment;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Splits each verified payment over what the loan owes on the payment's bank date ({@code payment_allocation},
 * V101), in the order of config {@code navix.payments.waterfall}: penal → principal (CC-09). Interest is
 * prepaid at disbursal on the DhanNow terms, so INTEREST owes nothing there; it is in the default order only
 * for loans priced on the navix terms. Whatever is left is EXCESS and opens a refund case (CC-08).
 */
@Service
@RequiredArgsConstructor
public class PaymentAllocator {

    /** EXT_* are an extension charge's parts (Phase 6b); the waterfall never fills them. */
    public enum Component { PENAL, INTEREST, PRINCIPAL, EXCESS, EXT_FEE, EXT_GST, EXT_INTEREST }

    private final JdbcTemplate jdbc;
    private final RefundCaseService refunds;
    private final RepaymentScheduleService schedules;
    private final ObjectMapper json;

    @Value("${navix.payments.waterfall:PENAL,INTEREST,PRINCIPAL}")
    private List<Component> waterfall = List.of(Component.PENAL, Component.INTEREST, Component.PRINCIPAL);

    /**
     * Allocate one payment that has just been verified.
     *
     * @param penalToDate    late charge accrued up to the payment's {@code paid_on} (capped), before any payment
     * @param interestToDate interest still owed to that date (0 on UPFRONT terms)
     * @param principal      the loan principal; pass 0 for all three when the loan was already closed
     */
    @Transactional
    public Map<Component, Long> allocate(Payment payment, long penalToDate, long interestToDate, long principal) {
        Map<Component, Long> paid = new EnumMap<>(Component.class);
        // What earlier repayments covered. An extension's own late-charge share is accounted on the loan
        // (penal_carried_paise) when it activates, so it is not counted here.
        jdbc.query("select a.component, sum(a.amount_paise) from payment_allocation a join payment p on p.id = a.payment_id"
                        + " where a.loan_id = ? and p.purpose = 'REPAYMENT' group by a.component",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> paid.put(Component.valueOf(rs.getString(1)), rs.getLong(2)),
                payment.getLoanId());
        Map<Component, Long> due = new EnumMap<>(Component.class);
        due.put(Component.PENAL, Math.max(0L, penalToDate - paid.getOrDefault(Component.PENAL, 0L)));
        due.put(Component.INTEREST, Math.max(0L, interestToDate - paid.getOrDefault(Component.INTEREST, 0L)));
        due.put(Component.PRINCIPAL, Math.max(0L, principal - paid.getOrDefault(Component.PRINCIPAL, 0L)));

        Map<Component, Long> split = split(payment.getAmount(), waterfall, due);
        split.forEach((component, amount) -> jdbc.update(
                "insert into payment_allocation (payment_id, loan_id, component, amount_paise) values (?, ?, ?, ?)",
                payment.getId(), payment.getLoanId(), component.name(), amount));
        schedules.applyPaid(payment.getLoanId(),
                split.getOrDefault(Component.PRINCIPAL, 0L) + split.getOrDefault(Component.INTEREST, 0L),
                split.getOrDefault(Component.PENAL, 0L));
        refunds.open(payment.getLoanId(), payment.getId(), null, split.getOrDefault(Component.EXCESS, 0L),
                RefundCaseService.Reason.OVERPAYMENT);
        return split;
    }

    /**
     * Undo the split of a payment that is being reversed (CC-10): its allocation rows are removed (and returned,
     * for the audit row), what it paid comes off the schedule, and its open refund case is cancelled. A refund
     * already paid out is refused: that money has to be recovered first.
     */
    @Transactional
    public Map<Component, Long> unallocate(Payment payment, Instant now) {
        List<String> refund = jdbc.queryForList("select status from refund_case where payment_id = ?", String.class,
                payment.getId());
        if (refund.contains("REFUNDED")) {
            throw new BusinessException("REFUND_ALREADY_PAID",
                    "Part of this payment was already refunded; recover the refund before reversing it.");
        }
        Map<Component, Long> split = new EnumMap<>(Component.class);
        jdbc.query("select component, amount_paise from payment_allocation where payment_id = ?",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> split.put(Component.valueOf(rs.getString(1)), rs.getLong(2)), payment.getId());
        jdbc.update("delete from payment_allocation where payment_id = ?", payment.getId());
        schedules.reversePaid(payment.getLoanId(),
                split.getOrDefault(Component.PRINCIPAL, 0L) + split.getOrDefault(Component.INTEREST, 0L),
                split.getOrDefault(Component.PENAL, 0L), now);
        jdbc.update("update refund_case set status = 'CANCELLED', cancel_reason = 'PAYMENT_REVERSED', updated_at = ?,"
                + " updated_by = 'system' where payment_id = ? and status = 'OPEN'", Timestamp.from(now), payment.getId());
        return split;
    }

    /** A {@code balance_adjustment} row (V103): every money or state correction outside the waterfall. */
    @Transactional
    public void adjustment(Long loanId, Long customerId, String kind, Map<String, Object> before,
                           Map<String, Object> after, Long approverId, String reasonCode, Instant at) {
        jdbc.update("insert into balance_adjustment (loan_id, customer_id, kind, before, after, approver_id, reason_code,"
                        + " at) values (?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?)",
                loanId, customerId, kind, toJson(before), toJson(after), approverId, reasonCode, Timestamp.from(at));
    }

    private String toJson(Map<String, Object> map) {
        try {
            return json.writeValueAsString(map);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The waterfall itself: fill each component in {@code order} up to its due; the rest is EXCESS. Zero parts are left out. */
    static Map<Component, Long> split(long amount, List<Component> order, Map<Component, Long> due) {
        Map<Component, Long> out = new EnumMap<>(Component.class);
        long left = amount;
        for (Component c : order) {
            long take = Math.min(left, due.getOrDefault(c, 0L));
            if (take > 0) {
                out.put(c, take);
                left -= take;
            }
        }
        if (left > 0) {
            out.put(Component.EXCESS, left);
        }
        return out;
    }
}
