package com.shoptriva.inventory.mapper;

import com.shoptriva.inventory.dto.InventoryResponse;
import com.shoptriva.inventory.entity.Inventory;

public final class InventoryMapper {

    private InventoryMapper() {
    }

    public static InventoryResponse toResponse(
            Inventory inventory
    ) {

        return InventoryResponse.builder()
                .inventoryId(inventory.getId())
                .productId(inventory.getProduct().getId())
                .productName(inventory.getProduct().getName())
                .availableQuantity(inventory.getAvailableQuantity())
                .version(inventory.getVersion())
                .build();
    }
}
