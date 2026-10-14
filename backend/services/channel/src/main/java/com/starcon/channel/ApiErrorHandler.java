package com.starcon.channel;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrorHandler {
  @ExceptionHandler({
    org.jooq.exception.DataAccessException.class,
    org.springframework.dao.DataAccessException.class
  })
  org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> database(
      Exception e) {
    return org.springframework.http.ResponseEntity.status(409)
        .body(
            org.springframework.http.ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "店铺编码重复、平台账号已被绑定，或该店铺仍被单据引用。"));
  }

  @ExceptionHandler({IllegalArgumentException.class, java.time.format.DateTimeParseException.class})
  org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> invalid(
      Exception e) {
    return org.springframework.http.ResponseEntity.badRequest()
        .body(
            org.springframework.http.ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "字段格式不正确，请检查类型、日期和必填项"));
  }

  @ExceptionHandler(ResponseStatusException.class)
  org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> status(
      ResponseStatusException e) {
    return org.springframework.http.ResponseEntity.status(e.getStatusCode())
        .body(
            org.springframework.http.ProblemDetail.forStatusAndDetail(
                e.getStatusCode(), e.getReason() == null ? "请求无法完成" : e.getReason()));
  }

  @ExceptionHandler(RestClientResponseException.class)
  org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> upstream(
      Exception e) {
    return org.springframework.http.ResponseEntity.status(503)
        .body(
            org.springframework.http.ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "身份服务暂时不可用，请稍后重试"));
  }
}
