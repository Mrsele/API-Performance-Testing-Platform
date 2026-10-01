package dev.perfkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RestErrors {
  @ExceptionHandler({IllegalArgumentException.class, JsonProcessingException.class})
  ResponseEntity<Map<String, String>> bad(Exception e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(e.getMessage())));
  }

  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<Map<String, String>> missing(Exception e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found: " + e.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, String>> other(Exception e) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", String.valueOf(e)));
  }
}
