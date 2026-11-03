package com.starcon.crm;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.6.1.2 的错误翻译 —— <b>只管本模块的控制器</b>。
 *
 * <p>🔴 <b>刻意做成模块内的一份，而不是改共享的 {@link ApiErrorHandler}</b>：
 * {@code ApiErrorHandler} 是 crm 服务<b>所有</b>模块共用的文件（15.4.2.1 引入），
 * 改它会让别的车道的行为一起变 —— 那不是本车道该碰的东西。
 * 本模块用自己的 advice（{@code assignableTypes} 限定到 {@link PointsController}），
 * 别的模块的端点**一个字都不受影响**。
 *
 * <p><b>为什么需要它</b>：服务层的业务拒绝用
 * {@link ResponseStatusException} 带一句「能看懂的话」抛出，
 * 而 Spring 默认把它翻译成 {@code response.sendError(status)} ——
 * <b>响应体是空的</b>。调用方拿到的只有「409」，不知道是「余额不足」
 * 还是「币种对不上」。运营改不了配置就会来问「到底哪里不对」。
 * 这里把它翻成 RFC 7807 的 {@link ProblemDetail}，<b>理由进响应体</b>。
 *
 * <p>状态码**沿用服务层给的**（400 / 404 / 409 / 503），不另起一套 ——
 * 同一个业务错误在不同路径下必须落在同一个状态码上，调用方才能一致地处理。
 */
@RestControllerAdvice(assignableTypes = PointsController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PointsErrorHandler {

  /**
   * 业务拒绝（400 / 404 / 409 / 503 …）：<b>把理由写进响应体</b>。
   *
   * <p>🔴 503 尤其重要：本模块读不到上游订单事实时一律 503，
   * 理由必须说清「读不到」而不是「没有数据」——
   * 运营看到「没有数据」会以为这批买家真的没下过单。
   */
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ProblemDetail> business(ResponseStatusException e) {
    HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
    if (status == null) {
      status = HttpStatus.INTERNAL_SERVER_ERROR;
    }
    String reason = e.getReason();
    if (reason == null || reason.isBlank()) {
      reason = "请求被拒绝：" + status.getReasonPhrase();
    }
    return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, reason));
  }

  /**
   * 入参校验失败：<b>把哪个字段不合法说清楚</b>。
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
    StringBuilder detail = new StringBuilder("入参不合法：");
    boolean first = true;
    for (var error : e.getBindingResult().getFieldErrors()) {
      if (!first) detail.append("；");
      detail.append(error.getField()).append(" ").append(error.getDefaultMessage());
      first = false;
    }
    if (first) {
      detail.append("请求体校验未通过。");
    }
    return ResponseEntity.badRequest()
        .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail.toString()));
  }
}
