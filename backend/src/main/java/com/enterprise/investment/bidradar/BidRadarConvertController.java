package com.enterprise.investment.bidradar;

import com.enterprise.investment.business.CrmNumbers;
import com.enterprise.investment.mapper.AccountMapper;
import com.enterprise.investment.mapper.AuditMapper;
import com.enterprise.investment.mapper.BusinessMapper;
import com.enterprise.investment.mapper.EnterpriseMapper;
import com.enterprise.investment.mapper.LeadMapper;
import com.enterprise.investment.mapper.ProjectMapper;
import com.enterprise.investment.security.AccountPrincipal;
import com.enterprise.investment.security.DataScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static com.enterprise.investment.business.Rows.params;
import static com.enterprise.investment.business.Rows.trimmed;

@RestController
@RequestMapping("/api/bid-radar/convert")
public class BidRadarConvertController {
    private static final String SOURCE_TYPE = "BID_RADAR";
    private static final String SOURCE_CHANNEL = "标讯雷达";

    private final LeadMapper leads;
    private final BusinessMapper businesses;
    private final EnterpriseMapper enterprises;
    private final ProjectMapper projects;
    private final AccountMapper accounts;
    private final AuditMapper audits;
    private final DataScopeResolver scopes;

    BidRadarConvertController(LeadMapper leads, BusinessMapper businesses, EnterpriseMapper enterprises,
                              ProjectMapper projects, AccountMapper accounts, AuditMapper audits,
                              DataScopeResolver scopes) {
        this.leads = leads;
        this.businesses = businesses;
        this.enterprises = enterprises;
        this.projects = projects;
        this.accounts = accounts;
        this.audits = audits;
        this.scopes = scopes;
    }

    @PostMapping("/lead")
    @PreAuthorize("@ss.hasPermission('bidradar:view') and @ss.hasPermission('lead:create')")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> convertLead(@Valid @RequestBody LeadConvertInput input,
                                           @AuthenticationPrincipal AccountPrincipal actor) {
        String url = sourceUrl(input.sourceUrl());
        String title = sourceTitle(input.sourceTitle());
        validateRelations(input.customerId(), input.projectId());
        String hash = SourceHash.sha256(url);
        guardConverted(hash);
        Long owner = normalizeOwner(input.ownerId(), scopes.leadOwner(actor), "线索");
        if (owner == null) {
            owner = actor.id();
        }
        var values = params("enterprise", input.customerId(), "project", input.projectId(),
                "source", SOURCE_CHANNEL, "owner", owner, "nextFollowup", input.nextFollowupAt(),
                "actor", actor.id(), "sourceType", SOURCE_TYPE, "sourceUrl", url,
                "sourceHash", hash, "sourceTitle", title);
        try {
            leads.insertFromBid(values);
        } catch (DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该标讯已转为线索或商机");
        }
        long id = ((Number) values.get("id")).longValue();
        audits.record(actor.id(), "CONVERT_BID", "LEAD", id);
        return Map.of("id", id, "type", "LEAD");
    }

    @PostMapping("/business")
    @PreAuthorize("@ss.hasPermission('bidradar:view') and @ss.hasPermission('business:create')")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> convertBusiness(@Valid @RequestBody BusinessConvertInput input,
                                               @AuthenticationPrincipal AccountPrincipal actor) {
        String url = sourceUrl(input.sourceUrl());
        String title = sourceTitle(input.sourceTitle());
        validateRelations(input.customerId(), input.projectId());
        String hash = SourceHash.sha256(url);
        guardConverted(hash);
        Long owner = normalizeOwner(input.ownerId(), scopes.ownerFilter("business", actor), "商机");
        if (owner == null) {
            owner = actor.id();
        }
        var values = params("businessNo", CrmNumbers.next("BUS"),
                "name", title,
                "customer", input.customerId(), "project", input.projectId(), "owner", owner,
                "amount", input.amount(), "probability", null, "source", SOURCE_CHANNEL,
                "remark", trimmed(input.note()), "actor", actor.id(), "sourceType", SOURCE_TYPE,
                "sourceUrl", url, "sourceHash", hash, "sourceTitle", title);
        try {
            businesses.insertFromBid(values);
        } catch (DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该标讯已转为线索或商机");
        }
        long id = ((Number) values.get("id")).longValue();
        audits.record(actor.id(), "CONVERT_BID", "BUSINESS", id);
        return Map.of("id", id, "type", "BUSINESS");
    }

    private void guardConverted(String hash) {
        Map<String, Object> lead = leads.findBySourceHash(hash);
        if (lead != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该标讯已转为线索");
        }
        Map<String, Object> business = businesses.findBySourceHash(hash);
        if (business != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该标讯已转为商机");
        }
    }

    private void validateRelations(Long customerId, Long projectId) {
        if (enterprises.exists(customerId) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户不存在");
        }
        if (projectId != null && projects.findById(projectId) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "招商项目不存在");
        }
    }

    private Long normalizeOwner(Long requested, Long scopeOwner, String target) {
        if (scopeOwner != null) {
            if (requested != null && !requested.equals(scopeOwner)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "招商人员仅可分配给自己");
            }
            return scopeOwner;
        }
        if (requested != null && accounts.findAssignable(requested) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "负责人必须是已启用的招商人员");
        }
        return requested;
    }

    private static String sourceTitle(String raw) {
        String title = trimmed(raw);
        return title.length() > 255 ? title.substring(0, 255) : title;
    }

    private static String sourceUrl(String raw) {
        String url = trimmed(raw);
        if (url == null || url.length() > 1000
                || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标讯来源网址无效");
        }
        return url;
    }

    record LeadConvertInput(
            @NotNull @Positive Long customerId,
            @Positive Long projectId,
            @Positive Long ownerId,
            @NotBlank @Size(max = 1000) String sourceUrl,
            @NotBlank String sourceTitle,
            LocalDateTime nextFollowupAt) {
    }

    record BusinessConvertInput(
            @NotNull @Positive Long customerId,
            @Positive Long projectId,
            @Positive Long ownerId,
            @NotBlank @Size(max = 1000) String sourceUrl,
            @NotBlank String sourceTitle,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal amount,
            @Size(max = 5000) String note) {
    }
}
