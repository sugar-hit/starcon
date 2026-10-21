package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.4.1.1 调整单管理的传输模型。
 *
 * <p>蓝图原话：「**发起库存调整**（盘盈盘亏、损坏、报废、错发纠正、平台仓差异），填写原因与凭证，
 * **支持批量调整**。」输入：「调整请求（bucket、数量、原因码、附件）；{@code expected_version}。」
 * 输出：「调整单（草稿/待审/已批/已执行）；库存分录（反向或正向）。」
 * 以及红线：「**调整必须走单据**，不允许在明细页直接改余额——没有单据就没有责任人与原因。」
 *
 * <p><b>与 7.4.2.2 盘点差异处理（V62）的调整单是两套东西</b>：V62 的调整单头上有 NOT NULL 的
 * {@code sheet_id}，也就是说**没有盘点表就没有调整单**；本模块是**通用调整单** —— 损坏、报废、
 * 错发纠正这些场景根本没有盘点表。两者并存，落地在各自的表与端点上。
 *
 * <p><b>审批动作不属于本模块</b>：蓝图把「按金额/数量阈值审批」放在 7.4.1.2（上游 17 卷审批中心）。
 * 本模块实现「建单 + 提交待审 + 过账落分录」，批准这一步走**独立权限码 + 必填理由 + 职责分离**
 * 的最小口径；阈值规则、批量审批、驳回工作流归 7.4.1.2。状态枚举**现在就写全**，
 * 7.4.1.2 交付时不必 ALTER 本模块的表（跨车道 DDL 回归面 = 0）。
 */
public final class InventoryAdjustmentModels {

  private InventoryAdjustmentModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 调整单状态机：DRAFT → PENDING_APPROVAL → APPROVED → POSTED，另有 REJECTED / CANCELLED。
   *
   * <p>蓝图的四个状态「草稿 / 待审 / 已批 / 已执行」在这里逐一对应；两个终止态是为了让
   * 「驳回」与「作废」用不同的词 —— 把它们合成一个词就没法用 CHECK 区分「可回到桌面的」
   * 与「终态的」。
   */
  public enum InventoryAdjustmentStatus {
    DRAFT("草稿"),
    PENDING_APPROVAL("待审批"),
    APPROVED("已批待入账"),
    REJECTED("已驳回"),
    POSTED("已入账"),
    CANCELLED("已作废");

    private final String label;

    InventoryAdjustmentStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 调整原因码（受控词表，DB 层 {@code CHECK} + {@code inventory_adjustment_reason} 词表表双重约束）。
   *
   * <p>六类与蓝图点名的五类逐字对应（盘盈盘亏拆成两个码，因为两者的方向相反、留痕口径不同）。
   *
   * <p><b>本枚举只保留「码 + 可用性」这两个必须由代码强制的��实</b>；中文名、说明与不可用原因
   * 全部存在 {@code inventory_adjustment_reason} 词表表里并由服务层读出 —— 词表只有一份，
   * 改文案不用改代码，代码常量与词表也不会漂移。
   *
   * <p><b>{@link #PLATFORM_VARIANCE} 不可用</b>：平台仓（FBA）库存是**观测值**不是自有账本 ——
   * V55 的 {@code guard_platform_warehouse_no_ledger} 直接禁止给平台仓写 balance / bucket 行，
   * 所以平台仓**根本没有 stock_bucket 行可调**。把平台观测值当成自货调整掉，等于制造出
   * 「平台仓也有自有账本」的第二个真相（8.1.1.1 早就警告过：直接求和会把 300+310 算成 610
   * 那个不存在的数）。本模块**显式拒绝**该码并说明去向，不静默接受。
   */
  public enum InventoryAdjustmentReason {
    OVERAGE(true, ""),
    SHORTAGE(true, ""),
    DAMAGE(true, ""),
    SCRAP(true, ""),
    MISSHIP_CORRECTION(true, ""),
    PLATFORM_VARIANCE(false, "10.1.1.1 / 8.1.1.1");

    private final boolean available;
    private final String ownerModule;

    InventoryAdjustmentReason(boolean available, String ownerModule) {
      this.available = available;
      this.ownerModule = ownerModule;
    }

    /** 该原因码当前是否可用。不可用的码保留在词表里（vocabulary 完整），但建行时会被拒绝。 */
    public boolean available() {
      return available;
    }

    /** 不可用时归哪个模块。 */
    public String ownerModule() {
      return ownerModule;
    }
  }

  // ------------------------------------------------------------ 输入

  /**
   * 发起调整单（草稿）。
   *
   * <p>{@code lines} 是**批量调整**的入口：一次给多行，落一张单（蓝图：「支持批量调整」）。
   * 零行会被拒绝 —— 悄悄建一张零行单据会让台账上看起来像「处理过了」。
   *
   * @param reasonCode 单据级原因码（受控值）；行可以覆盖它
   * @param note 单据级原因原文（自由文本，给人看）
   * @param lines 调整行（批量）
   * @param attachments 凭证附件（catalog 媒体库逻辑引用 + 快照元数据）
   * @param sourceOrderId 上游 6.4.2.2 不良品处置单 id（**跨服务逻辑 ID**，不建 FK）
   * @param sourceInstructionId 上游 6.4.2.2 库存处置指令 id（**跨服务逻辑 ID**）
   */
  public record InventoryAdjustmentCreateInput(
      String reasonCode,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) List<InventoryAdjustmentLineInput> lines,
      @Schema(nullable = true) List<InventoryAdjustmentAttachmentInput> attachments,
      @Schema(nullable = true) UUID sourceOrderId,
      @Schema(nullable = true) UUID sourceInstructionId) {}

  /**
   * 一行调整请求。
   *
   * @param bucketId 目标库位明细（7.1.1.2 的最小事实单位）
   * @param qtyDelta 带符号数量（正=增加在手，负=减少在手）；精度与 {@code stock_bucket.on_hand} 同口径
   * @param reasonCode 行级原因码；不给则取单据级
   * @param expectedVersion **乐观锁**：读这行时看到的 {@code stock_bucket.version}；
   *     与当前不一致说明看到的是过期数量，服务层返回 409
   * @param note 行级原因原文
   */
  public record InventoryAdjustmentLineInput(
      UUID bucketId,
      BigDecimal qtyDelta,
      @Schema(nullable = true) String reasonCode,
      Long expectedVersion,
      @Schema(nullable = true) String note) {}

  /**
   * 凭证附件（catalog 媒体库的逻辑引用 + 快照元数据）。
   *
   * <p><b>没有上传端点</b>：媒体库归 catalog（3.4.1.1），是独立库，上传动作在那边完成；
   * 本模块只登记引用与快照（媒体 id + 文件名 + 类型 + 大小），挂载时刻由服务端写。
   *
   * @param mediaId catalog 媒体 id（逻辑引用，**不建跨库 FK**）
   * @param fileName 文件名快照
   * @param mediaType MIME 类型快照
   * @param sizeBytes 字节数快照
   */
  public record InventoryAdjustmentAttachmentInput(
      UUID mediaId, String fileName, @Schema(nullable = true) String mediaType, Long sizeBytes) {}

  /** 提交 / 批准 / 驳回 / 作废 / 入账共用它：一句人读得懂的话 + 当时的判断。 */
  public record InventoryAdjustmentNoteInput(@Schema(nullable = true) String note) {}

  // ------------------------------------------------------------ 调整单

  /**
   * 一张调整单的头部。
   *
   * <p>行数与三个数量**全部现算**，表里没有对应列：它们是明细行的函数，另存一份就要在
   * 每次加行/过账后同步，而漏同步一次就会出现「单据显示已入账但还差几行」这种说不清的错。
   *
   * @param status 状态机当前位置；POSTED 只能从 APPROVED 到达（DB 级约束）
   * @param gainQty 增调合计（正数）
   * @param lossQty 减调合计（正数，绝对值）
   * @param netQty 净调整量 = 增调 − 减调，带符号
   */
  public record InventoryAdjustmentOrder(
      UUID id,
      String code,
      InventoryAdjustmentStatus status,
      String reasonCode,
      String reasonLabel,
      String note,
      @Schema(nullable = true) String sourceType,
      @Schema(nullable = true) UUID sourceOrderId,
      @Schema(nullable = true) UUID sourceInstructionId,
      String sourceReference,
      long lineCount,
      long gainLineCount,
      long lossLineCount,
      BigDecimal gainQty,
      BigDecimal lossQty,
      BigDecimal netQty,
      @Schema(nullable = true) UUID submittedByUserId,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      @Schema(nullable = true) UUID decidedByUserId,
      @Schema(nullable = true) OffsetDateTime decidedAt,
      @Schema(nullable = true) String decisionNote,
      @Schema(nullable = true) UUID postedByUserId,
      @Schema(nullable = true) OffsetDateTime postedAt,
      @Schema(nullable = true) UUID cancelledByUserId,
      @Schema(nullable = true) OffsetDateTime cancelledAt,
      UUID createdByUserId,
      OffsetDateTime createdAt) {}

  /**
   * 调整单一行 = 一个 bucket 上的一次性数量快照。
   *
   * @param expectedVersion 建行时读到的 bucket 版本（乐观锁基准）
   * @param currentBucketVersion 查询那一刻的 bucket 版本：与 expectedVersion 不一致即说明这行已过期
   * @param bucketOnHand 查询那一刻的 bucket 在手量：让人看得见「这行是基于哪个数算出来的」
   */
  public record InventoryAdjustmentOrderLine(
      UUID id,
      UUID orderId,
      UUID bucketId,
      UUID balanceId,
      UUID legalEntityId,
      UUID skuId,
      String skuCode,
      String skuName,
      UUID warehouseId,
      String warehouseCode,
      UUID locationId,
      String locationCode,
      @Schema(nullable = true) UUID lotId,
      String lotCode,
      UUID qualityStatusId,
      String qualityStatusCode,
      BigDecimal qtyDelta,
      String reasonCode,
      String reasonLabel,
      String note,
      long expectedVersion,
      @Schema(nullable = true) Long currentBucketVersion,
      @Schema(nullable = true) BigDecimal bucketOnHand,
      @Schema(nullable = true) OffsetDateTime postedAt,
      @Schema(nullable = true) UUID postedByUserId,
      @Schema(nullable = true) UUID entryId,
      @Schema(nullable = true) UUID transactionId) {}

  /** 凭证附件（catalog 媒体库的逻辑引用 + 快照元数据）。 */
  public record InventoryAdjustmentAttachment(
      UUID id,
      UUID orderId,
      UUID mediaId,
      String fileName,
      String mediaType,
      @Schema(nullable = true) Long sizeBytes,
      OffsetDateTime mountedAt,
      UUID mountedByUserId) {}

  /** 调整单列表（真分页）。 */
  public record InventoryAdjustmentOrderPage(
      List<InventoryAdjustmentOrder> orders, long total, int page, int size) {}

  /** 调整单详情 = 头部 + 全部行 + 凭证清单 + 完整审计时间线。 */
  public record InventoryAdjustmentOrderDetail(
      InventoryAdjustmentOrder order,
      List<InventoryAdjustmentOrderLine> lines,
      List<InventoryAdjustmentAttachment> attachments,
      List<InventoryAdjustmentAuditEntry> auditTrail) {}

  /**
   * 入账结果。
   *
   * @param transactionId 写进 {@code inventory_transaction} 的业务单 id（ADJUSTMENT 类型）
   * @param entryCount 写进 {@code inventory_entry} 的分录条数
   * @param balanceIds 被改动的余额行（去重）——「动了哪几行账」必须能立刻答出来
   */
  public record InventoryAdjustmentPostResult(
      InventoryAdjustmentOrder order,
      UUID transactionId,
      int entryCount,
      List<UUID> balanceIds,
      BigDecimal postedQty,
      String note) {}

  /** 审计时间线上的一条：谁在什么时候把这张单推到了哪一步。 */
  public record InventoryAdjustmentAuditEntry(
      UUID id,
      UUID orderId,
      String action,
      String actionLabel,
      UUID actorUserId,
      OffsetDateTime occurredAt,
      String detail) {}

  // ------------------------------------------------------------ 能力与选项

  /** 原因码选项（前端据此渲染下拉；{@code available=false} 的项要显示成不可选并说明去向）。 */
  public record InventoryAdjustmentReasonOption(
      String value, String label, String description, boolean available, String ownerModule, String note) {}

  /**
   * 一条能力声明。
   *
   * <p>仓库既有约定（8.1.2.1 / 7.4.2.1 / 6.4.2.2 一致）：未接入的东西用
   * {@code available=false} + {@code ownerModule} + {@code note} 表达，**不建占位表、
   * 不返回空集冒充成功**。
   */
  public record InventoryAdjustmentCapability(
      String key, String label, boolean available, String ownerModule, String href, String note) {}

  /**
   * 状态机的一条合法转移。
   *
   * @param allowed 该转移当前是否允许
   * @param permission 执行该动作所需的权限码（批准与其它动作**刻意不同**）
   */
  public record InventoryAdjustmentTransition(
      String action,
      String from,
      String to,
      boolean allowed,
      String permission,
      String note) {}

  /**
   * 调整单能力说明。
   *
   * <p>前端据此渲染选项与提示，而不是把规则硬编码在界面上 —— 规则改了就该只改一处。
   */
  public record InventoryAdjustmentCapabilities(
      List<InventoryAdjustmentReasonOption> reasons,
      List<InventoryAdjustmentStatus> statuses,
      List<InventoryAdjustmentTransition> transitions,
      List<InventoryAdjustmentCapability> dependencies,
      String approvalNote,
      String postingNote,
      String quantityNote,
      String attachmentNote) {}

  // ------------------------------------------------------------ 6.4.2.2 来源

  /**
   * 6.4.2.2 不良品处置单（**trade 服务**，只读投影）。
   *
   * <p>跨服务边界：trade 是这张单的事实所有者，本服务只经授权 HTTP 读它，
   * **不跨库读它的表、不建跨库 FK**（AGENTS.md 模块边界纪律）。
   */
  public record InventoryAdjustmentDefectiveSource(
      UUID id,
      String dispositionNo,
      String status,
      String statusLabel,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      @Schema(nullable = true) String batchNo,
      BigDecimal remainingQuantity,
      long instructionCount) {}

  /** 6.4.2.2 处置单的一条库存处置指令（可作为调整单的上游来源）。 */
  public record InventoryAdjustmentDefectiveInstruction(
      UUID id,
      UUID orderId,
      String dispositionNo,
      String action,
      String actionLabel,
      BigDecimal quantity,
      String ownerModule,
      String status,
      String statusLabel,
      String reason,
      @Schema(nullable = true) String externalReference) {}

  /**
   * 6.4.2.2 来源候选（列表 + 指令）。
   *
   * @param available 上游是否可用（trade 不可达时为 false）
   * @param note 不可用时说明原因与影响
   */
  public record InventoryAdjustmentDefectiveSourcePage(
      List<InventoryAdjustmentDefectiveSource> orders,
      List<InventoryAdjustmentDefectiveInstruction> instructions,
      long total,
      boolean available,
      String note) {}
}
