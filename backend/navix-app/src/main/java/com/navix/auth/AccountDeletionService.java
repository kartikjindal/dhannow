package com.navix.auth;

import com.navix.common.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CC-45, account deletion from the app (Play policy). Refused while a loan is open or an application is under way
 * ({@code LOAN_OPEN}). Otherwise, at once: sign-in is closed ({@code ACCOUNT_CLOSED} from then on), every session and
 * push token ends, offers are switched off and the consent withdrawal is recorded. KYC and loan records are
 * regulatory records and stay until {@code purge_after} ({@code navix.account-deletion.retention-years} after the
 * request); {@link #purgeDue} then blanks the identity fields and frees the mobile.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    /** Application statuses that hold nothing open: anything else is a file still moving (or money in flight). */
    private static final String SETTLED_APP_STATUSES = "('DRAFT', 'KYC_REJECTED', 'REJECTED', 'CANCELLED', 'CLOSED', 'WRITTEN_OFF')";

    public record DeletionView(String status, Instant requestedAt, LocalDate purgeAfter) {
    }

    private final JdbcTemplate jdbc;
    private final RefreshTokenService refreshTokens;
    private final DeviceService devices;
    private final Clock clock;
    private final int retentionYears;

    public AccountDeletionService(JdbcTemplate jdbc, RefreshTokenService refreshTokens, DeviceService devices,
                                  Clock clock,
                                  @Value("${navix.account-deletion.retention-years:5}") int retentionYears) {
        this.jdbc = jdbc;
        this.refreshTokens = refreshTokens;
        this.devices = devices;
        this.clock = clock;
        this.retentionYears = retentionYears;
    }

    /** The borrower's request. Idempotent: a second call returns the first request. */
    @Transactional
    public DeletionView request(long customerId) {
        Optional<DeletionView> existing = find(customerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Long open = jdbc.queryForObject("select (select count(*) from loan where customer_id = ? and lifecycle_status <> 'CLOSED')"
                + " + (select count(*) from loan_application where customer_id = ? and status not in "
                + SETTLED_APP_STATUSES + ")", Long.class, customerId, customerId);
        if (open != null && open > 0) {
            throw new BusinessException("LOAN_OPEN",
                    "You have a loan or an application in progress. You can delete your account once it is closed.");
        }
        LocalDate purgeAfter = LocalDate.now(clock).plusYears(retentionYears);
        jdbc.update("insert into account_deletion_request (customer_id, purge_after) values (?, ?)", customerId,
                purgeAfter);
        jdbc.update("update borrower_preferences set offers_opt_in = false where customer_id = ?", customerId);
        // Sessions and push end now; the 15-minute access token lapses on its own.
        refreshTokens.revokeAll(customerId);
        devices.removeAll(customerId);
        log.info("account deletion requested customerId={} purgeAfter={}", customerId, purgeAfter);
        return find(customerId).orElseThrow();
    }

    public Optional<DeletionView> find(long customerId) {
        List<DeletionView> rows = jdbc.query("select status, requested_at, purge_after from account_deletion_request"
                + " where customer_id = ?", (rs, i) -> new DeletionView(rs.getString(1),
                rs.getTimestamp(2).toInstant(), rs.getObject(3, LocalDate.class)), customerId);
        return rows.stream().findFirst();
    }

    /**
     * Blank the identity of every account whose retention period has ended: profile identity fields, bank
     * accounts, the waitlist form, the password and the mobile registration. Loans, payments and adjustments stay
     * (they are anonymous without the profile). @return how many accounts were purged.
     */
    @Transactional
    public int purgeDue() {
        List<Long> due = jdbc.queryForList("select customer_id from account_deletion_request where status = 'REQUESTED'"
                + " and purge_after <= ? for update skip locked", Long.class, LocalDate.now(clock));
        for (Long c : due) {
            jdbc.update("update customer_profile set full_name = null, pan = null, pan_hash = null, aadhaar = null,"
                    + " aadhaar_hash = null, mobile = null, email = null, official_email = null, address = null,"
                    + " dob = null, uan = null, salary_account_number = null, salary_account_mobile = null,"
                    + " emergency_contact_name = null, emergency_contact_phone = null, emergency_contact_relation = null"
                    + " where application_id in (select id from loan_application where customer_id = ?)", c);
            jdbc.update("update loan_application set disbursal_account_number = null, disbursal_holder_name = null"
                    + " where customer_id = ?", c);
            jdbc.update("update penny_drop_attempt set account_number = null where customer_id = ?", c);
            jdbc.update("delete from onboarding_waitlist where customer_id = ?", c);
            jdbc.update("delete from borrower_credential where customer_id = ?", c);
            jdbc.update("delete from device_token where customer_id = ?", c);
            jdbc.update("delete from borrower_mobile where customer_id = ?", c);
            jdbc.update("update account_deletion_request set status = 'PURGED', purged_at = now() where customer_id = ?", c);
            log.info("account purged customerId={}", c);
        }
        return due.size();
    }
}
