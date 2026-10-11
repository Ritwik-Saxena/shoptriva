package com.shoptriva.order.controller;

import com.shoptriva.order.dto.OrderResponse;
import com.shoptriva.order.dto.OrderSummaryResponse;
import com.shoptriva.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // Get all orders belonging to the logged-in customer
    @GetMapping
    public List<OrderSummaryResponse> getMyOrders() {

        return orderService.getMyOrders();
    }

    // Get one order belonging to the logged-in customer
    @GetMapping("/{orderId}")
    public OrderResponse getMyOrderById(
            @PathVariable Long orderId
    ) {

        return orderService.getMyOrderById(orderId);
    }
}
