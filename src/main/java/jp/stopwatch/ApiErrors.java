package jp.stopwatch;

import java.util.Map;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ApiErrors {
  private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> status(ResponseStatusException e) {
    return ResponseEntity.status(e.getStatusCode())
        .body(Map.of("message", e.getReason() == null ? "操作に失敗しました。" : e.getReason()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    String message =
        e instanceof IllegalArgumentException ? e.getMessage() : "入力内容を確認してください。必須項目・数値・日付が不正です。";
    return ResponseEntity.badRequest().body(Map.of("message", message));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict(DataIntegrityViolationException e) {
    log.warn("Database constraint rejected operation", e);
    return ResponseEntity.status(409)
        .body(Map.of("message", "同名のデータや関連データと競合しています。未分類への移動で名前が重複する場合は、先に作業項目名を変更してください。"));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> failure(Exception e) {
    log.error("Request failed", e);
    return ResponseEntity.internalServerError()
        .body(Map.of("message", "保存または読み込みに失敗しました。時間をおいて再試行してください。"));
  }
}
