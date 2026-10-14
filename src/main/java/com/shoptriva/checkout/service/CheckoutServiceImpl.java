package com.shoptriva.checkout.service;

import com.shoptriva.auth.entity.User;
import com.shoptriva.auth.service.CurrentUserService;
import com.shoptriva.cart.entity.Cart;
import com.shoptriva.cart.entity.CartItem;
import com.shoptriva.cart.repository.CartItemRepository;
import com.shoptriva.cart.repository.CartRepository;
import com.shoptriva.common.exception.CheckoutException;
import com.shoptriva.order.dto.OrderResponse;
import com.shoptriva.order.entity.Order;
import com.shoptriva.order.entity.OrderItem;
import com.shoptriva.order.entity.OrderStatus;
import com.shoptriva.order.mapper.OrderMapper;
import com.shoptriva.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CheckoutServiceImpl implements CheckoutService {

    private static final long RESERVATION_MINUTES = 15;

    private final CurrentUserService currentUserService;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final OrderRepository orderRepository;
    private final StockReservationService reservationService;

    @Override
    @Transactional
    public OrderResponse checkout(String idempotencyKey) {

        // STEP 1: Validate the idempotency key
        String checkoutKey = validateCheckoutKey(idempotencyKey);

        // STEP 2: Identify authenticated customer
        User user = currentUserService.getCurrentUser();
        Long userId = user.getId();

        // STEP 3: Return existing order for retry
        var existing = orderRepository
                .findByUserIdAndCheckoutKey(userId, checkoutKey);

        if (existing.isPresent()) {
            return OrderMapper.toResponse(existing.get());
        }

        // STEP 4: Lock customer's cart
        Cart cart = cartRepository
                .findForCheckoutByUserId(userId)
                .orElseThrow(() ->
                        new CheckoutException(
                                "Cart is empty"
                        )
                );

        // STEP 5: Recheck after obtaining the lock
        existing = orderRepository
                .findByUserIdAndCheckoutKey(userId, checkoutKey);

        if (existing.isPresent()) {
            return OrderMapper.toResponse(existing.get());
        }

        // STEP 6: Load cart items
        List<CartItem> cartItems =
                cartItemRepository.findByCartId(cart.getId());

        if (cartItems.isEmpty()) {
            throw new CheckoutException(
                    "Cannot checkout an empty cart"
            );
        }

        // STEP 7: Create pending order
        Order order = Order.builder()
                .user(user)
                .checkoutKey(checkoutKey)
                .status(OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)
                .build();

        BigDecimal totalAmount = BigDecimal.ZERO;

        // STEP 8: Build immutable purchase snapshots
        for (CartItem cartItem : cartItems) {

            int quantity = cartItem.getQuantity();

            if (quantity <= 0) {
                throw new CheckoutException(
                        "Cart item quantity must be positive"
                );
            }

            var product = cartItem.getProduct();

            BigDecimal unitPrice = product.getPrice();

            if (unitPrice == null ||
                    unitPrice.signum() < 0) {

                throw new CheckoutException(
                        "Invalid product price"
                );
            }

            BigDecimal lineTotal = unitPrice.multiply(
                    BigDecimal.valueOf(quantity)
            );

            OrderItem orderItem = OrderItem.builder()
                    .product(product)
                    .productName(product.getName())
                    .quantity(quantity)
                    .unitPrice(unitPrice)
                    .lineTotal(lineTotal)
                    .build();

            order.addItem(orderItem);

            totalAmount = totalAmount.add(lineTotal);
        }

        order.setTotalAmount(totalAmount);

        // STEP 9: Persist order and order items
        Order savedOrder = orderRepository.saveAndFlush(order);

        // STEP 10: Reserve inventory for every product
        Instant expiresAt = Instant.now()
                .plusSeconds(RESERVATION_MINUTES * 60);

        for (OrderItem item : savedOrder.getItems()) {

            reservationService.reserve(
                    savedOrder,
                    item.getProduct().getId(),
                    item.getQuantity(),
                    expiresAt
            );
        }

        // STEP 11: Clear cart only after reservation succeeds
        cartItemRepository.deleteByCartId(cart.getId());

        // STEP 12: Flush pending database changes
        cartItemRepository.flush();

        // STEP 13: Return saved order
        return OrderMapper.toResponse(savedOrder);
    }

    private String validateCheckoutKey(String key) {

        if (key == null || key.isBlank()) {
            throw new CheckoutException(
                    "Idempotency-Key header is required"
            );
        }

        try {
            UUID uuid = UUID.fromString(key);

            String canonicalKey = uuid.toString();

            if (!canonicalKey.equals(key)) {
                throw new CheckoutException(
                        "Idempotency-Key must be a canonical UUID"
                );
            }

            return canonicalKey;

        } catch (IllegalArgumentException ex) {
            throw new CheckoutException(
                    "Idempotency-Key must be a valid UUID"
            );
        }
    }
}
