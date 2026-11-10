package com.enterprise.investment.openclaw;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手动运行的只读联调检查；普通构建不连接真实网关。 */
@EnabledIfSystemProperty(named = "openclaw.liveTest", matches = "true")
class OpenClawGatewayLiveTest {
    @Test
    void configuredAgentIsAvailableWithAppliedRestrictions() throws Exception {
        String tokenFile = System.getProperty("openclaw.liveTokenFile");
        assertNotNull(tokenFile, "请指定 openclaw.liveTokenFile");
        assertTrue(Files.isRegularFile(Path.of(tokenFile)), "加密令牌文件不存在");
        String token = OpenClawClient.readEncryptedToken(Path.of(tokenFile));
        assertTrue(!token.isBlank(), "加密令牌为空");
        var inspector = new OpenClawGatewayInspector(new ObjectMapper(), HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:18789"));
        assertTrue(inspector.activeConfig(token).isObject(), "未读取到已生效的网关配置");
        HttpRequest models = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18789/v1/models"))
                .header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<String> response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(models, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() == 200, "模型列表状态码：" + response.statusCode());
        var data = new ObjectMapper().readTree(response.body()).path("data");
        boolean listed = false;
        for (var model : data) if ("openclaw/crm-chat".equals(model.path("id").asText())) listed = true;
        assertTrue(listed, "网关模型列表未显示受限助手");
        OpenClawClient client = new OpenClawClient(new ObjectMapper(), "", "http://127.0.0.1:18789", "", tokenFile);
        OpenClawClient.Status status = client.status();
        assertTrue(status.ready(), status.message());
    }
}
