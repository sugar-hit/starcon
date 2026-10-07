package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.8.1.1 分配策略配置的传输模型。
 *
 * <p>蓝图原话：「定义同一总可用库存分配给多个店铺/平台的方式：按销量占比比例分配、按固定优先级先到先得、
 * 按店铺类型（自营/托管）优先级、留出不分配缓冲。支持 SKU 或店铺维度的策略覆盖。输入：分配策略配置
 * （方法、参数、生效范围）；当前可用量；各店铺历史销量。输出：生效的分配策略；各店铺分摊额。」
 * 以及关系段的红线：「**多平台共享库存时必须有一份统一的分摊算法**，否则会出现『某个店铺一直抢不到货』
 * 或『所有店铺都以为有货』的超卖。」
 *
 * <p><b>三种方法的算式（整个模块只有这一套口径，别处再写一遍就会算错）</b>：
 *
 * <pre>
 *   缓冲   buffer      = RATIO: trunc(available × bufferRatio)
 *                      | QUANTITY: min(bufferQuantity, available)
 *   可分配 allocatable = available − buffer
 *
 *   PROPORTIONAL_SALES  份额_i = 销量_i / Σ销量
 *                      分摊_i = trunc(allocatable × 份额_i)          ← 逐个向下取整
 *                      余数   = allocatable − Σ分摊_i                  ← 不足成员数
 *                      补发   = 余数按「份额小数部分从大到小」逐件 +1，同小数按店铺 id 升序
 *                      ⇒ Σ分摊 ≤ allocatable，且余数被显式处理（不是四舍五入后总账对不上）
 *
 *   FIXED_PRIORITY      按 priority 升序逐个分：
 *                      分摊_i = min(trunc(allocatable × shareLimit_i), 剩余)   ← 上限为空则取满剩余
 *
 *   SHOP_TYPE_PRIORITY  同上，但次序先按 shopTypeOrder 里该店铺类型的名次，
 *                      同类型内再按 priority 升序。
 * </pre>
 *
 * <p><b>先到先得为什么向下取整而不是四舍五入</b>：份额上限 33.3% × 可分配 10 件 = 3.33 件，
 * 三家各 3.33 时逐个向下取整得 9 件、余 1 件 —— 那 1 件按份额小数部分最大的一家补发。
 * 若改成四舍五入，三家各得 3.33→3.33→3.34，Σ 仍是 10，看起来更「精确」，
 * 但它把「不满一件」也发了出去，而可分配量未必还有第 11 件。
 *
 * <p><b>补发为什么按小数部分排序而不是「都加一件」</b>：11 件按 0.6/0.4 分是 6.6/4.4，
 * 向下取整得 6/4、余 1 件；把它给小数部分大的那家（0.6）得 7/4 而不是 6/5 ——
 * 后者会把货分给销量占比更低的那家，而「按销量占比分配」这句话就名不副实了。
 *
 * <p>数量与比例一律 {@link BigDecimal}（DB 侧 numeric），比较一律 {@code compareTo}
 * 而非 {@code equals}（后者把 0 与 0.0000 判成不等）。
 */
public final class AllocationPolicyModels {

  private AllocationPolicyModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 生效范围。蓝图要求「支持 SKU 或店铺维度的策略覆盖」，覆盖即分档：
   * 优先级 SKU &gt; SHOP &gt; DEFAULT（{@link #priority()} 升序即更优先），与 17.5.1.1
   * 业务参数、7.2.2.1 可售量口径同一套解析约定。
   */
  public enum AllocationScopeType {
    DEFAULT("全租户兜底"),
    SHOP("按店铺"),
    SKU("按 SKU");

    private final String label;
    private final int priority;

    AllocationScopeType(String label) {
      this.label = label;
      this.priority = ordinal();
    }

    public String label() {
      return label;
    }

    /** 解析优先级：数值越小越优先（SKU 最具体，DEFAULT 兜底）。 */
    public int priority() {
      return priority;
    }
  }

  /**
   * 分摊方法。三个取值对应蓝图列的三种「方式」。
   *
   * <p>刻意把「留出不分配缓冲」做成参数（{@code bufferMode}）而不是第四种方法：
   * 缓冲在三种方法下都要扣，先到先得时不留缓冲就是「低优先级店铺永远拿不到货」，
   * 比例分配时不留缓冲就是「一件不留」—— 那是共同需求，不是另一种分法。
   */
  public enum AllocationMethod {
    PROPORTIONAL_SALES("按销量占比比例分配"),
    FIXED_PRIORITY("按固定优先级先到先得"),
    SHOP_TYPE_PRIORITY("按店铺类型优先级");

    private final String label;

    AllocationMethod(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 该方法是否需要销量数据。只有比例分配读 {@code allocation_shop_sales}。 */
    public boolean needsSales() {
      return this == PROPORTIONAL_SALES;
    }
  }

  /** 缓冲口径：按比例留，还是按件数留。二者互斥（V68 的 CHECK 钉死）。 */
  public enum AllocationBufferMode {
    RATIO("按比例留"),
    QUANTITY("按件数留");

    private final String label;

    AllocationBufferMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 一个店铺在某次分摊里拿到什么、以及**为什么**是这个数。
   *
   * <p>蓝图说的「某个店铺一直抢不到货」必须能一眼看出是哪种原因，所以原因不是备注而是枚举：
   *
   * <ul>
   *   <li>{@code OK}：正常分到货。
   *   <li>{@code CAP_REACHED}：触到自己的份额上限（先到先得下这是正常的封顶）。
   *   <li>{@code DEMAND_EXHAUSTED}：货被排在前面的店铺分完了，它没抢到。
   *   <li>{@code NO_SALES}：销量为 0，比例分配下必然分不到（销量 0 的店铺分不到货，如实如此）。
   *   <li>{@code DISABLED}：成员被停用，不参与分摊。
   * </ul>
   */
  public enum AllocationOutcome {
    OK("正常"),
    CAP_REACHED("触到份额上限"),
    DEMAND_EXHAUSTED("货被前面的分完"),
    NO_SALES("销量为 0"),
    DISABLED("成员已停用");

    private final String label;

    AllocationOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 查询

  /** 策略列表查询。{@code effectiveOnly=true} 只看此刻仍在生效的档。 */
  public record AllocationPolicyQuery(
      String scopeType,
      @Schema(nullable = true) UUID referenceId,
      String method,
      boolean effectiveOnly,
      String keyword,
      int page,
      int size) {}

  // ------------------------------------------------------------ 策略分档

  /**
   * 一档分摊策略。
   *
   * <p>内容列写定后不可修改（V68 触发器钉死），改策略 = 收口旧档 + 开新档。
   * {@code effectiveTo} 为 null 表示至今仍在生效。
   *
   * @param bufferRatio 按比例留时的比例，0 ~ 1
   * @param bufferQuantity 按件数留时的件数；{@code bufferMode=QUANTITY} 时该列为 0
   * @param shopTypeOrder 店铺类型优先级顺序（逗号分隔的 5 种经营模式排列）
   * @param salesWindowDays 比例分配回看销量的天数
   */
  public record AllocationPolicyRow(
      UUID id,
      AllocationScopeType scopeType,
      @Schema(nullable = true) UUID referenceId,
      String referenceLabel,
      AllocationMethod method,
      AllocationBufferMode bufferMode,
      BigDecimal bufferRatio,
      BigDecimal bufferQuantity,
      String shopTypeOrder,
      int salesWindowDays,
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

  /**
   * 参与分摊的店铺成员。
   *
   * <p>{@code shareLimit} 为 null 表示**不设上限**：先到先得下不设上限意味着排第一的店铺
   * 拿走全部 —— 这在业务上几乎总是错的，所以这里给一个能显式封顶的口子，
   * 并让「不设上限」成为一个界面上必须被看见的取值。
   *
   * @param shopOperatingMode 店铺类型快照，取值与 channel 的 shop.operating_mode 一致（2.3.2.1）
   * @param priority 固定优先级，越小越先；SHOP_TYPE_PRIORITY 下是同类型内的次序
   */
  public record AllocationPolicyMember(
      UUID policyId,
      String sourceSystem,
      UUID referenceId,
      String referenceLabel,
      String shopOperatingMode,
      int priority,
      @Schema(nullable = true) BigDecimal shareLimit,
      boolean enabled) {}

  /** 策略详情 = 分档本身 + 它带的全部成员。 */
  public record AllocationPolicyDetail(AllocationPolicyRow policy, List<AllocationPolicyMember> members) {}

  /** 策略列表（真分页，total 不受分页影响）。 */
  public record AllocationPolicyPage(List<AllocationPolicyRow> policies, long total, int page, int size) {}

  // ------------------------------------------------------------ 写入

  /**
   * 新建一档分摊策略。
   *
   * <p>{@code scopeType} 非 DEFAULT 时必须给 {@code sourceSystem} + {@code referenceId}：
   * 店铺在 channel 服务、SKU 在 catalog 服务，本服务不建外键也不查它们的表
   * （AGENTS.md 模块边界纪律），只存跨服务定位键 + 写入时的可读名快照。
   *
   * @param bufferMode RATIO 时 {@code bufferQuantity} 必须为 0，QUANTITY 时 {@code bufferRatio} 必须为 0
   * @param shopTypeOrder 留空则用「自营 → 半托管 → 全托管 → 海外仓 → 本地」这一默认顺序
   */
  public record AllocationPolicyInput(
      @NotBlank @Size(max = 16) String scopeType,
      @Size(max = 32) String sourceSystem,
      @Schema(nullable = true) UUID referenceId,
      @Size(max = 150) String referenceLabel,
      @NotBlank @Size(max = 24) String method,
      @NotBlank @Size(max = 8) String bufferMode,
      @Schema(nullable = true) @DecimalMin(value = "0") @DecimalMax(value = "1") BigDecimal bufferRatio,
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal bufferQuantity,
      @Size(max = 400) String shopTypeOrder,
      @Min(1) @Max(365) Integer salesWindowDays,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Size(max = 500) String note,
      @NotEmpty List<@NotNull @Valid AllocationPolicyMemberInput> members) {

    /**
     * 一个参与分摊的店铺。
     *
     * <p>{@code shopOperatingMode} 必须逐个声明：它不是装饰列 ——
     * 「按店铺类型优先级」这条方法真的按它排序，缺了它就没有排序依据。
     */
    public record AllocationPolicyMemberInput(
        @NotBlank @Size(max = 32) String sourceSystem,
        @NotNull UUID referenceId,
        @Size(max = 150) String referenceLabel,
        @NotBlank @Size(max = 24) String shopOperatingMode,
        @Min(0) Integer priority,
        @Schema(nullable = true) @DecimalMin(value = "0") @DecimalMax(value = "1") BigDecimal shareLimit,
        Boolean enabled) {}
  }

  /** 写入结果：新档/被换掉的那一档、以及为什么这么改。 */
  public record AllocationPolicySaved(
      UUID policyId,
      AllocationMethod method,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      @Schema(nullable = true) UUID supersededPolicyId,
      long memberCount,
      String message) {}

  // ------------------------------------------------------------ 生效值

  /**
   * 某一时刻真正生效的策略。
   *
   * <p>{@code policy} 为 null 表示连兜底档都没配 —— 这是一个**必须如实报出来**的状态，
   * 而不是「按平均分配处理」：没有兜底档时，多平台共享库存没有任何统一算法，
   * 而蓝图那句红线要的恰恰是「有一份」。
   */
  public record AllocationPolicyEffective(
      @Schema(nullable = true) AllocationPolicyRow policy,
      AllocationScopeType resolvedScopeType,
      boolean usedFallback,
      OffsetDateTime at,
      String note) {}

  // ------------------------------------------------------------ 销量登记

  /**
   * 登记一条店铺历史销量。
   *
   * <p>它是本服务**唯一的销量真相来源**：库存流水没有店铺维度，出库量不等于店铺销量，
   * 而订单域（05 卷）尚未交付，没有可查的订单事实。所以销量是登记进来的事实，
   * {@code /capabilities} 会把这件事如实讲给调用方而不是假装它是从订单算出来的。
   *
   * @param periodStart/periodEnd 业务日期闭区间
   */
  public record AllocationShopSalesInput(
      @NotBlank @Size(max = 32) String sourceSystem,
      @NotNull UUID referenceId,
      @Size(max = 150) String referenceLabel,
      @NotNull UUID skuId,
      @NotNull LocalDate periodStart,
      @NotNull LocalDate periodEnd,
      @NotNull @DecimalMin(value = "0") BigDecimal unitsSold) {}

  /** 销量登记结果。 */
  public record AllocationShopSalesSaved(
      UUID salesId,
      UUID referenceId,
      UUID skuId,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal unitsSold,
      String message) {}

  /**
   * 窗口内一个店铺的销量合计。
   *
   * @param records 汇总自几条登记行。区间不连续时会多于 1 条，
   *     而「这段时间到底登记了几次」是排查比例分配偏差时的第一个问题
   */
  public record SaleRow(
      UUID referenceId, String referenceLabel, BigDecimal units, long records) {}

  // ------------------------------------------------------------ 分摊

  /**
   * 发起一次分摊试算。
   *
   * <p>用 POST 而不是 GET：请求体是一组 SKU 加上成员集合，放 URL 里会撞长度限制
   * （与 {@code POST /inventory/availability}、7.2.2.1 的 calculation 同一理由）。
   *
   * @param availableQuantity 当前可用量；传 null 则由服务按 7.2.2.1 的池容量口径现算
   * @param skuId 只算一个 SKU。分摊是 SKU × 成员，一次算一个 SKU 才有「这批货」的含义
   */
  public record AllocationRequest(
      @NotEmpty List<@NotNull UUID> skuIds,
      @Schema(nullable = true) BigDecimal availableQuantity,
      @Schema(nullable = true) UUID referenceId,
      String scopeType,
      @Schema(nullable = true) OffsetDateTime at,
      Boolean persist) {}

  /**
   * 一个店铺在某次分摊里的结果。
   *
   * @param salesShare 该店销量在本档全部成员销量里的占比；销量合计为 0 时为 null
   * @param allocatedQty 分摊到它的件数（整件）
   * @param outcome 它拿到这个数的原因，见 {@link AllocationOutcome}
   */
  public record AllocationLine(
      String sourceSystem,
      UUID referenceId,
      String referenceLabel,
      String shopOperatingMode,
      int allocationOrder,
      BigDecimal salesUnits,
      @Schema(nullable = true) BigDecimal salesShare,
      BigDecimal allocatedQty,
      AllocationOutcome outcome) {}

  /**
   * 一次分摊的完整结果 —— 本模块真正的产出。
   *
   * <p>守恒式就在这三个数里，任何时候都能核对：
   * {@code availableQuantity = bufferQuantity + allocatableQuantity} 且
   * {@code allocatableQuantity = allocatedTotal + unallocatedQuantity}。
   * 两者都由 DB 的 CHECK 与延迟约束触发器强制，不靠应用层自觉。
   */
  public record AllocationResult(
      AllocationPolicyEffective effective,
      @Schema(nullable = true) AllocationRunRef run,
      UUID skuId,
      String skuCode,
      List<AllocationLine> lines,
      BigDecimal availableQuantity,
      BigDecimal bufferQuantity,
      BigDecimal allocatableQuantity,
      BigDecimal allocatedTotal,
      BigDecimal unallocatedQuantity,
      LocalDate salesWindowStart,
      LocalDate salesWindowEnd,
      BigDecimal totalSalesUnits,
      @Schema(nullable = true) BigDecimal totalSalesShare,
      long memberCount,
      long starvedMemberCount,
      String note) {}

  /** 落库的一次分摊引用。{@code runId} 为 null 表示这是一次**试算**，没有写任何行。 */
  public record AllocationRunRef(UUID runId, OffsetDateTime allocatedAt, boolean persisted) {}

  /** 分摊批次列表（真分页）。 */
  public record AllocationRunPage(List<AllocationRunSummary> runs, long total, int page, int size) {}

  /** 分摊批次摘要（列表用；明细在详情端点里）。 */
  public record AllocationRunSummary(
      UUID runId,
      UUID policyId,
      String policyLabel,
      AllocationMethod method,
      UUID skuId,
      String skuCode,
      OffsetDateTime allocatedAt,
      BigDecimal availableQuantity,
      BigDecimal bufferQuantity,
      BigDecimal allocatableQuantity,
      BigDecimal allocatedTotal,
      BigDecimal unallocatedQuantity,
      long lineCount,
      long starvedLineCount) {}

  /** 分摊批次详情 = 摘要 + 逐店明细。跨租户在 RLS 下查不到 → 404，不泄露存在性。 */
  public record AllocationRunDetail(AllocationRunSummary run, List<AllocationLine> lines) {}

  // ------------------------------------------------------------ 能力

  /** 分摊方法的一项能力说明。{@code available=false} 时 {@code note} 说明为什么。 */
  public record AllocationMethodOption(
      String code, String label, boolean available, boolean needsSales, String note) {}

  /** 分摊范围维度的一项能力说明。 */
  public record AllocationScopeOption(String code, String label, boolean available, String note) {}

  /**
   * 分摊能力清单 —— 前端据此渲染选项与提示，而不是把规则硬编码在界面上。
   *
   * @param salesBasisAvailable 销量数据是否可自动派生。订单域未交付，恒为 false 并给出原因
   * @param bufferNote 讲清 7.8.1.1 的缓冲与 7.2.2.1 的平台回写缓冲是两个不同的东西
   */
  public record AllocationCapabilities(
      List<AllocationMethodOption> methods,
      List<AllocationScopeOption> scopes,
      List<String> shopOperatingModes,
      String defaultShopTypeOrder,
      boolean salesBasisAvailable,
      String salesBasisNote,
      String bufferNote,
      String downstreamNote) {}
}