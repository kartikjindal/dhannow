package com.navix.loan.repository;

import com.navix.loan.domain.EmiStatus;
import com.navix.loan.entity.EmiConversion;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for {@link EmiConversion} (V104). */
@Repository
public interface EmiConversionRepository extends JpaRepository<EmiConversion, UUID> {

    List<EmiConversion> findByLoanIdOrderByRequestedAtAsc(Long loanId);

    List<EmiConversion> findByStatusOrderByRequestedAtAsc(EmiStatus status);

    Optional<EmiConversion> findFirstByLoanIdAndStatusIn(Long loanId, Collection<EmiStatus> statuses);

    boolean existsByLoanIdAndStatusIn(Long loanId, Collection<EmiStatus> statuses);

    /** Open offers past their accept-by day. */
    @Query("select e.id from EmiConversion e where e.status in :statuses and e.expiresAt < :now")
    List<UUID> findExpirable(@Param("statuses") Collection<EmiStatus> statuses, @Param("now") Instant now);

    /** The loan closed: any open offer ends with it (the caller holds the loan's row). */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("update EmiConversion e set e.status = com.navix.loan.domain.EmiStatus.CANCELLED,"
            + " e.cancelReason = 'LOAN_CLOSED', e.cancelledAt = :at, e.version = e.version + 1"
            + " where e.loanId = :loanId and e.status in (com.navix.loan.domain.EmiStatus.REQUESTED,"
            + " com.navix.loan.domain.EmiStatus.APPROVED)")
    int cancelOpen(@Param("loanId") Long loanId, @Param("at") Instant at);
}
