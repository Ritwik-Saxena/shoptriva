package com.shoptriva.checkout.config;

import com.shoptriva.checkout.service.ReservationExpiryBatchProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "shoptriva.reservation-expiry", name = "enabled", havingValue = "true")
public class ReservationExpiryScheduler {

    private final ReservationExpiryBatchProcessor processor;

    @Scheduled(fixedDelayString = "${shoptriva.reservation-expiry.fixed-delay-ms:60000}",
            initialDelayString = "${shoptriva.reservation-expiry.initial-delay-ms:60000}")
    public void expireReservations() {
        var result = processor.processBatch();
        if (result.attempted() > 0) {
            log.info("Reservation expiry: attempted={}, expired={}, failed={}",
                    result.attempted(), result.expired(), result.failed());
        }
    }
}
