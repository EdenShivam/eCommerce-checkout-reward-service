package com.uniblox.checkout.service;

import com.uniblox.checkout.api.ApiException;
import com.uniblox.checkout.domain.Cart;
import com.uniblox.checkout.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.test.mock.mockito.reset=none")
class CartServiceIntegrationTest {
 @Autowired CartService carts; @Autowired ProductRepository products;
 @Test void addMergesQuantityAndUsesCurrentPrices(){ Cart cart=carts.create(); long product=products.findAll().getFirst().getId(); carts.add(cart.getId(),product,1); Cart updated=carts.add(cart.getId(),product,2); assertThat(updated.getItems()).singleElement().satisfies(i->assertThat(i.getQuantity()).isEqualTo(3)); assertThat(carts.subtotal(carts.get(cart.getId()))).isEqualByComparingTo("55.50"); }
 @Test void rejectsQuantityAboveStockWithoutChangingCart(){ Cart cart=carts.create(); long product=products.findAll().getFirst().getId(); assertThatThrownBy(()->carts.add(cart.getId(),product,4)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("INSUFFICIENT_STOCK"); assertThat(carts.get(cart.getId()).getItems()).isEmpty(); }
 @Test void putSetsAbsoluteQuantity(){ Cart cart=carts.create(); long product=products.findAll().get(1).getId(); carts.add(cart.getId(),product,1); carts.set(cart.getId(),product,3); assertThat(carts.get(cart.getId()).getItems()).singleElement().satisfies(i->assertThat(i.getQuantity()).isEqualTo(3)); }
}
