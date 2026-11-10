package com.enterprise.investment.agent;

import com.enterprise.investment.mapper.AgentEventDispatchMapper;
import com.enterprise.investment.mapper.AgentMapper;
import com.enterprise.investment.security.AccountPrincipal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Registers one run per durable collector event before any model call starts. */
@Service
public class AgentEventDispatchService {
    private final AgentEventDispatchMapper events;
    private final AgentMapper agent;

    AgentEventDispatchService(AgentEventDispatchMapper events, AgentMapper agent) {
        this.events = events;
        this.agent = agent;
    }

    record Prepared(boolean created, String status, String runId, long enterpriseId,
                    String source, String recordType, String title, String reason) {}

    @Transactional
    public Prepared prepare(long eventId, long recordId, AccountPrincipal actor) {
        Map<String, Object> event = events.lockEvent(eventId);
        if (event == null || ((Number) event.get("source_record_id")).longValue() != recordId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "采集事件不存在或来源记录不匹配");
        }
        Map<String, Object> existing = events.findDispatch(eventId);
        if (existing != null) {
            if (((Number) existing.get("owner_id")).longValue() != actor.id() && !actor.hasRole("ADMIN")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "采集事件不存在");
            }
            String runId = (String) existing.get("run_id");
            String status = "SKIPPED";
            if (runId != null) {
                events.expireAbandonedRun(runId);
                Map<String, Object> run = agent.findRun(runId);
                status = run == null ? "ERROR" : String.valueOf(run.get("status"));
            }
            return result(false, status, runId, event, (String) existing.get("reason"));
        }

        String type = String.valueOf(event.get("event_type"));
        String reason = null;
        if (((Number) event.get("is_demo")).intValue() == 1) reason = "演示记录不会自动启动 Agent";
        else if (!"VALID".equals(event.get("validity_status"))) reason = "来源记录已失效或已被新版本替代";
        else if (!type.equals("COLLECTOR_RECORD_CREATED") && !type.equals("COLLECTOR_RECORD_UPDATED")) {
            reason = "该事件不触发招商分析";
        }
        if (reason != null) {
            events.insert(eventId, null, actor.id(), "SKIPPED", reason);
            return result(false, "SKIPPED", null, event, reason);
        }

        String runId = UUID.randomUUID().toString();
        String conversationId = "event-" + runId;
        String title = "事件获客：" + event.get("title");
        agent.upsertConversation(conversationId, actor.id(), title.length() > 60 ? title.substring(0, 60) : title);
        agent.insertRun(runId, conversationId, actor.id(), null, title);
        events.insert(eventId, runId, actor.id(), "DISPATCHED", null);
        return result(true, "RUNNING", runId, event, null);
    }

    private static Prepared result(boolean created, String status, String runId, Map<String, Object> event, String reason) {
        return new Prepared(created, status, runId, ((Number) event.get("enterprise_id")).longValue(),
                String.valueOf(event.get("source")), String.valueOf(event.get("record_type")),
                "事件获客：" + event.get("title"), reason);
    }
}
