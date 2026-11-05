package com.enterprise.investment.business;

import com.enterprise.investment.security.AccountPrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.enterprise.investment.business.BusinessData.params;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final BusinessData db;

    DashboardController(BusinessData db) {
        this.db = db;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Dashboard dashboard(@AuthenticationPrincipal AccountPrincipal actor) {
        long enterpriseCount = number(db.one("SELECT COUNT(*) AS amount FROM ent_enterprise",
                Map.of(), "无法统计企业数量"), "amount");
        if ("DATA_STEWARD".equals(actor.role())) {
            return new Dashboard(enterpriseCount, null, null, null, null, null, List.of());
        }

        Long owner = "INVESTMENT_OFFICER".equals(actor.role()) ? actor.id() : null;
        var counts = db.one("""
                SELECT COUNT(*) AS leads,
                       COALESCE(SUM(CASE WHEN next_followup_at < NOW()
                           AND lead_status NOT IN ('WON', 'LOST') THEN 1 ELSE 0 END), 0) AS overdue,
                       COALESCE(SUM(CASE WHEN next_followup_at >= NOW()
                           AND next_followup_at < CURRENT_DATE + INTERVAL 1 DAY
                           AND lead_status NOT IN ('WON', 'LOST') THEN 1 ELSE 0 END), 0) AS today,
                       COALESCE(SUM(CASE WHEN next_followup_at >= NOW()
                           AND next_followup_at < CURRENT_DATE + INTERVAL 7 DAY
                           AND lead_status NOT IN ('WON', 'LOST') THEN 1 ELSE 0 END), 0) AS upcoming
                FROM crm_lead WHERE (:owner IS NULL OR owner_id = :owner)
                """, params("owner", owner), "无法统计线索数量");
        long projects = number(db.one("SELECT COUNT(*) AS amount FROM crm_project",
                Map.of(), "无法统计项目数量"), "amount");
        var reminders = db.query("""
                SELECT l.id, l.enterprise_id, e.name AS enterprise_name, p.project_name,
                       l.lead_status, l.next_followup_at
                FROM crm_lead l
                JOIN ent_enterprise e ON e.id = l.enterprise_id
                LEFT JOIN crm_project p ON p.id = l.project_id
                WHERE (:owner IS NULL OR l.owner_id = :owner)
                  AND l.lead_status NOT IN ('WON', 'LOST')
                  AND l.next_followup_at IS NOT NULL
                  AND l.next_followup_at < CURRENT_DATE + INTERVAL 7 DAY
                ORDER BY l.next_followup_at, l.id
                LIMIT 20
                """, params("owner", owner)).stream().map(BusinessData::view).toList();
        return new Dashboard(enterpriseCount, projects, number(counts, "leads"), number(counts, "overdue"),
                number(counts, "today"), number(counts, "upcoming"), reminders);
    }

    private static long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    record Dashboard(long enterpriseCount, Long projectCount, Long leadCount, Long overdueCount,
                     Long todayCount, Long upcomingCount, List<Map<String, Object>> reminders) {}
}
