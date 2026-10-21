package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.2.2.1 地址与支付校验的传输模型。
 *
 * <p>蓝图原话：「校验买家地址可投递性（国家/邮编/街道格式）、禁运国家清单、支付状态一致性（平台已付/货到付款/未付款）。
 * 输入：订单地址快照；支付方式与状态；禁运规则配置。输出：校验结果 + 风险等级。」
 * 蓝图加粗句：「<b>货到付款订单的履约风险最高</b>，需额外风控与保险策略。」
 *
 * <p><b>六条建模纪律</b>：
 *
 * <ul>
 *   <li><b>输出是两件，不是一件。</b>{@link AddressPaymentCheck#deliveryStatus}（校验结果）
 *       与 {@link AddressPaymentCheck#riskLevel}（风险等级）是两个字段、两条判定依据：
 *       可投递性只看地址类四项，风险等级看全部七项。合成一个字段的话，
 *       「货到付款但地址没问题」这类单就没有正确的表达位置。
 *   <li><b>货到付款独占最高档。</b>{@link RiskLevel#CRITICAL} <b>只能</b>由货到付款触发
 *       （V210 的表级 CHECK 双向钉死），{@link AddressPaymentCheckItem#severity} 的 CRITICAL
 *       也<b>只能</b>给 {@link AddressPaymentItemCode#COD_FULFILLMENT_RISK}。
 *       保险策略<b>不假装</b>有：{@link AddressPaymentItemCode#COD_INSURANCE_POLICY}
 *       恒为 {@link AddressPaymentItemStatus#UPSTREAM_UNAVAILABLE}。
 *   <li><b>七项恒定各一行。</b>{@link AddressPaymentCheckDetail#items} 恒 7 行：
 *       少一行的话「这单为什么没被拦住」就只能从计数反推，而计数不保留是谁缺席的。
 *   <li><b>「没量到」不是 0。</b>{@link AddressPaymentCheckItem#observedValue} 在该项不可用时是
 *       {@code null}，{@link AddressPaymentCheckItem#status} 说明为什么。
 *       文本型比对（国家码、邮编字面量、状态组合）走
 *       {@link AddressPaymentCheckItem#evidenceReference}，不进数值列。
 *   <li><b>禁运清单与国别规则是表，不是代码常量。</b>本类里<b>没有</b>任何国家数组、
 *       没有任何国家的邮编正则 —— 全部从 {@link AddressPaymentCountryRule} 读。
 *   <li><b>金额与数量全程 BigDecimal</b>，对齐 DB 的 numeric(19,4) 与 numeric(19,6)。
 * </ul>
 *
 * <p><b>record 简单类名全局唯一</b>：springdoc 用简单类名做 components.schemas 键，codegen 又把
 * 各服务的 schema 合并进同一个桶文件，重名会被<b>静默覆盖</b>、单测全绿而契约形状被改。
 * 本模块所有 record 一律带 {@code AddressPayment} 前缀（V153 用 {@code Intercept} 前缀、
 * V150 用 {@code Risk} 前缀避开 warehouse 的 {@code Dimension}，同一手法）。
 */
public final class AddressPaymentCheckModels {

  private AddressPaymentCheckModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 七个检查项（DB 词表，一字不改）。
   *
   * <p>蓝图职责句里的「国家/邮编/街道格式」拆成三项独立评估：
   * 合成一项会让「邮编这一项没规则可查」把「街道确实有错」也一起标成缺口。
   */
  public enum AddressPaymentItemCode {
    COUNTRY_EMBARGO("收货国禁运"),
    POSTCODE_FORMAT("邮编格式"),
    STREET_FORMAT("街道格式"),
    REQUIRED_CONTACT("必填联系方式"),
    PAYMENT_CONSISTENCY("支付状态一致性"),
    COD_FULFILLMENT_RISK("货到付款履约风险"),
    COD_INSURANCE_POLICY("货到付款保险策略");

    private final String label;

    AddressPaymentItemCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 单项状态。
   *
   * <p>把「不可用」拆成三种而不是一个 null，是为了让「这一项为什么没结论」在详情页上是三句不同的话：
   * 缺参照、上游没接通、这一单形态上不适用 —— 处置动作两两不同。
   */
  public enum AddressPaymentItemStatus {
    FLAGGED("命中"),
    CLEARED("已核对，未命中"),
    NO_REFERENCE("缺参照（无该国格式规则 / 未提供支付状态观察值）"),
    UPSTREAM_UNAVAILABLE("上游未交付，本项判不了"),
    NOT_APPLICABLE("该单形态上不适用");

    private final String label;

    AddressPaymentItemStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 严重度：决定风险等级落在哪一档。
   *
   * <p>{@link #CRITICAL} <b>只有</b>货到付款能用：加粗句「货到付款订单的履约风险最高」
   * 说的是这一类的风险最高，而「最高」这个词一旦有了第二个来源就不再成立。
   */
  public enum AddressPaymentItemSeverity {
    INFO("仅提示"),
    WARN("命中，风险等级至少 MEDIUM"),
    BLOCK("命中，不可投递或风险等级至少 HIGH"),
    CRITICAL("命中，风险等级 CRITICAL（仅货到付款）");

    private final String label;

    AddressPaymentItemSeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 取值来源：这一项的结论从哪来（DB 词表）。 */
  public enum AddressPaymentEvidenceSource {
    ADDRESS_SNAPSHOT("收货地址快照 order_address_snapshot（V99，本服务同库）"),
    TENANT_EMBARGO_LIST("租户可维护的禁运国家清单"),
    TENANT_COUNTRY_RULE("租户可维护的国别地址格式规则"),
    TENANT_PAYMENT_RULE("租户可维护的支付状态一致性规则"),
    ORDER_PAYMENT_METHOD("订单付款方式 sales_order.payment_method（V99）"),
    CALLER_PAYMENT_STATUS("调用方随请求提供的支付状态观察值"),
    MODULE_10_INSURANCE_NOT_DELIVERED("货到付款保险策略（无 L4 归属，未交付）");

    private final String label;

    AddressPaymentEvidenceSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * **风险等级**（DB 词表）。
   *
   * <p>{@link #CRITICAL} <b>只能</b>由货到付款触发，V210 用
   * {@code CHECK ((risk_level = 'CRITICAL') = cod_flagged)} 双向钉死：
   * 「是货到付款却不是最高风险」与「不是货到付款却给了最高风险」在写库这一层就被拒。
   */
  public enum AddressPaymentRiskLevel {
    LOW("低风险"),
    MEDIUM("中风险"),
    HIGH("高风险"),
    CRITICAL("最高风险（货到付款专属）");

    private final String label;

    AddressPaymentRiskLevel(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** **校验结果**（DB 词表）：可投递性。 */
  public enum AddressPaymentDeliveryStatus {
    DELIVERABLE("可投递"),
    NOT_DELIVERABLE("不可投递"),
    UNDETERMINED("未定（缺参照，按策略不出结论）");

    private final String label;

    AddressPaymentDeliveryStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 禁运强度。刻意不给「放行」档：清单上写「放行」的行会让「有行 = 有问题」失效。 */
  public enum AddressPaymentEmbargoLevel {
    BLOCK("不可发"),
    REVIEW("可疑（需人工或渠道确认）");

    private final String label;

    AddressPaymentEmbargoLevel(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 禁运清单来源。
   *
   * <p>刻意是「卷 + 关注点」而不是伪造的 L4 模块号：16 合规的国别禁运清单当前
   * 没有任何已交付的 L4 模块是它的交付方，编一个号会让后来的人去查一个不存在的模块。
   */
  public enum AddressPaymentRuleSource {
    TENANT_MAINTAINED("租户自维护"),
    MODULE_16_COMPLIANCE("16 合规国别禁运清单（未交付）");

    private final String label;

    AddressPaymentRuleSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 支付状态观察值词表（调用方提供的观察值，不是本服务的支付事实）。 */
  public enum AddressPaymentStatus {
    PAID("已付"),
    UNPAID("未付"),
    PARTIAL("部分付款"),
    COD("货到付款");

    private final String label;

    AddressPaymentStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 支付状态观察值的来源。 */
  public enum AddressPaymentStatusSource {
    PLATFORM_CALLBACK("平台回调"),
    TENANT_REPORTED("租户人工登记"),
    FINANCE_LEDGER("财务台账");

    private final String label;

    AddressPaymentStatusSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 支付状态一致性规则的严重度词表（DB 词表）。
   *
   * <p>{@link #OK} 表示该组合**自洽**（明细落 CLEARED），{@link #WARN} / {@link #BLOCK}
   * 表示**矛盾**（明细落 FLAGGED）。刻意<b>不</b>复用
   * {@link AddressPaymentItemSeverity}：那一档的 CRITICAL 是货到付款专属，
   * 而支付规则表里出现 CRITICAL 会让「最高风险」有第二个来源。
   */
  public enum AddressPaymentRuleSeverity {
    OK("自洽"),
    WARN("矛盾，需人工核对"),
    BLOCK("矛盾，不可放行");

    private final String label;

    AddressPaymentRuleSeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 缺参照怎么办（租户可维护口径，与 V153 / V150 同一纪律）。 */
  public enum AddressPaymentMissingReferencePolicy {
    /** 按已评估项判定，缺参照项计入 missingCount 并点名缺什么。 */
    ASSESS_KNOWN_ONLY("按已知项判定"),
    /** 有任何一项缺参照就不出「可投递」结论，只给「未定」。 */
    BLOCK_DECISION("缺参照即不出结论");

    private final String label;

    AddressPaymentMissingReferencePolicy(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：蓝图输入里的「支付方式与状态」中，**支付状态在本服务没有这个事实**
   * （12 财务未交付），而「保险策略」在蓝图中**没有任何 L4 模块持有**。
   * <b>哪几项真接通、哪几项靠租户登记、哪几项现在根本判不了，必须由接口自己说</b>，
   * 而不是让调用方从空数组或 404 里猜哪一环没通。
   */
  @Schema(description = "9.2.2.1 地址与支付校验能力与上下游可用性")
  public record AddressPaymentCapabilities(
      @Schema(description = "七个检查项逐项的可用性") List<AddressPaymentCapabilityItem> items,
      @Schema(description = "蓝图加粗句原文与本模块的落点") String boldRule,
      @Schema(description = "风险等级判定顺序原文") String riskOrder,
      @Schema(description = "可投递性判定顺序原文") String deliveryOrder,
      @Schema(description = "本租户是否已登记校验策略") boolean policyConfigured,
      @Schema(description = "禁运清单是否非空（空清单 = 没禁运任何国家，不是「都通过」）")
          boolean embargoListConfigured,
      @Schema(description = "上游与下游缺口原文") String gaps) {}

  /** 单个检查项的可用性。 */
  @Schema(description = "单个检查项的可用性")
  public record AddressPaymentCapabilityItem(
      @Schema(description = "检查项码") String code,
      @Schema(description = "中文名") String label,
      @Schema(description = "判定输入来源原文") String source,
      @Schema(description = "是否真接通（false = 租户维护或上游未交付）") boolean available,
      @Schema(description = "取哪一列阈值（真实 SQL / 租户维护 / 无阈值）") String thresholdSource,
      @Schema(description = "未交付时的归属模块") String ownerModule,
      @Schema(description = "缺口对业务的影响；已接通为 null") String impact) {}

  // ------------------------------------------------------------ 策略

  /** 校验策略读取形态。 */
  @Schema(description = "校验策略（每租户一条）")
  public record AddressPaymentPolicy(
      UUID id,
      String policyName,
      boolean enabled,
      AddressPaymentMissingReferencePolicy missingReferencePolicy,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 登记 / 更新校验策略。 */
  @Schema(description = "登记校验策略（upsert；不提供默认策略，必须显式登记）")
  public record AddressPaymentPolicyInput(
      @NotBlank @Size(max = 120) String policyName,
      @NotNull AddressPaymentMissingReferencePolicy missingReferencePolicy) {}

  // ------------------------------------------------------------ 禁运清单

  /** 禁运国家清单行。 */
  @Schema(description = "禁运国家清单（收货国）")
  public record AddressPaymentEmbargoRule(
      UUID id,
      String countryCode,
      AddressPaymentEmbargoLevel embargoLevel,
      AddressPaymentRuleSource source,
      String sourceReference,
      String reason,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      boolean enabled,
      OffsetDateTime createdAt) {}

  /** 登记一条禁运国家。 */
  @Schema(description = "登记一条禁运国家（ISO alpha-2 大写）")
  public record AddressPaymentEmbargoRuleInput(
      @NotBlank
          @Pattern(regexp = "^[A-Z]{2}$", message = "countryCode 必须是 ISO 3166-1 alpha-2 大写两字母")
          String countryCode,
      @NotNull AddressPaymentEmbargoLevel embargoLevel,
      @NotNull AddressPaymentRuleSource source,
      @Size(max = 200) String sourceReference,
      @Size(max = 400) String reason,
      @Schema(nullable = true) OffsetDateTime effectiveFrom,
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      Boolean enabled) {}

  /** 禁运清单分页（真分页 + 真搜索）。 */
  @Schema(description = "禁运国家清单分页")
  public record AddressPaymentEmbargoRulePage(
      List<AddressPaymentEmbargoRule> items, long total, int page, int size, int totalPages) {}

  // ------------------------------------------------------------ 国别规则

  /** 国别地址格式规则行。 */
  @Schema(description = "国别地址格式规则（一个国家一套）")
  public record AddressPaymentCountryRule(
      UUID id,
      String countryCode,
      boolean postcodeRequired,
      String postcodePattern,
      String postcodeNote,
      boolean streetRequired,
      int streetMinLength,
      boolean cityRequired,
      boolean regionRequired,
      boolean phoneRequired,
      boolean enabled,
      OffsetDateTime createdAt) {}

  /** 登记 / 更新国别地址格式规则。 */
  @Schema(description = "登记国别地址格式规则（邮编正则在服务端校验可编译）")
  public record AddressPaymentCountryRuleInput(
      @NotBlank
          @Pattern(regexp = "^[A-Z]{2}$", message = "countryCode 必须是 ISO 3166-1 alpha-2 大写两字母")
          String countryCode,
      boolean postcodeRequired,
      @Size(max = 200) String postcodePattern,
      @Size(max = 200) String postcodeNote,
      boolean streetRequired,
      @Min(0) @Max(300) int streetMinLength,
      boolean cityRequired,
      boolean regionRequired,
      boolean phoneRequired,
      Boolean enabled) {}

  /** 国别规则分页。 */
  @Schema(description = "国别地址格式规则分页")
  public record AddressPaymentCountryRulePage(
      List<AddressPaymentCountryRule> items, long total, int page, int size, int totalPages) {}

  // ------------------------------------------------------------ 支付规则

  /** 支付状态一致性规则行。 */
  @Schema(description = "支付状态一致性规则（付款方式 × 观察到的支付状态）")
  public record AddressPaymentPaymentRule(
      UUID id,
      String paymentMethod,
      String observedStatus,
      @Schema(description = "该组合是否自洽（true ⇒ 明细落 CLEARED，false ⇒ FLAGGED）")
          boolean expectedConsistent,
      String expectedSeverity,
      String reason,
      boolean enabled,
      OffsetDateTime createdAt) {}

  /** 登记 / 更新支付状态一致性规则。 */
  @Schema(description = "登记支付状态一致性规则（expectedSeverity=OK 表示自洽）")
  public record AddressPaymentPaymentRuleInput(
      @NotBlank
          @Pattern(
              regexp = "^(PREPAID|COD|BANK_TRANSFER|CREDIT_TERM|FREIGHT_FORWARDER|OTHER)$",
              message = "paymentMethod 必须是 sales_order.payment_method 词表内的值")
          String paymentMethod,
      @NotBlank
          @Pattern(
              regexp = "^(PAID|UNPAID|PARTIAL|COD)$",
              message = "observedStatus 必须是支付状态观察值词表内的值")
          String observedStatus,
      @NotNull AddressPaymentRuleSeverity expectedSeverity,
      @Size(max = 400) String reason,
      Boolean enabled) {}

  /** 支付规则分页。 */
  @Schema(description = "支付状态一致性规则分页")
  public record AddressPaymentPaymentRulePage(
      List<AddressPaymentPaymentRule> items, long total, int page, int size, int totalPages) {}

  // ------------------------------------------------------------ 校验

  /** 校验结果（头）。 */
  @Schema(description = "地址与支付校验结果（一张订单一次校验 = 一行）")
  public record AddressPaymentCheck(
      UUID id,
      UUID orderId,
      String orderNo,
      String orderSource,
      String orderCurrency,
      BigDecimal orderAmount,
      OffsetDateTime orderSubmittedAt,
      String paymentMethod,
      @Schema(nullable = true) String paymentStatus,
      @Schema(nullable = true) String paymentStatusSource,
      @Schema(nullable = true) OffsetDateTime paymentStatusObservedAt,
      @Schema(nullable = true) String shippingCountryCode,
      String shippingPostcode,
      String shippingStreet,
      String policyName,
      long policyVersion,
      OffsetDateTime assessedReferenceAt,
      @Schema(description = "输出之一：校验结果（可投递性）") String deliveryStatus,
      @Schema(description = "输出之二：风险等级") String riskLevel,
      @Schema(description = "是否货到付款（CRITICAL 的唯一来源）") boolean codFlagged,
      int itemCount,
      int flaggedCount,
      int clearedCount,
      int noReferenceCount,
      int upstreamUnavailableCount,
      int notApplicableCount,
      int blockedCount,
      int warnCount,
      int criticalCount,
      OffsetDateTime createdAt) {}

  /** 校验明细行。 */
  @Schema(description = "校验明细（七项恒定各占一行）")
  public record AddressPaymentCheckItem(
      String itemCode,
      String itemLabel,
      String status,
      String severity,
      String evidenceSource,
      @Schema(nullable = true) BigDecimal observedValue,
      @Schema(nullable = true) BigDecimal thresholdValue,
      int matchCount,
      String evidenceReference,
      String explanation) {}

  /** 校验详情：头 + 七行明细。 */
  @Schema(description = "地址与支付校验详情")
  public record AddressPaymentCheckDetail(AddressPaymentCheck check, List<AddressPaymentCheckItem> items) {}

  /** 落一次校验的返回。 */
  @Schema(description = "落一次地址与支付校验")
  public record AddressPaymentCheckSaved(AddressPaymentCheckDetail detail, String message) {}

  /** 触发一次校验的入参。 */
  @Schema(description = "对一张订单跑一次地址与支付校验")
  public record AddressPaymentCheckInput(
      @NotNull UUID orderId,
      @Schema(
              description = "支付状态观察值；本服务没有支付状态事实（12 财务未交付），由调用方提供；null ⇒ 该项缺参照",
              nullable = true)
          AddressPaymentStatus paymentStatus,
      @Schema(nullable = true) AddressPaymentStatusSource paymentStatusSource,
      @Schema(nullable = true) OffsetDateTime paymentStatusObservedAt) {}

  /** 校验结果分页（真分页 + 真筛选）。 */
  @Schema(description = "校验结果分页")
  public record AddressPaymentCheckPage(
      List<AddressPaymentCheck> items, long total, int page, int size, int totalPages) {}

  /** 全量概览（不受筛选与分页影响）。 */
  @Schema(description = "地址与支付校验概览")
  public record AddressPaymentOverview(
      long totalChecks,
      long codChecks,
      long notDeliverableChecks,
      long undeterminedChecks,
      long criticalChecks,
      long highChecks,
      long embargoRuleCount,
      long countryRuleCount,
      long paymentRuleCount,
      OffsetDateTime lastAssessedAt) {}
}
