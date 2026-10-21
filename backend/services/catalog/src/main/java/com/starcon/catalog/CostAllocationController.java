package com.starcon.catalog;

import static com.starcon.catalog.CostAllocationModels.*;

import jakarta.validation.Valid;
import java.util.List;
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
 * 5.2.1.3 成本分摊。
 *
 * <p>命名空间 {@code /catalog/cost-allocations}：与 5.2.1.1 成本构成、5.2.1.2 到岸成本
 * <b>共用页面路由 {@code /pricing/cost}</b>（前端按分区块组织），但端点各自独立 ——
 * 成本构成是 SKU 级单位成本、本模块是<b>货件级</b>分摊规则与分摊明细，两者的粒度不同，
 * 合并会让「这个 SKU 的成本」和「这一批货怎么摊」分不清。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p><b>🔴 跨服务调用透传调用方自己的 Authorization 头</b>：可分摊的货件来自 warehouse 的
 * 头程发运单（AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图），本服务把<b>调用方自己的</b>
 * bearer 原样带过去，租户由 warehouse 从成员关系解析 —— 本服务<b>不</b>替对方决定租户，
 * 也<b>不</b>发明 {@code X-Tenant-Id} 之类的头。
 */
@RestController
@RequestMapping("/catalog/cost-allocations")
public class CostAllocationController {

  private final AccessService access;
  private final CostAllocationService allocations;

  public CostAllocationController(AccessService access, CostAllocationService allocations) {
    this.access = access;
    this.allocations = allocations;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：四种分摊规则、两种来源、四个基数的取数口径、版本纪律、上下游关系。
   *
   * <p>页面上把它固定显示在顶部 —— 蓝图那句「分摊规则变更会导致历史利润变化」靠它落地。
   */
  @GetMapping("/capabilities")
  public PricingCostAllocationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> allocations.capabilities());
  }

  /**
   * 可分摊的货件候选（头程发运单）。
   *
   * <p>🔴 上游读不到时返回 **503**（理由写「读不到上游」），不用空清单冒充「没有货件」。
   */
  @GetMapping("/sources")
  public PricingCostAllocationSourceCandidatePage sources(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> allocations.sources(auth));
  }

  /** 分摊单台账：分页 + 关键词 / 来源 / 币种 / 规则筛选。 */
  @GetMapping
  public PricingCostAllocationListPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String basisRule,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            allocations.list(
                new PricingCostAllocationListQuery(
                    keyword,
                    sourceType,
                    currency,
                    basisRule,
                    page == null ? 0 : page,
                    size == null ? 20 : size)));
  }

  /**
   * 新建分摊单：选货件 + 选规则 + 填货件成本总额，落第一个版本。
   *
   * <p>换规则不是「改」，是「再开一版」：见 {@code /{allocationId}/revisions}。
   */
  @PostMapping
  public PricingCostAllocationSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PricingCostAllocationCreateInput input) {
    return call(auth, "spu.update", a -> allocations.create(a, input, auth));
  }

  /** 分摊单明细：当前版本 + 逐 SKU 分摊明细 + 与上一版的差异 + 版本历史。 */
  @GetMapping("/{allocationId}")
  public PricingCostAllocationDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID allocationId) {
    return call(auth, "spu.read", a -> allocations.detail(allocationId));
  }

  /** 版本历史（新到旧）。历史不可变 —— 没有 UPDATE 端点，也永远不会有。 */
  @GetMapping("/{allocationId}/revisions")
  public List<PricingCostAllocationRevisionRow> revisions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID allocationId) {
    return call(auth, "spu.read", a -> allocations.revisions(allocationId));
  }

  /** 某一个版本的明细（当时的快照）。 */
  @GetMapping("/{allocationId}/revisions/{revisionNo}")
  public PricingCostAllocationRevisionDetail revision(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID allocationId,
      @PathVariable int revisionNo) {
    return call(auth, "spu.read", a -> allocations.revision(allocationId, revisionNo));
  }

  /** 两版之间的差异对比（from / to 缺省 = 最近两版）。 */
  @GetMapping("/{allocationId}/diffs")
  public PricingCostAllocationDiff diffs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID allocationId,
      @RequestParam(required = false) Integer from,
      @RequestParam(required = false) Integer to) {
    return call(auth, "spu.read", a -> allocations.diff(allocationId, from, to));
  }

  /**
   * 试算重算（**不落库**）：给出新规则 / 新总额 / 新微调下的分摊明细与差异。
   *
   * <p>蓝图输出里的「重算与差异对比」落在这一步 —— 看清了再决定要不要落库。
   */
  @PostMapping("/{allocationId}/recalculate")
  public PricingCostAllocationPreview recalculate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID allocationId,
      @Valid @RequestBody PricingCostAllocationRecalculateInput input) {
    return call(auth, "spu.read", a -> allocations.recalculate(allocationId, input, auth));
  }

  /**
   * 应用重算：追加一个新版本，旧版本原样留着。
   *
   * <p>🔴 蓝图加粗句「调整必须留历史版本，不能就地覆盖」的物理落点。
   */
  @PostMapping("/{allocationId}/revisions")
  public PricingCostAllocationSaved applyRecalculation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID allocationId,
      @Valid @RequestBody PricingCostAllocationRecalculateInput input) {
    return call(auth, "spu.update", a -> allocations.applyRecalculation(a, allocationId, input, auth));
  }
}
