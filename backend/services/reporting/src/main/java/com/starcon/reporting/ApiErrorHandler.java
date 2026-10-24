package com.starcon.reporting;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误翻译（随 13 利润与经营分析服务引导引入，与 trade / warehouse / catalog / channel / crm /
 * finance 同构）。
 *
 * <p>数据库层约束冲突归 409（提示刷新重试），格式问题归 400。
 *
 * <p>刻意**不为本模块自己的业务拒绝另开一个 handler**： 服务层用 {@code
 * ResponseStatusException(BAD_REQUEST, "能看懂的话")} 直接给出原因， 而 DB 层的 23514 / 23503 会落到下面的
 * 409。把它们分开处理只会让同一个业务错误 在不同路径下返回两种状态码，调用方无法据此做一致的处理。
 */
@RestControllerAdvice
public class ApiErrorHandler {

  @ExceptionHandler({
    org.jooq.exception.DataAccessException.class,
    org.springframework.dao.DataAccessException.class
  })
  ResponseEntity<ProblemDetail> database(Exception e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "报表投影数据与既有记录冲突，或记录仍被引用。请刷新后检查输入。"));
  }

  @ExceptionHandler({IllegalArgumentException.class, java.time.format.DateTimeParseException.class})
  ResponseEntity<ProblemDetail> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "字段格式不正确，请检查类型、必填项与日期格式"));
  }
}
