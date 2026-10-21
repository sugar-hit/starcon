package com.starcon.trade;

import static com.starcon.trade.SupplierSourceModels.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.Map;
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
 * 6.1.2.1 档口与货源池。
 *
 * <p>命名空间 {@code /trade/sources}：卷 06 的前端入口是 {@code /supply/sources}，浏览器只经 /api/trade
 * 代理访问；后端路径按**服务**命名空间走，与 6.4.1.1（前端 /purchase/processing、后端 /trade/processing）、
 * 9.3.2.2（前端 /orders、后端 /trade/fulfillment/routing）同一纪律。
 *
 * <p>权限码用 {@code trade.sources.read} / {@code trade.sources.update}：登记档口、触发采集、收藏、
 * 建立货源关联都是写动作，不是读权限能带过去的。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，再在一个事务里绑定
 * {@code app.current_tenant}，由 RLS 在数据库层强制隔离。伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/sources")
public class SupplierSourceController {

  private final AccessService access;
  private final SupplierSourceService sources;

  public SupplierSourceController(AccessService access, SupplierSourceService sources) {
    this.access = access;
    this.sources = sources;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明 / 词表 / 概览

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由与 9.3.2.2 一致：把「哪些输入真有依据」「哪些下游还没交付」「各平台是否已接线」
   * 显式报出来，而不是让调用方从 404 或空数组里去猜。
   */
  @GetMapping("/capabilities")
  public SupplierSourceCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "sources.read", a -> sources.capabilities());
  }

  /** 词表选项（前端下拉的数据源，与后端同源）。 */
  @GetMapping("/options")
  public SupplierSourceOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "sources.read", a -> sources.options());
  }

  /** 概览（不受筛选与分页影响）。 */
  @GetMapping("/overview")
  public SourceOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "sources.read", a -> sources.overview());
  }

  // ================================================================ 档口

  /** 档口列表（真分页 + 真筛选）。 */
  @GetMapping("/stalls")
  public StallPage stalls(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platform,
      @RequestParam(required = false) String collectMethod,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "false") Boolean favoritedOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "sources.read",
        a ->
            sources.pageStalls(
                new StallQuery(
                    platform, collectMethod, status, keyword, Boolean.TRUE.equals(favoritedOnly), page, size)));
  }

  /** 登记档口。 */
  @PostMapping("/stalls")
  public StallRow createStall(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody StallInput input) {
    return call(auth, "sources.update", a -> sources.createStall(input, auth));
  }

  /** 档口详情：档口头 + 当前货源条目 + 采集批次历史。 */
  @GetMapping("/stalls/{stallId}")
  public StallDetail stall(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID stallId) {
    return call(auth, "sources.read", a -> sources.stall(stallId));
  }

  /** 修改档口（名称 / URL / 采集方式 / 供应商关联 / 启停）。编码与平台建后不可改。 */
  @PutMapping("/stalls/{stallId}")
  public StallRow updateStall(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID stallId,
      @Valid @RequestBody StallUpdateInput input) {
    return call(auth, "sources.update", a -> sources.updateStall(stallId, input, auth));
  }

  /**
   * 触发一次主动抓取（{@code URL_SYNC} 档口）。
   *
   * <p>成功与失败都返回 {@link SyncResult}：失败时 status=FAILED 且带失败原因，界面照实显示 ——
   * 抓不到就明说抓不到，绝不返回一批编造的货源。
   */
  @PostMapping("/stalls/{stallId}/sync")
  public SyncResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID stallId) {
    return call(auth, "sources.update", a -> sources.sync(stallId));
  }

  /**
   * 插件推送一次采集结果（蓝图输入句「插件采集」）。
   *
   * <p>这条路径**不需要**任何外部凭证 —— 插件把采到的货源条目推上来即可。
   */
  @PostMapping("/stalls/{stallId}/collections")
  public SyncResult pushCollection(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID stallId,
      @Valid @RequestBody CollectionPushInput input) {
    return call(auth, "sources.update", a -> sources.pushCollection(stallId, input));
  }

  /** 某个档口的采集批次历史随档口详情一起返回（见 {@link #stall}），这里不另开一个重复的形状。 */

  // ================================================================ 货源条目

  /** 货源条目列表（真分页 + 真筛选 + 真排序）。 */
  @GetMapping("/items")
  public SourceItemPage items(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID stallId,
      @RequestParam(required = false) String platform,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean onSale,
      @RequestParam(required = false, defaultValue = "false") Boolean favoritedOnly,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) BigDecimal priceFrom,
      @RequestParam(required = false) BigDecimal priceTo,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "false") Boolean desc,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "sources.read",
        a ->
            sources.pageItems(
                new SourceItemQuery(
                    stallId,
                    platform,
                    keyword,
                    onSale,
                    Boolean.TRUE.equals(favoritedOnly),
                    skuId,
                    priceFrom,
                    priceTo,
                    sort,
                    Boolean.TRUE.equals(desc),
                    page,
                    size)));
  }

  /** 货源条目详情：条目 + 它的全部快照版本（价格历史）。 */
  @GetMapping("/items/{itemId}")
  public SourceItemDetail item(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID itemId) {
    return call(auth, "sources.read", a -> sources.item(itemId));
  }

  /** 收藏货源（事实，不是临时标记）。 */
  @PostMapping("/items/{itemId}/favorite")
  public FavoriteSaved favorite(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID itemId,
      @RequestBody(required = false) FavoriteInput input) {
    return call(auth, "sources.update", a -> sources.favorite(itemId, input, a.userId()));
  }

  /** 取消收藏。 */
  @DeleteMapping("/items/{itemId}/favorite")
  public FavoriteSaved unfavorite(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID itemId) {
    return call(auth, "sources.update", a -> sources.unfavorite(itemId));
  }

  /** 建立货源与内部 SKU 的关联（比价视图的输入）。 */
  @PostMapping("/items/{itemId}/sku-links")
  public SkuLinkRow linkSku(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID itemId,
      @Valid @RequestBody SkuLinkInput input) {
    return call(auth, "sources.update", a -> sources.linkSku(itemId, input, auth, a.userId()));
  }

  /** 解除货源与内部 SKU 的关联。 */
  @DeleteMapping("/items/{itemId}/sku-links/{linkId}")
  public Map<String, Boolean> unlinkSku(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID itemId,
      @PathVariable UUID linkId) {
    return call(auth, "sources.update", a -> sources.unlinkSku(itemId, linkId));
  }

  /** 某内部 SKU 的货源关联视图 + 比价（按币种分组给出最低价）。 */
  @GetMapping("/sku/{skuId}/sources")
  public SkuSourceView skuSources(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID skuId) {
    return call(auth, "sources.read", a -> sources.skuSources(skuId));
  }

  // ================================================================ 采集接入登记

  /**
   * 供应商候选（建档口时选供应商用）。
   *
   * <p>事实来源是 catalog 的授权只读接口（6.1.1.1）；取不到就 503，**不返回空清单**
   * —— 空清单会被读成「这个租户一个供应商都没有」。
   */
  @GetMapping("/suppliers")
  public java.util.List<SupplierCandidateRow> suppliers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword) {
    return call(auth, "sources.read", a -> sources.supplierCandidates(keyword, auth));
  }

  /** 某个平台的采集接入登记（未登记 = 未接线）。 */
  @GetMapping("/collector-endpoint/{platform}")
  public CollectorEndpointRow collectorEndpoint(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String platform) {
    return call(auth, "sources.read", a -> sources.collectorEndpoint(platform));
  }

  /**
   * 登记 / 更新采集接入。
   *
   * <p>只存**引用**（secret:// / kv:// / vault:// / external-secrets:// 开头），本系统不保存任何明文密钥。
   */
  @PutMapping("/collector-endpoint/{platform}")
  public CollectorEndpointSaved saveCollectorEndpoint(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String platform,
      @Valid @RequestBody CollectorEndpointInput input) {
    return call(auth, "sources.update", a -> sources.saveCollectorEndpoint(platform, input));
  }

  // 裸路径 /trade/sources **刻意不放行**到 api-proxy：后端没有「一条不存在的汇总列表接口」，
  // 放行它会让界面以为可用。
}
