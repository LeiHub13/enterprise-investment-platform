package com.enterprise.investment.openclaw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** A bounded, read-only web search. It never fetches result URLs or writes CRM data. */
@Service
class ProspectingSearch {
    private static final URI ENDPOINT = URI.create("https://api.deepseek.com/v1/tools/search");
    private static final int MAX_RESPONSE_BYTES = 4_000_000;
    private static final int MAX_SOURCES = 12;
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI endpoint;
    private final String configuredKey;
    private final List<Path> keyFiles;

    @Autowired
    ProspectingSearch(ObjectMapper json, @Value("${DEEPSEEK_API_KEY:}") String configuredKey,
                      @Value("${app.prospecting.key-file:}") String keyFile) {
        this(json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6))
                        .version(HttpClient.Version.HTTP_1_1).build(), ENDPOINT,
                configuredKey, keyFile.isBlank()
                        ? List.of(Path.of("bidradar", ".env"), Path.of("..", "bidradar", ".env"))
                        : List.of(Path.of(keyFile)));
    }

    ProspectingSearch(ObjectMapper json, HttpClient http, URI endpoint, String key, List<Path> keyFiles) {
        this.json = json;
        this.http = http;
        this.endpoint = endpoint;
        this.configuredKey = key == null ? "" : key.trim();
        this.keyFiles = keyFiles;
    }

    record Source(int id, String title, String url, String date, String excerpt) {}
    record SearchResult(List<Source> sources, List<String> errors, int searchedQueries) {}
    private record RawSource(String title, String url, String date, String excerpt) {}

    SearchResult search() {
        String key = searchKey();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String after = today.minusYears(1).toString();
        List<String> queries = List.of(
                "南京 办公场地租赁 采购人 公司 " + today.getYear(),
                "南京 分公司 办公场所 租赁 采购 " + today.getYear(),
                "南京 企业 办公室 扩租 搬迁 寻租 after:" + after);
        List<List<RawSource>> groups = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int successful = 0;
        for (String query : queries) {
            try {
                groups.add(query(query, key));
                successful++;
            } catch (SearchFailure failure) {
                errors.add(failure.getMessage());
                groups.add(List.of());
                if (failure.stop) break;
            }
        }
        if (successful == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    errors.isEmpty() ? "搜索服务暂时不可用" : errors.get(0));
        }
        // Interleave first, deduplicate, then prioritize enterprise demand over property advertisements.
        Map<String, RawSource> distinct = new LinkedHashMap<>();
        for (int index = 0; index < 15; index++) {
            for (List<RawSource> group : groups) {
                if (index < group.size()) {
                    RawSource source = group.get(index);
                    distinct.putIfAbsent(source.url(), source);
                }
            }
        }
        List<Source> sources = new ArrayList<>();
        List<RawSource> ranked = distinct.values().stream()
                .sorted(Comparator.comparingInt(ProspectingSearch::rank).reversed()
                        .thenComparing(RawSource::date, Comparator.reverseOrder()))
                .limit(MAX_SOURCES).toList();
        for (RawSource source : ranked) {
            sources.add(new Source(sources.size() + 1, source.title(), source.url(), source.date(), source.excerpt()));
        }
        return new SearchResult(List.copyOf(sources), List.copyOf(errors), successful);
    }

    private static int rank(RawSource source) {
        String title = source.title();
        String text = title + " " + source.excerpt();
        int score = 0;
        if (text.contains("南京")) score += 3;
        if (text.contains("采购人") || text.contains("承租人") || text.contains("采购单位")) score += 7;
        if (text.contains("现就") && text.contains("采购")) score += 5;
        if (text.contains("寻租") || text.contains("拟租") || text.contains("扩租") || text.contains("搬迁")) score += 4;
        if (text.contains("公司") || text.contains("集团") || text.contains("分行")) score += 3;
        if (text.contains("办公") && (text.contains("租赁") || text.contains("采购"))) score += 3;
        if (title.contains("招租") || title.contains("出租") || title.contains("楼盘") || title.contains("房源")) score -= 8;
        if (text.contains("招租单位") || text.contains("出租方")) score -= 5;
        if (title.contains("成交") || title.contains("中标") || title.contains("续租")) score -= 2;
        try {
            LocalDate date = LocalDate.parse(source.date().substring(0, 10));
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            if (date.isBefore(today.minusYears(1))) score -= 4;
            else if (!date.isBefore(today.minusMonths(6))) score += 2;
        } catch (RuntimeException ignored) {
            // Missing or malformed publication dates remain visible as unverified.
        }
        return score;
    }

    private List<RawSource> query(String query, String key) throws SearchFailure {
        ObjectNode payload = json.createObjectNode();
        payload.put("query", query);
        payload.put("count", 15);
        payload.put("need_content", true);
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    int code = response.statusCode();
                    String message = switch (code) {
                        case 401 -> "搜索密钥无效，请检查标讯雷达配置";
                        case 402 -> "搜索账户余额不足";
                        case 403 -> "搜索服务拒绝访问";
                        case 429 -> "搜索请求过于频繁，请稍后再试";
                        default -> "搜索服务返回 HTTP " + code;
                    };
                    throw new SearchFailure(message, code == 401 || code == 402 || code == 403 || code == 429);
                }
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) throw new SearchFailure("搜索结果过大，请稍后重试", false);
                JsonNode results = json.readTree(bytes).path("results");
                if (!results.isArray()) throw new SearchFailure("搜索服务未返回有效结果", false);
                List<RawSource> items = new ArrayList<>();
                for (JsonNode item : results) {
                    String url = safeUrl(item.path("url").asText(""));
                    String title = clean(item.path("title").asText(""), 120);
                    String excerpt = clean(firstText(item, "content", "summary", "snippet"), 320);
                    String date = localDate(item.path("date").asText(""));
                    if (url != null && !title.isEmpty() && !excerpt.isEmpty()) {
                        items.add(new RawSource(title, url, date, excerpt));
                    }
                }
                return items;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new SearchFailure("搜索已中断", true);
        } catch (IOException | IllegalArgumentException error) {
            throw new SearchFailure("搜索服务连接失败或响应异常", false);
        }
    }

    static String clean(String value, int maxLength) {
        String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static String firstText(JsonNode item, String... fields) {
        for (String field : fields) {
            String value = item.path(field).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    static String localDate(String value) {
        if (value == null || value.isBlank()) return "";
        String date = value.trim();
        try {
            if (date.contains("T")) {
                return OffsetDateTime.parse(date).atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                        .toLocalDate().toString();
            }
            return LocalDate.parse(date).toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    static String safeUrl(String value) {
        if (value == null || value.length() > 1000) return null;
        try {
            String candidate = value.trim();
            int hash = candidate.indexOf('#');
            if (hash >= 0 && (candidate.indexOf('#', hash + 1) >= 0
                    || candidate.substring(0, hash).toLowerCase(Locale.ROOT).endsWith(".pdf"))) {
                candidate = candidate.substring(0, hash);
            }
            URI uri = URI.create(candidate);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null || uri.getScheme() == null
                    || !List.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                    || host.equalsIgnoreCase("localhost") || host.toLowerCase(Locale.ROOT).endsWith(".local")
                    || host.matches("\\d+(?:\\.\\d+){3}") || host.contains(":")) return null;
            return uri.toASCIIString();
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private String searchKey() {
        if (!configuredKey.isBlank()) return configuredKey;
        for (Path file : keyFiles) {
            try {
                if (!Files.isRegularFile(file)) continue;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String text = line.strip().replace("\uFEFF", "");
                    int equals = text.indexOf('=');
                    if (equals < 0 || !text.substring(0, equals).trim().equals("DEEPSEEK_API_KEY")) continue;
                    String key = text.substring(equals + 1).trim();
                    if (key.length() >= 2 && ((key.startsWith("\"") && key.endsWith("\""))
                            || (key.startsWith("'") && key.endsWith("'")))) {
                        key = key.substring(1, key.length() - 1).trim();
                    }
                    if (!key.isBlank()) return key;
                }
            } catch (IOException ignored) {
                // Try the other common working directory without disclosing file contents.
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "请先在标讯雷达配置 DeepSeek 搜索密钥");
    }

    private static final class SearchFailure extends Exception {
        final boolean stop;
        SearchFailure(String message, boolean stop) { super(message); this.stop = stop; }
    }
}
