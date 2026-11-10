package com.enterprise.investment.security;

import com.enterprise.investment.mapper.RoleMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, TokenService tokens, AccountDirectory accounts) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new TokenAuthenticationFilter(tokens, accounts), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf", "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/api/openapi", "/api/openapi/**", "/api/swagger-ui.html", "/api/swagger-ui/**", "/swagger-ui/**").permitAll()
                        .requestMatchers("/api/collector/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) ->
                                writeError(response, 401, "请先登录或登录已过期"))
                        .accessDeniedHandler((request, response, failure) ->
                                writeError(response, 403, "没有操作权限")))
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins:}") String configured) {
        List<String> origins = Arrays.stream(configured.split(",")).map(String::trim).filter(value -> !value.isEmpty())
                .map(SecurityConfiguration::origin).toList();
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Collector-Token"));
        cors.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    private static String origin(String value) {
        try {
            URI uri = URI.create(value);
            if ((!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))) {
                throw new IllegalArgumentException();
            }
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("APP_CORS_ALLOWED_ORIGINS 只能包含明确的 http(s) 来源，不能使用通配符或路径");
        }
    }

    @Bean
    ApplicationRunner bootstrapAdmin(AccountDirectory accounts, RoleMapper roles,
                                     @Value("${app.bootstrap-admin.username}") String username,
                                     @Value("${app.bootstrap-admin.password}") String password) {
        return arguments -> {
            roles.ensureUserRolesFromLegacy();
            accounts.bootstrap(username, password);
        };
    }

    private static void writeError(HttpServletResponse response, int status, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
