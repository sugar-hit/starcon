package com.starcon.finance;

import static com.starcon.finance.TransactionModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 12.3.1.2 交易明细的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/transactions}</b>：与同服务的 12.1.1.1（{@code /finance/receivables}）、
 * 12.2.1.1（{@code /finance/receipts}）、12.3.1.1（{@code /finance/settlement}）刻意不共用一个前缀。
 *
 * <p>⚠️ 首 token 是 {@code transactions} 而不是 {@code settlement-transactions}：
 * 12.3.1.1 已占了 {@code settlement}，两个首 token 不同的命名空间才不会互相吃掉对方的路径。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.transaction.read / .create}：登记明细与导入交易报告都不是读权限能带过去的动作。
 */
@RestController
@RequestMapping("/finance/transactions")
public class TransactionController {

  private final AccessService access;
  private final TransactionService transactions;

  public TransactionController(AccessService access, TransactionService transactions) {
    this.access = access;
    this.transactions = transactions;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.transaction." + permission);
    return access.run(actor, "finance.transaction." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceTransactionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> transactions.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinanceTransactionOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> transactions.options());
  }

  // ================================================================ 列表 / 详情

  /**
   * 交易明细列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。
   */
  @GetMapping
  public FinanceTransactionPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String settlementId,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false) String transactionType,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate occurredFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate occurredTo,
      @RequestParam(required = false) Boolean hasOrder,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            transactions.page(
                new FinanceTransactionQuery(
                    uuidOf(settlementId, "结算单"),
                    uuidOf(shopId, "店铺"),
                    enumOf(transactionType, FinanceTransactionType.class, "交易类型"),
                    enumOf(category, SettlementModels.SettlementCategory.class, "结算类别"),
                    currency,
                    enumOf(source, FinanceTransactionSource.class, "明细来源"),
                    platformCode,
                    occurredFrom,
                    occurredTo,
                    hasOrder,
                    keyword,
                    page,
                    size)));
  }

  /** 一条明细的详情：明细本身 + 所属结算单的当前状态与汇总项。 */
  @GetMapping("/{id}")
  public FinanceTransactionDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> transactions.detail(id));
  }

  /**
   * 汇总校验：<b>明细合计 = 结算单汇总</b>。
   *
   * <p>⚠️ 这是**实时算出来**的核对，不是存下来的快照；它只核对，不匹配、不分类差异
   * （自动对账任务属 12.3.2.1，**未交付**）。
   */
  @GetMapping("/reconciliation")
  public FinanceTransactionReconciliation reconciliation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String settlementId) {
    UUID id = uuidOf(settlementId, "结算单");
    if (id == null) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "必须指定结算单");
    }
    return call(auth, "read", actor -> transactions.reconciliation(id));
  }

  // ================================================================ 登记 / 导入

  /**
   * 登记一条交易明细（追加到一张在册结算单下）。
   *
   * <p>⚠️ 「平台交易报告同步」当前**不可产生**（上游「卷 02 同步」未交付该端点），
   * 服务端会拒绝并写明归属模块。
   */
  @PostMapping
  public FinanceTransactionRow register(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceTransactionInput input) {
    return call(auth, "create", actor -> transactions.register(input, actor));
  }

  /** 批量登记交易明细（同一张结算单）。每一行都单独验，不放松任何一条校验。 */
  @PostMapping("/batch")
  public List<FinanceTransactionRow> registerBatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceTransactionBatchInput input) {
    return call(auth, "create", actor -> transactions.registerBatch(input, actor));
  }

  /**
   * 平台交易报告导入（CSV / JSON）：解析 → 真入库 + 原文与解析版本留痕。
   *
   * <p>⚠️ 文件级错误（CSV 缺列 / JSON 不是一个数组）**整体失败**（400），一条都不导入；
   * 行级错误只跳过那一行并逐条写进返回的 warnings。
   */
  @PostMapping("/import")
  public FinanceTransactionImportResult importReport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody FinanceTransactionImportInput input) {
    return call(auth, "create", actor -> transactions.importReport(input, actor));
  }

  // ================================================================ 工具

  /** 词表解析：非法取值给一句能直接展示的话，而不是一个枚举名。 */
  private static <T extends Enum<T>> T enumOf(String raw, Class<T> type, String label) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Enum.valueOf(type, raw.trim());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, label + "取值不合法：" + raw);
    }
  }

  /** UUID 解析：非法取值给一句能直接展示的话。 */
  private static UUID uuidOf(String raw, String label) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, label + "取值不合法：" + raw);
    }
  }
}
