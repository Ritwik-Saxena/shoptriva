package com.shoptriva.inventory.repository;

import com.shoptriva.catalog.entity.Category;
import com.shoptriva.catalog.entity.Product;
import com.shoptriva.inventory.entity.Inventory;
import jakarta.persistence.EntityManager;
import org.hibernate.StaleObjectStateException;
import org.hibernate.StaleStateException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryOptimisticLockingIntegrationTest {

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.36")
            .withDatabaseName("shoptriva_optimistic_lock_test")
            .withUsername("inventory_test").withPassword("inventory_test")
            .withCommand("--default-storage-engine=InnoDB",
                    "--transaction-isolation=REPEATABLE-READ", "--innodb-lock-wait-timeout=10")
            .withUrlParam("connectTimeout", "10000")
            .withUrlParam("socketTimeout", "30000")
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void containerDatabase(DynamicPropertyRegistry properties) {
        // All connections use the disposable container, with no external database fallback.
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "create-only");
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        properties.add("spring.datasource.hikari.connection-timeout", () -> 10000);
    }

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void inventory_shouldRejectStaleUpdateAfterAnotherTransactionCommits() throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jdbcTemplate.queryForObject("select database()", String.class))
                .isEqualTo(MYSQL.getDatabaseName());
        assertThat(jdbcTemplate.queryForList(
                "select engine from information_schema.tables where table_schema = database()",
                String.class)).isNotEmpty().allMatch("InnoDB"::equals);

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        Fixture fixture = transactions.execute(status -> createFixture());
        assertThat(fixture).isNotNull();
        assertNoOrderSideEffects();

        var staleRead = new CompletableFuture<InventoryRead>();
        var firstCommitted = new CompletableFuture<Void>();
        var sequence = new ConcurrentLinkedQueue<String>();
        var staleCompletion = new AtomicInteger(TransactionSynchronization.STATUS_UNKNOWN);
        var worker = Executors.newSingleThreadExecutor();
        Future<?> stale = null;
        Throwable originalFailure = null;
        try {
            stale = worker.submit(() -> {
                try {
                    transactions.executeWithoutResult(status -> {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int completionStatus) {
                                staleCompletion.set(completionStatus);
                                sequence.add("B_COMPLETED:" + completionStatus);
                            }
                        });
                        Inventory inventory = inventoryRepository.findByProductId(fixture.productId()).orElseThrow();
                        assertThat(entityManager.contains(inventory)).isTrue();
                        assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
                        assertThat(inventory.getVersion()).isEqualTo(fixture.version());
                        InventoryRead read = currentRead(inventory);
                        sequence.add("B_READ");
                        staleRead.complete(read);

                        awaitCommit(firstCommitted);

                        // The same managed instance still holds v; no refresh, clear, or reload.
                        assertThat(entityManager.contains(inventory)).isTrue();
                        assertThat(inventory.getVersion()).isEqualTo(fixture.version());
                        assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
                        sequence.add("B_UPDATE");
                        inventory.setAvailableQuantity(7);
                        inventoryRepository.flush();
                    });
                } catch (RuntimeException | Error failure) {
                    // Also unblock the coordinator if B fails before announcing its read.
                    staleRead.completeExceptionally(failure);
                    throw failure;
                }
            });

            InventoryRead readB = staleRead.get(30, TimeUnit.SECONDS);
            InventoryRead readA = transactions.execute(status -> {
                Inventory inventory = inventoryRepository.findByProductId(fixture.productId()).orElseThrow();
                assertThat(inventory.getAvailableQuantity()).isEqualTo(10);
                assertThat(inventory.getVersion()).isEqualTo(fixture.version());
                InventoryRead read = currentRead(inventory);
                sequence.add("A_READ");
                inventory.setAvailableQuantity(8);
                inventoryRepository.flush();
                assertThat(inventory.getVersion()).isEqualTo(fixture.version() + 1);
                sequence.add("A_FLUSHED");
                return read;
            });
            // execute() has returned successfully, so A has committed, not merely flushed.
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(readA).isNotNull();
            assertThat(readA.connectionId()).isNotEqualTo(readB.connectionId());
            assertThat(readA.threadId()).isNotEqualTo(readB.threadId());
            assertThat(readA.version()).isEqualTo(readB.version());
            assertCommittedInventory(fixture);
            sequence.add("A_COMMITTED");
            firstCommitted.complete(null);

            ExecutionException staleFailure = null;
            try {
                stale.get(45, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                staleFailure = failure;
            }
            assertThat(staleFailure).as("B must fail its real version-checked update").isNotNull();
            assertThat(staleFailure.getCause()).isInstanceOf(ObjectOptimisticLockingFailureException.class)
                    .hasRootCauseInstanceOf(StaleStateException.class)
                    .cause().isInstanceOf(StaleObjectStateException.class);
            assertThat(staleCompletion.get()).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThat(sequence).containsExactly("B_READ", "A_READ", "A_FLUSHED", "A_COMMITTED",
                    "B_UPDATE", "B_COMPLETED:" + TransactionSynchronization.STATUS_ROLLED_BACK);
            for (Throwable cause = staleFailure.getCause(); cause != null; cause = cause.getCause()) {
                System.out.println("SHOP-106 exception: " + cause.getClass().getName() + ": " + cause.getMessage());
            }
            System.out.println("SHOP-106 sequence: " + sequence);
            System.out.println("SHOP-106 connections: A=" + readA.connectionId() + ", B=" + readB.connectionId());
            // B's transaction has ended and rolled back; these are fresh database reads.
            assertCommittedInventory(fixture);
            assertNoOrderSideEffects();
        } catch (Exception | Error failure) {
            originalFailure = failure;
            throw failure;
        } finally {
            Throwable cleanupFailure = null;
            boolean interrupted = originalFailure instanceof InterruptedException;
            try {
                if (stale != null) stale.cancel(true);
            } catch (RuntimeException | Error failure) {
                cleanupFailure = failure;
            }
            try {
                worker.shutdownNow();
            } catch (RuntimeException | Error failure) {
                if (cleanupFailure == null) cleanupFailure = failure;
                else cleanupFailure.addSuppressed(failure);
            }
            try {
                assertThat(worker.awaitTermination(10, TimeUnit.SECONDS))
                        .as("inventory worker must terminate before container cleanup").isTrue();
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

    private Fixture createFixture() {
        Category category = Category.builder().name("Headphones").build();
        entityManager.persist(category);
        Product product = Product.builder().name("Sony Head Phone").category(category)
                .price(new BigDecimal("9999.00")).build();
        entityManager.persist(product);
        Inventory inventory = inventoryRepository.saveAndFlush(
                Inventory.builder().product(product).availableQuantity(10).build());
        return new Fixture(product.getId(), inventory.getVersion());
    }

    private InventoryRead currentRead(Inventory inventory) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(jdbcTemplate.queryForObject("select @@transaction_isolation", String.class))
                .isEqualTo("REPEATABLE-READ");
        return new InventoryRead(jdbcTemplate.queryForObject("select connection_id()", Long.class),
                Thread.currentThread().threadId(), inventory.getVersion());
    }

    private void awaitCommit(CompletableFuture<Void> firstCommitted) {
        try {
            firstCommitted.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for A to commit", failure);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("A did not commit within the coordination deadline", failure);
        }
    }

    private void assertCommittedInventory(Fixture fixture) {
        assertThat(jdbcTemplate.queryForObject(
                "select available_quantity from inventories where product_id = ?",
                Integer.class, fixture.productId())).isEqualTo(8);
        assertThat(jdbcTemplate.queryForObject(
                "select version from inventories where product_id = ?",
                Long.class, fixture.productId())).isEqualTo(fixture.version() + 1);
    }

    private void assertNoOrderSideEffects() {
        assertThat(jdbcTemplate.queryForObject("select count(*) from orders", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from order_items", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from stock_reservations", Long.class)).isZero();
    }

    private record Fixture(Long productId, Long version) {}
    private record InventoryRead(Long connectionId, long threadId, Long version) {}
}
