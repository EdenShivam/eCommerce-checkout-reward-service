package com.uniblox.checkout.service;

import com.uniblox.checkout.config.SqlTraceInspector;
import com.uniblox.checkout.domain.Cart;
import com.uniblox.checkout.config.SqlTraceInspector.SqlStatement;
import com.uniblox.checkout.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
	"spring.test.mock.mockito.reset=none",
	"spring.datasource.url=jdbc:h2:mem:cart-lock-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
class CartLockSqlOrderTest {
 @Autowired CartService carts; @Autowired ProductRepository products;
 @Test void locksCartBeforeReadingOrWritingCartItems(){
  Cart cart=carts.create(); long product=products.findAll().getFirst().getId(); SqlTraceInspector.clear(); carts.add(cart.getId(),product,1);
  List<SqlStatement> events=SqlTraceInspector.events();
  List<SqlStatement> transactionEvents=events.stream().filter(event->Objects.equals(event.transactionName(),"com.uniblox.checkout.service.CartService.add")).toList();
  assertThat(transactionEvents).isNotEmpty();
  String thread=transactionEvents.getFirst().threadName();
  assertThat(transactionEvents).allSatisfy(event->{assertThat(event.threadName()).isEqualTo(thread);assertThat(event.transactionName()).isEqualTo("com.uniblox.checkout.service.CartService.add");});
  int firstCart=IntStream.range(0,transactionEvents.size()).filter(index->touchesCartTable(transactionEvents.get(index).sql())).findFirst().orElse(-1);
  assertThat(firstCart).isGreaterThanOrEqualTo(0);
  assertThat(transactionEvents.get(firstCart).sql()).contains("for update");
  int lock=IntStream.range(0,transactionEvents.size()).filter(index->transactionEvents.get(index).sql().contains("from carts")&&transactionEvents.get(index).sql().contains("for update")).findFirst().orElse(-1);
  int item=IntStream.range(0,transactionEvents.size()).filter(index->transactionEvents.get(index).sql().contains("cart_items")).findFirst().orElse(-1);
  assertThat(lock).isEqualTo(firstCart);
  assertThat(item).isGreaterThan(lock);
 }
 private boolean touchesCartTable(String sql){return sql.matches("(?s).*\\b(from|update|into|join|delete from)\\s+`?carts`?\\b.*");}
}
