package com.shoptriva.checkout.service;

import com.shoptriva.checkout.entity.ReservationStatus;
import com.shoptriva.checkout.entity.StockReservation;
import com.shoptriva.order.entity.Order;

import java.time.Instant;

public interface StockReservationService {

    StockReservation reserve(
            Order order,
            Long productId,
            int quantity,
            Instant expiresAt
    );

    void commitForOrder(Long orderId);

    boolean expireForOrder(Long orderId);

    void releaseForOrder(
            Long orderId,
            ReservationStatus finalStatus
    );
}
