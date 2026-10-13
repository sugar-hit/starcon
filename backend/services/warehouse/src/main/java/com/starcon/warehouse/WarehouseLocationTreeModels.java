package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.1.2.1 库位树的传输模型。
 *
 * <p>职责原文：「维护库位层级与属性（区 → 巷 → 排 → 架 → 层 → 格），支持容量、承重、
 * 类型（存储/暂存/待检/残次/冻结）、是否可拣。输入：库位增删改与层级调整；防环校验。
 * 输出：库位树 + 占用情况。」
 *
 * <p><b>类型词表复用 {@link WarehouseFacilityModels} 的六值</b>，与 V2 建表时的 CHECK
 * 逐字一致（STORAGE/RECEIVING/SHIPPING/QUARANTINE/DEFECTIVE/FROZEN ↔
 * 存储/暂存/待检/残次/冻结）。本模块**不改**那张 CHECK，也不新增类型 ——
 * 待检/残次/冻结库位天然不可拣这件事由 {@link LocationType#pickableByNature()} 表达，
 * 不靠往词表里塞一个「不可拣类型」来凑。
 *
 * <p><b>占用情况不是一个字段，是一整组。</b>库位上压着的货来自 {@code stock_bucket}
 * （7.1.x 的事实表），所以占用同时给在手、预占、SKU 数与明细行数 —— 只给一个
 * {@code used} 会让人分不清「这个库位满了」是货多还是 SKU 杂。
 */
public final class WarehouseLocationTreeModels {

  private WarehouseLocationTreeModels() {}

  // ------------------------------------------------------------ 库位类型

  /**
   * 库位类型（受控词表，与 V2 的 CHECK 逐字一致，本模块不增不减不改）。
   *
   * <p>蓝图说「存储/暂存/待检/残次/冻结」五类，V2 建的是六值：多出的 RECEIVING（收货暂存）
   * 与 SHIPPING（发货暂存）正是「暂存」在真实仓库里的两处落点 —— 收货台与发货台不是同一个
   * 暂存区，而它们都不是「存储」。所以这里照实给六个，而不是把词表砍到五个去迁就蓝图措辞。
   */
  public enum LocationType {
    /** 存储：正式库位，可放可取。 */
    STORAGE("存储"),
    /** 收货暂存：到货未上架前暂存。 */
    RECEIVING("收货暂存"),
    /** 发货暂存：拣完待发。 */
    SHIPPING("发货暂存"),
    /** 待检：质量状态待判定，不可拣。 */
    QUARANTINE("待检"),
    /** 残次：不合格品专用，不可拣。 */
    DEFECTIVE("残次"),
    /** 冻结：业务或海关冻结，不可拣。 */
    FROZEN("冻结");

    private final String label;

    LocationType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /**
     * 待检 / 残次 / 冻结三类天然不可拣。
     *
     * <p>这是**属性说明**不是 DB 约束：V2 建表时把 {@code pickable} 定成布尔列而不是
     * 从 type 推导，正是为了让「一个待检库位被临时当作可发货暂存」这种现场操作有地方表达。
     * 所以这里只把「天然不可拣」讲清楚，改不改由人决定。
     */
    public boolean pickableByNature() {
      return this == STORAGE || this == RECEIVING || this == SHIPPING;
    }

    /** 词表校验用；非法值抛 IllegalArgumentException → ApiErrorHandler 翻成 400。 */
    public static LocationType of(String raw) {
      for (LocationType t : values()) {
        if (t.name().equalsIgnoreCase(raw == null ? "" : raw.trim())) return t;
      }
      throw new IllegalArgumentException("库位类型不在词表内：" + raw);
    }
  }

  // ------------------------------------------------------------ 树

  /**
   * 库位树（一个仓库一棵）。
   *
   * @param warehouseId 所属仓库
   * @param levels **扁平**的层级行，按「父先于子、同层按 code」排序，
   *     {@code depth} 给出缩进层级。树本身不分页，所以这是全集而不是一页
   * @param rootCount 顶层根（depth=0）数量，界面据此判断「树是否为空」
   * @param totals 整棵树的合计，**不受分页与筛选影响**
   */
  public record LocationTree(
      UUID warehouseId,
      List<LocationNode> levels,
      long rootCount,
      TreeTotals totals,
      String note) {}

  /**
   * 单个库位节点（**扁平的一行**，不带 children）。
   *
   * <p><b>刻意不给 {@code children} 递归字段。</b>运行时树确实是嵌套的（前端按 depth 缩进渲染），
   * 但 springdoc 会**丢掉自引用的 record 属性** —— {@code List<LocationNode> children} 在生成的
   * OpenAPI spec 里整个字段消失，codegen 出来的 TS 类型也就没有它。与其让契约与运行时不一致
   * （前端按类型写 children 却收不到），不如把形状说成「一组带 depth 的层级行」：
   * 契约、运行时与前端三者说的是同一件事。identity 的 {@code DepartmentView.children}
   * 能进契约是因为它在 Controller 里被 springdoc 当作根类型扫描，本模块在 Models 类里。
   *
   * @param depth 层级，根为 0（蓝图六层：区→巷→排→架→层→格，1..6 对应 depth 0..5）
   * @param capacity 容量。**可空**（V105 补的列是 nullable 无 DEFAULT，存量库位没有容量概念）——
   *     「没量过」与「量出来是 0」在界面上必须能分开，所以不把 null 折成 0
   * @param weightLimit 承重上限，同样可空
   * @param occupied 该库位**直接**压着的货（不含子树）
   */
  public record LocationNode(
      UUID id,
      UUID parentId,
      String code,
      String name,
      LocationType type,
      @Schema(nullable = true) String typeLabel,
      String status,
      @Schema(nullable = true) String statusLabel,
      boolean pickable,
      @Schema(nullable = true) Boolean pickableByNature,
      int depth,
      @Schema(nullable = true) BigDecimal capacity,
      @Schema(nullable = true) BigDecimal weightLimit,
      Occupancy occupied,
      Occupancy subtreeOccupancy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 一个库位（或一棵子树）上的占用情况。
   *
   * <p><b>手数与预留是两个数，不相加。</b>「预占」是已被订单锁住但还没走的货，
   * 把它并进在手会让拣货以为还有货可拿。
   *
   * @param onHand 在手件数（NUMERIC 读回，不经过 double）
   * @param reserved 已被订单预占的件数
   * @param skuCount 该范围内涉及的 SKU 数
   * @param bucketRows 构成它的 stock_bucket 明细行数
   * @param capacityUtilization 容量利用率 = 在手 / 容量。<b>容量未量过时为 null</b> ——
   *     拿 0 去表示「没量过」会让「量过且空置」与「从没量过」长得一样
   * @param capacityExceeded 是否已超出量得的容量（无容量时恒为 false，不给 null：
   *     三态里「没量过」由 capacityUtilization=null 承担）
   */
  public record Occupancy(
      BigDecimal onHand,
      BigDecimal reserved,
      long skuCount,
      long bucketRows,
      @Schema(nullable = true) BigDecimal capacityUtilization,
      boolean capacityExceeded) {}

  /**
   * 整棵树合计。
   *
   * <p>刻意<b>不给</b>容量利用率合计：把各节点的容量相加当分母，在「只有部分节点量过容量」
   * 时会算出一个没人测过的比例。合计只给真数，利用率逐节点给。
   */
  public record TreeTotals(
      long locationCount,
      long rootCount,
      long leafCount,
      long pickableCount,
      long capacityMeasuredCount,
      BigDecimal onHand,
      BigDecimal reserved,
      long skuCount,
      long skuCountDistinct,
      long typeCount,
      List<TypeBreakdown> byType) {}

  /** 按库位类型分组（统计用，不含占用 —— 占用在节点上，避免同一份数字搬两次）。 */
  public record TypeBreakdown(LocationType type, @Schema(nullable = true) String label, long count) {}

  // ------------------------------------------------------------ 写入入参

  /**
   * 新建库位。
   *
   * @param parentId 父库位。<b>null = 顶层根节点</b>（区），不是漏填
   * @param capacity 容量，可空
   * @param weightLimit 承重上限，可空
   * @param pickable 是否可拣
   */
  public record LocationCreateInput(
      @Schema(nullable = true) String code,
      String name,
      @Schema(nullable = true) UUID parentId,
      @Schema(nullable = true) String type,
      @Schema(nullable = true) Boolean pickable,
      @Schema(nullable = true) BigDecimal capacity,
      @Schema(nullable = true) BigDecimal weightLimit) {}

  /**
   * 改库位属性与层级。
   *
   * @param parentId 目标父库位。**null 是有意义的值**（升为根节点），
   *     所以用三态表达：{@code parentProvided=true} 且值为 null 就是「升为根」，
   *     {@code parentProvided=false} 才是「没带这个字段，别动层级」
   */
  public record LocationUpdateInput(
      @Schema(nullable = true) String name,
      @Schema(nullable = true) String type,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) Boolean pickable,
      @Schema(nullable = true) BigDecimal capacity,
      @Schema(nullable = true) BigDecimal weightLimit,
      @Schema(nullable = true) UUID parentId,
      @Schema(nullable = true) Boolean parentProvided) {}

  /** 写操作回执。 */
  public record LocationSaved(UUID id, String code, UUID parentId, String action, String note) {}

  /** 树维护的能力声明：哪些维度有真相来源、哪些还没有。 */
  public record LocationTreeCapabilities(
      boolean hierarchyMaintained,
      boolean capacityTracked,
      boolean weightTracked,
      boolean occupancyLive,
      boolean autoAllocationAvailable,
      @Schema(nullable = true) String autoAllocationOwnerModule,
      @Schema(nullable = true) String pickPathOwnerModule,
      List<LocationType> types,
      int maxDepth,
      String note) {}
}