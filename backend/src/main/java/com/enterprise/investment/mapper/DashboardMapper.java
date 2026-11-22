package com.enterprise.investment.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DashboardMapper {
    long countEnterprises();

    Map<String, Object> leadCounts(@Param("owner") Long owner);

    long countProjects();

    List<Map<String, Object>> reminders(@Param("owner") Long owner);
}
