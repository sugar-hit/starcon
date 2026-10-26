package com.starcon.finance;

import static com.starcon.finance.CostCollectionModels.*;

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
 * 12.4.2.1 费用归集的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/cost-collections}</b>：与同服务的 12.3.1.2（{@code /finance/transactions}）、
 * 12.7.2.1（{@code /finance/fund-analysis}）刻意不共用一个前缀 —— 那是别的模块的命名空间。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code finance.costcollection.read / .create / .update}：
 * 归集规则与费用池是租户自己的账务口径，读权限带不过去。
 */
@RestController
@RequestMapping("/finance/cost-collections")
public class CostCollectionController {

  private final AccessService access;
  private final CostCollectionService collections;

  public CostCollectionController(AccessService access, CostCollectionService collections) {
    this.access = access;
    this.collections = collections;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.costcollection." + permission);
    return access.run(actor, "finance.costcollection." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceCostCollectionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> collections.capabilities());
  }

  // ================================================================ 归集规则

  /** 归集规则列表（租户自己维护，系统不预置任何一行）。 */
  @GetMapping("/rules")
  public List<FinanceCostCollectionRule> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> collections.rules());
  }

  /**
   * 蓝图标准绑定模板（<b>只读</b>，不落库）。
   *
   * <p>模板把蓝图那四类口径摊开给界面，让「按模板建规则」一键可用；
   * 但**系统不预置任何规则行** —— 费用池怎么划分是租户自己的账务口径。
   */
  @GetMapping("/rule-templates")
  public List<FinanceCostCollectionRuleTemplate> ruleTemplates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> collections.ruleTemplates());
  }

  /** 新建归集规则。 */
  @PostMapping("/rules")
  public FinanceCostCollectionRule createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceCostCollectionRuleInput input) {
    return call(auth, "create", actor -> collections.createRule(input));
  }

  /** 修改归集规则（规则下已有条目时不允许改口径）。 */
  @PutMapping("/rules/{id}")
  public FinanceCostCollectionRule updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceCostCollectionRuleInput input) {
    return call(auth, "update", actor -> collections.updateRule(id, input));
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（全部来自真实数据）。 */
  @GetMapping("/options")
  public FinanceCostCollectionOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> collections.options());
  }

  // ================================================================ 费用池

  /** 费用池列表（真分页 + 真筛选 + 按币种分开的汇总）。 */
  @GetMapping("/pools")
  public FinanceCostCollectionPoolPage pools(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String poolKey,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate periodFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate periodTo) {
    return call(
        auth,
        "read",
        actor ->
            collections.pools(
                poolKey, dimension, currency, periodFrom, periodTo));
  }

  /** 一个费用池的详情（池 + 它的归集条目）。 */
  @GetMapping("/pools/{id}")
  public FinanceCostCollectionPoolDetail pool(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> collections.poolDetail(id));
  }

  // ================================================================ 归集明细

  /**
   * 归集明细列表：真分页 + 真筛选 + 真排序 + 汇总覆盖整个筛选结果集。
   *
   * <p>⚠️ 列表里的 {@code targetDimension} 是**规则声明的目标维度**，
   * <b>不是</b>分摊结果 —— 本模块不做分摊。
   */
  @GetMapping("/entries")
  public FinanceCostCollectionEntryPage entries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String poolId,
      @RequestParam(required = false) String ruleId,
      @RequestParam(required = false) String feeSource,
      @RequestParam(required = false) String feeCategory,
      @RequestParam(required = false) String targetDimension,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String poolKey,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate baseDateFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate baseDateTo,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate periodStart,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate periodEnd,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            collections.entries(
                new FinanceCostCollectionQuery(
                    uuidOf(poolId, "费用池"),
                    uuidOf(ruleId, "归集规则"),
                    enumOf(feeSource, FeeSource.class, "费用来源"),
                    enumOf(feeCategory, FeeCategory.class, "费用项"),
                    enumOf(targetDimension, TargetDimension.class, "分摊目标维度"),
                    currency,
                    poolKey,
                    baseDateFrom,
                    baseDateTo,
                    periodStart,
                    periodEnd,
                    keyword,
                    page,
                    size)));
  }

  /** 一条归集条目的详情（条目 + 归集依据）。 */
  @GetMapping("/entries/{id}")
  public FinanceCostCollectionEntry entry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> collections.entry(id));
  }

  /**
   * 撤掉一条归集条目（**调整**用）。
   *
   * <p>蓝图关系句：「先按规则归集费用池，再按维度分摊，<b>便于核对与调整</b>」——
   * 费用进错了池就应当能撤出来重新归集。
   *
   * <p>⚠️ **刻意用 POST 而不是 DELETE**：同服务的代理出口
   * （{@code apps/web/src/app/api/finance/[...path]/route.ts}）**刻意不导出 DELETE** ——
   * 那条出口对全部 finance 路径生效，导出 DELETE 会把「能不能删」的决定权扩散到别的模块
   * （应收单是凭据，只许作废不许删）。⇒ 本模块用 POST 的动作形状
   * （与 12.1.1.3 的 {@code bad-debt/{uuid}/evidence/{uuid}/remove} 同一约定），
   * 路径是本模块自己的，不会误伤其它模块。
   */
  @PostMapping("/entries/{id}/remove")
  public void removeEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "update",
        actor -> {
          collections.deleteEntry(id);
          return null;
        });
  }

  // ================================================================ 归集

  /**
   * 从 12.3.1.2 结算明细行归集平台费（按月）。
   *
   * <p>⚠️ 广告费**不在这一档**：结算单里的 ADVERTISING 行与活动报表是同一笔钱的两种口径，
   * 两个都归集会重复计费。
   */
  @PostMapping("/collect/platform-settlement")
  public FinanceCostCollectionResult collectPlatformSettlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID ruleId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate to) {
    return call(
        auth,
        "create",
        actor -> collections.collectPlatformSettlement(ruleId, from, to));
  }

  /**
   * 从 14.3.1.1 活动报表归集广告费（按月）。
   *
   * <p>⚠️ 广告报表行是活动级汇总、<b>不带单一发生日期</b> ⇒ 基准日必须显式给出
   * （要关账的那个月），不静默默认「今天」。
   */
  @PostMapping("/collect/ad-spend")
  public FinanceCostCollectionResult collectAdSpend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID ruleId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate baseDate) {
    return call(
        auth,
        "create",
        actor -> collections.collectAdSpend(ruleId, baseDate, auth));
  }

  /** 从 10.2.1.1 运单运费登记归集物流费（按单）。 */
  @PostMapping("/collect/logistics")
  public FinanceCostCollectionResult collectLogistics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID ruleId) {
    return call(
        auth,
        "create",
        actor -> collections.collectLogistics(ruleId, auth));
  }

  /**
   * 从 10.4.1.1 头程发运单归集头程费（按 SKU）。
   *
   * <p>⚠️ 归集到**单票**发运单，SKU 级拆分是 12.4.2.2 的分摊职责 —— 本模块越界做它，
   * 两条车道会互相打架。
   */
  @PostMapping("/collect/head-mile")
  public FinanceCostCollectionResult collectHeadMile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID ruleId) {
    return call(
        auth,
        "create",
        actor -> collections.collectHeadMile(ruleId, auth));
  }

  /** 人工录入归集（订阅费 / 人员费用 / 办公费用）。 */
  @PostMapping("/collect/manual")
  public FinanceCostCollectionResult collectManual(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceCostCollectionManualInput input) {
    return call(auth, "create", actor -> collections.collectManual(input));
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
