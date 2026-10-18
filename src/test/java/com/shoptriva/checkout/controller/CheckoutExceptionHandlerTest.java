package com.shoptriva.checkout.controller;

import com.shoptriva.checkout.service.CheckoutService;
import com.shoptriva.common.exception.GlobalExceptionHandler;
import com.shoptriva.common.exception.InsufficientStockException;
import jakarta.servlet.ServletException;
import org.hibernate.exception.LockAcquisitionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CheckoutExceptionHandlerTest {

    private CheckoutService checkoutService;
    private MockMvc mvc;
    private String checkoutKey;

    @BeforeEach
    void setUp() {
        checkoutService = mock(CheckoutService.class);
        checkoutKey = UUID.randomUUID().toString();
        mvc = MockMvcBuilders.standaloneSetup(new CheckoutController(checkoutService))
                .setControllerAdvice(new CheckoutExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    void checkout_shouldReturnSafeConflictForNestedMySqlDeadlockWithoutRetrying() throws Exception {
        when(checkoutService.checkout(checkoutKey)).thenThrow(deadlock());

        var response = assertConflict(checkout(), "CONCURRENT_UPDATE_CONFLICT",
                "Checkout conflicted with another request. Please retry using the same Idempotency-Key.")
                .andReturn().getResponse();

        assertThat(response.getContentAsString()).doesNotContain(
                "inventories", "SQLState", "40001", "1213", "Exception", "Deadlock found");
        verify(checkoutService).checkout(checkoutKey);
        verifyNoMoreInteractions(checkoutService);
    }

    @ParameterizedTest
    @MethodSource("nonDeadlockLockFailures")
    void checkout_shouldLeaveNonDeadlockLockFailuresUnresolved(CannotAcquireLockException failure) {
        when(checkoutService.checkout(checkoutKey)).thenThrow(failure);

        // Standalone MVC has no Boot /error dispatch: unresolved exceptions propagate.
        // This proves preservation of the original failure, not delegation to global advice.
        assertThatThrownBy(this::checkout).isInstanceOf(ServletException.class)
                .cause().isSameAs(failure);
        verify(checkoutService).checkout(checkoutKey);
        verifyNoMoreInteractions(checkoutService);
    }

    static Stream<CannotAcquireLockException> nonDeadlockLockFailures() {
        return Stream.of(
                new CannotAcquireLockException("Lock wait timeout", new SQLException("timeout", "HY000", 1205)),
                new CannotAcquireLockException("Wrong code", new SQLException("timeout", "40001", 1205)),
                new CannotAcquireLockException("Wrong state", new SQLException("failure", "HY000", 1213)),
                new CannotAcquireLockException("Missing state", new SQLException("failure", null, 1213)),
                new CannotAcquireLockException("State alone", new SQLException("failure", "40001", 0)),
                new CannotAcquireLockException("No SQL cause")
        );
    }

    @Test
    void checkout_shouldPreserveGlobalOptimisticLockResponse() throws Exception {
        when(checkoutService.checkout(checkoutKey)).thenThrow(
                new ObjectOptimisticLockingFailureException("Inventory", 1L));

        assertConflict(checkout(), "CONCURRENT_UPDATE_CONFLICT",
                "Inventory was modified by another request. Please retry.");
    }

    @Test
    void checkout_shouldPreserveGlobalInsufficientStockResponse() throws Exception {
        when(checkoutService.checkout(checkoutKey)).thenThrow(new InsufficientStockException(1L, 2, 1));

        assertConflict(checkout(), "INSUFFICIENT_STOCK",
                "Insufficient stock for product id 1. Requested: 2, available: 1");
    }

    @Test
    void checkout_shouldNotConvertOtherDatabaseExceptionTypesEvenWithDeadlockCause() {
        var failure = new DataIntegrityViolationException("Unrelated failure", deadlock().getCause());
        when(checkoutService.checkout(checkoutKey)).thenThrow(failure);

        assertThatThrownBy(this::checkout).isInstanceOf(ServletException.class)
                .cause().isSameAs(failure);
    }

    @Test
    void otherController_shouldNotReceiveCheckoutDeadlockHandling() {
        var failure = deadlock();
        MockMvc otherMvc = MockMvcBuilders.standaloneSetup(new OtherController(failure))
                .setControllerAdvice(new CheckoutExceptionHandler(), new GlobalExceptionHandler())
                .build();

        assertThatThrownBy(() -> otherMvc.perform(post("/other")))
                .isInstanceOf(ServletException.class).cause().isSameAs(failure);
    }

    private ResultActions checkout() throws Exception {
        return mvc.perform(post("/api/checkout").header("Idempotency-Key", checkoutKey));
    }

    private ResultActions assertConflict(ResultActions result, String error, String message) throws Exception {
        return result.andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*").value(hasSize(5)))
                .andExpect(jsonPath("$.timestamp").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?")))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value(error))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.path").value("/api/checkout"));
    }

    private static CannotAcquireLockException deadlock() {
        SQLException sql = new SQLException("Deadlock found on inventories", "40001", 1213);
        return new CannotAcquireLockException("update inventories failed",
                new LockAcquisitionException("Database details", sql, "update inventories ..."));
    }

    @RestController
    static class OtherController {
        private final CannotAcquireLockException failure;

        OtherController(CannotAcquireLockException failure) {
            this.failure = failure;
        }

        @PostMapping("/other")
        public void fail() {
            throw failure;
        }
    }
}
