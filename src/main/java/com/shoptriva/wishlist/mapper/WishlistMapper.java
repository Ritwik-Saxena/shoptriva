package com.shoptriva.wishlist.mapper;

import com.shoptriva.wishlist.dto.WishlistItemResponse;
import com.shoptriva.wishlist.dto.WishlistResponse;
import com.shoptriva.wishlist.entity.Wishlist;
import com.shoptriva.wishlist.entity.WishlistItem;

import java.util.List;

public final class WishlistMapper {

    private WishlistMapper() {
    }

    public static WishlistItemResponse toItemResponse(
            WishlistItem item
    ) {
        return WishlistItemResponse.builder()
                .itemId(item.getId())
                .productId(item.getProduct().getId())
                .productName(item.getProduct().getName())
                .price(item.getProduct().getPrice())
                .build();
    }

    public static WishlistResponse toResponse(
            Wishlist wishlist,
            List<WishlistItem> items
    ) {
        List<WishlistItemResponse> itemResponses =
                items.stream()
                        .map(WishlistMapper::toItemResponse)
                        .toList();

        return WishlistResponse.builder()
                .wishlistId(wishlist.getId())
                .items(itemResponses)
                .totalItems(itemResponses.size())
                .build();
    }
}
