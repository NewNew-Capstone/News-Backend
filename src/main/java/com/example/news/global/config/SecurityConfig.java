package com.example.news.global.config;

import com.example.news.global.jwt.JwtAuthenticationFilter;
import com.example.news.global.jwt.JwtUtil;
import com.example.news.global.jwt.service.TokenBlacklistService;
import com.example.news.global.security.UserDetailsServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwtUtil;//.

    private final UserDetailsServiceImpl userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;

    private static final List<String> EXCLUDE_PATHS = Arrays.asList(
            "/swagger-ui/**", "/swagger-ui.html", "/api-docs/**", "/v3/api-docs/**",
            "/static/**", "/webjars/**",
            "/actuator/**", "/health", "/error", "/favicon.ico",
            "/api/upload/image/**", "/ws-chat/**", "/*.html",
            "/api/v1/users/check", "/api/v1/users/signup", "/api/v1/users/login",
            "/api/v1/users/test-login", "/api/v1/users/refresh",
            "/api/v1/auth/**",
            "/api/v1/enums/**", "/api/v1/home/**", "/ws-chat",
            "/api/v1/files/upload",
            "/api/v1/youtube/recommendations",
            "/api/v1/youtube/*",
            "/api/v1/youtube/*/comments",
            "/api/v1/issues/popular-videos",
            "/api/v1/chat/welcome",
            "/api/v1/comparison/**",
            "/api/videos/compare-on-click"
    );

    private static final List<String> JWT_EXCLUDE_PATHS = EXCLUDE_PATHS.stream()
            .filter(path -> !path.equals("/api/v1/home/**"))
            .toList();

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .formLogin(formLogin -> formLogin.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(EXCLUDE_PATHS.toArray(new String[0])).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(customAuthenticationEntryPoint())
                )
                .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtUtil, userDetailsService, tokenBlacklistService, JWT_EXCLUDE_PATHS);
    }

    @Bean
    public AuthenticationEntryPoint customAuthenticationEntryPoint() {
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"status\":{\"statusCode\":\"C401\",\"message\":\"Unauthorized\",\"description\":\"" + authException.getMessage() + "\"}}"
            );
        };
    }
}
