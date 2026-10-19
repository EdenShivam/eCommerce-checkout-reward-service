package com.uniblox.checkout.api;

import com.uniblox.checkout.domain.Cart;
import com.uniblox.checkout.domain.CartStatus;
import com.uniblox.checkout.domain.Product;
import com.uniblox.checkout.domain.Order;
import com.uniblox.checkout.domain.OrderLine;
import com.fasterxml.jackson.databind.JsonNode;
import com.uniblox.checkout.repository.OrderRepository;
import com.uniblox.checkout.repository.ProductRepository;
import com.uniblox.checkout.service.CartService;
import com.uniblox.checkout.service.CheckoutFacade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping
public class StoreController {
    private final ProductRepository products;
    private final CartService carts;
    private final OrderRepository orderRepository;
    private final CheckoutFacade checkoutFacade;

    public StoreController(ProductRepository products, CartService carts, OrderRepository orderRepository, CheckoutFacade checkoutFacade) {
        this.products = products;
        this.carts = carts;
        this.orderRepository = orderRepository;
        this.checkoutFacade = checkoutFacade;
    }

    @GetMapping("/products")
    List<ProductView> products() {
        return products.findAll().stream().map(ProductView::from).toList();
    }

    @GetMapping("/products/{id}")
    ProductView product(@PathVariable long id) {
        return ProductView.from(products.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product not found")));
    }

    @PostMapping("/carts")
    @ResponseStatus(HttpStatus.CREATED)
    CartView create() {
        return CartView.from(carts.create(), carts);
    }

    @GetMapping("/carts/{id}")
    CartView cart(@PathVariable UUID id) {
        return CartView.from(carts.get(id), carts);
    }

    @PostMapping("/carts/{id}/items")
    CartView add(@PathVariable UUID id, @Valid @RequestBody ItemRequest request) {
        return CartView.from(carts.add(id, request.productId(), request.quantity()), carts);
    }

    @PutMapping("/carts/{id}/items/{productId}")
    CartView set(@PathVariable UUID id, @PathVariable long productId, @Valid @RequestBody QuantityRequest request) {
        return CartView.from(carts.set(id, productId, request.quantity()), carts);
    }

    @DeleteMapping("/carts/{id}/items/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID id, @PathVariable long productId) {
        carts.remove(id, productId);
    }

    @PostMapping("/carts/{id}/checkout")
    ResponseEntity<?> checkout(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) CheckoutRequest request) {
            var result = checkoutFacade.checkoutResult(id, idempotencyKey,
                request == null ? null : optionalString(request.couponCode(), "couponCode"),
                request == null ? null : optionalMoney(request.expectedTotal()));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(OrderView.from(result.order()));
    }

    @GetMapping("/orders/{orderId}")
    OrderView order(@PathVariable UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found"));
        return OrderView.from(order);
    }

    record ItemRequest(@Positive long productId, @Positive int quantity) {}
    record QuantityRequest(@Positive int quantity) {}
    record CheckoutRequest(JsonNode couponCode, JsonNode expectedTotal) {}

    record ProductView(long id, String name, String unitPrice, int stock) {
        static ProductView from(Product product) {
            return new ProductView(product.getId(), product.getName(), money(product.getUnitPrice()), product.getStock());
        }
    }

    record CartLineView(long productId, String productName, int quantity, String unitPrice, String lineTotal) {}

    record CartView(UUID id, CartStatus status, List<CartLineView> items, String subtotal) {
        static CartView from(Cart cart, CartService service) {
            return new CartView(
                    cart.getId(),
                    cart.getStatus(),
                    cart.getItems().stream().map(item -> {
                        Product product = service.productById(item.getProductId());
                        BigDecimal total = product.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())).setScale(2);
                        return new CartLineView(product.getId(), product.getName(), item.getQuantity(), money(product.getUnitPrice()), money(total));
                    }).toList(),
                    money(service.subtotal(cart))
            );
        }
    }

    record OrderLineView(Long productId, String productName, int quantity, String unitPrice, String lineSubtotal) {
        static OrderLineView from(OrderLine line) {
            return new OrderLineView(line.getProductId(), line.getProductName(), line.getQuantity(), money(line.getUnitPrice()), money(line.getLineSubtotal()));
        }
    }

    record OrderView(UUID id, UUID cartId, String status, List<OrderLineView> lines, String gross, String discount,
                     String net, String couponCode, String discountPercent, java.time.Instant createdAt) {
        static OrderView from(Order order) {
            return new OrderView(order.getId(), order.getCartId(), order.getStatus(), order.getLines().stream().map(OrderLineView::from).toList(),
                    money(order.getGross()), money(order.getDiscount()), money(order.getNet()), order.getCouponCode(),
                    order.getDiscountPercent() == null ? null : money(order.getDiscountPercent()), order.getCreatedAt());
        }
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String optionalString(JsonNode value, String field) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", field + " must be a string");
        }
        return value.textValue();
    }

    private static BigDecimal optionalMoney(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "expectedTotal must be a string");
        }
        try {
            return new BigDecimal(value.textValue());
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "expectedTotal must be a decimal string");
        }
    }
}
