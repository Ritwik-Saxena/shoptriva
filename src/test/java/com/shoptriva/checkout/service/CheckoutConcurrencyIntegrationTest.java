package com.shoptriva.checkout.service;

import com.shoptriva.auth.entity.Role;
import com.shoptriva.auth.entity.User;
import com.shoptriva.auth.security.UserPrincipal;
import com.shoptriva.cart.entity.Cart;
import com.shoptriva.cart.entity.CartItem;
import com.shoptriva.catalog.entity.Category;
import com.shoptriva.catalog.entity.Product;
import com.shoptriva.inventory.entity.Inventory;
import com.shoptriva.inventory.repository.InventoryRepository;
import com.shoptriva.order.dto.OrderResponse;
import com.shoptriva.order.entity.Order;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CheckoutConcurrencyIntegrationTest {

    // No reuse, fixed host port, external database URL, or host data volume.
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.36")
            .withDatabaseName("shoptriva_concurrency_test")
            .withUsername("checkout_test")
            .withPassword("checkout_test")
            .withCommand("--default-storage-engine=InnoDB",
                    "--transaction-isolation=REPEATABLE-READ", "--innodb-lock-wait-timeout=10")
            .withUrlParam("connectTimeout", "10000")
            .withUrlParam("socketTimeout", "30000")
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void containerDatabase(DynamicPropertyRegistry properties) {
        // Override every datasource setting from the H2 profile/main configuration.
        // If Docker cannot start this container, context creation fails; no fallback.
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        // The container starts empty and is destroyed afterward; no initial schema drop.
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-only");
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        properties.add("spring.datasource.hikari.connection-timeout", () -> 10000);
    }

    @Autowired
    private CheckoutService checkoutService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private InventoryRepository inventoryRepository;

    @MockitoSpyBean
    private StockReservationService reservationService;

    @Test
    void checkout_shouldAllowOnlyOneCustomerToBuyTheLastUnit() throws Exception {
        assertThat(jdbcTemplate.queryForObject("select database()", String.class))
                .isEqualTo(MYSQL.getDatabaseName());
        assertThat(jdbcTemplate.queryForList(
                "select engine from information_schema.tables where table_schema = database()",
                String.class
        )).isNotEmpty().allMatch("InnoDB"::equals);

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Fixture fixture = transactions.execute(status -> createFixture());
        assertThat(fixture).isNotNull();
        Long initialVersion = jdbcTemplate.queryForObject(
                "select version from inventories where product_id = ?", Long.class, fixture.productId()
        );
        assertThat(jdbcTemplate.queryForObject("select count(*) from orders", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from stock_reservations", Long.class))
                .isZero();

        CyclicBarrier bothReadLastUnit = new CyclicBarrier(2);
        var reads = new ConcurrentLinkedQueue<InventoryRead>();
        var successfulReservationStock = new ConcurrentLinkedQueue<Integer>();

        doAnswer(invocation -> {
            // Spring Data's derived interface method cannot use callRealMethod().
            // Execute the equivalent real JPA query, retaining a managed entity in
            // each worker's transaction; only the rendezvous is test instrumentation.
            Optional<Inventory> result = entityManager.createQuery(
                    "select inventory from Inventory inventory where inventory.product.id = :productId",
                    Inventory.class
            ).setParameter("productId", invocation.getArgument(0)).getResultList().stream().findFirst();
            Inventory inventory = result.orElseThrow();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(inventory.getAvailableQuantity()).isEqualTo(1);
            assertThat(inventory.getVersion()).isEqualTo(initialVersion);
            assertThat(jdbcTemplate.queryForObject("select @@transaction_isolation", String.class))
                    .isEqualTo("READ-COMMITTED");
            reads.add(new InventoryRead(Thread.currentThread().threadId(),
                    jdbcTemplate.queryForObject("select connection_id()", Long.class)));
            bothReadLastUnit.await(15, TimeUnit.SECONDS);
            return result;
        }).when(inventoryRepository).findByProductId(fixture.productId());

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            // Read flushed SQL state on the same connection, without clearing JPA state.
            Integer stock = jdbcTemplate.queryForObject(
                    "select available_quantity from inventories where product_id = ?",
                    Integer.class, fixture.productId()
            );
            assertThat(stock).isZero();
            successfulReservationStock.add(stock);
            return result;
        }).when(reservationService).reserve(
                any(Order.class), eq(fixture.productId()), eq(1), any(Instant.class)
        );

        var workers = Executors.newFixedThreadPool(2);
        Future<Outcome> first = null;
        Future<Outcome> second = null;
        List<Outcome> outcomes;
        Throwable originalFailure = null;
        try {
            first = workers.submit(() -> checkoutAs(fixture.first()));
            second = workers.submit(() -> checkoutAs(fixture.second()));
            outcomes = List.of(first.get(45, TimeUnit.SECONDS), second.get(45, TimeUnit.SECONDS));
        } catch (Exception | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
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
                assertThat(workers.awaitTermination(10, TimeUnit.SECONDS))
                        .as("checkout workers must terminate before container cleanup").isTrue();
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

        // Report both outcomes before assertions so an unexpected failure remains diagnosable.
        outcomes.forEach(outcome -> System.out.println("SHOP-104 customer "
                + outcome.customer().principal().getId() + ": "
                + (outcome.failure() == null ? "SUCCESS order=" + outcome.response().getOrderId()
                : failureDescription(outcome.failure()))));
        assertThat(reads).hasSize(2);
        assertThat(reads).extracting(InventoryRead::threadId).doesNotHaveDuplicates();
        assertThat(reads).extracting(InventoryRead::connectionId).doesNotHaveDuplicates();
        assertThat(outcomes).filteredOn(outcome -> outcome.failure() == null).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.failure() != null).hasSize(1);
        Outcome winner = outcomes.stream().filter(outcome -> outcome.failure() == null)
                .findFirst().orElseThrow();
        Outcome loser = outcomes.stream().filter(outcome -> outcome.failure() != null)
                .findFirst().orElseThrow();
        assertThat(successfulReservationStock).containsExactly(0);
        verify(inventoryRepository, times(2)).findByProductId(fixture.productId());
        verify(reservationService, times(2)).reserve(
                any(Order.class), eq(fixture.productId()), eq(1), any(Instant.class)
        );

        // Both service proxies have now returned/thrown: commit/rollback has completed.
        transactions.executeWithoutResult(status -> {
            assertThat(jdbcTemplate.queryForObject(
                    "select available_quantity from inventories where product_id = ?",
                    Integer.class, fixture.productId()
            )).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from inventories where available_quantity < 0", Long.class
            )).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select version from inventories where product_id = ?", Long.class, fixture.productId()
            )).isEqualTo(initialVersion + 1);
            assertThat(jdbcTemplate.queryForObject("select count(*) from orders", Long.class)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject("select count(*) from order_items", Long.class)).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject("select count(*) from stock_reservations", Long.class))
                    .isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from orders where id = ? and user_id = ? "
                            + "and checkout_key = ? and status = 'PENDING'", Long.class,
                    winner.response().getOrderId(), winner.customer().principal().getId(),
                    winner.customer().key()
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from order_items where order_id = ? and product_id = ? and quantity = 1",
                    Long.class, winner.response().getOrderId(), fixture.productId()
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from stock_reservations r join inventories i on i.id = r.inventory_id "
                            + "where r.order_id = ? and i.product_id = ? and r.quantity = 1 "
                            + "and r.status = 'RESERVED' and r.expires_at > current_timestamp",
                    Long.class, winner.response().getOrderId(), fixture.productId()
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from orders where user_id = ?", Long.class,
                    loser.customer().principal().getId()
            )).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from cart_items where cart_id = ?", Long.class,
                    winner.customer().cartId()
            )).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from cart_items", Long.class
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from cart_items where id = ? and cart_id = ? "
                            + "and product_id = ? and quantity = 1", Long.class,
                    loser.customer().cartItemId(), loser.customer().cartId(), fixture.productId()
            )).isEqualTo(1L);
        });
        // Do not accept timeouts, barrier failures, or arbitrary persistence exceptions.
        assertThat(isExpectedConcurrencyFailure(loser.failure()))
                .as(failureDescription(loser.failure())).isTrue();
    }

    private Fixture createFixture() {
        Category category = Category.builder().name("Headphones").build();
        entityManager.persist(category);
        Product product = Product.builder().name("Sony Head Phone")
                .category(category).price(new BigDecimal("9999.00")).build();
        entityManager.persist(product);
        entityManager.persist(Inventory.builder().product(product).availableQuantity(1).build());
        return new Fixture(product.getId(), createCustomer("A", product), createCustomer("B", product));
    }

    private Customer createCustomer(String name, Product product) {
        User user = User.builder().name(name).email(name + "@example.com")
                .password("unused-test-password").role(Role.CUSTOMER).enabled(true).build();
        entityManager.persist(user);
        Cart cart = Cart.builder().user(user).build();
        entityManager.persist(cart);
        CartItem item = CartItem.builder().cart(cart).product(product).quantity(1).build();
        entityManager.persist(item);
        return new Customer(UserPrincipal.from(user), cart.getId(), item.getId(), UUID.randomUUID().toString());
    }

    private Outcome checkoutAs(Customer customer) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                customer.principal(), null, customer.principal().getAuthorities()));
        SecurityContextHolder.setContext(context);
        try {
            return new Outcome(customer, checkoutService.checkout(customer.key()), null);
        } catch (RuntimeException failure) {
            return new Outcome(customer, null, failure);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isExpectedConcurrencyFailure(RuntimeException failure) {
        if (failure instanceof ObjectOptimisticLockingFailureException) return true;
        if (!(failure instanceof DataAccessException)) return false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && sql.getErrorCode() == 1213 && "40001".equals(sql.getSQLState())) return true;
        }
        return false;
    }

    private String failureDescription(RuntimeException failure) {
        StringBuilder description = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            description.append(cause.getClass().getName()).append(": ").append(cause.getMessage());
            if (cause instanceof SQLException sql) {
                description.append(" [SQLState=").append(sql.getSQLState())
                        .append(", errorCode=").append(sql.getErrorCode()).append(']');
            }
            if (cause.getCause() != null) description.append(" -> ");
        }
        return description.toString();
    }

    private record Customer(UserPrincipal principal, Long cartId, Long cartItemId, String key) {}
    private record Fixture(Long productId, Customer first, Customer second) {}
    private record InventoryRead(long threadId, Long connectionId) {}
    private record Outcome(Customer customer, OrderResponse response, RuntimeException failure) {}
}
