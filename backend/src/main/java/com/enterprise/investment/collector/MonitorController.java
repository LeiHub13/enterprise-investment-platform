package com.enterprise.investment.collector;

import com.enterprise.investment.business.Rows;
import com.enterprise.investment.mapper.AuditMapper;
import com.enterprise.investment.mapper.DataMapper;
import com.enterprise.investment.mapper.MonitorMapper;
import com.enterprise.investment.security.AccountPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/monitor")
@PreAuthorize("@ss.hasPermission('governance:view')")
public class MonitorController {
    private final MonitorMapper monitors;
    private final DataMapper data;
    private final AuditMapper audits;

    MonitorController(MonitorMapper monitors, DataMapper data, AuditMapper audits) {
        this.monitors = monitors;
        this.data = data;
        this.audits = audits;
    }

    record CreateInput(@NotNull @Positive Long enterpriseId, @NotBlank String mode,
                       @Min(60) @Max(43200) int intervalMinutes, @Min(1) @Max(10) int maxPages) {}
    record UpdateInput(@Min(60) @Max(43200) int intervalMinutes, @Min(1) @Max(10) int maxPages,
                       @NotNull Boolean enabled) {}

    @GetMapping("/status")
    public Map<String, Object> status() { return Rows.view(monitors.summary()); }

    @GetMapping("/tasks")
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        if (page < 1 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码或每页数量无效");
        return Map.of("items", monitors.list(size, (page - 1L) * size).stream().map(Rows::view).toList(), "total", monitors.count());
    }

    @GetMapping("/tasks/{id}/runs")
    public List<Map<String, Object>> runs(@PathVariable long id) {
        Rows.required(monitors.find(id), "监测任务不存在");
        return monitors.runs(id, 30).stream().map(Rows::view).toList();
    }

    @PostMapping("/tasks")
    @PreAuthorize("@ss.hasPermission('governance:view') and @ss.hasPermission('monitor:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody CreateInput input, @AuthenticationPrincipal AccountPrincipal actor) {
        String mode = input.mode().trim().toUpperCase(Locale.ROOT);
        if (!List.of("DEMO", "LIVE").contains(mode)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "监测模式无效");
        if (data.findCompany(input.enterpriseId()) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "企业不存在");
        Map<String, Object> values = Rows.params("enterprise", input.enterpriseId(), "mode", mode,
                "intervalMinutes", input.intervalMinutes(), "maxPages", input.maxPages(), "actor", actor.id());
        try {
            monitors.insert(values);
        } catch (DuplicateKeyException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该企业已有相同模式的监测任务");
        }
        long id = ((Number) values.get("id")).longValue();
        audits.record(actor.id(), "CREATE", "MONITOR_TASK", id);
        return Rows.view(Rows.required(monitors.find(id), "监测任务不存在"));
    }

    @PutMapping("/tasks/{id}")
    @PreAuthorize("@ss.hasPermission('governance:view') and @ss.hasPermission('monitor:manage')")
    @Transactional
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody UpdateInput input,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        Rows.required(monitors.lock(id), "监测任务不存在");
        monitors.updateSettings(id, input.intervalMinutes(), input.maxPages(), input.enabled(), actor.id());
        audits.record(actor.id(), input.enabled() ? "ENABLE_OR_UPDATE" : "PAUSE_OR_UPDATE", "MONITOR_TASK", id);
        return Rows.view(Rows.required(monitors.find(id), "监测任务不存在"));
    }
}
