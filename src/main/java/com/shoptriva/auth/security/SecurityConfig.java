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
            HttpSecurity http) throws Exception {

        http
                // We are using JWT, so CSRF is not needed here
                .csrf(AbstractHttpConfigurer::disable)

                // Disable default login page
                .formLogin(AbstractHttpConfigurer::disable)

                // Disable browser popup/basic authentication
                .httpBasic(AbstractHttpConfigurer::disable)

                // JWT means server does not maintain session
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                // Our custom JSON 401 / 403 handlers
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )

                // Authorization rules
                .authorizeHttpRequests(auth -> auth

                        // Public authentication APIs
                        .requestMatchers(
                                "/api/auth/**"
                        ).permitAll()

                        // Anyone can view products/categories
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/products/**",
                                "/api/categories/**"
                        ).permitAll()

                        // Only ADMIN can create
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/products/**",
                                "/api/categories/**"
                        ).hasRole("ADMIN")

                        // Only ADMIN can update
                        .requestMatchers(
                                HttpMethod.PUT,
                                "/api/products/**",
                                "/api/categories/**"
                        ).hasRole("ADMIN")

                        // Only ADMIN can delete
                        .requestMatchers(
                                HttpMethod.DELETE,
                                "/api/products/**",
                                "/api/categories/**"
                        ).hasRole("ADMIN")

                        // Admin-only APIs
                        .requestMatchers("/api/admin/**")
                        .hasRole("ADMIN")

                        // Customer-only APIs
                        .requestMatchers("/api/cart/**")
                        .hasRole("CUSTOMER")

                        .requestMatchers("/api/wishlist/**")
                        .hasRole("CUSTOMER")

                        // Any future endpoint must at least be authenticated
                        .anyRequest()
                        .authenticated()
                )

                // Run our JWT filter before Spring's username/password filter
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}
