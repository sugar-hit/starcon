package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * 13.3.1.1 产品表现分析的 HTTP 端点。
 *
 * <p>命名空间是 {@code /reporting/product-performance}：13.2.2.2 用的是
 * {@code /reporting/product-sales}，两个兄弟模块各有各的前缀，
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码：{@code reporting.product-performance.read}（读 360 度分析页）与
 * {@code reporting.projection.write}（刷新投影 —— 写的是**投影**，不是业务事实）。
 * 本模块**没有任何「创建业务事实」的权限码**（reporting 是投影服务）。
 */
@RestController
@RequestMapping("/reporting/product-performance")
@Tag(name = "13.3.1.1 产品表现分析")
public class ReportingProductPerformanceController {

  private final AccessService access;
  private final ReportingProductPerformanceService performance;
  private final ReportingProductPerformanceRefreshService refresh;

  public ReportingProductPerformanceController(
      AccessService access,
      ReportingProductPerformanceService performance,
      ReportingProductPerformanceRefreshService refresh) {
    this.access = access;
    this.performance = performance;
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
      summary = "能力声明：哪些上游真接通、哪些指标本轮不可用（归谁管）、判定规则是什么",
      description =
          "notDelivered 列出的是**确实拿不到**的部分，每条都写清原因与归属模块 —— "
              + "把「拿不到」伪装成 0 比说清楚更糟。")
  public ReportingProductPerformanceModels.ProductPerformanceCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.product-performance.read",
        actor ->
            new ReportingProductPerformanceModels.ProductPerformanceCapabilities(
                true, true, true, UPSTREAM_MODULES, NOT_DELIVERED, RULES));
  }

  // ================================================================ 指标口径目录

  @GetMapping("/metrics")
  @Operation(summary = "360 度指标口径目录（口径唯一的展示形态）")
  public List<ReportingProductPerformanceModels.PerformanceMetricDefinitionView> metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.product-performance.read",
        actor -> performance.metricDefinitions(actor.tenantId()));
  }

  // ================================================================ 360 度分析页

  @GetMapping
  @Operation(
      summary = "单产品 360 度分析：销量 / 销售额 / 毛利 / 流量 / 转化 / 退货 / 库存 / 库龄 / 广告 / 竞品",
      description =
          "🔴 **一次性拉齐**：一次调用返回全部指标 + 趋势 + 排名分位 + 环比基线 —— "
              + "页面滚动看完，不用来回跳，也不做瀑布式串行请求。"
              + "⚠️ **绝不跨币种相加**：金额按币种分别列出；只有件数与次数给跨币种合计。"
              + "⚠️ **「算不出来」与「等于 0」分开**：分母为 0 或没有事实时是「不可比」/「没有数据」，不是 0。"
              + "基线默认「上一个等长周期」（可显式给 baselineFrom / baselineTo）。")
  public ReportingProductPerformanceModels.ProductPerformanceResponse analyze(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate baselineFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate baselineTo,
      @RequestParam(required = false, defaultValue = "SKU") String dimensionType,
      @RequestParam String dimensionKey,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "reporting.product-performance.read",
        actor -> {
          var window = resolveWindow(from, to);
          var level = normalizeLevel(dimensionType);
          var baseline = resolveBaseline(window, baselineFrom, baselineTo);
          return performance.analyze(
              actor.tenantId(),
              window.from(),
              window.to(),
              baseline.from(),
              baseline.to(),
              level,
              dimensionKey,
              blankToNull(currency));
        });
  }

  // ================================================================ 刷新（投影摄取）

  @PostMapping("/refresh")
  @Operation(
      summary = "从上游拉取 360 度事实并投影（写的是投影，不是业务事实）",
      description =
          "上游：channel 的平台表现 / 跟卖记录 / 广告活动报表与投放商品、trade 的售后单与明细、"
              + "finance 的成本分录与批次、warehouse 的库存余额与库龄。"
              + "🔴 **基线窗口与当期窗口一起刷**：一次刷新就能看到环比 —— "
              + "只刷当期窗口的话，环比那一列永远是「没有数据」。")
  public List<ReportingProductPerformanceModels.ProductPerformanceRefreshResult> refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate baselineFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate baselineTo,
      @RequestParam(required = false, defaultValue = "UTC") String timezone) {
    ZoneId zone;
    try {
      zone = ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时区无法解析：" + timezone);
    }
    return call(
        auth,
        "reporting.projection.write",
        actor -> {
          var window = resolveWindow(from, to);
          var baseline = resolveBaseline(window, baselineFrom, baselineTo);
          var span =
              java.time.temporal.ChronoUnit.DAYS.between(baseline.from(), window.to()) + 1;
          if (span > ReportingProductPerformanceRefreshService.MAX_WINDOW_DAYS) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "360 度窗口（基线 + 当期）最多 "
                    + ReportingProductPerformanceRefreshService.MAX_WINDOW_DAYS
                    + " 天：售后单要逐单取明细，窗口太长会把上游压垮。");
          }
          return refresh.refresh(
              actor.tenantId(),
              auth,
              window.from(),
              window.to(),
              baseline.from(),
              baseline.to(),
              zone);
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

  /** 基线窗口。默认「上一个等长周期」。 */
  private Window resolveBaseline(Window window, LocalDate baselineFrom, LocalDate baselineTo) {
    if (baselineFrom != null && baselineTo != null) {
      if (baselineFrom.isAfter(baselineTo)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "基线起始日期不能晚于基线结束日期。");
      }
      return new Window(baselineFrom, baselineTo);
    }
    var days = java.time.temporal.ChronoUnit.DAYS.between(window.from(), window.to()) + 1;
    return new Window(window.from().minusDays(days), window.from().minusDays(1));
  }

  /** 维度层（SKU / SPU / LISTING）。非法取值 ⇒ 400（不静默回落成 SKU）。 */
  private String normalizeLevel(String level) {
    if (level == null || level.isBlank()) return "SKU";
    var normalized = level.trim().toUpperCase();
    if (!normalized.equals("SKU") && !normalized.equals("SPU") && !normalized.equals("LISTING")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "维度层只能是 SKU / SPU / LISTING（收到的是「" + level + "」）");
    }
    return normalized;
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
          "9 订单（trade /trade/sales-orders/list + /detail/{id}）：13.2.2.2 已投影的销量 / 销售额 / 订单数",
          "9 售后（trade /trade/aftersales/cases + /cases/{id}）：退货件数与退货金额（按 SKU 归集）",
          "12.4.1.2 成本（finance /finance/cost-calculation/entries + /runs）：SKU 级出库成本（成本法）",
          "7 库存（warehouse /warehouse/inventory/overview）：在手 / 预留 / 可用数量（时点）",
          "7 库存库龄（warehouse /warehouse/inventory/aging/slow-moving）：库龄天数与档位（时点）",
          "4.5.1.1 平台表现（channel /channel/listing-performance）：曝光 / 点击 / Session / 平台口径销量与销售额",
          "4.5.2.1 跟卖（channel /channel/listing-hijackers/records）：跟卖价格与自家参考售价（参考指标）",
          "14.3.1.1 广告活动报表（channel /channel/ad-reports/rows）+ 14.1.1.2 投放商品"
              + "（/channel/ad-campaign-details/campaigns/{id}/products）：活动级花费 / 曝光 / 点击 / 订单 / 销售额");

  /**
   * **确实拿不到**的部分（每条都写清原因与归属模块，不写「未接入」这种空话）。
   */
  private static final List<String> NOT_DELIVERED =
      List.of(
          "🔴 **商品维度 ACOS / ROAS 本轮不可用**（归 **14.3.1.3 商品维度广告效果** / **14.3.2.1 归因**）："
              + "channel 的广告报表只有活动 / 平台 / 店铺 / 广告类型 / 币种五种分组维度，"
              + "**没有 SKU / SPU / Listing 维度**；广告归因（末次点击 / 首次点击）也未交付。"
              + "本模块给出的是**活动级**花费，**不换算**成商品级 ACOS / ROAS —— 换算就是编造归因。",
          "🔴 **毛利不等于 13.4 多模式利润**：本模块的毛利 = 销售额 − 出库成本，"
              + "出库成本取 **12.4.1.2 的成本法出库成本**，**不含**平台费 / 头程 / 物流费 / 广告费等成本项。"
              + "要按经营模式（自发货 / 全托管 / 半托管 / 平台仓）拆利润，得等 13.4.1.1。",
          "🔴 **库存金额算不出来**：warehouse 的库存事实层**没有任何成本列**（已 grep 全仓确认），"
              + "所以「库存占用了多少钱」这一格是**没有数据**，**不是 0 元**。",
          "🔴 **竞品对比只是参考指标**：跟卖价格来自 4.5.2.1，自家参考售价是**人工维护**的"
              + "（非平台权威价）；**竞品数据不进自有商品模型**，只在这一段里并列展示。",
          "🔴 **Listing 层的订单侧指标只算「可唯一归属」的那部分**：trade 的订单明细**只带 SKU 码**，"
              + "同一款内部 SKU 挂在多个 Listing 上时，它的销量 / 成本 / 退货**无法唯一归属**到某个 Listing。"
              + "本模块**不摊给任何 Listing**（摊给谁都是造假）。",
          "🔴 **广告活动的投放商品有张数上限**：活动 ↔ Listing 的绑定只能**逐活动取**，"
              + "触到上限时如实报「已截断」，没取到绑定的活动其花费**不摊给任何商品**。",
          "**成本批次重算的边界**：同一条出库流水被重算过时会产生新批次的新分录，"
              + "本服务按「同一流水取最新批次那一版」去重；找不到所属批次的分录会**原样保留**并在刷新结果里报出。",
          "**商品档案依赖 13.2.2.2**：本模块**只读** 13.2.2.2 的商品维度投影（SKU / SPU / Listing 三层档案"
              + "与 Listing ↔ SKU 绑定）。它没刷新过 ⇒ 本模块的刷新会报「没有商品档案」，"
              + "而不是凭空造一个商品。");

  /** 判定规则与口径说明（阈值写清楚，页面上照着显示）。 */
  private static final List<ReportingProductPerformanceModels.PerformanceRuleNote> RULES =
      List.of(
          new ReportingProductPerformanceModels.PerformanceRuleNote(
              "ONE_CALL",
              "🔴 **一次性拉齐**：一次调用返回全部指标 + 趋势 + 排名分位 + 环比基线 —— "
                  + "页面滚动看完，不用来回跳，也不做瀑布式串行请求。"),
          new ReportingProductPerformanceModels.PerformanceRuleNote(
              "BASELINE",
              "基线默认「**上一个等长周期**」（PREVIOUS_PERIOD），可显式给 baselineFrom / baselineTo。"
                  + "变化量 = 本期 − 基线；变化率 = 变化量 ÷ |基线|，**基线为 0 时算不出来**"
                  + "（「上期是 0」与「本期没变」是两件事）。"),
          new ReportingProductPerformanceModels.PerformanceRuleNote(
              "RANKING",
              "排名分位按**销量（件数）**排：金额跨币种不可比，用金额排会让不同币种的「第一名」混在一起。"
                  + "同层级里**没有事实的商品不进榜**（数了它，排名分母会把「没数据」当成「表现差」）。"
                  + "分位 = (总数 − 名次 + 1) ÷ 总数（第一名 100%）。"),
          new ReportingProductPerformanceModels.PerformanceRuleNote(
              "NO_DOUBLE_COUNT",
              "🔴 **维度不重复计数**：订单侧指标（销量 / 成本 / 退货）在 SPU 层 = 名下 SKU 之和，"
                  + "在 Listing 层 = **可唯一归属**的那些 SKU 之和；"
                  + "平台侧指标（流量 / 转化 / 广告 / 跟卖）原生是 Listing 粒度，"
                  + "在 SKU / SPU 层按**去重后**的 Listing 求和（同一个 Listing 挂多个 SKU 时**只算一次**）。"),
          new ReportingProductPerformanceModels.PerformanceRuleNote(
              "CURRENCY",
              "🔴 **绝不跨币种相加**：金额按币种分别列出；只有件数与次数（与货币无关）"
                  + "给一个跨币种合计，它的币种显示为「与币种无关」。"),
          new ReportingProductPerformanceModels.PerformanceRuleNote(
              "NO_ZERO_FAKE",
              "🔴 **「算不出来」与「等于 0」分开**：分母为 0 / 没有事实 / 上游未交付，"
                  + "分别显示为「不可比」/「没有数据」/「本轮不可用（归谁管）」，**绝不用 0 冒充**。"),
          new ReportingProductPerformanceModels.PerformanceRuleNote(
              "DATA_LATENCY",
              "数据延迟：报表是投影，不是实时的。页面上常驻显示**数据更新时间**"
                  + "（最近一次投影落库时刻）；空表时是 null（不是一个长得像时间的假值）。"
                  + "报表数字与业务系统冲突时，**以业务系统为准**。"));
}
