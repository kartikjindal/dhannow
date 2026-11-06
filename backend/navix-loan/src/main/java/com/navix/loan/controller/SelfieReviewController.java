package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.service.ApplicationVerificationService.StepResult;
import com.navix.loan.service.SelfieReviewService;
import com.navix.loan.service.SelfieReviewService.SelfieReviewItem;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The staff "Selfie review" queue (PLAN Phase 8). Credit team and ADMIN. */
@RestController
@RequestMapping("/api/staff/selfie-reviews")
@RequiredArgsConstructor
public class SelfieReviewController {

    private final SelfieReviewService reviews;

    public record ReviewDecision(String note, String reason) {
    }

    @GetMapping
    public ApiResponse<List<SelfieReviewItem>> queue() {
        return ApiResponse.ok(reviews.queue());
    }

    @PostMapping("/{applicationId}/approve")
    public ApiResponse<StepResult> approve(@PathVariable Long applicationId,
                                           @RequestBody(required = false) ReviewDecision req) {
        return ApiResponse.ok(reviews.approve(applicationId, req == null ? null : req.note()));
    }

    /** {@code reason} is required; the borrower is sent back to take a new selfie. */
    @PostMapping("/{applicationId}/reject")
    public ApiResponse<StepResult> reject(@PathVariable Long applicationId, @RequestBody ReviewDecision req) {
        return ApiResponse.ok(reviews.reject(applicationId, req.reason()));
    }
}
