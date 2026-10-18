package com.uniblox.checkout.api;
import com.uniblox.checkout.domain.*; import com.uniblox.checkout.repository.ProductRepository; import com.uniblox.checkout.service.CartService;
import jakarta.validation.Valid; import jakarta.validation.constraints.*; import org.springframework.http.*; import org.springframework.web.bind.annotation.*;
import java.math.*; import java.util.*;
@RestController @RequestMapping public class StoreController {
 private final ProductRepository products; private final CartService carts; public StoreController(ProductRepository p,CartService c){products=p;carts=c;}
 @GetMapping("/products") List<ProductView> products(){return products.findAll().stream().map(ProductView::from).toList();}
 @GetMapping("/products/{id}") ProductView product(@PathVariable long id){return ProductView.from(products.findById(id).orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND","Product not found")));}
 @PostMapping("/carts") @ResponseStatus(HttpStatus.CREATED) CartView create(){return CartView.from(carts.create(),carts);}
 @GetMapping("/carts/{id}") CartView cart(@PathVariable UUID id){return CartView.from(carts.get(id),carts);}
 @PostMapping("/carts/{id}/items") CartView add(@PathVariable UUID id,@Valid @RequestBody ItemRequest r){return CartView.from(carts.add(id,r.productId(),r.quantity()),carts);}
 @PutMapping("/carts/{id}/items/{productId}") CartView set(@PathVariable UUID id,@PathVariable long productId,@Valid @RequestBody QuantityRequest r){return CartView.from(carts.set(id,productId,r.quantity()),carts);}
 @DeleteMapping("/carts/{id}/items/{productId}") @ResponseStatus(HttpStatus.NO_CONTENT) void remove(@PathVariable UUID id,@PathVariable long productId){carts.remove(id,productId);}
 record ItemRequest(@Positive long productId,@Positive int quantity){} record QuantityRequest(@Positive int quantity){}
 record ProductView(long id,String name,BigDecimal unitPrice,int stock){static ProductView from(Product p){return new ProductView(p.getId(),p.getName(),p.getUnitPrice().setScale(2),p.getStock());}}
 record CartLineView(long productId,String productName,int quantity,BigDecimal unitPrice,BigDecimal lineTotal){}
 record CartView(UUID id,CartStatus status,List<CartLineView> items,BigDecimal subtotal){static CartView from(Cart c,CartService s){return new CartView(c.getId(),c.getStatus(),c.getItems().stream().map(i->{Product p=s.productById(i.getProductId()); BigDecimal total=p.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())).setScale(2); return new CartLineView(p.getId(),p.getName(),i.getQuantity(),p.getUnitPrice().setScale(2),total);}).toList(),s.subtotal(c));}}
}
