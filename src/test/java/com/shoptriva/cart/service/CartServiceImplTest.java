package com.shoptriva.cart.service;

import com.shoptriva.auth.entity.Role;
import com.shoptriva.auth.entity.User;
import com.shoptriva.auth.service.CurrentUserService;
import com.shoptriva.cart.dto.AddToCartRequest;
import com.shoptriva.cart.dto.CartResponse;
import com.shoptriva.cart.entity.Cart;
import com.shoptriva.cart.entity.CartItem;
import com.shoptriva.cart.repository.CartItemRepository;
import com.shoptriva.cart.repository.CartRepository;
import com.shoptriva.catalog.entity.Product;
import com.shoptriva.catalog.repository.ProductRepository;
import com.shoptriva.common.exception.CartItemNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartServiceImplTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private CartServiceImpl cartService;

    private User user;
    private Cart cart;
    private Product product;

    @BeforeEach
    void setUp() {

        user = User.builder()
                .id(1L)
                .name("Test User")
                .email("testuser@shoptriva.com")
                .password("encoded-password")
                .role(Role.CUSTOMER)
                .enabled(true)
                .build();

        cart = Cart.builder()
                .id(10L)
                .user(user)
                .build();

        product = Product.builder()
                .id(100L)
                .name("Samsung Galaxy S26")
                .description("Samsung flagship smartphone")
                .price(new BigDecimal("69999.00"))
                .build();
    }
    @Test
    void getCart_shouldReturnEmptyCart_whenUserHasNoCart() {

        when(currentUserService.getCurrentUserId())
                .thenReturn(1L);

        when(cartRepository.findByUserId(1L))
                .thenReturn(Optional.empty());

        CartResponse response = cartService.getCart();

        assertThat(response.getCartId()).isNull();
        assertThat(response.getItems()).isEmpty();
        assertThat(response.getTotalItems()).isZero();
        assertThat(response.getTotalAmount())
                .isEqualByComparingTo(BigDecimal.ZERO);

        verify(cartRepository).findByUserId(1L);
        verifyNoInteractions(cartItemRepository);
    }
    @Test
    void addItem_shouldCreateNewCartItem_whenProductNotAlreadyInCart() {

        AddToCartRequest request = new AddToCartRequest();
        request.setProductId(100L);
        request.setQuantity(2);

        when(currentUserService.getCurrentUser())
                .thenReturn(user);

        when(cartRepository.findByUserId(1L))
                .thenReturn(Optional.of(cart));

        when(productRepository.findById(100L))
                .thenReturn(Optional.of(product));

        when(cartItemRepository.findByCartIdAndProductId(
                10L,
                100L
        )).thenReturn(Optional.empty());

        when(cartItemRepository.findByCartId(10L))
                .thenReturn(List.of(
                        CartItem.builder()
                                .id(200L)
                                .cart(cart)
                                .product(product)
                                .quantity(2)
                                .build()
                ));

        CartResponse response = cartService.addItem(request);

        assertThat(response.getTotalItems())
                .isEqualTo(2);

        assertThat(response.getTotalAmount())
                .isEqualByComparingTo(
                        new BigDecimal("139998.00")
                );

        verify(cartItemRepository)
                .save(any(CartItem.class));
    }
    @Test
    void addItem_shouldIncreaseQuantity_whenProductAlreadyExists() {

        CartItem existingItem = CartItem.builder()
                .id(200L)
                .cart(cart)
                .product(product)
                .quantity(2)
                .build();

        AddToCartRequest request = new AddToCartRequest();
        request.setProductId(100L);
        request.setQuantity(3);

        when(currentUserService.getCurrentUser())
                .thenReturn(user);

        when(cartRepository.findByUserId(1L))
                .thenReturn(Optional.of(cart));

        when(productRepository.findById(100L))
                .thenReturn(Optional.of(product));

        when(cartItemRepository.findByCartIdAndProductId(
                10L,
                100L
        )).thenReturn(Optional.of(existingItem));

        when(cartItemRepository.findByCartId(10L))
                .thenReturn(List.of(existingItem));

        CartResponse response = cartService.addItem(request);

        assertThat(existingItem.getQuantity())
                .isEqualTo(5);

        assertThat(response.getTotalItems())
                .isEqualTo(5);

        assertThat(response.getTotalAmount())
                .isEqualByComparingTo(
                        new BigDecimal("349995.00")
                );

        verify(cartItemRepository)
                .save(existingItem);
    }
    @Test
    void removeItem_shouldRejectItem_whenItDoesNotBelongToCurrentUsersCart() {

        when(currentUserService.getCurrentUserId())
                .thenReturn(1L);

        when(cartRepository.findByUserId(1L))
                .thenReturn(Optional.of(cart));

        when(cartItemRepository.findByIdAndCartId(
                999L,
                10L
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                cartService.removeItem(999L)
        ).isInstanceOf(CartItemNotFoundException.class);

        verify(cartItemRepository, never())
                .delete(any());
    }
}