package com.enterprise.investment.business;

import com.enterprise.investment.mapper.ObservationMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/observations")
@PreAuthorize("@ss.hasPermission('lead:view')")
public class ObservationController {
    private static final List<String> STATUSES = List.of("WATCHING", "SUB_THRESHOLD", "REJECTED", "VERIFY_FAILED");
    private static final List<String> GRADES = List.of("PRIORITY", "VERIFY", "HOLD");
    private final ObservationMapper observations;

    ObservationController(ObservationMapper observations) {
        this.observations = observations;
    }

    @GetMapping
    public PageResult list(@RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) Long enterpriseId,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size) {
        long offset = Paging.offset(page, size);
        String filterKeyword = Rows.trimmed(keyword) == null ? null : Rows.contains(keyword);
        String filterStatus = Rows.trimmed(status);
        if (filterStatus != null && !STATUSES.contains(filterStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "观察记录状态无效");
        }
        long total = observations.count(filterKeyword, filterStatus, enterpriseId);
        var items = observations.list(filterKeyword, filterStatus, enterpriseId, size, offset)
                .stream().map(Rows::view).toList();
        return new PageResult(items, total);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return Rows.view(Rows.required(observations.findById(id), "观察记录不存在"));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermission('lead:create')")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody ObservationInput input) {
        String status = input.status().trim();
        if (!STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "观察记录状态无效");
        }
        String grade = Rows.trimmed(input.grade());
        if (grade != null && !GRADES.contains(grade)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评分等级无效");
        }
        var values = Rows.params("runId", Rows.trimmed(input.runId()), "enterpriseId", input.enterpriseId(),
                "projectId", input.projectId(), "signalId", input.signalId(),
                "sourceRecordId", input.sourceRecordId(), "totalScore", input.totalScore(),
                "grade", grade, "reason", Rows.trimmed(input.reason()), "status", status);
        observations.insert(values);
        long id = ((Number) values.get("id")).longValue();
        return get(id);
    }

    record ObservationInput(
            @Size(max = 36) String runId,
            @NotNull @Positive Long enterpriseId,
            @Positive Long projectId,
            @Positive Long signalId,
            @Positive Long sourceRecordId,
            @Min(0) @Max(100) Integer totalScore,
            @Size(max = 16) String grade,
            @Size(max = 2000) String reason,
            @NotBlank @Size(max = 20) String status) {}
}