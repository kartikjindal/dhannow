package com.navix.loan.repository;

import com.navix.loan.entity.CreditLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** A customer's credit-ladder level (V96). */
@Repository
public interface CreditLevelRepository extends JpaRepository<CreditLevel, Long> {
}
