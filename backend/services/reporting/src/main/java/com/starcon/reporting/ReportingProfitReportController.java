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
 * 13.4.1.1 多模式利润报表的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /reporting/profit-report}</b>：卷 13 的兄弟模块
 * （13.4.2.2 费用差异 / 13.4.1.2 SKU 利润 …）各自带自己的前缀，
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码只有两个：{@code reporting.profit-report.read}（本模块**只读**）与
 * {@code reporting.projection.write}（**投影摄取**：把上游事实拉成本服务的投影）。
 * 本模块**没有任何「创建业务事实」的权限** —— reporting 是投影服务。
 */
@RestController
@RequestMapping("/reporting/profit-report")
@Tag(name = "13.4.1.1 多模式利润报表")
public class ReportingProfitReportController {

  private final AccessService access;
  private final ReportingProfitReportService report;
  private final ReportingProfitReportRefreshService refresh;

  public ReportingProfitReportController(
      AccessService access,
      ReportingProfitReportService report,
      ReportingProfitReportRefreshService refresh) {
    this.access = access;
    this.report = report;
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
      summary = "能力声明：哪些上游真接通、哪些确实算不出来、归谁",
      description =
          "notDelivered 列出的是**确实算不出来**的部分，每条都写清原因与影响 —— "
              + "「算不出来」与「等于 0」在页面上长得一样，含义却相反。")
  public ReportingProfitReportModels.ProfitReportCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.profit-report.read", actor -> report.capabilities());
  }

  // ================================================================ 利润报表

  @GetMapping("/report")
  @Operation(
      summary = "多模式利润报表：按经营模式分别出毛利，支持多维核算",
      description =
          "不同经营模式的利润含义完全不同 ⇒ 报表**按模式分行，模式之间不合并**。"
              + "dimension 决定核算维度：MODE / SHOP / ORDER 用订单总额口径的收入，"
              + "SKU / SPU / MSKU 用订单行金额口径的收入（两者不是同一笔钱，不相加）。"
              + "毛利算不出来时返回 null（页面显示「—」），**不是 0.00**。")
  public ReportingProfitReportModels.ProfitReportResponse report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String granularity,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String operatingMode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) String baseCurrency) {
    return call(
        auth,
        "reporting.profit-report.read",
        actor -> {
          var window = resolveWindow(from, to);
          return report.report(
              actor.tenantId(),
              window.from(),
              window.to(),
              resolveGranularity(granularity),
              resolveDimension(dimension),
              resolveOperatingMode(operatingMode),
              blankToNull(shopCode),
              blankToNull(skuCode),
              blankToNull(baseCurrency));
        });
  }

  // ================================================================ 下钻

  @GetMapping("/drilldown")
  @Operation(
      summary = "多维下钻：这一格背后的构成明细（真分页 + 真计数）",
      description =
          "列出的是**事实行**（来源服务 + 上游行标识），可以逐条回到上游核对。"
              + "毛利那一格为什么算不出来，在这一层能直接看到缺的是哪几条事实。")
  public ReportingProfitReportModels.ProfitDrilldownPage drilldown(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String dimensionCode,
      @RequestParam(required = false) String operatingMode,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.profit-report.read",
        actor -> {
          var window = resolveWindow(from, to);
          return report.drilldown(
              actor.tenantId(),
              window.from(),
              window.to(),
              resolveDimension(dimension),
              blankToNull(dimensionCode),
              resolveOperatingMode(operatingMode),
              Math.max(page, 0),
              Math.min(Math.max(size, 1), ReportingProfitReportService.MAX_PAGE_SIZE));
        });
  }

  // ================================================================ 经营模式清单

  @GetMapping("/modes")
  @Operation(
      summary = "经营模式清单：每个模式带店铺数（模式取自 2.3.2.1）",
      description =
          "「未归属」= 挂不上店铺或上游没给经营模式的店铺 —— 它们的收入与成本**不混进任何真实模式**。")
  public List<ReportingProfitReportModels.ProfitModeView> modes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.profit-report.read", actor -> report.modes(actor.tenantId()));
  }

  // ================================================================ 投影摄取

  @PostMapping("/refresh")
  @Operation(
      summary = "刷新利润事实（从上游拉取 → 投影）",
      description =
          "与 POST /reporting/projection/refresh 是同一条管道（同一套幂等键与重建逻辑），"
              + "这里只跑本模块的四路上游。权限是 reporting.projection.write —— "
              + "**本模块没有任何「创建业务事实」的权限**。")
  public List<ReportingProfitReportModels.ProfitRefreshResult> refresh(
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

  /** 期间粒度。 */
  private String resolveGranularity(String raw) {
    if (raw == null || raw.isBlank()) return "DAY";
    var parsed = ReportingProfitReportModels.Granularity.parse(raw);
    if (parsed == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "granularity 只能是 DAY / MONTH，收到：" + raw);
    }
    return parsed.name();
  }

  /** 核算维度。 */
  private String resolveDimension(String raw) {
    if (raw == null || raw.isBlank()) return "MODE";
    var parsed = ReportingProfitReportModels.Dimension.parse(raw);
    if (parsed == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "dimension 只能是 MODE / SHOP / SKU / SPU / MSKU / ORDER，收到：" + raw);
    }
    return parsed.name();
  }

  /** 经营模式（非法取值给一句能直接展示的话，而不是抛枚举异常）。 */
  private String resolveOperatingMode(String raw) {
    if (raw == null || raw.isBlank()) return null;
    var parsed = ReportingProfitReportModels.OperatingMode.parse(raw);
    if (parsed == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "operatingMode 只能是 SELF_FULFILLED / FULL_CONSIGNMENT / SEMI_CONSIGNMENT / "
              + "OVERSEAS_WAREHOUSE / LOCAL / UNKNOWN，收到：" + raw);
    }
    return parsed.name();
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
