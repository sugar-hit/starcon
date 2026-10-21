package com.starcon.crm;

import static com.starcon.crm.MessageInboxModels.*;

import com.starcon.crm.MessageInboxModels.CrmMessageAttachment;
import com.starcon.crm.MessageInboxModels.CrmMessageAttachmentInput;
import com.starcon.crm.MessageInboxModels.CrmMessageBatchInput;
import com.starcon.crm.MessageInboxModels.CrmMessageBatchItem;
import com.starcon.crm.MessageInboxModels.CrmMessageBatchResult;
import com.starcon.crm.MessageInboxModels.CrmMessageBucket;
import com.starcon.crm.MessageInboxModels.CrmMessageCapabilities;
import com.starcon.crm.MessageInboxModels.CrmMessageCapabilityDimension;
import com.starcon.crm.MessageInboxModels.CrmMessageCreateInput;
import com.starcon.crm.MessageInboxModels.CrmMessageDetail;
import com.starcon.crm.MessageInboxModels.CrmMessageIntent;
import com.starcon.crm.MessageInboxModels.CrmMessageOptions;
import com.starcon.crm.MessageInboxModels.CrmMessageOrderContext;
import com.starcon.crm.MessageInboxModels.CrmMessagePage;
import com.starcon.crm.MessageInboxModels.CrmMessageRow;
import com.starcon.crm.MessageInboxModels.CrmMessageShopBucket;
import com.starcon.crm.MessageInboxModels.CrmMessageShopView;
import com.starcon.crm.MessageInboxModels.CrmMessageSummary;
import com.starcon.crm.MessageInboxModels.CrmMessageSyncAttempt;
import com.starcon.crm.MessageInboxModels.CrmMessageSyncInput;
import com.starcon.crm.PlatformMessagePort.PlatformMessageAvailability;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.1.1.1 消息收件箱：集中展示各店铺的平台站内信与买家消息。
 *
 * <p>蓝图 15 卷 15.1.1.1 原话：
 *
 * <blockquote>
 *
 * 职责：集中展示各店铺的平台站内信与买家消息：店铺、买家、主题、正文、附件、平台时间、收发方向、已读状态、关联订单，支持按店铺/类型/优先级/未读筛选与批量处理。 输入：平台消息同步（webhook + 拉取）；用户筛选条件；数据范围（店铺维度）。 输出：消息列表 + 详情视图 + 关联订单上下文。 关系：上游 = 2 渠道与店铺（同步）；下游 = 15.1.1.2 回复、11 售后（转售后）、15.4 工单。 <b>消息必须关联到买家与订单</b>，否则客服看到消息还要去猜是哪一单。
 *
 * </blockquote>
 *
 * <p><b>加粗句「消息必须关联到买家与订单」在本服务落成三层</b>：
 *
 * <ol>
 *   <li><b>DB 层</b>（V291）：{@code crm_message.buyer_reference} / {@code order_reference}
 *       是 NOT NULL + CHECK（空串也不行）—— 一条没有买家、没有订单的消息<b>根本存不进去</b>。
 *   <li><b>服务层</b>（本类）：登记时**必填**，且订单标识不能是空白。
 *   <li><b>展示层</b>：详情视图给出<b>订单上下文</b>；查不到时显示
 *       「订单上下文不可用 + 原因」，<b>不显示空白</b> —— 空白会被客服读成
 *       「这一单没有信息」，而不是「我们没查到」。
 * </ol>
 *
 * <p><b>跨服务纪律</b>：店铺目录在 <b>channel</b> 服务、订单上下文在 <b>trade</b> 服务，
 * 本服务<b>一条 SQL 都不碰</b>对方的库，只经 {@link MessageContextPort} 走授权 HTTP API 读，
 * 且转发的是<b>调用方自己的</b> {@code Authorization} bearer（**不传租户号**）：
 * 上游仍按已认证成员关系解析租户并强制 RLS，租户隔离是<b>两道</b>。
 *
 * <p><b>下游未交付</b>：15.1.1.2 消息发送、15.1.1.3 消息分类、11 售后（转售后）、
 * 15.4 工单在本车道基线里都<b>没有</b>可调的授权 API ⇒ 本模块
 * <b>不提供</b>发送、<b>不做</b>自动分类，转售后 / 建工单只落<b>处置意图</b>
 * （{@code crm_message_intent}，<b>不写下游单据、不编下游 id</b>）。
 */
@Service
public class MessageInboxService {

  // ================================================================ 常量

  /** 已读状态（与 V291 的 CHECK 同词表）。 */
  static final List<String> READ_STATUSES = List.of("UNREAD", "READ", "ARCHIVED");

  /** 收发方向。 */
  static final List<String> DIRECTIONS = List.of("INBOUND", "OUTBOUND");

  /** 消息类型。 */
  static final List<String> MESSAGE_TYPES =
      List.of("PLATFORM_INBOX", "BUYER_MESSAGE", "SYSTEM_NOTICE", "COMPLIANCE");

  /** 优先级。 */
  static final List<String> PRIORITIES = List.of("LOW", "NORMAL", "HIGH", "URGENT");

  /** 来源。PLATFORM_SYNC 词表先钉死（平台站内信适配器未交付）。 */
  static final List<String> SOURCES = List.of("MANUAL_ENTRY", "PLATFORM_SYNC");

  /** 批量处理动作。 */
  static final List<String> BATCH_ACTIONS =
      List.of(
          "MARK_READ", "MARK_UNREAD", "ARCHIVE", "SET_PRIORITY", "RECORD_INTENT", "CANCEL_INTENT");

  /** 处置意图类型。 */
  static final List<String> INTENT_TYPES = List.of("AFTERSALE_CASE", "SERVICE_TICKET");

  /** 列表分页上限（真分页的上限，防止一次拉爆）。 */
  static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final MessageContextPort context;
  private final PlatformMessagePort platform;

  public MessageInboxService(
      DSLContext sql, MessageContextPort context, PlatformMessagePort platform) {
    this.sql = sql;
    this.context = context;
    this.platform = platform;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 四维刻意是 {@code available=false}：
   *
   * <ul>
   *   <li>{@code platform_message_sync} —— 平台买家站内信适配器**未交付**，
   *       本模块**刻意不放假 vendor 实现**，同步入口明确 409 且一条数据都不写；
   *   <li>{@code message_reply} —— 15.1.1.2 消息发送未交付，本模块是**收件箱**，不发消息；
   *   <li>{@code message_classification} —— 15.1.1.3 未交付，优先级由**人工**设置；
   *   <li>{@code aftersale_handoff} / {@code ticket_handoff} —— 11 售后与 15.4 工单未交付，
   *       本模块只落**处置意图**，<b>不写下游单据、不编下游 id</b>。
   * </ul>
   */
  public CrmMessageCapabilities capabilities() {
    List<CrmMessageCapabilityDimension> dimensions =
        List.of(
            new CrmMessageCapabilityDimension(
                "shop_directory",
                "店铺目录（收件箱的数据范围是店铺维度）",
                true,
                "上游 channel 2.3.1.1 店铺清单，经授权 HTTP API 读（GET /channel/shops）",
                "2.3.1.1",
                "筛选项里的店铺来自**真实清单**，不是前端写死的常量；"
                    + "上游读不到时明确 503，不返回空清单冒充「没有店铺」"),
            new CrmMessageCapabilityDimension(
                "buyer_order_linkage",
                "🔴 消息必须关联到买家与订单",
                true,
                "本服务 V291 crm_message.buyer_reference / order_reference（NOT NULL + CHECK）",
                "",
                "一条**没有买家、没有订单**的消息在本库里存不进去 —— "
                    + "「留空以后再补」在客服场景里等于「永远补不上」"),
            new CrmMessageCapabilityDimension(
                "order_context",
                "关联订单上下文（订单号 / 平台 / 金额 / 状态等）",
                true,
                "上游 trade 9.1.1.1 全渠道统一订单列表，经授权 HTTP API 读"
                    + "（GET /trade/sales-orders/list）",
                "9.1.1.1",
                "查不到时显示「订单上下文不可用 + 原因」，**不显示空白** —— "
                    + "空白会被客服读成「这一单没有信息」，而不是「我们没查到」"),
            new CrmMessageCapabilityDimension(
                "order_context_permission",
                "读取上游订单上下文所需的权限",
                true,
                "trade 侧端点要求 trade.orders.read；本服务转发**调用方自己的** bearer",
                "9.1.1.1",
                "调用方在 trade 侧没有该权限时，订单上下文显示「不可用：调用方没有读取上游订单的权限」，"
                    + "消息本身照常可读 —— 不会因为一条关联订单读不到就整个页面报错"),
            new CrmMessageCapabilityDimension(
                "platform_message_sync",
                "平台消息同步（webhook + 拉取）",
                false,
                "channel 服务 vendor 包下当前只有 13 个 OAuth 适配器，**没有**买家站内信适配器",
                "2",
                "同步入口逐平台答「未接入」并返回 **409**，且**一条数据都不写**；"
                    + "一个返回成功回执的假通道会让「平台消息已经同步进来了」看起来发生过，"
                    + "而现场一条真实消息都还没有"),
            new CrmMessageCapabilityDimension(
                "message_reply",
                "回复买家消息（15.1.1.2）",
                false,
                "15.1.1.2 消息发送未交付",
                "15.1.1.2",
                "本模块是**收件箱**：不提供发送入口，也不伪造「已发送」"),
            new CrmMessageCapabilityDimension(
                "message_classification",
                "消息自动分类（15.1.1.3）",
                false,
                "15.1.1.3 消息分类未交付",
                "15.1.1.3",
                "优先级由**人工**设置（批量动作 SET_PRIORITY）；不做自动分类"),
            new CrmMessageCapabilityDimension(
                "aftersale_handoff",
                "转售后（11 售后）",
                false,
                "11 售后的授权 API 在本车道基线里**不可调用**",
                "11",
                "本模块只落**处置意图**（crm_message_intent，reason 必填），"
                    + "**不写售后单、不编售后单号** —— 编一个 id 会让「已转售后」看起来发生过"),
            new CrmMessageCapabilityDimension(
                "ticket_handoff",
                "建工单（15.4 客服工单）",
                false,
                "15.4 客服工单的授权 API 在本车道基线里**不可调用**",
                "15.4",
                "同上：只落**处置意图**，不写工单、不编工单号"),
            new CrmMessageCapabilityDimension(
                "batch_processing",
                "批量处理（已读 / 优先级 / 归档 / 处置意图）",
                true,
                "本服务 V291 + POST /crm/message-inbox/batch（逐条给出结论与原因）",
                "",
                "批量处理**逐条**给出成功与否与原因；失败的条目不会被悄悄跳过"),
            new CrmMessageCapabilityDimension(
                "email_sync",
                "买家邮件收发（15.1.2.1）",
                false,
                "15.1.2.1 邮件收发未交付",
                "15.1.2.1",
                "本模块只管**站内信**；部分平台要求邮件与站内信同会话处理，"
                    + "本模块**不假装**邮件已同步")
        );
    return new CrmMessageCapabilities(
        dimensions,
        READ_STATUSES,
        DIRECTIONS,
        MESSAGE_TYPES,
        PRIORITIES,
        SOURCES,
        BATCH_ACTIONS,
        INTENT_TYPES,
        "上游 2 渠道与店铺（店铺目录）与 9.1.1.1 订单（订单上下文）都在**别的服务**，"
            + "本服务一条 SQL 都不碰对方的库，只经授权 HTTP API 读；"
            + "下游 15.1.1.2 回复 / 15.1.1.3 分类 / 11 售后 / 15.4 工单**均未交付**，"
            + "本模块只落**处置意图**，不写下游单据、不编下游 id。",
        "🔴 消息必须关联到买家与订单：买家与订单在 DB 层就是 NOT NULL + CHECK，"
            + "存不进去；而订单上下文查不到时，页面显示「不可用 + 原因」，"
            + "**不显示空白** —— 客服看到空白会以为这一单没有信息，而不是我们没查到。");
  }

  // ================================================================ 上游读（跨服务端口）

  /** 店铺目录（来自 channel 的真实清单）。上游读不到一律 503。 */
  public List<CrmMessageShopView> shops(String bearer) {
    List<CrmMessageShopView> out = new ArrayList<>();
    for (MessageContextPort.ShopView shop : context.shops(bearer)) {
      out.add(
          new CrmMessageShopView(
              shop.id(),
              nz(shop.code()),
              nz(shop.name()),
              nz(shop.platformCode()),
              nz(shop.platformName()),
              nz(shop.status())));
    }
    return out;
  }

  /** 逐平台的站内信接入状态（平台清单来自**真实店铺目录**，不是写死的常量）。 */
  public List<PlatformMessageAvailability> platformAvailability(String bearer) {
    List<String> platformCodes = new ArrayList<>();
    for (MessageContextPort.ShopView shop : context.shops(bearer)) {
      String code = nz(shop.platformCode()).trim();
      if (!code.isEmpty() && !platformCodes.contains(code)) {
        platformCodes.add(code);
      }
    }
    return platform.availability(platformCodes);
  }

  // ================================================================ 筛选词表

  /**
   * 筛选下拉的词表。
   *
   * <p>平台与店铺来自**本租户真实数据**（不是硬编码词表）；
   * 类型 / 优先级 / 方向 / 已读状态是 **DB CHECK 允许的取值域**（V291 钉死），
   * 列出来是为了让界面能筛，而不是为了让界面自己编一套取值。
   */
  public CrmMessageOptions options() {
    List<String> platformCodes =
        sql.fetch(
                "select distinct platform_code from crm_message"
                    + " where tenant_id = ? order by platform_code",
                currentTenant())
            .map(r -> r.get(0, String.class));
    List<CrmMessageShopBucket> shops = shopBuckets();
    return new CrmMessageOptions(
        platformCodes,
        MESSAGE_TYPES,
        PRIORITIES,
        DIRECTIONS,
        READ_STATUSES,
        shops,
        "平台与店铺来自本租户**真实数据**；类型 / 优先级 / 方向 / 已读状态是 DB 层允许的取值域。");
  }

  // ================================================================ 列表

  /**
   * 消息列表：真分页（LIMIT/OFFSET + 独立 COUNT(*)）+ 真筛选 + 与分页无关的全量汇总。
   *
   * <p>每个筛选参数都对应一个**真实存在**的列。蓝图点名的店铺 / 类型 / 优先级 / 未读
   * 全部可筛；另加方向、买家、订单、关键字与平台时间窗。
   */
  public CrmMessagePage list(
      UUID shopId,
      String platformCode,
      String messageType,
      String priority,
      String direction,
      String readStatus,
      String buyerReference,
      String orderReference,
      String keyword,
      String platformTimeFrom,
      String platformTimeTo,
      int page,
      int size) {
    OffsetDateTime from = optionalMoment(platformTimeFrom, "平台时间起点");
    OffsetDateTime to = optionalMoment(platformTimeTo, "平台时间终点");
    if (from != null && to != null && !to.isAfter(from)) {
      throw bad("平台时间窗是空的：终点「" + to + "」不晚于起点「" + from + "」。");
    }
    int effectiveSize = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    int effectivePage = Math.max(page, 0);
    Filter filter =
        filter(
            shopId,
            platformCode,
            messageType,
            priority,
            direction,
            readStatus,
            buyerReference,
            orderReference,
            keyword,
            from,
            to);
    var rows =
        sql.fetch(
            messageColumns()
                + " from crm_message m"
                + filter.where()
                + " order by m.platform_time desc, m.id"
                + " limit ? offset ?",
            bindings(filter.binds(), effectiveSize, effectivePage * effectiveSize));
    long total =
        sql.fetchOne(
            "select count(*) from crm_message m" + filter.where(), filter.binds().toArray())
            .get(0, Long.class);
    return new CrmMessagePage(
        rows.map(MessageInboxService::message), effectivePage, effectiveSize, total, summary(filter));
  }

  /** 汇总：与分页无关（覆盖**整个筛选结果集**，不是当前页）。 */
  private CrmMessageSummary summary(Filter filter) {
    var row =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where m.read_status='UNREAD') as unread,"
                + " count(*) filter (where m.read_status='READ') as read,"
                + " count(*) filter (where m.read_status='ARCHIVED') as archived,"
                + " count(*) filter (where m.attachment_count>0) as with_attachment"
                + " from crm_message m"
                + filter.where(),
            filter.binds().toArray());
    long total = row.get("total", Long.class);
    long unread = row.get("unread", Long.class);
    long read = row.get("read", Long.class);
    long archived = row.get("archived", Long.class);
    long withAttachment = row.get("with_attachment", Long.class);
    return new CrmMessageSummary(
        total,
        unread,
        read,
        archived,
        withAttachment,
        buckets("m.priority", filter, PRIORITY_LABELS),
        buckets("m.message_type", filter, TYPE_LABELS),
        shopBuckets(filter));
  }

  /** 按一列分组的统计（只列**真实出现过**的取值域）。 */
  private List<CrmMessageBucket> buckets(
      String column, Filter filter, Map<String, String> labels) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record record :
        sql.fetch(
            "select " + column + " as code, count(*) as cnt from crm_message m"
                + filter.where()
                + " group by " + column
                + " order by cnt desc, code",
            filter.binds().toArray())) {
      counts.put(record.get("code", String.class), record.get("cnt", Long.class));
    }
    List<CrmMessageBucket> out = new ArrayList<>();
    counts.forEach(
        (code, count) -> out.add(new CrmMessageBucket(code, labels.getOrDefault(code, code), count)));
    return out;
  }

  /** 按店铺分组的统计（含未读数）。 */
  private List<CrmMessageShopBucket> shopBuckets(Filter filter) {
    List<CrmMessageShopBucket> out = new ArrayList<>();
    for (Record record :
        sql.fetch(
            "select m.shop_id as shop_id, max(m.shop_name) as shop_name,"
                + " max(m.platform_code) as platform_code, count(*) as cnt,"
                + " count(*) filter (where m.read_status='UNREAD') as unread"
                + " from crm_message m"
                + filter.where()
                + " group by m.shop_id order by cnt desc, m.shop_id",
            filter.binds().toArray())) {
      out.add(
          new CrmMessageShopBucket(
              record.get("shop_id", UUID.class),
              nz(record.get("shop_name", String.class)),
              nz(record.get("platform_code", String.class)),
              record.get("cnt", Long.class),
              record.get("unread", Long.class)));
    }
    return out;
  }

  /** 不带筛选的店铺分组（给下拉词表用）。 */
  private List<CrmMessageShopBucket> shopBuckets() {
    return shopBuckets(
        new Filter(
            " where m.tenant_id = ?",
            new ArrayList<>(List.of(currentTenant()))));
  }

  // ================================================================ 详情

  /**
   * 单条消息详情 = 消息本体 + 附件 + 关联订单上下文 + 处置意图。
   *
   * <p>🔴 订单上下文**三种结论**分得很清：
   *
   * <ul>
   *   <li>查到了 ⇒ {@code available=true}，给出订单号 / 平台 / 金额 / 状态 / 买家 / SKU；
   *   <li>上游确实没有这一单 ⇒ {@code available=false} + 说明「没有这一单」；
   *   <li>读不到上游（不可达 / 无权限）⇒ {@code available=false} + 说明**读不到**的原因。
   * </ul>
   *
   * <p>后两者都**不显示空白**：空白会被客服读成「这一单没有信息」。
   */
  public CrmMessageDetail detail(UUID messageId, String bearer) {
    CrmMessageRow row = requireMessage(messageId);
    return new CrmMessageDetail(
        row, attachments(messageId), orderContext(row, bearer), intents(messageId));
  }

  /** 关联订单上下文（查不到时明确说「不可用 + 原因」）。 */
  private CrmMessageOrderContext orderContext(CrmMessageRow message, String bearer) {
    try {
      MessageContextPort.OrderLookup lookup = context.order(message.orderReference(), bearer);
      if (lookup.found() && lookup.order() != null) {
        MessageContextPort.OrderView order = lookup.order();
        return new CrmMessageOrderContext(
            true,
            nz(order.orderNo()),
            nz(order.externalOrderNo()),
            nz(order.orderSource()),
            nz(order.currency()),
            order.grandTotalAmount(),
            order.lineCount(),
            nz(order.buyerName()),
            order.skuCodes() == null ? List.of() : order.skuCodes(),
            nz(order.documentStatus()),
            nz(order.platformStatus()),
            nz(order.paymentStatus()),
            nz(order.fulfillmentStatus()),
            nz(order.shippingWarehouse()),
            nz(order.aftersalesStatus()),
            str(order.submittedAt()),
            null);
      }
      String note = lookup.dataScopeNote();
      return new CrmMessageOrderContext(
          false,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          "订单上下文不可用：" + nz(lookup.reason()) + (note.isEmpty() ? "" : "（上游数据范围：" + note + "）"));
    } catch (ResponseStatusException e) {
      // 上游读不到（503 不可达 / 403 无权限）⇒ 明确说「读不到」，
      // 但**不**把整个详情请求打成失败：消息本身是本服务的事实，读得到。
      String reason = e.getReason() == null ? "上游服务返回 " + e.getStatusCode().value() : e.getReason();
      return new CrmMessageOrderContext(
          false,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          "订单上下文不可用：" + reason);
    }
  }

  // ================================================================ 登记消息

  /**
   * 登记一条平台消息 / 买家消息。
   *
   * <p>🔴 {@code buyerReference} 与 {@code orderReference} **必填**（蓝图加粗句，DB 层同款约束）。
   *
   * <p>店铺必须是 channel 的**真实店铺** —— 登记一条挂在不存在的店铺下的消息，
   * 筛「按店铺」时它会永远不出现。
   *
   * <p>⚠️ {@code source} 只允许 {@code MANUAL_ENTRY}：平台侧站内信适配器**未接入**，
   * 写 {@code PLATFORM_SYNC} 会被拒绝（见 {@link #sync}）。
   */
  @Transactional
  public CrmMessageRow create(CrmMessageCreateInput input, UUID actorId, String bearer) {
    MessageContextPort.ShopView shop = requireShop(input.shopId(), bearer);
    String buyer = nz(input.buyerReference()).trim();
    String order = nz(input.orderReference()).trim();
    if (buyer.isEmpty()) {
      throw bad("必须指定买家标识：蓝图要求「消息必须关联到买家与订单」，否则客服看到消息还要去猜是谁。");
    }
    if (order.isEmpty()) {
      throw bad("必须指定订单标识：蓝图要求「消息必须关联到买家与订单」，否则客服看到消息还要去猜是哪一单。");
    }
    String direction = oneOf(DIRECTIONS, input.direction(), "收发方向");
    String messageType = oneOf(MESSAGE_TYPES, input.messageType(), "消息类型");
    String priority =
        input.priority() == null || input.priority().isBlank()
            ? "NORMAL"
            : oneOf(PRIORITIES, input.priority(), "优先级");
    OffsetDateTime platformTime = parseMoment(input.platformTime(), "平台时间");
    String externalId = nz(input.externalMessageId()).trim();
    OffsetDateTime moment = now();
    UUID id = UUID.randomUUID();

    sql.execute(
        "insert into crm_message"
            + "(id,tenant_id,message_no,shop_id,shop_name,platform_code,buyer_reference,"
            + " order_reference,subject,body,direction,message_type,priority,read_status,"
            + " read_at,read_by,platform_time,attachment_count,source,external_message_id,"
            + " created_by,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,'UNREAD',null,null,?::timestamptz,0,'MANUAL_ENTRY',"
            + " ?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        nextMessageNo(),
        shop.id(),
        nz(shop.name()),
        nz(shop.platformCode()),
        buyer,
        order,
        nz(input.subject()),
        nz(input.body()),
        direction,
        messageType,
        priority,
        platformTime,
        externalId,
        actorId,
        nz(input.note()),
        moment,
        moment);

    if (input.attachments() != null) {
      for (CrmMessageAttachmentInput attachment : input.attachments()) {
        sql.execute(
            "insert into crm_message_attachment"
                + "(id,tenant_id,message_id,file_name,file_url,content_type,size_bytes,created_at)"
                + " values (?,?,?,?,?,?,?,?::timestamptz)",
            UUID.randomUUID(),
            currentTenant(),
            id,
            nz(attachment.fileName()).trim(),
            nz(attachment.fileUrl()),
            nz(attachment.contentType()),
            attachment.sizeBytes() == null ? BigDecimal.ZERO : attachment.sizeBytes(),
            moment);
      }
    }
    return requireMessage(id);
  }

  // ================================================================ 批量处理

  /**
   * 批量处理：已读 / 未读 / 归档 / 改优先级 / 登记处置意图 / 撤销处置意图。
   *
   * <p><b>逐条给出结论</b>：失败的条目不会被悄悄跳过 —— 「批量点了 20 条，
   * 实际生效 17 条」这件事必须看得见。
   */
  @Transactional
  public CrmMessageBatchResult batch(CrmMessageBatchInput input, UUID actorId, String bearer) {
    String action = oneOf(BATCH_ACTIONS, input.action(), "批量动作");
    List<CrmMessageBatchItem> items = new ArrayList<>();
    int succeeded = 0;
    for (UUID messageId : input.messageIds()) {
      CrmMessageBatchItem item = applyAction(action, messageId, input, actorId);
      if (item.success()) {
        succeeded++;
      }
      items.add(item);
    }
    return new CrmMessageBatchResult(
        action,
        input.messageIds().size(),
        succeeded,
        input.messageIds().size() - succeeded,
        items,
        "🔴 转售后 / 建工单在本车道基线里**没有可调的下游 API**："
            + "这两个动作只在本服务落**处置意图**（reason 必填），"
            + "**不写售后单、不写工单、不编下游单号**。");
  }

  /** 单条执行批量动作，返回这条的结论与原因。 */
  private CrmMessageBatchItem applyAction(
      String action, UUID messageId, CrmMessageBatchInput input, UUID actorId) {
    var row =
        sql.fetchOne(
            "select id,message_no,read_status from crm_message where tenant_id = ? and id = ?",
            currentTenant(),
            messageId);
    if (row == null) {
      return new CrmMessageBatchItem(messageId, "", false, "消息不存在，或不属于当前租户");
    }
    String messageNo = nz(row.get("message_no", String.class));
    String readStatus = nz(row.get("read_status", String.class));
    OffsetDateTime moment = now();
    switch (action) {
      case "MARK_READ" -> {
        if ("ARCHIVED".equals(readStatus)) {
          return new CrmMessageBatchItem(
              messageId, messageNo, false, "消息已归档，不能再标记已读");
        }
        if ("READ".equals(readStatus)) {
          return new CrmMessageBatchItem(messageId, messageNo, true, "本来就是已读状态，本次未改动");
        }
        sql.execute(
            "update crm_message set read_status='READ', read_at=?::timestamptz, read_by=?,"
                + " updated_at=?::timestamptz where tenant_id=? and id=?",
            moment,
            actorId,
            moment,
            currentTenant(),
            messageId);
        return new CrmMessageBatchItem(messageId, messageNo, true, "已标记为已读");
      }
      case "MARK_UNREAD" -> {
        if ("ARCHIVED".equals(readStatus)) {
          return new CrmMessageBatchItem(
              messageId, messageNo, false, "消息已归档，不能再改已读状态");
        }
        if ("UNREAD".equals(readStatus)) {
          return new CrmMessageBatchItem(messageId, messageNo, true, "本来就是未读状态，本次未改动");
        }
        sql.execute(
            "update crm_message set read_status='UNREAD', read_at=null, read_by=null,"
                + " updated_at=?::timestamptz where tenant_id=? and id=?",
            moment,
            currentTenant(),
            messageId);
        return new CrmMessageBatchItem(messageId, messageNo, true, "已标记为未读");
      }
      case "ARCHIVE" -> {
        if ("ARCHIVED".equals(readStatus)) {
          return new CrmMessageBatchItem(messageId, messageNo, true, "本来就是已归档，本次未改动");
        }
        sql.execute(
            "update crm_message set read_status='ARCHIVED', updated_at=?::timestamptz"
                + " where tenant_id=? and id=?",
            moment,
            currentTenant(),
            messageId);
        return new CrmMessageBatchItem(messageId, messageNo, true, "已归档");
      }
      case "SET_PRIORITY" -> {
        String priority =
            input.priority() == null || input.priority().isBlank()
                ? null
                : oneOf(PRIORITIES, input.priority(), "优先级");
        if (priority == null) {
          return new CrmMessageBatchItem(messageId, messageNo, false, "改优先级必须指定优先级");
        }
        sql.execute(
            "update crm_message set priority=?, updated_at=?::timestamptz where tenant_id=? and id=?",
            priority,
            moment,
            currentTenant(),
            messageId);
        return new CrmMessageBatchItem(messageId, messageNo, true, "优先级已改为 " + priority);
      }
      case "RECORD_INTENT" -> {
        String intentType =
            input.intentType() == null || input.intentType().isBlank()
                ? null
                : oneOf(INTENT_TYPES, input.intentType(), "处置意图类型");
        if (intentType == null) {
          return new CrmMessageBatchItem(messageId, messageNo, false, "登记意图必须指定意图类型");
        }
        String reason = nz(input.reason()).trim();
        if (reason.isEmpty()) {
          return new CrmMessageBatchItem(
              messageId, messageNo, false, "登记意图必须写明原因：没有理由的意图没法复核");
        }
        var existing =
            sql.fetchOne(
                "select id from crm_message_intent where tenant_id=? and message_id=?"
                    + " and intent_type=? and status='RECORDED'",
                currentTenant(),
                messageId,
                intentType);
        if (existing != null) {
          return new CrmMessageBatchItem(
              messageId, messageNo, false, "已经登记过同类处置意图（" + intentType + "），未重复登记");
        }
        sql.execute(
            "insert into crm_message_intent"
                + "(id,tenant_id,message_id,intent_type,status,reason,requested_by,created_at,updated_at)"
                + " values (?,?,?,?,'RECORDED',?,?,?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),
            currentTenant(),
            messageId,
            intentType,
            reason,
            actorId,
            moment,
            moment);
        return new CrmMessageBatchItem(
            messageId, messageNo, true, "已登记处置意图（" + intentType + "）；下游未交付，未创建任何单据");
      }
      case "CANCEL_INTENT" -> {
        String intentType =
            input.intentType() == null || input.intentType().isBlank()
                ? null
                : oneOf(INTENT_TYPES, input.intentType(), "处置意图类型");
        if (intentType == null) {
          return new CrmMessageBatchItem(messageId, messageNo, false, "撤销意图必须指定意图类型");
        }
        int updated =
            sql.execute(
                "update crm_message_intent set status='CANCELLED', updated_at=?::timestamptz"
                    + " where tenant_id=? and message_id=? and intent_type=? and status='RECORDED'",
                moment,
                currentTenant(),
                messageId,
                intentType);
        if (updated == 0) {
          return new CrmMessageBatchItem(
              messageId, messageNo, false, "没有可撤销的处置意图（" + intentType + "）");
        }
        return new CrmMessageBatchItem(messageId, messageNo, true, "已撤销处置意图");
      }
      default -> {
        return new CrmMessageBatchItem(messageId, messageNo, false, "未知批量动作：" + action);
      }
    }
  }

  // ================================================================ 平台消息同步（未接入）

  /**
   * 平台消息同步入口。
   *
   * <p>⚠️ 当前实现逐平台答「未接入」⇒ 返回 **409** 且**一条数据都不写**。
   *
   * <p>刻意**不**放一个「返回成功回执」的假 vendor：一个点了就成功的假同步
   * 会让「平台消息已经同步进来了」在数据上看起来发生过，而现场一条真实消息都还没有。
   */
  @Transactional
  public CrmMessageSyncAttempt sync(CrmMessageSyncInput input, String bearer) {
    PlatformMessagePort.PlatformMessageFetch fetch =
        platform.fetch(input.platformCode(), input.shopId(), input.since(), bearer);
    if (!fetch.connected()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, nz(fetch.reason()));
    }
    // ⚠️ 这一段只有真的接上平台适配器之后才会走到（当前实现的 connected 恒为 false）。
    // 保留它是为了：适配器交付时只换实现类，本模块不用再改。
    int written = 0;
    OffsetDateTime moment = now();
    for (PlatformMessagePort.PlatformMessage message : fetch.messages()) {
      var inserted =
          sql.execute(
              "insert into crm_message"
                  + "(id,tenant_id,message_no,shop_id,shop_name,platform_code,buyer_reference,"
                  + " order_reference,subject,body,direction,message_type,priority,read_status,"
                  + " read_at,read_by,platform_time,attachment_count,source,external_message_id,"
                  + " created_by,note,created_at,updated_at)"
                  + " values (?,?,?,?,?,?,?,?,?,?,?,?,'NORMAL','UNREAD',null,null,?::timestamptz,0,"
                  + " 'PLATFORM_SYNC',?,null,'',?::timestamptz,?::timestamptz)"
                  + " on conflict (tenant_id,shop_id,external_message_id) where external_message_id <> ''"
                  + " do nothing",
              UUID.randomUUID(),
              currentTenant(),
              nextMessageNo(),
              input.shopId(),
              "",
              input.platformCode(),
              nz(message.buyerReference()),
              nz(message.orderReference()),
              nz(message.subject()),
              nz(message.body()),
              nz(message.direction()),
              nz(message.messageType()),
              parseMoment(message.platformTime(), "平台时间"),
              nz(message.externalMessageId()),
              moment,
              moment);
      written += inserted;
    }
    return new CrmMessageSyncAttempt(
        input.platformCode(),
        input.shopId(),
        true,
        fetch.fetchedCount(),
        written,
        "已拉取 " + fetch.fetchedCount() + " 条平台消息，新增 " + written + " 条（重复的已跳过）。",
        fetch.ownerModule());
  }

  // ================================================================ 内部工具

  /** 筛选条件（WHERE 片段 + 绑定值）。 */
  private record Filter(String where, List<Object> binds) {}

  /** 拼装筛选条件。 */
  private Filter filter(
      UUID shopId,
      String platformCode,
      String messageType,
      String priority,
      String direction,
      String readStatus,
      String buyerReference,
      String orderReference,
      String keyword,
      OffsetDateTime from,
      OffsetDateTime to) {
    var where = new StringBuilder(" where m.tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (shopId != null) {
      where.append(" and m.shop_id = ?");
      binds.add(shopId);
    }
    String platformValue = blankToNull(platformCode);
    if (platformValue != null) {
      where.append(" and m.platform_code = ?");
      binds.add(platformValue);
    }
    String typeValue = blankToNull(messageType);
    if (typeValue != null) {
      where.append(" and m.message_type = ?");
      binds.add(typeValue);
    }
    String priorityValue = blankToNull(priority);
    if (priorityValue != null) {
      where.append(" and m.priority = ?");
      binds.add(priorityValue);
    }
    String directionValue = blankToNull(direction);
    if (directionValue != null) {
      where.append(" and m.direction = ?");
      binds.add(directionValue);
    }
    String statusValue = blankToNull(readStatus);
    if (statusValue != null) {
      where.append(" and m.read_status = ?");
      binds.add(statusValue);
    }
    String buyerValue = blankToNull(buyerReference);
    if (buyerValue != null) {
      where.append(" and m.buyer_reference = ?");
      binds.add(buyerValue);
    }
    String orderValue = blankToNull(orderReference);
    if (orderValue != null) {
      where.append(" and m.order_reference = ?");
      binds.add(orderValue);
    }
    String keywordValue = blankToNull(keyword);
    if (keywordValue != null) {
      String pattern = likePattern(keywordValue);
      where.append(
          " and (m.subject ilike ? escape '\\' or m.body ilike ? escape '\\'"
              + " or m.buyer_reference ilike ? escape '\\' or m.order_reference ilike ? escape '\\'"
              + " or m.message_no ilike ? escape '\\')");
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    if (from != null) {
      where.append(" and m.platform_time >= ?::timestamptz");
      binds.add(from);
    }
    if (to != null) {
      where.append(" and m.platform_time < ?::timestamptz");
      binds.add(to);
    }
    return new Filter(where.toString(), binds);
  }

  /** 关键字 → ILIKE 模式（**转义** {@code %} 与 {@code _}，否则搜 "100%" 会命中一切）。 */
  private static String likePattern(String keyword) {
    String escaped =
        keyword.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  /** 绑定值 + 分页参数。 */
  private static Object[] bindings(List<Object> binds, Object... tail) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(tail));
    return all.toArray();
  }

  /** 消息行的列（列表与详情共用）。 */
  private static String messageColumns() {
    return "select m.id,m.message_no,m.shop_id,m.shop_name,m.platform_code,m.buyer_reference,"
        + " m.order_reference,m.subject,m.body,m.direction,m.message_type,m.priority,m.read_status,"
        + " m.read_at,m.read_by,m.platform_time,m.attachment_count,m.source,m.external_message_id,"
        + " m.created_by,m.note,m.created_at,m.updated_at";
  }

  /** 记录 → 消息行。 */
  private static CrmMessageRow message(Record r) {
    return new CrmMessageRow(
        r.get("id", UUID.class),
        nz(r.get("message_no", String.class)),
        r.get("shop_id", UUID.class),
        nz(r.get("shop_name", String.class)),
        nz(r.get("platform_code", String.class)),
        nz(r.get("buyer_reference", String.class)),
        nz(r.get("order_reference", String.class)),
        nz(r.get("subject", String.class)),
        nz(r.get("body", String.class)),
        nz(r.get("direction", String.class)),
        nz(r.get("message_type", String.class)),
        nz(r.get("priority", String.class)),
        nz(r.get("read_status", String.class)),
        str(r.get("read_at", OffsetDateTime.class)),
        r.get("read_by", UUID.class),
        str(r.get("platform_time", OffsetDateTime.class)),
        r.get("attachment_count", Integer.class),
        nz(r.get("source", String.class)),
        nz(r.get("external_message_id", String.class)),
        r.get("created_by", UUID.class),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  /** 单条消息（不存在 → 404）。 */
  private CrmMessageRow requireMessage(UUID messageId) {
    var row = sql.fetchOne(messageColumns() + " from crm_message m where m.tenant_id=? and m.id=?",
        currentTenant(), messageId);
    if (row == null) {
      throw notFound("消息不存在，或不属于当前租户");
    }
    return message(row);
  }

  /** 附件列表。 */
  private List<CrmMessageAttachment> attachments(UUID messageId) {
    List<CrmMessageAttachment> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select a.id,a.message_id,a.file_name,a.file_url,a.content_type,a.size_bytes,a.created_at"
                + " from crm_message_attachment a where a.tenant_id=? and a.message_id=?"
                + " order by a.created_at, a.id",
            currentTenant(),
            messageId)) {
      out.add(
          new CrmMessageAttachment(
              r.get("id", UUID.class),
              r.get("message_id", UUID.class),
              nz(r.get("file_name", String.class)),
              nz(r.get("file_url", String.class)),
              nz(r.get("content_type", String.class)),
              r.get("size_bytes", BigDecimal.class),
              str(r.get("created_at", OffsetDateTime.class))));
    }
    return out;
  }

  /** 处置意图列表。 */
  private List<CrmMessageIntent> intents(UUID messageId) {
    List<CrmMessageIntent> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select i.id,i.message_id,i.intent_type,i.status,i.reason,i.requested_by,"
                + " i.created_at,i.updated_at from crm_message_intent i"
                + " where i.tenant_id=? and i.message_id=? order by i.created_at desc, i.id",
            currentTenant(),
            messageId)) {
      out.add(
          new CrmMessageIntent(
              r.get("id", UUID.class),
              r.get("message_id", UUID.class),
              nz(r.get("intent_type", String.class)),
              nz(r.get("status", String.class)),
              nz(r.get("reason", String.class)),
              r.get("requested_by", UUID.class),
              str(r.get("created_at", OffsetDateTime.class)),
              str(r.get("updated_at", OffsetDateTime.class))));
    }
    return out;
  }

  /** 店铺必须在 channel 的真实店铺清单里。 */
  private MessageContextPort.ShopView requireShop(UUID shopId, String bearer) {
    for (MessageContextPort.ShopView shop : context.shops(bearer)) {
      if (shop.id() != null && shop.id().equals(shopId)) {
        return shop;
      }
    }
    throw bad(
        "店铺 "
            + shopId
            + " 不在上游 channel 的真实店铺清单里："
            + "对着不存在的店铺登记消息，它在「按店铺」筛选下会永远不出现。");
  }

  /** 消息号：MSG-yyyyMMdd-000001（序列单独授权，见 V291 授权段）。 */
  private String nextMessageNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_message_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "MSG-" + day + "-" + String.format("%06d", seq);
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /**
   * 解析带时区偏移量的时刻。
   *
   * <p>刻意用 {@code OffsetDateTime.parse}：用 {@code Timestamp.valueOf(LocalDateTime)} 会在东八区
   * **静默偏 −28800 秒**，写进去的平台时刻与运营填的差 8 小时，而页面看上去一切正常。
   */
  private static OffsetDateTime parseMoment(String value, String label) {
    String text = nz(value).trim();
    if (text.isEmpty()) {
      throw bad("必须指定" + label + "，例如 2026-10-10T08:00:00+08:00");
    }
    try {
      return OffsetDateTime.parse(text);
    } catch (java.time.format.DateTimeParseException e) {
      throw bad(
          label
              + "「"
              + text
              + "」格式不对：请带时区偏移量，例如 2026-10-10T08:00:00+08:00。"
              + "缺偏移量的时间会在东八区静默偏 8 小时，页面却看不出异常。");
    }
  }

  /** 可选时刻：空白即「不筛」。 */
  private static OffsetDateTime optionalMoment(String value, String label) {
    String text = nz(value).trim();
    return text.isEmpty() ? null : parseMoment(text, label);
  }

  private static String oneOf(List<String> allowed, String value, String label) {
    String candidate = nz(value).trim();
    if (!allowed.contains(candidate)) {
      throw bad(label + "「" + candidate + "」不在允许取值里：" + String.join(" / ", allowed));
    }
    return candidate;
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String str(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }

  /** 优先级取值域的中文标签（服务端给，前端只原样展示）。 */
  private static final Map<String, String> PRIORITY_LABELS =
      Map.of("LOW", "低", "NORMAL", "普通", "HIGH", "高", "URGENT", "紧急");

  /** 消息类型取值域的中文标签。 */
  private static final Map<String, String> TYPE_LABELS =
      Map.of(
          "PLATFORM_INBOX", "平台站内信",
          "BUYER_MESSAGE", "买家消息",
          "SYSTEM_NOTICE", "平台通知",
          "COMPLIANCE", "合规通知");
}
