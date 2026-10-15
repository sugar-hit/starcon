package com.starcon.trade;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.7.1.2 经销与分销管理的传输模型。
 *
 * <p>蓝图原话：「管理经销商/分销商：授权区域与产品线、授权期限、经销价与返点、销量配额与回款考核、窜货监控（跨区域销售预警）」， 输出「经销授权台账；配额完成率；返点计提；窜货预警」。
 *
 * <p><b>本文件的每一个字段都对应 V133 迁移里的一个真实列</b>，枚举值域与 DB 的 CHECK 逐条对齐 —— 只改 Java 不改迁移，DB 会拒绝写入，而报错对调用方毫无意义。
 *
 * <p><b>四条建模纪律</b>：
 *
 * <ul>
 *   <li><b>三个输出是算出来的，不是填进来的</b>：{@link DealerSaleInput} 没有 amount
 *       （由 quantity × dealerPrice 算出）、{@link DealerQuotaAttainmentRow} 的完成率由 NUMERIC 除法算出、{@link
 *       DealerRebateRow} 的返点金额由 base × rate 算出。三处都不接受调用方直接传总额，否则「界面上算的和单据上记的不是一回事」。
 *   <li><b>未确认的口径要显式暴露，不能藏</b>：{@link DealerRebateRow#financeConfirmed} 与 {@link
 *       DealerComplianceState#UNVERIFIED} 把「本模块口径未经财务确认」「合规无上游」写成数据， 而不是靠界面文案提醒。
 *   <li><b>期限是两个日期的区间</b>：{@link DealerAuthorizationInput#validFrom} / {@code validTo} 都是 date，过期判定是区间比较，
 *       <b>不</b>从创建时刻 + N 天反推（补录历史授权时那会把期限整体平移）。
 *   <li><b>经销商是 CUSTOMER 角色的往来单位</b>：{@link DealerProfileInput#partyId} 指向 3.6.1.2 的主体，档案本身<b>不含</b>「是不是经销商」——
 *       那是 {@link DealerAuthorizationInput} 的事实。
 * </ul>
 *
 * <p>命名统一用 {@code Dealer*} 前缀：全仓契约是 {@code tools/codegen/catalog-contracts.py} 把 <b>所有服务</b>的 spec
 * 合并进同一个扁平文件，同名 key 后者覆盖前者且不报冲突， 故刻意避开已占用的 {@code SalesOrder*} / {@code B2BOrder*} / {@code ProcessingOrder*}。
 */
public final class DealerChannelModels {

  private DealerChannelModels() {}

  // ------------------------------------------------------------ 枚举

  /** 授权状态（DB {@code dealer_authorization.status} 的 CHECK 值域）。 */
  public enum DealerAuthorizationStatus {
    /** 生效中：销售与配额考核正常进行。 */
    ACTIVE,
    /** 已暂停：期间还在，但停止考核与销售登记（临时冻结）。 */
    SUSPENDED,
    /** 已到期：授权期限走完（由到期判定或显式动作产生，终态）。 */
    EXPIRED
  }

  /**
   * 合规状态（DB {@code dealer_authorization.compliance_state}）。
   *
   * <p>蓝图把「16 合规（授权）」列为上游，而 16 合规<b>未交付</b>，因此本服务<b>没有任何可读的合规结论来源</b>，服务层只写得出 {@link
   * #UNVERIFIED}。另两个值是留给 16 合规交付后的词表位。
   *
   * <p>刻意不是 {@code NOT NULL DEFAULT 'VERIFIED'}：把「没有校验过」画成「校验通过」是合规类字段最危险的一种默认值。
   */
  public enum DealerComplianceState {
    /** 未经合规校验 —— 当前唯一可达状态（16 合规未交付，无可读上游）。 */
    UNVERIFIED,
    /** 已通过合规校验（16 合规交付后写入）。 */
    VERIFIED,
    /** 合规否决（16 合规交付后写入）。 */
    REJECTED
  }

  /** 窜货越界维度（DB {@code dealer_diversion_alert.breach_kind}）。 */
  public enum DealerBreachKind {
    /** 卖了未授权的区域。 */
    REGION,
    /** 卖了未授权的产品线。 */
    PRODUCT_LINE,
    /** 既越区域又越产品线 —— 合并成一条，不拆成两条（否则「有几笔窜货」被算大）。 */
    BOTH
  }

  /** 窜货预警处置状态（DB {@code dealer_diversion_alert.alert_state}）。 */
  public enum DealerAlertState {
    /** 待处置（新建即此态）。 */
    OPEN,
    /** 已确认是真的窜货，正在处理。 */
    ACKNOWLEDGED,
    /** 判为误报 —— <b>必须</b>写明理由（DB 的 CHECK 强制）。 */
    DISMISSED
  }

  /** 预警级别（DB {@code dealer_diversion_alert.severity}）。 */
  public enum DealerSeverity {
    /** 低：金额小或首次。 */
    LOW,
    /** 中：默认级别。 */
    MEDIUM,
    /** 高：金额大，或同一授权重复触发。 */
    HIGH
  }

  /** 返点计提状态（DB {@code dealer_rebate.accrual_state}）。 */
  public enum DealerAccrualState {
    /** 已计提（本模块口径，尚未与财务确认）。 */
    ACCRUED,
    /** 已结清 —— 写回属于 12 财务的写入所有权，本模块只读呈现。 */
    SETTLED
  }

  /** 客户分级（与 catalog V30 {@code party_customer_profile.customer_level} 同词表）。 */
  public enum DealerCustomerLevel {
    STRATEGIC,
    KEY,
    STANDARD,
    PROSPECT,
    RISK
  }

  // ------------------------------------------------------------ 输入

  /**
   * 经销商档案投影的写入形状（3.6.1.2 → trade 侧只读投影）。
   *
   * <p>这是<b>事件同步的写入面</b>：catalog 侧客户分级 / 账期变更后同步过来，幂等键是 partyId，版本号单调（乱序到达的旧事件会被 DB 拒绝覆盖新值）。
   */
  public record DealerProfileInput(
      @NotNull UUID partyId,
      @NotBlank @Size(max = 32) String partyCode,
      @NotBlank @Size(max = 160) String partyName,
      @NotNull DealerCustomerLevel customerLevel,
      @Pattern(regexp = "^[A-Z]{3}$") String defaultCurrency,
      @Min(0) @Max(365) int paymentTermsDays,
      long sourceVersion) {}

  /**
   * 经销商授权的写入形状（授权台账输出①）。
   *
   * <p>{@code rebateRate} 是 <b>0..1 的小数</b>而不是百分数（0.05 与 5% 差 100 倍）， {@link DecimalMax} 与 DB 的 CHECK 各拦一道。
   */
  public record DealerAuthorizationInput(
      @NotNull UUID dealerProfileId,
      @NotBlank @Size(max = 48) String authCode,
      @NotEmpty List<@NotBlank @Size(max = 64) String> regionCodes,
      @NotEmpty List<@NotBlank @Size(max = 64) String> productLineCodes,
      @NotNull LocalDate validFrom,
      @NotNull LocalDate validTo,
      @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency,
      @NotNull @DecimalMin(value = "0") @Digits(integer = 15, fraction = 4) BigDecimal dealerPrice,
      @NotNull @DecimalMin(value = "0.0") @DecimalMax(value = "1.0") @Digits(integer = 3, fraction = 6)
          BigDecimal rebateRate,
      @Size(max = 500) String note) {}

  /** 授权状态流转（暂停 / 恢复 / 到期）。终态 EXPIRED 不可回退。 */
  public record DealerAuthorizationStatusInput(
      @NotNull DealerAuthorizationStatus status, @Size(max = 500) String note) {}

  /** 销量与回款考核目标（输出②的目标侧）。目标为 0 = 不考核。 */
  public record DealerQuotaInput(
      @NotNull UUID authorizationId,
      @NotNull LocalDate periodStart,
      @NotNull LocalDate periodEnd,
      @NotNull @DecimalMin(value = "0") @Digits(integer = 13, fraction = 6) BigDecimal targetQuantity,
      @NotNull @DecimalMin(value = "0") @Digits(integer = 20, fraction = 4) BigDecimal targetAmount,
      @NotNull @DecimalMin(value = "0") @Digits(integer = 20, fraction = 4) BigDecimal targetSettledAmount,
      @Size(max = 500) String note) {}

  /**
   * 经销商销售与回款记录（配额完成率与窜货判定的<b>唯一来源</b>）。
   *
   * <p>刻意<b>没有 amount</b>：金额由 {@code quantity × authorization.dealerPrice} 算出，让调用方直接传总额等于允许它绕过经销价。
   *
   * <p>{@code soldOn} 是<b>业务日期</b>（卖方所在时区的日历日），不是从 timestamptz 截出来的 —— 用 timestamptz 截 date
   * 会让夜里的单子偏移一天，且偏移方向取决于 JVM 与数据库各自的时区设置。
   */
  public record DealerSaleInput(
      @NotNull UUID authorizationId,
      @NotNull UUID skuId,
      @Size(max = 64) String skuCode,
      @Size(max = 200) String skuName,
      @NotBlank @Size(max = 64) String productLineCode,
      @NotBlank @Size(max = 64) String soldRegionCode,
      @NotNull LocalDate soldOn,
      @NotNull @DecimalMin(value = "0") @Digits(integer = 13, fraction = 6) BigDecimal quantity,
      @DecimalMin(value = "0") @Digits(integer = 20, fraction = 4) BigDecimal settledAmount,
      @Size(max = 500) String note) {}

  /**
   * 返点计提（输出③）的写入形状。
   *
   * <p>期间给定而不是「计提到今天」：计提是对<b>某个已结束期间</b>的负债确认， 计提到当天会让每次重跑都把新销售混进已确认的基数里。
   */
  public record DealerRebateAccrueInput(
      @NotNull UUID authorizationId,
      @NotNull LocalDate periodStart,
      @NotNull LocalDate periodEnd,
      @Size(max = 500) String note) {}

  /** 窜货预警处置（判为误报时 note 必填，DB 的 CHECK 也会兜一次）。 */
  public record DealerAlertStateInput(
      @NotNull DealerAlertState alertState,
      @NotBlank @Size(max = 500) String note) {}

  // ------------------------------------------------------------ 行

  /** 经销商档案投影行。 */
  public record DealerProfileRow(
      UUID id,
      UUID partyId,
      String partyCode,
      String partyName,
      DealerCustomerLevel customerLevel,
      String defaultCurrency,
      int paymentTermsDays,
      String sourceSystem,
      long sourceVersion,
      OffsetDateTime syncedAt) {}

  /** 授权台账行。 */
  public record DealerAuthorizationRow(
      UUID id,
      UUID dealerProfileId,
      String authCode,
      List<String> regionCodes,
      List<String> productLineCodes,
      LocalDate validFrom,
      LocalDate validTo,
      String currency,
      BigDecimal dealerPrice,
      BigDecimal rebateRate,
      DealerAuthorizationStatus status,
      DealerComplianceState complianceState,
      int version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 授权台账事件行（append-only）。 */
  public record DealerAuthorizationEventRow(
      UUID id,
      UUID authorizationId,
      String eventType,
      String fromStatus,
      String toStatus,
      String note,
      OffsetDateTime occurredAt) {}

  /** 配额目标行。 */
  public record DealerQuotaRow(
      UUID id,
      UUID authorizationId,
      LocalDate periodStart,
      LocalDate periodEnd,
      BigDecimal targetQuantity,
      BigDecimal targetAmount,
      BigDecimal targetSettledAmount,
      String note) {}

  /**
   * 配额完成率（输出②）。
   *
   * <p><b>三个完成率都是 {@code BigDecimal} 且可能是 {@code null}</b>：目标为 0（不限配额）时完成率是<b>无定义</b>而不是 0 或
   * 100%。0 会被读成「一个都没完成」，100% 会被读成「超额完成」—— 两者都是编造出来的答案。
   */
  public record DealerQuotaAttainmentRow(
      UUID quotaId,
      UUID authorizationId,
      String authCode,
      UUID dealerProfileId,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      BigDecimal targetQuantity,
      BigDecimal actualQuantity,
      BigDecimal quantityAttainmentRate,
      BigDecimal targetAmount,
      BigDecimal actualAmount,
      BigDecimal amountAttainmentRate,
      BigDecimal targetSettledAmount,
      BigDecimal actualSettledAmount,
      BigDecimal settledAttainmentRate) {}

  /** 销售记录行。 */
  public record DealerSaleRow(
      UUID id,
      UUID dealerProfileId,
      UUID authorizationId,
      UUID skuId,
      String skuCode,
      String skuName,
      String productLineCode,
      String soldRegionCode,
      LocalDate soldOn,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal amount,
      String currency,
      BigDecimal settledAmount,
      String note,
      OffsetDateTime recordedAt) {}

  /**
   * 返点计提行（输出③）。
   *
   * <p>{@code financeConfirmed=false} 是<b>本模块口径的常态</b>：蓝图原文写死「需与财务确认计提口径」，而 12 应付/返点未交付，
   * 所以这一列恒为 false，直到财务确认口径并回写。界面上必须把它显示出来。
   */
  public record DealerRebateRow(
      UUID id,
      UUID authorizationId,
      UUID dealerProfileId,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      BigDecimal rebateRate,
      BigDecimal baseAmount,
      BigDecimal rebateAmount,
      DealerAccrualState accrualState,
      boolean financeConfirmed,
      String note,
      OffsetDateTime createdAt) {}

  /** 窜货预警行（输出④）。 */
  public record DealerDiversionAlertRow(
      UUID id,
      UUID dealerProfileId,
      UUID authorizationId,
      UUID dealerSaleId,
      DealerBreachKind breachKind,
      List<String> authorizedRegions,
      List<String> authorizedProductLines,
      String soldRegionCode,
      String productLineCode,
      BigDecimal saleAmount,
      String currency,
      DealerSeverity severity,
      DealerAlertState alertState,
      String note,
      OffsetDateTime detectedAt,
      OffsetDateTime updatedAt) {}

  // ------------------------------------------------------------ 分页与详情

  /** 授权详情：台账头 + 该授权的配额 + 计提 + 预警 + 事件流。 */
  public record DealerAuthorizationDetail(
      DealerAuthorizationRow authorization,
      List<DealerQuotaRow> quotas,
      List<DealerRebateRow> rebates,
      List<DealerDiversionAlertRow> alerts,
      List<DealerAuthorizationEventRow> events) {}

  public record DealerProfilePage(List<DealerProfileRow> items, int page, int size, long total) {}

  public record DealerAuthorizationPage(
      List<DealerAuthorizationRow> items, int page, int size, long total) {}

  public record DealerQuotaAttainmentPage(
      List<DealerQuotaAttainmentRow> items, int page, int size, long total) {}

  public record DealerSalePage(List<DealerSaleRow> items, int page, int size, long total) {}

  public record DealerRebatePage(List<DealerRebateRow> items, int page, int size, long total) {}

  public record DealerAlertPage(List<DealerDiversionAlertRow> items, int page, int size, long total) {}

  /** 写入成功后的回执（版本号 + id，供前端做乐观锁续写）。 */
  public record DealerSaved(UUID id, int version) {}

  // ------------------------------------------------------------ 能力声明

  /**
   * 一条上下游依赖的可用性。
   *
   * <p>{@code available=false} 时 {@code note} <b>必须</b>说清「谁没交付、因此这一环现在是什么行为」——
   * 把缺口报成 0、报成默认值或干脆不提，调用方就会把「没接通」当成「数据就是空的」。
   */
  public record DealerDependency(
      String moduleId,
      String moduleName,
      String ownerService,
      boolean available,
      String note) {}

  /**
   * 能力与上下游可用性声明。
   *
   * <p>存在的理由与 9.7.1.1 / 9.1.2.2 一致：把「哪些输入真有依据」「下游有没有接上」显式报出来，而不是让调用方从 404 或空数组里猜哪一环没通。
   */
  public record DealerCapabilities(
      List<DealerAuthorizationStatus> statuses,
      List<DealerComplianceState> complianceStates,
      List<DealerBreachKind> breachKinds,
      List<DealerAlertState> alertStates,
      List<DealerSeverity> severities,
      List<DealerAccrualState> accrualStates,
      List<DealerCustomerLevel> customerLevels,
      List<String> rules,
      List<DealerDependency> dependencies) {}
}
