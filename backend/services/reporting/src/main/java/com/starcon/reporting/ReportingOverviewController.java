package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.1.1.1 经营总览的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /reporting/overview}</b>：卷 13 的兄弟模块（13.1.1.2 多店对比 /
 * 13.2 销量分析 / 13.4 利润分析 …）各自会带自己的前缀，挤在同一个前缀下会让「哪个模块的哪个端点」
 * 变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码只有 {@code reporting.overview.read}（本模块**只读**；写的是投影，不在这里）。
 */
@RestController
@RequestMapping("/reporting/overview")
@Tag(name = "13.1.1.1 经营总览")
public class ReportingOverviewController {

  private final AccessService access;
  private final ReportingOverviewService overview;

  public ReportingOverviewController(AccessService access, ReportingOverviewService overview) {
    this.access = access;
    this.overview = overview;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "reporting.overview.read");
    return access.run(actor, "reporting.overview.read", () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明：哪些上游真接通、哪些还没交付、归谁",
      description =
          "上游列表按**本服务真的读过的授权只读 API**列出（不是「蓝图上写着上游」）；"
              + "notDelivered 列出的是**确实拿不到**的部分，每条都写清原因与影响。")
  public ReportingOverviewCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> new ReportingOverviewCapabilities(true, true, true, UPSTREAM_MODULES, NOT_DELIVERED));
  }

  // ================================================================ 指标口径目录

  @GetMapping("/metrics")
  @Operation(summary = "指标口径目录（口径唯一的展示形态）")
  public List<ReportingOverviewModels.MetricDefinitionView> metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> overview.metricDefinitions(actor.tenantId()));
  }

  // ================================================================ 经营总览

  @GetMapping
  @Operation(
      summary = "经营总览：指标卡 + 趋势 + 异常提示 + 数据更新时间戳",
      description =
          "时间范围与维度选择都在这里。默认窗口是「最近 30 天」。"
              + "维度取值 COMPANY / LEGAL_ENTITY / SHOP；给了 shopId / legalEntityId 时维度会跟着收窄。")
  public ReportingOverviewModels.OverviewResponse overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String baseCurrency) {
    return call(
        auth,
        actor -> {
          var window = resolveWindow(from, to);
          var scope = resolveScope(scopeType, legalEntityId, shopId);
          return overview.overview(
              actor.tenantId(),
              window.from(),
              window.to(),
              scope.scopeType(),
              scope.legalEntityId(),
              scope.shopId(),
              baseCurrency == null || baseCurrency.isBlank() ? null : baseCurrency.trim().toUpperCase());
        });
  }

  // ================================================================ 趋势

  @GetMapping("/trend")
  @Operation(summary = "趋势：逐日逐币种（没有事实的那一天没有点，不是 0）")
  public List<ReportingOverviewModels.TrendPoint> trend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) List<String> metricCodes,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) UUID shopId) {
    return call(
        auth,
        actor -> {
          var window = resolveWindow(from, to);
          var scope = resolveScope(scopeType, legalEntityId, shopId);
          var codes =
              metricCodes == null || metricCodes.isEmpty()
                  ? ReportingOverviewService.TREND_METRICS
                  : metricCodes;
          return overview.trend(
              actor.tenantId(),
              codes,
              scope.scopeType(),
              scope.legalEntityId(),
              scope.shopId(),
              window.from(),
              window.to());
        });
  }

  // ================================================================ 下钻

  @GetMapping("/drilldown")
  @Operation(summary = "下钻：构成明细（哪个订单 / 哪个 SKU / 哪个活动 / 哪张应收）")
  public ReportingOverviewModels.DrilldownPage drilldown(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String metricCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        actor -> {
          var window = resolveWindow(from, to);
          var scope = resolveScope(scopeType, legalEntityId, shopId);
          return overview.drilldown(
              actor.tenantId(),
              metricCode,
              scope.scopeType(),
              scope.legalEntityId(),
              scope.shopId(),
              window.from(),
              window.to(),
              page,
              Math.min(Math.max(size, 1), 200));
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

  /** 维度。 */
  private record Scope(String scopeType, UUID legalEntityId, UUID shopId) {}

  /**
   * 解析维度。
   *
   * <p>⚠️ **给了维度 id 就必须是对应的维度**：{@code shopId} 配 {@code LEGAL_ENTITY}
   * 是自相矛盾的请求（公司级事实里根本没有店铺归属），直接 400 而不是**悄悄按公司级返回** ——
   * 后者会让用户以为「这个店铺没有数据」，而真相是「请求写错了」。
   */
  private Scope resolveScope(String scopeType, UUID legalEntityId, UUID shopId) {
    var effective = scopeType == null || scopeType.isBlank() ? null : scopeType.trim().toUpperCase();
    if (effective == null) {
      effective =
          shopId != null ? "SHOP" : (legalEntityId != null ? "LEGAL_ENTITY" : "COMPANY");
    }
    if (!List.of("COMPANY", "LEGAL_ENTITY", "SHOP").contains(effective)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "scopeType 只能是 COMPANY / LEGAL_ENTITY / SHOP，收到：" + effective);
    }
    if ("SHOP".equals(effective) && legalEntityId != null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "scopeType=SHOP 时不能同时给 legalEntityId（店铺维度与法人维度不是一回事）");
    }
    if ("LEGAL_ENTITY".equals(effective) && shopId != null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "scopeType=LEGAL_ENTITY 时不能同时给 shopId（法人维度与店铺维度不是一回事）");
    }
    if ("COMPANY".equals(effective) && (shopId != null || legalEntityId != null)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "scopeType=COMPANY 时不能带 shopId / legalEntityId（公司维度不带维度归属）");
    }
    return new Scope(effective, legalEntityId, shopId);
  }

  /**
   * 能力声明。
   *
   * @param upstreamConnected 上游是否真接通（本模块的四个上游全部已交付）
   * @param drilldownAvailable 是否可下钻
   * @param dataUpdatedAtVisible 是否常驻显示数据更新时间
   */
  public record ReportingOverviewCapabilities(
      boolean upstreamConnected,
      boolean drilldownAvailable,
      boolean dataUpdatedAtVisible,
      List<String> upstreamModules,
      List<String> notDelivered) {}

  // ==========================================================================
  // 能力声明的静态事实（**逐条对着上游的授权只读 API 核过**）
  // ==========================================================================

  /**
   * 本服务**真的读过**的上游模块（蓝图 13.1.1.1 的上游是「12 财务、9 订单、7 库存、14 广告」，
   * 另外两个是算派生指标必需的补充来源）。
   *
   * <p>⚠️ **不列没读过的**：能力声明是给前端判断「哪些块能画」的依据，
   * 列一个其实没接通的上游比不列更糟 —— 界面会以为功能已交付。
   */
  private static final List<String> UPSTREAM_MODULES =
      List.of(
          "9.1.1.1 订单列表（销售额 / 订单量 / 销量 / SKU 下钻）",
          "11.1.1.1 售后单列表（退货额 → 退货率）",
          "12.1.1.1 应收单管理（应收余额）",
          "12.4.1.2 成本计算执行（成本 → 毛利额 → 毛利率）",
          "12.5.2.2 财务报表（净利，读利润表的「本期损益」行）",
          "14.3.1.1 活动报表（广告花费）",
          "7.1.1.1 库存总览（在手数量 → 库存总额）",
          "5.2.1.1 成本构成（单位成本，用于库存成本法估值）");

  /**
   * **确实拿不到**的部分（每条都写清原因，不写「未接入」这种空话）。
   *
   * <p>⚠️ 这一栏是本模块最容易被做成「全部已交付」的地方：能力声明写「全部接通」时，
   * 前端会把每一个卡片都画成有数据的样子，而实际上店铺维度下有一半指标根本读不到。
   */
  private static final List<String> NOT_DELIVERED =
      List.of(
          "店铺 / 法人维度的**销售类指标**：上游 sales_order 表没有 shop_id / legal_entity_id，"
              + "成本 / 退货 / 广告的上游事实同样不带归属 ⇒ 销售额、订单量、销量、成本、退货额、"
              + "广告花费只落在公司维度。切到店铺或法人维度时这些卡片是「该口径下没有事实数据」，不是 0。",
          "**上游的推事件**：上游尚未产出事实型 outbox 事件 ⇒ 现在的事实来自 "
              + "POST /reporting/projection/refresh 的拉取路径；推路径 POST /reporting/projection/events "
              + "已就绪（与拉共用同一套幂等键、口径表与重建逻辑），但暂无上游投递。",
          "**库存总额与应收余额的窗口口径**：这两项是时点快照（aggregation=LAST），"
              + "只在刷新当天有值；窗口内没有快照的日子不是 0，是「没有快照」。");
}
