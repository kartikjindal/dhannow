package com.navix.collections.repository;

import com.navix.collections.entity.ExtensionStatus;
import com.navix.collections.entity.LoanExtension;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for {@link LoanExtension} (V103). */
@Repository
public interface LoanExtensionRepository extends JpaRepository<LoanExtension, UUID> {

    List<LoanExtension> findByLoanIdOrderBySeqAsc(Long loanId);

    Optional<LoanExtension> findFirstByLoanIdAndStatusIn(Long loanId, Collection<ExtensionStatus> statuses);

    @Query("select coalesce(max(e.seq), 0) from LoanExtension e where e.loanId = :loanId")
    int maxSeq(@Param("loanId") Long loanId);

    /** Offers whose pay-by has passed and that still wait on the Head or the borrower. */
    @Query("select e.id from LoanExtension e where e.status in :statuses and e.expiresAt < :now")
    List<UUID> findExpirable(@Param("statuses") Collection<ExtensionStatus> statuses, @Param("now") Instant now);
}
