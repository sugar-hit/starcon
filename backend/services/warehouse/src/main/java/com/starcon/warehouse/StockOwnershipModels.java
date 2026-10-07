package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.7.1.1 货主与所有权的传输模型。
 *
 * <p>蓝图原话：「为库存 bucket 显式标注货主（stock_owner）与所有权类型（自有 / 供应商寄售 /
 * 平台所有 / 代销），使『货在谁的仓库』与『货是谁的』不再混为一谈。默认自有，但租户可把整仓
 * 或整 SKU 设为寄售。」下游 = 12 财务「自有货才进自有资产，寄售不进」。
 *
 * <p><b>这个模块真正解决的是「一行的身份」而不是「几列配置」</b>：
 *
 * <pre>
 *   自有货      on_hand=100 @ A仓某库位
 *   供应商寄售  on_hand=100 @ A仓**同一个**库位
 * </pre>
 *
 * <p>这两行在货主维度落地之前，其余五个维度逐列相同，无法互相区分。12 财务算自有资产时
 * 只能整仓一起算：把别人的货算成自己的资产（虚增），或把自己的货漏出去（少计）——
 * <b>两者都不是精度问题，而是口径错了且事后无法分辨</b>。
 *
 * <p><b>四条实现纪律</b>：
 *
 * <ul>
 *   <li><b>货主不用 tenant 顶替</b>：寄售货的货主是供应商（catalog），owner_kind =
 *       SUPPLIER_CONSIGNMENT。用 tenant 代替货主，「寄售不进自有资产」这条规则永远无法表达。
 *   <li><b>「计入自有资产」是生成列</b>（{@code countsAsOwnedAsset}），不是应用层判断：
 *       下游财务只读这一列，它必须是 ownerKind 的函数，不能是第二个真相。
 *   <li><b>区间 + 不可变</b>：货权档内容写定后不可改，改货权 = 收口旧档 + 开新档
 *       （与 V56 可售量口径、17.5.1.1 业务参数同一套约定）。
 *   <li><b>数量一律 BigDecimal + numeric(19,4)</b>，与 stock_bucket 的源列同刻度
 *       （warehouse 服务既有口径，见 V1/V2 文件头）。比较一律 {@code compareTo} 不用
 *       {@code equals}（后者把 0 与 0.0000 判成不等）。
 * </ul>
 */
public final class StockOwnershipModels {

  private StockOwnershipModels() {}

  // ------------------------------------------------------------ 枚举

  /** 所有权类型（蓝图词表，一字不改）。 */
  public enum StockOwnerKind {
    SELF("自有"),
    SUPPLIER_CONSIGNMENT("供应商寄售"),
    PLATFORM("平台所有"),
    CONSIGNEE("代销");

    private final String label;

    StockOwnerKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 货主指向谁。
   *
   * <p>{@code LEGAL_ENTITY} 是唯一在本服务有登记表的一类（{@code legal_entity_ref}，identity 是
   * 法人主数据的唯一所有者），所以它是唯一建了复合外键的一类。{@code SUPPLIER} /
   * {@code PLATFORM} 是 catalog / channel 的跨服务逻辑 ID，只存 ID + 写入时的可读名快照。
   */
  public enum OwnerRefType {
    LEGAL_ENTITY("本租户法人"),
    SUPPLIER("供应商（catalog）"),
    PLATFORM("平台（channel）"),
    INTERNAL("本租户自身");

    private final String label;

    OwnerRefType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 货权配置的范围档。解析优先级 {@link #priority()} 升序即更优先：SKU &gt; LEGAL_ENTITY &gt;
   * WAREHOUSE &gt; DEFAULT（最具体者胜，与 17.5.1.1、V56 同一套分档约定）。
   */
  public enum OwnershipScopeType {
    DEFAULT("全租户兜底"),
    WAREHOUSE("整仓"),
    LEGAL_ENTITY("按法人"),
    SKU("按 SKU");

    private final String label;

    OwnershipScopeType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析优先级：数值越小越优先（SKU 最具体，DEFAULT 兜底）。 */
    public int priority() {
      return ordinal();
    }
  }

  // ------------------------------------------------------------ 货主档案

  /** 货主列表查询。 */
  public record StockOwnerQuery(
      String ownerKind,
      String status,
      @Schema(nullable = true) UUID ownerRefId,
      Boolean ownedAssetOnly,
      String keyword,
      int page,
      int size) {}

  /**
   * 一个货主。
   *
   * @param countsAsOwnedAsset 生成列：仅 {@code ownerKind=SELF} 为 true。寄售 / 代销 / 平台所有
   *     一律 false —— 这是下游 12 财务「自有货才进自有资产」唯一的判据。
   * @param legalEntityLinked 货主是否真的挂在某个已登记法人名下。false 表示这是「租户还没登记
   *     法人时的兜底自有货主」，财务据此知道这批自有货暂时无法归到法人资产表。
   * @param systemCreated 由库存写入路径自动建立的兜底货主（{@code createdByMembershipId} 为空）。
   */
  public record StockOwnerRow(
      UUID id,
      String code,
      String name,
      StockOwnerKind ownerKind,
      boolean countsAsOwnedAsset,
      OwnerRefType ownerRefType,
      @Schema(nullable = true) UUID ownerRefId,
      String ownerRefLabel,
      boolean legalEntityLinked,
      boolean isDefault,
      String status,
      String note,
      @Schema(nullable = true) UUID createdByMembershipId,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 货主列表（真分页，total 不受分页影响）。 */
  public record StockOwnerPage(List<StockOwnerRow> owners, long total, int page, int size) {}

  /**
   * 新建货主。
   *
   * <p>{@code supplierRefId} / {@code platformRefId} 是**跨服务逻辑 ID**，本服务不建外键也不查
   * 对方的表（AGENTS.md 模块边界纪律）：{@code ownerRefType=SUPPLIER} 时给 {@code ownerRefId}
   * （catalog 的 party_supplier_profile 主键），{@code ownerRefType=PLATFORM} 时
   * {@code ownerRefId} 留空（平台仓由渠道侧分类，7.1.3.1 那条车道，本模块不臆造平台枚举）。
   *
   * @param ownerRefType LEGAL_ENTITY 时 ownerRefId 必须是本租户已登记的法人，否则 DB 拒绝
   */
  public record StockOwnerInput(
      @NotBlank @Size(max = 32) String code,
      @NotBlank @Size(max = 150) String name,
      @NotBlank @Size(max = 20) String ownerKind,
      @NotBlank @Size(max = 16) String ownerRefType,
      @Schema(nullable = true) UUID ownerRefId,
      @Size(max = 150) String ownerRefLabel,
      boolean isDefault,
      @Size(max = 16) String status,
      @Size(max = 500) String note) {}

  /** 货主写入结果。 */
  public record StockOwnerSaved(UUID ownerId, String code, String ownerKind, boolean isDefault, String message) {}

  // ------------------------------------------------------------ 货权配置

  /** 货权档列表查询。{@code effectiveOnly=true} 只看此刻仍在生效的档。 */
  public record OwnershipRuleQuery(
      String scopeType,
      @Schema(nullable = true) UUID ownerId,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID skuId,
      boolean effectiveOnly,
      String keyword,
      int page,
      int size) {}

  /**
   * 一档货权配置。
   *
   * <p>三个定位键按 {@code scopeType} 只会有一个有值（DB 层 CHECK 钉死，响应里另外几个恒为 null）。
   * 相应的 code/name 是查询时联查出来的可读名快照，不是这一行自己存的。
   */
  public record OwnershipRuleRow(
      UUID id,
      OwnershipScopeType scopeType,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) String warehouseCode,
      @Schema(nullable = true) String warehouseName,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) String legalEntityCode,
      @Schema(nullable = true) String legalEntityName,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      UUID ownerId,
      String ownerName,
      StockOwnerKind ownerKind,
      boolean countsAsOwnedAsset,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String note,
      UUID createdByMembershipId,
      OffsetDateTime createdAt) {

    /** 该档是否在给定时刻生效（左闭右开）。 */
    public boolean covers(OffsetDateTime at) {
      return !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo));
    }
  }

  /** 货权档列表（真分页，total 不受分页影响）。 */
  public record OwnershipRulePage(List<OwnershipRuleRow> rules, long total, int page, int size) {}

  /**
   * 新建一档货权配置（{@code supersedeRuleId} 非空时 = 换版）。
   *
   * <p>{@code scopeType} 非 DEFAULT 时必须给对应的定位键：仓库 / 法人在本服务（可校验存在性），
   * {@code skuId} 是 catalog 的逻辑 ID（跨服务，不校验）。
   */
  public record OwnershipRuleInput(
      @NotBlank @Size(max = 16) String scopeType,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID skuId,
      @NotNull UUID ownerId,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Size(max = 500) String note) {}

  /** 货权档写入结果：新档 / 被换掉的那一档 / 为什么这么改。 */
  public record OwnershipRuleSaved(
      UUID ruleId,
      UUID ownerId,
      String ownerName,
      OwnershipScopeType scopeType,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      @Schema(nullable = true) UUID supersededRuleId,
      String message) {}

  // ------------------------------------------------------------ 货权解析

  /**
   * 某一组维度在某一时刻真正生效的货主。
   *
   * <p>{@code rule} 为 null 表示四档全未命中、落到了租户的兜底货主上（默认自有）。
   * {@code usedFallback} 让调用方知道自己看的是「没配过货权」的兜底而不是一条真实配置 ——
   * 界面必须能区分这两者，否则运营会以为那条寄售规则还生效着。
   */
  public record StockOwnershipResolved(
      @Schema(nullable = true) OwnershipRuleRow rule,
      @Schema(nullable = true) StockOwnerRow owner,
      @Schema(nullable = true) OwnershipScopeType matchedScope,
      boolean usedFallback,
      OffsetDateTime at,
      String note) {}

  // ------------------------------------------------------------ 按货主拆分的库存报表

  /**
   * 一个货主名下的库存汇总（真实从 {@code stock_bucket} 聚合）。
   *
   * @param countsAsOwnedAsset 该货主的货是否计入自有资产（生成列，寄售/代销恒为 false）
   */
  public record OwnerInventoryRow(
      UUID ownerId,
      String ownerName,
      String ownerCode,
      StockOwnerKind ownerKind,
      boolean countsAsOwnedAsset,
      long warehouseCount,
      long skuCount,
      long locationCount,
      long bucketCount,
      BigDecimal onHand,
      BigDecimal reserved,
      BigDecimal available) {}

  /**
   * 按货主拆分的库存报表。
   *
   * <p><b>{@code amountBasisAvailable} 恒为 false，且这是如实报出而不是没做完</b>：
   * 全库 warehouse 与 catalog 两侧的迁移与 {@code db/schemas} 中都没有任何**库存成本**列
   * （catalog 侧只有 {@code supplier_quote_price_tier}，那是报价不是库存计价，
   * 且属另一个服务的跨服务引用）。蓝图要求的「按货主拆分的货值」在本仓库当前状态下
   * **没有可用的计价依据** —— 用采购报价冒充库存成本会把账面资产算错，
   * 比不给更糟。所以这里给数量口径的完整拆分，货值口径显式报不可用并说明原因
   * （与 7.4.2.2 盘点差异的 {@code amountBasisAvailable=false} 同一套处置）。
   */
  public record OwnerInventoryReport(
      List<OwnerInventoryRow> rows,
      long totalBucketCount,
      BigDecimal totalOnHand,
      BigDecimal totalReserved,
      BigDecimal totalAvailable,
      BigDecimal ownedAssetOnHand,
      BigDecimal nonOwnedAssetOnHand,
      long ownerCount,
      boolean amountBasisAvailable,
      String amountBasisNote,
      OffsetDateTime at,
      String note) {}

  // ------------------------------------------------------------ 货权转移与漂移

  /**
   * 货权转移请求：把某一范围内属于 {@code fromOwnerId} 的 bucket 改判给 {@code toOwnerId}。
   *
   * <p>为什么需要它：货权档只影响**之后**写入的 bucket（生效区间的意义就在这里）。
   * 而「整仓设为寄售」这句话显然包含这个仓里**已经躺着的那批自有货** ——
   * 只改配置不改存量，报表会继续把旧货算成自有资产，而这正是本模块要消灭的混淆。
   *
   * <p>转移不产生任何出入库：不改数量、不动 balance、不写流水。货权是账上的归属，
   * 不是货物流动 —— 它只在同仓同库位换了个「货主」标注。数量会被
   * {@code guard_bucket_conservation} 照常核对，转移前后 Σ 不变。
   *
   * @param warehouseId / legalEntityId / skuId 三个可选过滤条件，全部留空 = 整租户转移
   */
  public record OwnershipReassignRequest(
      @NotNull UUID fromOwnerId,
      @NotNull UUID toOwnerId,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID skuId) {}

  /** 货权转移结果。{@code movedBucketCount=0} 是一个合法且要如实报出的结果。 */
  public record OwnershipReassignResult(
      UUID fromOwnerId,
      String fromOwnerName,
      UUID toOwnerId,
      String toOwnerName,
      long movedBucketCount,
      BigDecimal movedOnHand,
      String message) {}

  /**
   * 货权漂移：bucket 上的货主与「按当前生效档应该属于的货主」不一致。
   *
   * <p>这类行是**有意的**：货权档只影响之后写入的 bucket，存量不会自动跟着改。
   * 页面把它列出来，是为了让「这批货的货权要不要跟着新配置改」变成一个可回答的问题，
   * 而不是等人对不上账时才发现。
   */
  public record OwnershipDriftRow(
      long bucketCount,
      BigDecimal onHand,
      UUID currentOwnerId,
      String currentOwnerName,
      @Schema(nullable = true) UUID expectedOwnerId,
      @Schema(nullable = true) String expectedOwnerName,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID legalEntityId,
      @Schema(nullable = true) UUID skuId) {}

  /** 货权漂移汇总。 */
  public record OwnershipDriftReport(
      List<OwnershipDriftRow> rows, long totalBucketCount, BigDecimal totalOnHand, String note) {}

  // ------------------------------------------------------------ 能力声明

  /**
   * 本模块的能力与上游可用性声明。
   *
   * <p>存在的理由与 7.4.2.2 盘点差异一致：把「哪些维度真有依据」显式报出来，
   * 而不是让调用方从 404 或一个空数组里去猜是哪一环没通。
   *
   * @param amountValuationAvailable 恒为 false，理由见 {@link OwnerInventoryReport}
   */
  public record StockOwnershipCapabilities(
      List<Dimension> dimensions,
      boolean amountValuationAvailable,
      String amountValuationNote,
      String note) {

    /**
     * 一个维度是否可用。
     *
     * @param source 该维度的上游归属（服务 + 模块），便于调用方知道去哪补
     */
    public record Dimension(
        String code,
        String label,
        boolean available,
        String source,
        @Schema(nullable = true) String unavailableReason) {}
  }

  // ------------------------------------------------------------ 参照下拉

  /** 货权转移对话框需要的可选项。仓库与法人来自本服务；SKU 是 catalog 逻辑 ID。 */
  public record OwnershipReferenceOptions(
      List<WarehouseRefLite> warehouses,
      List<LegalEntityRefLite> legalEntities,
      List<SkuRefLite> skus) {

    /** 仓库下拉项（只带本页要显示的三列，不把 warehouse 全表塞进响应）。 */
    public record WarehouseRefLite(UUID id, String code, String name) {}

    /** 法人下拉项。 */
    public record LegalEntityRefLite(UUID id, String code, String name) {}

    /** SKU 下拉项。skuId 是 catalog 主键（本服务只存逻辑 ID，不建外键）。 */
    public record SkuRefLite(UUID id, String code, String name) {}
  }
}