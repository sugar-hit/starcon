package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 2.5.2.1 常用回写操作 —— 批量改库存 / 改价 / 下架 / 回传物流。
 *
 * <p>蓝图加粗句：「**批量操作不等于事务**：每条独立成败、部分失败要如实回报，不能因一条失败回滚全部（跨服务无全局事务）。」 这三个视图类型就是把那句话变成可断言的返回值：
 *
 * <ul>
 *   <li>{@link WritebackItemView} —— 逐条结果：每一条都有自己的成败与原因
 *   <li>{@link WritebackBatchSummary} —— 批次聚合：部分成功是真的一个状态，不是措辞
 *   <li>{@link WritebackBatchView} —— 批次头 + 逐条结果，一次读全
 * </ul>
 *
 * <p>金额与数量一律 {@link BigDecimal}（JSON number），前端只原样展示，不做算术。
 */
public final class WritebackBatchModels {
  private WritebackBatchModels() {}

  // ==================================================================
  // 可回写目标登记簿
  // ==================================================================

  /**
   * 可回写目标。
   *
   * <p>LISTING 是平台商品/变体（改库存、改价、下架的对象），SHIPMENT 是平台订单（回传物流的对象）。 两者不混：给订单改价在这个模型里根本无处安放，硬塞进去只会造出一条平台侧必然失败的写操作。
   */
  public record WritebackTargetView(
      UUID id,
      UUID shopId,
      String shopName,
      String platformCode,
      String targetType,
      String externalRef,
      String listingRef,
      String skuCode,
      @Schema(nullable = true) BigDecimal currentStock,
      @Schema(nullable = true) BigDecimal currentPrice,
      @Schema(nullable = true) String currency,
      String status,
      String blockedReason,
      Instant createdAt,
      Instant updatedAt) {}

  /**
   * 登记 / 更新一个可回写目标。同一（店铺, 类型, 平台键）重复登记是更新，不是造第二条。
   *
   * <p>⚠️ 所有「平台上本来就没有」的字段都标了 {@code @Schema(nullable = true)}：它们在 DB 里是
   * {@code NOT NULL DEFAULT ''}，Java 侧就该允许 null（服务层写空串）。不标的话 codegen 会把它们
   * 生成为**必填 string**，前端为了过类型检查只能发 {@code ""} —— 契约开始说谎，而契约是前后端
   * 唯一共享面。
   */
  public record WritebackTargetInput(
      @NotNull UUID shopId,
      @NotNull TargetType targetType,
      @NotBlank @Size(max = 128) String externalRef,
      @Schema(nullable = true) @Size(max = 128) String listingRef,
      @Schema(nullable = true) @Size(max = 64) String skuCode,
      @Schema(nullable = true) @DecimalMin(value = "0") @Digits(integer = 11, fraction = 3)
          BigDecimal currentStock,
      @Schema(nullable = true) @DecimalMin(value = "0.0001") @Digits(integer = 14, fraction = 4)
          BigDecimal currentPrice,
      @Schema(nullable = true) @Pattern(regexp = "^[A-Z]{3}$", message = "币种必须是三位大写 ISO 4217 代码")
          String currency,
      @Schema(nullable = true) @Size(max = 64) String sourceRef) {}

  /** 封禁 / 解封。封禁是批量回写里最常见的「单条失败」真实来源。 */
  public record WritebackTargetStatusInput(
      @NotNull TargetStatus status, @Size(max = 400) String reason) {}

  public record WritebackTargetPage(
      List<WritebackTargetView> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      WritebackTargetSummary summary) {}

  /** 候选池汇总：真聚合，不受分页影响 —— 否则「这一屏看到的」会与「实际总量」不一致。 */
  public record WritebackTargetSummary(
      long total,
      long activeCount,
      long blockedCount,
      long listingCount,
      long shipmentCount,
      long shops) {}

  public enum TargetType {
    LISTING,
    SHIPMENT
  }

  public enum TargetStatus {
    ACTIVE,
    BLOCKED
  }

  // ==================================================================
  // 批量回写
  // ==================================================================

  /**
   * 批量动作。
   *
   * <p>四种动作对应 {@link BatchKind}。DELIST 落到平台侧是 operation='PUBLISH' + payload.delist=true（理由见 V169 文件头第四节），所以前端展示的是业务语义「下架」，不是表里的原始枚举。
   */
  public record WritebackBatchInput(
      @NotNull BatchKind kind,
      /** 单店铺批量；为 null 时按 filter 跨店铺批量（蓝图：「按店铺与筛选条件批量执行」）。 */
      @Schema(nullable = true) UUID shopId,
      /** 筛选条件；与 shopId 二选一或同时给（同时给时取交集）。 */
      @Schema(nullable = true) @Valid TargetFilter filter,
      /** 勾选的记录范围；给了就按它逐条执行，不给就按 filter 解析候选集。 */
      @Schema(nullable = true) @Size(max = 500) List<UUID> targetIds,
      @NotNull @Valid BatchParameters parameters,
      /** 业务幂等键：同一键重复提交复用既有批次，不产生第二批平台动作。不给则由服务端按请求算摘要。 */
      @Schema(nullable = true) @Size(max = 160) String idempotencyKey,
      @Schema(nullable = true) @Size(max = 400) String reason) {}

  /** 候选集筛选条件 —— 每一条都落到真实 SQL 谓词，不是前端过滤。 */
  public record TargetFilter(
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) TargetType targetType,
      /** 平台键或 SKU 码前缀命中（大小写不敏感）。 */
      @Schema(nullable = true) @Size(max = 128) String keyword,
      @Schema(nullable = true) TargetStatus status) {}

  /**
   * 批量参数。
   *
   * <p>公共值 + 逐目标覆盖：批量回传物流里每票货的运单号本就各不相同，只给一个公共运单号等于逼着运营分 N 次批量 —— 而 N 次批量之间还可能漏勾。所以 {@link #perTarget} 按目标 id 覆盖公共值。
   *
   * <p>合并顺序固定为「公共值 → 逐目标覆盖」，同名字段以逐目标为准。
   *
   * <p>⚠️ {@code perTarget} 是**具名 record 的列表而不是 Map**：Map 的值类型会被 springdoc 推成
   * {@code object}，codegen 出一个空对象 {@code {}}，前端既拿不到运单号也过不了类型检查
   * （本仓库 9.6.1.3 已踩过并改为 record）。目标 id 由调用方写在每条自己的 {@code targetId} 上 ——
   * Map 的 key 在 OpenAPI 里本来也保不住。
   */
  public record BatchParameters(
      @Schema(nullable = true) @DecimalMin(value = "0") @Digits(integer = 11, fraction = 3)
          BigDecimal stock,
      @Schema(nullable = true) @DecimalMin(value = "0.0001") @Digits(integer = 14, fraction = 4)
          BigDecimal price,
      @Schema(nullable = true) @Pattern(regexp = "^[A-Z]{3}$", message = "币种必须是三位大写 ISO 4217 代码")
          String currency,
      @Schema(nullable = true) @Size(max = 64) String trackingNumber,
      @Schema(nullable = true) @Size(max = 32) String carrierCode,
      /** 发货时刻：OffsetDateTime，绑 timestamptz 列必须写 ?::timestamptz。 */
      @Schema(nullable = true) OffsetDateTime shippedAt,
      /** 逐目标覆盖：只写要覆盖的字段，null 表示沿用公共值。 */
      @Schema(nullable = true) List<PerTargetParameters> perTarget) {}

  /** 逐目标覆盖项。{@code targetId} 必填 —— 没有它就不知道这一条盖的是谁。 */
  public record PerTargetParameters(
      @NotBlank @Size(max = 36) String targetId,
      @Schema(nullable = true) @DecimalMin(value = "0") @Digits(integer = 11, fraction = 3)
          BigDecimal stock,
      @Schema(nullable = true) @DecimalMin(value = "0.0001") @Digits(integer = 14, fraction = 4)
          BigDecimal price,
      @Schema(nullable = true) @Size(max = 64) String trackingNumber,
      @Schema(nullable = true) @Size(max = 32) String carrierCode,
      @Schema(nullable = true) OffsetDateTime shippedAt) {}

  public enum BatchKind {
    UPDATE_STOCK,
    UPDATE_PRICE,
    DELIST,
    SHIP_UPDATE
  }

  /** 逐条结果。 */
  public record WritebackItemView(
      int position,
      @Schema(nullable = true) UUID targetId,
      UUID shopId,
      String targetType,
      String externalRef,
      String listingRef,
      @Schema(nullable = true) String skuCode,
      /** QUEUED = 已生成平台任务等回执；REJECTED = 生成前被拦下，没发出去。 */
      String itemResult,
      @Schema(nullable = true) UUID operationId,
      /** 平台任务的**真实**状态（现查 platform_operation，不是快照）。 */
      String operationStatus,
      @Schema(nullable = true) String idempotencyKey,
      String reason,
      @Schema(nullable = true) Instant shippedAt,
      @Schema(nullable = true) BigDecimal stock,
      @Schema(nullable = true) BigDecimal price,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String trackingNumber,
      @Schema(nullable = true) String carrierCode,
      Instant createdAt) {}

  /**
   * 批次聚合。
   *
   * <p>{@code outcome} 的取值就是「部分失败要如实回报」这句话的实现：
   *
   * <ul>
   *   <li>SUCCEEDED —— 选中的每一条都成功
   *   <li>PARTIAL —— 有成功也有失败（**部分成功**，这是本模块的主状态）
   *   <li>FAILED —— 选中的每一条都失败
   *   <li>IN_PROGRESS —— 还有 PENDING/SENDING/UNKNOWN，结果未定
   * </ul>
   *
   * <p>UNKNOWN 单列成一类而不是并进失败：结果未知时平台可能已经执行，把它算成失败会让运营去重试，从而造成重复扣减 —— 2.5.1.2 整节讲的就是这件事。
   */
  public record WritebackBatchSummary(
      String outcome,
      int selected,
      int queued,
      int rejected,
      int succeeded,
      int failed,
      int unknown,
      int pending) {}

  public record WritebackBatchView(
      UUID id,
      String batchNo,
      BatchKind kind,
      @Schema(nullable = true) UUID shopId,
      String idempotencyKey,
      int selectedCount,
      int rejectedCount,
      String reason,
      String filterSnapshot,
      String paramSnapshot,
      WritebackBatchSummary summary,
      List<WritebackItemView> items,
      Instant createdAt) {}

  public record WritebackBatchPage(
      List<WritebackBatchView> items,
      int page,
      int size,
      long totalElements,
      int totalPages,
      WritebackBatchSummary summary) {}

  /** 试算：不生成任何任务，只回答「这条筛选会命中多少条、其中多少条会被拦下」。 */
  public record WritebackBatchPreview(
      BatchKind kind,
      int matched,
      int applicable,
      int blocked,
      int missingParameters,
      List<WritebackPreviewIssue> issues,
      WritebackBatchParameters parameters) {}

  public record WritebackPreviewIssue(
      @Schema(nullable = true) UUID targetId,
      String externalRef,
      String code,
      String message) {}

  /** 某个动作实际会用到的参数（合并公共值后的口径），供界面回显。 */
  public record WritebackBatchParameters(
      @Schema(nullable = true) BigDecimal stock,
      @Schema(nullable = true) BigDecimal price,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String trackingNumber,
      @Schema(nullable = true) String carrierCode,
      @Schema(nullable = true) OffsetDateTime shippedAt,
      int perTargetCount) {}
}
