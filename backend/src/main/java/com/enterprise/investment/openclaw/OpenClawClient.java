package com.enterprise.investment.openclaw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class OpenClawClient {
    private static final String AGENT = "openclaw/crm-chat";
    private final ObjectMapper json;
    private final HttpClient client;
    private final Path configPath;
    private final URI baseUri;
    private final String directToken;
    private final Path encryptedTokenFile;
    private final ConfigReader activeConfig;

    @FunctionalInterface
    interface ConfigReader { JsonNode read(String token) throws IOException; }

    @Autowired
    OpenClawClient(ObjectMapper json,
                   @Value("${app.openclaw.config-path:${OPENCLAW_GATEWAY_CONFIG_PATH:}}") String configPath,
                   @Value("${app.openclaw.base-url:http://127.0.0.1:18789}") String baseUrl,
                   @Value("${OPENCLAW_GATEWAY_TOKEN:}") String directToken,
                   @Value("${OPENCLAW_GATEWAY_TOKEN_FILE:}") String encryptedTokenFile) {
        this(json, configPath.isBlank() ? null : Path.of(configPath), URI.create(baseUrl), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).build(),
                directToken,
                encryptedTokenFile.isBlank() ? defaultTokenFile() : Path.of(encryptedTokenFile), null);
    }

    OpenClawClient(ObjectMapper json, Path configPath, URI baseUri, HttpClient client) {
        this(json, configPath, baseUri, client, "", null,
                token -> json.readTree(Files.readString(configPath)));
    }

    OpenClawClient(ObjectMapper json, Path configPath, URI baseUri, HttpClient client,
                   String directToken, Path encryptedTokenFile, ConfigReader activeConfig) {
        if (!"http".equalsIgnoreCase(baseUri.getScheme()) || !"127.0.0.1".equals(baseUri.getHost())
                || baseUri.getUserInfo() != null || baseUri.getQuery() != null || baseUri.getFragment() != null
                || (baseUri.getPath() != null && !baseUri.getPath().isEmpty() && !"/".equals(baseUri.getPath()))) {
            throw new IllegalArgumentException("OpenClaw 网关必须使用本机回环地址");
        }
        this.json = json;
        this.configPath = configPath;
        this.baseUri = baseUri;
        this.client = client;
        this.directToken = directToken.trim();
        this.encryptedTokenFile = encryptedTokenFile;
        this.activeConfig = activeConfig == null ? new OpenClawGatewayInspector(json, client, baseUri)::activeConfig : activeConfig;
    }

    record Status(boolean ready, String message, String conversationId) {}
    record Config(String token, String issue) {}

    Status status() {
        Config config = config();
        String issue = config.issue();
        if (issue == null && !agentAvailable(config.token())) {
            issue = "OpenClaw 对话服务尚未就绪，请检查服务配置并重新加载。";
        }
        return new Status(issue == null, issue == null ? "OpenClaw 已连接" : issue, UUID.randomUUID().toString());
    }

    String chat(long userId, String conversationId, String message) {
        String text = message == null ? "" : message.trim();
        if (text.isEmpty() || text.length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息须为 1 至 4000 字");
        }
        if (java.util.regex.Pattern.compile("(?m)^[\\s\\p{Z}\\p{Cf}]*[/／]").matcher(text).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请使用普通文字，不支持网关斜杠命令");
        }
        UUID session;
        try {
            session = UUID.fromString(conversationId);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对话编号无效");
        }
        Config config = config();
        if (config.issue() != null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, config.issue());
        }
        if (!agentAvailable(config.token())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OpenClaw 对话服务尚未就绪");
        }
        ObjectNode payload = json.createObjectNode();
        payload.put("model", AGENT);
        payload.put("user", "enterprise-crm:" + userId + ":" + session);
        payload.put("stream", false);
        ObjectNode userMessage = payload.putArray("messages").addObject();
        userMessage.put("role", "user");
        userMessage.put("content", text);
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/v1/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + config.token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenClaw 暂时无法完成对话，请检查服务和模型配置");
            }
            JsonNode content = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenClaw 未返回有效回复");
            }
            String answer = content.asText();
            return answer.length() > 32000 ? answer.substring(0, 32000) : answer;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenClaw 请求中断");
        } catch (IOException | IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法连接 OpenClaw 对话服务");
        }
    }

    private Config config() {
        try {
            String token = encryptedTokenFile != null && Files.isRegularFile(encryptedTokenFile)
                    ? readEncryptedToken(encryptedTokenFile) : directToken;
            if (token.isBlank() && configPath != null) {
                JsonNode source = json.readTree(Files.readString(configPath));
                JsonNode tokenNode = source.at("/gateway/auth/token");
                token = tokenNode.isTextual() ? tokenNode.asText().trim() : "";
            }
            if (token.isBlank() || token.startsWith("${")) {
                return new Config(null, "请先配置 OpenClaw 网关令牌，配置方法见项目说明。");
            }
            JsonNode root = activeConfig.read(token);
            if (!root.at("/gateway/http/endpoints/chatCompletions/enabled").asBoolean(false)) {
                return new Config(null, "请先启用 OpenClaw 的对话接口，配置方法见项目说明。");
            }
            JsonNode agent = root.at("/agents/entries/crm-chat");
            JsonNode tools = agent.path("tools");
            JsonNode allow = tools.path("allow");
            JsonNode skills = agent.path("skills");
            if (!agent.path("workspace").asText().matches("(?:~|/home/[A-Za-z0-9._-]+|/Users/[A-Za-z0-9._-]+)/\\.openclaw(?:-[A-Za-z0-9_-]+)?/workspace-crm-chat")
                    || !skills.isArray() || !skills.isEmpty() || agent.has("runtime") || agent.has("models")
                    || hasCustomRuntime(root.at("/agents/defaults/models"))
                    || !"minimal".equals(tools.path("profile").asText()) || !allow.isArray() || allow.size() != 1
                    || !"session_status".equals(allow.get(0).asText())
                    || !contains(tools.path("deny"), "gateway")
                    || tools.path("elevated").path("enabled").asBoolean(true)) {
                return new Config(null, "请先配置受限的 crm-chat 智能体，配置方法见项目说明。");
            }
            if (!"token".equals(root.at("/gateway/auth/mode").asText("token"))) {
                return new Config(null, "OpenClaw 网关需要使用令牌认证模式。");
            }
            return new Config(token, null);
        } catch (IOException | RuntimeException error) {
            return new Config(null, "无法验证 OpenClaw 当前配置，请检查网关连接和令牌。");
        }
    }

    private static Path defaultTokenFile() {
        Path working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path local = working.resolve(".env.openclaw-token");
        if (Files.isRegularFile(local)) return local;
        Path parent = working.getParent();
        return parent != null && Files.isRegularFile(parent.resolve(".env.openclaw-token"))
                ? parent.resolve(".env.openclaw-token") : local;
    }

    static String readEncryptedToken(Path path) throws IOException {
        String script = "$ProgressPreference='SilentlyContinue';try{$ErrorActionPreference='Stop';"
                + "Import-Module ($PSHOME+'\\Modules\\Microsoft.PowerShell.Security\\Microsoft.PowerShell.Security.psd1');"
                + "$s=([string](Get-Content -Raw -LiteralPath $env:TASK_OPENCLAW_TOKEN_FILE)).Trim() | ConvertTo-SecureString;"
                + "[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);"
                + "[Console]::Out.Write([System.Net.NetworkCredential]::new('', $s).Password)}"
                + "catch{$n=$_.Exception.CommandName;if($n -notin @('Get-Content','ConvertTo-SecureString')){$n='other'};"
                + "[Console]::Error.Write('DECRYPT_ERROR:'+$_.Exception.GetType().Name+':'+$n);exit 1}";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
        builder.environment().put("TASK_OPENCLAW_TOKEN_FILE", path.toAbsolutePath().toString());
        Process process = builder.start();
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw new IOException("令牌解密超时");
            if (process.exitValue() != 0) {
                String reason = new String(process.getErrorStream().readNBytes(1024), StandardCharsets.UTF_8);
                throw new IOException(reason.matches("DECRYPT_ERROR:[A-Za-z0-9_.:-]+") ? reason : "令牌解密失败");
            }
            byte[] bytes = process.getInputStream().readNBytes(8193);
            if (bytes.length == 0 || bytes.length > 8192) throw new IOException("令牌格式无效");
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("令牌读取中断");
        } finally {
            process.destroy();
        }
    }

    private static boolean contains(JsonNode node, String value) {
        if (!node.isArray()) return false;
        for (JsonNode item : node) if (value.equals(item.asText())) return true;
        return false;
    }

    private static boolean hasCustomRuntime(JsonNode models) {
        if (!models.isObject()) return false;
        var entries = models.elements();
        while (entries.hasNext()) if (entries.next().has("agentRuntime")) return true;
        return false;
    }

    private boolean agentAvailable(String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/v1/models"))
                    .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return false;
            JsonNode models = json.readTree(response.body()).path("data");
            if (!models.isArray()) return false;
            for (JsonNode model : models) if (AGENT.equals(model.path("id").asText())) return true;
        } catch (IOException | IllegalArgumentException ignored) {
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return false;
    }
}
