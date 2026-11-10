package com.enterprise.investment.business;

import com.enterprise.investment.security.AccountPrincipal;
import com.enterprise.investment.mapper.EnterpriseVerificationMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping({"/api/enterprises", "/api/companies"})
@PreAuthorize("@ss.hasPermission('enterprise:view')")
public class EnterpriseController {
    private static final String WRITE = "@ss.hasPermission('enterprise:write')";
    private static final String IMPORT = "@ss.hasPermission('enterprise:import')";
    private final EnterpriseService enterprises;
    private final EnterpriseImport importer;
    private final EnterpriseVerificationMapper verifications;

    EnterpriseController(EnterpriseService enterprises, EnterpriseImport importer, EnterpriseVerificationMapper verifications) {
        this.enterprises = enterprises;
        this.importer = importer;
        this.verifications = verifications;
    }

    @GetMapping
    public PageResult list(@RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String provinceCode,
                                  @RequestParam(required = false) String cityCode,
                                  @RequestParam(required = false) String industryCode,
                                  @RequestParam(required = false) String businessStatus,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return enterprises.list(keyword, provinceCode, cityCode, industryCode, businessStatus, page, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return enterprises.get(id);
    }

    @GetMapping("/{id}/verifications")
    public List<Map<String, Object>> verifications(@PathVariable long id) {
        enterprises.get(id);
        return verifications.history(id, 30).stream().map(Rows::view).toList();
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody EnterpriseInput input,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        return enterprises.create(input, actor.id());
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    public Map<String, Object> replace(@PathVariable long id, @Valid @RequestBody EnterpriseInput input,
                                       @AuthenticationPrincipal AccountPrincipal actor) {
        return enterprises.replace(id, input, actor.id());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        enterprises.delete(id, actor.id());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(IMPORT)
    public EnterpriseImport.Result importFile(@RequestParam("file") MultipartFile file,
                                              @AuthenticationPrincipal AccountPrincipal actor) {
        return importer.run(file, actor.id());
    }
}
