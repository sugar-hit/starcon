package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.2.1.2 退货指令下发的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 原文：
 *
 * <blockquote>
 * 职责：向买家发出退货指引（地址、要求、时限），生成退货标签，跟踪买家回寄物流。
 * 输入：退货授权；退货地址（3.6.3 或平台 RMA 地址）；渠道选择。
 * 输出：退货指令 + 标签文件 + 回寄物流轨迹跟踪。
 * 关系：上游 = 11.2.1.1；下游 = 10 物流（退货运单）、15 客服（通知买家）。<b>多数平台退货由买家寄回</b>，本系统只负责指引与跟踪，不控制实物。
 * </blockquote>
 *
 * <p><b>本文件最重要的五件事</b>：
 *
 * <ol>
 *   <li><b>「已下发」不等于「已寄回」</b>。本模块<b>没有</b> RECEIVED 一态 —— 登记退回实物属
 *       11.2.2.1（未交付）。响应里的 {@link RmaInstructionRow#goodsReceived()} 恒为 false
 *       且带 {@link RmaInstructionRow#goodsReceivedOwnerModule()}，而不是给个 null 让界面自己猜。
 *       蓝图加粗句「本系统只负责指引与跟踪，<b>不控制实物</b>」在传输层就要说清。</li>
 *   <li><b>「渠道」是两个东西</b>：{@link RmaDeliveryChannel} 是<b>指引怎么送达买家</b>，
 *       {@link RmaLabel#carrierCode()} / {@link RmaLabel#serviceLevel()} 是<b>货用哪个承运商寄回</b>。
 *       压成一个字段会让「邮件已发但买家自己买了运费单」这种状态无处安放。</li>
 *   <li><b>标签文件是真字节 + SHA-256</b>（{@link RmaLabelContent}）：内容由服务层从库里真值渲染，
 *       校验和是内容字节的 SHA-256，读取时<b>重算并比对</b>。
 *       <b>刻意不生成 PDF</b>：真实面单要走 10 物流的承运商接口（未交付）。</li>
 *   <li><b>未交付的下游一律 delivered=false + 归属模块号</b>（{@link RmaInstructionRelatedModule}）：
 *       10 物流退货运单、15 客服通知、11.2.2.1 实收当前全部未交付，<b>不建影子表</b>。</li>
 *   <li><b>排序是白名单</b>（{@link RmaInstructionSort#parse}）：排序键进 SQL 文本，
 *       放任用户传表达式就是一条注入。</li>
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code RmaInstruction*} / {@code RmaLabel*} / {@code RmaTracking*}
 * 前缀，全仓 grep 确认与既有类型零重叠 —— 契约被 codegen 合并进一个扁平文件、
 * 同名后者覆盖前者且不报冲突。
 */
public final class RmaInstructionModels {

  private RmaInstructionModels() {}

  // ================================================================ 枚举

  /**
   * 退货指引状态机：一个状态列到底。
   *
   * <p>⚠️ <b>没有 RECEIVED</b>：蓝图加粗句「本系统只负责指引与跟踪，<b>不控制实物</b>」——
   * 下发指引之后货可能在路上，也可能买家压根没寄。实物入不入库是 11.2.2.1（未交付）的事。
   *
   * <p>⚠️ <b>EXPIRED 只判「时限过了」，不判「货没回来」</b>：时限过了而货在路上的单子，
   * 在承运商口径里仍可能签收成功；把它算成「已失效」会让下游拒收一件真收到的货。
   */
  public enum RmaInstructionStatus {
    DRAFT("草稿"),
    ISSUED("已下发"),
    ACKNOWLEDGED("买家已确认"),
    EXPIRED("时限已到，自动失效"),
    CANCELLED("已撤销");

    private final String label;

    RmaInstructionStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：内容与行是证据不是草稿，不能再改（DB 触发器同一条纪律）。 */
    public boolean terminal() {
      return this == EXPIRED || this == CANCELLED;
    }

    /** 已送达买家（含已确认）—— 指引真正出过门。 */
    public boolean deliveredToBuyer() {
      return this == ISSUED || this == ACKNOWLEDGED;
    }

    /** 还在等买家动作（还要管）。 */
    public boolean open() {
      return this == DRAFT || this == ISSUED || this == ACKNOWLEDGED;
    }
  }

  /**
   * 渠道选择①：<b>指引怎么送达买家</b>。
   *
   * <p>⚠️ 与标签上的承运商<b>不是同一件事</b>：这里决定买家**怎么看到指引**，
   * 标签决定货**用哪个承运商寄回**。压成一个字段会让「邮件已发但买家自己买了运费单」无处安放。
   */
  public enum RmaDeliveryChannel {
    BUYER_EMAIL("买家邮箱"),
    PLATFORM_MESSAGE("平台站内消息"),
    TENANT_DOWNLOAD_PRINT("租户下载后打印/转发"),
    API_CALLBACK("外部系统回调下发");

    private final String label;

    RmaDeliveryChannel(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 退货地址来源。与 V161 {@code rma_authorization.return_address_source} <b>完全同形</b>。
   *
   * <p>⚠️ 3.6.3 地址本在 <b>catalog 服务</b>（V31）、平台地址在 <b>channel 独立库</b>，
   * 本服务当前都读不到 ⇒ 照抄 V150 / V156 / V157 / V161 的同一条解法：来源可枚举，
   * 写成 MODULE_* 时必须给出 {@code sourceReference}，正式接口到位后按同一形状写入，
   * <b>表结构与查询都不用改</b>。
   */
  public enum RmaInstructionAddressSource {
    PLATFORM_RMA("平台 RMA 地址"),
    MODULE_3_6_3_ADDRESS_BOOK("3.6.3 地址与联系人"),
    TENANT_MAINTAINED("租户自行维护");

    private final String label;

    RmaInstructionAddressSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 快递档位（渠道选择②）。空串表示「不指定」—— 自出模板标签时承运商还没定。 */
  public enum RmaServiceLevel {
    STANDARD("标准"),
    EXPRESS("快递"),
    PRIORITY("优先");

    private final String label;

    RmaServiceLevel(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 退货标签来源。
   *
   * <p>⚠️ 只有前两种是本模块<b>真能产出</b>的：
   * {@link #MODULE_10_CARRIER_LABEL_API} 由 10 物流面单接口生成，<b>未交付</b> ——
   * 枚举保留是给同步器接入的口子，不是「已经对接」。
   */
  public enum RmaLabelSource {
    SELF_GENERATED_TEMPLATE("本模块按授权单真值生成的模板标签"),
    TENANT_PROVIDED_FILE("租户已从外部渠道拿到并登记的标签文件"),
    MODULE_10_CARRIER_LABEL_API("10 物流面单接口生成（未交付）");

    private final String label;

    RmaLabelSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 标签状态。
   *
   * <p>刻意<b>没有 REQUESTED</b>（已向渠道下单但还没拿到文件）：那是一个没有消费者的中间态，
   * 列出来只会让界面显示一个「等渠道」却没人能推进的按钮。真实的两步是
   * **生成**（拿到内容）→ **交付**（到买家手上），中间任何时候可以**作废**。
   */
  public enum RmaLabelStatus {
    GENERATED("已生成"),
    DELIVERED("已交付"),
    VOIDED("已作废");

    private final String label;

    RmaLabelStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public boolean terminal() {
      return this == VOIDED;
    }
  }

  /** 标签文件格式。⚠️ 刻意不含 PDF —— 真实面单要走 10 物流的承运商接口（未交付）。 */
  public enum RmaLabelFormat {
    TEXT_TEMPLATE("纯文本，一行一个字段"),
    HTML_TEMPLATE("带排版标记的文本");

    private final String label;

    RmaLabelFormat(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 回寄轨迹事件码。⚠️ <b>不驱动库存</b>：DELIVERED 只表示「承运商说送到了」。 */
  public enum RmaTrackingEventCode {
    ACCEPTED("已揽收"),
    PICKED_UP("已取件"),
    IN_TRANSIT("运输中"),
    ARRIVED_AT_ORIGIN("已到目的地"),
    CUSTOMS_HOLD("清关扣留"),
    OUT_FOR_DELIVERY("派送中"),
    DELIVERED("已签收"),
    EXCEPTION("异常"),
    RETURNED_TO_SENDER("退回寄件人"),
    CANCELLED("已取消");

    private final String label;

    RmaTrackingEventCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 承运商口径的「送达」。⚠️ 不等于「我们收到货了」—— 那是 11.2.2.1 的事。 */
    public boolean carrierDelivered() {
      return this == DELIVERED;
    }
  }

  /**
   * 轨迹来源。
   *
   * <p>⚠️ 只有 {@link #TENANT_MANUAL_ENTRY} 是本服务当前真能写进来的；
   * 另外两个是<b>未交付</b>的接入口子（回调未接、10 物流轮询未交付）。
   */
  public enum RmaTrackingSource {
    TENANT_MANUAL_ENTRY("运营/客服手工登记"),
    CARRIER_WEBHOOK("承运商回调（未接入）"),
    MODULE_10_LOGISTICS_POLL("10 物流轮询写入（未交付）");

    private final String label;

    RmaTrackingSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 本服务当前真能写进来的来源。其余两个属系统接入，不开放给人工写入。 */
    public boolean tenantWritable() {
      return this == TENANT_MANUAL_ENTRY;
    }
  }

  /** 时效状态。判定只有一个真身：服务层一次算好，整页复用同一参考时刻。 */
  public enum RmaInstructionTimeliness {
    NOT_APPLICABLE("不适用"),
    ON_TRACK("正常"),
    DUE_SOON("临期"),
    OVERDUE("已超时"),
    CLOSED("已关闭");

    private final String label;

    RmaInstructionTimeliness(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 排序白名单。
   *
   * <p>排序键拼进 SQL 文本，放任请求参数进来就是注入；所以 {@link #parse} 只认这四个键。
   *
   * <p>⚠️ 每一条都必须带表别名 {@code r.}：列表查询里 {@code rma_instruction} 被别名成 {@code r}，
   * 而 PostgreSQL <b>不允许在 ORDER BY 里用原表名</b>，报出来的是一句
   * 「invalid reference to FROM-clause entry」而不是「别名写错了」。
   */
  public enum RmaInstructionSort {
    CREATED_DESC("r.created_at desc, r.id desc"),
    DUE_ASC("r.return_by_at asc nulls last, r.id desc"),
    STATUS_ASC("r.instruction_status asc, r.created_at desc"),
    CHANNEL_ASC("r.delivery_channel asc, r.created_at desc");

    private final String sql;

    RmaInstructionSort(String sql) {
      this.sql = sql;
    }

    public String sql() {
      return sql;
    }

    public static RmaInstructionSort parse(String raw) {
      if (raw == null) {
        return CREATED_DESC;
      }
      return switch (raw) {
        case "created_desc" -> CREATED_DESC;
        case "due_asc" -> DUE_ASC;
        case "status_asc" -> STATUS_ASC;
        case "channel_asc" -> CHANNEL_ASC;
        default -> CREATED_DESC;
      };
    }
  }

  // ================================================================ 入参

  /**
   * 登记一张退货指引草稿。
   *
   * <p><b>地址与时限不给就是从退货授权单复制</b>（{@code syncFromRma}）：指引里的地址是
   * <b>买家要看到的那一份</b>，让操作员手工重敲一遍地址只会敲错，而敲错的后果是货寄丢。
   *
   * <p><b>建单不是下发</b>：草稿还没出过门。真正送达是 {@code /issue} 的事 ——
   * 两者合成一个动作会让「谁下发的、什么时候下发的」在留痕里消失。
   */
  public record RmaInstructionCreate(
      UUID rmaId,
      String deliveryChannel,
      @Schema(nullable = true) String recipientName,
      @Schema(nullable = true) String recipientEmail,
      @Schema(nullable = true) String recipientPhone,
      @Schema(nullable = true) String packingRequirements,
      @Schema(nullable = true) String buyerNote,
      Boolean syncFromRma,
      @Schema(nullable = true) List<RmaInstructionLineDraft> lines) {}

  /** 建单时逐行给的应寄回数量与说明（不给则取该授权行的授权量）。 */
  public record RmaInstructionLineDraft(
      UUID rmaLineId, @Schema(nullable = true) BigDecimal expectedQty,
      @Schema(nullable = true) String lineNote) {}

  /**
   * 改草稿要素（收件方 / 渠道 / 包装要求 / 逐行明细）。
   *
   * <p><b>只允许 DRAFT 改</b>：指引下发后内容即冻结（DB 触发器 guard_rma_instruction_frozen）——
   * 买家可能已经照着旧地址寄了。要改先撤销再重建。
   */
  public record RmaInstructionUpdate(
      String deliveryChannel,
      @Schema(nullable = true) String recipientName,
      @Schema(nullable = true) String recipientEmail,
      @Schema(nullable = true) String recipientPhone,
      @Schema(nullable = true) String packingRequirements,
      @Schema(nullable = true) String buyerNote,
      @Schema(nullable = true) List<RmaInstructionLineDraft> lines,
      Long expectedVersion) {}

  /** 下发指引（内容在这一刻冻结）。 */
  public record RmaInstructionIssue(@Schema(nullable = true) String issueNote, Long expectedVersion) {}

  /** 买家确认收到指引。⚠️ 这是买家的<b>主动回执</b>，不是我们推测他看到了。 */
  public record RmaInstructionAcknowledge(
      @Schema(nullable = true) String acknowledgeNote, Long expectedVersion) {}

  /** 人工撤销指引（授权被撤销 / 买家改主意了）。 */
  public record RmaInstructionCancel(String reason, Long expectedVersion) {}

  /**
   * 生成退货标签。
   *
   * @param labelSource {@code SELF_GENERATED_TEMPLATE} 时 content 必填；
   *     {@code TENANT_PROVIDED_FILE} 时 {@code carrierCode} 与 {@code trackingNo} 必填（DB CHECK 兜底）
   * @param content 租户登记外部标签时传文件原文；本模块自出模板时**不给**（服务层按真值渲染）
   */
  public record RmaLabelGenerate(
      String labelSource,
      @Schema(nullable = true) String carrierCode,
      @Schema(nullable = true) String serviceLevel,
      String labelFormat,
      @Schema(nullable = true) String trackingNo,
      @Schema(nullable = true) BigDecimal weightKg,
      @Schema(nullable = true) BigDecimal lengthCm,
      @Schema(nullable = true) BigDecimal widthCm,
      @Schema(nullable = true) BigDecimal heightCm,
      @Schema(nullable = true) String content) {}

  /** 作废标签（承运商取消 / 地址改了重出 / 指令撤销）。 */
  public record RmaLabelVoid(String reason) {}

  /**
   * 交付标签（贴纸真的到买家手上了）。
   *
   * <p>⚠️ <b>「生成」不等于「交付」</b>：生成只是我们手上有一张能打印的贴纸，
   * 买家手上没有那张纸，退货照样收不到。交付渠道与指引的送达渠道<b>不是同一件事</b> ——
   * 指引可以走平台站内消息，而标签必须以附件 / 图片形式真的到买家手上。
   */
  public record RmaLabelDeliver(String deliveredVia) {}

  /**
   * 登记一条回寄轨迹事件（追加式，UPDATE/DELETE 权限已在 DB 层收回）。
   *
   * @param trackedAt 我们<b>知道</b>这件事的时刻；不给则取当前时刻。与 {@code occurredAt}
   *     分开记是因为两者的差就是信息延迟，混成一个字段就再也算不出延迟了
   */
  public record RmaTrackingAppend(
      String carrierCode,
      String trackingNo,
      String eventCode,
      @Schema(nullable = true) String eventLocation,
      @Schema(nullable = true) String eventDescription,
      OffsetDateTime occurredAt,
      @Schema(nullable = true) OffsetDateTime trackedAt,
      @Schema(nullable = true) String trackingSource) {}

  /** 列表分页与筛选。 */
  public record RmaInstructionListQuery(
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String channel,
      @Schema(nullable = true) UUID rmaId,
      @Schema(nullable = true) Boolean overdueOnly,
      @Schema(nullable = true) String carrier,
      @Schema(nullable = true) String trackingNo,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort) {}

  // ================================================================ 出参

  /** 指令行：SKU 与单价**实时读订单行**，不复制（照抄 V161 同一纪律）。 */
  public record RmaInstructionLine(
      UUID id,
      UUID rmaLineId,
      UUID orderLineId,
      String skuCode,
      String skuName,
      String specSnapshot,
      String unitPrice,
      String currency,
      BigDecimal authorizedQty,
      BigDecimal expectedQty,
      String lineNote) {}

  /** 退货标签行。⚠️ 不带文件内容 —— 内容走 {@link RmaLabelContent} 单独取。 */
  public record RmaLabel(
      UUID id,
      String labelNo,
      UUID instructionId,
      String labelStatus,
      String labelStatusLabel,
      String labelSource,
      String labelSourceLabel,
      @Schema(nullable = true) String carrierCode,
      @Schema(nullable = true) String serviceLevel,
      @Schema(nullable = true) String serviceLevelLabel,
      String labelFormat,
      @Schema(nullable = true) String trackingNo,
      @Schema(nullable = true) BigDecimal weightKg,
      @Schema(nullable = true) BigDecimal lengthCm,
      @Schema(nullable = true) BigDecimal widthCm,
      @Schema(nullable = true) BigDecimal heightCm,
      String labelChecksum,
      int contentLength,
      OffsetDateTime generatedAt,
      UUID generatedBy,
      @Schema(nullable = true) OffsetDateTime deliveredAt,
      @Schema(nullable = true) UUID deliveredBy,
      @Schema(nullable = true) String deliveredVia,
      @Schema(nullable = true) OffsetDateTime voidedAt,
      @Schema(nullable = true) UUID voidedBy,
      @Schema(nullable = true) String voidReason) {}

  /**
   * 标签文件内容。
   *
   * @param checksumVerified <b>服务端读取时重算过 SHA-256 并与库里比对的结果</b>。
   *     false 表示文件内容在库里被改过 —— 页面据此显示「完整性校验失败」而不是照常渲染一份
   *     可能被篡改的地址（买家照着它寄，货寄到谁家由篡改者决定）。
   */
  public record RmaLabelContent(
      UUID labelId,
      String labelNo,
      String labelFormat,
      String labelSource,
      String content,
      String contentType,
      String storedChecksum,
      String computedChecksum,
      boolean checksumVerified,
      @Schema(nullable = true) String note) {}

  /** 一条回寄轨迹事件（追加式表，不可能被改）。 */
  public record RmaTrackingEvent(
      UUID id,
      String carrierCode,
      String trackingNo,
      String eventCode,
      String eventCodeLabel,
      @Schema(nullable = true) String eventLocation,
      @Schema(nullable = true) String eventDescription,
      OffsetDateTime occurredAt,
      OffsetDateTime trackedAt,
      @Schema(nullable = true) Long lagSeconds,
      String trackingSource,
      String trackingSourceLabel,
      UUID recordedBy,
      OffsetDateTime createdAt) {}

  /**
   * 轨迹回放结果：按运单号分组的当前状态。
   *
   * <p>⚠️ {@code currentStatus} 由「该运单号 {@code occurred_at} 最大的那条事件」算出，
   * <b>不单独存列</b>：存一个 current_status 列就得回答「它和事件表哪个为准」，
   * 而两者不一致时（「已签收」的事件旁边写着「运输中」的当前值）没人能判对。
   */
  public record RmaTrackingSummaryRow(
      String carrierCode,
      String trackingNo,
      String currentEventCode,
      String currentEventCodeLabel,
      @Schema(nullable = true) String currentEventLocation,
      @Schema(nullable = true) OffsetDateTime currentOccurredAt,
      int eventCount,
      @Schema(nullable = true) OffsetDateTime firstOccurredAt) {}

  /** 轨迹回放。 */
  public record RmaTrackingTimeline(
      UUID instructionId,
      List<RmaTrackingEvent> events,
      List<RmaTrackingSummaryRow> parcels,
      @Schema(nullable = true) String latestEventCode,
      @Schema(nullable = true) String latestEventCodeLabel,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 列表行。{@code goodsReceived} 恒为 false —— 登记实收属 11.2.2.1（未交付）。 */
  public record RmaInstructionRow(
      UUID id,
      String instructionNo,
      UUID rmaId,
      String rmaNo,
      UUID caseId,
      String caseNo,
      UUID orderId,
      String orderNo,
      String instructionStatus,
      String instructionStatusLabel,
      String deliveryChannel,
      String deliveryChannelLabel,
      @Schema(nullable = true) String recipientName,
      @Schema(nullable = true) String recipientEmail,
      @Schema(nullable = true) String returnRecipientName,
      @Schema(nullable = true) String returnCountryCode,
      OffsetDateTime returnByAt,
      String timelinessState,
      String timelinessStateLabel,
      @Schema(nullable = true) Long remainingSeconds,
      int lineCount,
      BigDecimal expectedQty,
      @Schema(nullable = true) String labelStatus,
      @Schema(nullable = true) String labelNo,
      @Schema(nullable = true) String labelCarrier,
      @Schema(nullable = true) String labelTrackingNo,
      int trackingEventCount,
      @Schema(nullable = true) String latestTrackingEventCode,
      @Schema(nullable = true) String latestTrackingEventLabel,
      boolean goodsReceived,
      String goodsReceivedOwnerModule,
      RmaInstructionAction action,
      OffsetDateTime createdAt) {}

  /** 详情头。字段与列表一致，另带版本号与冻结时刻。 */
  public record RmaInstructionHead(
      UUID id,
      String instructionNo,
      UUID rmaId,
      String rmaNo,
      String rmaStatus,
      UUID caseId,
      String caseNo,
      UUID orderId,
      String orderNo,
      String instructionStatus,
      String instructionStatusLabel,
      String deliveryChannel,
      String deliveryChannelLabel,
      @Schema(nullable = true) String recipientName,
      @Schema(nullable = true) String recipientEmail,
      @Schema(nullable = true) String recipientPhone,
      String packingRequirements,
      @Schema(nullable = true) String buyerNote,
      String addressSource,
      String addressSourceLabel,
      @Schema(nullable = true) String sourceReference,
      String returnRecipientName,
      @Schema(nullable = true) String returnRecipientPhone,
      @Schema(nullable = true) String returnContactEmail,
      @Schema(nullable = true) String returnCountryCode,
      @Schema(nullable = true) String returnProvince,
      @Schema(nullable = true) String returnCity,
      @Schema(nullable = true) String returnDistrict,
      @Schema(nullable = true) String returnPostcode,
      String returnAddressLine1,
      @Schema(nullable = true) String returnAddressLine2,
      OffsetDateTime returnByAt,
      @Schema(nullable = true) OffsetDateTime issuedAt,
      @Schema(nullable = true) UUID issuedBy,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      @Schema(nullable = true) UUID acknowledgedBy,
      @Schema(nullable = true) OffsetDateTime expiredAt,
      @Schema(nullable = true) OffsetDateTime cancelledAt,
      @Schema(nullable = true) UUID cancelledBy,
      @Schema(nullable = true) String cancelReason,
      boolean frozen,
      @Schema(nullable = true) OffsetDateTime contentFrozenAt,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      long version) {}

  /** 指令状态留痕的一行（追加式表，不可能被改）。 */
  public record RmaInstructionEvent(
      UUID id,
      String eventType,
      String eventTypeLabel,
      @Schema(nullable = true) String fromStatus,
      String toStatus,
      String reasonNote,
      UUID occurredBy,
      OffsetDateTime occurredAt) {}

  /** 详情聚合。 */
  public record RmaInstructionDetail(
      RmaInstructionHead head,
      List<RmaInstructionLine> lines,
      List<RmaInstructionEvent> events,
      List<RmaTrackingEvent> trackingEvents,
      List<RmaTrackingSummaryRow> trackingParcels,
      @Schema(nullable = true) RmaLabel label,
      BigDecimal expectedQty,
      String timelinessState,
      String timelinessStateLabel,
      @Schema(nullable = true) Long remainingSeconds,
      boolean goodsReceived,
      String goodsReceivedOwnerModule,
      @Schema(nullable = true) String latestTrackingEventCode,
      @Schema(nullable = true) String latestTrackingEventLabel,
      List<RmaInstructionAction> actions,
      List<RmaInstructionRelatedModule> relatedModules,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 列表页汇总（真分页之外的全量口径，与分页无关）。 */
  public record RmaInstructionSummary(
      long total,
      long draft,
      long issued,
      long acknowledged,
      long expired,
      long cancelled,
      long overdue,
      long deliveredToBuyer,
      long withLabel,
      long withTracking,
      long deliveredGoods) {}

  /** 列表页。 */
  public record RmaInstructionPage(
      List<RmaInstructionRow> rows,
      int page,
      int size,
      long total,
      RmaInstructionSummary summary,
      OffsetDateTime evaluatedAt,
      String paginationNote,
      String timelinessNote,
      String goodsReceivedNote,
      String sortNote) {}

  /** 时限到期自动失效的逐条结果。 */
  public record RmaInstructionExpireItem(
      UUID id,
      String instructionNo,
      String statusBefore,
      @Schema(nullable = true) OffsetDateTime returnByAt,
      @Schema(nullable = true) OffsetDateTime expiredAt,
      boolean trackingStarted,
      String outcome,
      @Schema(nullable = true) String detail) {}

  /** 时限到期自动失效的批次结果。 */
  public record RmaInstructionExpireResult(
      int scanned,
      int expired,
      int alreadyClosed,
      List<RmaInstructionExpireItem> items,
      OffsetDateTime evaluatedAt,
      String note) {}

  /** 能力区块。 */
  public record RmaInstructionSectionCapability(
      String section, String label, boolean available, @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String reason, @Schema(nullable = true) String landsIn) {}

  /** 关联模块可用性。未交付的一律 href=null —— 一个点进去 404 的链接比没有链接更糟。 */
  public record RmaInstructionRelatedModule(
      String id, String label, boolean delivered, String ownerModule, String reason,
      @Schema(nullable = true) String href) {}

  /**
   * 操作入口。
   *
   * <p>{@code allowed=false} 时必须带 {@code blockingReason}：按钮变灰而不给理由会让人以为系统坏了。
   */
  public record RmaInstructionAction(
      String code, String label, boolean allowed, @Schema(nullable = true) String method,
      @Schema(nullable = true) String path, boolean required,
      @Schema(nullable = true) String precondition, @Schema(nullable = true) String blockingReason,
      @Schema(nullable = true) String ownerModule, @Schema(nullable = true) String unavailableReason) {}

  /** 本模块<b>刻意不做</b>的事，页面据此显示而不是让用户自己猜。 */
  public record RmaInstructionBoundary(String boundary, String ownerModule, String reason) {}

  /** 能力声明。 */
  public record RmaInstructionCapabilities(
      List<String> dataSources,
      List<RmaInstructionSectionCapability> sections,
      List<RmaInstructionRelatedModule> relatedModules,
      List<RmaInstructionAction> actions,
      List<RmaInstructionBoundary> boundaries,
      String stateMachineNote,
      String addressSnapshotNote,
      String labelNote,
      String trackingNote,
      String goodsReceivedNote) {}
}
