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

                // JWT API → CSRF protection not required
                .csrf(AbstractHttpConfigurer::disable)

                // Disable Spring's default login page
                .formLogin(AbstractHttpConfigurer::disable)

                // Disable HTTP Basic authentication
                .httpBasic(AbstractHttpConfigurer::disable)

                // Do not create server-side sessions
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

                // Authorization rules
                .authorizeHttpRequests(auth -> auth

                        // =====================================================
                        // PUBLIC APIs
                        // =====================================================

                        // Authentication APIs
                        .requestMatchers(
                                "/api/auth/**"
                        ).permitAll()

                        // Anyone can view products and categories
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/products/**",
                                "/api/categories/**"
                        ).permitAll()


                        // =====================================================
                        // ADMIN - PRODUCT / CATEGORY MANAGEMENT
                        // =====================================================

                        // Create products/categories
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/products/**",
                                "/api/categories/**"
                        ).hasRole("ADMIN")

                        // Update products/categories
                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/products/**",
                                "/api/categories/**"
                        ).hasRole("ADMIN")

                        // Delete products/categories
                        .requestMatchers(
                                HttpMethod.DELETE,
                                "/api/products/**",
                                "/api/categories/**"
                        ).hasRole("ADMIN")


                        // =====================================================
                        // ADMIN APIs
                        // =====================================================

                        .requestMatchers(
                                "/api/admin/**"
                        ).hasRole("ADMIN")


                        // =====================================================
                        // CUSTOMER APIs
                        // =====================================================

                        // Cart belongs only to customers
                        .requestMatchers(
                                "/api/cart/**"
                        ).hasRole("CUSTOMER")

                        // Wishlist belongs only to customers
                        .requestMatchers(
                                "/api/wishlist/**"
                        ).hasRole("CUSTOMER")


                        // =====================================================
                        // INVENTORY APIs
                        // =====================================================

                        // CUSTOMER and ADMIN can view inventory
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/inventory/**"
                        ).hasAnyRole(
                                "CUSTOMER",
                                "ADMIN"
                        )

                        // Only ADMIN can create inventory
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/inventory/**"
                        ).hasRole("ADMIN")

                        // Only ADMIN can set exact stock
                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/inventory/**"
                        ).hasRole("ADMIN")

                        // Only ADMIN can increase/decrease stock
                        .requestMatchers(
                                HttpMethod.PATCH,
                                "/api/inventory/**"
                        ).hasRole("ADMIN")


                        // =====================================================
                        // FALLBACK
                        // =====================================================

                        // Any endpoint not matched above requires authentication
                        .anyRequest()
                        .authenticated()
                )

                // JWT authentication must run before Spring's
                // username/password authentication filter
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}
