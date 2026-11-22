package com.enterprise.investment.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContactMapper {
    long count(@Param("enterprise") long enterprise, @Param("keyword") String keyword);

    List<Map<String, Object>> list(@Param("enterprise") long enterprise, @Param("keyword") String keyword,
                                   @Param("limit") int limit, @Param("offset") long offset);

    Map<String, Object> findById(@Param("enterprise") long enterprise, @Param("id") long id);

    Map<String, Object> findByIdForUpdate(@Param("enterprise") long enterprise, @Param("id") long id);

    int insert(Map<String, Object> values);

    int updateBasic(@Param("id") long id, @Param("name") String name, @Param("position") String position);

    int updateSensitive(@Param("id") long id, @Param("phone") byte[] phone, @Param("email") byte[] email);

    int delete(@Param("id") long id);

    Long lockFollowup(@Param("contact") long contact);

    Long findAccessibleEnterprise(@Param("enterprise") long enterprise, @Param("owner") Long owner);
}
