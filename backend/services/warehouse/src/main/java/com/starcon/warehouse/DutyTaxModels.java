package com.starcon.warehouse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.5.1.3 关税与税费。
 *
 * <p>蓝图 10-logistics-and-freight.md 10.5.1.3 的输入/输出契约：
 *
 * <ul>
 *   <li>职责：计算与管理进口税费：关税（按 HS 编码税率）、增值税/GST、关税承担方（DDP/DDU）、 到岸成本构成，供成本核算与报价参考。
 *   <li>输入：HS 编码税率表；目的国税则；申报价值；贸易条款。
 *   <li>输出：税费估算 + 实际缴纳记录；到岸成本明细。
 * </ul>
 *
 * <p>DTO 一律带 {@code Duty}/{@code Tariff} 前缀：四个服务的 schema 被 codegen
 * 拍平进同一命名空间，重名会被**静默覆盖且没有任何警告**（AGENTS.md 契约纪律）。
 *
 * <p>本模块必须自己讲清的四条口径（都随响应返回，不让界面上凭空猜）：
 *
 * <ol>
 *   <li><b>估算与实缴分别留痕，这是蓝图加粗的硬要求</b>：蓝图原文「**税费估算与实际缴纳差异要记录**， 长期差异大说明 HS 归类或税率配置有误」。所以 {@code
 *       DutyTaxEstimate}（估算）、{@code DutyTaxPayment} （实缴）、{@code DutyTaxVariance}（差异）三张表互相不覆盖，且
 *       <b>DB 层钉死「有实缴就必须有差异记录」</b>（V90 的 {@code duty_payment_has_variance}）。
 *       只存最终一个数的话，差异永远无法被回答，「HS 归类错了」这个结论就没有证据。
 *   <li><b>税率是按生效期检索的落库数据，不是代码常量</b>：税则会变，用常量记税率等于 「改了税率就改了历史」。每条税率带 {@code
 *       effectiveFrom/To}，估算明细把命中的税率值 <b>抄进自己的行里</b>（{@code dutyAdValoremRate} + {@code
 *       dutyRatePrefix}）， 所以税率表那条将来被改，本行仍能回答「当时按 3.25% 算的」。
 *   <li><b>0% 税率合法，「没匹配到税率」不合法</b>：英国零税率商品、免税供货都是真实的 0% 配置， 所以税率表允许 0；但估算单<b>锁定前</b>必须所有行都匹配到税率
 *       （V90 的 {@code duty_estimate_lock_completeness}），否则「关税 0 元」就会进报价与成本核算。
 *   <li><b>DDP/DDU 改变的是成本归属，不是税率</b>：DDU 下关税与目的国税费由买方承担， 卖方到岸成本**不得**含它们（V90 的 {@code
 *       duty_landed_cost} 钉死）。把 DDU 的税算进 卖方成本，就是在给一个不属于自己的成本记账。
 * </ol>
 */
public final class DutyTaxModels {
  private DutyTaxModels() {}

  /** 税种。关税与增值税/GST 是**两笔独立的钱**，到岸成本明细必须能分开答。 */
  public enum TaxKind {
    /** 关税。 */
    DUTY("关税"),
    /** 增值税。 */
    VAT("增值税"),
    /** 商品服务税（澳大利亚/英国等）。 */
    GST("商品服务税");

    private final String label;

    TaxKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static TaxKind parse(String raw) {
      for (var k : values()) {
        if (k.name().equalsIgnoreCase(raw)) {
          return k;
        }
      }
      throw new IllegalArgumentException("未知的税种：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 税率适用范围。
   *
   * <p>不能混在一张「HS 税率表」里硬塞：绝大多数国家的增值税/GST 是<b>按国征收</b>、不按 HS 编码细分， 把国别增值税硬绑到某个 HS 码上，结果是换一个 HS
   * 码就换了一个增值税率 —— 那是凭空造出来的税率。
   */
  public enum RateScope {
    /** 国别：整个目的国一律适用（增值税/GST 通常是这种）。 */
    COUNTRY("国别"),
    /** 按 HS 编码前缀：越长的前缀越优先（关税是这种）。 */
    HS("按 HS 编码");

    private final String label;

    RateScope(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 关税承担方（蓝图职责点名的 DDP/DDU 落到哪一列）。 */
  public enum DutyBearer {
    /** 卖方承担（DDP 必须是这个）。 */
    SELLER("卖方承担"),
    /** 买方承担（DDU 必须是这个）。 */
    BUYER("买方承担"),
    /** 承运人承担。 */
    CARRIER("承运人承担");

    private final String label;

    DutyBearer(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static DutyBearer parse(String raw) {
      for (var b : values()) {
        if (b.name().equalsIgnoreCase(raw)) {
          return b;
        }
      }
      throw new IllegalArgumentException("未知的关税承担方：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 贸易条款。与 10.4.1.1 first_mile_shipment.incoterm 同一组取值口径。 */
  public enum Incoterm {
    EXW("工厂交货"),
    FCA("货交承运人"),
    FOB("装运港船上交货"),
    CFR("成本加运费"),
    CIF("成本保险加运费"),
    DDP("完税后交货"),
    DDU("未完税交货");

    private final String label;

    Incoterm(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static Incoterm parse(String raw) {
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw)) {
          return t;
        }
      }
      throw new IllegalArgumentException("未知的贸易条款：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 估算单状态词表。与 {@code duty_tax_estimate.status} 的 CHECK 逐值对应。
   *
   * <p>合法转换只有这一张表（{@link #allowedNext} 是唯一真相，服务层与前端共用）：
   *
   * <pre>
   * DRAFT     → DRAFT（改）/ ESTIMATED（算出税了）/ VOID
   * ESTIMATED → DRAFT（改基础）/ ESTIMATED（按新税率重算）/ LOCKED（锁定）/ VOID
   * LOCKED    → VOID（只允许作废；基础与税率都不许再改）
   * VOID      → 终态
   * </pre>
   *
   * <p>{@code LOCKED} 之后连承担方都不许改（V90 {@code duty_estimate_lock}）：改了就等于把 DDP 的成本挪成 DDU
   * 的成本，而已发出的报价失去了依据。
   */
  public enum EstimateStatus {
    /** 草稿：可以自由改基础与承担方。 */
    DRAFT("草稿"),
    /** 已估算：已按税率表算出税，可以重算。 */
    ESTIMATED("已估算"),
    /** 已锁定：作为对外报价/成本依据发出，之后只读。 */
    LOCKED("已锁定"),
    /** 已作废：终态。 */
    VOID("已作废");

    private final String label;

    EstimateStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static EstimateStatus parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      throw new IllegalArgumentException("未知的估算单状态：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 唯一真相：合法转换表。任何不在表内的跳转都必须被拒绝。 */
    public static List<EstimateStatus> allowedNext(EstimateStatus current) {
      return switch (current) {
        case DRAFT -> List.of(DRAFT, ESTIMATED, VOID);
        case ESTIMATED -> List.of(DRAFT, ESTIMATED, LOCKED, VOID);
        case LOCKED -> List.of(VOID);
        case VOID -> List.of();
      };
    }
  }

  /**
   * 税率匹配状态。
   *
   * <p>{@code NO_MATCH} 是本模块最要紧的一个状态：它意味着「税率表里没有这条 HS 码对应的税率」， 算出来的税是 0。蓝图那句「长期差异大说明 HS
   * 归类或税率配置有误」在账上最先表现成 {@code NO_MATCH} 的行被锁定进报价 —— 所以 V90 禁止带 {@code NO_MATCH} 锁定。
   */
  public enum RateMatchStatus {
    /** 命中了税率（税率值已抄进本行）。 */
    MATCHED("已匹配"),
    /** 没命中：税率表里没有对应税率，按 0 算，**必须被看见**。 */
    NO_MATCH("未匹配"),
    /** 命中的税率已过生效期，不可用。 */
    EXPIRED("税率已失效");

    private final String label;

    RateMatchStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static RateMatchStatus parse(String raw) {
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw)) {
          return s;
        }
      }
      return NO_MATCH;
    }
  }

  /** 缴款性质。缴错走**新增** REFUND/ADJUSTMENT 行，不改旧行（V90 append-only）。 */
  public enum PaymentKind {
    /** 正常缴纳。 */
    PAYMENT("缴纳"),
    /** 退汇（多缴退回）。 */
    REFUND("退汇"),
    /** 海关核定后补退。 */
    ADJUSTMENT("调整");

    private final String label;

    PaymentKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    public static PaymentKind parse(String raw) {
      for (var k : values()) {
        if (k.name().equalsIgnoreCase(raw)) {
          return k;
        }
      }
      throw new IllegalArgumentException("未知的缴款性质：" + raw + "，可选：" + String.join("、", names()));
    }
  }

  /** 实际付款方（谁真的掏了这笔钱）。 */
  public enum DutyPayer {
    SELLER("卖方"),
    BUYER("买方"),
    CARRIER("承运人"),
    CUSTOMS("海关代缴");

    private final String label;

    DutyPayer(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    public static DutyPayer parse(String raw) {
      for (var p : values()) {
        if (p.name().equalsIgnoreCase(raw)) {
          return p;
        }
      }
      throw new IllegalArgumentException("未知的付款方：" + raw + "，可选：" + String.join("、", names()));
    }
  }

  /**
   * 差异归因（蓝图那句话的落地字段）。
   *
   * <p>蓝图原文：「长期差异大说明 <b>HS 归类</b>或<b>税率配置</b>有误」—— 所以前两个取值就是 蓝图点名的两个根因，其余是运维上同样常见的口径差。
   */
  public enum VarianceCause {
    PENDING("待归因"),
    HS_CLASSIFICATION("HS 归类错误"),
    RATE_CONFIG("税率配置错误"),
    CUSTOMS_VALUE("完税价格差异"),
    FX_RATE("汇率差异"),
    FTA_PREFERENCE("FTA 优惠未适用"),
    EXEMPTION("免税/零税率适用"),
    PENALTY_INTEREST("滞纳金/附加"),
    OTHER("其他");

    private final String label;

    VarianceCause(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static VarianceCause parse(String raw) {
      for (var c : values()) {
        if (c.name().equalsIgnoreCase(raw)) {
          return c;
        }
      }
      return PENDING;
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /** 差异处理结果。已复核的必须有复核人（V90 CHECK 钉死）。 */
  public enum VarianceResolution {
    OPEN("待处理"),
    INVESTIGATING("排查中"),
    FIXED_RATE_CONFIG("已修税率配置"),
    FIXED_HS_CLASSIFICATION("已修 HS 归类"),
    ACCEPTED("接受差异"),
    WRITTEN_OFF("核销");

    private final String label;

    VarianceResolution(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    public static VarianceResolution parse(String raw) {
      for (var r : values()) {
        if (r.name().equalsIgnoreCase(raw)) {
          return r;
        }
      }
      throw new IllegalArgumentException("未知的差异处理结果：" + raw + "，可选：" + String.join("、", names()));
    }
  }

  // ================================================================ 能力与只读出口

  /** 一个维度/依赖的声明（与 10.5.1.1 的 {@code CustomsDimension} 同形）。 */
  public record DutyTaxDimension(
      String value,
      String label,
      boolean available,
      String ownerModule,
      String href,
      String note) {}

  public record DutyTaxTransition(
      String from, String fromLabel, String to, String toLabel, String action, String note) {}

  public record DutyTaxKindOption(String value, String label) {}

  public record DutyTaxBearerOption(String value, String label) {}

  public record DutyTaxIncotermOption(String value, String label) {}

  public record DutyTaxStatusOption(String value, String label) {}

  public record DutyTaxCauseOption(String value, String label) {}

  public record DutyTaxResolutionOption(String value, String label) {}

  public record DutyTaxMatchStatusOption(String value, String label) {}

  /**
   * 关税与税费能力清单。
   *
   * <p>未交付依赖逐条声明：3.7.1.1（HS 编码字典）与 16.3.1.1（税号与辖区/税则）在本项目里 **都没有可调用的上游**（product_compliance
   * 服务不存在），因此税率由本租户维护并如实标注 {@code tax_source} —— 不假装存在一个国家海关的官方税率库。
   */
  public record DutyTaxCapabilities(
      List<DutyTaxDimension> dependencies,
      List<DutyTaxTransition> transitions,
      List<DutyTaxStatusOption> statusOptions,
      List<DutyTaxKindOption> taxKinds,
      List<DutyTaxBearerOption> dutyBearers,
      List<DutyTaxIncotermOption> incoterms,
      List<DutyTaxCauseOption> varianceCauses,
      List<DutyTaxResolutionOption> varianceResolutions,
      List<DutyTaxMatchStatusOption> matchStatuses,
      List<ScopeOption> rateScopes,
      String estimateVsPaymentNote,
      String rateEffectiveNote,
      String zeroRateNote,
      String dutyBearerNote,
      String landedCostNote) {}

  /** 税率适用范围的选项（COUNTRY / HS）。 */
  public record ScopeOption(String value, String label) {}

  // ================================================================ 税则税率表

  public record DutyRateRow(
      UUID id,
      String destinationCountryCode,
      String rateScope,
      String hsCodePrefix,
      String taxKind,
      BigDecimal adValoremRate,
      BigDecimal specificRatePerUnit,
      String specificUom,
      boolean preferential,
      String treatyCode,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      String taxSource,
      boolean taxRateVerified,
      String sourceSystem,
      String sourceReference,
      String notes,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record DutyRateSaved(UUID id, String status, String message) {}

  // ================================================================ 估算单

  public record DutyEstimateRow(
      UUID id,
      String estimateCode,
      UUID declarationId,
      String declarationCode,
      String incoterm,
      String dutyBearer,
      UUID legalEntityId,
      String destinationCountryCode,
      String estimateCurrency,
      String baseCurrency,
      BigDecimal exchangeRate,
      Integer exchangeRateScale,
      LocalDate exchangeRateDate,
      BigDecimal customsValue,
      BigDecimal goodsValue,
      BigDecimal freightAmount,
      BigDecimal insuranceAmount,
      BigDecimal otherChargeAmount,
      BigDecimal estimatedDutyAmount,
      BigDecimal estimatedVatAmount,
      BigDecimal estimatedTotalTax,
      BigDecimal landedCostTotal,
      BigDecimal landedCostSeller,
      String status,
      OffsetDateTime estimatedAt,
      OffsetDateTime lockedAt,
      BigDecimal paidDutyAmount,
      BigDecimal paidVatAmount,
      BigDecimal paidTotalAmount,
      BigDecimal varianceTotal,
      int unmatchedRateLines,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record DutyEstimateLineRow(
      UUID id,
      int lineNo,
      UUID declarationLineId,
      int sourceLineNo,
      UUID skuId,
      String skuCode,
      String hsCode,
      String originCountryCode,
      String uom,
      BigDecimal quantity,
      BigDecimal declaredValue,
      BigDecimal lineCustomsValue,
      BigDecimal allocatedFreight,
      BigDecimal allocatedInsurance,
      BigDecimal allocatedOtherCharge,
      UUID dutyRateId,
      BigDecimal dutyAdValoremRate,
      BigDecimal dutySpecificRate,
      String dutyRatePrefix,
      String rateMatchStatus,
      boolean vatRateMatched,
      boolean preferentialApplied,
      String treatyCode,
      BigDecimal estimatedDutyAmount,
      BigDecimal vatBaseAmount,
      BigDecimal vatRate,
      BigDecimal estimatedVatAmount,
      BigDecimal estimatedLineTotalTax,
      String notes) {}

  // ================================================================ 实缴与差异

  public record DutyPaymentRow(
      UUID id,
      UUID estimateId,
      UUID declarationId,
      int paymentSequence,
      String taxKind,
      String paymentKind,
      BigDecimal paidAmount,
      String currency,
      String payer,
      String paymentReference,
      OffsetDateTime paidAt,
      String payerName,
      String sourceSystem,
      String sourceReference,
      String notes,
      OffsetDateTime createdAt) {}

  public record DutyVarianceRow(
      UUID id,
      UUID estimateId,
      String taxKind,
      int paymentSequence,
      UUID declarationLineId,
      UUID estimateLineId,
      BigDecimal estimatedAmount,
      BigDecimal actualAmount,
      BigDecimal varianceAmount,
      BigDecimal varianceRate,
      boolean rateCalculable,
      String currency,
      String varianceCause,
      String causeNote,
      String resolution,
      OffsetDateTime reviewedAt,
      UUID reviewedBy,
      String reviewNote,
      String sourceReference,
      OffsetDateTime createdAt) {}

  /**
   * 差异归因汇总行 —— 蓝图那句「长期差异大说明 HS 归类或税率配置有误」的**查询出口**。
   *
   * <p>{@code dimension} 按维度取不同含义：{@code byCause} 里是归因值， {@code byHsCode} 里是 HS 编码。某组 HS
   * 码反复出差异，就说明该改归类还是该改税率。
   */
  public record DutyVarianceCauseSummary(
      String dimension,
      String dimensionLabel,
      int estimateCount,
      int varianceCount,
      BigDecimal estimatedAmount,
      BigDecimal actualAmount,
      BigDecimal varianceAmount,
      String topCause) {}

  /**
   * 未匹配税率的明细行按 HS 编码汇总 —— 「税率配置有误」的**领先指标**。
   *
   * <p>它比实缴差异更早出现：税率没配的行一旦被锁定就已经错在报价里了， 要等海关实缴下来才看得出差异，那时报价早就发出去了。
   */
  public record DutyUnmatchedRateSummary(
      String hsCode,
      int lineCount,
      int estimateCount,
      String destinationCountryCodes,
      int matchingRateCount) {}

  /** 差异分析（按归因、按 HS 编码、未匹配税率三个维度）。 */
  public record DutyVarianceAnalysis(
      String destinationCountryCode,
      LocalDate from,
      LocalDate to,
      int estimateCount,
      int paymentCount,
      int openVarianceCount,
      BigDecimal totalEstimated,
      BigDecimal totalActual,
      BigDecimal totalVariance,
      BigDecimal absVarianceRate,
      List<DutyVarianceCauseSummary> byCause,
      List<DutyVarianceCauseSummary> byHsCode,
      List<DutyUnmatchedRateSummary> unmatchedRateByHsCode,
      String note) {}

  public record DutyEstimateSummary(
      int total,
      int draft,
      int estimated,
      int locked,
      int voided,
      BigDecimal estimatedTaxTotal,
      BigDecimal paidTotal,
      BigDecimal varianceTotal,
      int unmatchedRateLines,
      int openVarianceCount) {}

  public record DutyEstimatePage(
      List<DutyEstimateRow> rows,
      DutyEstimateSummary summary,
      int page,
      int size,
      int totalPages) {}

  public record DutyEstimateDetail(
      DutyEstimateRow estimate,
      List<DutyEstimateLineRow> lines,
      List<DutyPaymentRow> payments,
      List<DutyVarianceRow> variances,
      List<DutyTaxTransition> transitions,
      String note) {}

  public record DutySaved(UUID id, String status, String message) {}

  public record DutyRateCreateRequest(
      String destinationCountryCode,
      String rateScope,
      String hsCodePrefix,
      String taxKind,
      BigDecimal adValoremRate,
      BigDecimal specificRatePerUnit,
      String specificUom,
      Boolean preferential,
      String treatyCode,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      String taxSource,
      Boolean taxRateVerified,
      String sourceSystem,
      String sourceReference,
      String notes) {}

  public record DutyRateUpdateRequest(
      BigDecimal adValoremRate,
      BigDecimal specificRatePerUnit,
      String specificUom,
      Boolean preferential,
      String treatyCode,
      LocalDate effectiveTo,
      Boolean taxRateVerified,
      String sourceReference,
      String notes,
      Long expectedVersion) {}

  public record DutyEstimateCreateRequest(
      UUID declarationId,
      String incoterm,
      String dutyBearer,
      UUID legalEntityId,
      String destinationCountryCode,
      String estimateCurrency,
      String baseCurrency,
      BigDecimal exchangeRate,
      Integer exchangeRateScale,
      LocalDate exchangeRateDate,
      BigDecimal customsValue,
      BigDecimal goodsValue,
      BigDecimal freightAmount,
      BigDecimal insuranceAmount,
      BigDecimal otherChargeAmount,
      LocalDate rateDate,
      String notes) {}

  public record DutyEstimateUpdateRequest(
      String incoterm,
      String dutyBearer,
      BigDecimal customsValue,
      BigDecimal goodsValue,
      BigDecimal freightAmount,
      BigDecimal insuranceAmount,
      BigDecimal otherChargeAmount,
      BigDecimal exchangeRate,
      Long expectedVersion,
      String notes) {}

  /**
   * 记录实缴，<b>并同时写下差异快照与归因</b>。
   *
   * <p>归因在建差异那一刻就写进去（{@code varianceCause} / {@code varianceResolution}）： 差异记录是 append-only
   * 的，**不提供事后改归因的端点** —— 已归因的差异是纠错闭环的证据。 需要重新判定就再录一笔缴款对账出新的快照，纠错过程本身就是证据链。
   *
   * <p>{@code declarationLineId} 用来把差异落到具体某一行：不指名时差异只记到税种级。
   */
  public record DutyPaymentCreateRequest(
      String taxKind,
      String paymentKind,
      BigDecimal paidAmount,
      String currency,
      String payer,
      String paymentReference,
      OffsetDateTime paidAt,
      String payerName,
      String sourceSystem,
      String sourceReference,
      UUID declarationLineId,
      String varianceCause,
      String causeNote,
      String varianceResolution,
      String reviewNote,
      String notes) {}

  public record DutyLockRequest(String notes) {}

  public record DutyVoidRequest(String reason) {}

  public record DutyEstimateQuery(
      List<String> status,
      String destinationCountryCode,
      String dutyBearer,
      String incoterm,
      UUID declarationId,
      Boolean unmatchedRateOnly,
      Boolean varianceOnly,
      String keyword,
      String sort,
      int page,
      int size) {}

  public record DutyRateQuery(
      String destinationCountryCode,
      String taxKind,
      String rateScope,
      String hsCodePrefix,
      Boolean preferentialOnly,
      LocalDate effectiveOn,
      String keyword,
      String sort,
      int page,
      int size) {}
}
