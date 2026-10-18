package com.shoptriva.checkout.controller;

import com.shoptriva.common.response.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;
import java.time.LocalDateTime;

@RestControllerAdvice(assignableTypes = CheckoutController.class)
public class CheckoutExceptionHandler {

    @ExceptionHandler(CannotAcquireLockException.class)
    public ResponseEntity<ApiErrorResponse> handleCannotAcquireLock(
            CannotAcquireLockException ex,
            HttpServletRequest request
    ) {
        if (!isMySqlDeadlock(ex)) {
            // MVC continues with the remaining resolvers, not another advice method.
            // GlobalExceptionHandler has no mapping for these unmatched lock failures.
            throw ex;
        }

        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiErrorResponse.builder()
                        .timestamp(LocalDateTime.now())
                        .status(HttpStatus.CONFLICT.value())
                        .error("CONCURRENT_UPDATE_CONFLICT")
                        .message("Checkout conflicted with another request. "
                                + "Please retry using the same Idempotency-Key.")
                        .path(request.getRequestURI())
                        .build());
    }

    private boolean isMySqlDeadlock(CannotAcquireLockException exception) {
        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && sql.getErrorCode() == 1213 && "40001".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
