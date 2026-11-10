package com.enterprise.investment.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TagMapper {
    long count(@Param("keyword") String keyword);

    List<Map<String, Object>> list(@Param("keyword") String keyword,
                                   @Param("limit") int limit, @Param("offset") long offset);

    int insert(Map<String, Object> tag);

    Map<String, Object> findById(@Param("id") long id);

    List<Map<String, Object>> assigned(@Param("enterprise") long enterprise);

    Long existsEnterprise(@Param("id") long id);

    Long lockEnterprise(@Param("id") long id);

    long countExisting(@Param("tags") List<Long> tags);

    int clearEnterpriseTags(@Param("enterprise") long enterprise);

    int assignEnterpriseTag(@Param("enterprise") long enterprise, @Param("tag") long tag);
}
