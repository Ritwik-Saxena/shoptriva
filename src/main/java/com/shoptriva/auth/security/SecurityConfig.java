
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
                // =====================================
                // STATELESS JWT AUTHENTICATION
                // =====================================

                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                // =====================================
                // CUSTOM 401 AND 403 ERROR HANDLING
                // =====================================

                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(
                                authenticationEntryPoint
                        )
                        .accessDeniedHandler(
                                accessDeniedHandler
                        )
                )

                // =====================================
                // ENDPOINT AUTHORIZATION RULES
                // =====================================

                .authorizeHttpRequests(auth -> auth

                        // =====================================
                        // PUBLIC APIs
                        // =====================================

                        .requestMatchers("/api/auth/**")
                        .permitAll()

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/products",
                                "/api/products/**",
                                "/api/categories",
                                "/api/categories/**"
                        ).permitAll()

                        // =====================================
                        // ADMIN - CATALOG MANAGEMENT
                        // =====================================

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/products",
                                "/api/products/**",
                                "/api/categories",
                                "/api/categories/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/products",
                                "/api/products/**",
                                "/api/categories",
                                "/api/categories/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.DELETE,
                                "/api/products",
                                "/api/products/**",
                                "/api/categories",
                                "/api/categories/**"
                        ).hasRole("ADMIN")

                        // Deny unsupported catalog operations
                        .requestMatchers(
                                "/api/products",
                                "/api/products/**",
                                "/api/categories",
                                "/api/categories/**"
                        ).denyAll()

                        // =====================================
                        // ADMIN APIs
                        // =====================================

                        .requestMatchers("/api/admin/**")
                        .hasRole("ADMIN")

                        // =====================================
                        // CUSTOMER - CART
                        // =====================================

                        .requestMatchers(
                                "/api/cart",
                                "/api/cart/**"
                        ).hasRole("CUSTOMER")

                        // =====================================
                        // CUSTOMER - WISHLIST
                        // =====================================

                        .requestMatchers(
                                "/api/wishlist",
                                "/api/wishlist/**"
                        ).hasRole("CUSTOMER")

                        // =====================================
                        // CUSTOMER - ORDERS
                        // =====================================

                        .requestMatchers(
                                "/api/orders",
                                "/api/orders/**"
                        ).hasRole("CUSTOMER")

                        // =====================================
                        // CUSTOMER - CHECKOUT
                        // =====================================

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/checkout"
                        ).hasRole("CUSTOMER")

                        // Reject other checkout operations
                        .requestMatchers(
                                "/api/checkout",
                                "/api/checkout/**"
                        ).denyAll()

                        // =====================================
                        // INVENTORY - READ
                        // CUSTOMER + ADMIN
                        // =====================================

                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/inventory",
                                "/api/inventory/**"
                        ).hasAnyRole("CUSTOMER", "ADMIN")

                        // =====================================
                        // INVENTORY - WRITE
                        // ADMIN ONLY
                        // =====================================

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/inventory",
                                "/api/inventory/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/inventory",
                                "/api/inventory/**"
                        ).hasRole("ADMIN")

                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/inventory",
                                "/api/inventory/**"
                        ).hasRole("ADMIN")

                        // Reject all other inventory operations
                        .requestMatchers(
                                "/api/inventory",
                                "/api/inventory/**"
                        ).denyAll()

                        // =====================================
                        // FALLBACK - AUTHENTICATED USERS
                        // =====================================

                        .anyRequest()
                        .authenticated()
                )

                // =====================================
                // JWT FILTER
                // =====================================

                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}
