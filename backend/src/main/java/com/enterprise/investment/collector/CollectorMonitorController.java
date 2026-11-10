package com.enterprise.investment.collector;

import com.enterprise.investment.business.Rows;
import com.enterprise.investment.mapper.MonitorMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Worker endpoints are protected by the collector service token filter. */
@RestController
@RequestMapping("/api/collector/monitor")
public class CollectorMonitorController {
    private final MonitorMapper monitors;

    CollectorMonitorController(MonitorMapper monitors) { this.monitors = monitors; }

    record ClaimInput(@Min(1) @Max(10) int limit) {}
    record FinishInput(@NotBlank @Size(max = 36) String leaseToken, @NotBlank String status,
                       @Size(max = 32) String errorCode, @Min(0) @Max(10000) int createdRecords,
                       @Min(0) @Max(10000) int updatedRecords, @Min(0) @Max(10) int pagesFetched,
                       boolean possiblyTruncated) {}

    @PostMapping("/claim")
    @Transactional
    public List<Map<String, Object>> claim(@Valid @RequestBody ClaimInput input) {
        var result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : monitors.lockDue(input.limit())) {
            long id = ((Number) row.get("id")).longValue();
            if (row.get("lease_token") != null) monitors.markAbandoned((String) row.get("lease_token"));
            String token = UUID.randomUUID().toString();
            monitors.lease(id, token);
            monitors.insertRun(id, token);
            Map<String, Object> item = Rows.view(row);
            item.put("leaseToken", token);
            result.add(item);
        }
        return result;
    }

    @PostMapping("/{id}/finish")
    @Transactional
    public Map<String, Object> finish(@PathVariable long id, @Valid @RequestBody FinishInput input) {
        String status = input.status();
        if (!List.of("SUCCESS", "EMPTY", "FAILED").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "监测结果状态无效");
        }
        String code = input.errorCode();
        if ("FAILED".equals(status) && (code == null || !List.of("QCC_CONFIG", "QCC_FAILURE", "UPLOAD_FAILURE", "UNKNOWN").contains(code))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "监测错误代码无效");
        }
        if (!"FAILED".equals(status)) code = null;
        if ("EMPTY".equals(status) && (input.createdRecords() != 0 || input.updatedRecords() != 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无数据结果不能包含新增或更新记录");
        }
        if (monitors.completeTask(id, input.leaseToken(), status) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "监测任务租约无效或已过期");
        }
        if (monitors.completeRun(input.leaseToken(), status, code, input.createdRecords(), input.updatedRecords(),
                input.pagesFetched(), input.possiblyTruncated()) != 1) {
            throw new IllegalStateException("监测运行记录不存在");
        }
        if (!"FAILED".equals(status)) monitors.updateCursor(id, input.pagesFetched(), input.possiblyTruncated());
        return Map.of("recorded", true, "status", status);
    }
}
