package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.4.1.2 证据与申诉的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 原文：
 *
 * <blockquote>
 * 职责：组织举证材料（物流签收、沟通记录、商品照片、检测报告）打包提交申诉，记录平台回复与结果。
 * 输入：举证材料；申诉文本（可用模板）；提交动作。
 * 输出：申诉提交记录 + 平台回复 + 结果（胜诉/败诉/部分）。
 * 关系：上游 = 11.4.1.1；下游 = 2 平台接口、16 合规（账号绩效）。
 * <b>举证材料必须来自系统记录</b>（轨迹、沟通日志），临时拼凑的证据在申诉中往往不被采信。
 * </blockquote>
 *
 * <p><b>本文件最重要的四件事</b>：
 *
 * <ol>
 *   <li><b>时限判定只有一个判据</b>：{@link PlatformAppealGateView} 的每一个字段都来自
 *       DB 函数 {@code platform_dispute_appeal_gate(...)} ——
 *       <b>服务端与 DB 触发器调的是同一个函数</b>（照抄 6.3.1.3 的做法）。
 *       服务层**不自己写一遍</b> {@code evidence_deadline <= now()}，
 *       否则规则改一次就与服务层漂移。</li>
 *   <li><b>举证材料只能来自系统记录</b>：{@link PlatformAppealMaterialRow} 带
 *       {@code sourceModule} + {@code sourceRefId}（逻辑引用）与
 *       {@code sourceRefNo} / {@code sourceLabel} / {@code sourceLocator} /
 *       {@code sourceOccurredAt}（快照元数据）——
 *       <b>不复制文件本体、不建跨库外键</b>（照抄 7.4.1.1 的附件做法）。</li>
 *   <li><b>「提交申诉」不假装能调平台</b>：channel 服务**没有**纠纷 / 申诉提交端点
 *       （grep 取证见 V269 迁移第一节）⇒ 本模块只落
 *       {@link PlatformAppealSubmissionRow}（待提交平台事实：目标系统 + 目标键 + 载荷 +
 *       幂等键 + 状态机 + 重试计数），平台侧经**授权 API** 回写平台回复与结果。</li>
 *   <li><b>结果有「部分」这一档</b>：{@link PlatformAppealStatus#RESOLVED_PARTIAL} ——
 *       11.4.1.1 的结果码域里**没有**「部分」，所以它只落在申诉层，
 *       金额口径走 11.4.1.1 的结算分录。</li>
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code PlatformAppeal*} 前缀，与 11.4.1.1 的
 * {@code PlatformDispute*} 零重叠 —— 契约被 codegen 合并进一个扁平文件、
 * 同名后者覆盖前者且不报冲突。
 */
public final class PlatformDisputeAppealModels {

  private PlatformDisputeAppealModels() {}

  // ================================================================ 枚举

  /**
   * 申诉单状态机。
   *
   * <p>⚠️ <b>四个终态不可逆</b>：{@link #RESOLVED_WON} / {@link #RESOLVED_LOST} /
   * {@link #RESOLVED_PARTIAL} / {@link #WITHDRAWN} 一旦落定，业务要素整行冻结
   * （V269 守卫③）。申诉是要拿去跟平台对质的凭据，
   * 「我们当时交了什么、平台回了什么」必须只有一个答案。
   */
  public enum PlatformAppealStatus {
    DRAFT("草稿"),
    SUBMITTED("已提交平台"),
    PLATFORM_REPLIED("已收到平台回复"),
    RESOLVED_WON("我方胜诉"),
    RESOLVED_LOST("我方败诉"),
    RESOLVED_PARTIAL("平台部分支持"),
    WITHDRAWN("我方撤诉");

    private final String label;

    PlatformAppealStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：不可再流转、不可再改业务要素。 */
    public boolean terminal() {
      return this == RESOLVED_WON
          || this == RESOLVED_LOST
          || this == RESOLVED_PARTIAL
          || this == WITHDRAWN;
    }
  }

  /**
   * 申诉层的结果码。
   *
   * <p>⚠️ **刻意不含「超期自动判负」**（11.4.1.1 的 {@code DEADLINE_EXPIRED}）：
   * 那是系统按超期做的判定，不接受人工声明。超期的申诉根本**提交不出去**
   * （V269 守卫①），所以这一档在申诉层没有对应物。
   */
  public enum PlatformAppealResolution {
    PLATFORM_RULING_FOR("平台判我方胜诉"),
    PLATFORM_RULING_AGAINST("平台判我方败诉"),
    PARTIAL_RULING("平台部分支持"),
    MUTUAL_AGREEMENT("双方达成一致"),
    WITHDRAWN_BY_US("我方撤诉");

    private final String label;

    PlatformAppealResolution(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 平台回复里带的**结果档位**（蓝图输出句「胜诉/败诉/部分」）。
   *
   * <p>这是**平台侧回写**时给的口径；服务层把它映射成
   * {@link PlatformAppealStatus} + {@link PlatformAppealResolution}。
   */
  public enum PlatformAppealResult {
    WON("胜诉"),
    LOST("败诉"),
    PARTIAL("部分支持");

    private final String label;

    PlatformAppealResult(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 待提交平台事实的状态机。
   *
   * <p>⚠️ <b>SENT（本服务已发出）与 ACKNOWLEDGED（平台侧已受理）是两个状态，
   * 不能合并</b>：「发过去了」和「对方收下了」不是一回事
   * （照抄 9.6.1.3 的同一条纪律）。
   */
  public enum PlatformAppealSubmissionStatus {
    PENDING("待提交"),
    SENT("已发出"),
    ACKNOWLEDGED("平台已受理"),
    FAILED("提交失败");

    private final String label;

    PlatformAppealSubmissionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 提交尝试的结果档位。
   *
   * <p>⚠️ <b>SENT 与 ACKNOWLEDGED 是两件事</b>：「发过去了」和「对方收下了」不是一回事
   * （照抄 9.6.1.3 的同一条纪律）。
   */
  public enum PlatformAppealSubmissionOutcome {
    SENT("已发出"),
    FAILED("提交失败"),
    ACKNOWLEDGED("平台已受理");

    private final String label;

    PlatformAppealSubmissionOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 举证材料的**来源系统记录**（蓝图加粗句「举证材料必须来自系统记录」）。
   *
   * <p>每个取值对应 trade 自己库里的一张事实表（V269 第三节）：
   *
   * <ul>
   *   <li>{@code SHIPMENT_TRACKING_OBSERVATION} → {@code shipment_record_tracking_observation}
   *       —— 物流签收 / 物流轨迹</li>
   *   <li>{@code AFTERSALES_MESSAGE} → {@code aftersales_message} —— 沟通记录</li>
   *   <li>{@code AFTERSALES_ATTACHMENT} → {@code aftersales_attachment}
   *       —— 商品照片 / 其它凭证</li>
   *   <li>{@code QUALITY_INSPECTION_ORDER} → {@code quality_inspection_order} —— 检测报告</li>
   * </ul>
   *
   * <p>⚠️ **没有「随便上传一个文件」这一档** —— 临时拼凑的证据在申诉中往往不被采信。
   */
  public enum PlatformAppealSourceModule {
    SHIPMENT_TRACKING_OBSERVATION("物流轨迹观测"),
    AFTERSALES_MESSAGE("售后沟通记录"),
    AFTERSALES_ATTACHMENT("售后单附件"),
    QUALITY_INSPECTION_ORDER("质检单");

    private final String label;

    PlatformAppealSourceModule(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 提交窗口的派生状态（照抄 11.4.1.1 的 {@code PlatformDeadlineState} 的形状，
   * 但**判据在 DB 函数里**，不在服务层）。
   *
   * <ul>
   *   <li>{@code OPEN} —— 窗口开着，可以提交申诉</li>
   *   <li>{@code EXPIRED} —— 已过举证时限（或已被系统判负）</li>
   *   <li>{@code RELEASED} —— 举证已提交，时限解除</li>
   *   <li>{@code CLOSED} —— 纠纷已在其它终态（胜诉 / 撤诉）</li>
   *   <li>{@code UNKNOWN} —— 纠纷单不存在</li>
   * </ul>
   */
  public enum PlatformAppealWindowState {
    OPEN("窗口开着"),
    EXPIRED("举证时限已过"),
    RELEASED("举证已提交，时限解除"),
    CLOSED("纠纷已终结"),
    UNKNOWN("纠纷单不存在");

    private final String label;

    PlatformAppealWindowState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 建申诉单（草稿）。
   *
   * <p>⚠️ 主张金额**不在入参里**：它由服务端从纠纷单上读（快照），
   * 不信客户端 —— 客户端传一个数进来就是「我们自己改了自己主张的金额」。
   */
  public record PlatformAppealCreate(
      UUID disputeId,
      String appealText,
      @Schema(nullable = true) String templateCode) {}

  /** 改申诉文本（草稿态；提交给平台的那一份是快照）。 */
  public record PlatformAppealTextUpdate(
      String appealText,
      @Schema(nullable = true) String templateCode,
      Long expectedVersion) {}

  /**
   * 挂一份证据材料。
   *
   * <p>⚠️ <b>没有「上传文件」字段</b>：材料必须指向系统里已有的一条事实记录
   * （{@code sourceModule} + {@code sourceRefId}）。
   * ⚠️ <b>没有「证据类型」字段</b>：类型由来源记录**推导**
   * （附件按 {@code evidence_kind} 映射、消息是沟通记录、轨迹是物流签收…）。
   */
  public record PlatformAppealMaterialCreate(
      String sourceModule,
      UUID sourceRefId,
      @Schema(nullable = true) UUID evidenceItemId,
      @Schema(nullable = true) String materialNote) {}

  /** 提交申诉（草稿 → 已提交平台）。 */
  public record PlatformAppealSubmit(
      @Schema(nullable = true) String submitNote, Long expectedVersion) {}

  /**
   * 收回平台回复 + 结果（**授权 API**：平台侧 / channel 适配器回写）。
   *
   * <p>⚠️ 「部分」这一档**必须**给 {@code awardedAmount}（平台实际支持的部分金额），
   * 且必须**小于**主张金额 —— 全支持是胜诉、全不支持是败诉。
   */
  public record PlatformAppealPlatformReply(
      String platformReplyText,
      @Schema(nullable = true) String platformReplyReference,
      @Schema(nullable = true) String result,
      @Schema(nullable = true) BigDecimal awardedAmount,
      @Schema(nullable = true) String awardedCurrency,
      @Schema(nullable = true) String resolutionNote,
      Long expectedVersion) {}

  /** 我方撤诉。必须写理由。 */
  public record PlatformAppealWithdraw(String withdrawReason, Long expectedVersion) {}

  /** 重试提交到平台（把上一条尝试标成失败并另起一次）。 */
  public record PlatformAppealRetrySubmission(@Schema(nullable = true) String lastError) {}

  /**
   * 记录一次提交尝试的结果。
   *
   * <p>⚠️ **channel 侧没有申诉适配器** ⇒ 这个端点当前由**平台侧经授权 API** 或
   * **人工**（在 Amazon 后台手工递了申诉时）调用，把「发出去了 / 失败了 / 平台收下了」
   * 如实记下来。
   */
  public record PlatformAppealSubmissionAttempt(
      String outcome,
      @Schema(nullable = true) String errorMessage,
      @Schema(nullable = true) OffsetDateTime nextRetryAt) {}

  /** 列表查询。真分页 + 多维筛选 + 排序白名单。 */
  public record PlatformAppealListQuery(
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String disputeId,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String resolutionCode,
      @Schema(nullable = true) String submissionStatus,
      @Schema(nullable = true) Boolean submittableOnly,
      @Schema(nullable = true) OffsetDateTime from,
      @Schema(nullable = true) OffsetDateTime to,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort) {}

  // ================================================================ 出参

  /**
   * 提交窗口的判据结果。
   *
   * <p>⚠️ <b>每个字段都来自 DB 函数 {@code platform_dispute_appeal_gate(...)}</b> ——
   * 服务层与 DB 触发器调的是**同一个函数**，两侧不可能漂移。
   */
  public record PlatformAppealGateView(
      String windowState,
      String windowStateLabel,
      boolean submittable,
      String disputeStatus,
      @Schema(nullable = true) OffsetDateTime evidenceDeadline,
      @Schema(nullable = true) OffsetDateTime evidenceSubmittedAt,
      long checklistItemCount,
      long missingRequiredCount,
      long coveredItemCount,
      long uncoveredRequiredCount) {}

  /** 申诉单行（列表用）。 */
  public record PlatformAppealRow(
      UUID id,
      String appealNo,
      UUID disputeId,
      String disputeNo,
      String platformCode,
      String platformDisputeNo,
      String appealStatus,
      String appealStatusLabel,
      @Schema(nullable = true) String templateCode,
      String claimedCurrency,
      BigDecimal claimedAmount,
      @Schema(nullable = true) BigDecimal awardedAmount,
      @Schema(nullable = true) String awardedCurrency,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      @Schema(nullable = true) OffsetDateTime platformReplyAt,
      @Schema(nullable = true) String resolutionCode,
      @Schema(nullable = true) String resolutionCodeLabel,
      @Schema(nullable = true) OffsetDateTime resolvedAt,
      long materialCount,
      long submissionCount,
      String windowState,
      String windowStateLabel,
      boolean submittable,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  /** 证据材料行（**逻辑引用 + 快照元数据**，不含文件本体）。 */
  public record PlatformAppealMaterialRow(
      UUID id,
      int materialSeq,
      String evidenceType,
      String evidenceTypeLabel,
      @Schema(nullable = true) UUID evidenceItemId,
      String sourceModule,
      String sourceModuleLabel,
      UUID sourceRefId,
      String sourceRefNo,
      String sourceLabel,
      String sourceLocator,
      OffsetDateTime sourceOccurredAt,
      String sourceKind,
      String materialNote,
      boolean providedChecklistItem,
      OffsetDateTime createdAt) {}

  /** 待提交平台事实行。 */
  public record PlatformAppealSubmissionRow(
      UUID id,
      String targetSystem,
      String targetKey,
      String idempotencyKey,
      String submissionStatus,
      String submissionStatusLabel,
      int attemptCount,
      @Schema(nullable = true) OffsetDateTime lastAttemptAt,
      @Schema(nullable = true) OffsetDateTime nextRetryAt,
      String lastError,
      @Schema(nullable = true) OffsetDateTime sentAt,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      OffsetDateTime createdAt) {}

  /** 留痕行（追加式）。 */
  public record PlatformAppealEventRow(
      UUID id,
      String eventType,
      String eventTypeLabel,
      @Schema(nullable = true) String fromStatus,
      @Schema(nullable = true) String toStatus,
      String reasonNote,
      @Schema(nullable = true) UUID occurredBy,
      OffsetDateTime occurredAt) {}

  /** 申诉单详情（头 + 材料 + 提交记录 + 留痕 + 判据 + 可执行动作）。 */
  public record PlatformAppealDetail(
      PlatformAppealRow head,
      String appealText,
      String submitNote,
      String platformReplyText,
      String platformReplyReference,
      @Schema(nullable = true) UUID platformReplyBy,
      String resolutionNote,
      @Schema(nullable = true) UUID resolvedBy,
      @Schema(nullable = true) UUID submittedBy,
      @Schema(nullable = true) UUID createdBy,
      List<PlatformAppealMaterialRow> materials,
      List<PlatformAppealSubmissionRow> submissions,
      List<PlatformAppealEventRow> events,
      PlatformAppealGateView gate,
      List<PlatformAppealAction> actions) {}

  /** 列表页。 */
  public record PlatformAppealPage(
      List<PlatformAppealRow> rows,
      int page,
      int size,
      long total,
      PlatformAppealSummary summary,
      OffsetDateTime generatedAt,
      String paginationNote,
      String deadlineNote,
      String sourceNote,
      String sortNote) {}

  /** 汇总（金额**按币种分组**，不做固定汇率折算）。 */
  public record PlatformAppealSummary(
      long totalAppeals,
      long draftCount,
      long submittedCount,
      long repliedCount,
      long wonCount,
      long lostCount,
      long partialCount,
      long withdrawnCount,
      long submittableCount,
      long pendingSubmissionCount,
      List<PlatformAppealCurrencyTotal> currencyBreakdown) {}

  /** 按币种的金额合计。 */
  public record PlatformAppealCurrencyTotal(
      String currency, long appealCount, BigDecimal claimedAmount, BigDecimal awardedAmount) {}

  /** 当前可执行动作（每条都带**为什么不能做**）。 */
  public record PlatformAppealAction(
      String code,
      String label,
      boolean allowed,
      String method,
      String path,
      boolean required,
      String precondition,
      @Schema(nullable = true) String blockingReason) {}

  // ================================================================ 能力声明

  /** 职责句的逐块能力。 */
  public record PlatformAppealSectionCapability(
      String section,
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      String reason,
      @Schema(nullable = true) String landsIn) {}

  /** 上下游模块的交付状态。 */
  public record PlatformAppealRelatedModule(
      String id,
      String label,
      boolean delivered,
      String ownerModule,
      String reason,
      @Schema(nullable = true) String href) {}

  /** 能力声明里的动作表。 */
  public record PlatformAppealCapabilityAction(
      String code,
      String label,
      boolean allowed,
      @Schema(nullable = true) String method,
      @Schema(nullable = true) String path,
      boolean required,
      @Schema(nullable = true) String precondition,
      @Schema(nullable = true) String blockingReason,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String unavailableReason) {}

  /** 刻意不做的事。 */
  public record PlatformAppealBoundary(String boundary, String ownerModule, String reason) {}

  /** 能力声明整体。 */
  public record PlatformAppealCapabilities(
      List<String> facts,
      List<PlatformAppealSectionCapability> dimensions,
      List<PlatformAppealRelatedModule> relatedModules,
      List<PlatformAppealCapabilityAction> actions,
      List<PlatformAppealBoundary> boundaries,
      String stateMachine,
      String deadlineRule,
      String sourceRule,
      String platformSubmissionRule) {}
}
