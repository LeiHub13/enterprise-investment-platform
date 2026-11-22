package com.enterprise.investment.agent;

import com.enterprise.investment.business.Rows;
import com.enterprise.investment.mapper.AgentMapper;
import com.enterprise.investment.mapper.DataMapper;
import com.enterprise.investment.security.AccountPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/agent")
@PreAuthorize("@ss.hasPermission('agent:use')")
public class AgentProxyController {
    private static final Logger LOG = LoggerFactory.getLogger(AgentProxyController.class);
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper json;
    private final AgentMapper agent;
    private final DataMapper data;
    private final AgentEventDispatchService dispatches;
    private final String baseUrl;

    AgentProxyController(ObjectMapper json, AgentMapper agent, DataMapper data, AgentEventDispatchService dispatches,
                         @Value("${app.agent.base-url:http://127.0.0.1:8766}") String baseUrl) {
        this.json = json;
        this.agent = agent;
        this.data = data;
        this.dispatches = dispatches;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    record ChatInput(@NotBlank @Size(max = 64) String conversationId,
                     @NotNull @Positive Long projectId,
                     @NotBlank @Size(max = 4000) String message,
                     @Size(max = 16) String source) {}

    record DecisionInput(boolean approved) {}

    record EventInput(@NotNull @Positive Long recordId) {}

    record DispatchInput(@NotNull @Positive Long eventId, @NotNull @Positive Long recordId) {}

    @PostMapping("/chat")
    public void chat(@Valid @RequestBody ChatInput input, HttpServletRequest request,
                     HttpServletResponse response, @AuthenticationPrincipal AccountPrincipal actor) {
        String runId = UUID.randomUUID().toString();
        String source = input.source() == null || input.source().isBlank() ? "INTERNAL" : input.source().trim();
        if (!"INTERNAL".equals(source) && !"EXTERNAL".equals(source)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "企业来源参数无效");
        }
        String title = input.message().trim();
        agent.upsertConversation(input.conversationId(), actor.id(),
                title.length() > 60 ? title.substring(0, 60) : title);
        Long owner = agent.conversationOwner(input.conversationId());
        if (owner == null || owner != actor.id()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权使用该 Agent 会话");
        }
        var turns = new java.util.ArrayList<>(agent.conversationHistory(input.conversationId(), actor.id()));
        java.util.Collections.reverse(turns);
        var history = json.createArrayNode();
        for (Map<String, Object> turn : turns) {
            String previousMessage = String.valueOf(turn.get("message"));
            String previousReport = String.valueOf(turn.get("report"));
            history.addObject().put("message", previousMessage.substring(0, Math.min(previousMessage.length(), 2000)))
                    .put("report", previousReport.substring(0, Math.min(previousReport.length(), 2000)));
        }
        agent.insertRun(runId, input.conversationId(), actor.id(), input.projectId(), title);
        var payloadNode = json.createObjectNode()
                .put("runId", runId)
                .put("conversationId", input.conversationId())
                .put("projectId", input.projectId())
                .put("message", title)
                .put("source", source);
        payloadNode.set("history", history);
        tee("/agent/chat", payloadNode.toString(), request, response, runId);
    }

    @GetMapping("/events/records")
    @PreAuthorize("@ss.hasPermission('agent:use') and @ss.hasPermission('enterprise:view')")
    public List<Map<String, Object>> eventRecords() {
        return data.listRecords(null, null, null, 50, 0).stream().map(Rows::view).toList();
    }

    @PostMapping("/events")
    @PreAuthorize("@ss.hasPermission('agent:use') and @ss.hasPermission('enterprise:view')")
    public void events(@Valid @RequestBody EventInput input, HttpServletRequest request,
                       HttpServletResponse response, @AuthenticationPrincipal AccountPrincipal actor) {
        Map<String, Object> record = data.findRecordById(input.recordId());
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "采集记录不存在");
        }
        long enterpriseId = ((Number) record.get("enterprise_id")).longValue();
        String title = "事件获客：" + record.get("title");
        String runId = UUID.randomUUID().toString();
        String conversationId = "event-" + runId;
        agent.upsertConversation(conversationId, actor.id(), title.length() > 60 ? title.substring(0, 60) : title);
        agent.insertRun(runId, conversationId, actor.id(), null, title);
        String payload = json.createObjectNode()
                .put("runId", runId)
                .put("enterpriseId", enterpriseId)
                .put("recordId", input.recordId())
                .put("source", String.valueOf(record.get("source")))
                .put("recordType", String.valueOf(record.get("record_type")))
                .put("title", title)
                .toString();
        tee("/agent/events", payload, request, response, runId);
    }

    @PostMapping("/events/dispatch")
    @PreAuthorize("@ss.hasPermission('agent:use') and @ss.hasPermission('enterprise:view')")
    public void dispatch(@Valid @RequestBody DispatchInput input, HttpServletRequest request,
                         HttpServletResponse response, @AuthenticationPrincipal AccountPrincipal actor) throws IOException {
        var prepared = dispatches.prepare(input.eventId(), input.recordId(), actor);
        if (!prepared.created()) {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            json.writeValue(response.getWriter(), Rows.params("runId", prepared.runId(), "status", prepared.status(),
                    "reason", prepared.reason(), "duplicate", !"SKIPPED".equals(prepared.status())));
            return;
        }
        String payload = json.createObjectNode()
                .put("runId", prepared.runId()).put("enterpriseId", prepared.enterpriseId())
                .put("recordId", input.recordId()).put("source", prepared.source())
                .put("recordType", prepared.recordType()).put("title", prepared.title()).toString();
        tee("/agent/events", payload, request, response, prepared.runId());
    }

    @PostMapping("/tasks/{runId}/decision")
    public void decision(@PathVariable String runId, @Valid @RequestBody DecisionInput input,
                         HttpServletRequest request, HttpServletResponse response,
                         @AuthenticationPrincipal AccountPrincipal actor) {
        Map<String, Object> run = ownedRun(runId, actor);
        if (!"AWAITING_REVIEW".equals(run.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务当前不在待审核状态");
        }
        if (agent.claimReview(runId, input.approved() ? "APPROVING" : "REJECTING") != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "审核已由其他请求处理");
        }
        tee("/agent/tasks/" + runId + "/decision",
                json.createObjectNode().put("approved", input.approved()).toString(),
                request, response, runId);
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "1") int page,
                                         @AuthenticationPrincipal AccountPrincipal actor) {
        if (page < 1 || page > 10000 || (status != null && !List.of("RUNNING", "AWAITING_REVIEW", "APPROVING", "REJECTING", "DONE", "REJECTED", "ERROR").contains(status))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务状态或页码无效");
        }
        return agent.listRuns(actor.id(), status, 20, (page - 1L) * 20).stream().map(Rows::view).toList();
    }

    @GetMapping("/tasks/{runId}/trace")
    public Map<String, Object> trace(@PathVariable String runId, @AuthenticationPrincipal AccountPrincipal actor) {
        Map<String, Object> run = ownedRun(runId, actor);
        return Map.of(
                "run", Rows.view(run),
                "tools", agent.listTools(runId).stream().map(Rows::view).toList(),
                "scores", agent.listScores(runId).stream().map(Rows::view).toList());
    }

    @GetMapping("/tasks/{runId}")
    public Map<String, Object> task(@PathVariable String runId, @AuthenticationPrincipal AccountPrincipal actor) {
        return Rows.view(ownedRun(runId, actor));
    }

    private Map<String, Object> ownedRun(String runId, AccountPrincipal actor) {
        Map<String, Object> run = agent.findRun(runId);
        if (run == null || (((Number) run.get("user_id")).longValue() != actor.id() && !actor.hasRole("ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 任务不存在");
        }
        return run;
    }

    private void tee(String path, String payload, HttpServletRequest request,
                     HttpServletResponse response, String runId) {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        HttpRequest upstream = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", request.getHeader("Authorization") == null ? "" : request.getHeader("Authorization"))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        boolean clientGone = false;
        try {
            var upstreamResponse = client.send(upstream, HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = upstreamResponse.body();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                PrintWriter out = response.getWriter();
                if (upstreamResponse.statusCode() < 200 || upstreamResponse.statusCode() >= 300) {
                    agent.finishRun(runId, "ERROR");
                    out.write("event: error\ndata: {\"message\":\"Agent 服务拒绝请求或任务无法恢复\"}\n\n");
                    out.flush();
                    return;
                }
                String event = "";
                String line;
                boolean terminal = false;
                while ((line = reader.readLine()) != null) {
                    if (!clientGone) {
                        try {
                            out.write(line);
                            out.write('\n');
                            out.flush();
                            clientGone = out.checkError();
                        } catch (RuntimeException error) {
                            clientGone = true;
                        }
                    }
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        persist(runId, event, line.substring(5).trim());
                        terminal |= "done".equals(event) || "error".equals(event);
                        event = "";
                    }
                }
                if (!terminal) {
                    agent.finishRun(runId, "ERROR");
                    if (!clientGone) {
                        out.write("event: error\ndata: {\"message\":\"Agent 连接提前结束，请重试\"}\n\n");
                        out.flush();
                    }
                }
            }
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            agent.finishRun(runId, "ERROR");
            if (!clientGone) {
                try {
                    response.getWriter().write("event: error\ndata: {\"message\":\"Agent 服务连接失败，请确认服务已启动\"}\n\n");
                    response.getWriter().flush();
                } catch (IOException ignored) {
                    // 客户端已断开
                }
            }
        }
    }

    private void persist(String runId, String event, String data) {
        try {
            JsonNode node = json.readTree(data);
            switch (event) {
                case "tool" -> agent.insertToolLog(runId, text(node, "node"), text(node, "tool"),
                        text(node, "argsSummary"), text(node, "resultSummary"),
                        node.path("status").asText("OK"), node.path("durationMs").asInt(0));
                case "score" -> {
                    if (node.hasNonNull("enterpriseId") && node.path("enterpriseId").asLong() > 0) {
                        agent.insertScore(runId, node.path("enterpriseId").asLong(),
                                node.path("projectId").asLong(), node.path("totalScore").asInt(),
                                node.path("industryScore").asInt(), node.path("regionScore").asInt(),
                                node.path("scaleScore").asInt(), node.path("signalScore").asInt(),
                                node.path("completenessScore").asInt(), text(node, "grade"),
                                node.path("sourceType").asText("INTERNAL"),
                                node.path("reasons").toString(),
                                node.hasNonNull("duplicateLeadId") ? node.path("duplicateLeadId").asLong() : null,
                                node.path("fitScore").asInt(), node.path("intentScore").asInt(),
                                node.path("confidenceScore").asInt());
                    }
                }
                case "report" -> agent.setReport(runId, text(node, "text"));
                case "review_required" -> agent.markAwaitingReview(runId);
                case "lead_created" -> agent.appendLeadId(runId, node.path("leadId").asLong());
                case "done" -> {
                    String status = node.path("status").asText("DONE");
                    if ("AWAITING_REVIEW".equals(status)) {
                        agent.markAwaitingReview(runId);
                    } else {
                        agent.finishRun(runId, status);
                    }
                }
                case "error" -> agent.finishRun(runId, "ERROR");
                default -> {
                }
            }
        } catch (IOException | RuntimeException error) {
            LOG.error("Agent audit persistence failed: run={}, event={}, error={}", runId, event, error.getClass().getSimpleName());
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }
}
