package com.enterprise.investment.openclaw;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenClawGatewayInspectorTest {
    @Test
    void readsOnlyTheAppliedGatewayConfiguration() throws Exception {
        Fixture fixture = new Fixture("revision-1");
        assertTrue(fixture.inspector.activeConfig("fixture-token").isObject());
        assertEquals(List.of("connect", "config.get"), fixture.methods);
        verify(fixture.socket).abort();
    }

    @Test
    void rejectsConfigurationThatIsNotYetApplied() {
        Fixture fixture = new Fixture("older-revision");
        assertThrows(IOException.class, () -> fixture.inspector.activeConfig("fixture-token"));
        verify(fixture.socket).abort();
    }

    private static final class Fixture {
        final ObjectMapper json = new ObjectMapper();
        final HttpClient http = mock(HttpClient.class);
        final WebSocket.Builder builder = mock(WebSocket.Builder.class);
        final WebSocket socket = mock(WebSocket.class);
        final AtomicReference<WebSocket.Listener> listener = new AtomicReference<>();
        final List<String> methods = new ArrayList<>();
        final OpenClawGatewayInspector inspector;

        Fixture(String appliedHash) {
            when(http.newWebSocketBuilder()).thenReturn(builder);
            when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
            when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class))).thenAnswer(invocation -> {
                WebSocket.Listener current = invocation.getArgument(1);
                listener.set(current);
                current.onOpen(socket);
                current.onText(socket, "{\"type\":\"event\",\"event\":\"connect.challenge\"}", true);
                return CompletableFuture.completedFuture(socket);
            });
            when(socket.sendText(any(CharSequence.class), eq(true))).thenAnswer(invocation -> {
                var request = json.readTree(invocation.<CharSequence>getArgument(0).toString());
                String method = request.path("method").asText();
                methods.add(method);
                String reply = method.equals("connect")
                        ? "{\"id\":\"connect\",\"ok\":true}"
                        : "{\"id\":\"snapshot\",\"ok\":true,\"payload\":{\"valid\":true,"
                        + "\"configRevisionHash\":\"revision-1\",\"appliedConfigHash\":\"" + appliedHash
                        + "\",\"config\":{\"agents\":{}}}}";
                listener.get().onText(socket, reply, true);
                return CompletableFuture.completedFuture(socket);
            });
            inspector = new OpenClawGatewayInspector(json, http, URI.create("http://127.0.0.1:18789"));
        }
    }
}
