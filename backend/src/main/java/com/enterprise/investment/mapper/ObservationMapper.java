package com.enterprise.investment.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ObservationMapper {
    long count(@Param("keyword") String keyword, @Param("status") String status,
               @Param("enterpriseId") Long enterpriseId);

    List<Map<String, Object>> list(@Param("keyword") String keyword, @Param("status") String status,
                                   @Param("enterpriseId") Long enterpriseId,
                                   @Param("limit") int limit, @Param("offset") long offset);

    Map<String, Object> findById(@Param("id") long id);

    int insert(Map<String, Object> values);
}