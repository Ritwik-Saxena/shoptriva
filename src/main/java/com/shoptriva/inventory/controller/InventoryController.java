package com.shoptriva.inventory.controller;

import com.shoptriva.inventory.dto.CreateInventoryRequest;
import com.shoptriva.inventory.dto.InventoryResponse;
import com.shoptriva.inventory.dto.StockAdjustmentRequest;
import com.shoptriva.inventory.dto.UpdateStockRequest;
import com.shoptriva.inventory.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping
    public ResponseEntity<InventoryResponse> createInventory(
            @Valid @RequestBody CreateInventoryRequest request
    ) {

        InventoryResponse response =
                inventoryService.createInventory(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping("/products/{productId}")
    public InventoryResponse getInventory(
            @PathVariable Long productId
    ) {

        return inventoryService
                .getInventoryByProductId(productId);
    }

    @PutMapping("/products/{productId}")
    public InventoryResponse updateStock(
            @PathVariable Long productId,
            @Valid @RequestBody UpdateStockRequest request
    ) {

        return inventoryService
                .updateStock(productId, request);
    }

    @PatchMapping("/products/{productId}/increase")
    public InventoryResponse increaseStock(
            @PathVariable Long productId,
            @Valid @RequestBody StockAdjustmentRequest request
    ) {

        return inventoryService
                .increaseStock(productId, request);
    }

    @PatchMapping("/products/{productId}/decrease")
    public InventoryResponse decreaseStock(
            @PathVariable Long productId,
            @Valid @RequestBody StockAdjustmentRequest request
    ) {

        return inventoryService
                .decreaseStock(productId, request);
    }
}
