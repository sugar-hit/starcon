package com.starcon.warehouse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误翻译。
 *
 * <p>与 catalog / channel 同构：数据库层约束冲突归 409（提示刷新重试）， 格式问题归 400，跨服务未接入的筛选归 422。
 */
@RestControllerAdvice
public class ApiErrorHandler {

  /**
   * 依赖未接入上游的筛选条件被显式拒绝（422）。
   *
   * <p>单独一个 handler 而不是塞进 400：400 说的是「你填错了」，422 说的是 「这个条件当前不可用」。混成 400 时前端会把它显示成输入错误提示，
   * 用户会反复改一个本来就改不动的筛选条件。
   */
  @ExceptionHandler(CatalogProjectionPort.DimensionUnavailableException.class)
  ResponseEntity<ProblemDetail> dimensionUnavailable(
      CatalogProjectionPort.DimensionUnavailableException e) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage()));
  }

  @ExceptionHandler({
    org.jooq.exception.DataAccessException.class,
    org.springframework.dao.DataAccessException.class
  })
  ResponseEntity<ProblemDetail> database(Exception e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "库存数据与既有记录冲突，或记录仍被引用。请刷新后检查输入。"));
  }

  @ExceptionHandler({IllegalArgumentException.class, java.time.format.DateTimeParseException.class})
  ResponseEntity<ProblemDetail> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "字段格式不正确，请检查类型、数量和必填项"));
  }
}
