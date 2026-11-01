package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.loan.domain.ConfigStatus;
import com.navix.loan.domain.PricingSnapshot;
import com.navix.loan.domain.ProductSnapshot;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.entity.PricingConfig;
import com.navix.loan.entity.ProductConfig;
import com.navix.loan.repository.PricingConfigRepository;
import com.navix.loan.repository.ProductConfigRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The versioned pricing and product rules (V92/V93), PLAN Phase 1.
 *
 * <p>The ACTIVE versions are read once, at offer time, by {@link #freeze}, which stamps their ids on
 * the application; {@code LoanService.disburse} copies them to the loan. Every later money read goes
 * through {@link #mathFor} on those ids, so activating a new version never changes an open offer or
 * a live loan (CC-03). A row with no version (created before V92) prices on the navix terms.
 *
 * <p>Snapshots are cached by id: a version's terms cannot change once it leaves DRAFT (DB trigger),
 * and only non-DRAFT versions are ever frozen.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private final PricingConfigRepository pricingRepository;
    private final ProductConfigRepository productRepository;

    private final Map<Long, PricingSnapshot> pricingById = new ConcurrentHashMap<>();
    private final Map<Long, ProductSnapshot> productById = new ConcurrentHashMap<>();

    /** The ACTIVE pricing version; {@code PRICING_NOT_CONFIGURED} when none is active. */
    @Transactional(readOnly = true)
    public PricingSnapshot activePricing() {
        return pricingRepository.findFirstByStatus(ConfigStatus.ACTIVE)
                .map(PricingConfig::toSnapshot)
                .orElseThrow(() -> new BusinessException("PRICING_NOT_CONFIGURED", "No active pricing version"));
    }

    /** The ACTIVE product version; {@code PRICING_NOT_CONFIGURED} when none is active. */
    @Transactional(readOnly = true)
    public ProductSnapshot activeProduct() {
        return productRepository.findFirstByStatus(ConfigStatus.ACTIVE)
                .map(ProductConfig::toSnapshot)
                .orElseThrow(() -> new BusinessException("PRICING_NOT_CONFIGURED", "No active product version"));
    }

    /**
     * Freeze the ACTIVE versions onto an offer and open its validity window (CC-02). Called when the
     * application is sanctioned or re-applied, and by the KFS re-quote of a lapsed offer.
     */
    @Transactional(readOnly = true)
    public void freeze(LoanApplication app) {
        ProductSnapshot product = activeProduct();
        app.setPricingVersionId(activePricing().versionId());
        app.setProductVersionId(product.versionId());
        app.setOfferValidUntil(Instant.now().plus(Duration.ofDays(product.offerValidityDays())));
    }

    /** The pricing version {@code versionId}; null → the navix terms. */
    @Transactional(readOnly = true)
    public PricingSnapshot pricing(Long versionId) {
        if (versionId == null) {
            return LoanMath.NAVIX_PRICING;
        }
        return pricingById.computeIfAbsent(versionId, id -> pricingRepository.findById(id)
                .map(PricingConfig::toSnapshot)
                .orElseThrow(() -> new IllegalStateException("pricing_config " + id + " not found")));
    }

    /** The product version {@code versionId}; null → the seed rules ({@link ProductSnapshot#SEED}). */
    @Transactional(readOnly = true)
    public ProductSnapshot product(Long versionId) {
        if (versionId == null) {
            return ProductSnapshot.SEED;
        }
        return productById.computeIfAbsent(versionId, id -> productRepository.findById(id)
                .map(ProductConfig::toSnapshot)
                .orElseThrow(() -> new IllegalStateException("product_config " + id + " not found")));
    }

    /** Money math on the terms frozen on this offer. */
    public LoanMath mathFor(LoanApplication app) {
        return new LoanMath(pricing(app.getPricingVersionId()));
    }

    /** Money math on the terms this loan was disbursed on. */
    public LoanMath mathFor(Loan loan) {
        return new LoanMath(pricing(loan.getPricingVersionId()));
    }

    /**
     * An offer may only be drawn at one of its product version's ladder amounts (and never above the
     * sanctioned ceiling, which the caller checks). Pre-V92 offers have no ladder and are not checked.
     */
    public void requireAllowedAmount(LoanApplication app, long amountPaise) {
        if (app.getProductVersionId() == null) {
            return;
        }
        if (!product(app.getProductVersionId()).allowedAmountsPaise().contains(amountPaise)) {
            throw new BusinessException("AMOUNT_NOT_ALLOWED", "Choose one of the loan amounts on offer");
        }
    }

    /** One ladder amount priced on the ACTIVE version, for staff screens (PLAN Phase 10b: figures come from here, not the web). */
    public record Quote(long principalPaise, long feePaise, long gstPaise, long interestPaise, long netPaise,
                        long repayPaise, int tenureDays) {
    }

    public java.util.List<Quote> quotes(java.util.List<Long> amountsPaise) {
        PricingSnapshot p = activePricing();
        LoanMath m = new LoanMath(p);
        return amountsPaise.stream().map(a -> new Quote(a, m.processingFeePaise(a), m.gstPaise(a), m.upfrontInterestPaise(a),
                m.netDisbursedPaise(a), m.totalRepayablePaise(a, p.tenureDays()), p.tenureDays())).toList();
    }
}
