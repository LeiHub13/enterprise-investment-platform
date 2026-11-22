package com.enterprise.investment.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MonitorMapper {
    List<Map<String, Object>> list(@Param("limit") int limit, @Param("offset") long offset);
    long count();
    Map<String, Object> summary();
    Map<String, Object> find(@Param("id") long id);
    Map<String, Object> lock(@Param("id") long id);
    int insert(Map<String, Object> values);
    int updateSettings(@Param("id") long id, @Param("intervalMinutes") int intervalMinutes,
                       @Param("maxPages") int maxPages, @Param("enabled") boolean enabled, @Param("actor") long actor);
    List<Map<String, Object>> runs(@Param("id") long id, @Param("limit") int limit);
    List<Map<String, Object>> lockDue(@Param("limit") int limit);
    int markAbandoned(@Param("token") String token);
    int lease(@Param("id") long id, @Param("token") String token);
    int insertRun(@Param("id") long id, @Param("token") String token);
    int completeTask(@Param("id") long id, @Param("token") String token, @Param("status") String status);
    int completeRun(@Param("token") String token, @Param("status") String status, @Param("errorCode") String errorCode,
                    @Param("created") int created, @Param("updated") int updated, @Param("pages") int pages,
                    @Param("truncated") boolean truncated);
    int updateCursor(@Param("id") long id, @Param("pages") int pages, @Param("truncated") boolean truncated);
}
