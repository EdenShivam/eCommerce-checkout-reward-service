package com.uniblox.checkout.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.ProblemDetail;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
class ErrorHandler {
	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handle(ApiException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.status, exception.getMessage());
		problem.setProperty("code", exception.code);
		HttpHeaders headers = new HttpHeaders();
		if ("RETRYABLE_CONFLICT".equals(exception.code)) {
			headers.set("Retry-After", "1");
		}
		return new ResponseEntity<>(problem, headers, exception.status);
	}

	@ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
			MissingRequestHeaderException.class, MissingPathVariableException.class,
			MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
	ResponseEntity<ProblemDetail> handleValidation(Exception exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
		problem.setProperty("code", "VALIDATION_FAILED");
		Map<String, String> fieldErrors = new LinkedHashMap<>();
		if (exception instanceof MethodArgumentNotValidException validationException) {
			for (FieldError error : validationException.getBindingResult().getFieldErrors()) {
				fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
			}
		} else if (exception instanceof MissingRequestHeaderException missingHeader) {
			fieldErrors.put(missingHeader.getHeaderName(), "Header is required");
		} else if (exception instanceof MethodArgumentTypeMismatchException typeMismatch) {
			fieldErrors.put(typeMismatch.getName(), "Value has an invalid format");
		}
		if (!fieldErrors.isEmpty()) {
			problem.setProperty("fieldErrors", fieldErrors);
		}
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
		problem.setProperty("code", "INTERNAL_ERROR");
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
	}
}
