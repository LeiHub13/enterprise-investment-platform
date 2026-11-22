package com.enterprise.investment.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RoleMapper {
    List<Map<String, Object>> listRoles();

    Map<String, Object> findRoleByCode(@Param("code") String code);

    Map<String, Object> findRoleById(@Param("id") long id);

    Long findEnabledRoleIdByCode(@Param("code") String code);

    List<String> permissionCodesOf(@Param("userId") long userId);

    List<Map<String, Object>> menusOf(@Param("userId") long userId);

    List<Map<String, Object>> dataScopesOf(@Param("userId") long userId);

    int insertUserRole(@Param("userId") long userId, @Param("roleId") long roleId);

    int deleteUserRoles(@Param("userId") long userId);

    int ensureUserRolesFromLegacy();

    List<Map<String, Object>> listMenus();

    List<Long> menuIdsOfRole(@Param("roleId") long roleId);

    int deleteRoleMenus(@Param("roleId") long roleId);

    int insertRoleMenu(@Param("roleId") long roleId, @Param("menuId") long menuId);

    List<Map<String, Object>> dataScopesOfRole(@Param("roleId") long roleId);

    int deleteRoleScopes(@Param("roleId") long roleId);

    int insertRoleScope(@Param("roleId") long roleId, @Param("resource") String resource,
                        @Param("scope") String scope);

    long countUsersOfRole(@Param("roleId") long roleId);
}
