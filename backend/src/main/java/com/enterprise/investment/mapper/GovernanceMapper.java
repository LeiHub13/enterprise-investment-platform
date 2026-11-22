package com.enterprise.investment.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GovernanceMapper {
    long countHistory(@Param("enterprise") long enterprise);

    List<Map<String, Object>> history(@Param("enterprise") long enterprise,
                                      @Param("limit") int limit, @Param("offset") long offset);

    long countImports();

    List<Map<String, Object>> imports(@Param("limit") int limit, @Param("offset") long offset);

    Long existsImportBatch(@Param("id") long id);

    long countImportRows(@Param("id") long id, @Param("failuresOnly") boolean failuresOnly);

    List<Map<String, Object>> importRows(@Param("id") long id, @Param("failuresOnly") boolean failuresOnly,
                                         @Param("limit") int limit, @Param("offset") long offset);
}
