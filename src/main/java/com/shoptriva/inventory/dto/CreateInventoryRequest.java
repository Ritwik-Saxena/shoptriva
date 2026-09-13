package com.shoptriva.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateInventoryRequest {

    @NotNull
    private Long productId;

    @NotNull
    @Min(0)
    private Integer availableQuantity;
}
