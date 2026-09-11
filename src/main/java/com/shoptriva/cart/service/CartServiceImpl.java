package com.shoptriva.cart.service;

import com.shoptriva.auth.entity.User;
import com.shoptriva.auth.service.CurrentUserService;
import com.shoptriva.cart.dto.AddToCartRequest;
import com.shoptriva.cart.dto.CartResponse;
import com.shoptriva.cart.dto.UpdateCartItemRequest;
import com.shoptriva.cart.entity.Cart;
import com.shoptriva.cart.entity.CartItem;
import com.shoptriva.cart.mapper.CartMapper;
import com.shoptriva.cart.repository.CartItemRepository;
import com.shoptriva.cart.repository.CartRepository;
import com.shoptriva.catalog.entity.Product;
import com.shoptriva.catalog.repository.ProductRepository;
import com.shoptriva.common.exception.CartItemNotFoundException;
import com.shoptriva.common.exception.ProductNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartServiceImpl implements CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final CurrentUserService currentUserService;

    @Override
    public CartResponse getCart() {

        Long userId = currentUserService.getCurrentUserId();

        return cartRepository.findByUserId(userId)
                .map(this::buildCartResponse)
                .orElseGet(this::emptyCartResponse);
    }

    @Override
    @Transactional
    public CartResponse addItem(AddToCartRequest request) {

        User user = currentUserService.getCurrentUser();

        Cart cart = getOrCreateCart(user);

        Product product = productRepository
                .findById(request.getProductId())
                .orElseThrow(() ->
                        new ProductNotFoundException(
                                request.getProductId()
                        )
                );

        CartItem cartItem = cartItemRepository
                .findByCartIdAndProductId(
                        cart.getId(),
                        product.getId()
                )
                .orElse(null);

        if (cartItem == null) {

            cartItem = CartItem.builder()
                    .cart(cart)
                    .product(product)
                    .quantity(request.getQuantity())
                    .build();

        } else {

            cartItem.setQuantity(
                    cartItem.getQuantity()
                            + request.getQuantity()
            );
        }

        cartItemRepository.save(cartItem);

        return buildCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse updateItemQuantity(
            Long itemId,
            UpdateCartItemRequest request) {

        Long userId = currentUserService.getCurrentUserId();

        Cart cart = cartRepository
                .findByUserId(userId)
                .orElseThrow(() ->
                        new CartItemNotFoundException(itemId)
                );

        CartItem cartItem = cartItemRepository
                .findByIdAndCartId(
                        itemId,
                        cart.getId()
                )
                .orElseThrow(() ->
                        new CartItemNotFoundException(itemId)
                );

        cartItem.setQuantity(request.getQuantity());

        cartItemRepository.save(cartItem);

        return buildCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse removeItem(Long itemId) {

        Long userId = currentUserService.getCurrentUserId();

        Cart cart = cartRepository
                .findByUserId(userId)
                .orElseThrow(() ->
                        new CartItemNotFoundException(itemId)
                );

        CartItem cartItem = cartItemRepository
                .findByIdAndCartId(
                        itemId,
                        cart.getId()
                )
                .orElseThrow(() ->
                        new CartItemNotFoundException(itemId)
                );

        cartItemRepository.delete(cartItem);

        return buildCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse clearCart() {

        Long userId = currentUserService.getCurrentUserId();

        return cartRepository
                .findByUserId(userId)
                .map(cart -> {

                    cartItemRepository.deleteByCartId(
                            cart.getId()
                    );

                    return CartMapper.toResponse(
                            cart,
                            List.of()
                    );
                })
                .orElseGet(this::emptyCartResponse);
    }

    private Cart getOrCreateCart(User user) {

        return cartRepository
                .findByUserId(user.getId())
                .orElseGet(() -> {

                    Cart cart = Cart.builder()
                            .user(user)
                            .build();

                    return cartRepository.save(cart);
                });
    }

    private CartResponse buildCartResponse(Cart cart) {

        List<CartItem> items =
                cartItemRepository.findByCartId(
                        cart.getId()
                );

        return CartMapper.toResponse(
                cart,
                items
        );
    }

    private CartResponse emptyCartResponse() {

        return CartResponse.builder()
                .cartId(null)
                .items(List.of())
                .totalItems(0)
                .totalAmount(BigDecimal.ZERO)
                .build();
    }
}