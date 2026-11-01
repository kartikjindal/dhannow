package com.navix.app.staffdesk;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.web.ApiResponse;
import com.navix.loan.service.CreditLevelService;
import com.navix.loan.service.CreditLevelService.CreditLevelView;
import com.navix.loan.service.PricingService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff console reads and the config maker-checker the earlier phases left to the console (PLAN Phase 10):
 * <ul>
 *   <li>{@code GET /api/staff/customers/{id}/credit-level}: the ladder level with its history.</li>
 *   <li>{@code GET /api/staff/balance-adjustments}: the append-only adjustment log (COMPLIANCE reads it).</li>
 *   <li>{@code /api/staff/config}: pricing and product versions; a DRAFT is made by ADMIN or FINANCE and
 *       approved by the other person (maker ≠ checker), which retires the ACTIVE version. Terms of a non-DRAFT
 *       version cannot change (V92/V93 triggers); offers freeze the version they were made on.</li>
 * </ul>
 * Everything is under {@code /api/staff}, so the token audience gate applies; roles are checked here.
 */
@RestController
public class StaffDeskController {

    private static final Set<String> LEDGER_READERS = Set.of("ADMIN", "FINANCE", "COMPLIANCE", "ACCOUNTANT", "COLLECTION_HEAD");
    private static final Set<String> CONFIG_READERS = Set.of("ADMIN", "FINANCE", "COMPLIANCE");
    private static final Set<String> CONFIG_MAKERS = Set.of("ADMIN", "FINANCE");
    /** Pricing terms a draft may set; the rest are copied from the ACTIVE version. */
    private static final Map<String, String> PRICING_FIELDS = Map.of("pfRate", "pf_rate", "gstRate", "gst_rate",
            "dailyRate", "daily_rate", "tenureDays", "tenure_days", "penalRate", "penal_rate", "penalCapDays", "penal_cap_days");
    private static final Map<String, String> PRODUCT_FIELDS = Map.of("minPaise", "min_paise", "maxPaise", "max_paise",
            "overdueReviewDpd", "overdue_review_dpd", "offerValidityDays", "offer_validity_days",
            "extensionMax", "extension_max", "emiGapDays", "emi_gap_days", "emiThreeFromPaise", "emi_three_from_paise");

    private final NamedParameterJdbcTemplate jdbc;
    private final CreditLevelService creditLevels;
    private final PricingService pricing;

    public StaffDeskController(NamedParameterJdbcTemplate jdbc, CreditLevelService creditLevels, PricingService pricing) {
        this.jdbc = jdbc;
        this.creditLevels = creditLevels;
        this.pricing = pricing;
    }

    /** The level, its history, and every ladder amount priced on the ACTIVE terms (the sanction dialog's chips). */
    public record CreditLevelDetail(CreditLevelView current, List<Map<String, Object>> history,
                                    List<PricingService.Quote> quotes) {
    }

    @GetMapping("/api/staff/customers/{customerId}/credit-level")
    public ApiResponse<CreditLevelDetail> creditLevel(@PathVariable Long customerId) {
        requireRole(null);
        List<Map<String, Object>> history = jdbc.queryForList("select h.from_level as \"fromLevel\", h.to_level as \"toLevel\","
                + " h.reason, h.loan_id as \"loanId\", h.reason_code as \"reasonCode\", h.approved_by as \"approvedBy\","
                + " su.name as \"approvedByName\", h.at, h.reversed_at as \"reversedAt\" from credit_level_history h"
                + " left join staff_user su on su.id = h.approved_by where h.customer_id = :c order by h.at desc, h.id desc",
                new MapSqlParameterSource("c", customerId));
        CreditLevelView current = creditLevels.view(customerId);
        return ApiResponse.ok(new CreditLevelDetail(current, history, pricing.quotes(current.ladder())));
    }

    @GetMapping("/api/staff/balance-adjustments")
    public ApiResponse<List<Map<String, Object>>> adjustments(@RequestParam(required = false) Long loanId,
                                                              @RequestParam(required = false) Long customerId,
                                                              @RequestParam(required = false) String kind) {
        requireRole(LEDGER_READERS);
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" where true");
        if (loanId != null) {
            where.append(" and b.loan_id = :loanId");
            p.addValue("loanId", loanId);
        }
        if (customerId != null) {
            where.append(" and b.customer_id = :customerId");
            p.addValue("customerId", customerId);
        }
        if (kind != null && !kind.isBlank()) {
            where.append(" and b.kind = :kind");
            p.addValue("kind", kind);
        }
        return ApiResponse.ok(jdbc.queryForList("select b.id, b.loan_id as \"loanId\", b.customer_id as \"customerId\", b.kind,"
                + " b.reason_code as \"reasonCode\", r.label as \"reasonLabel\", b.note, cast(b.before as text) as before,"
                + " cast(b.after as text) as after, b.schedule_version_from as \"scheduleVersionFrom\","
                + " b.schedule_version_to as \"scheduleVersionTo\", b.approver_id as \"approverId\", su.name as \"approverName\", b.at"
                + " from balance_adjustment b left join balance_adjustment_reason r on r.code = b.reason_code"
                + " left join staff_user su on su.id = b.approver_id" + where + " order by b.at desc, b.id desc limit 500", p));
    }

    public record ConfigView(List<Map<String, Object>> pricing, List<Map<String, Object>> product) {
    }

    @GetMapping("/api/staff/config")
    public ApiResponse<ConfigView> config() {
        requireRole(CONFIG_READERS);
        return ApiResponse.ok(new ConfigView(versions("pricing_config"), versions("product_config")));
    }

    @PostMapping("/api/staff/config/{kind}")
    @Transactional
    public ApiResponse<ConfigView> draft(@PathVariable String kind, @RequestBody Map<String, Object> terms) {
        CurrentActor actor = requireRole(CONFIG_MAKERS);
        String table = table(kind);
        Map<String, String> fields = "pricing".equals(kind) ? PRICING_FIELDS : PRODUCT_FIELDS;
        if (count("select count(*) from " + table + " where status = 'DRAFT'") > 0) {
            throw new BusinessException("DRAFT_EXISTS", "Approve or discard the open draft first");
        }
        List<String> cols = jdbc.queryForList("select column_name from information_schema.columns where table_name = :t"
                + " and column_name not in ('id', 'version', 'status', 'created_at', 'created_by', 'updated_at', 'updated_by',"
                + " 'approved_by', 'approved_at') order by ordinal_position", new MapSqlParameterSource("t", table), String.class);
        String list = String.join(", ", cols);
        jdbc.update("insert into " + table + " (version, status, created_by, " + list + ") select"
                + " (select max(version) + 1 from " + table + "), 'DRAFT', :by, " + list + " from " + table
                + " where status = 'ACTIVE'", new MapSqlParameterSource("by", actor.id()));
        MapSqlParameterSource p = new MapSqlParameterSource();
        List<String> sets = new ArrayList<>();
        terms.forEach((k, v) -> {
            String col = fields.get(k);
            if (col == null) {
                throw new BusinessException("UNKNOWN_FIELD", "Not an editable term: " + k);
            }
            if (!(v instanceof Number n) || n.doubleValue() < 0) {
                throw new BusinessException("INVALID_VALUE", k + " must be a number of zero or more");
            }
            sets.add(col + " = :" + k);
            p.addValue(k, v instanceof Double d ? BigDecimal.valueOf(d) : v);
        });
        if (!sets.isEmpty()) {
            jdbc.update("update " + table + " set " + String.join(", ", sets) + " where status = 'DRAFT'", p);
        }
        return config();
    }

    @PostMapping("/api/staff/config/{kind}/{id}/approve")
    @Transactional
    public ApiResponse<ConfigView> approve(@PathVariable String kind, @PathVariable Long id) {
        CurrentActor actor = requireRole(CONFIG_MAKERS);
        String table = table(kind);
        Map<String, Object> row = draftRow(table, id);
        if (actor.id().equals(String.valueOf(row.get("created_by")))) {
            throw new BusinessException("SOD_VIOLATION", "A draft is approved by someone other than its maker");
        }
        jdbc.update("update " + table + " set status = 'RETIRED', updated_by = :by, updated_at = now() where status = 'ACTIVE'",
                new MapSqlParameterSource("by", actor.id()));
        String approval = "pricing".equals(kind) ? ", approved_by = :approver, approved_at = now(), effective_from = now()" : "";
        jdbc.update("update " + table + " set status = 'ACTIVE', updated_by = :by, updated_at = now()" + approval + " where id = :id",
                new MapSqlParameterSource("id", id).addValue("by", actor.id()).addValue("approver", Long.valueOf(actor.id())));
        return config();
    }

    @PostMapping("/api/staff/config/{kind}/{id}/discard")
    @Transactional
    public ApiResponse<ConfigView> discard(@PathVariable String kind, @PathVariable Long id) {
        requireRole(CONFIG_MAKERS);
        String table = table(kind);
        draftRow(table, id);
        jdbc.update("delete from " + table + " where id = :id and status = 'DRAFT'", new MapSqlParameterSource("id", id));
        return config();
    }

    private Map<String, Object> draftRow(String table, Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from " + table + " where id = :id and status = 'DRAFT'",
                new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new BusinessException("STALE_STATE", "That version is not an open draft");
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> versions(String table) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select * from " + table + " order by version desc limit 20",
                new MapSqlParameterSource())) {
            Map<String, Object> m = new LinkedHashMap<>();
            r.forEach((k, v) -> m.put(camel(k), v instanceof java.sql.Array a ? sqlArray(a) : v));
            out.add(m);
        }
        return out;
    }

    private static Object sqlArray(java.sql.Array a) {
        try {
            return a.getArray();
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return n == null ? 0 : n;
    }

    private static String table(String kind) {
        return switch (kind) {
            case "pricing" -> "pricing_config";
            case "product" -> "product_config";
            default -> throw new BusinessException("UNKNOWN_CONFIG", "Config is pricing or product");
        };
    }

    private static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    /** Any staff role but DSA when {@code allowed} is null; else one of {@code allowed} (ADMIN always passes reads). */
    private static CurrentActor requireRole(Set<String> allowed) {
        CurrentActor actor = ActorContext.get();
        String role = actor.role();
        boolean ok = allowed == null ? role != null && !"DSA".equals(role) && !"BORROWER".equals(role) && !"SYSTEM".equals(role)
                : allowed.contains(role);
        if (!ok) {
            throw new BusinessException("FORBIDDEN_ROLE", "Your role cannot use this");
        }
        return actor;
    }
}
