package com.starcon.trade;

import static com.starcon.trade.PurchaseOrderApprovalModels.*;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 6.3.1.3 采购单审批（卷 06 采购与供应 / L3 6.3.1 采购单的第三格）。
 *
 * <p><b>命名空间是 {@code /trade/purchase-order-approvals}</b>：6.3.1.1（列表）占了
 * {@code /trade/purchase-orders}，6.3.1.2（编辑）占了 {@code /trade/purchase-order-editor}，
 * 本模块是第三个兄弟 ⇒ 首段用<b>独立字面量</b> {@code purchase-order-approvals}，
 * 与既有白名单里的任何一条都**零命中**（逐条核对过 {@code apps/web/src/lib/api-proxy.ts}
 * 的全部既有正则）。
 *
 * <p><b>租户绝不来自任何请求头</b>：{@link #call} / {@link #write} / {@link #approve} /
 * {@link #finance} 先向 identity 确认已认证成员关系与权限，再在一个事务里绑定
 * {@code app.current_tenant}，由 RLS 在数据库层强制隔离。伪造的 {@code X-Tenant-Id} 头
 * 改不动这个设置。
 *
 * <p><b>权限码分四档</b>：读（{@code trade.purchase.read}）、配置规则与审批人
 * （{@code trade.purchase.update}）、<b>审批</b>（{@code trade.purchase.approve}）、
 * 财务确认（{@code trade.purchase.finance}）。审批与编辑是两种不同的权力 ——
 * 谁能把单批成已批，不该由「能改这张单」顺带获得。
 *
 * <p><b>数据范围由服务端从角色授权推导</b>（ALL / DEPARTMENT / SELF），
 * <b>不接受请求参数传入</b>（照 6.3.1.1 / 6.3.1.2 / 9.1.1.1 的既有做法）。
 */
@RestController
@RequestMapping("/trade/purchase-order-approvals")
public class PurchaseOrderApprovalController {

  private final AccessService access;
  private final PurchaseOrderApprovalService approvals;

  public PurchaseOrderApprovalController(AccessService access, PurchaseOrderApprovalService approvals) {
    this.access = access;
    this.approvals = approvals;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, PurchaseOrderApprovalService.READ_PERMISSION);
    return access.run(actor, PurchaseOrderApprovalService.READ_PERMISSION, () -> work.apply(actor));
  }

  /** 配置入口（规则与审批人）。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, PurchaseOrderApprovalService.UPDATE_PERMISSION);
    return access.run(actor, PurchaseOrderApprovalService.UPDATE_PERMISSION, () -> work.apply(actor));
  }

  /** 审批入口：<b>独立</b>的审批权限码。 */
  private <T> T approveAs(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor =
        access.require(bearer, PurchaseOrderApprovalService.APPROVE_PERMISSION);
    return access.run(actor, PurchaseOrderApprovalService.APPROVE_PERMISSION, () -> work.apply(actor));
  }

  /** 财务确认入口：<b>独立</b>的财务权限码（照 11.2.3.3 的 trade.aftersales.finance 同款）。 */
  private <T> T finance(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, PurchaseOrderApprovalService.FINANCE_PERMISSION);
    return access.run(actor, PurchaseOrderApprovalService.FINANCE_PERMISSION, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：逐条说清本模块能做什么、哪些归别的模块（12 财务 / 08 仓储 / 17 组织）。 */
  @GetMapping("/capabilities")
  public PurchaseOrderApprovalCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> approvals.capabilities());
  }

  // ================================================================ 规则

  /** 审批规则列表（租户级配置，一次返回全部）。 */
  @GetMapping("/rules")
  public PurchaseOrderApprovalRuleList rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> approvals.rules());
  }

  /** 新建审批规则。 */
  @PostMapping("/rules")
  public PurchaseOrderApprovalRuleView createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PurchaseOrderApprovalRuleInput request) {
    return write(auth, actor -> approvals.createRule(request, actor.userId()));
  }

  /** 改审批规则（带 version 乐观锁；规则号建后不可改）。 */
  @PutMapping("/rules/{id}")
  public PurchaseOrderApprovalRuleView updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PurchaseOrderApprovalRuleInput request) {
    return write(auth, actor -> approvals.updateRule(id, request, actor.userId()));
  }

  /** 启用 / 停用审批规则。 */
  @PostMapping("/rules/{id}/status")
  public PurchaseOrderApprovalRuleView setRuleStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam("status") String status,
      @RequestParam("version") long version) {
    return write(auth, actor -> approvals.setRuleStatus(id, status, version, actor.userId()));
  }

  // ================================================================ 审批成员

  /** 采购员分级 + 审批人/层级配置。 */
  @GetMapping("/members")
  public PurchaseOrderApprovalMembers members(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> approvals.members());
  }

  /** 登记 / 改一个采购员的分级。 */
  @PostMapping("/members/buyers")
  public PurchaseOrderApprovalBuyerView upsertBuyer(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PurchaseOrderApprovalBuyerInput request) {
    return write(auth, actor -> approvals.upsertBuyer(request, actor.userId()));
  }

  /** 登记 / 改一个审批人（层级 + 金额授权区间）。 */
  @PostMapping("/members/approvers")
  public PurchaseOrderApprovalApproverView upsertApprover(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PurchaseOrderApprovalApproverInput request) {
    return write(auth, actor -> approvals.upsertApprover(request, actor.userId()));
  }

  // ================================================================ 审批台账

  /**
   * 审批台账（真分页 + 筛选 + 按币种分列的汇总）。
   *
   * @param caseStatus 审批单状态（PENDING / APPROVED / REJECTED）
   */
  @GetMapping("/orders")
  public PurchaseOrderApprovalPage orders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(value = "caseStatus", required = false) String caseStatus,
      @RequestParam(value = "tier", required = false) String tier,
      @RequestParam(value = "currency", required = false) String currency,
      @RequestParam(value = "supplierCode", required = false) String supplierCode,
      @RequestParam(value = "keyword", required = false) String keyword,
      @RequestParam(value = "page", defaultValue = "0") int page,
      @RequestParam(value = "size", defaultValue = "20") int size) {
    return call(
        auth,
        actor ->
            approvals.list(
                caseStatus,
                tier,
                currency,
                supplierCode,
                keyword,
                page,
                size,
                actor.userId(),
                actor.departmentId(),
                scopeMode(actor)));
  }

  /** 审批详情（单 + 审批链 + 采购单留痕 + 应付登记 + 收货放行 + 当前可执行动作）。 */
  @GetMapping("/orders/{id}")
  public PurchaseOrderApprovalDetail order(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        actor ->
            approvals.detail(id, actor.userId(), actor.departmentId(), scopeMode(actor)));
  }

  // ================================================================ 审批动作

  /** 审批通过（意见可选）。 */
  @PostMapping("/orders/{id}/approve")
  public PurchaseOrderApprovalDetail approve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) PurchaseOrderApprovalDecisionRequest request) {
    return approveAs(
        auth,
        actor ->
            approvals.approve(
                id,
                request,
                actor.userId(),
                actor.departmentId(),
                scopeMode(actor, PurchaseOrderApprovalService.APPROVE_PERMISSION)));
  }

  /** 驳回（<b>意见必填</b>）。 */
  @PostMapping("/orders/{id}/reject")
  public PurchaseOrderApprovalDetail reject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) PurchaseOrderApprovalDecisionRequest request) {
    return approveAs(
        auth,
        actor ->
            approvals.reject(
                id,
                request,
                actor.userId(),
                actor.departmentId(),
                scopeMode(actor, PurchaseOrderApprovalService.APPROVE_PERMISSION)));
  }

  /**
   * 批量审批 / 批量驳回。
   *
   * <p>🔴 **逐条给结论**：一张批不过的不影响其它张，失败原因逐条返回。
   */
  @PostMapping("/orders/batch")
  public PurchaseOrderApprovalBatchResult batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PurchaseOrderApprovalBatchRequest request) {
    return approveAs(
        auth,
        actor ->
            approvals.batch(
                request,
                actor.userId(),
                actor.departmentId(),
                scopeMode(actor, PurchaseOrderApprovalService.APPROVE_PERMISSION)));
  }

  // ================================================================ 应付台账

  /** 应付登记台账（登记额 / 生效额分列）。 */
  @GetMapping("/payables")
  public PurchaseOrderApprovalPayablePage payables(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "page", defaultValue = "0") int page,
      @RequestParam(value = "size", defaultValue = "20") int size) {
    return call(auth, actor -> approvals.payables(status, page, size));
  }

  /** 财务确认（登记的应付变生效；需独立财务权限码）。 */
  @PostMapping("/payables/{id}/confirm")
  public PurchaseOrderApprovalPayableView confirmPayable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PurchaseOrderApprovalPayableConfirmRequest request) {
    return finance(
        auth, actor -> approvals.confirmPayable(id, request == null ? "" : request.financeReference(), actor.userId()));
  }

  /** 财务驳回（登记的应付作废）。 */
  @PostMapping("/payables/{id}/reject")
  public PurchaseOrderApprovalPayableView rejectPayable(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PurchaseOrderApprovalPayableRejectRequest request) {
    return finance(
        auth, actor -> approvals.rejectPayable(id, request == null ? "" : request.reason(), actor.userId()));
  }

  // ================================================================ 数据范围

  /**
   * 数据范围：ALL / DEPARTMENT / SELF。
   *
   * <p>取值规则与 {@code AccessService.require} 同一套授权：<b>命中该权限的授权里</b>
   * 只要有一条 ALL 就按 ALL；否则有 DEPARTMENT 且当前成员有部门就按 DEPARTMENT；
   * 再否则按 SELF 收窄到本人负责的采购单。
   *
   * <p>⚠️ 「操作和范围必须由同一分配满足」（ADR-0005）：这里只看<b>含有本权限</b>的授权，
   * 别的权限的授权（哪怕是 ALL）不参与这个模块的数据范围判定。
   * 审批动作按<b>审批权限</b>判定范围，读动作按<b>读权限</b>判定范围 ——
   * 「能读全量但只能审自己范围内的单」是常见且合理的授权组合。
   */
  static String scopeMode(AccessService.Access a, String permission) {
    List<AccessService.Grant> grants =
        a.grants() == null ? List.<AccessService.Grant>of() : a.grants();
    boolean all =
        grants.stream()
            .anyMatch(g -> "ALL".equals(g.scopeMode()) && g.permissions().contains(permission));
    if (all) {
      return PurchaseOrderApprovalService.SCOPE_ALL;
    }
    boolean department =
        grants.stream()
            .anyMatch(
                g -> "DEPARTMENT".equals(g.scopeMode()) && g.permissions().contains(permission));
    if (department && a.departmentId() != null) {
      return PurchaseOrderApprovalService.SCOPE_DEPARTMENT;
    }
    return PurchaseOrderApprovalService.SCOPE_SELF;
  }

  /** 读路径的数据范围。 */
  static String scopeMode(AccessService.Access a) {
    return scopeMode(a, PurchaseOrderApprovalService.READ_PERMISSION);
  }
}
