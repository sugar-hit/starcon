package com.starcon.channel;

import static com.starcon.channel.PiiAccessModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.6.1.1 PII 管理。
 *
 * <p>蓝图：「管理个人信息（买家姓名、地址、电话、邮箱）的访问与脱敏：默认脱敏展示、
 * 按角色授权查看完整信息、访问留痕、导出审批。输入：PII 访问请求；授权配置；导出申请。
 * 输出：脱敏数据或授权后的完整数据；访问审计记录。
 * 关系：上游 = 15 客户、9 订单；下游 = 客服与运营页面。
 * <b>完整地址查看需留痕</b>——批量导出 PII 是数据泄露事件的典型形态。」
 *
 * <p>🔴 端点清单（命名空间 {@code /channel/pii-access}，与既有的
 * {@code /channel/buyer-risk-scores}、{@code /channel/ip-risk-library} 等**并列而不重叠**）：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：字段脱敏规则、授权模型、审计与导出规则、边界。
 *   <li>{@code GET /policy} / {@code PUT /policy} —— 导出限量 / 审批有效期 / 每日查看上限（一租户一条）。
 *   <li>{@code GET /grants} / {@code PUT /grants} / {@code POST /grants/{id}/revoke} —— 授权配置。
 *   <li>{@code GET /subjects} —— PII 主体列表（**默认脱敏**，真分页 + 真搜索）。
 *   <li>{@code GET /subjects/{kind}/{ref}} —— 查看一个主体（默认脱敏；{@code reveal=true} 走授权判定）。
 *   <li>{@code GET /exports} / {@code POST /exports} —— 导出申请列表与提交申请。
 *   <li>{@code GET /exports/{id}} —— 导出申请详情（含逐条清单）。
 *   <li>{@code POST /exports/{id}/approve} / {@code POST /exports/{id}/reject} —— 审批 / 驳回。
 *   <li>{@code POST /exports/{id}/execute} —— 执行导出（**必须已审批**）。
 *   <li>{@code GET /audit} —— 访问审计（真分页 + 真筛选 + 汇总计数）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「导出全部」端点（导出对象必须逐条列明）、
 * 没有「关闭脱敏」端点（脱敏是硬默认）、没有「关闭审批」端点（审批是硬规则）、
 * 没有「删除审计」端点（审计是 append-only）——
 * <b>放行一个不存在的入口比 404 更糟</b>（界面会以为功能已交付）。
 *
 * <p>🔴 <b>被拒的请求为什么由控制器在事务提交后才转成 403</b>：
 * 服务层在事务里把那次越权尝试**写进了审计表**，而审计表是 append-only 的。
 * 如果在事务里抛异常，整个事务回滚 ⇒ <b>越权尝试的留痕会一起被回滚掉</b> ——
 * 那正是最该被看见的一类记录，却恰恰记不下来。所以：服务层返回「被拒」，
 * 控制器在 {@code access.run(...)} 返回（事务已提交）之后才抛 403。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改），identity 零改动。
 * 租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/channel/pii-access")
public class PiiAccessController {

  private final AccessService access;
  private final PiiAccessService service;

  public PiiAccessController(AccessService access, PiiAccessService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：字段脱敏规则、授权模型、审计与导出规则、上游读面、边界。 */
  @GetMapping("/capabilities")
  public PiiAccessCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 策略 ----

  /** 策略。没登记过返回 configured=false（**不预填任何默认值**）。 */
  @GetMapping("/policy")
  public PiiAccessPolicyView policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.policy());
  }

  /** 登记 / 修改策略（upsert，一租户一条）。 */
  @PutMapping("/policy")
  public PiiAccessPolicyView savePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PiiAccessPolicyInput input) {
    return call(auth, "channel.operation.update", a -> service.savePolicy(input));
  }

  // ---- 授权配置 ----

  /** 授权配置列表（真分页 + 真筛选）。 */
  @GetMapping("/grants")
  public PiiAccessGrantPage grants(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String roleCode,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.grants(roleCode, status, page, size));
  }

  /** 登记 / 修改授权配置（按「角色 × 字段」upsert）。 */
  @PutMapping("/grants")
  public PiiAccessGrantView saveGrant(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PiiAccessGrantInput input) {
    return call(auth, "channel.operation.update", a -> service.saveGrant(a, input));
  }

  /** 撤销授权（**理由必填**）。 */
  @PostMapping("/grants/{grantId}/revoke")
  public PiiAccessGrantView revokeGrant(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String grantId,
      @Valid @RequestBody PiiAccessGrantRevokeInput input) {
    return call(auth, "channel.operation.update", a -> service.revokeGrant(a, grantId, input));
  }

  // ---- 主体 ----

  /**
   * PII 主体列表（**默认脱敏**，真分页 + 真搜索）。
   *
   * <p>{@code kind} = {@code ORDER}（9 订单）或 {@code CUSTOMER}（15 客户）。
   */
  @GetMapping("/subjects")
  public PiiAccessSubjectPage subjects(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth, "channel.operation.read", a -> service.subjects(a, auth, kind, keyword, page, size));
  }

  /**
   * 查看一个主体的 PII。
   *
   * <p>🔴 <b>默认脱敏</b>：不带 {@code reveal=true} 时返回的永远是脱敏值。
   * {@code reveal=true} 时<b>必须带 {@code fields}</b>，且每一栏都要通过授权判定。
   */
  @GetMapping("/subjects/{kind}/{ref}")
  public PiiAccessSubjectDetail subject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String kind,
      @PathVariable String ref,
      @RequestParam(defaultValue = "false") boolean reveal,
      @RequestParam(required = false) List<String> fields,
      @RequestParam(required = false) String reason) {
    var actor = access.require(auth, "channel.operation.read");
    // 🔴 事务在 access.run 返回时**已经提交**（审计行已落库），之后才可能抛 403。
    var outcome =
        access.run(
            actor,
            "channel.operation.read",
            () -> service.subject(actor, auth, kind, ref, reveal, fields, reason));
    if (!outcome.allowed()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, outcome.denialReason());
    }
    return outcome.detail();
  }

  // ---- 导出 ----

  /** 导出申请列表（真分页 + 真筛选）。 */
  @GetMapping("/exports")
  public PiiAccessExportPage exports(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String kind,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.exportRequests(status, kind, page, size));
  }

  /** 导出申请详情（含逐条清单）。 */
  @GetMapping("/exports/{requestId}")
  public PiiAccessExportRequestView exportRequest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String requestId) {
    return call(auth, "channel.operation.read", a -> service.exportRequest(requestId));
  }

  /**
   * 提交导出申请（**必须走审批**；导出对象必须逐条列明）。
   */
  @PostMapping("/exports")
  public PiiAccessExportRequestView createExport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PiiAccessExportRequestInput input) {
    return call(auth, "channel.operation.create", a -> service.createExportRequest(a, input));
  }

  /** 批准导出申请（**审批人 ≠ 申请人**）。 */
  @PostMapping("/exports/{requestId}/approve")
  public PiiAccessExportRequestView approveExport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String requestId,
      @RequestBody(required = false) PiiAccessExportDecisionInput input) {
    return call(auth, "channel.operation.update", a -> service.approveExport(a, requestId, input));
  }

  /** 驳回导出申请（**理由必填**）。 */
  @PostMapping("/exports/{requestId}/reject")
  public PiiAccessExportRequestView rejectExport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String requestId,
      @RequestBody(required = false) PiiAccessExportDecisionInput input) {
    return call(auth, "channel.operation.update", a -> service.rejectExport(a, requestId, input));
  }

  /**
   * 执行导出（**必须已审批**）。
   *
   * <p>🔴 未审批 / 审批过期 / 超出当前上限 ⇒ 403，且这次尝试**已留在审计表里**。
   */
  @PostMapping("/exports/{requestId}/execute")
  public PiiAccessExportResult executeExport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String requestId) {
    var actor = access.require(auth, "channel.operation.create");
    // 🔴 事务提交之后才可能抛 403（审计行不能被回滚带走）。
    var outcome =
        access.run(
            actor, "channel.operation.create", () -> service.executeExport(actor, auth, requestId));
    if (!outcome.allowed()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, outcome.denialReason());
    }
    return outcome.result();
  }

  // ---- 审计 ----

  /** 访问审计（真分页 + 真筛选 + 与分页无关的汇总）。 */
  @GetMapping("/audit")
  public PiiAccessAuditPage audit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String subjectRef,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String decision,
      @RequestParam(required = false) String actorId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.audit(kind, subjectRef, action, decision, actorId, page, size));
  }
}
