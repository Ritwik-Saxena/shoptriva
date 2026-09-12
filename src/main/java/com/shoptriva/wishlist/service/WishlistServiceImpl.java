package com.shoptriva.wishlist.service;

import com.shoptriva.auth.entity.User;
import com.shoptriva.auth.service.CurrentUserService;
import com.shoptriva.catalog.entity.Product;
import com.shoptriva.catalog.repository.ProductRepository;
import com.shoptriva.common.exception.ProductNotFoundException;
import com.shoptriva.common.exception.WishlistItemNotFoundException;
import com.shoptriva.wishlist.dto.AddToWishlistRequest;
import com.shoptriva.wishlist.dto.WishlistResponse;
import com.shoptriva.wishlist.entity.Wishlist;
import com.shoptriva.wishlist.entity.WishlistItem;
import com.shoptriva.wishlist.mapper.WishlistMapper;
import com.shoptriva.wishlist.repository.WishlistItemRepository;
import com.shoptriva.wishlist.repository.WishlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WishlistServiceImpl implements WishlistService {

    private final WishlistRepository wishlistRepository;
    private final WishlistItemRepository wishlistItemRepository;
    private final ProductRepository productRepository;
    private final CurrentUserService currentUserService;

    @Override
    public WishlistResponse getWishlist() {

        Long userId = currentUserService.getCurrentUserId();

        return wishlistRepository.findByUserId(userId)
                .map(this::buildWishlistResponse)
                .orElseGet(this::emptyWishlistResponse);
    }

    @Override
    @Transactional
    public WishlistResponse addItem(AddToWishlistRequest request) {

        User user = currentUserService.getCurrentUser();

        Wishlist wishlist = getOrCreateWishlist(user);

        Product product = productRepository
                .findById(request.getProductId())
                .orElseThrow(() ->
                        new ProductNotFoundException(
                                request.getProductId()
                        )
                );

        boolean alreadyExists = wishlistItemRepository
                .findByWishlistIdAndProductId(
                        wishlist.getId(),
                        product.getId()
                )
                .isPresent();

        if (!alreadyExists) {

            WishlistItem item = WishlistItem.builder()
                    .wishlist(wishlist)
                    .product(product)
                    .build();

            wishlistItemRepository.save(item);
        }

        return buildWishlistResponse(wishlist);
    }

    @Override
    @Transactional
    public WishlistResponse removeItem(Long itemId) {

        Long userId = currentUserService.getCurrentUserId();

        Wishlist wishlist = wishlistRepository
                .findByUserId(userId)
                .orElseThrow(() ->
                        new WishlistItemNotFoundException(itemId)
                );

        WishlistItem item = wishlistItemRepository
                .findByIdAndWishlistId(
                        itemId,
                        wishlist.getId()
                )
                .orElseThrow(() ->
                        new WishlistItemNotFoundException(itemId)
                );

        wishlistItemRepository.delete(item);

        return buildWishlistResponse(wishlist);
    }

    @Override
    @Transactional
    public WishlistResponse clearWishlist() {

        Long userId = currentUserService.getCurrentUserId();

        return wishlistRepository
                .findByUserId(userId)
                .map(wishlist -> {

                    wishlistItemRepository.deleteByWishlistId(
                            wishlist.getId()
                    );

                    return WishlistMapper.toResponse(
                            wishlist,
                            List.of()
                    );
                })
                .orElseGet(this::emptyWishlistResponse);
    }

    private Wishlist getOrCreateWishlist(User user) {

        return wishlistRepository
                .findByUserId(user.getId())
                .orElseGet(() -> {

                    Wishlist wishlist = Wishlist.builder()
                            .user(user)
                            .build();

                    return wishlistRepository.save(wishlist);
                });
    }

    private WishlistResponse buildWishlistResponse(
            Wishlist wishlist
    ) {

        List<WishlistItem> items =
                wishlistItemRepository.findByWishlistId(
                        wishlist.getId()
                );

        return WishlistMapper.toResponse(
                wishlist,
                items
        );
    }

    private WishlistResponse emptyWishlistResponse() {

        return WishlistResponse.builder()
                .wishlistId(null)
                .items(List.of())
                .totalItems(0)
                .build();
    }
}
