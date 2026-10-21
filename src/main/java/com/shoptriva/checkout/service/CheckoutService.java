package com.shoptriva.checkout.service;

import com.shoptriva.order.dto.OrderResponse;

public interface CheckoutService {

    OrderResponse checkout(String idempotencyKey);
}
