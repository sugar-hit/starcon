package com.starcon.platform;

import static com.starcon.platform.FinanceSoftwareModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 18.2.1.1「财务软件」的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/finance-software}</b>：与 18.4.2.1 的 {@code /platform/text}、
 * 18.1.1.1 的 {@code /platform/apps} 同服务、不同前缀，三者管的是完全不同的东西
 * （「谁可以接入我们」/「文案怎么翻」/「凭证怎么交给外部财务软件」）。
 * 与 finance 服务自己的 {@code /finance/vouchers} 也**不是一回事** ——
 * 那一组是**业务账**（本系统自己的账），本组是**法定账的衔接**（本系统 ↔ 外部财务软件）。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码 {@code platform.finance.read / .create / .update / .export / .reconcile / .push}</b>：
 * 「导出凭证文件」「算核对差异」「直连推送」是三个**独立**的码 ——
 * 能配科目映射的人不该顺手把法定凭证文件导出去，能导出文件的人也不该顺手把对账结论定了。
 */
@RestController
@RequestMapping("/platform/finance-software")
public class FinanceSoftwareController {

  private final AccessService access;
  private final FinanceSoftwareService finance;

  public FinanceSoftwareController(AccessService access, FinanceSoftwareService finance) {
    this.access = access;
    this.finance = finance;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.finance." + permission);
    return access.run(actor, "platform.finance." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与概览

  /** 能力声明：哪些真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceSoftwareCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> finance.capabilities());
  }

  /** 概览：几个数。 */
  @GetMapping("/overview")
  public FinanceSoftwareOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> finance.overview());
  }

  // ================================================================ 连接档案

  /** 连接档案列表（真筛选）。 */
  @GetMapping("/accounts")
  public List<FinanceSoftwareAccountRow> accounts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String vendor,
      @RequestParam(required = false) String status) {
    return call(auth, "read", actor -> finance.accounts(vendor, status));
  }

  /** 新增或修改一个连接档案。 */
  @PostMapping("/accounts")
  public FinanceSoftwareAccountRow saveAccount(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceSoftwareAccountInput input) {
    return call(auth, "create", actor -> finance.saveAccount(input, actor));
  }

  /** 停用一个连接档案（**不删**：历史凭证文件与核对任务要能复现）。 */
  @PostMapping("/accounts/{id}/disable")
  public FinanceSoftwareAccountRow disableAccount(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> finance.disableAccount(id, actor));
  }

  // ================================================================ 科目映射

  /** 科目映射列表（真筛选 + 真关键词）。 */
  @GetMapping("/subject-maps")
  public List<FinanceSoftwareSubjectMapRow> subjectMaps(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) String keyword) {
    return call(auth, "read", actor -> finance.subjectMaps(accountId, keyword));
  }

  /** 新增或修改一条科目映射。 */
  @PostMapping("/subject-maps")
  public FinanceSoftwareSubjectMapRow saveSubjectMap(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceSoftwareSubjectMapInput input) {
    return call(auth, "update", actor -> finance.saveSubjectMap(input, actor));
  }

  /** 删除一条科目映射。 */
  @DeleteMapping("/subject-maps/{id}")
  public void deleteSubjectMap(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "update",
        actor -> {
          finance.deleteSubjectMap(id);
          return null;
        });
  }

  // ================================================================ 主数据映射

  /** 主数据映射列表（真筛选）。 */
  @GetMapping("/master-maps")
  public List<FinanceSoftwareMasterMapRow> masterMaps(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) String masterType) {
    return call(auth, "read", actor -> finance.masterMaps(accountId, masterType));
  }

  /** 新增或修改一条主数据映射。 */
  @PostMapping("/master-maps")
  public FinanceSoftwareMasterMapRow saveMasterMap(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceSoftwareMasterMapInput input) {
    return call(auth, "update", actor -> finance.saveMasterMap(input, actor));
  }

  /** 删除一条主数据映射。 */
  @DeleteMapping("/master-maps/{id}")
  public void deleteMasterMap(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "update",
        actor -> {
          finance.deleteMasterMap(id);
          return null;
        });
  }

  // ================================================================ 凭证文件

  /** 凭证文件导出记录列表（真数据）。 */
  @GetMapping("/voucher-files")
  public List<FinanceSoftwareVoucherFileRow> voucherFiles(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID accountId) {
    return call(auth, "read", actor -> finance.voucherFiles(accountId));
  }

  /** 凭证文件详情（**含真内容** —— 事后要能原样取回当初导出的那一份）。 */
  @GetMapping("/voucher-files/{id}")
  public FinanceSoftwareVoucherFileDetail voucherFile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> finance.voucherFile(id));
  }

  /**
   * 导出凭证文件（CSV / Excel / XML）。
   *
   * <p>🔴 真读上游 12.5.2.1 的**已过账**凭证 → 套科目映射 → 生成**真文件内容** →
   * 落 sha256 / 字节数 / 借贷合计 / 未映射行数 → 内容一并落库。
   */
  @PostMapping("/voucher-files/export")
  public FinanceSoftwareVoucherExportResult exportVoucherFile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceSoftwareVoucherExportInput input) {
    return call(auth, "export", actor -> finance.exportVoucherFile(input, actor, auth));
  }

  // ================================================================ 余额核对

  /** 核对任务列表（真数据）。 */
  @GetMapping("/reconcile-tasks")
  public List<FinanceSoftwareReconcileTaskRow> reconcileTasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID accountId,
      @RequestParam(required = false) String status) {
    return call(auth, "read", actor -> finance.reconcileTasks(accountId, status));
  }

  /** 核对详情：任务 + 导入的外部余额 + 算出来的差异。 */
  @GetMapping("/reconcile-tasks/{id}")
  public FinanceSoftwareReconcileDetail reconcileDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> finance.reconcileDetail(id));
  }

  /** 建一个核对任务。 */
  @PostMapping("/reconcile-tasks")
  public FinanceSoftwareReconcileTaskRow createReconcileTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceSoftwareReconcileTaskInput input) {
    return call(auth, "create", actor -> finance.createReconcileTask(input, actor, auth));
  }

  /** 删除一个**还没算**的核对任务。 */
  @DeleteMapping("/reconcile-tasks/{id}")
  public void deleteReconcileTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "update",
        actor -> {
          finance.deleteReconcileTask(id);
          return null;
        });
  }

  /** 导入外部余额（一批科目；同科目重复导入**覆盖**）。 */
  @PostMapping("/reconcile-tasks/{id}/balances")
  public Map<String, Object> importBalances(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceSoftwareBalanceImportInput input) {
    return call(
        auth,
        "create",
        actor -> Map.<String, Object>of("imported", finance.importBalances(id, input, actor)));
  }

  /**
   * **算**核对差异。
   *
   * <p>本地余额真读上游 12.5.2.2 试算平衡表；外部余额来自导入的快照行；
   * 两者同口径后逐科目相减，差异落库。
   */
  @PostMapping("/reconcile-tasks/{id}/compute")
  public FinanceSoftwareReconcileTaskRow computeReconcile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "reconcile", actor -> finance.computeReconcile(id, actor, auth));
  }

  // ================================================================ 直连推送（未接入）

  /**
   * 直连推送凭证到外部财务软件。
   *
   * <p>🔴 **本仓当前恒 409，且一次数据都不写**：外部财务软件适配器未接入。
   * 适配器交付之后，这个端点立刻变成真的。
   */
  @PostMapping("/push")
  public FinanceSoftwarePushOutcome push(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceSoftwarePushInput input) {
    return call(auth, "push", actor -> finance.push(input, actor, auth));
  }

  // ================================================================ 上游（跨服务只读）

  /** 会计期间清单（来自 12.5.2.1 凭证管理）—— 余额核对要按期间区间取本地余额。 */
  @GetMapping("/periods")
  public List<FinanceSoftwarePeriodOption> periods(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> finance.periods(auth));
  }

  /** 可选币种（来自 12.5.2.1 的真实取值，不是写死的词表）。 */
  @GetMapping("/currencies")
  public List<String> currencies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> finance.currencies(auth));
  }
}
