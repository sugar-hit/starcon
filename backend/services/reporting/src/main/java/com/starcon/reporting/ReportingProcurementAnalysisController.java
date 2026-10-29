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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.5.2.1 采购分析的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /reporting/procurement-analysis}</b>：卷 13 的兄弟模块
 * （13.5.1.1 库存分析 / 13.4.2.1 成本结构 / 13.4.1.2 SKU 利润 …）各自带自己的前缀，
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码有三个：{@code reporting.procurement-analysis.read}（本模块**只读**）、
 * {@code reporting.procurement-analysis.write}（**只改本模块自己的阈值规则表**，不写任何上游口径）、
 * {@code reporting.projection.write}（**投影摄取**：把上游事实拉成本服务的投影）。
 */
@RestController
@RequestMapping("/reporting/procurement-analysis")
@Tag(name = "13.5.2.1 采购分析")
public class ReportingProcurementAnalysisController {

  private final AccessService access;
  private final ReportingProcurementAnalysisService procurementAnalysis;
  private final ReportingProcurementAnalysisRefreshService refresh;

  public ReportingProcurementAnalysisController(
      AccessService access,
      ReportingProcurementAnalysisService procurementAnalysis,
      ReportingProcurementAnalysisRefreshService refresh) {
    this.access = access;
    this.procurementAnalysis = procurementAnalysis;
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
      summary = "能力声明：七件指标分别来自哪个上游、哪些确实拿不到",
      description =
          "notAvailable 列出的是**确实拆不出来**的部分（没有到货记录 ⇒ 采购周期不可比；"
              + "销售额投影没跑 ⇒ 成本占比不可比；没有评级 ⇒ 评级列是「未评级」而不是 0 分），"
              + "每条都写清原因与影响 —— 「算不出来」与「数值是 0」在页面上长得一样，含义却相反。")
  public ReportingProcurementAnalysisModels.ProcurementAnalysisCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.procurement-analysis.read", actor -> procurementAnalysis.capabilities(actor.tenantId()));
  }

  // ================================================================ 采购金额趋势与占比

  @GetMapping("/trend")
  @Operation(
      summary = "采购金额趋势与占比（**逐币种**）",
      description =
          "金额口径 = **trade 6.5.2.1 的到货流水**（分批到货按流水累加）。"
              + "**多币种绝不相加** —— 每一行带自己的币种；跨币种的「总计」只有单币种时才存在。"
              + "占比的分母是**同币种**的窗口合计；分母为 0 ⇒ 占比**不可比**（不是 0%）。")
  public ReportingProcurementAnalysisModels.ProcurementTrendResponse trend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String granularity,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "reporting.procurement-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return procurementAnalysis.trend(
              actor.tenantId(),
              window.from(),
              window.to(),
              resolveGranularity(granularity),
              resolveDimension(dimension),
              resolveCurrency(currency));
        });
  }

  // ================================================================ 供应商集中度

  @GetMapping("/concentration")
  @Operation(
      summary = "供应商集中度 + **风险信号**",
      description =
          "🔴 蓝图加粗句「供应商集中度是供应链风险指标，单一供应商占比过高会在断供时直接击穿业务」："
              + "本端点给的不只是中性百分比，还有**风险等级**（LOW / MEDIUM / HIGH）与"
              + "**风险说明**（哪一家、占比多少、断供时哪一档采购额会中断）。"
              + "逐币种分段 —— 集中度**不跨币种合并**（跨币种相加会得到一个没有意义的数）。")
  public ReportingProcurementAnalysisModels.ProcurementConcentrationResponse concentration(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "reporting.procurement-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return procurementAnalysis.concentration(
              actor.tenantId(), window.from(), window.to(), resolveCurrency(currency));
        });
  }

  // ================================================================ 价格波动

  @GetMapping("/price-variance")
  @Operation(
      summary = "采购价格波动（当前期间 vs **上一等长期间**）",
      description =
          "均价 = 金额 ÷ 数量（**加权**均价，不是各行单价的平均）。"
              + "上一期间没有数量、或均价为 0 ⇒ 环比**不可比**（不是 0%）。"
              + "逐币种分列 —— 跨币种的价格不可比。")
  public ReportingProcurementAnalysisModels.ProcurementPriceVarianceResponse priceVariance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "reporting.procurement-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return procurementAnalysis.priceVariance(
              actor.tenantId(),
              window.from(),
              window.to(),
              resolvePriceDimension(dimension),
              resolveCurrency(currency));
        });
  }

  // ================================================================ 到货准时率

  @GetMapping("/on-time")
  @Operation(
      summary = "到货准时率（**判定归 trade 6.3.2.2**，本模块只搬运）",
      description =
          "分母 = **已到货**流水数；PENDING（未到货）**不进分母**。分母为 0 ⇒ 准时率**不可比**（不是 0%）。"
              + "逾期待到货清单可下钻到具体单据。")
  public ReportingProcurementAnalysisModels.ProcurementOnTimeResponse onTime(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.procurement-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return procurementAnalysis.onTime(actor.tenantId(), window.from(), window.to());
        });
  }

  // ================================================================ 采购周期

  @GetMapping("/purchase-cycle")
  @Operation(
      summary = "采购周期（到货日 − 下单日）",
      description =
          "🔴 这是**实际采购周期**，不是「承诺到货日 − 下单日」那个**承诺交期** —— 两者不能混。"
              + "来源是 catalog 6.1.1.1 的到货记录（**全仓唯一同时有下单日与到货日的事实源**）。"
              + "没有到货记录 ⇒ 周期**不可比**（不是 0 天）。")
  public ReportingProcurementAnalysisModels.ProcurementCycleResponse purchaseCycle(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.procurement-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return procurementAnalysis.purchaseCycle(actor.tenantId(), window.from(), window.to());
        });
  }

  // ================================================================ 采购成本占销售额比

  @GetMapping("/cost-to-sales")
  @Operation(
      summary = "采购成本占销售额比（**逐币种**）",
      description =
          "分母（销售额）取 **13.1.1.1 已投影的 SALES_AMOUNT**，本模块**不重算**（口径唯一）。"
              + "该投影没跑 ⇒ 分母为 0 ⇒ 比率**不可比**（不是 0%）。"
              + "**多币种绝不相加** —— 每个币种各自和自己的销售额比。")
  public ReportingProcurementAnalysisModels.ProcurementCostToSalesResponse costToSales(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.procurement-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return procurementAnalysis.costToSales(actor.tenantId(), window.from(), window.to());
        });
  }

  // ================================================================ 供应商对比

  @GetMapping("/suppliers")
  @Operation(
      summary = "供应商对比（真分页 + 真计数）",
      description =
          "每一行给出：供应商档案（catalog 6.1.1.1，只读）、采购贡献与占比、准时率、"
              + "**平均采购周期**、**供应商评级**（6.1.1.3 的最新一期）。"
              + "多币种时**不跨币种相加** —— 请用 `currency` 参数筛选后再看金额与占比。")
  public ReportingProcurementAnalysisModels.ProcurementSupplierComparisonResponse suppliers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "reporting.procurement-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return procurementAnalysis.suppliers(
              actor.tenantId(),
              window.from(),
              window.to(),
              resolveCurrency(currency),
              Math.max(page, 0),
              Math.max(size, 1));
        });
  }

  // ================================================================ 下钻

  @GetMapping("/drilldown")
  @Operation(
      summary = "下钻到到货流水（真分页 + 真计数）",
      description =
          "每一行是一次到货流水：金额、单价、数量、承诺到货日与准时判定，"
              + "都能回到上游逐条核对。**没有金额的行是「—」，不在任何合计里**（不是 0 元）。")
  public ReportingProcurementAnalysisModels.ProcurementDrilldownResponse drilldown(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.procurement-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return procurementAnalysis.drilldown(
              actor.tenantId(),
              window.from(),
              window.to(),
              resolveCurrency(currency),
              Math.max(page, 0),
              Math.max(size, 1));
        });
  }

  // ================================================================ 阈值规则

  @GetMapping("/rules")
  @Operation(
      summary = "阈值规则（可配置项）",
      description =
          "集中度风险线、准时率目标与采购周期目标都落在**租户自己的规则表**里"
              + "（没有配置时给默认值）；不同采购结构差一个数量级，写死的阈值对谁都不对。")
  public List<ReportingProcurementAnalysisModels.ProcurementAnalysisRule> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.procurement-analysis.read",
        actor -> procurementAnalysis.rules(actor.tenantId()));
  }

  @PutMapping("/rules/{ruleType}")
  @Operation(
      summary = "改阈值",
      description =
          "只改写进来的那几个字段；本模块**只改自己的规则表**，"
              + "不写任何上游口径（准时判定在 trade、供应商档案在 catalog、评级在 trade）。")
  public ReportingProcurementAnalysisModels.ProcurementAnalysisRule updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String ruleType,
      @RequestBody ReportingProcurementAnalysisModels.ProcurementAnalysisRuleInput input) {
    return call(
        auth,
        "reporting.procurement-analysis.write",
        actor -> procurementAnalysis.updateRule(actor.tenantId(), resolveRuleType(ruleType), input));
  }

  // ================================================================ 投影摄取

  @PostMapping("/refresh")
  @Operation(
      summary = "刷新采购分析投影（从上游拉取 → 投影）",
      description =
          "拉两路上游（trade 采购到货流水 + 供应商评级；catalog 供应商档案 + 到货记录）"
              + "并落成本服务的投影（幂等：同一批上游事实重放不会翻倍）。"
              + "权限是 reporting.projection.write —— **本模块没有任何「创建业务事实」的权限**。")
  public ReportingProcurementAnalysisModels.ProcurementAnalysisRefreshResult refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String timezone) {
    // ⚠️ 这里**不走 call(...)**：拉取上游需要调用方自己的 bearer（跨服务只读端口转发它），
    // 而 Access 里不带 bearer，所以 bearer 从请求头直接取。
    var actor = access.require(auth, "reporting.projection.write");
    return access.run(
        actor,
        "reporting.projection.write",
        () -> {
          var window = resolveWindow(from, to);
          return refresh.refresh(
              actor.tenantId(), auth, window.from(), window.to(), resolveTimezone(timezone));
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

  /** 期间粒度。 */
  private String resolveGranularity(String raw) {
    if (raw == null || raw.isBlank()) return "DAY";
    var value = raw.trim().toUpperCase();
    if (!List.of("DAY", "WEEK", "MONTH").contains(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "granularity 只能是 DAY / WEEK / MONTH，收到：" + raw);
    }
    return value;
  }

  /** 拆解维度（趋势用）。 */
  private String resolveDimension(String raw) {
    if (raw == null || raw.isBlank()) return "ALL";
    var value = raw.trim().toUpperCase();
    if (!List.of("ALL", "SUPPLIER", "CATEGORY", "SHOP", "SKU").contains(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "dimension 只能是 ALL / SUPPLIER / CATEGORY / SHOP / SKU，收到：" + raw);
    }
    return value;
  }

  /** 拆解维度（价格波动用）。 */
  private String resolvePriceDimension(String raw) {
    if (raw == null || raw.isBlank()) return "SKU";
    var value = raw.trim().toUpperCase();
    if (!List.of("SKU", "SUPPLIER").contains(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "dimension 只能是 SKU / SUPPLIER，收到：" + raw);
    }
    return value;
  }

  /** 币种过滤（空 ⇒ 全部；**绝不跨币种相加**）。 */
  private String resolveCurrency(String raw) {
    if (raw == null || raw.isBlank()) return null;
    var value = raw.trim().toUpperCase();
    if (!value.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "currency 必须是 ISO alpha-3 币种码，收到：" + raw);
    }
    return value;
  }

  /** 规则类型。 */
  private String resolveRuleType(String raw) {
    var value = raw == null ? "" : raw.trim().toUpperCase();
    if (!List.of("CONCENTRATION_RISK", "ON_TIME_TARGET", "PURCHASE_CYCLE_TARGET").contains(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "ruleType 只能是 CONCENTRATION_RISK / ON_TIME_TARGET / PURCHASE_CYCLE_TARGET，收到：" + raw);
    }
    return value;
  }

  /** 时区（采购周期的「下单日」换算；默认 UTC，**不写死**在代码里的某个租户时区）。 */
  private ZoneId resolveTimezone(String raw) {
    if (raw == null || raw.isBlank()) return ZoneId.of("UTC");
    try {
      return ZoneId.of(raw.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "timezone 不是合法时区标识：" + raw);
    }
  }
}
