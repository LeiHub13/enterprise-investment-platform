package com.enterprise.investment.bidradar;

import com.enterprise.investment.security.AccountPrincipal;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BidRadarPermissionTest {
    @Test
    void paidQccRouteRejectsNonAdminBeforeConnecting() {
        BidRadarProxyController proxy = new BidRadarProxyController("http://127.0.0.1:1");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bid-radar/qcc/search");
        var response = proxy.proxy(request, new AccountPrincipal(1, "staff", "INVESTMENT_OFFICER"));
        assertEquals(403, response.getStatusCode().value());
    }

    @Test
    void adminCanForwardQccRequestToBidRadar() throws Exception {
        AtomicReference<String> forwarded = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/qcc/search", exchange -> {
            forwarded.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] result = "{\"count\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, result.length);
            try (var output = exchange.getResponseBody()) { output.write(result); }
        });
        server.start();
        try {
            BidRadarProxyController proxy = new BidRadarProxyController("http://127.0.0.1:" + server.getAddress().getPort());
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bid-radar/qcc/search");
            request.setContentType("application/json");
            request.setContent("{\"id\":3}".getBytes(StandardCharsets.UTF_8));
            var response = proxy.proxy(request, new AccountPrincipal(1, "admin", "ADMIN"));
            assertEquals(200, response.getStatusCode().value());
            assertEquals("{\"id\":3}", forwarded.get());
            assertEquals("{\"count\":1}", new String(response.getBody(), StandardCharsets.UTF_8));
        } finally {
            server.stop(0);
        }
    }
}
