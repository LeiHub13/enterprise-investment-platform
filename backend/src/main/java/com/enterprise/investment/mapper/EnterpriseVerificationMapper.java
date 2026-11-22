package com.enterprise.investment.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EnterpriseVerificationMapper {
    Map<String, Object> company(@Param("id") long id);
    long countByNormalizedName(@Param("name") String name);
    int insert(Map<String, Object> values);
    Map<String, Object> lockById(@Param("id") long id);
    Map<String, Object> latestReal(@Param("enterpriseId") long enterpriseId);
    List<Map<String, Object>> history(@Param("enterpriseId") long enterpriseId, @Param("limit") int limit);
}
