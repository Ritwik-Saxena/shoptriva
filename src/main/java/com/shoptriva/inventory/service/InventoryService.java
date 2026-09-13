package com.shoptriva.inventory.service;

import com.shoptriva.inventory.dto.CreateInventoryRequest;
import com.shoptriva.inventory.dto.InventoryResponse;
import com.shoptriva.inventory.dto.StockAdjustmentRequest;
import com.shoptriva.inventory.dto.UpdateStockRequest;

public interface InventoryService {

    InventoryResponse createInventory(
            CreateInventoryRequest request
    );

    InventoryResponse getInventoryByProductId(
            Long productId
    );

    InventoryResponse updateStock(
            Long productId,
            UpdateStockRequest request
    );

    InventoryResponse increaseStock(
            Long productId,
            StockAdjustmentRequest request
    );

    InventoryResponse decreaseStock(
            Long productId,
            StockAdjustmentRequest request
    );
}
