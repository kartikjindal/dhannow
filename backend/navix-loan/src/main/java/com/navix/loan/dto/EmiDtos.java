package com.navix.loan.dto;

import com.navix.loan.entity.EmiConversion;
import com.navix.loan.service.RepaymentScheduleService.Instalment;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Request and response shapes for recovery EMI conversion (PLAN Phase 6c). Money is integer paise. */
public final class EmiDtos {

    private EmiDtos() {
    }

    /** Collections asks for a conversion. {@code acceptBy} defaults to the last day the offer may stay open. */
    public record EmiRequest(LocalDate acceptBy, String note) {
    }

    public record ReasonRequest(String reason, String note) {
    }

    /** Borrower accept: the OTP sent to the mobile on file, the consent text version shown, and the version read. */
    public record AcceptRequest(String otp, String consentVersion, Long version) {
    }

    /** Borrower decline: {@code NOT_NOW} or {@code DID_NOT_ASK}. */
    public record DeclineRequest(String reason, Long version) {
    }

    public record OtpSentView(boolean sent, int ttlSeconds, String devCode) {
    }

    /**
     * One instalment. {@code chargesPaise} is the late charge owed on the conversion date, spread like the
     * principal; {@code paidPaise}/{@code status} are null on a preview.
     */
    public record InstalmentView(int seq, LocalDate dueDate, Instant dueAt, long principalPaise, long chargesPaise,
                                 long totalPaise, Long paidPaise, String status) {

        public static List<InstalmentView> preview(List<Instalment> plan) {
            List<InstalmentView> out = new ArrayList<>();
            for (int i = 0; i < plan.size(); i++) {
                Instalment p = plan.get(i);
                out.add(new InstalmentView(i + 1, p.dueDate(), p.dueAt(), p.principalPaise(), p.penalPaise(),
                        p.totalPaise(), null, null));
            }
            return out;
        }
    }

    /** One conversion as staff see it. */
    public record EmiView(UUID id, Long loanId, String status, long version, int instalmentCount, int gapDays,
                          LocalDate acceptBy, Instant expiresAt, String note, Long requestedBy, Instant requestedAt,
                          Long approvedBy, Instant approvedAt, Long rejectedBy, String rejectReason, String rejectNote,
                          Long cancelledBy, String cancelReason, Instant acceptedAt, Instant declinedAt,
                          String declinedReason, LocalDate conversionDate, Long principalPaise, Long chargesPaise,
                          String interestMode, Integer scheduleVersion, Instant activatedAt) {

        public static EmiView of(EmiConversion e) {
            return new EmiView(e.getId(), e.getLoanId(), e.getStatus().name(), e.getVersion(), e.getInstalmentCount(),
                    e.getGapDays(), e.getAcceptBy(), e.getExpiresAt(), e.getNote(), e.getRequestedBy(),
                    e.getRequestedAt(), e.getApprovedBy(), e.getApprovedAt(), e.getRejectedBy(), e.getRejectReason(),
                    e.getRejectNote(), e.getCancelledBy(), e.getCancelReason(), e.getAcceptedAt(), e.getDeclinedAt(),
                    e.getDeclinedReason(), e.getConversionDate(), e.getPrincipalPaise(), e.getChargesPaise(),
                    e.getInterestMode(), e.getScheduleVersion(), e.getActivatedAt());
        }
    }

    /**
     * The loan's EMI panel: whether a conversion can be requested now and why not ({@code reasonCode}), the plan
     * it would make if accepted today, the open conversion and the history.
     */
    public record EmiPanel(Long loanId, boolean eligible, String reasonCode, long owedTodayPaise,
                           List<InstalmentView> previewForToday, EmiView current, List<EmiView> history) {
    }

    /**
     * What the borrower sees: no staff ids or notes. {@code instalments} is the plan for accepting today while
     * the offer is APPROVED, and the schedule itself once ACTIVE.
     */
    public record BorrowerEmiView(UUID id, Long loanId, String status, long version, LocalDate acceptBy,
                                  Instant expiresAt, int instalmentCount, long totalPaise,
                                  List<InstalmentView> instalments, String interestMode, Instant acceptedAt,
                                  Instant declinedAt, Instant activatedAt) {
    }
}
