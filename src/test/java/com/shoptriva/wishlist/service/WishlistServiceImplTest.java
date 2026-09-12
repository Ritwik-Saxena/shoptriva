package com.shoptriva.wishlist.service;

import com.shoptriva.auth.entity.Role;
import com.shoptriva.auth.entity.User;
import com.shoptriva.auth.service.CurrentUserService;
import com.shoptriva.catalog.entity.Product;
import com.shoptriva.catalog.repository.ProductRepository;
import com.shoptriva.common.exception.WishlistItemNotFoundException;
import com.shoptriva.wishlist.dto.AddToWishlistRequest;
import com.shoptriva.wishlist.dto.WishlistResponse;
import com.shoptriva.wishlist.entity.Wishlist;
import com.shoptriva.wishlist.entity.WishlistItem;
import com.shoptriva.wishlist.repository.WishlistItemRepository;
import com.shoptriva.wishlist.repository.WishlistRepository;
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
class WishlistServiceImplTest {

    @Mock
    private WishlistRepository wishlistRepository;

    @Mock
    private WishlistItemRepository wishlistItemRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CurrentUserService currentUserService;

    @InjectMocks
    private WishlistServiceImpl wishlistService;

    private User user;
    private Wishlist wishlist;
    private Product product;
    private WishlistItem wishlistItem;

    @BeforeEach
    void setUp() {

        user = User.builder()
                .id(1L)
                .name("Test Customer")
                .email("customer@test.com")
                .password("password")
                .role(Role.CUSTOMER)
                .enabled(true)
                .build();

        wishlist = Wishlist.builder()
                .id(10L)
                .user(user)
                .build();

        product = Product.builder()
                .id(100L)
                .name("iPhone")
                .description("Test product")
                .price(new BigDecimal("69999.00"))
                .build();

        wishlistItem = WishlistItem.builder()
                .id(1000L)
                .wishlist(wishlist)
                .product(product)
                .build();
    }

    @Test
    void getWishlist_shouldReturnEmptyWishlist_whenUserHasNoWishlist() {

        when(currentUserService.getCurrentUserId())
                .thenReturn(1L);

        when(wishlistRepository.findByUserId(1L))
                .thenReturn(Optional.empty());

        WishlistResponse response =
                wishlistService.getWishlist();

        assertThat(response.getWishlistId()).isNull();
        assertThat(response.getItems()).isEmpty();
        assertThat(response.getTotalItems()).isZero();
    }

    @Test
    void addItem_shouldCreateWishlistItem_whenProductNotAlreadyPresent() {

        AddToWishlistRequest request =
                new AddToWishlistRequest();

        request.setProductId(100L);

        when(currentUserService.getCurrentUser())
                .thenReturn(user);

        when(wishlistRepository.findByUserId(1L))
                .thenReturn(Optional.of(wishlist));

        when(productRepository.findById(100L))
                .thenReturn(Optional.of(product));

        when(wishlistItemRepository
                .findByWishlistIdAndProductId(10L, 100L))
                .thenReturn(Optional.empty());

        when(wishlistItemRepository.findByWishlistId(10L))
                .thenReturn(List.of(wishlistItem));

        WishlistResponse response =
                wishlistService.addItem(request);

        assertThat(response.getWishlistId()).isEqualTo(10L);
        assertThat(response.getTotalItems()).isEqualTo(1);
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().getFirst().getProductId())
                .isEqualTo(100L);

        verify(wishlistItemRepository)
                .save(any(WishlistItem.class));
    }

    @Test
    void addItem_shouldNotCreateDuplicate_whenProductAlreadyExists() {

        AddToWishlistRequest request =
                new AddToWishlistRequest();

        request.setProductId(100L);

        when(currentUserService.getCurrentUser())
                .thenReturn(user);

        when(wishlistRepository.findByUserId(1L))
                .thenReturn(Optional.of(wishlist));

        when(productRepository.findById(100L))
                .thenReturn(Optional.of(product));

        when(wishlistItemRepository
                .findByWishlistIdAndProductId(10L, 100L))
                .thenReturn(Optional.of(wishlistItem));

        when(wishlistItemRepository.findByWishlistId(10L))
                .thenReturn(List.of(wishlistItem));

        WishlistResponse response =
                wishlistService.addItem(request);

        assertThat(response.getTotalItems()).isEqualTo(1);

        verify(wishlistItemRepository, never())
                .save(any(WishlistItem.class));
    }

    @Test
    void removeItem_shouldRejectItem_whenItDoesNotBelongToCurrentUsersWishlist() {

        when(currentUserService.getCurrentUserId())
                .thenReturn(1L);

        when(wishlistRepository.findByUserId(1L))
                .thenReturn(Optional.of(wishlist));

        when(wishlistItemRepository
                .findByIdAndWishlistId(999L, 10L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                wishlistService.removeItem(999L))
                .isInstanceOf(WishlistItemNotFoundException.class)
                .hasMessageContaining("999");

        verify(wishlistItemRepository, never())
                .delete(any(WishlistItem.class));
    }

    @Test
    void clearWishlist_shouldDeleteAllItems() {

        when(currentUserService.getCurrentUserId())
                .thenReturn(1L);

        when(wishlistRepository.findByUserId(1L))
                .thenReturn(Optional.of(wishlist));

        WishlistResponse response =
                wishlistService.clearWishlist();

        verify(wishlistItemRepository)
                .deleteByWishlistId(10L);

        assertThat(response.getWishlistId()).isEqualTo(10L);
        assertThat(response.getItems()).isEmpty();
        assertThat(response.getTotalItems()).isZero();
    }
}
