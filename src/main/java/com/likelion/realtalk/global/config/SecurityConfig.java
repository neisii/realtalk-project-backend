package com.likelion.realtalk.global.config;

import com.likelion.realtalk.domain.oauth.handler.OAuth2FailureHandler;
import com.likelion.realtalk.domain.oauth.handler.OAuth2SuccessHandler;
import com.likelion.realtalk.domain.oauth.service.CustomOAuth2UserService;
import com.likelion.realtalk.global.security.jwt.JwtAuthenticationFilter;
import com.likelion.realtalk.global.security.jwt.JwtProvider;
import com.likelion.realtalk.infra.redis.DebateRedisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;
    private final DebateRedisRepository debateRedisRepository;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final OAuth2FailureHandler oAuth2FailureHandler;

    @Value("${frontend.url}")
    private String frontendUrl;

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtProvider, debateRedisRepository);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/oauth2/**", "/login/**",
                                "/api/auth/refresh",
                                "/ws-debate/**",
                                "/actuator/health",
                                "/swagger-ui/**", "/v3/api-docs/**"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/debate-rooms", "/api/debate-rooms/**",
                                "/api/categories",
                                "/api/debate-topics",
                                "/api/debate-results/**",
                                "/api/debate/**"
                        ).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/debate-results/*/vote").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/debate-rooms/match").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/debate-topics").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/debate-topics/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth2 -> {
                    oauth2.userInfoEndpoint(u -> u.userService(customOAuth2UserService));
                    oauth2.successHandler(oAuth2SuccessHandler);
                    oauth2.failureHandler(oAuth2FailureHandler);
                })
                .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(frontendUrl));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
