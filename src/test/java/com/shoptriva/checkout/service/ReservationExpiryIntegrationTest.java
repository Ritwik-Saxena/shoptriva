package com.shoptriva.checkout.service;

import com.shoptriva.auth.entity.Role;
import com.shoptriva.auth.entity.User;
import com.shoptriva.auth.security.UserPrincipal;
import com.shoptriva.cart.entity.Cart;
import com.shoptriva.cart.entity.CartItem;
import com.shoptriva.catalog.entity.Category;
import com.shoptriva.catalog.entity.Product;
import com.shoptriva.checkout.config.ReservationExpiryScheduler;
import com.shoptriva.checkout.entity.ReservationStatus;
import com.shoptriva.checkout.entity.StockReservation;
import com.shoptriva.common.exception.ReservationConflictException;
import com.shoptriva.inventory.entity.Inventory;
import com.shoptriva.order.entity.OrderStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {
        "shoptriva.reservation-expiry.enabled=true",
        "shoptriva.reservation-expiry.batch-size=2",
        "shoptriva.reservation-expiry.initial-delay-ms=3600000",
        "shoptriva.reservation-expiry.fixed-delay-ms=3600000"
})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationExpiryIntegrationTest {

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.36")
            .withDatabaseName("shoptriva_expiry_test")
            .withUsername("expiry_test").withPassword("expiry_test")
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

    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private CheckoutService checkoutService;
    @Autowired private ReservationExpiryBatchProcessor processor;
    @Autowired private ReservationExpiryScheduler scheduler;
    @Autowired private ScheduledAnnotationBeanPostProcessor scheduledTasks;
    @MockitoSpyBean private StockReservationService reservations;

    @BeforeEach
    void cleanDisposableDatabase() {
        assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo(MYSQL.getDatabaseName());
        assertThat(jdbc.queryForList("select engine from information_schema.tables "
                + "where table_schema = database()", String.class)).allMatch("InnoDB"::equals);
        for (String table : List.of("stock_reservations", "order_items", "orders", "cart_items",
                "inventories", "products", "carts", "categories", "users")) {
            jdbc.update("delete from " + table);
        }
    }

    @Test
    void expiry_shouldRestoreExactlyOnceCancelOrderAndPreserveReplay() {
        Fixture fixture = checkout();
        due(fixture);
        assertThat(processor.processBatch()).isEqualTo(new ReservationExpiryBatchProcessor.BatchResult(1, 1, 0));
        assertState(fixture, "CANCELLED", "EXPIRED", 10, 2);
        assertThat(reservations.expireForOrder(fixture.orderId())).isFalse();
        reservations.releaseForOrder(fixture.orderId(), ReservationStatus.EXPIRED);
        assertThat(processor.processBatch().attempted()).isZero();
        assertState(fixture, "CANCELLED", "EXPIRED", 10, 2);
        assertThatThrownBy(() -> reservations.commitForOrder(fixture.orderId()))
                .isInstanceOf(ReservationConflictException.class);
        authenticate(fixture.principal());
        try {
            var replay = checkoutService.checkout(fixture.key());
            assertThat(replay.getOrderId()).isEqualTo(fixture.orderId());
            assertThat(replay.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(jdbc.queryForObject("select count(*) from orders", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_items", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from cart_items", Long.class)).isZero();
        assertState(fixture, "CANCELLED", "EXPIRED", 10, 2);
    }

    @Test
    void expiry_shouldWaitUntilEveryReservationIsDue() {
        Fixture fixture = checkout();
        assertThat(reservations.expireForOrder(fixture.orderId())).isFalse();
        assertThat(processor.processBatch().attempted()).isZero();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createQuery("select r from StockReservation r where r.order.id = :orderId "
                                + "order by r.id", StockReservation.class)
                        .setParameter("orderId", fixture.orderId()).setMaxResults(1).getSingleResult()
                        .setExpiresAt(Instant.now().minusSeconds(60)));
        assertThat(processor.processBatch().attempted()).isZero();
        assertThat(reservations.expireForOrder(fixture.orderId())).isFalse();
        assertThatThrownBy(() -> reservations.releaseForOrder(fixture.orderId(), ReservationStatus.EXPIRED))
                .isInstanceOf(ReservationConflictException.class);
        assertState(fixture, "PENDING", "RESERVED", 8, 1);
        due(fixture);
        assertThatThrownBy(() -> reservations.commitForOrder(fixture.orderId()))
                .isInstanceOf(ReservationConflictException.class).hasMessage("Reservation has expired");
        assertThat(reservations.expireForOrder(fixture.orderId())).isTrue();
        assertState(fixture, "CANCELLED", "EXPIRED", 10, 2);
    }

    @Test
    void expiry_shouldNeverReleaseCommittedReservations() {
        Fixture fixture = checkout();
        // Exercises the existing reservation guard, not a payment endpoint or payment workflow.
        reservations.commitForOrder(fixture.orderId());
        reservations.commitForOrder(fixture.orderId());
        due(fixture);
        assertThat(processor.processBatch().attempted()).isZero();
        assertThat(reservations.expireForOrder(fixture.orderId())).isFalse();
        assertState(fixture, "PENDING", "COMMITTED", 8, 1);
    }

    @Test
    void releasedReservations_shouldRejectLaterCommit() {
        Fixture fixture = checkout();
        reservations.releaseForOrder(fixture.orderId(), ReservationStatus.RELEASED);
        reservations.releaseForOrder(fixture.orderId(), ReservationStatus.RELEASED);
        assertThatThrownBy(() -> reservations.commitForOrder(fixture.orderId()))
                .isInstanceOf(ReservationConflictException.class);
        assertThat(reservations.expireForOrder(fixture.orderId())).isFalse();
        assertState(fixture, "CANCELLED", "RELEASED", 10, 2);
    }

    @Test
    void batch_shouldRespectConfiguredBoundAndRegisterSchedule() {
        assertThat(scheduler).isNotNull();
        assertThat(scheduledTasks.getScheduledTasks()).hasSize(1);
        Fixture first = checkout();
        Fixture second = checkout();
        Fixture third = checkout();
        List.of(first, second, third).forEach(this::due);
        assertThat(processor.processBatch()).isEqualTo(new ReservationExpiryBatchProcessor.BatchResult(2, 2, 0));
        assertState(first, "CANCELLED", "EXPIRED", 10, 2);
        assertState(second, "CANCELLED", "EXPIRED", 10, 2);
        assertState(third, "PENDING", "RESERVED", 8, 1);
        assertThat(processor.processBatch()).isEqualTo(new ReservationExpiryBatchProcessor.BatchResult(1, 1, 0));
        assertState(third, "CANCELLED", "EXPIRED", 10, 2);
    }

    @Test
    void failureAfterFlush_shouldRollBackOrderReservationsAndStockAndContinueBatch() {
        Fixture failing = checkout();
        Fixture healthy = checkout();
        due(failing);
        due(healthy);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(result).isEqualTo(true);
            // Verify real SQL writes happened in this transaction before injecting failure.
            assertState(failing, "CANCELLED", "EXPIRED", 10, 2);
            throw new IllegalStateException("Injected failure after expiry flush");
        }).when(reservations).expireForOrder(failing.orderId());

        assertThat(processor.processBatch()).isEqualTo(new ReservationExpiryBatchProcessor.BatchResult(2, 1, 1));
        assertState(failing, "PENDING", "RESERVED", 8, 1);
        assertState(healthy, "CANCELLED", "EXPIRED", 10, 2);
        assertThat(jdbc.queryForObject("select version from orders where id = ?", Long.class,
                failing.orderId())).isZero();
        assertThat(jdbc.queryForList("select version from stock_reservations where order_id = ?",
                Long.class, failing.orderId())).containsExactly(1L, 1L);
    }

    @Test
    void concurrentWorkers_shouldSerializeAndRestoreOnlyOnce() throws Exception {
        Fixture fixture = checkout();
        due(fixture);
        List<Outcome> outcomes = contend(() -> reservations.expireForOrder(fixture.orderId()),
                () -> reservations.expireForOrder(fixture.orderId()));
        outcomes.forEach(outcome -> assertThat(outcome.failure()).isNull());
        assertThat(outcomes).extracting(Outcome::value).containsExactly(true, false);
        assertState(fixture, "CANCELLED", "EXPIRED", 10, 2);
    }

    @Test
    void commitWaitingForExpiry_shouldRejectAfterExpiryCommits() throws Exception {
        Fixture fixture = checkout();
        due(fixture);
        List<Outcome> outcomes = contend(() -> reservations.expireForOrder(fixture.orderId()), () -> {
            reservations.commitForOrder(fixture.orderId());
            return null;
        });
        assertThat(outcomes.get(0).failure()).isNull();
        assertThat(outcomes.get(0).value()).isEqualTo(true);
        assertThat(outcomes.get(1).failure()).isInstanceOf(ReservationConflictException.class);
        assertState(fixture, "CANCELLED", "EXPIRED", 10, 2);
    }

    private List<Outcome> contend(Supplier<?> firstCall, Supplier<?> secondCall) throws Exception {
        var ready = new CompletableFuture<Void>();
        var allowCommit = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        Future<Outcome> first = null;
        Future<Outcome> second = null;
        Throwable originalFailure = null;
        try {
            first = workers.submit(() -> outcome(() -> {
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                return tx.execute(status -> {
                    try {
                        Object result = firstCall.get();
                        entityManager.flush();
                        ready.complete(null);
                        if (!allowCommit.await(20, TimeUnit.SECONDS)) throw new AssertionError("Commit gate timed out");
                        return result;
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    } catch (RuntimeException | Error failure) {
                        ready.completeExceptionally(failure);
                        throw failure;
                    }
                });
            }));
            ready.get(15, TimeUnit.SECONDS);
            second = workers.submit(() -> outcome(secondCall));
            assertOrderLockWait();
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

    private void assertOrderLockWait() throws Exception {
        // Separate observer connection to the disposable container only.
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.prepareStatement("""
                     select w.requesting_thread_id, w.blocking_thread_id
                     from performance_schema.data_lock_waits w
                     join performance_schema.data_locks l
                       on l.engine_lock_id = w.requesting_engine_lock_id and l.engine = w.engine
                     where l.object_schema = ? and l.object_name = 'orders'
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
            fail("Expected two separate MySQL transactions contending on the order lock");
        }
    }

    private Outcome outcome(Supplier<?> action) {
        try {
            return new Outcome(action.get(), null);
        } catch (RuntimeException failure) {
            return new Outcome(null, failure);
        }
    }

    private Fixture checkout() {
        UserPrincipal principal = new TransactionTemplate(transactionManager).execute(status -> {
            String name = UUID.randomUUID().toString();
            User user = User.builder().name(name).email(name + "@example.com").password("unused")
                    .role(Role.CUSTOMER).enabled(true).build();
            entityManager.persist(user);
            Cart cart = Cart.builder().user(user).build();
            entityManager.persist(cart);
            Category category = Category.builder().name(name).build();
            entityManager.persist(category);
            for (int i = 0; i < 2; i++) {
                Product product = Product.builder().name("Product " + i).category(category)
                        .price(new BigDecimal("10.00")).build();
                entityManager.persist(product);
                entityManager.persist(Inventory.builder().product(product).availableQuantity(10).build());
                entityManager.persist(CartItem.builder().cart(cart).product(product).quantity(2).build());
            }
            return UserPrincipal.from(user);
        });
        String key = UUID.randomUUID().toString();
        authenticate(principal);
        try {
            return new Fixture(checkoutService.checkout(key).getOrderId(), principal, key);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private void due(Fixture fixture) {
        // Use Hibernate's Instant mapping, avoiding JDBC/default-zone timestamp conversion.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createQuery("select r from StockReservation r where r.order.id = :orderId",
                                StockReservation.class).setParameter("orderId", fixture.orderId())
                        .getResultList().forEach(reservation ->
                                reservation.setExpiresAt(Instant.now().minusSeconds(60))));
    }

    private void assertState(Fixture fixture, String orderStatus, String reservationStatus,
                             int stock, long inventoryVersion) {
        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class,
                fixture.orderId())).isEqualTo(orderStatus);
        assertThat(jdbc.queryForList("select status from stock_reservations where order_id = ?",
                String.class, fixture.orderId())).containsExactly(reservationStatus, reservationStatus);
        assertThat(jdbc.queryForList("select i.available_quantity from inventories i join stock_reservations r "
                        + "on r.inventory_id = i.id where r.order_id = ?", Integer.class, fixture.orderId()))
                .containsExactly(stock, stock);
        assertThat(jdbc.queryForList("select i.version from inventories i join stock_reservations r "
                        + "on r.inventory_id = i.id where r.order_id = ?", Long.class, fixture.orderId()))
                .containsExactly(inventoryVersion, inventoryVersion);
    }

    private record Fixture(Long orderId, UserPrincipal principal, String key) {}
    private record Outcome(Object value, RuntimeException failure) {}
}
