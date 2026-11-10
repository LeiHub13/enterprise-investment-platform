package com.enterprise.investment.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BusinessMapper {
    long count(@Param("scopeOwner") Long scopeOwner, @Param("keyword") String keyword,
               @Param("stage") String stage, @Param("customer") Long customer,
               @Param("project") Long project, @Param("owner") Long owner);

    List<Map<String, Object>> list(@Param("scopeOwner") Long scopeOwner, @Param("keyword") String keyword,
                                   @Param("stage") String stage, @Param("customer") Long customer,
                                   @Param("project") Long project, @Param("owner") Long owner,
                                   @Param("limit") int limit, @Param("offset") long offset);

    Map<String, Object> findById(@Param("id") long id, @Param("scopeOwner") Long scopeOwner);

    Map<String, Object> lockById(@Param("id") long id, @Param("scopeOwner") Long scopeOwner);

    Map<String, Object> findByLeadId(@Param("lead") long lead);

    Map<String, Object> findByLeadIdForUpdate(@Param("lead") long lead);

    Map<String, Object> findBySourceHash(@Param("hash") String hash);

    int insert(Map<String, Object> values);

    int insertFromBid(Map<String, Object> values);

    int update(Map<String, Object> values);

    int delete(@Param("id") long id);

    int markWon(@Param("id") long id, @Param("dealDate") LocalDate dealDate);

    long countByCustomer(@Param("customer") long customer, @Param("scopeOwner") Long scopeOwner);

    List<Map<String, Object>> listByCustomer(@Param("customer") long customer,
                                             @Param("scopeOwner") Long scopeOwner,
                                             @Param("limit") int limit);

    BigDecimal sumAmountByCustomer(@Param("customer") long customer, @Param("scopeOwner") Long scopeOwner);
}
