package com.starcon.finance;

import static com.starcon.finance.SettlementModels.*;

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
 * 12.3.1.1 结算单管理的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/settlement}</b>：与同服务的 12.1.1.1（{@code /finance/receivables}）、
 * 12.2.1.1（{@code /finance/receipts}）以及卷 12 的其余模块刻意不共用一个前缀 ——
 * 那些模块还没交付，等它们落地时各自有自己的前缀，挤在同一个前缀下会让「哪个模块的哪个端点」
 * 变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.settlement.read / .create / .update}：登记结算单、导入账单、
 * 登记到账、作废都不是读权限能带过去的动作。
 */
@RestController
@RequestMapping("/finance/settlement")
public class SettlementController {

  private final AccessService access;
  private final SettlementService settlements;

  public SettlementController(AccessService access, SettlementService settlements) {
    this.access = access;
    this.settlements = settlements;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.settlement." + permission);
    return access.run(actor, "finance.settlement." + permission, () -> work.apply(actor));
  }

  /** 需要把调用方自己的会话转发给上游（channel）时用。 */
  private <T> T callWithBearer(
      String bearer, String permission, BiFunction2<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, "finance.settlement." + permission);
    return access.run(
        actor, "finance.settlement." + permission, () -> work.apply(actor, bearer));
  }

  /** 两个入参的函数（避免在这里引 java.util.function.BiFunction 的类型噪声）。 */
  @FunctionalInterface
  interface BiFunction2<A, B, T> {
    T apply(A first, B second);
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceSettlementCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> settlements.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinanceSettlementOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> settlements.options());
  }

  /**
   * 店铺选项（来自上游 2 渠道与店铺 2.3.1.1）。
   *
   * <p>⚠️ 跨服务**只走授权 HTTP API**：转发的是调用方自己的会话，
   * channel 侧仍按已认证成员关系解析租户并强制 RLS。
   */
  @GetMapping("/shops")
  public List<FinanceSettlementShopOption> shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return callWithBearer(auth, "read", (actor, bearer) -> settlements.shopOptions(bearer));
  }

  // ================================================================ 结算周期配置

  /** 结算周期配置列表（一家店一份）。 */
  @GetMapping("/cycles")
  public List<FinanceSettlementCycleRow> cycles(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> settlements.cycles());
  }

  /** 登记 / 改写一家店的结算周期配置（按店铺 upsert）。 */
  @PostMapping("/cycles")
  public FinanceSettlementCycleRow upsertCycle(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody FinanceSettlementCycleInput input) {
    return callWithBearer(
        auth, "update", (actor, bearer) -> settlements.upsertCycle(input, actor, bearer));
  }

  // ================================================================ 列表 / 详情

  /**
   * 结算单列表：真分页 + 真筛选 + 真排序 + 汇总（汇总覆盖整个筛选结果集，不是当前页）。
   *
   * <p>⚠️ 按结算周期结束日**倒序**排（最近一期在上面）。
   */
  @GetMapping
  public FinanceSettlementPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate periodFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate periodTo,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            settlements.page(
                new SettlementQuery(
                    enumOf(status, SettlementStatus.class, "结算状态"),
                    uuidOf(shopId, "店铺"),
                    currency,
                    enumOf(source, SettlementSource.class, "结算来源"),
                    platformCode,
                    periodFrom,
                    periodTo,
                    keyword,
                    page,
                    size)));
  }

  /** 结算单详情：单头 + 汇总项 + 明细行 + 事件流 + 原始文件。 */
  @GetMapping("/{id}")
  public FinanceSettlementDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> settlements.detail(id));
  }

  /**
   * 「与我方账的差额」：平台侧金额 vs 我方应收（同店铺、同周期、同币种）。
   *
   * <p>⚠️ **自动对账任务属 12.3.2.1，未交付** —— 这里只算差额，不做匹配。
   */
  @GetMapping("/{id}/difference")
  public FinanceSettlementDifference difference(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> settlements.difference(id));
  }

  // ================================================================ 登记 / 导入

  /**
   * 手工登记一张结算单（连明细行一起）。
   *
   * <p>⚠️ 「平台结算报告同步」当前**不可产生**（上游「卷 02 同步」未交付该端点），
   * 服务端会拒绝并写明归属模块。
   */
  @PostMapping
  public FinanceSettlementRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceSettlementInput input) {
    return callWithBearer(
        auth, "create", (actor, bearer) -> settlements.create(input, actor, bearer));
  }

  /**
   * 平台账单导入（CSV / JSON）：解析 → 真入库 + 原始文件与解析版本留痕。
   *
   * <p>⚠️ 文件级错误（CSV 缺列 / JSON 不是一个数组）**整体失败**（400），一条都不导入；
   * 行级错误只跳过那一行并逐条写进返回的 warnings。
   */
  @PostMapping("/import")
  public FinanceSettlementImportResult importBill(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody FinanceSettlementImportInput input) {
    return callWithBearer(
        auth, "create", (actor, bearer) -> settlements.importBill(input, actor, bearer));
  }

  // ================================================================ 到账 / 作废

  /** 登记到账：写实际到账时间与到账金额，并推进到账状态。 */
  @PostMapping("/{id}/arrival")
  public FinanceSettlementRow recordArrival(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinanceSettlementArrivalInput input) {
    return call(auth, "update", actor -> settlements.recordArrival(id, input, actor));
  }

  /** 作废（红冲的等价物）：终态，整单冻结。理由必填。 */
  @PostMapping("/{id}/void")
  public FinanceSettlementRow voidSettlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody FinanceSettlementVoidInput input) {
    return call(auth, "update", actor -> settlements.voidSettlement(id, input, actor));
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
