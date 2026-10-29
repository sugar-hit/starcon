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
 * 13.5.3.1 物流分析的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /reporting/logistics-analysis}</b>：卷 13 的兄弟模块
 * （13.5.1.1 库存分析 / 13.5.2.1 采购分析 / 13.4.1.2 SKU 利润 …）各自带自己的前缀，
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码有三个：{@code reporting.logistics-analysis.read}（本模块**只读**）、
 * {@code reporting.logistics-analysis.write}（**只改本模块自己的阈值规则表**，不写任何上游口径）、
 * {@code reporting.projection.write}（**投影摄取**：把上游事实拉成本服务的投影）。
 */
@RestController
@RequestMapping("/reporting/logistics-analysis")
@Tag(name = "13.5.3.1 物流分析")
public class ReportingLogisticsAnalysisController {

  private final AccessService access;
  private final ReportingLogisticsAnalysisService logisticsAnalysis;
  private final ReportingLogisticsAnalysisRefreshService refresh;

  public ReportingLogisticsAnalysisController(
      AccessService access,
      ReportingLogisticsAnalysisService logisticsAnalysis,
      ReportingLogisticsAnalysisRefreshService refresh) {
    this.access = access;
    this.logisticsAnalysis = logisticsAnalysis;
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
      summary = "能力声明：六件指标分别来自哪个上游、哪些确实拿不到",
      description =
          "notAvailable 列出的是**确实拆不出来**的部分（没投影 ⇒ 那一格不可比；"
              + "没有类目归属 ⇒ 类目占比不可比），每条都写清原因与影响 —— "
              + "「算不出来」与「数值是 0」在页面上长得一样，含义却相反。")
  public ReportingLogisticsAnalysisModels.LogisticsAnalysisCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.logistics-analysis.read", actor -> logisticsAnalysis.capabilities(actor.tenantId()));
  }

  // ================================================================ 成本总览

  @GetMapping("/overview")
  @Operation(
      summary = "物流成本总览（**逐币种**：尾程 / 头程分摊 / 关税 / 包材 / 合计）",
      description =
          "口径 = **channel 10.6.1.1 的成本分析**。**多币种绝不相加** —— 每一行带自己的币种；"
              + "跨币种的「总计」不存在。缺项（仓储费在本仓没有来源）在 missingComponents 里逐条列明，"
              + "**不是 0**。⚠️ 上游的「每票运费」分母是**票**不是**单**，单均运费看 `/freight-per-order`。")
  public ReportingLogisticsAnalysisModels.LogisticsCostOverviewResponse overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.logistics-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return logisticsAnalysis.overview(actor.tenantId(), window.from(), window.to());
        });
  }

  // ================================================================ 单均运费

  @GetMapping("/freight-per-order")
  @Operation(
      summary = "单均运费（运费总额 ÷ **订单数**，逐币种）",
      description =
          "🔴 **分母是订单数，不是运单票数**：订单取同一批运单上的 distinct 销售订单号，"
              + "与运费总额**同范围**。channel 自己的「每票运费」也并排给出（**两者不是一回事**："
              + "一单多票时票数 > 订单数，用票数会把单均运费压低）。"
              + "**没有订单 ⇒ 不可比**（不是 0 元）。")
  public ReportingLogisticsAnalysisModels.LogisticsFreightPerOrderResponse freightPerOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.logistics-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return logisticsAnalysis.freightPerOrder(actor.tenantId(), window.from(), window.to());
        });
  }

  // ================================================================ 头程分摊后成本

  @GetMapping("/head-mile")
  @Operation(
      summary = "头程分摊后成本（逐币种 + 分摊单清单）",
      description =
          "🔴 **本模块不重算一套分摊** —— 分摊结果读 **channel 10.6.1.1**，"
              + "两个分摊口径会让「物流成本占比」有两个答案。分摊单按**结账时点（cost_as_of）**归窗，"
              + "并与上游成本总览的头程分摊位**并排对照**（对不上时以 channel 为准）。")
  public ReportingLogisticsAnalysisModels.LogisticsHeadMileResponse headMile(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.logistics-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return logisticsAnalysis.headMile(actor.tenantId(), window.from(), window.to());
        });
  }

  // ================================================================ 渠道对比

  @GetMapping("/channels")
  @Operation(
      summary = "渠道对比（承运商 × 渠道 × 币种，真分页 + 真计数）",
      description =
          "渠道维度取 **channel 10.6.1.1 的 by-channel**。**逐币种**：占比的分母是"
              + "**同币种**的窗口合计；跨币种没有占比。每一行带自己的币种。")
  public ReportingLogisticsAnalysisModels.LogisticsChannelComparisonResponse channels(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "reporting.logistics-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return logisticsAnalysis.channels(
              actor.tenantId(),
              window.from(),
              window.to(),
              resolveCurrency(currency),
              Math.max(page, 0),
              Math.max(size, 1));
        });
  }

  // ================================================================ 时效达成率

  @GetMapping("/timeliness")
  @Operation(
      summary = "时效达成率（**判定归 channel 10.6.2.1**，本模块只搬运）",
      description =
          "达成率 = 准点数 ÷ **可比数**；没有承诺送达日与未签收的票**不进分母**。"
              + "分母为 0 ⇒ 达成率**不可比**（不是 0%）。按国家的汇总**先汇总再相除**，"
              + "不是各格比率的平均。")
  public ReportingLogisticsAnalysisModels.LogisticsTimelinessResponse timeliness(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.logistics-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return logisticsAnalysis.timeliness(actor.tenantId(), window.from(), window.to());
        });
  }

  // ================================================================ 各国成本对比

  @GetMapping("/countries")
  @Operation(
      summary = "各国成本对比（**逐币种**：币种 × 目的国）",
      description =
          "口径 = **运单级尾程运费**（运单是运费金额与目的国共同的最小颗粒）。"
              + "**多币种绝不相加**：某国占比的分母是**同币种**的合计。"
              + "**运费未登记的票有票数、没有金额**，不进任何金额合计。"
              + "没有目的国的票单列「未标注目的国」，不摊进任何国家。")
  public ReportingLogisticsAnalysisModels.LogisticsCountryCostResponse countries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.logistics-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return logisticsAnalysis.countryCost(actor.tenantId(), window.from(), window.to());
        });
  }

  // ================================================================ 异常件占比

  @GetMapping("/exceptions")
  @Operation(
      summary = "异常件占比（**窗口内异常件 ÷ 窗口内运单**，分子分母同范围）",
      description =
          "🔴 分子取 channel 10.3.1.2 的**已判定异常件**（按发现日归窗），"
              + "分母取**同一窗口**的运单数。上游的 `/exceptions/overview` 是**累计全量**"
              + "（没有时间窗），拿它除以窗口内运单数会得到一个**没有意义**的比率 —— "
              + "本模块只把它单列为对照，不参与占比计算。"
              + "分母为 0 ⇒ 占比**不可比**（不是 0%）。")
  public ReportingLogisticsAnalysisModels.LogisticsExceptionShareResponse exceptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.logistics-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return logisticsAnalysis.exceptionShare(actor.tenantId(), window.from(), window.to());
        });
  }

  // ================================================================ 类目维度物流成本占比

  @GetMapping("/category-cost")
  @Operation(
      summary = "**类目维度**的物流成本占销售额比（下游 5 定价的输入）",
      description =
          "🔴 蓝图关系句：「**物流成本占比高的类目必须单独定价**，统一毛利率会吃掉利润。」"
              + "分子 = 类目下的**头程分摊后成本**（channel 的分摊明细逐 SKU，再归到类目）；"
              + "分母 = **13.4.1.2 SKU 利润的销售额投影**（component='REVENUE'）—— 与利润侧同口径，"
              + "本模块**不重算销售额**。"
              + "⚠️ **类目维度只覆盖头程分摊部分**：尾程运费落在运单上，一票可能多 SKU，"
              + "**没有 SKU 维度** ⇒ 那一段进不了类目。")
  public ReportingLogisticsAnalysisModels.LogisticsCategoryCostResponse categoryCost(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "reporting.logistics-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return logisticsAnalysis.categoryCost(actor.tenantId(), window.from(), window.to());
        });
  }

  // ================================================================ 下钻

  @GetMapping("/drilldown")
  @Operation(
      summary = "下钻到运单（真分页 + 真计数）",
      description =
          "每一行是一张运单：运费金额、目的国、销售订单号，都能回到上游逐条核对。"
              + "**没有运费的行是「—」**，不在任何合计里（不是 0 元）。")
  public ReportingLogisticsAnalysisModels.LogisticsDrilldownResponse drilldown(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String country,
      @RequestParam(required = false) String carrierChannel,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.logistics-analysis.read",
        actor -> {
          var window = resolveWindow(from, to);
          return logisticsAnalysis.drilldown(
              actor.tenantId(),
              window.from(),
              window.to(),
              resolveCurrency(currency),
              text(country),
              text(carrierChannel),
              Math.max(page, 0),
              Math.max(size, 1));
        });
  }

  // ================================================================ 阈值规则

  @GetMapping("/rules")
  @Operation(
      summary = "阈值规则（可配置项）",
      description =
          "时效达成率目标、单均运费目标、异常件占比上限、物流成本占销售额比上限都落在"
              + "**租户自己的规则表**里（没有配置时给默认值）；"
              + "不同物流结构差一个数量级，写死的阈值对谁都不对。")
  public List<ReportingLogisticsAnalysisModels.LogisticsAnalysisRule> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.logistics-analysis.read",
        actor -> logisticsAnalysis.rules(actor.tenantId()));
  }

  @PutMapping("/rules/{ruleType}")
  @Operation(
      summary = "改阈值",
      description =
          "只改写进来的那几个字段；本模块**只改自己的规则表**，"
              + "不写任何上游口径（达成率在 channel、分摊在 channel、运费登记在 trade）。")
  public ReportingLogisticsAnalysisModels.LogisticsAnalysisRule updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String ruleType,
      @RequestBody ReportingLogisticsAnalysisModels.LogisticsAnalysisRuleInput input) {
    return call(
        auth,
        "reporting.logistics-analysis.write",
        actor -> logisticsAnalysis.updateRule(actor.tenantId(), resolveRuleType(ruleType), input));
  }

  // ================================================================ 投影摄取

  @PostMapping("/refresh")
  @Operation(
      summary = "刷新物流分析投影（从上游拉取 → 投影）",
      description =
          "拉三路上游（channel 10.6.1.1 成本分析 + 10.6.2.1 时效 + 10.3.1.2 异常件；"
              + "trade 10.2.1.1 运单；catalog 6.1.1.1 SKU 类目）并落成本服务的投影"
              + "（幂等：同一批上游事实重放不会翻倍）。"
              + "权限是 reporting.projection.write —— **本模块没有任何「创建业务事实」的权限**。")
  public ReportingLogisticsAnalysisModels.LogisticsAnalysisRefreshResult refresh(
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
    if (!List.of(
            "ON_TIME_TARGET",
            "FREIGHT_PER_ORDER_TARGET",
            "EXCEPTION_SHARE_TARGET",
            "COST_TO_SALES_TARGET")
        .contains(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "ruleType 只能是 ON_TIME_TARGET / FREIGHT_PER_ORDER_TARGET / EXCEPTION_SHARE_TARGET /"
              + " COST_TO_SALES_TARGET，收到：" + raw);
    }
    return value;
  }

  /** 时区（运单 / 异常件的「业务日」换算；默认 UTC，**不写死**在代码里的某个租户时区）。 */
  private ZoneId resolveTimezone(String raw) {
    if (raw == null || raw.isBlank()) return ZoneId.of("UTC");
    try {
      return ZoneId.of(raw.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "timezone 不是合法的时区标识：" + raw);
    }
  }

  /** null → null（不过滤）；空白 → null（不过滤）。 */
  private static String text(String raw) {
    if (raw == null) return null;
    var trimmed = raw.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
