package com.enterprise.investment.collector;

import com.enterprise.investment.business.Rows;
import com.enterprise.investment.mapper.DataMapper;
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

@RestController
@RequestMapping("/api/collector/outbox")
public class CollectorOutboxController {
    private final DataMapper data;

    CollectorOutboxController(DataMapper data) { this.data = data; }

    record ClaimInput(@Min(1) @Max(100) int limit) {}
    record LeaseInput(@NotBlank @Size(max = 36) String leaseToken) {}

    @PostMapping("/claim")
    @Transactional
    public List<Map<String, Object>> claim(@Valid @RequestBody ClaimInput input) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : data.lockOutboxBatch(input.limit())) {
            long id = ((Number) row.get("id")).longValue();
            String lease = UUID.randomUUID().toString();
            data.leaseOutbox(id, lease);
            Map<String, Object> event = Rows.view(row);
            event.put("leaseToken", lease);
            result.add(event);
        }
        return result;
    }

    @PostMapping("/{id}/ack")
    public Map<String, Object> ack(@PathVariable long id, @Valid @RequestBody LeaseInput input) {
        if (data.ackOutbox(id, input.leaseToken()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "事件租约无效或已过期");
        }
        return Map.of("published", true);
    }

    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable long id, @Valid @RequestBody LeaseInput input) {
        if (data.retryOutbox(id, input.leaseToken()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "事件租约无效");
        }
        return Map.of("retryScheduled", true);
    }
}
