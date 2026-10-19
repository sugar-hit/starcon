package com.starcon.channel;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 4.2.3.2 合规预检 的**拒绝路径**翻译器。
 *
 * <p>🔴 本模块在 DB 层给 {@code listing_editor_submission} 加了一条 BEFORE INSERT 触发器
 * （见 V198 第三节 (c)）：草稿只要还有未修复的阻断项，**入队这一步就被数据库直接拒掉**。
 * 那是「阻断项在服务端强制校验」的硬证据 —— 绕过前端直接调 4.2.1.1 的提交端点也过不去。
 *
 * <p>🔴 但 channel 的通用错误处理器（{@code ApiErrorHandler}）会把所有
 * {@code DataAccessException} 翻成同一句「店铺编码重复、平台账号已被绑定，或该店铺仍被单据引用。」
 * —— 那句话对合规拒绝是**错的**，会把运营引到完全不相干的地方去。
 * 所以本类用更高的优先级，**只**接管本模块触发器抛的那一种错误，把它翻成
 * 「有未修复的阻断项，未修复不允许发布（服务端已拦截）」。
 *
 * <p>🔴 其它异常一律**不**在这里接管，仍走 {@code ApiErrorHandler} 的通用分支 ——
 * 本类不是通用错误处理器的替代品。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ListingComplianceErrorHandler {

  /** 触发器拒绝时的固定标记（与 V198 的触发器文案逐字对应）。 */
  static final String BLOCK_MARKER = "unresolved blocking compliance risk";

  /**
   * 把「有未修复的阻断项」这个 DB 拒绝翻成 409 + 说人话的 detail。
   *
   * <p>只匹配 SQLSTATE 23514（check_violation）**且**消息里有本模块的标记 ——
   * 别的模块的 CHECK 约束失败照旧走通用分支，不会被这里误伤。
   */
  @ExceptionHandler({
    org.jooq.exception.DataAccessException.class,
    org.springframework.dao.DataAccessException.class
  })
  ResponseEntity<ProblemDetail> complianceBlocked(Exception e) {
    if (!isComplianceRejection(e)) {
      throw e instanceof RuntimeException runtime ? runtime : new RuntimeException(e);
    }
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "该内容存在未修复的合规阻断项，**未修复不允许发布**（服务端已拦截）。"
                    + "请到合规预检页处理阻断项后重试。"));
  }

  /** 判断这到底是不是本模块的合规拒绝。 */
  static boolean isComplianceRejection(Throwable e) {
    Throwable current = e;
    while (current != null) {
      if (current.getMessage() != null && current.getMessage().contains(BLOCK_MARKER)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
