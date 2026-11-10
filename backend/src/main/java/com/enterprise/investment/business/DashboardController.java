package com.enterprise.investment.business;

import com.enterprise.investment.mapper.DashboardMapper;
import com.enterprise.investment.security.AccountPrincipal;
import com.enterprise.investment.security.DataScopeResolver;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@PreAuthorize("@ss.hasPermission('dashboard:view')")
public class DashboardController {
    private final DashboardMapper dashboard;
    private final DataScopeResolver scopes;

    DashboardController(DashboardMapper dashboard, DataScopeResolver scopes) {
        this.dashboard = dashboard;
        this.scopes = scopes;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Dashboard dashboard(@AuthenticationPrincipal AccountPrincipal actor) {
        long enterpriseCount = actor.hasPermission("enterprise:view") ? dashboard.countEnterprises() : 0;
        Long projects = actor.hasPermission("project:view") ? dashboard.countProjects() : null;
        if (!actor.hasPermission("lead:view")) {
            return new Dashboard(enterpriseCount, projects, null, null, null, null, List.of());
        }

        Long owner = scopes.leadOwner(actor);
        var counts = dashboard.leadCounts(owner);
        var reminders = dashboard.reminders(owner).stream().map(Rows::view).toList();
        return new Dashboard(enterpriseCount, projects, number(counts, "leads"), number(counts, "overdue"),
                number(counts, "today"), number(counts, "upcoming"), reminders);
    }

    private static long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    record Dashboard(long enterpriseCount, Long projectCount, Long leadCount, Long overdueCount,
                     Long todayCount, Long upcomingCount, List<Map<String, Object>> reminders) {}
}
