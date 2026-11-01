package com.navix.loan.service;

import com.navix.common.brand.GrievanceProperties;
import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ConflictException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.idempotency.IdempotencyService;
import com.navix.common.notification.event.ComplaintFiledEvent;
import com.navix.common.notification.event.ComplaintResolvedEvent;
import com.navix.common.notification.event.ComplaintSlaBreachedEvent;
import com.navix.common.notification.event.ExtensionDeclinedEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.LoanRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grievance tickets (PLAN Phase 7, {@code complaint}, V105). A borrower files one in the app; SUPPORT can file one
 * for a borrower who called; the system files one when a borrower says they never asked for an extension or EMI
 * offer (CC-71). Each ticket carries its SLA due dates ({@link ComplaintSla}); the SLA sweep flags breaches.
 *
 * <p>Staff: SUPPORT and ADMIN work the queue, COMPLIANCE reads it. A {@code LOAN_DISPUTE} ticket on a loan puts the
 * loan on hold ({@link LoanDisputeService} hears {@link ComplaintFiledEvent} in the same transaction).
 */
@Service
@RequiredArgsConstructor
public class ComplaintService {

    public enum Status { OPEN, ACKNOWLEDGED, IN_REVIEW, RESOLVED, ESCALATED }

    public static final Set<String> CATEGORIES = Set.of("PAYMENT", "CHARGES", "COLLECTIONS_CONDUCT", "LOAN_DISPUTE",
            "EXTENSION_NOT_REQUESTED", "DATA_PRIVACY", "APP_OR_SERVICE", "OTHER");
    static final Set<String> READ_ROLES = Set.of("SUPPORT", "ADMIN", "COMPLIANCE");
    static final Set<String> WRITE_ROLES = Set.of("SUPPORT", "ADMIN");
    static final int MAX_BODY = 4000;

    /** Allowed moves. RESOLVED is final; ESCALATED goes back to review or is resolved. */
    static final Map<Status, Set<Status>> MOVES = Map.of(
            Status.OPEN, EnumSet.of(Status.ACKNOWLEDGED, Status.IN_REVIEW, Status.RESOLVED, Status.ESCALATED),
            Status.ACKNOWLEDGED, EnumSet.of(Status.IN_REVIEW, Status.RESOLVED, Status.ESCALATED),
            Status.IN_REVIEW, EnumSet.of(Status.RESOLVED, Status.ESCALATED),
            Status.ESCALATED, EnumSet.of(Status.IN_REVIEW, Status.RESOLVED),
            Status.RESOLVED, EnumSet.noneOf(Status.class));

    public record FileRequest(String category, String body, Long loanId, String attachmentKey) {
    }

    public record StaffFileRequest(Long customerId, String category, String body, Long loanId, String attachmentKey) {
    }

    public record GrievanceOfficer(String name, String phone, String email) {
    }

    public record ComplaintView(Long id, String ticketNo, Long customerId, Long loanId, String category, String body,
                                String attachmentKey, String source, String status, Instant createdAt,
                                Instant ackDueAt, Instant reviewDueAt, Instant resolveDueAt, Instant acknowledgedAt,
                                Instant reviewStartedAt, Instant resolvedAt, Instant escalatedAt,
                                String resolutionNote, Long assignedTo, List<String> breached, long version) {
    }

    /** What the borrower sees: their ticket plus where to escalate it (the grievance officer, from config). */
    public record BorrowerComplaintView(Long id, String ticketNo, Long loanId, String category, String body,
                                        String status, Instant createdAt, Instant ackDueAt, Instant resolveDueAt,
                                        Instant acknowledgedAt, Instant resolvedAt, String resolutionNote,
                                        GrievanceOfficer grievanceOfficer) {
    }

    public record EventView(String action, String fromStatus, String toStatus, String note, String actorId,
                            String actorRole, Instant at) {
    }

    public record StaffComplaintView(ComplaintView complaint, List<EventView> events) {
    }

    public record Holiday(LocalDate day, String name) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final IdempotencyService idempotency;
    private final StaffDirectory staff;
    private final LoanRepository loans;
    private final GrievanceProperties grievance;

    // ---- borrower -----------------------------------------------------------------------

    /** The borrower files a ticket. One per {@code Idempotency-Key} (CC-22). */
    @Transactional
    public BorrowerComplaintView file(FileRequest request, String idempotencyKey) {
        CurrentActor actor = requireBorrower();
        Long customerId = Long.valueOf(actor.id());
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException("IDEMPOTENCY_KEY_REQUIRED", "Send an Idempotency-Key header with this request.");
        }
        validate(request == null ? null : request.category(), request == null ? null : request.body(),
                request == null ? null : request.attachmentKey());
        requireOwnLoan(request.loanId(), customerId);
        Long id = idempotency.execute("COMPLAINT_FILE", idempotencyKey.trim(),
                customerId + "|" + request.category() + "|" + request.loanId() + "|" + request.body().trim(),
                () -> insert(customerId, request.loanId(), request.category(), request.body(),
                        request.attachmentKey(), "BORROWER", actor));
        return borrowerView(require(id));
    }

    @Transactional(readOnly = true)
    public List<BorrowerComplaintView> mine() {
        Long customerId = Long.valueOf(requireBorrower().id());
        return jdbc.query(SELECT + " where customer_id = ? order by id desc", ROW, customerId).stream()
                .map(this::borrowerView).toList();
    }

    @Transactional(readOnly = true)
    public BorrowerComplaintView mineOne(Long id) {
        Long customerId = Long.valueOf(requireBorrower().id());
        ComplaintView c = require(id);
        if (!customerId.equals(c.customerId())) {
            throw new BusinessException("FORBIDDEN_OWNER", "Not your complaint");
        }
        return borrowerView(c);
    }

    // ---- staff --------------------------------------------------------------------------

    /** SUPPORT files a ticket for a borrower who called or wrote in. */
    @Transactional
    public ComplaintView fileFor(StaffFileRequest request) {
        CurrentActor actor = requireRole(WRITE_ROLES);
        if (request == null || request.customerId() == null) {
            throw new BusinessException("CUSTOMER_REQUIRED", "Choose the customer the complaint is from.");
        }
        validate(request.category(), request.body(), request.attachmentKey());
        requireOwnLoan(request.loanId(), request.customerId());
        return require(insert(request.customerId(), request.loanId(), request.category(), request.body(),
                request.attachmentKey(), "STAFF", actor));
    }

    /** The queue, newest first. Every filter is optional; {@code breached=true} keeps tickets past any milestone. */
    @Transactional(readOnly = true)
    public List<ComplaintView> list(String status, String category, Long assignedTo, Long customerId, Boolean breached) {
        requireRole(READ_ROLES);
        StringBuilder where = new StringBuilder(" where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" and status = ?");
            args.add(parseStatus(status).name());
        }
        if (category != null && !category.isBlank()) {
            where.append(" and category = ?");
            args.add(category.trim());
        }
        if (assignedTo != null) {
            where.append(" and assigned_to = ?");
            args.add(assignedTo);
        }
        if (customerId != null) {
            where.append(" and customer_id = ?");
            args.add(customerId);
        }
        if (Boolean.TRUE.equals(breached)) {
            where.append(" and (ack_breached_at is not null or review_breached_at is not null"
                    + " or resolve_breached_at is not null)");
        }
        // ponytail: fixed 500-row cap, add paging when the queue outgrows it.
        return jdbc.query(SELECT + where + " order by id desc limit 500", ROW, args.toArray());
    }

    @Transactional(readOnly = true)
    public StaffComplaintView get(Long id) {
        requireRole(READ_ROLES);
        return new StaffComplaintView(require(id), jdbc.query("select action, from_status, to_status, note, actor_id,"
                + " actor_role, at from complaint_event where complaint_id = ? order by id", (rs, i) -> new EventView(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getTimestamp(7).toInstant()), id));
    }

    @Transactional
    public ComplaintView acknowledge(Long id, String note, Long version) {
        return move(id, Status.ACKNOWLEDGED, note, version);
    }

    @Transactional
    public ComplaintView resolve(Long id, String note, Long version) {
        return move(id, Status.RESOLVED, note, version);
    }

    @Transactional
    public ComplaintView escalate(Long id, String note, Long version) {
        return move(id, Status.ESCALATED, note, version);
    }

    /** Assign (or, with null, unassign) the ticket to an active staff member. */
    @Transactional
    public ComplaintView assign(Long id, Long staffId) {
        CurrentActor actor = requireRole(WRITE_ROLES);
        ComplaintView c = require(id);
        if (staffId != null) {
            var s = staff.findStaff(staffId).orElse(null);
            if (s == null || !s.active() || "DSA".equals(s.role()) || "COMPLIANCE".equals(s.role())) {
                throw new BusinessException("INVALID_ASSIGNEE", "Assign the complaint to an active staff member.");
            }
        }
        if (c.status().equals(Status.RESOLVED.name())) {
            throw new ConflictException("STALE_STATE", "This complaint is resolved.", c.status());
        }
        jdbc.update("update complaint set assigned_to = ?, version = version + 1, updated_at = ? where id = ?",
                staffId, Timestamp.from(clock.instant()), id);
        event(id, "ASSIGNED", null, null, staffId == null ? "Unassigned" : "Assigned to staff " + staffId, actor);
        return require(id);
    }

    /**
     * Move a ticket. Leaving OPEN acknowledges it; reaching IN_REVIEW (or resolving) starts the review; RESOLVED and
     * ESCALATED need a note. A stale {@code version} or a move the ticket's status does not allow is 409.
     */
    @Transactional
    public ComplaintView move(Long id, Status to, String note, Long version) {
        CurrentActor actor = requireRole(WRITE_ROLES);
        ComplaintView c = require(id);
        Status from = Status.valueOf(c.status());
        if (version != null && version != c.version()) {
            throw new ConflictException("STALE_STATE", "This complaint changed; refresh and try again.", c.status());
        }
        if (!MOVES.get(from).contains(to)) {
            throw new ConflictException("STALE_STATE", "A " + from.name().toLowerCase() + " complaint cannot move to "
                    + to.name().toLowerCase() + ".", c.status());
        }
        String text = note == null || note.isBlank() ? null : note.trim();
        if ((to == Status.RESOLVED || to == Status.ESCALATED) && text == null) {
            throw new BusinessException("NOTE_REQUIRED", to == Status.RESOLVED
                    ? "Write the resolution the borrower will read." : "Say why the complaint is escalated.");
        }
        Timestamp now = Timestamp.from(clock.instant());
        int updated = jdbc.update("""
                update complaint set status = ?, version = version + 1, updated_at = ?,
                    acknowledged_at = coalesce(acknowledged_at, ?),
                    review_started_at = case when ? in ('IN_REVIEW', 'RESOLVED') then coalesce(review_started_at, ?)
                                             else review_started_at end,
                    resolved_at = case when ? = 'RESOLVED' then ? else resolved_at end,
                    resolution_note = case when ? = 'RESOLVED' then ? else resolution_note end,
                    escalated_at = case when ? = 'ESCALATED' then ? else escalated_at end
                where id = ? and status = ? and version = ?
                """, to.name(), now, now, to.name(), now, to.name(), now, to.name(), text, to.name(), now,
                id, from.name(), c.version());
        if (updated == 0) {
            throw new ConflictException("STALE_STATE", "This complaint changed; refresh and try again.", c.status());
        }
        event(id, to.name(), from.name(), to.name(), text, actor);
        if (to == Status.RESOLVED) {
            events.publishEvent(new ComplaintResolvedEvent(id, c.ticketNo(), c.customerId(), text));
        }
        return require(id);
    }

    // ---- holiday calendar (CC-64), ADMIN -------------------------------------------------

    @Transactional(readOnly = true)
    public List<Holiday> holidays() {
        requireRole(Set.of("ADMIN", "SUPPORT", "COMPLIANCE"));
        return jdbc.query("select day, name from holiday_calendar order by day",
                (rs, i) -> new Holiday(rs.getObject(1, LocalDate.class), rs.getString(2)));
    }

    /** Add or rename a holiday. Tickets already filed keep the due dates they were given. */
    @Transactional
    public List<Holiday> addHoliday(LocalDate day, String name) {
        CurrentActor actor = requireRole(Set.of("ADMIN"));
        if (day == null || name == null || name.isBlank()) {
            throw new BusinessException("HOLIDAY_REQUIRED", "Give the holiday a date and a name.");
        }
        jdbc.update("insert into holiday_calendar (day, name, created_by) values (?, ?, ?)"
                + " on conflict (day) do update set name = excluded.name", day, name.trim(), actor.id());
        return holidays();
    }

    @Transactional
    public List<Holiday> removeHoliday(LocalDate day) {
        requireRole(Set.of("ADMIN"));
        jdbc.update("delete from holiday_calendar where day = ?", day);
        return holidays();
    }

    // ---- system -------------------------------------------------------------------------

    /** CC-71: a borrower who says they never asked for an extension gets a complaint ticket, in the same transaction. */
    @EventListener
    public void onExtensionDeclined(ExtensionDeclinedEvent e) {
        if ("DID_NOT_ASK".equals(e.reason()) && e.customerId() != null) {
            openSystem(e.customerId(), e.loanId(), "Borrower said they did not ask for the loan extension offered"
                    + " on this loan (extension " + e.extensionId() + ").");
        }
    }

    /** A ticket the system opens on the borrower's behalf (CC-71: "I didn't ask" on an extension or EMI offer). */
    @Transactional
    public Long openSystem(Long customerId, Long loanId, String body) {
        return insert(customerId, loanId, "EXTENSION_NOT_REQUESTED", body, null, "SYSTEM", CurrentActor.SYSTEM);
    }

    /**
     * The SLA sweep: flag each milestone missed (not yet acknowledged / in review / resolved by its due time) once,
     * and tell the assignee and the ADMINs. Idempotent: a flagged milestone is never flagged again.
     *
     * @return how many milestones were flagged
     */
    @Transactional
    public int flagBreaches() {
        Timestamp now = Timestamp.from(clock.instant());
        int flagged = 0;
        for (String[] m : new String[][] {{"ACK", "ack", "acknowledged_at"}, {"REVIEW", "review", "review_started_at"},
                {"RESOLVE", "resolve", "resolved_at"}}) {
            String sql = "update complaint set " + m[1] + "_breached_at = ? where " + m[1] + "_breached_at is null and "
                    + m[2] + " is null and status <> 'RESOLVED' and " + m[1] + "_due_at < ?"
                    + " returning id, ticket_no, customer_id, assigned_to, " + m[1] + "_due_at";
            for (ComplaintSlaBreachedEvent e : jdbc.query(sql, (rs, i) -> new ComplaintSlaBreachedEvent(
                    rs.getLong(1), rs.getString(2), rs.getLong(3), (Long) rs.getObject(4), m[0],
                    rs.getTimestamp(5).toInstant()), now, now)) {
                events.publishEvent(e);
                flagged++;
            }
        }
        return flagged;
    }

    // ---- internals ----------------------------------------------------------------------

    private Long insert(Long customerId, Long loanId, String category, String body, String attachmentKey,
                        String source, CurrentActor actor) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(ComplaintSla.IST).toLocalDate();
        Set<LocalDate> holidays = new HashSet<>(jdbc.queryForList("select day from holiday_calendar where day between ?"
                + " and ?", LocalDate.class, today, today.plusDays(ComplaintSla.HOLIDAY_LOOKAHEAD_DAYS)));
        ComplaintSla.DueDates due = ComplaintSla.dueDates(now, holidays);
        Map<String, Object> row = jdbc.queryForMap("""
                insert into complaint (customer_id, loan_id, category, body, attachment_key, source, ack_due_at,
                    review_due_at, resolve_due_at, created_by, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id, ticket_no
                """, customerId, loanId, category, body.trim(), blankToNull(attachmentKey), source,
                Timestamp.from(due.ackDueAt()), Timestamp.from(due.reviewDueAt()), Timestamp.from(due.resolveDueAt()),
                actor.id(), Timestamp.from(now));
        Long id = ((Number) row.get("id")).longValue();
        event(id, "FILED", null, Status.OPEN.name(), null, actor);
        events.publishEvent(new ComplaintFiledEvent(id, (String) row.get("ticket_no"), customerId, loanId, category,
                source, due.resolveDueAt()));
        return id;
    }

    private void event(Long complaintId, String action, String from, String to, String note, CurrentActor actor) {
        jdbc.update("insert into complaint_event (complaint_id, action, from_status, to_status, note, actor_id,"
                        + " actor_role, at) values (?, ?, ?, ?, ?, ?, ?, ?)", complaintId, action, from, to, note,
                actor.id(), actor.role(), Timestamp.from(clock.instant()));
    }

    private static void validate(String category, String body, String attachmentKey) {
        if (category == null || !CATEGORIES.contains(category)) {
            throw new BusinessException("INVALID_CATEGORY", "category must be one of " + CATEGORIES);
        }
        if (body == null || body.isBlank()) {
            throw new BusinessException("BODY_REQUIRED", "Tell us what went wrong.");
        }
        if (body.length() > MAX_BODY) {
            throw new BusinessException("BODY_TOO_LONG", "Keep the complaint under " + MAX_BODY + " characters.");
        }
        if (attachmentKey != null && attachmentKey.length() > 512) {
            throw new BusinessException("INVALID_ATTACHMENT", "The attachment reference is too long.");
        }
    }

    private void requireOwnLoan(Long loanId, Long customerId) {
        if (loanId == null) {
            return;
        }
        Loan loan = loans.findById(loanId).orElseThrow(() -> new ResourceNotFoundException("Loan", String.valueOf(loanId)));
        if (!customerId.equals(loan.getCustomerId())) {
            throw new BusinessException("FORBIDDEN_OWNER", "Not your loan");
        }
    }

    private static CurrentActor requireBorrower() {
        CurrentActor actor = ActorContext.get();
        if (!"BORROWER".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "Only the borrower can do this.");
        }
        return actor;
    }

    private static CurrentActor requireRole(Set<String> roles) {
        CurrentActor actor = ActorContext.get();
        if (!roles.contains(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role " + String.join(" or ",
                    roles.stream().sorted().toList()));
        }
        return actor;
    }

    public static Status parseStatus(String status) {
        try {
            return Status.valueOf(status == null ? "" : status.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_STATUS", "status must be one of " + List.of(Status.values()));
        }
    }

    private ComplaintView require(Long id) {
        return jdbc.query(SELECT + " where id = ?", ROW, id).stream().findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Complaint", String.valueOf(id)));
    }

    private BorrowerComplaintView borrowerView(ComplaintView c) {
        return new BorrowerComplaintView(c.id(), c.ticketNo(), c.loanId(), c.category(), c.body(), c.status(),
                c.createdAt(), c.ackDueAt(), c.resolveDueAt(), c.acknowledgedAt(), c.resolvedAt(), c.resolutionNote(),
                new GrievanceOfficer(grievance.officerName(), grievance.officerPhone(), grievance.officerEmail()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static final String SELECT = "select id, ticket_no, customer_id, loan_id, category, body, attachment_key,"
            + " source, status, created_at, ack_due_at, review_due_at, resolve_due_at, acknowledged_at,"
            + " review_started_at, resolved_at, escalated_at, resolution_note, assigned_to, ack_breached_at,"
            + " review_breached_at, resolve_breached_at, version from complaint";

    private static final RowMapper<ComplaintView> ROW = (rs, i) -> {
        List<String> breached = new ArrayList<>();
        for (String m : List.of("ack", "review", "resolve")) {
            if (rs.getTimestamp(m + "_breached_at") != null) {
                breached.add(m.toUpperCase());
            }
        }
        return new ComplaintView(rs.getLong("id"), rs.getString("ticket_no"), rs.getLong("customer_id"),
                (Long) rs.getObject("loan_id"), rs.getString("category"), rs.getString("body"),
                rs.getString("attachment_key"), rs.getString("source"), rs.getString("status"),
                instant(rs, "created_at"), instant(rs, "ack_due_at"), instant(rs, "review_due_at"),
                instant(rs, "resolve_due_at"), instant(rs, "acknowledged_at"), instant(rs, "review_started_at"),
                instant(rs, "resolved_at"), instant(rs, "escalated_at"), rs.getString("resolution_note"),
                (Long) rs.getObject("assigned_to"), breached, rs.getLong("version"));
    };
}
