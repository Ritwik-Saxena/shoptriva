package com.shoptriva.checkout.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "shoptriva.reservation-expiry")
public class ReservationExpiryProperties {

    @Min(1)
    @Max(1000)
    private int batchSize = 100;
}
