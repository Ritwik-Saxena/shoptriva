package com.shoptriva.inventory.service;

import com.shoptriva.catalog.entity.Product;
import com.shoptriva.catalog.repository.ProductRepository;
import com.shoptriva.common.exception.DuplicateInventoryException;
import com.shoptriva.common.exception.InsufficientStockException;
import com.shoptriva.inventory.dto.CreateInventoryRequest;
import com.shoptriva.inventory.dto.InventoryResponse;
import com.shoptriva.inventory.dto.StockAdjustmentRequest;
import com.shoptriva.inventory.dto.UpdateStockRequest;
import com.shoptriva.inventory.entity.Inventory;
import com.shoptriva.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private InventoryServiceImpl inventoryService;

    private Product product;
    private Inventory inventory;

    @BeforeEach
    void setUp() {

        product = Product.builder()
                .id(1L)
                .name("iPhone")
                .description("Test product")
                .price(new BigDecimal("69999.00"))
                .build();

        inventory = Inventory.builder()
                .id(10L)
                .product(product)
                .availableQuantity(10)
                .version(0L)
                .build();
    }

    @Test
    void createInventory_shouldCreateInventory_whenProductExists() {

        CreateInventoryRequest request =
                new CreateInventoryRequest();

        request.setProductId(1L);
        request.setAvailableQuantity(20);

        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product));

        when(inventoryRepository.findByProductId(1L))
                .thenReturn(Optional.empty());

        when(inventoryRepository.save(any(Inventory.class)))
                .thenAnswer(invocation -> {

                    Inventory savedInventory =
                            invocation.getArgument(0);

                    savedInventory.setId(10L);
                    savedInventory.setVersion(0L);

                    return savedInventory;
                });

        InventoryResponse response =
                inventoryService.createInventory(request);

        assertThat(response.getInventoryId())
                .isEqualTo(10L);

        assertThat(response.getProductId())
                .isEqualTo(1L);

        assertThat(response.getAvailableQuantity())
                .isEqualTo(20);

        verify(inventoryRepository)
                .save(any(Inventory.class));
    }

    @Test
    void createInventory_shouldRejectDuplicateInventory() {

        CreateInventoryRequest request =
                new CreateInventoryRequest();

        request.setProductId(1L);
        request.setAvailableQuantity(20);

        when(productRepository.findById(1L))
                .thenReturn(Optional.of(product));

        when(inventoryRepository.findByProductId(1L))
                .thenReturn(Optional.of(inventory));

        assertThatThrownBy(() ->
                inventoryService.createInventory(request))
                .isInstanceOf(DuplicateInventoryException.class)
                .hasMessageContaining("1");

        verify(inventoryRepository, never())
                .save(any(Inventory.class));
    }

    @Test
    void updateStock_shouldSetExactQuantity() {

        UpdateStockRequest request =
                new UpdateStockRequest();

        request.setAvailableQuantity(25);

        when(inventoryRepository.findByProductId(1L))
                .thenReturn(Optional.of(inventory));

        InventoryResponse response =
                inventoryService.updateStock(
                        1L,
                        request
                );

        assertThat(response.getAvailableQuantity())
                .isEqualTo(25);

        assertThat(inventory.getAvailableQuantity())
                .isEqualTo(25);

        verify(inventoryRepository)
                .flush();
    }

    @Test
    void increaseStock_shouldIncreaseAvailableQuantity() {

        StockAdjustmentRequest request =
                new StockAdjustmentRequest();

        request.setQuantity(5);

        when(inventoryRepository.findByProductId(1L))
                .thenReturn(Optional.of(inventory));

        InventoryResponse response =
                inventoryService.increaseStock(
                        1L,
                        request
                );

        assertThat(response.getAvailableQuantity())
                .isEqualTo(15);

        assertThat(inventory.getAvailableQuantity())
                .isEqualTo(15);

        verify(inventoryRepository)
                .flush();
    }

    @Test
    void decreaseStock_shouldDecreaseAvailableQuantity() {

        StockAdjustmentRequest request =
                new StockAdjustmentRequest();

        request.setQuantity(4);

        when(inventoryRepository.findByProductId(1L))
                .thenReturn(Optional.of(inventory));

        InventoryResponse response =
                inventoryService.decreaseStock(
                        1L,
                        request
                );

        assertThat(response.getAvailableQuantity())
                .isEqualTo(6);

        assertThat(inventory.getAvailableQuantity())
                .isEqualTo(6);

        verify(inventoryRepository)
                .flush();
    }

    @Test
    void decreaseStock_shouldThrowException_whenStockIsInsufficient() {

        StockAdjustmentRequest request =
                new StockAdjustmentRequest();

        request.setQuantity(15);

        when(inventoryRepository.findByProductId(1L))
                .thenReturn(Optional.of(inventory));

        assertThatThrownBy(() ->
                inventoryService.decreaseStock(
                        1L,
                        request
                ))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("15")
                .hasMessageContaining("10");

        assertThat(inventory.getAvailableQuantity())
                .isEqualTo(10);

        verify(inventoryRepository, never())
                .flush();
    }
}
