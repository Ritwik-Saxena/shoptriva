package com.shoptriva.checkout.repository;

import com.shoptriva.checkout.entity.ReservationStatus;
import com.shoptriva.checkout.entity.StockReservation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StockReservationRepository
        extends JpaRepository<StockReservation, Long> {

    // An order is eligible only when every reservation is still reserved and due.
    // Select IDs rather than managed entities; the worker rechecks under an order lock.
    @Query("""
            select distinct r.order.id from StockReservation r
            where r.status = com.shoptriva.checkout.entity.ReservationStatus.RESERVED
              and r.expiresAt <= :cutoff
              and r.order.status = com.shoptriva.order.entity.OrderStatus.PENDING
              and not exists (
                select other.id from StockReservation other where other.order.id = r.order.id
                  and (other.status <> com.shoptriva.checkout.entity.ReservationStatus.RESERVED
                       or other.expiresAt > :cutoff))
            order by r.order.id
            """)
    List<Long> findOrderIdsReadyForExpiry(@Param("cutoff") Instant cutoff, Pageable pageable);

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
