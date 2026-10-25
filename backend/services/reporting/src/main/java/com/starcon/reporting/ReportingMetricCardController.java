package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.1.2.1 核心指标卡的 HTTP 端点（卷 01 工作台与门户 L3 1.1.2 关键指标下的第一格）。
 *
 * <p><b>命名空间是 {@code /reporting/metric-cards}</b>：与既有的
 * {@code /reporting/overview}（13.1.1.1 经营总览）、
 * {@code /reporting/store-comparison}（13.1.1.2 多店对比）、
 * {@code /reporting/expense-variance}（13.4.2.2 费用差异）并列，互不覆盖。
 *
 * <p>🔴 **租户不来自任何请求头**：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>🔴 **本模块没有写端点**：卷 01 卷首写死「工作台只读 + 跳转」。
 *
 * <p>🔴 **权限码沿用 {@code reporting.overview.read}**：本模块读的**就是** 13.1.1.1 经营总览
 * 那套投影读模型（同一批表、同一套口径、同一份 RLS），没有新增任何数据面能力 ——
 * 另起一个权限码会让「谁能看经营指标」出现两个答案，而新码还要去 identity 登记
 * （那需要另一个服务的迁移版本号，本车道没有分配）。
 */
@RestController
@RequestMapping("/reporting/metric-cards")
@Tag(name = "1.1.2.1 核心指标卡")
public class ReportingMetricCardController {

  /** 本模块读数用的权限码（与 13.1.1.1 经营总览同一份数据面）。 */
  private static final String PERMISSION = "reporting.overview.read";

  private final AccessService access;
  private final ReportingMetricCardService cards;

  public ReportingMetricCardController(AccessService access, ReportingMetricCardService cards) {
    this.access = access;
    this.cards = cards;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, PERMISSION);
    return access.run(actor, PERMISSION, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明：逐个指标报上游、口径、归属与下钻目标",
      description =
          "每个指标一条：是否有上游、算法（分子 / 分母引用）、来源表、口径归属模块、单位与币种、"
              + "下钻目标是否可用。**接不通的指标不给公式也不给分母** —— 给了就是编一个出来。")
  public ReportingMetricCardModels.MetricCardsCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> cards.capabilities(actor.tenantId()));
  }

  // ================================================================ 卡片数据

  @GetMapping
  @Operation(
      summary = "核心指标卡：指标值 + 环比 + 同比 + 趋势箭头 + 下钻链接 + 口径说明",
      description =
          "时间口径由 window 选：TODAY（今日）/ YESTERDAY（昨日）/ THIS_MONTH（本月）/ CUSTOM（自定义区间）。"
              + "「今日 / 昨日 / 本月」的边界按**本租户时区**算（取自本租户投影事实的分桶时区），不写死 UTC。"
              + "baseCurrency 给了且有汇率快照才折算本位币合计 —— 没有汇率就是不可判定，不是按 1:1 算。"
              + "工作台不下发租户号与范围参数：范围由服务端判定。")
  public ReportingMetricCardModels.MetricCardsResponse cards(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "TODAY") String window,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String baseCurrency) {
    return call(
        auth,
        actor ->
            cards.cards(
                actor.tenantId(),
                bearerOf(auth),
                window,
                from,
                to,
                baseCurrency == null || baseCurrency.isBlank() ? null : baseCurrency.trim().toUpperCase()));
  }

  /** 把调用方的 bearer 原样转发给上游授权 API（上游按**调用方自己的身份**再判一次权限）。 */
  private String bearerOf(String auth) {
    return auth == null || auth.isBlank() ? "" : auth;
  }
}
