package com.navix.loan.service;

import com.navix.common.security.ActorContext;
import com.navix.loan.entity.Loan;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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

    /** One instalment of a new schedule version. {@code penalPaise} is late charge converted into an EMI. */
    public record Instalment(LocalDate dueDate, Instant dueAt, long principalPaise, long penalPaise) {

        public long totalPaise() {
            return principalPaise + penalPaise;
        }
    }

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

    /**
     * An activated extension (Phase 6b): supersede the ACTIVE schedule, then add the next version, kind
     * EXTENSION, with one instalment of the principal still open on the new due date. The fee, GST and
     * interest of the extension were paid to activate it, so none of them falls due here.
     *
     * @return {from, to} schedule versions; from is 0 when the loan had no schedule
     */
    @Transactional
    public int[] writeExtension(Loan loan, long principalOpenPaise) {
        return writeVersion(loan, "EXTENSION",
                List.of(new Instalment(loan.getDueDate(), loan.getDueAt(), principalOpenPaise, 0L)));
    }

    /** An activated recovery EMI (Phase 6c): supersede the ACTIVE schedule, then add the EMI version. */
    @Transactional
    public int[] writeEmi(Loan loan, List<Instalment> instalments) {
        return writeVersion(loan, "EMI", instalments);
    }

    /** Supersede first, then insert: the partial unique index allows one ACTIVE schedule per loan (V102). */
    private int[] writeVersion(Loan loan, String kind, List<Instalment> instalments) {
        Map<String, Object> active = jdbc.queryForList(
                "select id, version from repayment_schedule where loan_id = ? and status = 'ACTIVE'", loan.getId())
                .stream().findFirst().orElse(null);
        Long parentId = active == null ? null : ((Number) active.get("id")).longValue();
        int from = active == null ? 0 : ((Number) active.get("version")).intValue();
        int to = jdbc.queryForObject("select coalesce(max(version), 0) + 1 from repayment_schedule where loan_id = ?",
                Integer.class, loan.getId());
        if (parentId != null) {
            jdbc.update("update repayment_schedule set status = 'SUPERSEDED' where id = ?", parentId);
        }
        Long scheduleId = jdbc.queryForObject("""
                insert into repayment_schedule (loan_id, version, kind, parent_version_id, status, created_by, reason_code)
                values (?, ?, ?, ?, 'ACTIVE', ?, ?) returning id
                """, Long.class, loan.getId(), to, kind, parentId, ActorContext.get().id(), kind);
        int seq = 1;
        for (Instalment i : instalments) {
            jdbc.update("""
                    insert into schedule_instalment (schedule_id, seq, due_date, due_at, principal_paise, penal_paise)
                    values (?, ?, ?, ?, ?, ?)
                    """, scheduleId, seq++, i.dueDate(), Timestamp.from(i.dueAt()), i.principalPaise(), i.penalPaise());
        }
        return new int[] {from, to};
    }

    /**
     * Spread verified money over the active schedule's open instalments, earliest first. Principal (and
     * navix-terms interest) always counts; late charge counts only on an EMI schedule, whose instalments
     * carry the late charge that was converted.
     */
    @Transactional
    public void applyPaid(Long loanId, long paise, long penalPaise) {
        List<Map<String, Object>> due = jdbc.queryForList("""
                select i.id, s.kind, i.principal_paise + i.interest_paise + i.fee_paise + i.gst_paise + i.penal_paise
                       - i.paid_paise as open
                from schedule_instalment i join repayment_schedule s on s.id = i.schedule_id
                where s.loan_id = ? and s.status = 'ACTIVE' and i.status <> 'PAID' order by i.seq
                """, loanId);
        long left = paise + (!due.isEmpty() && "EMI".equals(due.get(0).get("kind")) ? penalPaise : 0L);
        for (Map<String, Object> row : due) {
            if (left <= 0) {
                break;
            }
            long take = Math.min(left, ((Number) row.get("open")).longValue());
            jdbc.update("update schedule_instalment set paid_paise = paid_paise + ?, status = case when paid_paise + ?"
                    + " >= principal_paise + interest_paise + fee_paise + gst_paise + penal_paise then 'PAID'"
                    + " else status end where id = ?", take, take, row.get("id"));
            left -= take;
        }
    }

    /**
     * A reversed payment (CC-10): take what it paid back off the active schedule, latest instalment first.
     * ponytail: assumes the payment was spread over the schedule that is still active; one reversed after a
     * later extension or EMI superseded it only lowers the new schedule's paid amounts, never below zero.
     */
    @Transactional
    public void reversePaid(Long loanId, long paise, long penalPaise, Instant now) {
        List<Map<String, Object>> paid = jdbc.queryForList("""
                select i.id, s.kind, i.paid_paise from schedule_instalment i join repayment_schedule s on s.id = i.schedule_id
                where s.loan_id = ? and s.status = 'ACTIVE' and i.paid_paise > 0 order by i.seq desc
                """, loanId);
        long left = paise + (!paid.isEmpty() && "EMI".equals(paid.get(0).get("kind")) ? penalPaise : 0L);
        for (Map<String, Object> row : paid) {
            if (left <= 0) {
                break;
            }
            long take = Math.min(left, ((Number) row.get("paid_paise")).longValue());
            jdbc.update("update schedule_instalment set paid_paise = paid_paise - ?,"
                    + " status = case when due_at < ? then 'OVERDUE' else 'DUE' end where id = ?",
                    take, Timestamp.from(now), row.get("id"));
            left -= take;
        }
    }

    /** CC-17: open instalments of open loans past their {@code due_at} read OVERDUE. @return how many moved */
    @Transactional
    public int markOverdue(Instant now) {
        return jdbc.update("""
                update schedule_instalment i set status = 'OVERDUE'
                from repayment_schedule s join loan l on l.id = s.loan_id
                where s.id = i.schedule_id and s.status = 'ACTIVE' and i.status = 'DUE' and i.due_at < ?
                  and l.lifecycle_status <> 'CLOSED'
                """, Timestamp.from(now));
    }
}
