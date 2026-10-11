package com.shoptriva.checkout.repository;

import com.shoptriva.checkout.entity.ReservationStatus;
import com.shoptriva.checkout.entity.StockReservation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StockReservationRepository
        extends JpaRepository<StockReservation, Long> {

    // Find all reservations belonging to an order
    List<StockReservation> findByOrderIdOrderByIdAsc(
            Long orderId
    );

    // Find a specific product inventory reservation for an order
    Optional<StockReservation> findByOrderIdAndInventoryId(
            Long orderId,
            Long inventoryId
    );

    // Find reservations that have reached their expiry time
    List<StockReservation>
    findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
            ReservationStatus status,
            Instant expiresAt,
            Pageable pageable
    );
}
