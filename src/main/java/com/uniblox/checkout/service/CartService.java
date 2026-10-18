package com.uniblox.checkout.service;
import com.uniblox.checkout.api.ApiException; import com.uniblox.checkout.domain.*; import com.uniblox.checkout.repository.*;
import org.springframework.http.HttpStatus; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import java.math.*; import java.util.*;
@Service public class CartService {
 private final CartRepository carts; private final ProductRepository products;
 public CartService(CartRepository c,ProductRepository p){carts=c;products=p;}
 @Transactional public Cart create(){return carts.save(new Cart(UUID.randomUUID()));}
 @Transactional(readOnly=true) public Cart get(UUID id){return carts.findById(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"CART_NOT_FOUND","Cart not found"));}
 @Transactional public Cart add(UUID cartId,long productId,int quantity){ Cart cart=lockedOpen(cartId); Product p=product(productId); CartItem line=cart.findItem(productId); int result; try {result=Math.addExact(line==null?0:line.getQuantity(),quantity);}catch(ArithmeticException e){throw validation("Quantity is too large");} checkQuantity(result,p); cart.add(p,quantity); return cart; }
 @Transactional public Cart set(UUID cartId,long productId,int quantity){ Cart cart=lockedOpen(cartId); Product p=product(productId); if(cart.findItem(productId)==null) throw new ApiException(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND","Product is not in cart"); checkQuantity(quantity,p); cart.set(productId,quantity); return cart; }
 @Transactional public void remove(UUID cartId,long productId){ Cart cart=lockedOpen(cartId); if(cart.findItem(productId)==null) throw new ApiException(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND","Product is not in cart"); cart.remove(productId); }
 private Cart lockedOpen(UUID id){ Cart c=carts.findByIdForUpdate(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"CART_NOT_FOUND","Cart not found")); if(c.getStatus()!=CartStatus.OPEN) throw new ApiException(HttpStatus.CONFLICT,"CART_ALREADY_CHECKED_OUT","Cart has already been checked out"); return c; }
 private Product product(long id){return products.findById(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND","Product not found"));}
 private void checkQuantity(int q,Product p){if(q<=0||q>1_000_000)throw validation("Quantity must be between 1 and 1000000"); if(q>p.getStock())throw new ApiException(HttpStatus.CONFLICT,"INSUFFICIENT_STOCK","Available quantity: "+p.getStock());}
 private ApiException validation(String m){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED",m);}
 public BigDecimal subtotal(Cart cart){return cart.getItems().stream().map(i->product(i.getProductId()).getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity()))).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2,RoundingMode.HALF_UP);}
}
