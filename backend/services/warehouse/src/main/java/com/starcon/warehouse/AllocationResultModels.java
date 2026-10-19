package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.8.2.1 分摊结果查看的传输模型。
 *
 * <p>蓝图原话：「展示某 SKU/仓在各店铺的分摊量、已用、剩余、回写平台的数值，以及回写失败或延迟导致的实际风险敞口。输入：分摊结果；平台回写状态（4.3.2.1）。输出：分摊明细表 + 平台回写一致性核对。」以及关系段的红线：「**平台回写有延迟**，分摊需保留缓冲以覆盖同步窗口，缓冲不足会超卖。」
 *
 * <p><b>本模块的算式（就这一套，页面按它渲染）</b>：
 *
 * <pre>
 *   分摊量   allocated  = allocation_run_line.allocated_qty        （7.8.1.1 的事实）
 *   已用     consumed   = 最近一次回写观测里的 consumed_quantity  （平台侧已消耗）
 *   剩余     remaining  = 分摊量 − 已用
 *   平台挂着的 platform = 最近一次**带确认值**的观测里的 written_quantity
 *   缓冲     buffer     = 分摊量 − trunc(分摊量 × (1 − 缓冲比例) + 超卖容忍)
 *   敞口     exposure   = max(0, platform − remaining)             （平台还在卖、我方已没有）
 *
 *   一致性：platform vs (分摊量 − 缓冲)
 *     = 相等 ⇒ 一致；大于 ⇒ 平台挂多了；小于 ⇒ 平台挂少了
 *   敞口判定：exposure > buffer ⇒ **缓冲不足，同步窗口内会超卖**
 * </pre>
 *
 * <p><b>三条纪律</b>：
 *
 * <ul>
 *   <li><b>「不知道」是一个状态，不是一个 0</b>。{@link WritebackState#UNKNOWN} 与
 *       {@link ExposureState#UNKNOWN} 都是**显式取值**，调用方能把它与「回写数 = 0」区分开。
 *       平台回写状态在 channel 服务，本服务**不跨服务读表**；本地没收到回填时一律判 UNKNOWN。
 *   <li><b>数量与比例一律 {@link BigDecimal}</b>（DB 侧 numeric），比较一律 {@code compareTo}。
 *   <li><b>可空就是可空</b>：{@code consumedQuantity} / {@code platformQuantity} / {@code bufferQuantity}
 *       为 null 时表示「本地没有这个数」，不是 0。
 * </ul>
 */
public final class AllocationResultModels {

  private AllocationResultModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 平台回写状态（本地投影的当前值）。
   *
   * <p>{@code UNKNOWN} 是本模块最重要的一个取值：<b>本地尚未收到回写状态</b>。它与
   * 「回写数 = 0」是完全不同的两件事 —— 前者该去查同步，后者会去补货。
   */
  public enum WritebackState {
    UNKNOWN("尚未回填"),
    SUCCEEDED("已回写成功"),
    FAILED("回写失败"),
    PENDING("已发出未确认");

    private final String label;

    WritebackState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 平台回写一致性（分摊数 vs 回写数 vs 缓冲）。
   *
   * <p>{@code UNKNOWN} 有两个来源：本地没有回写状态；或者有状态但不知道平台当前挂的数；
   * 还有一种是「该店没有配置 7.2.2.1 口径」，此时「应该挂多少」本身没有依据。
   */
  public enum ConsistencyState {
    UNKNOWN("无法核对"),
    CONSISTENT("一致"),
    OVER_ADVERTISED("平台挂多了"),
    UNDER_ADVERTISED("平台挂少了");

    private final String label;

    ConsistencyState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 风险敞口判定。
   *
   * <p>{@code BEYOND_BUFFER} 是蓝图那句「缓冲不足会超卖」的落点：它不是一句提示文案，
   * 而是一个**算出来的数**（敞口件数）与一个**比出来的结论**（敞口 &gt; 缓冲）。
   */
  public enum ExposureState {
    UNKNOWN("无法计算（回写状态未知）"),
    NO_EXPOSURE("无敞口"),
    WITHIN_BUFFER("敞口在缓冲覆盖范围内"),
    BEYOND_BUFFER("缓冲不足，同步窗口内会超卖");

    private final String label;

    ExposureState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 观测来源。{@code MANUAL} 是运营显式录入的快照，其余两种是系统推送。 */
  public enum WritebackSourceKind {
    EVENT("事件推送"),
    API("授权 API 推送"),
    MANUAL("显式录入");

    private final String label;

    WritebackSourceKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 结果表的筛选口径。
   *
   * <p>{@code EXPOSURE} / {@code BEYOND_BUFFER} / {@code INCONSISTENT} / {@code UNKNOWN}
   * 是四种**不同的病**，刻意分成四个取值而不是一个「异常」开关：
   * 前者是「已经在超卖」、{@code UNKNOWN} 是「连数都没有」——处置完全不同。
   */
  public enum ResultScope {
    ALL("全部"),
    EXPOSURE("只看有敞口的"),
    BEYOND_BUFFER("只看缓冲不足的"),
    INCONSISTENT("只看回写不一致的"),
    UNKNOWN("只看回写状态未知的");

    private final String label;

    ResultScope(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 查询

  /**
   * 分摊明细表的查询条件。
   *
   * @param runId 分摊轮次
   * @param skuId SKU
   * @param warehouseId 仓。**按回写观测所记录的仓筛选**；本地尚未收到回写状态的行
   *     无法归属到仓，会被这个条件排除（被排除的行数由 {@code warehouseUnattributedCount} 报出）
   * @param referenceId 店铺（channel 服务的逻辑 id）
   * @param method 分摊方法
   * @param allocatedFrom / allocatedTo 分摊时刻区间（左闭右开）
   * @param scope 只看某一类问题
   */
  public record AllocationResultQuery(
      @Schema(nullable = true) UUID runId,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID referenceId,
      @Schema(nullable = true) String method,
      @Schema(nullable = true) OffsetDateTime allocatedFrom,
      @Schema(nullable = true) OffsetDateTime allocatedTo,
      @Schema(nullable = true) String scope,
      int page,
      int size) {}

  // ------------------------------------------------------------ 分摊明细行

  /**
   * 一行分摊明细。
   *
   * @param allocatedQuantity 分摊量（7.8.1.1 的事实）
   * @param runBufferQuantity 这次分摊的**分摊缓冲**（7.8.1.1 的口径：同一批货里压根不参与分摊的量）。
   *     它与本行的 {@code bufferQuantity}（7.2.2.1 的**平台回写缓冲**）是两个不同的东西
   * @param outcome 7.8.1.1 的分摊结论（OK / CAP_REACHED / DEMAND_EXHAUSTED / NO_SALES / DISABLED）
   * @param consumedQuantity 已用（平台侧已消耗）。<b>null = 本地没有这个数</b>
   * @param remainingQuantity 剩余 = 分摊量 − 已用。null 时同样表示「算不出来」
   * @param writebackState 平台回写状态。<b>UNKNOWN = 尚未回填</b>
   * @param platformQuantity 平台当前挂着的可售件数。null = 不知道
   * @param attemptedQuantity 本次尝试回写的件数
   * @param bufferRatio 平台回写缓冲比例（来自 7.2.2.1 的 sellable_policy）。<b>null = 该店没配口径</b>
   * @param bufferQuantity 这次分摊里为该店保留的缓冲。null = 没配口径
   * @param writableQuantity 应当回写给平台的可售件数 = 分摊量 − 缓冲。null = 没配口径
   * @param exposureQuantity 风险敞口 = max(0, 平台挂着的 − 我方剩余)。null = 算不出来
   * @param advertisedDelta 平台挂着的 − 我方剩余（有符号）。正数 = 平台多挂，负数 = 平台少挂
   */
  public record AllocationResultRow(
      UUID runId,
      UUID skuId,
      @Schema(nullable = true) String skuCode,
      String method,
      OffsetDateTime allocatedAt,
      UUID policyId,
      BigDecimal availableQuantity,
      BigDecimal runBufferQuantity,
      BigDecimal allocatableQuantity,
      BigDecimal runAllocatedTotal,
      BigDecimal runUnallocatedQuantity,
      String sourceSystem,
      UUID referenceId,
      String referenceLabel,
      String shopOperatingMode,
      int allocationOrder,
      BigDecimal allocatedQuantity,
      String outcome,
      BigDecimal salesUnits,
      @Schema(nullable = true) BigDecimal salesShare,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) String warehouseCode,
      @Schema(nullable = true) String warehouseName,
      WritebackState writebackState,
      @Schema(nullable = true) BigDecimal attemptedQuantity,
      @Schema(nullable = true) BigDecimal platformQuantity,
      @Schema(nullable = true) BigDecimal consumedQuantity,
      @Schema(nullable = true) BigDecimal remainingQuantity,
      @Schema(nullable = true) String failureReason,
      @Schema(nullable = true) OffsetDateTime asOf,
      @Schema(nullable = true) String sourceKind,
      @Schema(nullable = true) String sourceReference,
      @Schema(nullable = true) BigDecimal bufferRatio,
      boolean bufferConfigured,
      @Schema(nullable = true) BigDecimal bufferQuantity,
      @Schema(nullable = true) BigDecimal writableQuantity,
      @Schema(nullable = true) BigDecimal exposureQuantity,
      @Schema(nullable = true) BigDecimal advertisedDelta,
      ConsistencyState consistencyState,
      ExposureState exposureState,
      @Schema(nullable = true) String floorStatus,
      @Schema(nullable = true) BigDecimal floorShortfallQuantity,
      @Schema(nullable = true) Boolean suppressed,
      @Schema(nullable = true) String suppressionReason) {}

  /**
   * 分摊明细表（真分页，total 不受分页影响）。
   *
   * @param summary 整个筛选口径下的汇总（不是当前页）
   * @param warehouseUnattributedCount 按仓筛选时被排除的「仓归属未知」行数。
   *     本地尚未收到回写状态的行无法归属到仓，这个数让「筛选掉了多少」可见，
   *     而不是让它们无声消失。
   */
  public record AllocationResultPage(
      List<AllocationResultRow> rows,
      long total,
      int page,
      int size,
      AllocationResultSummary summary,
      long warehouseUnattributedCount,
      OffsetDateTime evaluatedAt) {}

  /**
   * 分摊结果的汇总 —— 「平台回写一致性核对」的总量口径。
   *
   * <p>三个「已知 / 未知」的区分是这一页最重要的语义：
   *
   * <ul>
   *   <li>{@code consumedKnownCount} / {@code platformKnownCount}：分母是**全部行数**，
   *       分子是**真的拿到了这个数的行数**。两个数一起看才知道「已用」这一列有多少可信。
   *   <li>{@code unknownStateCount}：本地尚未收到回写状态的行数。
   *   <li>{@code exposureTotal} 只在可算的行上求和（{@code exposureComputableCount} 行），
   *       不可算的行**不按 0 计入**。
   * </ul>
   */
  public record AllocationResultSummary(
      long rowCount,
      BigDecimal allocatedTotal,
      long consumedKnownCount,
      BigDecimal consumedTotal,
      long platformKnownCount,
      BigDecimal platformTotal,
      long unknownStateCount,
      long consistentCount,
      long overAdvertisedCount,
      long underAdvertisedCount,
      long unknownConsistencyCount,
      long exposureComputableCount,
      BigDecimal exposureTotal,
      long withinBufferCount,
      long beyondBufferCount,
      long noExposureCount,
      long unknownExposureCount) {}

  // ------------------------------------------------------------ 分摊轮次

  /**
   * 一次分摊轮次（7.8.1.1 的 allocation_run 头）。
   *
   * @param shopCount 这批货分给了几家店
   * @param observedShopCount 其中几家有回写状态观测
   * @param unknownShopCount 其中几家**还没有**回写状态（= shopCount − observedShopCount）
   */
  public record AllocationResultRunRow(
      UUID runId,
      UUID skuId,
      @Schema(nullable = true) String skuCode,
      String method,
      OffsetDateTime allocatedAt,
      BigDecimal availableQuantity,
      BigDecimal bufferQuantity,
      BigDecimal allocatableQuantity,
      BigDecimal allocatedTotal,
      BigDecimal unallocatedQuantity,
      long shopCount,
      long observedShopCount,
      long unknownShopCount) {}

  /** 分摊轮次列表（真分页）。 */
  public record AllocationResultRunPage(List<AllocationResultRunRow> runs, long total, int page, int size) {}

  // ------------------------------------------------------------ 回写观测

  /**
   * 登记一次平台回写状态的观测。
   *
   * <p>{@code writtenQuantity} 在 {@code state=SUCCEEDED} 时必填（DB 层 CHECK 也钉住了）；
   * {@code state=FAILED} 时 {@code failureReason} 必填。
   *
   * @param asOf 采集时刻（**观测成立的那一刻**，不是入库时刻）
   * @param sourceKind EVENT / API / MANUAL
   * @param sourceReference 来源标识：事件 id、API 调用标识或录入单号
   */
  public record AllocationWritebackInput(
      @NotNull UUID runId,
      @NotBlank @Size(max = 32) String sourceSystem,
      @NotNull UUID referenceId,
      @Size(max = 150) String referenceLabel,
      @NotNull UUID warehouseId,
      @NotBlank @Size(max = 16) String state,
      @NotNull @DecimalMin(value = "0") BigDecimal attemptedQuantity,
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal writtenQuantity,
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal consumedQuantity,
      @Size(max = 300) String failureReason,
      @NotBlank @Size(max = 16) String sourceKind,
      @NotBlank @Size(max = 200) String sourceReference,
      @NotNull OffsetDateTime asOf) {}

  /** 一条回写状态观测。 */
  public record AllocationWritebackRow(
      UUID id,
      UUID runId,
      String sourceSystem,
      UUID referenceId,
      String referenceLabel,
      UUID warehouseId,
      @Schema(nullable = true) String warehouseCode,
      @Schema(nullable = true) String warehouseName,
      WritebackState state,
      BigDecimal attemptedQuantity,
      @Schema(nullable = true) BigDecimal platformQuantity,
      @Schema(nullable = true) BigDecimal consumedQuantity,
      String failureReason,
      WritebackSourceKind sourceKind,
      String sourceReference,
      OffsetDateTime asOf,
      UUID recordedByMembershipId,
      OffsetDateTime createdAt) {}

  /** 回写观测列表（真分页）。 */
  public record AllocationWritebackPage(
      List<AllocationWritebackRow> observations, long total, int page, int size) {}

  /** 登记观测的结果。 */
  public record AllocationWritebackSaved(
      UUID observationId,
      UUID runId,
      UUID referenceId,
      WritebackState state,
      @Schema(nullable = true) BigDecimal platformQuantity,
      @Schema(nullable = true) BigDecimal consumedQuantity,
      OffsetDateTime asOf,
      String message) {}

  // ------------------------------------------------------------ 一致性核对

  /**
   * 一次分摊轮次的核对结论。
   *
   * @param exposureTotal 这一轮所有可算的敞口之和
   * @param beyondBufferShopCount 缓冲不足的店铺数 —— 这一轮最该先看的一个数
   */
  public record AllocationReconciliationRun(
      UUID runId,
      UUID skuId,
      @Schema(nullable = true) String skuCode,
      OffsetDateTime allocatedAt,
      long shopCount,
      long unknownStateCount,
      long consistentCount,
      long overAdvertisedCount,
      long underAdvertisedCount,
      long beyondBufferCount,
      long withinBufferCount,
      long exposureComputableCount,
      BigDecimal exposureTotal) {}

  /** 平台回写一致性核对的报告：总量口径 + 逐轮结论。 */
  public record AllocationReconciliationReport(
      AllocationResultSummary summary,
      List<AllocationReconciliationRun> runs,
      String note) {}

  // ------------------------------------------------------------ 能力

  /** 一项能力的说明。{@code available=false} 时 {@code note} 说明为什么。 */
  public record ResultCapabilityOption(String code, String label, boolean available, String note) {}

  /**
   * 分摊结果查看的能力清单 —— 前端据此渲染文案与状态色，而不是把规则硬编码在界面上。
   *
   * @param writebackProjectionAvailable 平台回写状态是否可自动获得。恒为 false：
   *     4.3.2.1 在 channel 服务，本服务不跨服务读表，只能靠事件 / 授权 API 推送或显式录入
   * @param writebackProjectionNote 讲清这条边界：回写状态经事件 / API 进入本地投影，**不跨服务读表**
   * @param unknownStateNote 讲清「UNKNOWN 与 0 是两件事」
   * @param bufferNote 讲清三个「缓冲」里本模块用的是哪一个
   * @param exposureNote 讲清敞口算式与「缓冲不足 ⇒ 同步窗口内会超卖」
   * @param warehouseNote 讲清仓维度的来源与「尚未回填的行无法归属到仓」
   */
  public record AllocationResultCapabilities(
      List<ResultCapabilityOption> writebackStates,
      List<ResultCapabilityOption> consistencyStates,
      List<ResultCapabilityOption> exposureStates,
      List<ResultCapabilityOption> sourceKinds,
      List<ResultCapabilityOption> scopes,
      boolean writebackProjectionAvailable,
      String writebackProjectionNote,
      String unknownStateNote,
      String bufferNote,
      String exposureNote,
      String warehouseNote,
      String downstreamNote) {}
}
