package com.starcon.crm;

import static com.starcon.crm.BuyerEmailModels.*;

import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailAttachment;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailAttachmentInput;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailCapabilities;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailCapabilityDimension;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailConversation;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailCreateInput;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailDetail;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailPage;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailRow;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailSendInput;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailSendRecord;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailSendResult;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailShopView;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailSyncInput;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailSyncResult;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailTemplateInput;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailTemplatePage;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailTemplateRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.1.2.1 邮件收发：管理买家邮件（Amazon Buyer-Seller Messaging、eBay 邮件等）：
 * 收发、附件、模板、与站内信的双向同步或标记为同源。
 *
 * <p>蓝图 15 卷 15.1.2.1 原话：
 *
 * <blockquote>
 *
 * 职责：管理买家邮件（Amazon Buyer-Seller Messaging、eBay 邮件等）：收发、附件、模板、与站内信的双向同步或标记为同源。
 * 输入：邮件同步；发送请求。 输出：邮件列表与详情 + 发送记录。
 * 关系：上游 = 2 同步；下游 = 15.1.1.2 发送。 <b>部分平台要求邮件与站内信在同一会话处理</b>，分开展示会导致重复回复买家。
 *
 * </blockquote>
 *
 * <p><b>加粗句「分开展示会导致重复回复买家」在本服务落成三层</b>：
 *
 * <ol>
 *   <li><b>会话键</b>（{@code conversation_key}）：同一买家 + 同一订单 + 同一店铺的自然键，
 *       收 / 发两封邮件**必然**同键 ⇒ 会话视图天然完整（{@link #conversation}）。</li>
 *   <li><b>同源标记不靠外键</b>：15.1.1.1 消息收件箱同批并行开发，它的表**不在本模块基线里**
 *       ⇒ 本表<b>不建任何指向它的外键</b>，只留 nullable 的 {@code source_message_id}（软引用）。
 *       两边靠 {@code conversation_key} 相等来对齐「同源」。</li>
 *   <li><b>状态不撒谎</b>：发送失败**不改**收到的那封邮件的状态（它仍是 {@code RECEIVED}），
 *       失败由**发出去的那一行**（OUTBOUND + {@code status='FAILED'}）如实记着 ——
 *       这与 15.1.1.2 蓝图里「发送失败要保留原消息状态」是同一条红线。</li>
 * </ol>
 *
 * <p><b>跨服务纪律</b>：上游 2 同步与店铺目录在 <b>channel</b> 服务，本服务<b>一条 SQL 都不碰</b>
 * 对方的库：店铺目录经 {@link SignoffDeliveredOrderPort} 走授权 HTTP API 读；
 * 平台买家邮件经 {@link PlatformEmailPort} 接缝接（当前实现
 * {@link UnavailablePlatformEmailPort} 对所有平台答「未接入」，同步端点 409 且**不写任何数据**）。
 * 下游 15.1.1.2 消息发送**未交付** ⇒ 本服务**不实现**「从收件箱发送消息」这个动作，
 * 只做**邮件自己的发送记录**。
 */
@Service
public class BuyerEmailService {

  // ================================================================ 常量

  /** 邮件方向（与 V292 的 CHECK 同词表）。 */
  static final List<String> DIRECTIONS = List.of("INBOUND", "OUTBOUND");

  /** 邮件状态。收到的邮件只有 RECEIVED / REPLIED；发出的邮件只有 QUEUED / SENT / FAILED。 */
  static final List<String> EMAIL_STATUSES =
      List.of("RECEIVED", "REPLIED", "QUEUED", "SENT", "FAILED");

  /** 邮件来源。PLATFORM_SYNC 词表先钉死（平台适配器未交付）。 */
  static final List<String> SOURCES = List.of("MANUAL_ENTRY", "PLATFORM_SYNC");

  /** 发送记录的触达通道。PLATFORM_API 词表先钉死，将来平台接口接入时不需要改这里的语义。 */
  static final List<String> TRANSPORTS = List.of("PLATFORM_API", "MANUAL_HANDOFF");

  /** 发送记录状态。 */
  static final List<String> SEND_STATUSES = List.of("QUEUED", "SENT", "FAILED", "CANCELLED");

  /** 邮件模板状态。 */
  static final List<String> TEMPLATE_STATUSES = List.of("ACTIVE", "DISABLED");

  /** 列表页最大条数（与本服务其它模块同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 会话视图一次最多返回多少封（同会话邮件通常只有几封）。 */
  static final int MAX_CONVERSATION = 200;

  private final DSLContext sql;
  private final SignoffDeliveredOrderPort shops;
  private final PlatformEmailPort platformEmail;

  public BuyerEmailService(
      DSLContext sql, SignoffDeliveredOrderPort shops, PlatformEmailPort platformEmail) {
    this.sql = sql;
    this.shops = shops;
    this.platformEmail = platformEmail;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 四维刻意是 {@code available=false}：
   *
   * <ul>
   *   <li>{@code platform_email_sync} —— 平台买家邮件适配器**未交付**（channel/vendor 只有 OAuth 适配器）。
   *   <li>{@code inbox_thread} —— 站内信原文属 15.1.1.1，**同批并行开发**，本模块看不到。
   *   <li>{@code buyer_identity} —— 本仓**没有买家档案**（15.3.1.1 未交付）。
   *   <li>{@code platform_email_api} —— 平台侧邮件发送接口**未接入**，本模块**刻意不放假 vendor 实现**。
   * </ul>
   */
  public CrmBuyerEmailCapabilities capabilities() {
    List<CrmBuyerEmailCapabilityDimension> dimensions =
        List.of(
            new CrmBuyerEmailCapabilityDimension(
                "shop_directory",
                "店铺目录（买家邮件按店铺归属）",
                true,
                "上游 channel V1 shop，经授权 HTTP API 读（GET /channel/shops）",
                "2.3.1.1",
                "邮件的店铺从**真实清单**里选，不是前端写死的常量；"
                    + "店铺清单读不到时端点 503，**绝不**返回空集合冒充「没有店铺」"),
            new CrmBuyerEmailCapabilityDimension(
                "email_registration",
                "邮件登记入口（人工登记收到的买家邮件）",
                true,
                "本服务 V292 crm_buyer_email",
                "",
                "登记的是**收到的**邮件；发出去的邮件由发送记录自动落成 OUTBOUND 行，"
                    + "与收到的那封共用同一个会话键"),
            new CrmBuyerEmailCapabilityDimension(
                "platform_email_sync",
                "🔴 平台买家邮件同步（自动把平台上的买家来信拉进来）",
                false,
                "channel/vendor 目前只有 13 个 OAuth 适配器，**没有任何平台的买家邮件适配器**",
                "2",
                "同步端点明确 **409** 且**不写任何数据**；买家邮件只能**人工登记**。"
                    + "一个「返回空列表」的假同步会让运营以为这个店铺没有买家来信"),
            new CrmBuyerEmailCapabilityDimension(
                "inbox_thread",
                "🔴 站内信原文（同一会话里的另一半）",
                false,
                "15.1.1.1 消息收件箱**同批并行开发**，其表不在本模块基线里",
                "15.1.1.1",
                "本模块**不假装**能看到站内信：同源靠 **conversation_key 相等**标记，"
                    + "两边按同一公式对齐是它落地之后的下一次迭代；"
                    + "本表**不建**指向它的外键（跨批次 FK 会让迁移直接失败）"),
            new CrmBuyerEmailCapabilityDimension(
                "buyer_identity",
                "买家标识（会话键与筛选键）",
                false,
                "本仓**没有买家档案**（15.3.1.1 客户列表未交付）",
                "15.3.1.1",
                "买家标识由运营按店铺提供，**只作会话键与筛选键**，不是买家主数据"),
            new CrmBuyerEmailCapabilityDimension(
                "platform_email_api",
                "🔴 平台侧邮件发送接口（发到买家邮箱）",
                false,
                "平台适配器在 channel 服务，本仓**没有**该 vendor 实现",
                "2",
                "发送记录只允许 MANUAL_HANDOFF（人工交接）；"
                    + "一个「返回成功回执」的假通道会让「买家已经收到回复了」在数据上看起来发生过，"
                    + "而重复回复买家的代价正是平台违规与买家投诉"),
            new CrmBuyerEmailCapabilityDimension(
                "message_send",
                "从收件箱发送消息（站内信的回复动作）",
                false,
                "下游 15.1.1.2 消息发送**未交付**",
                "15.1.1.2",
                "本模块**不实现**「从收件箱发送消息」；它只做**邮件自己的发送记录**，"
                    + "两者在 capabilities 里分开声明，不混为一谈"),
            new CrmBuyerEmailCapabilityDimension(
                "email_template",
                "邮件模板（本模块自己的）",
                true,
                "本服务 V292 crm_buyer_email_template",
                "",
                "模板 CRUD + 发送时引用模板；**不是** 15.4 知识库（那是按店铺 / 类目 / 语言组织的知识档案），"
                    + "也**不是** 15.1.1.2 的消息模板"),
            new CrmBuyerEmailCapabilityDimension(
                "attachment_metadata",
                "附件（**元数据**）",
                true,
                "本服务 V292 crm_buyer_email_attachment",
                "",
                "只记文件名 / 类型 / 字节数 / 存储键 / 校验和；"
                    + "crm 服务**不持有文件字节**，文件归平台侧与 8 仓储"),
            new CrmBuyerEmailCapabilityDimension(
                "send_failure_keeps_state",
                "🔴 发送失败要保留原邮件状态",
                true,
                "本服务 V292 crm_buyer_email + 发送记录",
                "",
                "发送失败**不改**收到的那封邮件的状态（仍是 RECEIVED），"
                    + "失败由**发出去的那一行**（OUTBOUND + FAILED）与失败原因如实记着 —— "
                    + "不能让客服以为已经回过"));
    return new CrmBuyerEmailCapabilities(
        dimensions,
        DIRECTIONS,
        EMAIL_STATUSES,
        SOURCES,
        TRANSPORTS,
        SEND_STATUSES,
        conversationKeySpec(),
        "上游 2 同步与店铺目录在 **channel** 服务，本服务一条 SQL 都不碰对方的库："
            + "店铺目录经授权 HTTP API 读（GET /channel/shops），"
            + "平台买家邮件经 PlatformEmailPort 接缝接（当前对所有平台答「未接入」）。"
            + "下游 15.1.1.2 消息发送未交付 ⇒ 本模块只做**邮件自己的发送记录**；"
            + "站内信侧 15.1.1.1 同批并行开发 ⇒ 本模块**不建**指向它的外键，只用会话键标记同源。",
        "🔴 部分平台要求邮件与站内信在同一会话处理：同一买家 + 同一订单 + 同一店铺的邮件"
            + "共用一个会话键，会话视图里能看到「我们回过没有」；"
            + "发送失败保留原邮件状态，失败原因与平台回执都留痕。");
  }

  /** 会话键公式（页面与 15.1.1.1 都要按这一条对齐）。 */
  public static String conversationKeySpec() {
    return "<shopId>|<buyerRef 小写>|<salesOrderNo 小写>；无订单号时第三段写 '-'。"
        + "同一买家 + 同一订单 + 同一店铺的收 / 发两封邮件必然同键。"
        + "平台给了线程号时可显式指定，会话键以显式值为准。";
  }

  /**
   * 会话键推导（<b>唯一推导点</b>）。
   *
   * <p>为什么必须是唯一推导点：15.1.1.1 消息收件箱同批并行开发，两边要靠同一个键对齐
   * 「同源」。若两个模块各算各的，对齐就永远对不上 —— 而加粗句要的正是这个对齐。
   */
  public static String conversationKey(UUID shopId, String buyerRef, String salesOrderNo) {
    String order = nz(salesOrderNo).trim();
    return shopId
        + "|"
        + nz(buyerRef).trim().toLowerCase(Locale.ROOT)
        + "|"
        + (order.isEmpty() ? "-" : order.toLowerCase(Locale.ROOT));
  }

  // ================================================================ 上游读（跨服务端口）

  /**
   * 店铺目录（来自 channel 的真实清单）+ 每个平台的买家邮件接入状态。
   *
   * <p>上游读不到一律 503 —— **绝不返回空集合冒充「没有店铺」**。
   */
  public List<CrmBuyerEmailShopView> shops(String bearer) {
    List<CrmBuyerEmailShopView> out = new ArrayList<>();
    for (var shop : shops.shops(bearer)) {
      var availability = platformEmail.availability(nz(shop.platformCode()));
      out.add(
          new CrmBuyerEmailShopView(
              shop.id(),
              nz(shop.code()),
              nz(shop.name()),
              nz(shop.platformCode()),
              nz(shop.platformName()),
              nz(shop.status()),
              availability.available(),
              availability.reason()));
    }
    return out;
  }

  // ================================================================ 邮件读取

  /** 邮件列表：真分页 + 真筛选（店铺 / 平台 / 方向 / 状态 / 买家 / 订单 / 会话键 / 主题 / 时间窗）。 */
  public CrmBuyerEmailPage emailPage(
      UUID shopId,
      String platformCode,
      String direction,
      String status,
      String buyerRef,
      String salesOrderNo,
      String conversationKey,
      String subject,
      String receivedFrom,
      String receivedTo,
      int page,
      int size) {
    OffsetDateTime from = optionalMoment(receivedFrom, "邮件时刻起点");
    OffsetDateTime to = optionalMoment(receivedTo, "邮件时刻终点");
    List<Object> binds = new ArrayList<>();
    String where = emailWhere(shopId, platformCode, direction, status, buyerRef, salesOrderNo,
        conversationKey, subject, from, to, binds);

    long total =
        sql.fetchOne("select count(*) from crm_buyer_email e" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmBuyerEmailRow> items =
        sql.fetch(
                emailColumns() + " from crm_buyer_email e" + where
                    + " order by e.received_at desc, e.id limit ? offset ?",
                pageBinds.toArray())
            .map(BuyerEmailService::email);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmBuyerEmailPage(items, safePage, safeSize, total, totalPages);
  }

  /** 单封邮件详情 = 邮件本体 + 附件 + 发送记录 + 同会话邮件。 */
  public CrmBuyerEmailDetail detail(UUID emailId) {
    CrmBuyerEmailRow row = requireEmail(emailId);
    return new CrmBuyerEmailDetail(
        row, attachments(emailId), sendRecords(emailId), conversationEmails(row.conversationKey()));
  }

  /**
   * 会话视图（加粗句「同一会话处理」的落点）。
   *
   * <p>🔴 {@code inboxThread} 恒为 {@code available=false}：站内信原文属 15.1.1.1，
   * 本模块**看不到**。本视图**不假装**能看到站内信 ——
   * 同源靠会话键相等来标记，等它落地后两边按同一公式对齐。
   */
  public CrmBuyerEmailConversation conversation(UUID emailId) {
    CrmBuyerEmailRow row = requireEmail(emailId);
    List<CrmBuyerEmailRow> emails = conversationEmails(row.conversationKey());
    List<CrmBuyerEmailSendRecord> records = new ArrayList<>();
    for (CrmBuyerEmailRow each : emails) {
      records.addAll(sendRecords(each.id()));
    }
    return new CrmBuyerEmailConversation(
        row.conversationKey(),
        row.buyerRef(),
        row.salesOrderNo(),
        emails,
        records,
        new CrmBuyerEmailCapabilityDimension(
            "inbox_thread",
            "站内信原文（同一会话里的另一半）",
            false,
            "15.1.1.1 消息收件箱**同批并行开发**，其表不在本模块基线里",
            "15.1.1.1",
            "本视图**不假装**能看到站内信：同源靠 conversation_key 相等来标记，"
                + "两边按同一公式对齐是它落地之后的下一次迭代"),
        "会话键 "
            + row.conversationKey()
            + " 下的全部邮件（收 + 发）。站内信那一半暂不可见（15.1.1.1 未交付），"
            + "但**同源标记已经落在这条会话键上** —— 它落地后按同一公式对齐即可。");
  }

  // ================================================================ 登记收到的邮件

  /**
   * 登记一封**收到的**买家邮件。
   *
   * <p>方向恒为 INBOUND：发出去的邮件由「发送记录」落成 OUTBOUND 行，
   * 与收到的那封共用同一个会话键 —— 这样会话视图才是完整的。
   */
  @Transactional
  public CrmBuyerEmailRow create(CrmBuyerEmailCreateInput input, UUID actorId, String bearer) {
    CrmBuyerEmailShopView shop = requireShop(input.shopId(), bearer);
    OffsetDateTime receivedAt = parseMoment(input.receivedAt(), "邮件时刻");
    String buyerRef = nz(input.buyerRef()).trim();
    if (buyerRef.isEmpty()) {
      throw bad("必须写明买家标识：它决定这封邮件属于哪个会话，缺了会话就串不起来。");
    }
    String salesOrderNo = nz(input.salesOrderNo()).trim();
    String key =
        nz(input.conversationKey()).trim().isEmpty()
            ? conversationKey(shop.id(), buyerRef, salesOrderNo)
            : nz(input.conversationKey()).trim();
    String externalId = nz(input.externalMessageId()).trim();

    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_buyer_email"
            + "(id,tenant_id,email_no,shop_id,shop_name,platform_code,direction,source,"
            + " buyer_ref,buyer_name,buyer_email,subject,body,conversation_key,source_message_id,"
            + " reply_to_email_id,sales_order_no,external_message_id,received_at,status,"
            + " created_by,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?,'INBOUND','MANUAL_ENTRY',?,?,?,?,?,?,?,null,?,?,?::timestamptz,"
            + " 'RECEIVED',?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        nextEmailNo(),
        shop.id(),
        nz(shop.name()),
        nz(shop.platformCode()).toUpperCase(Locale.ROOT),
        buyerRef,
        nz(input.buyerName()).trim(),
        nz(input.buyerEmail()).trim(),
        nz(input.subject()).trim(),
        nz(input.body()),
        key,
        input.sourceMessageId(),
        salesOrderNo,
        externalId,
        receivedAt,
        actorId,
        nz(input.note()),
        moment,
        moment);
    return emailById(id);
  }

  // ================================================================ 附件

  /**
   * 登记一个附件的**元数据**。
   *
   * <p>🔴 本服务**不持有文件字节**：crm 服务没有对象存储。
   * 附件字节归平台侧与 8 仓储；这里只记「这个附件是什么」。
   */
  @Transactional
  public CrmBuyerEmailAttachment addAttachment(
      UUID emailId, CrmBuyerEmailAttachmentInput input) {
    requireEmail(emailId);
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_buyer_email_attachment"
            + "(id,tenant_id,email_id,file_name,content_type,size_bytes,storage_key,checksum_sha256,"
            + " created_at)"
            + " values (?,?,?,?,?,?,?,?,?::timestamptz)",
        id,
        currentTenant(),
        emailId,
        nz(input.fileName()).trim(),
        nz(input.contentType()).trim(),
        input.sizeBytes() == null ? BigDecimal.ZERO : input.sizeBytes(),
        nz(input.storageKey()).trim(),
        nz(input.checksumSha256()).trim().toLowerCase(Locale.ROOT),
        moment);
    return attachmentById(id);
  }

  // ================================================================ 发送记录

  /**
   * 登记一次发送（= 蓝图输出句里的「发送记录」）。
   *
   * <p>🔴 {@code transport=PLATFORM_API} 一律拒绝：平台侧邮件发送接口**未接入**。
   * 一个「返回成功回执」的假通道会让「买家已经收到回复了」在数据上看起来发生过，
   * 而重复回复买家的代价正是平台违规与买家投诉。
   *
   * <p>🔴 发送失败**不改**收到的那封邮件的状态：它仍是 {@code RECEIVED}。
   * 失败由**发出去的那一行**（OUTBOUND + {@code status='FAILED'}）与失败原因如实记着。
   */
  @Transactional
  public CrmBuyerEmailSendResult addSendRecord(
      UUID emailId, CrmBuyerEmailSendInput input, UUID actorId) {
    CrmBuyerEmailRow email = requireEmail(emailId);
    if (!"INBOUND".equals(email.direction())) {
      throw conflict(
          "邮件 "
              + email.emailNo()
              + " 是**发出去**的那一封（回复），不能再对它登记发送记录 —— "
              + "回复的回复会让会话里出现无限套娃。");
    }
    String transport = oneOf(TRANSPORTS, input.transport(), "触达通道");
    if ("PLATFORM_API".equals(transport)) {
      throw conflict(
          "平台侧邮件发送接口**未接入**（capabilities 里 platform_email_api 维度 available=false）："
              + "本模块不允许登记一条「已发到平台」的记录 —— 那会让「买家已经收到回复了」"
              + "在数据上看起来发生过，而重复回复买家的代价正是平台违规与买家投诉。"
              + "请改用 MANUAL_HANDOFF 登记人工交接。");
    }
    String status = oneOf(SEND_STATUSES, input.status(), "发送结果");
    if ("FAILED".equals(status) && nz(input.failureReason()).trim().isEmpty()) {
      throw bad("登记失败必须写明失败原因：只写「失败」而不写为什么，事后无法判断该不该重试。");
    }

    CrmBuyerEmailTemplateRow template = null;
    if (input.templateId() != null) {
      template = requireTemplate(input.templateId());
      if (!"ACTIVE".equals(template.status())) {
        throw conflict(
            "邮件模板 " + template.templateCode() + " 已停用，不能用它发送：停用的模板不该再出现在回复里。");
      }
      if (template.shopId() != null && !template.shopId().equals(email.shopId())) {
        throw conflict(
            "邮件模板 "
                + template.templateCode()
                + " 只适用于店铺 "
                + template.shopId()
                + "，不能用于店铺 "
                + email.shopId()
                + " 的邮件。");
      }
    }

    String subject = nz(input.subject()).trim();
    String body = nz(input.body());
    if (subject.isEmpty() && template != null) subject = nz(template.subject()).trim();
    if (body.isEmpty() && template != null) body = nz(template.body());
    if (subject.isEmpty() && body.isEmpty()) {
      throw bad("这次发送没有正文：要么写主题与正文，要么引用一个邮件模板。");
    }

    OffsetDateTime moment = now();
    OffsetDateTime attemptedAt = "SENT".equals(status) ? moment : null;
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into crm_buyer_email_send_record"
            + "(id,tenant_id,email_id,template_id,template_code,transport,status,subject,body,"
            + " platform_receipt,attempted_at,failure_reason,operator_id,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?::timestamptz)",
        id,
        currentTenant(),
        emailId,
        template == null ? null : template.id(),
        template == null ? "" : template.templateCode(),
        transport,
        status,
        subject,
        body,
        nz(input.platformReceipt()).trim(),
        attemptedAt,
        nz(input.failureReason()).trim(),
        actorId,
        moment);

    // 发出去的那一行（OUTBOUND）：与收到的那封共用会话键 ⇒ 会话视图完整。
    // ⚠️ 它的状态**就是**这次发送的结果（QUEUED / SENT / FAILED），
    //    而**收到的那封**的状态在失败时**不动**（仍是 RECEIVED）。
    UUID outboundId = UUID.randomUUID();
    sql.execute(
        "insert into crm_buyer_email"
            + "(id,tenant_id,email_no,shop_id,shop_name,platform_code,direction,source,"
            + " buyer_ref,buyer_name,buyer_email,subject,body,conversation_key,source_message_id,"
            + " reply_to_email_id,sales_order_no,external_message_id,received_at,status,"
            + " created_by,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?,'OUTBOUND','MANUAL_ENTRY',?,?,?,?,?,?,null,?,?,?,?::timestamptz,"
            + " ?,?,?,?::timestamptz,?::timestamptz)",
        outboundId,
        currentTenant(),
        nextEmailNo(),
        email.shopId(),
        email.shopName(),
        email.platformCode(),
        email.buyerRef(),
        email.buyerName(),
        email.buyerEmail(),
        subject,
        body,
        email.conversationKey(),
        emailId,
        email.salesOrderNo(),
        "",
        moment,
        outboundStatus(status),
        actorId,
        nz(input.note()),
        moment,
        moment);

    // 附件挂在**发出去的那一封**上：买家寄来的附件与我们的附件不能混在一张清单里。
    if (input.attachments() != null) {
      for (CrmBuyerEmailAttachmentInput attachment : input.attachments()) {
        UUID attachmentId = UUID.randomUUID();
        sql.execute(
            "insert into crm_buyer_email_attachment"
                + "(id,tenant_id,email_id,file_name,content_type,size_bytes,storage_key,"
                + " checksum_sha256,created_at)"
                + " values (?,?,?,?,?,?,?,?,?::timestamptz)",
            attachmentId,
            currentTenant(),
            outboundId,
            nz(attachment.fileName()).trim(),
            nz(attachment.contentType()).trim(),
            attachment.sizeBytes() == null ? BigDecimal.ZERO : attachment.sizeBytes(),
            nz(attachment.storageKey()).trim(),
            nz(attachment.checksumSha256()).trim().toLowerCase(Locale.ROOT),
            moment);
      }
    }

    // 🔴 只有**成功**的发送才把收到的那封标成「已回复」；失败**保留原状态**。
    if ("SENT".equals(status)) {
      sql.execute(
          "update crm_buyer_email set status='REPLIED', updated_at=?::timestamptz"
              + " where tenant_id=? and id=?",
          moment,
          currentTenant(),
          emailId);
    }

    CrmBuyerEmailSendRecord record = sendRecordById(id);
    return new CrmBuyerEmailSendResult(
        record,
        emailById(outboundId),
        "SENT".equals(status)
            ? "已发出。收到的那封邮件状态改为「已回复」，会话里能看到我们回的那一行。"
            : "已登记，但**没有发出**：收到的那封邮件状态保持不变（仍是「已收到」），"
                + "失败原因与平台回执都留在这条发送记录里 —— 不能让客服以为已经回过。");
  }

  /** 发送结果 → 发出去的那一行的状态（QUEUED / SENT / FAILED；CANCELLED 当作未发出）。 */
  private static String outboundStatus(String sendStatus) {
    return switch (sendStatus) {
      case "SENT" -> "SENT";
      case "FAILED" -> "FAILED";
      default -> "QUEUED";
    };
  }

  // ================================================================ 邮件模板

  /** 模板列表：真分页 + 真筛选（店铺 / 语言 / 状态 / 关键字）。 */
  public CrmBuyerEmailTemplatePage templatePage(
      UUID shopId, String locale, String status, String keyword, int page, int size) {
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    if (shopId != null) {
      where.append(" and (shop_id = ? or shop_id is null)");
      binds.add(shopId);
    }
    if (blankToNull(locale) != null) {
      where.append(" and locale = ?");
      binds.add(locale.trim());
    }
    if (blankToNull(status) != null) {
      where.append(" and status = ?");
      binds.add(status.trim());
    }
    if (blankToNull(keyword) != null) {
      where.append(" and (strpos(lower(template_code), lower(?)) > 0"
          + " or strpos(lower(name), lower(?)) > 0)");
      binds.add(keyword.trim());
      binds.add(keyword.trim());
    }
    long total =
        sql.fetchOne("select count(*) from crm_buyer_email_template" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmBuyerEmailTemplateRow> items =
        sql.fetch(
                "select id,tenant_id,template_code,name,locale,subject,body,shop_id,status,created_by,"
                    + " note,created_at,updated_at,"
                    + " (select count(*) from crm_buyer_email_send_record s"
                    + "   where s.tenant_id = t.tenant_id and s.template_id = t.id) as usage_count"
                    + " from crm_buyer_email_template t"
                    + where
                    + " order by template_code limit ? offset ?",
                pageBinds.toArray())
            .map(BuyerEmailService::template);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmBuyerEmailTemplatePage(items, safePage, safeSize, total, totalPages);
  }

  /** 单条模板详情。 */
  public CrmBuyerEmailTemplateRow template(UUID templateId) {
    return requireTemplate(templateId);
  }

  /** 新建模板。 */
  @Transactional
  public CrmBuyerEmailTemplateRow createTemplate(
      CrmBuyerEmailTemplateInput input, UUID actorId) {
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_buyer_email_template"
            + "(id,tenant_id,template_code,name,locale,subject,body,shop_id,status,created_by,note,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        nz(input.templateCode()).trim(),
        nz(input.name()).trim(),
        templateLocale(input.locale()),
        nz(input.subject()).trim(),
        nz(input.body()),
        input.shopId(),
        templateStatus(input.status()),
        actorId,
        nz(input.note()),
        moment,
        moment);
    return requireTemplate(id);
  }

  /**
   * 改模板。
   *
   * <p>⚠️ {@code template_code} 建后**不可改**：它是业务键，改了会让「这条回复用的是哪套话术」
   * 在历史里出现两个答案。
   */
  @Transactional
  public CrmBuyerEmailTemplateRow updateTemplate(
      UUID templateId, CrmBuyerEmailTemplateInput input, UUID actorId) {
    CrmBuyerEmailTemplateRow existing = requireTemplate(templateId);
    String code = nz(input.templateCode()).trim();
    if (!code.isEmpty() && !code.equals(existing.templateCode())) {
      throw bad(
          "模板编码建后不可改（当前 "
              + existing.templateCode()
              + "）：它是业务键，改了会让「这条回复用的是哪套话术」在历史里出现两个答案。");
    }
    OffsetDateTime moment = now();
    sql.execute(
        "update crm_buyer_email_template set name=?,locale=?,subject=?,body=?,shop_id=?,status=?,"
            + " note=?,updated_at=?::timestamptz where tenant_id=? and id=?",
        nz(input.name()).trim().isEmpty() ? existing.name() : nz(input.name()).trim(),
        templateLocale(input.locale()),
        nz(input.subject()).trim(),
        nz(input.body()),
        input.shopId(),
        templateStatus(input.status()),
        nz(input.note()),
        moment,
        currentTenant(),
        templateId);
    return requireTemplate(templateId);
  }

  /**
   * 删模板。
   *
   * <p>🔴 **已被发送记录引用过的模板删不掉**（DB 触发器 guard_crm_buyer_email_template_delete）：
   * 删掉它会让「这次回复用的哪套话术」在历史里只剩一个编码快照、没有正文可查。
   * 要停用请改成 DISABLED。
   */
  @Transactional
  public void deleteTemplate(UUID templateId) {
    CrmBuyerEmailTemplateRow existing = requireTemplate(templateId);
    sql.execute(
        "delete from crm_buyer_email_template where tenant_id=? and id=?",
        currentTenant(),
        templateId);
  }

  // ================================================================ 平台邮件同步

  /**
   * 平台买家邮件同步。
   *
   * <p>🔴 当前**恒为 409** 且**不写任何数据**：平台买家邮件适配器未交付
   * （channel/vendor 只有 OAuth 适配器）。未接入是业务状态，不是故障 ——
   * 端点如实告诉调用方「这个能力现在不存在」，而不是返回空列表冒充「这个店铺没有买家来信」。
   */
  @Transactional
  public CrmBuyerEmailSyncResult sync(CrmBuyerEmailSyncInput input, String bearer) {
    CrmBuyerEmailShopView shop = requireShop(input.shopId(), bearer);
    var availability = platformEmail.availability(shop.platformCode());
    if (!availability.available()) {
      throw conflict(availability.reason());
    }
    var fetched = platformEmail.fetch(shop.platformCode(), shop.id(), bearer);
    if (!fetched.available()) {
      throw conflict(fetched.reason());
    }
    int synced = 0;
    for (var raw : fetched.emails()) {
      insertSyncedEmail(shop, raw);
      synced++;
    }
    return new CrmBuyerEmailSyncResult(
        synced,
        true,
        "",
        "已同步 " + synced + " 封平台买家邮件（按 external_message_id 幂等去重）。");
  }

  /** 落一行平台同步来的邮件（人工登记那条路不经过这里）。 */
  private void insertSyncedEmail(CrmBuyerEmailShopView shop, PlatformEmailPort.RawEmail raw) {
    OffsetDateTime sentAt = raw.sentAt() == null ? now() : raw.sentAt();
    String buyerRef = nz(raw.buyerRef()).trim();
    String salesOrderNo = nz(raw.salesOrderNo()).trim();
    String key =
        nz(raw.conversationRef()).trim().isEmpty()
            ? conversationKey(shop.id(), buyerRef, salesOrderNo)
            : nz(raw.conversationRef()).trim();
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_buyer_email"
            + "(id,tenant_id,email_no,shop_id,shop_name,platform_code,direction,source,"
            + " buyer_ref,buyer_name,buyer_email,subject,body,conversation_key,source_message_id,"
            + " reply_to_email_id,sales_order_no,external_message_id,received_at,status,"
            + " created_by,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?,'INBOUND','PLATFORM_SYNC',?,?,?,?,?,?,null,null,?,?,"
            + "?::timestamptz,'RECEIVED',null,'',?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id, external_message_id) where external_message_id <> '' do nothing",
        id,
        currentTenant(),
        nextEmailNo(),
        shop.id(),
        nz(shop.name()),
        nz(shop.platformCode()).toUpperCase(Locale.ROOT),
        buyerRef,
        nz(raw.buyerName()).trim(),
        nz(raw.buyerEmail()).trim(),
        nz(raw.subject()).trim(),
        nz(raw.body()),
        key,
        salesOrderNo,
        nz(raw.externalMessageId()).trim(),
        sentAt,
        moment,
        moment);
  }

  // ================================================================ 内部

  /** 目标店铺必须在 channel 的真实店铺清单里（否则运营会对一家不存在的店登记邮件）。 */
  private CrmBuyerEmailShopView requireShop(UUID shopId, String bearer) {
    for (CrmBuyerEmailShopView shop : shops(bearer)) {
      if (shop.id().equals(shopId)) return shop;
    }
    throw bad("店铺 " + shopId + " 不在上游的店铺清单里：请从真实店铺目录里选一个店铺。");
  }

  private CrmBuyerEmailRow requireEmail(UUID emailId) {
    CrmBuyerEmailRow row = emailById(emailId);
    if (row == null) throw notFound("邮件不存在：" + emailId);
    return row;
  }

  private CrmBuyerEmailTemplateRow requireTemplate(UUID templateId) {
    var row =
        sql.fetchOne(
            "select id,tenant_id,template_code,name,locale,subject,body,shop_id,status,created_by,"
                + " note,created_at,updated_at,"
                + " (select count(*) from crm_buyer_email_send_record s"
                + "   where s.tenant_id = t.tenant_id and s.template_id = t.id) as usage_count"
                + " from crm_buyer_email_template t where t.tenant_id = ? and t.id = ?",
            currentTenant(),
            templateId);
    if (row == null) throw notFound("邮件模板不存在：" + templateId);
    return template(row);
  }

  private CrmBuyerEmailRow emailById(UUID emailId) {
    var row =
        sql.fetchOne(emailColumns() + " from crm_buyer_email e where e.tenant_id = ? and e.id = ?",
            currentTenant(), emailId);
    return row == null ? null : email(row);
  }

  private CrmBuyerEmailAttachment attachmentById(UUID attachmentId) {
    var row =
        sql.fetchOne(
            "select id,tenant_id,email_id,file_name,content_type,size_bytes,storage_key,"
                + " checksum_sha256,created_at from crm_buyer_email_attachment"
                + " where tenant_id = ? and id = ?",
            currentTenant(),
            attachmentId);
    if (row == null) throw notFound("附件不存在：" + attachmentId);
    return attachment(row);
  }

  private CrmBuyerEmailSendRecord sendRecordById(UUID id) {
    var row =
        sql.fetchOne(
            "select id,tenant_id,email_id,template_id,template_code,transport,status,subject,body,"
                + " platform_receipt,attempted_at,failure_reason,operator_id,created_at"
                + " from crm_buyer_email_send_record where tenant_id = ? and id = ?",
            currentTenant(),
            id);
    if (row == null) throw notFound("发送记录不存在：" + id);
    return sendRecord(row);
  }

  private List<CrmBuyerEmailAttachment> attachments(UUID emailId) {
    return sql.fetch(
            "select id,tenant_id,email_id,file_name,content_type,size_bytes,storage_key,"
                + " checksum_sha256,created_at from crm_buyer_email_attachment"
                + " where tenant_id = ? and email_id = ? order by created_at, id",
            currentTenant(),
            emailId)
        .map(BuyerEmailService::attachment);
  }

  private List<CrmBuyerEmailSendRecord> sendRecords(UUID emailId) {
    return sql.fetch(
            "select id,tenant_id,email_id,template_id,template_code,transport,status,subject,body,"
                + " platform_receipt,attempted_at,failure_reason,operator_id,created_at"
                + " from crm_buyer_email_send_record"
                + " where tenant_id = ? and email_id = ? order by created_at desc, id desc",
            currentTenant(),
            emailId)
        .map(BuyerEmailService::sendRecord);
  }

  /** 同一会话键下的全部邮件（**时间正序**：会话是自上而下读的）。 */
  private List<CrmBuyerEmailRow> conversationEmails(String conversationKey) {
    return sql.fetch(
            emailColumns() + " from crm_buyer_email e"
                + " where e.tenant_id = ? and e.conversation_key = ?"
                + " order by e.received_at asc, e.id asc limit ?",
            currentTenant(),
            conversationKey,
            MAX_CONVERSATION)
        .map(BuyerEmailService::email);
  }

  /** 邮件号：BE-yyyyMMdd-000001（序列单独授权，见 V292 授权段）。 */
  private String nextEmailNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_buyer_email_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "BE-" + day + "-" + String.format("%06d", seq);
  }

  /** 邮件列表的 WHERE 片段与绑定（列表与计数**同一套口径**）。 */
  private String emailWhere(
      UUID shopId,
      String platformCode,
      String direction,
      String status,
      String buyerRef,
      String salesOrderNo,
      String conversationKey,
      String subject,
      OffsetDateTime from,
      OffsetDateTime to,
      List<Object> binds) {
    binds.add(currentTenant());
    StringBuilder where = new StringBuilder(" where e.tenant_id = ?");
    if (shopId != null) {
      where.append(" and e.shop_id = ?");
      binds.add(shopId);
    }
    if (blankToNull(platformCode) != null) {
      where.append(" and e.platform_code = ?");
      binds.add(platformCode.trim());
    }
    if (blankToNull(direction) != null) {
      where.append(" and e.direction = ?");
      binds.add(direction.trim());
    }
    if (blankToNull(status) != null) {
      where.append(" and e.status = ?");
      binds.add(status.trim());
    }
    if (blankToNull(buyerRef) != null) {
      where.append(" and e.buyer_ref = ?");
      binds.add(buyerRef.trim());
    }
    if (blankToNull(salesOrderNo) != null) {
      where.append(" and e.sales_order_no = ?");
      binds.add(salesOrderNo.trim());
    }
    if (blankToNull(conversationKey) != null) {
      where.append(" and e.conversation_key = ?");
      binds.add(conversationKey.trim());
    }
    if (blankToNull(subject) != null) {
      where.append(" and strpos(lower(e.subject), lower(?)) > 0");
      binds.add(subject.trim());
    }
    if (from != null) {
      where.append(" and e.received_at >= ?::timestamptz");
      binds.add(from);
    }
    if (to != null) {
      where.append(" and e.received_at < ?::timestamptz");
      binds.add(to);
    }
    return where.toString();
  }

  /** 邮件行的 SELECT 片段（附件数与发送记录数**从真实明细数出来**，不是记出来的计数器）。 */
  private static String emailColumns() {
    return "select e.id,e.tenant_id,e.email_no,e.shop_id,e.shop_name,e.platform_code,e.direction,"
        + " e.source,e.buyer_ref,e.buyer_name,e.buyer_email,e.subject,e.body,e.conversation_key,"
        + " e.source_message_id,e.reply_to_email_id,e.sales_order_no,e.external_message_id,"
        + " e.received_at,e.status,e.created_by,e.note,e.created_at,e.updated_at,"
        + " (select count(*)::int from crm_buyer_email_attachment a"
        + "   where a.tenant_id = e.tenant_id and a.email_id = e.id) as attachment_count,"
        + " (select count(*)::int from crm_buyer_email_send_record s"
        + "   where s.tenant_id = e.tenant_id and s.email_id = e.id) as send_record_count";
  }

  private static CrmBuyerEmailRow email(Record r) {
    return new CrmBuyerEmailRow(
        r.get("id", UUID.class),
        nz(r.get("email_no", String.class)),
        r.get("shop_id", UUID.class),
        nz(r.get("shop_name", String.class)),
        nz(r.get("platform_code", String.class)),
        nz(r.get("direction", String.class)),
        nz(r.get("source", String.class)),
        nz(r.get("buyer_ref", String.class)),
        nz(r.get("buyer_name", String.class)),
        nz(r.get("buyer_email", String.class)),
        nz(r.get("subject", String.class)),
        nz(r.get("body", String.class)),
        nz(r.get("conversation_key", String.class)),
        r.get("source_message_id", UUID.class),
        r.get("reply_to_email_id", UUID.class),
        nz(r.get("sales_order_no", String.class)),
        nz(r.get("external_message_id", String.class)),
        str(r.get("received_at", OffsetDateTime.class)),
        nz(r.get("status", String.class)),
        nz(r.get("attachment_count", Long.class)),
        nz(r.get("send_record_count", Long.class)),
        r.get("created_by", UUID.class),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmBuyerEmailAttachment attachment(Record r) {
    return new CrmBuyerEmailAttachment(
        r.get("id", UUID.class),
        r.get("email_id", UUID.class),
        nz(r.get("file_name", String.class)),
        nz(r.get("content_type", String.class)),
        r.get("size_bytes", BigDecimal.class),
        nz(r.get("storage_key", String.class)),
        nz(r.get("checksum_sha256", String.class)),
        str(r.get("created_at", OffsetDateTime.class)));
  }

  private static CrmBuyerEmailSendRecord sendRecord(Record r) {
    return new CrmBuyerEmailSendRecord(
        r.get("id", UUID.class),
        r.get("email_id", UUID.class),
        r.get("template_id", UUID.class),
        nz(r.get("template_code", String.class)),
        nz(r.get("transport", String.class)),
        nz(r.get("status", String.class)),
        nz(r.get("subject", String.class)),
        nz(r.get("body", String.class)),
        nz(r.get("platform_receipt", String.class)),
        str(r.get("attempted_at", OffsetDateTime.class)),
        nz(r.get("failure_reason", String.class)),
        r.get("operator_id", UUID.class),
        str(r.get("created_at", OffsetDateTime.class)));
  }

  private static CrmBuyerEmailTemplateRow template(Record r) {
    return new CrmBuyerEmailTemplateRow(
        r.get("id", UUID.class),
        nz(r.get("template_code", String.class)),
        nz(r.get("name", String.class)),
        nz(r.get("locale", String.class)),
        nz(r.get("subject", String.class)),
        nz(r.get("body", String.class)),
        r.get("shop_id", UUID.class),
        nz(r.get("status", String.class)),
        nz(r.get("usage_count", Long.class)),
        r.get("created_by", UUID.class),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  // ================================================================ 小工具

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
   * **静默偏 −28800 秒**，写进去的邮件时刻与运营填的差 8 小时，而页面看上去一切正常。
   */
  private static OffsetDateTime parseMoment(String value, String label) {
    String text = nz(value).trim();
    if (text.isEmpty()) throw bad("必须指定" + label + "，例如 2026-10-10T08:00:00+08:00");
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

  /** 模板语言标签：留空即 en-US（与 V99 知识库同一默认）。 */
  private static String templateLocale(String locale) {
    String text = blankToNull(locale);
    return text == null ? "en-US" : text;
  }

  private static String templateStatus(String status) {
    String text = blankToNull(status);
    return text == null ? "ACTIVE" : oneOf(TEMPLATE_STATUSES, text, "模板状态");
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static long nz(Long value) {
    return value == null ? 0L : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String str(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }
}
