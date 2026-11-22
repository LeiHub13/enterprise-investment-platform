package com.enterprise.investment.business;

import com.enterprise.investment.mapper.CompanySignalMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/enterprises/{id}/signals")
@PreAuthorize("@ss.hasPermission('enterprise:view')")
public class CompanySignalController {
    private static final List<String> TYPES = List.of(
            "RECRUITMENT", "FUNDING", "BRANCH", "SCOPE_CHANGE", "RELOCATION", "DEPARTMENT");
    private final CompanySignalMapper signals;
    private final EnterpriseService enterprises;
    private final ObjectMapper json;

    CompanySignalController(CompanySignalMapper signals, EnterpriseService enterprises, ObjectMapper json) {
        this.signals = signals;
        this.enterprises = enterprises;
        this.json = json;
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable long id) {
        enterprises.get(id);
        return signals.listByEnterprise(id).stream().map(this::safeSignal).toList();
    }

    private Map<String, Object> safeSignal(Map<String, Object> row) {
        Map<String, Object> result = Rows.view(row);
        Object raw = result.remove("rawPayload");
        if (raw == null || !"RECRUITMENT".equals(result.get("signalType"))) return result;
        try {
            JsonNode posts = json.readTree(String.valueOf(raw)).path("posts");
            if (!posts.isArray()) return result;
            List<Map<String, String>> safePosts = new ArrayList<>();
            for (JsonNode post : posts) {
                if (safePosts.size() >= 5000) break;
                safePosts.add(Map.of("externalId", field(post, "externalId", 100), "title", field(post, "title", 200),
                        "publishedAt", field(post, "publishedAt", 30), "location", field(post, "location", 120)));
            }
            result.put("recruitmentPosts", safePosts);
        } catch (java.io.IOException ignored) {
            // 无法解析的来源快照不会被当作零职位。
        }
        return result;
    }

    private static String field(JsonNode node, String name, int limit) {
        String value = node.path(name).asText("");
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermission('enterprise:write')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@PathVariable long id, @Valid @RequestBody SignalInput input) {
        enterprises.get(id);
        String type = input.signalType() == null ? "" : input.signalType().trim();
        if (!TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "信号类型无效");
        }
        signals.insert(id, type, input.title().trim(), input.detail(), input.sourceName(),
                input.sourceUrl(), input.observedAt(), input.strength());
        return Map.of("created", true);
    }

    record SignalInput(
            @NotBlank @Size(max = 24) String signalType,
            @NotBlank @Size(max = 255) String title,
            @Size(max = 15000) String detail,
            @Size(max = 128) String sourceName,
            @Size(max = 512) String sourceUrl,
            LocalDate observedAt,
            @Min(1) @Max(3) int strength) {}
}
