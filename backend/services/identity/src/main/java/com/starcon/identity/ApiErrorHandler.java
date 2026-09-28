package com.starcon.identity;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrorHandler {
  @ExceptionHandler({
    org.jooq.exception.DataAccessException.class,
    org.springframework.dao.DataAccessException.class
  })
  ResponseEntity<ProblemDetail> database(Exception e) {
    Throwable cause = e;
    while (cause != null) {
      if (cause instanceof java.sql.SQLException sql && "42501".equals(sql.getSQLState()))
        return ResponseEntity.status(403)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "没有目标租户的授权"));
      cause = cause.getCause();
    }
    return ResponseEntity.status(409)
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "编码或账号已存在、记录仍被引用，或目标身份不可用。请刷新后检查输入。"));
  }

  @ExceptionHandler({IllegalArgumentException.class, java.time.format.DateTimeParseException.class})
  ResponseEntity<ProblemDetail> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "字段格式不正确，请检查类型、日期和必填项"));
  }
}
