package com.enterprise.investment.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StatisticsMapper {
    long countEnterprises();

    long countLeads(@Param("owner") Long owner);

    long countBusinesses(@Param("owner") Long owner);

    long countWonBusinesses(@Param("owner") Long owner);

    long countContracts(@Param("owner") Long owner);

    BigDecimal sumContractAmount(@Param("owner") Long owner);

    BigDecimal sumReceivablePlan(@Param("owner") Long owner);

    BigDecimal sumReceivableReceived(@Param("owner") Long owner);

    List<Map<String, Object>> stageCounts(@Param("owner") Long owner);

    List<Map<String, Object>> ownerPerformance(@Param("owner") Long owner);
}
