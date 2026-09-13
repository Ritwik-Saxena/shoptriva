package com.shoptriva.inventory.service;

import com.shoptriva.catalog.entity.Product;
import com.shoptriva.catalog.repository.ProductRepository;
import com.shoptriva.common.exception.DuplicateInventoryException;
import com.shoptriva.common.exception.InsufficientStockException;
import com.shoptriva.common.exception.InventoryNotFoundException;
import com.shoptriva.common.exception.ProductNotFoundException;
import com.shoptriva.inventory.dto.CreateInventoryRequest;
import com.shoptriva.inventory.dto.InventoryResponse;
import com.shoptriva.inventory.dto.StockAdjustmentRequest;
import com.shoptriva.inventory.dto.UpdateStockRequest;
import com.shoptriva.inventory.entity.Inventory;
import com.shoptriva.inventory.mapper.InventoryMapper;
import com.shoptriva.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;

    @Override
    @Transactional
    public InventoryResponse createInventory(
            CreateInventoryRequest request
    ) {

        Long productId = request.getProductId();

        Product product = productRepository
                .findById(productId)
                .orElseThrow(() ->
                        new ProductNotFoundException(productId)
                );

        boolean inventoryAlreadyExists =
                inventoryRepository
                        .findByProductId(productId)
                        .isPresent();

        if (inventoryAlreadyExists) {
            throw new DuplicateInventoryException(productId);
        }

        Inventory inventory = Inventory.builder()
                .product(product)
                .availableQuantity(
                        request.getAvailableQuantity()
                )
                .build();

        Inventory savedInventory =
                inventoryRepository.save(inventory);

        return InventoryMapper.toResponse(savedInventory);
    }

    @Override
    public InventoryResponse getInventoryByProductId(
            Long productId
    ) {

        Inventory inventory =
                getInventoryOrThrow(productId);

        return InventoryMapper.toResponse(inventory);
    }

    @Override
    @Transactional
    public InventoryResponse updateStock(
            Long productId,
            UpdateStockRequest request
    ) {

        Inventory inventory =
                getInventoryOrThrow(productId);

        inventory.setAvailableQuantity(
                request.getAvailableQuantity()
        );

        /*
         * Flush now so Hibernate performs the version-aware UPDATE
         * before we build the response.
         *
         * This also means an optimistic-locking conflict is detected
         * here rather than only at transaction commit.
         */
        inventoryRepository.flush();

        return InventoryMapper.toResponse(inventory);
    }

    @Override
    @Transactional
    public InventoryResponse increaseStock(
            Long productId,
            StockAdjustmentRequest request
    ) {

        Inventory inventory =
                getInventoryOrThrow(productId);

        int updatedQuantity =
                inventory.getAvailableQuantity()
                        + request.getQuantity();

        inventory.setAvailableQuantity(updatedQuantity);

        inventoryRepository.flush();

        return InventoryMapper.toResponse(inventory);
    }

    @Override
    @Transactional
    public InventoryResponse decreaseStock(
            Long productId,
            StockAdjustmentRequest request
    ) {

        Inventory inventory =
                getInventoryOrThrow(productId);

        int availableQuantity =
                inventory.getAvailableQuantity();

        int requestedQuantity =
                request.getQuantity();

        if (requestedQuantity > availableQuantity) {

            throw new InsufficientStockException(
                    productId,
                    requestedQuantity,
                    availableQuantity
            );
        }

        inventory.setAvailableQuantity(
                availableQuantity - requestedQuantity
        );

        inventoryRepository.flush();

        return InventoryMapper.toResponse(inventory);
    }

    private Inventory getInventoryOrThrow(
            Long productId
    ) {

        return inventoryRepository
                .findByProductId(productId)
                .orElseThrow(() ->
                        new InventoryNotFoundException(
                                productId
                        )
                );
    }
}
