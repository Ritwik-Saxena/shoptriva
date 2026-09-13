package com.shoptriva.inventory.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class InventoryResponse {

    Long inventoryId;
    Long productId;
    String productName;
    Integer availableQuantity;
    Long version;
}
