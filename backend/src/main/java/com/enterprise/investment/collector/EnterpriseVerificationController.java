package com.enterprise.investment.collector;

import com.enterprise.investment.business.Rows;
import com.enterprise.investment.mapper.EnterpriseVerificationMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Ingests the allow-listed fields from the authorized QCC verification connector. */
@RestController
@RequestMapping("/api/collector/verifications")
public class EnterpriseVerificationController {
    private final EnterpriseVerificationMapper verifications;

    EnterpriseVerificationController(EnterpriseVerificationMapper verifications) { this.verifications = verifications; }

    record VerificationInput(@NotNull @Positive Long enterpriseId, @NotBlank @Size(max = 128) String orderRef,
                             @NotNull Boolean demo, @NotNull Boolean found, @Size(max = 1000) String verifiedName,
                             @Size(max = 50) String verifiedUscc, @Size(max = 100) String businessStatus,
                             @Size(max = 1000) String registeredAddress) {}

    @GetMapping("/companies/{id}/latest")
    public Map<String, Object> latest(@PathVariable long id) {
        Rows.required(verifications.company(id), "企业不存在");
        Map<String, Object> latest = verifications.latestReal(id);
        return latest == null ? Map.of("verificationStatus", "PENDING", "verificationFresh", false) : Rows.view(latest);
    }

    @PostMapping
    @Transactional
    public Map<String, Object> ingest(@Valid @RequestBody VerificationInput input) {
        Map<String, Object> company = Rows.required(verifications.company(input.enterpriseId()), "企业不存在");
        String expectedName = normalized(String.valueOf(company.get("name")));
        String actualName = normalized(input.verifiedName());
        String expectedCode = code((String) company.get("uscc"));
        String actualCode = code(input.verifiedUscc());
        String state = input.businessStatus() == null ? "" : input.businessStatus().trim();
        String result;
        String reason;
        if (input.demo()) {
            result = "PENDING"; reason = "演示核验不代表真实主体状态";
        } else if (!input.found()) {
            result = "NOT_FOUND"; reason = "企查查未返回匹配主体，需人工核实";
        } else if (actualName.isEmpty() || !actualCode.matches("[0-9A-Z]{18}")) {
            result = "PENDING"; reason = "核验结果缺少有效企业名称或统一社会信用代码";
        } else if ((!expectedCode.isEmpty() && !expectedCode.equals(actualCode))
                || (expectedCode.isEmpty() && !expectedName.equals(actualName))) {
            result = "MISMATCH"; reason = "核验结果与企业档案主体不一致";
        } else if (expectedCode.isEmpty() && verifications.countByNormalizedName((String) company.get("name_normalized")) != 1) {
            result = "PENDING"; reason = "存在同名档案，需用统一社会信用代码人工区分";
        } else if (List.of("注销", "吊销", "停业", "清算", "迁出").stream().anyMatch(state::contains)) {
            result = "INACTIVE"; reason = "企查查返回的登记状态需停止自动转线索并人工核实";
        } else if (state.contains("存续") || state.contains("在业") || state.contains("开业") || state.equals("正常")) {
            result = "VERIFIED"; reason = expectedName.equals(actualName) ? "企查查授权接口返回匹配主体和在营状态" : "信用代码一致，企业名称有变化，请人工核实";
        } else {
            result = "PENDING"; reason = "企查查返回的登记状态不足以确认在营";
        }
        String source = input.demo() ? "QCC_2001_DEMO" : "QCC_2001";
        Map<String, Object> values = Rows.params("enterpriseId", input.enterpriseId(), "source", source,
                "orderRef", input.orderRef().trim(), "demo", input.demo(), "status", result,
                "name", input.verifiedName(), "uscc", input.verifiedUscc(), "businessStatus", input.businessStatus(),
                "address", input.registeredAddress(), "reason", reason);
        verifications.insert(values);
        Map<String, Object> stored = Rows.required(verifications.lockById(((Number) values.get("id")).longValue()), "核验记录不存在");
        if (((Number) stored.get("enterprise_id")).longValue() != input.enterpriseId()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "核验来源流水号已属于其他企业");
        }
        return Rows.view(stored);
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).replaceAll("(?U)\\s+", " ").strip();
    }
    private static String code(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
}
