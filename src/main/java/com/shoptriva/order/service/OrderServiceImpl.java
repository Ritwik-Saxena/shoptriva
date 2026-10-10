package com.shoptriva.order.service;

import com.shoptriva.auth.service.CurrentUserService;
import com.shoptriva.common.exception.OrderNotFoundException;
import com.shoptriva.order.dto.OrderResponse;
import com.shoptriva.order.dto.OrderSummaryResponse;
import com.shoptriva.order.entity.Order;
import com.shoptriva.order.mapper.OrderMapper;
import com.shoptriva.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;

    private final CurrentUserService currentUserService;

    @Override
    public List<OrderSummaryResponse> getMyOrders() {

        Long userId =
                currentUserService.getCurrentUserId();

        List<Order> orders =
                orderRepository
                        .findByUserIdOrderByCreatedAtDesc(userId);

        return orders.stream()
                .map(OrderMapper::toSummaryResponse)
                .toList();
    }

    @Override
    public OrderResponse getMyOrderById(Long orderId) {

        Long userId =
                currentUserService.getCurrentUserId();

        Order order = orderRepository
                .findByIdAndUserId(orderId, userId)
                .orElseThrow(() ->
                        new OrderNotFoundException(orderId)
                );

        return OrderMapper.toResponse(order);
    }
}
