package com.shoptriva.wishlist.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class WishlistItemResponse {

    Long itemId;
    Long productId;
    String productName;
    BigDecimal price;
}
