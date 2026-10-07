package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.6.2.1 库存健康的传输模型。
 *
 * <p>蓝图原话：「库存健康度诊断：周转率、缺货率、超储率、呆滞占比、库存准确率、负库存发生次数，
 * 按仓库与类目打分并给出改进建议」「输出：健康度评分卡 + 趋势 + 问题清单与建议」
 * 「**库存准确率是硬指标**，低于阈值应阻断结算与对账——账不准时报表全是错的」。
 *
 * <p><b>本模块最重要的一条纪律：算不出来的指标必须如实标记为不可用，而不是拿别的数据凑一个数。</b>
 * 六个指标在本仓库当前能算的是两个（准确率、负库存发生次数），其余四个依赖尚未交付的上游。
 * 用库存自身数据拼一个假的周转率，比不给指标危害大得多 —— 它会被当真去调补货，
 * 而真正的缺口一个也没被修掉。所以 {@link InventoryHealthMetric} 有三个状态而不是一个值：
 *
 * <ul>
 *   <li>{@code AVAILABLE}：有真实数据源，取值可追溯到具体的核对批次 / 盘点单。
 *   <li>{@code UNAVAILABLE}：口径定死了，但上游模块未交付（{@code blockedByModule} 记着是谁）。
 *       交付后自动转成 AVAILABLE，不需要改表结构。
 *   <li>{@code NO_DATA}：上游已交付，但这个租户在这个窗口内没有可算的样本
 *       （没核过账、没盘点）。这与 UNAVAILABLE 是两回事，运营该做的动作也不同。
 * </ul>
 *
 * <p><b>评分口径</b>：分数只对 {@code AVAILABLE} 的指标加权，并同时给出
 * {@code availableMetricCount} / {@code totalMetricCount}。六个指标里只算得出一个时，
 * 那个分数必须让人一眼看出它不是"整体健康度"，而是"已覆盖维度上的健康度" ——
 * 否则一个 100 分会被当成"这个仓没问题"而掩盖另外五个维度根本没测。
 *
 * <p><b>扣分项可追溯</b>：每个 {@link InventoryHealthIssue} 都带 {@code evidence}，
 * 指向具体的核对批次 id / 盘点单号 / 仓编码。打分不是为了给一个好看的数字，
 * 而是让运营知道**先去看哪几行**。
 */
public final class InventoryHealthModels {

  private InventoryHealthModels() {}

  // ------------------------------------------------------------ 枚举

  /** 评估粒度。按仓库可算；按类目不可算（类目真相在 catalog，跨服务不得 join）。 */
  public enum HealthScopeType {
    TENANT("全部仓库"),
    WAREHOUSE("仓库");

    private final String label;

    HealthScopeType(String label) {
      this.label = label;
    }

    /** 中文标签。界面上用；契约与库里仍然是 ASCII 码值。 */
    public String label() {
      return label;
    }
  }

  /** 指标的可用状态。三值口径的含义差别见类注释。 */
  public enum MetricAvailability {
    AVAILABLE("可算"),
    UNAVAILABLE("上游未交付"),
    NO_DATA("窗口内无样本");

    private final String label;

    MetricAvailability(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 蓝图点名的六个指标。
   *
   * <p>每个枚举值都带一条 {@code basis}：它要么说清数据从哪来，要么说清为什么算不了。
   * 这条字符串会原样落进 API 响应，运营看的是它而不是猜。
   */
  public enum HealthMetricType {
    TURNOVER(
        "周转率",
        "依赖销售聚合（销售成本 ÷ 平均库存）。13 销量未交付，且 warehouse 不持有销售事实、"
            + "跨服务不得查它的表，因此本服务算不出真实周转率。",
        "13"),
    STOCKOUT_RATE(
        "缺货率",
        "缺货率的分母是「有需求却没货的次数」，需求必须来自销售/订单事实。13 销量与"
            + "9 订单均未交付；用「当前在库为 0」当分母会把从没卖过的 SKU 全算成缺货，是假指标。",
        "13"),
    OVERSTOCK_RATE(
        "超储率",
        "超储率衡量相对需求的超出量，需求基线来自销售聚合。13 销量未交付。"
            + "inventory_policy.max_on_hand 是一条**配置上限**，按它统计得到的是「超出配置上限的占比」，"
            + "与蓝图的超储率不是同一口径，因此不拿它冒充。",
        "13"),
    STAGNANT_SHARE(
        "呆滞占比",
        "呆滞需要库龄分桶（7.6.1 库龄与呆滞），该模块与本模块同批交付但尚未落库。"
            + "7.1.1.2 的 inventory_lot.received_on 虽可算库龄，但呆滞判定口径（分桶阈值、"
            + "处置分类）属于 7.6.1 的交付范围，此处不抢跑它的口径。",
        "7.6.1"),
    ACCURACY(
        "库存准确率",
        "数据源：7.1.2.2 账实核对的差异行占比（口径 A），以及 7.4.2.1 盘点单的"
            + "账面数=实盘数行占比（口径 B）。两者都真实可算，是本模块的硬指标。",
        null),
    NEGATIVE_STOCK(
        "负库存发生次数",
        "V1 inventory_balance 与 V2 stock_bucket 都有 CHECK(on_hand>=0)/CHECK(reserved>=0)/"
            + "CHECK(available>=0)，负库存在**结构上不可能发生**。本指标如实返回 0（可证明的 0），"
            + "并由集成测试实跑「DB 拒绝写入负库存」来证明，而不是省略这一行。",
        null);

    private final String label;
    private final String basis;
    private final String blockedByModule;

    HealthMetricType(String label, String basis, String blockedByModule) {
      this.label = label;
      this.basis = basis;
      this.blockedByModule = blockedByModule;
    }

    public String label() {
      return label;
    }

    /** 取值依据 / 不可用原因。会原样落进 API 响应。 */
    public String basis() {
      return basis;
    }

    /** 阻塞该指标的上游模块号；本指标可算时为 {@code null}。 */
    public String blockedByModule() {
      return blockedByModule;
    }

    /** 该指标是否依赖尚未交付的上游。 */
    public boolean blocked() {
      return blockedByModule != null;
    }

    /** 六个指标，全模块唯一一处清单。库里靠 CHECK 兜住，Java 侧靠这个枚举。 */
    public static List<HealthMetricType> all() {
      return List.of(values());
    }
  }

  /** 评级。UNVERIFIABLE 是真实状态：算得出的维度不足以支撑判断时，不该假装给个分数。 */
  public enum HealthGrade {
    HEALTHY("健康"),
    WATCH("关注"),
    RISK("风险"),
    UNVERIFIABLE("无法判定");

    private final String label;

    HealthGrade(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 问题严重度。CRITICAL 会直接触发准确率红线判定。 */
  public enum HealthIssueSeverity {
    INFO,
    WARN,
    CRITICAL;

    public String label() {
      return switch (this) {
        case INFO -> "提示";
        case WARN -> "警告";
        case CRITICAL -> "严重";
      };
    }
  }

  // ------------------------------------------------------------ 查询

  /**
   * 评估请求。
   *
   * <p>窗口缺省近 30 天，与 7.1.2.2 核对窗口同一套默认值，让"最近一次核对"落在窗口内。
   */
  public record InventoryHealthQuery(
      @Schema(nullable = true) OffsetDateTime from,
      @Schema(nullable = true) OffsetDateTime to,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) BigDecimal accuracyThreshold) {}

  // ------------------------------------------------------------ 输出

  /**
   * 单个指标的一行。
   *
   * <p>{@code value} 与 {@code availability} 必须配套（DB 侧 CHECK 已钉死）：
   * 不是 AVAILABLE 就没有值。{@code source} 说明这个值取自哪张表，
   * {@code numerator}/{@code denominator} 跟着值一起给，否则事后没人能算出这个分数是怎么来的。
   */
  public record InventoryHealthMetric(
      HealthMetricType metricType,
      String metricLabel,
      MetricAvailability availability,
      @Schema(nullable = true) BigDecimal value,
      @Schema(nullable = true) String source,
      @Schema(nullable = true) BigDecimal numerator,
      @Schema(nullable = true) BigDecimal denominator,
      BigDecimal deduction,
      String basis,
      @Schema(nullable = true) String blockedByModule) {}

  /** 一个范围（仓库或全租户）的评分卡。 */
  public record InventoryHealthScorecard(
      HealthScopeType scopeType,
      @Schema(nullable = true) UUID scopeKey,
      String scopeLabel,
      BigDecimal score,
      HealthGrade grade,
      String gradeLabel,
      /** 可算出的指标数 / 指标总数。两者不等时说明这是"部分维度得分"。 */
      int availableMetricCount,
      int totalMetricCount,
      @Schema(nullable = true) BigDecimal accuracyRate,
      @Schema(nullable = true) BigDecimal accuracyThreshold,
      boolean accuracyPassed,
      boolean blocked,
      String blockReason,
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo,
      OffsetDateTime evaluatedAt,
      List<InventoryHealthMetric> metrics) {}

  /** 评分卡列表 + 汇总（汇总不受分页影响）。 */
  public record InventoryHealthScorecardPage(
      List<InventoryHealthScorecard> scorecards, long total, int page, int size) {}

  /**
   * 一条问题。
   *
   * <p>{@code evidence} 是可追溯性的落点：每条问题都要指出"先去看哪一行"，
   * 而不是只给一句评价。
   */
  public record InventoryHealthIssue(
      HealthIssueSeverity severity,
      HealthMetricType metricType,
      String scopeLabel,
      String title,
      String detail,
      String recommendation,
      String evidence) {}

  /** 问题清单（未结问题，按严重度与范围排序）。 */
  public record InventoryHealthIssuePage(
      List<InventoryHealthIssue> issues, long total, int criticalCount, int page, int size) {}

  /**
   * 趋势上的一点：某时点的准确率。
   *
   * <p>两个来源各自成序列，不混：核对口径按 {@code inventory_reconciliation_run.created_at}，
   * 盘点口径按 {@code stocktake_sheet.frozen_at}。混成一条线会让人以为「盘了一次就等于核了一次」。
   */
  public record InventoryHealthTrendPoint(
      String source,
      @Schema(nullable = true) UUID sourceId,
      String sourceLabel,
      OffsetDateTime at,
      @Schema(nullable = true) BigDecimal accuracy,
      long sampleCount,
      long discrepancyCount) {}

  public record InventoryHealthTrend(
      HealthScopeType scopeType,
      @Schema(nullable = true) UUID scopeKey,
      String scopeLabel,
      OffsetDateTime windowFrom,
      OffsetDateTime windowTo,
      List<InventoryHealthTrendPoint> points) {}

  /**
   * 准确率红线判定结果。
   *
   * <p><b>本记录只是判定，不是执行。</b>蓝图说低于阈值「应阻断结算与对账」，
   * 真正的阻断由 12 财务结算与 7.1.2.2 对账各自在自己的边界内执行；
   * warehouse 无权替它们阻断，也不应该知道它们的单据状态。
   * 本服务提供的是一个**可查询、可复现、带证据**的判定，接口边界见类注释。
   */
  public record InventoryHealthGate(
      HealthScopeType scopeType,
      @Schema(nullable = true) UUID scopeKey,
      String scopeLabel,
      MetricAvailability accuracyState,
      @Schema(nullable = true) BigDecimal accuracyRate,
      BigDecimal accuracyThreshold,
      boolean blocked,
      String blockReason,
      String basis,
      @Schema(nullable = true) OffsetDateTime lastReconciledAt,
      @Schema(nullable = true) OffsetDateTime lastStocktakenAt,
      OffsetDateTime evaluatedAt,
      List<InventoryHealthIssue> issues) {}

  /** 评估执行结果：新落库的评分卡 + 判定。 */
  public record InventoryHealthAssessed(
      InventoryHealthScorecard tenant, List<InventoryHealthScorecard> warehouses, InventoryHealthGate gate) {}
}