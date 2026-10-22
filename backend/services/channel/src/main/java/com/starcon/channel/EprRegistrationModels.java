package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 16.3.2.2 EPR/包装法的传输模型。
 *
 * <p>蓝图：「管理 EPR 与包装法合规（德国 LUCID、包装注册、电池法、WEEE 等）：注册号、适用国家与品类、
 * 申报周期与关联费用。输入：注册信息；适用规则；申报数据。输出：EPR 档案 + 合规状态 + 费用记录。
 * 关系：上游 = 16.3.1.1；下游 = 12 财务（费用）、4 刊登（是否允许在相关国家销售）。
 * <b>EPR 未注册的国家应直接限制刊登</b>，这是避免下架与罚款最有效的做法。」
 *
 * <p>🔴 六个形状各自回答一个不同的问题：
 *
 * <ul>
 *   <li>{@link EprCapabilities} —— 「哪些下游未交付、量不到归谁、判定的口径是什么」。</li>
 *   <li>{@link EprRegistrationView} —— 「这份制度级注册档案长什么样」（含当前版本判定）。</li>
 *   <li>{@link EprCoverageRow} —— 「<b>这个国家这个品类有没有覆盖</b>」（多对多的反查结果）。</li>
 *   <li>{@link EprPublishGate} —— 「<b>这份内容能不能发</b>」（服务端算出来的刊登结论）。</li>
 *   <li>{@link EprFilingRow} —— 「当前是否在申报期内」。</li>
 *   <li>{@link EprFeeView} —— 「这笔费用是谁付的、哪个周期、多少钱、什么币种」。</li>
 * </ul>
 *
 * <p>🔴 <b>「读不到」与「没有」是两个状态</b>：上游读不到时 {@code available=false} + 原因，
 * <b>不许</b>被显示成「不存在」或「未注册」。
 */
public final class EprRegistrationModels {

  private EprRegistrationModels() {}

  // ---------------------------------------------------------------- 能力声明

  /**
   * 一个未交付的下游模块。
   *
   * @param moduleId 模块号（如 12）
   * @param moduleName 模块名
   * @param ownerModule 归谁（available=false 时必填）
   * @param available 当前是否已交付
   * @param impact 未交付 / 量不到的影响说明（界面原样显示）
   */
  public record EprCapabilityGap(
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
  public record EprUpstream(
      String moduleId, String moduleName, boolean available, String ownerModule, String impact) {}

  /**
   * 能力声明。
   *
   * @param gaps 未交付的下游清单
   * @param upstreams 上游读取状态
   * @param regimeTypes 制度词表（值域钉死在 DB 层）
   * @param categories 适用品类词表
   * @param filingCycles 申报周期词表
   * @param validityKinds 有效期形态词表
   * @param feeTypes 费用类型词表
   * @param feeStatuses 费用状态词表
   * @param paidBy 费用承担方词表
   * @param reasons 判定原因词表
   * @param conclusions 闸门结论词表
   * @param filingStatuses 申报期状态词表
   * @param regimeSemantics 制度语义（制度级 vs 上游辖区级）
   * @param applicabilitySemantics 适用语义（不适用 vs 未注册 vs 判不了）
   * @param filingSemantics 申报期语义
   * @param feeSemantics 费用语义（不含财务凭证 + 按币种分组）
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   * @param enforcementNote 落地说明（闸门挂在哪两个放行口上）
   */
  public record EprCapabilities(
      List<EprCapabilityGap> gaps,
      List<EprUpstream> upstreams,
      List<String> regimeTypes,
      List<String> categories,
      List<String> filingCycles,
      List<String> validityKinds,
      List<String> feeTypes,
      List<String> feeStatuses,
      List<String> paidBy,
      List<String> reasons,
      List<String> conclusions,
      List<String> filingStatuses,
      String regimeSemantics,
      String applicabilitySemantics,
      String filingSemantics,
      String feeSemantics,
      String upstreamNote,
      String downstreamNote,
      String enforcementNote) {}

  // ---------------------------------------------------------------- 参考数据

  /**
   * 一个制度（受控值域里的一行）+ 它在哪些国家适用、适用哪些品类。
   *
   * @param code 制度编码（LUCID / PACKAGING_REGISTRATION / BATTERY / WEEE）
   * @param name 制度名称
   * @param authority 主管机构
   * @param defaultFilingCycle 默认申报周期
   * @param countries 该制度在哪些国家适用（不在这里 ⇒ NOT_APPLICABLE ⇒ 放行）
   */
  public record EprRegimeView(
      String code,
      String name,
      String authority,
      String defaultFilingCycle,
      List<EprCountryRegimeView> countries) {}

  /**
   * 一个国家 × 一条制度的适用事实。
   *
   * @param countryCode ISO 3166-1 alpha-2
   * @param countryName 国家名称
   * @param regimeCode 制度编码
   * @param regimeName 制度名称
   * @param authority 该国该制度的主管机构
   * @param categoryCodes 该国该制度适用的品类
   */
  public record EprCountryRegimeView(
      String countryCode,
      String countryName,
      String regimeCode,
      String regimeName,
      String authority,
      List<String> categoryCodes) {}

  /** 制度码表清单。 */
  public record EprRegimeList(List<EprRegimeView> items, int total, String note) {}

  /**
   * 一个适用品类。
   *
   * @param code 品类编码
   * @param name 品类名称
   */
  public record EprCategoryView(String code, String name) {}

  /** 适用品类码表清单。 */
  public record EprCategoryList(List<EprCategoryView> items, int total, String note) {}

  // ---------------------------------------------------------------- 店铺目录

  /**
   * 店铺目录里的一行（**直接读本服务自己的 `shop` 表**，不写死店铺常量、不造平行店铺表）。
   *
   * @param shopId 店铺 ID
   * @param code 店铺编码
   * @param name 店铺名称
   * @param platformCode 平台编码
   * @param platformName 平台名称
   * @param marketplaceCodes 启用的站点编码
   * @param eprCountryCodes 启用的站点里**要求 EPR** 的国家（空 = 不用管 EPR）
   */
  public record EprShopView(
      String shopId,
      String code,
      String name,
      String platformCode,
      String platformName,
      List<String> marketplaceCodes,
      List<String> eprCountryCodes) {}

  /** 店铺目录清单。 */
  public record EprShopList(List<EprShopView> items, int total, String note) {}

  // ---------------------------------------------------------------- EPR 档案

  /**
   * 新建 / 修改一条 EPR 注册的入参。
   *
   * @param regimeCode 制度编码（必须落在受控值域里）
   * @param registrationNo 制度级注册号
   * @param status 状态（ACTIVE / DISABLED）
   * @param validityKind 有效期形态（FIXED / OPEN_ENDED / UNKNOWN）
   * @param validFrom 有效期起（ISO-8601 带偏移量）
   * @param validUntil 有效期止（ISO-8601 带偏移量）
   * @param filingCycle 申报周期
   * @param filingStartMonth 申报期起始月（1..12）
   * @param filingEndMonth 申报期结束月（1..12）
   * @param effectiveFrom 生效时刻（ISO-8601 带偏移量；**不许早于当下** ⇒ 不许回填）
   * @param countryCodes 适用国家（ISO 3166-1 alpha-2）
   * @param categoryCodes 适用品类
   * @param note 备注
   */
  public record EprRegistrationInput(
      String regimeCode,
      String registrationNo,
      String status,
      String validityKind,
      String validFrom,
      String validUntil,
      String filingCycle,
      Integer filingStartMonth,
      Integer filingEndMonth,
      String effectiveFrom,
      List<String> countryCodes,
      List<String> categoryCodes,
      String note) {}

  /**
   * 一条 EPR 制度级注册档案（**当前生效版本**）。
   *
   * @param id 版本行 ID
   * @param registrationKey 逻辑登记 ID（跨版本稳定）
   * @param versionNo 版本号
   * @param regimeCode 制度编码
   * @param regimeName 制度名称
   * @param authority 主管机构
   * @param registrationNo 制度级注册号
   * @param status 状态（ACTIVE / DISABLED）
   * @param validityKind 有效期形态
   * @param validFrom 有效期起
   * @param validUntil 有效期止
   * @param filingCycle 申报周期
   * @param filingCycleLabel 申报周期的人话
   * @param filingStartMonth 申报期起始月
   * @param filingEndMonth 申报期结束月
   * @param filingStatus 申报期状态（IN_PERIOD / OUT_OF_PERIOD / NOT_RECURRING / UNKNOWN）
   * @param filingStatusLabel 申报期状态的人话
   * @param filingPeriodLabel 本期申报期窗口
   * @param daysToNextFiling 距下一期申报期开始还有几天（null = 没有周期 / 判不了）
   * @param effectiveFrom 生效起
   * @param effectiveTo 生效止（空 = 当前版本）
   * @param current 是否当前生效版本
   * @param countryCodes 适用国家
   * @param categoryCodes 适用品类
   * @param daysToExpiry 距有效期止还有几天（负数 = 已过期；null = 长期有效 / 没记）
   * @param note 备注
   * @param createdAt 创建时刻
   * @param updatedAt 更新时刻
   */
  public record EprRegistrationView(
      String id,
      String registrationKey,
      int versionNo,
      String regimeCode,
      String regimeName,
      String authority,
      String registrationNo,
      String status,
      String validityKind,
      String validFrom,
      String validUntil,
      String filingCycle,
      String filingCycleLabel,
      Integer filingStartMonth,
      Integer filingEndMonth,
      String filingStatus,
      String filingStatusLabel,
      String filingPeriodLabel,
      @Schema(description = "距下一期申报期开始还有几天；null = 没有周期 / 判不了") Integer daysToNextFiling,
      String effectiveFrom,
      String effectiveTo,
      boolean current,
      List<String> countryCodes,
      List<String> categoryCodes,
      @Schema(description = "距有效期止还有几天；负数 = 已过期；null = 长期有效 / 没记有效期")
          Integer daysToExpiry,
      String note,
      String createdAt,
      String updatedAt) {}

  /** EPR 档案清单（真分页）。 */
  public record EprRegistrationPage(List<EprRegistrationView> items, int total, int page, int size) {}

  /**
   * 一个生效版本（版本历史里的一行）。
   *
   * @param id 版本行 ID
   * @param versionNo 版本号
   * @param registrationNo 注册号
   * @param status 状态
   * @param validityKind 有效期形态
   * @param validFrom 有效期起
   * @param validUntil 有效期止
   * @param filingCycle 申报周期
   * @param effectiveFrom 生效起
   * @param effectiveTo 生效止（空 = 当前版本）
   * @param countryCodes 适用国家
   * @param categoryCodes 适用品类
   * @param note 备注
   * @param createdAt 创建时刻
   */
  public record EprVersionView(
      String id,
      int versionNo,
      String registrationNo,
      String status,
      String validityKind,
      String validFrom,
      String validUntil,
      String filingCycle,
      String effectiveFrom,
      String effectiveTo,
      List<String> countryCodes,
      List<String> categoryCodes,
      String note,
      String createdAt) {}

  /** 版本历史（按版本号倒序）。 */
  public record EprVersionList(String registrationKey, List<EprVersionView> items, String note) {}

  // ---------------------------------------------------------------- 覆盖情况

  /**
   * 一条覆盖结论：**这个国家这个制度**有没有被覆盖。
   *
   * @param countryCode 国家码
   * @param countryName 国家名称
   * @param regimeCode 制度编码
   * @param regimeName 制度名称
   * @param applicable 该国是否要求该制度（false ⇒ NOT_APPLICABLE ⇒ 放行）
   * @param categoryCodes 该国该制度适用的品类
   * @param registrationId 命中的登记版本行 ID（空 = 没有）
   * @param registrationNo 命中的注册号（空 = 没有）
   * @param covered 是否已覆盖（登记在、没过期、品类覆盖）
   * @param reasonCode 判定原因
   * @param reasonLabel 判定原因的人话
   * @param message 人话说明（**界面上直接显示**）
   */
  public record EprCoverageRow(
      String countryCode,
      String countryName,
      String regimeCode,
      String regimeName,
      boolean applicable,
      List<String> categoryCodes,
      String registrationId,
      String registrationNo,
      boolean covered,
      String reasonCode,
      String reasonLabel,
      String message) {}

  /** 覆盖情况清单（真分页 + 汇总计数）。 */
  public record EprCoveragePage(
      List<EprCoverageRow> items,
      int total,
      int page,
      int size,
      long coveredCount,
      long blockedCount,
      long notApplicableCount,
      String note) {}

  // ---------------------------------------------------------------- 合规状态

  /**
   * 一个国家的合规状态。
   *
   * @param countryCode 国家码
   * @param countryName 国家名称
   * @param applicableRegimeCount 该国要求的制度数
   * @param coveredRegimeCount 已覆盖的制度数
   * @param blockedRegimeCount 未覆盖 / 判不了的制度数
   * @param status 状态（COMPLIANT / NON_COMPLIANT / INDETERMINATE / NOT_APPLICABLE）
   * @param statusLabel 状态的人话
   * @param reasons 逐条原因
   */
  public record EprCountryStatus(
      String countryCode,
      String countryName,
      int applicableRegimeCount,
      int coveredRegimeCount,
      int blockedRegimeCount,
      String status,
      String statusLabel,
      List<EprGateReason> reasons) {}

  /** 合规状态汇总（按国家）。 */
  public record EprComplianceStatus(
      List<EprCountryStatus> countries,
      int total,
      long compliantCount,
      long nonCompliantCount,
      long indeterminateCount,
      String note) {}

  // ---------------------------------------------------------------- 刊登闸门

  /**
   * 闸门的入参。
   *
   * @param countryCode 目标国家（ISO 3166-1 alpha-2）
   * @param categoryCode 目标品类（**可空**；空 = 按国家维度判，与 DB 层强制口径一致）
   * @param shopId 关联店铺（可空，只用于定位与展示）
   */
  public record EprPublishGateRequest(String countryCode, String categoryCode, String shopId) {}

  /**
   * 一条阻断 / 放行原因。
   *
   * @param regimeCode 制度编码
   * @param regimeName 制度名称
   * @param countryCode 国家码
   * @param countryName 国家名称
   * @param categoryCode 涉及的品类（空 = 该国该制度的全部适用品类）
   * @param reasonCode 判定原因
   * @param reasonLabel 判定原因的人话
   * @param message 人话说明（**界面上直接显示**）
   * @param blocking 会不会阻断刊登
   */
  public record EprGateReason(
      String regimeCode,
      String regimeName,
      String countryCode,
      String countryName,
      String categoryCode,
      String reasonCode,
      String reasonLabel,
      String message,
      boolean blocking) {}

  /**
   * 一个判定口径下的结论（国家维度 / 品类维度各一份）。
   *
   * @param allowed 是否放行（**只有结论是 ALLOWED 时才为 true**）
   * @param conclusion 结论（ALLOWED / BLOCKED / INDETERMINATE）
   * @param conclusionLabel 结论的人话
   * @param reasons 逐条原因
   */
  public record EprGateScope(
      boolean allowed, String conclusion, String conclusionLabel, List<EprGateReason> reasons) {}

  /**
   * 🔴 刊登闸门的结论（**服务端算出来的**，不是前端拼的）。
   *
   * @param allowed 是否放行（**只有结论是 ALLOWED 时才为 true**）
   * @param conclusion 结论（ALLOWED / BLOCKED / INDETERMINATE）
   * @param conclusionLabel 结论的人话
   * @param countryCode 国家码
   * @param countryName 国家名称
   * @param categoryCode 品类（空 = 按国家维度判）
   * @param reasons 逐条原因
   * @param countryScope 🔴 **国家维度**的结论 —— 这就是 DB 层刊登触发器强制的口径
   *     （刊登入口的平台类目路径无法可靠映射到 EPR 品类 ⇒ DB 层只能按国家维度判）；
   *     品类维度的结论更细，但**不改变** DB 层实际拦不拦。
   * @param evaluatedAt 判定时刻（同源 DB 时钟）
   * @param note 口径说明
   */
  public record EprPublishGate(
      boolean allowed,
      String conclusion,
      String conclusionLabel,
      String countryCode,
      String countryName,
      String categoryCode,
      List<EprGateReason> reasons,
      EprGateScope countryScope,
      String evaluatedAt,
      String note) {}

  // ---------------------------------------------------------------- 申报期

  /**
   * 一条申报期状态。
   *
   * @param registrationId 登记版本行 ID
   * @param registrationKey 逻辑登记 ID
   * @param regimeCode 制度编码
   * @param regimeName 制度名称
   * @param registrationNo 注册号
   * @param filingCycle 申报周期
   * @param filingCycleLabel 申报周期的人话
   * @param filingStartMonth 申报期起始月
   * @param filingEndMonth 申报期结束月
   * @param filingStatus 申报期状态
   * @param filingStatusLabel 申报期状态的人话
   * @param filingPeriodLabel 本期申报期窗口
   * @param daysToNextFiling 距下一期申报期开始还有几天
   * @param countryCodes 适用国家
   * @param categoryCodes 适用品类
   */
  public record EprFilingRow(
      String registrationId,
      String registrationKey,
      String regimeCode,
      String regimeName,
      String registrationNo,
      String filingCycle,
      String filingCycleLabel,
      Integer filingStartMonth,
      Integer filingEndMonth,
      String filingStatus,
      String filingStatusLabel,
      String filingPeriodLabel,
      Integer daysToNextFiling,
      List<String> countryCodes,
      List<String> categoryCodes) {}

  /** 申报期状态清单（真分页）。 */
  public record EprFilingPage(
      List<EprFilingRow> items, int total, int page, int size, long inPeriodCount, String note) {}

  // ---------------------------------------------------------------- 不适用声明

  /**
   * 新建 / 撤销一条「本制度在本国不适用我的业务」声明。
   *
   * @param countryCode 国家码
   * @param regimeCode 制度编码
   * @param reason 理由（**必填**）
   * @param effectiveFrom 生效时刻（**不许早于当下**）
   * @param note 备注
   */
  public record EprExemptionInput(
      String countryCode, String regimeCode, String reason, String effectiveFrom, String note) {}

  /**
   * 一条不适用声明。
   *
   * @param id 声明 ID
   * @param countryCode 国家码
   * @param countryName 国家名称
   * @param regimeCode 制度编码
   * @param regimeName 制度名称
   * @param status 状态（ACTIVE / REVOKED）
   * @param effectiveFrom 生效起
   * @param effectiveTo 生效止（空 = 仍然生效）
   * @param reason 理由
   * @param note 备注
   * @param createdAt 创建时刻
   */
  public record EprExemptionView(
      String id,
      String countryCode,
      String countryName,
      String regimeCode,
      String regimeName,
      String status,
      String effectiveFrom,
      String effectiveTo,
      String reason,
      String note,
      String createdAt) {}

  /** 不适用声明清单（真分页）。 */
  public record EprExemptionPage(
      List<EprExemptionView> items, int total, int page, int size, long activeCount, String note) {}

  // ---------------------------------------------------------------- 费用

  /**
   * 新建 / 修改一笔 EPR 费用的入参。
   *
   * @param registrationKey 逻辑登记 ID
   * @param feeType 费用类型
   * @param amount 金额（字符串形式，**十进制**；**不许浮点**）
   * @param currency 币种（三个大写字母）
   * @param billingCycle 计费周期
   * @param periodStart 归属周期起（ISO-8601 带偏移量）
   * @param periodEnd 归属周期止（ISO-8601 带偏移量）
   * @param status 状态（RECORDED / PAID / WAIVED）
   * @param paidBy 承担方
   * @param note 备注
   */
  public record EprFeeInput(
      String registrationKey,
      String feeType,
      String amount,
      String currency,
      String billingCycle,
      String periodStart,
      String periodEnd,
      String status,
      String paidBy,
      String note) {}

  /**
   * 一笔 EPR 费用。
   *
   * @param id 费用 ID
   * @param registrationKey 逻辑登记 ID
   * @param regimeCode 制度编码
   * @param regimeName 制度名称
   * @param registrationNo 注册号
   * @param feeType 费用类型
   * @param feeTypeLabel 费用类型的人话
   * @param amount 金额（十进制字符串）
   * @param currency 币种
   * @param billingCycle 计费周期
   * @param billingCycleLabel 计费周期的人话
   * @param periodStart 归属周期起
   * @param periodEnd 归属周期止
   * @param status 状态
   * @param statusLabel 状态的人话
   * @param paidBy 承担方
   * @param paidByLabel 承担方的人话
   * @param note 备注
   * @param createdAt 创建时刻
   */
  public record EprFeeView(
      String id,
      String registrationKey,
      String regimeCode,
      String regimeName,
      String registrationNo,
      String feeType,
      String feeTypeLabel,
      String amount,
      String currency,
      String billingCycle,
      String billingCycleLabel,
      String periodStart,
      String periodEnd,
      String status,
      String statusLabel,
      String paidBy,
      String paidByLabel,
      String note,
      String createdAt) {}

  /** 费用清单（真分页）。 */
  public record EprFeePage(List<EprFeeView> items, int total, int page, int size, String note) {}

  /**
   * 按币种分组的合计（🔴 **不同币种绝不直接相加**）。
   *
   * @param currency 币种
   * @param total 该币种下的合计（十进制字符串）
   * @param count 该币种下的笔数
   */
  public record EprFeeTotal(String currency, String total, long count) {}

  /**
   * 费用汇总。
   *
   * @param totals 按币种分组的合计
   * @param totalRecords 总笔数
   * @param currencies 涉及的币种（按币种分组，**不是**一个总数）
   * @param note 口径说明
   */
  public record EprFeeSummary(
      List<EprFeeTotal> totals, long totalRecords, List<String> currencies, String note) {}

  /**
   * 一次批量判定（多个国家一起问「能不能刊登」）。
   *
   * @param requests 待判定的目标清单
   */
  public record EprPublishGateBatchRequest(List<EprPublishGateRequest> requests) {}

  /** 批量刊登闸门结论。 */
  public record EprPublishGateBatch(
      boolean allowed,
      List<EprPublishGate> gates,
      long blockedCount,
      String evaluatedAt,
      String note) {}
}
