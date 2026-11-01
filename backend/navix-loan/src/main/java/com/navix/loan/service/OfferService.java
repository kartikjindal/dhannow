package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.dto.OfferDtos.DisbursalAccountRequest;
import com.navix.loan.dto.OfferDtos.DisbursalAccountView;
import com.navix.loan.dto.OfferDtos.EsignInitRequest;
import com.navix.loan.dto.OfferDtos.OfferSummaryView;
import com.navix.loan.dto.OfferDtos.ManualEsignRequest;
import com.navix.loan.dto.OfferDtos.ReferenceInput;
import com.navix.loan.dto.OfferDtos.ReferenceView;
import com.navix.loan.dto.LoanDocumentDtos.KfsView;
import com.navix.loan.dto.OfferDtos.SanctionLetterView;
import com.navix.loan.entity.ApplicationReference;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.ApplicationReferenceRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.service.ApplicationVerificationService.StepResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The borrower's post-sanction journey (revamp.md Phase 3): draw down an amount within the credit
 * team's ceiling, name two references, read and eSign the Key Fact Statement, and confirm where the
 * money lands. Everything here runs while the application is {@code SANCTIONED}; the last step hands
 * off to {@link ApplicationFlowService#acceptOffer} and the file leaves the borrower's hands.
 *
 * <p>The identity checks that sit inside this journey (DigiLocker, selfie, geo-address) are not here
 * — they are the existing {@code ApplicationVerificationService} steps, unchanged, just reached from
 * {@code /loan/*} instead of {@code /signup/*}. This class owns only what Phase 3 newly captures.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OfferService {

    /** IST cut-off for same-day release (revamp.md decision 37) — calendar days, no weekend roll. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime SAME_DAY_CUTOFF = LocalTime.of(18, 0);

    private final LoanApplicationRepository applicationRepo;
    private final CustomerProfileRepository profileRepo;
    private final ApplicationReferenceRepository referenceRepo;
    private final PennyDropGuard pennyDropGuard;
    private final ApplicationDocumentRepository documentRepo;
    private final ApplicationVerificationService verification;
    private final ApplicationFlowService flow;
    private final JourneyService journey;
    private final ProfileChangeLogger changeLogger;
    private final LoanDocumentService documents;
    private final PricingService pricing;
    private final MandateService mandateService;

    // ---------------------------------------------------------------- 1. amount

    /**
     * Record how much of the sanctioned ceiling the borrower actually wants (screen 1). The
     * application stays {@code SANCTIONED} — nothing moves until the disbursal account is confirmed
     * at the end of the journey.
     */
    @Transactional
    public LoanApplication chooseAmount(Long appId, long amountPaise) {
        LoanApplication app = requireSanctioned(appId);
        long ceiling = ceilingOf(app);
        if (amountPaise < LoanMath.MIN_LOAN_PAISE) {
            throw new BusinessException("AMOUNT_TOO_LOW", "Requested amount is below the minimum of ₹1,000");
        }
        if (amountPaise > ceiling) {
            throw new BusinessException("LIMIT_EXCEEDED", "Requested amount exceeds the sanctioned amount");
        }
        // DhanNow: only the ladder amounts of the frozen product version (the ceiling is already
        // capped at the customer's credit level by the sanction).
        pricing.requireAllowedAmount(app, amountPaise);
        app.setAmountRequested(amountPaise);
        applicationRepo.save(app);
        journey.advance(appId, JourneyService.OfferStep.OFFER_REPAYMENT_DATE);
        return app;
    }

    // ---------------------------------------------------------------- 4. references

    @Transactional(readOnly = true)
    public List<ReferenceView> references(Long appId) {
        return referenceRepo.findByApplicationIdOrderBySlotAsc(appId).stream()
                .map(r -> new ReferenceView(r.getSlot().intValue(), r.getFullName(), r.getMobile(),
                        r.getRelation()))
                .toList();
    }

    /**
     * Save both references (screen 4). Exactly two, and the three mobiles involved — the two contacts
     * and the borrower's own — must all differ (revamp.md decision 39): a reference who is the
     * borrower, or the same person listed twice, is not a reference.
     */
    @Transactional
    public List<ReferenceView> saveReferences(Long appId, List<ReferenceInput> inputs) {
        // ADMIN corrections (staff detail dialog) land here long after the borrower accepted the
        // offer, so they must not be held to SANCTIONED — that is the borrower's window, not theirs.
        boolean admin = "ADMIN".equals(ActorContext.get().role());
        LoanApplication app = admin
                ? applicationRepo.findById(appId)
                        .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)))
                : requireSanctioned(appId);
        if (inputs == null || inputs.size() != 2) {
            throw new BusinessException("REFERENCES_REQUIRED", "Two references are required");
        }
        String own = profileRepo.findByApplicationId(appId).map(CustomerProfile::getMobile).orElse(null);
        List<String> mobiles = new ArrayList<>();
        for (ReferenceInput in : inputs) {
            String mobile = digits(in.mobile());
            if (blank(in.fullName()) || mobile.length() != 10) {
                throw new BusinessException("REFERENCE_INVALID",
                        "Each reference needs a name and a 10-digit mobile number");
            }
            if (!ApplicationReference.RELATIONS.contains(in.relation())) {
                throw new BusinessException("REFERENCE_INVALID", "Choose a relation from the list");
            }
            if (own != null && mobile.equals(digits(own))) {
                throw new BusinessException("REFERENCE_IS_SELF",
                        "A reference cannot be your own mobile number");
            }
            if (mobiles.contains(mobile)) {
                throw new BusinessException("REFERENCE_DUPLICATE",
                        "Your two references must have different mobile numbers");
            }
            mobiles.add(mobile);
        }

        for (int i = 0; i < inputs.size(); i++) {
            ReferenceInput in = inputs.get(i);
            short slot = (short) (i + 1);
            ApplicationReference row = referenceRepo.findByApplicationIdAndSlot(appId, slot)
                    .orElseGet(ApplicationReference::new);
            String oldName = row.getFullName();
            String oldMobile = row.getMobile();
            String oldRelation = row.getRelation();

            row.setApplicationId(appId);
            row.setCustomerId(app.getCustomerId());
            row.setSlot(slot);
            row.setFullName(in.fullName().trim());
            row.setMobile(mobiles.get(i));
            row.setRelation(in.relation());
            referenceRepo.save(row);

            if (admin) {
                String prefix = "reference" + slot;
                changeLogger.logIfChanged(app.getCustomerId(), appId, prefix + ".fullName", oldName, row.getFullName());
                changeLogger.logIfChanged(app.getCustomerId(), appId, prefix + ".mobile", oldMobile, row.getMobile());
                changeLogger.logIfChanged(app.getCustomerId(), appId, prefix + ".relation", oldRelation, row.getRelation());
            }
        }
        // Only the borrower walking the journey moves the pointer; an admin edit must not rewind it.
        if (!admin) {
            journey.advance(appId, JourneyService.OfferStep.OFFER_SUMMARY);
        }
        return references(appId);
    }

    // ---------------------------------------------------------------- 5. summary

    /**
     * The loan summary (screen 5) — every number the borrower is shown before they commit, computed
     * on the pricing version frozen on this offer, which the disbursed loan will use too, so the
     * summary and the ledger can't disagree.
     */
    @Transactional(readOnly = true)
    public OfferSummaryView summary(Long appId) {
        LoanApplication app = requireSanctioned(appId);
        return quote(app, drawdownOf(app));
    }

    /**
     * The B1 price list (PLAN Phase 11): {@link #summary} for every ladder amount of the frozen product
     * version up to the sanctioned ceiling (which the sanction already capped at the credit level), on
     * the frozen pricing. A pre-V92 offer has no ladder and gets its one drawdown.
     */
    @Transactional(readOnly = true)
    public List<OfferSummaryView> quotes(Long appId) {
        LoanApplication app = requireSanctioned(appId);
        long ceiling = ceilingOf(app);
        List<Long> amounts = app.getProductVersionId() == null ? List.of(drawdownOf(app))
                : pricing.product(app.getProductVersionId()).allowedAmountsPaise().stream()
                        .filter(a -> a >= LoanMath.MIN_LOAN_PAISE && a <= ceiling).sorted().toList();
        return amounts.stream().map(a -> quote(app, a)).toList();
    }

    private OfferSummaryView quote(LoanApplication app, long principal) {
        LoanMath math = pricing.mathFor(app);
        int tenure = tenureDays(app, math);
        long fee = math.processingFeePaise(principal);
        long gst = math.gstPaise(principal);
        long net = math.netDisbursedPaise(principal);
        long interest = math.interestPaise(principal, tenure);
        LocalDate expectedDisbursal = expectedDisbursalDate(ZonedDateTime.now(IST));
        var p = math.pricing();
        return new OfferSummaryView(app.getId(), ceilingOf(app), principal, fee, gst, net, interest,
                math.totalRepayablePaise(principal, tenure), tenure, repaymentDate(app, math, expectedDisbursal),
                expectedDisbursal, bps(p.pfRate()), bps(p.gstRate()), bps(p.dailyRate()));
    }

    /** A rate fraction as basis points: 0.10 → 1000. */
    private static int bps(java.math.BigDecimal rate) {
        return rate == null ? 0 : rate.movePointRight(4).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
    }

    /**
     * When the borrower should expect the money: today if it is before 18:00 IST, otherwise tomorrow
     * (revamp.md decision 37). Calendar days as stated — deliberately no weekend or holiday roll,
     * even though a real NEFT/IMPS release would see one.
     */
    static LocalDate expectedDisbursalDate(ZonedDateTime nowIst) {
        return nowIst.toLocalTime().isBefore(SAME_DAY_CUTOFF)
                ? nowIst.toLocalDate()
                : nowIst.toLocalDate().plusDays(1);
    }

    // ---------------------------------------------------------------- 6. KFS

    /**
     * The Key Fact Statement for this offer (PLAN Phase 5, journey step KFS after the summary): a link
     * to read it and whether it is acknowledged. A lapsed offer is re-quoted here (CC-02).
     */
    @Transactional
    public KfsView kfs(Long appId) {
        KfsView view = documents.kfs(requireSanctioned(appId));
        journey.advance(appId, JourneyService.OfferStep.OFFER_KFS);
        return view;
    }

    /** The borrower acknowledges the KFS; required before e-sign. */
    @Transactional
    public KfsView acknowledgeKfs(Long appId, String ip, String userAgent) {
        KfsView view = documents.acknowledgeKfs(requireSanctioned(appId), ip, userAgent);
        journey.advance(appId, JourneyService.OfferStep.OFFER_SELFIE);
        return view;
    }

    // ---------------------------------------------------------------- 8. sanction letter

    /**
     * Issue the sanction letter and loan agreement (screen 8) and return a short-lived link to the
     * pack the borrower signs. Re-issued on every visit: the borrower may have changed their drawdown
     * amount since they last looked, and a stale pack is one they'd then e-sign.
     */
    @Transactional
    public SanctionLetterView sanctionLetter(Long appId) {
        SanctionLetterView view = documents.signingPack(requireSanctioned(appId));
        journey.advance(appId, JourneyService.OfferStep.OFFER_SANCTION_LETTER);
        return view;
    }

    // ---------------------------------------------------------------- 9. eSign

    /**
     * Start Aadhaar e-sign of the pack (screen 9) — returns the provider URL to redirect the borrower
     * to in {@code derived.url}, or {@code derived.fallback=true} when the provider is unavailable and
     * they should draw a signature instead. The KFS must be acknowledged on an open offer first.
     */
    @Transactional
    public StepResult esignInit(Long appId, EsignInitRequest req) {
        documents.requireKfsAcknowledged(requireSanctioned(appId));
        return verification.esignInit(appId, req.successRedirectUrl(), req.failureRedirectUrl());
    }

    /**
     * Poll the signing session. The journey only advances once the signature is actually captured — a
     * PENDING poll must not let the borrower past the one step that gates disbursement. The signed
     * copy is then recorded as the {@code AGREEMENT} document.
     */
    @Transactional
    public StepResult esignStatus(Long appId, String ip) {
        LoanApplication app = requireSanctioned(appId);
        StepResult result = verification.esignStatus(appId);
        if (ApplicationVerificationService.PASS.equals(result.status())) {
            documents.recordSignedAgreement(app, signedAt(result), ip, evidence("AADHAAR_ESIGN", result));
            journey.advance(appId, JourneyService.OfferStep.OFFER_SANCTIONED);
        }
        return result;
    }

    @Transactional
    public StepResult manualEsign(Long appId, ManualEsignRequest req, String ip) {
        LoanApplication app = requireSanctioned(appId);
        documents.requireKfsAcknowledged(app);
        StepResult result = verification.recordManualEsign(appId, req.signatureDataUrl(),
                req.latitude(), req.longitude(), req.accuracyMeters());

        CustomerProfile profile = profileRepo.findByApplicationId(appId).orElse(null);
        Instant signedAt = signedAt(result);
        byte[] signature = Base64.getDecoder().decode(
                req.signatureDataUrl().substring(req.signatureDataUrl().indexOf(',') + 1));
        documents.storeDrawnSignature(app, signature, profile != null ? profile.getFullName() : "Borrower", signedAt);
        documents.recordSignedAgreement(app, signedAt, ip, evidence("DRAWN_SIGNATURE", result));

        journey.advance(appId, JourneyService.OfferStep.OFFER_SANCTIONED);
        return result;
    }

    private static Instant signedAt(StepResult result) {
        Object at = result.derived() == null ? null : result.derived().get("signedAt");
        return at == null ? null : Instant.parse(String.valueOf(at));
    }

    /** What the signature record keeps: how it was signed and the provider's reference, never the URL. */
    private static Map<String, Object> evidence(String method, StepResult result) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("method", method);
        if (result.derived() != null) {
            result.derived().forEach((k, v) -> {
                if (!"url".equals(k)) {
                    e.put(k, v);
                }
            });
        }
        return e;
    }

    // ---------------------------------------------------------------- 11. disbursal account

    /**
     * What the last screen prefills: the salary account credit already saw. The borrower confirms it
     * as-is (no penny drop, decision 9) or types another (penny drop, and the 3-strikes rule applies).
     */
    @Transactional(readOnly = true)
    public DisbursalAccountView disbursalAccount(Long appId) {
        LoanApplication app = requireSanctioned(appId);
        CustomerProfile p = profileRepo.findByApplicationId(appId).orElse(null);
        Instant lockedUntil = pennyDropGuard.lockedUntil(app.getCustomerId()).orElse(null);
        String account = firstNonBlank(app.getDisbursalAccountNumber(), p != null ? p.getSalaryAccountNumber() : null);
        String ifsc = firstNonBlank(app.getDisbursalIfsc(), p != null ? p.getSalaryIfsc() : null);
        boolean reusable = Boolean.TRUE.equals(app.getDisbursalAccountVerified())
                && matches(account, ifsc, app.getDisbursalAccountNumber(), app.getDisbursalIfsc());
        return new DisbursalAccountView(
                account,
                ifsc,
                firstNonBlank(app.getDisbursalHolderName(), p != null ? p.getFullName() : null),
                firstNonBlank(app.getDisbursalBank(), p != null ? p.getSalaryBank() : null),
                reusable,
                !reusable,
                lockedUntil,
                pennyDropGuard.remainingAttempts(app.getCustomerId()));
    }

    /**
     * Confirm where this advance is paid (screen 11) and hand the application to disbursement.
     *
     * <p>The penny drop fires <b>only</b> when the account differs from the salary account on file
     * (revamp.md decision 9) — confirming the salary account unchanged never costs a provider call and
     * never can fail. That is the accepted trade-off: the first disbursal can go to an account number
     * that was only ever typed in, with the Credit Executive's read of the file as the sole check.
     *
     * <p>A changed account that fails counts a strike; three inside a window earns a 12-hour lock, and
     * a success clears the count outright (decision 40).
     */
    @Transactional
    public LoanApplication confirmDisbursalAccount(Long appId, DisbursalAccountRequest req) {
        LoanApplication app = requireSanctioned(appId);
        if (app.getAmountRequested() == null) {
            throw new BusinessException("AMOUNT_NOT_CHOSEN", "Choose your loan amount first");
        }
        String account = digits(req.accountNumber());
        String ifsc = req.ifsc() == null ? "" : req.ifsc().trim().toUpperCase();
        if (account.length() < 6 || ifsc.length() != 11) {
            throw new BusinessException("ACCOUNT_INVALID",
                    "Invalid account details, please enter the bank details again.");
        }

        CustomerProfile p = profileRepo.findByApplicationId(appId).orElse(null);
        // "Changed" means changed from an account we already have reason to trust — either the
        // salary account credit verified the file against, or (on a re-apply) the account the last
        // advance was actually paid into, carried over by ApplicationFlowService. Matching either is
        // not a change, so no penny drop fires (revamp.md decisions 9, 45). Computed before either
        // branch writes to the application, since both compare against the PREVIOUS destination.
        boolean changed = !matches(account, ifsc, p == null ? null : p.getSalaryAccountNumber(),
                p == null ? null : p.getSalaryIfsc())
                && !matches(account, ifsc, app.getDisbursalAccountNumber(), app.getDisbursalIfsc());

        if (req.useBankProof()) {
            // The escape hatch for a borrower the penny drop has locked out (typically a name-at-bank
            // mismatch three strikes running): a cancelled cheque or passbook stands in for the
            // provider check, judged later by the Disbursement Head. This bypasses the strikes lock
            // deliberately — it IS the way out of that lock — and never calls verifyPennyDrop or
            // PennyDropGuard.record, so it can neither trip nor be blocked by the 3-strikes rule.
            if (documentRepo.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                    appId, ApplicationVerificationService.BANK_PROOF).isEmpty()) {
                throw new BusinessException("BANK_PROOF_REQUIRED",
                        "Upload a cancelled cheque or passbook first");
            }
            app.setDisbursalAccountNumber(account);
            app.setDisbursalIfsc(ifsc);
            app.setDisbursalHolderName(firstNonBlank(req.holderName(), p != null ? p.getFullName() : null));
            app.setDisbursalBank(firstNonBlank(req.bank(), p != null ? p.getSalaryBank() : null));
            // The real value, not a blanket true: this column records whether the money is going
            // somewhere other than the salary account credit saw, and a borrower who simply could
            // not verify the account they have always used has not changed anything. Hardcoding it
            // would misreport them to the release desk as having redirected their payout.
            app.setDisbursalAccountChanged(changed);
            app.setDisbursalAccountVerified(false);
            app.setDisbursalConfirmedAt(Instant.now());
            applicationRepo.save(app);

            verification.recordBankProofPending(appId, account, ifsc);

            return afterAccountConfirmed(app);
        }
        boolean verified = Boolean.TRUE.equals(app.getDisbursalAccountVerified())
                && matches(account, ifsc, app.getDisbursalAccountNumber(), app.getDisbursalIfsc());
        if (!verified) {
            if (pennyDropGuard.lockedUntil(app.getCustomerId()).isPresent()) {
                throw new BusinessException("PENNY_DROP_LOCKED",
                        "Too many failed attempts. Please try again after "
                                + PennyDropGuard.LOCK.toHours() + " hours.");
            }
            StepResult drop = verification.verifyPennyDrop(appId, account, ifsc, true);
            verified = ApplicationVerificationService.PASS.equals(drop.status());
            // Committed in its own transaction — this method throws on failure, and a strike that
            // rolled back with the rejection would never move the counter.
            pennyDropGuard.record(app.getCustomerId(), appId, account, ifsc, verified,
                    verified ? null : drop.message());
            if (!verified) {
                throw new BusinessException("ACCOUNT_INVALID",
                        "Invalid account details, please enter the bank details again.");
            }
        }

        app.setDisbursalAccountNumber(account);
        app.setDisbursalIfsc(ifsc);
        app.setDisbursalHolderName(firstNonBlank(req.holderName(), p != null ? p.getFullName() : null));
        app.setDisbursalBank(firstNonBlank(req.bank(), p != null ? p.getSalaryBank() : null));
        app.setDisbursalAccountChanged(changed);
        app.setDisbursalAccountVerified(verified);
        app.setDisbursalConfirmedAt(Instant.now());
        applicationRepo.save(app);

        return afterAccountConfirmed(app);
    }

    /**
     * Both exits of {@link #confirmDisbursalAccount} (bank-proof and penny-drop) route here. With the
     * {@code digio-mandate} flag on the borrower registers an eMandate next; {@link #mandateStatus}
     * is what hands the file off once a UMRN lands. Flag off: straight to the Disbursement Head.
     */
    private LoanApplication afterAccountConfirmed(LoanApplication app) {
        Long appId = app.getId();
        if (mandateService.mandateRequired(app)) {
            journey.advance(appId, JourneyService.OfferStep.OFFER_MANDATE);
            return app;
        }
        journey.advance(appId, JourneyService.OfferStep.OFFER_DONE);
        // Hands off: SANCTIONED → DISBURSEMENT_PENDING, gated on the eSign row existing.
        return flow.acceptOffer(appId, app.getAmountRequested());
    }

    // ---------------------------------------------------------------- 11. eMandate

    /**
     * Statuses from which a borrower may set up a mandate. Deliberately wide: the capability is now
     * offered to anyone without one (a live loan never saw the mandate step), and the money guards that
     * matter — the kill switch, the account having been proven, the attempt cap — live in
     * {@link MandateService#init} where every caller passes through them.
     *
     * <p>What is excluded is what has nothing to mandate or no business mandating: a file still being
     * decided (no proven account yet) and a dead one. DEFAULTED and WRITTEN_OFF are in, because a
     * mandate is exactly how a defaulted balance might still be collected.
     */
    private static final java.util.Set<ApplicationStatus> MANDATE_SETUP_ALLOWED = java.util.EnumSet.of(
            ApplicationStatus.PRE_APPROVED, ApplicationStatus.SANCTIONED, ApplicationStatus.DISBURSEMENT_PENDING,
            ApplicationStatus.DISBURSAL_CONFIRMATION_PENDING, ApplicationStatus.DISBURSEMENT_FAILED, ApplicationStatus.DISBURSED, ApplicationStatus.ACTIVE,
            ApplicationStatus.OVERDUE, ApplicationStatus.DEFAULTED, ApplicationStatus.WRITTEN_OFF,
            ApplicationStatus.CLOSED);

    /**
     * PRE_APPROVED is admitted alongside SANCTIONED on purpose. The reborrow fast-track in
     * {@code ApplicationFlowService.apply} gates on the same mandate but has no offer journey behind it,
     * so a borrower parked there was told MANDATE_REQUIRED with no endpoint that would let them register
     * one — a dead end not even ADMIN could rescue. The money guards (kill switch, proven account,
     * attempt cap) live in {@code MandateService.init} and apply to both statuses alike.
     */
    @Transactional
    public StepResult mandateInit(Long appId) {
        LoanApplication app = applicationRepo.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)));
        if (!MANDATE_SETUP_ALLOWED.contains(app.getStatus())) {
            throw new BusinessException("NOT_APPLICABLE", "This offer isn't ready");
        }
        return mandateService.init(appId);
    }

    /** UMRN present means the borrower may proceed: that, and only that, hands the file off. */
    @Transactional
    public StepResult mandateStatus(Long appId) {
        LoanApplication app = applicationRepo.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)));
        if (app.getStatus() != ApplicationStatus.SANCTIONED) {
            // The file has already left the borrower's hands — a repeat poll after hand-off (second tab,
            // retry), or a staff override that pushed a BANK_UNSUPPORTED file on without a mandate. Report
            // the stored row; throwing NOT_APPLICABLE here left the page polling "Still waiting" for ever
            // while the advance was actually moving.
            return mandateService.status(appId);
        }
        StepResult result = mandateService.status(appId);
        // disbursalConfirmedAt is load-bearing, not a nicety: with the flag off (the shipped default)
        // mandateRequired is false for EVERY application, and acceptOffer checks the eSign but not the
        // account — so without this a poll on any sanctioned, eSigned file would queue it for
        // disbursement with no account number, no IFSC and no penny drop.
        if (app.getDisbursalConfirmedAt() != null && !mandateService.mandateRequired(app)) {
            // Either the UMRN landed, or the kill switch was turned off under a borrower who had already
            // confirmed their account — in which case this is the pre-feature hand-off, not a skipped step.
            journey.advance(appId, JourneyService.OfferStep.OFFER_DONE);
            flow.acceptOffer(appId, app.getAmountRequested());
            return ApplicationVerificationService.PASS.equals(result.status()) ? result
                    : new StepResult(MandateService.CHECK_TYPE, ApplicationVerificationService.PASS,
                            "eMandate not required", java.util.Map.of());
        }
        return result;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Every step here is the borrower's own, on an application a Credit Executive has sanctioned and
     * that has not yet left their hands. Ownership is checked by the controller; this guards the state.
     */
    private LoanApplication requireSanctioned(Long appId) {
        LoanApplication app = applicationRepo.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)));
        if (app.getStatus() != ApplicationStatus.SANCTIONED) {
            throw new BusinessException("NOT_APPLICABLE", "This offer isn't ready");
        }
        return app;
    }

    private static long ceilingOf(LoanApplication app) {
        return app.getSanctionedAmountPaise() != null ? app.getSanctionedAmountPaise() : 0L;
    }

    /** What the borrower has chosen to draw, defaulting to the full ceiling until they pick. */
    private static long drawdownOf(LoanApplication app) {
        return app.getAmountRequested() != null ? app.getAmountRequested() : ceilingOf(app);
    }

    /**
     * DhanNow (UPFRONT): the frozen version's fixed tenure, the same source {@code LoanService.disburse}
     * uses. Navix: days from today to the credit team's repayment date. Recomputed rather than read off
     * {@code sanction_tenure_days}, which was measured on the day of sanction — a borrower who
     * returns a week later would otherwise be quoted a week of interest they won't be charged.
     * Never below 1: a sanction never expires (decision 41), so the date can be in the past.
     */
    private int tenureDays(LoanApplication app, LoanMath math) {
        if (math.pricing().upfront()) {
            return math.pricing().tenureDays();
        }
        LocalDate due = app.getApprovedRepaymentDate();
        if (due == null) {
            return app.getSanctionTenureDays() != null ? app.getSanctionTenureDays() : LoanMath.TERM_DAYS;
        }
        long days = ChronoUnit.DAYS.between(LocalDate.now(IST), due);
        return (int) Math.max(1, days);
    }

    /**
     * The repayment date the offer states. DhanNow: indicative only, 14 days from the expected
     * disbursal; the final date is fixed when the disbursal is confirmed (CC-01). Navix: the credit
     * team's approved date.
     */
    static LocalDate repaymentDate(LoanApplication app, LoanMath math, LocalDate expectedDisbursal) {
        return math.pricing().upfront() ? math.dueDate(expectedDisbursal, null) : app.getApprovedRepaymentDate();
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    /**
     * Whether the typed account is the same one as {@code refAccount}/{@code refIfsc}. A blank
     * reference never matches — "we have nothing on file" must read as a change (and so a penny
     * drop), not as agreement.
     */
    private static boolean matches(String account, String ifsc, String refAccount, String refIfsc) {
        String ref = digits(refAccount);
        if (ref.isEmpty() || blank(refIfsc)) {
            return false;
        }
        return account.equals(ref) && ifsc.equals(refIfsc.trim().toUpperCase());
    }

    private static String firstNonBlank(String a, String b) {
        return !blank(a) ? a : b;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
