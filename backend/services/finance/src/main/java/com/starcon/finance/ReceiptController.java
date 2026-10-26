package com.starcon.finance;

import static com.starcon.finance.ReceiptModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
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
 * 12.2.1.1 收款单的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/receipts}</b>：与同服务的 12.1.1.1（{@code /finance/receivables}）
 * 以及卷 12 的其余模块（12.2.1.2 核销 / 12.3.1 结算 / 12.5 总账 …）刻意不共用一个前缀 ——
 * 那些模块还没交付，等它们落地时各自有自己的前缀，挤在同一个前缀下会让「哪个模块的哪个端点」
 * 变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.receipt.read / .create / .update}：登记收款、作废、预留核销
 * 都不是读权限能带过去的动作。
 */
@RestController
@RequestMapping("/finance/receipts")
public class ReceiptController {

  private final AccessService access;
  private final ReceiptService receipts;

  public ReceiptController(AccessService access, ReceiptService receipts) {
    this.access = access;
    this.receipts = receipts;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.receipt." + permission);
    return access.run(actor, "finance.receipt." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceReceiptCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> receipts.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinanceReceiptOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> receipts.options());
  }

  // ================================================================ 列表 / 详情

  /**
   * 收款单列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。
   *
   * <p>⚠️ 按到账时间**倒序**排（资金台账看的是最近到账）。
   */
  @GetMapping
  public FinanceReceiptPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String sourceSystem,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String payerType,
      @RequestParam(required = false) Boolean advanceOnly,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate receivedFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate receivedTo,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            receipts.page(
                new ReceiptQuery(
                    enumOf(status, ReceiptStatus.class, "收款状态"),
                    enumOf(source, ReceiptSource.class, "收款来源"),
                    sourceSystem,
                    currency,
                    enumOf(payerType, PayerType.class, "付款方类型"),
                    advanceOnly,
                    receivedFrom,
                    receivedTo,
                    keyword,
                    page,
                    size)));
  }

  /** 收款单详情：单头 + 预留核销关系 + 事件流。 */
  @GetMapping("/{id}")
  public FinanceReceiptDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> receipts.detail(id));
  }

  // ================================================================ 登记

  /**
   * 手工登记一张收款单。
   *
   * <p>⚠️ 「平台回款」当前**不可产生**（上游 12.3.1.1 结算未交付），服务端会拒绝并写明归属模块。
   */
  @PostMapping
  public FinanceReceiptRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceReceiptInput input) {
    return call(auth, "create", actor -> receipts.create(input, actor));
  }

  /**
   * 到账流水导入（CSV / JSON）：解析 → 真入库 + 幂等。
   *
   * <p>⚠️ 文件级错误（CSV 缺列 / JSON 不是一个数组）**整体失败**（400），一条都不导入；
   * 行级错误只跳过那一行并逐条写进返回的 warnings。
   */
  @PostMapping("/import")
  public FinanceReceiptImportResult importFlow(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody FinanceReceiptImportInput input) {
    return call(auth, "create", actor -> receipts.importFlow(input, actor));
  }

  // ================================================================ 预留核销（合并回款）

  /**
   * 登记一笔预留核销：这笔钱**打算**核销到某一笔应收上。
   *
   * <p>⚠️ <b>预留不是核销</b>：它不减少未核销余额；实际核销由 12.2.1.2 承担。
   */
  @PostMapping("/{id}/allocations")
  public FinanceReceiptAllocationRow reserveAllocation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinanceReceiptAllocationInput input) {
    return call(auth, "update", actor -> receipts.reserveAllocation(id, input, actor));
  }

  /** 释放一笔预留核销（**不是**核销撤销 —— 核销撤销属 12.2.1.2）。 */
  @PostMapping("/{id}/allocations/{allocationId}/release")
  public FinanceReceiptAllocationRow releaseAllocation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID allocationId) {
    return call(auth, "update", actor -> receipts.releaseAllocation(id, allocationId, actor));
  }

  // ================================================================ 作废

  /** 作废（红冲的等价物）：终态，整单冻结。理由必填。 */
  @PostMapping("/{id}/void")
  public FinanceReceiptRow voidReceipt(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinanceReceiptVoidInput input) {
    return call(auth, "update", actor -> receipts.voidReceipt(id, input, actor));
  }

  // ================================================================ 未核销余额

  /**
   * 未核销余额（**按币种分开**）。
   *
   * <p>⚠️ 核销（12.2.1.2）未交付 ⇒ 未核销余额恒等于收款金额。
   */
  @GetMapping("/balance")
  public FinanceReceiptBalance balance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> receipts.balance());
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
}
