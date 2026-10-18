package com.uniblox.checkout.repository;
import com.uniblox.checkout.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ProductRepository extends JpaRepository<Product, Long> {}
