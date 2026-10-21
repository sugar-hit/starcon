package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryAdjustmentApprovalModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 7.4.1.2 调整审批。
 *
 * <p>蓝图：「**按金额/数量阈值审批调整单，审批通过后才执行库存分录；支持批量审批与驳回。**」
 * 输入：「待审调整单；17 审批规则。」输出：「审批结果；执行后的库存分录；审计记录。」
 * 以及：「**库存调整是舞弊高发区**（无审批的调整 = 任意改账），阈值审批是必要控制。」
 *
 * <p>命名空间 {@code /warehouse/inventory/adjustment-approvals}：刻意与 7.4.1.1 的
 * {@code /warehouse/inventory/adjustments}（调整单本体）**分开一段** ——
 * 调整单是单据，审批是另一件事；把两件事塞进同一个前缀会让「单据接口」与「审批中心接口」
 * 在白名单与权限上互相打架。
 *
 * <p><b>批准动作本身归 7.4.1.1</b>：本模块的 {@code /approve} 做的是
 * 「阈值判定 → 记审批链路 → 人数凑齐 ⇒ 调 7.4.1.1 的批准端点」，
 * <b>不重复实现</b>那个动作（必填理由 + 提交人≠审批人 + DB 状态机都归它）。
 *
 * <p><b>权限码</b>：本模块用既有的 {@code warehouse.inventory.read} /
 * {@code warehouse.inventory.update}。专属的 {@code warehouse.inventory.approve}
 * 需要在 identity 注册权限码，而 <b>identity 的 Flyway 版本号没有预分配给本车道</b>
 * （当前 max V163）⇒ 并行车道撞号会让 identity 的 Flyway 直接炸掉，后果不可逆。
 * 所以本次**不自造未注册的权限码**（未注册的码会让所有人都 403）；
 * 专属权限码登记列为后续待办，能力声明里如实标出。
 */
@RestController
@RequestMapping("/warehouse")
public class InventoryAdjustmentApprovalController {

  private final AccessService access;
  private final InventoryAdjustmentApprovalService approvals;

  public InventoryAdjustmentApprovalController(
      AccessService access, InventoryAdjustmentApprovalService approvals) {
    this.access = access;
    this.approvals = approvals;
  }

  /**
   * 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），
   * 再在一个事务里绑定 RLS 租户上下文执行查询。
   */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 能力与配置

  /**
   * 能力声明：阈值审批、批量审批、驳回、审批链路、会计影响，以及**如实说清**的边界
   * （17 组织通用审批流为什么没接、金额为什么是申报值、12 财务为什么没有）。
   */
  @GetMapping("/inventory/adjustment-approvals/capabilities")
  public InventoryAdjustmentApprovalCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> approvals.capabilities());
  }

  /** 阈值规则列表（租户级配置）。 */
  @GetMapping("/inventory/adjustment-approvals/rules")
  public List<InventoryAdjustmentApprovalRule> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> approvals.rules());
  }

  /** 新建一条阈值规则。 */
  @PostMapping("/inventory/adjustment-approvals/rules")
  public InventoryAdjustmentApprovalRule createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody InventoryAdjustmentApprovalRuleInput input) {
    return call(auth, "inventory.update", a -> approvals.createRule(input, a.userId()));
  }

  /** 改一条阈值规则（已冻结的审批单不受影响 —— 判定在提交那一刻就冻结了）。 */
  @PostMapping("/inventory/adjustment-approvals/rules/{ruleId}/update")
  public InventoryAdjustmentApprovalRule updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody InventoryAdjustmentApprovalRuleInput input) {
    return call(auth, "inventory.update", a -> approvals.updateRule(ruleId, input, a.userId()));
  }

  /** 启用 / 停用一条阈值规则。 */
  @PostMapping("/inventory/adjustment-approvals/rules/{ruleId}/status")
  public InventoryAdjustmentApprovalRule setRuleStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody InventoryAdjustmentApprovalStatusInput input) {
    return call(
        auth, "inventory.update", a -> approvals.setRuleStatus(ruleId, input.status(), a.userId()));
  }

  /** 审批人授权列表。 */
  @GetMapping("/inventory/adjustment-approvals/approvers")
  public List<InventoryAdjustmentApprovalApprover> approvers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> approvals.approvers());
  }

  /** 登记一个审批人授权（层级 + 金额授权区间 + 数量授权上限）。 */
  @PostMapping("/inventory/adjustment-approvals/approvers")
  public InventoryAdjustmentApprovalApprover registerApprover(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody InventoryAdjustmentApprovalApproverInput input) {
    return call(auth, "inventory.update", a -> approvals.registerApprover(input, a.userId()));
  }

  /** 启用 / 停用一个审批人授权。 */
  @PostMapping("/inventory/adjustment-approvals/approvers/{approverId}/status")
  public InventoryAdjustmentApprovalApprover setApproverStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID approverId,
      @Valid @RequestBody InventoryAdjustmentApprovalStatusInput input) {
    return call(
        auth,
        "inventory.update",
        a -> approvals.setApproverStatus(approverId, input.status(), a.userId()));
  }

  // ------------------------------------------------------------ 工作台

  /**
   * 待我审批（真分页）。
   *
   * <p>每条都带「此刻我能不能批」与原因：不该由我批的**显示出来但标成不可批** ——
   * 直接不显示会让人以为「这单没人管」。
   */
  @GetMapping("/inventory/adjustment-approvals/pending")
  public InventoryAdjustmentApprovalPendingPage pending(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "inventory.read", a -> approvals.pending(a.userId(), page, size));
  }

  /**
   * 阈值判定（只读试算，不落库）。
   *
   * <p>让用户在提交之前看到「这张单会命中哪一档、要几级几个人批」。
   */
  @PostMapping("/inventory/adjustment-approvals/evaluate")
  public InventoryAdjustmentApprovalEvaluation evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody InventoryAdjustmentApprovalEvaluateInput input) {
    return call(auth, "inventory.read", a -> approvals.evaluate(input, a.userId()));
  }

  /**
   * 提交待审：**冻结阈值判定 + 登记估值**，然后调 7.4.1.1 的提交动作。
   *
   * <p>金额（登记额）必填：不申报金额就没有金额阈值，而且「不申报就能躲开金额档」
   * 是必须堵掉的口子。
   */
  @PostMapping("/inventory/adjustment-approvals/{orderId}/submit")
  public InventoryAdjustmentApprovalCaseDetail submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @Valid @RequestBody InventoryAdjustmentApprovalSubmitInput input) {
    return call(auth, "inventory.update", a -> approvals.submit(orderId, input, a.userId()));
  }

  /** 审批单详情 = 审批单 + 全部链路记录。 */
  @GetMapping("/inventory/adjustment-approvals/{orderId}")
  public InventoryAdjustmentApprovalCaseDetail approvalCase(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId) {
    return call(auth, "inventory.read", a -> approvals.caseDetail(orderId));
  }

  /**
   * 批准：阈值判定 → 记审批链路 → 人数凑齐则放行到 7.4.1.1 的批准动作。
   *
   * <p>批准**仍然不改余额** —— 它只是允许入账，入账是独立的 {@code /post} 一步。
   */
  @PostMapping("/inventory/adjustment-approvals/{orderId}/approve")
  public InventoryAdjustmentApprovalCaseDetail approve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody InventoryAdjustmentApprovalNoteInput input) {
    return call(
        auth,
        "inventory.update",
        a -> approvals.approve(orderId, input == null ? null : input.note(), a.userId()));
  }

  /** 驳回：意见必填；驳回后改完重提会开新的一条审批链。 */
  @PostMapping("/inventory/adjustment-approvals/{orderId}/reject")
  public InventoryAdjustmentApprovalCaseDetail reject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID orderId,
      @RequestBody InventoryAdjustmentApprovalNoteInput input) {
    return call(
        auth,
        "inventory.update",
        a -> approvals.reject(orderId, input == null ? null : input.note(), a.userId()));
  }

  /** 批量审批：逐条给结论（成功条数 + 失败原因），**不静默丢弃**失败的那几张。 */
  @PostMapping("/inventory/adjustment-approvals/batch/approve")
  public InventoryAdjustmentApprovalBatchResult batchApprove(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody InventoryAdjustmentApprovalBatchInput input) {
    return call(
        auth, "inventory.update", a -> approvals.batch(input, a.userId(), true));
  }

  /** 批量驳回：意见必填；逐条给结论。 */
  @PostMapping("/inventory/adjustment-approvals/batch/reject")
  public InventoryAdjustmentApprovalBatchResult batchReject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody InventoryAdjustmentApprovalBatchInput input) {
    return call(
        auth, "inventory.update", a -> approvals.batch(input, a.userId(), false));
  }

  /**
   * 调整的会计影响台账（登记额 / 生效额分两列）。
   *
   * <p>12 财务服务在本仓不存在 ⇒ 这里只是**warehouse 侧自有事实**，不跨库读 finance。
   */
  @GetMapping("/inventory/adjustment-approvals/valuations")
  public InventoryAdjustmentApprovalValuationPage valuations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "inventory.read", a -> approvals.valuations(status, page, size));
  }
}
