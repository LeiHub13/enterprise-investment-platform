package com.enterprise.investment.collector;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 采集服务专用令牌：仅保护 /api/collector/**，令牌为空时接口整体禁用。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CollectorTokenFilter extends OncePerRequestFilter {
    private final byte[] token;

    CollectorTokenFilter(@Value("${app.collector.token:}") String token) {
        this.token = token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/collector/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader("X-Collector-Token");
        boolean valid = token.length > 0 && provided != null
                && MessageDigest.isEqual(token, provided.trim().getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json; charset=utf-8");
            response.getWriter().write("{\"message\":\"采集服务未启用或令牌无效\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}