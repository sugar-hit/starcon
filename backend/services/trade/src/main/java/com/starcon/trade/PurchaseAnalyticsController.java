package com.starcon.trade;

import static com.starcon.trade.PurchaseAnalyticsModels.*;

import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 6.5.2.1 采购分析。
 *
 * <p>命名空间 {@code /trade/purchase-analytics}：后端命名空间<b>按服务走</b>（与 6.2.1.3 的
 * {@code /trade/purchase-plans}、6.3.2.2 的 {@code /trade/purchase-arrivals} 同一纪律）。
 * 前端路由是 {@code /purchase/analytics} —— 那是<b>前端</b>路由，与后端命名空间是两件事。
 *
 * <p>权限码沿用 {@code trade.<page>.<action>}：{@code trade.purchase-analytics.read} /
 * {@code trade.purchase-analytics.update}。本车道<b>不改 identity 的权限真相源</b>（那是另一个服务的
 * 迁移版本号），而是沿用 trade 服务既有的权限码体系。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/purchase-analytics")
public class PurchaseAnalyticsController {

  private final AccessService access;
  private final PurchaseAnalyticsService service;

  public PurchaseAnalyticsController(AccessService access, PurchaseAnalyticsService service) {
    this.access = access;
    this.service = service;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：到货与逾期在 6.3.2.2、采购计划在 6.2.1.3、实收量在 warehouse、
   * 索赔在 11.2.3.3、类目与汇率在 catalog；采购单原单（6.3.1.x）、质检（6.4.2.1）、
   * 利润与经营分析（13）**未交付**。哪一个接通了必须由接口自己报出。
   */
  @GetMapping("/capabilities")
  public PurchaseAnalyticsCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "purchase-analytics.read", a -> service.capabilities());
  }

  // ================================================================ 参照下拉

  /** 筛选下拉项（全部来自已物化的真实事实）。 */
  @GetMapping("/options")
  public PurchaseAnalyticsOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "purchase-analytics.read", a -> service.options());
  }

  // ================================================================ 同步

  /**
   * 把已交付的上游事实物化进采购分析台账。
   *
   * <p>幂等：同一事实键只更新、不新增 ⇒ 同步可以重跑，不会把金额翻倍。
   */
  @PostMapping("/facts/sync")
  public PurchaseAnalyticsSyncResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody(required = false) PurchaseAnalyticsSyncRequest input) {
    return call(
        auth,
        "purchase-analytics.update",
        a ->
            service.sync(
                input == null ? new PurchaseAnalyticsSyncRequest(null, null, null) : input,
                a.userId(),
                auth));
  }

  /** 同步批次留痕（真分页）。 */
  @GetMapping("/sync-runs")
  public PurchaseAnalyticsSyncRunPage syncRuns(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "purchase-analytics.read", a -> service.syncRuns(page, size));
  }

  // ================================================================ 事实台账（下钻）

  /** 事实台账列表（真分页 + 真筛选 + 真排序）—— 所有指标的下钻出口。 */
  @GetMapping("/facts")
  public PurchaseAnalyticsFactPage facts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String supplierRefId,
      @RequestParam(required = false) String categoryCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String factKind,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) String onTimeStatus,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "occurred") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "purchase-analytics.read",
        a ->
            service.pageFacts(
                new PurchaseAnalyticsQuery(
                    from,
                    to,
                    currency,
                    supplierRefId,
                    categoryCode,
                    shopCode,
                    factKind,
                    sourceType,
                    onTimeStatus,
                    keyword,
                    sort,
                    page,
                    size)));
  }

  /** 事实详情。 */
  @GetMapping("/facts/{id}")
  public PurchaseAnalyticsFactDetail fact(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "purchase-analytics.read", a -> service.factDetail(id));
  }

  // ================================================================ 报表

  /** 总览 KPI。 */
  @GetMapping("/summary")
  public PurchaseAnalyticsSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String supplierRefId) {
    return call(
        auth,
        "purchase-analytics.read",
        a -> service.summary(query(from, to, currency, supplierRefId, null, null, null, null)));
  }

  /** 采购金额趋势（按币种分列）。 */
  @GetMapping("/trend")
  public PurchaseAnalyticsTrend trend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false, defaultValue = "MONTH") String granularity,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String supplierRefId) {
    return call(
        auth,
        "purchase-analytics.read",
        a ->
            service.trend(
                query(from, to, currency, supplierRefId, null, null, null, null), granularity));
  }

  /** 供应商集中度与贡献。 */
  @GetMapping("/supplier-concentration")
  public PurchaseAnalyticsConcentration supplierConcentration(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String supplierRefId,
      @RequestParam(required = false, defaultValue = "5") int topN) {
    return call(
        auth,
        "purchase-analytics.read",
        a ->
            service.supplierConcentration(
                query(from, to, currency, supplierRefId, null, null, null, null), topN));
  }

  /** 采购价格波动（环比）。 */
  @GetMapping("/price-variance")
  public PurchaseAnalyticsPriceVariance priceVariance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String supplierRefId,
      @RequestParam(required = false, defaultValue = "SUPPLIER") String dimension) {
    return call(
        auth,
        "purchase-analytics.read",
        a ->
            service.priceVariance(
                query(from, to, currency, supplierRefId, null, null, null, null), dimension));
  }

  /** 到货准时率（分母是承诺到货行，不是收货行）。 */
  @GetMapping("/on-time-rate")
  public PurchaseAnalyticsOnTimeRate onTimeRate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String supplierRefId) {
    return call(
        auth,
        "purchase-analytics.read",
        a -> service.onTimeRate(query(from, to, currency, supplierRefId, null, null, null, null)));
  }

  /** 采购成本占销售额比（分母不可用时显式标「算不出」）。 */
  @GetMapping("/cost-to-sales-ratio")
  public PurchaseAnalyticsCostToSales costToSalesRatio(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String supplierRefId) {
    return call(
        auth,
        "purchase-analytics.read",
        a -> service.costToSales(query(from, to, currency, supplierRefId, null, null, null, null)));
  }

  /** 按类目 / 店铺维度的采购效率。 */
  @GetMapping("/efficiency")
  public PurchaseAnalyticsEfficiency efficiency(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false, defaultValue = "CATEGORY") String dimension,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String supplierRefId,
      @RequestParam(required = false) String categoryCode,
      @RequestParam(required = false) String shopCode) {
    return call(
        auth,
        "purchase-analytics.read",
        a ->
            service.efficiency(
                query(from, to, currency, supplierRefId, categoryCode, shopCode, null, null),
                dimension));
  }

  // ================================================================ 导出

  /**
   * 导出下钻明细 CSV。
   *
   * <p>StreamingResponseBody 分批写出，峰值内存与结果集大小无关；导出内容与当前筛选完全一致。
   */
  @GetMapping(value = "/export", produces = "text/csv")
  public ResponseEntity<StreamingResponseBody> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String supplierRefId,
      @RequestParam(required = false) String categoryCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String factKind,
      @RequestParam(required = false) String sourceType,
      @RequestParam(required = false) String onTimeStatus,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "occurred") String sort) {
    var actor = access.require(auth, "trade.purchase-analytics.read");
    var query =
        query(
            from,
            to,
            currency,
            supplierRefId,
            categoryCode,
            shopCode,
            factKind,
            sourceType,
            onTimeStatus,
            keyword,
            sort);
    // 先在租户事务里校验筛选条件，别等到已经开始写流才失败。
    access.run(actor, "trade.purchase-analytics.read", () -> service.pageFacts(query));
    StreamingResponseBody body =
        out -> service.streamCsv(actor, query, chunk -> write(out, chunk));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"purchase-analytics-"
                + System.currentTimeMillis()
                + ".csv\"")
        .body(body);
  }

  private static void write(java.io.OutputStream out, String chunk) {
    try {
      out.write(chunk.getBytes(StandardCharsets.UTF_8));
      out.flush();
    } catch (IOException e) {
      // 客户端提前断开（关掉下载页）是正常行为，不该当成服务端错误往上抛。
      throw new org.springframework.web.client.RestClientException("导出已中断", e);
    }
  }

  private static PurchaseAnalyticsQuery query(
      LocalDate from,
      LocalDate to,
      String currency,
      String supplierRefId,
      String categoryCode,
      String shopCode,
      String factKind,
      String sourceType) {
    return new PurchaseAnalyticsQuery(
        from,
        to,
        currency,
        supplierRefId,
        categoryCode,
        shopCode,
        factKind,
        sourceType,
        null,
        null,
        "occurred",
        0,
        50);
  }

  private static PurchaseAnalyticsQuery query(
      LocalDate from,
      LocalDate to,
      String currency,
      String supplierRefId,
      String categoryCode,
      String shopCode,
      String factKind,
      String sourceType,
      String onTimeStatus,
      String keyword,
      String sort) {
    return new PurchaseAnalyticsQuery(
        from,
        to,
        currency,
        supplierRefId,
        categoryCode,
        shopCode,
        factKind,
        sourceType,
        onTimeStatus,
        keyword,
        sort,
        0,
        50);
  }
}
