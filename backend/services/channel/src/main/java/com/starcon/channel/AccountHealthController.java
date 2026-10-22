package com.starcon.channel;

import static com.starcon.channel.AccountHealthModels.*;

import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.1.1.1 账号健康总览。
 *
 * <p>蓝图：「按店铺展示账号健康状态：绩效指标（ODR、迟发率、取消率、有效追踪率）、政策违规计数、
 * 申诉状态、风险等级，支持跨店铺对比与风险排序。输入：平台绩效数据同步；风险等级计算规则；
 * 用户数据范围（店铺维度）。输出：店铺健康卡片 + 风险排序 + 需处理项跳转。
 * 关系：上游 = 2 同步；下游 = 16.1.1.2 指标明细、16.1.2.1 违规。
 * <b>风险排序比明细更重要</b>：先处理最可能掉号的店铺。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：哪些下游未交付、量不到归谁、等级与排序的口径。
 *   <li>{@code GET /shops} —— 店铺健康卡片（**真分页 + 真筛选**，排序恒为风险优先）。
 *   <li>{@code GET /shops/{id}} —— 单家店的卡片（指标 + 逐条依据 + 排序名次）。
 *   <li>{@code GET /ranking} —— 跨店铺风险排序（**真分页**，名次是全局名次 + 为什么排这个位置）。
 *   <li>{@code GET /pending-items} —— 需处理项（**只给本模块真实存在的**待处理项）。
 *   <li>{@code GET /thresholds} —— 生效阈值（内置默认 + 租户覆盖）。
 *   <li>{@code PUT /thresholds/{metricCode}} —— 改阈值（**服务端行为**，推进规则版本）。
 *   <li>{@code POST /shops/{id}/metrics} —— 手工录入绩效指标（**恒标 MANUAL**）。
 *   <li>{@code POST /shops/{id}/assess} —— 跑一次判定，落一条**不可改写**的凭据。
 *   <li>{@code GET /assessments} —— 判定历史（**真分页**）。
 *   <li>{@code POST /shops/{id}/sync} —— 🔴 平台绩效同步（**未接入 ⇒ 恒 409 且一条数据都不写**）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「违规计数」端点、没有「申诉状态」端点 ——
 * 16.1.2.1 / 16.1.2.2 未交付，**放行一个不存在的入口比 404 更糟**（界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改）
 * 与 {@code channel.sync.trigger}（触发同步），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/account-health")
public class AccountHealthController {

  private final AccessService access;
  private final AccountHealthService service;

  public AccountHealthController(AccessService access, AccountHealthService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：未交付的下游逐条列出（available=false + ownerModule + 影响说明）。 */
  @GetMapping("/capabilities")
  public AccountHealthCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 健康卡片 ----

  /** 店铺健康卡片列表（真分页 + 真筛选；排序恒为风险优先）。 */
  @GetMapping("/shops")
  public AccountHealthShopPage shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String riskLevel,
      @RequestParam(required = false) String dataState,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "10") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.shops(platformCode, keyword, riskLevel, dataState, shopId, page, size));
  }

  /** 单家店的健康卡片。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/shops/{id}")
  public AccountHealthShopCard shop(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.shop(id));
  }

  /** 跨店铺风险排序（真分页；名次是全局名次 + 为什么排这个位置）。 */
  @GetMapping("/ranking")
  public AccountHealthRankingPage ranking(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String riskLevel,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "10") int size) {
    return call(
        auth, "channel.operation.read", a -> service.ranking(platformCode, keyword, riskLevel, shopId, page, size));
  }

  /** 需处理项（只给本模块自己真实存在的待处理项）。 */
  @GetMapping("/pending-items")
  public AccountHealthPendingList pendingItems(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String keyword) {
    return call(auth, "channel.operation.read", a -> service.pendingItems(platformCode, keyword));
  }

  // ---- 阈值 ----

  /** 生效阈值（内置默认 + 租户覆盖）。 */
  @GetMapping("/thresholds")
  public AccountHealthThresholdList thresholds(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.thresholds());
  }

  /** 改阈值（服务端行为；推进规则版本，历史判定不受影响）。 */
  @PutMapping("/thresholds/{metricCode}")
  public AccountHealthThresholdView saveThreshold(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String metricCode,
      @RequestBody AccountHealthThresholdInput input) {
    return call(auth, "channel.operation.update", a -> service.saveThreshold(metricCode, input));
  }

  // ---- 指标录入与判定 ----

  /** 手工录入一条绩效指标（source 恒为 MANUAL，不冒充平台同步）。 */
  @PostMapping("/shops/{id}/metrics")
  public AccountHealthShopCard recordMetric(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody AccountHealthMetricInput input) {
    return call(auth, "channel.operation.create", a -> service.recordMetric(id, input));
  }

  /** 跑一次账号健康判定，落一条不可改写的凭据。 */
  @PostMapping("/shops/{id}/assess")
  public AccountHealthAssessmentResult assess(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.create", a -> service.assess(id));
  }

  /** 判定历史（真分页）。 */
  @GetMapping("/assessments")
  public AccountHealthAssessmentPage assessments(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.assessments(shopId, page, size));
  }

  // ---- 平台绩效同步 ----

  /**
   * 🔴 触发一次平台账号绩效同步。
   *
   * <p><b>当前恒 409 且一条数据都不写</b>：平台账号绩效适配器未交付（归 2.4 同步运行）。
   * 适配器接上之后本端点自动放行，无需改前端。
   */
  @PostMapping("/shops/{id}/sync")
  public AccountHealthShopCard sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.sync.trigger", a -> service.sync(id, auth));
  }
}
