package com.navix.loan.repository;

import com.navix.loan.entity.CreditLevelHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Every credit-level decision (V96). */
@Repository
public interface CreditLevelHistoryRepository extends JpaRepository<CreditLevelHistory, Long> {
}
