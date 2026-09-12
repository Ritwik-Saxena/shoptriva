package com.shoptriva.wishlist.service;

import com.shoptriva.wishlist.dto.AddToWishlistRequest;
import com.shoptriva.wishlist.dto.WishlistResponse;

public interface WishlistService {

    WishlistResponse getWishlist();

    WishlistResponse addItem(AddToWishlistRequest request);

    WishlistResponse removeItem(Long itemId);

    WishlistResponse clearWishlist();
}
