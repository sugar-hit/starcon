package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
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
 * 13.4.2.2 费用差异的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /reporting/expense-variance}</b>：卷 13 的兄弟模块
 * （13.4.1.1 多模式利润报表 / 13.4.2.1 成本结构 …）各自带自己的前缀，
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码只有两个：{@code reporting.expense-variance.read}（本模块**只读**）
 * 与 {@code reporting.projection.write}（**投影摄取**：把上游事实拉成本服务的投影）。
 * 本模块**没有任何「创建业务事实」的权限** —— reporting 是投影服务。
 */
@RestController
@RequestMapping("/reporting/expense-variance")
@Tag(name = "13.4.2.2 费用差异")
public class ReportingExpenseController {

  private final AccessService access;
  private final ReportingExpenseService expenses;
  private final ReportingExpenseRefreshService refresh;

  public ReportingExpenseController(
      AccessService access, ReportingExpenseService expenses, ReportingExpenseRefreshService refresh) {
    this.access = access;
    this.expenses = expenses;
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
      summary = "能力声明：哪些上游真接通、哪些确实拿不到、归谁",
      description =
          "notDelivered 列出的是**确实算不出差异**的部分，每条都写清原因与影响 —— "
              + "「算不出来」与「差异是 0」在页面上长得一样，含义却相反。")
  public ReportingExpenseModels.ExpenseVarianceCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.expense-variance.read", actor -> expenses.capabilities());
  }

  // ================================================================ 差异表

  @GetMapping("/variance")
  @Operation(
      summary = "费用差异表 + 偏差趋势 + 规则调优建议",
      description =
          "差异 = 实际 − 预计：差异为负 ⇒ 预计高估（规则配高了）；差异为正 ⇒ 预计低估（规则配低了）。"
              + "默认窗口是「最近 30 天」。granularity 决定趋势的期间粒度：DAY / WEEK / MONTH。")
  public ReportingExpenseModels.ExpenseVarianceResponse variance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String granularity,
      @RequestParam(required = false) String feeType,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String skuCode) {
    return call(
        auth,
        "reporting.expense-variance.read",
        actor -> {
          var window = resolveWindow(from, to);
          var fee = resolveFeeType(feeType);
          return expenses.variance(
              actor.tenantId(),
              window.from(),
              window.to(),
              resolveGranularity(granularity),
              fee,
              blankToNull(platformCode),
              blankToNull(shopCode),
              blankToNull(skuCode));
        });
  }

  // ================================================================ 偏差趋势

  @GetMapping("/trend")
  @Operation(summary = "偏差趋势：按期间粒度给出差异率（没有可比行的期间没有点，不是 0%）")
  public List<ReportingExpenseModels.ExpenseVarianceTrendPoint> trend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String granularity,
      @RequestParam(required = false) String feeType,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String skuCode) {
    return call(
        auth,
        "reporting.expense-variance.read",
        actor -> {
          var window = resolveWindow(from, to);
          var response =
              expenses.variance(
                  actor.tenantId(),
                  window.from(),
                  window.to(),
                  resolveGranularity(granularity),
                  resolveFeeType(feeType),
                  blankToNull(platformCode),
                  blankToNull(shopCode),
                  blankToNull(skuCode));
          return response.trend();
        });
  }

  // ================================================================ 规则调优建议

  @GetMapping("/recommendations")
  @Operation(
      summary = "规则调优建议：系统性高估 / 低估",
      description =
          "只给**系统性**偏差：差异率绝对值 ≥ 阈值且可比样本 ≥ 最少组数。"
              + "没有销售额 / 数量基数时反推不出费率，只给定性建议。")
  public List<ReportingExpenseModels.ExpenseVarianceRecommendation> recommendations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String feeType,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String skuCode) {
    return call(
        auth,
        "reporting.expense-variance.read",
        actor -> {
          var window = resolveWindow(from, to);
          var response =
              expenses.variance(
                  actor.tenantId(),
                  window.from(),
                  window.to(),
                  "DAY",
                  resolveFeeType(feeType),
                  blankToNull(platformCode),
                  blankToNull(shopCode),
                  blankToNull(skuCode));
          return response.recommendations();
        });
  }

  // ================================================================ SKU 级差异

  @GetMapping("/by-sku")
  @Operation(
      summary = "SKU 级差异：按 SKU 与期间展示差异率（真分页 + 真计数）",
      description =
          "只覆盖**两侧都有事实**的 SKU —— 目前只有**头程分摊**两侧都带 sku_code。")
  public ReportingExpenseModels.ExpenseSkuVariancePage bySku(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String feeType,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.expense-variance.read",
        actor -> {
          var window = resolveWindow(from, to);
          return expenses.skuVariance(
              actor.tenantId(),
              window.from(),
              window.to(),
              resolveFeeType(feeType),
              page,
              Math.min(Math.max(size, 1), 200));
        });
  }

  // ================================================================ 投影摄取

  @PostMapping("/refresh")
  @Operation(
      summary = "刷新费用事实（从上游拉取 → 投影）",
      description =
          "与 POST /reporting/projection/refresh 是同一条管道（同一套幂等键与重建逻辑），"
              + "这里只跑本模块的五路上游。权限是 reporting.projection.write —— "
              + "**本模块没有任何「创建业务事实」的权限**。")
  public List<ReportingProjectionModels.RefreshResult> refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) List<String> sources,
      @RequestParam(required = false, defaultValue = "UTC") String timezone) {
    // ⚠️ 这里**不走 call(...)**：拉取上游需要调用方自己的 bearer（跨服务只读端口转发它），
    // 而 Access 里不带 bearer，所以 bearer 从请求头直接取。
    var actor = access.require(auth, "reporting.projection.write");
    return access.run(
        actor,
        "reporting.projection.write",
        () -> {
          var window = resolveWindow(from, to);
          var zone = resolveTimezone(timezone);
          return refresh.refresh(
              actor.tenantId(), auth, window.from(), window.to(), sources, zone);
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

  /** 费用类型（非法取值给一句能直接展示的话，而不是抛枚举异常）。 */
  private String resolveFeeType(String raw) {
    if (raw == null || raw.isBlank()) return null;
    var parsed = ReportingExpenseModels.FeeType.parse(raw);
    if (parsed == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "feeType 只能是 PLATFORM_FEE / LOGISTICS_FEE / AD_FEE / HEAD_FREIGHT，收到：" + raw);
    }
    return parsed.name();
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

  /** 分桶时区（跨时区店铺汇总必须显示所用时区）。 */
  private ZoneId resolveTimezone(String raw) {
    if (raw == null || raw.isBlank()) return ZoneId.of("UTC");
    try {
      return ZoneId.of(raw.trim());
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "timezone 不是合法时区：" + raw);
    }
  }

  private static String blankToNull(String raw) {
    return raw == null || raw.isBlank() ? null : raw.trim();
  }
}
