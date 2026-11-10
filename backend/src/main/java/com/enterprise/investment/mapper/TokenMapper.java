package com.enterprise.investment.mapper;

import java.time.LocalDateTime;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TokenMapper {
    int insert(@Param("userId") long userId,
               @Param("tokenHash") String tokenHash,
               @Param("expiresAt") LocalDateTime expiresAt,
               @Param("ip") String ip,
               @Param("userAgent") String userAgent);

    Map<String, Object> findValidByHash(@Param("tokenHash") String tokenHash,
                                        @Param("now") LocalDateTime now);

    int revokeByHash(@Param("tokenHash") String tokenHash);

    int revokeAllByUser(@Param("userId") long userId);

    int deleteExpired(@Param("now") LocalDateTime now);
}
