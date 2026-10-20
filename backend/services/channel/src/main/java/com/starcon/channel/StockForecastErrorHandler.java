package com.starcon.channel;

import com.starcon.channel.StockForecastModels.SpecViolation;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 10.4.2.2 备货预报的错误出口。
 *
 * <p>🔴 蓝图加粗句：「规格不符会被拒收造成额外成本与延误。」所以规格校验不通过时
 * 必须**明确拒绝并指出违反哪一条** —— 而不是丢一句「规格不符」。
 *
 * <p>本类把 {@link StockForecastService.SpecValidationException} 渲染成
 * {@code 422 + ProblemDetail}，并在 ProblemDetail 上挂一个 {@code violations} 数组，
 * 每条带：规则码（{@code rule}）、规则说明（{@code ruleLabel}）、涉及 SKU、
 * 说明、期望值与实际值。前端据此**逐条**告诉运营改什么，而不是让它自己猜。
 *
 * <p>刻意**不**改既有 {@code ApiErrorHandler}：那是全 channel 服务共用的出口，
 * 本模块的问题域（规格违规）用自己的一份 advice 承接，跨车道回归面 = 0。
 */
@RestControllerAdvice
public class StockForecastErrorHandler {

  @ExceptionHandler(StockForecastService.SpecValidationException.class)
  ResponseEntity<ProblemDetail> specInvalid(StockForecastService.SpecValidationException e) {
    List<SpecViolation> violations = e.violations();
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "规格校验未通过：" + violations.size() + " 条违规，提交前必须先改掉");
    body.setProperty("violations", violations);
    body.setProperty("error", "SPEC_VALIDATION_FAILED");
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
  }
}
