package com.enterprise.investment.business;

import com.enterprise.investment.mapper.EnterpriseMapper;
import com.enterprise.investment.mapper.GovernanceMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api")
public class GovernanceController {
    private final GovernanceMapper governance;
    private final EnterpriseMapper enterprises;

    GovernanceController(GovernanceMapper governance, EnterpriseMapper enterprises) {
        this.governance = governance;
        this.enterprises = enterprises;
    }

    @GetMapping("/enterprises/{id}/history")
    @PreAuthorize("@ss.hasPermission('enterprise:view')")
    public PageResult history(@PathVariable long id,
                              @RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int size) {
        if (enterprises.exists(id) == null) {
            throw new ResponseStatusException(NOT_FOUND, "企业不存在");
        }
        long offset = Paging.offset(page, size);
        long total = governance.countHistory(id);
        var items = governance.history(id, size, offset).stream().map(Rows::view).toList();
        return new PageResult(items, total);
    }

    @GetMapping("/import-batches")
    @PreAuthorize("@ss.hasPermission('governance:view')")
    public PageResult imports(@RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int size) {
        long offset = Paging.offset(page, size);
        long total = governance.countImports();
        var items = governance.imports(size, offset).stream().map(Rows::view).toList();
        return new PageResult(items, total);
    }

    @GetMapping("/import-batches/{id}/rows")
    @PreAuthorize("@ss.hasPermission('governance:view')")
    public PageResult importRows(@PathVariable long id,
                                 @RequestParam(defaultValue = "false") boolean failuresOnly,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        if (governance.existsImportBatch(id) == null) {
            throw new ResponseStatusException(NOT_FOUND, "导入批次不存在");
        }
        long offset = Paging.offset(page, size);
        long total = governance.countImportRows(id, failuresOnly);
        var items = governance.importRows(id, failuresOnly, size, offset).stream().map(Rows::view).toList();
        return new PageResult(items, total);
    }
}
