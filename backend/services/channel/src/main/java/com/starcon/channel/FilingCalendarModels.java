package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * 16.3.1.2 申报日历的传输模型。
 *
 * <p>蓝图：「管理各辖区申报日历：申报周期、截止日、所需数据、状态（未开始/数据就绪/已提交/已确认），
 * 到期提醒与责任人。输入：申报周期配置；辖区要求；12.6.1.2 数据就绪状态。
 * 输出：申报日历视图 + 提醒 + 状态跟踪。关系：上游 = 16.3.1.1、12.6.1.2；下游 = 1 工作台待办。
 * <b>漏报罚款远高于系统能提供的价值</b>，日历提醒是最低成本的合规保障。」
 *
 * <p>🔴 三个形状各自回答一个不同的问题：
 *
 * <ul>
 *   <li>{@link FilingCalendarCapabilities} —— 「哪些下游量不到、量不到归谁、判定口径是什么」。</li>
 *   <li>{@link FilingCalendarEntryView} —— 「这个辖区这一期的申报义务长什么样」。</li>
 *   <li>{@link FilingCalendarReminderView} —— 「什么时候该提醒谁，提醒了没有」。</li>
 * </ul>
 *
 * <p>🔴 <b>「读不到」与「没有」是两个状态</b>：12.6.1.2 的申报底稿读不到时，
 * {@code dataReadinessAvailable=false} + 原因，<b>不许</b>被显示成「数据没就绪」。
 * 反过来，<b>本模型里没有任何金额字段</b> —— 金额留在 finance（12.6.1.2），
 * 抄一份进来就是两个真值源。
 */
public final class FilingCalendarModels {

  private FilingCalendarModels() {}

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
  public record FilingCalendarCapabilityGap(
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
  public record FilingCalendarUpstream(
      String moduleId, String moduleName, boolean available, String ownerModule, String impact) {}

  /**
   * 能力声明。
   *
   * @param gaps 未交付的下游清单
   * @param upstreams 上游读取状态（辖区码表 / 申报底稿 / 成员目录）
   * @param taxTypes 申报线（税种）词表
   * @param filingCycles 申报周期词表
   * @param statuses 状态词表
   * @param reminderKinds 提醒档位词表
   * @param statusSemantics 状态语义（受控词表 + 单调 + 依据要求）
   * @param jurisdictionSemantics 辖区语义（值域钉死 + 派生边界）
   * @param dataReadinessSemantics 数据就绪语义（**投影**，不是抄一份底稿）
   * @param ownerSemantics 责任人语义（租户内成员，跨库软引用）
   * @param upstreamNote 上游说明
   * @param downstreamNote 下游说明
   * @param scopeNote 申报边界（本模块管什么、不管什么）
   */
  public record FilingCalendarCapabilities(
      List<FilingCalendarCapabilityGap> gaps,
      List<FilingCalendarUpstream> upstreams,
      List<String> taxTypes,
      List<String> filingCycles,
      List<String> statuses,
      List<String> reminderKinds,
      String statusSemantics,
      String jurisdictionSemantics,
      String dataReadinessSemantics,
      String ownerSemantics,
      String upstreamNote,
      String downstreamNote,
      String scopeNote) {}

  // ---------------------------------------------------------------- 选项

  /**
   * 一个销售辖区（受控值域里的一行）。
   *
   * @param code 辖区编码
   * @param name 辖区名称
   * @param region 区域
   * @param taxRegime 税制（VAT / GST / 销售税 / 消费税 / 无）
   * @param defaultFilingCycle 该辖区的默认申报周期（**可为空**：辖区没规定 ≠ 没有周期）
   */
  public record FilingCalendarJurisdiction(
      String code, String name, String region, String taxRegime, String defaultFilingCycle) {}

  /** 辖区选项清单。 */
  public record FilingCalendarJurisdictionList(
      List<FilingCalendarJurisdiction> items, int total, String note) {}

  /**
   * 一个可指派的责任人（租户内成员）。
   *
   * @param membershipId 成员 ID（identity 拥有）
   * @param username 成员名（**读不到时是空串，不是「不存在」**）
   * @param status 成员状态
   */
  public record FilingCalendarMember(String membershipId, String username, String status) {}

  /**
   * 责任人候选清单。
   *
   * @param items 成员
   * @param available 目录读到了吗（false ⇒ 显示「读不到」，**不是**「没有成员」）
   * @param note 读不到 / 读到的原因说明
   */
  public record FilingCalendarMemberList(
      List<FilingCalendarMember> items, boolean available, String note) {}

  // ---------------------------------------------------------------- 申报周期配置

  /**
   * 登记 / 修改一份申报周期配置。
   *
   * @param jurisdictionCode 辖区编码（必须落在受控值域里）
   * @param taxType 申报线（税种）
   * @param filingCycle 申报周期
   * @param periodAnchorDay 期起始日（几号，1..28）
   * @param dueOffsetDays 截止偏移（期末 + N 天）
   * @param dueHour 截止时刻（0..23）
   * @param dueTimezone 截止时刻的时区（IANA 名，如 {@code Europe/Berlin}）
   * @param reminderLeadDays 提前提醒天数（0..180）
   * @param effectiveFrom 从哪天起按这份配置生成申报期
   * @param status 状态（ACTIVE / ARCHIVED；**撤下 = ARCHIVED**，不做物理删除）
   * @param note 备注
   */
  public record FilingCalendarCycleConfigInput(
      String jurisdictionCode,
      String taxType,
      String filingCycle,
      Integer periodAnchorDay,
      Integer dueOffsetDays,
      Integer dueHour,
      String dueTimezone,
      Integer reminderLeadDays,
      String effectiveFrom,
      String status,
      String note) {}

  /**
   * 一份申报周期配置。
   *
   * @param id 配置 ID
   * @param jurisdictionCode 辖区编码
   * @param jurisdictionName 辖区名称
   * @param taxType 申报线（税种）
   * @param filingCycle 申报周期
   * @param periodAnchorDay 期起始日
   * @param dueOffsetDays 截止偏移（天）
   * @param dueHour 截止时刻
   * @param dueTimezone 截止时区
   * @param reminderLeadDays 提前提醒天数
   * @param effectiveFrom 生效起始日
   * @param status 状态（ACTIVE / ARCHIVED）
   * @param note 备注
   * @param createdAt 创建时刻
   * @param updatedAt 更新时刻
   */
  public record FilingCalendarCycleConfigView(
      String id,
      String jurisdictionCode,
      String jurisdictionName,
      String taxType,
      String filingCycle,
      int periodAnchorDay,
      int dueOffsetDays,
      int dueHour,
      String dueTimezone,
      int reminderLeadDays,
      String effectiveFrom,
      String status,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 申报周期配置清单（真分页 + 真筛选）。 */
  public record FilingCalendarCycleConfigPage(
      List<FilingCalendarCycleConfigView> items, int total, int page, int size) {}

  // ---------------------------------------------------------------- 日历条目

  /**
   * 一次申报需要凑齐的数据项（**这个条目自己的口径**，不是全局常量）。
   *
   * @param code 数据项编码
   * @param label 数据项名称
   * @param required 是否必需
   * @param source 数据来自哪（12.6.1.2 底稿 / 16.3.1.1 登记 / 手工录入 …）
   */
  public record FilingCalendarRequiredData(
      String code, String label, boolean required, String source) {}

  /**
   * 登记 / 修改一条申报日历条目。
   *
   * @param jurisdictionCode 辖区编码
   * @param taxType 申报线（税种）
   * @param filingCycle 申报周期
   * @param periodStart 申报期起
   * @param periodEnd 申报期止
   * @param dueAt 截止日（ISO-8601 带偏移量；留空则按周期配置推）
   * @param requiredData 所需数据清单
   * @param responsibleMembershipId 责任人（identity 成员 ID；落库前校验）
   * @param reminderLeadDays 提前提醒天数（留空则按周期配置取）
   * @param note 备注
   */
  public record FilingCalendarEntryInput(
      String jurisdictionCode,
      String taxType,
      String filingCycle,
      String periodStart,
      String periodEnd,
      String dueAt,
      List<FilingCalendarRequiredData> requiredData,
      String responsibleMembershipId,
      Integer reminderLeadDays,
      String note) {}

  /**
   * 一条申报日历条目。
   *
   * @param id 条目 ID
   * @param jurisdictionCode 辖区编码
   * @param jurisdictionName 辖区名称
   * @param taxType 申报线（税种）
   * @param filingCycle 申报周期
   * @param periodStart 申报期起
   * @param periodEnd 申报期止
   * @param dueAt 截止日（**按条目自己的当地时区**显示）
   * @param dueTimezone 截止日的当地时区（IANA 名，如 {@code Europe/Berlin}）
   * @param status 状态（NOT_STARTED / DATA_READY / SUBMITTED / CONFIRMED）
   * @param statusLabel 状态的人话
   * @param statusAdvancedAt 状态推进到当前档的时刻
   * @param requiredData 所需数据清单
   * @param dataReady 数据就绪吗（**投影**：该辖区该周期当前有没有一份生效中的底稿）
   * @param dataReadyDraftId 命中的生效底稿 ID（**逻辑引用**，金额在 finance，不在本表）
   * @param dataReadinessAvailable 上游**读到了**吗（false ⇒ 显示「读不到」，**不是**「没就绪」）
   * @param dataReadinessNote 读不到 / 没就绪的原因说明
   * @param dataReadyCheckedAt 上次核对数据就绪的时刻
   * @param responsibleMembershipId 责任人成员 ID（**逻辑引用**）
   * @param responsibleName 责任人姓名（**读不到时是空串**，不是「没有责任人」）
   * @param responsibleAvailable 责任人读到了吗
   * @param responsibleNote 责任人读不到的原因说明
   * @param reminderLeadDays 提前提醒天数
   * @param daysToDue 距截止还有几天（**负数 = 已逾期**）
   * @param overdue 已逾期吗（**服务端按当前时刻算**，不信任客户端时间）
   * @param submittedAt 提交时刻
   * @param submittedBy 提交人（成员 ID）
   * @param submissionReference 提交凭据号（**用户填的外部凭据**，系统不代造）
   * @param confirmedAt 确认时刻
   * @param confirmedBy 确认人（成员 ID）
   * @param confirmationReference 确认凭据号
   * @param note 备注
   * @param createdAt 创建时刻
   * @param updatedAt 更新时刻
   */
  public record FilingCalendarEntryView(
      String id,
      String jurisdictionCode,
      String jurisdictionName,
      String taxType,
      String filingCycle,
      String periodStart,
      String periodEnd,
      String dueAt,
      String dueTimezone,
      String status,
      String statusLabel,
      String statusAdvancedAt,
      List<FilingCalendarRequiredData> requiredData,
      boolean dataReady,
      String dataReadyDraftId,
      boolean dataReadinessAvailable,
      String dataReadinessNote,
      String dataReadyCheckedAt,
      String responsibleMembershipId,
      String responsibleName,
      boolean responsibleAvailable,
      String responsibleNote,
      int reminderLeadDays,
      @Schema(description = "距截止还有几天；负数 = 已逾期") Integer daysToDue,
      boolean overdue,
      String submittedAt,
      String submittedBy,
      String submissionReference,
      String confirmedAt,
      String confirmedBy,
      String confirmationReference,
      String note,
      String createdAt,
      String updatedAt) {}

  /**
   * 日历清单（真分页 + 真筛选）。
   *
   * @param items 条目
   * @param total 总条数
   * @param page 页码（0 起）
   * @param size 每页条数
   * @param overdueCount 逾期未提交条数（**全量计数，不受分页截断**）
   * @param dueSoonCount 临近截止未提交条数（全量计数）
   * @param notSubmittedCount 未提交条数（全量计数）
   * @param notice 口径 / 截断说明（如实报「只取回前 N 条（共 M 条）」）
   */
  public record FilingCalendarEntryPage(
      List<FilingCalendarEntryView> items,
      int total,
      int page,
      int size,
      long overdueCount,
      long dueSoonCount,
      long notSubmittedCount,
      String notice) {}

  /**
   * 按周期配置批量生成申报期的结果。
   *
   * @param created 新建条数
   * @param skipped 跳过条数（这一期已经有了）
   * @param items 本次涉及的条目
   * @param note 口径说明
   */
  public record FilingCalendarGenerateResult(
      int created, int skipped, List<FilingCalendarEntryView> items, String note) {}

  /**
   * 推进状态的入参。
   *
   * @param targetStatus 目标状态（DATA_READY / SUBMITTED / CONFIRMED）
   * @param reference 凭据号（**推进到已提交 / 已确认时必填**；系统不代造受理号）
   * @param note 备注
   */
  public record FilingCalendarAdvanceInput(String targetStatus, String reference, String note) {}

  // ---------------------------------------------------------------- 提醒

  /**
   * 一条到期提醒。
   *
   * @param id 提醒 ID
   * @param entryId 条目 ID
   * @param jurisdictionCode 辖区编码
   * @param jurisdictionName 辖区名称
   * @param taxType 申报线（税种）
   * @param filingCycle 申报周期
   * @param periodStart 申报期起
   * @param periodEnd 申报期止
   * @param dueAt 截止日（**按条目自己的当地时区**显示）
   * @param dueTimezone 截止日的当地时区（IANA 名）
   * @param kind 提醒档位（DUE_SOON / DUE）
   * @param kindLabel 档位的人话
   * @param remindAt 提醒时刻
   * @param acknowledged 已确认已知悉吗
   * @param acknowledgedAt 确认时刻
   * @param acknowledgedBy 确认人（成员 ID）
   * @param status 状态（NOT_STARTED / DATA_READY / SUBMITTED / CONFIRMED）
   * @param statusLabel 状态的人话
   * @param responsibleMembershipId 责任人成员 ID
   * @param responsibleName 责任人姓名（**读不到时是空串**）
   * @param responsibleAvailable 责任人读到了吗
   * @param responsibleNote 责任人读不到的原因说明
   */
  public record FilingCalendarReminderView(
      String id,
      String entryId,
      String jurisdictionCode,
      String jurisdictionName,
      String taxType,
      String filingCycle,
      String periodStart,
      String periodEnd,
      String dueAt,
      String dueTimezone,
      String kind,
      String kindLabel,
      String remindAt,
      boolean acknowledged,
      String acknowledgedAt,
      String acknowledgedBy,
      String status,
      String statusLabel,
      String responsibleMembershipId,
      String responsibleName,
      boolean responsibleAvailable,
      String responsibleNote) {}

  /**
   * 到期提醒清单（真分页 + 真筛选）。
   *
   * @param items 提醒
   * @param total 总条数
   * @param page 页码（0 起）
   * @param size 每页条数
   * @param unacknowledgedCount 未确认条数（**全量计数，不受分页截断**）
   * @param notice 口径 / 截断说明（如实报「只取回前 N 条（共 M 条）」）
   */
  public record FilingCalendarReminderPage(
      List<FilingCalendarReminderView> items,
      int total,
      int page,
      int size,
      long unacknowledgedCount,
      String notice) {}

  /**
   * 责任人候选与辖区选项合并后的筛选项。
   *
   * @param jurisdictions 辖区选项
   * @param members 责任人候选
   * @param taxTypes 申报线词表
   * @param filingCycles 申报周期词表
   * @param statuses 状态词表
   * @param reminderKinds 提醒档位词表
   * @param note 口径说明
   */
  public record FilingCalendarOptions(
      List<FilingCalendarJurisdiction> jurisdictions,
      FilingCalendarMemberList members,
      List<String> taxTypes,
      List<String> filingCycles,
      List<String> statuses,
      List<String> reminderKinds,
      Map<String, String> statusLabels,
      String note) {}
}
