package com.starcon.platform;

import static com.starcon.platform.AiForecastModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 18.4.2.2「选品与需求预测」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/ai/forecast}</b>：同卷的兄弟模块各有各的前缀
 * （{@code /platform/text} 18.4.2.1、{@code /platform/ai/usage} 18.4.3.1、
 * {@code /platform/ai/templates} 18.5.1.1、{@code /platform/automation/runs} 18.3.2.1）——
 * 前缀互不为前缀，谁也不会吃掉谁。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码 {@code platform.ai_forecast.read / .create / .update / .adopt}</b>：
 * 「采纳建议」是<b>独立</b>的一个码 —— 蓝图加粗句「建议只作为参考输入，不自动执行」
 * 说的是一道门槛，能改预测参数的人不该顺手把别人的建议标成「已采纳」。
 */
@RestController
@RequestMapping("/platform/ai/forecast")
public class AiForecastController {

  private final AccessService access;
  private final AiForecastService forecasts;

  public AiForecastController(AccessService access, AiForecastService forecasts) {
    this.access = access;
    this.forecasts = forecasts;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.ai_forecast." + permission);
    return access.run(actor, "platform.ai_forecast." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与概览

  /** 能力声明：算法口径、参数、阈值、来源与边界逐条报出。 */
  @GetMapping("/capabilities")
  public AiForecastCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> forecasts.capabilities());
  }

  /** 概览：几个数。 */
  @GetMapping("/overview")
  public AiForecastOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> forecasts.overview());
  }

  // ================================================================ 促销日历

  /** 促销日历列表（真分页 + 真筛选）。 */
  @GetMapping("/promotions")
  public AiForecastPromotionPage promotions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String promotionType,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) Boolean activeOnly,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor -> forecasts.promotions(keyword, promotionType, scopeType, activeOnly, page, size));
  }

  /** 登记或修改一条促销（租户自己的业务事实；录入方式随记录落库）。 */
  @PostMapping("/promotions")
  public AiForecastPromotionRow savePromotion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AiForecastPromotionInput input) {
    return call(auth, "create", actor -> forecasts.savePromotion(input, actor));
  }

  /** 删除一条促销。 */
  @DeleteMapping("/promotions/{id}")
  public void deletePromotion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "update",
        actor -> {
          forecasts.deletePromotion(id);
          return null;
        });
  }

  // ================================================================ 需求历史

  /** 需求历史台账（真分页 + 真筛选）。 */
  @GetMapping("/history")
  public AiForecastHistoryPage history(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String source,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> forecasts.history(skuCode, from, to, source, page, size));
  }

  /** 录入一条需求历史（**人工录入**）。 */
  @PostMapping("/history")
  public AiForecastHistoryRow saveHistory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AiForecastHistoryInput input) {
    return call(auth, "create", actor -> forecasts.saveHistory(input));
  }

  /** 删除一条需求历史（更正录入用）。 */
  @DeleteMapping("/history/{id}")
  public void deleteHistory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "update",
        actor -> {
          forecasts.deleteHistory(id);
          return null;
        });
  }

  /**
   * 从 <b>13 分析</b>（reporting）同步历史销量。
   *
   * <p>🔴 上游读不到时**如实报 503 且一个数据都不写**（不会假装同步成功）。
   */
  @PostMapping("/history/sync")
  public AiForecastHistorySyncResult syncHistory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to) {
    return call(auth, "create", actor -> forecasts.syncHistory(from, to, auth));
  }

  // ================================================================ 预测运行

  /** 发起一次预测运行（**只产出建议，不执行任何下游动作**）。 */
  @PostMapping("/runs")
  public AiForecastRunDetail run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AiForecastRunInput input) {
    return call(auth, "create", actor -> forecasts.run(input, actor, auth));
  }

  /** 运行列表（真分页 + 真筛选）。 */
  @GetMapping("/runs")
  public AiForecastRunPage runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> forecasts.runs(status, page, size));
  }

  /** 运行详情（运行 + 每个 SKU 的结果 + 影响因素）。 */
  @GetMapping("/runs/{id}")
  public AiForecastRunDetail runDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> forecasts.runDetail(id));
  }

  // ================================================================ 预测结果

  /** 预测结果列表（真分页 + 真筛选）。 */
  @GetMapping("/forecasts")
  public AiForecastResultPage forecasts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) UUID runId,
      @RequestParam(required = false) String historySource,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor -> forecasts.forecasts(skuCode, runId, historySource, page, size));
  }

  /** 预测详情（**点估计 + 置信区间 + 影响因素**）。 */
  @GetMapping("/forecasts/{id}")
  public AiForecastResultDetail forecastDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> forecasts.forecastDetail(id));
  }

  // ================================================================ 建议

  /** 建议列表（真分页 + 真筛选）。 */
  @GetMapping("/replenish-suggestions")
  public AiForecastSuggestionPage suggestions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String suggestionType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String skuCode,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor -> forecasts.suggestions(suggestionType, status, skuCode, page, size));
  }

  /**
   * 人工采纳一条建议。
   *
   * <p>🔴 **只是记下「人决定了采纳」**：本模块**不建补货单、不改价**。
   */
  @PostMapping("/suggestions/{id}/adopt")
  public AiForecastSuggestionRow adopt(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) AiForecastSuggestionDecision decision) {
    return call(
        auth,
        "adopt",
        actor -> forecasts.adoptSuggestion(id, decision == null ? null : decision.note(), actor));
  }

  /** 人工驳回一条建议。 */
  @PostMapping("/suggestions/{id}/reject")
  public AiForecastSuggestionRow reject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) AiForecastSuggestionDecision decision) {
    return call(
        auth,
        "adopt",
        actor -> forecasts.rejectSuggestion(id, decision == null ? null : decision.note(), actor));
  }

  // ================================================================ 价格弹性

  /** 价格弹性（**算不出来的 SKU 也在列表里，并写明差在哪**）。 */
  @GetMapping("/price-elasticity")
  public AiForecastPriceElasticityView priceElasticity(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String skuCode,
      @RequestParam(defaultValue = "0") int windowDays) {
    return call(auth, "read", actor -> forecasts.priceElasticity(skuCode, windowDays));
  }

  // ================================================================ 爆品 / 机会品

  /** 爆品识别（阈值 + 统计窗口随响应一起给出）。 */
  @GetMapping("/hot-items")
  public AiForecastHotItemView hotItems(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "0") int windowDays,
      @RequestParam(defaultValue = "0") int limit) {
    return call(auth, "read", actor -> forecasts.hotItems(windowDays, limit));
  }

  /** 机会品发现（阈值 + 统计窗口随响应一起给出）。 */
  @GetMapping("/opportunities")
  public AiForecastOpportunityView opportunities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "0") int windowDays,
      @RequestParam(defaultValue = "0") int limit) {
    return call(auth, "read", actor -> forecasts.opportunities(windowDays, limit));
  }
}
