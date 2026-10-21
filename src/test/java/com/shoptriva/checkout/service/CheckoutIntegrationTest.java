package com.shoptriva.checkout.service;

import com.shoptriva.auth.entity.Role;
import com.shoptriva.auth.entity.User;
import com.shoptriva.auth.security.UserPrincipal;
import com.shoptriva.cart.entity.Cart;
import com.shoptriva.cart.entity.CartItem;
import com.shoptriva.cart.repository.CartItemRepository;
import com.shoptriva.catalog.entity.Category;
import com.shoptriva.catalog.entity.Product;
import com.shoptriva.checkout.entity.ReservationStatus;
import com.shoptriva.checkout.repository.StockReservationRepository;
import com.shoptriva.common.exception.InsufficientStockException;
import com.shoptriva.common.exception.CheckoutException;
import com.shoptriva.common.exception.QuantityLimitException;
import com.shoptriva.cart.service.CartService;
import com.shoptriva.cart.dto.AddToCartRequest;
import com.shoptriva.cart.dto.UpdateCartItemRequest;
import com.shoptriva.inventory.service.InventoryService;
import com.shoptriva.inventory.dto.StockAdjustmentRequest;
import com.shoptriva.inventory.entity.Inventory;
import com.shoptriva.inventory.repository.InventoryRepository;
import com.shoptriva.order.dto.OrderResponse;
import com.shoptriva.order.entity.Order;
import com.shoptriva.order.entity.OrderStatus;
import com.shoptriva.order.repository.OrderItemRepository;
import com.shoptriva.order.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:checkout_integration;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CheckoutIntegrationTest {

    @Autowired private CartService cartService;
    @Autowired private InventoryService inventoryService;

    @ParameterizedTest
    @ValueSource(strings = {"9999999999.99", "3000000000.00"})
    void checkout_shouldRejectLineOrOrderOverflowWithoutSideEffects(String price) {
        transactions.executeWithoutResult(status -> productIds.forEach(id ->
                entityManager.find(Product.class, id).setPrice(new BigDecimal(price))));
        long orders = orderRepository.count();
        long items = orderItemRepository.count();
        long reservations = reservationRepository.count();
        List<Long> versions = inventoryVersions();

        assertThatThrownBy(() -> checkoutService.checkout(checkoutKey))
                .isInstanceOf(CheckoutException.class)
                .hasMessageContaining(price.startsWith("999") ? "Order item total" : "Order total");

        assertThat(orderRepository.count()).isEqualTo(orders);
        assertThat(orderItemRepository.count()).isEqualTo(items);
        assertThat(reservationRepository.count()).isEqualTo(reservations);
        assertThat(inventoryVersions()).isEqualTo(versions);
        transactions.executeWithoutResult(status -> {
            assertStock(10, 10);
            assertThat(cartItemRepository.findByCartId(cartId)).hasSize(2)
                    .allSatisfy(item -> assertThat(item.getQuantity()).isEqualTo(2));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"item-add", "total-add", "total-update"})
    void cart_shouldRollBackQuantityOverflow(String operation) {
        Long firstItemId = transactions.execute(status -> {
            List<CartItem> items = cartItemRepository.findByCartId(cartId);
            CartItem first = items.stream().filter(item -> item.getProduct().getId().equals(productIds.get(0)))
                    .findFirst().orElseThrow();
            first.setQuantity(operation.equals("item-add") ? Integer.MAX_VALUE : Integer.MAX_VALUE - 2);
            if (operation.equals("item-add")) {
                items.stream().filter(item -> item != first).forEach(entityManager::remove);
            }
            return first.getId();
        });
        var quantities = jdbcTemplate.queryForList(
                "select quantity from cart_items where cart_id = ? order by id", Integer.class, cartId);
        assertThatThrownBy(() -> {
            if (operation.equals("total-update")) {
                UpdateCartItemRequest request = new UpdateCartItemRequest();
                request.setQuantity(Integer.MAX_VALUE - 1);
                cartService.updateItemQuantity(firstItemId, request);
            } else {
                AddToCartRequest request = new AddToCartRequest();
                request.setProductId(productIds.get(0));
                request.setQuantity(1);
                cartService.addItem(request);
            }
        }).isInstanceOf(QuantityLimitException.class);
        assertThat(jdbcTemplate.queryForList(
                "select quantity from cart_items where cart_id = ? order by id", Integer.class, cartId))
                .isEqualTo(quantities);
    }

    @Test
    void inventory_shouldRollBackOverflowWithoutVersionChange() {
        transactions.executeWithoutResult(status -> inventoryRepository.findByProductId(productIds.get(0))
                .orElseThrow().setAvailableQuantity(Integer.MAX_VALUE));
        List<Long> versions = inventoryVersions();
        StockAdjustmentRequest request = new StockAdjustmentRequest();
        request.setQuantity(1);

        assertThatThrownBy(() -> inventoryService.increaseStock(productIds.get(0), request))
                .isInstanceOf(QuantityLimitException.class);
        assertThat(inventoryVersions()).isEqualTo(versions);
        transactions.executeWithoutResult(status -> assertStock(Integer.MAX_VALUE, 10));
    }

    @Autowired
    private CheckoutService checkoutService;

    // Every reservation executes the real service, including in the rollback test.
    @MockitoSpyBean
    private StockReservationService reservationService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private StockReservationRepository reservationRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    @MockitoSpyBean
    private CartItemRepository cartItemRepository;

    private TransactionTemplate transactions;
    private Long userId;
    private Long cartId;
    private List<Long> productIds;
    private List<Long> cartItemIds;
    private String checkoutKey;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        checkoutKey = UUID.randomUUID().toString();

        // Commit fixtures before calling checkout through its Spring proxy.
        // No test-level @Transactional: checkout must own its commit/rollback.
        transactions.executeWithoutResult(status -> {
            User user = User.builder()
                    .name("Checkout Customer")
                    .email(checkoutKey + "@example.com")
                    .password("unused-test-password")
                    .role(Role.CUSTOMER)
                    .enabled(true)
                    .build();
            entityManager.persist(user);
            userId = user.getId();

            Category category = Category.builder()
                    .name("Checkout " + checkoutKey)
                    .build();
            entityManager.persist(category);

            Cart cart = Cart.builder().user(user).build();
            entityManager.persist(cart);
            cartId = cart.getId();

            addProductToCart(category, cart, "Headphones", "100.00");
            addProductToCart(category, cart, "Keyboard", "50.00");
            entityManager.flush();

            // Stable fixture identifiers; this does not assume repository ordering.
            List<CartItem> items = cartItemRepository.findByCartId(cartId);
            productIds = items.stream().map(item -> item.getProduct().getId()).sorted().toList();
            cartItemIds = items.stream().map(CartItem::getId).toList();

            UserPrincipal principal = UserPrincipal.from(user);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities()
                    )
            );
        });
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void checkout_shouldPersistOrderAndReservationsDeductStockAndClearCart() {
        Instant startedAt = Instant.now();

        OrderResponse response = checkoutService.checkout(checkoutKey);

        transactions.executeWithoutResult(status -> {
            Order order = orderRepository.findById(response.getOrderId()).orElseThrow();
            assertThat(order.getUser().getId()).isEqualTo(userId);
            assertThat(order.getCheckoutKey()).isEqualTo(checkoutKey);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(order.getTotalAmount()).isEqualByComparingTo("300.00");
            assertThat(order.getItems()).hasSize(2).allSatisfy(item -> {
                assertThat(item.getId()).isNotNull();
                assertThat(item.getQuantity()).isEqualTo(2);
                assertThat(item.getProductName()).isEqualTo(item.getProduct().getName());
                assertThat(item.getUnitPrice()).isEqualByComparingTo(item.getProduct().getPrice());
                assertThat(item.getLineTotal()).isEqualByComparingTo(
                        item.getUnitPrice().multiply(BigDecimal.valueOf(2))
                );
            });
            assertThat(order.getItems()).extracting(item -> item.getProduct().getId())
                    .containsExactlyInAnyOrderElementsOf(productIds);

            var reservations = reservationRepository.findByOrderIdOrderByIdAsc(order.getId());
            assertThat(reservations).hasSize(2).allSatisfy(reservation -> {
                assertThat(reservation.getOrder().getId()).isEqualTo(order.getId());
                assertThat(reservation.getQuantity()).isEqualTo(2);
                assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RESERVED);
                assertThat(reservation.getExpiresAt()).isAfter(startedAt);
            });
            assertThat(reservations)
                    .extracting(reservation -> reservation.getInventory().getProduct().getId())
                    .containsExactlyInAnyOrderElementsOf(productIds);
            assertStock(8, 8);
            assertThat(cartItemRepository.findByCartId(cartId)).isEmpty();
        });
    }

    @Test
    void checkout_shouldRollBackEverythingWhenSecondProductHasInsufficientStock() {
        // Checkout preserves this list's order when building and reserving order items.
        // Control only this test's input order, loading real managed rows from H2.
        doAnswer(invocation -> entityManager.createQuery(
                "select item from CartItem item where item.cart.id = :cartId "
                        + "order by item.product.id", CartItem.class
        ).setParameter("cartId", cartId).getResultList())
                .when(cartItemRepository).findByCartId(cartId);

        transactions.executeWithoutResult(status ->
                inventoryRepository.findByProductId(productIds.get(1)).orElseThrow()
                        .setAvailableQuantity(1)
        );
        long ordersBefore = orderRepository.count();
        long orderItemsBefore = orderItemRepository.count();
        long reservationsBefore = reservationRepository.count();
        List<Long> versionsBefore = inventoryVersions();
        AtomicBoolean firstReservationSucceeded = new AtomicBoolean();

        doAnswer(invocation -> {
            Object reservation = invocation.callRealMethod();
            Order order = invocation.getArgument(0);

            // reserve() already flushed. JDBC reads the same transaction's H2
            // connection without flushing or detaching any managed JPA entities.
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from stock_reservations where order_id = ?",
                    Long.class, order.getId()
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from stock_reservations r "
                            + "join inventories i on i.id = r.inventory_id "
                            + "where r.order_id = ? and i.product_id = ? "
                            + "and r.quantity = ? and r.status = ?",
                    Long.class, order.getId(), productIds.get(0), 2,
                    ReservationStatus.RESERVED.name()
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "select available_quantity from inventories where product_id = ?",
                    Integer.class, productIds.get(0)
            )).isEqualTo(8);
            assertThat(jdbcTemplate.queryForObject(
                    "select available_quantity from inventories where product_id = ?",
                    Integer.class, productIds.get(1)
            )).isEqualTo(1);
            firstReservationSucceeded.set(true);
            return reservation;
        }).when(reservationService).reserve(
                any(Order.class), eq(productIds.get(0)), eq(2), any(Instant.class)
        );

        assertThatThrownBy(() -> checkoutService.checkout(checkoutKey))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessage("Insufficient stock for product id " + productIds.get(1)
                        + ". Requested: 2, available: 1");
        assertThat(firstReservationSucceeded).isTrue();

        // Prove failure occurred after the first real reservation and flush.
        var calls = inOrder(reservationService);
        calls.verify(reservationService).reserve(
                any(Order.class), eq(productIds.get(0)), eq(2), any(Instant.class)
        );
        calls.verify(reservationService).reserve(
                any(Order.class), eq(productIds.get(1)), eq(2), any(Instant.class)
        );
        calls.verifyNoMoreInteractions();

        transactions.executeWithoutResult(status -> {
            assertThat(orderRepository.count()).isEqualTo(ordersBefore);
            assertThat(orderItemRepository.count()).isEqualTo(orderItemsBefore);
            assertThat(reservationRepository.count()).isEqualTo(reservationsBefore);
            assertThat(orderRepository.findByUserIdAndCheckoutKey(userId, checkoutKey)).isEmpty();
            assertStock(10, 1);
            assertThat(cartItemRepository.findByCartId(cartId))
                    .hasSize(2)
                    .allSatisfy(item -> assertThat(item.getQuantity()).isEqualTo(2))
                    .extracting(CartItem::getId)
                    .containsExactlyInAnyOrderElementsOf(cartItemIds);
        });
        assertThat(inventoryVersions()).isEqualTo(versionsBefore);
    }

    @Test
    void checkout_shouldReturnOriginalOrderWithoutDeductingStockAgainForSameKey() {
        OrderResponse original = checkoutService.checkout(checkoutKey);
        long ordersAfterCheckout = orderRepository.count();
        long itemsAfterCheckout = orderItemRepository.count();
        long reservationsAfterCheckout = reservationRepository.count();
        List<Long> versionsAfterCheckout = inventoryVersions();

        // This is a separate service transaction, after the original has committed.
        OrderResponse replay = checkoutService.checkout(checkoutKey);

        assertThat(replay.getOrderId()).isEqualTo(original.getOrderId());
        assertThat(replay.getStatus()).isEqualTo(original.getStatus());
        assertThat(replay.getTotalAmount()).isEqualByComparingTo(original.getTotalAmount());
        assertThat(replay.getItems()).containsExactlyInAnyOrderElementsOf(original.getItems());
        transactions.executeWithoutResult(status -> {
            // Database timestamp precision may differ from the initial Java value.
            assertThat(replay.getCreatedAt()).isEqualTo(
                    orderRepository.findById(original.getOrderId()).orElseThrow().getCreatedAt()
            );
            assertThat(orderRepository.count()).isEqualTo(ordersAfterCheckout);
            assertThat(orderItemRepository.count()).isEqualTo(itemsAfterCheckout);
            assertThat(reservationRepository.count()).isEqualTo(reservationsAfterCheckout);
            assertThat(orderRepository.findByUserIdOrderByCreatedAtDesc(userId))
                    .extracting(Order::getId).containsExactly(original.getOrderId());
            assertStock(8, 8);
            assertThat(cartItemRepository.findByCartId(cartId)).isEmpty();
        });
        assertThat(inventoryVersions()).isEqualTo(versionsAfterCheckout);
    }

    private void addProductToCart(Category category, Cart cart, String name, String price) {
        Product product = Product.builder()
                .name(name)
                .price(new BigDecimal(price))
                .category(category)
                .build();
        entityManager.persist(product);
        entityManager.persist(Inventory.builder()
                .product(product).availableQuantity(10).build());
        entityManager.persist(CartItem.builder()
                .cart(cart).product(product).quantity(2).build());
    }

    private void assertStock(int firstQuantity, int secondQuantity) {
        assertThat(inventoryRepository.findByProductId(productIds.get(0)).orElseThrow()
                .getAvailableQuantity()).isEqualTo(firstQuantity);
        assertThat(inventoryRepository.findByProductId(productIds.get(1)).orElseThrow()
                .getAvailableQuantity()).isEqualTo(secondQuantity);
    }

    private List<Long> inventoryVersions() {
        return transactions.execute(status -> productIds.stream()
                .map(id -> inventoryRepository.findByProductId(id).orElseThrow().getVersion())
                .toList());
    }
}
