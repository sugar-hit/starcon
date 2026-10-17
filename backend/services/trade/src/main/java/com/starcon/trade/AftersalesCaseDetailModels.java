package com.starcon.trade;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 11.1.1.2 售后单详情的传输模型。
 *
 * <p>蓝图 11-aftersales-and-returns.md 第 87-92 行原文：
 *
 * <blockquote>
 * 职责：单售后全景：原订单信息、申请内容与买家诉求、商品行与数量、沟通记录、附件与凭证、处置流程状态、退款与退货关联、平台侧状态、责任判定。
 * 输入：{@code aftersales_id}；聚合子资源。
 * 输出：详情 + 操作入口 + 时间线。
 * 关系：上游 = 11.1.1.1；下游 = 11.2 退货、11.3 退款、11.4 纠纷。<b>详情页要把"买家说了什么"和"我们做了什么"按时间线对齐</b>，这是纠纷举证的基础。
 * </blockquote>
 *
 * <p><b>本文件最重要的四件事</b>：
 *
 * <ol>
 *   <li><b>时间线是对齐的，不是罗列的。</b>{@link AftersalesTimelineEvent#actorSide()} 是这条事件的
 *       <b>说话方</b>（BUYER / US / PLATFORM / SYSTEM），{@link AftersalesTimelineEvent#occurredAt()} 是它
 *       <b>发生</b>的时刻（不是被录入库的时刻）。蓝图那句加粗句就落在这两列上：
 *       举证时要回答的是「买家先说的还是我们先回的」，而错位的时间线在申诉里往往不被采信。
 *   <li><b>未交付的下游一律 {@code delivered=false} + 归属模块号</b>（{@link AftersalesRelatedModule}）。
 *       11.2 退货 / 11.3 退款 / 11.4 纠纷当前<b>全部未交付</b> ⇒ 详情页对应区块显示「该能力未交付」
 *       而不是空白或假数据。<b>不建影子表</b>：一张本服务自造的「退款记录」会被当成已对接。
 *   <li><b>原订单字段不复制</b>。{@link AftersalesOrderInfo} / {@link AftersalesOrderLine} 直接来自
 *       {@code sales_order} 簇（V99，同库只读）。复制一份就会与订单漂移，而
 *       「截图上的订单号和系统里的不一样」是纠纷举证里最致命的破绽。
 *   <li><b>时效判定仍然只有一个真身</b>：{@link AftersalesCaseHeader#timelinessState()} 来自 DB 函数
 *       {@code aftersales_timeliness_state()}（V156），本模块<b>不</b>在 Java 侧重算。
 * </ol>
 *
 * <p><b>命名。</b>统一用 {@code Aftersales*} 前缀，全仓 grep 确认与既有类型零重叠 ——
 * 契约被 codegen 合并进一个扁平文件、同名后者覆盖前者且不报冲突。
 */
public final class AftersalesCaseDetailModels {

  private AftersalesCaseDetailModels() {}

  // ================================================================ 枚举

  /** 沟通方向：谁对谁说的。这是时间线「买家说了什么 vs 我们做了什么」的分类轴。 */
  public enum AftersalesMessageDirection {
    BUYER_TO_US("买家 → 我方"),
    US_TO_BUYER("我方 → 买家");

    private final String label;

    AftersalesMessageDirection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 沟通渠道。
   *
   * <p>⚠️ {@code PLATFORM} **不代表**「这条消息来自平台接口」：卷 2 平台消息在 channel 独立库且当前无任何
   * 平台消息表，本服务读不到真实对话流，所以平台消息只能由租户**转录**（来源
   * {@code TENANT_MAINTAINED}）。渠道与来源是**两个维度**：渠道说「在哪说的」，来源说「谁写进来的」。
   * 合成一列会让「我们转录的买家原话」被读成「系统收到的平台消息」，举证时那个差别是要命的。
   */
  public enum AftersalesMessageChannel {
    PLATFORM("平台站内信"),
    EMAIL("邮件"),
    CHAT("在线聊天"),
    PHONE("电话"),
    INTERNAL_NOTE("内部记录");

    private final String label;

    AftersalesMessageChannel(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 凭证类别：纠纷举证要按类目组织材料。 */
  public enum AftersalesEvidenceKind {
    BUYER_EVIDENCE("买家提供的证据"),
    OUR_EVIDENCE("我方提供的证据"),
    PRODUCT_PHOTO("商品照片"),
    DELIVERY_PROOF("物流签收凭据"),
    PAYMENT_PROOF("支付凭据"),
    INSPECTION_REPORT("质检报告"),
    OTHER("其他");

    private final String label;

    AftersalesEvidenceKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 凭证提供方：举证时「平台判责看谁的证据」是第一等问题。 */
  public enum AftersalesEvidenceSide {
    BUYER("买家"),
    US("我方");

    private final String label;

    AftersalesEvidenceSide(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 凭证 / 沟通记录的来源。
   *
   * <p>照抄 V150 {@code order_risk_signal.signal_source} 与 V156 {@code aftersales_owner_assignment}
   * 的既有解法：卷 2 平台消息未交付，只能由租户登记；正式接口到位后由同步器写 {@code MODULE_*} 行，
   * <b>表结构不变</b>。刻意**不伪造** L4 模块号（卷 2 里没有任何一个 L4 是售后消息同步的交付方）。
   */
  public enum AftersalesDetailSource {
    TENANT_MAINTAINED("租户登记"),
    MODULE_2_PLATFORM_MESSAGE("2 平台消息同步（未交付）");

    private final String label;

    AftersalesDetailSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 时间线事件的说话方：蓝图加粗句「买家说了什么 / 我们做了什么」的分类轴。 */
  public enum AftersalesTimelineSide {
    BUYER("买家"),
    US("我方"),
    PLATFORM("平台"),
    SYSTEM("系统");

    private final String label;

    AftersalesTimelineSide(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 时间线事件类型。每一类都对应**真实存在**的一张表的一个时刻，不编造事件。 */
  public enum AftersalesTimelineKind {
    CASE_RECEIVED("我们收到售后申请"),
    PLATFORM_RECEIVED("平台受理"),
    OWNER_ASSIGNED("责任人分配"),
    NOTE_ADDED("备注"),
    AUTHORIZED("授权决定"),
    MESSAGE("沟通记录"),
    ATTACHMENT("凭证上传"),
    LINE_ADDED("商品行登记"),
    TICKET_CREATED("转工单"),
    CASE_RESOLVED("售后单结案");

    private final String label;

    AftersalesTimelineKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 登记一条商品行（涉及哪一行、退几件、这一行退多少钱）。
   *
   * <p>⚠️ V156 的 aftersales_case 只有<b>合计</b>数量，没有行级明细。一张三行订单只退其中一行时，
   * 用合计数无法回答「退的是哪一行、退几件」—— 那是职责句里「商品行与数量」的字面要求。
   *
   * @param orderLineId 必须属于本单所关联的订单（**服务端真查一次** sales_order_line，
   *     因为本迁移刻意不加外键，见 V157 第四节）
   * @param requestedAmount 只有退款类（退货退款 / 仅退款）带；换货 / 补发 / 维修**不许带**
   */
  public record AftersalesCaseLineCreate(
      UUID orderLineId,
      BigDecimal quantity,
      @Schema(nullable = true) BigDecimal requestedAmount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String buyerNote) {}

  /**
   * 登记一条沟通记录。
   *
   * @param sentAt 消息<b>发生</b>的时刻（对话里的那一刻），不是录入时刻 ——
   *     填 now() 会让一条三个月前的平台消息在举证里显示成「今天买家说的」
   * @param capturedAt 采集时刻（照抄 V156 的 captured_at 纪律）
   */
  public record AftersalesMessageCreate(
      String direction,
      String channel,
      String messageBody,
      @Schema(nullable = true) String externalMessageId,
      @Schema(nullable = true) String messageSource,
      @Schema(nullable = true) String sourceReference,
      OffsetDateTime sentAt,
      @Schema(nullable = true) OffsetDateTime capturedAt) {}

  /**
   * 登记一份附件 / 凭证。
   *
   * @param storageRef 对象存储定位符（**不是** URL 全文，也不是文件内容）。
   *     本服务没有文件服务，存 base64 会让一行凭证把详情响应顶到几十 MB。
   */
  public record AftersalesAttachmentCreate(
      String evidenceKind,
      String fileName,
      @Schema(nullable = true) String contentType,
      String storageRef,
      @Schema(nullable = true) Long fileSizeBytes,
      @Schema(nullable = true) String description,
      String attachedBySide,
      @Schema(nullable = true) String evidenceSource,
      @Schema(nullable = true) String sourceReference,
      @Schema(nullable = true) OffsetDateTime capturedAt) {}

  /**
   * 详情页追加单条备注。
   *
   * <p>⚠️ 写 {@code note_source = 'TENANT_SINGLE'}：V156 的表定义里**已经**留了这个值，
   * 但 11.1.1.1 只写 {@code TENANT_BATCH}（它的入口是批量操作）—— 也就是说这个槽位
   * 在本模块交付前<b>没有任何写入者</b>。详情页的「加备注」正是它的写入者。
   * 仍不新增枚举值：V156 是既成事实，本模块是它的读者，读者不该改写者的词表。
   */
  public record AftersalesNoteCreate(
      String noteBody, @Schema(nullable = true) String authorId) {}

  /** 时间线分页。 */
  public record AftersalesTimelineQuery(
      @Schema(nullable = true) Integer page, @Schema(nullable = true) Integer size) {}

  // ================================================================ 出参

  /**
   * 售后单详情聚合 —— 蓝图职责句里的九个区块 + 「详情 + 操作入口 + 时间线」的输出句。
   *
   * <p>⚠️ <b>order / ticket 允许为 null 而其余集合永不为 null</b>：
   *   订单缺失是一**数据异常**（V156 不加外键，订单行可能因故不在），
   *   界面要显示「关联订单缺失」并让人去查；而集合为空是**正常业务状态**
   *   （这张单还没有沟通记录），返回 null 会让前端到处判空。
   *
   * @param orderLines 订单的全部行（V99 快照），含 {@code involved} 标记本单涉及哪几行
   * @param timeline / timelineTotal 时间线全量 + 总条数（单张单的事件量有界，不分页）
   * @param evaluatedAt 本次判定时效的**同一个**参考时刻（与 11.1.1.1 同一条纪律）
   */
  public record AftersalesCaseDetail(
      AftersalesCaseHeader header,
      @Schema(nullable = true) AftersalesOrderInfo order,
      List<AftersalesOrderLine> orderLines,
      List<AftersalesCaseLine> lines,
      List<AftersalesMessage> messages,
      List<AftersalesAttachment> attachments,
      List<AftersalesCaseNote> notes,
      List<AftersalesAuthorization> authorizations,
      List<AftersalesOwnerAssignment> ownerAssignments,
      @Schema(nullable = true) AftersalesServiceTicket ticket,
      AftersalesPlatformStatus platform,
      AftersalesLiability liability,
      List<AftersalesTimelineEvent> timeline,
      long timelineTotal,
      long buyerEventCount,
      long ourEventCount,
      List<AftersalesRelatedModule> relatedModules,
      List<AftersalesActionEntry> actions,
      OffsetDateTime evaluatedAt,
      String alignmentNote) {}

  /**
   * 平台侧状态（aftersales_case 上 V156 已有的四个列 + 两个时限）。
   *
   * <p>⚠️ {@code platformDecisionStatus} <b>恒为 null</b>：那是「平台最终判了退 / 不退 /
   * 部分退」的结果，属 2 平台同步与 11.4 纠纷（<b>均未交付</b>）。填一个占位状态
   * 会让运营以为平台已经判过责，而它没判。
   */
  public record AftersalesPlatformStatus(
      @Schema(nullable = true) String platformCaseNo,
      @Schema(nullable = true) String platformCaseUrl,
      @Schema(nullable = true) OffsetDateTime platformReceivedAt,
      @Schema(nullable = true) OffsetDateTime replyDueAt,
      @Schema(nullable = true) OffsetDateTime resolveDueAt,
      @Schema(nullable = true) OffsetDateTime effectiveDueAt,
      String timelinessState,
      String timelinessLabel,
      @Schema(nullable = true) Long remainingSeconds,
      @Schema(nullable = true) String platformDecisionStatus,
      boolean decisionStatusAvailable,
      String decisionStatusOwnerModule,
      /** <b>恒为 null</b>：sales_order 没有 shop_code 列（实查 V99），卷 2 店铺在 channel
       *  独立库、禁止跨服务读 ⇒ 店铺维度当前不可得。契约里声明为可空，否则前端会被迫
       *  把它当 string 用，而真相是「系统还不知道有哪些店」。 */
      @Schema(nullable = true) String platformShopCode,
      boolean platformShopCodeAvailable,
      String platformShopCodeOwnerModule) {}

  /** 工单状态（aftersales_service_ticket 的 CHECK 四值）。11.4.2.1 未交付，本服务自己管。 */
  public enum AftersalesTicketStatus {
    OPEN("待处理"),
    IN_PROGRESS("处理中"),
    RESOLVED("已解决"),
    CANCELLED("已取消");

    private final String label;

    AftersalesTicketStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 售后单头（本单自己的事实）。
   *
   * @param timelinessState 时效状态，判定真身在 DB 函数 {@code aftersales_timeliness_state()}，
   *     本模块**不重算**
   * @param remainingSeconds 已结案或无平台时限时为 <b>null</b>（null 有语义：不是 0）
   */
  public record AftersalesCaseHeader(
      UUID id,
      String caseNo,
      String caseType,
      String caseTypeLabel,
      String status,
      String statusLabel,
      boolean terminal,
      String reasonCode,
      String reasonLabel,
      @Schema(nullable = true) String reasonNote,
      BigDecimal requestedAmount,
      String currency,
      BigDecimal requestedQuantity,
      @Schema(nullable = true) String buyerRequest,
      String timelinessState,
      String timelinessLabel,
      @Schema(nullable = true) OffsetDateTime replyDueAt,
      @Schema(nullable = true) OffsetDateTime resolveDueAt,
      @Schema(nullable = true) OffsetDateTime effectiveDueAt,
      @Schema(nullable = true) Long remainingSeconds,
      OffsetDateTime receivedAt,
      @Schema(nullable = true) OffsetDateTime resolvedAt) {}

  /**
   * 原订单信息（sales_order 簇，V99，<b>同库只读</b>）。
   *
   * @param platformShopCode <b>恒为 null</b>：sales_order 没有 shop_code 列（实查 V99），
   *     卷 2 店铺在 channel 独立库、禁止跨服务 SQL。空列会被读成「这家店没有售后单」
   * @param customer* 来自 sales_order_party_snapshot（<b>下单当时的快照</b>：客户改名不影响历史单）
   * @param shipping* 来自 order_address_snapshot 同理
   */
  public record AftersalesOrderInfo(
      UUID orderId,
      @Schema(nullable = true) String orderNo,
      @Schema(nullable = true) String orderSource,
      @Schema(nullable = true) String platformShopCode,
      @Schema(nullable = true) String externalOrderNo,
      @Schema(nullable = true) String orderStatus,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String paymentMethod,
      @Schema(nullable = true) BigDecimal grandTotalAmount,
      @Schema(nullable = true) BigDecimal subtotalAmount,
      @Schema(nullable = true) BigDecimal shippingAmount,
      @Schema(nullable = true) OffsetDateTime submittedAt,
      @Schema(nullable = true) String customerCode,
      @Schema(nullable = true) String customerName,
      @Schema(nullable = true) String customerType,
      @Schema(nullable = true) String recipientName,
      @Schema(nullable = true) String recipientPhone,
      @Schema(nullable = true) String recipientEmail,
      @Schema(nullable = true) String countryCode,
      @Schema(nullable = true) String province,
      @Schema(nullable = true) String city,
      @Schema(nullable = true) String addressLine) {}

  /**
   * 订单行（sales_order_line，V99 的<b>下单快照</b>）。
   *
   * @param skuName / specSnapshot 快照值：商品事后改名或改规格都不影响历史订单行
   * @param involved 本单是否涉及这一行（由 aftersales_case_line 决定，没有登记就是 false）
   * @param aftersalesQuantity 本单在这一行上登记的涉及数量；未登记为 null
   */
  public record AftersalesOrderLine(
      UUID id,
      int lineNo,
      UUID skuId,
      String skuCode,
      String skuName,
      @Schema(nullable = true) String specSnapshot,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal lineAmount,
      boolean involved,
      @Schema(nullable = true) BigDecimal aftersalesQuantity,
      @Schema(nullable = true) BigDecimal aftersalesAmount) {}

  /** 商品行（本单登记的涉及行，aftersales_case_line，V157）。 */
  public record AftersalesCaseLine(
      UUID id,
      UUID orderLineId,
      int lineNo,
      String skuCode,
      String skuName,
      @Schema(nullable = true) String specSnapshot,
      BigDecimal quantity,
      @Schema(nullable = true) BigDecimal requestedAmount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String buyerNote,
      OffsetDateTime createdAt) {}

  /** 沟通记录（aftersales_message，V157）。追加式。 */
  public record AftersalesMessage(
      UUID id,
      String direction,
      String directionLabel,
      String channel,
      String channelLabel,
      String messageBody,
      @Schema(nullable = true) String externalMessageId,
      String messageSource,
      String messageSourceLabel,
      @Schema(nullable = true) String sourceReference,
      OffsetDateTime sentAt,
      OffsetDateTime capturedAt,
      @Schema(nullable = true) UUID authorId) {}

  /** 附件与凭证（aftersales_attachment，V157）。追加式。 */
  public record AftersalesAttachment(
      UUID id,
      String evidenceKind,
      String evidenceKindLabel,
      String attachedBySide,
      String attachedBySideLabel,
      String fileName,
      @Schema(nullable = true) String contentType,
      String storageRef,
      @Schema(nullable = true) Long fileSizeBytes,
      @Schema(nullable = true) String description,
      String evidenceSource,
      String evidenceSourceLabel,
      @Schema(nullable = true) String sourceReference,
      OffsetDateTime capturedAt,
      @Schema(nullable = true) UUID uploadedBy) {}

  /** 备注（aftersales_case_note，V156 既有）。追加式。 */
  public record AftersalesCaseNote(
      UUID id,
      String noteBody,
      String noteSource,
      @Schema(nullable = true) UUID batchId,
      @Schema(nullable = true) UUID authorId,
      OffsetDateTime createdAt) {}

  /** 授权决定（aftersales_authorization，V156 既有）。追加式。 */
  public record AftersalesAuthorization(
      UUID id,
      String authorizationKind,
      String authorizationKindLabel,
      String decision,
      String decisionLabel,
      @Schema(nullable = true) BigDecimal authorizedAmount,
      @Schema(nullable = true) String currency,
      @Schema(nullable = true) String reasonNote,
      @Schema(nullable = true) UUID batchId,
      @Schema(nullable = true) UUID decidedBy,
      OffsetDateTime decidedAt) {}

  /** 责任人流转（aftersales_owner_assignment，V156 既有）。追加式：当前值 = captured_at 最大那行。 */
  public record AftersalesOwnerAssignment(
      UUID id,
      String ownerRef,
      @Schema(nullable = true) String ownerDisplayName,
      @Schema(nullable = true) String ownerRoleLabel,
      String ownerSource,
      String ownerSourceLabel,
      @Schema(nullable = true) String sourceReference,
      OffsetDateTime capturedAt) {}

  /** 已转工单（aftersales_service_ticket，V156 既有；11.4.2.1 未交付，本服务自己管这张表）。 */
  public record AftersalesServiceTicket(
      UUID id,
      String ticketNo,
      String ticketStatus,
      String ticketStatusLabel,
      String priority,
      String priorityLabel,
      @Schema(nullable = true) String reasonNote,
      String ownerModule,
      @Schema(nullable = true) UUID createdBy,
      OffsetDateTime createdAt) {}

  /**
   * 时间线事件 —— **蓝图加粗句的落点**。
   *
   * @param id <b>可空</b>：受理 / 平台受理 / 结案三类事件是**聚合事实**（单头的一个时刻），
   *     没有对应的单行主键；指向具体产物的引用由 {@code reference} 承担
   * @param actorSide 说话方：BUYER（买家说了什么）/ US（我们做了什么）/ PLATFORM（平台）/ SYSTEM（系统自己）
   * @param occurredAt 事件<b>发生</b>的时刻。时间线按它排序 —— 不是按录入库的时刻，
   *     否则一条昨天发生的消息今天补录就会排在今天发生的授权之后，顺序与事实相反
   * @param reference 产物引用（备注 / 授权 / 凭证 / 消息 / 工单的 id 或单号），可空
   */
  public record AftersalesTimelineEvent(
      @Schema(nullable = true) UUID id,
      String kind,
      String kindLabel,
      String actorSide,
      String actorSideLabel,
      OffsetDateTime occurredAt,
      String summary,
      @Schema(nullable = true) String body,
      @Schema(nullable = true) String reference) {}

  /** 时间线页：真分页（同一批查询形状，与详情聚合里的 timeline 用同一段 SQL）。 */
  public record AftersalesTimelinePage(
      UUID caseId,
      String caseNo,
      List<AftersalesTimelineEvent> events,
      int page,
      int size,
      long total,
      long buyerEventCount,
      long ourEventCount,
      OffsetDateTime evaluatedAt,
      String alignmentNote) {}

  /**
   * 责任判定（由**真实**事实合成）。
   *
   * <p>⚠️ 「判定」在这里是「我们**认了**还是**不认**」：由授权决定（批准 / 部分批准 / 拒绝）
   * 与原因分类、责任人共同构成。**环节级归因**（批次 / 生产 / 运输 / 仓储）属 11.1.2.2
   * 商品问题归因（**未交付**）⇒ {@code attributionDelivered=false} 且 {@code attributionOwner}
   * 如实报出模块号。**不编一条归因结论出来**：编出来的归因会在供应商索赔（6.4.2.2）
   * 与质量分析（11.5.2）里被当成真结论用。
   *
   * @param liability 责任认定的中文口径（前端直接显示）
   */
  public record AftersalesLiability(
      String decisionCode,
      String decisionLabel,
      String liability,
      @Schema(nullable = true) String decidedAt,
      @Schema(nullable = true) BigDecimal authorizedAmount,
      @Schema(nullable = true) String currency,
      boolean attributionDelivered,
      String attributionOwner,
      String attributionNote,
      String ownerCurrent,
      @Schema(nullable = true) String ownerSourceLabel) {}

  /**
   * 关联模块的可用性 —— 职责句里的「退款与退货关联」**如实报**。
   *
   * @param delivered 11.2 / 11.3 / 11.4 当前<b>全部未交付</b> ⇒ false
   * @param ownerModule 谁交付之后它才会变成真数据
   * @param href <b>刻意恒为 null</b>：未交付的模块没有页面可跳，
   *     一个点进去 404 的链接比没有链接更容易让人误以为功能已交付
   */
  public record AftersalesRelatedModule(
      String code,
      String label,
      boolean delivered,
      String ownerModule,
      String reason,
      @Schema(nullable = true) String href) {}

  /**
   * 操作入口 —— 职责输出句里的「详情 + 操作入口 + 时间线」里的那一项。
   *
   * <p>每个入口都指向**真实存在**的端点。可用为 false 的入口（受理 / 退货 / 退款 / 申诉）
   * 带着 ownerModule 与 reason 报出，界面据此禁用并显示归属 —— 而不是渲染一个点了没反应的按钮。
   *
   * @param method GET / POST
   * @param path 相对后端的路径（前端拼接 base）
   */
  public record AftersalesActionEntry(
      String code,
      String label,
      boolean available,
      String method,
      String path,
      boolean requiresWrite,
      @Schema(nullable = true) String precondition,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String reason) {}

  /**
   * 详情页能力声明：哪些区块真有依据、哪些属未交付模块、哪些数据是租户登记的。
   *
   * <p>与 11.1.1.1 的 capabilities 同款约定（照抄该模块已建立的仓库惯例），
   * 但**分块**到本模块的九个职责区块上。
   */
  public record AftersalesDetailCapabilities(
      List<String> availableSections,
      List<AftersalesSectionCapability> sections,
      List<AftersalesRelatedModule> relatedModules,
      List<AftersalesActionEntry> actionEntries,
      List<AftersalesWriteSource> writeSources,
      boolean crossServiceReadAvailable,
      String crossServiceNote,
      String fileStorageNote,
      String timelineNote) {}

  /** 详情页九个区块里每一块的可用性。 */
  public record AftersalesSectionCapability(
      String section,
      String label,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      @Schema(nullable = true) String reason,
      String landsIn) {}

  /** 写入来源：哪些数据是租户登记的（正式接口到位后由同步器按同一形状写 MODULE_* 行）。 */
  public record AftersalesWriteSource(
      String table,
      String label,
      String sourceCode,
      String sourceLabel,
      boolean upstreamDelivered,
      String ownerModule,
      String reason) {}
}
