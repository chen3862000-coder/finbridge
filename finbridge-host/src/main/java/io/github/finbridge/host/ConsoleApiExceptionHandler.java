package io.github.finbridge.host;

import io.github.finbridge.lending.application.LendingCoreException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ConsoleApiExceptionHandler {
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> forbidden(AccessDeniedException failure) {
        return response(HttpStatus.FORBIDDEN, "ACCESS_DENIED", failure.getMessage());
    }

    @ExceptionHandler(LendingCoreException.class)
    public ResponseEntity<Map<String, String>> notFound(LendingCoreException failure) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", failure.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException failure) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", failure.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException failure) {
        return response(HttpStatus.CONFLICT, "CONFLICT", failure.getMessage());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, String>> validation(Exception failure) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request validation failed");
    }

    private static ResponseEntity<Map<String, String>> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
