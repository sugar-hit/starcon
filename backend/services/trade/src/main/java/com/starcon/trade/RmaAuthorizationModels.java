package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.2.1.1 退货授权单的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 原文：
 *
 * <blockquote>
 * 职责：审批退货申请：确认可退数量（原订单行已退/已发数量约束）、退货地址、买家回寄物流、目标处理方式；部分退货需说明理由。
 * 输入：退货申请；原订单行可退量校验；审批决定。
 * 输出：退货授权单（授权/部分授权/拒绝）+ 拒绝理由。
 * 关系：上游 = 11.1.1.2；下游 = 11.2.1.2 退货指令。<b>授权不等于已收到</b>——授权后货可能在路上或根本没寄回，需要超时未寄自动关闭。
 * </blockquote>
 *
 * <p><b>本文件最重要的五件事</b>：
 *
 * <ol>
 *   <li><b>「授权」与「已收到」是两个状态</b>。本模块的状态机<b>没有</b> RECEIVED 一态 ——
 *       登记退回实物属 11.2.2.1（未交付）。蓝图加粗句「授权不等于已收到」在传输层就要说清：
 *       {@link RmaRow#received()} 恒为 false 且带 {@link RmaRow#receivedOwnerModule()}，
 *       而不是给一个 null 让界面自己猜。</li>
 *   <li><b>可退量是一个被算出来的事实，不是输入</b>（{@link RmaReturnableReport}）。
 *       它的三个来源分别是：订购量（V99 sales_order_line.quantity）、已发量
 *       （V119 order_fulfillment_line_progress.shipped_qty）、其他在途授权累计（本模块）。
 *       <b>已退数量（11.2.2.2 未交付）单列一列 null 并附说明</b>，不填 0 冒充「没退过」。</li>
 *   <li><b>目标处理方式是「意图」不是处置结论</b>（{@link RmaTargetDisposition}）——
 *       货还没到手，任何「已处置」都是假的。</li>
 *   <li><b>未交付的下游一律 delivered=false + 归属模块号</b>（{@link RmaRelatedModule}）：
 *       11.2.1.2 / 11.2.2.x / 11.2.3.x 当前全部未交付，<b>不建影子表</b>。</li>
 *   <li><b>排序是白名单</b>（{@link RmaSort#parse}）：排序键进 SQL 文本，
 *       放任用户传表达式就是一条注入。</li>
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code Rma*} 前缀（退货授权 = Return Merchandise Authorization），
 * 全仓 grep 确认与既有类型零重叠 —— 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 */
public final class RmaAuthorizationModels {

  private RmaAuthorizationModels() {}

  // ================================================================ 枚举

  /**
   * 退货授权单状态机：一个状态列到底，<b>decision 由它派生</b>（{@link RmaDecision#of}），不设第二份真相。
   *
   * <p>两个「占用可退量」的状态只有 AUTHORIZED 与 PARTIALLY_AUTHORIZED：草稿还没批；
   * 拒绝 / 撤销 / 超时关闭的单不会有货回来（超时正是「没寄回」），量要还给订单行。
   */
  public enum RmaStatus {
    DRAFT("草稿"),
    AUTHORIZED("全部授权"),
    PARTIALLY_AUTHORIZED("部分授权"),
    REJECTED("已拒绝"),
    EXPIRED("超时未寄，已关闭"),
    CANCELLED("已撤销");

    private final String label;

    RmaStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：行是证据不是草稿，不能再改（DB 触发器同一条纪律）。 */
    public boolean terminal() {
      return this == REJECTED || this == EXPIRED || this == CANCELLED;
    }

    /** 占用可退量（买家还可能把货寄回来）。 */
    public boolean consumesReturnable() {
      return this == AUTHORIZED || this == PARTIALLY_AUTHORIZED;
    }

    /** 授权已发出、货在路上或没寄回 —— 这是蓝图加粗句真正针对的那一段。 */
    public boolean awaitingBuyerShipment() {
      return this == AUTHORIZED || this == PARTIALLY_AUTHORIZED;
    }
  }

  /**
   * 审批决定。由 {@link RmaStatus} 派生，不单独存储 —— 存两份就会出现「状态是部分授权、
   * 决定字段写着全部授权」这种自相矛盾的行，而审批单据的自相矛盾在纠纷里是要命的。
   */
  public enum RmaDecision {
    PENDING("待审批"),
    APPROVED("授权"),
    PARTIALLY_APPROVED("部分授权"),
    REJECTED("拒绝");

    private final String label;

    RmaDecision(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static RmaDecision of(RmaStatus status) {
      return switch (status) {
        case AUTHORIZED -> APPROVED;
        case PARTIALLY_AUTHORIZED -> PARTIALLY_APPROVED;
        case REJECTED -> REJECTED;
        default -> PENDING;
      };
    }
  }

  /**
   * 目标处理方式。
   *
   * <p>⚠️ 六个值都是<b>意图</b>：审批当时打算怎么处置退回的货，<b>不是</b>已经处置完。
   * 货还在路上（授权 ≠ 收到），此刻任何「已处置」都是假的 —— 真正的处置决定属 11.2.3.2（未交付）。
   */
  public enum RmaTargetDisposition {
    REFUND_TO_BUYER("退款给买家"),
    RESTOCK_FOR_RESALE("转可售再销售"),
    SUPPLIER_CLAIM("向供应商索赔"),
    REPAIR("维修"),
    SCRAP("报废"),
    INSPECTION_HOLD("待检");

    private final String label;

    RmaTargetDisposition(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 退货地址来源。
   *
   * <p>蓝图输入句写「3.6.3 或平台 RMA 地址」。<b>实查集成头 f760ba37</b>：3.6.3.1 地址与联系人已交付，
   * 但迁移在 <b>catalog 服务</b>（V31__party_addresses_contacts.sql），是另一个数据库
   * ⇒ 按 AGENTS.md 模块边界纪律禁止跨服务 SQL / 视图 / FK，本服务<b>读不到它</b>。
   * ⇒ 照抄 V150 signal_source / V156 owner_source / V157 message_source 的同一条解法：
   * 写成 MODULE_* 时必须给出 sourceReference，3.6.3 交付后由同步器按<b>同一形状</b>写入，
   * 表结构与查询都不用改。
   *
   * <p>⚠️ PLATFORM_RMA 同样不代表「这个地址我们真的发过」：平台地址在 channel 独立库，
   * 本服务当前无任何平台地址表，平台 RMA 地址当前也只能由租户登记。
   */
  public enum RmaAddressSource {
    PLATFORM_RMA("平台 RMA 地址"),
    MODULE_3_6_3_ADDRESS_BOOK("3.6.3 地址与联系人"),
    TENANT_MAINTAINED("租户自行维护");

    private final String label;

    RmaAddressSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 买家回寄物流来源。11.2.1.2 退货指令下发（标签 + 轨迹跟踪）未交付，当前只能租户登记。 */
  public enum RmaLogisticsSource {
    TENANT_MAINTAINED("租户登记买家报来的运单"),
    MODULE_11_2_1_2_RMA_INSTRUCTION("11.2.1.2 退货指令");

    private final String label;

    RmaLogisticsSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 寄回时限状态。判定只有一个真身：服务层一次算好，整页复用同一参考时刻。 */
  public enum RmaTimelinessState {
    NOT_APPLICABLE("不适用"),
    ON_TRACK("正常"),
    DUE_SOON("临期"),
    OVERDUE("已超时"),
    CLOSED("已关闭");

    private final String label;

    RmaTimelinessState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 排序白名单。
   *
   * <p>排序键拼进 SQL 文本，放任请求参数进来就是注入；所以 {@link #parse} 只认这四个键，
   * 其余一律落到默认键（照抄 AftersalesCaseService 的同一条纪律）。
   *
   * <p>⚠️ 每一条都必须带表别名 {@code r.}：列表查询里 {@code rma_authorization} 被别名成 {@code r}，
   * 而 PostgreSQL <b>不允许在 ORDER BY 里用原表名</b>（FROM 子句里没有这个条目），
   * 报出来的是一句「invalid reference to FROM-clause entry」而不是「别名写错了」。
   */
  public enum RmaSort {
    CREATED_DESC("r.created_at desc, r.id desc"),
    DUE_ASC("r.return_by_at asc nulls last, r.id desc"),
    STATUS_ASC("r.rma_status asc, r.created_at desc"),
    DISPOSITION_ASC("r.target_disposition asc, r.created_at desc");

    private final String sql;

    RmaSort(String sql) {
      this.sql = sql;
    }

    public String sql() {
      return sql;
    }

    public static RmaSort parse(String raw) {
      if (raw == null) {
        return CREATED_DESC;
      }
      return switch (raw) {
        case "created_desc" -> CREATED_DESC;
        case "due_asc" -> DUE_ASC;
        case "status_asc" -> STATUS_ASC;
        case "disposition_asc" -> DISPOSITION_ASC;
        default -> CREATED_DESC;
      };
    }
  }

  // ================================================================ 入参

  /**
   * 登记一张退货授权单（草稿）。
   *
   * <p>行默认<b>全部来自售后单的商品行</b>（{@code caseLineIds} 省略时取该售后单全部商品行），
   * 授权量一律为 0 —— 建单不是审批，审批是 {@link RmaDecide} 的事。
   */
  public record RmaCreate(
      UUID caseId,
      String targetDisposition,
      String returnAddressSource,
      @Schema(nullable = true) String sourceReference,
      String returnRecipientName,
      String returnRecipientPhone,
      @Schema(nullable = true) String returnContactEmail,
      String returnCountryCode,
      @Schema(nullable = true) String returnProvince,
      @Schema(nullable = true) String returnCity,
      @Schema(nullable = true) String returnDistrict,
      @Schema(nullable = true) String returnPostcode,
      String returnAddressLine1,
      @Schema(nullable = true) String returnAddressLine2,
      @Schema(nullable = true) List<UUID> caseLineIds,
      @Schema(nullable = true) List<RmaLineDraft> lines) {}

  /** 建单时逐行给的申请量与说明（不给则取售后商品行的数量）。 */
  public record RmaLineDraft(UUID caseLineId, @Schema(nullable = true) BigDecimal requestedQty,
      @Schema(nullable = true) String lineNote) {}

  /**
   * 改草稿要素（目标处理方式 / 退货地址 / 逐行说明）。
   *
   * <p><b>只允许 DRAFT 改</b>：授权已发出后地址与处理方式就是**给买家的承诺**，
   * 买家可能已经照着旧地址寄了 —— 这时候改地址等于让货寄丢。
   */
  public record RmaUpdate(
      String targetDisposition,
      String returnAddressSource,
      @Schema(nullable = true) String sourceReference,
      String returnRecipientName,
      String returnRecipientPhone,
      @Schema(nullable = true) String returnContactEmail,
      String returnCountryCode,
      @Schema(nullable = true) String returnProvince,
      @Schema(nullable = true) String returnCity,
      @Schema(nullable = true) String returnDistrict,
      @Schema(nullable = true) String returnPostcode,
      String returnAddressLine1,
      @Schema(nullable = true) String returnAddressLine2,
      @Schema(nullable = true) List<RmaLineDraft> lines,
      Long expectedVersion) {}

  /**
   * 审批决定：授权 / 部分授权 / 拒绝。
   *
   * <p>逐行给授权量（{@link RmaLineDecision#authorizedQty}）—— 职责句的「确认可退数量」是<b>行级</b>的，
   * 只给一个单头总数无法回答「3 件里退哪几件」。
   *
   * @param returnByAt 授权 / 部分授权时<b>必填</b>：没有时限的授权永远不会超时关闭，
   *     而蓝图加粗句要的正是「没寄回就自动关掉」
   * @param decisionReason 部分授权与拒绝时<b>必填</b>（DB CHECK 兜底）
   */
  public record RmaDecide(
      String decision,
      @Schema(nullable = true) OffsetDateTime returnByAt,
      @Schema(nullable = true) String decisionReason,
      List<RmaLineDecision> lines,
      Long expectedVersion) {}

  /** 逐行授权量。 */
  public record RmaLineDecision(UUID lineId, BigDecimal authorizedQty,
      @Schema(nullable = true) String lineNote) {}

  /** 登记买家回寄物流（承运商 / 运单号 / 寄出时刻三件套同进同出）。 */
  public record RmaReturnLogistics(
      String carrier, String trackingNo, OffsetDateTime shippedAt, String logisticsSource,
      Long expectedVersion) {}

  /** 人工撤销（货还没回来，操作员发现批错了）。 */
  public record RmaCancel(String reason, Long expectedVersion) {}

  /** 列表分页与筛选。 */
  public record RmaListQuery(
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String decision,
      @Schema(nullable = true) String disposition,
      @Schema(nullable = true) UUID caseId,
      @Schema(nullable = true) Boolean overdueOnly,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort) {}

  // ================================================================ 出参

  /** 可退量校验里的一条订单行：三个来源的口径 + 算出来的上限，逐项可审。 */
  public record RmaReturnableLine(
      UUID caseLineId,
      UUID orderLineId,
      String skuCode,
      String skuName,
      BigDecimal caseRequestedQty,
      BigDecimal orderedQty,
      @Schema(nullable = true) BigDecimal shippedQty,
      String shippedQuantitySource,
      @Schema(nullable = true) BigDecimal receivedQty,
      String receivedQuantitySource,
      BigDecimal inflightAuthorizedQty,
      BigDecimal returnableQty,
      @Schema(nullable = true) BigDecimal refundableAmount,
      String currency,
      List<String> blockingReasons) {}

  /**
   * 一张售后单的可退量校验结果（{@code GET /rma-authorizations/returnable-quantities}）。
   *
   * @param receivedQuantityDelivered 恒为 false —— 已退数量的<b>写入所有者是 11.2.2.2</b>，
   *     未交付。本模块**不建影子表**：一张本服务自造的「已退数量」会被当成已对接，
   *     而重复记已退数量会让「这个 SKU 一共退了几件」有两个答案。
   */
  public record RmaReturnableReport(
      UUID caseId,
      String caseNo,
      UUID orderId,
      String orderNo,
      String caseType,
      String caseStatus,
      List<RmaReturnableLine> lines,
      BigDecimal caseRequestedQty,
      BigDecimal returnableQty,
      boolean receivedQuantityDelivered,
      String receivedQuantityOwnerModule,
      String receivedQuantityNote,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 列表行。{@code received} 恒为 false —— 登记实收属 11.2.2.1（未交付）。 */
  public record RmaRow(
      UUID id,
      String rmaNo,
      UUID caseId,
      String caseNo,
      UUID orderId,
      String orderNo,
      String rmaStatus,
      String rmaStatusLabel,
      String decision,
      String decisionLabel,
      String targetDisposition,
      String targetDispositionLabel,
      BigDecimal authorizedQty,
      BigDecimal requestedQty,
      int lineCount,
      String returnCountryCode,
      String returnRecipientName,
      @Schema(nullable = true) String returnCarrier,
      @Schema(nullable = true) String returnTrackingNo,
      @Schema(nullable = true) OffsetDateTime returnShippedAt,
      @Schema(nullable = true) OffsetDateTime returnByAt,
      @Schema(nullable = true) OffsetDateTime expiredAt,
      String timelinessState,
      String timelinessStateLabel,
      @Schema(nullable = true) Long remainingSeconds,
      @Schema(nullable = true) String decisionReason,
      boolean received,
      String receivedOwnerModule,
      RmaActionEntry action,
      OffsetDateTime createdAt) {}

  /** 详情头。字段与列表一致，另带决定留痕与版本号。 */
  public record RmaHead(
      UUID id,
      String rmaNo,
      UUID caseId,
      String caseNo,
      String caseType,
      String caseStatus,
      UUID orderId,
      String orderNo,
      String rmaStatus,
      String rmaStatusLabel,
      String decision,
      String decisionLabel,
      String targetDisposition,
      String targetDispositionLabel,
      String returnAddressSource,
      String returnAddressSourceLabel,
      @Schema(nullable = true) String sourceReference,
      String returnRecipientName,
      String returnRecipientPhone,
      @Schema(nullable = true) String returnContactEmail,
      String returnCountryCode,
      @Schema(nullable = true) String returnProvince,
      @Schema(nullable = true) String returnCity,
      @Schema(nullable = true) String returnDistrict,
      @Schema(nullable = true) String returnPostcode,
      String returnAddressLine1,
      @Schema(nullable = true) String returnAddressLine2,
      String returnLogisticsSource,
      String returnLogisticsSourceLabel,
      @Schema(nullable = true) String returnCarrier,
      @Schema(nullable = true) String returnTrackingNo,
      @Schema(nullable = true) OffsetDateTime returnShippedAt,
      @Schema(nullable = true) UUID returnLogisticsRecordedBy,
      @Schema(nullable = true) OffsetDateTime returnLogisticsRecordedAt,
      @Schema(nullable = true) OffsetDateTime returnByAt,
      @Schema(nullable = true) OffsetDateTime expiredAt,
      String decisionReason,
      @Schema(nullable = true) UUID decidedBy,
      @Schema(nullable = true) OffsetDateTime decidedAt,
      @Schema(nullable = true) UUID cancelledBy,
      @Schema(nullable = true) OffsetDateTime cancelledAt,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  /** 详情行：SKU 与单价**实时读订单行**，不复制（照抄 V157 第四节同一纪律）。 */
  public record RmaLine(
      UUID id,
      UUID caseLineId,
      UUID orderLineId,
      String skuCode,
      String skuName,
      String specSnapshot,
      String unitPrice,
      String currency,
      BigDecimal requestedQty,
      BigDecimal authorizedQty,
      @Schema(nullable = true) BigDecimal orderedQtySnapshot,
      @Schema(nullable = true) BigDecimal shippedQtySnapshot,
      BigDecimal priorAuthorizedQtySnapshot,
      BigDecimal returnableQty,
      @Schema(nullable = true) BigDecimal indicativeRefundAmount,
      String lineNote) {}

  /** 审批留痕的一行（追加式表，不可能被改）。 */
  public record RmaDecisionEvent(
      UUID id,
      String eventType,
      String eventTypeLabel,
      @Schema(nullable = true) String fromStatus,
      String toStatus,
      String reasonNote,
      UUID occurredBy,
      OffsetDateTime occurredAt) {}

  /** 详情聚合。 */
  public record RmaDetail(
      RmaHead head,
      List<RmaLine> lines,
      List<RmaDecisionEvent> events,
      BigDecimal authorizedQty,
      BigDecimal requestedQty,
      String timelinessState,
      String timelinessStateLabel,
      @Schema(nullable = true) Long remainingSeconds,
      boolean received,
      String receivedOwnerModule,
      List<RmaActionEntry> actions,
      List<RmaRelatedModule> relatedModules,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 列表页汇总（真分页之外的全量口径，与分页无关）。 */
  public record RmaSummary(
      long total,
      long draft,
      long authorized,
      long partiallyAuthorized,
      long rejected,
      long expired,
      long cancelled,
      long overdue,
      long awaitingShipment,
      long receivedLogged) {}

  /** 列表页。 */
  public record RmaPage(
      List<RmaRow> rows,
      int page,
      int size,
      long total,
      RmaSummary summary,
      OffsetDateTime evaluatedAt,
      String paginationNote,
      String timelinessNote,
      String receivedNote,
      String sortNote) {}

  /** 超时未寄自动关闭的逐条结果。 */
  public record RmaExpireItem(
      UUID id,
      String rmaNo,
      String statusBefore,
      @Schema(nullable = true) OffsetDateTime returnByAt,
      @Schema(nullable = true) OffsetDateTime expiredAt,
      boolean tracked,
      String outcome,
      @Schema(nullable = true) String detail) {}

  /** 超时未寄自动关闭的批次结果。 */
  public record RmaExpireResult(
      int scanned,
      int expired,
      int alreadyClosed,
      List<RmaExpireItem> items,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 能力区块。 */
  public record RmaSectionCapability(
      String section, String label, boolean available, @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String reason, @Schema(nullable = true) String landsIn) {}

  /** 关联模块可用性。未交付的一律 href=null —— 一个点进去 404 的链接比没有链接更糟。 */
  public record RmaRelatedModule(
      String id, String label, boolean delivered, String ownerModule, String reason,
      @Schema(nullable = true) String href) {}

  /** 操作入口。{@code allowed=false} 时必须带 {@code blockingReason}：按钮变灰而不给理由会让人以为系统坏了。 */
  public record RmaActionEntry(
      String code, String label, boolean allowed, @Schema(nullable = true) String method,
      @Schema(nullable = true) String path, boolean required,
      @Schema(nullable = true) String precondition, @Schema(nullable = true) String blockingReason,
      @Schema(nullable = true) String ownerModule, @Schema(nullable = true) String unavailableReason) {}

  /** 本模块**刻意不做**的事，页面据此显示而不是让用户自己猜。 */
  public record RmaBoundary(String boundary, String ownerModule, String reason) {}

  /** 能力声明。 */
  public record RmaCapabilities(
      List<String> dataSources,
      List<RmaSectionCapability> sections,
      List<RmaRelatedModule> relatedModules,
      List<RmaActionEntry> actions,
      List<RmaBoundary> boundaries,
      String stateMachineNote,
      String returnableQuantityNote,
      String receivedNote) {}
}
