package com.navix.loan.repository;

import com.navix.loan.entity.CreditLevelHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Every credit-level decision (V96). */
@Repository
public interface CreditLevelHistoryRepository extends JpaRepository<CreditLevelHistory, Long> {

    /** The closure row of a loan that has not been reversed (one at most, V104). */
    java.util.Optional<CreditLevelHistory> findFirstByLoanIdAndReasonInAndReversedAtIsNull(Long loanId,
            java.util.Collection<com.navix.loan.domain.CreditLevelReason> reasons);
}
