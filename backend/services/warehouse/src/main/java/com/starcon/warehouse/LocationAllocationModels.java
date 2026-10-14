package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.1.2.2 库位自动分配的传输模型。
 *
 * <p>蓝图原文：「按规则为入库商品推荐库位：同品类聚类、最短上架路径、容量与承重匹配、
 * 待检/残次专用库位强制规则。输入：入库 SKU 与数量；库位属性与占用；路径距离。
 * 输出：推荐库位列表（带推荐理由）。」以及关系段的红线：「**质量状态决定库位类型**：
 * 残次品入残次库位是硬规则，不允许混放。」
 *
 * <p><b>类型名全部带 {@code LocationAllocation} 前缀。</b>springdoc 用 record 的
 * <b>简单类名</b>做 OpenAPI {@code components.schemas} 的键，所以同名的 record 会
 * <b>静默</b>覆盖别人的 schema —— 而单元测试与 jOOQ 测试全绿，只有 {@code pnpm typecheck}
 * 会红。仓库里已经有一次真实事故（{@code ScopeOption} 撞 {@code DutyTaxModels.ScopeOption}）。
 * 同服务另有 8.1.2.1 的 {@code LocationType} / {@code LocationRow} / {@code LocationTotals}
 * 等裸名，本模块**刻意一个都不复用**，全部加前缀。
 *
 * <p><b>规则分两类，模型上分开表达。</b>
 *
 * <ul>
 *   <li><b>硬规则</b>：质量状态 → 库位类型（SELLABLE→STORAGE / PENDING_INSPECTION→QUARANTINE /
 *       DEFECTIVE→DEFECTIVE）。不合格的库位**根本不出现在候选里**，而不是排在后面扣分 ——
 *       残次品进存储位是硬规则，不是「差一点的选择」。
 *   <li><b>排序规则</b>：同品类聚类、最短上架路径、容量与承重余量。这三条只影响先后。
 * </ul>
 *
 * <p><b>「没量过」与「量出来不够」必须能分开。</b>所以容量与承重的每一条判定都带
 * {@code evaluated} 标志：{@code false} 表示这条规则<b>无法判定</b>（库位没量过容量，
 * 或调用方没给需求容量），而不是「通过了」。给不出答案时说没答案，好过默认放行。
 */
public final class LocationAllocationModels {

  private LocationAllocationModels() {}

  // ------------------------------------------------------------ 硬规则

  /**
   * 质量状态角色 → 目标库位类型（受控三映射）。
   *
   * <p>与 V106 的 {@code location_allocation_required_type()} 是同一张表的两个实现
   * （Java 侧给中文报错，plpgsql 侧是权威判据），集成测试逐条断言两侧一致。
   *
   * <p>另外三类库位（RECEIVING 收货暂存 / SHIPPING 发货暂存 / FROZEN 冻结）**刻意不出现**
   * 在这张映射里：前两个是货还没上架或拣完待发的中转位，冻结位是「这批货不许动」，
   * 三者都不是入库商品该落的地方。
   */
  public enum PlacementRule {
    /** 可售品 → 正式存储库位。 */
    SELLABLE("可售", WarehouseLocationTreeModels.LocationType.STORAGE),
    /** 待检品 → 待检专用库位（质检通过前不得进可发货区）。 */
    PENDING_INSPECTION("待检", WarehouseLocationTreeModels.LocationType.QUARANTINE),
    /** 残次品 → 残次专用库位。**这是蓝图点名的硬规则，不允许混放。** */
    DEFECTIVE("残次", WarehouseLocationTreeModels.LocationType.DEFECTIVE);

    private final String label;
    private final WarehouseLocationTreeModels.LocationType requiredType;

    PlacementRule(String label, WarehouseLocationTreeModels.LocationType requiredType) {
      this.label = label;
      this.requiredType = requiredType;
    }

    public String label() {
      return label;
    }

    /** 该质量角色**只能**进的库位类型。 */
    public WarehouseLocationTreeModels.LocationType requiredType() {
      return requiredType;
    }

    /**
     * 词表校验用；非法值抛 IllegalArgumentException → ApiErrorHandler 翻成 400。
     *
     * <p>刻意严格：role 是 V1 的三值 CHECK，取不到就是数据问题，不做「猜一个默认值」。
     */
    public static PlacementRule of(String raw) {
      for (PlacementRule r : values()) {
        if (r.name().equalsIgnoreCase(raw == null ? "" : raw.trim())) return r;
      }
      throw new IllegalArgumentException("质量状态角色不在词表内（V1 固定三值）：" + raw);
    }
  }

  /** 决策状态（与 V106 的 CHECK 逐字一致）。 */
  public enum AllocationStatus {
    /** 已选定、尚未派工。8.2.2.1 上架任务未交付，此刻没有任何下游消费它。 */
    PLANNED("已选定"),
    /** 已释放：交到 8.2.2.1 上架任务。 */
    RELEASED("已释放"),
    /** 已撤销：必须带原因。 */
    CANCELLED("已撤销");

    private final String label;

    AllocationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static AllocationStatus of(String raw) {
      for (AllocationStatus s : values()) {
        if (s.name().equalsIgnoreCase(raw == null ? "" : raw.trim())) return s;
      }
      throw new IllegalArgumentException("上架决策状态不在词表内：" + raw);
    }
  }

  // ------------------------------------------------------------ 推荐

  /**
   * 要推荐的入库明细。
   *
   * @param receiptId 收货单（8.2.1.1）
   * @param receiptLineId 只推某一行。**null = 整张单全部可上架行**
   * @param requiredCapacity 本批货要占的容量（与库位 capacity 同一单位）。可空 = 未量过，
   *     此时容量规则 <b>无法判定</b>，服务层不会假装它通过
   * @param requiredWeight 本批货的承重需求，同上
   * @param limit 每个明细行最多返回多少个候选（真截断，不是假分页）
   */
  public record LocationAllocationRecommendInput(
      UUID receiptId,
      @Schema(nullable = true) UUID receiptLineId,
      @Schema(nullable = true) BigDecimal requiredCapacity,
      @Schema(nullable = true) BigDecimal requiredWeight,
      @Schema(nullable = true) Integer limit) {}

  /**
   * 一条收货明细的推荐结果（一行一批货，因为同一行的质量状态是确定的）。
   *
   * @param requiredRule 硬规则推出的目标库位类型 —— 候选里的库位类型<b>必然</b>是它，
   *     所以客户端可以直接据此校验，不需要自己再比一遍
   * @param candidates 候选库位，按推荐分倒序；<b>已按 limit 真截断</b>
   * @param rejectedLocationCount 因不满足硬规则（类型不符 / 停用 / 混放）被排除的库位数。
   *     刻意给出来：只看到候选会以为「这个仓只有 3 个库位」
   */
  public record LocationAllocationLinePlan(
      UUID receiptLineId,
      UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      String skuUnitNote,
      UUID qualityStatusId,
      String qualityRole,
      @Schema(nullable = true) String qualityLabel,
      PlacementRule requiredRule,
      WarehouseLocationTreeModels.LocationType requiredType,
      @Schema(nullable = true) String requiredTypeLabel,
      BigDecimal outstandingQuantity,
      BigDecimal allocateQuantity,
      @Schema(nullable = true) UUID sourceLocationId,
      @Schema(nullable = true) String sourceLocationCode,
      @Schema(nullable = true) String sourceLocationType,
      @Schema(nullable = true) Integer sourceDepth,
      BigDecimal requiredCapacity,
      BigDecimal requiredWeight,
      long candidateCount,
      long rejectedLocationCount,
      List<LocationAllocationCandidate> candidates,
      String note) {}

  /**
   * 一个候选库位（**带推荐理由** —— 蓝图把理由写进了输出定义，不是可选项）。
   *
   * <p><b>「不通过」的库位不进候选。</b>容量/承重不够不是候选（它已经装不下了），
   * 而是<b>被排除</b>，计数在 {@link LocationAllocationLinePlan#rejectedLocationCount} 与
   * 排除理由里。把装不下的库位排在候选末尾让人去点，是把硬约束伪装成建议。
   *
   * @param distance 上架路径跳数：源库位 → 本库位，按 V105 的 parent_id 树算。越小越近
   * @param affinitySkus 同品类聚类命中数：本库位上已有多少个 SKU 与入库这批货**历史上同库位共置**。
   *     这不是 catalog 类目（catalog 未接入且禁止复制本地权威列），是从 stock_bucket
   *     真实共置关系算出来的聚类信号，见 {@link LocationAllocationCapabilities}
   * @param occupiedCapacity 本库位已被<b>未取消的上架决策</b>承诺占用的容量
   * @param remainingCapacity 剩余容量（{@code capacity - occupiedCapacity}）。
   *     库位没量过容量时为 null —— 不折成 0，「没量过」与「量出来是 0」在界面上必须能分开
   * @param capacityEvaluated 容量规则是否<b>可判定</b>。false 表示「没量过 / 没给需求」，
   *     <b>不是</b>「通过了」
   */
  public record LocationAllocationCandidate(
      UUID locationId,
      String code,
      String name,
      WarehouseLocationTreeModels.LocationType type,
      @Schema(nullable = true) String typeLabel,
      int depth,
      int distance,
      @Schema(nullable = true) BigDecimal capacity,
      @Schema(nullable = true) BigDecimal weightLimit,
      BigDecimal occupiedCapacity,
      @Schema(nullable = true) BigDecimal remainingCapacity,
      boolean capacityEvaluated,
      @Schema(nullable = true) BigDecimal requiredCapacity,
      boolean weightEvaluated,
      @Schema(nullable = true) BigDecimal requiredWeight,
      @Schema(nullable = true) BigDecimal occupiedWeight,
      @Schema(nullable = true) BigDecimal remainingWeight,
      BigDecimal onHand,
      long onHandSkuCount,
      long affinitySkus,
      int score,
      List<LocationAllocationReason> reasons) {}

  /**
   * 一条推荐理由。
   *
   * @param code 受控理由码（见 {@link ReasonCode}）
   * @param text 人可读的一句话。理由要给现场看，所以写成人话，不给「score=12」这种数字
   * @param weight 该理由对排序的贡献（正数=加分，0=只是说明）。给出来是为了让
   *     「为什么它排第一」可复核，而不是一个无法解释的 score
   */
  public record LocationAllocationReason(ReasonCode code, String text, int weight) {}

  /** 推荐理由码。刻意受控：界面上要按「为什么」归类，自由文本没法归类。 */
  public enum ReasonCode {
    /** 命中硬规则：质量状态推出的目标库位类型。 */
    TYPE_RULE("类型符合质量状态硬规则"),
    /** 同品类聚类：该库位上已有与本批货共置过的 SKU。 */
    CATEGORY_CLUSTER("同品类聚类命中"),
    /** 最短上架路径：源位到目标位跳数在候选里最短。 */
    SHORTEST_PATH("上架路径最短"),
    /** 容量余量充足。 */
    CAPACITY_FIT("容量余量充足"),
    /** 承重余量充足。 */
    WEIGHT_FIT("承重余量充足"),
    /** 空库位。 */
    EMPTY_LOCATION("空库位"),
    /** 容量无法判定（库位没量过或没给需求）—— 是<b>说明</b>不是通过。 */
    CAPACITY_UNKNOWN("容量无法判定"),
    /** 承重无法判定，同上。 */
    WEIGHT_UNKNOWN("承重无法判定"),
    /** 该库位已被本行之外的未取消决策占用。 */
    ALREADY_COMMITTED("已有其他上架承诺占用");

    private final String text;

    ReasonCode(String text) {
      this.text = text;
    }

    public String text() {
      return text;
    }
  }

  // ------------------------------------------------------------ 写入

  /**
   * 选定一个库位。
   *
   * <p>只提交**决定**（哪个库位），不提交分数与理由：那些由服务端按同一套规则重算并冻结成
   * 快照。客户端传上来的分数一律不采信 —— 否则「理由」就成了可以随便编的字段。
   *
   * @param allocateQuantity 本次上架数量，缺省取该行未上架的余量
   * @param requiredCapacity / requiredWeight 需求容量与承重；不传则沿用推荐时的入参，
   *     两者都没有时容量规则无法判定（如实报出，不假通过）
   */
  public record LocationAllocationConfirmInput(
      UUID receiptId,
      UUID receiptLineId,
      UUID locationId,
      @Schema(nullable = true) BigDecimal allocateQuantity,
      @Schema(nullable = true) BigDecimal requiredCapacity,
      @Schema(nullable = true) BigDecimal requiredWeight) {}

  /** 写操作回执。 */
  public record LocationAllocationSaved(
      UUID id,
      UUID receiptLineId,
      UUID locationId,
      @Schema(nullable = true) String locationCode,
      String status,
      @Schema(nullable = true) String statusLabel,
      PlacementRule requiredRule,
      @Schema(nullable = true) String ruleSummary,
      @Schema(nullable = true) OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime releasedAt,
      String note) {}

  /** 撤销已选定的上架决策。 */
  public record LocationAllocationCancelInput(@Schema(nullable = true) String reason) {}

  // ------------------------------------------------------------ 读

  /** 已落库的上架决策。 */
  public record LocationAllocationDecision(
      UUID id,
      UUID receiptId,
      @Schema(nullable = true) String receiptCode,
      UUID receiptLineId,
      UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      UUID qualityStatusId,
      String qualityRole,
      @Schema(nullable = true) String qualityLabel,
      PlacementRule requiredRule,
      WarehouseLocationTreeModels.LocationType requiredType,
      @Schema(nullable = true) String requiredTypeLabel,
      UUID warehouseId,
      @Schema(nullable = true) String warehouseName,
      UUID sourceLocationId,
      @Schema(nullable = true) String sourceLocationCode,
      UUID locationId,
      @Schema(nullable = true) String locationCode,
      @Schema(nullable = true) String locationName,
      BigDecimal allocateQuantity,
      @Schema(nullable = true) BigDecimal requiredCapacity,
      @Schema(nullable = true) BigDecimal requiredWeight,
      int sourceDistance,
      long affinitySkus,
      String status,
      @Schema(nullable = true) String statusLabel,
      @Schema(nullable = true) String ruleSummary,
      @Schema(nullable = true) String cancelReason,
      OffsetDateTime releasedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 决策清单的筛选与分页参数（排序键走白名单，非法键 400 而非静默忽略）。 */
  public record LocationAllocationQuery(
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String qualityRole,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  /** 决策清单页。合计**不受分页与筛选影响**（与 8.1.1.1 / 8.1.1.2 同一口径）。 */
  public record LocationAllocationPage(
      LocationAllocationTotals totals, List<LocationAllocationDecision> rows, long total, int page, int size, String note) {}

  public record LocationAllocationTotals(
      long decisionCount,
      long plannedCount,
      long releasedCount,
      long cancelledCount,
      long defectiveCount,
      long pendingInspectionCount,
      BigDecimal allocateQuantity,
      long coverageLineCount) {}

  /**
   * 能力声明：哪些规则有真相来源、哪些<b>没有</b>。
   *
   * <p>三条如实报 false 的，都是本服务里真的拿不到的东西：
   *
   * <ul>
   *   <li>{@code catalogCategoryAvailable=false} —— 「同品类」<b>不是</b> catalog 类目。
   *       catalog 的类目树 3.3.1.1 未开发，且 AGENTS.md 禁止把上游权威列复制成本地列，
   *       所以聚类信号取自 {@code stock_bucket} 的<b>真实共置关系</b>
   *       （历史上和这批货同过库位的 SKU）。这是warehouse 服务内可得的真信号，
   *       但它不等于 catalog 类目，所以必须标出来而不是含糊过去。
   *   <li>{@code routingGraphAvailable=false} —— 「路径距离」是**库位树跳数**，
   *       真正的巷道路径图属 8.3.2.1 拣货路径，未交付。
   *   <li>{@code stockVolumeAvailable=false} —— 库位上既有库存占了多少<b>体积</b>
   *       在本服务无真相来源（sku_ref 不存体积/重量），所以容量口径只覆盖本模块
   *       自己承诺过的量。这条写清楚，是为了防止有人拿件数凑一个假的容量利用率。
   * </ul>
   */
  public record LocationAllocationCapabilities(
      boolean hardPlacementRuleEnforced,
      boolean occupancyLive,
      boolean pathDistanceAvailable,
      boolean routingGraphAvailable,
      @Schema(nullable = true) String routingGraphOwnerModule,
      boolean categoryAffinityAvailable,
      boolean catalogCategoryAvailable,
      @Schema(nullable = true) String catalogCategoryOwnerModule,
      boolean capacityTracked,
      boolean stockVolumeAvailable,
      boolean putAwayTaskAvailable,
      @Schema(nullable = true) String putAwayTaskOwnerModule,
      List<PlacementRule> rules,
      List<WarehouseLocationTreeModels.LocationType> targetTypes,
      int maxCandidates,
      String note) {}
}
