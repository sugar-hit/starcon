package com.starcon.finance;

import static com.starcon.finance.DeferredSettlementModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
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
 * 12.3.1.3 延迟结算的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/deferred-settlement}</b>：与同服务的 12.3.1.1（{@code /finance/settlement}）、
 * 12.3.1.2（{@code /finance/transactions}）刻意不共用一个前缀 —— 首 token 也刻意不同
 * （{@code deferred-settlement} ≠ {@code settlement} ≠ {@code transactions}），
 * 免得「延迟结算」被「结算单」的前缀吃掉。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.deferred_settlement.read / .create / .update}：
 * 扫描入台账、登记结算规则、作废都不是读权限能带过去的动作。
 */
@RestController
@RequestMapping("/finance/deferred-settlement")
public class DeferredSettlementController {

  private final AccessService access;
  private final DeferredSettlementService deferredSettlements;

  public DeferredSettlementController(AccessService access, DeferredSettlementService deferredSettlements) {
    this.access = access;
    this.deferredSettlements = deferredSettlements;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.deferred_settlement." + permission);
    return access.run(actor, "finance.deferred_settlement." + permission, () -> work.apply(actor));
  }

  /** 需要把调用方自己的会话转发给上游（trade）时用。 */
  private <T> T callWithBearer(
      String bearer, String permission, BiFunction2<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, "finance.deferred_settlement." + permission);
    return access.run(
        actor, "finance.deferred_settlement." + permission, () -> work.apply(actor, bearer));
  }

  /** 两个入参的函数（避免在这里引 java.util.function.BiFunction 的类型噪声）。 */
  @FunctionalInterface
  interface BiFunction2<A, B, T> {
    T apply(A first, B second);
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceDeferredSettlementCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> deferredSettlements.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinanceDeferredSettlementOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> deferredSettlements.options());
  }

  // ================================================================ 结算规则

  /** 结算规则清单（店铺级规则 + 平台级默认规则）。 */
  @GetMapping("/rules")
  public List<FinanceDeferredSettlementRuleRow> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> deferredSettlements.rules());
  }

  /**
   * 登记 / 改写一条结算规则（按「平台 + 店铺」upsert）。
   *
   * <p>⚠️ 预计到账时间由 **DB 触发器**从「发货时间 + 结算模式 + 周期 + 到账偏移」派生，
   * 服务层写不进去 —— 规则改了只影响**之后**扫进来的行，历史行保留当时的规则快照。
   */
  @PostMapping("/rules")
  public FinanceDeferredSettlementRuleRow upsertRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody FinanceDeferredSettlementRuleInput input) {
    return call(auth, "update", actor -> deferredSettlements.upsertRule(input, actor));
  }

  // ================================================================ 列表 / 详情 / 统计

  /**
   * 延迟结算明细列表：真分页 + 真筛选 + 真排序 + 两套口径汇总。
   *
   * <p>⚠️ 汇总覆盖**整个筛选结果集**，不是当前页。
   */
  @GetMapping
  public FinanceDeferredSettlementPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime shippedFrom,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime shippedTo,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            deferredSettlements.page(
                new DeferredSettlementQuery(
                    enumOf(status, DeferredSettlementStatus.class, "延迟结算状态"),
                    platformCode,
                    shopCode,
                    currency,
                    shippedFrom,
                    shippedTo,
                    overdueOnly,
                    keyword,
                    page,
                    size)));
  }

  /**
   * 在途资金统计（蓝图输出之一：「在途资金统计 + 预计到账时间」）。
   *
   * <p>⚠️ **两套口径同时给出**（订单口径 / 已结算口径），**分币种**，随响应返回口径定义。
   */
  @GetMapping("/summary")
  public FinanceDeferredSettlementSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime shippedFrom,
      @RequestParam(required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime shippedTo,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) String keyword) {
    return call(
        auth,
        "read",
        actor ->
            deferredSettlements.summary(
                new DeferredSettlementQuery(
                    enumOf(status, DeferredSettlementStatus.class, "延迟结算状态"),
                    platformCode,
                    shopCode,
                    currency,
                    shippedFrom,
                    shippedTo,
                    overdueOnly,
                    keyword,
                    0,
                    1)));
  }

  /** 台账明细：单头 + 事件流。 */
  @GetMapping("/{id}")
  public FinanceDeferredSettlementDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> deferredSettlements.detail(id));
  }

  // ================================================================ 扫描 / 作废

  /**
   * 从 trade 扫描「已发货但钱未到账」的订单进延迟结算台账。
   *
   * <p>⚠️ **上游读不到一律 503** 并写明「读不到」—— 绝不返回空集合冒充「平台没有订单」。
   */
  @PostMapping("/scan")
  public FinanceDeferredSettlementScanResult scan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return callWithBearer(
        auth, "create", (actor, bearer) -> deferredSettlements.scan(bearer, actor));
  }

  /** 作废（红冲的等价物）：终态，不进任何口径。理由必填。 */
  @PostMapping("/{id}/void")
  public FinanceDeferredSettlementRow voidDeferredSettlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceDeferredSettlementVoidInput input) {
    return call(auth, "update", actor -> deferredSettlements.voidRow(id, input, actor));
  }

  // ================================================================ 工具

  /** 词表解析：非法取值给一句能直接展示的话，而不是一个枚举名。 */
  private static <T extends Enum<T>> T enumOf(String raw, Class<T> type, String label) {
    if (raw == null || raw.isBlank()) return null;
    try {
      return Enum.valueOf(type, raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, label + "取值不合法：" + raw);
    }
  }
}
