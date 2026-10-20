package com.starcon.channel;

import static com.starcon.channel.LogisticsTimelinessModels.*;

import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 10.6.2.1 时效分析。
 *
 * <p>端点按蓝图模块号标注，方便对照 10-logistics-and-freight.md。
 *
 * <p>🔴 形状：{@code /capabilities}（能力声明）→ {@code /overview}（全量概览）
 * → 报表（{@code /reports/**}）→ 明细（{@code /subjects}）。
 *
 * <p>🔴 前缀是 {@code /channel/logistics-timeliness}，**不是** {@code fulfillment/timeliness}：
 * 后者是 trade 服务 9.5.1.1 订单时效的形状，两者不是同一件事，也不是同一个服务。
 *
 * <p>本控制器<b>只有一个写入口</b>：{@code POST /reports/score-snapshots}
 * （重算并落库时效评分台账）。其余全是只读报表。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}
 * （与 10.1.1.2 渠道配置、10.1.1.3 时效与限制、10.3.1.1 轨迹查询同族），
 * 不需要改动 identity 服务。
 */
@RestController
@RequestMapping("/channel/logistics-timeliness")
public class LogisticsTimelinessController {

  private final AccessService access;
  private final LogisticsTimelinessService timeliness;

  public LogisticsTimelinessController(AccessService access, LogisticsTimelinessService timeliness) {
    this.access = access;
    this.timeliness = timeliness;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  @GetMapping("/capabilities")
  public LogisticsTimelinessCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> timeliness.capabilities());
  }

  /** 概览：<b>全量口径</b>，不受列表筛选与分页影响。 */
  @GetMapping("/overview")
  public LogisticsTimelinessOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return call(auth, "channel.operation.read", a -> timeliness.overview(from, to));
  }

  // ---- 报表 ----

  /**
   * 🔴 交叉报表：一行 = <b>一个渠道 × 一个国家</b>。
   *
   * <p>蓝图加粗句「时效数据要按渠道和国家交叉分析」的落点：这一组端点<b>没有</b>
   * 「只按渠道」或「只按国家」的形状，总体口径只在 {@code totals} 里出现。
   */
  @GetMapping("/reports/matrix")
  public TimelinessMatrixPage matrix(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String channelId,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false, defaultValue = "OVERDUE_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> timeliness.matrix(from, to, channelId, countryCode, sort, page, size));
  }

  /** 月度趋势：旺季变化（按 UTC 月分桶）。 */
  @GetMapping("/reports/monthly")
  public TimelinessMonthlyReport monthly(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String channelId,
      @RequestParam(required = false) String countryCode) {
    return call(
        auth,
        "channel.operation.read",
        a -> timeliness.monthly(from, to, channelId, countryCode));
  }

  /** 异常集中分析：哪些「渠道 × 国家」的格贡献了大部分超时。 */
  @GetMapping("/reports/anomalies")
  public TimelinessAnomalyReport anomalies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return call(auth, "channel.operation.read", a -> timeliness.anomalies(from, to));
  }

  /** 渠道时效评分（实时计算，按渠道 × 国家）。 */
  @GetMapping("/reports/scores")
  public TimelinessScorePage scores(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String channelId,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> timeliness.scores(from, to, channelId, countryCode, page, size));
  }

  /** 时效评分台账（已落库的快照，真分页 + 真筛选）。 */
  @GetMapping("/reports/score-snapshots")
  public TimelinessScoreSnapshotPage scoreSnapshots(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String channelId,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> timeliness.scoreSnapshots(from, to, channelId, countryCode, page, size));
  }

  /**
   * 重算并落库时效评分（<b>唯一写入口</b>，幂等）。
   *
   * <p>同一档窗口重算一次 = 先删后插（同一事务），不会堆出第二份快照。
   */
  @PostMapping("/reports/score-snapshots")
  public TimelinessScoreSnapshotResult snapshotScores(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TimelinessSnapshotRequest request) {
    return call(
        auth,
        "channel.operation.create",
        a ->
            timeliness.snapshotScores(
                request == null ? null : request.from(),
                request == null ? null : request.to(),
                request == null ? null : request.minSampleThreshold()));
  }

  // ---- 明细 ----
  /** 逐票时效明细（真分页 + 真筛选）：想核实某一格为什么超时，往这里看。 */
  @GetMapping("/subjects")
  public TimelinessSubjectPage subjects(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) String channelId,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> timeliness.subjects(from, to, channelId, countryCode, status, page, size));
  }
}
