package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.2.2.1 店铺与站点的 HTTP 端点。
 *
 * <p>命名空间是 {@code /reporting/store-site}：13.2.2.2 用的是 {@code /reporting/product-sales}，
 * 13.2.1.1 用的是 {@code /reporting/sales-trend}，兄弟模块各有各的前缀，
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码：{@code reporting.store-site.read}（读店铺/站点拆解报表）与
 * {@code reporting.projection.write}（刷新投影 —— 写的是**投影**，不是业务事实）。
 * 本模块**没有任何「创建业务事实」的权限码**（reporting 是投影服务）。
 */
@RestController
@RequestMapping("/reporting/store-site")
@Tag(name = "13.2.2.1 店铺与站点")
public class ReportingStoreSiteController {

  private final AccessService access;
  private final ReportingStoreSiteService service;
  private final ReportingStoreSiteRefreshService refresh;

  public ReportingStoreSiteController(
      AccessService access,
      ReportingStoreSiteService service,
      ReportingStoreSiteRefreshService refresh) {
    this.access = access;
    this.service = service;
    this.refresh = refresh;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明：哪些上游真接通、哪些口径在店铺/站点维度拿不到、判定规则是什么",
      description =
          "notDelivered 列出的是**确实拿不到**的部分，每条都写清原因与影响 —— "
              + "把「拿不到」伪装成 0 比说清楚更糟。")
  public ReportingStoreSiteModels.StoreSiteCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.store-site.read",
        actor ->
            new ReportingStoreSiteModels.StoreSiteCapabilities(
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                UPSTREAM_MODULES,
                NOT_DELIVERED,
                RULES,
                List.of("SHOP", "SITE", "PLATFORM"),
                List.of(
                    ReportingStoreSiteCatalog.STORE_SITE_SALES_AMOUNT,
                    ReportingStoreSiteCatalog.STORE_SITE_SALES_QUANTITY,
                    ReportingStoreSiteCatalog.STORE_SITE_ORDER_COUNT,
                    ReportingStoreSiteCatalog.STORE_SITE_AMOUNT_SHARE,
                    ReportingStoreSiteCatalog.STORE_SITE_QUANTITY_SHARE,
                    ReportingStoreSiteCatalog.STORE_SITE_AMOUNT_GROWTH,
                    ReportingStoreSiteCatalog.STORE_SITE_QUANTITY_GROWTH,
                    ReportingStoreSiteCatalog.STORE_SITE_AMOUNT_GROWTH_CONTRIBUTION,
                    ReportingStoreSiteCatalog.STORE_SITE_QUANTITY_GROWTH_CONTRIBUTION,
                    ReportingStoreSiteCatalog.STORE_SITE_NEW_STORE_AMOUNT_CONTRIBUTION)));
  }

  // ================================================================ 指标口径目录

  @GetMapping("/metrics")
  @Operation(summary = "店铺/站点维度的指标口径目录（口径唯一的展示形态）")
  public List<ReportingStoreSiteModels.StoreSiteMetricDefinitionView> metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.store-site.read",
        actor -> service.metricDefinitions(actor.tenantId()));
  }

  // ================================================================ 维度拆解报表

  @GetMapping
  @Operation(
      summary = "店铺 / 站点 / 平台维度拆解：销量与销售额 + 占比 + 增长贡献 + 新老店 + 渠道占比",
      description =
          "时间范围默认「最近 30 天」。维度由 dimension 决定（SHOP / SITE / PLATFORM）。"
              + "🔴 **绝不跨币种相加**：成交金额按币种分别列出；只有件数与单数给跨币种合计。"
              + "🔴 **站点维度有归属边界**：订单不带站点，启用多个站点的店铺其销量归不了任何站点，"
              + "本模块单列「无法唯一归属」，不摊给任何站点。"
              + "🔴 **增长贡献度** = 该维度的增量 ÷ 全部维度的增量之和 —— "
              + "它回答的是「哪个店带来了增量」。")
  public ReportingStoreSiteModels.StoreSiteResponse report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "SHOP") String dimension,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.store-site.read",
        actor -> {
          var window = resolveWindow(from, to);
          return service.report(
              actor.tenantId(), window.from(), window.to(), dimension, page, size);
        });
  }

  // ================================================================ 构成明细下钻

  @GetMapping("/drilldown")
  @Operation(
      summary = "构成明细下钻：某个维度对象、某个指标的构成明细（哪张订单、哪张订单的哪一行）",
      description =
          "卷 13 卷首加粗句「可下钻」的落点：每个汇总数字都能钻到构成明细。"
              + "维度对象由 dimension + dimensionKey 定位（SHOP 维度 ⇒ dimensionKey 就是店铺码）。")
  public ReportingStoreSiteModels.StoreSiteDrilldownResponse drilldown(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "SHOP") String dimension,
      @RequestParam String dimensionKey,
      @RequestParam(required = false) String metricCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.store-site.read",
        actor -> {
          var window = resolveWindow(from, to);
          return service.drilldown(
              actor.tenantId(),
              window.from(),
              window.to(),
              dimension,
              dimensionKey,
              blankToNull(metricCode),
              page,
              size);
        });
  }

  // ================================================================ 刷新（投影摄取）

  @PostMapping("/refresh")
  @Operation(
      summary = "从上游拉取店铺/站点档案与店铺维度事实并投影（写的是投影，不是业务事实）",
      description =
          "上游：channel 的平台与站点目录（站点币种 / 时区 / 国家）、channel 的店铺清单与"
              + "店铺详情（该店铺启用哪些站点）、trade 的销售订单与订单明细（金额 / 销量 / 订单数）"
              + "与平台单（销售订单 → 店铺归属）。"
              + "⚠️ 一次刷新会投影**当前窗口 + 上一等长窗口**（基期），否则增长贡献度永远算不出来。")
  public List<ReportingStoreSiteModels.StoreSiteRefreshResult> refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.projection.write",
        actor -> {
          var window = resolveWindow(from, to);
          var days = ReportingStoreSiteRefreshService.windowDays(window.from(), window.to());
          if (days > ReportingStoreSiteRefreshService.MAX_WINDOW_DAYS) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "店铺/站点窗口最多 "
                    + ReportingStoreSiteRefreshService.MAX_WINDOW_DAYS
                    + " 天：订单明细要逐单取，窗口太长会把上游压垮。");
          }
          return refresh.refresh(actor.tenantId(), auth, window.from(), window.to());
        });
  }

  // ================================================================ 参数解析

  /** 时间窗口。默认「最近 30 天」（含今天）。 */
  private record Window(LocalDate from, LocalDate to) {}

  private Window resolveWindow(LocalDate from, LocalDate to) {
    var end = to == null ? LocalDate.now() : to;
    var start = from == null ? end.minusDays(29) : from;
    if (start.isAfter(end)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "起始日期不能晚于结束日期（from=" + start + "、to=" + end + "）");
    }
    return new Window(start, end);
  }

  /** 空串 ⇒ null（不把空串当成一个真的筛选值）。 */
  private String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  // ==========================================================================
  // 能力声明的静态事实（**逐条对着上游的授权只读 API 核过**）
  // ==========================================================================

  /**
   * 本服务**真的读过**的上游模块。
   *
   * <p>⚠️ **不列没读过的**：能力声明是给前端判断「哪些块能画」的依据，
   * 列一个其实没接通的上游比不列更糟 —— 界面会以为功能已交付。
   */
  private static final List<String> UPSTREAM_MODULES =
      List.of(
          "2.1.1.1 / 2.1.1.3 平台与站点目录（channel /channel/platforms：站点币种、时区、国家）",
          "2.3.1.1 店铺列表（channel /channel/shops：店铺编码、名称、所属平台）",
          "2.3.1.2 店铺详情（channel /channel/shops/{id}：**该店铺启用了哪些站点**）",
          "9.1.1.1 销售订单列表与明细（trade /trade/sales-orders/list、/detail/{id}：金额、币种、下单时刻、**销量**）",
          "9.1.1.1 平台单列表（trade /trade/platform-orders/orders：**销售订单 → 店铺归属**的唯一来源）");

  /**
   * **确实拿不到**的部分（每条都写清原因，不写「未接入」这种空话）。
   */
  private static final List<String> NOT_DELIVERED =
      List.of(
          "🔴 **站点维度有归属边界**：trade 的销售订单与平台单**都不带站点**（只带 shop_code 与 platform_code），"
              + "站点归属只来自 channel 的 shop_marketplace ⇒ **一个店铺启用了多个站点时，"
              + "它的销量归不了任何站点**（摊给谁是造假）。本模块把这一部分**单列**成「无法唯一归属到站点」，"
              + "既不摊给任何站点、也不悄悄丢掉。店铺只启用一个站点时，销量才唯一归属到那个站点。",
          "🔴 **订单明细逐单取（有上限）**：**销量（件数）只存在于订单明细里**，而明细要逐单取（N+1），"
              + "所以一次刷新最多取 200 张单的明细。触到上限如实报「已截断」并在报表上给出**明细覆盖率**，"
              + "**不悄悄少算**。成交金额与订单数不受这个上限影响。",
          "**币种缺失的订单不计入任何币种**：订单币种不是 3 位大写码时，它的金额不能被算进任何一个币种"
              + "（算进某个币种是编造）。这类订单的条数在刷新结果里如实报出。",
          "**平台维度的「未标注平台」组**：channel 的店铺档案里平台为空串时（尚未确认归属），"
              + "该店铺归到「未标注平台」组 —— **绝不猜一个平台**。",
          "**新店 / 老店只按成交事实判定**：站点启用时间（channel 的 shop_marketplace.enabled_at）"
              + "只是旁证 —— 站点开了但一直没出单，在经营上仍然是新店。",
          "**站点级的币种与时区来自全局码表**：站点的默认币种 / 时区（channel 的 marketplace）"
              + "是**站点参数**，不是这家店实际成交用的币种 —— 实际成交币种以订单为准，两者可能不同。",
          "**利润 / 毛利维度不在本模块**：13.2.2.1 的职责是「销量与销售额的拆解」，"
              + "利润口径属于 13.4 利润分析（未交付），本模块不掺别的口径。",
          "**退货量不在本模块**：退货是 13.4 利润分析的事；本模块的「销量」是**成交**口径，不是净销量。");

  /** 判定规则与口径说明（阈值写清楚，页面上照着显示）。 */
  private static final List<ReportingStoreSiteModels.StoreSiteRuleNote> RULES =
      List.of(
          new ReportingStoreSiteModels.StoreSiteRuleNote(
              "DIMENSION_TREE",
              "🔴 **三个维度是同一棵树的三层**（平台 → 站点 → 店铺）：事实只落在**店铺层**，"
                  + "站点 / 平台维度在查询期按档案重分组 —— 这样同一个数不会有三个真相。"
                  + "站点码只在平台内唯一 ⇒ 站点维度键是「平台码:站点码」。"),
          new ReportingStoreSiteModels.StoreSiteRuleNote(
              "CURRENCY",
              "🔴 **绝不跨币种相加**：成交金额按币种分别列出。**只有件数与单数**（与货币无关）"
                  + "给出一个跨币种合计，它的币种显示为空串 —— 空串 = 与币种无关，不是「币种未知」。"),
          new ReportingStoreSiteModels.StoreSiteRuleNote(
              "SITE_ATTRIBUTION",
              "🔴 **站点归属边界**：订单不带站点 ⇒ 一个店铺启用多个站点时销量归不了任何站点，"
                  + "本模块**单列**「无法唯一归属到站点」，不摊给任何站点，"
                  + "也**不计入站点维度的占比分母**。"),
          new ReportingStoreSiteModels.StoreSiteRuleNote(
              "GROWTH_CONTRIBUTION",
              "**增长贡献度** = 该维度的增量 ÷ **全部维度的增量之和**。"
                  + "🔴 这就是蓝图关系句说的「增长贡献度比绝对值有用」—— "
                  + "它回答的是「这次的增量是哪个店带来的」。"
                  + "总增量为负时贡献度**照样是负数**（它回答的是「这次掉的量里各家各掉多少」）；"
                  + "总增量为 0 时算不出来（不是 0%）。"),
          new ReportingStoreSiteModels.StoreSiteRuleNote(
              "STORE_AGE",
              ReportingStoreSiteCatalog.STORE_AGE_RULE),
          new ReportingStoreSiteModels.StoreSiteRuleNote(
              "NOT_COMPARABLE",
              "🔴 **「算不出来」不等于 0**：基期缺失、基期为 0、事实不存在，"
                  + "一律返回「不可比 / 没有数据」，**不用 0 冒充** —— 假 0 会被读成「这个店没卖出去」。"),
          new ReportingStoreSiteModels.StoreSiteRuleNote(
              "DRILLDOWN",
              "可下钻：每个汇总数字都能钻到构成明细（哪张订单、哪张订单的哪一行、哪个 SKU）。"),
          new ReportingStoreSiteModels.StoreSiteRuleNote(
              "DATA_LATENCY",
              "数据延迟：报表是投影，不是实时的。页面上常驻显示**数据更新时间**"
                  + "（最近一次投影落库时刻）；空表时是 null（不是一个长得像时间的假值）。"
                  + "报表数字与业务系统冲突时，**以业务系统为准**。"));
}
