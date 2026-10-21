package com.starcon.catalog;

import static com.starcon.catalog.SkuCostCompositionModels.*;

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
 * 5.2.1.1 成本构成。
 *
 * <p>命名空间 {@code /catalog/sku-cost-compositions}：与 5.2.1.2 到岸成本、5.2.1.3 成本分摊
 * **共用页面路由 {@code /pricing/cost}**（前端按分区块组织），但端点各自独立 ——
 * 到岸成本是单据级概念、成本分摊是规则表，与本模块的「SKU 级单位成本构成」不同源。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p><b>🔴 跨服务调用透传调用方自己的 Authorization 头</b>：头程分摊与关税的唯一来源是
 * warehouse 的授权只读端点（AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图），
 * 本服务把**调用方自己的** bearer 原样带过去，租户由 warehouse 从成员关系解析 ——
 * 本服务**不**替对方决定租户，也**不**发明 {@code X-Tenant-Id} 之类的头。
 */
@RestController
@RequestMapping("/catalog/sku-cost-compositions")
public class SkuCostCompositionController {

  private final AccessService access;
  private final SkuCostCompositionService costs;

  public SkuCostCompositionController(AccessService access, SkuCostCompositionService costs) {
    this.access = access;
    this.costs = costs;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：五个分项的来源、能不能取到、归谁管；三种成本法的口径；四条必须讲清的说明。
   *
   * <p>页面上把它固定显示在顶部 —— 蓝图那句「成本口径必须在页面上明示用了哪种成本法」靠它落地。
   */
  @GetMapping("/capabilities")
  public CostCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> costs.capabilities());
  }

  /**
   * 成本构成台账：分页 + 关键词 / 成本法 / 币种 / 是否已维护筛选。
   *
   * <p>列表**不实时连上游**（头程与关税在明细里算），所以这里永远是快的。
   */
  @GetMapping
  public CostListPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String costMethod,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) Boolean maintained,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate windowFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate windowTo,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            costs.list(
                new CostListQuery(
                    keyword, costMethod, currency, maintained, windowFrom, windowTo,
                    page == null ? 0 : page, size == null ? 20 : size)));
  }

  /**
   * 成本构成明细：五个分项 + 合计 + 生效时间 + 成本变动历史 + 批次 + 手工调整项 + 报价参考。
   *
   * <p>🔴 上游读不到时返回 **503**（理由写「读不到上游」），不用空集合冒充「上游没有这笔成本」。
   */
  @GetMapping("/{skuId}")
  public CostDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate windowFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate windowTo) {
    return call(
        auth,
        "spu.read",
        a -> costs.detail(skuId, currency, windowFrom(windowFrom), windowTo(windowTo), auth));
  }

  /**
   * 保存成本构成（成本法 / 计价币种 / 包装成本参数 / 生效时间）。
   *
   * <p><b>每一次保存都追加一条历史快照</b>：换成本法是换口径，历史必须留版本 ——
   * 「这个 SKU 昨天的成本 12.00 是移动加权算的」要能被回答。
   */
  @PutMapping("/{skuId}")
  public CostSaved save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @Valid @RequestBody ProfileInput input) {
    return call(auth, "spu.update", a -> costs.save(a, skuId, input, auth));
  }

  /** 追加手工调整项（**原因必填**）。 */
  @PostMapping("/{skuId}/adjustments")
  public CostSaved addAdjustment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @Valid @RequestBody AdjustmentInput input) {
    return call(auth, "spu.update", a -> costs.addAdjustment(a, skuId, input, auth));
  }

  /** 作废一条调整项（**必须给理由**）。作废不是删除：旧行留在库里，历史可追溯。 */
  @PostMapping("/{skuId}/adjustments/{adjustmentId}/void")
  public CostSaved voidAdjustment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @PathVariable UUID adjustmentId,
      @Valid @RequestBody VoidInput input) {
    return call(auth, "spu.update", a -> costs.voidAdjustment(a, skuId, adjustmentId, input, auth));
  }

  /** 成本变动历史（新到旧）。历史不可变 —— 没有 UPDATE 端点，也永远不会。 */
  @GetMapping("/{skuId}/revisions")
  public List<RevisionRow> revisions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId,
      @RequestParam(required = false) String currency) {
    return call(auth, "spu.read", a -> costs.revisions(skuId, currency));
  }

  /** 成本口径窗口的起点：缺省是最近 90 天的头。 */
  private static LocalDate windowFrom(LocalDate value) {
    return value == null ? LocalDate.now().minusDays(90) : value;
  }

  /** 成本口径窗口的终点：缺省是今天。 */
  private static LocalDate windowTo(LocalDate value) {
    return value == null ? LocalDate.now() : value;
  }
}
