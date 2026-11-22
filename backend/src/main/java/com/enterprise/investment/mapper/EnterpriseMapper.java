package com.enterprise.investment.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EnterpriseMapper {
    long count(@Param("keyword") String keyword, @Param("provinces") List<String> provinces,
               @Param("cities") List<String> cities, @Param("industry") String industry,
               @Param("status") String status);

    List<Map<String, Object>> list(@Param("keyword") String keyword, @Param("provinces") List<String> provinces,
                                   @Param("cities") List<String> cities, @Param("industry") String industry,
                                   @Param("status") String status,
                                   @Param("limit") int limit, @Param("offset") long offset);

    Map<String, Object> findById(@Param("id") long id);

    Map<String, Object> findByIdForUpdate(@Param("id") long id);

    Long exists(@Param("id") long id);

    long countCandidates(Map<String, Object> fields);

    int insert(Map<String, Object> values);

    int update(Map<String, Object> values);

    int delete(@Param("id") long id);

    Long findDataSource(@Param("type") String type, @Param("name") String name);

    Long lockDataSource(@Param("id") long id);

    int insertSourceRecord(Map<String, Object> record);

    int insertChange(@Param("enterprise") long enterprise, @Param("sourceRecord") long sourceRecord,
                     @Param("field") String field, @Param("oldValue") String oldValue,
                     @Param("newValue") String newValue, @Param("actor") long actor);
}
