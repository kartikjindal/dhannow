package com.navix.loan.repository;

import com.navix.loan.domain.LoanDocumentType;
import com.navix.loan.entity.LoanDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanDocumentRepository extends JpaRepository<LoanDocument, Long> {

    /** The latest version of one document type issued on an offer. */
    Optional<LoanDocument> findFirstByApplicationIdAndTypeOrderByVersionDesc(Long applicationId, LoanDocumentType type);

    /** Every document of a loan: those issued on its offer and those issued on the loan itself. */
    List<LoanDocument> findByApplicationIdOrLoanIdOrderByIdAsc(Long applicationId, Long loanId);

    boolean existsByLoanIdAndType(Long loanId, LoanDocumentType type);
}
