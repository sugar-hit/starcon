package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
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
 * 13.2.1.1 销量趋势的 HTTP 端点。
 *
 * <p>命名空间是 {@code /reporting/sales-trend}：13.1.1.1 用的是 {@code /reporting/overview}，
 * 13.1.1.2 用的是 {@code /reporting/store-comparison} —— 三个兄弟模块各有各的前缀，
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码：{@code reporting.sales-trend.read}（读趋势 / 对比 / 事件 / 口径 / 能力声明）与
 * {@code reporting.projection.write}（刷新投影 —— 写的是**投影**，不是业务事实）。
 */
@RestController
@RequestMapping("/reporting/sales-trend")
@Tag(name = "13.2.1.1 销量趋势")
public class ReportingSalesTrendController {

  /** 小时粒度下窗口最多多少天（小时桶太多会让图没法看）。 */
  static final int MAX_HOUR_WINDOW_DAYS = 7;

  private final AccessService access;
  private final ReportingSalesTrendService trend;
  private final ReportingSalesTrendRefreshService refresh;

  public ReportingSalesTrendController(
      AccessService access,
      ReportingSalesTrendService trend,
      ReportingSalesTrendRefreshService refresh) {
    this.access = access;
    this.trend = trend;
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
      summary = "能力声明：哪些上游真接通、哪些口径确实拿不到、异常怎么判定",
      description =
          "notDelivered 列出的是**确实拿不到**的部分，每条都写清原因与影响 —— "
              + "把「拿不到」伪装成 0 比说清楚更糟。")
  public ReportingSalesTrendModels.SalesTrendCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.sales-trend.read",
        actor ->
            new ReportingSalesTrendModels.SalesTrendCapabilities(
                true, true, true, true, true, UPSTREAM_MODULES, NOT_DELIVERED, RULES, GRANULARITIES,
                DIMENSIONS));
  }

  // ================================================================ 指标口径目录

  @GetMapping("/metrics")
  @Operation(summary = "销量趋势指标口径目录（口径唯一的展示形态）")
  public List<ReportingSalesTrendModels.SalesTrendMetricDefinitionView> metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.sales-trend.read", actor -> trend.metricDefinitions(actor.tenantId()));
  }

  // ================================================================ 趋势

  @GetMapping
  @Operation(
      summary = "销量趋势：趋势点列 + 环比 / 同比 + 异常日期标注",
      description =
          "时间范围默认「最近 30 天」。粒度支持日 / 周 / 月 / 小时（小时粒度下窗口最多 7 天）。"
              + "⚠️ **环比**比的是上一个同粒度桶，**同比**比的是去年同期；"
              + "基期缺失或为 0 时显示「不可比」，**不是 0%**。"
              + "⚠️ 金额按币种分别列出，**不跨币种相加**。"
              + "⚠️ 异常点会带上**关联事件**（大促 / 秒杀 / 缺货 / 断货）—— 没有事件的异常会明说「查不到原因」。")
  public ReportingSalesTrendModels.SalesTrendResponse trend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "DAY") String granularity,
      @RequestParam(required = false, defaultValue = "UTC") String timezone,
      @RequestParam(required = false) String metricCodes,
      @RequestParam(required = false) String baseCurrency) {
    return call(
        auth,
        "reporting.sales-trend.read",
        actor -> {
          var window = resolveWindow(from, to);
          var bucket = resolveGranularity(granularity);
          var zone = resolveZone(timezone);
          var days = ReportingSalesTrendRefreshService.windowDays(window.from(), window.to());
          if (bucket == ReportingSalesTrendGranularity.HOUR && days > MAX_HOUR_WINDOW_DAYS) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "小时粒度下窗口最多 " + MAX_HOUR_WINDOW_DAYS + " 天（当前 " + days + " 天）—— "
                    + "小时桶太多，图没法看。");
          }
          return trend.trend(
              actor.tenantId(),
              window.from(),
              window.to(),
              bucket,
              zone,
              splitCodes(metricCodes),
              normalizeCurrency(baseCurrency));
        });
  }

  // ================================================================ 多维对比

  @GetMapping("/comparison")
  @Operation(
      summary = "多维对比：同比 / 环比（TOTAL）、店铺间（STORE）、产品间（PRODUCT）",
      description =
          "dimension=TOTAL 比的是**上一等长窗口**与**去年同期窗口**；"
              + "dimension=STORE 是店铺间对比，dimension=PRODUCT 是产品间对比。"
              + "⚠️ 三个维度的口径**不可相加**（商品维度用订单明细行金额，店铺维度不含挂不上店铺的订单）。"
              + "⚠️ 金额按币种分别列出，排序在**币种内**做。")
  public ReportingSalesTrendModels.SalesTrendComparisonResponse comparison(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "DAY") String granularity,
      @RequestParam(required = false, defaultValue = "UTC") String timezone,
      @RequestParam(required = false, defaultValue = "TOTAL") String dimension,
      @RequestParam(required = false) String metricCodes,
      @RequestParam(required = false) String baseCurrency) {
    return call(
        auth,
        "reporting.sales-trend.read",
        actor -> {
          var window = resolveWindow(from, to);
          var bucket = resolveGranularity(granularity);
          var zone = resolveZone(timezone);
          var days = ReportingSalesTrendRefreshService.windowDays(window.from(), window.to());
          if (bucket == ReportingSalesTrendGranularity.HOUR && days > MAX_HOUR_WINDOW_DAYS) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "小时粒度下窗口最多 " + MAX_HOUR_WINDOW_DAYS + " 天（当前 " + days + " 天）。");
          }
          return trend.comparison(
              actor.tenantId(),
              window.from(),
              window.to(),
              bucket,
              zone,
              dimension,
              splitCodes(metricCodes),
              normalizeCurrency(baseCurrency));
        });
  }

  // ================================================================ 异常事件

  @GetMapping("/events")
  @Operation(
      summary = "异常日期标注：大促 / 秒杀 / 缺货 / 断货事件",
      description =
          "断货与缺货事件来自上游的**断货风险快照** —— 上游还没算过快照时这里就是空的"
              + "（是「上游没有」，不是「读不到」）。"
              + "事件覆盖的日期按**本次请求的时区**换算。")
  public ReportingSalesTrendModels.SalesTrendEventResponse events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "UTC") String timezone,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String skuCode) {
    return call(
        auth,
        "reporting.sales-trend.read",
        actor -> {
          var window = resolveWindow(from, to);
          return trend.events(
              actor.tenantId(),
              window.from(),
              window.to(),
              resolveZone(timezone),
              shopCode == null ? null : shopCode.trim(),
              skuCode == null ? null : skuCode.trim());
        });
  }

  // ================================================================ 刷新（投影摄取）

  @PostMapping("/refresh")
  @Operation(
      summary = "从上游拉取销量事实并投影（写的是投影，不是业务事实）",
      description =
          "上游：trade 的销售订单与订单明细、trade 的平台单（店铺归属）、"
              + "channel 的促销与秒杀、trade 的断货风险。"
              + "⚠️ 一次刷新会投**三个窗口**：当前 / 上一等长 / 去年同期 —— "
              + "没有基期，环比与同比就**算不出来**（显示「不可比」，不是 0%）。"
              + "⚠️ 订单明细要逐单取（有上限），超出的部分**未计入销量与商品维度**，刷新结果里会报出截断。")
  public List<ReportingSalesTrendModels.SalesTrendRefreshResult> refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "DAY") String granularity,
      @RequestParam(required = false, defaultValue = "UTC") String timezone) {
    return call(
        auth,
        "reporting.projection.write",
        actor -> {
          var window = resolveWindow(from, to);
          var days = ReportingSalesTrendRefreshService.windowDays(window.from(), window.to());
          if (days > ReportingSalesTrendRefreshService.MAX_WINDOW_DAYS) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "窗口最多 " + ReportingSalesTrendRefreshService.MAX_WINDOW_DAYS + " 天（当前 " + days + " 天）—— "
                    + "一次刷新要投当前 / 上一等长 / 去年同期三个窗口，窗口太长会把上游压垮。");
          }
          return refresh.refresh(
              actor.tenantId(),
              auth,
              window.from(),
              window.to(),
              resolveGranularity(granularity),
              resolveZone(timezone));
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

  private ReportingSalesTrendGranularity resolveGranularity(String value) {
    var granularity = ReportingSalesTrendGranularity.of(value);
    if (granularity == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "粒度只能是 DAY / WEEK / MONTH / HOUR：" + value);
    }
    return granularity;
  }

  private ZoneId resolveZone(String value) {
    try {
      return ZoneId.of(value == null || value.isBlank() ? "UTC" : value.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时区无法解析：" + value);
    }
  }

  private List<String> splitCodes(String value) {
    if (value == null || value.isBlank()) return List.of();
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(code -> !code.isEmpty())
        .toList();
  }

  private String normalizeCurrency(String value) {
    if (value == null || value.isBlank()) return null;
    var normalized = value.trim().toUpperCase();
    if (!normalized.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必须是三位大写字母：" + value);
    }
    return normalized;
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
          "9.1.1.1 销售订单列表（trade sales-orders/list：金额、币种、下单时刻）",
          "9.1.1.1 销售订单明细（trade sales-orders/detail/{id}：销量与 SKU 维度的构成明细）",
          "9.1.1.1 平台单列表（trade platform-orders/orders：用 salesOrderId 把订单回挂到店铺）",
          "14.4.1.1 促销列表（channel ad-promotions/promotions：大促事件）",
          "14.4.2.1 秒杀列表（channel ad-flash-sales/flash-sales：秒杀事件）",
          "6.2.1.1 缺货与断货风险（trade stockout-risk/items：断货日 / 缺货日事件）");

  /**
   * **确实拿不到**的部分（每条都写清原因，不写「未接入」这种空话）。
   */
  private static final List<String> NOT_DELIVERED =
      List.of(
          "**「价格调整」事件**：关系句点名的三类事件里，「价格调整」没有上游事件源 —— "
              + "价格变更在 5 定价服务，本仓没有可读的事件端点。"
              + "⇒ 异常点上只有大促 / 秒杀 / 缺货 / 断货四类关联，**不会编一条价格调整出来**。",
          "**按店铺时区分桶**：上游的订单时刻带的是**绝对时刻**，店铺各自的时区在 channel 的店铺档案里，"
              + "本模块没有读它（13.2.1.2 分时与分周模式才是按店铺时区的那一格）。"
              + "⇒ 本模块的日 / 周 / 月 / 小时分桶**统一按调用方指定的时区**算，"
              + "并在响应里显示所用时区 —— **不假装每个店铺都按自己的时区统计过**。",
          "**SPU 口径的商品维度**：订单明细行只有 SKU 码，SPU 合并要商品主数据（3）。"
              + "⇒ 本模块的商品维度是 **SKU 口径**；SPU 汇总是 13.2.2.2 的职责。",
          "**店铺维度的销量**：平台单只带订单金额，不带 SKU 与数量 ⇒ 店铺维度只有成交金额与订单量，"
              + "**没有销量**（口径表里就没有这个指标码，不给公式也不给分母）。",
          "**超过明细取数上限的订单**：销量与商品维度要逐单取订单明细（有上限），"
              + "超出的部分**未计入** —— 刷新结果里会标 TRUNCATED 并写清条数，"
              + "**不悄悄少算**。");

  /** 判定规则（阈值写清楚，页面上照着显示）。 */
  private static final List<ReportingSalesTrendModels.SalesTrendRuleNote> RULES =
      List.of(
          new ReportingSalesTrendModels.SalesTrendRuleNote(
              "ANOMALY",
              "异常判定：某个桶的值相对**同序列窗口内中位数**的偏离 ≥ ±50% ⇒ 判成异常"
                  + "（SPIKE 偏高 / DIP 偏低）。中位数是稳健基线，不会被一两个尖峰带偏。"
                  + "基数为 0 或该桶没有事实时**不算异常**。"),
          new ReportingSalesTrendModels.SalesTrendRuleNote(
              "EVENT_LINK",
              "异常必须能关联事件：异常点会列出落在该桶里的**大促 / 秒杀 / 缺货 / 断货**事件；"
                  + "一个事件都没关联上时，明说「**查不到原因**」—— "
                  + "否则图表只是曲线没有洞察（蓝图关系句的加粗要求）。"),
          new ReportingSalesTrendModels.SalesTrendRuleNote(
              "PERIOD_OVER_PERIOD",
              "环比基准：趋势点比的是**上一个同粒度桶**（日看昨天、周看上周、月看上月）；"
                  + "对比矩阵比的是**上一等长窗口**（按桶等长，不是按天等长 —— "
                  + "月粒度下必须比等数量的月桶）。基期缺失或为 0 ⇒ **算不出**（不是 0%）。"),
          new ReportingSalesTrendModels.SalesTrendRuleNote(
              "YEAR_OVER_YEAR",
              "同比基准：**去年同期**的同一个桶 / 同一个窗口。"
                  + "基期缺失或为 0 ⇒ **算不出**（不是 0%）。"),
          new ReportingSalesTrendModels.SalesTrendRuleNote(
              "CURRENCY",
              "金额**按币种分别列出**，**绝不跨币种相加**；给了本位币时按各币种最近一条生效汇率折算，"
                  + "并把**汇率与时点**一起返回（今天的汇率变了不会让上月的报表变）。"
                  + "没有生效汇率时**不做折算**（绝不按 1:1 冒充）。"),
          new ReportingSalesTrendModels.SalesTrendRuleNote(
              "DIMENSION",
              "三个维度的口径**不可相加**：公司维度用订单总额，商品维度用订单明细行金额"
                  + "（订单级折扣 / 运费 / 税不摊到行），店铺维度不含挂不上店铺的订单。"
                  + "指标与维度必须对得上（口径表里登记了维度），对不上就是口径错位。"));

  /** 可用粒度。 */
  private static final List<String> GRANULARITIES = List.of("DAY", "WEEK", "MONTH", "HOUR");

  /** 可用维度。 */
  private static final List<String> DIMENSIONS = List.of("TOTAL", "STORE", "PRODUCT");
}
