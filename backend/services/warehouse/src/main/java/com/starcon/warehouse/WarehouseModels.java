package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.1.1.1 库存总览的传输模型。
 *
 * <p>行级返回的形状刻意与 7.1.1.2 库存明细将来要消费的形状一致：
 * **法人货权 × SKU × 仓库 × 质量状态** 就是总览行，一行同时是"一个 bucket 的最粗粒度"。
 * 总览不另造一套只有自己能用的私有结构，否则 7.1.1.2 落地时必须重写一遍映射，
 * 而重写映射的地方正是最容易把口径改错的地方（在手与可用差一个在途）。
 *
 * <p>口径全部来自 V1 迁移文件头的定义，不在 Java 侧二次推导：
 * available 由 {@code inventory_balance} 的生成列聚合而来，Java 只做求和。
 */
public final class WarehouseModels {

  private WarehouseModels() {}

  // ------------------------------------------------------------ 质量状态角色

  /** 质量状态在总账里的三值角色。守恒式要求在仓实物能被这三个角色完整覆盖。 */
  public enum QualityRole {
    /** 可售：唯一允许被预占、在 available 里可发货的质量状态。 */
    SELLABLE("可售"),
    /** 待检：已入库但未经质检确认，不得预占。 */
    PENDING_INSPECTION("待检"),
    /** 残次：质检判定不可售，不得预占。 */
    DEFECTIVE("残次");

    private final String label;

    QualityRole(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 筛选与查询

  /**
   * 总览查询条件。
   *
   * @param warehouseId 仓库过滤
   * @param legalEntityId 货权法人过滤（不传 = 全部法人）
   * @param skuId SKU 过滤
   * @param keyword 关键字：匹配 SKU 编码 / 名称 / 仓库编码 / 名称 / 法人编码 / 名称
   * @param categoryIds 类目过滤（catalog 拥有；未接入时显式 422）
   * @param brandIds 品牌过滤（catalog 拥有；未接入时显式 422）
   * @param shopIds 店铺过滤（channel 拥有；未接入时显式 422）
   * @param zeroStock 零库存过滤：true 只看零库存行，false 只看非零库存行，null 不按此条件筛
   * @param overstock 超储过滤：true 只看超储行，false 只看未超储行，null 不按此条件筛
   * @param sort 排序键（白名单）：ON_HAND / AVAILABLE / IN_TRANSIT / SKU_CODE
   * @param page 页码，从 0 开始
   * @param size 每页行数，服务端有上限
   */
  public record OverviewQuery(
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) List<UUID> categoryIds,
      @Schema(nullable = true) List<UUID> brandIds,
      @Schema(nullable = true) List<UUID> shopIds,
      @Schema(nullable = true) Boolean zeroStock,
      @Schema(nullable = true) Boolean overstock,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  // ------------------------------------------------------------ 总览响应

  /**
   * 总览响应。
   *
   * @param summary 全量合计（**不受分页影响**）：守住"总览合计 == 明细逐行求和"这条核对线
   * @param rows 当前页的行
   * @param groups 分组统计（按仓库 / 法人 / 质量角色 / SKU 四个维度）
   * @param capabilities 跨服务筛选维度接入情况，前端据此禁用不可用的筛选项
   * @param total 满足筛选条件的总行数
   * @param page 当前页码
   * @param size 每页行数
   */
  public record InventoryOverview(
      OverviewSummary summary,
      List<OverviewRow> rows,
      List<OverviewGroup> groups,
      List<DimensionCapability> capabilities,
      long total,
      int page,
      int size) {}

  /**
   * 全量合计。
   *
   * <p>守恒式：**在手 = 可售 + 待检 + 残次 + 在途**，由 {@code balanceCheck} 显式复核，
   * 不成立就说明聚合口径已经漂移（这是最该当场炸掉而不是悄悄显示一个数的场景）。
   *
   * @param onHandAtWarehouse 在仓实物 = Σ inventory_balance.on_hand
   * @param onHand 在手 = 在仓实物 + 在途（对外展示的"在手"口径）
   * @param reserved 预占 = Σ inventory_balance.reserved
   * @param available 可用 = Σ inventory_balance.available（生成列聚合，不含在途）
   * @param inTransit 在途 = Σ inventory_transfer.quantity WHERE status='IN_TRANSIT'
   * @param sellable 可售
   * @param pendingInspection 待检
   * @param defective 残次
   * @param rowCount 参与统计的行数
   * @param zeroStockRowCount 零库存行数（available &lt;= 0）
   * @param overstockRowCount 超储行数（已设阈值且在手超过阈值）
   * @param thresholdUnsetRowCount **未设阈值**的行数：0 与"没查过"含义不同，
   *     界面上必须与 overstockRowCount 分开显示（与 catalog unregisteredSkus 同一口径）
   * @param balanceCheck 守恒式复核结果
   */
  public record OverviewSummary(
      BigDecimal onHandAtWarehouse,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      BigDecimal inTransit,
      BigDecimal sellable,
      BigDecimal pendingInspection,
      BigDecimal defective,
      long rowCount,
      long zeroStockRowCount,
      long overstockRowCount,
      long thresholdUnsetRowCount,
      BalanceCheck balanceCheck) {}

  /**
   * 守恒式复核：可用量 = 在仓实物 − 预占（不含在途），且在手 = 可售 + 待检 + 残次 + 在途。
   *
   * @param availableMatches 在仓实物 − 预占 == 可用
   * @param onHandMatches 在手 == 可售 + 待检 + 残次 + 在途
   */
  public record BalanceCheck(boolean availableMatches, boolean onHandMatches) {}

  /**
   * 总览行 = 法人货权 × SKU × 仓库 × 质量状态。
   *
   * <p>这正是 7.1.1.2 明细的 bucket 形状（库位与批次维度属 7.1.1.2，届时在下方追加字段，
   * 不改本行的既有字段语义）。
   *
   * @param inTransit 该行的在途量：在途按**收货仓**归属本行，所以"这批货到哪个仓"看得见
   * @param zeroStock 零库存行：available &le; 0
   * @param thresholdConfigured 是否已配置阈值。false 时 {@code maxOnHand} 为 null、
   *     {@code overstock} 恒为 false，两者含义不同：一个是"没查过"，一个是"查过没超"
   * @param detailHref 指向 7.1.1.2 明细的路由契约（带全筛选参数）
   */
  public record OverviewRow(
      UUID balanceId,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      UUID qualityStatusId,
      String qualityStatusCode,
      String qualityStatusName,
      QualityRole qualityRole,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      BigDecimal inTransit,
      BigDecimal sellable,
      BigDecimal pendingInspection,
      BigDecimal defective,
      boolean zeroStock,
      boolean thresholdConfigured,
      @Schema(nullable = true) BigDecimal maxOnHand,
      @Schema(nullable = true) String policyScope,
      boolean overstock,
      long version,
      OffsetDateTime updatedAt,
      String detailHref) {}

  /**
   * 分组统计行（汇总图的数据源）。同样来自**同一份**聚合查询，不另算一遍。
   *
   * @param dimension 维度：WAREHOUSE / LEGAL_ENTITY / QUALITY_ROLE / SKU
   */
  public record OverviewGroup(
      String dimension,
      @Schema(nullable = true) UUID key,
      String label,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available,
      BigDecimal inTransit,
      BigDecimal sellable,
      BigDecimal pendingInspection,
      BigDecimal defective,
      long rowCount,
      long zeroStockRowCount,
      long overstockRowCount) {}

  /**
   * 跨服务筛选维度的接入情况。
   *
   * @param available 是否已接入。false 时前端**禁用**该筛选项并说明归哪个服务，
   *     而不是发一个必然返回 422 的请求
   */
  public record DimensionCapability(
      String dimension, String label, String owner, boolean available, String note) {}

  // ------------------------------------------------------------ 引用登记（供筛选与展示）

  /** 仓库下拉项。 */
  public record WarehouseRef(
      UUID id, String code, String name, String countryCode, String status, boolean isDefault) {}

  /** 法人引用下拉项（identity 拥有主数据，这里是 warehouse 的登记快照）。 */
  public record LegalEntityRef(UUID id, String code, String name, String status) {}

  /** SKU 引用下拉项（catalog 拥有档案，这里是 warehouse 的登记快照）。 */
  public record SkuRef(UUID id, String code, String name) {}

  /** 质量状态下拉项。 */
  public record QualityStatusRef(
      UUID id, String code, String name, QualityRole role, boolean enabled) {}

  // ------------------------------------------------------------ 库存阈值策略（超储来源）

  /**
   * 超储阈值策略。7.2.2 在此基础上扩展（补货点 / 安全库存 / 提前期已留列）。
   *
   * @param scopeType SKU / CATEGORY / WAREHOUSE / DEFAULT
   * @param scopeKey SKU: sku_ref.id；CATEGORY: 类目文本；WAREHOUSE: warehouse.id；DEFAULT: ''
   */
  public record InventoryPolicy(
      UUID id,
      String scopeType,
      String scopeKey,
      @Schema(nullable = true) BigDecimal maxOnHand,
      @Schema(nullable = true) BigDecimal minOnHand,
      @Schema(nullable = true) BigDecimal reorderPoint,
      @Schema(nullable = true) BigDecimal safetyStock,
      boolean enabled,
      long version) {}

  /** 阈值保存结果。 */
  public record InventoryPolicySaved(UUID id, long version) {}

  /** 阈值录入。 */
  public record InventoryPolicyInput(
      @jakarta.validation.constraints.NotBlank
          @jakarta.validation.constraints.Size(max = 16)
          String scopeType,
      @jakarta.validation.constraints.Size(max = 100) String scopeKey,
      @Schema(nullable = true)
          @jakarta.validation.constraints.DecimalMin(value = "0")
          BigDecimal maxOnHand,
      @Schema(nullable = true)
          @jakarta.validation.constraints.DecimalMin(value = "0")
          BigDecimal minOnHand) {}
}
