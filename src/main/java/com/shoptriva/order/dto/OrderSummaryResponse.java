package com.shoptriva.order.dto;

import com.shoptriva.order.entity.OrderStatus;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Value
@Builder
public class OrderSummaryResponse {

    Long orderId;

    OrderStatus status;

    BigDecimal totalAmount;

    Integer totalQuantity;

    LocalDateTime createdAt;
}
