package com.starcon.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 5.1.2.1 的两个 409 出口。
 *
 * <p>🔴 **单独一个 advice，不往 {@link ApiErrorHandler} 里塞**：那是全服务共用的文件，
 * 并行车道都在改它 ⇒ 本车道另起一个文件，它只处理本模块的两个异常类型，
 * 与既有 handler 不会抢。
 *
 * <h2>为什么必须是 409 + 结构化明细</h2>
 *
 * <p>蓝图：「冲突即拒绝并列出冲突明细」。只回一句「有冲突」的话，
 * 运营没法按条改 —— 他不知道撞了哪两张表、哪个店铺、哪个站点、
 * 生效区间重叠在哪。所以明细整份挂在 ProblemDetail 上退回前端
 * （与 3.1.3.2 的 {@code conflicts}、3.6.2.1 的 {@code candidates} 同一手法）。
 */
@RestControllerAdvice
public class PriceListPriorityExceptionHandler {

  /**
   * 保存时被冲突检测挡下。
   *
   * <p>🔴 状态码 409（Conflict）：请求本身合法，是**当前配置**互相冲突。
   */
  @ExceptionHandler(PriceListPriorityConflictException.class)
  ResponseEntity<ProblemDetail> conflict(PriceListPriorityConflictException e) {
    var report = e.report();
    var body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    body.setProperty("conflicts", report.conflicts());
    body.setProperty("conflictCount", report.conflictCount());
    body.setProperty("blockingCount", report.blockingCount());
    body.setProperty("pairCount", report.pairCount());
    body.setProperty("dimensionCoverage", report.dimensionCoverage());
    body.setProperty("skuDimensionNote", report.skuDimensionNote());
    body.setProperty("truncated", report.truncated());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }

  /**
   * 解析不出来（同一上下文里多条价目表优先级相同）。
   *
   * <p>🔴 把并列候选**全部**退回给用户选 —— 蓝图原话：「必须报错让用户选，不随机取」。
   */
  @ExceptionHandler(PriceListPriorityAmbiguityException.class)
  ResponseEntity<ProblemDetail> ambiguity(PriceListPriorityAmbiguityException e) {
    var resolution = e.resolution();
    var body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    body.setProperty("ambiguous", true);
    body.setProperty("candidates", resolution.candidates());
    body.setProperty("excluded", resolution.excluded());
    body.setProperty("skuCoverage", resolution.skuCoverage());
    body.setProperty("shopCode", resolution.shopCode());
    body.setProperty("siteCode", resolution.siteCode());
    body.setProperty("at", resolution.at());
    return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
  }
}
