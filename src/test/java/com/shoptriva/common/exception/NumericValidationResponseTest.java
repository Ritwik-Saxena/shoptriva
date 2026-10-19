package com.shoptriva.common.exception;

import com.shoptriva.catalog.controller.ProductController;
import com.shoptriva.catalog.service.ProductService;
import com.shoptriva.cart.controller.CartController;
import com.shoptriva.cart.service.CartService;
import com.shoptriva.inventory.controller.InventoryController;
import com.shoptriva.inventory.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NumericValidationResponseTest {

    private ProductService products;
    private CartService carts;
    private InventoryService inventory;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        products = mock(ProductService.class);
        carts = mock(CartService.class);
        inventory = mock(InventoryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ProductController(products),
                        new CartController(carts), new InventoryController(inventory))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "12.345", "10000000000.00"})
    void product_shouldRejectUnrepresentablePriceBeforePersistence(String price) throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(price)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value("/api/products"));
        verifyNoInteractions(products);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "9999999999.99"})
    void product_shouldAcceptColumnBoundaries(String price) throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
                        .content(productJson(price)))
                .andExpect(status().isOk());
        verify(products).createProduct(any());
    }

    @Test
    void inventoryOverflow_shouldUseExistingValidationErrorFormat() throws Exception {
        when(inventory.increaseStock(eq(1L), any())).thenThrow(new QuantityLimitException());
        mvc.perform(patch("/api/inventory/products/1/increase").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Quantity exceeds the supported limit"));
    }

    @Test
    void cartOverflow_shouldUseExistingValidationErrorFormat() throws Exception {
        when(carts.addItem(any())).thenThrow(new QuantityLimitException());
        mvc.perform(post("/api/cart/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Quantity exceeds the supported limit"));
    }

    private String productJson(String price) {
        return "{\"name\":\"Headphones\",\"categoryId\":1,\"price\":" + price + "}";
    }
}
