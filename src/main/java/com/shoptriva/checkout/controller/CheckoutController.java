package com.shoptriva.checkout.controller;

import com.shoptriva.checkout.service.CheckoutService;
import com.shoptriva.order.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/checkout")
@RequiredArgsConstructor
public class CheckoutController {

    private final CheckoutService checkoutService;

    @PostMapping
    public ResponseEntity<OrderResponse> checkout(
            @RequestHeader(
                    value = "Idempotency-Key",
                    required = false
            ) String idempotencyKey
    ) {

        OrderResponse response =
                checkoutService.checkout(idempotencyKey);

        return ResponseEntity.ok(response);
    }
}
