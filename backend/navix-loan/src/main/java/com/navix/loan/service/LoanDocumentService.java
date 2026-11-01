package com.navix.loan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.brand.BrandProperties;
import com.navix.common.brand.GrievanceProperties;
import com.navix.common.brand.LenderProperties;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.notification.event.ApplicationTransitionedEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.loan.domain.LoanDocumentType;
import com.navix.loan.domain.LoanLifecycle;
import com.navix.loan.domain.PricingSnapshot;
import com.navix.loan.dto.LoanDocumentDtos.DocumentUrlView;
import com.navix.loan.dto.LoanDocumentDtos.KfsView;
import com.navix.loan.dto.LoanDocumentDtos.LoanDocumentView;
import com.navix.loan.dto.OfferDtos.SanctionLetterView;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.LoanDocument;
import com.navix.loan.pdf.DocumentFacts;
import com.navix.loan.pdf.KfsPdfRenderer;
import com.navix.loan.pdf.LoanAgreementPdfRenderer;
import com.navix.loan.pdf.SanctionLetterPdfRenderer;
import com.navix.loan.repository.ApplicationDocumentRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import com.navix.loan.repository.LoanDocumentRepository;
import com.navix.loan.repository.LoanRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * DhanNow loan documents (PLAN Phase 5): the KFS, the sanction letter + agreement signing pack, the
 * signed agreement and the repayment schedule, each a versioned {@code loan_document} row with its S3
 * key and SHA-256. Every document is rendered from one {@link DocumentFacts}, built here from the
 * pricing snapshot frozen on the offer through {@link LoanMath}, so the documents, the offer summary
 * and the ledger show the same figures.
 *
 * <p>The KFS must be acknowledged before e-sign ({@link #requireKfsAcknowledged}). An acknowledgement
 * binds to the offer it was shown for: the same amount, the same pricing version, and an offer still
 * inside its validity window (CC-02). A lapsed offer is re-quoted on the current pricing when the KFS
 * is next opened, which issues a new KFS to acknowledge.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LoanDocumentService {

    /** How long a document download link lives. */
    static final Duration URL_TTL = Duration.ofMinutes(5);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LoanDocumentRepository documents;
    private final LoanApplicationRepository applications;
    private final LoanRepository loans;
    private final CustomerProfileRepository profiles;
    private final ApplicationDocumentRepository applicationDocuments;
    private final PricingService pricing;
    private final KfsPdfRenderer kfsRenderer;
    private final SanctionLetterPdfRenderer sanctionRenderer;
    private final LoanAgreementPdfRenderer agreementRenderer;
    private final DocumentStoragePort storage;
    private final LenderProperties lender;
    private final BrandProperties brand;
    private final GrievanceProperties grievance;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    @Value("${navix.apr.method:IRR}")
    private AprCalculator.Method apr = AprCalculator.Method.IRR;

    // ---------------------------------------------------------------- facts

    /**
     * What every document states, on the terms frozen on the offer (or on the loan, once minted). Before
     * the disbursal is confirmed the due date is indicative: expected disbursal + tenure (CC-01).
     */
    public DocumentFacts facts(LoanApplication app, Loan loan) {
        LoanMath math = loan != null ? pricing.mathFor(loan) : pricing.mathFor(app);
        PricingSnapshot p = math.pricing();
        var product = pricing.product(loan != null ? loan.getProductVersionId() : app.getProductVersionId());
        long principal = loan != null ? loan.getPrincipal() : drawdownOf(app);
        int tenure = p.upfront() ? p.tenureDays()
                : app.getSanctionTenureDays() != null ? app.getSanctionTenureDays() : LoanMath.TERM_DAYS;
        long net = math.netDisbursedPaise(principal);
        long total = math.totalRepayablePaise(principal, tenure);
        boolean confirmed = loan != null && loan.getLifecycleStatus() != LoanLifecycle.DISBURSEMENT_PENDING;
        LocalDate due = confirmed ? loan.getDueDate()
                : OfferService.repaymentDate(app, math, OfferService.expectedDisbursalDate(ZonedDateTime.now(clock)));
        CustomerProfile profile = profiles.findByApplicationId(app.getId()).orElse(null);
        return new DocumentFacts(
                app.getId(),
                loan != null ? loan.getId() : app.getLoanId(),
                1,
                LocalDate.now(clock.withZone(IST)),
                profile != null ? profile.getFullName() : null,
                profile != null ? profile.getMobile() : null,
                profile != null ? profile.getEmail() : null,
                profile != null ? profile.getAddress() : null,
                profile != null ? mask(profile.getPan(), 4) : null,
                lender, brand, grievance,
                p.version(),
                principal,
                p.pfRate(), math.processingFeePaise(principal),
                p.gstRate(), math.gstPaise(principal),
                p.dailyRate(), tenure, p.upfront(), math.interestPaise(principal, tenure),
                net, total,
                apr.aprPercent(net, total, tenure), apr.name(),
                p.penalEnabled(), p.penalRate(), p.penalCapDays(), math.latePenaltyPaise(principal, p.penalCapDays()),
                p.prepayRebate(),
                confirmed ? loan.getDisbursedOn() : null,
                due,
                product.dueCutoff(),
                app.getOfferValidUntil() == null ? null : LocalDate.ofInstant(app.getOfferValidUntil(), IST),
                app.getPurpose(),
                maskAccount(app.getDisbursalAccountNumber() != null ? app.getDisbursalAccountNumber()
                        : profile != null ? profile.getSalaryAccountNumber() : null),
                null);
    }

    // ---------------------------------------------------------------- KFS

    /**
     * The offer's KFS. Reused while it still describes the offer (same amount and pricing version);
     * otherwise, or after a lapsed offer is re-quoted, a new version is issued.
     */
    @Transactional
    public KfsView kfs(LoanApplication app) {
        boolean requoted = requoteIfLapsed(app);
        LoanDocument kfs = latest(app.getId(), LoanDocumentType.KFS)
                .filter(d -> !requoted && describesOffer(d, app))
                .orElseGet(() -> issue(app, LoanDocumentType.KFS, kfsRenderer::render));
        return kfsView(app, kfs);
    }

    /** The borrower acknowledges the KFS they were shown; idempotent. */
    @Transactional
    public KfsView acknowledgeKfs(LoanApplication app, String ip, String userAgent) {
        if (lapsed(app, now())) {
            throw expired();
        }
        LoanDocument kfs = latest(app.getId(), LoanDocumentType.KFS)
                .filter(d -> describesOffer(d, app))
                .orElseThrow(() -> new BusinessException("KFS_NOT_ISSUED", "Open your Key Fact Statement first"));
        if (kfs.getAcceptedAt() == null) {
            kfs.setAcceptedAt(now());
            kfs.setAcceptedIp(ip);
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("action", "KFS_ACKNOWLEDGED");
            evidence.put("actorId", actorId());
            evidence.put("customerId", app.getCustomerId());
            evidence.put("userAgent", userAgent);
            evidence.put("sha256", kfs.getSha256());
            kfs.setAcceptanceEvidence(toJson(evidence));
            documents.save(kfs);
        }
        return kfsView(app, kfs);
    }

    /** The e-sign gate: the offer is open and its current KFS is acknowledged (CC-02). */
    public void requireKfsAcknowledged(LoanApplication app) {
        Instant now = now();
        if (lapsed(app, now)) {
            throw expired();
        }
        if (!kfsAcknowledged(app, latest(app.getId(), LoanDocumentType.KFS).orElse(null), now)) {
            throw new BusinessException("KFS_NOT_ACKNOWLEDGED",
                    "Read and accept the Key Fact Statement before you sign the loan agreement");
        }
    }

    /** Whether {@code latestKfs} is an acknowledgement of {@code app}'s current, unexpired offer. */
    static boolean kfsAcknowledged(LoanApplication app, LoanDocument latestKfs, Instant now) {
        return latestKfs != null && latestKfs.getAcceptedAt() != null && describesOffer(latestKfs, app)
                && !lapsed(app, now);
    }

    // ---------------------------------------------------------------- signing pack and signature

    /**
     * Issue the sanction letter and the agreement, and store the pack the borrower e-signs (sanction
     * letter + agreement) as the navix {@code SANCTION_LETTER} application document, which the
     * unchanged e-sign flow signs. Re-issued on every visit, so a changed amount is never signed stale.
     */
    @Transactional
    public SanctionLetterView signingPack(LoanApplication app) {
        int version = nextVersion(app.getId(), LoanDocumentType.SANCTION);
        DocumentFacts f = facts(app, null).withVersion(version);
        byte[] letter = sanctionRenderer.render(f);
        save(app, LoanDocumentType.SANCTION, version, letter, f);
        byte[] pack = agreementRenderer.renderPack(letter, f);
        String key = "applications/" + app.getId() + "/sanction_letter/sanction-letter.pdf";
        ApplicationDocument doc = storeApplicationDocument(app.getId(), ApplicationVerificationService.SANCTION_LETTER,
                key, "sanction-letter.pdf", pack);
        return new SanctionLetterView(doc.getId(), storage.presignDownload(key));
    }

    /** A drawn signature: re-render the pack with the signature and store it as the signed agreement. */
    @Transactional
    public void storeDrawnSignature(LoanApplication app, byte[] signaturePng, String signerName, Instant signedAt) {
        int version = latest(app.getId(), LoanDocumentType.SANCTION).map(LoanDocument::getVersion).orElse(1);
        DocumentFacts f = facts(app, null).withVersion(version).withAcceptedAt(signedAt);
        byte[] signed = agreementRenderer.renderSignedPack(sanctionRenderer.render(f), f, signaturePng,
                signerName, signedAt);
        storeApplicationDocument(app.getId(), ApplicationVerificationService.SIGNED_AGREEMENT,
                "applications/" + app.getId() + "/signed_agreement/sanction-letter-signed.pdf",
                "sanction-letter-signed.pdf", signed);
    }

    /**
     * Record the signed agreement as the {@code AGREEMENT} loan document (the signed copy the e-sign
     * flow stored as {@code SIGNED_AGREEMENT}) and stamp the sanction letter accepted. Idempotent: a
     * status poll after the webhook already signed (CC-32) records it once.
     */
    @Transactional
    public void recordSignedAgreement(LoanApplication app, Instant signedAt, String ip, Map<String, Object> evidence) {
        if (latest(app.getId(), LoanDocumentType.AGREEMENT).isPresent()) {
            return;
        }
        ApplicationDocument signed = applicationDocuments.findFirstByApplicationIdAndDocTypeOrderByIdDesc(
                        app.getId(), ApplicationVerificationService.SIGNED_AGREEMENT)
                .orElseThrow(() -> new BusinessException("SIGNED_AGREEMENT_UNAVAILABLE",
                        "We couldn't find your signed agreement. Please try again."));
        LoanDocument sanction = latest(app.getId(), LoanDocumentType.SANCTION).orElse(null);
        LoanDocument kfs = latest(app.getId(), LoanDocumentType.KFS).orElse(null);
        Instant at = signedAt != null ? signedAt : now();

        LoanDocument agreement = new LoanDocument();
        agreement.setApplicationId(app.getId());
        agreement.setLoanId(app.getLoanId());
        agreement.setType(LoanDocumentType.AGREEMENT);
        agreement.setVersion(sanction != null ? sanction.getVersion() : 1);
        agreement.setPricingVersionId(app.getPricingVersionId());
        agreement.setAmountPaise(drawdownOf(app));
        agreement.setS3Key(signed.getS3ObjectKey());
        agreement.setSha256(hashOfStored(signed.getS3ObjectKey()));
        agreement.setCreatedAt(now());
        agreement.setAcceptedAt(at);
        agreement.setAcceptedIp(ip);
        Map<String, Object> record = new LinkedHashMap<>(evidence);
        record.put("action", "AGREEMENT_SIGNED");
        record.put("customerId", app.getCustomerId());
        record.put("kfsDocumentId", kfs != null ? kfs.getId() : null);
        record.put("sanctionDocumentId", sanction != null ? sanction.getId() : null);
        agreement.setAcceptanceEvidence(toJson(record));
        documents.save(agreement);

        if (sanction != null && sanction.getAcceptedAt() == null) {
            sanction.setAcceptedAt(at);
            sanction.setAcceptedIp(ip);
            documents.save(sanction);
        }
    }

    // ---------------------------------------------------------------- schedule (CC-01)

    /**
     * Issue the repayment schedule with the final due date once the disbursal is confirmed (CC-01).
     * After the confirmation commits, in its own transaction: a storage failure is logged and never
     * undoes the Accountant's confirmation.
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onLoanActivated(ApplicationTransitionedEvent event) {
        if (!"ACTIVATE".equals(event.action()) || event.loanId() == null) {
            return;
        }
        try {
            issueSchedule(event.loanId());
        } catch (RuntimeException e) {
            log.error("Repayment schedule not issued for loan {}: {}", event.loanId(), e.toString());
        }
    }

    void issueSchedule(Long loanId) {
        if (documents.existsByLoanIdAndType(loanId, LoanDocumentType.SCHEDULE)) {
            return;
        }
        Loan loan = loans.findById(loanId).orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(loanId)));
        LoanApplication app = applications.findByLoanId(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", "loan " + loanId));
        int version = nextVersion(app.getId(), LoanDocumentType.SCHEDULE);
        DocumentFacts f = facts(app, loan).withVersion(version);
        save(app, LoanDocumentType.SCHEDULE, version, kfsRenderer.renderSchedule(f), f);
    }

    // ---------------------------------------------------------------- access

    /** A loan's documents: the borrower's own, or any loan for staff (DSA excluded). */
    @Transactional(readOnly = true)
    public List<LoanDocumentView> list(Long loanId) {
        Loan loan = requireAccess(loanId);
        Long appId = applications.findByLoanId(loan.getId()).map(LoanApplication::getId).orElse(null);
        return documents.findByApplicationIdOrLoanIdOrderByIdAsc(appId, loanId).stream()
                .map(LoanDocumentView::of)
                .toList();
    }

    /** A 5-minute download link for one of a loan's documents. Staff reads are audited. */
    @Transactional
    public DocumentUrlView url(Long loanId, Long documentId) {
        Loan loan = requireAccess(loanId);
        Long appId = applications.findByLoanId(loan.getId()).map(LoanApplication::getId).orElse(null);
        LoanDocument doc = documents.findById(documentId)
                .filter(d -> Objects.equals(d.getLoanId(), loanId)
                        || (appId != null && Objects.equals(d.getApplicationId(), appId)))
                .orElseThrow(() -> new ResourceNotFoundException("LoanDocument", String.valueOf(documentId)));
        if (doc.getS3Key() == null) {
            throw new BusinessException("DOCUMENT_UNAVAILABLE", "This document is not available to download");
        }
        var actor = ActorContext.get();
        if (!"BORROWER".equals(actor.role())) {
            jdbc.update("insert into loan_document_access (loan_document_id, actor_id, actor_role, at) values (?, ?, ?, ?)",
                    doc.getId(), actor.id(), actor.role(), java.sql.Timestamp.from(now()));
        }
        return new DocumentUrlView(doc.getId(), storage.presignDownload(doc.getS3Key(), URL_TTL), URL_TTL.toSeconds());
    }

    private Loan requireAccess(Long loanId) {
        var actor = ActorContext.get();
        if ("DSA".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "DSAs cannot view loan documents");
        }
        Loan loan = loans.findById(loanId).orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(loanId)));
        if ("BORROWER".equals(actor.role()) && !String.valueOf(loan.getCustomerId()).equals(actor.id())) {
            throw new BusinessException("FORBIDDEN_OWNER", "Not your loan");
        }
        return loan;
    }

    // ---------------------------------------------------------------- helpers

    /** CC-02: a lapsed offer is re-frozen on the current pricing with a fresh validity window. */
    private boolean requoteIfLapsed(LoanApplication app) {
        if (!lapsed(app, now())) {
            return false;
        }
        pricing.freeze(app);
        applications.save(app);
        log.info("Offer {} lapsed; re-quoted on pricing version {}", app.getId(), app.getPricingVersionId());
        return true;
    }

    private static boolean lapsed(LoanApplication app, Instant now) {
        return app.getOfferValidUntil() != null && !now.isBefore(app.getOfferValidUntil());
    }

    private static boolean describesOffer(LoanDocument d, LoanApplication app) {
        return Objects.equals(d.getAmountPaise(), drawdownOf(app))
                && Objects.equals(d.getPricingVersionId(), app.getPricingVersionId());
    }

    private static BusinessException expired() {
        return new BusinessException("OFFER_EXPIRED",
                "Your offer has expired. Open the Key Fact Statement again for a fresh quote.");
    }

    private LoanDocument issue(LoanApplication app, LoanDocumentType type, Function<DocumentFacts, byte[]> renderer) {
        int version = nextVersion(app.getId(), type);
        DocumentFacts f = facts(app, null).withVersion(version);
        return save(app, type, version, renderer.apply(f), f);
    }

    private LoanDocument save(LoanApplication app, LoanDocumentType type, int version, byte[] pdf, DocumentFacts f) {
        String key = "applications/" + app.getId() + "/documents/" + type.name().toLowerCase() + "-v" + version + ".pdf";
        store(key, pdf);
        LoanDocument d = new LoanDocument();
        d.setApplicationId(app.getId());
        d.setLoanId(f.loanId());
        d.setType(type);
        d.setVersion(version);
        d.setPricingVersionId(app.getPricingVersionId());
        d.setAmountPaise(f.principalPaise());
        d.setS3Key(key);
        d.setSha256(sha256(pdf));
        d.setCreatedAt(now());
        return documents.save(d);
    }

    private ApplicationDocument storeApplicationDocument(Long appId, String docType, String key, String fileName,
                                                         byte[] pdf) {
        store(key, pdf);
        ApplicationDocument doc = applicationDocuments.findFirstByApplicationIdAndDocTypeOrderByIdDesc(appId, docType)
                .orElseGet(ApplicationDocument::new);
        doc.setApplicationId(appId);
        doc.setDocType(docType);
        doc.setFileName(fileName);
        doc.setContentType("application/pdf");
        doc.setSizeBytes((long) pdf.length);
        doc.setS3ObjectKey(key);
        doc.setData(null); // S3-backed: keep the "exactly one of data / s3ObjectKey" invariant
        return applicationDocuments.save(doc);
    }

    /** The borrower must read and sign these, so a storage failure says so rather than degrading. */
    private void store(String key, byte[] pdf) {
        try {
            storage.store(key, pdf, "application/pdf");
        } catch (RuntimeException e) {
            log.error("Document storage failed for {}: {}", key, e.toString());
            throw new BusinessException("DOCUMENT_UNAVAILABLE",
                    "We couldn't prepare your document just now. Please try again in a moment.");
        }
    }

    private String hashOfStored(String key) {
        try {
            return sha256(storage.fetch(key));
        } catch (RuntimeException e) {
            // The signature is already captured; a missing hash must not undo it.
            log.warn("Could not hash signed agreement {}: {}", key, e.toString());
            return null;
        }
    }

    private Optional<LoanDocument> latest(Long appId, LoanDocumentType type) {
        return documents.findFirstByApplicationIdAndTypeOrderByVersionDesc(appId, type);
    }

    private int nextVersion(Long appId, LoanDocumentType type) {
        return latest(appId, type).map(d -> d.getVersion() + 1).orElse(1);
    }

    private KfsView kfsView(LoanApplication app, LoanDocument kfs) {
        return new KfsView(kfs.getId(), kfs.getVersion(), storage.presignDownload(kfs.getS3Key(), URL_TTL),
                kfs.getAcceptedAt() != null, kfs.getAcceptedAt(), app.getOfferValidUntil());
    }

    private Instant now() {
        return clock.instant();
    }

    private static String actorId() {
        return ActorContext.get().id();
    }

    static long drawdownOf(LoanApplication app) {
        if (app.getAmountRequested() != null) {
            return app.getAmountRequested();
        }
        return app.getSanctionedAmountPaise() != null ? app.getSanctionedAmountPaise() : 0L;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mask(String value, int visible) {
        if (value == null || value.length() <= visible) {
            return value;
        }
        return "•".repeat(value.length() - visible) + value.substring(value.length() - visible);
    }

    private static String maskAccount(String account) {
        String digits = account == null ? "" : account.replaceAll("\\D", "");
        return digits.length() < 4 ? null : "•••• " + digits.substring(digits.length() - 4);
    }

    private String toJson(Map<String, Object> map) {
        try {
            return json.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
