package com.enterprise.investment.business;

import com.enterprise.investment.mapper.AuditMapper;
import com.enterprise.investment.mapper.ContactMapper;
import com.enterprise.investment.security.AccountPrincipal;
import com.enterprise.investment.security.DataScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static com.enterprise.investment.business.Rows.contains;
import static com.enterprise.investment.business.Rows.params;
import static com.enterprise.investment.business.Rows.trimmed;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/enterprises/{enterpriseId}/contacts")
@PreAuthorize("@ss.hasPermission('contact:view')")
public class ContactController {
    private final ContactMapper contacts;
    private final ContactEncryption encryption;
    private final AuditMapper audits;
    private final DataScopeResolver scopes;

    ContactController(ContactMapper contacts, ContactEncryption encryption, AuditMapper audits,
                      DataScopeResolver scopes) {
        this.contacts = contacts;
        this.encryption = encryption;
        this.audits = audits;
        this.scopes = scopes;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ContactPage list(@PathVariable long enterpriseId,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size,
                            @AuthenticationPrincipal AccountPrincipal actor) {
        checkEnterpriseAccess(enterpriseId, actor);
        long offset = Paging.offset(page, size);
        String filterKeyword = trimmed(keyword) == null ? null : contains(keyword);
        long total = contacts.count(enterpriseId, filterKeyword);
        var items = contacts.list(enterpriseId, filterKeyword, size, offset).stream().map(this::view).toList();
        return new ContactPage(items, total);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ContactView get(@PathVariable long enterpriseId, @PathVariable long id,
                           @AuthenticationPrincipal AccountPrincipal actor) {
        checkEnterpriseAccess(enterpriseId, actor);
        return view(find(enterpriseId, id, false));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermission('contact:write')")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public ContactView create(@PathVariable long enterpriseId, @Valid @RequestBody ContactInput input,
                              @AuthenticationPrincipal AccountPrincipal actor) {
        checkEnterpriseAccess(enterpriseId, actor);
        validateInput(input);
        var values = params("enterprise", enterpriseId, "name", trimmed(input.name()),
                "position", trimmed(input.position()));
        contacts.insert(values);
        long id = ((Number) values.get("id")).longValue();
        saveSensitiveFields(enterpriseId, id, input);
        audits.record(actor.id(), "CREATE", "CONTACT", id);
        return view(find(enterpriseId, id, false));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('contact:write')")
    @Transactional
    public ContactView replace(@PathVariable long enterpriseId, @PathVariable long id,
                               @Valid @RequestBody ContactInput input,
                               @AuthenticationPrincipal AccountPrincipal actor) {
        checkEnterpriseAccess(enterpriseId, actor);
        find(enterpriseId, id, true);
        validateInput(input);
        contacts.updateBasic(id, trimmed(input.name()), trimmed(input.position()));
        saveSensitiveFields(enterpriseId, id, input);
        audits.record(actor.id(), "UPDATE", "CONTACT", id);
        return view(find(enterpriseId, id, false));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('contact:delete')")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long enterpriseId, @PathVariable long id,
                       @AuthenticationPrincipal AccountPrincipal actor) {
        checkEnterpriseAccess(enterpriseId, actor);
        find(enterpriseId, id, true);
        if (contacts.lockFollowup(id) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该联系人已有跟进记录，无法删除");
        }
        contacts.delete(id);
        audits.record(actor.id(), "DELETE", "CONTACT", id);
    }

    private void checkEnterpriseAccess(long enterpriseId, AccountPrincipal actor) {
        Long owner = scopes.contactOwner(actor);
        if (contacts.findAccessibleEnterprise(enterpriseId, owner) == null) {
            throw new ResponseStatusException(NOT_FOUND, "企业不存在或没有联系人访问权限");
        }
    }

    private Map<String, Object> find(long enterpriseId, long id, boolean lock) {
        var row = lock ? contacts.findByIdForUpdate(enterpriseId, id) : contacts.findById(enterpriseId, id);
        return Rows.required(row, "联系人不存在");
    }

    private void saveSensitiveFields(long enterpriseId, long id, ContactInput input) {
        byte[] phone = encryption.encrypt(trimmed(input.phone()), enterpriseId, id, "phone");
        byte[] email = encryption.encrypt(trimmed(input.email()), enterpriseId, id, "email");
        contacts.updateSensitive(id, phone, email);
    }

    private ContactView view(Map<String, Object> row) {
        var fields = Rows.view(row);
        long id = ((Number) fields.get("id")).longValue();
        long enterprise = ((Number) fields.get("enterpriseId")).longValue();
        return new ContactView(id, enterprise, (String) fields.get("name"), (String) fields.get("position"),
                encryption.decrypt((byte[]) fields.get("phoneCiphertext"), enterprise, id, "phone"),
                encryption.decrypt((byte[]) fields.get("emailCiphertext"), enterprise, id, "email"),
                fields.get("createdAt"), fields.get("updatedAt"));
    }

    private static void validateInput(ContactInput input) {
        if (trimmed(input.name()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写联系人姓名");
        }
        if (input.email() != null && input.email().getBytes(StandardCharsets.UTF_8).length > 739) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱内容过长");
        }
    }

    record ContactInput(@NotBlank @Size(max = 100) String name,
                        @Size(max = 100) String position,
                        @Size(max = 60) String phone,
                        @Size(max = 254) @Email String email) {}

    record ContactView(long id, long enterpriseId, String name, String position, String phone, String email,
                       Object createdAt, Object updatedAt) {}

    record ContactPage(List<ContactView> items, long total) {}
}
