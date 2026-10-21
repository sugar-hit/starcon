package com.starcon.channel;

import static com.starcon.channel.AdRuleModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 14.2.2.2 自动规则。
 *
 * <p>蓝图：「自动化规则：按条件自动调整预算/竞价/启停（ACOS 超标降价、ROAS 达标加预算、消耗完毕暂停、花费无转化停投），
 * 支持规则优先级、单次调整上限与总上限、试运行模式。输入：规则定义；实时广告表现；上下限保护参数。输出：规则命中记录 +
 * 调整执行记录 + 效果回溯。关系：上游 = 14.3 报表；下游 = 平台广告 API。<b>自动规则必须有上限与试运行</b>——没有上限的自动调价一次
 * 异常就能烧掉大量预算。」端点按蓝图模块号标注，方便对照 14-ads-and-growth.md。
 *
 * <p>🔴 四个入口刻意分开，各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET/POST /rules} + {@code /rules/{id}/status} + {@code /rules/{id}/conditions} ——
 *       「规则定义」与「上下限保护参数」。<b>上限与试运行是必填项</b>：请求模型里它们没有 null 的语义位置。
 *   <li>{@code GET/POST /metrics} —— 「实时广告表现」。这是本模块的<b>显式写入口</b>：
 *       上游 14.3 报表未开发，指标只能从这里登记；每一行都带<b>数据截至时间</b>。
 *   <li>{@code POST /runs} —— 「按条件自动调整」的执行入口。运行口径与规则自身的试运行开关**取更严的那个**。
 *   <li>{@code GET /hits} / {@code GET /executions} / {@code GET /dispatches} / {@code GET /effects} ——
 *       蓝图要求的三样输出 + 待下发动作。{@code POST /effects/evaluate} 负责把回溯算出来。
 * </ul>
 *
 * <p>🔴 {@code POST /dispatches/{id}/cancel} 是本模块<b>唯一</b>能改变待下发队列的动作。
 * 真实下发（把动作发到平台广告 API）本模块**不实现** —— 所以这里刻意**不提供**「标记已下发」这种假动作：
 * 界面显示的是「待下发」，不是「已生效」。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 14.1.2.2 广告类型差异、
 * 10.1.1.2 渠道配置同族），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ad-rules")
public class AdRuleController {

  private final AccessService access;
  private final AdRuleService service;

  public AdRuleController(AccessService access, AdRuleService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：未交付的段（平台侧真实下发 / 14.3 报表 / 14.1.1.1 活动列表 / 14.2.2.1 预算分配）显式 available=false。 */
  @GetMapping("/capabilities")
  public AdRuleCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 规则（「规则定义」+「上下限保护参数」）----

  /** 规则列表：真分页 + 真筛选，并给出每条规则的数据截至时间与是否过期。 */
  @GetMapping("/rules")
  public AdRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) Boolean dryRun,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.rules(
                blank(status),
                uuid(platformId),
                blank(shopCode),
                blank(targetType),
                dryRun,
                page,
                size));
  }

  /**
   * 登记 / 改一条规则。
   *
   * <p>🔔 单次上限、总上限、试运行开关是**必填**的：一条没有上限的规则在服务层就被拒，
   * DB 层还有 CHECK 兜底（「自动规则必须有上限与试运行」的第一道闸）。
   */
  @PostMapping("/rules")
  public AdRuleDetail saveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveRuleRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveRule(request));
  }

  /** 规则详情：规则本体 + 条件 + 最近一次评估的结论。 */
  @GetMapping("/rules/{id}")
  public AdRuleDetail rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.rule(id));
  }

  /** 启停一条规则。 */
  @PostMapping("/rules/{id}/status")
  public AdRuleRow setRuleStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam String status) {
    return call(auth, "channel.operation.update", a -> service.setRuleStatus(id, status));
  }

  /** 某条规则的全部条件（AND 语义）。 */
  @GetMapping("/rules/{id}/conditions")
  public java.util.List<AdRuleConditionRow> conditions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.conditions(id));
  }

  // ---- 广告表现指标（「实时广告表现」的显式写入口）----

  /** 指标列表：真分页 + 真筛选，并给出全库最新的数据截至时间。 */
  @GetMapping("/metrics")
  public AdRuleMetricPage metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String metricScope,
      @RequestParam(required = false) String externalCampaignId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.metrics(
                uuid(platformId),
                blank(shopCode),
                blank(metricScope),
                blank(externalCampaignId),
                page,
                size));
  }

  /**
   * 登记一条广告表现指标。
   *
   * <p>🔔 数据截至时间（{@code metricsAsOf}）**必填**：没有它就分不清「没有数据」与「没同步到」。
   */
  @PostMapping("/metrics")
  public AdRuleMetricRow saveMetric(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveMetricRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveMetric(request));
  }

  /** 指标详情。 */
  @GetMapping("/metrics/{id}")
  public AdRuleMetricRow metric(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.metric(id));
  }

  // ---- 评估运行（「按条件自动调整」的执行入口）----

  /** 运行列表：真分页。 */
  @GetMapping("/runs")
  public AdRuleRunPage runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String runMode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.runs(blank(runMode), page, size));
  }

  /**
   * 跑一次评估。
   *
   * <p>🔔 试运行取**更严的那个**：规则自身 {@code dryRun=true} 时，这次运行**不会**真的调 ——
   * 一次「APPLY」的运行不能把一条开着试运行的规则推成真实执行。
   */
  @PostMapping("/runs")
  public AdRuleRunDetail run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RunRequest request) {
    return call(auth, "channel.operation.create", a -> service.run(request));
  }

  /** 运行详情：运行头 + 本次的命中记录 + 本次的执行记录。 */
  @GetMapping("/runs/{id}")
  public AdRuleRunDetail runDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.runDetail(id));
  }

  // ---- 命中记录（「规则命中记录」）----

  /** 命中记录：真分页 + 真筛选（命中与未命中都留痕）。 */
  @GetMapping("/hits")
  public AdRuleHitPage hits(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleId,
      @RequestParam(required = false) String runId,
      @RequestParam(required = false) Boolean matched,
      @RequestParam(required = false) Boolean selected,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.hits(uuid(ruleId), uuid(runId), matched, selected, page, size));
  }

  // ---- 调整执行记录（「调整执行记录」）----

  /** 执行记录：真分页 + 真筛选（试运行 / 真实执行 / 被拒绝都在）。 */
  @GetMapping("/executions")
  public AdRuleExecutionPage executions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleId,
      @RequestParam(required = false) String runId,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.executions(uuid(ruleId), uuid(runId), blank(outcome), page, size));
  }

  /** 执行记录详情。 */
  @GetMapping("/executions/{id}")
  public AdRuleExecutionRow execution(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.execution(id));
  }

  // ---- 待下发动作（下游 = 平台广告 API，本模块不实现真实下发）----

  /** 待下发动作：真分页 + 真筛选。 */
  @GetMapping("/dispatches")
  public AdRuleDispatchPage dispatches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleId,
      @RequestParam(required = false) String dispatchStatus,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.dispatches(uuid(ruleId), blank(dispatchStatus), page, size));
  }

  /**
   * 撤销一条待下发动作。
   *
   * <p>🔔 刻意**不提供**「标记已下发」：平台侧真实下发通道未交付，假装发过了就是假的。
   */
  @PostMapping("/dispatches/{id}/cancel")
  public AdRuleDispatchRow cancelDispatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody CancelDispatchRequest request) {
    return call(auth, "channel.operation.update", a -> service.cancelDispatch(id, request.reason()));
  }

  // ---- 效果回溯（「效果回溯」）----

  /** 效果回溯：真分页 + 真筛选。 */
  @GetMapping("/effects")
  public AdRuleEffectPage effects(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleId,
      @RequestParam(required = false) String verdict,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.effects(uuid(ruleId), blank(verdict), page, size));
  }

  /**
   * 回溯：把执行后的指标变化算出来，落成效果回溯记录。
   *
   * <p>🔔 「执行后还没有更新的指标数据」是一个**合法且必须被看见**的答案（{@code INSUFFICIENT_DATA}），
   * 不能拿 0 冒充「没变化」。
   */
  @PostMapping("/effects/evaluate")
  public AdRuleEffectPage evaluateEffects(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) EffectEvaluateRequest request) {
    EffectEvaluateRequest payload =
        request == null ? new EffectEvaluateRequest(null, "") : request;
    return call(auth, "channel.operation.create", a -> service.evaluateEffects(payload));
  }

  // ---- 工具 ----

  /** 空串与 null 同义，避免把 {@code ?platformId=} 当成「筛选空字符串」。 */
  private static String blank(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static UUID uuid(String value) {
    String trimmed = blank(value);
    if (trimmed == null) return null;
    try {
      return UUID.fromString(trimmed);
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "id 不是合法 UUID");
    }
  }
}
