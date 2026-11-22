package com.enterprise.investment.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentLeadMapper {
    Map<String, Object> lockRun(@Param("runId") String runId);
    Map<String, Object> findScore(@Param("runId") String runId, @Param("enterpriseId") long enterpriseId,
                                  @Param("projectId") long projectId);
    Long findCreatedLead(@Param("runId") String runId, @Param("enterpriseId") long enterpriseId,
                         @Param("projectId") long projectId);
    int reserve(@Param("runId") String runId, @Param("enterpriseId") long enterpriseId,
                @Param("projectId") long projectId);
    int attachLead(@Param("runId") String runId, @Param("enterpriseId") long enterpriseId,
                   @Param("projectId") long projectId, @Param("leadId") long leadId);
    int insertEvidence(@Param("leadId") long leadId, @Param("enterpriseId") long enterpriseId,
                       @Param("signalIds") List<Long> signalIds);
    List<Map<String, Object>> listEvidence(@Param("leadId") long leadId);
    Map<String, Object> findLeadAnalysis(@Param("leadId") long leadId);
}
