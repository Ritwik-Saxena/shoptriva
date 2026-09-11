package com.shoptriva.auth.security;

import tools.jackson.databind.ObjectMapper;
import com.shoptriva.common.response.ApiErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler
        implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException, ServletException {

        ApiErrorResponse errorResponse =
                ApiErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.FORBIDDEN.value())
                        .error("FORBIDDEN")
                        .message("You do not have permission to access this resource")
                        .path(request.getRequestURI())
                        .build();

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json");

        objectMapper.writeValue(
                response.getOutputStream(),
                errorResponse
        );
    }
}