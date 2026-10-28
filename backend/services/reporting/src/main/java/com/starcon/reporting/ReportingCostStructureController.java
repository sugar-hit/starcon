package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
 * 13.4.2.1 成本结构的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /reporting/cost-structure}</b>：卷 13 的兄弟模块
 * （13.4.2.2 费用差异 / 13.1.1.1 经营总览 …）各自带自己的前缀，
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码有三个：{@code reporting.cost-structure.read}（本模块**只读**）、
 * {@code reporting.cost-structure.write}（**只改本模块自己的阈值规则表**，不写任何上游口径）、
 * {@code reporting.projection.write}（**投影摄取**：把上游事实拉成本服务的投影）。
 */
@RestController
@RequestMapping("/reporting/cost-structure")
@Tag(name = "13.4.2.1 成本结构")
public class ReportingCostStructureController {

  private final AccessService access;
  private final ReportingCostStructureService costStructure;
  private final ReportingCostStructureRefreshService refresh;

  public ReportingCostStructureController(
      AccessService access,
      ReportingCostStructureService costStructure,
      ReportingCostStructureRefreshService refresh) {
    this.access = access;
    this.costStructure = costStructure;
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
      summary = "能力声明：七类成本分别来自哪个上游、哪些确实拿不到",
      description =
          "notDelivered 列出的是**确实拆不出来**的部分（平台费的 SKU 级结构、退货的店铺维度等），"
              + "每条都写清原因与影响 —— 「算不出来」与「占比 0%」在页面上长得一样，含义却相反。")
  public ReportingCostStructureModels.CostStructureCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.cost-structure.read", actor -> costStructure.capabilities());
  }

  // ================================================================ 占比结构

  @GetMapping("/structure")
  @Operation(
      summary = "成本占比结构 + 环比 / 同比基线 + 异常提示",
      description =
          "占比的分母口径由 denominatorCaliber 决定并**写在响应里**（TOTAL_COST 总成本口径 / "
              + "REVENUE 收入口径），两种口径不可混着读。"
              + "基线来自**真实历史投影数据**（前 N 期同口径占比），不是写死的基线常量。"
              + "算不出来的占比是 null（页面上是「—」），**不是 0%**。")
  public ReportingCostStructureModels.CostStructureResponse structure(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String granularity,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String denominatorCaliber,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) BigDecimal thresholdValue,
      @RequestParam(required = false) Integer minHistoryPeriods,
      @RequestParam(required = false) String thresholdMode) {
    return call(
        auth,
        "reporting.cost-structure.read",
        actor -> {
          var window = resolveWindow(from, to);
          return costStructure.structure(
              actor.tenantId(),
              window.from(),
              window.to(),
              resolveGranularity(granularity),
              resolveDimension(dimension),
              resolveDenominatorCaliber(denominatorCaliber),
              resolveCurrency(currency),
              thresholdValue,
              minHistoryPeriods,
              thresholdMode);
        });
  }

  // ================================================================ 趋势

  @GetMapping("/trend")
  @Operation(
      summary = "成本占比趋势（按期间粒度）",
      description = "**分母算不出来的期间没有点**（不是 0%）——「算不出来」与「真的是 0」必须分开。")
  public List<ReportingCostStructureModels.CostStructureTrendPoint> trend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String granularity,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String denominatorCaliber,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "reporting.cost-structure.read",
        actor -> {
          var window = resolveWindow(from, to);
          return costStructure
              .structure(
                  actor.tenantId(),
                  window.from(),
                  window.to(),
                  resolveGranularity(granularity),
                  resolveDimension(dimension),
                  resolveDenominatorCaliber(denominatorCaliber),
                  resolveCurrency(currency),
                  null,
                  null,
                  null)
              .trend();
        });
  }

  // ================================================================ 异常提示

  @GetMapping("/anomalies")
  @Operation(
      summary = "异常提示：占比相对历史基线的异常跳变",
      description =
          "阈值**不是写死的百分比**：它来自租户自己的规则表（固定百分点 / 基线标准差的若干倍），"
              + "可在 PUT /reporting/cost-structure/rules/{costCategory} 上改。"
              + "判不出来（历史期数不足 / 基线无波动）时**不报异常**，而是给出「判不了」的原因。")
  public List<ReportingCostStructureModels.CostStructureAnomaly> anomalies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String granularity,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String denominatorCaliber,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "reporting.cost-structure.read",
        actor -> {
          var window = resolveWindow(from, to);
          return costStructure
              .structure(
                  actor.tenantId(),
                  window.from(),
                  window.to(),
                  resolveGranularity(granularity),
                  resolveDimension(dimension),
                  resolveDenominatorCaliber(denominatorCaliber),
                  resolveCurrency(currency),
                  null,
                  null,
                  null)
              .anomalies();
        });
  }

  // ================================================================ 阈值规则

  @GetMapping("/rules")
  @Operation(
      summary = "异常判定阈值规则（可配置项）",
      description =
          "阈值落在**租户自己的规则表**里：固定百分点（FIXED）或基线标准差的若干倍（STDDEV）；"
              + "最少历史期数决定基线要前几期同口径占比。")
  public List<ReportingCostStructureModels.CostStructureRule> rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "reporting.cost-structure.read",
        actor -> costStructure.rules(actor.tenantId()));
  }

  @PutMapping("/rules/{costCategory}")
  @Operation(
      summary = "改某一类成本的异常判定阈值",
      description =
          "只改写进来的那几个字段；本模块**只改自己的规则表**，不写任何上游口径"
              + "（费率规则在 catalog、归集规则在 finance）。")
  public ReportingCostStructureModels.CostStructureRule updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String costCategory,
      @RequestBody ReportingCostStructureModels.CostStructureRuleInput input) {
    return call(
        auth,
        "reporting.cost-structure.write",
        actor -> costStructure.updateRule(actor.tenantId(), costCategory, input));
  }

  // ================================================================ 投影摄取

  @PostMapping("/refresh")
  @Operation(
      summary = "刷新成本事实（从上游拉取 → 投影）",
      description =
          "拉七路上游的成本事实并归到七类成本里（幂等：同一批上游事实重放不会翻倍）。"
              + "权限是 reporting.projection.write —— **本模块没有任何「创建业务事实」的权限**。")
  public List<ReportingCostStructureModels.CostStructureRefreshSummary> refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) List<String> sources) {
    // ⚠️ 这里**不走 call(...)**：拉取上游需要调用方自己的 bearer（跨服务只读端口转发它），
    // 而 Access 里不带 bearer，所以 bearer 从请求头直接取。
    var actor = access.require(auth, "reporting.projection.write");
    return access.run(
        actor,
        "reporting.projection.write",
        () -> {
          var window = resolveWindow(from, to);
          return refresh.refresh(actor.tenantId(), auth, window.from(), window.to(), sources);
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

  /** 归属维度。 */
  private String resolveDimension(String raw) {
    if (raw == null || raw.isBlank()) return "ALL";
    var value = raw.trim().toUpperCase();
    if (!List.of("ALL", "SKU", "SHOP", "CATEGORY", "PLATFORM").contains(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "dimension 只能是 ALL / SKU / SHOP / CATEGORY / PLATFORM，收到：" + raw);
    }
    return value;
  }

  /** 分母口径。 */
  private String resolveDenominatorCaliber(String raw) {
    if (raw == null || raw.isBlank()) return "TOTAL_COST";
    var value = raw.trim().toUpperCase();
    if (!List.of("TOTAL_COST", "REVENUE").contains(value)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "denominatorCaliber 只能是 TOTAL_COST / REVENUE，收到：" + raw);
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
}
