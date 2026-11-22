package com.enterprise.investment.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DataMapper {
    Long findCompany(@Param("id") long id);

    int insertSourceCursor(@Param("identity") String identity, @Param("enterpriseId") long enterpriseId);

    Map<String, Object> lockSourceCursor(@Param("identity") String identity);

    int updateSourceCursor(@Param("identity") String identity, @Param("recordId") long recordId,
                           @Param("version") int version, @Param("fingerprint") String fingerprint,
                           @Param("sourceUpdatedAt") LocalDateTime sourceUpdatedAt);

    int attachLegacyIdentity(@Param("id") long id, @Param("identity") String identity);

    int insertVersion(Map<String, Object> values);

    int supersedeRecord(@Param("id") long id);

    Map<String, Object> findSnapshotByHash(@Param("contentHash") String contentHash);

    Map<String, Object> findRecordById(@Param("id") long id);

    int insertSignal(@Param("enterpriseId") long enterpriseId, @Param("signalType") String signalType,
                      @Param("title") String title, @Param("detail") String detail,
                      @Param("sourceName") String sourceName, @Param("sourceUrl") String sourceUrl,
                      @Param("observedAt") LocalDate observedAt, @Param("strength") int strength,
                      @Param("sourceRecordId") long sourceRecordId, @Param("confidence") double confidence,
                      @Param("expiresAt") LocalDateTime expiresAt);

    int insertOutbox(@Param("recordId") long recordId, @Param("eventType") String eventType);

    List<Long> lockExpiringRecords(@Param("limit") int limit);

    int expireRecord(@Param("id") long id);

    List<Map<String, Object>> lockOutboxBatch(@Param("limit") int limit);

    int leaseOutbox(@Param("id") long id, @Param("token") String token);

    int ackOutbox(@Param("id") long id, @Param("token") String token);

    int retryOutbox(@Param("id") long id, @Param("token") String token);

    List<Map<String, Object>> resolveCompany(@Param("uscc") String uscc, @Param("name") String name);

    List<Map<String, Object>> sourceStatus();

    long countRecords(@Param("source") String source, @Param("type") String type,
                      @Param("keyword") String keyword);

    List<Map<String, Object>> listRecords(@Param("source") String source, @Param("type") String type,
                                          @Param("keyword") String keyword,
                                          @Param("limit") int limit, @Param("offset") long offset);

    long countSignals(@Param("keyword") String keyword);

    List<Map<String, Object>> listSignals(@Param("keyword") String keyword,
                                          @Param("limit") int limit, @Param("offset") long offset);

    Map<String, Object> collectorSummary();
}
