package com.uniblox.checkout.repository;

import com.uniblox.checkout.domain.Cart;
import com.uniblox.checkout.domain.CartStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.id = :id")
    Optional<Cart> findByIdForUpdate(@Param("id") UUID id);

    @EntityGraph(attributePaths = "items")
    @Query("select c from Cart c where c.id = :id")
    Optional<Cart> findDetailedById(@Param("id") UUID id);

    @Modifying(flushAutomatically = true)
    @Query("update Cart c set c.status = :newStatus where c.id = :id and c.status = :oldStatus")
    int markCheckedOutIfOpen(@Param("id") UUID id,
                            @Param("newStatus") CartStatus newStatus,
                            @Param("oldStatus") CartStatus oldStatus);
}
