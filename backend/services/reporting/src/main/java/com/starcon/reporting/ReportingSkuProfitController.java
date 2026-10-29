package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
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
 * 13.4.1.2 SKU 利润的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /reporting/sku-profit}</b>：兄弟模块 13.4.1.1 用的是
 * {@code /reporting/profit-report}，两者是**不同的命名空间** —— 把 SKU 利润塞进
 * {@code profit-report} 下面会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码：{@code reporting.sku-profit.read}（本模块**只读**）与
 * {@code reporting.projection.write}（**投影摄取**：把上游退款事实拉成本服务的投影）。
 * 本模块**没有任何「创建业务事实」的权限** —— reporting 是投影服务。
 */
@RestController
@RequestMapping("/reporting/sku-profit")
@Tag(name = "13.4.1.2 SKU 利润")
public class ReportingSkuProfitController {

  private final AccessService access;
  private final ReportingSkuProfitService profit;
  private final ReportingSkuProfitRefreshService refresh;

  public ReportingSkuProfitController(
      AccessService access,
      ReportingSkuProfitService profit,
      ReportingSkuProfitRefreshService refresh) {
    this.access = access;
    this.profit = profit;
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
  public ReportingSkuProfitModels.SkuProfitCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.sku-profit.read", actor -> profit.capabilities());
  }

  // ================================================================ SKU 利润表

  @GetMapping("/report")
  @Operation(
      summary = "SKU 利润表：单品收入 − 各项成本 = 净利（不可比时是「—」，不是 0）",
      description =
          "一行 = 一个 SKU × 一个币种（**绝不跨币种相加**）。"
              + "成本只取上游**已分摊到 SKU** 的那些行：上游分摊到店铺 / 订单时，"
              + "SKU 维度就拿不到那一格 —— 显示「不可归属到 SKU」，**不是 0**。")
  public ReportingSkuProfitModels.SkuProfitResponse report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "reporting.sku-profit.read",
        actor -> {
          var window = resolveWindow(from, to);
          return profit.report(
              actor.tenantId(),
              window.from(),
              window.to(),
              blankToNull(skuCode),
              resolveCurrency(currency));
        });
  }

  // ================================================================ 构成瀑布

  @GetMapping("/waterfall")
  @Operation(
      summary = "构成瀑布：收入 → 逐项成本 → 净利",
      description =
          "每一步都是**服务端算好的**，前端只画图。哪一步拿不到，净利就是「—（不可比）」，**不是 0**。")
  public ReportingSkuProfitModels.SkuProfitWaterfall waterfall(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String skuCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "reporting.sku-profit.read",
        actor -> {
          var window = resolveWindow(from, to);
          return profit.waterfall(
              actor.tenantId(),
              window.from(),
              window.to(),
              requireSkuCode(skuCode),
              resolveCurrency(currency));
        });
  }

  // ================================================================ 订单明细钻取

  @GetMapping("/orders")
  @Operation(
      summary = "订单级明细钻取：这个 SKU 背后的事实行（真分页 + 真计数）",
      description =
          "列出的是**事实行**（来源服务 + 上游行标识），可以逐条回到上游核对。"
              + "净利为什么算不出来，在这一层能直接看到缺的是哪几条事实。")
  public ReportingSkuProfitModels.SkuProfitOrderPage orders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String skuCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String currency,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "reporting.sku-profit.read",
        actor -> {
          var window = resolveWindow(from, to);
          return profit.orders(
              actor.tenantId(),
              window.from(),
              window.to(),
              requireSkuCode(skuCode),
              resolveCurrency(currency),
              Math.max(page, 0),
              Math.min(Math.max(size, 1), ReportingSkuProfitService.MAX_PAGE_SIZE));
        });
  }

  // ================================================================ 投影摄取

  @PostMapping("/refresh")
  @Operation(
      summary = "刷新退款损失事实（从上游拉取 → 投影）",
      description =
          "只跑本模块这一路上游：9/11 售后的退款申请单。权限是 reporting.projection.write —— "
              + "**本模块没有任何「创建业务事实」的权限**。")
  public ReportingSkuProfitModels.SkuProfitRefreshResult refresh(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    // ⚠️ 这里**不走 call(...)**：拉取上游需要调用方自己的 bearer（跨服务只读端口转发它），
    // 而 Access 里不带 bearer，所以 bearer 从请求头直接取。
    var actor = access.require(auth, "reporting.projection.write");
    return access.run(
        actor,
        "reporting.projection.write",
        () -> {
          var window = resolveWindow(from, to);
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

  /** 币种（只收 3 位大写码；不合规给一句能直接展示的话）。 */
  private String resolveCurrency(String raw) {
    if (raw == null || raw.isBlank()) return null;
    var value = raw.trim().toUpperCase();
    if (!value.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "currency 必须是 3 位大写币种码（如 USD），收到：" + raw);
    }
    return value;
  }

  /** SKU 编码（下钻与瀑布必须给一个 SKU）。 */
  private String requireSkuCode(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "skuCode 不能为空：这一页的每一格都是一个 SKU。");
    }
    return raw.trim();
  }

  private static String blankToNull(String raw) {
    return raw == null || raw.isBlank() ? null : raw.trim();
  }
}
