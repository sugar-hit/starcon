package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryBucketModels.*;
import static com.starcon.warehouse.InventoryLedgerModels.*;
import static com.starcon.warehouse.InventoryReconciliationModels.*;
import static com.starcon.warehouse.SellablePolicyModels.*;
import static com.starcon.warehouse.StocktakeModels.*;
import static com.starcon.warehouse.WarehouseModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 7.1.1.1 库存总览。
 *
 * <p>命名空间 {@code /warehouse}，与 catalog 的 {@code /catalog}、channel 的
 * {@code /channel} 同一套约定：每个服务暴露自己的前缀，浏览器只经 /api/warehouse 代理访问。
 *
 * <p>权限码前缀 {@code warehouse.}：总览读用 {@code warehouse.inventory.read}，
 * 阈值维护用 {@code warehouse.inventory.update}（写入超储线是经营决策，不是读权限）。
 */
@RestController
@RequestMapping("/warehouse")
public class InventoryController {
  private final AccessService access;
  private final InventoryOverviewService overview;
  private final InventoryBucketService buckets;
  private final WarehouseReferenceService references;
  private final InventoryAvailabilityService availability;
  private final InventoryLedgerService ledger;
  private final InventoryReconciliationService reconciliation;
  private final SellablePolicyService sellablePolicies;
  private final StocktakeService stocktakes;

  public InventoryController(
      AccessService access,
      InventoryOverviewService overview,
      InventoryBucketService buckets,
      WarehouseReferenceService references,
      InventoryAvailabilityService availability,
      InventoryLedgerService ledger,
      InventoryReconciliationService reconciliation,
      SellablePolicyService sellablePolicies,
      StocktakeService stocktakes) {
    this.access = access;
    this.overview = overview;
    this.buckets = buckets;
    this.references = references;
    this.availability = availability;
    this.ledger = ledger;
    this.reconciliation = reconciliation;
    this.sellablePolicies = sellablePolicies;
    this.stocktakes = stocktakes;
  }

  /**
   * 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），
   * 再在一个事务里绑定 RLS 租户上下文执行查询。
   */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 库存总览：汇总表 + 分组统计 + 合计。
   *
   * <p>纯聚合视图，不存独立数据 —— 响应里的每个数字都能在同一份余额数据上核对出来。
   */
  @GetMapping("/inventory/overview")
  public InventoryOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) List<UUID> categoryIds,
      @RequestParam(required = false) List<UUID> brandIds,
      @RequestParam(required = false) List<UUID> shopIds,
      @RequestParam(required = false) Boolean zeroStock,
      @RequestParam(required = false) Boolean overstock,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            overview.overview(
                new OverviewQuery(
                    warehouseId,
                    legalEntityId,
                    skuId,
                    keyword,
                    categoryIds,
                    brandIds,
                    shopIds,
                    zeroStock,
                    overstock,
                    sort,
                    page,
                    size)));
  }

  /**
   * 7.1.1.2 库存明细：bucket 级的最细粒度行 + 与余额的核对结果。
   *
   * <p>一行 = 法人货权 × SKU × 仓库 × 库位 × 批次 × 质量状态，正是蓝图定义的最小事实单位。
   * 读权限与总览同为 {@code warehouse.inventory.read}：明细不写任何数据，与总览同源同权。
   */
  @GetMapping("/inventory/buckets")
  public InventoryBucketDetail buckets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) UUID locationId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID lotId,
      @RequestParam(required = false) UUID qualityStatusId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean onlyPickable,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            buckets.detail(
                new BucketDetailQuery(
                    legalEntityId,
                    warehouseId,
                    locationId,
                    skuId,
                    lotId,
                    qualityStatusId,
                    keyword,
                    onlyPickable,
                    sort,
                    page,
                    size)));
  }

  /** 行内操作入口（发起调整 / 发起调拨 / 查看流水）。目标模块未交付时 available=false。 */
  @GetMapping("/inventory/bucket-actions")
  public List<BucketAction> bucketActions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> buckets.actions());
  }

  /**
   * 7.1.2.1 库存流水台账：流水列表 + 期间净变动汇总 + 分录下钻入口。
   *
   * <p>**只读端点**：蓝图要求「流水只追加不修改，错误用反向分录纠正」，所以本模块没有任何
   * POST/PUT/DELETE 落在流水与分录上（那两张表在 V1 里已被 append-only 触发器与
   * REVOKE UPDATE,DELETE 双重锁死）。
   *
   * <p>读权限沿用 {@code warehouse.inventory.read}：台账与总览/明细同源同权，
   * 台账比明细更敏感（能看出谁在什么时候动了多少货），但**没有额外的读权限** ——
   * 多设一个权限码只会让"能看到库存却看不到流水"变成一个没人记得开的开关。
   *
   * <p>时间参数按 ISO-8601 带偏移量解析（{@code 2026-10-01T00:00:00+08:00}），
   * 与 {@code timestamptz} 列的存储口径一致；格式不对直接 400，不按服务器本地时区猜。
   */
  @GetMapping("/inventory/ledger")
  public InventoryLedgerPage ledger(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) List<String> kinds,
      @RequestParam(required = false) List<String> refTypes,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            ledger.page(
                new InventoryLedgerQuery(
                    moment(from),
                    moment(to),
                    skuId,
                    warehouseId,
                    kinds,
                    refTypes,
                    keyword,
                    sort,
                    page,
                    size,
                    a.userId(),
                    selfOnly(a, "warehouse.inventory.read"))));
  }

  /**
   * 分录下钻：一笔业务的全部带符号分录。
   *
   * <p>跨租户访问在 RLS 下表现为「查不到」，返回 404 而不是 403 —— 403 会泄露「这笔业务存在」。
   */
  @GetMapping("/inventory/ledger/{transactionId}")
  public InventoryLedgerDrill ledgerDrill(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID transactionId) {
    return call(auth, "inventory.read", a -> ledger.drill(transactionId));
  }

  /** 来源单据类型下拉。租户级词表，不随时间与其它筛选变化。 */
  @GetMapping("/inventory/ledger/ref-types")
  public List<InventoryRefType> ledgerRefTypes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> ledger.refTypes());
  }

  // ------------------------------------------------------------ 7.1.2.2 账实核对

  /**
   * 执行一次账实核对。
   *
   * <p>用 POST 而不是 GET：核对会**落一份不可变的结论**（run + findings），是写操作而不是查询。
   * 这也决定了它的权限码是 {@code warehouse.inventory.update} 而不是 read ——
   * 权限码要如实反映动作，哪怕这次动作并不改动任何库存数量。把「写审计记录」挂在 read 上，
   * 就等于声明 read 权限包含写操作，那道闸门等于没有。
   *
   * <p>幂等性刻意**不**做：同一区间核两次会得到两条记录。核对结论是时点事实，
   * 「昨天核过什么」和「刚才核过什么」是两条不同的记录才对，去重反而会让人以为看到了旧的。
   *
   * <p>时间参数按 ISO-8601 带偏移量解析，与台账端点同一套规则。
   */
  @PostMapping("/inventory/reconciliation")
  public ReconciliationResult runReconciliation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ReconciliationRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> reconciliation.run(request == null ? new ReconciliationRequest(null, null, null, null, null) : request, a.userId()));
  }

  /** 核对批次列表（真分页）。status 可选：CONSISTENT / DISCREPANT。 */
  @GetMapping("/inventory/reconciliation")
  public ReconciliationRunPage reconciliationRuns(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "inventory.read", a -> reconciliation.runs(page, size, status));
  }

  /** 某次核对的结论 + 可疑区间排名。跨租户在 RLS 下查不到 → 404，不泄露存在性。 */
  @GetMapping("/inventory/reconciliation/{runId}")
  public ReconciliationResult reconciliationRun(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID runId) {
    return call(
        auth,
        "inventory.read",
        a ->
            new ReconciliationResult(
                reconciliation.runOf(runId), List.of(), List.of(), reconciliation.suspectWindows(runId)));
  }

  /**
   * 某次核对的发现分页。
   *
   * <p>{@code kind} 决定翻的是差异明细（DISCREPANCY）还是可疑流水（SUSPECT，不传即 SUSPECT）。
   * 分页是必要的：一次大范围核对可能定位出成百上千条线索，界面必须能翻。
   */
  @GetMapping("/inventory/reconciliation/{runId}/findings")
  public ReconciliationFindingPage reconciliationFindings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID runId,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "inventory.read", a -> reconciliation.findings(runId, kind, page, size));
  }

  /**
   * 解析 ISO-8601 时间（必须带偏移量或 Z）。
   *
   * <p>不接受不带偏移的裸时间串：那样"2026-10-01T00:00:00"会被按运行环境的本地时区猜，
   * 同一份请求在开发机与 K8s 里会筛出不同区间，台账的期间汇总就没法复现了。
   */
  private static OffsetDateTime moment(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value.trim());
    } catch (java.time.format.DateTimeParseException e) {
      throw new IllegalArgumentException(
          "时间格式需为带时区的 ISO-8601，例如 2026-10-01T00:00:00+08:00：" + value);
    }
  }

  /**
   * 数据范围是否为「仅本人」。
   *
   * <p>蓝图把「用户数据范围」列为台账的输入之一，所以它必须真的生效，而不是只挂在页面上。
   * 取值规则与 {@code AccessService.require} 同一套授权：命中的授权里只要有一条 ALL 就按 ALL，
   * 有一条 DEPARTMENT 就按 DEPARTMENT（库存没有部门维度，DEPARTMENT 退化为本租户全量，
   * 这个退化由 {@code dataScopeNote} 在响应里如实说明），否则按 SELF 收窄到本人操作过的业务。
   *
   * <p>注意 actorId 与 selfOnly 都来自**已认证身份**，不接受任何请求参数传入 ——
   * 否则"只看我自己的流水"会变成一个能越权的开关。
   */
  private static boolean selfOnly(AccessService.Access a, String permission) {
    var read = permission.substring(0, permission.lastIndexOf('.')) + ".read";
    List<AccessService.Grant> grants = a.grants() == null ? List.of() : a.grants();
    String scope =
        grants.stream()
            .filter(g -> g.permissions().contains(permission) && g.permissions().contains(read))
            .map(AccessService.Grant::scopeMode)
            .min(
                java.util.Comparator.comparingInt(
                    (String s) ->
                        switch (s) {
                          case "ALL" -> 0;
                          case "DEPARTMENT" -> 1;
                          default -> 2;
                        }))
            .orElse("ALL");
    return "SELF".equals(scope);
  }

  /**
   * 批量可用量查询（3.5.2.2 BOM 展开消费）。
   *
   * <p>用 POST 而不是 GET：请求体是一组 SKU，展开树最坏 500 个 UUID，
   * 放 URL 里会撞长度限制，也会把一份清单塞进访问日志。
   *
   * <p>租户不来自请求体也不来自任何客户端头：{@link #call} 先向 identity 确认
   * 已认证成员关系与权限，再在一个事务里绑定 {@code app.current_tenant}，
   * 由 RLS 在数据库层强制隔离。catalog 侧必须透传调用者的 Authorization 头 ——
   * 丢了它这里就无从解析租户（这是跨服务调用丢掉租户上下文的直接后果）。
   */
  @PostMapping("/inventory/availability")
  public AvailabilityView availability(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AvailabilityQuery query) {
    return call(auth, "inventory.read", a -> availability.availability(query));
  }

  /** 跨服务筛选维度接入情况：前端据此禁用不可用的筛选项，而不是让用户填完收 422。 */
  @GetMapping("/inventory/capabilities")
  public List<DimensionCapability> capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> overview.capabilities());
  }

  // ------------------------------------------------------------ 参照数据

  @GetMapping("/warehouses")
  public List<WarehouseRef> warehouses(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> references.warehouses());
  }

  @GetMapping("/legal-entities")
  public List<LegalEntityRef> legalEntities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> references.legalEntities());
  }

  @GetMapping("/skus")
  public List<SkuRef> skus(@RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> references.skus());
  }

  /**
   * 库位下拉（7.1.1.2 的逐级下钻第二级）。
   *
   * <p>按仓库过滤，让"先选仓、再选库位"这条下钻路径在 UI 上成立，而不是全租户库位混在一起。
   */
  @GetMapping("/locations")
  public List<LocationRef> locations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String type) {
    return call(auth, "inventory.read", a -> buckets.locations(warehouseId, type));
  }

  /** 批次下拉（7.1.1.2 的逐级下钻最末一级）。 */
  @GetMapping("/lots")
  public List<LotRef> lots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String keyword) {
    return call(auth, "inventory.read", a -> buckets.lots(skuId, keyword));
  }

  @GetMapping("/quality-statuses")
  public List<QualityStatusRef> qualityStatuses(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> references.qualityStatuses());
  }

  // ------------------------------------------------------------ 超储阈值

  @GetMapping("/inventory/policies")
  public List<InventoryPolicy> policies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType) {
    return call(auth, "inventory.read", a -> references.policies(scopeType));
  }

  @PostMapping("/inventory/policies")
  public InventoryPolicySaved createPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody InventoryPolicyInput input) {
    return call(auth, "inventory.update", a -> references.savePolicy(null, input));
  }

  @PutMapping("/inventory/policies/{id}")
  public InventoryPolicySaved updatePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody InventoryPolicyInput input) {
    return call(auth, "inventory.update", a -> references.savePolicy(id, input));
  }

  @DeleteMapping("/inventory/policies/{id}")
  public void deletePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "inventory.update",
        a -> {
          references.deletePolicy(id);
          return null;
        });
  }

  // ------------------------------------------------------------ 7.2.2.1 可售量口径

  /**
   * 7.2.2.1 可售量口径：口径分档列表（真分页）。
   *
   * <p>{@code effectiveOnly=true} 只看此刻仍在生效的档；服务端用 {@code now()} 判定而不是
   * 接受调用方传时刻 —— 「看未来某一刻谁生效」在这里没有业务含义，却会变成一个能试错的入口。
   *
   * <p>读权限沿用 {@code warehouse.inventory.read}：读口径不改任何数，
   * 与总览/明细同源同权。
   */
  @GetMapping("/inventory/sellable-policies")
  public SellablePolicyPage sellablePolicies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) UUID referenceId,
      @RequestParam(required = false) String poolMode,
      @RequestParam(required = false, defaultValue = "false") boolean effectiveOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            sellablePolicies.page(
                new SellablePolicyQuery(
                    scopeType, referenceId, poolMode, effectiveOnly, keyword, page, size)));
  }

  /**
   * 解析某一时刻真正生效的口径（分档生效语义，与 17.5.1.1 业务参数同一套约定）。
   *
   * <p>{@code at} 缺省取当前时刻。返回里的 {@code usedFallback} 与 {@code note} 如实说明
   * 用的是指定档还是回退到的兜底档 —— 没有配置时返回 {@code policy=null}，调用方据此拒绝
   * 回写平台，而不是回写 0（立刻断货）或回写满（超卖）。
   */
  @GetMapping("/inventory/sellable-policies/effective")
  public SellablePolicyEffective sellableEffective(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) UUID referenceId,
      @RequestParam(required = false) String at) {
    return call(
        auth,
        "inventory.read",
        a -> sellablePolicies.effective(scopeType, referenceId, moment(at)));
  }

  /** 共享池名册 + 安全判定。池余量的比例只取决于缓冲配置，不需要库存数据。 */
  @GetMapping("/inventory/sellable-policies/pools")
  public List<SellablePoolSummary> sellablePools(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String at) {
    return call(auth, "inventory.read", a -> sellablePolicies.pools(moment(at)));
  }

  /** 单个池的成员清单、仓构成与被平台口径排除掉的 FBA 量。池名对不上 → 404。 */
  @GetMapping("/inventory/sellable-policies/pools/{poolKey}")
  public SellablePoolDetail sellablePool(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String poolKey,
      @RequestParam(required = false) String at) {
    return call(auth, "inventory.read", a -> sellablePolicies.pool(poolKey, moment(at)));
  }

  /** 口径详情（含它吃哪几个仓、哪些算自有）。跨租户在 RLS 下查不到 → 404，不泄露存在性。 */
  @GetMapping("/inventory/sellable-policies/{id}")
  public SellablePolicyDetail sellablePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.read", a -> sellablePolicies.detail(id));
  }

  /**
   * 新建一档口径。
   *
   * <p>权限码是 {@code warehouse.inventory.update} 而不是 read：缓冲比例与超卖容忍是经营决策，
   * 配错了直接超卖 —— 把「改防超卖的最后一道防线」挂在读权限上，那道闸门等于没有。
   */
  @PostMapping("/inventory/sellable-policies")
  public SellablePolicySaved createSellablePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SellablePolicyInput input) {
    return call(auth, "inventory.update", a -> sellablePolicies.save(null, input, a.userId()));
  }

  /**
   * 换版：收口当前在效的那一档，再开新档。
   *
   * <p>刻意不给「改这一档」的 PUT —— 口径内容写定后不可改（V56 触发器在 DB 层挡住），
   * 改口径只能通过收口 + 新开，历史那一档因此永远是「当时真正生效的防线」的凭据。
   */
  @PostMapping("/inventory/sellable-policies/{id}/versions")
  public SellablePolicySaved supersedeSellablePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SellablePolicyInput input) {
    return call(auth, "inventory.update", a -> sellablePolicies.save(id, input, a.userId()));
  }

  /**
   * 作废一档（收口它的生效区间）。
   *
   * <p>用 POST 而不是 DELETE：作废是审计事件，档位行必须永久保留（V54 台账的同一条纪律）。
   * 整个口径路径上**没有** DELETE 端点 —— 想知道"能不能物理删掉一条防超卖配置"，答案是不能。
   */
  @PostMapping("/inventory/sellable-policies/{id}/retire")
  public SellablePolicySaved retireSellablePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String at) {
    return call(auth, "inventory.update", a -> sellablePolicies.retire(id, moment(at)));
  }

  /**
   * 可售量计算：按生效口径算出每个 SKU 的平台可售数与池余量。
   *
   * <p>用 POST 而不是 GET：请求体是一组 SKU（与 {@code /inventory/availability} 同一理由，
   * 列表放 URL 里会撞长度限制）。7.1.1.3 与 04 回写消费的就是这个。
   */
  @PostMapping("/inventory/sellable-policies/calculation")
  public SellableQuantityResult sellableCalculation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SellableQuantityRequest request) {
    return call(auth, "inventory.read", a -> sellablePolicies.calculate(request));
  }

  // ------------------------------------------------------------ 7.4.2.1 盘点单

  /**
   * 7.4.2.1 盘点单列表（真分页；进度三计数与三列合计由明细现算）。
   *
   * <p>读权限沿用 {@code warehouse.inventory.read}：读盘点单不改任何库存。
   */
  @GetMapping("/inventory/stocktakes")
  public StocktakeSheetPage stocktakeSheets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "inventory.read", a -> stocktakes.page(status, scopeType, keyword, page, size));
  }

  /**
   * 盘点范围维度接入情况（仓库 / 库位 / 类目 / ABC 分类）。
   *
   * <p>前端据此**禁用**不可用的范围选项，而不是让用户填完收 422。
   * ABC 分类在这里被如实报成「库里没有这个字段」—— 界面必须能对用户讲清楚，
   * 而不是让一个选项凭空消失。
   */
  @GetMapping("/inventory/stocktakes/capabilities")
  public List<StocktakeScopeCapability> stocktakeCapabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> stocktakes.capabilities());
  }

  /** 盘点单详情 + 账面/实盘/差异三列合计。跨租户在 RLS 下查不到 → 404，不泄露存在性。 */
  @GetMapping("/inventory/stocktakes/{id}")
  public StocktakeDetail stocktakeSheet(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.read", a -> stocktakes.detail(id));
  }

  /**
   * 发起盘点：冻结时点快照 + 生成盘点任务。
   *
   * <p>权限码是 {@code warehouse.inventory.update} 而不是 read：它把范围内的账面数**复制成行**
   * 冻结下来（一种写库存事实的动作），并生成一堆要人去现场执行的任务。
   * 把「冻结账面」挂在读权限上，那道闸门等于没有。
   *
   * <p>刻意**没有** {@code frozenAt} 请求参数：冻结时刻就是发起的那一刻。
   * 允许调用方指定时刻等于允许「盘三天前的账」，而账面数与三天前那一刻的库存无从对齐。
   */
  @PostMapping("/inventory/stocktakes")
  public StocktakeSaved createStocktake(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody StocktakeRequest request) {
    return call(auth, "inventory.update", a -> stocktakes.create(request, a.userId()));
  }

  /**
   * 盘点明细（三列：账面 / 实盘 / 差异）。
   *
   * <p>{@code filter} 四个互斥口径：ALL / PENDING（未录入）/ MATCH（账实相符）/ GAIN（盘盈）/
   * LOSS（盘亏）。筛选与展示走的是同一个 {@code variance} 列，所以界面上算的差异与库里存的
   * 差异永远是同一个数。
   */
  @GetMapping("/inventory/stocktakes/{id}/lines")
  public StocktakeLinePage stocktakeLines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String filter,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "inventory.read", a -> stocktakes.lines(id, filter, keyword, page, size));
  }

  /**
   * 录入实盘数（或撤销录入：{@code countedQty} 传 null）。
   *
   * <p>权限码是 update：它写盘点明细。**但它不直接改库存余额** —— 差异要走 7.4.2.2 生成
   * 调整单再经 7.4.1.2 审批（蓝图红线：「差异不直接改余额」）。
   *
   * <p>录完这一行之后盘点单状态会**重算**（而不是往前推一格），因此复盘、撤销录入都能
   * 自然落回正确的状态，不需要额外分支。
   */
  @PostMapping("/inventory/stocktakes/{id}/lines/{lineId}/count")
  public StocktakeLine countStocktakeLine(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID lineId,
      @RequestBody StocktakeCountInput input) {
    return call(auth, "inventory.update", a -> stocktakes.count(id, lineId, input, a.userId()));
  }

  /**
   * 盘点任务列表（按库位或按 SKU）。
   *
   * <p>任务状态与三个进度计数全部**由明细行派生**，表里没有对应列 —— 另存一份就要在每次
   * 录入后同步它，而漏同步一次就会出现「任务显示已完成但还有行没盘」。
   *
   * <p>{@code onlyOpen=true} 时未盘完的任务排在最前：派工时先看谁还没干完。
   */
  @GetMapping("/inventory/stocktakes/{id}/tasks")
  public List<StocktakeTask> stocktakeTasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) Boolean onlyOpen) {
    return call(auth, "inventory.read", a -> stocktakes.tasks(id, onlyOpen));
  }

  /**
   * 派工：把一个盘点任务指给某个已认证用户（传 null 即取消派工）。
   *
   * <p>本服务无法校验目标用户是否真是本租户成员 —— identity 才是成员关系的所有者，
   * 跨服务不建 FK 也不查对方的表（与 7.2.2.1 的 reference_id 同一理由）。
   * 代价：派错人只影响派工，不会越权读写别人的盘点单（读写仍由 RLS 按租户强制）。
   */
  @PostMapping("/inventory/stocktakes/{id}/tasks/{taskId}/assign")
  public StocktakeTask assignStocktakeTask(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID taskId,
      @RequestBody StocktakeAssignInput input) {
    return call(auth, "inventory.update", a -> stocktakes.assign(id, taskId, input));
  }
}
