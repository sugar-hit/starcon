package com.starcon.catalog;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrorHandler {
  @ExceptionHandler({
    org.jooq.exception.DataAccessException.class,
    org.springframework.dao.DataAccessException.class
  })
  ResponseEntity<ProblemDetail> database(Exception e) {
    return ResponseEntity.status(409)
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "编码或规格组合重复，或记录仍被引用。请刷新后检查输入。"));
  }

  @ExceptionHandler({IllegalArgumentException.class, java.time.format.DateTimeParseException.class})
  ResponseEntity<ProblemDetail> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "字段格式不正确，请检查类型、日期和必填项"));
  }

  /**
   * 3.1.3.2 组合唯一性校验未通过。
   *
   * <p>单独一个 handler：蓝图要求输出是**结构化冲突清单**，而通用 409 只给一句"编码或规格组合重复"——
   * 那样运营没法按条改。把 {@code conflicts} 挂在 ProblemDetail 上退回前端，
   * 界面才能逐条显示「撞了谁、出现在第几条」。
   */
  @ExceptionHandler(VariantCombinationService.CombinationConflictException.class)
  ResponseEntity<ProblemDetail> combinationConflict(
      VariantCombinationService.CombinationConflictException e) {
    var result = e.result();
    var body =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, "组合校验未通过：存在 " + result.conflictCount() + " 条冲突，草稿未生成");
    body.setProperty("conflicts", result.conflicts());
    body.setProperty("submittedCount", result.submittedCount());
    body.setProperty("plannedCount", result.plannedCount());
    body.setProperty("conflictCount", result.conflictCount());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }
}
