package com.navix.loan.dto;

import com.navix.loan.domain.LoanDocumentType;
import com.navix.loan.entity.LoanDocument;
import java.time.Instant;

/** Loan documents (PLAN Phase 5): the KFS on the offer, and a loan's document list and download links. */
public final class LoanDocumentDtos {

    private LoanDocumentDtos() {
    }

    /** The offer's current KFS: a short-lived link to read it, and whether it is acknowledged. */
    public record KfsView(Long documentId, int version, String url, boolean acknowledged, Instant acceptedAt,
                          Instant offerValidUntil) {
    }

    public record LoanDocumentView(Long id, LoanDocumentType type, int version, Instant createdAt,
                                   Instant acceptedAt, String sha256) {
        public static LoanDocumentView of(LoanDocument d) {
            return new LoanDocumentView(d.getId(), d.getType(), d.getVersion(), d.getCreatedAt(),
                    d.getAcceptedAt(), d.getSha256());
        }
    }

    public record DocumentUrlView(Long documentId, String url, long expiresInSeconds) {
    }
}
