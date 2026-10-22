package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 15.5.1.1 自动回复的传输模型。
 *
 * <p>蓝图 15 卷 15.5.1.1 原话：
 *
 * <blockquote>
 * 职责：配置消息自动回复：按触发条件（收到消息分类、关键词、无人工介入超时）自动发送回复，支持节假日无人值守、分级自动回复（先自动引导再转人工）。
 * 输入：自动回复规则；模板内容；触发条件。
 * 输出：自动回复记录 + 转人工标记。
 * 关系：上游 = 15.1.1.3、15.4.2；下游 = 15.1.1.2 发送。 <b>自动回复必须可一键关闭并留出人工接管</b>，很多平台对纯自动回复有明确限制。
 * </blockquote>
 *
 * <p><b>本文件最重要的五件事</b>：
 *
 * <ol>
 *   <li><b>触发条件三选一</b>（{@link CrmAutoReplyTriggerType}）：
 *       分类命中 / 关键词命中 / 无人工介入超时。互斥 —— 同时写两种，命中口径就没法复现。</li>
 *   <li><b>分级自动回复的阶梯语义显式落在字段上</b>：
 *       {@code ladderCode} + {@code level} + {@code escalateToHuman} +
 *       {@code levelWaitMinutes}（「先自动引导 → 仍无人工介入 → 再发一级 → 转人工」）。</li>
 *   <li><b>🔴 一键关闭是一等状态</b>（{@link CrmAutoReplySettingRow}）：
 *       全局开关 + 关闭原因 + 操作人 + 关闭时刻，<b>关闭是即时生效</b>。</li>
 *   <li><b>🔴 值班日历是配置不是常量</b>（{@link CrmAutoReplyDutyCalendarRow}）：
 *       节假日 / 非营业时段要不要自动回复，<b>租户可配</b>。</li>
 *   <li><b>输出是两件</b>：{@link CrmAutoReplyRecordRow}（自动回复记录，只追加）
 *       与 {@link CrmAutoReplyEscalationRow}（转人工标记，只追加），
 *       再加 {@link CrmAutoReplyTakeoverRow}（人工接管标记，只追加）。</li>
 * </ol>
 *
 * <p><b>命名</b>：统一用 {@code CrmAutoReply*} 前缀。全仓 grep 确认与既有类型零重叠 ——
 * 契约被 codegen 合并进扁平文件、同名 key 后者覆盖前者且不报冲突，
 * 故不按前缀扫而是逐个名字精确比对。
 */
public final class AutoReplyModels {

  private AutoReplyModels() {}

  // ================================================================ 枚举

  /**
   * 触发条件 —— 蓝图职责句点名的三种，<b>互斥</b>。
   *
   * <p>🔴 {@link #HUMAN_TIMEOUT}（无人工介入超时）的「人工介入」= 15.1.1.2 里一条
   * <b>不是本模块产生</b>的 SENT 发送记录；15.4.1.2 工单处理<b>已交付</b>（同服务同库，
   * {@code crm_service_ticket_action} 是工单侧介入动作的事实表），但<b>本模块当前仍未把
   * 工单侧介入动作纳入豁免判定</b> ⇒ 工单侧的介入动作仍读不到。
   */
  public enum CrmAutoReplyTriggerType {
    CLASSIFICATION("收到消息分类命中"),
    KEYWORD("关键词命中"),
    HUMAN_TIMEOUT("无人工介入超时");

    private final String label;

    CrmAutoReplyTriggerType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 自动回复记录的结果。
   *
   * <p>🔴 {@link #SUPPRESSED}（被闸门挡下）**不是**失败：它表示「本该自动回，
   * 但被某道闸挡住了」，原因逐条写明（全局关闭 / 人工接管 / 值班闸 / 上游不允许自动回复）。
   * 闸门挡下本身也是要留痕的事实。
   */
  public enum CrmAutoReplyOutcome {
    SEND_REGISTERED("已登记发送"),
    SUPPRESSED("被闸门挡下（未发送）"),
    CANCELLED("已撤销（不得再发）");

    private final String label;

    CrmAutoReplyOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 值班安排：无人值守 / 有人值班 / 休息。 */
  public enum CrmAutoReplyDutyMode {
    UNATTENDED("无人值守"),
    ATTENDED("有人值班"),
    CLOSED("休息");

    private final String label;

    CrmAutoReplyDutyMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 值班日历的生效范围。 */
  public enum CrmAutoReplyDutyScope {
    GLOBAL("整租户"),
    PLATFORM("按平台"),
    RULE("按规则");

    private final String label;

    CrmAutoReplyDutyScope(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 转人工标记的原因码。
   *
   * <p>🔴 词表**只有四档**，正好对应四个真实来源：
   * 阶梯走完 / 值班闸挡下 / 发送登记失败 / 人工手动标记。
   */
  public enum CrmAutoReplyEscalationReason {
    LADDER_EXHAUSTED("阶梯走完"),
    DUTY_GATE("值班闸挡下"),
    SEND_FAILED("发送登记失败"),
    MANUAL_ESCALATION("人工手动标记");

    private final String label;

    CrmAutoReplyEscalationReason(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 新建 / 修改一条自动回复规则。
   *
   * <p>🔴 {@code triggerType} 决定另外两个必填项：
   * {@code CLASSIFICATION} ⇒ {@code classificationLabels} 非空；
   * {@code KEYWORD} ⇒ {@code keywords} 非空；
   * {@code HUMAN_TIMEOUT} ⇒ {@code humanTimeoutMinutes > 0}。
   *
   * <p>🔴 {@code level > 1} ⇒ {@code levelWaitMinutes > 0}
   * （间隔为 0 会让一次触发把整条阶梯一次走完）。
   */
  public record CrmAutoReplyRuleInput(
      @NotBlank @Size(max = 64) String ruleCode,
      @NotBlank @Size(max = 200) String ruleName,
      @NotBlank @Size(max = 64)
          @Schema(description = "阶梯标识：同一阶梯的多级规则共用它") String ladderCode,
      @Schema(nullable = true) Integer level,
      @Schema(nullable = true) Boolean escalateToHuman,
      @Schema(nullable = true) Boolean enabled,
      @NotBlank
          @Pattern(regexp = "CLASSIFICATION|KEYWORD|HUMAN_TIMEOUT")
          String triggerType,
      @Schema(nullable = true) String classificationLabels,
      @Schema(nullable = true) String keywords,
      @Schema(nullable = true) @Pattern(regexp = "ANY|ALL") String matchMode,
      @Schema(nullable = true) String platformCodes,
      @Schema(nullable = true) Integer humanTimeoutMinutes,
      @Schema(nullable = true) Integer levelWaitMinutes,
      @Schema(nullable = true) Boolean unattendedAutoReplyAllowed,
      @Schema(nullable = true) UUID templateId,
      @Schema(nullable = true) UUID knowledgeVersionId,
      @Schema(nullable = true) @Size(max = 500) String subject,
      @Schema(nullable = true) String body,
      @Schema(nullable = true) @Size(max = 2000) String note) {}

  /**
   * 一键开关（🔴 加粗句「必须可一键关闭」）。
   *
   * <p>🔴 {@code enabled=false} 时 {@code reason} <b>必填</b>：
   * 没有原因的一键关闭，事后没人知道当时为什么关、该什么时候开回来。
   */
  public record CrmAutoReplySettingToggleInput(
      @NotNull Boolean enabled,
      @Schema(nullable = true) @Size(max = 500) String reason) {}

  /**
   * 新建一条值班日历（节假日 / 非营业时段安排）。
   *
   * <p>🔴 {@code scope=PLATFORM} ⇒ {@code platformCode} 必填；
   * {@code scope=RULE} ⇒ {@code ruleId} 必填。
   */
  public record CrmAutoReplyDutyCalendarInput(
      @NotBlank @Pattern(regexp = "GLOBAL|PLATFORM|RULE") String scope,
      @Schema(nullable = true) @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String platformCode,
      @Schema(nullable = true) UUID ruleId,
      @NotBlank @Schema(description = "生效日期（当地日期），ISO 形如 2026-10-23") String calendarDate,
      @NotBlank @Pattern(regexp = "UNATTENDED|ATTENDED|CLOSED") String dutyMode,
      @Schema(nullable = true) @Size(max = 5) String dutyStart,
      @Schema(nullable = true) @Size(max = 5) String dutyEnd,
      @Schema(nullable = true) Boolean autoReplyAllowed,
      @Schema(nullable = true) @Size(max = 64) String timezone,
      @Schema(nullable = true) @Size(max = 100) String holidayName,
      @Schema(nullable = true) @Size(max = 2000) String note) {}

  /**
   * 触发一次自动回复评估。
   *
   * <p>⚠️ 平台消息同步（15.1.1.1 的 PLATFORM_SYNC）**未接入** ⇒ 本模块的触发入口
   * 由调用方**显式**调用；平台 webhook 适配器接入后由它调用<b>同一个</b>入口。
   *
   * @param messageId 要评估的消息（15.1.1.1 的 crm_message.id）
   * @param at 评估时刻（空 = 现在）。留这个口子是为了「按某个时刻回看是否该回」可复核。
   */
  public record CrmAutoReplyTriggerInput(
      @NotNull UUID messageId, @Schema(nullable = true) String at) {}

  /**
   * 人工手动标记转人工（蓝图输出句里的「转人工标记」的人工入口）。
   *
   * <p>🔴 {@code reason} <b>必填</b>。
   */
  public record CrmAutoReplyEscalateInput(
      @NotBlank @Size(max = 1000) String reason,
      @Schema(nullable = true) @Size(max = 2000) String note) {}

  /**
   * 标记人工接管（🔴 加粗句「留出人工接管」）。
   *
   * <p>🔴 {@code reason} <b>必填</b>：没有理由的接管，事后没人知道客服为什么不再让系统回。
   */
  public record CrmAutoReplyTakeoverInput(
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) UUID messageId,
      @Schema(nullable = true) UUID recordId,
      @Schema(nullable = true) UUID escalationId,
      @NotBlank @Size(max = 1000) String reason,
      @Schema(nullable = true) String takenOverAt,
      @Schema(nullable = true) @Size(max = 2000) String note) {}

  // ================================================================ 出参

  /**
   * 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>字段名与兄弟模块一致：{@code dimension / description / available / source /
   * ownerModule / impact}。
   */
  public record CrmAutoReplyCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /**
   * 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。
   *
   * @param globalKillSwitch 明确声明「一键关闭是即时生效」的语义
   * @param platformAutoReplyPolicy 🔴 平台对纯自动回复的限制 —— 本仓拿不到 ⇒ available=false
   * @param triggerTypes 三种触发条件
   * @param conversationScope 说明「会话」在本模块的定义（上游没有会话实体）
   */
  public record CrmAutoReplyCapabilities(
      List<CrmAutoReplyCapabilityDimension> dimensions,
      List<String> triggerTypes,
      List<String> outcomes,
      List<String> dutyModes,
      List<String> dutyScopes,
      List<String> escalationReasons,
      List<String> classificationLabels,
      String globalKillSwitch,
      String platformAutoReplyPolicy,
      String conversationScope,
      String humanInterventionBasis,
      String actionPolicy,
      String boundaryNote,
      String highlight) {}

  /**
   * 全局开关（每租户一行）。
   *
   * @param autoReplyEnabled false = 本租户现在一条自动回复都不发
   * @param closedReason 关闭原因（关闭时必填）
   * @param pendingCancelledCount 因本次关闭而撤销的待发条数
   */
  public record CrmAutoReplySettingRow(
      boolean autoReplyEnabled,
      String closedReason,
      @Schema(nullable = true) String closedAt,
      @Schema(nullable = true) UUID closedBy,
      int pendingCancelledCount,
      String updatedAt,
      String note) {}

  /**
   * 一条自动回复规则。
   *
   * @param level 阶梯第几级 · ladderCode 阶梯标识
   * @param escalateToHuman 本级处理完是否转人工
   * @param humanTimeoutMinutes 「无人工介入超时」口径（分钟）
   * @param levelWaitMinutes 本级最短间隔（分钟，距上一级自动回复）
   * @param unattendedAutoReplyAllowed 没有任何排班记录时的默认口径
   */
  public record CrmAutoReplyRuleRow(
      UUID id,
      String ruleCode,
      String ruleName,
      String ladderCode,
      int level,
      boolean escalateToHuman,
      boolean enabled,
      String triggerType,
      String triggerTypeLabel,
      String classificationLabels,
      String keywords,
      String matchMode,
      String platformCodes,
      int humanTimeoutMinutes,
      int levelWaitMinutes,
      boolean unattendedAutoReplyAllowed,
      @Schema(nullable = true) UUID templateId,
      String templateCode,
      @Schema(nullable = true) UUID knowledgeVersionId,
      String subject,
      String body,
      String note,
      @Schema(nullable = true) UUID createdBy,
      String createdAt,
      String updatedAt) {}

  /** 自动回复规则分页（真分页：总数与页数都从真实查询来）。 */
  public record CrmAutoReplyRulePage(
      List<CrmAutoReplyRuleRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 一条值班日历。
   *
   * @param autoReplyAllowed 🔴 此时段是否允许自动回复（租户可配，不是写死常量）
   */
  public record CrmAutoReplyDutyCalendarRow(
      UUID id,
      String scope,
      String scopeLabel,
      @Schema(nullable = true) String platformCode,
      @Schema(nullable = true) UUID ruleId,
      @Schema(nullable = true) String ruleCode,
      String calendarDate,
      String dutyMode,
      String dutyModeLabel,
      @Schema(nullable = true) String dutyStart,
      @Schema(nullable = true) String dutyEnd,
      boolean autoReplyAllowed,
      String timezone,
      String holidayName,
      String note,
      @Schema(nullable = true) UUID createdBy,
      String createdAt,
      String updatedAt) {}

  /** 值班日历分页（真分页）。 */
  public record CrmAutoReplyDutyCalendarPage(
      List<CrmAutoReplyDutyCalendarRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 一条自动回复记录（= 蓝图输出句里的「自动回复记录」）。
   *
   * @param outcome 结果 · reason 未发送原因 / 撤销原因（人话）
   * @param sendStatus 由 15.1.1.2 的发送记录**推导**出来的实际发送结果
   * @param templateBodySnapshot / bodySnapshot 模板版本快照（改规则不追溯改变历史记录）
   */
  public record CrmAutoReplyRecordRow(
      UUID id,
      String recordNo,
      @Schema(nullable = true) UUID ruleId,
      String ruleCode,
      String ruleName,
      String ladderCode,
      int level,
      String triggerType,
      String triggerTypeLabel,
      String triggerReason,
      UUID messageId,
      String messageNo,
      UUID shopId,
      String shopName,
      String platformCode,
      String buyerReference,
      String orderReference,
      @Schema(nullable = true) UUID classificationId,
      String classificationLabel,
      @Schema(nullable = true) UUID templateId,
      String templateCode,
      String templateBodySnapshot,
      @Schema(nullable = true) UUID knowledgeVersionId,
      int knowledgeVersionNo,
      String bodySnapshot,
      String outcome,
      String outcomeLabel,
      String reason,
      @Schema(nullable = true) UUID sendId,
      String sendNo,
      String sendStatus,
      String sendFailureReason,
      @Schema(nullable = true) UUID originRecordId,
      boolean escalated,
      String occurredAt,
      String createdAt) {}

  /** 自动回复记录分页（真分页）。 */
  public record CrmAutoReplyRecordPage(
      List<CrmAutoReplyRecordRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 一条转人工标记（= 蓝图输出句里的「转人工标记」）。
   *
   * @param takenOver 是否已被人工接管（由接管表推导，不写在转人工标记上 —— 它只追加）
   */
  public record CrmAutoReplyEscalationRow(
      UUID id,
      UUID messageId,
      String messageNo,
      UUID shopId,
      String shopName,
      String platformCode,
      String buyerReference,
      String orderReference,
      @Schema(nullable = true) UUID recordId,
      @Schema(nullable = true) UUID ruleId,
      String ruleCode,
      int level,
      String reasonCode,
      String reasonCodeLabel,
      String reason,
      @Schema(nullable = true) UUID escalatedBy,
      String escalatedAt,
      boolean takenOver,
      @Schema(nullable = true) UUID takeoverId,
      String createdAt) {}

  /** 转人工标记分页（真分页）。 */
  public record CrmAutoReplyEscalationPage(
      List<CrmAutoReplyEscalationRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 一条人工接管标记。
   *
   * @param conversationKey 会话标识（shop_id + buyer_reference）—— 本模块自己的定义
   */
  public record CrmAutoReplyTakeoverRow(
      UUID id,
      UUID shopId,
      String shopName,
      String platformCode,
      String buyerReference,
      String conversationKey,
      @Schema(nullable = true) UUID messageId,
      @Schema(nullable = true) UUID recordId,
      @Schema(nullable = true) UUID escalationId,
      UUID takenOverBy,
      String takenOverAt,
      String reason,
      String createdAt) {}

  /** 人工接管标记分页（真分页）。 */
  public record CrmAutoReplyTakeoverPage(
      List<CrmAutoReplyTakeoverRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 一次触发评估的**逐条**结论。
   *
   * @param ruleCode 命中的规则 · fired 是否真的发了 · reason 没发的原因（人话）
   */
  public record CrmAutoReplyTriggeredItem(
      UUID ruleId,
      String ruleCode,
      String ruleName,
      String ladderCode,
      int level,
      String triggerType,
      String triggerTypeLabel,
      boolean matched,
      boolean fired,
      String outcome,
      String outcomeLabel,
      String reason,
      @Schema(nullable = true) UUID recordId,
      @Schema(nullable = true) String recordNo,
      @Schema(nullable = true) UUID sendId,
      @Schema(nullable = true) String sendNo,
      boolean escalated,
      @Schema(nullable = true) UUID escalationId) {}

  /**
   * 一次触发评估的汇总。
   *
   * @param globalClosed true = 全局自动回复已关闭，本次**没有**评估也没有发送任何东西
   * @param closedReason 全局关闭原因（关闭时非空）
   * @param conversationTakenOver true = 该会话已被人工接管 ⇒ 本次不再自动回复
   * @param classificationLabel 上游 15.1.1.3 的分类标签（未分类时为空）
   */
  public record CrmAutoReplyTriggerResult(
      UUID messageId,
      String messageNo,
      boolean evaluated,
      boolean globalClosed,
      String closedReason,
      boolean conversationTakenOver,
      String classificationLabel,
      boolean classificationAutoReplyEligible,
      String evaluatedAt,
      int matchedCount,
      int firedCount,
      int suppressedCount,
      int escalatedCount,
      List<CrmAutoReplyTriggeredItem> items,
      String note) {}

  /** 一档统计（状态 / 触发条件分组）。 */
  public record CrmAutoReplyBucket(String code, String label, long count) {}

  /**
   * 统计汇总 —— <b>全部由服务端算</b>。
   *
   * <p>🔴 前端<b>不重算、不做算术</b>：这里给什么页面就显示什么。
   *
   * @param byOutcome 按记录结果分组（code + 服务端给的中文标签 + 条数）
   * @param byTriggerType 按触发条件分组
   */
  public record CrmAutoReplyStats(
      long totalRecords,
      long sendRegistered,
      long suppressed,
      long escalated,
      long cancelled,
      long escalations,
      long escalationsTakenOver,
      long takeovers,
      long recordsByLevelOne,
      long recordsByLevelLater,
      List<CrmAutoReplyBucket> byOutcome,
      List<CrmAutoReplyBucket> byTriggerType,
      String note) {}
}
