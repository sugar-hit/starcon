package com.starcon.finance;

import static com.starcon.finance.BadDebtModels.*;

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
 * 12.1.1.3 坏账与核销的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/bad-debt}</b>：与 12.1.1.1 的 {@code /finance/receivables}、
 * 12.1.1.2 的 {@code /finance/receivable-aging} **刻意不共用一个前缀** ——
 * 那是别的模块的命名空间（端点、契约、页面都归各自所有）。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.baddebt.read / .create / .update / .approve}：
 * <b>审批用独立权限码</b>（{@code .approve}）—— 提交与审批是两件事，
 * 「能提交」不该顺带给出「能批准」。
 *
 * <p>🔴 <b>刻意没有「批量核销」端点</b>：蓝图加粗句「不允许批量一键核销无审批」。
 * 每一次核销都要有申请、有依据材料、有审批（且审批人 ≠ 提交人、意见必填）。
 */
@RestController
@RequestMapping("/finance/bad-debt")
public class BadDebtController {

  private final AccessService access;
  private final BadDebtService badDebt;

  public BadDebtController(AccessService access, BadDebtService badDebt) {
    this.access = access;
    this.badDebt = badDebt;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.baddebt." + permission);
    return access.run(actor, "finance.baddebt." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：上游真接通 / 下游缺口归谁 / 「会计处理不可用」的归属模块。 */
  @GetMapping("/capabilities")
  public FinanceBadDebtCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> badDebt.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（全部来自真实数据 + 本模块的枚举词表）。 */
  @GetMapping("/options")
  public FinanceBadDebtOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> badDebt.options());
  }

  // ================================================================ 汇总

  /** 坏账汇总：按状态 / 事由 / 币种（**不跨币种合计**）。 */
  @GetMapping("/summary")
  public FinanceBadDebtSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> badDebt.summary());
  }

  // ================================================================ 列表 / 详情

  /** 申请单列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集）。 */
  @GetMapping
  public FinanceBadDebtPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String reasonType,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID receivableId,
      @RequestParam(required = false) UUID disputeId,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            badDebt.page(
                new BadDebtQuery(
                    enumOf(status, BadDebtStatus.class, "坏账核销状态"),
                    enumOf(reasonType, BadDebtReasonType.class, "坏账事由"),
                    currency,
                    receivableId,
                    disputeId,
                    shopId,
                    keyword,
                    page,
                    size)));
  }

  /** 申请单详情：单头 + 依据材料 + 审计留痕 + 当前可执行动作。 */
  @GetMapping("/{id}")
  public FinanceBadDebtDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> badDebt.detail(id, actor));
  }

  // ================================================================ 建单

  /**
   * 提交坏账核销申请（草稿）。
   *
   * <p>⚠️ 目标应收单必须**已确认且一分未收**：已部分回款的应收不受理
   * （冲减「已核销」那部分属 12.2.1.2 核销的冲销范围）。
   */
  @PostMapping
  public FinanceBadDebtRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody FinanceBadDebtInput input) {
    return call(auth, "create", actor -> badDebt.create(input, actor, auth));
  }

  // ================================================================ 依据材料

  /** 加一条依据材料（只有草稿可以）。 */
  @PostMapping("/{id}/evidence")
  public FinanceBadDebtDetail addEvidence(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinanceBadDebtEvidenceInput input) {
    return call(auth, "update", actor -> badDebt.addEvidence(id, input, actor));
  }

  /**
   * 移除一条依据材料（只有草稿可以）。
   *
   * <p>⚠️ 走 **POST** 而不是 DELETE：finance 的 API 出口**刻意不导出 DELETE**
   * （应收单是凭据，错了只能作废不能删）—— 本模块不越界改那个出口的约定。
   * 服务层是**软移除**（{@code status='REMOVED'}），依据材料本身不可物理删除。
   */
  @PostMapping("/{id}/evidence/{evidenceId}/remove")
  public FinanceBadDebtDetail removeEvidence(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID evidenceId) {
    return call(auth, "update", actor -> badDebt.removeEvidence(id, evidenceId, actor));
  }

  // ================================================================ 提交 / 审批

  /** 提交审批。**必须**已有至少一条依据材料。 */
  @PostMapping("/{id}/submit")
  public FinanceBadDebtRow submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> badDebt.submit(id, actor, auth));
  }

  /**
   * 审批通过。**审批人 ≠ 提交人**，**审批意见必填**。
   *
   * <p>⚠️ 用**独立权限码** {@code finance.baddebt.approve}：提交与审批是两件事。
   */
  @PostMapping("/{id}/approve")
  public FinanceBadDebtRow approve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinanceBadDebtDecisionInput input) {
    return call(auth, "approve", actor -> badDebt.approve(id, input, actor));
  }

  /** 审批驳回。**审批意见必填**。 */
  @PostMapping("/{id}/reject")
  public FinanceBadDebtRow reject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinanceBadDebtDecisionInput input) {
    return call(auth, "approve", actor -> badDebt.reject(id, input, actor));
  }

  // ================================================================ 核销执行

  /**
   * 执行核销：**作废原应收单 + 为仍可收回的余额新建承接单**。
   *
   * <p>⚠️ 本模块**不写** {@code finance_receivable_line.settled_amount} —— 那一列的写者恒为 12.2.1.2 核销。
   */
  @PostMapping("/{id}/write-off")
  public FinanceBadDebtRow writeOff(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "approve", actor -> badDebt.writeOff(id, actor));
  }

  // ================================================================ 作废

  /** 作废申请（理由必填）。终态不可作废。 */
  @PostMapping("/{id}/void")
  public FinanceBadDebtRow voidCase(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinanceBadDebtVoidInput input) {
    return call(auth, "update", actor -> badDebt.voidCase(id, input, actor));
  }

  // ================================================================ 小工具

  /** 枚举参数解析：词表外的值直接 400（不猜、不静默忽略）。 */
  private static <E extends Enum<E>> E enumOf(String value, Class<E> type, String label) {
    if (value == null || value.isBlank()) return null;
    try {
      return Enum.valueOf(type, value.trim());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, label + "取值不合法：" + value);
    }
  }
}
