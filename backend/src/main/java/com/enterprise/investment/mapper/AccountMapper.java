package com.enterprise.investment.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AccountMapper {
    Map<String, Object> findByUsername(@Param("username") String username);

    List<Map<String, Object>> list(@Param("administrator") boolean administrator);

    Map<String, Object> findCurrent(@Param("id") long id);

    int insert(Map<String, Object> user);

    List<Map<String, Object>> lockAdministrators();

    Map<String, Object> lockById(@Param("id") long id);

    Long findAssignable(@Param("id") long id);

    int updatePassword(@Param("id") long id, @Param("passwordHash") String passwordHash);

    int updateEnabled(@Param("id") long id, @Param("enabled") boolean enabled);

    int updateRole(@Param("id") long id, @Param("role") String role);

    long countEnabledAdmins();
}
