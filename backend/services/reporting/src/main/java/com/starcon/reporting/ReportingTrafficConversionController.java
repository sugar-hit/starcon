package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * 13.3.2.1 流量转化的 HTTP 端点。
 *
 * <p>命名空间是 {@code /reporting/traffic-conversion}：13.2.2.1 用的是
 * {@code /reporting/store-site}，13.2.2.2 用的是 {@code /reporting/product-sales}，
 * 兄弟模块各有各的前缀，挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码：{@code reporting.traffic-conversion.read}（读流量转化报表）与
 * {@code reporting.projection.write}（刷新投影 —— 写的是**投影**，不是业务事实）。
 * 本模块**没有任何「创建业务事实」的权限码**（reporting 是投影服务）。
 */
@RestController
@RequestMapping("/reporting/traffic-conversion")
@Tag(name = "13.3.2.1 流量转化")
public class ReportingTrafficConversionController {

  private final AccessService access;
  private final ReportingTrafficConversionService service;
  private final ReportingTrafficConversionRefreshService refresh;

  public ReportingTrafficConversionController(
      AccessService access,
      ReportingTrafficConversionService service,
      ReportingTrafficConversionRefreshService refresh) {
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
      summary = "能力声明：哪些上游真接通、哪些口径拿不到、判定规则与诊断阈值是什么",
      description =
          "notDelivered 列出的是**确实拿不到**的部分，每条都写清原因与影响 —— "
              + "把「拿不到」伪装成 0 比说清楚更糟。")
  public ReportingTrafficConversionModels.TrafficConversionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.traffic-conversion.read",
        actor ->
            new ReportingTrafficConversionModels.TrafficConversionCapabilities(
                true,
                true,
                true,
                true,
                true,
                true,
                UPSTREAM_MODULES,
                NOT_DELIVERED,
                RULES,
                List.of(
                    ReportingTrafficConversionCatalog.IMPRESSIONS,
                    ReportingTrafficConversionCatalog.CLICKS,
                    ReportingTrafficConversionCatalog.SESSIONS,
                    ReportingTrafficConversionCatalog.PLATFORM_UNITS,
                    ReportingTrafficConversionCatalog.PLATFORM_SALES_AMOUNT,
                    ReportingTrafficConversionCatalog.AD_IMPRESSIONS,
                    ReportingTrafficConversionCatalog.AD_CLICKS,
                    ReportingTrafficConversionCatalog.AD_ORDERS,
                    ReportingTrafficConversionCatalog.AD_SPEND,
                    ReportingTrafficConversionCatalog.AD_SALES_AMOUNT,
                    ReportingTrafficConversionCatalog.ORDER_COUNT,
                    ReportingTrafficConversionCatalog.ORDER_AMOUNT,
                    ReportingTrafficConversionCatalog.CTR,
                    ReportingTrafficConversionCatalog.CONVERSION_RATE,
                    ReportingTrafficConversionCatalog.PLATFORM_AOV,
                    ReportingTrafficConversionCatalog.AD_CTR,
                    ReportingTrafficConversionCatalog.AD_CONVERSION_RATE,
                    ReportingTrafficConversionCatalog.AD_CPC,
                    ReportingTrafficConversionCatalog.AD_AOV,
                    ReportingTrafficConversionCatalog.ORDER_AOV,
                    ReportingTrafficConversionCatalog.ORGANIC_IMPRESSIONS,
                    ReportingTrafficConversionCatalog.ORGANIC_CLICKS),
                List.of("DAY", "WEEK", "MONTH")));
  }

  // ================================================================ 指标口径目录

  @GetMapping("/metrics")
  @Operation(summary = "流量转化的指标口径目录（口径唯一的展示形态）")
  public List<ReportingTrafficConversionModels.TrafficMetricDefinitionView> metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.traffic-conversion.read",
        actor -> service.metricDefinitions(actor.tenantId()));
  }

  // ================================================================ 主报表

  @GetMapping
  @Operation(
      summary = "流量转化：漏斗视图 + 关键指标 + 转化率趋势 + 异常诊断",
      description =
          "时间范围默认「最近 30 天」。🔴 **平台流量数据与内部订单数据口径不同**，"
              + "本接口把两套口径**分列返回**、**绝不混算**：平台口径（页面口径）与订单口径"
              + "各带自己的标签。🔴 **自然流量是推导值**（平台商品口径 − 广告口径），"
              + "相减为负时报「口径冲突」，绝不截成 0。🔴 **订单口径的转化率算不出来**"
              + "（内部订单没有 Session）—— 那一格是「不可比」，不是 0%。"
              + "🔴 **绝不跨币种相加**：金额按币种分别报。")
  public ReportingTrafficConversionModels.TrafficConversionResponse report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false, defaultValue = "DAY") String granularity) {
    return call(
        auth,
        "reporting.traffic-conversion.read",
        actor -> {
          var window = resolveWindow(from, to);
          var grain = ReportingSalesTrendGranularity.of(granularity);
          // ⚠️ HOUR 是共享枚举里的合法值，但本模块的事实是**按日**落的 ⇒ 不给「小时」粒度
          //   （给了也是假精度：桶里还是整天的量）。
          if (grain == null || grain == ReportingSalesTrendGranularity.HOUR) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "粒度只认 DAY / WEEK / MONTH（本模块的事实按日落，给不了小时粒度；收到："
                    + granularity
                    + "）");
          }
          return service.report(actor.tenantId(), window.from(), window.to(), grain.name());
        });
  }

  // ================================================================ 构成明细下钻

  @GetMapping("/drilldown")
  @Operation(
      summary = "构成明细下钻：某个来源、某个指标的构成明细（哪个 listing / 哪个广告活动 / 哪张订单）",
      description =
          "卷 13 卷首加粗句「可下钻」的落点：每个汇总数字都能钻到构成明细。"
              + "🔴 明细行带**来源口径**（LISTING / AD / ORDER）与**统计日** —— "
              + "平台口径是平台自然日，订单口径是 UTC 自然日。")
  public ReportingTrafficConversionModels.TrafficDrilldownResponse drilldown(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String trafficSource,
      @RequestParam(required = false) String metricCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.traffic-conversion.read",
        actor -> {
          var window = resolveWindow(from, to);
          return service.drilldown(
              actor.tenantId(),
              window.from(),
              window.to(),
              blankToNull(trafficSource),
              blankToNull(metricCode),
              page,
              size);
        });
  }

  // ================================================================ 刷新（投影摄取）

  @PostMapping("/refresh")
  @Operation(
      summary = "从上游拉取流量与转化数据并投影（写的是投影，不是业务事实）",
      description =
          "上游：channel 的平台表现快照（4.5.1.1：曝光 / 点击 / Session / 平台销量 / 平台销售额）、"
              + "channel 的广告活动报表（14.3.1.1：广告曝光 / 点击 / 订单 / 花费 / 销售额）、"
              + "trade 的销售订单列表（9.1.1.1：订单笔数与订单金额）。"
              + "⚠️ 三路数据**分属不同口径**：平台口径（平台自然日）与订单口径（UTC 自然日）。"
              + "⚠️ 刷新会**重建**该窗口（先删旧行再整段重写）—— 平台修正同一天报表时旧行不会残留。")
  public List<ReportingTrafficConversionModels.TrafficRefreshResult> refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.projection.write",
        actor -> {
          var window = resolveWindow(from, to);
          var days =
              ReportingTrafficConversionRefreshService.windowDays(window.from(), window.to());
          if (days > ReportingTrafficConversionRefreshService.MAX_WINDOW_DAYS) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "流量转化窗口最多 "
                    + ReportingTrafficConversionRefreshService.MAX_WINDOW_DAYS
                    + " 天：上游要逐页拉平台表现与广告报表，窗口太长会把上游压垮。");
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
          "2.1.2.1 平台表现快照（channel /channel/listing-performance：曝光、点击、Session、平台销量、平台销售额、**平台自然日**）",
          "14.3.1.1 广告活动报表（channel /channel/ad-reports/rows?dimension=CAMPAIGN&granularity=DAY：广告曝光、点击、订单、花费、销售额）",
          "9.1.1.1 销售订单列表（trade /trade/sales-orders/list：订单笔数、订单金额、币种、下单时刻）");

  /**
   * **确实拿不到**的部分（每条都写清原因，不写「未接入」这种空话）。
   */
  private static final List<String> NOT_DELIVERED =
      List.of(
          "🔴 **平台没有按流量来源拆分的流量口径**：channel 的平台表现（listing 级）与广告报表"
              + "（活动级）是**两套独立报表**，统计范围不同。本模块的「自然流量」是两者相减的"
              + "**推导值**（残余），**不是平台给出的自然流量**；相减为负时该格报「口径冲突」，"
              + "**绝不截成 0**。",
          "🔴 **自然 Session 算不出来**：广告侧没有 Session 口径 ⇒ 自然流量的漏斗只有"
              + "曝光与点击两节，Session 那一格是「不可比」，**不是 0**。",
          "🔴 **订单口径的转化率算不出来**：内部订单不带 Session ⇒ 分母缺失**不是 0**，"
              + "那一格显示「不可比」。",
          "🔴 **广告口径拿不到店铺码**：上游按活动分组时只给店铺**名** ⇒ 广告口径的事实行"
              + "**不带店铺码**（空 = 上游没给，**绝不猜一个**）。",
          "🔴 **平台口径与订单口径的统计日不是一个日**：平台用**平台自然日**，"
              + "内部订单用 **UTC 自然日** ⇒ 跨口径比较时必须知道这件事。",
          "**币种缺失的金额不落库**：平台没给币种的金额 / 内部订单缺币种的金额**不计入任何币种**"
              + "（算进某个币种是编造）；这类行的条数在刷新结果里如实报出。",
          "**归因不在本模块**：本模块的「广告成交订单」是**平台侧归因到广告活动**的订单，"
              + "不做末次点击 / 首次点击归因（那是 13.3.2.2 关联营销效果的事）。",
          "**分页只在下钻上**：主报表一次返回整个窗口（窗口上限 92 天 ⇒ 趋势最多 92 个点）；"
              + "构成明细是真分页（总数 + limit/offset）。");

  /** 判定规则与口径说明（阈值写清楚，页面上照着显示）。 */
  private static final List<ReportingTrafficConversionModels.TrafficRuleNote> RULES =
      List.of(
          new ReportingTrafficConversionModels.TrafficRuleNote(
              "CALIBER_SEPARATION",
              "🔴 **两套口径分列、不混算**：平台口径（页面口径）与订单口径（统计范围不同）"
                  + "各有各的指标；转化率有两个口径、客单价有三个口径，**每个都带自己的标签**。"),
          new ReportingTrafficConversionModels.TrafficRuleNote(
              "ORGANIC_DERIVED",
              "🔴 **自然流量是推导值**：平台商品口径 − 广告口径。相减为负说明两套口径统计范围"
                  + "不一致，该格报「口径冲突」，**绝不截成 0**。"),
          new ReportingTrafficConversionModels.TrafficRuleNote(
              "NOT_COMPARABLE",
              "🔴 **「算不出来」不等于 0**：分母缺失、分母为 0、事实不存在，"
                  + "一律返回「不可比 / 没有数据」，**不用 0 冒充** —— 假 0 会被读成「这段时间没流量」。"),
          new ReportingTrafficConversionModels.TrafficRuleNote(
              "CURRENCY",
              "🔴 **绝不跨币种相加**：金额按币种分别列出；只有曝光 / 点击 / Session / 笔数"
                  + "这类与货币无关的量给一个跨币种合计，它的币种显示为空串 —— "
                  + "空串 = 与币种无关，不是「币种未知」。"),
          new ReportingTrafficConversionModels.TrafficRuleNote(
              "BUCKET_BASIS",
              "🔴 **统计日的日历口径也是口径**：平台口径按**平台自然日**，订单口径按 **UTC 自然日**。"),
          new ReportingTrafficConversionModels.TrafficRuleNote(
              "DIAGNOSIS_IMPRESSION_COLLAPSE",
              "异常诊断 · "
                  + ReportingTrafficConversionCatalog.RULE_IMPRESSION_COLLAPSE
                  + "：曝光环比下降超过 "
                  + ReportingTrafficConversionCatalog.IMPRESSION_COLLAPSE_THRESHOLD_NOTE
                  + "（HIGH）。"),
          new ReportingTrafficConversionModels.TrafficRuleNote(
              "DIAGNOSIS_CTR_DROP",
              "异常诊断 · "
                  + ReportingTrafficConversionCatalog.RULE_CTR_DROP
                  + "：点击率环比下降超过 "
                  + ReportingTrafficConversionCatalog.CTR_DROP_THRESHOLD_NOTE
                  + "（MEDIUM）。"),
          new ReportingTrafficConversionModels.TrafficRuleNote(
              "DIAGNOSIS_CONVERSION_RATE_DROP",
              "异常诊断 · "
                  + ReportingTrafficConversionCatalog.RULE_CONVERSION_RATE_DROP
                  + "：转化率环比下降超过 "
                  + ReportingTrafficConversionCatalog.CONVERSION_RATE_DROP_THRESHOLD_NOTE
                  + "（MEDIUM）。"),
          new ReportingTrafficConversionModels.TrafficRuleNote(
              "DIAGNOSIS_SESSION_CLICK_GAP",
              "异常诊断 · "
                  + ReportingTrafficConversionCatalog.RULE_SESSION_CLICK_GAP
                  + "：同一统计日内点击数大于 Session 数 ⇒ 平台这两项的统计范围不一致（**口径异常**）。"),
          new ReportingTrafficConversionModels.TrafficRuleNote(
              "DIAGNOSIS_CALIBER_GAP",
              "异常诊断 · "
                  + ReportingTrafficConversionCatalog.RULE_CALIBER_GAP
                  + "："
                  + ReportingTrafficConversionCatalog.CALIBER_GAP_THRESHOLD_NOTE
                  + " ⇒ 平台口径与订单口径**统计范围不同**（不是数据错，但必须被看见）。"),
          new ReportingTrafficConversionModels.TrafficRuleNote(
              "DIAGNOSIS_BASELINE",
              "🔴 **环比基线是窗口内的上一个桶**：窗口外的事实没有投影 ⇒ "
                  + "窗口的第一个桶没有基线，**不诊断**（不是「没有异常」）。"),
          new ReportingTrafficConversionModels.TrafficRuleNote(
              "DATA_LATENCY",
              "数据延迟：报表是投影，不是实时的。页面上常驻显示**数据更新时间**"
                  + "（最近一次投影落库时刻）；空表时是 null（不是一个长得像时间的假值）。"
                  + "报表数字与业务系统冲突时，**以业务系统为准**。"));
}
