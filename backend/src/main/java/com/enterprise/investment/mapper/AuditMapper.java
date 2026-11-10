package com.enterprise.investment.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuditMapper {
    int record(@Param("userId") long userId, @Param("operation") String operation,
               @Param("resourceType") String resourceType, @Param("resourceId") long resourceId);
}
