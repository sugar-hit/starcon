package com.starcon.channel;

import static com.starcon.channel.PlatformViolationModels.*;

import java.util.List;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.1.2.1 违规与处罚。
 *
 * <p>蓝图：「记录与管理平台违规与处罚：违规类型、涉及商品/订单、处罚措施（警告/下架/限制销售/扣款/停号）、
 * 处理状态与整改要求。输入：平台违规通知同步；整改记录。输出：违规记录 + 整改跟踪 + 扣款金额（进 12 财务）。
 * 关系：上游 = 2 同步；下游 = 12 财务（罚扣款）、4 刊登（问题商品下架）、16.1.2.2 申诉。
 * <b>违规常涉及多个商品</b>，需批量定位受影响 listing 与库存。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：值域、状态机、未交付下游逐条列出。
 *   <li>{@code GET /shops} —— 店铺范围清单（**真分页**，来自本服务自己的店铺目录）。
 *   <li>{@code GET /violations} —— 违规记录清单（**真分页 + 真筛选**）。
 *   <li>{@code POST /violations} —— 人工登记违规（**source 恒为 MANUAL**，**幂等**）。
 *   <li>{@code GET /violations/{id}} —— 违规详情（处罚 + 涉及主体 + 整改要求 + 整改凭证）。
 *   <li>{@code POST /violations/{id}/status} —— 处理状态流转（**非法转换 422**）。
 *   <li>{@code POST /violations/{id}/penalties} —— 记一条处罚措施（**只有扣款带金额 + 币种**）。
 *   <li>{@code POST /violations/{id}/subjects} —— 批量挂涉及商品 / 订单（**多对多**）。
 *   <li>{@code GET /violations/{id}/affected} —— **批量定位**受影响 listing 与库存。
 *   <li>{@code POST /violations/{id}/delist} —— 批量下架（下游 4 刊登 4.1.1.1，**已交付**）。
 *   <li>{@code POST /violations/{id}/remediations} —— 记一条整改要求。
 *   <li>{@code GET /remediations} —— 整改要求清单（**真分页**）。
 *   <li>{@code PUT /remediations/{id}} —— 整改状态流转（**非法转换 422**）。
 *   <li>{@code POST /remediations/{id}/evidence} —— 追加整改凭证（**append-only**）。
 *   <li>{@code GET /penalties} —— 处罚清单（**真分页** + **按币种分组的合计**）。
 *   <li>{@code POST /shops/{shopId}/sync} —— 🔴 同步入口：接收平台违规通知（**幂等**）。
 *   <li>{@code GET /sync-runs} —— 同步运行记录（**真分页**）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有申诉端点、没有财务端点 —— 16.1.2.2 申诉管理与 12 财务
 * <b>都未交付</b>，放行一个不存在的入口比 404 更糟（界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改）
 * 与 {@code channel.sync.trigger}（触发同步），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/platform-violations")
public class PlatformViolationController {

  private final AccessService access;
  private final PlatformViolationService service;

  public PlatformViolationController(AccessService access, PlatformViolationService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：值域、状态机，以及**未交付的下游逐条列出**（available=false + ownerModule + 影响）。 */
  @GetMapping("/capabilities")
  public PlatformViolationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 店铺范围 ----

  /** 店铺范围清单（真分页 + 真统计；数据来自本服务自己的店铺目录）。 */
  @GetMapping("/shops")
  public PlatformViolationShopPage shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.shops(platformCode, keyword, shopId, page, size));
  }

  // ---- 违规记录 ----

  /** 违规记录清单（真分页 + 真筛选）。 */
  @GetMapping("/violations")
  public PlatformViolationPage violations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String violationType,
      @RequestParam(required = false) String measure,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.violations(
                shopId, status, violationType, measure, source, keyword, from, to, page, size));
  }

  /** 人工登记违规（source 恒为 MANUAL；按平台通知 id 幂等）。 */
  @PostMapping("/violations")
  public PlatformViolationRegistration register(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PlatformViolationRegisterInput input) {
    return call(auth, "channel.operation.create", a -> service.register(input));
  }

  /** 违规详情（处罚 + 涉及主体 + 整改要求 + 整改凭证）。 */
  @GetMapping("/violations/{id}")
  public PlatformViolationDetail violation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.violation(id));
  }

  /** 处理状态流转（非法转换 422）。 */
  @PostMapping("/violations/{id}/status")
  public PlatformViolation transition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody PlatformViolationStatusInput input) {
    return call(auth, "channel.operation.update", a -> service.transition(id, input));
  }

  /** 记一条处罚措施（只有扣款带金额 + 币种）。 */
  @PostMapping("/violations/{id}/penalties")
  public PlatformPenalty addPenalty(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody PlatformPenaltyInput input) {
    return call(auth, "channel.operation.create", a -> service.addPenalty(id, input));
  }

  /** 批量挂涉及商品 / 订单（多对多）。 */
  @PostMapping("/violations/{id}/subjects")
  public PlatformViolationSubjectBatchResult attachSubjects(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody List<PlatformViolationSubjectInput> body) {
    return call(auth, "channel.operation.create", a -> service.attachSubjects(id, body));
  }

  /** 🔴 批量定位受影响 listing 与库存（一次 SQL + 一次库存调用）。 */
  @GetMapping("/violations/{id}/affected")
  public PlatformViolationAffected affected(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false) String warehouseId) {
    return call(auth, "channel.operation.read", a -> service.affected(id, warehouseId, auth));
  }

  /** 🔴 批量下架（下游 4 刊登 · 4.1.1.1 的 DELIST，**同服务，已交付**）。 */
  @PostMapping("/violations/{id}/delist")
  public PlatformViolationDelistResult delist(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.update", a -> service.delist(id, auth));
  }

  // ---- 整改跟踪 ----

  /** 记一条整改要求。 */
  @PostMapping("/violations/{id}/remediations")
  public PlatformViolationRemediation addRemediation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody PlatformViolationRemediationInput input) {
    return call(auth, "channel.operation.create", a -> service.addRemediation(id, input));
  }

  /** 整改要求清单（真分页 + 真筛选）。 */
  @GetMapping("/remediations")
  public PlatformViolationRemediationPage remediations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false) String violationId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String overdueOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.remediations(shopId, violationId, status, overdueOnly, page, size));
  }

  /** 整改状态流转（非法转换 422）。 */
  @PutMapping("/remediations/{id}")
  public PlatformViolationRemediation transitionRemediation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody PlatformViolationRemediationStatusInput input) {
    return call(auth, "channel.operation.update", a -> service.transitionRemediation(id, input));
  }

  /** 追加整改凭证（append-only：不可改写、不可删）。 */
  @PostMapping("/remediations/{id}/evidence")
  public PlatformViolationEvidence addEvidence(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody PlatformViolationEvidenceInput input) {
    return call(auth, "channel.operation.create", a -> service.addEvidence(id, input));
  }

  // ---- 处罚清单 ----

  /** 处罚清单（真分页 + 按币种分组的合计）。 */
  @GetMapping("/penalties")
  public PlatformPenaltyPage penalties(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false) String violationId,
      @RequestParam(required = false) String measure,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.penalties(shopId, violationId, measure, page, size));
  }

  // ---- 同步 ----

  /**
   * 🔴 同步入口：接收一批平台违规通知。
   *
   * <p>平台违规通知是**平台推送**的形态，所以入口是「接收」。每条通知按
   * {@code externalNoticeId} **幂等**：已存在 ⇒ 跳过（不新建、不改写）。
   */
  @PostMapping("/shops/{shopId}/sync")
  public PlatformViolationSyncResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String shopId,
      @RequestBody PlatformViolationSyncInput input) {
    return call(auth, "channel.sync.trigger", a -> service.sync(shopId, input));
  }

  /** 同步运行记录（真分页）。 */
  @GetMapping("/sync-runs")
  public PlatformViolationSyncRunPage syncRuns(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.syncRuns(shopId, page, size));
  }
}
