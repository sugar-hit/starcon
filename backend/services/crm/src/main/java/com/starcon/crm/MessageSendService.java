package com.starcon.crm;

import static com.starcon.crm.MessageSendModels.*;

import com.starcon.crm.MessageSendModels.CrmMessageSendAttachment;
import com.starcon.crm.MessageSendModels.CrmMessageSendAttachmentInput;
import com.starcon.crm.MessageSendModels.CrmMessageSendAttempt;
import com.starcon.crm.MessageSendModels.CrmMessageSendBatchInput;
import com.starcon.crm.MessageSendModels.CrmMessageSendBatchItem;
import com.starcon.crm.MessageSendModels.CrmMessageSendBatchResult;
import com.starcon.crm.MessageSendModels.CrmMessageSendBucket;
import com.starcon.crm.MessageSendModels.CrmMessageSendCapabilityDimension;
import com.starcon.crm.MessageSendModels.CrmMessageSendCapabilities;
import com.starcon.crm.MessageSendModels.CrmMessageSendDetail;
import com.starcon.crm.MessageSendModels.CrmMessageSendInput;
import com.starcon.crm.MessageSendModels.CrmMessageSendKnowledgeTemplate;
import com.starcon.crm.MessageSendModels.CrmMessageSendOutcome;
import com.starcon.crm.MessageSendModels.CrmMessageSendPage;
import com.starcon.crm.MessageSendModels.CrmMessageSendResultInput;
import com.starcon.crm.MessageSendModels.CrmMessageSendRetryInput;
import com.starcon.crm.MessageSendModels.CrmMessageSendRow;
import com.starcon.crm.MessageSendModels.CrmMessageSendSummary;
import com.starcon.crm.MessageSendModels.CrmMessageSendTarget;
import com.starcon.crm.MessageSendModels.CrmMessageSendTargetPage;
import com.starcon.crm.MessageSendModels.CrmMessageSendTemplateInput;
import com.starcon.crm.MessageSendModels.CrmMessageSendTemplatePage;
import com.starcon.crm.MessageSendModels.CrmMessageSendTemplateRow;
import com.starcon.crm.MessageSendModels.CrmMessageTargetState;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.1.1.2 消息发送：回复买家消息。
 *
 * <p>蓝图 15 卷 15.1.1.2 原话：
 *
 * <blockquote>
 *
 * 职责：回复买家消息：调用模板、手工编辑、附件支持、发送记录、失败重试；支持批量相同回复（同类问题）。 输入：回复内容（模板或手工）；目标消息；附件；idempotency_key。 输出：发送记录 + 平台回执 + 失败原因。 关系：上游 = 15.1.1.1、15.4.2 知识库；下游 = 平台消息接口。 <b>发送失败要保留原消息状态</b>，不能让客服以为已回复。
 *
 * </blockquote>
 *
 * <p><b>🔴 加粗句「发送失败要保留原消息状态」在本服务落成「推导」而不是「写回」</b>：
 *
 * <ul>
 *   <li>一条消息「是否已回复」 ⇔ 存在一条 {@code status='SENT'} 的发送记录指向它
 *       （见 {@link #targetState}）。</li>
 *   <li>本服务<b>从不 UPDATE {@code crm_message}</b> —— 那张表的写入所有者是 15.1.1.1，
 *       本模块对它只读。</li>
 *   <li>于是「发送失败」这条路径上，源消息的已回复状态<b>根本没有能力改变</b>：
 *       没有那一列可以被改，也就没有「忘了改回来」这种事故 ——
 *       而「忘了不改」正是客服看到「已回复」而实际没发出去的<b>唯一</b>成因。</li>
 * </ul>
 *
 * <p><b>平台投递未接入</b>：{@link PlatformMessageSendPort} 的当前实现逐平台答「未接入 + 归模块 2」，
 * 服务层据此<b>拒绝</b> {@code transport='PLATFORM_API'} 并返回 409，一次数据都不写。
 * 一个返回成功回执的假通道会让「消息已经发给买家了」在数据上看起来发生过，
 * 而现场一条真消息都没发出去。
 *
 * <p><b>跨服务纪律</b>：订单上下文在 <b>trade</b> 服务，本服务一条 SQL 都不碰对方的库，
 * 只经 {@link MessageContextPort} 走授权 HTTP API 读，且转发的是<b>调用方自己的</b>
 * {@code Authorization} bearer（<b>不传租户号</b>）；取不到就明确失败，
 * 绝不返回空集合冒充「上游没有数据」。
 *
 * <p><b>下游未交付</b>：15.1.1.3 消息分类、15.5.1 自动回复、11 售后、15.4 工单、
 * 15.1.2.1 邮件收发在本车道基线里都<b>没有</b>可调的授权 API ⇒ 本模块
 * <b>不做</b>自动分类、<b>不提供</b>自动回复、不写任何下游单据、
 * 也<b>不假装</b>邮件已与站内信同步。
 */
@Service
public class MessageSendService {

  // ================================================================ 常量

  /** 触达通道。PLATFORM_API 词表先钉死（平台适配器未交付，将来接入时不用改语义）。 */
  static final List<String> TRANSPORTS = List.of("PLATFORM_API", "MANUAL_HANDOFF");

  /** 发送记录状态。 */
  static final List<String> SEND_STATUSES = List.of("QUEUED", "SENT", "FAILED", "CANCELLED");

  /** 登记时可写的状态（登记入口不接受 CANCELLED —— 那是作废动作，不是登记结果）。 */
  static final List<String> REGISTRABLE_STATUSES = List.of("QUEUED", "SENT", "FAILED");

  /** 正文来源。 */
  static final List<String> BODY_SOURCES =
      List.of("MANUAL", "TEMPLATE", "TEMPLATE_EDITED", "KNOWLEDGE", "KNOWLEDGE_EDITED");

  /** 模板状态。 */
  static final List<String> TEMPLATE_STATUSES = List.of("ACTIVE", "DISABLED");

  /** 列表分页上限（真分页的上限，防止一次拉爆）。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 模板变量的<b>封闭词表</b>。
   *
   * <p>刻意只留这 11 个：模板里的占位符必须是<b>服务端能确定填上</b>的，
   * 否则就会在正文里留下 {@code {{xxx}}} 这种没人看得懂的字样 ——
   * 而那封信是要发给买家的。
   */
  static final List<String> TEMPLATE_VARIABLES =
      List.of(
          "buyer_reference",
          "order_reference",
          "shop_name",
          "platform_code",
          "message_subject",
          "buyer_name",
          "order_no",
          "platform_order_no",
          "sku_list",
          "currency",
          "order_amount");

  /** 依赖<b>订单上下文</b>的变量：填不上就必须让发送失败，而不是悄悄留空。 */
  static final Set<String> ORDER_CONTEXT_VARIABLES =
      Set.of("buyer_name", "order_no", "platform_order_no", "sku_list", "currency", "order_amount");

  /** 占位符形状（只认小写下划线；其余形状留给「没闭合」的检查去报错）。 */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z0-9_]+)}}");

  private final DSLContext sql;
  private final MessageContextPort context;
  private final PlatformMessageSendPort platformSend;
  private final KnowledgeBaseService knowledge;
  private final TransactionTemplate nested;

  public MessageSendService(
      DSLContext sql,
      MessageContextPort context,
      PlatformMessageSendPort platformSend,
      KnowledgeBaseService knowledge,
      PlatformTransactionManager manager) {
    this.sql = sql;
    this.context = context;
    this.platformSend = platformSend;
    this.knowledge = knowledge;
    // 批量相同回复：每一条**独立成一个保存点**，一条写失败不会连累后面的条目。
    // 用 NESTED（保存点）而不是 REQUIRES_NEW（另开事务）：另开事务会丢掉
    // 事务局部的 app.current_tenant 绑定（RLS 会立刻看不见任何行），
    // 而且连接池只有 1 条连接时还会自锁。保存点复用同一条连接。
    var template = new TransactionTemplate(manager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    this.nested = template;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 {@code platform_message_api} 维度刻意是 {@code available=false}：
   * 平台侧买家站内信<b>发送</b>适配器在本仓不存在（channel/vendor 下只有 13 个 OAuth 适配器）。
   * 本模块<b>刻意不放假 vendor 实现</b>，投递入口明确 409 且一次数据都不写。
   */
  public CrmMessageSendCapabilities capabilities() {
    List<CrmMessageSendCapabilityDimension> dimensions =
        List.of(
            new CrmMessageSendCapabilityDimension(
                "reply_templates",
                "调用模板（站内信回复模板）",
                true,
                "本服务 V297 crm_message_send_template + /crm/message-sends/templates",
                "",
                "模板按店铺 / 语言 / 状态筛选；历史发送记录<b>快照</b>当时的 template_code 与正文，"
                    + "模板改掉之后仍可查「当时用的哪套话术」"),
            new CrmMessageSendCapabilityDimension(
                "knowledge_reply_source",
                "调用知识库话术（15.4.2 上游）",
                true,
                "上游 15.4.2 知识库（同库同服务）：crm_knowledge_entry / crm_knowledge_version",
                "15.4.2.1",
                "只列<b>此刻生效</b>的版本：草稿版本没有生效过，"
                    + "拿它答复买家等于引用一段从未给过买家的内容"),
            new CrmMessageSendCapabilityDimension(
                "manual_edit",
                "手工编辑（模板起稿后改，或纯手写）",
                true,
                "本服务 V297 crm_message_send.body_source（MANUAL / TEMPLATE_EDITED / KNOWLEDGE_EDITED）",
                "",
                "改了哪一版<b>留痕</b>：body_source 回答「这句到底是模板原话还是人改过的」"),
            new CrmMessageSendCapabilityDimension(
                "attachments",
                "附件支持",
                true,
                "本服务 V297 crm_message_send_attachment（文件名 / 链接 / MIME / 字节数 numeric(19,6)）",
                "",
                "附件字节归平台侧与 8 仓储，本服务只记元数据；附件计数由 DB 触发器维护"),
            new CrmMessageSendCapabilityDimension(
                "send_records",
                "发送记录 + 平台回执 + 失败原因",
                true,
                "本服务 V297 crm_message_send + GET /crm/message-sends/sends",
                "",
                "每次发送都留一条记录；FAILED 必须带失败原因 —— "
                    + "只写「失败」而不写为什么，事后无法判断该不该重试"),
            new CrmMessageSendCapabilityDimension(
                "failure_retry",
                "失败重试",
                true,
                "本服务 V297 crm_message_send_attempt + POST /crm/message-sends/sends/{id}/retry",
                "",
                "每一次尝试一行（含失败原因与平台回执）：「第 1 次为什么失败、第 2 次为什么失败」有落点"),
            new CrmMessageSendCapabilityDimension(
                "batch_identical_reply",
                "批量相同回复（同类问题）",
                true,
                "本服务 V297 crm_message_send.batch_id + POST /crm/message-sends/batch",
                "",
                "同一正文发给多条消息；<b>逐条</b>给出成功与否与原因，失败的条目不会被悄悄跳过"),
            new CrmMessageSendCapabilityDimension(
                "idempotency",
                "幂等键（idempotency_key）",
                true,
                "本服务 V297 UNIQUE (tenant_id, idempotency_key)",
                "",
                "重放同一条键<b>不会</b>产生第二条发送记录 —— "
                    + "「重复回复买家」的代价是平台违规与买家投诉"),
            new CrmMessageSendCapabilityDimension(
                "platform_message_api",
                "平台侧站内信发送接口",
                false,
                "channel 服务 vendor 包下当前只有 13 个 OAuth 适配器，<b>没有</b>买家站内信适配器",
                "2",
                "投递入口<b>拒绝</b> transport=PLATFORM_API 并返回 409，且<b>一次数据都不写</b>；"
                    + "一个返回成功回执的假通道会让「消息已经发给买家了」看起来发生过，"
                    + "而现场一条真消息都没发出去"),
            new CrmMessageSendCapabilityDimension(
                "target_message_state",
                "🔴 发送失败保留原消息状态",
                true,
                "「已回复」由「存在一条 SENT 的发送记录」推导；本模块<b>从不 UPDATE crm_message</b>",
                "",
                "发送失败时源消息的已回复状态<b>根本没有能力改变</b> —— "
                    + "没有那一列可以被改，也就没有「忘了改回来」这种事故"),
            new CrmMessageSendCapabilityDimension(
                "message_classification",
                "消息自动分类（15.1.1.3）",
                false,
                "15.1.1.3 消息分类未交付",
                "15.1.1.3",
                "本模块的发送<b>全部由人工发起</b>，不做自动分类驱动的自动回复"),
            new CrmMessageSendCapabilityDimension(
                "auto_reply",
                "自动回复（15.5.1）",
                false,
                "15.5.1 自动回复未交付",
                "15.5.1",
                "本模块<b>不提供</b>任何自动触发；批量相同回复也是<b>人工发起</b>的一次批量动作"),
            new CrmMessageSendCapabilityDimension(
                "email_same_thread",
                "与买家邮件同会话处理（15.1.2.1）",
                false,
                "15.1.2.1 邮件收发已交付，但<b>刻意不共用表</b>（邮件有它自己的发送记录）",
                "15.1.2.1",
                "部分平台要求邮件与站内信在同一会话处理；本模块<b>不假装</b>两边已同步，"
                    + "也不把邮件的发送记录拿来当站内信的发送记录"),
            new CrmMessageSendCapabilityDimension(
                "aftersale_handoff",
                "转售后（11 售后）",
                false,
                "11 售后的授权 API 在本车道基线里<b>不可调用</b>",
                "11",
                "本模块<b>不写</b>任何售后单、<b>不编</b>售后单号"),
            new CrmMessageSendCapabilityDimension(
                "ticket_handoff",
                "建工单（15.4 客服工单）",
                false,
                "15.4 客服工单的授权 API 在本车道基线里<b>不可调用</b>",
                "15.4",
                "同上：不写工单、不编工单号"));
    return new CrmMessageSendCapabilities(
        dimensions,
        TRANSPORTS,
        SEND_STATUSES,
        BODY_SOURCES,
        TEMPLATE_VARIABLES,
        "上游 15.1.1.1 消息收件箱（目标消息，只读）与 15.4.2 知识库（话术来源）都在<b>本服务</b>；"
            + "关联订单上下文在 trade 服务，只经授权 HTTP API 读。"
            + "下游平台消息接口<b>未接入</b>（归模块 2），投递只允许<b>人工交接</b>；"
            + "15.1.1.3 分类 / 15.5.1 自动回复 / 11 售后 / 15.4 工单<b>均未交付</b>，本模块不碰。",
        "🔴 发送失败要保留原消息状态：「已回复」由「存在一条 SENT 的发送记录」推导，"
            + "本模块<b>从不写源消息</b> —— 失败时它<b>根本没有能力</b>变成「已回复」，"
            + "所以客服不会看到「已回复」而实际没发出去。");
  }

  // ================================================================ 回复模板

  /** 模板列表：真分页（LIMIT/OFFSET + 独立 COUNT(*)）+ 真筛选。 */
  public CrmMessageSendTemplatePage templates(
      UUID shopId, String locale, String status, String keyword, int page, int size) {
    int effectiveSize = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    int effectivePage = Math.max(page, 0);
    var where = new StringBuilder(" where tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (shopId != null) {
      where.append(" and shop_id = ?");
      binds.add(shopId);
    }
    String localeValue = blankToNull(locale);
    if (localeValue != null) {
      where.append(" and locale = ?");
      binds.add(localeValue);
    }
    String statusValue = blankToNull(status);
    if (statusValue != null) {
      where.append(" and status = ?");
      binds.add(statusValue);
    }
    String keywordValue = blankToNull(keyword);
    if (keywordValue != null) {
      String pattern = likePattern(keywordValue);
      where.append(
          " and (template_code ilike ? escape '\\' or name ilike ? escape '\\'"
              + " or subject ilike ? escape '\\' or body ilike ? escape '\\')");
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    var rows =
        sql.fetch(
            "select id,template_code,name,locale,subject,body,shop_id,platform_code,status,note,"
                + " created_at,updated_at from crm_message_send_template"
                + where
                + " order by template_code"
                + " limit ? offset ?",
            bindings(binds, effectiveSize, effectivePage * effectiveSize));
    long total =
        sql.fetchOne("select count(*) from crm_message_send_template" + where, binds.toArray())
            .get(0, Long.class);
    return new CrmMessageSendTemplatePage(
        rows.map(MessageSendService::templateRow), effectivePage, effectiveSize, total);
  }

  /** 单条模板详情。 */
  public CrmMessageSendTemplateRow template(UUID templateId) {
    return requireTemplate(templateId);
  }

  /** 新建模板。 */
  @Transactional
  public CrmMessageSendTemplateRow createTemplate(
      CrmMessageSendTemplateInput input, UUID actorId) {
    String code = nz(input.templateCode()).trim();
    if (code.isEmpty()) {
      throw bad("模板编码不能为空：它要能被运营按编码引用。");
    }
    if (nz(input.name()).trim().isEmpty()) {
      throw bad("模板名不能为空：模板列表上只有编码是看不出这是什么话术的。");
    }
    var existing =
        sql.fetchOne(
            "select id from crm_message_send_template where tenant_id=? and template_code=?",
            currentTenant(),
            code);
    if (existing != null) {
      throw bad("模板编码「" + code + "」已被占用：同一个租户内模板编码必须唯一，否则引用会指错。");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    String status =
        input.status() == null || input.status().isBlank()
            ? "ACTIVE"
            : oneOf(TEMPLATE_STATUSES, input.status(), "模板状态");
    sql.execute(
        "insert into crm_message_send_template"
            + "(id,tenant_id,template_code,name,locale,subject,body,shop_id,platform_code,status,"
            + " created_by,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        code,
        nz(input.name()).trim(),
        nz(input.locale()).trim(),
        nz(input.subject()),
        nz(input.body()),
        input.shopId(),
        blankToNull(input.platformCode()),
        status,
        actorId,
        nz(input.note()),
        moment,
        moment);
    return requireTemplate(id);
  }

  /**
   * 改模板。
   *
   * <p>⚠️ 改模板<b>不改历史</b>：历史发送记录已经快照了当时的 template_code 与正文，
   * 「当时用的哪套话术」仍可读 —— 模板改了不等于历史回复变了。
   */
  @Transactional
  public CrmMessageSendTemplateRow updateTemplate(
      UUID templateId, CrmMessageSendTemplateInput input, UUID actorId) {
    CrmMessageSendTemplateRow existing = requireTemplate(templateId);
    String code = nz(input.templateCode()).trim();
    if (code.isEmpty()) {
      throw bad("模板编码不能为空：它要能被运营按编码引用。");
    }
    if (nz(input.name()).trim().isEmpty()) {
      throw bad("模板名不能为空：模板列表上只有编码是看不出这是什么话术的。");
    }
    var clash =
        sql.fetchOne(
            "select id from crm_message_send_template where tenant_id=? and template_code=? and id<>?",
            currentTenant(),
            code,
            templateId);
    if (clash != null) {
      throw bad("模板编码「" + code + "」已被占用：同一个租户内模板编码必须唯一，否则引用会指错。");
    }
    String status =
        input.status() == null || input.status().isBlank()
            ? existing.status()
            : oneOf(TEMPLATE_STATUSES, input.status(), "模板状态");
    sql.execute(
        "update crm_message_send_template set template_code=?,name=?,locale=?,subject=?,body=?,"
            + " shop_id=?,platform_code=?,status=?,note=?,updated_at=?::timestamptz"
            + " where tenant_id=? and id=?",
        code,
        nz(input.name()).trim(),
        nz(input.locale()).trim(),
        nz(input.subject()),
        nz(input.body()),
        input.shopId(),
        blankToNull(input.platformCode()),
        status,
        nz(input.note()),
        now(),
        currentTenant(),
        templateId);
    return requireTemplate(templateId);
  }

  /**
   * 停用并删除模板。
   *
   * <p>⚠️ 只在<b>还没有任何发送记录引用</b>它时才真删；否则改成停用 ——
   * 删掉之后历史发送记录上的 template_id 会指向一个不存在的模板，
   * 「当时用的哪套话术」就再也读不出来了。
   */
  @Transactional
  public void deleteTemplate(UUID templateId) {
    requireTemplate(templateId);
    var used =
        sql.fetchOne(
            "select count(*) from crm_message_send where tenant_id=? and template_id=?",
            currentTenant(),
            templateId);
    long usedCount = used == null ? 0L : used.get(0, Long.class);
    if (usedCount > 0) {
      sql.execute(
          "update crm_message_send_template set status='DISABLED', updated_at=?::timestamptz"
              + " where tenant_id=? and id=?",
          now(),
          currentTenant(),
          templateId);
      return;
    }
    sql.execute(
        "delete from crm_message_send_template where tenant_id=? and id=?",
        currentTenant(),
        templateId);
  }

  // ================================================================ 知识库话术来源

  /**
   * 知识库条目（15.4.2 上游）作为回复模板来源。
   *
   * <p>⚠️ 只列<b>此刻生效</b>的版本 —— 草稿版本没有生效过，
   * 拿它答复买家等于在引用一段从未给过买家的内容。
   */
  public List<CrmMessageSendKnowledgeTemplate> knowledgeTemplates(UUID shopId, String locale) {
    List<CrmMessageSendKnowledgeTemplate> out = new ArrayList<>();
    for (var entry : knowledge.effectiveEntries(shopId, null, blankToNull(locale), null)) {
      var version = entry.effectiveVersion();
      if (version == null) {
        continue;
      }
      out.add(
          new CrmMessageSendKnowledgeTemplate(
              entry.id(),
              version.id(),
              version.versionNo(),
              nz(entry.title()),
              nz(entry.topicKey()),
              nz(entry.locale()),
              entry.shopId(),
              blankToNull(entry.platformCode()),
              nz(version.question()),
              nz(version.answer()),
              version.effectiveFrom()));
    }
    return out;
  }

  // ================================================================ 可回复目标

  /**
   * 可回复的目标消息列表（来自 15.1.1.1 收件箱）。
   *
   * <p>🔴 「已回复」是<b>推导</b>出来的：存在一条 {@code status='SENT'} 的发送记录即为已回复。
   * 这里<b>不</b>把「已回复」写回源消息，只读。
   */
  public CrmMessageSendTargetPage targets(
      UUID shopId,
      String keyword,
      String buyerReference,
      String orderReference,
      Boolean onlyUnreplied,
      int page,
      int size) {
    int effectiveSize = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    int effectivePage = Math.max(page, 0);
    var where = new StringBuilder(" where m.tenant_id = ? and m.direction = 'INBOUND'");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (shopId != null) {
      where.append(" and m.shop_id = ?");
      binds.add(shopId);
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
    // ⚠️ 「只看未回复」的过滤条件引用了 `replied` 这个 lateral 别名 ——
    //    所以它必须**在拼查询串之前**追加（拼出来的字符串是当时的快照），
    //    而且**计数查询必须带上同一个 lateral join**，否则 PG 会报
    //    「missing FROM-clause entry for table replied」，整页 409。
    if (Boolean.TRUE.equals(onlyUnreplied)) {
      where.append(" and replied.id is null");
    }
    String columns =
        "select m.id,m.message_no,m.shop_id,m.shop_name,m.platform_code,m.buyer_reference,"
            + " m.order_reference,m.subject,m.platform_time,"
            + " replied.id as replied_send_id, replied.send_no as replied_send_no,"
            + " last_send.id as last_send_id, last_send.status as last_send_status,"
            + " last_send.failure_reason as last_failure_reason"
            + " from crm_message m"
            + " left join lateral ("
            + "   select s.id,s.send_no,s.status,s.failure_reason from crm_message_send s"
            + "    where s.tenant_id = m.tenant_id and s.target_message_id = m.id"
            + "    order by s.created_at desc, s.id limit 1) last_send on true"
            + " left join lateral ("
            + "   select s.id,s.send_no from crm_message_send s"
            + "    where s.tenant_id = m.tenant_id and s.target_message_id = m.id and s.status='SENT'"
            + "    order by s.created_at desc, s.id limit 1) replied on true"
            + where;
    var rows =
        sql.fetch(
            columns + " order by m.platform_time desc, m.id limit ? offset ?",
            bindings(binds, effectiveSize, effectivePage * effectiveSize));
    long total =
        sql.fetchOne(
                "select count(*)" + countJoin() + where,
                binds.toArray())
            .get(0, Long.class);
    List<CrmMessageSendTarget> items = new ArrayList<>();
    for (Record r : rows) {
      UUID repliedId = r.get("replied_send_id", UUID.class);
      UUID lastId = r.get("last_send_id", UUID.class);
      items.add(
          new CrmMessageSendTarget(
              r.get("id", UUID.class),
              nz(r.get("message_no", String.class)),
              r.get("shop_id", UUID.class),
              nz(r.get("shop_name", String.class)),
              nz(r.get("platform_code", String.class)),
              nz(r.get("buyer_reference", String.class)),
              nz(r.get("order_reference", String.class)),
              nz(r.get("subject", String.class)),
              str(r.get("platform_time", OffsetDateTime.class)),
              repliedId == null ? "UNREPLIED" : "REPLIED",
              repliedId,
              nz(r.get("replied_send_no", String.class)),
              lastId == null ? null : nz(r.get("last_send_status", String.class)),
              lastId == null ? null : nz(r.get("last_failure_reason", String.class))));
    }
    long unreplied = countUnreplied(where, binds);
    return new CrmMessageSendTargetPage(items, effectivePage, effectiveSize, total, unreplied);
  }

  /**
   * 计数查询用的 lateral join（与主查询**同一个** `replied` 别名）。
   *
   * <p>⚠️ 少这一段，「只看未回复」的过滤条件就会引用不到 `replied`，
   * PG 报 missing FROM-clause，整页 409 —— 而看上去只是一个筛选条件。
   */
  private static String countJoin() {
    return " from crm_message m"
        + " left join lateral ("
        + "   select s.id,s.send_no from crm_message_send s"
        + "    where s.tenant_id = m.tenant_id and s.target_message_id = m.id and s.status='SENT'"
        + "    order by s.created_at desc, s.id limit 1) replied on true";
  }

  /** 未回复的目标数（与分页无关；与计数查询同口径）。 */
  private long countUnreplied(StringBuilder where, List<Object> binds) {
    var unrepliedWhere = new StringBuilder(where.toString());
    unrepliedWhere.append(" and replied.id is null");
    return sql.fetchOne(
            "select count(*)" + countJoin() + unrepliedWhere, binds.toArray())
        .get(0, Long.class);
  }

  // ================================================================ 发送

  /**
   * 登记一次发送。
   *
   * <p>🔴 {@code idempotencyKey} 必填：重放同一条键<b>不会</b>产生第二条发送记录。
   *
   * <p>🔴 发送失败<b>不改源消息</b>：本方法<b>从不 UPDATE {@code crm_message}</b>。
   */
  @Transactional
  public CrmMessageSendOutcome send(CrmMessageSendInput input, UUID actorId, String bearer) {
    return sendInternal(input, actorId, bearer, null);
  }

  /**
   * 登记一次发送的内部实现。
   *
   * <p>{@code batchId} 在<b>插入时</b>就写上：事后补写会撞上
   * {@code guard_crm_message_send_identity}（身份列建后不可改）——
   * 而批次号是**这次批量动作的身份**的一部分，它本来就该在登记时就确定。
   */
  private CrmMessageSendOutcome sendInternal(
      CrmMessageSendInput input, UUID actorId, String bearer, UUID batchId) {
    String key = nz(input.idempotencyKey()).trim();
    if (key.isEmpty()) {
      throw bad("必须给出幂等键：重放同一次回复不该产生第二条发送记录。");
    }
    var replay =
        sql.fetchOne(
            "select id from crm_message_send where tenant_id=? and idempotency_key=?",
            currentTenant(),
            key);
    if (replay != null) {
      CrmMessageSendRow existing = requireSend(replay.get("id", UUID.class));
      return new CrmMessageSendOutcome(
          existing,
          true,
          targetState(existing.targetMessageId()),
          "幂等键「" + key + "」此前已登记过（发送记录 " + existing.sendNo() + "），"
              + "本次<b>没有</b>新建记录，也没有再发一次。");
    }
    Target target = requireTarget(input.targetMessageId());
    Content content =
        resolveContent(
            target,
            input.templateId(),
            input.knowledgeVersionId(),
            input.subject(),
            input.body(),
            bearer);
    String transport = requireTransport(input.transport());
    String status =
        input.status() == null || input.status().isBlank()
            ? "QUEUED"
            : oneOf(REGISTRABLE_STATUSES, input.status(), "发送结果");
    String failureReason = nz(input.failureReason()).trim();
    if ("FAILED".equals(status) && failureReason.isEmpty()) {
      throw bad("登记失败必须写明失败原因：只写「失败」而不写为什么，事后无法判断该不该重试。");
    }
    OffsetDateTime moment = now();
    UUID id = UUID.randomUUID();
    String sendNo = nextSendNo();

    sql.execute(
        "insert into crm_message_send"
            + "(id,tenant_id,send_no,target_message_id,shop_id,shop_name,platform_code,"
            + " buyer_reference,order_reference,template_id,template_code,knowledge_entry_id,"
            + " knowledge_version_id,knowledge_version_no,subject,body,body_source,transport,status,"
            + " failure_reason,platform_receipt,platform_message_id,attachment_count,attempt_count,"
            + " last_attempt_at,sent_at,idempotency_key,batch_id,created_by,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,1,?::timestamptz,?::timestamptz,?,"
            + " ?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        sendNo,
        target.messageId(),
        target.shopId(),
        target.shopName(),
        target.platformCode(),
        target.buyerReference(),
        target.orderReference(),
        content.templateId(),
        content.templateCode(),
        content.knowledgeEntryId(),
        content.knowledgeVersionId(),
        content.knowledgeVersionNo(),
        content.subject(),
        content.body(),
        content.bodySource(),
        transport,
        status,
        failureReason,
        nz(input.platformReceipt()),
        nz(input.platformMessageId()).trim(),
        moment,
        "SENT".equals(status) ? moment : null,
        key,
        batchId,
        actorId,
        nz(input.note()),
        moment,
        moment);

    if (input.attachments() != null) {
      for (CrmMessageSendAttachmentInput attachment : input.attachments()) {
        sql.execute(
            "insert into crm_message_send_attachment"
                + "(id,tenant_id,send_id,file_name,file_url,content_type,size_bytes,created_at)"
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

    sql.execute(
        "insert into crm_message_send_attempt"
            + "(id,tenant_id,send_id,attempt_no,status,failure_reason,platform_receipt,"
            + " platform_message_id,attempted_at,operator_note)"
            + " values (?,?,?,1,?,?,?,?,?::timestamptz,?)",
        UUID.randomUUID(),
        currentTenant(),
        id,
        status,
        failureReason,
        nz(input.platformReceipt()),
        nz(input.platformMessageId()).trim(),
        moment,
        nz(input.note()));

    String referenceWarning = "";
    if ("SENT".equals(status)) {
      referenceWarning = recordKnowledgeReference(content, sendNo, actorId);
    }
    CrmMessageSendRow row = requireSend(id);
    return new CrmMessageSendOutcome(
        row, false, targetState(row.targetMessageId()), outcomeNote(row, "发送记录已登记（"
            + row.sendNo() + "）。") + referenceWarning);
  }

  /**
   * 批量相同回复（同类问题）：同一个正文发给多条消息。
   *
   * <p><b>逐条给出结论</b>：失败的条目不会被悄悄跳过 —— 「批量点了 20 条，
   * 实际生效 17 条」这件事必须看得见。每一条独立成一个<b>保存点</b>，
   * 一条写失败不会连累后面的条目。
   */
  @Transactional
  public CrmMessageSendBatchResult batch(
      CrmMessageSendBatchInput input, UUID actorId, String bearer) {
    String baseKey = nz(input.idempotencyKey()).trim();
    if (baseKey.isEmpty()) {
      throw bad("批量发送必须给出幂等键：重放同一批不该再发一次。");
    }
    UUID batchId = UUID.randomUUID();
    List<CrmMessageSendBatchItem> items = new ArrayList<>();
    int succeeded = 0;
    for (UUID messageId : input.messageIds()) {
      String messageNo = "";
      try {
        var row =
            sql.fetchOne(
                "select message_no from crm_message where tenant_id=? and id=?",
                currentTenant(),
                messageId);
        if (row != null) {
          messageNo = nz(row.get("message_no", String.class));
        }
        CrmMessageSendInput single =
            new CrmMessageSendInput(
                messageId,
                input.templateId(),
                input.knowledgeVersionId(),
                input.subject(),
                input.body(),
                input.transport(),
                "QUEUED",
                null,
                null,
                null,
                input.attachments(),
                baseKey + "#" + messageId,
                input.note());
        CrmMessageSendOutcome outcome =
            nested.execute(status -> sendInternal(single, actorId, bearer, batchId));
        if (outcome == null) {
          items.add(
              new CrmMessageSendBatchItem(messageId, messageNo, false, null, null, "这一条没有产生发送记录"));
          continue;
        }
        items.add(
            new CrmMessageSendBatchItem(
                messageId,
                messageNo,
                true,
                outcome.send().id(),
                outcome.send().sendNo(),
                outcome.replayed()
                    ? "幂等键此前已登记过（" + outcome.send().sendNo() + "），本次未重复发送"
                    : "已登记发送记录 " + outcome.send().sendNo()));
        succeeded++;
      } catch (RuntimeException e) {
        items.add(
            new CrmMessageSendBatchItem(
                messageId, messageNo, false, null, null, failureText(e)));
      }
    }
    return new CrmMessageSendBatchResult(
        batchId,
        input.messageIds().size(),
        succeeded,
        input.messageIds().size() - succeeded,
        items,
        "🔴 平台侧发送接口<b>未接入</b>（归模块 2）：本批次登记的都是<b>人工交接</b>记录 —— "
            + "运营需要在平台侧实际发出后逐条回登记结果与平台回执；"
            + "在回登记之前，这些消息的「已回复」状态<b>保持未回复</b>。");
  }

  // ================================================================ 发送记录读取

  /** 发送记录列表：真分页 + 真筛选 + 与分页无关的全量汇总。 */
  public CrmMessageSendPage sends(
      UUID shopId,
      String platformCode,
      String status,
      UUID targetMessageId,
      UUID batchId,
      String keyword,
      int page,
      int size) {
    int effectiveSize = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    int effectivePage = Math.max(page, 0);
    var where = new StringBuilder(" where s.tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (shopId != null) {
      where.append(" and s.shop_id = ?");
      binds.add(shopId);
    }
    String platformValue = blankToNull(platformCode);
    if (platformValue != null) {
      where.append(" and s.platform_code = ?");
      binds.add(platformValue);
    }
    String statusValue = blankToNull(status);
    if (statusValue != null) {
      where.append(" and s.status = ?");
      binds.add(statusValue);
    }
    if (targetMessageId != null) {
      where.append(" and s.target_message_id = ?");
      binds.add(targetMessageId);
    }
    if (batchId != null) {
      where.append(" and s.batch_id = ?");
      binds.add(batchId);
    }
    String keywordValue = blankToNull(keyword);
    if (keywordValue != null) {
      String pattern = likePattern(keywordValue);
      where.append(
          " and (s.send_no ilike ? escape '\\' or s.subject ilike ? escape '\\'"
              + " or s.body ilike ? escape '\\' or s.buyer_reference ilike ? escape '\\'"
              + " or s.order_reference ilike ? escape '\\' or s.template_code ilike ? escape '\\')");
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    var rows =
        sql.fetch(
            sendColumns() + " from crm_message_send s" + where
                + " order by s.created_at desc, s.id limit ? offset ?",
            bindings(binds, effectiveSize, effectivePage * effectiveSize));
    long total =
        sql.fetchOne("select count(*) from crm_message_send s" + where, binds.toArray())
            .get(0, Long.class);
    return new CrmMessageSendPage(
        rows.map(MessageSendService::sendRow),
        effectivePage,
        effectiveSize,
        total,
        summary(where, binds));
  }

  /** 发送记录详情 = 发送本体 + 附件 + 尝试留痕 + 目标消息状态。 */
  public CrmMessageSendDetail detail(UUID sendId) {
    CrmMessageSendRow row = requireSend(sendId);
    return new CrmMessageSendDetail(
        row, attachments(sendId), attempts(sendId), targetState(row.targetMessageId()));
  }

  // ================================================================ 回登记与重试

  /**
   * 登记一次投递的结果（人工交接：运营在平台侧实际发出或失败之后回登记）。
   *
   * <p>🔴 登记 SENT <b>不改源消息</b> —— 已回复状态由「存在一条 SENT 的发送记录」推导，
   * 本模块<b>从不 UPDATE {@code crm_message}</b>。
   */
  @Transactional
  public CrmMessageSendOutcome registerResult(
      UUID sendId, CrmMessageSendResultInput input, UUID actorId) {
    CrmMessageSendRow existing = requireSend(sendId);
    if ("SENT".equals(existing.status())) {
      throw bad("发送记录 " + existing.sendNo() + " 已经登记为已发出，不能再改结果。");
    }
    if ("CANCELLED".equals(existing.status())) {
      throw bad("发送记录 " + existing.sendNo() + " 已作废，不能再登记结果。");
    }
    if ("FAILED".equals(existing.status())) {
      throw bad(
          "发送记录 "
              + existing.sendNo()
              + " 当前是失败状态：请先重试再登记结果，"
              + "这样每一次尝试的失败原因才不会互相覆盖。");
    }
    String status = oneOf(List.of("SENT", "FAILED"), input.status(), "发送结果");
    String failureReason = nz(input.failureReason()).trim();
    if ("FAILED".equals(status) && failureReason.isEmpty()) {
      throw bad("登记失败必须写明失败原因：只写「失败」而不写为什么，事后无法判断该不该重试。");
    }
    OffsetDateTime moment = now();
    sql.execute(
        "update crm_message_send_attempt set status=?,failure_reason=?,platform_receipt=?,"
            + " platform_message_id=?,attempted_at=?::timestamptz,operator_note=?"
            + " where tenant_id=? and send_id=? and attempt_no=?",
        status,
        failureReason,
        nz(input.platformReceipt()),
        nz(input.platformMessageId()).trim(),
        moment,
        nz(input.operatorNote()),
        currentTenant(),
        sendId,
        existing.attemptCount());
    sql.execute(
        "update crm_message_send set status=?,failure_reason=?,platform_receipt=?,platform_message_id=?,"
            + " sent_at=?::timestamptz,last_attempt_at=?::timestamptz,updated_at=?::timestamptz"
            + " where tenant_id=? and id=?",
        status,
        failureReason,
        nz(input.platformReceipt()),
        nz(input.platformMessageId()).trim(),
        "SENT".equals(status) ? moment : null,
        moment,
        moment,
        currentTenant(),
        sendId);
    CrmMessageSendRow row = requireSend(sendId);
    String referenceWarning = "";
    if ("SENT".equals(status)) {
      referenceWarning =
          recordKnowledgeReference(
              new Content(
                  row.templateId(),
                  row.templateCode(),
                  row.knowledgeEntryId(),
                  row.knowledgeVersionId(),
                  row.knowledgeVersionNo(),
                  row.subject(),
                  row.body(),
                  row.bodySource()),
              row.sendNo(),
              actorId);
    }
    return new CrmMessageSendOutcome(
        row,
        false,
        targetState(row.targetMessageId()),
        outcomeNote(row, "已登记投递结果（" + row.sendNo() + "）。") + referenceWarning);
  }

  /**
   * 失败重试：开一次新的尝试。
   *
   * <p>⚠️ 重试<b>不改正文</b>：改正文就是另一次回复，不是重试。
   * 新的一次尝试单独一行，历史那次为什么失败仍可查。
   */
  @Transactional
  public CrmMessageSendOutcome retry(UUID sendId, CrmMessageSendRetryInput input, UUID actorId) {
    CrmMessageSendRow existing = requireSend(sendId);
    if ("SENT".equals(existing.status())) {
      throw bad("发送记录 " + existing.sendNo() + " 已经发出去了，不需要重试。");
    }
    if ("CANCELLED".equals(existing.status())) {
      throw bad("发送记录 " + existing.sendNo() + " 已作废，不能重试。");
    }
    if (!"FAILED".equals(existing.status())) {
      throw bad(
          "只有失败的发送记录才能重试；"
              + existing.sendNo()
              + " 当前是 "
              + existing.status()
              + "（待发的直接登记结果即可）。");
    }
    int attemptNo = existing.attemptCount() + 1;
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_message_send_attempt"
            + "(id,tenant_id,send_id,attempt_no,status,failure_reason,platform_receipt,"
            + " platform_message_id,attempted_at,operator_note)"
            + " values (?,?,?,?,'QUEUED','','','',?::timestamptz,?)",
        UUID.randomUUID(),
        currentTenant(),
        sendId,
        attemptNo,
        moment,
        nz(input == null ? null : input.operatorNote()));
    sql.execute(
        "update crm_message_send set status='QUEUED',attempt_count=?,last_attempt_at=?::timestamptz,"
            + " updated_at=?::timestamptz where tenant_id=? and id=?",
        attemptNo,
        moment,
        moment,
        currentTenant(),
        sendId);
    CrmMessageSendRow row = requireSend(sendId);
    return new CrmMessageSendOutcome(
        row,
        false,
        targetState(row.targetMessageId()),
        "已开启第 " + attemptNo + " 次尝试（发送记录 " + row.sendNo() + "）。");
  }

  // ================================================================ 内部：内容解析

  /** 目标消息（来自 15.1.1.1 的 crm_message，只读）。 */
  private record Target(
      UUID messageId,
      String messageNo,
      UUID shopId,
      String shopName,
      String platformCode,
      String buyerReference,
      String orderReference,
      String subject) {}

  /** 解析出来的回复内容。 */
  private record Content(
      UUID templateId,
      String templateCode,
      UUID knowledgeEntryId,
      UUID knowledgeVersionId,
      int knowledgeVersionNo,
      String subject,
      String body,
      String bodySource) {}

  /** 目标消息（不存在 → 404；不是收到的 → 400）。 */
  private Target requireTarget(UUID messageId) {
    var row =
        sql.fetchOne(
            "select id,message_no,shop_id,shop_name,platform_code,buyer_reference,order_reference,"
                + " subject,direction from crm_message where tenant_id=? and id=?",
            currentTenant(),
            messageId);
    if (row == null) {
      throw notFound("目标消息不存在，或不属于当前租户");
    }
    String direction = nz(row.get("direction", String.class));
    if (!"INBOUND".equals(direction)) {
      throw bad("只能回复**收到的**消息：这条是发出去的方向（" + direction + "），回复它没有意义。");
    }
    return new Target(
        row.get("id", UUID.class),
        nz(row.get("message_no", String.class)),
        row.get("shop_id", UUID.class),
        nz(row.get("shop_name", String.class)),
        nz(row.get("platform_code", String.class)),
        nz(row.get("buyer_reference", String.class)),
        nz(row.get("order_reference", String.class)),
        nz(row.get("subject", String.class)));
  }

  /**
   * 解析回复内容：模板 / 知识库 / 手工，三选一（模板与知识库<b>不同时</b>用）。
   *
   * <p>规则唯一，前端不自己解释：
   *
   * <ul>
   *   <li>给了模板 → 取模板；请求里又给了非空正文 ⇒ 以请求为准（= 模板起稿 + 手工编辑）；
   *   <li>给了知识版本 → 取知识版本正文；请求里又给了非空正文 ⇒ 以请求为准；
   *   <li>都没有 → 用请求里的正文；
   *   <li>三者都没有可用正文 ⇒ 拒绝，不留一条空回复的发送记录。</li>
   * </ul>
   *
   * <p>解析完<b>立刻渲染</b>模板变量：渲染不出来的占位符不能让发送继续 ——
   * 一封带着 {@code {{order_amount}}} 原样发给买家的信，比发不出去糟得多。
   */
  private Content resolveContent(
      Target target,
      UUID templateId,
      UUID knowledgeVersionId,
      String subject,
      String body,
      String bearer) {
    String manualSubject = nz(subject).trim();
    String manualBody = nz(body).trim();
    if (templateId != null && knowledgeVersionId != null) {
      throw bad("一次发送只能选一个话术来源：要么用回复模板，要么用知识库条目，不能两个都指定。");
    }
    if (templateId != null) {
      CrmMessageSendTemplateRow template = requireTemplate(templateId);
      if (!"ACTIVE".equals(template.status())) {
        throw bad("模板「" + template.templateCode() + "」已停用，不能用于新发送。");
      }
      boolean edited = !manualBody.isEmpty();
      String finalBody = edited ? manualBody : nz(template.body());
      String finalSubject = manualSubject.isEmpty() ? nz(template.subject()) : manualSubject;
      if (finalBody.trim().isEmpty()) {
        throw bad("模板「" + template.templateCode() + "」的正文是空的：请先补上正文或改用手工填写。");
      }
      return new Content(
          template.id(),
          template.templateCode(),
          null,
          null,
          0,
          render(finalSubject, target, bearer),
          render(finalBody, target, bearer),
          edited ? "TEMPLATE_EDITED" : "TEMPLATE");
    }
    if (knowledgeVersionId != null) {
      var version =
          sql.fetchOne(
              "select id,entry_id,version_no,question,answer,status,effective_from,effective_to"
                  + " from crm_knowledge_version where tenant_id=? and id=?",
              currentTenant(),
              knowledgeVersionId);
      if (version == null) {
        throw notFound("知识版本不存在，或不属于当前租户");
      }
      String status = nz(version.get("status", String.class));
      if (!"PUBLISHED".equals(status)) {
        throw bad(
            "知识版本 "
                + version.get("version_no", Integer.class)
                + " 还是草稿，草稿没有生效过，"
                + "引用它等于在引用一段从未给过买家的内容。请先发布这一版。");
      }
      OffsetDateTime effectiveFrom = version.get("effective_from", OffsetDateTime.class);
      if (effectiveFrom != null && effectiveFrom.isAfter(now())) {
        throw bad(
            "知识版本 "
                + version.get("version_no", Integer.class)
                + " 的生效起点是 "
                + effectiveFrom
                + "，还没生效，现在引用它会让「买家当时收到了哪一版」变成假记录。");
      }
      OffsetDateTime effectiveTo = version.get("effective_to", OffsetDateTime.class);
      if (effectiveTo != null && !effectiveTo.isAfter(now())) {
        throw bad(
            "知识版本 "
                + version.get("version_no", Integer.class)
                + " 已经于 "
                + effectiveTo
                + " 关闭，现在引用它会让「买家当时收到了哪一版」变成假记录。");
      }
      boolean edited = !manualBody.isEmpty();
      String finalBody = edited ? manualBody : nz(version.get("answer", String.class));
      String finalSubject =
          manualSubject.isEmpty() ? nz(version.get("question", String.class)) : manualSubject;
      if (finalBody.trim().isEmpty()) {
        throw bad("知识版本的答复是空的：请改用其它话术或手工填写。");
      }
      return new Content(
          null,
          "",
          version.get("entry_id", UUID.class),
          version.get("id", UUID.class),
          version.get("version_no", Integer.class),
          render(finalSubject, target, bearer),
          render(finalBody, target, bearer),
          edited ? "KNOWLEDGE_EDITED" : "KNOWLEDGE");
    }
    if (manualBody.isEmpty()) {
      throw bad("回复内容不能为空：请调用模板、引用知识库条目，或手工填写正文。");
    }
    return new Content(
        null,
        "",
        null,
        null,
        0,
        render(manualSubject, target, bearer),
        render(manualBody, target, bearer),
        "MANUAL");
  }

  /**
   * 渲染模板里的占位符。
   *
   * <p>🔴 <b>填不上的变量让发送失败</b>，而不是悄悄留空 ——
   * 一封把 {@code {{order_amount}}} 原样发给买家的信，比发不出去糟得多。
   *
   * <p>未定义的变量同样直接失败：正文里留着 {@code {{xxx}}} 没人看得懂，
   * 而那封信是要发给买家的。
   */
  private String render(String text, Target target, String bearer) {
    String source = nz(text);
    if (!source.contains("{{")) {
      return source;
    }
    Set<String> used = new LinkedHashSet<>();
    Matcher matcher = PLACEHOLDER.matcher(source);
    while (matcher.find()) {
      used.add(matcher.group(1));
    }
    List<String> unknown = new ArrayList<>();
    for (String name : used) {
      if (!TEMPLATE_VARIABLES.contains(name)) {
        unknown.add(name);
      }
    }
    if (!unknown.isEmpty()) {
      throw bad(
          "模板里有未定义的变量："
              + String.join(" / ", unknown)
              + "。可用变量只有："
              + String.join(" / ", TEMPLATE_VARIABLES)
              + "。正文里留着没定义的占位符发给买家，是没人看得懂的。");
    }
    Map<String, String> values = messageVariables(target);
    List<String> needOrder = new ArrayList<>();
    for (String name : used) {
      if (ORDER_CONTEXT_VARIABLES.contains(name)) {
        needOrder.add(name);
      }
    }
    String orderUnavailable = null;
    if (!needOrder.isEmpty()) {
      try {
        var lookup = context.order(target.orderReference(), bearer);
        if (lookup.found() && lookup.order() != null) {
          values.putAll(orderVariables(lookup.order()));
        } else {
          orderUnavailable = nz(lookup.reason());
          if (orderUnavailable.isEmpty()) {
            orderUnavailable =
                "上游 trade 订单服务里没有订单号 " + target.orderReference() + " 这一单";
          }
        }
      } catch (ResponseStatusException e) {
        // 上游读不到（不可达 / 无权限）⇒ 同样按「填不上」处理：
        // 变量填不上就必须让发送失败，而不是把 503 原样抛给运营，
        // 让ta以为是「系统坏了」而不是「这句模板里有填不上的东西」。
        orderUnavailable =
            e.getReason() == null ? "上游订单服务返回 " + e.getStatusCode().value() : e.getReason();
      }
    }
    if (orderUnavailable != null) {
      throw bad(
          "模板里用到了依赖订单上下文的变量（"
              + String.join(" / ", needOrder)
              + "），但订单上下文不可用："
              + orderUnavailable
              + "。填不上的变量必须让发送失败 —— 悄悄留空会把一封坏信发给买家。");
    }
    Matcher replace =
        PLACEHOLDER.matcher(source);
    String rendered =
        replace.replaceAll(m -> Matcher.quoteReplacement(values.getOrDefault(m.group(1), "")));
    if (rendered.contains("{{")) {
      throw bad("模板里有没写完的占位符：" + rendered + "。占位符必须写成 {{变量名}} 的完整形式。");
    }
    return rendered;
  }

  /** 来自目标消息本身的变量。 */
  private static Map<String, String> messageVariables(Target target) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("buyer_reference", nz(target.buyerReference()));
    values.put("order_reference", nz(target.orderReference()));
    values.put("shop_name", nz(target.shopName()));
    values.put("platform_code", nz(target.platformCode()));
    values.put("message_subject", nz(target.subject()));
    return values;
  }

  /** 来自 trade 订单上下文的变量（金额原样透传，禁浮点）。 */
  private static Map<String, String> orderVariables(MessageContextPort.OrderView order) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("buyer_name", nz(order.buyerName()));
    values.put("order_no", nz(order.orderNo()));
    values.put("platform_order_no", nz(order.externalOrderNo()));
    values.put("sku_list", order.skuCodes() == null ? "" : String.join("、", order.skuCodes()));
    values.put("currency", nz(order.currency()));
    values.put(
        "order_amount",
        order.grandTotalAmount() == null ? "" : order.grandTotalAmount().toPlainString());
    return values;
  }

  // ================================================================ 内部：状态推导

  /**
   * 目标消息的已回复状态（<b>推导</b>，不写回源消息）。
   *
   * <p>🔴 这是「发送失败要保留原消息状态」的落点：没有 SENT 的发送记录 ⇒ 未回复。
   * 发送失败这条路径上，本方法返回的 repliedState <b>不变</b>。
   */
  public CrmMessageTargetState targetState(UUID messageId) {
    var rows =
        sql.fetch(
            "select id,send_no,status,failure_reason,target_message_id,shop_id,shop_name,"
                + " platform_code,buyer_reference,order_reference,template_id,template_code,"
                + " knowledge_entry_id,knowledge_version_id,knowledge_version_no,subject,body,"
                + " body_source,transport,platform_receipt,platform_message_id,attachment_count,"
                + " attempt_count,last_attempt_at,sent_at,idempotency_key,batch_id,created_by,note,"
                + " created_at,updated_at"
                + " from crm_message_send"
                + " where tenant_id=? and target_message_id=? order by created_at desc, id",
            currentTenant(),
            messageId);
    CrmMessageSendRow replied = null;
    CrmMessageSendRow last = null;
    for (Record r : rows) {
      if (last == null) {
        last = sendRow(r);
      }
      if (replied == null && "SENT".equals(nz(r.get("status", String.class)))) {
        replied = sendRow(r);
      }
    }
    String messageNo = "";
    var message =
        sql.fetchOne(
            "select message_no from crm_message where tenant_id=? and id=?",
            currentTenant(),
            messageId);
    if (message != null) {
      messageNo = nz(message.get("message_no", String.class));
    }
    return new CrmMessageTargetState(
        messageId,
        messageNo,
        replied == null ? "UNREPLIED" : "REPLIED",
        replied == null ? null : replied.id(),
        replied == null ? null : replied.sendNo(),
        last == null ? null : last.status(),
        last == null ? null : nz(last.failureReason()),
        replied == null
            ? "这条消息还没有一条「已发出」的发送记录 ⇒ 仍是**未回复**。"
                + "（发送失败不会把它变成已回复 —— 源消息的状态压根不会被改。）"
            : "已由发送记录 " + replied.sendNo() + " 发出。");
  }

  // ================================================================ 内部：读

  /** 发送记录的列（列表与详情共用）。 */
  private static String sendColumns() {
    return "select s.id,s.send_no,s.target_message_id,s.shop_id,s.shop_name,s.platform_code,"
        + " s.buyer_reference,s.order_reference,s.template_id,s.template_code,s.knowledge_entry_id,"
        + " s.knowledge_version_id,s.knowledge_version_no,s.subject,s.body,s.body_source,s.transport,"
        + " s.status,s.failure_reason,s.platform_receipt,s.platform_message_id,s.attachment_count,"
        + " s.attempt_count,s.last_attempt_at,s.sent_at,s.idempotency_key,s.batch_id,s.created_by,"
        + " s.note,s.created_at,s.updated_at";
  }

  /** 记录 → 发送行。 */
  private static CrmMessageSendRow sendRow(Record r) {
    return new CrmMessageSendRow(
        r.get("id", UUID.class),
        nz(r.get("send_no", String.class)),
        r.get("target_message_id", UUID.class),
        r.get("shop_id", UUID.class),
        nz(r.get("shop_name", String.class)),
        nz(r.get("platform_code", String.class)),
        nz(r.get("buyer_reference", String.class)),
        nz(r.get("order_reference", String.class)),
        r.get("template_id", UUID.class),
        nz(r.get("template_code", String.class)),
        r.get("knowledge_entry_id", UUID.class),
        r.get("knowledge_version_id", UUID.class),
        r.get("knowledge_version_no", Integer.class) == null
            ? 0
            : r.get("knowledge_version_no", Integer.class),
        nz(r.get("subject", String.class)),
        nz(r.get("body", String.class)),
        nz(r.get("body_source", String.class)),
        nz(r.get("transport", String.class)),
        nz(r.get("status", String.class)),
        nz(r.get("failure_reason", String.class)),
        nz(r.get("platform_receipt", String.class)),
        nz(r.get("platform_message_id", String.class)),
        r.get("attachment_count", Integer.class) == null
            ? 0
            : r.get("attachment_count", Integer.class),
        r.get("attempt_count", Integer.class) == null ? 0 : r.get("attempt_count", Integer.class),
        str(r.get("last_attempt_at", OffsetDateTime.class)),
        str(r.get("sent_at", OffsetDateTime.class)),
        nz(r.get("idempotency_key", String.class)),
        r.get("batch_id", UUID.class),
        r.get("created_by", UUID.class),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  /** 单条发送记录（不存在 → 404）。 */
  private CrmMessageSendRow requireSend(UUID sendId) {
    var row =
        sql.fetchOne(sendColumns() + " from crm_message_send s where s.tenant_id=? and s.id=?",
            currentTenant(), sendId);
    if (row == null) {
      throw notFound("发送记录不存在，或不属于当前租户");
    }
    return sendRow(row);
  }

  /** 单条模板（不存在 → 404）。 */
  private CrmMessageSendTemplateRow requireTemplate(UUID templateId) {
    var row =
        sql.fetchOne(
            "select id,template_code,name,locale,subject,body,shop_id,platform_code,status,note,"
                + " created_at,updated_at from crm_message_send_template where tenant_id=? and id=?",
            currentTenant(),
            templateId);
    if (row == null) {
      throw notFound("回复模板不存在，或不属于当前租户");
    }
    return templateRow(row);
  }

  /** 记录 → 模板行。 */
  private static CrmMessageSendTemplateRow templateRow(Record r) {
    return new CrmMessageSendTemplateRow(
        r.get("id", UUID.class),
        nz(r.get("template_code", String.class)),
        nz(r.get("name", String.class)),
        nz(r.get("locale", String.class)),
        nz(r.get("subject", String.class)),
        nz(r.get("body", String.class)),
        r.get("shop_id", UUID.class),
        blankToNull(r.get("platform_code", String.class)),
        nz(r.get("status", String.class)),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  /** 附件列表。 */
  private List<CrmMessageSendAttachment> attachments(UUID sendId) {
    List<CrmMessageSendAttachment> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,send_id,file_name,file_url,content_type,size_bytes,created_at"
                + " from crm_message_send_attachment where tenant_id=? and send_id=?"
                + " order by created_at, id",
            currentTenant(),
            sendId)) {
      out.add(
          new CrmMessageSendAttachment(
              r.get("id", UUID.class),
              r.get("send_id", UUID.class),
              nz(r.get("file_name", String.class)),
              nz(r.get("file_url", String.class)),
              nz(r.get("content_type", String.class)),
              r.get("size_bytes", BigDecimal.class),
              str(r.get("created_at", OffsetDateTime.class))));
    }
    return out;
  }

  /** 尝试留痕。 */
  private List<CrmMessageSendAttempt> attempts(UUID sendId) {
    List<CrmMessageSendAttempt> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select id,send_id,attempt_no,status,failure_reason,platform_receipt,platform_message_id,"
                + " attempted_at,operator_note from crm_message_send_attempt"
                + " where tenant_id=? and send_id=? order by attempt_no",
            currentTenant(),
            sendId)) {
      out.add(
          new CrmMessageSendAttempt(
              r.get("id", UUID.class),
              r.get("send_id", UUID.class),
              r.get("attempt_no", Integer.class) == null ? 0 : r.get("attempt_no", Integer.class),
              nz(r.get("status", String.class)),
              nz(r.get("failure_reason", String.class)),
              nz(r.get("platform_receipt", String.class)),
              nz(r.get("platform_message_id", String.class)),
              str(r.get("attempted_at", OffsetDateTime.class)),
              nz(r.get("operator_note", String.class))));
    }
    return out;
  }

  /** 汇总：与分页无关（覆盖整个筛选结果集）。 */
  private CrmMessageSendSummary summary(StringBuilder where, List<Object> binds) {
    var row =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where s.status='SENT') as sent,"
                + " count(*) filter (where s.status='FAILED') as failed,"
                + " count(*) filter (where s.status='QUEUED') as queued,"
                + " count(*) filter (where s.status='CANCELLED') as cancelled"
                + " from crm_message_send s"
                + where,
            binds.toArray());
    long total = row.get("total", Long.class);
    long sent = row.get("sent", Long.class);
    long failed = row.get("failed", Long.class);
    long queued = row.get("queued", Long.class);
    long cancelled = row.get("cancelled", Long.class);
    return new CrmMessageSendSummary(
        total,
        sent,
        failed,
        queued,
        cancelled,
        buckets("s.status", where, binds, STATUS_LABELS),
        buckets("s.transport", where, binds, TRANSPORT_LABELS));
  }

  /** 按一列分组的统计（只列真实出现过的取值域）。 */
  private List<CrmMessageSendBucket> buckets(
      String column, StringBuilder where, List<Object> binds, Map<String, String> labels) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record record :
        sql.fetch(
            "select " + column + " as code, count(*) as cnt from crm_message_send s"
                + where
                + " group by "
                + column
                + " order by cnt desc, code",
            binds.toArray())) {
      counts.put(record.get("code", String.class), record.get("cnt", Long.class));
    }
    List<CrmMessageSendBucket> out = new ArrayList<>();
    counts.forEach(
        (code, count) ->
            out.add(new CrmMessageSendBucket(code, labels.getOrDefault(code, code), count)));
    return out;
  }

  // ================================================================ 内部：工具

  /** 触达通道：PLATFORM_API <b>未接入</b>，直接拒绝（一次数据都不写）。 */
  private String requireTransport(String transport) {
    String value = nz(transport).trim();
    if (!TRANSPORTS.contains(value)) {
      throw bad("触达通道「" + value + "」不在允许取值里：" + String.join(" / ", TRANSPORTS));
    }
    if ("PLATFORM_API".equals(value)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "平台侧站内信发送接口**未接入**（归模块 2）："
              + "channel 服务的 vendor 包下只有 OAuth 适配器，没有站内信发送适配器，"
              + "所以平台侧的消息发送接口还没有通道。"
              + "请改用 MANUAL_HANDOFF（人工交接）：在平台侧实际发出后回登记结果与平台回执。"
              + "本模块**一次数据都不会写** —— 一条返回成功回执的假通道会让"
              + "「消息已经发给买家了」看起来发生过，而现场一条真消息都没发出去。");
    }
    return value;
  }

  /**
   * 引用知识版本时向知识库登记一条引用（使「买家当时收到了哪一版」可查）。
   *
   * <p>⚠️ 引用登记失败<b>不</b>让发送记录回滚：消息已经发出去了，
   * 把这个事实丢掉比引用查不到更糟。所以失败<b>如实回报</b>在结果里。
   *
   * @return 空串表示成功；否则是失败说明
   */
  private String recordKnowledgeReference(Content content, String sendNo, UUID actorId) {
    if (content.knowledgeVersionId() == null) {
      return "";
    }
    try {
      knowledge.recordReference(
          content.knowledgeVersionId(),
          new KnowledgeBaseModels.CrmRecordReferenceInput(
              "MANUAL_REPLY", sendNo, "15.1.1.2 消息发送：" + content.bodySource()),
          actorId);
      return "";
    } catch (RuntimeException e) {
      return " ⚠️ 知识引用登记失败（" + failureText(e) + "）：发送记录已保存，"
          + "但「买家当时收到了哪一版」暂时查不到。";
    }
  }

  /** 发送结果的一句话说明。 */
  private String outcomeNote(CrmMessageSendRow row, String prefix) {
    String target =
        "REPLIED".equals(targetState(row.targetMessageId()).repliedState())
            ? "目标消息**已回复**。"
            : "目标消息仍是**未回复** —— 发送失败不会把它变成已回复（源消息的状态压根不会被改）。";
    return prefix + " " + target;
  }

  /** 把异常翻译成一句人能看懂的话。 */
  private static String failureText(RuntimeException e) {
    if (e instanceof ResponseStatusException status) {
      return status.getReason() == null
          ? "请求失败（" + status.getStatusCode().value() + "）"
          : status.getReason();
    }
    String message = e.getMessage();
    return message == null || message.isBlank()
        ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + "：" + message;
  }

  /** 发送号：MSGS-yyyyMMdd-000001（序列单独授权，见 V297 授权段）。 */
  private String nextSendNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_message_send_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "MSGS-" + day + "-" + String.format("%06d", seq);
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 关键字 → ILIKE 模式（转义 % 与 _，否则搜 "100%" 会命中一切）。 */
  private static String likePattern(String keyword) {
    String escaped = keyword.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  /** 绑定值 + 分页参数。 */
  private static Object[] bindings(List<Object> binds, Object... tail) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(tail));
    return all.toArray();
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

  /** 状态取值域的中文标签（服务端给，前端只原样展示）。 */
  private static final Map<String, String> STATUS_LABELS =
      Map.of("QUEUED", "待发", "SENT", "已发出", "FAILED", "失败", "CANCELLED", "已作废");

  /** 触达通道取值域的中文标签。 */
  private static final Map<String, String> TRANSPORT_LABELS =
      Map.of("MANUAL_HANDOFF", "人工交接", "PLATFORM_API", "平台接口（未接入）");
}
