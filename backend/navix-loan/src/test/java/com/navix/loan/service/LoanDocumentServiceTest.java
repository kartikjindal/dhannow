package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.navix.common.brand.BrandProperties;
import com.navix.common.brand.GrievanceProperties;
import com.navix.common.brand.LenderProperties;
import com.navix.common.exception.BusinessException;
import com.navix.common.notification.event.ApplicationTransitionedEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.domain.ConfigStatus;
import com.navix.loan.domain.LoanDocumentType;
import com.navix.loan.domain.ProductSnapshot;
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
import com.navix.loan.repository.PricingConfigRepository;
import com.navix.loan.repository.ProductConfigRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PLAN Phase 5: the documents, end to end on the service. Golden text: every required field and ₹
 * figure is read back out of each PDF; the figures are the {@link LoanMath} ones (money parity).
 */
class LoanDocumentServiceTest {

    private static final long APP = 54L;
    private static final long CUSTOMER = 7L;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** 1 Nov 2026, 11:30 IST: before the 18:00 cut-off, so the expected disbursal is today. */
    private static final Instant NOW = Instant.parse("2026-11-01T06:00:00Z");

    static final LenderProperties LENDER = new LenderProperties("Example Lender Private Limited",
            "1 Example Road, Gurugram 122001", "Registration EX-0001", "1800 000 000, care@lender.example");
    static final BrandProperties BRAND = new BrandProperties("DhanNow", "www.dhannow.com", "9211331169",
            "support@dhannow.com");
    static final GrievanceProperties GRIEVANCE = new GrievanceProperties("Dharmendra Rajput", "9211331170",
            "grievance@dhannow.com");

    private final LoanDocumentRepository documents = mock(LoanDocumentRepository.class);
    private final LoanApplicationRepository applications = mock(LoanApplicationRepository.class);
    private final LoanRepository loans = mock(LoanRepository.class);
    private final CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
    private final ApplicationDocumentRepository applicationDocuments = mock(ApplicationDocumentRepository.class);
    private final PricingConfigRepository pricingRepo = mock(PricingConfigRepository.class);
    private final ProductConfigRepository productRepo = mock(ProductConfigRepository.class);
    private final DocumentStoragePort storage = mock(DocumentStoragePort.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final com.navix.common.outbox.Outbox outbox = mock(com.navix.common.outbox.Outbox.class);

    private final List<LoanDocument> rows = new ArrayList<>();
    private final Map<String, byte[]> stored = new HashMap<>();
    private final Map<String, ApplicationDocument> appDocs = new HashMap<>();
    private LoanDocumentService service;
    private PricingService pricing;
    private LoanApplication app;

    @BeforeEach
    void setUp() {
        when(pricingRepo.findById(1L)).thenReturn(Optional.of(
                TestPricing.pricingRow(1L, 1, LoanMath.DHANNOW_SEED_PRICING, ConfigStatus.ACTIVE)));
        when(pricingRepo.findFirstByStatus(ConfigStatus.ACTIVE)).thenReturn(Optional.of(
                TestPricing.pricingRow(1L, 1, LoanMath.DHANNOW_SEED_PRICING, ConfigStatus.ACTIVE)));
        when(productRepo.findById(1L)).thenReturn(Optional.of(TestPricing.seedProductRow(1L, ConfigStatus.ACTIVE)));
        when(productRepo.findFirstByStatus(ConfigStatus.ACTIVE))
                .thenReturn(Optional.of(TestPricing.seedProductRow(1L, ConfigStatus.ACTIVE)));
        pricing = new PricingService(pricingRepo, productRepo);

        service = new LoanDocumentService(documents, applications, loans, profiles, applicationDocuments, pricing,
                new KfsPdfRenderer(), new SanctionLetterPdfRenderer(), new LoanAgreementPdfRenderer(), storage,
                LENDER, BRAND, GRIEVANCE, jdbc, new ObjectMapper(), Clock.fixed(NOW, IST), outbox);
        // The outbox's first try, straight away (its retry and locking are OutboxIntegrationTest's business).
        org.mockito.Mockito.doAnswer(i -> {
            service.handle(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(outbox).enqueue(anyString(), anyString());

        app = new LoanApplication();
        app.setId(APP);
        app.setCustomerId(CUSTOMER);
        app.setStatus(ApplicationStatus.SANCTIONED);
        app.setSanctionedAmountPaise(100_000L);
        app.setAmountRequested(100_000L);
        app.setPricingVersionId(1L);
        app.setProductVersionId(1L);
        app.setPurpose("Medical expenses");
        app.setOfferValidUntil(NOW.plus(Duration.ofDays(3)));

        CustomerProfile profile = new CustomerProfile();
        profile.setApplicationId(APP);
        profile.setFullName("Asha Kumari");
        profile.setMobile("9000000106");
        profile.setEmail("asha@example.com");
        profile.setAddress("12 MG Road, Gurugram");
        profile.setPan("ZYXPQ1006Q");
        profile.setSalaryAccountNumber("123456789012");
        when(profiles.findByApplicationId(APP)).thenReturn(Optional.of(profile));
        when(applications.save(any())).thenAnswer(i -> i.getArgument(0));

        // An in-memory loan_document table.
        when(documents.save(any())).thenAnswer(i -> {
            LoanDocument d = i.getArgument(0);
            if (d.getId() == null) {
                d.setId((long) rows.size() + 1);
                rows.add(d);
            }
            return d;
        });
        when(documents.findFirstByApplicationIdAndTypeOrderByVersionDesc(eq(APP), any())).thenAnswer(i ->
                rows.stream().filter(d -> d.getType() == i.getArgument(1))
                        .max(Comparator.comparingInt(LoanDocument::getVersion)));
        when(documents.existsByLoanIdAndType(any(), any())).thenAnswer(i -> rows.stream()
                .anyMatch(d -> i.getArgument(0).equals(d.getLoanId()) && d.getType() == i.getArgument(1)));
        when(documents.findById(any())).thenAnswer(i -> rows.stream()
                .filter(d -> d.getId().equals(i.getArgument(0))).findFirst());
        when(documents.findByApplicationIdOrLoanIdOrderByIdAsc(any(), any())).thenAnswer(i -> List.copyOf(rows));

        // S3 and the navix application_document rows.
        org.mockito.Mockito.doAnswer(i -> stored.put(i.getArgument(0), i.getArgument(1)))
                .when(storage).store(anyString(), any(), anyString());
        lenient().when(storage.fetch(anyString())).thenAnswer(i -> stored.get(i.<String>getArgument(0)));
        lenient().when(storage.presignDownload(anyString())).thenAnswer(i -> "https://s3.test/" + i.getArgument(0));
        lenient().when(storage.presignDownload(anyString(), any())).thenAnswer(i -> "https://s3.test/" + i.getArgument(0));
        when(applicationDocuments.findFirstByApplicationIdAndDocTypeOrderByIdDesc(eq(APP), anyString()))
                .thenAnswer(i -> Optional.ofNullable(appDocs.get(i.<String>getArgument(1))));
        when(applicationDocuments.save(any())).thenAnswer(i -> {
            ApplicationDocument d = i.getArgument(0);
            if (d.getId() == null) {
                d.setId(100L + appDocs.size());
            }
            appDocs.put(d.getDocType(), d);
            return d;
        });
        ActorContext.set(new CurrentActor(String.valueOf(CUSTOMER), "Asha", "BORROWER"));
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    // ---------------------------------------------------------------- money parity

    @Test
    void factsAreTheLoanMathFiguresForEveryLadderAmount() {
        LoanMath math = new LoanMath(LoanMath.DHANNOW_SEED_PRICING);
        for (long p : ProductSnapshot.SEED.allowedAmountsPaise()) {
            app.setAmountRequested(p);
            DocumentFacts f = service.facts(app, null);
            assertThat(f.processingFeePaise()).isEqualTo(math.processingFeePaise(p));
            assertThat(f.gstPaise()).isEqualTo(math.gstPaise(p));
            assertThat(f.interestPaise()).isEqualTo(math.upfrontInterestPaise(p));
            assertThat(f.netDisbursedPaise()).isEqualTo(math.netDisbursedPaise(p));
            assertThat(f.totalRepayablePaise()).isEqualTo(p);
            assertThat(f.totalDeductionsPaise() + f.netDisbursedPaise()).isEqualTo(p);
            assertThat(f.aprPercent()).isEqualByComparingTo("239096.78");
        }
    }

    // ---------------------------------------------------------------- golden text

    @Test
    void kfsStatesEveryRequiredFieldAndTheRupeeFigures() {
        var view = service.kfs(app);

        assertThat(view.version()).isEqualTo(1);
        assertThat(view.acknowledged()).isFalse();
        assertThat(text(stored.get(rows.get(0).getS3Key()))).contains(
                "KEY FACT STATEMENT", "KFS no. DN-KFS-54-1", "Application no. 54", "Pricing version 1",
                "Example Lender Private Limited", "1 Example Road, Gurugram 122001", "Registration EX-0001",
                "Asha Kumari",
                "Loan amount (principal)", "₹1,000",
                "Loan term", "14 days",
                "1% per day on the principal, fixed, simple interest for 14 days (₹140), deducted upfront",
                "Processing fee (10% of the principal)", "₹100",
                "GST on the processing fee (18%)", "₹18",
                "Other charges", "₹0",
                "Total deducted from the principal", "₹258",
                "Net amount disbursed to you", "₹742",
                "Total amount you repay",
                "Annual percentage rate (APR)", "239,096.78%",
                "Number of instalments", "Single repayment of ₹1,000 on the due date",
                "14 days from the disbursal date, by 23:59 IST (indicative: 15 Nov 2026)",
                "2% per day on the principal from the day after the due date, for at most 30 days (at most ₹600)",
                "the fee, GST and interest deducted upfront are not refunded",
                "Cooling-off / look-up period", "Not applicable to this loan",
                "Offer valid until", "4 Nov 2026",
                "Dharmendra Rajput", "9211331170", "grievance@dhannow.com", "1800 000 000, care@lender.example",
                "cms.rbi.org.in",
                "9211331169", "support@dhannow.com", "www.dhannow.com");
        assertThat(rows.get(0).getSha256()).hasSize(64);
        assertThat(rows.get(0).getPricingVersionId()).isEqualTo(1L);
        assertThat(rows.get(0).getAmountPaise()).isEqualTo(100_000L);
    }

    @Test
    void sanctionLetterAndAgreementStateTheTemplateFields() {
        var view = service.signingPack(app);

        LoanDocument sanction = rows.get(0);
        assertThat(sanction.getType()).isEqualTo(LoanDocumentType.SANCTION);
        assertThat(text(stored.get(sanction.getS3Key()))).contains(
                "LOAN SANCTION LETTER", "Sanction letter no.", "DN-SL-54-1", "Date of issue", "1 Nov 2026",
                "Application no.", "Loan account no.", "Not yet assigned",
                "Validity of offer", "Offer acceptance deadline", "4 Nov 2026",
                "Lender's legal name", "Example Lender Private Limited", "Lender's registered address",
                "Loan purpose", "Medical expenses", "Annual percentage rate (APR)", "239,096.78%",
                "Total deductions from disbursal", "₹258", "Net amount to be disbursed", "₹742",
                "Total amount payable by you", "₹1,000",
                "First / single repayment due date", "Final repayment due date", "(indicative: 15 Nov 2026)",
                "Repayment method and beneficiary", "Example Lender Private Limited account shown on the Repay screen",
                "Conditions before disbursal", "The loan agreement has been signed.",
                "Acceptance by borrower", "Date and time of acceptance", "On e-sign",
                "Disbursal account", "•••• 9012");

        // The pack the borrower e-signs: the sanction letter, then the agreement.
        String pack = text(stored.get("applications/54/sanction_letter/sanction-letter.pdf"));
        assertThat(view.url()).contains("sanction-letter.pdf");
        assertThat(pack).contains("LOAN SANCTION LETTER", "LOAN AGREEMENT", "Agreement no. DN-LA-54-1",
                "Document version 1", "Parties", "The lender lends you ₹1,000", "₹100", "₹18", "₹140", "₹258",
                "pays ₹742", "You repay ₹1,000 in a single payment", "239,096.78%", "Electronic execution");
        assertThat(appDocs.get(ApplicationVerificationService.SANCTION_LETTER).getS3ObjectKey())
                .isEqualTo("applications/54/sanction_letter/sanction-letter.pdf");
    }

    @Test
    void noDocumentNamesTheOldBrandOrMakesARetiredClaim() throws Exception {
        service.kfs(app);
        service.signingPack(app);
        service.storeDrawnSignature(app, signaturePng(), "Asha Kumari", NOW);
        Loan loan = activeLoan();
        service.onLoanActivated(activated(loan.getId()));

        assertThat(stored).hasSizeGreaterThanOrEqualTo(5);
        for (var e : stored.entrySet()) {
            String t = text(e.getValue()).toLowerCase();
            assertThat(t).as(e.getKey()).doesNotContain("dhanboost", "nbfc", "auto-debit", "salary",
                    "pay early", "save interest", "25%");
        }
    }

    // ---------------------------------------------------------------- KFS acknowledgement and CC-02

    @Test
    void eSignIsRefusedUntilTheKfsIsAcknowledged() {
        assertThatThrownBy(() -> service.requireKfsAcknowledged(app))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Key Fact Statement");
        service.kfs(app);
        assertThatThrownBy(() -> service.requireKfsAcknowledged(app)).isInstanceOf(BusinessException.class);

        var ack = service.acknowledgeKfs(app, "203.0.113.9", "DhanNowApp/1.0");

        assertThat(ack.acknowledged()).isTrue();
        assertThat(rows.get(0).getAcceptedAt()).isEqualTo(NOW);
        assertThat(rows.get(0).getAcceptedIp()).isEqualTo("203.0.113.9");
        assertThat(rows.get(0).getAcceptanceEvidence()).contains("KFS_ACKNOWLEDGED", "DhanNowApp/1.0");
        service.requireKfsAcknowledged(app);
        // Opening the KFS again reuses the acknowledged one.
        assertThat(service.kfs(app).documentId()).isEqualTo(ack.documentId());
    }

    @Test
    void anAcknowledgementIsForTheAmountItWasShownFor() {
        app.setSanctionedAmountPaise(200_000L);
        service.kfs(app);
        service.acknowledgeKfs(app, null, null);

        app.setAmountRequested(200_000L);

        assertThatThrownBy(() -> service.requireKfsAcknowledged(app))
                .hasFieldOrPropertyWithValue("code", "KFS_NOT_ACKNOWLEDGED");
        var fresh = service.kfs(app);
        assertThat(fresh.version()).isEqualTo(2);
        assertThat(fresh.acknowledged()).isFalse();
    }

    @Test
    void cc02_aLapsedOfferIsRequotedAndItsOldKfsCanNeverBeSigned() {
        service.kfs(app);
        service.acknowledgeKfs(app, null, null);
        app.setOfferValidUntil(NOW.minusSeconds(1));
        // Pricing version 2 became ACTIVE while the offer was open (CC-03): the re-quote uses it.
        var v2 = TestPricing.pricingRow(2L, 2, LoanMath.DHANNOW_SEED_PRICING, ConfigStatus.ACTIVE);
        when(pricingRepo.findFirstByStatus(ConfigStatus.ACTIVE)).thenReturn(Optional.of(v2));
        when(pricingRepo.findById(2L)).thenReturn(Optional.of(v2));

        assertThatThrownBy(() -> service.requireKfsAcknowledged(app)).hasFieldOrPropertyWithValue("code", "OFFER_EXPIRED");
        assertThatThrownBy(() -> service.acknowledgeKfs(app, null, null))
                .hasFieldOrPropertyWithValue("code", "OFFER_EXPIRED");

        var requoted = service.kfs(app);

        assertThat(app.getPricingVersionId()).isEqualTo(2L);
        assertThat(app.getOfferValidUntil()).isAfter(NOW);
        assertThat(requoted.version()).isEqualTo(2);
        assertThat(requoted.acknowledged()).isFalse();
        assertThat(rows.get(1).getPricingVersionId()).isEqualTo(2L);
        assertThatThrownBy(() -> service.requireKfsAcknowledged(app))
                .hasFieldOrPropertyWithValue("code", "KFS_NOT_ACKNOWLEDGED");
        service.acknowledgeKfs(app, null, null);
        service.requireKfsAcknowledged(app);
    }

    // ---------------------------------------------------------------- signature

    @Test
    void theSignedPackIsRecordedOnceAsTheAgreement() throws Exception {
        service.kfs(app);
        service.acknowledgeKfs(app, null, null);
        service.signingPack(app);
        Instant signedAt = Instant.parse("2026-11-01T07:47:18Z");
        service.storeDrawnSignature(app, signaturePng(), "Asha Kumari", signedAt);

        service.recordSignedAgreement(app, signedAt, "203.0.113.9", Map.of("method", "DRAWN_SIGNATURE"));
        service.recordSignedAgreement(app, signedAt, "203.0.113.9", Map.of("method", "DRAWN_SIGNATURE"));

        List<LoanDocument> agreements = rows.stream().filter(d -> d.getType() == LoanDocumentType.AGREEMENT).toList();
        assertThat(agreements).hasSize(1);
        LoanDocument agreement = agreements.get(0);
        String key = "applications/54/signed_agreement/sanction-letter-signed.pdf";
        assertThat(agreement.getS3Key()).isEqualTo(key);
        assertThat(agreement.getSha256()).isEqualTo(LoanDocumentService.sha256(stored.get(key)));
        assertThat(agreement.getAcceptedAt()).isEqualTo(signedAt);
        assertThat(agreement.getAcceptanceEvidence()).contains("AGREEMENT_SIGNED", "DRAWN_SIGNATURE", "kfsDocumentId");
        assertThat(rows.stream().filter(d -> d.getType() == LoanDocumentType.SANCTION).findFirst().orElseThrow()
                .getAcceptedAt()).isEqualTo(signedAt);
        assertThat(text(stored.get(key))).contains("Borrower signature", "Signed by Asha Kumari",
                "1 Nov 2026, 1:17:18 PM IST", "Date and time of acceptance");
    }

    // ---------------------------------------------------------------- CC-01 schedule

    @Test
    void cc01_theScheduleIsIssuedOnceWithTheConfirmedDueDate() {
        Loan loan = activeLoan();

        service.onLoanActivated(activated(loan.getId()));
        service.onLoanActivated(activated(loan.getId()));
        service.onLoanActivated(new ApplicationTransitionedEvent(APP, CUSTOMER, loan.getId(), "SANCTIONED",
                "DISBURSEMENT_PENDING", "ACCEPT_OFFER", null, "7", "BORROWER", NOW, null));

        List<LoanDocument> schedules = rows.stream().filter(d -> d.getType() == LoanDocumentType.SCHEDULE).toList();
        assertThat(schedules).hasSize(1);
        assertThat(schedules.get(0).getLoanId()).isEqualTo(loan.getId());
        assertThat(text(stored.get(schedules.get(0).getS3Key()))).contains("REPAYMENT SCHEDULE",
                "Loan account no. " + com.navix.common.util.CustomerIds.loanAccount(loan.getId()), "Disbursed on", "3 Nov 2026", "17 Nov 2026, by 23:59 IST",
                "Single repayment of ₹1,000", "₹742");
    }

    /** EXT-22 / PLAN Phase 9: a storage failure is thrown to the outbox, which keeps the row and retries it. */
    @Test
    void aScheduleFailureIsLeftForTheOutboxToRetry() {
        Loan loan = activeLoan();
        org.mockito.Mockito.doThrow(new IllegalStateException("s3 down")).when(storage).store(anyString(), any(), anyString());

        assertThatThrownBy(() -> service.onLoanActivated(activated(loan.getId())))
                .isInstanceOf(com.navix.common.exception.BusinessException.class);
        assertThat(rows).isEmpty();
        verify(outbox).enqueue(LoanDocumentService.OUTBOX_SCHEDULE, "{\"loanId\":" + loan.getId() + "}");
    }

    // ---------------------------------------------------------------- closure receipt (Phase 6a)

    /** The receipt states the payments and the waterfall split, in the brand's plain wording. */
    @Test
    void theClosureReceiptStatesThePaymentsAndHowTheyWereApplied() {
        Loan loan = activeLoan();
        var c = new com.navix.loan.pdf.ClosureFacts(LocalDate.of(2026, 11, 19),
                List.of(new com.navix.loan.pdf.ClosureFacts.PaidLine(LocalDate.of(2026, 11, 19), "UTR77", 104_000L)),
                4_000L, 100_000L, 0L, false);

        String t = text(new KfsPdfRenderer().renderClosureReceipt(service.facts(app, loan), c));

        assertThat(t).contains("LOAN CLOSURE RECEIPT", "Loan account no. " + com.navix.common.util.CustomerIds.loanAccount(loan.getId()), "Repaid in full",
                "19 Nov 2026, ref UTR77", "₹1,040", "Late charge", "₹40", "Principal",
                "Nothing more is owed on this loan.", "No refund is due.");
        assertThat(t.toLowerCase()).doesNotContain("dhanboost", "nbfc", "pay early", "save interest");
    }

    /** CC-08 on the receipt: the excess is stated as a refund. */
    @Test
    void cc08_theClosureReceiptStatesTheRefund() {
        Loan loan = activeLoan();
        var c = new com.navix.loan.pdf.ClosureFacts(LocalDate.of(2026, 11, 17),
                List.of(new com.navix.loan.pdf.ClosureFacts.PaidLine(LocalDate.of(2026, 11, 17), null, 110_000L)),
                0L, 100_000L, 10_000L, false);

        assertThat(text(new KfsPdfRenderer().renderClosureReceipt(service.facts(app, loan), c)))
                .contains("Excess, to be refunded to you", "₹100", "We will refund ₹100 to your bank account.");
    }

    @Test
    void theClosureReceiptIsIssuedOnceOnTheRepaidTransition() {
        Loan loan = activeLoan();
        var repaid = new ApplicationTransitionedEvent(APP, CUSTOMER, loan.getId(), "ACTIVE", "CLOSED", "REPAID",
                null, "3", "ACCOUNTANT", NOW, null);
        service.onLoanClosed(repaid);
        service.onLoanClosed(repaid);
        service.onLoanClosed(activated(loan.getId()));

        assertThat(rows).filteredOn(d -> d.getType() == LoanDocumentType.CLOSURE_RECEIPT).hasSize(1);
    }

    // ---------------------------------------------------------------- access

    @Test
    void anotherCustomersLoanIsForbiddenOwner() {
        Loan loan = activeLoan();
        ActorContext.set(new CurrentActor("8", "Other", "BORROWER"));

        assertThatThrownBy(() -> service.list(loan.getId())).hasFieldOrPropertyWithValue("code", "FORBIDDEN_OWNER");
        assertThatThrownBy(() -> service.url(loan.getId(), 1L)).hasFieldOrPropertyWithValue("code", "FORBIDDEN_OWNER");
        ActorContext.set(new CurrentActor("9", "Dsa", "DSA"));
        assertThatThrownBy(() -> service.list(loan.getId())).hasFieldOrPropertyWithValue("code", "FORBIDDEN_ROLE");
    }

    @Test
    void theOwnerGetsAFiveMinuteLinkAndStaffReadsAreAudited() {
        Loan loan = activeLoan();
        service.onLoanActivated(activated(loan.getId()));
        Long docId = rows.get(0).getId();

        var own = service.url(loan.getId(), docId);
        assertThat(own.expiresInSeconds()).isEqualTo(300);
        verify(storage).presignDownload(rows.get(0).getS3Key(), Duration.ofMinutes(5));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        assertThat(service.list(loan.getId())).extracting(v -> v.type()).containsExactly(LoanDocumentType.SCHEDULE);

        ActorContext.set(new CurrentActor("3", "Accountant", "ACCOUNTANT"));
        service.url(loan.getId(), docId);
        verify(jdbc).update(org.mockito.ArgumentMatchers.startsWith("insert into loan_document_access"),
                eq(docId), eq("3"), eq("ACCOUNTANT"), any());
    }

    // ---------------------------------------------------------------- fixtures

    /** A confirmed loan: day 0 on 3 Nov 2026, due 17 Nov at 23:59:59 IST. */
    private Loan activeLoan() {
        Loan loan = new Loan();
        loan.setId(900L);
        loan.setCustomerId(CUSTOMER);
        loan.setPrincipal(100_000L);
        loan.setPricingVersionId(1L);
        loan.setProductVersionId(1L);
        loan.setDisbursedOn(LocalDate.of(2026, 11, 3));
        loan.setDueDate(LocalDate.of(2026, 11, 17));
        app.setLoanId(900L);
        lenient().when(loans.findById(900L)).thenReturn(Optional.of(loan));
        lenient().when(applications.findByLoanId(900L)).thenReturn(Optional.of(app));
        return loan;
    }

    private static ApplicationTransitionedEvent activated(Long loanId) {
        return new ApplicationTransitionedEvent(APP, CUSTOMER, loanId, "DISBURSED", "ACTIVE", "ACTIVATE",
                null, "3", "ACCOUNTANT", NOW, null);
    }

    private static byte[] signaturePng() throws Exception {
        BufferedImage signature = new BufferedImage(180, 50, BufferedImage.TYPE_INT_ARGB);
        signature.setRGB(20, 20, 0xff0c2540);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(signature, "png", png);
        return png.toByteArray();
    }

    /** Every page's text, whitespace collapsed so a wrapped line still matches. */
    static String text(byte[] pdf) {
        try {
            PdfReader reader = new PdfReader(pdf);
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder all = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                all.append(extractor.getTextFromPage(page)).append(' ');
            }
            reader.close();
            return all.toString().replaceAll("\\s+", " ");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
