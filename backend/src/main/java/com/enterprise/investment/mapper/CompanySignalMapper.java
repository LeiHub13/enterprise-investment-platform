package com.enterprise.investment.mapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CompanySignalMapper {
    List<Map<String, Object>> listByEnterprise(@Param("enterpriseId") long enterpriseId);

    int insert(@Param("enterpriseId") long enterpriseId, @Param("signalType") String signalType,
               @Param("title") String title, @Param("detail") String detail,
               @Param("sourceName") String sourceName, @Param("sourceUrl") String sourceUrl,
               @Param("observedAt") LocalDate observedAt, @Param("strength") int strength);
}