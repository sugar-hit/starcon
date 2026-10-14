package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 3.6.2.1 供应商报价：报价台账与蓝图点名的输出「有效报价列表」。
 *
 * <p>路由分两处，理由是**读取粒度不同**：
 *
 * <ul>
 *   <li>{@code /catalog/supplier-quotes/*} —— 跨主体的报价台账（概览 / 列表 / 词表 / 详情 / 写入）。 运营要问的是「哪些 SKU
 *       快没价了」「哪些报价优先级并列要人处理」，主体不固定， 所以它是一个集合资源。
 *   <li>{@code /catalog/supplier-quotes/effective*} —— 蓝图「输出」那一行的读出口。
 *       与列表分开的理由：列表是<b>给人看的</b>（它把所有有效报价都列出来并标注状态）， 而这里两个端点是<b>给下游单据用的</b>（06 采购带出价格、05 定价取成本参考），
 *       它们需要的是"唯一那一条"，因此必须在歧义时<b>失败</b>而不是给人看。
 * </ul>
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}， 与 3.6.1.1 / 3.6.1.2
 * 完全一致。不新造权限词表 —— 那要改 identity 服务的迁移， 属于另一个服务的写入所有权。
 *
 * <p>租户一律来自已认证成员关系（{@link AccessService} 向 identity 解析）， <b>不读 {@code X-Tenant-Id}
 * 头</b>：那是客户端自称的租户，AGENTS.md 明令禁止。
 */
@RestController
@RequestMapping("/catalog/supplier-quotes")
public class SupplierQuoteController {
  private final AccessService access;
  private final SupplierQuoteService service;

  public SupplierQuoteController(AccessService access, SupplierQuoteService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 概览与词表

  /** 概览：报价总数、有效数、歧义数（蓝图点名规则的治理计数）、停用数、即将到期数。 */
  @GetMapping("/overview")
  public SupplierQuoteOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.overview());
  }

  /**
   * 词表选项：前端下拉的数据源。
   *
   * <p>与后端词表同源（枚举只增不改这条承诺要在**一处**兑现）。 币种与采购单位刻意不在这里给列表：那是 12 财务与 3.3.3 的资产。
   */
  @GetMapping("/options")
  public SupplierQuoteOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> service.options());
  }

  // ------------------------------------------------------------ 列表与详情

  /**
   * 报价台账列表：分页 + 关键词 / SKU / 供应商 / 币种 / 状态 / 采购单位 / 「只看有效」「只看需人工选择」筛选。
   *
   * <p>每行的 {@code selectionState} 按 (SKU, 供应商) 分组计算 —— 优先级只在同一个供应商内部可比，跨供应商比优先级是错的。
   */
  @GetMapping
  public SupplierQuotePage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID supplierId,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String purchaseUnitCode,
      @RequestParam(required = false) Boolean effectiveOnly,
      @RequestParam(required = false) Boolean ambiguousOnly,
      @RequestParam(required = false) LocalDate at,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "spu.read",
        a ->
            service.list(
                new SupplierQuoteQuery(
                    keyword,
                    skuId,
                    supplierId,
                    currency,
                    status,
                    purchaseUnitCode,
                    effectiveOnly,
                    ambiguousOnly,
                    at,
                    page,
                    size)));
  }

  /** 一条报价的详情（带阶梯价明细）。 */
  @GetMapping("/{id}")
  public SupplierQuote detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> service.detail(id));
  }

  /** 一条报价的阶梯价明细（"编辑报价"面板单独刷新阶梯时用）。 */
  @GetMapping("/{id}/tiers")
  public Map<String, Object> tiers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    var tiers = call(auth, "spu.read", a -> service.tiersOf(id));
    return Map.of("items", tiers);
  }

  // ------------------------------------------------------------ 有效报价（蓝图输出）

  /**
   * 有效报价列表（蓝图的「输出」原文：有效报价列表，默认取优先级最高且在有效期内的版本）。
   *
   * <p>返回全部有效报价并<b>逐条标注</b>选择状态： {@code SELECTED} / {@code AMBIGUOUS} / {@code LOWER_PRIORITY}。
   *
   * <p>歧义时 {@code selected} 返回 <b>null</b> 而不是随便一条（规则 **R2**）： 蓝图那句「同一优先级多条有效报价必须报错让用户选，不能随机取一条」在这里
   * 体现为"不替你选 + 把并列的都列出来"。
   *
   * <p>不传 {@code supplierId} 时（05 定价 / 13 利润读全市场成本参考）： {@code selected} <b>恒为
   * null</b>，因为优先级只在同一个供应商内部才有可比性 —— 星尘的优先级 1 与宏远的优先级 1 之间没有可比性，那是两个供应商在竞价。
   *
   * <p>传 {@code quantity} 时会顺带在选中那条报价的阶梯里定位适用档（{@code selectedTier}）。 数量低于最低档时 {@code selectedTier}
   * 为 null 并给出提示，**不**退回高档价。
   */
  @GetMapping("/effective")
  public SupplierQuoteEffectiveResult effective(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID skuId,
      @RequestParam(required = false) UUID supplierId,
      @RequestParam(required = false) LocalDate at,
      @RequestParam(required = false) java.math.BigDecimal quantity) {
    return call(auth, "spu.read", a -> service.effective(skuId, supplierId, at, quantity));
  }

  /**
   * 严格取价出口（06 采购单带出价格走这个）。
   *
   * <p>与 {@code /effective} 只有一个差别：<b>歧义时 409 而不是返回 ambiguous=true</b>。 「报错让用户选」必须发生在建单的那一刻 ——
   * 采购单不能带着一条"系统随便选的价"发出去， 而一个 200 + {@code selected=null} 很容易被前端误读成"没报价"
   * （症状是提示文案答非所问，真正的原因却是优先级并列）。
   *
   * <p>没有有效报价时也 404（"这个 SKU 该供应商现在没价可下"，是一个需要解决的阻断）。
   */
  @GetMapping("/effective/resolve")
  public SupplierQuoteEffectiveResult resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID skuId,
      @RequestParam UUID supplierId,
      @RequestParam(required = false) LocalDate at,
      @RequestParam(required = false) java.math.BigDecimal quantity) {
    return call(auth, "spu.read", a -> service.resolve(skuId, supplierId, at, quantity));
  }

  // ------------------------------------------------------------ 写入

  /**
   * 新建报价。
   *
   * <p>{@code supplierId} 传的是**主体 id**（{@code party.id}）而不是角色 id： 前端手上只有主体，SUPPLIER 角色由服务端解析（规则
   * **R1**）。
   *
   * <p>{@code tiers} 至少一档、起订量严格递增（规则 **R5**）； MOQ 必填且 &gt; 0、交期 0 表示当日发货（规则 **R4**）； 币种是 ISO 4217
   * 三位码、阶梯与报价同币种（规则 **R3**）。
   */
  @PostMapping
  public SupplierQuote create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SupplierQuoteInput input) {
    return call(auth, "spu.update", a -> service.create(a, input));
  }

  /**
   * 改报价（乐观锁 + 阶梯整体替换）。
   *
   * <p>必须回传详情里拿到的 {@code version}，否则 409。 阶梯是**整体替换**而不是增量合并 —— 报价的阶梯是一个整体， 合并写入会让"删掉一档"这个动作无法表达。
   */
  @PutMapping("/{id}")
  public SupplierQuote update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody SupplierQuoteInput input) {
    return call(auth, "spu.update", a -> service.update(a, id, input));
  }

  /**
   * 删掉一条报价（阶梯由 ON DELETE CASCADE 跟着删）。
   *
   * <p>删掉之后 06 采购退回"该 SKU 无有效报价"（建单时报错要求重新询价）， 这是一个有意义且可逆的状态。已发生的采购单带的是自己的冻结快照，不读本表。
   */
  @DeleteMapping("/{id}")
  public Map<String, Boolean> delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam long version) {
    call(
        auth,
        "spu.update",
        a -> {
          service.delete(a, id, version);
          return Boolean.TRUE;
        });
    return Map.of("deleted", true);
  }
}
