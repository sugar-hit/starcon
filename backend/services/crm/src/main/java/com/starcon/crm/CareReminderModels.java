package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 15.5.2.1 关怀与催办的传输模型。
 *
 * <p>蓝图 15 卷 15.5.2.1 原话：
 *
 * <blockquote>
 * 职责：主动触达买家：发货通知、物流提醒、催付（未付款订单）、催评、售后进度通知、关怀邮件（节假日/生日），按规则定时发送并遵守频次上限与退订要求。
 * 输入：触达规则；目标买家集合；退订名单。
 * 输出：触达发送记录 + 打开/点击统计 + 退订记录。
 * 关系：上游 = 9 订单、10 物流、15.3.2.2 接触记录；下游 = 15.1.1.2 发送、12 财务（催付加速回款）。
 * <b>催付有合规边界</b>：部分平台限制催付话术与频次，规则需按平台配置，过度催付会被投诉。
 * </blockquote>
 *
 * <p><b>本文件最重要的四件事</b>：
 *
 * <ol>
 *   <li><b>触达类型照蓝图点名的六类归位</b>（{@link CareReminderType}）：
 *       发货通知 / 物流提醒 / 催付 / 催评 / 售后进度通知 / 关怀邮件。</li>
 *   <li><b>规则按平台配置</b>：{@code platformCode} 单值 +
 *       {@code frequencyLimit} + {@code frequencyWindowHours} + {@code quietHoursStart/End}
 *       —— 不同平台可以各配各的催付话术与频次，**不允许**一个全局规则套所有平台。</li>
 *   <li><b>拦截是一等状态</b>（{@link CareReminderBlockCode}）：
 *       退订拦截 / 频次超限 / 平台不支持 / 未签收 —— 每个都带**人话原因**，
 *       落成一条 {@code status='BLOCKED'} 的任务行，**不是静默跳过**。</li>
 *   <li><b>统计口径服务端算</b>（{@link CrmCareReminderStats}）：
 *       触达总数 / 成功数 / 失败数 / 打开数 / 点击数 / 退订拦截数 / 频次拦截数
 *       全部由服务端聚合，<b>前端不重算、不做算术</b>。</li>
 * </ol>
 *
 * <p><b>命名</b>：统一用 {@code CrmCareReminder*} 前缀。全仓 grep 确认与既有类型零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名 key 后者覆盖前者且不报冲突，
 * 故不按前缀扫而是逐个名字精确比对。
 */
public final class CareReminderModels {

  private CareReminderModels() {}

  // ================================================================ 枚举

  /**
   * 触达类型 —— 照蓝图点名的六类归位。
   *
   * <p>🔴 {@link #PAYMENT_DEMAND}（催付）**带合规边界**：只对白名单平台成立，
   * 且规则必须按平台配置（不同的平台配不同的催付频次）。
   */
  public enum CareReminderType {
    SHIPMENT_NOTICE("发货通知"),
    LOGISTICS_REMINDER("物流提醒"),
    PAYMENT_DEMAND("催付（未付款订单）"),
    REVIEW_REQUEST("催评"),
    AFTERSALES_PROGRESS("售后进度通知"),
    CARE_MAIL("关怀邮件（节假日 / 生日）");

    private final String label;

    CareReminderType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 触达任务状态。
   *
   * <p>🔴 {@link #BLOCKED}（被拦截）**不是**失败：它表示「这一条触达被合规闸挡住了」，
   * 原因逐条写明（退订 / 频次超限 / 平台不支持 / 未签收）。拦截本身也是要留痕的事实。
   */
  public enum CareReminderStatus {
    QUEUED("已排期待发"),
    SENT("已发出"),
    FAILED("发送失败"),
    BLOCKED("被拦截"),
    CANCELLED("已作废");

    private final String label;

    CareReminderStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 拦截码 —— 加粗句「遵守频次上限与退订要求」的逐条明确原因。
   *
   * <p>🔴 词表**只有两档**，正好对应那两道强制闸：退订闸与频次闸。
   * 「该平台不支持催付」**不在**这里：它是**规则级**的合规判定 ——
   * 平台不在白名单里时那条催付规则根本建不出来（表级 CHECK），
   * 不存在「一条催付任务被平台挡住了」这种自相矛盾的情况。
   */
  public enum CareReminderBlockCode {
    OPT_OUT("退订拦截"),
    FREQUENCY_LIMIT("频次超限");

    private final String label;

    CareReminderBlockCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 触达通道：人工交接（当前唯一可用）/ 平台侧发送接口（未接入）。 */
  public enum CareReminderTransport {
    MANUAL_HANDOFF("人工交接"),
    PLATFORM_API("平台侧发送接口（未接入）");

    private final String label;

    CareReminderTransport(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 统计事件类型：打开 / 点击。 */
  public enum CareReminderEventType {
    OPEN("打开"),
    CLICK("点击");

    private final String label;

    CareReminderEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 人工交接时登记的发送结果。 */
  public enum CareReminderDispatchOutcome {
    SENT("已发出"),
    FAILED("发送失败");

    private final String label;

    CareReminderDispatchOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 新建 / 修改一条触达规则。
   *
   * <p>🔴 {@code platformCode} 是**单平台**：加粗句「规则需按平台配置」的落点之一。
   * 🔴 {@code frequencyLimit} + {@code frequencyWindowHours} 是频次上限本身。
   * 🔴 {@code quietHoursStart} / {@code quietHoursEnd} 要么都填要么都不填。
   */
  public record CrmCareReminderRuleInput(
      @NotBlank @Size(max = 64) String ruleCode,
      @NotBlank @Size(max = 200) String ruleName,
      @NotBlank String reminderType,
      @NotBlank @Size(max = 32) String platformCode,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) @Size(max = 200) String shopName,
      @Schema(nullable = true) Map<String, Object> targetFilter,
      @Schema(nullable = true) Integer frequencyLimit,
      @Schema(nullable = true) Integer frequencyWindowHours,
      @Schema(nullable = true) String quietHoursStart,
      @Schema(nullable = true) String quietHoursEnd,
      @Schema(nullable = true) String sendTime,
      @Schema(nullable = true) @Size(max = 64) String timezone,
      @Schema(nullable = true) @Size(max = 64) String templateCode,
      @Schema(nullable = true) @Size(max = 400) String subject,
      @Schema(nullable = true) String body,
      @Schema(nullable = true) @Size(max = 2000) String note) {}

  /**
   * 生成触达任务。
   *
   * <p>规则 id 走路径参数；这里只放**这一次生成的参数**：
   * 计划时刻（空 = 按规则的每日发送时刻算下一次）、目标窗口与收窄条件。
   */
  public record CrmCareReminderGenerateInput(
      @Schema(nullable = true) String plannedAt,
      @Schema(nullable = true) Integer lookbackDays,
      @Schema(nullable = true) BigDecimal minAmount,
      @Schema(nullable = true) @Size(max = 32) String orderSource,
      @Schema(nullable = true) List<String> customerRefs) {}

  /**
   * 人工交接登记发送结果。
   *
   * <p>🔴 {@code outcome=FAILED} 时 {@code failureReason} **必填**。
   */
  public record CrmCareReminderDispatchInput(
      @NotBlank String outcome,
      @Schema(nullable = true) String sentAt,
      @Schema(nullable = true) String failureReason,
      @Schema(nullable = true) String platformReceipt,
      @Schema(nullable = true) @Size(max = 2000) String note) {}

  /**
   * 录入一条打开 / 点击事件。
   *
   * <p>⚠️ 平台回执**未接入** ⇒ 自动采集端点如实答「未接入」且不写数据；
   * 这个入口是**平台 webhook 或人工录入**用的。
   */
  public record CrmCareReminderStatEventInput(
      @NotBlank String taskId,
      @NotBlank String eventType,
      @Schema(nullable = true) String occurredAt,
      @Schema(nullable = true) @Size(max = 120) String source) {}

  // ================================================================ 出参

  /**
   * 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>字段名与兄弟模块一致：{@code dimension / description / available / source /
   * ownerModule / impact}。
   */
  public record CrmCareReminderCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /**
   * 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。
   *
   * @param paymentDemandAllowedPlatforms 🔴 允许催付的平台白名单（合规边界，页面要照它画）
   * @param reminderTypes 六类触达类型
   * @param actionPolicy 明确声明「本模块不重造发送」与「平台回执未接入」
   */
  public record CrmCareReminderCapabilities(
      List<CrmCareReminderCapabilityDimension> dimensions,
      List<String> reminderTypes,
      List<String> reminderStatuses,
      List<String> blockCodes,
      List<String> paymentDemandAllowedPlatforms,
      List<String> marketingReminderTypes,
      String optOutPolicy,
      String frequencyPolicy,
      String actionPolicy,
      String boundaryNote,
      String highlight) {}

  /**
   * 一条触达规则。
   *
   * @param frequencyLimit 频次上限（窗口内最多触达几次）
   * @param frequencyWindowHours 频次窗口（小时）
   * @param quietHoursStart 静默时段起（本地时刻，HH:mm）
   * @param quietHoursEnd 静默时段止
   */
  public record CrmCareReminderRuleRow(
      UUID id,
      String ruleCode,
      String ruleName,
      String reminderType,
      String reminderTypeLabel,
      String platformCode,
      @Schema(nullable = true) UUID shopId,
      String shopName,
      Map<String, Object> targetFilter,
      int frequencyLimit,
      int frequencyWindowHours,
      @Schema(nullable = true) String quietHoursStart,
      @Schema(nullable = true) String quietHoursEnd,
      String sendTime,
      String timezone,
      String templateCode,
      String subject,
      String body,
      String status,
      String statusLabel,
      @Schema(nullable = true) UUID createdBy,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 触达规则分页（真分页：总数与页数都从真实查询来）。 */
  public record CrmCareReminderRulePage(
      List<CrmCareReminderRuleRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 一条触达任务（= 蓝图输出句里的「触达发送记录」）。
   *
   * @param blockCode / blockReason 🔴 拦截的**明确原因**（退订 / 频次超限 / 平台不支持 / 未签收）
   * @param openCount / clickCount 服务端聚合出来的打开数与点击数（前端不重算）
   */
  public record CrmCareReminderTaskRow(
      UUID id,
      String taskNo,
      UUID ruleId,
      String ruleCode,
      String reminderType,
      String reminderTypeLabel,
      String customerRef,
      String customerName,
      @Schema(nullable = true) UUID shopId,
      String shopName,
      String platformCode,
      String orderReference,
      String subject,
      String body,
      String transport,
      String transportLabel,
      String status,
      String statusLabel,
      String plannedAt,
      @Schema(nullable = true) String sentAt,
      String failureReason,
      String blockCode,
      String blockReason,
      String platformReceipt,
      long openCount,
      long clickCount,
      @Schema(nullable = true) UUID createdBy,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 触达任务分页（真分页）。 */
  public record CrmCareReminderTaskPage(
      List<CrmCareReminderTaskRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 生成触达任务的结果。
   *
   * @param created 真正排进去的任务数
   * @param blocked 被合规闸挡下的任务数（每条都带明确原因）
   * @param idempotentHits 幂等命中（当天已生成过，没有重复记账）
   * @param upstreamCandidateCount 上游给出的候选数（真实读到的，不是猜的）
   * @param matchedCount 按规则条件（平台 / 金额 / 订单来源 / 指定买家）收窄后剩下的数
   * @param reasons 逐条拦截原因（原因 → 条数）
   */
  public record CrmCareReminderGenerateResult(
      UUID ruleId,
      String ruleCode,
      int created,
      int blocked,
      int idempotentHits,
      int upstreamCandidateCount,
      int matchedCount,
      Map<String, Integer> reasons,
      String plannedAt,
      String note) {}

  /** 打开 / 点击事件的一行。 */
  public record CrmCareReminderStatEventRow(
      UUID id,
      UUID taskId,
      String taskNo,
      String eventType,
      String eventTypeLabel,
      String occurredAt,
      String source,
      String createdAt) {}

  /** 打开 / 点击事件分页（真分页）。 */
  public record CrmCareReminderStatEventPage(
      List<CrmCareReminderStatEventRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 平台打开 / 点击回执的**接入状态**。
   *
   * <p>🔴 用 {@code Unavailable*Port} 同款写法：<b>如实答「未接入」并把原因带出来</b>，
   * 而不是抛异常 —— 异常会走 Spring 的 {@code sendError}，MockMvc 与浏览器里
   * **响应体是空的**，原因就丢了；调用方只剩一句「保存失败」，等于没答。
   *
   * @param available 恒为 false（平台未提供打开/点击回执接口）
   * @param reason 人能读的原因（缺什么、归谁、现在怎么办）
   * @param ownerModule 未接入的归属模块：2 = 渠道与店铺（平台适配器都在 channel 的 vendor 包下）
   */
  public record CrmCareReminderPlatformReceiptStatus(
      boolean available, String reason, String ownerModule) {}

  /**
   * 统计汇总 —— <b>全部由服务端算</b>。
   *
   * <p>🔴 前端<b>不重算、不做算术</b>：这里给什么页面就显示什么。
   *
   * @param totalTasks 触达总数
   * @param sentTasks 成功数（已发出）
   * @param failedTasks 失败数
   * @param blockedTasks 被拦截数
   * @param openCount 打开数
   * @param clickCount 点击数
   * @param optOutBlockedCount 退订拦截数
   * @param frequencyBlockedCount 频次拦截数
   */
  public record CrmCareReminderStats(
      long totalTasks,
      long queuedTasks,
      long sentTasks,
      long failedTasks,
      long blockedTasks,
      long cancelledTasks,
      long openCount,
      long clickCount,
      long optOutBlockedCount,
      long frequencyBlockedCount,
      String note) {}
}
