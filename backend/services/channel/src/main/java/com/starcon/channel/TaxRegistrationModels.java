package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * 16.3.1.1 税号与辖区的传输模型。
 *
 * <p>蓝图：「维护各店铺/法人在各销售辖区的税务登记：税号、VAT 状态、是否注册、EPR 注册号、
 * 注册有效期与申报周期。输入：税号录入；辖区选择；有效期。输出：税务登记档案 + 缺失项提示。
 * 关系：上游 = 17 法人、2 店铺；下游 = 16.3.2.1、12.6 税务。
 * <b>未注册辖区的销售额是合规炸弹</b>：欧洲没有 VAT 号还在欧盟销售，是稽查重点。」
 *
 * <p>🔴 四个形状各自回答一个不同的问题：
 *
 * <ul>
 *   <li>{@link TaxRegistrationCapabilities} —— 「哪些下游量不到、量不到归谁、缺口的判定口径是什么」。
 *       16.3.1.2 申报日历、16.3.2.1 VAT/IOSS、16.3.2.2 EPR/包装法、12.6 税务当前<b>都未交付</b>，
 *       这里如实报出，<b>不假装有</b>。</li>
 *   <li>{@link TaxRegistrationView} —— 「这个主体在这个辖区的登记档案长什么样」。</li>
 *   <li>{@link TaxGapItem} —— 「**缺什么**」：一条一个缺失项，带严重度与整改建议。</li>
 *   <li>{@link TaxCoverageRow} —— 「这家店该登记的辖区登记齐了没有」：逐辖区覆盖情况。</li>
 * </ul>
 *
 * <p>🔴 <b>「读不到」与「没有」是两个状态</b>：上游法人档案读不到时，
 * {@code legalEntityAvailable=false} + 原因，<b>不许</b>被显示成「法人叫空白」或「未注册」。
 */
public final class TaxRegistrationModels {

  private TaxRegistrationModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 一个未交付的下游模块。
   *
   * @param moduleId 模块号（如 16.3.2.1）
   * @param moduleName 模块名
   * @param ownerModule 归谁（available=false 时必填）
   * @param available 当前是否已交付
   * @param impact 未交付 / 量不到的影响说明（界面原样显示）
   */
  public record TaxRegistrationCapabilityGap(
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
  public record TaxRegistrationUpstream(
      String moduleId, String moduleName, boolean available, String ownerModule, String impact) {}

  /**
   * 能力声明。
   *
   * @param gaps 未交付的下游清单
   * @param upstreams 上游读取状态（店铺目录 / 法人档案）
   * @param gapTypes 缺失项类型词表
   * @param gapSeverities 严重度词表
   * @param vatStatuses VAT 状态词表
   * @param filingCycles 申报周期词表
   * @param subjectTypes 主体类型词表
   * @param gapSemantics 缺失项的判定口径（加粗句：怎么算缺、什么算缺）
   * @param subjectSemantics 主体语义（店铺 / 法人；法人登记怎么覆盖店铺）
   * @param jurisdictionSemantics 辖区语义（值域钉死 + 推导边界）
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   * @param reportingScopeNote 申报范围的边界（本模块管什么、不管什么）
   */
  public record TaxRegistrationCapabilities(
      List<TaxRegistrationCapabilityGap> gaps,
      List<TaxRegistrationUpstream> upstreams,
      List<String> gapTypes,
      List<String> gapSeverities,
      List<String> vatStatuses,
      List<String> filingCycles,
      List<String> subjectTypes,
      String gapSemantics,
      String subjectSemantics,
      String jurisdictionSemantics,
      String upstreamNote,
      String downstreamNote,
      String reportingScopeNote) {}

  // ---------------------------------------------------------------- 辖区码表

  /**
   * 一个销售辖区（受控值域里的一行）。
   *
   * @param code 辖区编码（如 {@code EU-DE}）
   * @param name 辖区名称
   * @param region 区域
   * @param countryCode ISO 3166-1 alpha-2
   * @param subdivisionCode ISO 3166-2 分区码（国家级辖区为空串）
   * @param taxRegime 税制（VAT / GST / 销售税 / 消费税 / 无）
   * @param vatRequired 该辖区是否要求登记号
   * @param requiresEpr 该辖区是否要求 EPR 注册号
   * @param defaultFilingCycle 默认申报周期
   * @param derivationScope 「站点国家」能不能推导出该辖区的登记要求
   */
  public record TaxJurisdictionView(
      String code,
      String name,
      String region,
      String countryCode,
      String subdivisionCode,
      String taxRegime,
      boolean vatRequired,
      boolean requiresEpr,
      String defaultFilingCycle,
      String derivationScope) {}

  /** 辖区码表清单。 */
  public record TaxJurisdictionList(List<TaxJurisdictionView> items, int total, String note) {}

  // ---------------------------------------------------------------- 店铺目录

  /**
   * 店铺目录里的一行（**直接读本服务自己的 `shop` 表**，不写死店铺常量、不造平行店铺表）。
   *
   * @param shopId 店铺 ID
   * @param code 店铺编码
   * @param name 店铺名称
   * @param platformCode 平台编码
   * @param platformName 平台名称
   * @param legalEntityId 绑定法人（**逻辑 ID**，identity 拥有；为空串表示未绑定）
   * @param marketplaceCodes 启用的站点编码
   * @param requiredJurisdictionCodes 按站点推导出**应当登记**的辖区编码
   */
  public record TaxShopView(
      String shopId,
      String code,
      String name,
      String platformCode,
      String platformName,
      String legalEntityId,
      List<String> marketplaceCodes,
      List<String> requiredJurisdictionCodes) {}

  /** 店铺目录清单。 */
  public record TaxShopList(List<TaxShopView> items, int total, String note) {}

  // ---------------------------------------------------------------- 登记档案

  /**
   * 新建 / 修改一条税务登记的入参。
   *
   * @param subjectType 主体类型（SHOP / LEGAL_ENTITY）
   * @param subjectId 主体 ID（店铺本库 ID / 法人逻辑 ID）
   * @param jurisdictionCode 辖区编码（必须落在受控值域里）
   * @param taxNumber 税号
   * @param vatStatus VAT 状态
   * @param isRegistered 是否注册
   * @param vatNumber VAT 号
   * @param eprRegistrationNo EPR 注册号
   * @param filingCycle 申报周期
   * @param registrationValidFrom 注册有效期起（ISO-8601 带偏移量）
   * @param registrationValidUntil 注册有效期止（ISO-8601 带偏移量）
   * @param status 状态（ACTIVE / ARCHIVED）
   * @param note 备注
   */
  public record TaxRegistrationInput(
      String subjectType,
      String subjectId,
      String jurisdictionCode,
      String taxNumber,
      String vatStatus,
      Boolean isRegistered,
      String vatNumber,
      String eprRegistrationNo,
      String filingCycle,
      String registrationValidFrom,
      String registrationValidUntil,
      String status,
      String note) {}

  /**
   * 一条税务登记档案。
   *
   * @param id 登记 ID
   * @param subjectType 主体类型
   * @param subjectId 主体 ID
   * @param subjectCode 主体编码（店铺编码 / 法人编码；**读不到上游时是空串，不是「不存在」**）
   * @param subjectName 主体名称（同上）
   * @param subjectAvailable 上游读得到吗（false ⇒ 显示「读不到」）
   * @param subjectNote 读不到 / 解析不了的原因（读得到时是空串）
   * @param jurisdictionCode 辖区编码
   * @param jurisdictionName 辖区名称
   * @param taxRegime 该辖区的税制
   * @param taxNumber 税号
   * @param vatStatus VAT 状态
   * @param vatStatusLabel VAT 状态的人话
   * @param isRegistered 是否注册
   * @param vatNumber VAT 号
   * @param eprRegistrationNo EPR 注册号
   * @param filingCycle 申报周期
   * @param registrationValidFrom 注册有效期起
   * @param registrationValidUntil 注册有效期止
   * @param status 登记状态（ACTIVE / ARCHIVED）
   * @param effectiveStatus 生效状态（ACTIVE / EXPIRED / ARCHIVED）
   * @param daysToExpiry 距失效还有几天（**负数 = 已过期**；无有效期时为 null）
   * @param note 备注
   * @param createdAt 创建时刻
   * @param updatedAt 更新时刻
   */
  public record TaxRegistrationView(
      String id,
      String subjectType,
      String subjectId,
      String subjectCode,
      String subjectName,
      boolean subjectAvailable,
      String subjectNote,
      String jurisdictionCode,
      String jurisdictionName,
      String taxRegime,
      String taxNumber,
      String vatStatus,
      String vatStatusLabel,
      boolean isRegistered,
      String vatNumber,
      String eprRegistrationNo,
      String filingCycle,
      String registrationValidFrom,
      String registrationValidUntil,
      String status,
      String effectiveStatus,
      @Schema(description = "距失效还有几天；负数 = 已过期；null = 没记有效期") Integer daysToExpiry,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 登记档案清单（真分页）。 */
  public record TaxRegistrationPage(List<TaxRegistrationView> items, int total, int page, int size) {}

  // ---------------------------------------------------------------- 缺失项

  /**
   * 一条缺失项。
   *
   * @param gapType 缺失项类型
   * @param gapTypeLabel 缺失项类型的人话
   * @param severity 严重度（HIGH / MEDIUM）
   * @param subjectType 主体类型
   * @param subjectId 主体 ID
   * @param subjectCode 主体编码
   * @param subjectName 主体名称
   * @param shopId 关联店铺 ID（法人主体时为空串）
   * @param shopCode 关联店铺编码
   * @param shopName 关联店铺名称
   * @param jurisdictionCode 辖区编码
   * @param jurisdictionName 辖区名称
   * @param message 人话说明（**界面上直接显示**）
   * @param remediation 整改建议
   */
  public record TaxGapItem(
      String gapType,
      String gapTypeLabel,
      String severity,
      String subjectType,
      String subjectId,
      String subjectCode,
      String subjectName,
      String shopId,
      String shopCode,
      String shopName,
      String jurisdictionCode,
      String jurisdictionName,
      String message,
      String remediation) {}

  /** 缺失项清单（真分页 + 汇总计数）。 */
  public record TaxGapPage(
      List<TaxGapItem> items,
      int total,
      int page,
      int size,
      long highCount,
      long mediumCount,
      long affectedShopCount,
      String note) {}

  /** 按缺失项类型分的计数。 */
  public record TaxGapTypeCount(String gapType, String gapTypeLabel, String severity, long count) {}

  /**
   * 缺失项汇总。
   *
   * @param total 缺失项总数
   * @param highCount 高严重度条数
   * @param mediumCount 中严重度条数
   * @param affectedShopCount 受影响店铺数
   * @param affectedSubjectCount 受影响主体数（店铺 + 法人）
   * @param byGapType 按类型计数（键 = 缺失项类型）
   * @param gapTypeCounts 按类型的明细
   * @param note 口径说明
   */
  public record TaxGapSummary(
      long total,
      long highCount,
      long mediumCount,
      long affectedShopCount,
      long affectedSubjectCount,
      Map<String, Long> byGapType,
      List<TaxGapTypeCount> gapTypeCounts,
      String note) {}

  // ---------------------------------------------------------------- 覆盖情况

  /**
   * 一个辖区在某家店上的覆盖情况。
   *
   * @param jurisdictionCode 辖区编码
   * @param jurisdictionName 辖区名称
   * @param registrationId 命中的登记 ID（空串 = 没有登记）
   * @param registrationState 登记状态（REGISTERED / NOT_REGISTERED / EXPIRED / MISSING）
   * @param covered 是否已覆盖（登记在且未过期）
   */
  public record TaxCoverageItem(
      String jurisdictionCode,
      String jurisdictionName,
      String registrationId,
      String registrationState,
      boolean covered) {}

  /**
   * 一家店的辖区覆盖情况。
   *
   * @param shopId 店铺 ID
   * @param shopCode 店铺编码
   * @param shopName 店铺名称
   * @param platformCode 平台编码
   * @param requiredJurisdictionCount 应当登记的辖区数
   * @param coveredJurisdictionCount 已覆盖的辖区数
   * @param gapCount 该店的缺失项条数
   * @param coverageStatus 覆盖状态（COMPLETE / INCOMPLETE / NO_REQUIREMENT）
   * @param coverageStatusLabel 覆盖状态的人话
   * @param jurisdictions 逐辖区明细
   */
  public record TaxCoverageRow(
      String shopId,
      String shopCode,
      String shopName,
      String platformCode,
      int requiredJurisdictionCount,
      int coveredJurisdictionCount,
      int gapCount,
      String coverageStatus,
      String coverageStatusLabel,
      List<TaxCoverageItem> jurisdictions) {}

  /** 覆盖情况清单。 */
  public record TaxCoverageList(List<TaxCoverageRow> items, int total, String note) {}
}
