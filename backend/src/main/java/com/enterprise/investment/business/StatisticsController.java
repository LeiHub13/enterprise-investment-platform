package com.enterprise.investment.business;

import com.enterprise.investment.mapper.StatisticsMapper;
import com.enterprise.investment.security.AccountPrincipal;
import com.enterprise.investment.security.DataScopeResolver;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/statistics")
@PreAuthorize("@ss.hasPermission('statistics:view')")
public class StatisticsController {
    private final StatisticsMapper statistics;
    private final DataScopeResolver scopes;

    StatisticsController(StatisticsMapper statistics, DataScopeResolver scopes) {
        this.statistics = statistics;
        this.scopes = scopes;
    }

    @GetMapping
    public Statistics statistics(@AuthenticationPrincipal AccountPrincipal actor) {
        Long leadOwner = scopes.ownerFilter("lead", actor);
        Long businessOwner = scopes.ownerFilter("business", actor);
        Long contractOwner = scopes.ownerFilter("contract", actor);
        Long receivableOwner = scopes.ownerFilter("receivable", actor);

        List<Map<String, Object>> stages = statistics.stageCounts(businessOwner)
                .stream().map(Rows::view).toList();
        List<Map<String, Object>> owners = statistics.ownerPerformance(businessOwner)
                .stream().map(Rows::view).toList();

        return new Statistics(
                actor.hasPermission("enterprise:view") ? statistics.countEnterprises() : 0,
                statistics.countLeads(leadOwner),
                statistics.countBusinesses(businessOwner),
                statistics.countWonBusinesses(businessOwner),
                statistics.countContracts(contractOwner),
                money(statistics.sumContractAmount(contractOwner)),
                money(statistics.sumReceivablePlan(receivableOwner)),
                money(statistics.sumReceivableReceived(receivableOwner)),
                stages,
                owners);
    }

    private static String money(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    record Statistics(long enterpriseCount, long leadCount, long businessCount, long wonBusinessCount,
                      long contractCount, String contractAmount, String receivablePlanAmount,
                      String receivableReceivedAmount, List<Map<String, Object>> stages,
                      List<Map<String, Object>> owners) {
    }
}
