# Reservation expiry

The expiry worker cancels an abandoned `PENDING` order only when **all** its
reservations are `RESERVED` and their `expires_at` timestamps have been reached.
It restores each reserved quantity, marks those reservations `EXPIRED`, and sets
the order to the existing `CANCELLED` status in one transaction. Mixed deadlines
wait until the last reservation is due; mixed reservation states are not released.

The order row is locked before reading reservations. Expiry, release, and the
existing reservation commit guard follow this protocol. A second worker rechecks
the committed state and does nothing. Inventory and reservation versions remain
enabled; an inventory conflict rolls back the entire order's expiry. No retries
occur inside the failed transaction. A later scheduled batch can retry it.

Checkout idempotency keys remain attached to their orders. Replaying an expired
checkout returns the original order with its current `CANCELLED` status and does
not reserve stock again. No payment endpoints, gateway integration, or payment
processing are provided by this workflow.

## Configuration

Scheduling is disabled unless explicitly enabled:

```properties
shoptriva.reservation-expiry.enabled=true
shoptriva.reservation-expiry.batch-size=100
shoptriva.reservation-expiry.fixed-delay-ms=60000
shoptriva.reservation-expiry.initial-delay-ms=60000
```

Batch size defaults to 100 and must be between 1 and 1000. Both scheduling delays
default to 60 seconds. Each invocation selects at most one batch of distinct
eligible order IDs; each order is processed in its own transaction with a
30-second transaction timeout. The scheduler waits for an invocation to finish
before its next fixed delay. Configure database lock and connection timeouts in
deployment as well; a transaction timeout is not a hard wall-clock batch deadline.

The `idx_reservation_expiry` entity index covers `(status, expires_at, order_id)`.
Deploy this index through the environment's normal schema-change process for an
existing database before enabling the worker. This implementation does not run
production migrations or require a new order status enum value.

Failures are logged with the order ID, and batch summaries include attempted,
expired, and failed counts. A permanently failing order needs investigation
(for example, stock restoration exceeding the integer limit). Since each batch
starts with the lowest eligible IDs, enough permanently failing orders to fill
a batch can delay later orders. Monitor repeated failures; a durable retry queue
or quarantine policy is outside this minimal workflow.

## Verification

`ReservationExpiryIntegrationTest` uses only disposable MySQL/InnoDB containers.
It checks deadlines, repeat processing, stock/version state, cancellation,
idempotency replay, bounded batching, concurrent order-lock contention, rejection
of late reservation commits, and rollback after flushed writes. Scheduling is
registered with a long initial delay in tests so each batch is invoked explicitly.

```text
mvn -Dtest=ReservationExpiryIntegrationTest test
mvn clean test
```
