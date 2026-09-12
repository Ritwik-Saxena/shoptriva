package com.shoptriva.wishlist.controller;

import com.shoptriva.wishlist.dto.AddToWishlistRequest;
import com.shoptriva.wishlist.dto.WishlistResponse;
import com.shoptriva.wishlist.service.WishlistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wishlist")
@RequiredArgsConstructor
public class WishlistController {

    private final WishlistService wishlistService;

    @GetMapping
    public WishlistResponse getWishlist() {
        return wishlistService.getWishlist();
    }

    @PostMapping("/items")
    public WishlistResponse addItem(
            @Valid @RequestBody AddToWishlistRequest request
    ) {
        return wishlistService.addItem(request);
    }

    @DeleteMapping("/items/{itemId}")
    public WishlistResponse removeItem(
            @PathVariable Long itemId
    ) {
        return wishlistService.removeItem(itemId);
    }

    @DeleteMapping
    public WishlistResponse clearWishlist() {
        return wishlistService.clearWishlist();
    }
}
