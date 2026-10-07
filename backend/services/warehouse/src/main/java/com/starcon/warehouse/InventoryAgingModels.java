package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 7.6.1.1 库龄与呆滞的传输模型。
 *
 * <p>蓝图原文：「按入库时间计算库龄分布，识别滞销、临期、残次、呆滞库存，给出处置建议
 * （降价促销、清仓、退仓、报废）」，输入「批次/入库时间；当前可用量；销售速度；品类特性」，
 * 输出「库龄分桶报表（0-30/31-60/61-90/90+ 天）+ 呆滞清单 + 占用资金估算」。
 *
 * <p><b>类型名前缀是刻意选的</b>：契约 codegen 一次拍平全部四个服务的 schema 到同一命名空间，
 * 重名的 DTO 会被静默覆盖且没有任何警告。本模块每个类型都带 {@code Aging} 或
 * {@code SlowMoving} 前缀，让「重名覆盖」在物理上不可能发生。
 *
 * <p><b>库龄是「天数」不是时间戳</b>，按日期语义算（{@link LocalDate}）：
 * {@code current_date - 入库日} 在 PG 里返回 integer，直接用 {@link LocalDate} 承载
 * 一个「已经过去多少天」的量是类型说谎 —— 拿到 2026-10-07 的人无法分辨它是入库日还是今天。
 *
 * <p><b>「销售速度」尚未交付</b>（13 销量卷），且跨服务禁止读对方表。
 * 所以本模块的滞销/呆滞判定**只用库龄 + 可用量 + 质量状态 + 效期**四个已交付维度，
 * 并在 {@link SlowMovingRow#basis} 里如实写出判定依据，让使用方知道这个结论缺了速度因子。
 * 不假装用了销售速度 —— 那样得到的「周转天数」会是一个查无此表的假数字。
 */
public final class InventoryAgingModels {

  private InventoryAgingModels() {}

  // ================================================================ 词表

  /**
   * 库龄分桶。
   *
   * <p><b>四个桶的边界是闭区间，且互不重叠</b>（用测试钉住，见 InventoryAgingIntegrationTest）：
   *
   * <table>
   *   <caption>边界定义</caption>
   *   <tr><th>档位</th><th>库龄天数</th></tr>
   *   <tr><td>{@link #AGE_0_30}</td><td>0 ~ 30（含 30 当天）</td></tr>
   *   <tr><td>{@link #AGE_31_60}</td><td>31 ~ 60</td></tr>
   *   <tr><td>{@link #AGE_61_90}</td><td>61 ~ 90</td></tr>
   *   <tr><td>{@link #AGE_90_PLUS}</td><td>91 及以上</td></tr>
   * </table>
   *
   * <p><b>蓝图写的「90+」在本实现里是「90 天的量归 61-90 桶，91 天起才叫 90+」</b>。
   * 理由：若让 90 同时落进 61-90 与 90+ 两桶，则分桶报表的行合计会大于总库存，
   * 而一个「各桶加起来等于总量」的报表是呆滞处置唯一能被信任的前提。
   * 标签仍显示「90+ 天」，但真实下界是 91 天。
   *
   * <p>{@link #NO_INBOUND_DATE} 是一档**不适用**而不是第五个分桶：
   * 它表示「这一行算不出库龄」，必须与「库龄 0 天」分得开 ——
   * 后者是最新的库存，前者是数据缺失，混在一起会让报表凭空少一块库存。
   */
  public enum AgingBucket {
    AGE_0_30("0-30 天", 0, 30),
    AGE_31_60("31-60 天", 31, 60),
    AGE_61_90("61-90 天", 61, 90),
    AGE_90_PLUS("90+ 天", 91, Integer.MAX_VALUE),
    NO_INBOUND_DATE("无入库日期", -1, -1);

    private final String label;
    private final int lowerDays;
    private final int upperDays;

    AgingBucket(String label, int lowerDays, int upperDays) {
      this.label = label;
      this.lowerDays = lowerDays;
      this.upperDays = upperDays;
    }

    public String label() {
      return label;
    }

    public int lowerDays() {
      return lowerDays;
    }

    public int upperDays() {
      return upperDays;
    }

    /** 分桶是唯一的真相来源：SQL 侧的 CASE 与 Java 侧的枚举共用同一组边界。 */
    public static AgingBucket of(int agingDays) {
      if (agingDays < 0) return NO_INBOUND_DATE;
      for (AgingBucket b : values()) {
        if (b != NO_INBOUND_DATE && agingDays >= b.lowerDays && agingDays <= b.upperDays) {
          return b;
        }
      }
      return AGE_90_PLUS;
    }
  }

  /**
   * 库龄天数**取自哪个时间列**。
   *
   * <p>库龄必须按真实入库时间算，用「建行时间」或「最近变动时间」糊弄出来的数字
   * 会让整张报表失去意义，所以这个口径必须能被审阅、能在界面上被看见：
   *
   * <ul>
   *   <li>{@link #LOT_RECEIVED_ON}：该 bucket 挂了批次，取 {@code inventory_lot.received_on}
   *       （收货业务日，V2 定义）。这是**真实的入库日期**，是库龄的正口径。
   *   <li>{@link #BUCKET_CREATED_AT}：非批次管理品（{@code lot_id} 为空），仓里没有收货日可查，
   *       退回 {@code stock_bucket.created_at::date}。这是**系统建档日**，是入库日的代理值。
   * </ul>
   *
   * <p><b>刻意不用 {@code stock_bucket.last_movement_at} 算库龄</b>：它是「最近一次数量变动的
   * 业务时间」，每出库一票就前移。拿它算库龄等于把「最近被碰过多久」当成「躺了多久」——
   * 一批每天出 10 件的滞销品会被算成库龄 0 天，永远不进呆滞清单。
   * 7.3.1.2 排 FIFO 时也刻意不用它，理由完全一致。
   */
  public enum AgingDayBasis {
    LOT_RECEIVED_ON("批次收货日"),
    BUCKET_CREATED_AT("库位建档日（代理）");

    private final String label;

    AgingDayBasis(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 呆滞档位。三个档互斥，顺序即严重程度。
   *
   * <p>{@link #OBSOLETE} 判定条件比 {@link #SLOW_MOVING} 更严，所以「呆滞是滞销的子集」这条
   * 不变式在类型上成立（DB 的 {@code obsolete_days>=slow_moving_days} CHECK 兜住配置侧）。
   */
  public enum SlowMovingStatus {
    OBSOLETE("呆滞", "库龄已达呆滞线"),
    SLOW_MOVING("滞销", "库龄已达滞销线"),
    NORMAL("正常", "库龄未达滞销线");

    private final String label;
    private final String hint;

    SlowMovingStatus(String label, String hint) {
      this.label = label;
      this.hint = hint;
    }

    public String label() {
      return label;
    }

    public String hint() {
      return hint;
    }
  }

  /**
   * 处置建议。
   *
   * <p>四个动作对应蓝图的「降价促销、清仓、退仓、报废」。优先级从上到下：
   * <b>过期 > 残次 > 临期 > 呆滞 > 滞销</b> —— 越不可挽回的越靠前，
   * 因为一票已经过期的货不该被归类成「建议促销」，促销只会让它在货架上再烂 30 天。
   *
   * <p><b>本枚举只是建议，不是动作</b>：调价属 5.4.1、退仓与报废属 6.4.2.2，
   * 两者都未交付，本模块**不**声称能自动触发。
   */
  public enum SlowMovingDisposition {
    SCRAP("报废", "已过期或残次，无法再销售", 100),
    RETURN_TO_VENDOR("退仓", "残次且供应商可退，应优先退货而非报废", 90),
    MARKDOWN("降价促销", "尚未过期且可售，应尽快变现", 70),
    CLEARANCE("清仓", "库龄远超呆滞线，按库存年龄折价清空", 60),
    HOLD("暂不处置", "尚未命中任何处置条件", 0);

    private final String label;
    private final String rationale;
    private final int priority;

    SlowMovingDisposition(String label, String rationale, int priority) {
      this.label = label;
      this.rationale = rationale;
      this.priority = priority;
    }

    public String label() {
      return label;
    }

    public String rationale() {
      return rationale;
    }

    public int priority() {
      return priority;
    }
  }

  // ================================================================ 查询入参

  /** 库龄分桶报表查询。 */
  public record AgingBucketQuery(
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) String agingBasis,
      @Schema(nullable = true) String keyword) {}

  /** 呆滞清单查询。 */
  public record SlowMovingQuery(
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) List<String> slowMovingStatus,
      @Schema(nullable = true) String disposition,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  // ================================================================ 库龄分桶报表

  /**
   * 一个库龄桶的聚合行。
   *
   * @param bucket 档位
   * @param skuCount 该桶命中的 SKU 数（同一 SKU 可横跨多桶，所以各桶 skuCount 不可直接相加）
   * @param onHand 在手量、reserved 已预占、available 可用量（三者口径与 7.1.1.2 bucket 逐列一致）
   * @param bucketCount 命中的 bucket 行数
   * @param lotBasedBucketCount 其中有多少行是按真实收货日算的（余下为建档日代理值）
   */
  public record AgingBucketRow(
      String bucket,
      String label,
      Integer lowerDays,
      Integer upperDays,
      long skuCount,
      long bucketCount,
      long lotBasedBucketCount,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available) {}

  /**
   * 库龄报表的**合计行**。
   *
   * <p>{@code available} 必须等于四个桶之和（不加 NO_INBOUND_DATE 之外的任何漏项）；
   * 这一条不变式由集成测试直接断言 —— 一个「各桶加起来对不上」的库龄报表没人敢用来定清仓价。
   */
  public record AgingTotals(
      long skuCount, long bucketCount, BigDecimal onHand, BigDecimal reserved, BigDecimal available) {}

  /**
   * 资金占用估算的**诚实回答**。
   *
   * <p>蓝图输出里有「占用资金估算」，但 warehouse 服务的库存事实层没有任何成本列
   * （已 grep 全仓确认：无 unit_cost / avg_cost / cost_amount / unit_price / moving_average），
   * 成本口径属 finance 卷且跨服务禁止读对方表。
   *
   * <p>所以这里**返回不可用 + 原因，而不是编一个成本价**：
   * {@code available=false} 时 {@code occupiedAmount} 恒为 null，
   * 界面上必须显示 {@code reason} 而不是显示 0 元 ——
   * 一个 0 元会被读成「这些呆滞货不占资金」，那比报错危险得多。
   */
  public record AgingCapitalTie(
      boolean available,
      @Schema(nullable = true) BigDecimal occupiedAmount,
      @Schema(nullable = true) String currency,
      String reason,
      String blockedBy) {}

  /**
   * 库龄分桶报表。
   *
   * @param asOf 报表的「今天」。库龄每天都在变，所以这个日期必须随响应返回 ——
   *     一张不告诉你算到哪天的报表，存到 Excel 里三天后就没法复现了
   * @param defaultSlowMovingDays 兜底滞销线（该 SKU 未配策略时用）
   * @param distinctSlowMovingDaysCount 本次报表里实际用到的**不同滞销线数量**。
   *     它 > 1 说明租户给不同 SKU 配了不同阈值 —— 这不是错误，
   *     但如果没人知道，看到跨桶数字不一致会以为报表算错了
   * @param capitalTie 资金占用估算的诚实回答（见 {@link AgingCapitalTie}）
   */
  public record AgingBucketReport(
      LocalDate asOf,
      List<AgingBucketRow> buckets,
      AgingTotals totals,
      int defaultSlowMovingDays,
      int defaultObsoleteDays,
      long distinctSlowMovingDaysCount,
      AgingCapitalTie capitalTie,
      String agingBasisNote) {}

  // ================================================================ 呆滞清单

  /**
   * 呆滞清单的一行 = 一个 bucket（批次 × 库位粒度）。
   *
   * <p>粒度取 bucket 而非 SKU，因为处置是**按批次按库位**做的：
   * 同一 SKU 的两个批次一个刚到货一个躺了半年，把它们合成一行会让「该处理哪个批次」这个问题无解。
   *
   * @param agingDays 库龄天数（{@code current_date - 入库日}）。无入库日期时为 null
   * @param agingBucket 库龄档位
   * @param agingBasisDays 该行库龄取自哪个时间列
   * @param slowMovingDays 本行实际用的滞销线（该 SKU 配了策略就用配的）
   * @param slowMovingDaysSource 阈值来源：POLICY = 该 SKU 配过；DEFAULT = 用的默认值
   * @param slowMovingStatus 呆滞档位
   * @param disposition 建议的处置动作
   * @param basis 判定依据的**自然语言**表述。给处置的人看的是这句话，
   *     所以必须能独立读懂（而不是让人再去翻配置看阈值）
   * @param automatable 恒为 false：本模块不触发任何写操作。
   *     调价属 5.4.1、退仓报废属 6.4.2.2，二者未交付
   */
  public record SlowMovingRow(
      UUID bucketId,
      UUID skuId,
      String skuCode,
      String skuName,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) String lotCode,
      UUID warehouseId,
      String warehouseCode,
      UUID locationId,
      String locationCode,
      UUID qualityStatusId,
      String qualityStatusCode,
      String qualityRole,
      @Schema(nullable = true) LocalDate receivedOn,
      @Schema(nullable = true) LocalDate inboundDate,
      String agingBasis,
      String agingBasisLabel,
      @Schema(nullable = true) Integer agingDays,
      String agingBucket,
      String agingBucketLabel,
      int slowMovingDays,
      int obsoleteDays,
      String slowMovingDaysSource,
      String slowMovingStatus,
      String slowMovingStatusLabel,
      @Schema(nullable = true) Integer daysToExpiry,
      String expiryStatus,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      String disposition,
      String dispositionLabel,
      String dispositionRationale,
      String basis,
      boolean automatable) {}

  /** 呆滞清单汇总（**全量**，不受分页与档位筛选影响）。 */
  public record SlowMovingSummary(
      long skuCount,
      long bucketCount,
      long obsoleteCount,
      long slowMovingCount,
      BigDecimal obsoleteAvailable,
      BigDecimal slowMovingAvailable,
      BigDecimal obsoleteAndUpAvailable,
      @Schema(nullable = true) Integer oldestAgingDays,
      @Schema(nullable = true) LocalDate oldestInboundDate) {}

  /** 呆滞清单页。 */
  public record SlowMovingPage(
      SlowMovingSummary summary,
      List<SlowMovingRow> rows,
      List<SlowMovingStatusOption> statusOptions,
      List<SlowMovingDispositionOption> dispositionOptions,
      int defaultSlowMovingDays,
      int defaultObsoleteDays,
      String agingBasisNote,
      String salesVelocityNote,
      long total,
      int page,
      int size) {}

  /** 呆滞档位下拉项，带命中行数。 */
  public record SlowMovingStatusOption(String value, String label, long bucketCount) {}

  /** 处置建议下拉项，带命中行数。 */
  public record SlowMovingDispositionOption(String value, String label, long bucketCount) {}

  // ================================================================ 阈值策略

  /** 库龄阈值策略查询。 */
  public record AgingPolicyQuery(
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  /**
   * 阈值策略建立/修改请求。
   *
   * @param slowMovingDays 滞销线（天）。必须 &lt;= obsoleteDays，DB 的 CHECK 也这么判
   * @param obsoleteDays 呆滞线（天）
   */
  public record AgingPolicyUpsertRequest(
      @Schema(nullable = true) UUID skuId,
      Integer slowMovingDays,
      Integer obsoleteDays,
      Boolean enabled,
      @Schema(nullable = true) String notes) {}

  /** 阈值策略一行。 */
  public record AgingPolicyRow(
      UUID policyId,
      UUID skuId,
      String skuCode,
      String skuName,
      Integer slowMovingDays,
      Integer obsoleteDays,
      Boolean enabled,
      long bucketCount,
      BigDecimal onHand,
      String notes,
      java.time.OffsetDateTime updatedAt) {}

  /** 单个 SKU 的生效策略（配了就用配的，没配用默认值并标 configured=false）。 */
  public record AgingPolicyEffective(
      UUID skuId,
      String skuCode,
      String skuName,
      int slowMovingDays,
      int obsoleteDays,
      boolean enabled,
      boolean configured,
      String source,
      @Schema(nullable = true) Long bucketCount,
      @Schema(nullable = true) String notes) {}

  /** 阈值策略列表页。 */
  public record AgingPolicyPage(
      List<AgingPolicyRow> rows,
      int defaultSlowMovingDays,
      int defaultObsoleteDays,
      long total,
      int page,
      int size) {}
}
