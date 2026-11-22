package com.enterprise.investment.agent;

import com.enterprise.investment.mapper.AgentMapper;
import com.enterprise.investment.mapper.DataMapper;
import com.enterprise.investment.security.AccountPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class AgentProxyControllerTest {
    @Test
    void failedResumeMarksRunAsErrorAndReturnsAnSseError() throws Exception {
        exercise(409, "{\"message\":\"not resumable\"}", true);
    }

    @Test
    void truncatedStreamDoesNotLeaveRunApproving() throws Exception {
        exercise(200, "event: message\ndata: {\"text\":\"interrupted\"}\n\n", true);
    }

    @Test
    void completedStreamPersistsDoneStatus() throws Exception {
        exercise(200, "event: done\ndata: {\"status\":\"DONE\"}\n\n", false);
    }

    private void exercise(int status, String body, boolean error) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/tasks/test-run/decision", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            AgentMapper mapper = mock(AgentMapper.class);
            when(mapper.findRun("test-run")).thenReturn(Map.of("user_id", 1L, "status", "AWAITING_REVIEW"));
            when(mapper.claimReview("test-run", "APPROVING")).thenReturn(1);
            var proxy = new AgentProxyController(new ObjectMapper(), mapper, mock(DataMapper.class), mock(AgentEventDispatchService.class),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            var request = new MockHttpServletRequest();
            request.addHeader("Authorization", "Bearer test");
            var response = new MockHttpServletResponse();
            proxy.decision("test-run", new AgentProxyController.DecisionInput(true), request, response,
                    new AccountPrincipal(1, "test", "ADMIN"));
            if (error) {
                assertTrue(response.getContentAsString().contains("event: error"));
                verify(mapper).finishRun("test-run", "ERROR");
            } else {
                verify(mapper).finishRun("test-run", "DONE");
                verify(mapper, never()).finishRun("test-run", "ERROR");
            }
        } finally {
            server.stop(0);
        }
    }
}
