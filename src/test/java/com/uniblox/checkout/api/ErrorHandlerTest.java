package com.uniblox.checkout.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorHandlerTest {
    @Test
    void retryableConflictIncludesRetryAfterAndStableProblemCode() {
        var response = new ErrorHandler().handle(new ApiException(HttpStatus.CONFLICT, "RETRYABLE_CONFLICT", "Try again"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody().getProperties()).containsEntry("code", "RETRYABLE_CONFLICT");
    }

    @Test
    void unexpectedFailureDoesNotLeakExceptionDetails() {
        var response = new ErrorHandler().handleUnexpected(new IllegalStateException("sensitive database detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getDetail()).isEqualTo("An unexpected error occurred");
        assertThat(response.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(response.getBody().getDetail()).doesNotContain("sensitive database detail");
    }
}