package com.starcon.warehouse;

import static com.starcon.warehouse.StockOwnershipModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;
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
 * 7.7.1.1 货主与所有权。
 *
 * <p>命名空间 {@code /warehouse/inventory/ownership}：与总览、明细、台账、核对、批次、效期同一前缀， 浏览器只经 /api/warehouse 代理访问。
 *
 * <p>权限码沿用 {@code warehouse.}：读用 {@code warehouse.inventory.read}， 写入（建货主、配货权、货权转移）用 {@code
 * warehouse.inventory.update} —— 配货权与转移货权直接改变资产归属，不是读权限能带过去的动作。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code app.current_tenant}，由
 * RLS 在数据库层强制隔离。 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/warehouse/inventory/ownership")
public class StockOwnershipController {

  private final AccessService access;
  private final StockOwnershipService ownership;

  public StockOwnershipController(AccessService access, StockOwnershipService ownership) {
    this.access = access;
    this.ownership = ownership;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ============================================================ 能力声明

  /**
   * 本模块的能力与上游可用性。
   *
   * <p>存在的理由：货值口径**不可用**这件事必须由接口自己说，而不是让调用方从一个空数组里 去猜是哪一环没通（warehouse 与 catalog 全库都没有库存成本列，见
   * capabilities 的说明）。
   */
  @GetMapping("/capabilities")
  public StockOwnershipCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> ownership.capabilities());
  }

  // ============================================================ 货主档案

  /** 货主列表。{@code ownedAssetOnly=true} 只看计入自有资产的货主（生成列筛选）。 */
  @GetMapping("/owners")
  public StockOwnerPage owners(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ownerKind,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID ownerRefId,
      @RequestParam(required = false) Boolean ownedAssetOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            ownership.pageOwners(
                new StockOwnerQuery(
                    ownerKind, status, ownerRefId, ownedAssetOnly, keyword, page, size)));
  }

  /** 单个货主。跨租户在 RLS 下查不到 → 404。 */
  @GetMapping("/owners/{ownerId}")
  public StockOwnerRow owner(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ownerId) {
    return call(auth, "inventory.read", a -> ownership.owner(ownerId));
  }

  /**
   * 新建货主。
   *
   * <p>{@code ownerRefType=LEGAL_ENTITY} 时 {@code ownerRefId} 必须是本租户已登记的法人； {@code SUPPLIER} 是
   * catalog 的跨服务逻辑 ID（只存 ID + 名称快照，不建外键不联查）。
   */
  @PostMapping("/owners")
  public StockOwnerSaved createOwner(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody StockOwnerInput input) {
    return call(auth, "inventory.update", a -> ownership.saveOwner(input, a.membershipId()));
  }

  /** 改货主属性。编码与所有权类型不可改（见服务注释）。 */
  @PutMapping("/owners/{ownerId}")
  public StockOwnerSaved updateOwner(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ownerId,
      @Valid @RequestBody StockOwnerInput input) {
    return call(
        auth, "inventory.update", a -> ownership.updateOwner(ownerId, input, a.membershipId()));
  }

  /** 货主停用 / 启用。刻意没有 DELETE：货权是审计凭据，删掉之后历史货权无人能答。 */
  @PostMapping("/owners/{ownerId}/status")
  public StockOwnerSaved setOwnerStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ownerId,
      @RequestParam(required = false) String status) {
    return call(auth, "inventory.update", a -> ownership.setOwnerStatus(ownerId, status));
  }

  // ============================================================ 货权配置

  /** 货权档列表。{@code effectiveOnly=true} 只看此刻仍在生效的档。 */
  @GetMapping("/rules")
  public OwnershipRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) UUID ownerId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false, defaultValue = "false") boolean effectiveOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            ownership.pageRules(
                new OwnershipRuleQuery(
                    scopeType,
                    ownerId,
                    warehouseId,
                    legalEntityId,
                    skuId,
                    effectiveOnly,
                    keyword,
                    page,
                    size)));
  }

  /** 单档货权配置。 */
  @GetMapping("/rules/{ruleId}")
  public OwnershipRuleRow rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "inventory.read", a -> ownership.rule(ruleId));
  }

  /**
   * 新建一档货权配置。
   *
   * <p><b>只对生效之后写入的库位明细生效</b>：货权档带生效区间，存量 bucket 不会被自动改判。 要让存量跟着改，走 {@code POST
   * /rules/{ruleId}/apply}。
   */
  @PostMapping("/rules")
  public OwnershipRuleSaved createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody OwnershipRuleInput input) {
    return call(auth, "inventory.update", a -> ownership.saveRule(null, input, a.membershipId()));
  }

  /** 换版：收口旧档区间 + 开新档。没有「就地改」的入口。 */
  @PostMapping("/rules/{ruleId}/versions")
  public OwnershipRuleSaved supersedeRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody OwnershipRuleInput input) {
    return call(auth, "inventory.update", a -> ownership.saveRule(ruleId, input, a.membershipId()));
  }

  /** 作废一档货权（收口它的生效区间）。刻意没有 DELETE。 */
  @PostMapping("/rules/{ruleId}/retire")
  public OwnershipRuleSaved retireRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @RequestParam(required = false) OffsetDateTime at) {
    return call(auth, "inventory.update", a -> ownership.retireRule(ruleId, at));
  }

  // ============================================================ 解析与报表

  /**
   * 解析某一组维度在某一时刻真正生效的货主。
   *
   * <p>优先级 SKU &gt; LEGAL_ENTITY &gt; WAREHOUSE &gt; DEFAULT。四档全未命中时回落到默认货主， 并把 {@code
   * usedFallback} 标成 true —— 调用方必须能区分「查到一条真实配置」与「没配过」。
   *
   * <p>时刻不传取当前时刻；传了也只是用于回看历史某一刻的判定，不接受客户端指定租户。
   */
  @GetMapping("/resolved")
  public StockOwnershipResolved resolved(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) OffsetDateTime at) {
    return call(
        auth, "inventory.read", a -> ownership.resolve(warehouseId, legalEntityId, skuId, at));
  }

  /**
   * 按货主拆分的库存报表（真实从 stock_bucket 聚合）。
   *
   * <p>{@code amountBasisAvailable} 恒为 false：本仓库没有库存成本列，货值口径不可用。 宁可显式报不可用，也不给一个用采购报价冒充出来的假金额。
   */
  @GetMapping("/report")
  public OwnerInventoryReport report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) UUID skuId) {
    return call(
        auth, "inventory.read", a -> ownership.report(warehouseId, legalEntityId, skuId, null));
  }

  /**
   * 货权漂移清单：库位明细上的货主 ≠ 当前生效档应给的货主。
   *
   * <p>这类行是有意的：货权档只对生效之后写入的 bucket 赋权。把它们列出来， 是为了让「这批货要不要跟着改」成为一个可回答的问题。
   */
  @GetMapping("/drift")
  public OwnershipDriftReport drift(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) UUID skuId) {
    return call(auth, "inventory.read", a -> ownership.drift(warehouseId, legalEntityId, skuId));
  }

  // ============================================================ 货权转移

  /** 货权转移对话框需要的可选项（仓库 / 法人 / SKU）。 */
  @GetMapping("/reference-options")
  public OwnershipReferenceOptions referenceOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> ownership.referenceOptions());
  }

  /**
   * 货权转移：把某一范围内属于 {@code fromOwnerId} 的库位明细改判给 {@code toOwnerId}。
   *
   * <p><b>数量、预占、库存余额与流水都不变</b> —— 货权是账上的归属，不是货物流动。 这是本模块唯一批量改动既有库存事实行的入口，权限走 update。
   */
  @PostMapping("/reassign")
  public OwnershipReassignResult reassign(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody OwnershipReassignRequest request) {
    return call(auth, "inventory.update", a -> ownership.reassign(request, null));
  }
}
