package com.starcon.channel;

import static com.starcon.channel.AdBudgetAllocationModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 14.2.2.1 预算分配。
 *
 * <p>蓝图：「跨店铺/跨活动的预算分配与再平衡：按销售目标、利润、历史 ROAS 分配预算，识别预算受限的活动与闲置预算。
 * 输入：预算总额；分配规则；各活动历史表现。输出：分配方案 + 再平衡建议 + 执行任务。
 * 关系：上游 = 14.1.1.1、13.4 利润；下游 = 14.2.2.2。<b>预算应向利润高的活动倾斜</b>，按销售额平均分配是常见浪费。」
 * 端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 入口刻意分成六组，各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /capabilities}、{@code GET /options} —— 能力声明与下拉项。
 *       能力声明里**利润维度显式 available=false**：13.4 未交付，界面上必须让人看见这个状态，
 *       不许用假利润数字顶替。
 *   <li>{@code GET|POST /rules}、{@code GET /rules/{id}} —— 「分配规则」。权重位可插拔。
 *   <li>{@code GET|POST /pools}、{@code GET /pools/{id}} —— 「预算总额」。
 *   <li>{@code GET|POST /plans}、{@code GET /plans/{id}} —— 「分配方案」。
 *   <li>{@code GET /rebalances} —— 「再平衡建议」。
 *   <li>{@code GET|POST /tasks}、{@code GET /tasks/{id}}、{@code POST /tasks/{id}/apply} —— 「执行任务」。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.1.1.1 活动列表、
 * 14.2.2.2 自动规则同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-budgets")
public class AdBudgetAllocationController {

  private final AccessService access;
  private final AdBudgetAllocationService service;

  public AdBudgetAllocationController(AccessService access, AdBudgetAllocationService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：未交付的段（利润加权 / 汇率折算 / 平台回写 / 按方案自动建规则）显式 available=false。
   *
   * <p>🔴 利润那一档必须显式 false + ownerModule + 影响 —— 界面上要让人看见
   * 「当前只按销售目标与历史 ROAS 分配」，而不是让人以为利润加权已经生效。
   */
  @GetMapping("/capabilities")
  public AdBudgetCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 下拉项 + 护栏上限 + 可用指标日期区间（界面据此预填统计窗口）。 */
  @GetMapping("/options")
  public AdBudgetOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.options());
  }

  // ---- 分配规则 ----

  /** 分配规则列表：真分页 + 真筛选。 */
  @GetMapping("/rules")
  public AdBudgetRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.rules(status, currency, keyword, page, size));
  }

  /** 规则详情：权重位 + 销售目标清单。 */
  @GetMapping("/rules/{id}")
  public AdBudgetRuleDetail rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.rule(id));
  }

  /**
   * 登记分配规则（权重位 + 销售目标）。
   *
   * <p>🔴 利润权重 &gt; 0 会被拒绝并说清「13.4 尚未交付」—— 拒绝的理由要说得出「为什么现在不行」。
   */
  @PostMapping("/rules")
  public AdBudgetRuleDetail saveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveBudgetRuleRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveRule(request));
  }

  // ---- 预算池 ----

  /** 预算池列表（真分页 + 真筛选）。 */
  @GetMapping("/pools")
  public AdBudgetPoolPage pools(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.pools(currency, keyword, page, size));
  }

  /** 预算池详情。 */
  @GetMapping("/pools/{id}")
  public AdBudgetPoolRow pool(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.pool(id));
  }

  /** 登记预算池（预算总额 + 币种 + 周期 + 规则）。 */
  @PostMapping("/pools")
  public AdBudgetPoolRow savePool(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveBudgetPoolRequest request) {
    return call(auth, "channel.operation.create", a -> service.savePool(request));
  }

  // ---- 分配方案 ----

  /** 分配方案列表（真分页 + 真筛选）。 */
  @GetMapping("/plans")
  public AdBudgetPlanPage plans(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String poolId,
      @RequestParam(required = false) String ruleId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.plans(status, blankUuid(poolId), blankUuid(ruleId), page, size));
  }

  /** 方案详情：逐活动明细 + 未纳入分配的活动与原因 + 再平衡建议。 */
  @GetMapping("/plans/{id}")
  public AdBudgetPlanDetail plan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.plan(id));
  }

  /**
   * 按预算池与规则计算一份分配方案（真落库）。
   *
   * <p>🔴 统计窗口**必须显式给出**（from / to）：不从墙上时钟推导 ——
   * 「上个月」这种键在月初与月末会圈出不同范围，既没法复算也没法断言。
   */
  @PostMapping("/plans")
  public AdBudgetPlanDetail computePlan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ComputeBudgetPlanRequest request) {
    return call(auth, "channel.operation.create", a -> service.computePlan(request));
  }

  // ---- 再平衡建议 ----

  /** 再平衡建议集合读（真分页 + 真筛选）：把闲置的钱挪给受限的活动，总额不变。 */
  @GetMapping("/rebalances")
  public AdBudgetRebalancePage rebalances(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String planId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.rebalances(blankUuid(planId), status, page, size));
  }

  // ---- 执行任务 ----

  /** 执行任务列表（真分页 + 真筛选）。 */
  @GetMapping("/tasks")
  public AdBudgetTaskPage tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String planId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.tasks(blankUuid(planId), status, page, size));
  }

  /** 任务详情：逐条结果（含被护栏拒绝的那几条）。 */
  @GetMapping("/tasks/{id}")
  public AdBudgetTaskDetail task(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.task(id));
  }

  /** 从一份方案生成执行任务（待执行）。 */
  @PostMapping("/tasks")
  public AdBudgetTaskDetail createTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CreateBudgetTaskRequest request) {
    return call(auth, "channel.operation.create", a -> service.createTask(request));
  }

  /**
   * 执行任务：逐条落预算，逐条留痕。
   *
   * <p>🔴 走 14.1.1.1 的写入口（{@code ad_campaign} 是它的事实表，本模块不另开写路径），
   * 改动只落**本地**并标记「待回写平台」（平台回写队列未交付）。
   */
  @PostMapping("/tasks/{id}/apply")
  public AdBudgetTaskDetail applyTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.update", a -> service.applyTask(id));
  }

  /** 空串与 null 同义，避免把 {@code ?poolId=} 当成「筛选空字符串」。 */
  private static UUID blankUuid(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "id 不是合法 UUID");
    }
  }
}
