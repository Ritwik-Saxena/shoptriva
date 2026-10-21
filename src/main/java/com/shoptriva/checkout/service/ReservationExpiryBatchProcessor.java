package com.shoptriva.checkout.service;

import com.shoptriva.checkout.config.ReservationExpiryProperties;
import com.shoptriva.checkout.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationExpiryBatchProcessor {

    private final StockReservationRepository reservationRepository;
    private final StockReservationService reservationService;
    private final ReservationExpiryProperties properties;

    // Intentionally not transactional: each service call owns its commit/rollback.
    public BatchResult processBatch() {
        var orderIds = reservationRepository.findOrderIdsReadyForExpiry(
                Instant.now(), PageRequest.of(0, properties.getBatchSize()));
        int expired = 0;
        int failed = 0;
        for (Long orderId : orderIds) {
            try {
                if (reservationService.expireForOrder(orderId)) expired++;
            } catch (RuntimeException failure) {
                failed++;
                // No retry inside a failed transaction. The next batch can retry this order.
                log.warn("Reservation expiry failed for order {}", orderId, failure);
            }
        }
        return new BatchResult(orderIds.size(), expired, failed);
    }

    public record BatchResult(int attempted, int expired, int failed) {}
}
