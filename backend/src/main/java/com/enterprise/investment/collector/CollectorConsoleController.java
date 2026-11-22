package com.enterprise.investment.collector;

import com.enterprise.investment.business.Rows;
import com.enterprise.investment.mapper.DataMapper;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/governance/collector")
@PreAuthorize("@ss.hasPermission('governance:view')")
public class CollectorConsoleController {
    private final DataMapper data;

    CollectorConsoleController(DataMapper data) {
        this.data = data;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return Map.of("summary", Rows.view(data.collectorSummary()),
                "sources", data.sourceStatus().stream().map(Rows::view).toList());
    }

    @GetMapping("/records")
    public Map<String, Object> records(@RequestParam(required = false) String source,
                                       @RequestParam(required = false) String recordType,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        long offset = offset(page, size);
        String filter = keyword(keyword);
        String normalizedSource = code(source);
        String normalizedType = code(recordType);
        long total = data.countRecords(normalizedSource, normalizedType, filter);
        var items = data.listRecords(normalizedSource, normalizedType, filter, size, offset)
                .stream().map(Rows::view).toList();
        return Map.of("items", items, "total", total);
    }

    @GetMapping("/signals")
    public Map<String, Object> signals(@RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        long offset = offset(page, size);
        String filter = keyword(keyword);
        long total = data.countSignals(filter);
        var items = data.listSignals(filter, size, offset).stream().map(Rows::view).toList();
        return Map.of("items", items, "total", total);
    }

    private static long offset(int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new ResponseStatusException(BAD_REQUEST, "页码从 1 开始，每页可查询 1 到 100 条");
        }
        return (page - 1L) * size;
    }

    private static String keyword(String value) {
        String text = Rows.trimmed(value);
        return text == null ? null : Rows.contains(text);
    }

    private static String code(String value) {
        String text = Rows.trimmed(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }
}
