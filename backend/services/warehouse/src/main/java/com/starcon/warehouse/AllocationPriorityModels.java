package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.8.1.2 优先级与保底的传输模型。
 *
 * <p>蓝图原话：「在分摊冲突时按优先级裁决，并为高价值店铺设置保底可用量与安全垫，防止低优先级店铺长期断货影响其
 * 店铺绩效与平台考核。输入：店铺优先级；保底数量/比例；当前销售与绩效。输出：分摊结果（含被压低店铺的提示）； 保底生效记录。」以及关系段的红线：「**保底是策略不是事实**，需可解释可调整。」
 *
 * <p><b>两遍分配的算式（整个模块只有这一套口径）</b>：
 *
 * <pre>
 *   ① 可用量 available = 缓冲 buffer + 可分配量 allocatable      ← 缓冲口径是 7.8.1.1 的，本模块只算一次
 *   ② 保底池 floorPool = min(allocatable, Σ(保底_i + 安全垫_i))    ← 只统计配了保底且成员启用的店
 *   ③ 余量池 residual   = allocatable − 保底池
 *
 *   ④ 保底裁决（这就是「在分摊冲突时按优先级裁决」）：
 *        次序 = 策略解析出的次序（SHOP_TYPE_PRIORITY 时按 shopTypeOrder 名次 + 成员 priority；
 *                              另两种方法按成员 priority 升序，tie-break 用店铺 id 升序保证可复现）
 *        逐个：granted_i = min(请求_i, 保底池剩余)
 *               请求_i = floorMode=QUANTITY: min(保底件数 + 安全垫, allocatable)
 *                       | RATIO:    trunc(allocatable × 比例) + 安全垫，再夹到 allocatable
 *        差额 shortfall_i = 请求_i − granted_i，显式记成 shortfall 并写明原因
 *
 *   ⑤ 余量分配：保底**未被足额满足**的店铺才参与（已足额的不再从余量池拿第二份）
 *        按 7.8.1.1 的同一套算式跑在 residual 上，份额在参与店铺之间归一化
 *
 *   ⑥ 最终分摊_i = granted_i + residual_i
 *        基线 base_i = 同一批货在「完全没有保底」时按 7.8.1.1 算式算出的数
 *        被压低 ⟺ 最终分摊_i &lt; base_i
 *
 *   ⇒ Σ最终分摊 ≤ allocatable（超卖由 V68 的 CHECK 与 V76 的延迟触发器各挡一次）
 * </pre>
 *
 * <p><b>为什么先保底再分余量，而不是先分余量再补差</b>：补差要判断「补到多少算够」， 而那个判断依赖已经分出去的结果 —— 补完可能超过
 * allocatable，超出的部分又要从别人身上拿回来， 拿回来又会让别人不够格 ……。先划池子则每一步的上界都是静态的， 「谁先拿到」只取决于次序，而次序是**配置**出来的、可复现的。
 *
 * <p><b>为什么被压低这件事必须落库</b>：蓝图输出写的是「分摊结果（含被压低店铺的提示）」。 只显示最终分摊额的话，运营看到的是「这家店今天只有 3 件」，
 * 但答不出「它是被谁挤下去的」—— 而这正是本模块存在的意义。 {@code baseAllocatedQty} 与 {@code allocatedQty}
 * 的差就是那个答案，它必须是数据而不是注释。
 *
 * <p>数量与比例一律 {@link BigDecimal}（DB 侧 numeric），比较一律 {@code compareTo} 而非 {@code equals}（后者把 0 与
 * 0.0000 判成不等）。
 */
public final class AllocationPriorityModels {

  private AllocationPriorityModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 保底口径。蓝图输入写的是「保底数量/比例」，两种都支持。
   *
   * <p>刻意不给安全垫做比例口径：7.2.2.1 的 {@code buffer_ratio} 已经是比例口径， 再来一个比例口径的安全垫，界面上就会有两个都叫「比例」的百分比。
   */
  public enum FloorMode {
    QUANTITY("按件数保底"),
    RATIO("按可分配量比例保底");

    private final String label;

    FloorMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 保底兑现状态。{@code shortfallQty} 为 0 时恒为 {@code FULFILLED}（V76 的 CHECK 钉死）。 */
  public enum FloorStatus {
    FULFILLED("足额兑现"),
    PARTIAL("部分兑现"),
    UNFULFILLED("未兑现");

    private final String label;

    FloorStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 保底没给到的原因。
   *
   * <p>枚举而不是自由文本：「没给到」如果只表现为最终分摊额变小，界面上就和 「它本来就不够格」长得一模一样，复盘时根本分不出是货不够还是没配保底。
   */
  public enum FloorShortfallReason {
    POOL_EXHAUSTED("保底池被前面的店分完"),
    MEMBER_DISABLED("成员已停用，不参与分摊"),
    NO_STOCK("可用量不足以兑现任何保底"),
    NOT_A_MEMBER("已不在该策略的成员名册里");

    private final String label;

    FloorShortfallReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 被压低的原因。
   *
   * <p>{@code RESERVED_FOR_FLOOR} 是本模块最该被看见的一个值：它的意思是 「这台机器上原本没有你的事，是别家店的保底把你挤下去的」—— 处置建议（调保底 /
   * 调优先级）与「本来就轮不到它」完全不同。
   */
  public enum SuppressionReason {
    RESERVED_FOR_FLOOR("被别家店的保底占用了货"),
    POOL_EXHAUSTED("货被前面的店分完"),
    MEMBER_DISABLED("成员已停用"),
    NO_SALES("销量为 0");

    private final String label;

    SuppressionReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 查询

  /**
   * 保底列表查询。
   *
   * @param effectiveOnly {@code true} 只看此刻仍在生效的保底
   */
  public record FloorQuery(
      @Schema(nullable = true) UUID policyId,
      @Schema(nullable = true) UUID referenceId,
      boolean effectiveOnly,
      String keyword,
      int page,
      int size) {}

  // ------------------------------------------------------------ 保底分档

  /**
   * 一档保底。
   *
   * <p>内容列写定后不可修改（V76 触发器钉死），改保底 = 收口旧档 + 开新档。 {@code effectiveTo} 为 null 表示至今仍在生效。
   *
   * @param floorMode RATIO 时 {@code floorQuantity} 必须为 0，QUANTITY 时 {@code floorRatio} 必须为 0
   * @param safetyQuantity 保底之上的安全垫件数（只支持件数口径，理由见 {@link FloorMode}）
   */
  public record AllocationFloorRow(
      UUID id,
      UUID policyId,
      String sourceSystem,
      UUID referenceId,
      String referenceLabel,
      FloorMode floorMode,
      BigDecimal floorRatio,
      BigDecimal floorQuantity,
      BigDecimal safetyQuantity,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      String note,
      UUID createdByMembershipId,
      OffsetDateTime createdAt) {

    /** 该档保底是否在给定时刻生效（左闭右开，与 7.8.1.1 同一套区间约定）。 */
    public boolean covers(OffsetDateTime at) {
      return !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo));
    }
  }

  /** 保底列表（真分页，total 不受分页影响）。 */
  public record AllocationFloorPage(
      List<AllocationFloorRow> floors, long total, int page, int size) {}

  /** 新建 / 换版一档保底的输入。店铺必须已是该策略的启用成员（V76 触发器在 DB 层挡住）。 */
  public record AllocationFloorInput(
      @NotNull UUID policyId,
      @NotBlank @Size(max = 32) String sourceSystem,
      @NotNull UUID referenceId,
      @Size(max = 150) String referenceLabel,
      @NotBlank @Size(max = 8) String floorMode,
      @Schema(nullable = true) @DecimalMin(value = "0") @DecimalMax(value = "1")
          BigDecimal floorRatio,
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal floorQuantity,
      @Schema(nullable = true) @DecimalMin(value = "0") BigDecimal safetyQuantity,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Size(max = 500) String note) {}

  /** 写入结果：新档 / 被换掉的那一档、以及为什么这么改。 */
  public record AllocationFloorSaved(
      UUID floorId,
      UUID policyId,
      UUID referenceId,
      FloorMode floorMode,
      BigDecimal floorQuantity,
      BigDecimal floorRatio,
      BigDecimal safetyQuantity,
      OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      @Schema(nullable = true) UUID supersededFloorId,
      String message) {}

  // ------------------------------------------------------------ 分摊

  /**
   * 发起一次**带保底**的分摊。
   *
   * <p>用 POST 而不是 GET：与 7.8.1.1 的 {@code /calculation} 同一理由（请求体放不进 URL）。
   *
   * @param availableQuantity 当前可用量。<b>本模块必填</b>：7.2.2.1 的池容量口径 （合格可售可用量 + 含在途时发往该仓的在途）是 7.8.1.1
   *     服务里的私有实现， 本模块复制一份只会得到一个迟早与它漂移的副本，所以要求调用方传入 7.2.2.1 已经算出的那个数。capabilities 会把这条讲清楚。
   */
  public record PriorityAllocationRequest(
      @NotNull UUID skuId,
      @NotNull @DecimalMin(value = "0") BigDecimal availableQuantity,
      @Schema(nullable = true) UUID policyId,
      @Schema(nullable = true) OffsetDateTime at) {}

  /**
   * 一个店铺在带保底的分摊里的结果。
   *
   * @param floorGranted 它从保底池拿到的件数
   * @param floorOrder 它在保底仲裁次序里的名次（1 = 最先保住）。没配保底的店也有名次： 那一列回答的是「它本该排第几却没货保」
   * @param floorRequested 它应得的件数（轮到它时保底池还剩多少就是多少）
   * @param floorShortfall 差额；{@code floorStatus=UNFULFILLED} 时必为 {@code floorRequested}
   * @param residualQty 保底之外从余量池另外拿到的量
   * @param baseAllocatedQty 同一批货在「完全没有保底」时它本该拿到的数 —— 被压低提示的唯一依据
   * @param suppressed 它是否被压低（最终分摊 &lt; 无保底基线）
   */
  public record PriorityAllocationLine(
      String sourceSystem,
      UUID referenceId,
      String referenceLabel,
      String shopOperatingMode,
      int floorOrder,
      FloorMode floorMode,
      BigDecimal floorQuantity,
      BigDecimal floorRatio,
      BigDecimal safetyQuantity,
      BigDecimal floorRequested,
      BigDecimal floorGranted,
      BigDecimal floorShortfall,
      FloorStatus floorStatus,
      @Schema(nullable = true) FloorShortfallReason shortfallReason,
      BigDecimal residualQty,
      BigDecimal baseAllocatedQty,
      BigDecimal allocatedQty,
      boolean suppressed,
      @Schema(nullable = true) SuppressionReason suppressionReason) {}

  /**
   * 一次带保底的分摊的完整结果 —— 本模块真正的产出。
   *
   * <p>守恒式与 7.8.1.1 完全同形： {@code availableQuantity = bufferQuantity + allocatableQuantity} 且 {@code
   * allocatableQuantity = allocatedTotal + unallocatedQuantity}。 另加本模块自己的一条：{@code floorPool +
   * residualPool = allocatableQuantity}。
   */
  public record PriorityAllocationResult(
      @Schema(nullable = true) UUID policyId,
      String policyLabel,
      String method,
      @Schema(nullable = true) UUID runId,
      UUID skuId,
      String skuCode,
      List<PriorityAllocationLine> lines,
      BigDecimal availableQuantity,
      BigDecimal bufferQuantity,
      BigDecimal allocatableQuantity,
      BigDecimal floorPool,
      BigDecimal residualPool,
      BigDecimal allocatedTotal,
      BigDecimal unallocatedQuantity,
      BigDecimal baseAllocatedTotal,
      long memberCount,
      long floorMemberCount,
      long suppressedMemberCount,
      long shortfallMemberCount,
      LocalDate salesWindowStart,
      LocalDate salesWindowEnd,
      String note) {}

  // ------------------------------------------------------------ 保底生效记录

  /**
   * 一条保底生效记录（事实，append-only）。
   *
   * <p>{@code runId} 指向 7.8.1.1 写的 {@code allocation_run} —— 本模块**不另建分摊头**， 因为那会把「一次分摊」拆成两件事，而两个
   * run id 拼起来的记录迟早对不上。 一次带保底的分摊仍然只写一个 {@code allocation_run} + 若干 {@code
   * allocation_run_line}（7.8.1.1 的表与 CHECK 原样复用）， 本模块额外写若干 {@code allocation_priority_resolution}。
   */
  public record ResolutionRow(
      UUID runId,
      UUID referenceId,
      String referenceLabel,
      int floorOrder,
      FloorMode floorMode,
      BigDecimal floorQuantity,
      BigDecimal floorRatio,
      BigDecimal safetyQuantity,
      BigDecimal requestedQty,
      BigDecimal grantedQty,
      BigDecimal shortfallQty,
      FloorStatus status,
      @Schema(nullable = true) FloorShortfallReason shortfallReason,
      BigDecimal residualQty,
      BigDecimal baseAllocatedQty,
      BigDecimal allocatedQty,
      boolean suppressed,
      @Schema(nullable = true) SuppressionReason suppressionReason,
      OffsetDateTime createdAt) {}

  /** 保底生效记录列表（真分页）。默认按分摊时间倒序。 */
  public record ResolutionPage(List<ResolutionRow> resolutions, long total, int page, int size) {}

  // ------------------------------------------------------------ 能力

  /** 一项保底能力的说明。{@code available=false} 时 {@code note} 说明为什么。 */
  public record FloorCapabilityOption(String code, String label, boolean available, String note) {}

  /**
   * 保底能力清单 —— 前端据此渲染选项与提示，而不是把规则硬编码在界面上。
   *
   * @param performanceBasisAvailable 店铺绩效是否可自动派生。17 绩效域未交付，恒为 false
   * @param availableQuantityRequired 可用量是否必须由调用方传入（恒为 true，理由见 {@link
   *     PriorityAllocationRequest#availableQuantity()}）
   * @param poolNote 讲清本模块的保底池与 7.8.1.1 的分摊缓冲、7.2.2.1 的平台回写缓冲是三个不同的东西
   */
  public record PriorityCapabilities(
      List<FloorCapabilityOption> floorModes,
      List<FloorCapabilityOption> suppressReasons,
      List<String> shopOperatingModes,
      boolean performanceBasisAvailable,
      String performanceBasisNote,
      boolean availableQuantityRequired,
      String availableQuantityNote,
      String poolNote,
      String downstreamNote) {}
}
