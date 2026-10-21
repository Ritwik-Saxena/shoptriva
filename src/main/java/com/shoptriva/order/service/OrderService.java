package com.shoptriva.order.service;

import com.shoptriva.order.dto.OrderResponse;
import com.shoptriva.order.dto.OrderSummaryResponse;

import java.util.List;

public interface OrderService {

    List<OrderSummaryResponse> getMyOrders();

    OrderResponse getMyOrderById(Long orderId);
}
