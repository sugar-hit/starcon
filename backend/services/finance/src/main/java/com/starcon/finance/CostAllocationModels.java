package com.starcon.finance;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 12.4.2.2 分摊规则的传输模型。
 *
 * <p>蓝图 12-finance.md（12.4.2.2 分摊规则）原文：
 *
 * <blockquote>
 *
 * 职责：配置费用分摊规则：分摊维度（SKU/SPU/店铺/订单/类目）、分摊基数（数量/金额/重量/体积/等额）、生效区间、分摊顺序与舍入差处理。
 * 输入：规则配置；待分摊费用池。 输出：分摊结果（保留舍入差额到指定对象）+ 分摊明细。
 * 关系：上游 = 12.4.2.1；下游 = 13 利润（SKU 利润）。
 * <b>分摊结果之和必须等于被分摊总额</b>（含舍入差处理），否则利润报表总额对不上 —— 这是财务最不能接受的错误。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceCostAllocation*} 前缀，与既有服务其它模块的类型名零重叠 ——
 * 契约被 codegen 合并进一个扁平文件，同名后者覆盖前者且不报冲突。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。
 *
 * <p><b>🔴 蓝图的等式落在数据层，不落在注释里。</b>{@code sourceTotal} 与
 * {@code allocatedTotal} 恒相等（DB 层 CHECK + 延迟约束触发器兜底），
 * {@code roundingDifference} 是被补回去的零头（**不消失**），
 * {@code roundingAdjustment} 是每一行各自拿到的零头。
 */
public final class CostAllocationModels {

  private CostAllocationModels() {}

  /** 金额小数位（与同服务 12.1.1.1 / 12.4.2.1 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 数量小数位（与 DB 的 numeric(19,6) 一致）。 */
  public static final int QUANTITY_SCALE = 6;

  /** 比例小数位（DB 的 numeric(19,10)）。 */
  public static final int RATIO_SCALE = 10;

  // ================================================================ 枚举

  /**
   * <b>分摊维度</b>（蓝图职责句点名的五类：SKU / SPU / 店铺 / 订单 / 类目）。
   *
   * <p>⚠️ 这是**分摊到谁身上**的维度，与 12.4.2.1 的「归集口径维度」
   * （MONTH / ORDER / SKU / PERIOD）不是同一个东西：后者是「费用怎么归集」，
   * 本枚举是「费用摊给谁」。
   */
  public enum AllocationDimension {
    /** 按 SKU 分摊（下游 13 利润的 SKU 利润直接吃这一档）。 */
    SKU("按 SKU"),
    /** 按 SPU 分摊。 */
    SPU("按 SPU"),
    /** 按店铺分摊。 */
    SHOP("按店铺"),
    /** 按订单分摊。 */
    ORDER("按订单"),
    /** 按类目分摊。 */
    CATEGORY("按类目");

    private final String label;

    AllocationDimension(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * <b>分摊基数</b>（蓝图职责句点名的五类：数量 / 金额 / 重量 / 体积 / 等额）。
   *
   * <p>⚠️ 基数**取不到**是常态（包装重量没维护、这个 SKU 还没卖过）——
   * 那种行明确记成「基数不可得」，不参与分母、分摊额为 0，
   * 且全部取不到时**拒绝分摊**（与 catalog 5.2.1.3 同一纪律）。
   */
  public enum AllocationBasis {
    /** 按数量摊。 */
    QUANTITY("按数量"),
    /** 按金额摊。 */
    AMOUNT("按金额"),
    /** 按重量摊（克）。 */
    WEIGHT("按重量"),
    /** 按体积摊（cm³）。 */
    VOLUME("按体积"),
    /** 等额摊（每个对象摊一样的）。 */
    EQUAL("等额");

    private final String label;

    AllocationBasis(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * <b>舍入差处理</b>（蓝图职责句点名的「舍入差处理」）。
   *
   * <p>向下取整后剩下的零头补给谁 —— 零头**绝不消失**，它落到一个指定对象上并留痕。
   */
  public enum RoundingPolicy {
    /** 零头给余数最大的对象（经典最大余数法）。 */
    LARGEST_REMAINDER("最大余数优先"),
    /** 零头给余数最小的对象。 */
    SMALLEST_REMAINDER("最小余数优先"),
    /** 零头给第一个对象（按对象编码升序）。 */
    FIRST_TARGET("第一个对象"),
    /** 零头给最后一个对象（按对象编码升序）。 */
    LAST_TARGET("最后一个对象"),
    /** 零头给规则里**指定**的那个对象（必须点名）。 */
    SPECIFIED("指定对象");

    private final String label;

    RoundingPolicy(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 分摊规则的启用状态。 */
  public enum RuleStatus {
    ACTIVE("启用"),
    INACTIVE("停用");

    private final String label;

    RuleStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 分摊批次的状态。 */
  public enum BatchStatus {
    /** 已分摊（结果有效）。 */
    ALLOCATED("已分摊"),
    /** 已撤销（结果留档，但不再是有效分摊）。 */
    REVERTED("已撤销");

    private final String label;

    BatchStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 一条能力声明：有没有、依据是什么、缺了会怎样。 */
  public record FinanceCostAllocationCapability(
      @Schema(description = "模块 ID") String moduleId,
      @Schema(description = "这一维是什么") String dimension,
      @Schema(description = "本模块真接通了没有") boolean available,
      @Schema(description = "依据（表 / 端点 / 上游模块）") String evidence,
      @Schema(description = "不归本模块时归谁") String ownerModule,
      @Schema(description = "说明") String note) {}

  /** 下游缺口：谁还没交付、归谁。 */
  public record FinanceCostAllocationDownstreamGap(
      @Schema(description = "模块 ID") String moduleId,
      @Schema(description = "模块名") String name,
      @Schema(description = "归属服务") String service,
      @Schema(description = "已交付没有") boolean available,
      @Schema(description = "归属卷") String ownerVolume,
      @Schema(description = "说明") String note) {}

  /** 能力声明整体。 */
  public record FinanceCostAllocationCapabilities(
      List<FinanceCostAllocationCapability> capabilities,
      List<FinanceCostAllocationDownstreamGap> gaps) {}

  // ================================================================ 分摊规则

  /** 一条分摊规则。 */
  public record FinanceCostAllocationRule(
      UUID id,
      String ruleCode,
      String ruleName,
      @Schema(description = "分摊维度（摊给谁）") AllocationDimension targetDimension,
      @Schema(description = "分摊基数（按什么摊）") AllocationBasis allocationBasis,
      @Schema(description = "适用费用池标识（* = 全部池）") String poolKey,
      @Schema(description = "分摊顺序：数字小的先用") int priority,
      @Schema(description = "舍入差处理") RoundingPolicy roundingPolicy,
      @Schema(nullable = true, description = "零头落点的类型（策略 = 指定对象时必填）")
          AllocationDimension roundingTargetType,
      @Schema(nullable = true, description = "零头落点的对象编码（策略 = 指定对象时必填）")
          String roundingTargetCode,
      LocalDate effectiveFrom,
      @Schema(nullable = true) LocalDate effectiveTo,
      RuleStatus status,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 规则输入（新建 / 修改）。 */
  public record FinanceCostAllocationRuleInput(
      @NotBlank(message = "规则编码不能为空") String ruleCode,
      @NotBlank(message = "规则名称不能为空") String ruleName,
      @NotNull(message = "分摊维度不能为空") AllocationDimension targetDimension,
      @NotNull(message = "分摊基数不能为空") AllocationBasis allocationBasis,
      @Schema(nullable = true, description = "适用费用池标识（留空 = 全部池）") String poolKey,
      @Schema(nullable = true, description = "分摊顺序（数字小的先用）") Integer priority,
      @Schema(nullable = true, description = "舍入差处理（默认 最大余数优先）")
          RoundingPolicy roundingPolicy,
      @Schema(nullable = true, description = "零头落点类型（策略 = 指定对象时必填）")
          AllocationDimension roundingTargetType,
      @Schema(nullable = true, description = "零头落点对象编码（策略 = 指定对象时必填）")
          String roundingTargetCode,
      @NotNull(message = "生效起始日不能为空") LocalDate effectiveFrom,
      @Schema(nullable = true) LocalDate effectiveTo,
      @Schema(nullable = true) RuleStatus status,
      @Schema(nullable = true) String note) {}

  // ================================================================ 待分摊费用池

  /**
   * 一个待分摊的费用池（12.4.2.1 的池 + 本模块的视角）。
   *
   * @param allocationAvailable 该池**已完成分摊**（上游 V351 钉死的三列之一，
   *     语义在本模块交付后由「本模块不做分摊 ⇒ 恒 false」改为「已分摊」）
   * @param targetCount 已采集的分摊对象数
   */
  public record FinanceCostAllocationPool(
      UUID poolId,
      String poolKey,
      String poolName,
      @Schema(description = "归集口径维度（12.4.2.1 的口径，不是分摊维度）") String poolDimension,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      @Schema(description = "被分摊总额（= 池合计，由归集条目派生）") BigDecimal totalAmount,
      int entryCount,
      @Schema(description = "是否已完成分摊") boolean allocationAvailable,
      @Schema(description = "已采集的分摊对象数") int targetCount,
      @Schema(description = "已生效的分摊批次 id（还没分摊时为空）") UUID allocatedBatchId,
      @Schema(description = "已生效的分摊批次状态") String allocatedBatchStatus) {}

  /** 待分摊费用池列表（真分页 + 真筛选）。 */
  public record FinanceCostAllocationPoolPage(
      List<FinanceCostAllocationPool> items, int page, int size, long total) {}

  // ================================================================ 分摊对象

  /** 一个分摊对象（真实上游事实的快照 + 它的四种基数）。 */
  public record FinanceCostAllocationTarget(
      UUID id,
      UUID poolId,
      AllocationDimension targetDimension,
      @Schema(description = "对象类型（与维度一致）") String targetType,
      String targetCode,
      String targetName,
      @Schema(nullable = true, description = "数量基数") BigDecimal basisQuantity,
      @Schema(nullable = true, description = "金额基数") BigDecimal basisAmount,
      @Schema(nullable = true, description = "重量基数（克）") BigDecimal basisWeight,
      @Schema(nullable = true, description = "体积基数（cm³）") BigDecimal basisVolume,
      @Schema(description = "这份基数来自哪个上游事实") String basisSource,
      @Schema(description = "基数取不到时的原因（空 = 取到了）") String basisNote,
      LocalDate targetDate,
      OffsetDateTime collectedAt) {}

  /** 分摊对象列表（真分页）。 */
  public record FinanceCostAllocationTargetPage(
      List<FinanceCostAllocationTarget> items, int page, int size, long total) {}

  /** 采集分摊对象的结果：收了多少、哪些没收（及原因）。 */
  public record FinanceCostAllocationTargetCollectResult(
      UUID poolId,
      @Schema(description = "分摊维度") AllocationDimension dimension,
      @Schema(description = "采集到的对象数") int collected,
      @Schema(description = "因上游取不到而跳过的条数") int skipped,
      @Schema(description = "跳过的明细原因（逐条说明）") List<String> notes,
      @Schema(description = "采集到的分摊对象") List<FinanceCostAllocationTarget> targets) {}

  // ================================================================ 分摊批次

  /** 一次分摊（批次）。 */
  public record FinanceCostAllocationBatch(
      UUID id,
      UUID poolId,
      String poolKey,
      String poolName,
      UUID ruleId,
      String ruleCode,
      String ruleName,
      String currency,
      @Schema(description = "被分摊总额") BigDecimal sourceTotal,
      @Schema(description = "分摊总额（🔴 恒等于被分摊总额）") BigDecimal allocatedTotal,
      @Schema(description = "舍入差（被补回去的零头，不消失）") BigDecimal roundingDifference,
      @Schema(nullable = true) AllocationDimension roundingTargetType,
      @Schema(nullable = true) String roundingTargetCode,
      @Schema(description = "参与分摊的对象数") int targetCount,
      @Schema(description = "基数不可得的对象数") int basisUnavailableCount,
      BatchStatus status,
      OffsetDateTime allocatedAt,
      @Schema(nullable = true) OffsetDateTime revertedAt,
      String note) {}

  /** 分摊批次按币种的汇总（**绝不跨币种相加**）。 */
  public record FinanceCostAllocationBatchCurrencyTotal(
      String currency, BigDecimal sourceTotal, BigDecimal allocatedTotal, int batchCount) {}

  /** 分摊批次列表（真分页 + 真筛选 + 汇总覆盖整个筛选结果集）。 */
  public record FinanceCostAllocationBatchPage(
      List<FinanceCostAllocationBatch> items,
      int page,
      int size,
      long total,
      List<FinanceCostAllocationBatchCurrencyTotal> totalsByCurrency) {}

  // ================================================================ 分摊结果（明细）

  /** 一条分摊结果（一个对象摊到了多少、怎么摊的）。 */
  public record FinanceCostAllocationResult(
      UUID id,
      UUID batchId,
      AllocationDimension targetDimension,
      String targetCode,
      String targetName,
      @Schema(description = "实际用的基数（等额口径下恒为 1）") BigDecimal basisValue,
      @Schema(description = "基数是否可得（false ⇒ 分摊额 0，且不参与分母）")
          boolean basisAvailable,
      @Schema(description = "占比") BigDecimal shareRatio,
      @Schema(description = "未舍入的理论分摊额") BigDecimal exactAmount,
      @Schema(description = "最终分摊额") BigDecimal allocatedAmount,
      @Schema(description = "这一行拿到的零头") BigDecimal roundingAdjustment,
      @Schema(description = "是不是规则指定的零头落点") boolean isRoundingTarget,
      @Schema(description = "基数不可得时的原因") String basisNote) {}

  /** 分摊结果列表（真分页 + 真筛选 + 汇总覆盖整个筛选结果集）。 */
  public record FinanceCostAllocationResultPage(
      List<FinanceCostAllocationResult> items,
      int page,
      int size,
      long total,
      @Schema(description = "筛选结果集的分摊总额（🔴 恒等于该批次被分摊总额）")
          BigDecimal totalAmount,
      @Schema(description = "筛选结果集里被补回去的零头合计") BigDecimal roundingDifference) {}

  /** 一个批次的详情：批次 + 它的分摊明细。 */
  public record FinanceCostAllocationBatchDetail(
      FinanceCostAllocationBatch batch, List<FinanceCostAllocationResult> results) {}

  // ================================================================ 动作

  /** 执行分摊的输入。 */
  public record FinanceCostAllocationRequest(
      @NotNull(message = "费用池不能为空") UUID poolId,
      @Schema(nullable = true, description = "指定规则（留空 = 按分摊顺序自动选）") UUID ruleId,
      @Schema(nullable = true) String note) {}

  // ================================================================ 筛选项

  /** 筛选项下拉（全部来自真实数据 / 固定词表）。 */
  public record FinanceCostAllocationOptions(
      List<AllocationDimension> dimensions,
      List<AllocationBasis> bases,
      List<RoundingPolicy> roundingPolicies,
      List<String> poolKeys,
      List<String> ruleCodes,
      List<String> currencies) {}
}
