package com.shoptriva.auth.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        http
                // Stateless JWT authentication
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                // Custom JSON responses for 401 and 403
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )

                .authorizeHttpRequests(auth -> auth

                        // =====================================
                        // PUBLIC APIs
                        // =====================================

                        .requestMatchers("/api/auth/**")
                        .permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/products/**",
                                "/api/categories/**"
                        ).permitAll()


                        // =====================================
                        // ADMIN - CATALOG MANAGEMENT
                        // =====================================

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/products/**",
                                "/api/categories/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/products/**",
                                "/api/categories/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.DELETE,
                                "/api/products/**",
                                "/api/categories/**"
                        ).hasRole("ADMIN")


                        // =====================================
                        // ADMIN APIs
                        // =====================================

                        .requestMatchers("/api/admin/**")
                        .hasRole("ADMIN")


                        // =====================================
                        // CUSTOMER APIs
                        // =====================================

                        .requestMatchers("/api/cart/**")
                        .hasRole("CUSTOMER")

                        .requestMatchers("/api/wishlist/**")
                        .hasRole("CUSTOMER")

                        // Customer order history and details
                        .requestMatchers(
                                "/api/orders",
                                "/api/orders/**"
                        ).hasRole("CUSTOMER")


                        // =====================================
                        // INVENTORY - READ
                        // =====================================

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/inventory/**"
                        ).hasAnyRole("CUSTOMER", "ADMIN")


                        // =====================================
                        // INVENTORY - ADMIN MANAGEMENT
                        // =====================================

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/inventory/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/inventory/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/inventory/**"
                        ).hasRole("ADMIN")


                        // =====================================
                        // FALLBACK
                        // =====================================

                        .anyRequest()
                        .authenticated()
                )

                // Authenticate JWT before username/password filter
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}
