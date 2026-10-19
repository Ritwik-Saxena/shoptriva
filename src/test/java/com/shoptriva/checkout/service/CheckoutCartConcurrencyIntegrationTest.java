package com.shoptriva.checkout.service;

import com.shoptriva.auth.entity.Role;
import com.shoptriva.auth.entity.User;
import com.shoptriva.auth.security.UserPrincipal;
import com.shoptriva.cart.dto.AddToCartRequest;
import com.shoptriva.cart.dto.UpdateCartItemRequest;
import com.shoptriva.cart.entity.Cart;
import com.shoptriva.cart.entity.CartItem;
import com.shoptriva.cart.service.CartService;
import com.shoptriva.catalog.entity.Category;
import com.shoptriva.catalog.entity.Product;
import com.shoptriva.common.exception.CartItemNotFoundException;
import com.shoptriva.common.exception.CheckoutException;
import com.shoptriva.inventory.entity.Inventory;
import com.shoptriva.order.dto.OrderResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.testcontainers.mysql.MySQLContainer;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CheckoutCartConcurrencyIntegrationTest {

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.36")
            .withDatabaseName("shoptriva_cart_concurrency")
            .withUsername("checkout_test").withPassword("checkout_test")
            .withCommand("--default-storage-engine=InnoDB",
                    "--transaction-isolation=REPEATABLE-READ", "--innodb-lock-wait-timeout=20")
            .withUrlParam("connectTimeout", "10000").withUrlParam("socketTimeout", "30000")
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-only");
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        properties.add("spring.datasource.hikari.connection-timeout", () -> 10000);
    }

    @Autowired private CheckoutService checkoutService;
    @Autowired private CartService cartService;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void concurrentSameKey_shouldReturnSameOrderWithOneDeduction() throws Exception {
        Fixture fixture = fixture(true);
        String key = UUID.randomUUID().toString();
        List<Outcome> outcomes = contend(fixture,
                () -> checkoutService.checkout(key), () -> checkoutService.checkout(key));
        outcomes.forEach(outcome -> assertThat(outcome.failure()).isNull());
        OrderResponse original = (OrderResponse) outcomes.get(0).value();
        OrderResponse replay = (OrderResponse) outcomes.get(1).value();
        assertThat(replay).usingRecursiveComparison().ignoringFields("createdAt").isEqualTo(original);
        assertThat(replay.getCreatedAt()).isEqualTo(jdbc.queryForObject(
                "select created_at from orders where id = ?", java.sql.Timestamp.class,
                original.getOrderId()).toLocalDateTime());
        assertPurchase(fixture, 2);
        assertCart(fixture, 0);
    }

    @Test
    void simultaneousInitialAdds_shouldCreateOneCartAndAccumulateQuantity() throws Exception {
        Fixture fixture = fixture(false);
        List<Outcome> outcomes = contend(fixture, () -> add(fixture, 2), () -> add(fixture, 3));
        outcomes.forEach(outcome -> assertThat(outcome.failure()).isNull());
        assertCart(fixture, 5);
        assertPurchase(fixture, 0);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void cartMutation_shouldWaitForCheckoutCommit(Mutation mutation) throws Exception {
        Fixture fixture = fixture(true);
        List<Outcome> outcomes = contend(fixture,
                () -> checkoutService.checkout(UUID.randomUUID().toString()),
                () -> mutate(fixture, mutation));
        assertThat(outcomes.get(0).failure()).isNull();
        if (mutation == Mutation.UPDATE || mutation == Mutation.REMOVE) {
            assertThat(outcomes.get(1).failure()).isInstanceOf(CartItemNotFoundException.class);
        } else {
            assertThat(outcomes.get(1).failure()).isNull();
        }
        assertPurchase(fixture, 2);
        assertCart(fixture, mutation == Mutation.ADD ? 3 : 0);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void checkout_shouldReadCommittedCartMutation(Mutation mutation) throws Exception {
        Fixture fixture = fixture(true);
        List<Outcome> outcomes = contend(fixture, () -> mutate(fixture, mutation),
                () -> checkoutService.checkout(UUID.randomUUID().toString()));
        assertThat(outcomes.get(0).failure()).isNull();
        int purchased = switch (mutation) {
            case ADD -> 5;
            case UPDATE -> 3;
            case REMOVE, CLEAR -> 0;
        };
        if (purchased == 0) {
            assertThat(outcomes.get(1).failure()).isInstanceOf(CheckoutException.class)
                    .hasMessageContaining("empty cart");
        } else {
            assertThat(outcomes.get(1).failure()).isNull();
        }
        assertPurchase(fixture, purchased);
        assertCart(fixture, 0);
    }

    private Object mutate(Fixture fixture, Mutation mutation) {
        return switch (mutation) {
            case ADD -> add(fixture, 3);
            case UPDATE -> {
                UpdateCartItemRequest request = new UpdateCartItemRequest();
                request.setQuantity(3);
                yield cartService.updateItemQuantity(fixture.itemId(), request);
            }
            case REMOVE -> cartService.removeItem(fixture.itemId());
            case CLEAR -> cartService.clearCart();
        };
    }

    private Object add(Fixture fixture, int quantity) {
        AddToCartRequest request = new AddToCartRequest();
        request.setProductId(fixture.productId());
        request.setQuantity(quantity);
        return cartService.addItem(request);
    }

    private List<Outcome> contend(Fixture fixture, Supplier<?> firstCall, Supplier<?> secondCall)
            throws Exception {
        var firstFinishedWork = new CompletableFuture<Void>();
        var allowCommit = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        Future<Outcome> first = null;
        Future<Outcome> second = null;
        Throwable originalFailure = null;
        try {
            first = workers.submit(() -> asCustomer(fixture, () -> {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                return transaction.execute(status -> {
                    try {
                        Object result = firstCall.get();
                        entityManager.flush();
                        firstFinishedWork.complete(null);
                        await(allowCommit);
                        return result;
                    } catch (RuntimeException | Error failure) {
                        firstFinishedWork.completeExceptionally(failure);
                        throw failure;
                    }
                });
            }));
            firstFinishedWork.get(15, TimeUnit.SECONDS);
            // This call owns its service transaction: no test isolation override and no mocks.
            second = workers.submit(() -> asCustomer(fixture, secondCall));
            assertRealLockWait();
            assertThat(second.isDone()).isFalse();
            allowCommit.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } catch (Exception | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            allowCommit.countDown();
            Throwable cleanupFailure = null;
            boolean interrupted = originalFailure instanceof InterruptedException;
            for (Future<?> future : new Future<?>[]{first, second}) {
                try {
                    if (future != null) future.cancel(true);
                } catch (RuntimeException | Error failure) {
                    if (cleanupFailure == null) cleanupFailure = failure;
                    else cleanupFailure.addSuppressed(failure);
                }
            }
            try {
                workers.shutdownNow();
            } catch (RuntimeException | Error failure) {
                if (cleanupFailure == null) cleanupFailure = failure;
                else cleanupFailure.addSuppressed(failure);
            }
            try {
                assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            } catch (Exception | Error failure) {
                interrupted |= failure instanceof InterruptedException;
                if (cleanupFailure == null) cleanupFailure = failure;
                else cleanupFailure.addSuppressed(failure);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (cleanupFailure != null) {
                if (originalFailure != null) originalFailure.addSuppressed(cleanupFailure);
                else if (cleanupFailure instanceof Exception exception) throw exception;
                else throw (Error) cleanupFailure;
            }
        }
    }

    private void assertRealLockWait() throws Exception {
        // Root is used only to inspect performance_schema in this disposable container.
        // The application still runs as checkout_test; URL and password come solely from MYSQL.
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.prepareStatement("""
                     select w.requesting_thread_id, w.blocking_thread_id
                     from performance_schema.data_lock_waits w
                     join performance_schema.data_locks l
                       on l.engine_lock_id = w.requesting_engine_lock_id and l.engine = w.engine
                     where l.object_schema = ? and l.object_name = 'users'
                     """)) {
            statement.setString(1, MYSQL.getDatabaseName());
            statement.setQueryTimeout(5);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            do {
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        assertThat(rows.getLong(1)).isNotEqualTo(rows.getLong(2));
                        return;
                    }
                }
            } while (System.nanoTime() < deadline);
            fail("Second transaction did not contend on the customer lock before the deadline");
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new AssertionError("Commit gate timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Commit gate interrupted", ex);
        }
    }

    private Outcome asCustomer(Fixture fixture, Supplier<?> action) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                fixture.principal(), null, fixture.principal().getAuthorities()));
        SecurityContextHolder.setContext(context);
        try {
            return new Outcome(action.get(), null);
        } catch (RuntimeException failure) {
            return new Outcome(null, failure);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Fixture fixture(boolean withCart) {
        assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo(MYSQL.getDatabaseName());
        return new TransactionTemplate(transactionManager).execute(status -> {
            String name = UUID.randomUUID().toString();
            User user = User.builder().name(name).email(name + "@example.com")
                    .password("unused").role(Role.CUSTOMER).enabled(true).build();
            entityManager.persist(user);
            Category category = Category.builder().name(name).build();
            entityManager.persist(category);
            Product product = Product.builder().name("Sony Head Phone").category(category)
                    .price(new BigDecimal("10.00")).build();
            entityManager.persist(product);
            entityManager.persist(Inventory.builder().product(product).availableQuantity(10).build());
            Long itemId = null;
            if (withCart) {
                Cart cart = Cart.builder().user(user).build();
                entityManager.persist(cart);
                CartItem item = CartItem.builder().cart(cart).product(product).quantity(2).build();
                entityManager.persist(item);
                itemId = item.getId();
            }
            return new Fixture(UserPrincipal.from(user), product.getId(), itemId);
        });
    }

    private void assertCart(Fixture fixture, int quantity) {
        assertThat(jdbc.queryForObject("select count(*) from carts where user_id = ?", Long.class,
                fixture.principal().getId())).isEqualTo(1);
        assertThat(jdbc.queryForList("select i.quantity from cart_items i join carts c on c.id = i.cart_id "
                        + "where c.user_id = ?", Integer.class, fixture.principal().getId()))
                .containsExactlyElementsOf(quantity == 0 ? List.of() : List.of(quantity));
    }

    private void assertPurchase(Fixture fixture, int quantity) {
        assertThat(jdbc.queryForObject("select available_quantity from inventories where product_id = ?",
                Integer.class, fixture.productId())).isEqualTo(10 - quantity);
        assertThat(jdbc.queryForObject("select version from inventories where product_id = ?",
                Long.class, fixture.productId())).isEqualTo(quantity == 0 ? 0L : 1L);
        assertThat(jdbc.queryForObject("select count(*) from orders where user_id = ?", Long.class,
                fixture.principal().getId())).isEqualTo(quantity == 0 ? 0L : 1L);
        assertThat(jdbc.queryForList("select i.quantity from order_items i join orders o on o.id = i.order_id "
                        + "where o.user_id = ?", Integer.class, fixture.principal().getId()))
                .containsExactlyElementsOf(quantity == 0 ? List.of() : List.of(quantity));
        assertThat(jdbc.queryForList("select r.quantity from stock_reservations r join inventories i "
                        + "on i.id = r.inventory_id where i.product_id = ?", Integer.class, fixture.productId()))
                .containsExactlyElementsOf(quantity == 0 ? List.of() : List.of(quantity));
        if (quantity > 0) {
            assertThat(jdbc.queryForObject("select r.status from stock_reservations r join inventories i "
                            + "on i.id = r.inventory_id where i.product_id = ?", String.class, fixture.productId()))
                    .isEqualTo("RESERVED");
        }
    }

    private enum Mutation { ADD, UPDATE, REMOVE, CLEAR }
    private record Fixture(UserPrincipal principal, Long productId, Long itemId) {}
    private record Outcome(Object value, RuntimeException failure) {}
}
