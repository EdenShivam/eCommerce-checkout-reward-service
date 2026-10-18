package com.uniblox.checkout.repository;
import com.uniblox.checkout.domain.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface CartRepository extends JpaRepository<Cart, UUID> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from Cart c where c.id = :id") Optional<Cart> findByIdForUpdate(@Param("id") UUID id);
}
