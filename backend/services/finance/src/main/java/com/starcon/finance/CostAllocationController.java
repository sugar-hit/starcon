package com.starcon.finance;

import static com.starcon.finance.CostAllocationModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 12.4.2.2 分摊规则的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/cost-allocations}</b>：与 12.4.2.1（{@code /finance/cost-collections}）
 * 刻意不共用一个前缀 —— 那是别的模块的命名空间，而本模块与它的边界正是「归集 ≠ 分摊」。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.costallocation.read / .create / .update}：
 * 分摊规则与分摊结果是租户自己的账务口径，读权限带不过去。
 */
@RestController
@RequestMapping("/finance/cost-allocations")
public class CostAllocationController {

  private final AccessService access;
  private final CostAllocationService allocations;

  public CostAllocationController(AccessService access, CostAllocationService allocations) {
    this.access = access;
    this.allocations = allocations;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.costallocation." + permission);
    return access.run(actor, "finance.costallocation." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceCostAllocationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> allocations.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinanceCostAllocationOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> allocations.options());
  }

  // ================================================================ 分摊规则

  /** 分摊规则列表（租户自己维护，系统不预置任何一行）。 */
  @GetMapping("/rules")
  public List<FinanceCostAllocationRule> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> allocations.rules());
  }

  /** 新建分摊规则。 */
  @PostMapping("/rules")
  public FinanceCostAllocationRule createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceCostAllocationRuleInput input) {
    return call(auth, "create", actor -> allocations.createRule(input));
  }

  /** 修改分摊规则（已经产生过分摊批次的规则不允许改口径）。 */
  @PutMapping("/rules/{id}")
  public FinanceCostAllocationRule updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceCostAllocationRuleInput input) {
    return call(auth, "update", actor -> allocations.updateRule(id, input));
  }

  // ================================================================ 待分摊费用池

  /** 待分摊的费用池列表（真分页 + 真筛选）。 */
  @GetMapping("/pools")
  public FinanceCostAllocationPoolPage pools(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String poolKey,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate periodFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate periodTo,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor -> allocations.pools(poolKey, currency, periodFrom, periodTo, page, size));
  }

  // ================================================================ 分摊对象

  /** 分摊对象列表（真分页 + 真筛选）。 */
  @GetMapping("/targets")
  public FinanceCostAllocationTargetPage targets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID poolId,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor -> allocations.targets(poolId, dimension, keyword, page, size));
  }

  /**
   * 采集分摊对象（从**真实上游**拉：trade 平台订单 / 销售订单行、catalog SKU 主数据与包装档案）。
   *
   * <p>⚠️ 上游读不到就**显式失败**（503），绝不返回空集合冒充「上游没有订单」——
   * 那会让现场以为这个期间没卖过东西。
   */
  @PostMapping("/targets/collect")
  public FinanceCostAllocationTargetCollectResult collectTargets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID poolId,
      @RequestParam String dimension) {
    var dim = enumOf(dimension, AllocationDimension.class, "分摊维度");
    return call(
        auth, "create", actor -> allocations.collectTargets(poolId, dim, auth));
  }

  // ================================================================ 执行分摊

  /**
   * 执行分摊：把一个费用池按规则摊到它的分摊对象上。
   *
   * <p>🔴 保证：<b>Σ分摊结果 = 被分摊总额</b>（含舍入差处理）。
   */
  @PostMapping("/allocate")
  public FinanceCostAllocationBatchDetail allocate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceCostAllocationRequest request) {
    return call(auth, "create", actor -> allocations.allocate(request, auth));
  }

  // ================================================================ 分摊批次

  /** 分摊批次列表（真分页 + 真筛选 + 按币种分开的汇总）。 */
  @GetMapping("/batches")
  public FinanceCostAllocationBatchPage batches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID poolId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String currency,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth, "read", actor -> allocations.batches(poolId, status, currency, page, size));
  }

  /** 一个批次的详情（批次 + 分摊明细）。 */
  @GetMapping("/batches/{id}")
  public FinanceCostAllocationBatchDetail batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> allocations.batchDetail(id));
  }

  /**
   * 撤销一次分摊（池回到未分摊状态，可以按新规则重摊）。
   *
   * <p>⚠️ **刻意用 POST 而不是 DELETE**：同服务的代理出口
   * （{@code apps/web/src/app/api/finance/[...path]/route.ts}）**刻意不导出 DELETE** ——
   * 那条出口对全部 finance 路径生效，导出 DELETE 会把「能不能删」的决定权扩散到别的模块。
   * ⇒ 本模块用 POST 的动作形状（与 12.4.2.1 的 {@code entries/{uuid}/remove} 同一约定）。
   */
  @PostMapping("/batches/{id}/revert")
  public FinanceCostAllocationBatchDetail revert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> allocations.revert(id));
  }

  // ================================================================ 分摊结果（明细）

  /** 分摊结果列表（真分页 + 真筛选 + 汇总覆盖整个筛选结果集）。 */
  @GetMapping("/results")
  public FinanceCostAllocationResultPage results(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID poolId,
      @RequestParam(required = false) UUID batchId,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) Boolean basisAvailable,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor -> allocations.results(poolId, batchId, dimension, basisAvailable, page, size));
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
