package com.shoptriva.checkout.service;

import com.shoptriva.checkout.entity.ReservationStatus;
import com.shoptriva.checkout.entity.StockReservation;
import com.shoptriva.checkout.repository.StockReservationRepository;
import com.shoptriva.common.exception.InsufficientStockException;
import com.shoptriva.common.exception.InventoryNotFoundException;
import com.shoptriva.common.exception.ReservationConflictException;
import com.shoptriva.common.exception.OrderNotFoundException;
import com.shoptriva.common.exception.QuantityLimitException;
import com.shoptriva.inventory.entity.Inventory;
import com.shoptriva.inventory.repository.InventoryRepository;
import com.shoptriva.order.entity.Order;
import com.shoptriva.order.entity.OrderStatus;
import com.shoptriva.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StockReservationServiceImpl
        implements StockReservationService {

    private final StockReservationRepository reservationRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;

    // ==================================================
    // 1. RESERVE STOCK
    // ==================================================

    @Override
    @Transactional
    public StockReservation reserve(
            Order order,
            Long productId,
            int quantity,
            Instant expiresAt
    ) {

        if (order == null
                || order.getId() == null
                || order.getStatus() != OrderStatus.PENDING) {

            throw new ReservationConflictException(
                    "A saved PENDING order is required"
            );
        }

        if (quantity <= 0) {
            throw new ReservationConflictException(
                    "Reservation quantity must be positive"
            );
        }

        // Checkout already owns this newly inserted order's lock. Other callers
        // must not append a reservation to an order that has been cancelled.
        order = lockOrder(order.getId());
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ReservationConflictException("A saved PENDING order is required");
        }

        if (expiresAt == null
                || !expiresAt.isAfter(Instant.now())) {

            throw new ReservationConflictException(
                    "Reservation expiry must be in the future"
            );
        }

        Inventory inventory = inventoryRepository
                .findByProductId(productId)
                .orElseThrow(() ->
                        new InventoryNotFoundException(productId)
                );

        boolean alreadyExists = reservationRepository
                .findByOrderIdAndInventoryId(
                        order.getId(),
                        inventory.getId()
                )
                .isPresent();

        if (alreadyExists) {
            throw new ReservationConflictException(
                    "Reservation already exists for this product"
            );
        }

        int available = inventory.getAvailableQuantity();

        if (quantity > available) {
            throw new InsufficientStockException(
                    productId,
                    quantity,
                    available
            );
        }

        // Reduce the available quantity
        inventory.setAvailableQuantity(
                available - quantity
        );

        StockReservation reservation =
                StockReservation.builder()
                        .order(order)
                        .inventory(inventory)
                        .quantity(quantity)
                        .status(ReservationStatus.RESERVED)
                        .expiresAt(expiresAt)
                        .build();

        StockReservation saved =
                reservationRepository.save(reservation);

        // Forces the version-aware inventory update
        inventoryRepository.flush();

        return saved;
    }

    // ==================================================
    // 2. COMMIT RESERVATIONS AFTER SUCCESSFUL PAYMENT
    // ==================================================

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void commitForOrder(Long orderId) {

        Order order = lockOrder(orderId);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new ReservationConflictException("Cancelled order reservations cannot be committed");
        }

        List<StockReservation> reservations =
                getReservationsOrThrow(orderId);

        // Repeating an already successful commit is harmless
        boolean allCommitted = reservations.stream()
                .allMatch(r ->
                        r.getStatus() == ReservationStatus.COMMITTED
                );

        if (allCommitted) {
            return;
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ReservationConflictException("Order reservations cannot be committed");
        }

        Instant now = Instant.now();

        // Validate all before modifying any reservation
        for (StockReservation reservation : reservations) {

            if (reservation.getStatus()
                    != ReservationStatus.RESERVED) {

                throw new ReservationConflictException(
                        "Order reservations cannot be committed"
                );
            }

            if (!reservation.getExpiresAt().isAfter(now)) {

                throw new ReservationConflictException(
                        "Reservation has expired"
                );
            }
        }

        for (StockReservation reservation : reservations) {
            reservation.setStatus(
                    ReservationStatus.COMMITTED
            );
        }

        // No inventory deduction here!
        reservationRepository.flush();
    }

    // ==================================================
    // 3. RELEASE OR EXPIRE RESERVATIONS
    // ==================================================

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void releaseForOrder(
            Long orderId,
            ReservationStatus finalStatus
    ) {

        if (finalStatus != ReservationStatus.RELEASED
                && finalStatus != ReservationStatus.EXPIRED) {

            throw new ReservationConflictException(
                    "Invalid release status"
            );
        }

        Order order = lockOrder(orderId);
        List<StockReservation> reservations =
                getReservationsOrThrow(orderId);

        boolean alreadyReleased = reservations.stream()
                .allMatch(r -> r.getStatus() == finalStatus);

        // Idempotent: do not restore stock twice
        if (alreadyReleased) {
            return;
        }

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ReservationConflictException("Only pending orders can release reservations");
        }

        Instant now = Instant.now();

        // Validate all reservations before restoring stock
        for (StockReservation reservation : reservations) {

            if (reservation.getStatus()
                    != ReservationStatus.RESERVED) {

                throw new ReservationConflictException(
                        "Reservation cannot be released"
                );
            }

            if (finalStatus == ReservationStatus.EXPIRED
                    && reservation.getExpiresAt().isAfter(now)) {

                throw new ReservationConflictException(
                        "Reservation has not expired yet"
                );
            }
        }

        restoreStock(order, reservations, finalStatus);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 30)
    public boolean expireForOrder(Long orderId) {
        Order order = lockOrder(orderId);
        if (order.getStatus() != OrderStatus.PENDING) return false;

        List<StockReservation> reservations = getReservationsOrThrow(orderId);
        Instant now = Instant.now();
        if (reservations.stream().anyMatch(reservation ->
                reservation.getStatus() != ReservationStatus.RESERVED
                        || reservation.getExpiresAt().isAfter(now))) {
            return false;
        }

        restoreStock(order, reservations, ReservationStatus.EXPIRED);
        return true;
    }

    private Order lockOrder(Long orderId) {
        return orderRepository.findForReservationUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private void restoreStock(Order order, List<StockReservation> reservations,
                              ReservationStatus finalStatus) {
        // Consistent inventory order reduces contention between multi-product orders.
        reservations.sort(java.util.Comparator.comparing(r -> r.getInventory().getId()));
        for (StockReservation reservation : reservations) {
            Inventory inventory = reservation.getInventory();
            int restoredQuantity = QuantityLimitException.add(
                    inventory.getAvailableQuantity(),
                    reservation.getQuantity()
            );

            inventory.setAvailableQuantity(
                    restoredQuantity
            );

            reservation.setStatus(finalStatus);
        }

        order.setStatus(OrderStatus.CANCELLED);
        // Hibernate flushes all pending entity changes, including the order.
        // @Version protects both Inventory and reservations.
        reservationRepository.flush();
    }

    // ==================================================
    // SHARED LOOKUP
    // ==================================================

    private List<StockReservation> getReservationsOrThrow(
            Long orderId
    ) {

        List<StockReservation> reservations =
                reservationRepository
                        .findByOrderIdOrderByIdAsc(orderId);

        if (reservations.isEmpty()) {
            throw new ReservationConflictException(
                    "No reservations found for order " + orderId
            );
        }

        return reservations;
    }
}
