package com.starcon.channel;

import static com.starcon.channel.LogisticsTransitModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 10.1.1.3 时效与限制。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>🔴 本控制器**刻意没有渠道身份字段的写入口**：渠道代码 / 名称 / 承运商 / 计费归
 * 10.1.1.2，本模块只持有 {@code channelId} 一个引用 —— 复制一份就会造出
 * 「同一个渠道在两处各改一次」。
 *
 * <p>🔴 {@code POST /precheck} 是蓝图加粗句的落点：限制命中提示由<b>服务端</b>判定，
 * 它把上游 10.1.1.2 的渠道限制与本模块的尺寸重量上限、标记要求汇总成一个结论，
 * 规则只有一份。下游（10.1.2.1 运费试算 / 10.1.2.2 渠道比价 / 9.3.2.1 分仓 /
 * 10.2.1.2 运单申请 / 4.2.3.2 合规预检 / 5.3.1.1 测算）拿到结论与理由即可，
 * **不应该也不需要自己再判断一遍**。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}
 * （与 10.1.1.1 承运商、10.1.1.2 渠道配置、10.3.2.1 交运批次同族），
 * 不需要改动 identity 服务。
 */
@RestController
@RequestMapping("/channel/logistics-transit-limits")
public class LogisticsTransitController {
  private final AccessService access;
  private final LogisticsTransitService service;

  public LogisticsTransitController(AccessService access, LogisticsTransitService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  private void callVoid(String bearer, String permission, Consumer<AccessService.Access> work) {
    var actor = access.require(bearer, permission);
    access.run(
        actor,
        permission,
        () -> {
          work.accept(actor);
          return null;
        });
  }

  // ---- 能力声明 ----

  @GetMapping("/capabilities")
  public LogisticsTransitCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 概览：全量口径的分布，**不受列表筛选与分页影响**。 */
  @GetMapping("/overview")
  public LogisticsTransitOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.overview());
  }

  // ---- 时效规则（分国家 × 分重量段 × 服务等级）----

  /** 时效规则列表：真分页 + 真筛选，汇总走独立查询不受 limit/offset 影响。 */
  @GetMapping("/transit-rules")
  public TransitRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String channelId,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false) String serviceLevel,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String readiness,
      @RequestParam(required = false, defaultValue = "DEFAULT") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.rules(
                keyword, channelId, countryCode, serviceLevel, status, readiness, sort, page, size));
  }

  @GetMapping("/transit-rules/{id}")
  public TransitRuleRow ruleDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.ruleDetail(UUID.fromString(id)));
  }

  /** 登记 / 改一条时效规则。请求体<b>不接受</b>渠道身份字段。 */
  @PostMapping("/transit-rules")
  public TransitRuleRow saveRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveTransitRuleRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveRule(request));
  }

  @DeleteMapping("/transit-rules/{id}")
  public org.springframework.http.ResponseEntity<Void> deleteRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    callVoid(auth, "channel.operation.delete", a -> service.deleteRule(UUID.fromString(id)));
    return org.springframework.http.ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }

  // ---- 尺寸上限 / 重量上限 ----

  @GetMapping("/size-limits")
  public SizeLimitPage sizeLimits(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String channelId,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read",
        a -> service.sizeLimits(keyword, channelId, countryCode, page, size));
  }

  @GetMapping("/size-limits/{id}")
  public SizeLimitRow sizeLimitDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.sizeLimitDetail(UUID.fromString(id)));
  }

  @PostMapping("/size-limits")
  public SizeLimitRow saveSizeLimit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveSizeLimitRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveSizeLimit(request));
  }

  @DeleteMapping("/size-limits/{id}")
  public org.springframework.http.ResponseEntity<Void> deleteSizeLimit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    callVoid(auth, "channel.operation.delete", a -> service.deleteSizeLimit(UUID.fromString(id)));
    return org.springframework.http.ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }

  // ---- 电池 / 液体 / 危险品 / 磁力 标记要求 ----

  @GetMapping("/markings")
  public MarkingRequirementPage markings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String channelId,
      @RequestParam(required = false) String flagKind,
      @RequestParam(required = false) String requirementMode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.markings(channelId, flagKind, requirementMode, page, size));
  }

  @GetMapping("/markings/{id}")
  public MarkingRequirementRow markingDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.markingDetail(UUID.fromString(id)));
  }

  @PostMapping("/markings")
  public MarkingRequirementRow saveMarking(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveMarkingRequirementRequest request) {
    return call(auth, "channel.operation.create", a -> service.saveMarking(request));
  }

  @DeleteMapping("/markings/{id}")
  public org.springframework.http.ResponseEntity<Void> deleteMarking(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    callVoid(auth, "channel.operation.delete", a -> service.deleteMarking(UUID.fromString(id)));
    return org.springframework.http.ResponseEntity.status(HttpStatus.NO_CONTENT).build();
  }

  // ---- 🔴 两个判定端点（下游只读，不要重复实现规则）----

  /**
   * 时效估算：分国家 + 重量 → 命中重量段 → 预计到达区间。
   *
   * <p>刻意<b>只读</b>：估算不产生任何副作用，所以用只读权限码。下游
   * 5.3.1.1 测算（时效影响转化）与 10.2.2.2 面单模板都可以在任意时刻调它。
   */
  @PostMapping("/estimate")
  public TransitEstimateResult estimate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TransitEstimateRequest request) {
    return call(auth, "channel.operation.read", a -> service.estimate(request));
  }

  /**
   * 🔴 限制命中提示（选品与刊登阶段）。
   *
   * <p>汇总上游 10.1.1.2 的渠道限制 + 本模块的尺寸重量上限与标记要求，给出
   * PASS / REVIEW / BLOCK 与逐条结构化理由。刻意<b>只读</b>，任何能看渠道的人
   * 都能在上架前自查一遍。
   */
  @PostMapping("/precheck")
  public LimitPrecheckResult precheck(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LimitPrecheckRequest request) {
    return call(auth, "channel.operation.read", a -> service.precheck(request));
  }
}
