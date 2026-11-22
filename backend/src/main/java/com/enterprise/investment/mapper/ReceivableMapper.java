package com.enterprise.investment.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReceivableMapper {
    long count(@Param("scopeOwner") Long scopeOwner, @Param("keyword") String keyword,
               @Param("status") String status, @Param("contract") Long contract,
               @Param("customer") Long customer, @Param("owner") Long owner);

    List<Map<String, Object>> list(@Param("scopeOwner") Long scopeOwner, @Param("keyword") String keyword,
                                   @Param("status") String status, @Param("contract") Long contract,
                                   @Param("customer") Long customer, @Param("owner") Long owner,
                                   @Param("limit") int limit, @Param("offset") long offset);

    Map<String, Object> findById(@Param("id") long id, @Param("scopeOwner") Long scopeOwner);

    Map<String, Object> lockById(@Param("id") long id, @Param("scopeOwner") Long scopeOwner);

    int insert(Map<String, Object> values);

    int update(Map<String, Object> values);

    int delete(@Param("id") long id);

    long countByContract(@Param("contract") long contract);

    long countByCustomer(@Param("customer") long customer, @Param("scopeOwner") Long scopeOwner);

    List<Map<String, Object>> listByCustomer(@Param("customer") long customer,
                                             @Param("scopeOwner") Long scopeOwner,
                                             @Param("limit") int limit);

    BigDecimal sumPlanByCustomer(@Param("customer") long customer, @Param("scopeOwner") Long scopeOwner);

    BigDecimal sumReceivedByCustomer(@Param("customer") long customer, @Param("scopeOwner") Long scopeOwner);
}
