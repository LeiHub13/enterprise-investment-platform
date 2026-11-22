package com.enterprise.investment.mapper;

import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentEventDispatchMapper {
    Map<String, Object> lockEvent(@Param("id") long id);

    Map<String, Object> findDispatch(@Param("id") long id);

    int insert(@Param("eventId") long eventId, @Param("runId") String runId, @Param("ownerId") long ownerId,
               @Param("disposition") String disposition, @Param("reason") String reason);

    int expireAbandonedRun(@Param("runId") String runId);
}
