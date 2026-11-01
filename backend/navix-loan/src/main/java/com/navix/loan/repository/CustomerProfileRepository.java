package com.navix.loan.repository;

import com.navix.loan.entity.CustomerProfile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for the per-application customer KYC snapshot (1:1 with the application). */
@Repository
public interface CustomerProfileRepository extends JpaRepository<CustomerProfile, Long> {

    Optional<CustomerProfile> findByApplicationId(Long applicationId);

    /** Batch-load profiles for a set of applications — used to enrich application list views. */
    List<CustomerProfile> findByApplicationIdIn(Collection<Long> applicationIds);

    /**
     * The most recently captured profile for a mobile number (latest application wins). Used at
     * borrower login to resolve a returning borrower's display name from their stored KYC snapshot
     * instead of trusting a client-supplied name.
     */
    Optional<CustomerProfile> findFirstByMobileOrderByApplicationIdDesc(String mobile);

    /**
     * The most recently captured profile for an email address (latest application wins, mirroring
     * {@link #findFirstByMobileOrderByApplicationIdDesc}). Backs the email-only borrower
     * forgot-password lookup — the reset link only ever goes to that inbox, so a matching mobile is
     * no longer required.
     */
    Optional<CustomerProfile> findFirstByEmailIgnoreCaseOrderByApplicationIdDesc(String email);

    // --- identity uniqueness (a mobile/PAN may belong to only one customer) ---
    // Scoped to OTHER customers: a profile is matched by joining its application to resolve the
    // owning customerId, then excluding the queried customer. This lets the SAME customer
    // re-onboard through a new application (a second profile row carrying the same identity) while
    // still blocking a different person from reusing the PAN / mobile.
    // PAN and Aadhaar are encrypted (V111): every equality goes through the blind index. The defaults keep the
    // plain-value signatures for callers.
    default boolean existsPanForOtherCustomer(String pan, Long customerId) {
        return existsPanHashForOtherCustomer(com.navix.common.pii.PiiCipher.panIndex(pan), customerId);
    }

    @Query("select (count(p) > 0) from CustomerProfile p, LoanApplication a "
            + "where p.applicationId = a.id and p.panHash = :panHash and a.customerId <> :customerId")
    boolean existsPanHashForOtherCustomer(@Param("panHash") String panHash, @Param("customerId") Long customerId);

    @Query("select (count(p) > 0) from CustomerProfile p, LoanApplication a "
            + "where p.applicationId = a.id and p.mobile = :mobile and a.customerId <> :customerId")
    boolean existsMobileForOtherCustomer(@Param("mobile") String mobile, @Param("customerId") Long customerId);

    /**
     * The OTHER customers already holding this Aadhaar number (V75). Unlike the PAN / mobile rules
     * above this is not a hard block: a collision is written as an {@code AADHAAR_DUPLICATE} review
     * row for the credit team, never reported to the borrower — so the write path cannot be used to
     * probe which numbers we hold, and a genuine returning borrower on a new mobile is not refused.
     */
    default java.util.List<Long> findOtherCustomerIdsByAadhaar(String aadhaar, Long customerId) {
        return findOtherCustomerIdsByAadhaarHash(com.navix.common.pii.PiiCipher.digitsIndex(aadhaar), customerId);
    }

    @Query("select distinct a.customerId from CustomerProfile p, LoanApplication a "
            + "where p.applicationId = a.id and p.aadhaarHash = :aadhaarHash and a.customerId <> :customerId "
            + "order by a.customerId")
    java.util.List<Long> findOtherCustomerIdsByAadhaarHash(@Param("aadhaarHash") String aadhaarHash,
                                                           @Param("customerId") Long customerId);

    /**
     * The OTHER customers' applications holding this Aadhaar number that were actually submitted (ever
     * reached {@code KYC_PENDING}), newest first — what the submit-kyc duplicate rule rejects on. An
     * abandoned draft does not count: anyone can type a number into one, and that must not get the
     * number's real owner rejected later (those still raise the silent {@code AADHAAR_DUPLICATE} flag) —
     * and neither does a file itself rejected as {@code FRAUD_REJECTED}, whose identity documents disagreed.
     */
    default java.util.List<Long> findSubmittedApplicationIdsByAadhaarForOtherCustomers(String aadhaar,
                                                                                       Long customerId) {
        return findSubmittedApplicationIdsByAadhaarHashForOtherCustomers(com.navix.common.pii.PiiCipher.digitsIndex(aadhaar), customerId);
    }

    @Query("select a.id from CustomerProfile p, LoanApplication a "
            + "where p.applicationId = a.id and p.aadhaarHash = :aadhaarHash and a.customerId <> :customerId "
            + "and exists (select e.id from ApplicationEvent e where e.applicationId = a.id "
            + "and e.toStatus = com.navix.loan.domain.ApplicationStatus.KYC_PENDING) "
            + "and not exists (select r.id from ApplicationRejection r where r.applicationId = a.id "
            + "and r.reasonCode = 'FRAUD_REJECTED') "
            + "order by a.id desc")
    java.util.List<Long> findSubmittedApplicationIdsByAadhaarHashForOtherCustomers(
            @Param("aadhaarHash") String aadhaarHash, @Param("customerId") Long customerId);

    /**
     * Every mobile this customer has on file across their applications, newest application first.
     * A returning borrower who starts a fresh application while already signed in never passes back
     * through the mobile step, so that application's profile has no mobile of its own — this recovers
     * it from the customer's earlier profile instead of dead-ending the bureau-consent step.
     */
    @Query("select p.mobile from CustomerProfile p, LoanApplication a "
            + "where p.applicationId = a.id and a.customerId = :customerId "
            + "and p.mobile is not null and p.mobile <> '' order by p.applicationId desc")
    List<String> findMobilesForCustomer(@Param("customerId") Long customerId);

    /**
     * Whether {@code pan} already belongs to ANY existing customer — unlike
     * {@link #existsPanForOtherCustomer}, which is scoped to "other than this customer id" and so
     * can't answer "is this PAN known to us at all". Backs the DSA lead-creation entry-time
     * rejection (spec: a PAN belonging to an existing customer is rejected the same as a PAN
     * already held by another DSA's lead).
     */
    default boolean existsByPan(String pan) {
        return existsByPanHash(com.navix.common.pii.PiiCipher.panIndex(pan));
    }

    boolean existsByPanHash(String panHash);

    /**
     * The single earliest {@link com.navix.loan.entity.LoanApplication} created strictly after
     * {@code after} for a customer whose profile carries {@code pan} — the DSA attribution query.
     * Pinning to the earliest post-lead application (not the latest, and never a pre-existing
     * in-flight one) is what keeps a customer's repeat borrowing invisible to the DSA.
     */
    default List<com.navix.loan.entity.LoanApplication> findApplicationsAfterByPan(String pan,
                                                                                  java.time.Instant after) {
        return findApplicationsAfterByPanHash(com.navix.common.pii.PiiCipher.panIndex(pan), after);
    }

    @Query("select a from CustomerProfile p, LoanApplication a "
            + "where p.applicationId = a.id and p.panHash = :panHash and a.createdAt > :after "
            + "order by a.createdAt asc, a.id asc")
    List<com.navix.loan.entity.LoanApplication> findApplicationsAfterByPanHash(
            @Param("panHash") String panHash, @Param("after") java.time.Instant after);

    /**
     * Every application belonging to any of {@code pans}, oldest first — the BATCH form of
     * {@link #findApplicationsAfterByPan}.
     *
     * <p>The per-lead query filters on that one lead's {@code createdAt}; a list of leads each has a
     * different cutoff, so this returns the candidates unfiltered and the caller pins per lead in
     * memory. That keeps a page of leads to ONE query instead of one per lead — the shape
     * {@code DsaAdminService.roster()} gets wrong today, and which would be ruinous on
     * {@code LeadService.list()}, whose result set is unpaged.
     *
     * <p>Rows are {@code [pan, application]}. Bounded in practice by how many advances a PAN has
     * taken, which is small.
     */
    default List<Object[]> findApplicationsByPanIn(Collection<String> pans) {
        return pans.isEmpty() ? List.of() : findApplicationsByPanHashIn(hashes(pans));
    }

    @Query("select p.pan, a from CustomerProfile p, LoanApplication a "
            + "where p.applicationId = a.id and p.panHash in :hashes "
            + "order by a.createdAt asc, a.id asc")
    List<Object[]> findApplicationsByPanHashIn(@Param("hashes") Collection<String> hashes);

    /**
     * Which of {@code pans} already belong to a customer — the CSV lead import's "already a
     * customer" check. Distinct, non-null values only.
     */
    default List<String> findPansIn(Collection<String> pans) {
        return pans.isEmpty() ? List.of() : findPansByHashIn(hashes(pans)).stream().distinct().toList();
    }

    @Query("select p.pan from CustomerProfile p where p.panHash in :hashes")
    List<String> findPansByHashIn(@Param("hashes") Collection<String> hashes);

    private static List<String> hashes(Collection<String> pans) {
        return pans.stream().map(com.navix.common.pii.PiiCipher::panIndex).filter(java.util.Objects::nonNull).distinct().toList();
    }

    /** Same as {@link #findPansIn}, on mobile. */
    @Query("select distinct p.mobile from CustomerProfile p where p.mobile in :mobiles")
    List<String> findMobilesIn(@Param("mobiles") Collection<String> mobiles);

    /**
     * Profiles whose name / mobile / PAN match {@code needle} (already lower-cased and wrapped in
     * {@code %}), newest application first — the free-text half of the global-search "Applications"
     * group.
     *
     * <p>{@code ApplicationFlowService.filterByQuery} cannot serve that group: it narrows an
     * already-loaded status window, so it can only find an application whose stage the caller
     * already picked. Search has no stage, and loading every application to filter in memory is not
     * an option — hence one indexed query, capped by the caller's {@link Pageable}.
     */
    @Query("select p from CustomerProfile p "
            + "where lower(p.fullName) like :needle or p.mobile like :needle or p.panHash = :panHash "
            + "order by p.applicationId desc")
    List<CustomerProfile> searchByNameMobileOrPanHash(@Param("needle") String needle, @Param("panHash") String panHash,
                                                      Pageable pageable);

    /** {@code needle} is the lower-cased {@code %q%} pattern; {@code q} the raw term, matched as a full PAN. */
    default List<CustomerProfile> searchByNameMobileOrPan(String needle, String q, Pageable pageable) {
        return searchByNameMobileOrPanHash(needle, q == null ? null : com.navix.common.pii.PiiCipher.panIndex(q), pageable);
    }

    /** Which applications hold stored bureau facts and when they were pulled - without the (large) JSON. */
    interface BriefMetaRow {
        Long getApplicationId();
        java.time.Instant getGeneratedAt();
    }

    @Query("select p.applicationId as applicationId, p.creditBriefGeneratedAt as generatedAt "
            + "from CustomerProfile p where p.applicationId in :applicationIds and p.creditBriefFacts is not null")
    List<BriefMetaRow> findBriefMetaByApplicationIdIn(@Param("applicationIds") Collection<Long> applicationIds);

    /** Narrow projection of the stored bureau facts JSON for the trust stars — no other profile column. */
    interface BriefFactsRow {
        Long getApplicationId();
        String getFacts();
        java.time.Instant getGeneratedAt();
    }

    @Query("select p.applicationId as applicationId, p.creditBriefFacts as facts, "
            + "p.creditBriefGeneratedAt as generatedAt from CustomerProfile p "
            + "where p.applicationId in :applicationIds and p.creditBriefFacts is not null")
    List<BriefFactsRow> findBriefFactsByApplicationIdIn(@Param("applicationIds") Collection<Long> applicationIds);

    /** Another customer's registered mobile; one row per profile, [profile, customerId, applicationStatus]. */
    @Query("select p.mobile, a.customerId, a.id, a.status, p.fullName, a.createdAt "
            + "from CustomerProfile p, LoanApplication a "
            + "where p.applicationId = a.id and p.mobile in :mobiles and a.customerId <> :customerId "
            + "order by a.id desc")
    List<Object[]> findOtherBorrowersByMobileIn(@Param("mobiles") Collection<String> mobiles,
                                                @Param("customerId") Long customerId);

    /** Every profile carrying a stored bureau brief — the boot-time star re-score walks these. */
    @Query("select p.id from CustomerProfile p where p.creditBriefFacts is not null order by p.id")
    List<Long> findIdsWithCreditBriefFacts();
}
