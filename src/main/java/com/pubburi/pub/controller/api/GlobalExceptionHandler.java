package com.pubburi.pub.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (FieldError error : exception.getBindingResult().getFieldErrors()) {
			fields.put(error.getField(), error.getDefaultMessage());
		}
		ApiError error = new ApiError("VALIDATION_FAILED", "Invalid request", fields);
		return ResponseEntity.badRequest().body(ApiResponse.error(error));
	}

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException exception) {
		HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
		String code = status.name();
		String message = exception.getReason() == null ? status.getReasonPhrase() : exception.getReason();
		return ResponseEntity.status(status).body(ApiResponse.error(ApiError.of(code, message)));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException exception) {
		return ResponseEntity.badRequest().body(ApiResponse.error(ApiError.of("BAD_REQUEST", exception.getMessage())));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ApiResponse.error(ApiError.of("CONFLICT", "Related data prevents this operation")));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
		log.error("Unexpected API error", exception);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiResponse.error(ApiError.of("INTERNAL_SERVER_ERROR", "Unexpected server error")));
	}
}
