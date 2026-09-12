package com.shoptriva.wishlist.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class WishlistResponse {

    Long wishlistId;
    List<WishlistItemResponse> items;
    Integer totalItems;
}
