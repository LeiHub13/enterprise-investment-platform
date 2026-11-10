package com.enterprise.investment.openclaw;

import com.enterprise.investment.security.AccountPrincipal;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/openclaw")
@PreAuthorize("authentication.principal.hasRole('ADMIN')")
public class OpenClawController {
    private final OpenClawClient client;

    OpenClawController(OpenClawClient client) { this.client = client; }

    @GetMapping("/status")
    public OpenClawClient.Status status() { return client.status(); }

    record MessageRequest(String conversationId, String message) {}

    @PostMapping("/chat")
    public Map<String, String> chat(@AuthenticationPrincipal AccountPrincipal user, @RequestBody MessageRequest request) {
        return Map.of("reply", client.chat(user.id(), request.conversationId(), request.message()));
    }
}
