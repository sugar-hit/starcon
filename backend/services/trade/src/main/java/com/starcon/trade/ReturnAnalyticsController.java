package com.starcon.trade;

import static com.starcon.trade.ReturnAnalyticsModels.*;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 11.5.1.1 退货分析。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「统计退货情况：退货率（按店铺/SKU/类目/原因/批次）、
 * 退货金额趋势、拒退率、处理时效、平均退款金额与原因分布。」
 * 输出：「<b>退货分析报表 + 维度下钻 + 异常识别</b>。」
 * 加粗句：「<b>退货率必须与销售额同口径比较</b>，只看绝对数会误判。」
 *
 * <p><b>命名空间独立于 {@code /trade/quality-analysis}</b>（11.5.2.1 质量分析）、
 * {@code /trade/rma-authorizations}</b>（11.2.1.1 退货授权单）与
 * {@code /trade/refund-applications}</b>（11.3.1.1 退款申请）：
 * 那三个是<b>单据</b>，本模块是<b>聚合报表</b>。
 * 单据答「这一单怎么了」，本模块答「整体退得怎么样、哪些维度该先动」。
 * 两者形状不同，硬塞进一个命名空间只会让「单据列表」和「分析报表」互相污染。
 *
 * <p><b>权限码沿用</b> {@code trade.aftersales.read} / {@code trade.aftersales.write}：
 * 本模块是 11.5 售后分析锚点下的动作，与售后单同一业务面。
 *
 * <p><b>刻意不提供</b>：供应商评级写入（6.1.1.3）、跨系统经营分析（13）——
 * 二者<b>均未交付</b>，capabilities 逐条报 delivered=false 与归属模块。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 */
@RestController
@RequestMapping("/trade/return-analysis")
public class ReturnAnalyticsController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";

  private final AccessService access;
  private final ReturnAnalyticsService analytics;

  public ReturnAnalyticsController(AccessService access, ReturnAnalyticsService analytics) {
    this.access = access;
    this.analytics = analytics;
  }

  /** 统一读入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + READ);
    return access.run(actor, "trade." + READ, () -> work.apply(actor));
  }

  /** 写入口：需要 {@code trade.aftersales.write}。租户来源与读入口完全一致。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + WRITE);
    return access.run(actor, "trade." + WRITE, () -> work.apply(actor));
  }

  /** 期间参数。 */
  private ReturnAnalysisWindow window(String from, String to) {
    return new ReturnAnalysisWindow(parseDate(from, "from"), parseDate(to, "to"));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的每一项分析逐块报可用性与口径，
   * 上下游逐个报 delivered 与归属模块。
   */
  @GetMapping("/capabilities")
  public ReturnAnalysisCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> analytics.capabilities());
  }

  // ================================================================ 报表（只读）

  /** 退货分析总览（按币种分组）。 */
  @GetMapping("/overview")
  public ReturnAnalysisOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String currency) {
    return call(auth, actor -> analytics.overview(window(from, to), normalizeCurrency(currency)));
  }

  /** 维度下钻（店铺 / SKU / 类目 / 原因 / 批次）。 */
  @GetMapping("/breakdown")
  public ReturnBreakdownReport breakdown(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) Integer limit) {
    return call(
        auth,
        actor ->
            analytics.breakdown(
                window(from, to), dimension, normalizeCurrency(currency), limit));
  }

  /** 退货金额与退货率趋势（UTC 分桶）。 */
  @GetMapping("/trend")
  public ReturnTrendReport trend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String granularity) {
    return call(
        auth,
        actor -> analytics.trend(window(from, to), granularity, normalizeCurrency(currency)));
  }

  /** 异常识别（阈值随口径一起报出来）。 */
  @GetMapping("/anomalies")
  public ReturnAnomalyReport anomalies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String currency) {
    return call(
        auth, actor -> analytics.anomalies(window(from, to), normalizeCurrency(currency)));
  }

  // ================================================================ 快照

  /** 已保存的报表快照（追加式，默认按保存时间倒序）。 */
  @GetMapping("/snapshots")
  public ReturnAnalysisSnapshotPage snapshots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String dimension,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String snapshotNo,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        actor ->
            analytics.listSnapshots(
                dimension,
                normalizeCurrency(currency),
                snapshotNo,
                page == null ? 0 : page,
                size == null ? 20 : size));
  }

  /**
   * 保存一份退货分析报表快照。
   *
   * <p>⚠️ 快照是**追加式**的：算出来的每个维度值原样落库，
   * 三个月后「上个月那张报表是多少」才答得出来。
   */
  @PostMapping("/snapshots")
  public ReturnAnalysisSnapshotPage createSnapshot(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ReturnAnalysisSnapshotRequest request) {
    return write(auth, actor -> analytics.createSnapshot(request, actor.userId()));
  }

  // ================================================================ 口径

  /** 本租户登记的口径（默认只列生效版本）。 */
  @GetMapping("/basis")
  public ReturnAnalysisBasisPage basis(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        actor ->
            analytics.listBasis(
                normalizeCurrency(currency),
                status,
                page == null ? 0 : page,
                size == null ? 20 : size));
  }

  /**
   * 登记一份口径。
   *
   * <p>⚠️ 请求体里<b>没有</b> {@code basisStatus} / {@code effectiveTo}：
   * 建行恒为生效，且不预设截止日，截止日由换版时写。
   */
  @PostMapping("/basis")
  public ReturnAnalysisBasisRow createBasis(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ReturnAnalysisBasisCreate request) {
    return write(auth, actor -> analytics.createBasis(request, actor.userId()));
  }

  /** 口径换版（终态：老版本保留为已换版，且必须先写留痕）。 */
  @PostMapping("/basis/{id}/supersede")
  public ReturnAnalysisBasisRow supersedeBasis(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody ReturnAnalysisBasisSupersede request) {
    return write(auth, actor -> analytics.supersedeBasis(id, request, actor.userId()));
  }

  // ================================================================ 小工具

  /** 币种归一：空串按「不限」处理（null），避免传 {@code currency=} 被当成按空币种筛。 */
  private static String normalizeCurrency(String currency) {
    if (currency == null || currency.trim().isEmpty()) return null;
    return currency.trim().toUpperCase(java.util.Locale.ROOT);
  }

  /**
   * 期间参数的解析。
   *
   * <p>⚠️ 解析失败返回 400 而不是静默忽略：一个打错的 {@code from=2026-13-01}
   * 被当成「不筛期间」，用户会拿到一份全量报表还以为自己筛过了。
   */
  private static LocalDate parseDate(String value, String label) {
    if (value == null || value.trim().isEmpty()) {
      return null;
    }
    try {
      return LocalDate.parse(value.trim());
    } catch (RuntimeException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          label + " 必须是 yyyy-MM-dd 格式（收到 \"" + value.trim() + "\"）");
    }
  }
}
