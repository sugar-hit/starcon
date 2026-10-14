package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 8.5.2.1 库位利用率的传输模型。
 *
 * <p>蓝图原文：「分析库位占用：容量利用率、周转率、空置库位、爆满库位、低效库位，给出调整建议。
 * 输入：库位与库存占用；出库频次。输出：利用率报表 + 热力图 + 建议。」关系段：「上游 = 8.1.2.1；
 * 下游 = 8.1.2.2 自动分配优化。<b>库位热力数据同时优化拣货路径</b>（高频商品放近出口），一举两得。」
 *
 * <p><b>本模块不建新表</b>，理由写在 V109 留空的说明里：容量来自 8.1.2.1 的
 * {@code warehouse_location.capacity}（V105），占用来自 {@code stock_bucket}，出库频次来自
 * {@code inventory_transaction / inventory_entry}（append-only 事实）。建议是**读时派生**的，
 * 存下来只会立刻变陈旧。
 *
 * <p><b>命名空间：全部 {@code LocationUtilization*} 前缀。</b>springdoc 用 record 的
 * <b>简单类名</b>做 {@code components.schemas} 的键，而本仓库已有 {@code Occupancy} /
 * {@code LocationRow} / {@code LocationTotals} / {@code Turnover} 这几个裸名（8.1.x 与
 * InventoryDistributionService）。重名会被<b>静默覆盖</b>：别人的字段形状被改掉，
 * 单测全绿、只有跨服务的契约比对才红。所以这里一个裸名都不复用。
 *
 * <p><b>分母为 0 的边界一律给 null，绝不给 NaN、绝不假装 100%。</b>三处：
 *
 * <ul>
 *   <li>容量未量（{@code capacity IS NULL}）→ 利用率 null，{@code capacityMeasured=false}；
 *   <li>容量量出来是 0 → 利用率 null，{@code capacityZero=true}。<b>不能</b>给 0
 *       （「容量是 0」意味着这个库位装不下东西，与「装满了」是两回事），
 *       也<b>不能</b>给 100%（那是在替物理事实编一个数）；
 *   <li>在手为 0 → 周转率 null，{@code turnoverComputable=false}。
 *       出库 0 件除以在手 0 件不是「0 倍」，是<b>没有定义</b>。
 * </ul>
 *
 * <p><b>出库频次的归集粒度必须说破。</b>{@code inventory_entry} 记的是
 * <b>balance 粒度</b>（仓库 × SKU × 法人 × 质量状态），表上<b>没有 location_id</b> ——
 * 货从哪个库位拣走的这件事，系统里没有任何一张表在记。所以本模块的出库量是
 * 「按 SKU 在手占比把仓库级出库量<b>摊分</b>到库位」的结果：
 *
 * <pre>
 *   库位出库量 = Σ_SKU (该 SKU 本仓出库量 × 该库位占该 SKU 在手的比例)
 * </pre>
 *
 * 这个摊分让**库位出库量之和恰好等于仓库真实出库量**（不重复计数），而
 * 「这个库位上的货有多久没动」仍然是真的。粒度写在 {@link
 * LocationUtilizationCapabilities#outboundFrequencyGrain} 里随响应返回，
 * 界面上要能让人看见，不靠文档口口相传。
 */
public final class LocationUtilizationModels {

  private LocationUtilizationModels() {}

  /** 统计窗口的默认值（天）。出库频次与周转率都落在这个窗口上。 */
  public static final int DEFAULT_WINDOW_DAYS = 30;

  /** 窗口上限：再长的窗口对「库位该怎么摆」已经失去意义（货早该动过了）。 */
  public static final int MAX_WINDOW_DAYS = 365;

  // ------------------------------------------------------------ 枚举

  /**
   * 库位利用率的档位。
   *
   * <p><b>判定顺序是口径的一部分</b>，写在 {@link #classify} 里并且有测试钉住：
   * 先看有没有货，再看容量，最后才看比例。理由是「空置」是一个<b>不依赖容量</b>就成立的
   * 运营结论 —— 一个从没量过容量的空库位，毫无疑问是空置库位，值得被建议覆盖。
   * 反过来先判容量的话，存量库位（大量 capacity 为 NULL）会全部落进
   * {@link #UNMEASURED}，而报表会因此看不见真正该处理的空库位。
   */
  public enum LocationUtilizationGrade {
    /** 爆满：在手已超量得的容量（利用率 &gt; 100%）。 */
    OVERFULL("爆满"),
    /** 高位：利用率 85% ~ 100%，还没超但没余量了。 */
    HIGH("高位"),
    /** 适中：30% ~ 85%。 */
    BALANCED("适中"),
    /** 低位：有余量但放得不多（0% &lt; 利用率 &lt; 30%）。 */
    LOW("低位"),
    /** 空置：一个 SKU 都没有。 */
    IDLE("空置"),
    /** 未量容量：有货，但这个库位的容量从来没量过，用率算不出来。 */
    UNMEASURED("未量容量");

    private final String label;

    LocationUtilizationGrade(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 建议的种类。
   *
   * <p>每一类都由<b>真实数据</b>触发，没有一条是「凑够六种类型」的填充项 ——
   * 触发条件写在 SQL 与服务层注释里，没有数据就不会出现。
   */
  public enum LocationUtilizationAdviceKind {
    /** 爆满库位：在手超容量。建议扩容或分流。 */
    OVERFULL_LOCATION("爆满库位"),
    /** 空置库位：一个 SKU 都没有却占着地方。 */
    IDLE_LOCATION("空置库位"),
    /** 低效库位：压着货但窗口内一件没出。 */
    INEFFICIENT_LOCATION("低效库位"),
    /** 高频货在不可拣库位：出库频次高却放进了不可拣的库位。 */
    HIGH_FREQUENT_NON_PICKABLE("高频货在不可拣库位"),
    /** SKU 分散：同一个 SKU 散在多个库位，一次拣货要走好几趟。 */
    SKU_DISPERSION("SKU 分散"),
    /** 容量未量：这个库位的容量从来没量过，利用率无从谈起。 */
    UNMEASURED_LOCATION("容量未量");

    private final String label;

    LocationUtilizationAdviceKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 建议的处置优先级。 */
  public enum LocationUtilizationSeverity {
    /** 先处理：货已经压爆了 / 高频货放错了地方。 */
    HIGH("优先处理"),
    /** 需要关注：不紧急但会持续变差。 */
    MEDIUM("需要关注"),
    /** 补数据：缺的是度量本身，不是货。 */
    LOW("补数据");

    private final String label;

    LocationUtilizationSeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 热力图格子的强度来源。
   *
   * <p>热力图<b>不许画死色块</b>：每个格子都要能说出它的颜色是从哪个真实指标来的。
   * 前端只负责把 {@code heat}（0..100）映射成色阶，映射规则在前端、取值在服务端。
   */
  public enum LocationUtilizationMetric {
    /** 容量利用率 × 100，夹在 0..100。 */
    CAPACITY_UTILIZATION("容量利用率"),
    /** 容量量不出来，退回「在手量相对最忙库位」的比例 × 100。 */
    ON_HAND_RELATIVE("在手量相对值"),
    /** 空置库位：强度恒为 0（它没有货，「相对值」也该是 0 而不是去比一个空的分母）。 */
    IDLE("空置");

    private final String label;

    LocationUtilizationMetric(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 查询入参

  /**
   * 报表查询条件。
   *
   * @param windowDays 出库频次与周转率的统计窗口（天），1..{@value #MAX_WINDOW_DAYS}，
   *     缺省 {@value #DEFAULT_WINDOW_DAYS}
   * @param grade 只看某一档位，null = 全档
   * @param type 只看某一库位类型，null = 全类型
   * @param keyword 编码或名称的模糊匹配
   * @param minUtilization 只看利用率不低于此值的库位。<b>null = 不筛</b>；
   *     刻意<b>不</b>提供「只看未量/容量为 0」的第二条支路 —— 那两类的利用率恒为 null，
   *     用一个下限筛选天然就把它们排除了，需要看它们就按 grade=UNMEASURED 筛
   * @param pickableOnly 只看可拣库位
   */
  public record LocationUtilizationQuery(
      @Schema(nullable = true) Integer windowDays,
      @Schema(nullable = true) String grade,
      @Schema(nullable = true) String type,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) BigDecimal minUtilization,
      @Schema(nullable = true) Boolean pickableOnly) {}

  // ------------------------------------------------------------ 输出

  /**
   * 利用率报表（一个仓库一份）。
   *
   * @param locations 逐库位明细，**按「利用率降序 + 编码」排序**：爆满的排在最前，
   *     该动的地方不用翻到最后一页
   * @param totals 全仓合计，<b>不受筛选影响</b>（所以筛完只看得到一部分行时，
   *     顶部合计仍是全仓真相）
   * @param heatmap 热力图格子，与 {@code locations} 同源同粒度，只是换一种呈现
   * @param advice 调整建议，按严重度降序
   */
  public record LocationUtilizationReport(
      UUID warehouseId,
      int windowDays,
      List<LocationUtilizationRow> locations,
      LocationUtilizationTotals totals,
      List<LocationUtilizationHeatCell> heatmap,
      List<LocationUtilizationAdvice> advice,
      String note) {}

  /**
   * 单个库位的利用率明细。
   *
   * @param capacityUtilization 容量利用率。null = 容量未量（{@code capacityMeasured=false}）
   *     或容量为 0（{@code capacityZero=true}）—— 两种情况都<b>不</b>给 0，理由见文件头
   * @param outboundMovements 窗口内与本库位所放 SKU 相关的出库分录<b>去重计数之和</b>。
   *     注意它是「(SKU × 出库单) 对」的计数，不等于出库单张数 ——
   *     一张单同时发本库位的两个 SKU 会计 2。两个口径都真实，混用会得出
   *     「这个库位比全仓还忙」这种不可能的结论，所以这里写明。
   * @param turnover 周转率 = 摊分到本库位的出库量 ÷ 在手量。
   *     <b>可空</b>：在手为 0 时没有定义（{@code turnoverComputable=false}）
   * @param dwellDays 在手天数（距最近一次库存变动）。没有库存行时为 null ——
   *     「从没放过货」与「放了很久没动」是两件事
   */
  public record LocationUtilizationRow(
      UUID id,
      String code,
      String name,
      String type,
      @Schema(nullable = true) String typeLabel,
      String status,
      boolean pickable,
      @Schema(nullable = true) UUID parentId,
      @Schema(nullable = true) BigDecimal capacity,
      BigDecimal onHand,
      BigDecimal reserved,
      @Schema(nullable = true) BigDecimal capacityUtilization,
      boolean capacityMeasured,
      boolean capacityZero,
      boolean capacityExceeded,
      BigDecimal outboundQuantity,
      long outboundMovements,
      @Schema(nullable = true) BigDecimal turnover,
      boolean turnoverComputable,
      long skuCount,
      long skuDispersion,
      @Schema(nullable = true) BigDecimal dwellDays,
      LocationUtilizationGrade grade,
      @Schema(nullable = true) String gradeLabel,
      int heat) {}

  /**
   * 全仓合计。
   *
   * <p><b>容量利用率的合计只在「全部库位都量过容量」时才有意义</b>，否则给 null：
   * 把已量的容量相加当分母，会算出一个没人测过的比例（与 8.1.2.1 的 {@code TreeTotals}
   * 同一纪律 —— 那里是「刻意不给合计利用率」，这里给出条件并说明条件）。
   * 部分测量时 {@code capacityMeasuredCount < locationCount}，前端据此显示
   * 「仅覆盖 N/M 个库位」而不是一个假装完整的百分比。
   */
  public record LocationUtilizationTotals(
      long locationCount,
      long capacityMeasuredCount,
      long capacityZeroCount,
      long idleCount,
      long overfullCount,
      long highCount,
      long lowCount,
      long balancedCount,
      long unmeasuredCount,
      long pickableCount,
      BigDecimal capacity,
      BigDecimal onHand,
      BigDecimal reserved,
      @Schema(nullable = true) BigDecimal capacityUtilization,
      boolean utilizationComputable,
      BigDecimal outboundQuantity,
      long outboundMovements,
      @Schema(nullable = true) BigDecimal turnover,
      boolean turnoverComputable,
      long skuCount) {}

  /**
   * 热力图的一个格子。
   *
   * <p>刻意<b>不</b>给 RGBA / 色值字符串：颜色是前端的呈现责任（同一套品牌色板要给
   * 报表、图表、其它模块复用）。这里只给 0..100 的强度与它背后的真数，
   * 让「这个格子为什么红」可解释 —— {@code metric} 说清强度来自哪个指标。
   *
   * @param heat 0..100 的强度整数
   * @param value 该指标的原值（百分比是 0..1 的比例，不是 0..100）
   */
  public record LocationUtilizationHeatCell(
      UUID locationId,
      String code,
      String name,
      @Schema(nullable = true) UUID parentId,
      @Schema(nullable = true) String parentCode,
      int depth,
      LocationUtilizationGrade grade,
      @Schema(nullable = true) String gradeLabel,
      @Schema(nullable = true) BigDecimal capacityUtilization,
      LocationUtilizationMetric metric,
      int heat,
      @Schema(nullable = true) BigDecimal value) {}

  /**
   * 一条调整建议。
   *
   * <p>{@code ownerModule} 写明「这条建议落地由谁做」：分流/上架属 8.1.2.2 自动分配，
   * 拣货路径属 8.3.2.1。分析模块只负责<b>指出问题并指认归属</b>，不越界去执行。
   */
  public record LocationUtilizationAdvice(
      LocationUtilizationAdviceKind kind,
      @Schema(nullable = true) String kindLabel,
      LocationUtilizationSeverity severity,
      @Schema(nullable = true) String severityLabel,
      @Schema(nullable = true) UUID locationId,
      @Schema(nullable = true) String locationCode,
      @Schema(nullable = true) UUID skuId,
      String message,
      String action,
      @Schema(nullable = true) String ownerModule) {}

  /**
   * 单库位详情。
   *
   * @param skus 该库位上的 SKU 逐条拆分，含各自的出库频次与「这个 SKU 一共散在几个库位」。
   *     这是「高频商品放近出口」这条建议的<b>依据</b>，所以它必须是真数而不是估算文案
   */
  public record LocationUtilizationDetail(
      LocationUtilizationRow location,
      List<LocationUtilizationSkuRow> skus,
      List<LocationUtilizationAdvice> advice,
      String note) {}

  /** 库位上的一个 SKU。 */
  public record LocationUtilizationSkuRow(
      UUID skuId,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal outboundQuantity,
      long locationCount,
      long skuDispersion,
      @Schema(nullable = true) BigDecimal shareOfSkuOnHand,
      @Schema(nullable = true) BigDecimal dwellDays) {}

  /**
   * 能力声明。
   *
   * @param outboundFrequencyGrain 出库频次的归集粒度<b>原文</b>。摆在这里而不是只写在
   *     注释里：使用报表的人有权知道自己看到的出库量是怎么来的
   * @param turnoverBasis 周转率口径原文
   * @param autoAllocationAvailable 8.1.2.2 未交付时恒为 false（与本车道同时开发，
   *     不猜它的交付状态）
   */
  public record LocationUtilizationCapabilities(
      boolean capacityUtilizationLive,
      boolean turnoverLive,
      boolean heatmapLive,
      boolean adviceGenerated,
      boolean skuDispersionLive,
      boolean autoAllocationAvailable,
      @Schema(nullable = true) String autoAllocationOwnerModule,
      boolean pickPathOptimizationAvailable,
      @Schema(nullable = true) String pickPathOwnerModule,
      String outboundFrequencyGrain,
      String turnoverBasis,
      List<LocationUtilizationGrade> grades,
      int defaultWindowDays,
      int maxWindowDays,
      String note) {}
}
