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
 * 12.4.2.1 费用归集的传输模型。
 *
 * <p>蓝图 12-finance.md（12.4.2.1 费用归集）原文：
 *
 * <blockquote>
 *
 * 职责：归集各类费用到指定维度：按月分摊的平台费/订阅费、按单分摊的物流费、按 SKU 分摊的头程费、按期间分摊的人员与办公费用，保留归集依据。
 * 输入：费用来源（平台、物流、订阅、人工录入）；分摊目标维度。 输出：归集明细（费用项 → 分摊对象 → 金额）。
 * 关系：上游 = 12.3.1.2、10 物流、14 广告；下游 = 12.4.2.2。
 * <b>归集与分摊要分开</b>：先按规则归集费用池，再按维度分摊，便于核对与调整。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code FinanceCostCollection*} 前缀，与既有服务的类型名零重叠 ——
 * 契约被 codegen 合并进一个扁平文件，同名后者覆盖前者且不报冲突。
 *
 * <p><b>金额一律 BigDecimal + NUMERIC</b>：本文件里没有一个 {@code double}。
 *
 * <p><b>🔴 本模块只做归集，不做分摊。</b>模型里出现的 {@code targetDimension} 是
 * <b>规则声明的「下游要按什么维度分摊」</b>，<b>不是</b>分摊结果；
 * {@code allocatedAmount} / {@code allocationStatus} 是
 * {@code 归集 ≠ 分摊} 这条边界在数据层上的落点（恒为 0 / UNALLOCATED，见 V351）。
 */
public final class CostCollectionModels {

  private CostCollectionModels() {}

  /** 金额小数位（与同服务 12.1.1.1 / 12.3.1.1 一致）。 */
  public static final int MONEY_SCALE = 4;

  /** 数量小数位（与 DB 的 numeric(19,6) 一致）。 */
  public static final int QUANTITY_SCALE = 6;

  // ================================================================ 枚举

  /**
   * <b>费用来源</b>（蓝图输入里的「平台、物流、订阅、人工录入」的落地）。
   *
   * <p>⚠️ 每个来源对应**一个**上游事实族，规则把来源绑到费用项与目标维度上，
   * 绑定关系由 DB 层 CHECK 钉死（V351）—— 「按 SKU 分摊的平台费」这种说不通的配对写不进库。
   */
  public enum FeeSource {
    /** 平台结算单里的费用类明细（12.3.1.2 交易明细，同服务直读）。 */
    PLATFORM_SETTLEMENT("平台结算单", "12.3.1.2"),
    /** 广告花费（14.3.1.1 活动报表，channel 服务只读端口）。 */
    AD_SPEND("广告花费", "14.3.1.1"),
    /** 订阅费（人工录入）。 */
    SUBSCRIPTION("订阅", "人工录入"),
    /** 运单运费登记（10.2.1.1 运单列表，trade 服务只读端口）。 */
    LOGISTICS_WAYBILL("运单运费", "10.2.1.1"),
    /** 头程发运单（10.4.1.1，warehouse 服务只读端口）。 */
    HEAD_MILE_SHIPMENT("头程发运单", "10.4.1.1"),
    /** 人员费用（人工录入）。 */
    PERSONNEL("人员费用", "人工录入"),
    /** 办公费用（人工录入）。 */
    OFFICE("办公费用", "人工录入");

    private final String label;
    private final String upstream;

    FeeSource(String label, String upstream) {
      this.label = label;
      this.upstream = upstream;
    }

    public String label() {
      return label;
    }

    /** 这个来源的事实来自哪里（能力声明里逐条展开）。 */
    public String upstream() {
      return upstream;
    }
  }

  /** <b>费用项</b>：费用收进哪个桶（下游分摊的对象类别）。 */
  public enum FeeCategory {
    PLATFORM_FEE("平台费"),
    AD_FEE("广告费"),
    SUBSCRIPTION_FEE("订阅费"),
    LOGISTICS_FEE("物流费"),
    HEAD_MILE_FEE("头程费"),
    PERSONNEL_COST("人员费用"),
    OFFICE_COST("办公费用");

    private final String label;

    FeeCategory(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * <b>分摊目标维度</b>（蓝图职责句点名的四类口径）。
   *
   * <p>⚠️ 这是**规则声明的目标**，不是分摊结果。本模块不产出「哪一笔分到哪个 SKU」的明细。
   */
  public enum TargetDimension {
    /** 按月分摊（平台费 / 订阅费）。 */
    MONTH("按月"),
    /** 按单分摊（物流费）。 */
    ORDER("按单"),
    /** 按 SKU 分摊（头程费）。 */
    SKU("按 SKU"),
    /** 按期间分摊（人员与办公费用）。 */
    PERIOD("按期间");

    private final String label;

    TargetDimension(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** <b>归属期间口径</b>：归属期间是自然月还是会计期间。 */
  public enum PeriodBasis {
    /** 归属期间 = 基准日期所在的日历月（UTC 日历日）。 */
    CALENDAR_MONTH("自然月"),
    /** 归属期间 = 操作员显式给出的起止日（会计期间）。 */
    ACCOUNTING_PERIOD("会计期间");

    private final String label;

    PeriodBasis(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 归集规则的启用状态。 */
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

  /** 费用池状态。 */
  public enum PoolStatus {
    OPEN("开放"),
    CLOSED("关闭");

    private final String label;

    PoolStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 归集依据的类型（随来源而变，词表固定 ⇒ 可按类型核对）。 */
  public enum BasisType {
    SETTLEMENT_LINE("结算明细行"),
    AD_REPORT_ROW("广告报表行"),
    WAYBILL_FREIGHT("运单运费登记"),
    FIRST_MILE_SHIPMENT("头程发运单"),
    MANUAL_DOCUMENT("人工单据");

    private final String label;

    BasisType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /** 一条能力声明：有没有、依据是什么、缺了会怎样。 */
  public record FinanceCostCollectionCapability(
      @Schema(description = "模块 ID") String moduleId,
      @Schema(description = "这一维是什么") String dimension,
      @Schema(description = "本模块真接通了没有") boolean available,
      @Schema(description = "依据（表 / 端点 / 上游模块）") String evidence,
      @Schema(description = "不归本模块时归谁") String ownerModule,
      @Schema(description = "说明") String note) {}

  /** 下游缺口：谁还没交付、归谁。 */
  public record FinanceCostCollectionDownstreamGap(
      @Schema(description = "模块 ID") String moduleId,
      @Schema(description = "模块名") String name,
      @Schema(description = "归属服务") String service,
      @Schema(description = "已交付没有") boolean available,
      @Schema(description = "归属卷") String ownerVolume,
      @Schema(description = "说明") String note) {}

  /** 能力声明整体。 */
  public record FinanceCostCollectionCapabilities(
      List<FinanceCostCollectionCapability> capabilities,
      List<FinanceCostCollectionDownstreamGap> gaps) {}

  // ================================================================ 归集规则

  /** 一条归集规则。 */
  public record FinanceCostCollectionRule(
      UUID id,
      String ruleCode,
      String ruleName,
      FeeSource feeSource,
      FeeCategory feeCategory,
      TargetDimension targetDimension,
      PeriodBasis periodBasis,
      String poolKey,
      String poolName,
      LocalDate effectiveFrom,
      @Schema(nullable = true) LocalDate effectiveTo,
      RuleStatus status,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 规则输入（新建 / 修改）。 */
  public record FinanceCostCollectionRuleInput(
      @NotBlank(message = "规则编码不能为空") String ruleCode,
      @NotBlank(message = "规则名称不能为空") String ruleName,
      @NotNull(message = "费用来源不能为空") FeeSource feeSource,
      @NotNull(message = "费用项不能为空") FeeCategory feeCategory,
      @NotNull(message = "分摊目标维度不能为空") TargetDimension targetDimension,
      @NotNull(message = "归属期间口径不能为空") PeriodBasis periodBasis,
      @NotBlank(message = "费用池标识不能为空") String poolKey,
      @Schema(nullable = true) String poolName,
      @NotNull(message = "生效起始日不能为空") LocalDate effectiveFrom,
      @Schema(nullable = true) LocalDate effectiveTo,
      @Schema(nullable = true) RuleStatus status,
      @Schema(nullable = true) String note) {}

  /**
   * 蓝图标准绑定模板（<b>只读</b>：系统**不预置任何规则行**）。
   *
   * <p>费用池怎么划分是租户自己的账务口径，系统替用户默认一个池名就是替用户记账。
   * 模板只是把蓝图那四类口径摊开给界面，让「按模板建规则」一键可用。
   */
  public record FinanceCostCollectionRuleTemplate(
      FeeSource feeSource,
      FeeCategory feeCategory,
      TargetDimension targetDimension,
      PeriodBasis periodBasis,
      String poolKey,
      String poolName,
      String note,
      @Schema(description = "本租户是否已经配了这条来源的规则") boolean configured) {}

  // ================================================================ 费用池

  /** 一个费用池。 */
  public record FinanceCostCollectionPool(
      UUID id,
      String poolKey,
      String poolName,
      TargetDimension dimension,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      PoolStatus status,
      @Schema(description = "池合计（由条目派生，恒等于 Σ条目金额）") BigDecimal totalAmount,
      @Schema(description = "条目笔数") int entryCount,
      @Schema(description = "🔴 本模块恒为 false：归集 ≠ 分摊") boolean allocationAvailable,
      @Schema(description = "分摊归谁") String allocationOwnerModule,
      @Schema(description = "分摊边界说明") String allocationNote,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 费用池按币种的汇总（**绝不跨币种相加**）。 */
  public record FinanceCostCollectionPoolCurrencyTotal(
      String currency, BigDecimal totalAmount, int poolCount, int entryCount) {}

  /** 费用池列表（真分页 + 真筛选 + 汇总覆盖整个筛选结果集）。 */
  public record FinanceCostCollectionPoolPage(
      List<FinanceCostCollectionPool> items,
      int page,
      int size,
      long total,
      List<FinanceCostCollectionPoolCurrencyTotal> totalsByCurrency) {}

  /** 费用池详情：池 + 它的归集条目。 */
  public record FinanceCostCollectionPoolDetail(
      FinanceCostCollectionPool pool, List<FinanceCostCollectionEntry> entries) {}

  // ================================================================ 归集条目

  /**
   * 归集依据的分项明细（随来源而变；**用固定字段而不是自由 JSON**，
   * 免得界面与契约各自猜字段名）。
   */
  public record FinanceCostCollectionBasisDetail(
      @Schema(nullable = true, description = "费用子类（平台结算单里是交易类型：佣金 / FBA 费用 / 仓储费 / 配送费）")
          String feeSubType,
      @Schema(nullable = true, description = "上游登记来源（如 MANUAL_REGISTERED / CHANNEL_FEE_STATEMENT）")
          String upstreamSource,
      @Schema(nullable = true, description = "结算单号（来源 = 平台结算单）") String settlementNo,
      @Schema(nullable = true, description = "结算单行号（来源 = 平台结算单）") Integer settlementLineNo,
      @Schema(nullable = true, description = "平台代码（来源 = 平台结算单）") String platformCode,
      @Schema(nullable = true, description = "活动 id（来源 = 广告花费）") String campaignId,
      @Schema(nullable = true, description = "活动名（来源 = 广告花费）") String campaignName,
      @Schema(nullable = true, description = "平台名（来源 = 广告花费）") String platformName,
      @Schema(nullable = true, description = "运单号（来源 = 运单运费）") String waybillNo,
      @Schema(nullable = true, description = "订单号（来源 = 运单运费）") String orderReference,
      @Schema(nullable = true, description = "承运商（来源 = 运单运费 / 头程发运单）") String carrierCode,
      @Schema(nullable = true, description = "发运单号（来源 = 头程发运单）") String shipmentCode,
      @Schema(nullable = true, description = "头程运费分项（来源 = 头程发运单）") BigDecimal freightAmount,
      @Schema(nullable = true, description = "头程保险费分项（来源 = 头程发运单）") BigDecimal insuranceAmount,
      @Schema(nullable = true, description = "头程处理费分项（来源 = 头程发运单）") BigDecimal handlingAmount,
      @Schema(nullable = true, description = "费用单据号（来源 = 人工录入）") String documentNo,
      @Schema(nullable = true, description = "费用归属说明（来源 = 人工录入）") String subject) {

    /**
     * 依据分项的五个工厂方法。
     *
     * <p>⚠️ 这个 record 有 17 个字段，其中 12 个对某一来源恒为空 ——
     * 位置参数构造在这种长度下极易写错（写错一个 {@code null} 就会把
     * 「运费分项」写成「保险费分项」而不报错）。⇒ 每种来源一个工厂方法，
     * 字段名在方法里一目了然。
     */
    public static FinanceCostCollectionBasisDetail settlementLine(
        String feeSubType, String settlementNo, Integer lineNo, String platformCode) {
      return new FinanceCostCollectionBasisDetail(
          feeSubType, null, settlementNo, lineNo, platformCode,
          null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static FinanceCostCollectionBasisDetail adReport(
        String campaignId, String campaignName, String platformName) {
      return new FinanceCostCollectionBasisDetail(
          null, null, null, null, null,
          campaignId, campaignName, platformName, null, null, null, null, null, null, null, null, null);
    }

    public static FinanceCostCollectionBasisDetail waybillFreight(
        String upstreamSource, String waybillNo, String orderReference, String carrierCode) {
      return new FinanceCostCollectionBasisDetail(
          null, upstreamSource, null, null, null,
          null, null, null, waybillNo, orderReference, carrierCode, null, null, null, null, null, null);
    }

    public static FinanceCostCollectionBasisDetail headMile(
        String carrierCode,
        String shipmentCode,
        BigDecimal freightAmount,
        BigDecimal insuranceAmount,
        BigDecimal handlingAmount) {
      return new FinanceCostCollectionBasisDetail(
          null, null, null, null, null,
          null, null, null, null, null, carrierCode, shipmentCode,
          freightAmount, insuranceAmount, handlingAmount, null, null);
    }

    public static FinanceCostCollectionBasisDetail manualDocument(
        String upstreamSource, String documentNo, String subject) {
      return new FinanceCostCollectionBasisDetail(
          null, upstreamSource, null, null, null,
          null, null, null, null, null, null, null, null, null, null, documentNo, subject);
    }
  }

  /** 一条归集依据。 */
  public record FinanceCostCollectionBasis(
      UUID id,
      UUID entryId,
      BasisType basisType,
      String basisCode,
      String basisCurrency,
      @Schema(description = "上游事实的原始金额（与条目金额不一致时说明口径有调整）")
          BigDecimal basisAmount,
      @Schema(nullable = true) LocalDate basisDate,
      @Schema(description = "凭据引用（发票号 / 合同号 / 对账单号）") String evidenceRef,
      @Schema(nullable = true) FinanceCostCollectionBasisDetail detail,
      OffsetDateTime createdAt) {}

  /** 一条归集条目（费用池里的一笔费用）。 */
  public record FinanceCostCollectionEntry(
      UUID id,
      UUID poolId,
      UUID ruleId,
      FeeSource feeSource,
      FeeCategory feeCategory,
      @Schema(description = "规则声明的分摊目标维度（不是分摊结果）") TargetDimension targetDimension,
      @Schema(description = "分摊基准日期") LocalDate baseDate,
      @Schema(description = "归属期间起（来自费用池）") LocalDate periodStart,
      @Schema(description = "归属期间止（来自费用池）") LocalDate periodEnd,
      String currency,
      BigDecimal amount,
      @Schema(nullable = true) BigDecimal quantity,
      String sourceRefType,
      String sourceRefKey,
      String sourceRefCode,
      @Schema(nullable = true) BigDecimal sourceRefAmount,
      @Schema(description = "🔴 恒为 0：本模块不做分摊") BigDecimal allocatedAmount,
      @Schema(description = "🔴 恒为 UNALLOCATED：本模块不做分摊") String allocationStatus,
      @Schema(description = "分摊归谁") String allocationOwnerModule,
      String note,
      OffsetDateTime collectedAt,
      OffsetDateTime createdAt,
      String poolKey,
      String poolName,
      String ruleCode,
      String ruleName,
      @Schema(nullable = true) FinanceCostCollectionBasis basis) {}

  /** 条目按币种的汇总（**绝不跨币种相加**）。 */
  public record FinanceCostCollectionEntryCurrencyTotal(
      String currency, BigDecimal amount, int count, BigDecimal allocatedAmount) {}

  /** 条目按维度的汇总（口径维度，不是分摊结果）。 */
  public record FinanceCostCollectionEntryDimensionTotal(
      TargetDimension dimension, String currency, BigDecimal amount, int count) {}

  /** 归集明细列表（真分页 + 真筛选 + 真排序 + 汇总覆盖整个筛选结果集）。 */
  public record FinanceCostCollectionEntryPage(
      List<FinanceCostCollectionEntry> items,
      int page,
      int size,
      long total,
      List<FinanceCostCollectionEntryCurrencyTotal> totalsByCurrency,
      List<FinanceCostCollectionEntryDimensionTotal> totalsByDimension) {}

  // ================================================================ 归集动作

  /** 一次归集的结果：收了多少、收进哪个池、哪些没收（及原因）。 */
  public record FinanceCostCollectionResult(
      @Schema(description = "实际归集的条目数") int collected,
      @Schema(description = "因重复已存在而跳过的条数") int skippedDuplicated,
      @Schema(description = "因上游没有可归集的事实而跳过的条数") int skippedNoFact,
      @Schema(description = "跳过的明细原因（逐条说明）") List<String> notes,
      @Schema(description = "本次归集用到的规则") FinanceCostCollectionRule rule,
      @Schema(description = "收进的费用池（跨期间 / 币种时会有多个）")
          List<FinanceCostCollectionPool> pools,
      @Schema(description = "归集出来的条目") List<FinanceCostCollectionEntry> entries) {}

  /** 人工录入归集的输入。 */
  public record FinanceCostCollectionManualInput(
      @NotNull(message = "归集规则不能为空") UUID ruleId,
      @NotBlank(message = "费用单据号不能为空") String sourceRefKey,
      @Schema(nullable = true) String sourceRefCode,
      @NotBlank(message = "费用归属说明不能为空") String subject,
      @NotNull(message = "金额不能为空") BigDecimal amount,
      @NotBlank(message = "币种不能为空") String currency,
      @NotNull(message = "基准日期不能为空") LocalDate baseDate,
      @Schema(nullable = true, description = "归属期间起始日（规则是「会计期间」时必填；「自然月」时由基准日期推出）")
          LocalDate periodStart,
      @Schema(nullable = true, description = "归属期间截止日（规则是「会计期间」时必填；「自然月」时由基准日期推出）")
          LocalDate periodEnd,
      @Schema(nullable = true) BigDecimal quantity,
      @Schema(nullable = true) String evidenceRef,
      @Schema(nullable = true) String note) {}

  /** 归集筛选项（全部来自真实数据）。 */
  public record FinanceCostCollectionOptions(
      List<String> feeSources,
      List<String> feeCategories,
      List<String> dimensions,
      List<String> periodBases,
      List<String> currencies,
      List<String> poolKeys,
      List<FinanceCostCollectionPoolOption> pools,
      List<FinanceCostCollectionRuleOption> rules) {}

  /** 筛选项里的费用池下拉项。 */
  public record FinanceCostCollectionPoolOption(
      UUID id, String poolKey, String poolName, TargetDimension dimension,
      LocalDate periodStart, LocalDate periodEnd, String currency, BigDecimal totalAmount, int entryCount) {}

  /** 筛选项里的规则下拉项。 */
  public record FinanceCostCollectionRuleOption(
      UUID id, String ruleCode, String ruleName, FeeSource feeSource, FeeCategory feeCategory,
      TargetDimension targetDimension, PeriodBasis periodBasis, String poolKey, RuleStatus status) {}

  /** 归集查询条件。 */
  public record FinanceCostCollectionQuery(
      @Schema(nullable = true) UUID poolId,
      @Schema(nullable = true) UUID ruleId,
      @Schema(nullable = true) FeeSource feeSource,
      @Schema(nullable = true) FeeCategory feeCategory,
      @Schema(nullable = true) TargetDimension targetDimension,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String poolKey,
      @Schema(nullable = true) LocalDate baseDateFrom,
      @Schema(nullable = true) LocalDate baseDateTo,
      @Schema(nullable = true) LocalDate periodStart,
      @Schema(nullable = true) LocalDate periodEnd,
      @Schema(nullable = true) String keyword,
      int page,
      int size) {}
}
