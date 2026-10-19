package com.uniblox.checkout.service;

import com.uniblox.checkout.repository.IdempotencyRecordRepository;
import com.uniblox.checkout.repository.OrderRepository;
import com.uniblox.checkout.domain.IdempotencyRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.util.UUID;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CheckoutFacadeClassificationTest {
    @Test
    void unrelatedIntegrityViolationIsNotTreatedAsIdempotencyReplay() {
        CheckoutTransactionalService transactionalService = mock(CheckoutTransactionalService.class);
        IdempotencyRecordRepository idempotencyRecords = mock(IdempotencyRecordRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        CheckoutRequestHasher hasher = new CheckoutRequestHasher();
        DataIntegrityViolationException original = new DataIntegrityViolationException("foreign key violation on idempotency_records.order_id");
        when(transactionalService.execute(org.mockito.ArgumentMatchers.any())).thenThrow(original);
        CheckoutFacade facade = new CheckoutFacade(transactionalService, idempotencyRecords, orders, hasher);

        assertThatThrownBy(() -> facade.checkout(UUID.randomUUID(), "classification-test", null, null))
                .isSameAs(original);
    }

    @Test
    void recognizedLockFailureBecomesRetryableConflict() {
        CheckoutTransactionalService transactionalService = mock(CheckoutTransactionalService.class);
        IdempotencyRecordRepository idempotencyRecords = mock(IdempotencyRecordRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        CheckoutFacade facade = new CheckoutFacade(transactionalService, idempotencyRecords, orders, new CheckoutRequestHasher());
        when(transactionalService.execute(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new PessimisticLockingFailureException("observed H2 lock timeout"));

        assertThatThrownBy(() -> facade.checkout(UUID.randomUUID(), "lock-classification", null, null))
                .isInstanceOf(com.uniblox.checkout.api.ApiException.class)
                .extracting("code").isEqualTo("RETRYABLE_CONFLICT");
    }

    @Test
    void unknownDatabaseFailureIsNotClassifiedAsRetryableLockFailure() {
        assertThat(DatabaseFailureClassifier.isRetryableLockFailure(
                new DataAccessResourceFailureException("database connection unavailable"))).isFalse();
        assertThat(DatabaseFailureClassifier.isRetryableLockFailure(
                new java.sql.SQLException("constraint failure", "23000", 23513))).isFalse();
    }

    @Test
    void identifiedDuplicateWithoutCommittedRecordIsRetryableNotReplay() {
        UUID cartId = UUID.randomUUID();
        CheckoutTransactionalService transactionalService = mock(CheckoutTransactionalService.class);
        IdempotencyRecordRepository idempotencyRecords = mock(IdempotencyRecordRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        when(transactionalService.execute(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("UK_IDEMPOTENCY_CART_KEY duplicate"));
        when(idempotencyRecords.findByCartIdAndKey(cartId, "missing-record"))
                .thenReturn(Optional.empty());
        CheckoutFacade facade = new CheckoutFacade(transactionalService, idempotencyRecords, orders, new CheckoutRequestHasher());

        assertThatThrownBy(() -> facade.checkout(cartId, "missing-record", null, null))
                .isInstanceOf(com.uniblox.checkout.api.ApiException.class)
                .extracting("code").isEqualTo("RETRYABLE_CONFLICT");
    }

    @Test
    void identifiedDuplicateWithIncompleteCommittedRecordIsRetryableNotReplay() {
        UUID cartId = UUID.randomUUID();
        CheckoutTransactionalService transactionalService = mock(CheckoutTransactionalService.class);
        IdempotencyRecordRepository idempotencyRecords = mock(IdempotencyRecordRepository.class);
        OrderRepository orders = mock(OrderRepository.class);
        when(transactionalService.execute(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("UK_IDEMPOTENCY_CART_KEY duplicate"));
        when(idempotencyRecords.findByCartIdAndKey(cartId, "incomplete-record"))
                .thenReturn(Optional.of(new IdempotencyRecord(cartId, "incomplete-record", "hash-without-order")));
        CheckoutFacade facade = new CheckoutFacade(transactionalService, idempotencyRecords, orders, new CheckoutRequestHasher());

        assertThatThrownBy(() -> facade.checkout(cartId, "incomplete-record", null, null))
                .isInstanceOf(com.uniblox.checkout.api.ApiException.class)
                .extracting("code").isEqualTo("RETRYABLE_CONFLICT");
    }
}