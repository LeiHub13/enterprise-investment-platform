package com.enterprise.investment.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentMapper {
    int upsertConversation(@Param("id") String id, @Param("userId") long userId, @Param("title") String title);

    Long conversationOwner(@Param("id") String id);

    List<Map<String, Object>> conversationHistory(@Param("id") String id, @Param("userId") long userId);

    int insertRun(@Param("id") String id, @Param("conversationId") String conversationId,
                  @Param("userId") long userId, @Param("projectId") Long projectId, @Param("message") String message);

    Map<String, Object> findRun(@Param("id") String id);

    List<Map<String, Object>> listRuns(@Param("userId") long userId, @Param("status") String status,
                                       @Param("limit") int limit, @Param("offset") long offset);

    int setReport(@Param("id") String id, @Param("report") String report);

    int setStatus(@Param("id") String id, @Param("status") String status);

    int markAwaitingReview(@Param("id") String id);

    int claimReview(@Param("id") String id, @Param("status") String status);

    int finishRun(@Param("id") String id, @Param("status") String status);

    int appendLeadId(@Param("id") String id, @Param("leadId") long leadId);

    int insertToolLog(@Param("runId") String runId, @Param("node") String node, @Param("tool") String tool,
                      @Param("argsSummary") String argsSummary, @Param("resultSummary") String resultSummary,
                      @Param("status") String status, @Param("durationMs") int durationMs);

    int insertScore(@Param("runId") String runId, @Param("enterpriseId") long enterpriseId,
                    @Param("projectId") long projectId, @Param("totalScore") int totalScore,
                    @Param("industryScore") int industryScore, @Param("regionScore") int regionScore,
                    @Param("scaleScore") int scaleScore, @Param("signalScore") int signalScore,
                    @Param("completenessScore") int completenessScore, @Param("grade") String grade,
                    @Param("sourceType") String sourceType,
                    @Param("reasons") String reasons, @Param("duplicateLeadId") Long duplicateLeadId,
                    @Param("fitScore") int fitScore, @Param("intentScore") int intentScore,
                    @Param("confidenceScore") int confidenceScore);

    List<Map<String, Object>> listTools(@Param("runId") String runId);

    List<Map<String, Object>> listScores(@Param("runId") String runId);
}
