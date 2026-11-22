package com.enterprise.investment.mapper;

import java.time.LocalDateTime;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ImportMapper {
    int insertBatch(Map<String, Object> batch);

    int incrementSuccess(@Param("batch") long batch);

    int incrementFail(@Param("batch") long batch);

    int finish(@Param("batch") long batch, @Param("status") String status,
               @Param("finished") LocalDateTime finished);
}
