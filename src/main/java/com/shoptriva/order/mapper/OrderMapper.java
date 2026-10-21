package com.shoptriva.order.mapper;

import com.shoptriva.order.dto.OrderItemResponse;
import com.shoptriva.order.dto.OrderResponse;
import com.shoptriva.order.dto.OrderSummaryResponse;
import com.shoptriva.order.entity.Order;
import com.shoptriva.order.entity.OrderItem;

import java.util.List;

public final class OrderMapper {

    private OrderMapper() {
    }

    public static OrderItemResponse toItemResponse(
            OrderItem item
    ) {

        return OrderItemResponse.builder()
                .orderItemId(item.getId())
                .productId(item.getProduct().getId())
                .productName(item.getProductName())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .lineTotal(item.getLineTotal())
                .build();
    }

    public static OrderResponse toResponse(
            Order order
    ) {

        List<OrderItemResponse> itemResponses =
                order.getItems()
                        .stream()
                        .map(OrderMapper::toItemResponse)
                        .toList();

        return OrderResponse.builder()
                .orderId(order.getId())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .createdAt(order.getCreatedAt())
                .items(itemResponses)
                .build();
    }

    public static OrderSummaryResponse toSummaryResponse(
            Order order
    ) {

        int totalQuantity =
                order.getItems()
                        .stream()
                        .mapToInt(OrderItem::getQuantity)
                        .sum();

        return OrderSummaryResponse.builder()
                .orderId(order.getId())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .totalQuantity(totalQuantity)
                .createdAt(order.getCreatedAt())
                .build();
    }
}
