package com.enterprise.investment.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "app.legacy-panyue-status-enabled", havingValue = "true")
@RequestMapping("/api/panyue-agent")
@PreAuthorize("@ss.hasPermission('agent:use')")
public class PanyueAgentStatusController {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final String businessUrl;
    private final String agentUrl;

    PanyueAgentStatusController(
            @Value("${app.panyue-agent.business-url:http://127.0.0.1:18080}") String businessUrl,
            @Value("${app.panyue-agent.agent-url:http://127.0.0.1:18081}") String agentUrl) {
        this.businessUrl = businessUrl.replaceAll("/+$", "");
        this.agentUrl = agentUrl.replaceAll("/+$", "");
    }

    @GetMapping("/status")
    public Status status() {
        return new Status(
                probe("business-api", businessUrl + "/api/health"),
                probe("business-api-ready", businessUrl + "/api/health/ready"),
                probe("agent-api", agentUrl + "/internal/health"),
                LocalDateTime.now());
    }

    private Probe probe(String name, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean up = response.statusCode() >= 200 && response.statusCode() < 300;
            String body = response.body() == null ? "" : response.body().strip();
            String message = up ? "正常"
                    : body.isEmpty() ? "HTTP " + response.statusCode()
                    : body.substring(0, Math.min(body.length(), 180));
            return new Probe(name, url, up, response.statusCode(), message);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new Probe(name, url, false, 0, "请求被中断");
        } catch (Exception error) {
            return new Probe(name, url, false, 0, "未启动或无法连接");
        }
    }

    record Probe(String name, String url, boolean up, int status, String message) {
    }

    record Status(Probe businessApi, Probe businessReady, Probe agentApi, LocalDateTime checkedAt) {
    }
}
