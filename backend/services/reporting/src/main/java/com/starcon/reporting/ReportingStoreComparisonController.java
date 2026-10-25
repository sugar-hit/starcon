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
 * 13.1.1.2 多店对比的 HTTP 端点。
 *
 * <p>命名空间是 {@code /reporting/store-comparison}：13.1.1.1 用的是 {@code /reporting/overview}，
 * 两个兄弟模块各有各的前缀，挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码：{@code reporting.store-comparison.read}（读对比结果）与
 * {@code reporting.projection.write}（刷新投影 —— 写的是**投影**，不是业务事实）。
 */
@RestController
@RequestMapping("/reporting/store-comparison")
@Tag(name = "13.1.1.2 多店对比")
public class ReportingStoreComparisonController {

  private final AccessService access;
  private final ReportingStoreComparisonService comparison;
  private final ReportingStoreComparisonRefreshService refresh;

  public ReportingStoreComparisonController(
      AccessService access,
      ReportingStoreComparisonService comparison,
      ReportingStoreComparisonRefreshService refresh) {
    this.access = access;
    this.comparison = comparison;
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
      summary = "能力声明：哪些上游真接通、哪些口径在店铺维度拿不到、判定规则是什么",
      description =
          "notDelivered 列出的是**确实拿不到**的部分，每条都写清原因与影响 —— "
              + "把「拿不到」伪装成 0 比说清楚更糟。")
  public ReportingStoreComparisonModels.ComparisonCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.store-comparison.read",
        actor ->
            new ReportingStoreComparisonModels.ComparisonCapabilities(
                true, true, true, UPSTREAM_MODULES, NOT_DELIVERED, RULES));
  }

  // ================================================================ 指标口径目录

  @GetMapping("/metrics")
  @Operation(summary = "店铺对比指标口径目录（口径唯一的展示形态）")
  public List<ReportingStoreComparisonModels.StoreMetricDefinitionView> metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.store-comparison.read",
        actor -> comparison.metricDefinitions(actor.tenantId()));
  }

  // ================================================================ 店铺清单

  @GetMapping("/shops")
  @Operation(
      summary = "店铺清单 + 平台清单（筛选器与「按平台分组」用）",
      description = "平台归属来自 channel 的广告活动 —— 没有任何广告活动的店铺平台留空，会被归到「未标注平台」组。")
  public ReportingStoreComparisonModels.StoreListResponse shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.store-comparison.read", actor -> comparison.stores(actor.tenantId()));
  }

  // ================================================================ 对比矩阵

  @GetMapping
  @Operation(
      summary = "多店对比：对比矩阵 + 排名 + 差异归因提示 + 掉队/增长店铺",
      description =
          "时间范围默认「最近 30 天」。对比指标的选择由服务端的口径表决定（可用 metricCodes 之外的指标"
              + "在 capabilities 里说明）。"
              + "⚠️ **跨平台对比会标注口径差异**：矩阵里同时给出平台侧成交金额与还原口径成交金额，"
              + "两者之差是「口径差异额」。"
              + "⚠️ **不跨币种相加**：金额按币种分别列出，排名也在币种内做。")
  public ReportingStoreComparisonModels.ComparisonResponse comparison(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String baseCurrency) {
    return call(
        auth,
        "reporting.store-comparison.read",
        actor -> {
          var window = resolveWindow(from, to);
          return comparison.comparison(
              actor.tenantId(),
              window.from(),
              window.to(),
              platformCode == null || platformCode.isBlank() ? null : platformCode.trim(),
              baseCurrency == null || baseCurrency.isBlank() ? null : baseCurrency.trim().toUpperCase());
        });
  }

  // ================================================================ 刷新（投影摄取）

  @PostMapping("/refresh")
  @Operation(
      summary = "从上游拉取店铺维度事实并投影（写的是投影，不是业务事实）",
      description =
          "上游：channel 的店铺清单 / 广告活动 / 广告报表，trade 的平台单与售后单。"
              + "⚠️ **广告指标是逐日拉的**（channel 的店铺分组没有日期桶），所以窗口长度有硬上限，"
              + "超了直接 400（不悄悄截断）。"
              + "⚠️ 上一等长窗口也会一起投影 —— 增长口径（窗口环比）要拿它当分母。")
  public List<ReportingStoreComparisonModels.StoreRefreshResult> refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
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
          var days = ReportingStoreComparisonRefreshService.windowDays(window.from(), window.to());
          if (days * 2 > ReportingStoreComparisonRefreshService.MAX_WINDOW_DAYS) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "对比窗口最多 "
                    + (ReportingStoreComparisonRefreshService.MAX_WINDOW_DAYS / 2)
                    + " 天（含环比所需的上一等长窗口，总共 "
                    + ReportingStoreComparisonRefreshService.MAX_WINDOW_DAYS
                    + " 天）：广告指标逐日拉取，窗口太长会把上游压垮。");
          }
          return refresh.refresh(actor.tenantId(), auth, window.from(), window.to(), zone);
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
          "9.1.1.1 平台单列表（trade platform-orders/orders：带 shop_code 的成交金额与订单量）",
          "11.1.1.1 售后单列表（trade aftersales/cases：按订单号回挂店铺的退货额）",
          "14.3.1.1 活动报表（channel ad-reports/rows?dimension=SHOP：店铺维度广告花费与广告销售额）",
          "14.1.1.1 广告活动列表（channel ad-campaigns/campaigns：店铺归属平台的唯一来源）",
          "14.3.1.1 报表筛选项（channel ad-reports/options：店铺清单与平台清单）");

  /**
   * **确实拿不到**的部分（每条都写清原因，不写「未接入」这种空话）。
   */
  private static final List<String> NOT_DELIVERED =
      List.of(
          "**店铺维度的毛利率与利润**：上游的成本分录（12.4.1.2 成本计算）与利润表"
              + "（12.5.2.2 财务报表）都**没有店铺维度**，只有法人 / 公司维度。"
              + "⇒ 店铺对比矩阵里没有毛利率这一列 —— 写一个公司维度的毛利率挂到店铺上就是造假。",
          "**店铺维度的库存周转**：上游在手库存（7.1.1.1 库存总览）按 SKU × 法人 × 仓，"
              + "**没有店铺维度**。⇒ 库存周转在店铺维度**不可得**，等上游补齐维度后同一张事实表"
              + "就能直接承接，不需要改结构。",
          "**手工销售订单不在店铺维度内**：店铺维度的成交金额来自**平台单**"
              + "（9.1.1.1 平台单列表），手工录入的销售订单（9.1.1.1 订单列表）没有店铺归属，"
              + "因此**不计入**店铺对比。店铺对比口径 = 平台单口径，与公司口径（订单列表）不是同一个数。",
          "**平台归属的空缺**：店铺的平台来自 channel 的广告活动。没有任何广告活动的店铺"
              + "拿不到平台归属，会被归到「未标注平台」组并标注口径说明 —— 不会猜一个平台。",
          "**退货额按订单号回挂**：售后单本身没有店铺维度，靠我方销售订单号回挂；"
              + "挂不上的退货单不计入任何店铺（刷新结果里会报出挂不上的条数）。");

  /** 判定规则（阈值写清楚，页面上照着显示）。 */
  private static final List<ReportingStoreComparisonModels.RuleNote> RULES =
      List.of(
          new ReportingStoreComparisonModels.RuleNote(
              "GROWTH",
              "增长店铺：平台侧成交金额的环比增长率 ≥ +20%。"
                  + "环比 = (当前窗口 − 上一等长窗口) ÷ 上一等长窗口；上一窗口为 0 或没有事实时不可比。"),
          new ReportingStoreComparisonModels.RuleNote(
              "LAGGARD",
              "掉队店铺：平台侧成交金额的环比增长率 ≤ −20%。"),
          new ReportingStoreComparisonModels.RuleNote(
              "STABLE",
              "稳定：环比增长率在 ±20% 之间。"),
          new ReportingStoreComparisonModels.RuleNote(
              "UNCLASSIFIED",
              "不可比：上一等长窗口没有该店铺的成交金额事实（或金额为 0），算不出环比增长率 —— "
                  + "不按 0% 处理。"),
          new ReportingStoreComparisonModels.RuleNote(
              "ATTRIBUTION",
              "差异归因提示：把该店铺的指标与**全体店铺中位数**逐项比（中位数随店铺集合变化，"
                  + "所以提示里写的是「中位数」而不是某个具体店铺的数字）。"),
          new ReportingStoreComparisonModels.RuleNote(
              "RANKING",
              "排名在**币种内**做：一个 USD 店铺和一个 EUR 店铺的大小本来就不可比。"));
}
