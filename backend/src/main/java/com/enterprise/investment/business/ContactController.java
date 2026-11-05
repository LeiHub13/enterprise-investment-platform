package com.enterprise.investment.business;

import com.enterprise.investment.security.AccountPrincipal;
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

import static com.enterprise.investment.business.BusinessData.params;
import static com.enterprise.investment.business.BusinessData.trimmed;

@RestController
@RequestMapping("/api/enterprises/{enterpriseId}/contacts")
@PreAuthorize("hasAnyRole('ADMIN','INVESTMENT_MANAGER','INVESTMENT_OFFICER')")
public class ContactController {
    private static final String FIELDS = """
            id, enterprise_id, name, position, phone_ciphertext, email_ciphertext, created_at, updated_at
            """;

    private final BusinessData db;
    private final ContactEncryption encryption;

    ContactController(BusinessData db, ContactEncryption encryption) {
        this.db = db;
        this.encryption = encryption;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ContactPage list(@PathVariable long enterpriseId,
                            @RequestParam(required = false) String keyword,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size,
                            @AuthenticationPrincipal AccountPrincipal actor) {
        checkEnterpriseAccess(enterpriseId, actor);
        var result = db.page(FIELDS, "FROM crm_contact", """
                 WHERE enterprise_id = :enterprise AND (:keyword IS NULL OR name LIKE :keyword ESCAPE '!')
                """, "id DESC", params("enterprise", enterpriseId,
                "keyword", trimmed(keyword) == null ? null : BusinessData.contains(keyword)), page, size);
        return new ContactPage(result.items().stream().map(this::view).toList(), result.total());
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ContactView get(@PathVariable long enterpriseId, @PathVariable long id,
                           @AuthenticationPrincipal AccountPrincipal actor) {
        checkEnterpriseAccess(enterpriseId, actor);
        return view(find(enterpriseId, id, false));
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public ContactView create(@PathVariable long enterpriseId, @Valid @RequestBody ContactInput input,
                              @AuthenticationPrincipal AccountPrincipal actor) {
        checkEnterpriseAccess(enterpriseId, actor);
        validateInput(input);
        long id = db.insert("""
                INSERT INTO crm_contact (enterprise_id, name, position)
                VALUES (:enterprise, :name, :position)
                """, params("enterprise", enterpriseId, "name", trimmed(input.name()),
                "position", trimmed(input.position())));
        saveSensitiveFields(enterpriseId, id, input);
        db.audit(actor.id(), "CREATE", "CONTACT", id);
        return view(find(enterpriseId, id, false));
    }

    @PutMapping("/{id}")
    @Transactional
    public ContactView replace(@PathVariable long enterpriseId, @PathVariable long id,
                               @Valid @RequestBody ContactInput input,
                               @AuthenticationPrincipal AccountPrincipal actor) {
        checkEnterpriseAccess(enterpriseId, actor);
        find(enterpriseId, id, true);
        validateInput(input);
        db.update("UPDATE crm_contact SET name = :name, position = :position WHERE id = :id",
                params("id", id, "name", trimmed(input.name()), "position", trimmed(input.position())));
        saveSensitiveFields(enterpriseId, id, input);
        db.audit(actor.id(), "UPDATE", "CONTACT", id);
        return view(find(enterpriseId, id, false));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INVESTMENT_MANAGER')")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long enterpriseId, @PathVariable long id,
                       @AuthenticationPrincipal AccountPrincipal actor) {
        checkEnterpriseAccess(enterpriseId, actor);
        find(enterpriseId, id, true);
        if (!db.query("SELECT id FROM crm_followup WHERE contact_id = :id LIMIT 1 FOR UPDATE",
                params("id", id)).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该联系人已有跟进记录，无法删除");
        }
        db.update("DELETE FROM crm_contact WHERE id = :id", params("id", id));
        db.audit(actor.id(), "DELETE", "CONTACT", id);
    }

    private void checkEnterpriseAccess(long enterpriseId, AccountPrincipal actor) {
        Long owner = "INVESTMENT_OFFICER".equals(actor.role()) ? actor.id() : null;
        db.one("""
                SELECT e.id FROM ent_enterprise e
                WHERE e.id = :enterprise
                  AND (:owner IS NULL OR EXISTS (
                    SELECT 1 FROM crm_lead l WHERE l.enterprise_id = e.id AND l.owner_id = :owner
                  ))
                """, params("enterprise", enterpriseId, "owner", owner), "企业不存在或没有联系人访问权限");
    }

    private Map<String, Object> find(long enterpriseId, long id, boolean lock) {
        return db.one("SELECT " + FIELDS + " FROM crm_contact WHERE enterprise_id = :enterprise AND id = :id"
                        + (lock ? " FOR UPDATE" : ""),
                params("enterprise", enterpriseId, "id", id), "联系人不存在");
    }

    private void saveSensitiveFields(long enterpriseId, long id, ContactInput input) {
        byte[] phone = encryption.encrypt(trimmed(input.phone()), enterpriseId, id, "phone");
        byte[] email = encryption.encrypt(trimmed(input.email()), enterpriseId, id, "email");
        db.update("UPDATE crm_contact SET phone_ciphertext = :phone, email_ciphertext = :email WHERE id = :id",
                params("id", id, "phone", phone, "email", email));
    }

    private ContactView view(Map<String, Object> row) {
        var fields = BusinessData.view(row);
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
