package com.shoptriva.order.dto;

import com.shoptriva.order.entity.OrderStatus;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder
public class OrderResponse {

    Long orderId;

    OrderStatus status;

    BigDecimal totalAmount;

    LocalDateTime createdAt;

    List<OrderItemResponse> items;
}
