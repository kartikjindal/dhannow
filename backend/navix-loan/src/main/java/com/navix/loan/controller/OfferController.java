package com.navix.loan.controller;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.LoanDocumentDtos.KfsView;
import com.navix.loan.dto.OfferDtos.ChooseAmountRequest;
import com.navix.loan.dto.OfferDtos.DisbursalAccountRequest;
import com.navix.loan.dto.OfferDtos.DisbursalAccountView;
import com.navix.loan.dto.OfferDtos.EsignInitRequest;
import com.navix.loan.dto.OfferDtos.OfferSummaryView;
import com.navix.loan.dto.OfferDtos.ReferenceView;
import com.navix.loan.dto.OfferDtos.ReferencesRequest;
import com.navix.loan.dto.OfferDtos.SanctionLetterView;
import com.navix.loan.dto.OfferDtos.ManualEsignRequest;
import com.navix.loan.service.ApplicationFlowService;
import com.navix.loan.service.ApplicationVerificationService.StepResult;
import com.navix.loan.service.OfferService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The borrower's post-sanction offer journey (revamp.md Phase 3).
 *
 * <p>Borrower-only and ownership-checked, exactly like {@link ApplicationVerificationController} —
 * the identity steps inside this journey (DigiLocker, selfie, address) still live on that controller,
 * so the two authorize identically on purpose.
 */
@RestController
@RequestMapping("/api/applications/{id}/offer")
@RequiredArgsConstructor
public class OfferController {

    private final OfferService offer;
    private final ApplicationFlowService flow;
    private final com.navix.common.web.RedirectAllowlist redirects;

    @PostMapping("/amount")
    public ApiResponse<ApplicationView> chooseAmount(@PathVariable Long id,
                                                     @Valid @RequestBody ChooseAmountRequest req) {
        authorize(id);
        return ApiResponse.ok(ApplicationView.of(offer.chooseAmount(id, req.amountPaise())));
    }

    @GetMapping("/references")
    public ApiResponse<List<ReferenceView>> references(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(offer.references(id));
    }

    @PostMapping("/references")
    public ApiResponse<List<ReferenceView>> saveReferences(@PathVariable Long id,
                                                           @RequestBody ReferencesRequest req) {
        authorize(id);
        return ApiResponse.ok(offer.saveReferences(id, req.references()));
    }

    @GetMapping("/summary")
    public ApiResponse<OfferSummaryView> summary(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(offer.summary(id));
    }

    /** B1's price list: one summary per ladder amount the borrower may draw, on this offer's frozen terms. */
    @GetMapping("/quotes")
    public ApiResponse<List<OfferSummaryView>> quotes(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(offer.quotes(id));
    }

    /** The offer's Key Fact Statement (issued or reused) and whether it is acknowledged. */
    @PostMapping("/kfs")
    public ApiResponse<KfsView> kfs(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(offer.kfs(id));
    }

    /** Acknowledge the Key Fact Statement; required before e-sign. */
    @PostMapping("/kfs/ack")
    public ApiResponse<KfsView> acknowledgeKfs(@PathVariable Long id, HttpServletRequest request) {
        authorize(id);
        return ApiResponse.ok(offer.acknowledgeKfs(id, clientIp(request), request.getHeader("User-Agent")));
    }

    /** Issues the sanction letter and agreement and returns a short-lived URL to the pack to sign. */
    @PostMapping("/sanction-letter")
    public ApiResponse<SanctionLetterView> sanctionLetter(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(offer.sanctionLetter(id));
    }

    /** Start Aadhaar e-sign — {@code derived.url} is where to send the borrower. */
    @PostMapping("/esign/init")
    public ApiResponse<StepResult> esignInit(@PathVariable Long id,
                                             @Valid @RequestBody EsignInitRequest req) {
        authorize(id);
        // PLAN Phase 8 (CC-34): the signing journey may only return the borrower to an allowlisted address.
        redirects.require(req.successRedirectUrl());
        redirects.require(req.failureRedirectUrl());
        return ApiResponse.ok(offer.esignInit(id, req));
    }

    /** Poll the Aadhaar e-sign session after the provider redirects the borrower back. */
    @GetMapping("/esign/status")
    public ApiResponse<StepResult> esignStatus(@PathVariable Long id, HttpServletRequest request) {
        authorize(id);
        return ApiResponse.ok(offer.esignStatus(id, clientIp(request)));
    }

    /** Register (or reuse) the Digio eMandate — {@code derived.url} is where to send the borrower. */
    @PostMapping("/mandate/init")
    public ApiResponse<StepResult> mandateInit(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(offer.mandateInit(id));
    }

    /** Poll the eMandate; a UMRN hands the file to the Disbursement Head. */
    @GetMapping("/mandate/status")
    public ApiResponse<StepResult> mandateStatus(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(offer.mandateStatus(id));
    }

    /** Fallback: record a signature the borrower drew in the app. */
    @PostMapping("/esign")
    public ApiResponse<StepResult> esign(@PathVariable Long id, @RequestBody ManualEsignRequest req,
                                         HttpServletRequest request) {
        authorize(id);
        return ApiResponse.ok(offer.manualEsign(id, req, clientIp(request)));
    }

    @GetMapping("/disbursal-account")
    public ApiResponse<DisbursalAccountView> disbursalAccount(@PathVariable Long id) {
        authorize(id);
        return ApiResponse.ok(offer.disbursalAccount(id));
    }

    /** The last step of the journey: confirms the account and hands off to disbursement. */
    @PostMapping("/disbursal-account")
    public ApiResponse<ApplicationView> confirmDisbursalAccount(
            @PathVariable Long id, @Valid @RequestBody DisbursalAccountRequest req) {
        authorize(id);
        return ApiResponse.ok(ApplicationView.of(offer.confirmDisbursalAccount(id, req)));
    }

    /**
     * The borrower's IP for the acceptance record: the last X-Forwarded-For hop (the one the load
     * balancer appended, so not client-supplied), else the socket peer.
     */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].trim();
        }
        return request.getRemoteAddr();
    }

    /** Borrower-only + ownership: a BORROWER may act only on their own application; ADMIN on any. */
    private void authorize(Long id) {
        String role = ActorContext.get().role();
        if (!"BORROWER".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Borrower action required");
        }
        if ("BORROWER".equals(role)
                && !String.valueOf(flow.get(id).getCustomerId()).equals(ActorContext.get().id())) {
            throw new BusinessException("FORBIDDEN_OWNER", "Not your application");
        }
    }
}
