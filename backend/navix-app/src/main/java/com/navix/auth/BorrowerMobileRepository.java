package com.navix.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for {@link BorrowerMobile}: which customer id each mobile signs in to (CC-24). */
@Repository
public interface BorrowerMobileRepository extends JpaRepository<BorrowerMobile, Long> {

    java.util.Optional<BorrowerMobile> findByMobile(String mobile);

    /** The next server-generated customer id (V112 {@code customer_id_seq}). */
    @org.springframework.data.jpa.repository.Query(value = "select nextval('customer_id_seq')", nativeQuery = true)
    long nextCustomerId();

    /** CC-45: the account asked to be deleted, so it no longer signs in. */
    @org.springframework.data.jpa.repository.Query(value = "select exists (select 1 from account_deletion_request"
            + " where customer_id = :customerId)", nativeQuery = true)
    boolean deletionRequested(@org.springframework.data.repository.query.Param("customerId") long customerId);
}
