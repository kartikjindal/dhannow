package com.navix.loan.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.common.verification.ManualReviewLiveness;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff "Selfie review" queue (PLAN Phase 8): every guided selfie the {@link ManualReviewLiveness} sent to
 * REVIEW. Approve passes the SELFIE check; reject needs a reason, fails it and reopens the step so the borrower
 * takes a new selfie. Both go through {@link ApplicationVerificationService#manualDecision}, so the role check
 * (credit team) and the audit trail are the ones every other manual verification decision uses.
 */
@Service
@RequiredArgsConstructor
public class SelfieReviewService {

    /** One case. {@code frames} are short-lived links to the straight, right and left photos. */
    public record SelfieReviewItem(Long applicationId, Long customerId, String borrowerName, Instant submittedAt,
                                   int attempts, Map<String, String> frames, String message) {
    }

    private final ApplicationVerificationRepository verificationRepo;
    private final LoanApplicationRepository applicationRepo;
    private final CustomerProfileRepository profileRepo;
    private final DocumentStoragePort storage;
    private final ApplicationVerificationService verification;
    private final VerificationOutreachService outreach;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<SelfieReviewItem> queue() {
        CreditTeamGuard.requireCreditTeamOr("Selfie review");
        return verificationRepo.findByCheckTypeAndStatus(ApplicationVerificationService.SELFIE,
                        ApplicationVerificationService.REVIEW).stream()
                .filter(v -> ManualReviewLiveness.PROVIDER.equals(v.getProvider()))
                .sorted(Comparator.comparing(ApplicationVerification::getUpdatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::item)
                .toList();
    }

    @Transactional
    public ApplicationVerificationService.StepResult approve(Long appId, String note) {
        requireInQueue(appId);
        return verification.manualDecision(appId, ApplicationVerificationService.SELFIE, true, note);
    }

    @Transactional
    public ApplicationVerificationService.StepResult reject(Long appId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("NOTE_REQUIRED", "Give a reason so the borrower knows what to fix");
        }
        requireInQueue(appId);
        ApplicationVerificationService.StepResult failed =
                verification.manualDecision(appId, ApplicationVerificationService.SELFIE, false, reason);
        // Send the borrower back to the selfie step (V74 reopen + journey rewind), with fresh tries.
        LoanApplication app = applicationRepo.findById(appId).orElse(null);
        if (app != null && app.getStatus() == ApplicationStatus.SANCTIONED) {
            outreach.share(appId, ApplicationVerificationService.SELFIE, VerificationOutreachService.Channel.COPY);
        }
        return failed;
    }

    private void requireInQueue(Long appId) {
        CreditTeamGuard.requireCreditTeamOr("Selfie review");
        boolean inQueue = verificationRepo.findByApplicationIdAndCheckType(appId, ApplicationVerificationService.SELFIE)
                .filter(v -> ApplicationVerificationService.REVIEW.equals(v.getStatus()))
                .isPresent();
        if (!inQueue) {
            throw new BusinessException("STALE_STATE", "This selfie is no longer waiting for review. Refresh the queue.");
        }
    }

    private SelfieReviewItem item(ApplicationVerification v) {
        Map<String, Object> derived;
        try {
            derived = v.getDerived() == null ? Map.of()
                    : objectMapper.readValue(v.getDerived(), new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            derived = Map.of();
        }
        Map<String, String> frames = new LinkedHashMap<>();
        for (String side : List.of("Straight", "Right", "Left")) {
            if (derived.get("frame" + side) instanceof String key) {
                frames.put(side.toLowerCase(java.util.Locale.ROOT), storage.presignDownload(key));
            }
        }
        LoanApplication app = applicationRepo.findById(v.getApplicationId()).orElse(null);
        String name = profileRepo.findByApplicationId(v.getApplicationId()).map(CustomerProfile::getFullName).orElse(null);
        Object attempts = derived.get(ApplicationVerificationService.SELFIE_ATTEMPTS);
        return new SelfieReviewItem(v.getApplicationId(), app == null ? null : app.getCustomerId(), name,
                v.getUpdatedAt(), attempts instanceof Number n ? n.intValue() : 0, frames, v.getMessage());
    }
}
