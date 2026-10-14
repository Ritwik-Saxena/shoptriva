
package com.shoptriva.checkout.service;

import com.shoptriva.auth.entity.User;
import com.shoptriva.auth.service.CurrentUserService;
import com.shoptriva.cart.entity.Cart;
import com.shoptriva.cart.entity.CartItem;
import com.shoptriva.cart.repository.CartItemRepository;
import com.shoptriva.cart.repository.CartRepository;
import com.shoptriva.catalog.entity.Product;
import com.shoptriva.common.exception.CheckoutException;
import com.shoptriva.common.exception.InsufficientStockException;
import com.shoptriva.order.dto.OrderResponse;
import com.shoptriva.order.entity.Order;
import com.shoptriva.order.entity.OrderItem;
import com.shoptriva.order.entity.OrderStatus;
import com.shoptriva.order.repository.OrderRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CheckoutServiceImplTest {

    private static final String CHECKOUT_KEY =
            "6a02f101-5a19-44a4-91d8-6762fa390b10";

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private StockReservationService reservationService;

    @InjectMocks
    private CheckoutServiceImpl checkoutService;

    private User user;
    private Cart cart;
    private Product product;
    private CartItem cartItem;

    @BeforeEach
    void setUp() {

        user = User.builder()
                .id(1L)
                .name("Test Customer")
                .build();

        cart = Cart.builder()
                .id(10L)
                .user(user)
                .build();

        product = Product.builder()
                .id(1L)
                .name("Sony Head Phone")
                .price(new BigDecimal("9999.00"))
                .build();

        cartItem = CartItem.builder()
                .id(100L)
                .cart(cart)
                .product(product)
                .quantity(2)
                .build();
    }

    // ============================================
    // TEST 1: SUCCESSFUL CHECKOUT
    // ============================================

    @Test
    void checkout_shouldCreateOrderAndReserveStock() {

        mockFreshCheckout();

        OrderResponse response =
                checkoutService.checkout(CHECKOUT_KEY);

        assertThat(response.getOrderId()).isEqualTo(101L);

        assertThat(response.getStatus())
                .isEqualTo(OrderStatus.PENDING);

        assertThat(response.getTotalAmount())
                .isEqualByComparingTo("19998.00");

        assertThat(response.getItems()).hasSize(1);

        assertThat(response.getItems().get(0).getProductName())
                .isEqualTo("Sony Head Phone");

        assertThat(response.getItems().get(0).getQuantity())
                .isEqualTo(2);

        assertThat(response.getItems().get(0).getUnitPrice())
                .isEqualByComparingTo("9999.00");

        // Capture the order passed to the repository.
        ArgumentCaptor<Order> orderCaptor =
                ArgumentCaptor.forClass(Order.class);

        verify(orderRepository)
                .saveAndFlush(orderCaptor.capture());

        Order savedOrder = orderCaptor.getValue();

        assertThat(savedOrder.getCheckoutKey())
                .isEqualTo(CHECKOUT_KEY);

        assertThat(savedOrder.getItems()).hasSize(1);

        // Verify inventory reservation was requested.
        verify(reservationService).reserve(
                same(savedOrder),
                eq(1L),
                eq(2),
                any(Instant.class)
        );

        // Verify cart deletion was requested.
        verify(cartItemRepository).deleteByCartId(10L);

        verify(cartItemRepository).flush();
    }

    // ============================================
    // TEST 2: IDEMPOTENCY REPLAY
    // ============================================

    @Test
    void checkout_shouldReturnExistingOrderForSameKey() {

        Order existingOrder = Order.builder()
                .id(101L)
                .user(user)
                .checkoutKey(CHECKOUT_KEY)
                .status(OrderStatus.PENDING)
                .totalAmount(new BigDecimal("19998.00"))
                .build();

        OrderItem item = OrderItem.builder()
                .id(501L)
                .product(product)
                .productName("Sony Head Phone")
                .quantity(2)
                .unitPrice(new BigDecimal("9999.00"))
                .lineTotal(new BigDecimal("19998.00"))
                .build();

        existingOrder.addItem(item);

        when(currentUserService.getCurrentUser())
                .thenReturn(user);

        when(orderRepository.findByUserIdAndCheckoutKey(
                1L, CHECKOUT_KEY
        )).thenReturn(Optional.of(existingOrder));

        OrderResponse response =
                checkoutService.checkout(CHECKOUT_KEY);

        assertThat(response.getOrderId())
                .isEqualTo(101L);

        assertThat(response.getTotalAmount())
                .isEqualByComparingTo("19998.00");

        // No new order should be saved.
        verify(orderRepository, never())
                .saveAndFlush(any(Order.class));

        // No cart access, reservation or deduction.
        verifyNoInteractions(
                cartRepository,
                cartItemRepository,
                reservationService
        );
    }

    // ============================================
    // TEST 3: CUSTOMER HAS NO CART
    // ============================================

    @Test
    void checkout_shouldRejectWhenCartDoesNotExist() {

        when(currentUserService.getCurrentUser())
                .thenReturn(user);

        when(orderRepository.findByUserIdAndCheckoutKey(
                1L, CHECKOUT_KEY
        )).thenReturn(Optional.empty());

        when(cartRepository.findForCheckoutByUserId(1L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                checkoutService.checkout(CHECKOUT_KEY)
        )
                .isInstanceOf(CheckoutException.class)
                .hasMessageContaining("Cart is empty");

        verify(orderRepository, never())
                .saveAndFlush(any(Order.class));

        verifyNoInteractions(
                cartItemRepository,
                reservationService
        );
    }

    // ============================================
    // TEST 4: EMPTY CART
    // ============================================

    @Test
    void checkout_shouldRejectEmptyCart() {

        when(currentUserService.getCurrentUser())
                .thenReturn(user);

        when(orderRepository.findByUserIdAndCheckoutKey(
                1L, CHECKOUT_KEY
        )).thenReturn(Optional.empty());

        when(cartRepository.findForCheckoutByUserId(1L))
                .thenReturn(Optional.of(cart));

        when(cartItemRepository.findByCartId(10L))
                .thenReturn(List.of());

        assertThatThrownBy(() ->
                checkoutService.checkout(CHECKOUT_KEY)
        )
                .isInstanceOf(CheckoutException.class)
                .hasMessageContaining(
                        "Cannot checkout an empty cart"
                );

        verify(orderRepository, never())
                .saveAndFlush(any(Order.class));

        verifyNoInteractions(reservationService);
    }

    // ============================================
    // TEST 5: INSUFFICIENT INVENTORY
    // ============================================

    @Test
    void checkout_shouldStopWhenInventoryInsufficient() {

        mockFreshCheckout();

        when(reservationService.reserve(
                any(Order.class),
                eq(1L),
                eq(2),
                any(Instant.class)
        )).thenThrow(
                new InsufficientStockException(1L, 2, 1)
        );

        assertThatThrownBy(() ->
                checkoutService.checkout(CHECKOUT_KEY)
        ).isInstanceOf(
                InsufficientStockException.class
        );

        // Checkout must not reach cart deletion.
        verify(cartItemRepository, never())
                .deleteByCartId(anyLong());

        verify(cartItemRepository, never()).flush();
    }

    // ============================================
    // TEST 6: INVALID IDEMPOTENCY KEY
    // ============================================

    @Test
    void checkout_shouldRejectInvalidIdempotencyKey() {

        assertThatThrownBy(() ->
                checkoutService.checkout("invalid-key")
        )
                .isInstanceOf(CheckoutException.class)
                .hasMessageContaining("Idempotency-Key");

        // Invalid requests should not reach dependencies.
        verifyNoInteractions(
                currentUserService,
                cartRepository,
                cartItemRepository,
                orderRepository,
                reservationService
        );
    }

    // ============================================
    // SHARED TEST DATA / MOCK BEHAVIOR
    // ============================================

    private void mockFreshCheckout() {

        when(currentUserService.getCurrentUser())
                .thenReturn(user);

        when(orderRepository.findByUserIdAndCheckoutKey(
                1L, CHECKOUT_KEY
        )).thenReturn(Optional.empty());

        when(cartRepository.findForCheckoutByUserId(1L))
                .thenReturn(Optional.of(cart));

        when(cartItemRepository.findByCartId(10L))
                .thenReturn(List.of(cartItem));

        // Simulate JPA assigning generated IDs.
        when(orderRepository.saveAndFlush(any(Order.class)))
                .thenAnswer(invocation -> {

                    Order order = invocation.getArgument(0);

                    order.setId(101L);

                    long itemId = 501L;

                    for (OrderItem item : order.getItems()) {
                        item.setId(itemId++);
                    }

                    return order;
                });
    }
}
