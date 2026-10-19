package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.2.2.1 退货入库的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 原文：
 *
 * <blockquote>
 * 职责：登记退回实物：扫码核对 SKU 与序列号、清点数量、拍照留证、判定外包装与商品状态。
 * 输入：退货实收登记（SKU、数量、批次/序列号、照片）；退货授权单。
 * 输出：退货实收记录 + 关联授权行分配；待检库存入库分录（<b>不是可售</b>）。
 * 关系：上游 = 11.2.1.2；下游 = 11.2.2.2、11.2.3.1。<b>退回商品默认进待检状态</b>，这是硬规则——直接进可售会把残次品当新品卖出去。
 * </blockquote>
 *
 * <p><b>本文件最重要的四件事</b>：
 *
 * <ol>
 *   <li><b>退回商品默认进待检，绝不进可售</b>（硬规则 1）。传输层就要说清：
 *       {@link ReturnReceiptRow#sellableQtyDelta()} <b>恒为 0</b>，
 *       {@link ReturnInventoryPosting#inventoryState()} <b>恒为 PENDING_INSPECTION</b>，
 *       并且 {@link ReturnReceiptDetail#inspectionStatus()} 永远带归属模块号 ——
 *       界面拿不到一个「待质检」枚举值以外的取值，也就没有「直接转可售」的按钮。</li>
 *   <li><b>两个判定维度分别传输</b>（硬规则 2）：{@link ReturnReceiptLine#packagingCondition()} 与
 *       {@link ReturnReceiptLine#goodsCondition()} <b>各带自己的 label</b>，
 *       不合成一个 condition。蓝图 11.2.3.1 的加粗句「包装破损但商品完好可判可再销售，
 *       缺少配件则应判残次」在任何单值列里都无法表达。</li>
 *   <li><b>实收挂在授权行上，额度实时算</b>（硬规则 3）：{@link ReturnReceivableRow} 把
 *       「授权量 / 已占 / 剩余可收」三个数并排给出，界面在<b>扫码那一刻</b>就能告诉操作员
 *       还能收几件，而不是录完被后端拒了才知道。</li>
 *   <li><b>未交付的下游一律 delivered=false + 归属模块号</b>：11.2.2.2 实收分配、
 *       11.2.3.1 质检、11.2.3.2 处置、7 库存应用分录<b>全部未交付</b>，
 *       <b>不建影子表、不编状态</b>。</li>
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code ReturnReceipt*} / {@code ReturnGoods*} /
 * {@code ReturnPackaging*} / {@code ReturnInventoryPosting*} 前缀，全仓 grep 确认与既有类型零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 */
public final class ReturnReceiptModels {

  private ReturnReceiptModels() {}

  // ================================================================ 枚举

  /**
   * 实收单状态机：一个状态列到底。
   *
   * <p>⚠️ <b>没有 INSPECTED / RESALABLE / DEFECTIVE / SCRAPPED</b>：质检与处置属
   * 11.2.3.1 / 11.2.3.2，<b>未交付</b>。在本表加一个「已质检」状态会让界面显示成质检已完成 ——
   * 退回商品默认进待检，把它当已质检处理就等于跳过质检把残次品当新品卖出去。
   *
   * <p>⚠️ {@code POSTED} <b>不可逆</b>：入库那一刻待检分录已经生成，
   * 撤销等于「货不在待检区了」而账上还在。要回冲属 11.2.3.2，未交付。
   */
  public enum ReturnReceiptStatus {
    DRAFT("草稿"),
    POSTED("已入库，退回商品进待检"),
    CANCELLED("已撤销");

    private final String label;

    ReturnReceiptStatus(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }

    /** 终态：内容与行是证据不是草稿，不能再改（DB 触发器同一纪律）。 */
    public boolean terminal() {
      return this == CANCELLED;
    }

    /** 已入库（已生成待检分录）。本模块唯一的库存事实。 */
    public boolean posted() {
      return this == POSTED;
    }

    /** 还在录（还要管）。 */
    public boolean open() {
      return this == DRAFT;
    }
  }

  /**
   * 这个包裹是怎么到的。
   *
   * <p>⚠️ 与「指令怎么送达买家」（V165 的 delivery_channel）<b>不是同一件事</b>：
   * 那个决定买家<b>怎么看到指引</b>，这里决定<b>货怎么到的</b>。
   * 压成一个字段会让「邮件已发但买家自己把货送到门店」这种真实且常见的状态无处安放。
   */
  public enum ReturnReceiptChannel {
    CARRIER("承运商送达"),
    BUYER_DROPOFF("买家自行送抵"),
    WALK_IN("门店直接收货");

    private final String label;

    ReturnReceiptChannel(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }

    /** 必须带承运商与运单号的渠道（V172 表级 CHECK）。 */
    public boolean requiresWaybill() {
      return this == CARRIER;
    }
  }

  /**
   * 判定维度①：<b>外包装</b>。
   *
   * <p>⚠️ 与 {@link ReturnGoodsCondition} 是<b>两个独立维度</b>，刻意不合成一个 condition。
   * 「外箱压瘪、里面的货完好」在退货里非常常见（11.2.3.1 的加粗句正是拿它举例），
   * 压成单值之后质检员只能去猜「这个值说的是包装还是货」，
   * 而这两个维度的处置结论可能完全相反。
   */
  public enum ReturnPackagingCondition {
    INTACT("外包装完好"),
    DAMAGED("外包装破损"),
    DESTROYED("外包装损毁"),
    MISSING("无外包装"),
    NOT_INSPECTED("未判定");

    private final String label;

    ReturnPackagingCondition(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }

    /** 尚未判定 —— <b>入库前不允许</b>（V172 触发器 guard_return_receipt_posting）。 */
    public boolean undecided() {
      return this == NOT_INSPECTED;
    }
  }

  /**
   * 判定维度②：<b>商品状态</b>。
   *
   * <p>⚠️ 同上，与 {@link ReturnPackagingCondition} 独立。
   * {@code MISSING_PARTS}（配件缺失）刻意单列而不并进 DEFECTIVE：
   * 蓝图 11.2.3.1 说「<b>缺少配件则应判残次</b>」—— 它与「商品本体损坏」是不同的责任归属
   * （配件缺常常是仓库漏装，商品损坏常常是运输或买家），索赔方向不一样。
   *
   * <p>{@code NOT_APPLICABLE} 与 {@code NOT_INSPECTED} <b>刻意分开</b>：
   * 前者是「这件货本来就没有这个维度」（散装 / 无包装无配件的 SKU），
   * 后者是「还没判」。合成一个值会让入库闸门把「不适用」当成「还没判」一直拦住。
   */
  public enum ReturnGoodsCondition {
    UNOPENED("未拆封"),
    OPENED_UNDAMAGED("已拆封、无损坏"),
    USED("有使用痕迹"),
    DAMAGED("商品损坏"),
    DEFECTIVE("商品有缺陷"),
    MISSING_PARTS("配件缺失"),
    NOT_INSPECTED("未判定"),
    NOT_APPLICABLE("不适用");

    private final String label;

    ReturnGoodsCondition(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }

    /** 尚未判定 —— <b>入库前不允许</b>。注意 {@code NOT_APPLICABLE} <b>不算</b>。 */
    public boolean undecided() {
      return this == NOT_INSPECTED;
    }
  }

  /** 照片角色。与两个判定维度一一对应，质检与纠纷举证按角色取图。 */
  public enum ReturnPhotoRole {
    PACKAGING("外包装"),
    GOODS("商品"),
    SERIAL_LABEL("序列号标签"),
    DAMAGE_CLOSEUP("损坏特写"),
    OTHER("其他");

    private final String label;

    ReturnPhotoRole(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }
  }

  /**
   * 照片来源。照抄 V150 signal_source / V156 owner_source 的同一条解法。
   *
   * <p>写成 {@code MODULE_*} 时必须给出 {@code sourceReference}（哪个桶 / 哪条同步规则），
   * 「这行是同步器写的」不能只是一句口头声明。
   */
  public enum ReturnPhotoSource {
    TENANT_MANUAL_UPLOAD("人工上传"),
    MODULE_18_OBJECT_STORAGE_SYNC("18 集成对象存储同步器（未交付）");

    private final String label;

    ReturnPhotoSource(String label) {
      this.label = label;
    }

    public String label() {
      return this.label;
    }

    /** 写成 MODULE_* 时必须给出来源引用。 */
    public boolean needsSourceReference() {
      return this != TENANT_MANUAL_UPLOAD;
    }
  }

  /**
   * 排序白名单。
   *
   * <p>排序键会进 SQL 文本，放任调用方传表达式就是一条注入。
   */
  public enum ReturnReceiptSort {
    CREATED_DESC("r.created_at desc, r.id desc"),
    RECEIVED_ASC("r.received_at asc nulls last, r.id desc"),
    STATUS_ASC("r.receipt_status asc, r.created_at desc"),
    CHANNEL_ASC("r.receipt_channel asc, r.created_at desc");

    private final String sql;

    ReturnReceiptSort(String sql) {
      this.sql = sql;
    }

    public String sql() {
      return this.sql;
    }

    public static ReturnReceiptSort parse(String raw) {
      if (raw == null) {
        return CREATED_DESC;
      }
      return switch (raw) {
        case "created_desc" -> CREATED_DESC;
        case "received_asc" -> RECEIVED_ASC;
        case "status_asc" -> STATUS_ASC;
        case "channel_asc" -> CHANNEL_ASC;
        default -> CREATED_DESC;
      };
    }
  }

  // ================================================================ 入参

  /**
   * 登记一张退货实收草稿。
   *
   * <p><b>建单不是入库</b>：明细还在录，此刻不产生任何库存分录。
   * 合成一个动作会让「谁扫的、什么时候扫的」在留痕里消失 ——
   * 而买家投诉「这批货我根本没寄这么多」时这就是唯一的证据。
   */
  public record ReturnReceiptCreate(
      UUID rmaId,
      @Schema(nullable = true) UUID instructionId,
      String receiptChannel,
      @Schema(nullable = true) String carrierCode,
      @Schema(nullable = true) String returnTrackingNo,
      @Schema(nullable = true) String packageNote,
      OffsetDateTime receivedAt,
      List<ReturnReceiptLineDraft> lines,
      @Schema(nullable = true) List<ReturnPhotoDraft> photos) {}

  /**
   * 建单时的逐件明细（扫码核对 + 清点数量 + 两个判定维度）。
   *
   * <p>⚠️ {@code rmaLineId} <b>必填且不接受省略</b>：蓝图输出句要求「关联授权行分配」，
   * 而额度就是挂在授权行上的 —— 没有它就没有可收量，实收记录也答不出「这批货退的是哪几件」。
   */
  public record ReturnReceiptLineDraft(
      UUID rmaLineId,
      @Schema(nullable = true) String scannedSkuCode,
      @Schema(nullable = true) String scannedSerialNo,
      @Schema(nullable = true) String batchNo,
      BigDecimal receivedQty,
      String packagingCondition,
      String goodsCondition,
      @Schema(nullable = true) String countNote) {}

  /** 建单时的照片（拍照留证）。 */
  public record ReturnPhotoDraft(
      String photoSource,
      @Schema(nullable = true) String sourceReference,
      String photoRole,
      String objectUri,
      String contentSha256,
      long contentBytes,
      @Schema(nullable = true) String contentType,
      OffsetDateTime capturedAt,
      @Schema(nullable = true) String caption) {}

  /**
   * 改草稿要素。
   *
   * <p><b>只允许 DRAFT 改</b>：入库后行与照片是**证据**（质检与索赔都要引用），
   * DB 触发器 {@code guard_return_receipt_frozen} 同一条纪律。
   */
  public record ReturnReceiptUpdate(
      @Schema(nullable = true) UUID instructionId,
      String receiptChannel,
      @Schema(nullable = true) String carrierCode,
      @Schema(nullable = true) String returnTrackingNo,
      @Schema(nullable = true) String packageNote,
      OffsetDateTime receivedAt,
      List<ReturnReceiptLineDraft> lines,
      Long expectedVersion) {}

  /**
   * 入库（清点完成 → 生成待检分录）。
   *
   * <p>没有「入可售」的变体，<b>也没有</b>把目标状态作为参数的余地 ——
   * 硬规则 1 在 DB 层用 CHECK 钉死（{@code inventory_state = 'PENDING_INSPECTION'}），
   * 在传输层刻意<b>不提供</b>任何能改这个值的字段。
   */
  public record ReturnReceiptPost(@Schema(nullable = true) String note, Long expectedVersion) {}

  /** 人工撤销（录错了 / 买家又把货拿回去了）。<b>已入库的单不可撤销</b>。 */
  public record ReturnReceiptCancel(String cancelReason, Long expectedVersion) {}

  /** 追加一张照片（拍照留证可在草稿期持续补）。 */
  public record ReturnPhotoAppend(
      String photoSource,
      @Schema(nullable = true) String sourceReference,
      String photoRole,
      String objectUri,
      String contentSha256,
      long contentBytes,
      @Schema(nullable = true) String contentType,
      OffsetDateTime capturedAt,
      @Schema(nullable = true) String caption) {}

  /** 列表查询。真分页 + 多维筛选 + 排序白名单。 */
  public record ReturnReceiptListQuery(
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String channel,
      @Schema(nullable = true) UUID rmaId,
      @Schema(nullable = true) UUID instructionId,
      @Schema(nullable = true) UUID caseId,
      @Schema(nullable = true) String carrier,
      @Schema(nullable = true) String trackingNo,
      @Schema(nullable = true) String packagingCondition,
      @Schema(nullable = true) String goodsCondition,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort) {}

  // ================================================================ 出参

  /** 实收单头。 */
  public record ReturnReceiptHead(
      UUID id,
      String receiptNo,
      UUID rmaId,
      String rmaNo,
      String rmaStatus,
      @Schema(nullable = true) UUID instructionId,
      @Schema(nullable = true) String instructionNo,
      UUID caseId,
      String caseNo,
      UUID orderId,
      String orderNo,
      String receiptStatus,
      String receiptStatusLabel,
      String receiptChannel,
      String receiptChannelLabel,
      @Schema(nullable = true) String carrierCode,
      @Schema(nullable = true) String returnTrackingNo,
      @Schema(nullable = true) String packageNote,
      OffsetDateTime receivedAt,
      @Schema(nullable = true) OffsetDateTime postedAt,
      @Schema(nullable = true) UUID postedBy,
      @Schema(nullable = true) OffsetDateTime cancelledAt,
      @Schema(nullable = true) String cancelReason,
      long version,
      UUID createdBy,
      OffsetDateTime createdAt) {}

  /**
   * 实收明细行。
   *
   * <p>⚠️ <b>两个判定维度分列</b>（硬规则 2）：{@code packagingCondition} 与
   * {@code goodsCondition} 各带各的 label，不合成一个 condition。
   *
   * <p>⚠️ SKU 与单价<b>实时读订单行</b>（照抄 V161 / V165 纪律）：实收行是**证据**，
   * 要跟着订单行核对；订单行改名后两处必须仍然对得上。
   */
  public record ReturnReceiptLine(
      UUID id,
      UUID rmaLineId,
      UUID caseLineId,
      UUID orderLineId,
      String skuCode,
      String skuName,
      String specSnapshot,
      @Schema(nullable = true) String scannedSkuCode,
      @Schema(nullable = true) String scannedSerialNo,
      @Schema(nullable = true) String batchNo,
      BigDecimal authorizedQty,
      BigDecimal receivedQty,
      String packagingCondition,
      String packagingConditionLabel,
      String goodsCondition,
      String goodsConditionLabel,
      boolean bothDimensionsJudged,
      @Schema(nullable = true) String countNote,
      UUID countedBy,
      OffsetDateTime countedAt,
      int lineNo) {}

  /** 照片。⚠️ 不带二进制本体 —— 对象存储接入属 18 集成，未交付。 */
  public record ReturnPhoto(
      UUID id,
      String photoSource,
      String photoSourceLabel,
      @Schema(nullable = true) String sourceReference,
      String photoRole,
      String photoRoleLabel,
      String objectUri,
      String contentSha256,
      long contentBytes,
      @Schema(nullable = true) String contentType,
      OffsetDateTime capturedAt,
      OffsetDateTime registeredAt,
      @Schema(nullable = true) String caption,
      UUID registeredBy) {}

  /**
   * 待检库存入库分录（蓝图输出句「待检库存入库分录（<b>不是可售</b>）」）。
   *
   * <p>⚠️ <b>{@code sellableQtyDelta} 恒为 0</b>，{@code inventoryState} <b>恒为
   * PENDING_INSPECTION</b>，两者都由 V172 的表级 CHECK 在数据库层钉死。
   * 把这两个字段放进传输层不是装饰：界面拿不到别的取值，就<b>渲染不出</b>「转可售」的动作。
   */
  public record ReturnInventoryPosting(
      UUID id,
      UUID receiptId,
      UUID receiptLineId,
      String postingType,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      @Schema(nullable = true) String batchNo,
      String inventoryState,
      BigDecimal sellableQtyDelta,
      BigDecimal pendingInspectionQty,
      String packagingCondition,
      String goodsCondition,
      OffsetDateTime postedAt,
      UUID postedBy) {}

  /** 收货留痕（追加式，运行角色 UPDATE/DELETE 已收回）。 */
  public record ReturnReceiptEvent(
      UUID id,
      String eventType,
      String eventTypeLabel,
      @Schema(nullable = true) String fromStatus,
      String toStatus,
      String reasonNote,
      UUID occurredBy,
      OffsetDateTime occurredAt) {}

  /** 列表行。 */
  public record ReturnReceiptRow(
      UUID id,
      String receiptNo,
      UUID rmaId,
      String rmaNo,
      @Schema(nullable = true) UUID instructionId,
      UUID caseId,
      String caseNo,
      String orderNo,
      String receiptStatus,
      String receiptStatusLabel,
      String receiptChannel,
      String receiptChannelLabel,
      @Schema(nullable = true) String carrierCode,
      @Schema(nullable = true) String returnTrackingNo,
      OffsetDateTime receivedAt,
      @Schema(nullable = true) OffsetDateTime postedAt,
      int lineCount,
      BigDecimal receivedQty,
      int photoCount,
      int postingCount,
      BigDecimal pendingInspectionQty,
      BigDecimal sellableQtyDelta,
      /** 恒为 {@code PENDING_INSPECTION}：退回商品默认进待检（硬规则 1）。 */
      String inventoryState,
      boolean inspectionCompleted,
      String inspectionOwnerModule,
      String inspectionNote,
      long version) {}

  /**
   * 单张实收全景。
   *
   * <p>⚠️ {@code sellableQtyDelta} 与 {@code inventoryState} 是<b>汇总列</b>：
   * 全单所有分录的 sellable 增量之和**恒为 0**，状态**恒为 PENDING_INSPECTION**。
   * 页面据此显示「进待检 N 件，可售 0」而不是含糊地写一句「已入库」。
   */
  public record ReturnReceiptDetail(
      ReturnReceiptHead head,
      List<ReturnReceiptLine> lines,
      List<ReturnPhoto> photos,
      List<ReturnInventoryPosting> postings,
      List<ReturnReceiptEvent> events,
      BigDecimal receivedQty,
      BigDecimal pendingInspectionQty,
      BigDecimal sellableQtyDelta,
      String inventoryState,
      String inventoryStateNote,
      String inspectionStatus,
      String inspectionStatusLabel,
      String inspectionOwnerModule,
      boolean bothDimensionsJudged,
      int unjudgedLineCount,
      List<ReturnReceivableRow> receivable,
      List<ReturnReceiptAction> actions,
      List<ReturnReceiptRelatedModule> relatedModules,
      OffsetDateTime evaluatedAt,
      String note) {}

  /**
   * 剩余可收量（硬规则 3 的可见面）。
   *
   * <p>把「授权量 / 已被占用 / 还能收」三个数并排给出，让操作员在<b>扫码那一刻</b>
   * 就知道还能收几件，而不是录完被后端拒了才知道。
   *
   * <p>⚠️ {@code remainingQty} 可能<b>为负</b>：并入的执行顺序下，
   * 后到的实收会先占用再由前面的行让出来（见 11.2.2.2「按实际退回」处理超收，未交付）。
   * 界面必须把负数显式显示成「超出授权 N 件」，不能悄悄 clamp 到 0。
   */
  public record ReturnReceivableRow(
      UUID rmaId,
      String rmaNo,
      UUID rmaLineId,
      UUID caseLineId,
      UUID orderLineId,
      String skuCode,
      String skuName,
      BigDecimal authorizedQty,
      BigDecimal takenQty,
      BigDecimal remainingQty,
      int receiptCount,
      boolean overAuthorized) {}

  /** 剩余可收量端点的返回。 */
  public record ReturnReceivablePage(
      UUID rmaId,
      String rmaNo,
      String rmaStatus,
      List<ReturnReceivableRow> rows,
      BigDecimal authorizedTotal,
      BigDecimal takenTotal,
      BigDecimal remainingTotal,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 列表页汇总（真分页之外的全量口径，与分页无关）。 */
  public record ReturnReceiptSummary(
      long total,
      long draft,
      long posted,
      long cancelled,
      long carrier,
      long walkIn,
      long withPhotos,
      BigDecimal receivedQty,
      BigDecimal pendingInspectionQty,
      /** 恒为 0 —— 退回商品默认进待检（硬规则 1）。这一格是给界面做正对照用的。 */
      BigDecimal sellableQtyDelta,
      long pendingInspectionBatches,
      long packagingDamaged,
      long goodsDamaged) {}

  /** 列表页。 */
  public record ReturnReceiptPage(
      List<ReturnReceiptRow> rows,
      int page,
      int size,
      long total,
      ReturnReceiptSummary summary,
      OffsetDateTime evaluatedAt,
      String paginationNote,
      String inventoryNote,
      String inspectionNote,
      String sortNote) {}

  /** 能力区块。 */
  public record ReturnReceiptSectionCapability(
      String section, String label, boolean available, @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String reason, @Schema(nullable = true) String landsIn) {}

  /** 关联模块可用性。未交付的一律 href=null —— 一个点进去 404 的链接比没有链接更糟。 */
  public record ReturnReceiptRelatedModule(
      String id, String label, boolean delivered, String ownerModule, String reason,
      @Schema(nullable = true) String href) {}

  /**
   * 操作入口。
   *
   * <p>{@code allowed=false} 时必须带 {@code blockingReason}：按钮变灰而不给理由会让人以为系统坏了。
   *
   * <p>⚠️ <b>没有「转可售」入口，也不会有</b>：处置属 11.2.3.2，未交付。
   */
  public record ReturnReceiptAction(
      String code, String label, boolean allowed, @Schema(nullable = true) String method,
      @Schema(nullable = true) String path, boolean required,
      @Schema(nullable = true) String precondition, @Schema(nullable = true) String blockingReason,
      @Schema(nullable = true) String ownerModule, @Schema(nullable = true) String unavailableReason) {}

  /** 本模块<b>刻意不做</b>的事，页面据此显示而不是让用户自己猜。 */
  public record ReturnReceiptBoundary(String boundary, String ownerModule, String reason) {}

  /** 能力声明。 */
  public record ReturnReceiptCapabilities(
      List<String> dataSources,
      List<ReturnReceiptSectionCapability> sections,
      List<ReturnReceiptRelatedModule> relatedModules,
      List<ReturnReceiptAction> actions,
      List<ReturnReceiptBoundary> boundaries,
      String stateMachineNote,
      String inventoryNote,
      String inspectionNote,
      String allocationNote,
      String photoNote) {}
}
