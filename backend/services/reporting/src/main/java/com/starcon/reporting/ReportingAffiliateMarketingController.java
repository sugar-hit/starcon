package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.3.2.2 关联营销效果的 HTTP 端点。
 *
 * <p>命名空间是 {@code /reporting/affiliate-marketing}：13.3.2.1 用的是
 * {@code /reporting/traffic-conversion}，13.3.1.1 用的是 {@code /reporting/product-performance}，
 * 兄弟模块各有各的前缀，挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码：{@code reporting.affiliate-marketing.read}（读关联营销报表）与
 * {@code reporting.projection.write}（刷新投影 —— 写的是**投影**，不是业务事实）。
 * 本模块**没有任何「创建业务事实」的权限码**（reporting 是投影服务）。
 */
@RestController
@RequestMapping("/reporting/affiliate-marketing")
@Tag(name = "13.3.2.2 关联营销效果")
public class ReportingAffiliateMarketingController {

  private final AccessService access;
  private final ReportingAffiliateMarketingService service;
  private final ReportingAffiliateMarketingRefreshService refresh;

  public ReportingAffiliateMarketingController(
      AccessService access,
      ReportingAffiliateMarketingService service,
      ReportingAffiliateMarketingRefreshService refresh) {
    this.access = access;
    this.service = service;
    this.refresh = refresh;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明：哪些上游真接通、哪些口径拿不到、归因规则怎么算",
      description =
          "notDelivered 列出的是**确实拿不到**的部分，每条都写清原因、归谁管与当前替代口径 —— "
              + "把「拿不到」伪装成 0 比说清楚更糟。")
  public ReportingAffiliateMarketingModels.AffiliateMarketingCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.affiliate-marketing.read",
        actor -> service.capabilities(actor.tenantId()));
  }

  // ================================================================ 指标口径目录

  @GetMapping("/metrics")
  @Operation(summary = "关联营销的指标口径目录（口径唯一的展示形态）")
  public List<ReportingAffiliateMarketingModels.AffiliateMarketingMetricDefinitionView> metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth, "reporting.affiliate-marketing.read", actor -> service.metricDefinitions(actor.tenantId()));
  }

  // ================================================================ 主报表

  @GetMapping
  @Operation(
      summary = "关联营销效果报表：营销位效果 + 归因成交 + ROI（按 (营销位, 归因规则) 分列）",
      description =
          "时间范围默认「最近 30 天」。🔴 **归因规则必须显式**：报表里的成交归因用的是"
              + "**上游归因记录自带的规则快照**（不是「当前规则」），并且按 "
              + "(营销位, 归因规则) **分列** ⇒ ROI 只在同一条规则内部相除。"
              + "🔴 **两套口径不混算**：达人归因口径（成交归因 / 投入 / ROI）与落地商品口径"
              + "（曝光 / 点击 / 点击率）是两个统计范围，各带自己的标签。"
              + "🔴 **「算不出来」与「等于 0」分开**：本轮不可用 / 分母缺失一律显示「不可算」，"
              + "**不用 0 冒充**。🔴 **绝不跨币种相加**：金额按币种分别报，ROI 分子分母必须同币种。")
  public ReportingAffiliateMarketingModels.AffiliateMarketingResponse report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.affiliate-marketing.read",
        actor -> {
          var window = resolveWindow(from, to);
          return service.report(actor.tenantId(), window.from(), window.to());
        });
  }

  // ================================================================ 归因明细下钻

  @GetMapping("/attributions")
  @Operation(
      summary = "归因明细下钻：每一条归因记录（带规则快照）",
      description =
          "卷 13 卷首加粗句「可下钻」的落点：每个汇总数字都能钻到构成明细。"
              + "🔴 每行都带**规则快照**（归因模型 / 分摊基准 / 归因窗口 / 退款口径 / 佣金口径）—— "
              + "那是**上游归因记录自带的那份**，不是「当前规则」。"
              + "⚠️ **统计日按「归因发生日（UTC 自然日）」切桶**：上游归因记录**不带订单日期**。")
  public ReportingAffiliateMarketingModels.AffiliateMarketingAttributionPage attributions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String slotType,
      @RequestParam(required = false) String ruleCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.affiliate-marketing.read",
        actor -> {
          var window = resolveWindow(from, to);
          return service.attributions(
              actor.tenantId(),
              window.from(),
              window.to(),
              blankToNull(slotType),
              blankToNull(ruleCode),
              page,
              size);
        });
  }

  // ================================================================ 刷新（投影摄取）

  @PostMapping("/refresh")
  @Operation(
      summary = "从上游拉取关联营销数据并投影（写的是投影，不是业务事实）",
      description =
          "上游：channel 的带货归因记录（14.5.2.1：**带规则快照**的归因模型 / 分摊基准 / "
              + "归因窗口 / 退款口径 / 佣金口径）、channel 的内容回写留痕（4.3.2.1：**A+ 营销位存在**的证据）、"
              + "channel 的平台表现快照（4.5.1.1：落地商品口径的曝光 / 点击）。"
              + "⚠️ 刷新会**重建**该窗口（先删旧行再整段重写）—— 上游修正同一天的归因时旧行不会残留。")
  public List<ReportingAffiliateMarketingModels.AffiliateMarketingRefreshResult> refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.projection.write",
        actor -> {
          var window = resolveWindow(from, to);
          var days =
              ReportingAffiliateMarketingRefreshService.windowDays(window.from(), window.to());
          if (days > ReportingAffiliateMarketingRefreshService.MAX_WINDOW_DAYS) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "关联营销窗口最多 "
                    + ReportingAffiliateMarketingRefreshService.MAX_WINDOW_DAYS
                    + " 天：上游要逐页拉归因记录与平台表现，窗口太长会把上游压垮。");
          }
          return refresh.refresh(actor.tenantId(), auth, window.from(), window.to());
        });
  }

  // ================================================================ 参数解析

  /** 时间窗口。默认「最近 30 天」（含今天）。 */
  private record Window(LocalDate from, LocalDate to) {}

  private Window resolveWindow(LocalDate from, LocalDate to) {
    var end = to == null ? LocalDate.now() : to;
    var start = from == null ? end.minusDays(29) : from;
    if (start.isAfter(end)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "起始日期不能晚于结束日期（from=" + start + "、to=" + end + "）");
    }
    return new Window(start, end);
  }

  /** 空串 ⇒ null（不把空串当成一个真的筛选值）。 */
  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
