package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 16.3.2.3 销售税与 Marketplace Facilitator 的传输模型。
 *
 * <p>蓝图：「处理美国各州销售税与 Marketplace Facilitator 制度：应缴/代缴状态、州级阈值、免征额、
 * 按平台的代扣代缴说明与对账。输入：店铺所在州数据；平台代扣代缴报表；自营应税销售额。
 * 输出：各州税务状态 + 代扣代缴对账结果。关系：上游 = 2 店铺、12.3.1.2；下游 = 12.6 申报。
 * <b>平台代扣代缴不等于免税</b>，卖家的申报义务依然存在，只是由平台代收代缴，需要正确对账。」
 *
 * <p>🔴 五个形状各自回答一个不同的问题：
 *
 * <ul>
 *   <li>{@link SalesTaxCapabilities} —— 「哪些下游未交付、上游读不读得到、判定口径是什么」。</li>
 *   <li>{@link SalesTaxStateRow} —— 「<b>这个州现在是什么状态</b>」（登记 + 规则 + 代缴说明 + 最近一次对账）。</li>
 *   <li>{@link SalesTaxRateRuleView} —— 「<b>这个州的税率规则是哪一版、什么时候生效</b>」。</li>
 *   <li>{@link SalesTaxFacilitatorReportDetail} —— 「平台代扣代缴报表原文 + 逐行解析结果」。</li>
 *   <li>{@link SalesTaxReconciliationView} —— 「<b>应缴多少、平台代缴多少、差多少</b>」（两条金额线，不压成净额）。</li>
 * </ul>
 *
 * <p>🔴 <b>「读不到」与「没有」是两个状态</b>：上游读不到时 {@code available=false} + 原因，
 * <b>不许</b>被显示成「没有销售额」。
 */
public final class SalesTaxModels {

  private SalesTaxModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 一个未交付的下游模块。
   *
   * @param moduleId 模块号
   * @param moduleName 模块名
   * @param ownerModule 归谁（available=false 时必填）
   * @param available 当前是否已交付
   * @param impact 未交付的影响说明（界面原样显示）
   */
  public record SalesTaxCapabilityGap(
      String moduleId, String moduleName, String ownerModule, boolean available, String impact) {}

  /**
   * 一个上游的读取状态。
   *
   * @param moduleId 上游模块号
   * @param moduleName 上游模块名
   * @param available 本服务当前**读得到**吗（false ⇒ 界面显示「读不到」，**不是**「没有」）
   * @param ownerModule 该上游的归属模块
   * @param impact 读不到的影响说明
   */
  public record SalesTaxUpstream(
      String moduleId, String moduleName, boolean available, String ownerModule, String impact) {}

  /**
   * 能力声明。
   *
   * @param gaps 未交付的下游清单
   * @param upstreams 上游读取状态
   * @param nexusTypes 销售税联系类型词表
   * @param filingCycles 申报周期词表
   * @param reconciliationStatuses 对账状态词表
   * @param filingObligations 申报义务词表
   * @param rateSemantics 税率规则语义（版本化 + 不许写死）
   * @param reconciliationSemantics 对账语义（应缴 vs 已代缴两条线）
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   */
  public record SalesTaxCapabilities(
      List<SalesTaxCapabilityGap> gaps,
      List<SalesTaxUpstream> upstreams,
      List<String> nexusTypes,
      List<String> filingCycles,
      List<String> reconciliationStatuses,
      List<String> filingObligations,
      String rateSemantics,
      String reconciliationSemantics,
      String upstreamNote,
      String downstreamNote) {}

  // ---------------------------------------------------------------- 州状态

  /**
   * 一个州的销售税状态。
   *
   * @param stateCode 州码（US-XX）
   * @param stateName 州名
   * @param shopCount 在该州有销售税联系的店铺数
   * @param registeredShopCount 其中已登记销售税账号的店铺数
   * @param registrationStatus 登记状态（REGISTERED / PARTIAL / NOT_REGISTERED）
   * @param ruleStatus 规则状态（RULE_FOUND / RULE_MISSING）
   * @param ruleVersion 当前生效的规则版本号（ruleStatus=RULE_MISSING 时为 null）
   * @param baseRate 当前生效的州基准税率（小数率，0.0725 = 7.25%）
   * @param thresholdAmount 州级阈值
   * @param exemptionAmount 免征额
   * @param filingCycle 申报周期
   * @param marketplaceFacilitator 该州是否实施 Marketplace Facilitator 制度
   * @param facilitatorScopeCount 该州登记了几条「平台 × 代扣代缴说明」
   * @param latestReconciliationId 最近一次对账结果 id（还没有则 null）
   * @param latestPeriodStart 最近一次对账的期间起
   * @param latestPeriodEnd 最近一次对账的期间止
   * @param currency 最近一次对账的币种
   * @param selfTaxableSales 最近一次对账的自营应税销售额
   * @param expectedTax 最近一次对账的**应缴**
   * @param facilitatorTax 最近一次对账的**已由平台代扣代缴**
   * @param variance 最近一次对账的**差异**（应缴 − 已代缴）
   * @param reconciliationStatus 最近一次对账的状态
   * @param filingObligation 申报义务（FILING_REQUIRED / BELOW_THRESHOLD / NO_ACTIVITY /
   *     RULE_MISSING / NOT_REGISTERED）
   * @param status 汇总状态
   * @param statusLabel 汇总状态的人话
   * @param notes 逐条提示（界面原样显示）
   */
  public record SalesTaxStateRow(
      String stateCode,
      String stateName,
      int shopCount,
      int registeredShopCount,
      String registrationStatus,
      String ruleStatus,
      Integer ruleVersion,
      BigDecimal baseRate,
      BigDecimal thresholdAmount,
      BigDecimal exemptionAmount,
      String filingCycle,
      boolean marketplaceFacilitator,
      int facilitatorScopeCount,
      String latestReconciliationId,
      LocalDate latestPeriodStart,
      LocalDate latestPeriodEnd,
      String currency,
      BigDecimal selfTaxableSales,
      BigDecimal expectedTax,
      BigDecimal facilitatorTax,
      BigDecimal variance,
      String reconciliationStatus,
      String filingObligation,
      String status,
      String statusLabel,
      List<String> notes) {}

  /** 州状态清单（真分页 + 真筛选）。 */
  public record SalesTaxStatePage(
      List<SalesTaxStateRow> items, long total, int page, int size, String note) {}

  // ---------------------------------------------------------------- 店铺

  /**
   * 一个店铺 + 它在哪些州有销售税联系。
   *
   * @param id 店铺 id（本服务 shop.id）
   * @param code 店铺编码
   * @param name 店铺名
   * @param platformCode 平台编码
   * @param platformName 平台名
   * @param status 店铺状态
   * @param operatingMode 经营模式（2.3.2.1）
   * @param stateCodes 在哪些州有销售税联系
   * @param registeredStateCount 其中已登记销售税账号的州数
   */
  public record SalesTaxShopView(
      String id,
      String code,
      String name,
      String platformCode,
      String platformName,
      String status,
      String operatingMode,
      List<String> stateCodes,
      int registeredStateCount) {}

  /** 店铺目录（真分页 + 真筛选）。 */
  public record SalesTaxShopPage(
      List<SalesTaxShopView> items, long total, int page, int size, String note) {}

  // ---------------------------------------------------------------- 税率规则

  /**
   * 一版销售税率规则。
   *
   * @param id 规则版本 id
   * @param stateCode 州码
   * @param stateName 州名
   * @param ruleVersion 版本号（同一州内从 1 递增）
   * @param effectiveFrom 生效起始日（左闭）
   * @param effectiveTo 生效终止日（右开，可空 = 当前这版）
   * @param baseRate 州基准税率（小数率）
   * @param thresholdAmount 州级阈值
   * @param exemptionAmount 免征额
   * @param marketplaceFacilitator 该州是否实施 Marketplace Facilitator 制度
   * @param filingCycle 申报周期
   * @param status 状态（ACTIVE / SUPERSEDED / ARCHIVED）
   * @param note 备注
   * @param createdAt 创建时刻
   * @param updatedAt 更新时刻
   */
  public record SalesTaxRateRuleView(
      String id,
      String stateCode,
      String stateName,
      int ruleVersion,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      BigDecimal baseRate,
      BigDecimal thresholdAmount,
      BigDecimal exemptionAmount,
      boolean marketplaceFacilitator,
      String filingCycle,
      String status,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 录入 / 改一版税率规则。
   *
   * @param stateCode 州码
   * @param effectiveFrom 生效起始日（ISO 日期）
   * @param effectiveTo 生效终止日（ISO 日期，可空 = 当前这版）
   * @param baseRate 州基准税率（十进制字符串，如 "0.0725"）
   * @param thresholdAmount 州级阈值（十进制字符串）
   * @param exemptionAmount 免征额（十进制字符串）
   * @param marketplaceFacilitator 该州是否实施 Marketplace Facilitator 制度
   * @param filingCycle 申报周期
   * @param note 备注
   */
  public record SalesTaxRateRuleInput(
      String stateCode,
      String effectiveFrom,
      String effectiveTo,
      String baseRate,
      String thresholdAmount,
      String exemptionAmount,
      Boolean marketplaceFacilitator,
      String filingCycle,
      String note) {}

  /** 税率规则清单（真分页 + 真筛选）。 */
  public record SalesTaxRateRulePage(
      List<SalesTaxRateRuleView> items, long total, int page, int size, String note) {}

  /** 某一刻生效的那一版规则。 */
  public record SalesTaxEffectiveRule(
      String stateCode,
      String stateName,
      LocalDate asOf,
      boolean found,
      SalesTaxRateRuleView rule,
      String note) {}

  // ---------------------------------------------------------------- 店铺州联系

  /**
   * 一家店在一个州的销售税联系。
   *
   * @param id 记录 id
   * @param shopId 店铺 id
   * @param shopCode 店铺编码
   * @param shopName 店铺名
   * @param stateCode 州码
   * @param stateName 州名
   * @param nexusType 联系类型
   * @param registered 是否已登记销售税账号
   * @param registrationNo 销售税登记号
   * @param registeredFrom 登记生效日
   * @param note 备注
   * @param createdAt 创建时刻
   * @param updatedAt 更新时刻
   */
  public record SalesTaxShopStateView(
      String id,
      String shopId,
      String shopCode,
      String shopName,
      String stateCode,
      String stateName,
      String nexusType,
      boolean registered,
      String registrationNo,
      LocalDate registeredFrom,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 登记 / 改一家店在一个州的销售税联系。
   *
   * @param shopId 店铺 id
   * @param stateCode 州码
   * @param nexusType 联系类型
   * @param registered 是否已登记销售税账号
   * @param registrationNo 销售税登记号
   * @param registeredFrom 登记生效日（ISO 日期，可空）
   * @param note 备注
   */
  public record SalesTaxShopStateInput(
      String shopId,
      String stateCode,
      String nexusType,
      Boolean registered,
      String registrationNo,
      String registeredFrom,
      String note) {}

  /** 店铺州联系清单（真分页 + 真筛选）。 */
  public record SalesTaxShopStatePage(
      List<SalesTaxShopStateView> items, long total, int page, int size, String note) {}

  // ---------------------------------------------------------------- 代扣代缴说明

  /**
   * 一个平台在一个州的代扣代缴说明。
   *
   * @param id 记录 id
   * @param stateCode 州码
   * @param stateName 州名
   * @param platformCode 平台编码
   * @param facilitatorEnabled 该平台在该州是否代扣代缴
   * @param remitTo 汇缴对象
   * @param reportRequired 是否需要单独提交代扣代缴报表
   * @param note 说明正文
   * @param createdAt 创建时刻
   * @param updatedAt 更新时刻
   */
  public record SalesTaxFacilitatorScopeView(
      String id,
      String stateCode,
      String stateName,
      String platformCode,
      boolean facilitatorEnabled,
      String remitTo,
      boolean reportRequired,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * 登记 / 改一个平台在一个州的代扣代缴说明。
   *
   * @param stateCode 州码
   * @param platformCode 平台编码
   * @param facilitatorEnabled 该平台在该州是否代扣代缴
   * @param remitTo 汇缴对象
   * @param reportRequired 是否需要单独提交代扣代缴报表
   * @param note 说明正文
   */
  public record SalesTaxFacilitatorScopeInput(
      String stateCode,
      String platformCode,
      Boolean facilitatorEnabled,
      String remitTo,
      Boolean reportRequired,
      String note) {}

  /** 代扣代缴说明清单（真分页 + 真筛选）。 */
  public record SalesTaxFacilitatorScopePage(
      List<SalesTaxFacilitatorScopeView> items, long total, int page, int size, String note) {}

  // ---------------------------------------------------------------- 代扣代缴报表

  /**
   * 一份平台代扣代缴报表。
   *
   * @param id 报表 id
   * @param platformCode 平台编码
   * @param fileKey 报表身份（平台报表号 / 上传文件名）
   * @param fileName 上传文件名
   * @param contentType 文件类型（CSV / JSON）
   * @param contentHash 内容指纹（sha256）
   * @param byteSize 字节数
   * @param parseVersion 解析版本号
   * @param parseStatus 解析状态（PARSED / REJECTED）
   * @param parseWarnings 解析告警（逐条）
   * @param periodStart 申报期间起
   * @param periodEnd 申报期间止
   * @param uploadedBy 上传人
   * @param parsedAt 解析时刻
   * @param createdAt 落库时刻
   * @param lineCount 明细行数
   */
  public record SalesTaxFacilitatorReportView(
      String id,
      String platformCode,
      String fileKey,
      String fileName,
      String contentType,
      String contentHash,
      long byteSize,
      int parseVersion,
      String parseStatus,
      List<String> parseWarnings,
      LocalDate periodStart,
      LocalDate periodEnd,
      String uploadedBy,
      OffsetDateTime parsedAt,
      OffsetDateTime createdAt,
      int lineCount) {}

  /**
   * 一份报表的详情 = 报表本体 + 逐行解析结果。
   *
   * @param report 报表本体
   * @param lines 明细行
   */
  public record SalesTaxFacilitatorReportDetail(
      SalesTaxFacilitatorReportView report, List<SalesTaxFacilitatorLineView> lines) {}

  /**
   * 一行代扣代缴明细。
   *
   * @param id 明细 id
   * @param stateCode 州码
   * @param stateName 州名
   * @param platformCode 平台编码
   * @param shopCode 店铺编码（平台报表按店拆行时有值）
   * @param taxableAmount 应税销售额（平台口径）
   * @param taxCollected 平台已代扣代缴的税额
   * @param orderCount 订单笔数
   * @param currency 币种
   * @param sourceLineNo 文件里的行号
   * @param remark 备注
   */
  public record SalesTaxFacilitatorLineView(
      String id,
      String stateCode,
      String stateName,
      String platformCode,
      String shopCode,
      BigDecimal taxableAmount,
      BigDecimal taxCollected,
      long orderCount,
      String currency,
      int sourceLineNo,
      String remark) {}

  /** 报表清单（真分页 + 真筛选）。 */
  public record SalesTaxFacilitatorReportPage(
      List<SalesTaxFacilitatorReportView> items, long total, int page, int size, String note) {}

  /**
   * 上传一份平台代扣代缴报表。
   *
   * @param platformCode 平台编码
   * @param fileKey 报表身份（平台报表号 / 上传文件名）
   * @param fileName 上传文件名
   * @param contentType 文件类型（CSV / JSON）
   * @param content 原文（一字不改地落库）
   * @param periodStart 申报期间起（ISO 日期）
   * @param periodEnd 申报期间止（ISO 日期）
   */
  public record SalesTaxFacilitatorImportInput(
      String platformCode,
      String fileKey,
      String fileName,
      String contentType,
      String content,
      String periodStart,
      String periodEnd) {}

  /**
   * 导入结果。
   *
   * @param report 落库的报表本体
   * @param scanned 文件里一共读到多少条（**包含**被跳过的）
   * @param imported 成功入库多少条
   * @param skipped 跳过多少条
   * @param warnings 逐条告警（第几行 + 为什么）
   */
  public record SalesTaxFacilitatorImportResult(
      SalesTaxFacilitatorReportView report,
      int scanned,
      int imported,
      int skipped,
      List<String> warnings) {}

  // ---------------------------------------------------------------- 对账

  /**
   * 一条对账结果（州 × 期间 × 币种）。
   *
   * @param id 对账 id
   * @param stateCode 州码
   * @param stateName 州名
   * @param periodStart 期间起
   * @param periodEnd 期间止
   * @param currency 币种
   * @param selfTaxableSales 自营应税销售额
   * @param exemptSales 落在免征额内的部分（**不是免税**）
   * @param taxableBase 应税基数
   * @param expectedTax **应缴**
   * @param facilitatorTax **已由平台代扣代缴**
   * @param settlementTax 结算单口径的平台代扣税（**交叉验证用**，不参与差异计算）
   * @param variance **差异** = 应缴 − 已代缴
   * @param ruleVersion 用的哪一版规则
   * @param baseRate 用的税率
   * @param thresholdAmount 用的州级阈值
   * @param exemptionAmount 用的免征额
   * @param rateChangedInPeriod 期间内该州是否发过新版规则
   * @param status 对账状态
   * @param statusLabel 状态的人话
   * @param shopCount 自营销售额归集了几家店
   * @param computedAt 计算时刻
   */
  public record SalesTaxReconciliationView(
      String id,
      String stateCode,
      String stateName,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      BigDecimal selfTaxableSales,
      BigDecimal exemptSales,
      BigDecimal taxableBase,
      BigDecimal expectedTax,
      BigDecimal facilitatorTax,
      BigDecimal settlementTax,
      BigDecimal variance,
      int ruleVersion,
      BigDecimal baseRate,
      BigDecimal thresholdAmount,
      BigDecimal exemptionAmount,
      boolean rateChangedInPeriod,
      String status,
      String statusLabel,
      int shopCount,
      OffsetDateTime computedAt) {}

  /**
   * 算一次对账的请求。
   *
   * @param stateCode 州码（留空 = 该租户所有有销售税联系的州）
   * @param periodStart 期间起（ISO 日期）
   * @param periodEnd 期间止（ISO 日期）
   * @param currency 币种（留空 = 期间内出现过的全部币种，**按币种分开算，不相加**）
   */
  public record SalesTaxReconciliationRequest(
      String stateCode, String periodStart, String periodEnd, String currency) {}

  /** 对账结果清单（真分页 + 真筛选）。 */
  public record SalesTaxReconciliationPage(
      List<SalesTaxReconciliationView> items, long total, int page, int size, String note) {}

  // ---------------------------------------------------------------- 申报义务

  /**
   * 一条申报义务（下游 12.6 申报接手用）。
   *
   * @param reconciliationId 对账结果 id
   * @param stateCode 州码
   * @param stateName 州名
   * @param periodStart 期间起
   * @param periodEnd 期间止
   * @param currency 币种
   * @param expectedTax 应缴
   * @param facilitatorTax 已由平台代扣代缴
   * @param variance 差异（正数 = 平台少收了，卖家要补缴）
   * @param filingCycle 申报周期
   * @param registrationStatus 该州的登记状态
   * @param reconciliationStatus 对账状态
   * @param filingObligation 申报义务结论
   * @param note 提示
   */
  public record SalesTaxObligationRow(
      String reconciliationId,
      String stateCode,
      String stateName,
      LocalDate periodStart,
      LocalDate periodEnd,
      String currency,
      BigDecimal expectedTax,
      BigDecimal facilitatorTax,
      BigDecimal variance,
      String filingCycle,
      String registrationStatus,
      String reconciliationStatus,
      String filingObligation,
      String note) {}

  /** 申报义务清单（真分页 + 真筛选）。 */
  public record SalesTaxObligationPage(
      List<SalesTaxObligationRow> items, long total, int page, int size, String note) {}

  // ---------------------------------------------------------------- 通用

  /**
   * 一个可选值的传输包装（避免用 {@code null} 表达「没有」）。
   *
   * @param value 值
   */
  public record SalesTaxStringValue(@Schema(nullable = true) String value) {}
}
