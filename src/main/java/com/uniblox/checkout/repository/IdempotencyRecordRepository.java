package com.uniblox.checkout.repository;
import com.uniblox.checkout.domain.IdempotencyRecord; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Modifying; import org.springframework.data.jpa.repository.Query; import org.springframework.data.repository.query.Param; import java.util.*;
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord,Long>{Optional<IdempotencyRecord> findByCartIdAndKey(UUID cartId,String key);
 @Modifying(flushAutomatically = true)
 @Query("update IdempotencyRecord record set record.orderId = :orderId where record.id = :recordId")
 int completeOrder(@Param("recordId") Long recordId, @Param("orderId") UUID orderId);
}
