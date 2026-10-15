package com.starcon.trade;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.2.1.2 运单申请的契约形状。
 *
 * <p>风格照抄 9.4.1.1（{@code PendingShipmentModels}）：枚举与记录同文件，
 * 输入用 {@code @Valid} record，响应是不可变 record。
 *
 * <p>⚠️ 载荷一律用<b>具名 record</b> 而不是 {@code Map<String,Object>}：
 * Map 的值类型会被 springdoc 推成 {@code object}，codegen 出空对象 {@code {}}，
 * 前端既拿不到运单号也过不了类型检查（9.6.1.3 已踩过并修正）。
 */
final class WaybillModels {
  private WaybillModels() {}

  // ================================================================ 枚举

  /**
   * 运单申请状态机。
   *
   * <p>{@code ISSUED} 的唯一入口是<b>承运商真实返回了运单号</b>：
   * DB 层用 CHECK {@code (status='ISSUED') = (btrim(waybill_no)<>'')} 钉死，
   * 触发器另钉一道「已出号不可回退」。所以这个枚举里没有「预计单号」「占位单号」。
   */
  enum WaybillRequestStatus {
    QUEUED("已入队", false),
    SUBMITTING("提交中", false),
    ISSUED("已出号", true),
    FAILED("申请失败", true),
    CANCELLED("已作废", true);

    public final String displayName;
    public final boolean terminal;

    WaybillRequestStatus(String displayName, boolean terminal) {
      this.displayName = displayName;
      this.terminal = terminal;
    }
  }

  /** 队列条目状态。与申请状态刻意分开：队列说「排到谁了」，申请说「这一票怎么了」。 */
  enum WaybillQueueStatus {
    WAITING("等待窗口"),
    READY("可提交"),
    DISPATCHING("提交中"),
    DONE("已完成"),
    FAILED("已失败");

    public final String displayName;

    WaybillQueueStatus(String displayName) {
      this.displayName = displayName;
    }

    /** 还在队列里的（可被取出提交 / 可重排）。 */
    public boolean open() {
      return this == WAITING || this == READY;
    }
  }

  /**
   * 单次提交的结果。
   *
   * <p>{@code RATE_LIMITED} 与 {@code CARRIER_UNAVAILABLE} 可重试，
   * {@code CARRIER_REJECTED}（地址不可达 / 尺寸超限 / 余额不足）**不可重试** ——
   * 原样重发一次只会再被拒一次并多烧一次接口配额。这是本模块最贵的一个判断。
   */
  enum WaybillAttemptOutcome {
    SUCCEEDED("已出号", true),
    CARRIER_REJECTED("承运商拒收", false),
    RATE_LIMITED("渠道限流", true),
    CARRIER_UNAVAILABLE("承运商不可达", true),
    ADAPTER_MISSING("渠道未接通", false);

    public final String displayName;
    public final boolean retryable;

    WaybillAttemptOutcome(String displayName, boolean retryable) {
      this.displayName = displayName;
      this.retryable = retryable;
    }
  }

  // ================================================================ 能力声明

  /** 能力维度。与 9.4.1.1 同一形状，{@code available=false} 必须点名 ownerModule。 */
  public record WaybillCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  public record WaybillCapabilities(
      List<WaybillCapabilityDimension> dimensions,
      List<String> requestStatuses,
      List<String> queueStatuses,
      List<String> attemptOutcomes,
      List<String> carrierChannels,
      String rateLimitNote,
      String idempotencyNote,
      String boundaryNote) {}

  // ================================================================ 承运商登记

  /** 承运商登记行。刻意<b>没有</b> endpoint / 账号 / 密钥列 —— 凭据归 secret manager。 */
  public record WaybillCarrierRow(
      UUID id,
      String code,
      String displayName,
      String carrierChannel,
      String carrierChannelLabel,
      String adapterKey,
      boolean connected,
      String unavailableReason,
      int rateLimitPerWindow,
      int rateWindowSeconds,
      int maxRetryAttempts,
      String remark,
      boolean adapterAvailable,
      String submitBlockReason) {}

  public record WaybillCarrierPage(
      List<WaybillCarrierRow> items, int page, int size, long totalElements, long totalPages) {}

  // ================================================================ 运单申请

  public record WaybillRequestRow(
      UUID id,
      String requestNo,
      String idempotencyKey,
      UUID pendingUnitId,
      UUID pendingParcelId,
      String outboundOrderNo,
      String salesOrderNo,
      String warehouseRef,
      String carrierCode,
      String carrierChannel,
      String carrierChannelLabel,
      String shipFromName,
      String shipFromCountry,
      String shipToName,
      String shipToCountry,
      String shipToAddressLine,
      String shipToCity,
      String shipToPostalCode,
      BigDecimal grossWeightKg,
      BigDecimal lengthMm,
      BigDecimal widthMm,
      BigDecimal heightMm,
      String declaredCurrency,
      BigDecimal declaredValueTotal,
      int declaredItemCount,
      String customsSource,
      String labelTemplateRef,
      WaybillRequestStatus status,
      String statusLabel,
      String waybillNo,
      String labelFormat,
      String labelUri,
      String failureCode,
      String failureReason,
      String cancelReason,
      int attemptCount,
      int retryCount,
      OffsetDateTime notBefore,
      OffsetDateTime issuedAt,
      OffsetDateTime createdAt,
      String nextAction) {}

  public record WaybillCustomsItemRow(
      UUID id,
      UUID skuId,
      String skuRef,
      String productName,
      BigDecimal quantity,
      String unitCode,
      BigDecimal unitPrice,
      BigDecimal declaredValue,
      String hsCode,
      String originCountry,
      UUID destinationVersionId) {}

  public record WaybillQueueRow(
      UUID id,
      UUID requestId,
      String requestNo,
      String carrierCode,
      WaybillQueueStatus status,
      String statusLabel,
      OffsetDateTime scheduledAt,
      OffsetDateTime dispatchedAt,
      int attemptCount,
      int rescheduleCount,
      String lastFailureReason,
      long positionHint) {}

  public record WaybillAttemptRow(
      UUID id,
      UUID requestId,
      String requestNo,
      int attemptNo,
      WaybillAttemptOutcome outcome,
      String outcomeLabel,
      boolean retryable,
      String failureReason,
      String carrierRequestRef,
      String waybillNo,
      OffsetDateTime attemptedAt) {}

  public record WaybillEventRow(
      UUID id,
      UUID requestId,
      UUID queueEntryId,
      String eventKind,
      String fromStatus,
      String toStatus,
      String detail,
      OffsetDateTime occurredAt) {}

  /** 单张申请的详情：头 + 申报明细 + 队列 + 逐次尝试 + 事件流 + 判据。 */
  public record WaybillRequestDetail(
      WaybillRequestRow request,
      List<WaybillCustomsItemRow> customsItems,
      List<WaybillQueueRow> queueEntries,
      List<WaybillAttemptRow> attempts,
      List<WaybillEventRow> timeline,
      List<String> completionCriteria,
      List<String> boundaryNotes) {}

  public record WaybillRequestPage(
      List<WaybillRequestRow> items, int page, int size, long totalElements, long totalPages) {}

  // ================================================================ 输入

  /**
   * 提交一条运单申请（**逐包裹**，不是一个出库单一张单）。
   *
   * <p>{@code idempotencyKey} 是<b>必填</b>（蓝图输入句第四项），
   * DB 层用 {@code UNIQUE(tenant_id, idempotency_key)} 钉死：重复提交同一次申请
   * 不会产生第二张运单，重复提交返回的是**原来那一张**。
   *
   * <p>收寄信息走显式入参而不是从订单表反查：本模块的输入句是「待发货包裹 + 渠道 +
   * 申报信息」，而待发货投影（V124）刻意**不存买家地址**（改单不影响已打包的这一票），
   * 所以地址只能由调用方给出。
   */
  public record WaybillRequestInput(
      @NotBlank(message = "幂等键不能为空") @Size(max = 128) String idempotencyKey,
      @NotNull(message = "必须指定待发货包裹") UUID pendingParcelId,
      @NotBlank(message = "承运商代码不能为空") @Size(max = 64) String carrierCode,
      @NotBlank(message = "发件人不能为空") @Size(max = 200) String shipFromName,
      @NotBlank(message = "发件国不能为空") @Pattern(regexp = "^[A-Z]{2}$", message = "发件国必须是两位大写国家代码") String shipFromCountry,
      @NotBlank(message = "收件人不能为空") @Size(max = 200) String shipToName,
      @NotBlank(message = "收件国不能为空") @Pattern(regexp = "^[A-Z]{2}$", message = "收件国必须是两位大写国家代码") String shipToCountry,
      @Size(max = 400) String shipToAddressLine,
      @Size(max = 120) String shipToCity,
      @Size(max = 32) String shipToPostalCode,
      @NotBlank(message = "币种不能为空") @Pattern(regexp = "^[A-Z]{3}$", message = "币种必须是三位大写代码") String declaredCurrency,
      @Size(max = 128) String labelTemplateRef,
      @NotNull(message = "申报明细不能为空（没有明细项承运商与海关都会拒收）")
          List<@Valid WaybillCustomsItemInput> customsItems) {
    public WaybillRequestInput {
      customsItems = customsItems == null ? List.of() : List.copyOf(customsItems);
    }
  }

  /**
   * 一条申报明细。
   *
   * <p>{@code quantity} 用 {@code BigDecimal} + NUMERIC(19,6)：0.5 件、1.5 kg 这类
   * 实数是常态，整数「件」假设会让重量口径与实物对不上。
   * {@code declaredValue} 必须等于 {@code unitPrice * quantity}（DB 层 CHECK）。
   */
  public record WaybillCustomsItemInput(
      @NotNull(message = "SKU 不能为空") UUID skuId,
      @NotBlank(message = "SKU 编码不能为空") @Size(max = 64) String skuRef,
      @NotBlank(message = "品名不能为空") @Size(max = 300) String productName,
      @NotNull(message = "申报数量不能为空") @DecimalMin(value = "0", inclusive = false, message = "申报数量必须大于 0") BigDecimal quantity,
      @NotBlank(message = "单位不能为空") @Size(max = 16) String unitCode,
      @NotNull(message = "申报单价不能为空") @DecimalMin(value = "0", message = "申报单价不能为负") BigDecimal unitPrice,
      @NotBlank(message = "HS 编码不能为空") @Size(max = 32) String hsCode,
      @Size(max = 2) String originCountry,
      UUID destinationVersionId) {}

  /**
   * 批量申请（**一次请求不许跨承运商**）。
   *
   * <p>蓝图那句「批量申请要按渠道限流排队，否则触发接口限流影响整批」的
   * 前一半就是这一条：A 家与 B 家的配额互不相通，混在一次请求里排队时
   * 没法算每一票该等哪个窗口。9.4.1.1 的批量任务已经钉过同一条，
   * 本模块沿用并在服务层显式拒绝（错误消息把两个承运商都列出来）。
   */
  public record WaybillBatchRequestInput(
      @NotBlank(message = "幂等键前缀不能为空") @Size(max = 96) String idempotencyKeyPrefix,
      @NotBlank(message = "承运商代码不能为空") @Size(max = 64) String carrierCode,
      @NotBlank(message = "发件人不能为空") @Size(max = 200) String shipFromName,
      @NotBlank(message = "发件国不能为空") @Pattern(regexp = "^[A-Z]{2}$", message = "发件国必须是两位大写国家代码") String shipFromCountry,
      @NotBlank(message = "收件人不能为空") @Size(max = 200) String shipToName,
      @NotBlank(message = "收件国不能为空") @Pattern(regexp = "^[A-Z]{2}$", message = "收件国必须是两位大写国家代码") String shipToCountry,
      @Size(max = 400) String shipToAddressLine,
      @Size(max = 120) String shipToCity,
      @Size(max = 32) String shipToPostalCode,
      @NotBlank(message = "币种不能为空") @Pattern(regexp = "^[A-Z]{3}$", message = "币种必须是三位大写代码") String declaredCurrency,
      @Size(max = 128) String labelTemplateRef,
      @NotNull(message = "必须指定待发货包裹") @Positive(message = "包裹不能为空") List<UUID> pendingParcelIds,
      @NotNull(message = "申报明细不能为空") List<@Valid WaybillBatchCustomsInput> customsItems) {
    public WaybillBatchRequestInput {
      pendingParcelIds = pendingParcelIds == null ? List.of() : List.copyOf(pendingParcelIds);
      customsItems = customsItems == null ? List.of() : List.copyOf(customsItems);
    }
  }

  /**
   * 批量申请的申报明细。
   *
   * <p><b>{@code pendingParcelId} 是必填</b>：一张面单逐件打印，每一件印的是
   * <b>那一件</b>的口径。若允许一条明细不指定包裹而是「整批共用」，
   * 那么件 B 的面单上会印着件 A 的件数与 HS 编码 —— 承运商会照着错的申报放行，
   * 海关按错的口径查验，扣货与罚款是 10.5 明写的后果。
   *
   * <p>所以这里<b>刻意不提供</b>「整批共用一份」的写法：每条明细必须说清它属于哪个包裹。
   * 真的整批都是同一款货时，为每个包裹各写一条即可（{@code perParcel=true} 即该语义）。
   */
  public record WaybillBatchCustomsInput(
      @NotNull(message = "必须指���这条申报属于哪个包裹（面单逐件打印，口径逐件成立）")
          UUID pendingParcelId,
      @NotNull(message = "SKU 不能为空") UUID skuId,
      @NotBlank(message = "SKU 编码不能为空") @Size(max = 64) String skuRef,
      @NotBlank(message = "品名不能为空") @Size(max = 300) String productName,
      @NotNull(message = "申报数量不能为空") @DecimalMin(value = "0", inclusive = false, message = "申报数量必须大于 0") BigDecimal quantity,
      @NotBlank(message = "单位不能为空") @Size(max = 16) String unitCode,
      @NotNull(message = "申报单价不能为空") @DecimalMin(value = "0", message = "申报单价不能为负") BigDecimal unitPrice,
      @NotBlank(message = "HS 编码不能为空") @Size(max = 32) String hsCode,
      @Size(max = 2) String originCountry,
      UUID destinationVersionId) {}

  /** 批量提交结果：逐条成功 / 逐条失败，外加「哪些没进队列、为什么」。 */
  public record WaybillBatchResult(
      String carrierCode,
      int submitted,
      int skipped,
      int failed,
      List<WaybillBatchEntryResult> entries,
      List<String> notes) {}

  /** 批量里的一条：成功则给出单号，失败则给出**可读**原因而不是异常堆栈。 */
  public record WaybillBatchEntryResult(
      UUID pendingParcelId,
      String pendingParcelRef,
      String requestNo,
      UUID requestId,
      String waybillNo,
      boolean created,
      boolean idempotentReplay,
      String failureReason) {}

  /** 从队列放行一批（**逐条结果**，不是同步阻塞循环）。 */
  public record WaybillDispatchInput(
      @NotBlank(message = "承运商代码不能为空") @Size(max = 64) String carrierCode,
      @Positive(message = "单次放行条数必须大于 0") Integer maxDispatches) {}

  /** 放行结果：成功几条、限流几条、失败几条、逐条原因。 */
  public record WaybillDispatchResult(
      String carrierCode,
      int dispatched,
      int succeeded,
      int rateLimited,
      int failed,
      int skippedNotYetDue,
      List<WaybillDispatchEntryResult> entries,
      List<String> notes) {}

  public record WaybillDispatchEntryResult(
      UUID requestId,
      String requestNo,
      WaybillAttemptOutcome outcome,
      String outcomeLabel,
      String waybillNo,
      String failureReason,
      OffsetDateTime notBefore) {}

  /** 失败重试：显式重新入队，仍受幂等键约束。 */
  public record WaybillRetryInput(@Size(max = 400) String reason) {}

  public record WaybillRetryResult(
      UUID requestId,
      String requestNo,
      boolean requeued,
      OffsetDateTime notBefore,
      int retryCount,
      String failureReason,
      String note) {}

  /** 作废（还没出号的才允许作废）。 */
  public record WaybillCancelInput(@NotBlank(message = "作废必须写明原因") @Size(max = 400) String reason) {}

  public record WaybillCarrierInput(
      @NotBlank(message = "承运商代码不能为空")
          @Pattern(regexp = "^[A-Z0-9_\\-]{2,64}$", message = "承运商代码只能是大写字母、数字、下划线或中划线")
          String code,
      @NotBlank(message = "承运商名称不能为空") @Size(max = 200) String displayName,
      @NotBlank(message = "承运渠道不能为空") String carrierChannel,
      @NotBlank(message = "适配器键不能为空")
          @Pattern(regexp = "^[a-z0-9_\\-]{2,64}$", message = "适配器键只能是小写字母、数字、下划线或中划线")
          String adapterKey,
      @Positive(message = "窗口内请求上限必须大于 0") Integer rateLimitPerWindow,
      @Positive(message = "窗口长度必须大于 0") Integer rateWindowSeconds,
      @Positive(message = "重试上限必须大于 0") Integer maxRetryAttempts,
      @Size(max = 400) String remark,
      @Size(max = 400) String unavailableReason) {}
}
