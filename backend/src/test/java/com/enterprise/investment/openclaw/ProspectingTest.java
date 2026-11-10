package com.enterprise.investment.openclaw;

import com.enterprise.investment.security.AccountPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProspectingTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void publicationTimeIsDisplayedInChinaTime() {
        assertEquals("2026-09-30", ProspectingSearch.localDate("2026-09-29T16:00:00Z"));
        assertEquals("2026-09-30", ProspectingSearch.localDate("2026-09-30T10:00:00+08:00"));
        assertEquals("2026-09-30", ProspectingSearch.localDate("2026-09-30"));
        assertEquals("", ProspectingSearch.localDate("invalid date"));
        assertEquals("https://example.com/notice.pdf", ProspectingSearch.safeUrl("https://example.com/notice.pdf#1#1"));
        assertNull(ProspectingSearch.safeUrl("http://192.168.1.1/private"));
    }

    @Test
    void searchUsesThreeBoundedQueriesAndKeepsOnlySafeDistinctSources() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            int number = calls.incrementAndGet();
            JsonNodeAssertions.checkRequest(json, exchange.getRequestBody().readAllBytes());
            assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
            String payload = "{\"results\":["
                    + "{\"url\":\"https://example.com/same\",\"title\":\"南京办公租赁\",\"content\":\"采购人：南京甲公司\"},"
                    + "{\"url\":\"https://example.com/" + number + "\",\"title\":\"南京企业公告\",\"snippet\":\"办公需求待核实\"},"
                    + "{\"url\":\"http://127.0.0.1/private\",\"title\":\"禁止内网\",\"content\":\"test\"},"
                    + "{\"url\":\"javascript:alert(1)\",\"title\":\"无效\",\"content\":\"test\"}]}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
            ProspectingSearch client = new ProspectingSearch(json, HttpClient.newHttpClient(), endpoint, "test-key", List.of());
            ProspectingSearch.SearchResult result = client.search();
            assertEquals(3, calls.get());
            assertEquals(3, result.searchedQueries());
            assertEquals(4, result.sources().size());
            assertTrue(result.sources().stream().allMatch(s -> s.url().startsWith("https://example.com/")));
            assertEquals(List.of(1, 2, 3, 4), result.sources().stream().map(ProspectingSearch.Source::id).toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidKeyStopsWithoutTryingAllThreeQueries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            ProspectingSearch client = new ProspectingSearch(json, http, endpoint, "bad-key", List.of());
            ResponseStatusException error = assertThrows(ResponseStatusException.class, client::search);
            assertEquals(HttpStatus.BAD_GATEWAY, error.getStatusCode());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void onlyReturnsCompaniesAndEvidenceActuallyPresentInReferencedSource() {
        var source = new ProspectingSearch.Source(1, "南京办公场所租赁采购", "https://example.com/1", "2026-11-01",
                "采购人：南京测试企业有限公司，拟采购南京办公场所租赁服务。该需求是否仍有效待核实。");
        String modelReply = """
                ```json
                {"candidates":[
                  {"name":"南京测试企业有限公司","signal":"拟采购办公场所租赁，状态待核实","evidence":"采购人：南京测试企业有限公司","source":1},
                  {"name":"南京虚构企业有限公司","signal":"需要租赁","evidence":"采购人：南京测试企业有限公司","source":1},
                  {"name":"南京测试企业有限公司","signal":"需要租赁","evidence":"没有出现过的证据片段","source":1},
                  {"name":"南京测试企业有限公司","signal":"需要租赁","evidence":"采购人：南京测试企业有限公司","source":99}
                ]}
                ```
                """;
        ProspectingController controller = new ProspectingController(null, null, json);
        List<ProspectingController.Lead> leads = controller.extractLeads(modelReply, List.of(source));
        assertEquals(1, leads.size());
        assertEquals("南京测试企业有限公司", leads.get(0).company());
        assertEquals(1, leads.get(0).sourceId());
    }

    @Test
    void invalidConversationDoesNotTriggerSearch() {
        ProspectingSearch search = mock(ProspectingSearch.class);
        OpenClawClient claw = mock(OpenClawClient.class);
        ProspectingController controller = new ProspectingController(search, claw, json);
        var actor = new AccountPrincipal(1, "admin", "ADMIN");
        var request = new ProspectingController.Request("invalid", "泛悦广场", "");
        assertThrows(ResponseStatusException.class, () -> controller.find(actor, request));
        verifyNoInteractions(search, claw);
    }

    @Test
    void aiFailureStillReturnsExplicitBuyerAndSources() {
        ProspectingSearch search = mock(ProspectingSearch.class);
        OpenClawClient claw = mock(OpenClawClient.class);
        ProspectingController controller = new ProspectingController(search, claw, json);
        var actor = new AccountPrincipal(1, "admin", "ADMIN");
        String session = UUID.randomUUID().toString();
        var source = new ProspectingSearch.Source(1, "南京办公场地租赁", "https://example.com/1", "2026-10-01", "采购人：南京测试企业有限公司");
        when(claw.status()).thenReturn(new OpenClawClient.Status(true, "已连接", session));
        when(search.search()).thenReturn(new ProspectingSearch.SearchResult(List.of(source), List.of(), 3));
        when(claw.chat(eq(1L), eq(session), anyString())).thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
        var result = controller.find(actor, new ProspectingController.Request(session, "泛悦广场", ""));
        assertEquals(1, result.leads().size());
        assertEquals("南京测试企业有限公司", result.leads().get(0).company());
        assertEquals("source", result.leads().get(0).method());
        assertEquals(List.of(source), result.sources());
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void buyerExtractionExcludesLandlordsAndNonOfficeProcurement() {
        var buyer = new ProspectingSearch.Source(1, "南京办公场地续租采购", "https://example.com/1", "2026-09-21",
                "采购人：中旅旅行服务有限公司 南京分公司办公场地续租项目");
        var issuer = new ProspectingSearch.Source(2, "南京办公室办公场所租赁项目询比公告", "https://example.com/2", "2026-09-14",
                "金元证券股份有限公司现就南京办公室办公场所租赁项目进行询比采购");
        var landlord = new ProspectingSearch.Source(3, "南京办公楼对外招租公告", "https://example.com/3", "2026-09-14",
                "采购人：南京出租方有限公司，对外招租办公楼");
        var software = new ProspectingSearch.Source(4, "南京办公软件租赁采购", "https://example.com/4", "2026-09-14",
                "采购人：南京软件有限公司，办公软件租赁服务");
        var result = ProspectingController.fieldLeads(List.of(buyer, issuer, landlord, software));
        assertEquals(List.of("中旅旅行服务有限公司", "金元证券股份有限公司"),
                result.stream().map(ProspectingController.Lead::company).toList());
        assertTrue(result.get(0).signal().contains("续租"));
        assertTrue(result.stream().allMatch(lead -> lead.method().equals("source")));
    }

    @Test
    void aiCannotPresentSupplierAsCustomerAndHistoricalStatusIsNotInvented() {
        var supplier = new ProspectingSearch.Source(1, "供应链南京公司办公用房租赁项目询价通知", "https://example.com/1", "2026-07-03",
                "拟邀请供应商名称：南京鼓楼创新广场建设发展有限公司 采购内容：拟在南京鼓楼区扩租办公用房");
        var buyer = new ProspectingSearch.Source(2, "南京开发中心扩租职场办公家具采购", "https://example.com/2", "2026-07-28",
                "由人保运营有限公司江苏分公司（以下简称“采购人”）组织相关比选工作，采购办公家具。");
        String reply = """
                {"candidates":[
                  {"name":"南京鼓楼创新广场建设发展有限公司","signal":"急需办公室","evidence":"拟邀请供应商名称：南京鼓楼创新广场建设发展有限公司","source":1},
                  {"name":"人保运营有限公司江苏分公司","signal":"现在正在扩租","evidence":"由人保运营有限公司江苏分公司（以下简称“采购人”）","source":2}
                ]}
                """;
        var result = new ProspectingController(null, null, json).extractLeads(reply, List.of(supplier, buyer));
        assertEquals(1, result.size());
        assertEquals("人保运营有限公司江苏分公司", result.get(0).company());
        assertTrue(result.get(0).signal().contains("配套采购"));
        assertTrue(result.get(0).signal().contains("待核实"));
        assertFalse(result.get(0).signal().contains("现在正在"));
    }

    private static class JsonNodeAssertions {
        static void checkRequest(ObjectMapper json, byte[] payload) throws java.io.IOException {
            var node = json.readTree(payload);
            assertTrue(node.path("query").asText().contains("南京"));
            assertTrue(node.path("query").asText().length() <= 128);
            assertEquals(15, node.path("count").asInt());
            assertTrue(node.path("need_content").asBoolean());
        }
    }
}
