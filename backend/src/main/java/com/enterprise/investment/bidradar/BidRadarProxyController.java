package com.enterprise.investment.bidradar;

import com.enterprise.investment.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bid-radar")
@PreAuthorize("@ss.hasPermission('bidradar:view')")
public class BidRadarProxyController {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final String baseUrl;

    BidRadarProxyController(@Value("${app.bid-radar.base-url:http://127.0.0.1:8765}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request, @AuthenticationPrincipal AccountPrincipal actor) {
        String path = request.getRequestURI().substring("/api/bid-radar".length());
        if (!path.startsWith("/") || path.contains("..")) {
            return error(400, "标讯雷达接口路径无效");
        }
        if ("/qcc/search".equals(path) && (actor == null || !actor.hasRole("ADMIN"))) {
            return error(403, "企查查付费查询仅限管理员使用");
        }
        String query = request.getQueryString();
        URI uri = URI.create(baseUrl + "/api" + path + (query == null ? "" : "?" + query));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5));
        try {
            byte[] body = request.getInputStream().readAllBytes();
            String contentType = request.getContentType();
            if (body.length > 0) {
                builder.method(request.getMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
                if (contentType != null) {
                    builder.header("Content-Type", contentType);
                }
            } else {
                builder.method(request.getMethod(), HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            String responseType = response.headers().firstValue("Content-Type")
                    .orElse("application/json; charset=utf-8");
            return ResponseEntity.status(response.statusCode())
                    .contentType(MediaType.parseMediaType(responseType))
                    .body(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return error(502, "标讯雷达请求被中断");
        } catch (IOException | IllegalArgumentException error) {
            return error(502, "标讯雷达服务未启动或无法连接");
        }
    }

    private static ResponseEntity<byte[]> error(int status, String message) {
        String json = "{\"error\":\"" + message + "\"}";
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.getBytes(StandardCharsets.UTF_8));
    }
}
