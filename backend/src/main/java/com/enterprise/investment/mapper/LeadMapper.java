package com.enterprise.investment.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LeadMapper {
    long count(@Param("scopeOwner") Long scopeOwner, @Param("keyword") String keyword,
               @Param("status") String status, @Param("enterprise") Long enterprise,
               @Param("project") Long project, @Param("owner") Long owner,
               @Param("due") String due);

    List<Map<String, Object>> list(@Param("scopeOwner") Long scopeOwner, @Param("keyword") String keyword,
                                   @Param("status") String status, @Param("enterprise") Long enterprise,
                                   @Param("project") Long project, @Param("owner") Long owner,
                                   @Param("due") String due,
                                   @Param("limit") int limit, @Param("offset") long offset);

    Map<String, Object> findById(@Param("id") long id, @Param("scopeOwner") Long scopeOwner);

    Map<String, Object> findBySourceHash(@Param("hash") String hash);

    Map<String, Object> lockById(@Param("id") long id, @Param("scopeOwner") Long scopeOwner);

    int insert(Map<String, Object> values);

    int insertFromBid(Map<String, Object> values);

    int update(Map<String, Object> values);

    int updateStatus(@Param("id") long id, @Param("status") String status);

    int delete(@Param("id") long id);

    long countFollowups(@Param("id") long id);

    long countFollowupsPage(@Param("id") long id, @Param("scopeOwner") Long scopeOwner);

    List<Map<String, Object>> listFollowups(@Param("id") long id, @Param("scopeOwner") Long scopeOwner,
                                            @Param("limit") int limit, @Param("offset") long offset);

    Long lockContact(@Param("contact") long contact, @Param("enterprise") long enterprise);

    int insertFollowup(Map<String, Object> values);

    int updateNextFollowup(@Param("id") long id, @Param("nextFollowup") LocalDateTime nextFollowup);

    Map<String, Object> findFollowup(@Param("id") long id);
}
