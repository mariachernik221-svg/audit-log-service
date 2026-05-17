package com.auditlog.api;

import com.auditlog.service.MissingRequestValueException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(MissingRequestValueException.class)
  public ResponseEntity<Map<String, Object>> handleMissingValue(MissingRequestValueException ex) {
    return response(HttpStatus.BAD_REQUEST, ex.getMessage(), List.of());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Map<String, Object>> handleMissingParam(
      MissingServletRequestParameterException ex) {
    return response(HttpStatus.BAD_REQUEST, ex.getMessage(), List.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
    List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
    boolean anyMissing = fieldErrors.stream().anyMatch(e -> "NotNull".equals(e.getCode()));
    HttpStatus status = anyMissing ? HttpStatus.BAD_REQUEST : HttpStatus.UNPROCESSABLE_ENTITY;
    List<String> errors =
        fieldErrors.stream().map(e -> e.getField() + ": " + e.getDefaultMessage()).toList();
    return response(status, "validation failed", errors);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, Object>> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex) {
    String message = "invalid value for parameter '" + ex.getName() + "'";
    return response(HttpStatus.UNPROCESSABLE_ENTITY, message, List.of());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
    return response(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), List.of());
  }

  private static ResponseEntity<Map<String, Object>> response(
      HttpStatus status, String message, List<String> details) {
    return ResponseEntity.status(status).body(body(status, message, details));
  }

  private static Map<String, Object> body(HttpStatus status, String message, List<String> details) {
    return Map.of(
        "timestamp",
        Instant.now().toString(),
        "status",
        status.value(),
        "message",
        message == null ? status.getReasonPhrase() : message,
        "errors",
        details);
  }
}
