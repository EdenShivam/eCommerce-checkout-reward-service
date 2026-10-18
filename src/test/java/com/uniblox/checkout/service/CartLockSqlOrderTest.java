package com.uniblox.checkout.service;

import com.uniblox.checkout.config.SqlTraceInspector;
import com.uniblox.checkout.domain.Cart;
import com.uniblox.checkout.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.test.mock.mockito.reset=none")
class CartLockSqlOrderTest {
 @Autowired CartService carts; @Autowired ProductRepository products;
 @Test void locksCartBeforeReadingOrWritingCartItems(){ Cart cart=carts.create(); long product=products.findAll().getFirst().getId(); SqlTraceInspector.clear(); carts.add(cart.getId(),product,1); List<String> sql=SqlTraceInspector.statements(); int lock=index(sql,"from carts", "for update"); int item=index(sql,"cart_items", null); assertThat(lock).isGreaterThanOrEqualTo(0); assertThat(item).isGreaterThan(lock); }
 private int index(List<String> sql,String required,String also){ for(int i=0;i<sql.size();i++) if(sql.get(i).contains(required)&&(also==null||sql.get(i).contains(also))) return i; return -1; }
}
