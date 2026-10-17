package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 9.3.3.2 履约流程监控的传输模型。
 *
 * <p>蓝图原话：「监控履约流程的异常与超时：预占失败、库存不足、流程卡在某步、超时未发货，
 * 提供重试、切换路径、人工介入入口。」输出是「异常流程清单 + 处置操作 + 处置记录」。
 * 关系句是本模块的全部重点：「**超时不是失败**：流程卡住时先查证再决定重试或释放预占，
 * 否则会出现"重试造成重复发货"。」
 *
 * <p><b>命名纪律（硬要求，不是风格偏好）</b>：springdoc 用 record 的<b>简单类名</b>做
 * {@code components.schemas} 的键，而本仓已有 {@code FulfillmentRoutingCapabilities}（9.3.2.2）、
 * {@code FulfillmentRequestCapabilities}（9.3.3.1）等同名 record。因此本文件所有 record / enum
 * 一律以 {@code FulfillmentMonitor} 开头，嵌套声明同样起名 {@link FulfillmentMonitorDimension}
 * 而不是 {@code Dimension}。
 *
 * <p><b>四条建模纪律</b>：
 *
 * <ul>
 *   <li><b>词表只有一个来源</b>：各枚举一字不改地对应 V154 的 CHECK 词表。</li>
 *   <li><b>加粗句进类型系统</b>：{@link FulfillmentMonitorDispositionInput#verificationId()}
 *       在 {@code actionKind=RETRY} 时是必填的语义位 —— 不是「建议先查证」，
 *       而是「没有它，DB 触发器会拒」。</li>
 *   <li><b>下游未接通要显式说</b>：{@link FulfillmentMonitorCapabilities} 里
 *       {@code reservationReleaseDispatched} / {@code workbenchTodoPushed} 恒为 false。</li>
 *   <li><b>金额全程 BigDecimal</b>，数量 numeric(19,6)、金额 numeric(19,4)，耗时 numeric(12,3)。</li>
 * </ul>
 */
public final class FulfillmentMonitorModels {

  private FulfillmentMonitorModels() {}

  /**
   * 中文名。本文件里所有枚举都实现它，于是服务层与前端都能拿到 label，
   * 而不必在每处各写一遍 switch —— 各写一遍迟早会漂移。
   */
  public interface Labelled {
    String label();
  }

  // ================================================================ 枚举

  /**
   * 异常类别。覆盖蓝图点名的四类：预占失败、库存不足、流程卡在某步、超时未发货。
   *
   * <p>刻意分成四类而不是合并成「异常」一种：处置动作完全不同 ——
   * 库存不足该补货或换仓，流程卡住该查那一步，超时未发货该催或重试。
   * 合并成一类，选错处置的概率就上去了。
   */
  public enum FulfillmentMonitorAnomalyKind implements Labelled {
    RESERVATION_FAILED("预占失败"),
    INSUFFICIENT_STOCK("库存不足"),
    STEP_STUCK("流程卡在某步"),
    SHIPMENT_TIMEOUT("超时未发货");

    private final String label;

    FulfillmentMonitorAnomalyKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 严重度：INFO 只提示 / WARNING 要人看 / CRITICAL 必须立刻处置。 */
  public enum FulfillmentMonitorSeverity implements Labelled {
    INFO("提示"),
    WARNING("警告"),
    CRITICAL("严重");

    private final String label;

    FulfillmentMonitorSeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** CRITICAL 必须先于 WARNING 处置。 */
    public int rank() {
      return ordinal();
    }
  }

  /**
   * 案例处置状态（<b>不是</b>流程状态）。
   *
   * <p>流程状态唯一地住在 V152 的 fulfillment_request 头行；这里的轴是
   * 「这条异常有没有被查证、被处置」。两条轴正交，所以
   * {@link FulfillmentRequestStatus} 与本枚举没有任何映射关系，也不该有。
   */
  public enum FulfillmentMonitorCaseStatus implements Labelled {
    OPEN("待处理"),
    INVESTIGATING("查证中"),
    VERIFIED("已查证"),
    DISPOSED("已处置"),
    DISMISSED("已证伪");

    private final String label;

    FulfillmentMonitorCaseStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** DISPOSED 与 DISMISSED 是终态：已结案的异常不许改回未处理。 */
    public boolean terminal() {
      return this == DISPOSED || this == DISMISSED;
    }
  }

  /**
   * 查证结论。只有 {@code CONFIRMED} 解锁重试。
   *
   * <p>这正是蓝图那句「超时不是失败，先查证再决定」的可执行形态：
   * 查证结论为 NOT_CONFIRMED 时，走的是 DISMISS 而不是 RETRY。
   */
  public enum FulfillmentMonitorConclusion implements Labelled {
    CONFIRMED("坐实了异常"),
    NOT_CONFIRMED("证伪，不是异常");

    private final String label;

    FulfillmentMonitorConclusion(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 只有坐实才允许重试 —— DB 第 8.1 节的闸门。 */
    public boolean allowsRetry() {
      return this == CONFIRMED;
    }
  }

  /**
   * 查证范围：这次到底查了哪一侧。
   *
   * <p>这个字段就是防重复发货的<b>判断依据本身</b>：查了
   * {@code DOWNSTREAM_ACK} 确认下游确实没动，才敢重试；
   * 只查了 {@code LOCAL_STATE_ONLY}（本地状态没变）就重试，
   * 正是「重试造成重复发货」的起点。
   */
  public enum FulfillmentMonitorVerifiedScope implements Labelled {
    DOWNSTREAM_ACK("已向下游查证回执"),
    LOCAL_STATE_ONLY("只看本地状态"),
    WAREHOUSE_STOCK("已查 warehouse 库存"),
    CARRIER_TRACE("已查承运轨迹");

    private final String label;

    FulfillmentMonitorVerifiedScope(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 只有查到了下游/外部事实才算数；只看本地状态不足以支持重试。 */
    public boolean factual() {
      return this != LOCAL_STATE_ONLY;
    }
  }

  /** 处置动作：蓝图点名的重试 / 切换路径 / 人工介入 + 释放预占 + 证伪。 */
  public enum FulfillmentMonitorActionKind implements Labelled {
    RETRY("重试"),
    SWITCH_PATH("切换履约路径"),
    MANUAL_INTERVENTION("人工介入"),
    RELEASE_RESERVATION("释放预占"),
    DISMISS("证伪，不是异常");

    private final String label;

    FulfillmentMonitorActionKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 处置执行状态。本服务只登记动作与回执，不假装下游已经执行。 */
  public enum FulfillmentMonitorDispositionStatus implements Labelled {
    PENDING("待发出"),
    SENT("已发出"),
    ACKNOWLEDGED("下游已受理"),
    COMPLETED("已完成"),
    CANCELLED("已取消"),
    FAILED("已失败");

    private final String label;

    FulfillmentMonitorDispositionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public boolean terminal() {
      return this == COMPLETED || this == CANCELLED || this == FAILED;
    }
  }

  /** 释放预占登记的状态。缺省 REQUESTED = 指令已登记，待 7.2 回执（**未交付**）。 */
  public enum FulfillmentMonitorReleaseStatus implements Labelled {
    REQUESTED("待回执"),
    RELEASED("已释放"),
    REJECTED("已被拒");

    private final String label;

    FulfillmentMonitorReleaseStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public boolean terminal() {
      return this != REQUESTED;
    }
  }

  /** 阈值来源：每个被解析出的阈值都带来源，界面上必须答得出「这是谁定的」。 */
  public enum FulfillmentMonitorThresholdSource implements Labelled {
    TENANT_OVERRIDE("仓库覆盖档"),
    TENANT_DEFAULT("租户默认档"),
    BUILTIN("内置兜底"),
    SNAPSHOT_FROM_REQUEST("建单时快照");

    private final String label;

    FulfillmentMonitorThresholdSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 人工介入待办的种类（下游 1 工作台未交付，本服务内是权威待办）。 */
  public enum FulfillmentMonitorTodoAction implements Labelled {
    REVIEW_CASE("复核异常"),
    CONFIRM_DOWNSTREAM("向下游确认"),
    ESCALATE("升级处理"),
    CONTACT_SUPPLIER("联系供应方"),
    OTHER("其他");

    private final String label;

    FulfillmentMonitorTodoAction(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 待办项状态。 */
  public enum FulfillmentMonitorTodoStatus implements Labelled {
    OPEN("待认领"),
    CLAIMED("已认领"),
    DONE("已完成"),
    CANCELLED("已取消");

    private final String label;

    FulfillmentMonitorTodoStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public boolean terminal() {
      return this == DONE || this == CANCELLED;
    }
  }

  /**
   * 登记来源：{@code TENANT_MAINTAINED} = 租户自行登记（照 V150 既有解法）；
   * {@code MODULE_*} = 正式接口到位后由同步器按<b>同一形状</b>写入，结构不用改。
   */
  public enum FulfillmentMonitorSourceKind implements Labelled {
    TENANT_MAINTAINED("租户自行登记"),
    MODULE_7_2_1_3_RELEASE("7.2.1.3 释放预占同步"),
    MODULE_1_WORKBENCH_TODO("1 工作台待办同步");

    private final String label;

    FulfillmentMonitorSourceKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 事件类型（append-only）。 */
  public enum FulfillmentMonitorEventKind implements Labelled {
    DETECTED("已检出异常"),
    INVESTIGATING("进入查证"),
    VERIFIED("查证坐实"),
    NOT_CONFIRMED("查证证伪"),
    DISPOSITION_CREATED("已创建处置"),
    DISPOSITION_SENT("处置已发出"),
    DISPOSITION_ACKNOWLEDGED("下游已受理"),
    DISPOSITION_COMPLETED("处置完成"),
    DISPOSITION_FAILED("处置失败"),
    DISPOSITION_CANCELLED("处置已取消"),
    RELEASE_REGISTERED("已登记释放指令"),
    RELEASE_ACK("收到释放回执"),
    TODO_CREATED("已建待办"),
    TODO_CLAIMED("待办已认领"),
    TODO_DONE("待办已完成"),
    TODO_CANCELLED("待办已取消"),
    THRESHOLD_CHANGED("阈值已变更");

    private final String label;

    FulfillmentMonitorEventKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 流程步：与 V152 的 FulfillmentStepKind 同一阶梯（不另起一套词表）。 */
  public enum FulfillmentMonitorStepKind implements Labelled {
    RESERVE("锁定预占", 3600),
    OUTBOUND("生成出库单", 7200),
    WORK("编排作业", 14400),
    TRACK("跟踪完成", 86400);

    private final String label;

    private final int builtinTimeoutSeconds;

    FulfillmentMonitorStepKind(String label, int builtinTimeoutSeconds) {
      this.label = label;
      this.builtinTimeoutSeconds = builtinTimeoutSeconds;
    }

    public String label() {
      return label;
    }

    /** 内置兜底阈值：租户没配任何档时用它，且**必须带来源标成 BUILTIN**。 */
    public int builtinTimeoutSeconds() {
      return builtinTimeoutSeconds;
    }
  }

  // ================================================================ 能力声明

  /**
   * 单个上游 / 下游维度的可用性。
   *
   * @param key 维度键
   * @param label 中文名
   * @param available 是否真的接通了（false 时调用方必须自己读 reason，不许猜）
   * @param ownerModule 真实归属模块
   * @param reason 未接通的原因（available=false 时必填）
   */
  public record FulfillmentMonitorDimension(
      @Schema(example = "FULFILLMENT_PROCESS_STATE") String key,
      @Schema(example = "履约流程状态（9.3.3.1 已持久化）") String label,
      boolean available,
      @Schema(example = "9.3.3.1 履约请求") String ownerModule,
      String reason) {}

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：<b>两个下游都未交付</b>（1 工作台待办、7.2.1.3 释放预占），
   * 而这恰恰是最容易被误读的地方。界面若把释放登记的 REQUESTED 当成「货已释放」，
   * 那 warehouse 那边其实什么都没释放 —— 于是同一批货被两个流程各发一次。
   * 所以 {@code reservationReleaseDispatched=false}、{@code workbenchTodoPushed=false}
   * 必须由接口自己报出。
   */
  public record FulfillmentMonitorCapabilities(
      List<FulfillmentMonitorDimension> dimensions,
      @Schema(description = "是否真的把释放指令发给了 7.2.1.3（恒为 false：只登记，待下游回执）")
          boolean reservationReleaseDispatched,
      @Schema(description = "是否真的推到了 1 工作台待办（恒为 false：本服务内待办是权威）")
          boolean workbenchTodoPushed,
      @Schema(description = "是否真的把流程推回 RUNNING（恒为 false：本模块不代 9.3.3.1 改状态）")
          boolean processStateMutated,
      @Schema(description = "重试是否被「先查证」硬约束（恒为 true：蓝图加粗句）")
          boolean retryRequiresVerification,
      List<FulfillmentMonitorAnomalyKind> anomalyKinds,
      List<FulfillmentMonitorSeverity> severities,
      List<FulfillmentMonitorActionKind> actionKinds,
      List<FulfillmentMonitorVerifiedScope> verifiedScopes,
      @Schema(description = "「超时不是失败」这条口径怎么落地") String coreStanceNote,
      @Schema(description = "下游边界为什么是「登记指令」而不是「直接调下游」") String downstreamNote,
      @Schema(description = "本模块与 9.3.3.1 的分工") String scopeNote) {}

  // ================================================================ 查询 / 分页

  /** 异常流程清单查询条件（全部落到真实 SQL 筛选，不是前端过滤）。 */
  public record FulfillmentMonitorCaseQuery(
      String caseNo,
      String requestCode,
      String orderRef,
      String skuRef,
      String warehouseRef,
      String stepKind,
      String anomalyKind,
      String severity,
      String status,
      @Schema(description = "只看已查证但还没处置的") Boolean verifiedOnly,
      @Schema(description = "只看还停在未查证态的") Boolean pendingVerificationOnly,
      String keyword,
      Integer page,
      Integer size) {}

  /** 真分页一页（total 与分页无关，单条 count 得出）。 */
  public record FulfillmentMonitorCasePage(
      List<FulfillmentMonitorCaseRow> items, long total, int page, int size) {}

  /** 异常流程清单行。 */
  public record FulfillmentMonitorCaseRow(
      UUID id,
      String caseNo,
      UUID requestId,
      String requestCode,
      String orderRef,
      String skuRef,
      String warehouseRef,
      FulfillmentMonitorStepKind stepKind,
      String stepKindLabel,
      int attempt,
      FulfillmentMonitorAnomalyKind anomalyKind,
      String anomalyKindLabel,
      FulfillmentMonitorSeverity severity,
      String severityLabel,
      int thresholdSeconds,
      FulfillmentMonitorThresholdSource thresholdSource,
      String thresholdSourceLabel,
      BigDecimal overdueSeconds,
      @Schema(description = "判定算式原文，界面上直接展示这行就能复现判定")
          String ruleEvidence,
      OffsetDateTime detectedAt,
      FulfillmentMonitorCaseStatus status,
      String statusLabel,
      long monitorVersion,
      OffsetDateTime investigatingAt,
      OffsetDateTime verifiedAt,
      OffsetDateTime resolvedAt,
      String resolutionNote) {}

  /** 异常流程详情：案例 + 查证记录 + 处置 + 释放登记 + 待办 + 事件流。 */
  public record FulfillmentMonitorCaseDetail(
      FulfillmentMonitorCaseRow monitorCase,
      List<FulfillmentMonitorVerificationRow> verifications,
      List<FulfillmentMonitorDispositionRow> dispositions,
      List<FulfillmentMonitorReleaseRow> releases,
      List<FulfillmentMonitorTodoRow> todos,
      List<FulfillmentMonitorEventRow> events,
      @Schema(description = "当前是否允许发起重试（查证已坐实且没有未终结的处置）")
          boolean retryAllowed,
      @Schema(description = "不允许重试时写清楚差什么，界面直接显示这句话，不让调用方自己猜")
          String retryBlockedReason) {}

  /** 查证记录（append-only；「先查证」的举证凭据）。 */
  public record FulfillmentMonitorVerificationRow(
      UUID id,
      String verificationNo,
      FulfillmentMonitorConclusion conclusion,
      String conclusionLabel,
      FulfillmentMonitorVerifiedScope verifiedScope,
      String verifiedScopeLabel,
      String evidenceRef,
      String finding,
      OffsetDateTime verifiedAt,
      UUID verifiedByMembershipId) {}

  /** 处置操作 + 处置记录（蓝图输出第二、三项）。 */
  public record FulfillmentMonitorDispositionRow(
      UUID id,
      String dispositionNo,
      UUID caseId,
      UUID requestId,
      FulfillmentMonitorActionKind actionKind,
      String actionKindLabel,
      FulfillmentMonitorDispositionStatus status,
      String statusLabel,
      UUID verificationId,
      Integer retryGrantSeconds,
      String targetRouteRef,
      String targetWarehouseRef,
      BigDecimal estimatedCostDelta,
      String estimatedCostCurrencyCode,
      String note,
      UUID createdByMembershipId,
      OffsetDateTime createdAt,
      OffsetDateTime sentAt,
      OffsetDateTime acknowledgedAt,
      OffsetDateTime completedAt,
      OffsetDateTime cancelledAt,
      String outcome,
      String lastError) {}

  /** 释放预占登记（下游 7.2.1.3 未交付，缺省 REQUESTED）。 */
  public record FulfillmentMonitorReleaseRow(
      UUID id,
      String releaseNo,
      UUID caseId,
      UUID requestId,
      UUID dispositionId,
      UUID reservationId,
      FulfillmentMonitorSourceKind sourceKind,
      String sourceKindLabel,
      OffsetDateTime capturedAt,
      FulfillmentMonitorReleaseStatus status,
      String statusLabel,
      String releaseRef,
      BigDecimal releasedQty,
      String rejectReason,
      OffsetDateTime requestedAt,
      OffsetDateTime acknowledgedAt,
      String idempotencyKey) {}

  /** 人工介入待办项（下游 1 工作台未交付，本服务内是权威待办）。 */
  public record FulfillmentMonitorTodoRow(
      UUID id,
      String todoNo,
      UUID caseId,
      UUID requestId,
      FulfillmentMonitorTodoAction actionKind,
      String actionKindLabel,
      FulfillmentMonitorSeverity severity,
      String title,
      String detail,
      FulfillmentMonitorSourceKind sourceKind,
      String sourceKindLabel,
      OffsetDateTime capturedAt,
      FulfillmentMonitorTodoStatus status,
      String statusLabel,
      UUID claimedByMembershipId,
      OffsetDateTime claimedAt,
      OffsetDateTime doneAt,
      String doneNote) {}

  /** 事件流（append-only）。 */
  public record FulfillmentMonitorEventRow(
      UUID id,
      FulfillmentMonitorEventKind eventKind,
      String eventKindLabel,
      String fromStatus,
      String toStatus,
      long monitorVersion,
      String detail,
      UUID actorId,
      OffsetDateTime occurredAt) {}

  // ================================================================ 扫描

  /**
   * 扫描输入。
   *
   * <p>{@code asOfSecondsAgo} 把判定基准**前移**：正常扫描用数据库当前时间，
   * 而这条允许把「当前」挪到过去若干秒，用来在测试与演练里复现一次真实超时，
   * 而不是让服务层用启动时刻缓存时间（那会让时钟一偏，整个监控就失真）。
   */
  public record FulfillmentMonitorScanInput(
      @Schema(description = "只扫这一个仓；留空扫全租户") String warehouseRef,
      @Schema(description = "按几种异常一起检出（留空=全部四种）") List<String> anomalyKinds,
      Integer maxCases,
      @Schema(description = "把判定基准前移 N 秒（回放/演练用；留空=用数据库当前时间）")
          Integer asOfSecondsAgo) {}

  /** 单条扫描结果：某一张流程被判成了什么异常、或者为什么被跳过。 */
  public record FulfillmentMonitorScanEntry(
      UUID requestId,
      String requestCode,
      FulfillmentMonitorStepKind stepKind,
      FulfillmentMonitorAnomalyKind anomalyKind,
      FulfillmentMonitorSeverity severity,
      int thresholdSeconds,
      FulfillmentMonitorThresholdSource thresholdSource,
      BigDecimal overdueSeconds,
      String ruleEvidence,
      @Schema(description = "true=新检出案例；false=已有未结案案例被跳过") boolean created,
      UUID caseId,
      String caseNo,
      String skipReason) {}

  /** 扫描整体结果：逐条报出检出与跳过及原因，不给一个含糊的总数。 */
  public record FulfillmentMonitorScanResult(
      int scannedRequests, int createdCases, int skipped, List<FulfillmentMonitorScanEntry> entries) {}

  // ================================================================ 查证

  /** 发起一次查证（加粗句的落点）。 */
  public record FulfillmentMonitorVerifyInput(
      @NotNull Long expectedMonitorVersion,
      @NotNull FulfillmentMonitorConclusion conclusion,
      @NotNull FulfillmentMonitorVerifiedScope verifiedScope,
      @Schema(description = "本次查证看到的下游凭据（回执号 / 库存快照号 / 轨迹号）")
          @Size(max = 128) String evidenceRef,
      @NotBlank String finding,
      @Schema(description = "查证时刻；留空用数据库当前时间") OffsetDateTime verifiedAt,
      @NotBlank String idempotencyKey) {}

  /** 查证结果：回显 monitorVersion，调用方下一次操作必须带它。 */
  public record FulfillmentMonitorVerifySaved(
      UUID caseId,
      String caseNo,
      UUID verificationId,
      FulfillmentMonitorConclusion conclusion,
      FulfillmentMonitorCaseStatus caseStatus,
      long monitorVersion,
      OffsetDateTime verifiedAt) {}

  /** 结案结果。 */
  public record FulfillmentMonitorCaseSaved(
      UUID caseId,
      String caseNo,
      FulfillmentMonitorCaseStatus status,
      long monitorVersion,
      OffsetDateTime resolvedAt) {}

  // ================================================================ 处置

  /**
   * 发起一次处置。
   *
   * <p><b>加粗句在类型上的落点</b>：{@code actionKind=RETRY} 时 {@code verificationId}
   * 与 {@code retryGrantSeconds} 语义上必填 —— 不是「建议先查证」，
   * 而是「没有它 V154 第 8.1 / 8.4 节的触发器会直接拒」。
   */
  public record FulfillmentMonitorDispositionInput(
      @NotNull Long expectedMonitorVersion,
      @NotNull FulfillmentMonitorActionKind actionKind,
      @Schema(description = "RETRY 时必填：指向一条 CONFIRMED 的查证记录")
          UUID verificationId,
      @Schema(description = "RETRY 时必填：打算给这次重试多长窗口（秒）") Integer retryGrantSeconds,
      @Schema(description = "SWITCH_PATH 时必填：9.3.2.2 的 routing_decision_ref")
          String targetRouteRef,
      String targetWarehouseRef,
      @Schema(description = "SWITCH_PATH 可选：切换的成本差估值（必须与币种成对出现）")
          BigDecimal estimatedCostDelta,
      String estimatedCostCurrencyCode,
      @NotBlank String note,
      @NotBlank String idempotencyKey) {}

  /** 处置结果。 */
  public record FulfillmentMonitorDispositionSaved(
      UUID dispositionId,
      String dispositionNo,
      UUID caseId,
      UUID requestId,
      FulfillmentMonitorActionKind actionKind,
      FulfillmentMonitorDispositionStatus status,
      long monitorVersion,
      @Schema(description = "RELEASE_RESERVATION 时带出登记的释放指令 id（缺省 REQUESTED，待 7.2 回执）")
          UUID releaseId,
      String releaseNo) {}

  /** 推进处置状态（登记下游回执）。 */
  public record FulfillmentMonitorDispositionStatusInput(
      @NotNull FulfillmentMonitorDispositionStatus fromStatus,
      @NotNull FulfillmentMonitorDispositionStatus toStatus,
      String outcome,
      @Schema(description = "toStatus=FAILED 时必填") String lastError) {}

  /** 结案（处置完成后把案例落到 DISPOSED）。 */
  public record FulfillmentMonitorCloseInput(@NotNull Long expectedMonitorVersion, String note) {}

  /** 登记 7.2.1.3 的释放回执。 */
  public record FulfillmentMonitorReleaseAckInput(
      @NotNull FulfillmentMonitorReleaseStatus status,
      String releaseRef,
      BigDecimal releasedQty,
      String rejectReason) {}

  // ================================================================ 阈值

  /** 租户可维护的超时阈值。 */
  public record FulfillmentMonitorThresholdRow(
      UUID id,
      @Schema(description = "null = 租户默认档") String warehouseRef,
      FulfillmentMonitorStepKind stepKind,
      String stepKindLabel,
      int thresholdSeconds,
      FulfillmentMonitorThresholdSource source,
      String sourceLabel,
      String notes,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 阈值 upsert 输入。 */
  public record FulfillmentMonitorThresholdInput(
      @Schema(description = "留空 = 租户默认档") String warehouseRef,
      @NotNull FulfillmentMonitorStepKind stepKind,
      @NotNull Integer thresholdSeconds,
      String notes) {}

  /**
   * 当前生效的四步阈值与解析来源。
   *
   * <p>一次给全四步，让界面上的「卡了多久 / 阈值多久 / 谁定的」三句话不用逐个点。
   */
  public record FulfillmentMonitorEffectiveThresholds(
      String warehouseRef, List<FulfillmentMonitorThresholdRow> thresholds) {}

  // ================================================================ 待办

  /** 登记人工介入待办项（下游 1 工作台未交付，本服务内是权威待办）。 */
  public record FulfillmentMonitorTodoInput(
      @NotNull FulfillmentMonitorTodoAction actionKind,
      @NotBlank @Size(max = 200) String title,
      @Size(max = 600) String detail,
      @Schema(description = "采集时刻；留空用数据库当前时间。刻意与登记时刻分开")
          OffsetDateTime capturedAt,
      FulfillmentMonitorSourceKind sourceKind) {}

  /** 待办查询条件。 */
  public record FulfillmentMonitorTodoQuery(
      UUID caseId, String status, String severity, String actionKind, Integer page, Integer size) {}

  /** 待办分页。 */
  public record FulfillmentMonitorTodoPage(
      List<FulfillmentMonitorTodoRow> items, long total, int page, int size) {}

  /** 待办操作（认领 / 完成 / 取消）。 */
  public record FulfillmentMonitorTodoActionInput(
      @NotNull String action, @Size(max = 400) String note) {}
}
