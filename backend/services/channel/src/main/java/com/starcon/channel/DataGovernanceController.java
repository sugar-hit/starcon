package com.starcon.channel;

import static com.starcon.channel.DataGovernanceModels.*;

import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.6.2.1 数据出境与留存。
 *
 * <p>蓝图：「管理数据合规：跨境数据传输登记与合法性基础、数据留存期配置、到期清理与删除请求（被遗忘权）、
 * 保留期与财务留存期的区分。输入：数据流登记；留存策略；删除请求。
 * 输出：合规登记 + 清理/删除执行记录。关系：上游 = 17 权限、全部数据域；下游 = 数据清理任务。
 * <b>财务数据有法定留存期，PII 有 GDPR 删除权，两者冲突时按财务留存优先</b>——实体目录 §5 已明确这条边界。」
 *
 * <p>🔴 端点清单（命名空间 {@code /channel/data-governance}，与既有的
 * {@code /channel/pii-access}、{@code /channel/buyer-risk-scores} 等**并列而不重叠**）：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：上游可用性、登记 / 留存 / 冲突 / 删除 / 清理口径、边界。
 *   <li>{@code GET /domains} / {@code PUT /domains} —— 数据域登记表（**本模块自建的核心事实表**）。
 *   <li>{@code GET /transfers} / {@code POST /transfers} —— 跨境数据传输登记与合法性基础。
 *   <li>{@code GET /transfers/{id}} / {@code POST /transfers/{id}/suspend} / {@code POST /transfers/{id}/end}
 *       —— 单条登记、暂停、终止（**理由必填**；ENDED 是终态）。
 *   <li>{@code GET /retention-policies} / {@code POST /retention-policies} —— 留存策略（**版本化**）。
 *   <li>{@code GET /retention-conflicts} —— <b>实时</b>留存期裁决清单（冲突对 / 双方期限 / 裁决结果与依据）。
 *   <li>{@code GET /deletion-requests} / {@code POST /deletion-requests} —— 删除请求列表与提交。
 *   <li>{@code GET /deletion-requests/{id}} —— 删除请求详情。
 *   <li>{@code POST /deletion-requests/{id}/approve} / {@code /reject} / {@code /execute}
 *       —— 审批 / 驳回 / 执行。
 *   <li>{@code GET /cleanup-orders} / {@code POST /cleanup-orders} —— 清理工单（**可供下游任务消费**）。
 *   <li>{@code GET /cleanup-orders/{id}} / {@code POST /cleanup-orders/{id}/execute}
 *       —— 单张工单与执行回报。
 *   <li>{@code GET /cleanup-records} —— 清理执行记录（**append-only**，真分页）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「直接删除」端点（物理删除跨服务做不了，本模块只出工单）、
 * 没有「跳过审批」端点、没有「改写留存策略」端点（改策略 = 登记新版本，
 * 历史判定不被追溯改变）、没有「删除执行记录」端点（append-only）。
 * <b>放行一个不存在的入口比 404 更糟</b>（界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改），identity 零改动。
 * 租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/channel/data-governance")
public class DataGovernanceController {

  private final AccessService access;
  private final DataGovernanceService service;

  public DataGovernanceController(AccessService access, DataGovernanceService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：上游可用性、登记 / 留存 / 冲突 / 删除 / 清理口径、边界与下游归属。 */
  @GetMapping("/capabilities")
  public DataGovernanceCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 数据域 ----

  /** 数据域列表（真分页 + 真筛选）。 */
  @GetMapping("/domains")
  public DataGovernanceDomainPage domains(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String domainCode,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.domains(domainCode, page, size));
  }

  /** 登记 / 修改数据域（upsert，按 domain_code）。 */
  @PutMapping("/domains")
  public DataGovernanceDomainView saveDomain(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DataGovernanceDomainInput input) {
    return call(auth, "channel.operation.update", a -> service.saveDomain(input));
  }

  // ---- 跨境传输 ----

  /** 跨境传输登记列表（真分页 + 真筛选 + 汇总）。 */
  @GetMapping("/transfers")
  public DataGovernanceTransferPage transfers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String domainCode,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.transfers(status, domainCode, page, size));
  }

  /** 登记一条跨境数据传输。 */
  @PostMapping("/transfers")
  public DataGovernanceTransferView createTransfer(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DataGovernanceTransferInput input) {
    return call(auth, "channel.operation.create", a -> service.createTransfer(a, input));
  }

  /** 单条跨境传输登记。 */
  @GetMapping("/transfers/{transferId}")
  public DataGovernanceTransferView transfer(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String transferId) {
    return call(auth, "channel.operation.read", a -> service.transfer(transferId));
  }

  /** 暂停一条跨境传输（**理由必填**）。 */
  @PostMapping("/transfers/{transferId}/suspend")
  public DataGovernanceTransferView suspendTransfer(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String transferId,
      @Valid @RequestBody DataGovernanceTransferStatusInput input) {
    return call(auth, "channel.operation.update", a -> service.suspendTransfer(a, transferId, input));
  }

  /** 终止一条跨境传输（**理由必填**；ENDED 是终态）。 */
  @PostMapping("/transfers/{transferId}/end")
  public DataGovernanceTransferView endTransfer(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String transferId,
      @Valid @RequestBody DataGovernanceTransferStatusInput input) {
    return call(auth, "channel.operation.update", a -> service.endTransfer(a, transferId, input));
  }

  // ---- 留存策略 ----

  /** 留存策略列表（真分页 + 真筛选）。 */
  @GetMapping("/retention-policies")
  public RetentionPolicyPage policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String domainCode,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.policies(domainCode, page, size));
  }

  /**
   * 登记一条留存策略（**新增一个版本**；同（数据域 × 类别）的旧版本自动置 SUPERSEDED）。
   *
   * <p>🔴 生效时刻<b>不许回填</b>（回填会推翻过去的判定）；留存天数<b>必须是整天</b>。
   */
  @PostMapping("/retention-policies")
  public RetentionPolicyView createPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RetentionPolicyInput input) {
    return call(auth, "channel.operation.create", a -> service.createPolicy(a, input));
  }

  /**
   * <b>实时</b>留存期裁决清单（冲突对 / 双方期限 / 裁决结果与依据）。
   *
   * <p>🔴 这是「保留期与财务留存期冲突」的<b>可见裁决展示</b> —— 不只存不用。
   */
  @GetMapping("/retention-conflicts")
  public RetentionConflictPage conflicts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String domainCode,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.conflicts(domainCode, page, size));
  }

  // ---- 删除请求 ----

  /** 删除请求列表（真分页 + 真筛选 + 汇总）。 */
  @GetMapping("/deletion-requests")
  public DataGovernanceDeletionPage deletionRequests(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String domainCode,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", a -> service.deletionRequests(status, domainCode, page, size));
  }

  /** 提交一条删除请求（**理由必填**）。 */
  @PostMapping("/deletion-requests")
  public DataGovernanceDeletionRequestView createDeletionRequest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DataGovernanceDeletionRequestInput input) {
    return call(auth, "channel.operation.create", a -> service.createDeletionRequest(a, input));
  }

  /** 单条删除请求详情。 */
  @GetMapping("/deletion-requests/{requestId}")
  public DataGovernanceDeletionRequestView deletionRequest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String requestId) {
    return call(auth, "channel.operation.read", a -> service.deletionRequest(requestId));
  }

  /**
   * 审批一条删除请求（**审批人 ≠ 申请人**）。
   *
   * <p>🔴 审批时做<b>财务留存优先</b>的裁决：法定留存期没走完 ⇒ 判成
   * <b>DEFERRED（推迟）</b>，并写明推迟到哪一天、为什么。
   */
  @PostMapping("/deletion-requests/{requestId}/approve")
  public DataGovernanceDeletionRequestView approveDeletion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String requestId,
      @RequestBody(required = false) DataGovernanceDecisionInput input) {
    return call(auth, "channel.operation.update", a -> service.approveDeletion(a, requestId, input));
  }

  /** 驳回一条删除请求（**理由必填**）。 */
  @PostMapping("/deletion-requests/{requestId}/reject")
  public DataGovernanceDeletionRequestView rejectDeletion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String requestId,
      @Valid @RequestBody DataGovernanceRejectInput input) {
    return call(auth, "channel.operation.update", a -> service.rejectDeletion(a, requestId, input));
  }

  /**
   * 执行一条删除请求（**必须已批准**）。
   *
   * <p>🔴 执行 = **派发清理工单**（物理删除跨服务做不了）。
   * 被财务留存期推迟（DEFERRED）的请求 ⇒ 409，且写明要等到哪一天。
   */
  @PostMapping("/deletion-requests/{requestId}/execute")
  public DataGovernanceDeletionRequestView executeDeletion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String requestId) {
    return call(auth, "channel.operation.create", a -> service.executeDeletion(a, requestId));
  }

  // ---- 清理工单 ----

  /** 清理工单列表（真分页 + 真筛选 + 汇总）。 */
  @GetMapping("/cleanup-orders")
  public RetentionCleanupPage cleanupOrders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String domainCode,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.cleanupOrders(status, domainCode, page, size));
  }

  /**
   * 登记一张清理工单。
   *
   * <p>🔴 <b>最早可清理日由生效留存期算出来</b>，完成截止日不得早于它 ——
   * 财务（法定）留存期没走完之前不许排清理。
   */
  @PostMapping("/cleanup-orders")
  public RetentionCleanupOrderView createCleanupOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RetentionCleanupOrderInput input) {
    return call(auth, "channel.operation.create", a -> service.createCleanupOrder(a, input));
  }

  /** 单张清理工单。 */
  @GetMapping("/cleanup-orders/{orderId}")
  public RetentionCleanupOrderView cleanupOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String orderId) {
    return call(auth, "channel.operation.read", a -> service.cleanupOrder(orderId));
  }

  /** 执行清理工单（写一条 append-only 执行记录）。 */
  @PostMapping("/cleanup-orders/{orderId}/execute")
  public RetentionCleanupOrderView executeCleanupOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String orderId,
      @Valid @RequestBody RetentionCleanupExecuteInput input) {
    return call(auth, "channel.operation.update", a -> service.executeCleanupOrder(a, orderId, input));
  }

  // ---- 清理执行记录 ----

  /** 清理执行记录（**append-only**；真分页 + 真筛选）。 */
  @GetMapping("/cleanup-records")
  public RetentionCleanupRecordPage cleanupRecords(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String orderId,
      @RequestParam(required = false) String domainCode,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth, "channel.operation.read", a -> service.cleanupRecords(orderId, domainCode, page, size));
  }
}
