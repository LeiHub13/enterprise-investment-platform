package com.enterprise.investment.openclaw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 只读取网关已生效的配置；不提供通用 RPC 转发。 */
final class OpenClawGatewayInspector {
    private final ObjectMapper json;
    private final HttpClient client;
    private final URI gateway;

    OpenClawGatewayInspector(ObjectMapper json, HttpClient client, URI httpGateway) {
        this.json = json;
        this.client = client;
        this.gateway = URI.create("ws://127.0.0.1:" + (httpGateway.getPort() < 0 ? 80 : httpGateway.getPort()));
    }

    JsonNode activeConfig(String token) throws IOException {
        var listener = new Frames();
        WebSocket socket = null;
        try {
            socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(4))
                    .buildAsync(gateway, listener).get(6, TimeUnit.SECONDS);
            JsonNode challenge = listener.next(json, null);
            if (!"connect.challenge".equals(challenge.path("event").asText())) throw new IOException("网关握手失败");
            ObjectNode connect = request("connect", "connect");
            ObjectNode params = (ObjectNode) connect.path("params");
            params.put("minProtocol", 3).put("maxProtocol", 4);
            params.putObject("client").put("id", "gateway-client").put("version", "1.0.0")
                    .put("platform", "windows").put("mode", "backend");
            params.put("role", "operator");
            params.putArray("scopes").add("operator.admin");
            params.putArray("caps");
            params.putArray("commands");
            params.putObject("permissions");
            params.putObject("auth").put("token", token);
            params.put("locale", "zh-CN").put("userAgent", "enterprise-crm/1.0");
            socket.sendText(json.writeValueAsString(connect), true).get(4, TimeUnit.SECONDS);
            if (!listener.next(json, "connect").path("ok").asBoolean(false)) throw new IOException("网关认证失败");

            socket.sendText(json.writeValueAsString(request("snapshot", "config.get")), true)
                    .get(4, TimeUnit.SECONDS);
            JsonNode reply = listener.next(json, "snapshot");
            JsonNode payload = reply.path("payload");
            String desired = payload.path("configRevisionHash").asText();
            if (!reply.path("ok").asBoolean(false) || !payload.path("valid").asBoolean(false)
                    || desired.isBlank() || !desired.equals(payload.path("appliedConfigHash").asText())
                    || !payload.path("config").isObject()) {
                throw new IOException("网关配置尚未生效");
            }
            return payload.path("config");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("网关检查中断");
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("网关连接失败");
        } finally {
            if (socket != null) socket.abort();
        }
    }

    private ObjectNode request(String id, String method) {
        ObjectNode request = json.createObjectNode();
        request.put("type", "req").put("id", id).put("method", method);
        request.putObject("params");
        return request;
    }

    private static final class Frames implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>(128);
        private final StringBuilder current = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) { socket.request(1); }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            current.append(data);
            if (current.length() > 2_000_000) {
                messages.offer("");
                socket.abort();
            } else if (last) {
                messages.offer(current.toString());
                current.setLength(0);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) { messages.offer(""); }

        JsonNode next(ObjectMapper json, String id) throws IOException, InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IOException("网关响应超时");
                String frame = messages.poll(remaining, TimeUnit.NANOSECONDS);
                if (frame == null || frame.isEmpty()) throw new IOException("网关连接中断");
                JsonNode parsed = json.readTree(frame);
                if (id == null || id.equals(parsed.path("id").asText())) return parsed;
            }
        }
    }
}
