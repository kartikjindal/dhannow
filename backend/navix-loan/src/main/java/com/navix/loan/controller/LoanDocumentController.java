package com.navix.loan.controller;

import com.navix.common.web.ApiResponse;
import com.navix.loan.dto.LoanDocumentDtos.DocumentUrlView;
import com.navix.loan.dto.LoanDocumentDtos.LoanDocumentView;
import com.navix.loan.service.LoanDocumentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A loan's documents (PLAN Phase 5): KFS, sanction letter, signed agreement, schedule. The borrower
 * sees only their own loan (422 {@code FORBIDDEN_OWNER}); staff reads of a document are audited.
 */
@RestController
@RequestMapping("/api/loans/{loanId}/documents")
@RequiredArgsConstructor
public class LoanDocumentController {

    private final LoanDocumentService documents;

    @GetMapping
    public ApiResponse<List<LoanDocumentView>> list(@PathVariable Long loanId) {
        return ApiResponse.ok(documents.list(loanId));
    }

    /** A presigned download link, valid for 5 minutes. */
    @GetMapping("/{documentId}/url")
    public ApiResponse<DocumentUrlView> url(@PathVariable Long loanId, @PathVariable Long documentId) {
        return ApiResponse.ok(documents.url(loanId, documentId));
    }
}
