package com.enterprise.investment.business;

import com.enterprise.investment.mapper.BusinessMapper;
import com.enterprise.investment.mapper.ContactMapper;
import com.enterprise.investment.mapper.ContractMapper;
import com.enterprise.investment.mapper.LeadMapper;
import com.enterprise.investment.mapper.ReceivableMapper;
import com.enterprise.investment.security.AccountPrincipal;
import com.enterprise.investment.security.DataScopeResolver;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customers")
@PreAuthorize("@ss.hasPermission('enterprise:view')")
public class Customer360Controller {
    private static final int RELATED_LIMIT = 20;

    private final EnterpriseService enterprises;
    private final ContactMapper contacts;
    private final LeadMapper leads;
    private final BusinessMapper businesses;
    private final ContractMapper contracts;
    private final ReceivableMapper receivables;
    private final DataScopeResolver scopes;

    Customer360Controller(EnterpriseService enterprises, ContactMapper contacts, LeadMapper leads,
                          BusinessMapper businesses, ContractMapper contracts,
                          ReceivableMapper receivables, DataScopeResolver scopes) {
        this.enterprises = enterprises;
        this.contacts = contacts;
        this.leads = leads;
        this.businesses = businesses;
        this.contracts = contracts;
        this.receivables = receivables;
        this.scopes = scopes;
    }

    @GetMapping("/{id}/360")
    @Transactional(readOnly = true)
    public Customer360 customer360(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        Map<String, Object> customer = enterprises.get(id);

        long contactCount = 0;
        List<Map<String, Object>> contactItems = List.of();
        if (actor.hasPermission("contact:view")) {
            Long contactOwner = scopes.contactOwner(actor);
            if (contactOwner == null || contacts.findAccessibleEnterprise(id, contactOwner) != null) {
                contactCount = contacts.count(id, null);
                contactItems = contacts.list(id, null, RELATED_LIMIT, 0).stream()
                        .map(row -> Rows.params("id", row.get("id"), "name", row.get("name"),
                                "position", row.get("position")))
                        .toList();
            }
        }

        long leadCount = 0;
        List<Map<String, Object>> leadItems = List.of();
        if (actor.hasPermission("lead:view")) {
            Long leadOwner = scopes.leadOwner(actor);
            leadCount = leads.count(leadOwner, null, null, id, null, null, null);
            leadItems = leads.list(leadOwner, null, null, id, null, null, null, RELATED_LIMIT, 0)
                    .stream().map(Rows::view).toList();
        }

        long businessCount = 0;
        List<Map<String, Object>> businessItems = List.of();
        String businessAmount = "0";
        if (actor.hasPermission("business:view")) {
            Long owner = scopes.ownerFilter("business", actor);
            businessCount = businesses.countByCustomer(id, owner);
            businessItems = businesses.listByCustomer(id, owner, RELATED_LIMIT).stream()
                    .map(Rows::view).toList();
            businessAmount = money(businesses.sumAmountByCustomer(id, owner));
        }

        long contractCount = 0;
        List<Map<String, Object>> contractItems = List.of();
        String contractAmount = "0";
        if (actor.hasPermission("contract:view")) {
            Long owner = scopes.ownerFilter("contract", actor);
            contractCount = contracts.countByCustomer(id, owner);
            contractItems = contracts.listByCustomer(id, owner, RELATED_LIMIT).stream()
                    .map(Rows::view).toList();
            contractAmount = money(contracts.sumAmountByCustomer(id, owner));
        }

        long receivableCount = 0;
        List<Map<String, Object>> receivableItems = List.of();
        String receivablePlan = "0";
        String receivableReceived = "0";
        if (actor.hasPermission("receivable:view")) {
            Long owner = scopes.ownerFilter("receivable", actor);
            receivableCount = receivables.countByCustomer(id, owner);
            receivableItems = receivables.listByCustomer(id, owner, RELATED_LIMIT).stream()
                    .map(Rows::view).toList();
            receivablePlan = money(receivables.sumPlanByCustomer(id, owner));
            receivableReceived = money(receivables.sumReceivedByCustomer(id, owner));
        }

        return new Customer360(customer, contactItems, leadItems, businessItems, contractItems,
                receivableItems, new Summary(contactCount, leadCount, businessCount, businessAmount,
                contractCount, contractAmount, receivableCount, receivablePlan, receivableReceived));
    }

    private static String money(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    record Customer360(Map<String, Object> customer, List<Map<String, Object>> contacts,
                       List<Map<String, Object>> leads, List<Map<String, Object>> businesses,
                       List<Map<String, Object>> contracts, List<Map<String, Object>> receivables,
                       Summary summary) {
    }

    record Summary(long contactCount, long leadCount, long businessCount, String businessAmount,
                   long contractCount, String contractAmount, long receivableCount,
                   String receivablePlanAmount, String receivableReceivedAmount) {
    }
}
