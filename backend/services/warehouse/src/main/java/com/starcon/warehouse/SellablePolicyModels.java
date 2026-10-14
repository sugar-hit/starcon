package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.2.2.1 可售量口径的传输模型。
 *
 * <p>蓝图原话：「配置不同渠道/店铺的库存口径：是否共享同一池、是否允许超卖多少、平台可售量是按 自有仓还是含 FBA、平台可售数保留缓冲比例。输入：店铺/渠道维度配置（共享策略、缓冲比例、
 * 是否含在途）。输出：生效的可售量口径；供 7.1.1.3 与 04 回写使用。」
 *
 * <p><b>缓冲公式（整个模块只有这一套口径，别处再写一遍就会算错）</b>：
 *
 * <pre>
 *   池容量 poolCapacity   = Σ 合格 bucket 可用（quality_status.role='SELLABLE'）
 *                           + （口径含在途时）发往该仓的在途量
 *   成员回写 listing_i    = poolCapacity × (1 − bufferRatio_i) + oversollTolerance_i
 *   池已分配 committed    = Σ listing_i
 *   余量   headroom       = poolCapacity − committed
 * </pre>
 *
 * <p>缓冲比例越大、每个店铺留给平台的可售数越少，留给同步延迟的余量越大。共享池上 <b>各成员的分配比例必须合起来小于 1</b>：两个店铺各留 50% 缓冲时 committed 正好等于
 * 池容量，余量为 0，此时任何一笔在途延迟都会击穿 —— 这就是蓝图说的「多店铺共享池时， 缓冲不足会在同步延迟期间超卖」。所以 {@link PoolSafetyVerdict}
 * 是这个模块真正的产出， 而不是配置本身。
 *
 * <p>数量与比例一律 {@link BigDecimal}（DB 侧 numeric），比较一律 {@code compareTo} 而非 {@code equals}（后者把 0 与
 * 0.0000 判成不等）。
 */
public final class SellablePolicyModels {

  private SellablePolicyModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 生效范围。与 17.5.1.1 业务参数的 GLOBAL/LEGAL_ENTITY/SHOP 同一套分档约定， 这里换成渠道口径：优先级 SHOP &gt; CHANNEL &gt;
   * DEFAULT（{@link #priority()} 升序即更优先）。
   */
  public enum PolicyScopeType {
    DEFAULT("全租户兜底"),
    CHANNEL("按渠道"),
    SHOP("按店铺");

    private final String label;
    private final int priority;

    PolicyScopeType(String label) {
      this.label = label;
      this.priority = ordinal();
    }

    public String label() {
      return label;
    }

    /** 解析优先级：数值越小越优先（SHOP 最具体，DEFAULT 兜底）。 */
    public int priority() {
      return priority;
    }
  }

  /**
   * 共享池策略。SHARED 表示几档口径扣的是**同一个池子**。
   *
   * <p>这是策略不是数据：配错直接超卖，所以 {@code poolKey} 对 SHARED 是显式共享名， 对 EXCLUSIVE 由服务按 {@code
   * EXCL-}{referenceId} 派生并在 DB 层 CHECK 钉死。
   */
  public enum PoolMode {
    EXCLUSIVE("独占池"),
    SHARED("共享池");

    private final String label;

    PoolMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 平台可售量口径：只按自有仓，还是含 FBA。
   *
   * <p>与 7.1.3.1 的「FBA 库存是平台仓观测值，不是自有账本」同一条边界：FBA 计入 **平台回写口径**，但不计入自有账本的可用量推导。
   */
  public enum PlatformStockScope {
    OWN_WAREHOUSE("仅自有仓"),
    OWN_PLUS_FBA("自有仓 + FBA");

    private final String label;

    PlatformStockScope(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 池归属的口径下这个仓算自有还是平台观测。
   *
   * <p>刻意**不改** {@code warehouse} 表加 type 列：warehouse 没有 type 列， 而全局仓库分类归 7.1.3.1
   * 那条车道。分类挂在口径侧，边界更清楚。
   */
  public enum WarehouseKind {
    OWN("自有仓"),
    PLATFORM_FBA("平台仓 FBA");

    private final String label;

    WarehouseKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 共享池安全判定 —— 平台库存回写前的最后一道体检。
   *
   * <ul>
   *   <li>{@code GUARDED}：有余量（headroom &gt; 0）。同步延迟期间还有得垫。
   *   <li>{@code NO_HEADROOM}：余量恰好为 0。分配之和正好等于池容量，**没有任何防线** —— 独占池留 0% 缓冲、共享池各留 (成员数−1)/成员数
   *       缓冲都会落在这里。
   *   <li>{@code TOLERATED}：余量为负，但在已配置的超卖容忍额度之内 —— 这是**有意为之**的 超卖，不是配置错误。
   *   <li>{@code OVERSOLD}：余量为负且超出容忍额度。必然超卖，必须先改缓冲再回写。
   * </ul>
   */
  public enum PoolSafetyVerdict {
    GUARDED("有余量"),
    NO_HEADROOM("零余量"),
    TOLERATED("在容忍额度内的超卖"),
    OVERSOLD("必然超卖");

    private final String label;

    PoolSafetyVerdict(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 查询

  /** 口径列表查询。{@code effectiveOnly=true} 只看此刻仍在生效的档。 */
  public record SellablePolicyQuery(
      String scopeType,
      @Schema(nullable = true) UUID referenceId,
      String poolMode,
      boolean effectiveOnly,
      String keyword,
      int page,
      int size) {}

  // ------------------------------------------------------------ 口径分档

  /**
   * 一档可售量口径。
   *
   * <p>内容列写定后不可修改（V56 触发器钉死），改口径 = 收口旧档 + 开新档。 {@code effectiveTo} 为 null 表示至今仍在生效。
   *
   * @param bufferRatio 平台可售数保留缓冲比例，0 ~ 1
   * @param oversellTolerance 允许超卖的件数（oversellAllowed=false 时恒为 0）
   * @param poolKey 池标识；EXCLUSIVE 档由服务按 EXCL-{referenceId} 派生
   */
  public record SellablePolicyRow(
      UUID id,
      PolicyScopeType scopeType,
      @Schema(nullable = true) UUID referenceId,
      String referenceLabel,
      PoolMode poolMode,
      String poolKey,
      boolean oversellAllowed,
      BigDecimal oversellTolerance,
      PlatformStockScope platformStockScope,
      BigDecimal bufferRatio,
      boolean includeInTransit,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String note,
      UUID createdByMembershipId,
      OffsetDateTime createdAt) {

    /** 该档是否在给定时刻生效（左闭右开，与 17.5.1.1 同一套区间约定）。 */
    public boolean covers(OffsetDateTime at) {
      return !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo));
    }
  }

  /** 口径挂的仓：池容量的构成单元。 */
  public record SellablePolicyWarehouseLink(
      UUID policyId,
      UUID warehouseId,
      String warehouseCode,
      String warehouseName,
      WarehouseKind warehouseKind) {}

  /** 口径详情 = 分档本身 + 它吃哪几个仓。 */
  public record SellablePolicyDetail(
      SellablePolicyRow policy, List<SellablePolicyWarehouseLink> warehouses) {}

  /** 口径列表（真分页，total 不受分页影响）。 */
  public record SellablePolicyPage(
      List<SellablePolicyRow> policies, long total, int page, int size) {}

  // ------------------------------------------------------------ 写入

  /**
   * 新建一档口径。
   *
   * <p>{@code scopeType} 非 DEFAULT 时必须给 {@code sourceSystem} + {@code referenceId}： 店铺与渠道在 channel
   * 服务，本服务不建外键也不查它的表（AGENTS.md 模块边界纪律）， 只存跨服务定位键 + 写入时的可读名快照。
   *
   * <p>{@code effectiveFrom} 缺省取当前时刻；不早于同槽位已有档的起点（重叠由 DB 触发器拒绝， 错误信息里带冲突档 id）。
   *
   * @param bufferRatio 0 ~ 1；越界由 @DecimalMin/@DecimalMax 与 DB CHECK 双重拒绝
   * @param poolKey SHARED 必填（共享池名）；EXCLUSIVE 留空，由服务派生
   */
  public record SellablePolicyInput(
      @NotBlank @Size(max = 16) String scopeType,
      @Size(max = 32) String sourceSystem,
      @Schema(nullable = true) UUID referenceId,
      @Size(max = 150) String referenceLabel,
      @NotBlank @Size(max = 16) String poolMode,
      @Size(max = 64) String poolKey,
      boolean oversellAllowed,
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal oversellTolerance,
      @NotBlank @Size(max = 20) String platformStockScope,
      @NotNull @DecimalMin(value = "0") @DecimalMax(value = "1") BigDecimal bufferRatio,
      boolean includeInTransit,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Size(max = 500) String note,
      List<@NotNull @Valid SellablePolicyWarehouseBinding> warehouses) {

    /**
     * 挂仓关系的一行。
     *
     * <p>必须逐个仓声明它是自有仓还是平台仓（FBA），因为「平台可售量按自有仓还是含 FBA」 正是靠这一列可计算的：{@code OWN_WAREHOUSE} 口径下
     * PLATFORM_FBA 的仓被排除在池容量外。
     */
    public record SellablePolicyWarehouseBinding(
        @NotNull UUID warehouseId, @NotBlank @Size(max = 16) String warehouseKind) {}
  }

  /** 写入结果：新档/被换掉的那一档、以及为什么这么改。 */
  public record SellablePolicySaved(
      UUID policyId,
      String poolKey,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      @Schema(nullable = true) UUID supersededPolicyId,
      String message) {}

  // ------------------------------------------------------------ 生效值

  /**
   * 某一时刻真正生效的口径。
   *
   * <p>{@code policy} 为 null 表示连兜底档都没配 —— 这是一个**必须如实报出来**的状态， 而不是"按 0 处理"：没有兜底档时平台可售数没有依据，04
   * 回写应当拒绝而不是回写 0 （回写 0 = 立刻断货，回写满 = 超卖）。
   */
  public record SellablePolicyEffective(
      @Schema(nullable = true) SellablePolicyRow policy,
      PolicyScopeType resolvedScopeType,
      boolean usedFallback,
      OffsetDateTime at,
      String note) {}

  // ------------------------------------------------------------ 共享池

  /** 共享池里的一个成员口径，以及它在池里占掉多少回写额度的关键参数。 */
  public record SellablePoolMember(
      UUID policyId,
      String poolKey,
      PolicyScopeType scopeType,
      @Schema(nullable = true) UUID referenceId,
      String referenceLabel,
      BigDecimal bufferRatio,
      boolean oversellAllowed,
      BigDecimal oversellTolerance,
      PlatformStockScope platformStockScope,
      boolean includeInTransit,
      long warehouseCount) {}

  /**
   * 一个池的安全体检结论（跨 SKU 汇总）。
   *
   * <p>{@code headroom} = poolCapacity − committedListing。 负数表示各成员的回写额加起来已经超过池容量 —— 共享池必然超卖。 {@code
   * oversellToleranceTotal} 是成员容忍额度之和，headroom 为负但在它之内时判定 {@link
   * PoolSafetyVerdict#TOLERATED}（有意的超卖），超出才是 {@code OVERSOLD}。
   */
  public record SellablePoolSummary(
      String poolKey,
      PoolMode poolMode,
      long memberCount,
      long warehouseCount,
      BigDecimal poolCapacity,
      BigDecimal bufferRatioSum,
      BigDecimal allocationRatio,
      BigDecimal committedListing,
      BigDecimal oversellToleranceTotal,
      BigDecimal headroom,
      PoolSafetyVerdict verdict,
      String note) {}

  /** 池体检明细 = 汇总 + 成员清单 + 被平台口径排除掉的 FBA 量。 */
  public record SellablePoolDetail(
      SellablePoolSummary summary,
      List<SellablePoolMember> members,
      BigDecimal excludedPlatformQuantity,
      String note) {}

  // ------------------------------------------------------------ 可售量计算

  /**
   * 发起一次可售量计算。
   *
   * <p>用 POST 而不是 GET：请求体是一组 SKU（与 {@code POST /inventory/availability} 同一理由， 列表放 URL
   * 里会撞长度限制）。租户不来自请求体也不来自任何客户端头。
   *
   * @param referenceId 店铺/渠道的跨服务逻辑 id；不传则按兜底档计算
   * @param at 计算口径的生效时刻；不传取当前时刻
   */
  public record SellableQuantityRequest(
      List<UUID> skuIds,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID referenceId,
      String scopeType,
      @Schema(nullable = true) OffsetDateTime at) {}

  /**
   * 单个 SKU 的口径拆解 —— 7.1.1.3 的「构成拆解（每一项扣了多少）」与 04 回写都读它。
   *
   * <p>注意{@code qualifiedOnHand} 只含 {@code inventory_quality_status.role='SELLABLE'} 的
   * bucket：待检、残次、未上架被**排除**而不是扣减（蓝图 7.1.1.3 原文）。
   *
   * @param inTransitAdded 因口径 includeInTransit=true 而计入的在途量（否则为 0）
   * @param bufferReserved poolCapacity × bufferRatio，即这一档刻意留在池里不外放的部分
   * @param platformListing 最终可回写平台的件数（向下取整，回写必须是整数件）
   */
  public record SellableQuantityRow(
      UUID skuId,
      String skuCode,
      long warehouseCount,
      BigDecimal qualifiedOnHand,
      BigDecimal inTransitAdded,
      BigDecimal poolCapacity,
      BigDecimal bufferRatio,
      BigDecimal bufferReserved,
      BigDecimal oversellAllowance,
      BigDecimal platformListing) {}

  /**
   * 一次计算的完整结论。
   *
   * <p>{@code effective} 为 null（连兜底档都没配）时 {@code rows} 为空且 {@code verdict} 为 null：调用方据此拒绝回写，而不是回写
   * 0。
   */
  public record SellableQuantityResult(
      @Schema(nullable = true) SellablePolicyEffective effective,
      @Schema(nullable = true) SellablePoolSummary pool,
      List<SellableQuantityRow> rows,
      long requestedSkuCount,
      BigDecimal totalPoolCapacity,
      BigDecimal totalPlatformListing,
      @Schema(nullable = true) PoolSafetyVerdict verdict,
      String note) {}
}
