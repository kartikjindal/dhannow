package com.navix.loan.service;

import com.navix.common.security.ActorContext;
import com.navix.loan.entity.Loan;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repayment schedules ({@code repayment_schedule} + {@code schedule_instalment}, V102). The v1 BULLET
 * schedule is written when the Accountant confirms the disbursal, in the same transaction; it is never
 * edited (a database trigger refuses). Extensions and EMI add versions (Phase 6b/6c).
 */
@Service
@RequiredArgsConstructor
public class RepaymentScheduleService {

    private final JdbcTemplate jdbc;

    /** v1 BULLET: one instalment of the principal on the confirmed due date. Once per loan. */
    @Transactional
    public void writeOriginal(Loan loan) {
        Long scheduleId = jdbc.query("""
                insert into repayment_schedule (loan_id, version, kind, status, created_by)
                values (?, 1, 'BULLET', 'ACTIVE', ?) on conflict (loan_id, version) do nothing returning id
                """, (rs, i) -> rs.getLong(1), loan.getId(), ActorContext.get().id()).stream().findFirst().orElse(null);
        if (scheduleId == null) {
            return;
        }
        // What falls due on day 14: the principal (the fee, GST and interest were deducted at disbursal).
        // On navix (ACCRUED) terms the scheduled interest is due with it.
        long interest = Math.max(0L, loan.getTotalRepayable() - loan.getPrincipal());
        jdbc.update("""
                insert into schedule_instalment (schedule_id, seq, due_date, due_at, principal_paise, interest_paise)
                values (?, 1, ?, ?, ?, ?)
                """, scheduleId, loan.getDueDate(), Timestamp.from(loan.getDueAt()), loan.getPrincipal(), interest);
    }

    /** Spread verified principal over the active schedule's instalments, earliest first. */
    @Transactional
    public void applyPaid(Long loanId, long paise) {
        List<Map<String, Object>> due = jdbc.queryForList("""
                select i.id, i.principal_paise + i.interest_paise + i.fee_paise + i.gst_paise - i.paid_paise as open
                from schedule_instalment i join repayment_schedule s on s.id = i.schedule_id
                where s.loan_id = ? and s.status = 'ACTIVE' and i.status = 'DUE' order by i.seq
                """, loanId);
        long left = paise;
        for (Map<String, Object> row : due) {
            if (left <= 0) {
                break;
            }
            long open = ((Number) row.get("open")).longValue();
            long take = Math.min(left, open);
            jdbc.update("update schedule_instalment set paid_paise = paid_paise + ?,"
                    + " status = case when paid_paise + ? >= principal_paise + interest_paise + fee_paise + gst_paise"
                    + " then 'PAID' else 'DUE' end where id = ?", take, take, row.get("id"));
            left -= take;
        }
    }
}
