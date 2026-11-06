package com.navix.loan.service;

import com.navix.common.collections.ExtensionDirectory;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ConflictException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.notification.event.ComplaintFiledEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.security.SeparationOfDutiesGuard;
import com.navix.loan.domain.LoanHold;
import com.navix.loan.domain.LoanLifecycle;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.LoanRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loan disputes (PLAN Phase 7): {@code hold_status=DISPUTED} pauses reminders, the collections worklist and NACH
 * presentment (Phase 3). Raised by staff, or by a borrower's {@code LOAN_DISPUTE} complaint on that loan. Raising
 * one cancels an extension still on offer and an open EMI offer (EXT-27); while disputed no new offer can be made
 * (the eligibility checks refuse {@code ON_HOLD}). Every hold placed or lifted is a {@code loan_hold_event} row.
 * Release is maker-checker: whoever raised a dispute cannot lift it.
 */
@Service
@RequiredArgsConstructor
public class LoanDisputeService {

    static final Set<String> ROLES = Set.of("ADMIN", "SUPPORT", "COLLECTION_HEAD");

    public record DisputeView(Long loanId, String holdStatus, boolean extensionOfferCancelled,
                              boolean emiOfferCancelled, Long complaintId) {
    }

    public record HoldEventView(String fromHold, String toHold, String reason, Long complaintId, String actorId,
                                String actorRole, Instant at) {
    }

    private final LoanRepository loans;
    private final LoanExtensionLedger ledger;
    private final ExtensionDirectory extensions;
    private final EmiConversionService emis;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public DisputeView raise(Long loanId, String reason, Long complaintId) {
        CurrentActor actor = requireRole();
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("REASON_REQUIRED", "Say what the borrower disputes.");
        }
        return place(loanId, reason.trim(), complaintId, actor);
    }

    /** A borrower's LOAN_DISPUTE ticket on an open loan puts it on hold, in the ticket's own transaction. */
    @EventListener
    public void onComplaintFiled(ComplaintFiledEvent e) {
        if (!"LOAN_DISPUTE".equals(e.category()) || e.loanId() == null) {
            return;
        }
        Loan loan = loans.findById(e.loanId()).orElse(null);
        if (loan == null || loan.getLifecycleStatus() == LoanLifecycle.CLOSED || loan.getHoldStatus() != LoanHold.NONE) {
            return;
        }
        place(e.loanId(), "Complaint " + e.ticketNo(), e.complaintId(), CurrentActor.SYSTEM);
    }

    @Transactional
    public DisputeView release(Long loanId, String note) {
        CurrentActor actor = requireRole();
        if (note == null || note.isBlank()) {
            throw new BusinessException("REASON_REQUIRED", "Say how the dispute was settled.");
        }
        ledger.lock(loanId);
        Loan loan = require(loanId);
        if (loan.getHoldStatus() != LoanHold.DISPUTED) {
            throw new ConflictException("STALE_STATE", "This loan is not disputed.", loan.getHoldStatus().name());
        }
        String raisedBy = jdbc.query("select actor_id from loan_hold_event where loan_id = ? and to_hold = 'DISPUTED'"
                + " order by id desc limit 1", (rs, i) -> rs.getString(1), loanId).stream().findFirst().orElse(null);
        SeparationOfDutiesGuard.enforce(staffId(actor.id()), staffId(raisedBy));
        loan.setHoldStatus(LoanHold.NONE);
        loans.save(loan);
        event(loanId, LoanHold.DISPUTED, LoanHold.NONE, note.trim(), null, actor);
        return new DisputeView(loanId, LoanHold.NONE.name(), false, false, null);
    }

    @Transactional(readOnly = true)
    public List<HoldEventView> history(Long loanId) {
        CurrentActor actor = ActorContext.get();
        if (!ROLES.contains(actor.role()) && !"COMPLIANCE".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN, SUPPORT, COLLECTION_HEAD"
                    + " or COMPLIANCE");
        }
        return jdbc.query("select from_hold, to_hold, reason, complaint_id, actor_id, actor_role, at from loan_hold_event"
                + " where loan_id = ? order by id", (rs, i) -> new HoldEventView(rs.getString(1), rs.getString(2),
                rs.getString(3), (Long) rs.getObject(4), rs.getString(5), rs.getString(6),
                rs.getTimestamp(7).toInstant()), loanId);
    }

    private DisputeView place(Long loanId, String reason, Long complaintId, CurrentActor actor) {
        ledger.lock(loanId);
        Loan loan = require(loanId);
        if (loan.getLifecycleStatus() == LoanLifecycle.CLOSED) {
            throw new BusinessException("LOAN_CLOSED", "This loan is closed.");
        }
        if (loan.getHoldStatus() != LoanHold.NONE) {
            throw new ConflictException("STALE_STATE", "This loan is already on hold.", loan.getHoldStatus().name());
        }
        if (complaintId != null && jdbc.queryForObject("select count(*) from complaint where id = ? and customer_id = ?",
                Long.class, complaintId, loan.getCustomerId()) == 0) {
            throw new BusinessException("INVALID_COMPLAINT", "That complaint is not this borrower's.");
        }
        loan.setHoldStatus(LoanHold.DISPUTED);
        loans.save(loan);
        Instant now = clock.instant();
        boolean extension = extensions.cancelOffer(loanId, "DISPUTED", now);
        boolean emi = emis.cancelForDispute(loanId);
        event(loanId, LoanHold.NONE, LoanHold.DISPUTED, reason, complaintId, actor);
        return new DisputeView(loanId, LoanHold.DISPUTED.name(), extension, emi, complaintId);
    }

    private void event(Long loanId, LoanHold from, LoanHold to, String reason, Long complaintId, CurrentActor actor) {
        jdbc.update("insert into loan_hold_event (loan_id, from_hold, to_hold, reason, complaint_id, actor_id, actor_role,"
                        + " at) values (?, ?, ?, ?, ?, ?, ?, ?)", loanId, from.name(), to.name(), reason, complaintId,
                actor.id(), actor.role(), Timestamp.from(clock.instant()));
    }

    private Loan require(Long loanId) {
        return loans.findById(loanId).orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(loanId)));
    }

    private static CurrentActor requireRole() {
        CurrentActor actor = ActorContext.get();
        if (!ROLES.contains(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN, SUPPORT or COLLECTION_HEAD");
        }
        return actor;
    }

    private static Long staffId(String id) {
        try {
            return id == null ? null : Long.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
