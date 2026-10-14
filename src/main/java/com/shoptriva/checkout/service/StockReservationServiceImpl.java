package com.shoptriva.checkout.service;

import com.shoptriva.checkout.entity.ReservationStatus;
import com.shoptriva.checkout.entity.StockReservation;
import com.shoptriva.checkout.repository.StockReservationRepository;
import com.shoptriva.common.exception.InsufficientStockException;
import com.shoptriva.common.exception.InventoryNotFoundException;
import com.shoptriva.common.exception.ReservationConflictException;
import com.shoptriva.inventory.entity.Inventory;
import com.shoptriva.inventory.repository.InventoryRepository;
import com.shoptriva.order.entity.Order;
import com.shoptriva.order.entity.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class StockReservationServiceImpl
        implements StockReservationService {

    private final StockReservationRepository reservationRepository;
    private final InventoryRepository inventoryRepository;

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
    @Transactional
    public void commitForOrder(Long orderId) {

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
    @Transactional
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

        List<StockReservation> reservations =
                getReservationsOrThrow(orderId);

        boolean alreadyReleased = reservations.stream()
                .allMatch(r -> r.getStatus() == finalStatus);

        // Idempotent: do not restore stock twice
        if (alreadyReleased) {
            return;
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

        for (StockReservation reservation : reservations) {

            Inventory inventory =
                    reservation.getInventory();

            int restoredQuantity = Math.addExact(
                    inventory.getAvailableQuantity(),
                    reservation.getQuantity()
            );

            inventory.setAvailableQuantity(
                    restoredQuantity
            );

            reservation.setStatus(finalStatus);
        }

        // Hibernate flushes all pending entity changes.
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
