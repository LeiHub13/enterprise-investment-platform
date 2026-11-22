package com.enterprise.investment.openclaw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class OpenClawClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private Path config;
    private OpenClawClient client;
    private final AtomicInteger chatRequests = new AtomicInteger();
    private final AtomicReference<JsonNode> body = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void setup(@TempDir Path directory) throws Exception {
        config = directory.resolve("config.json");
        Files.writeString(config, config(true, "[\"session_status\"]"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> reply(exchange,
                "{\"data\":[{\"id\":\"openclaw/crm-chat\"}]}"));
        server.createContext("/v1/chat/completions", exchange -> {
            chatRequests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(json.readTree(exchange.getRequestBody()));
            reply(exchange, "{\"choices\":[{\"message\":{\"content\":\"测试回复\"}}]}");
        });
        server.start();
        URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = new OpenClawClient(json, config, url, HttpClient.newHttpClient());
    }

    @AfterEach
    void close() { if (server != null) server.stop(0); }

    @Test
    void routesOnlyToRestrictedAgentWithPerConversationIdentity() {
        var status = client.status();
        assertTrue(status.ready());
        assertEquals("测试回复", client.chat(23, status.conversationId(), "请整理跟进提纲"));
        assertEquals("Bearer test-secret", authorization.get());
        assertEquals("openclaw/crm-chat", body.get().path("model").asText());
        assertEquals("enterprise-crm:23:" + status.conversationId(), body.get().path("user").asText());
        assertEquals("请整理跟进提纲", body.get().path("messages").path(0).path("content").asText());
        assertEquals(1, chatRequests.get());
    }

    @Test
    void disabledOrBroadAgentCannotRun() throws Exception {
        Files.writeString(config, config(false, "[\"session_status\"]"));
        assertFalse(client.status().ready());
        assertThrows(ResponseStatusException.class, () -> client.chat(1, UUID.randomUUID().toString(), "hello"));
        Files.writeString(config, config(true, "[\"session_status\",\"exec\"]"));
        assertFalse(client.status().ready());
        assertThrows(ResponseStatusException.class, () -> client.chat(1, UUID.randomUUID().toString(), "hello"));
        assertEquals(0, chatRequests.get());
    }

    @Test
    void invalidInputCannotRun() {
        assertThrows(ResponseStatusException.class, () -> client.chat(1, "bad-session", "hello"));
        assertThrows(ResponseStatusException.class, () -> client.chat(1, UUID.randomUUID().toString(), " "));
        assertThrows(ResponseStatusException.class, () -> client.chat(1, UUID.randomUUID().toString(), "/config set a b"));
        assertThrows(ResponseStatusException.class, () -> client.chat(1, UUID.randomUUID().toString(), "hello\n  ／config set a b"));
        assertEquals(0, chatRequests.get());
    }

    @Test
    void missingGatewayConfigFailsClosed() {
        OpenClawClient unconfigured = new OpenClawClient(json, null,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), HttpClient.newHttpClient());
        assertFalse(unconfigured.status().ready());
        assertThrows(ResponseStatusException.class, () -> unconfigured.chat(1, UUID.randomUUID().toString(), "hello"));
        assertEquals(0, chatRequests.get());
    }

    @Test
    void springCanCreateClientWithoutGatewayConfiguration() {
        new ApplicationContextRunner().withPropertyValues("OPENCLAW_GATEWAY_TOKEN_FILE=Z:/nonexistent/openclaw-token")
                .withBean(ObjectMapper.class).withBean(OpenClawClient.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.getBean(OpenClawClient.class).status().ready());
                });
    }

    private static String config(boolean enabled, String allow) {
        return "{\"gateway\":{\"auth\":{\"mode\":\"token\",\"token\":\"test-secret\"},"
                + "\"http\":{\"endpoints\":{\"chatCompletions\":{\"enabled\":" + enabled + "}}}},"
                + "\"agents\":{\"entries\":{\"crm-chat\":{\"workspace\":\"~/.openclaw/workspace-crm-chat\",\"skills\":[],\"tools\":{\"profile\":\"minimal\","
                + "\"allow\":" + allow + ",\"deny\":[\"gateway\"],\"elevated\":{\"enabled\":false}}}}}}";
    }

    private static void reply(HttpExchange exchange, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
