package com.shoptriva.wishlist.repository;

import com.shoptriva.wishlist.entity.WishlistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WishlistItemRepository
        extends JpaRepository<WishlistItem, Long> {

    Optional<WishlistItem> findByWishlistIdAndProductId(
            Long wishlistId,
            Long productId
    );

    Optional<WishlistItem> findByIdAndWishlistId(
            Long id,
            Long wishlistId
    );

    List<WishlistItem> findByWishlistId(Long wishlistId);

    void deleteByWishlistId(Long wishlistId);
}
