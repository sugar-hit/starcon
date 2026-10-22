package com.starcon.crm;

import static com.starcon.crm.AutoReplyModels.*;

import com.starcon.crm.AutoReplyModels.CrmAutoReplyBucket;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyCapabilities;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyCapabilityDimension;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyDutyCalendarInput;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyDutyCalendarPage;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyDutyCalendarRow;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyEscalateInput;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyEscalationPage;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyEscalationRow;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyOutcome;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyRecordPage;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyRecordRow;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyRuleInput;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyRulePage;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyRuleRow;
import com.starcon.crm.AutoReplyModels.CrmAutoReplySettingRow;
import com.starcon.crm.AutoReplyModels.CrmAutoReplySettingToggleInput;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyStats;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyTakeoverInput;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyTakeoverPage;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyTakeoverRow;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyTriggerInput;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyTriggerResult;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyTriggerType;
import com.starcon.crm.AutoReplyModels.CrmAutoReplyTriggeredItem;
import com.starcon.crm.MessageSendModels.CrmMessageSendInput;
import com.starcon.crm.MessageSendModels.CrmMessageSendOutcome;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.5.1.1 自动回复。
 *
 * <p>蓝图 15 卷 15.5.1.1 原话：
 *
 * <blockquote>
 * 职责：配置消息自动回复：按触发条件（收到消息分类、关键词、无人工介入超时）自动发送回复，支持节假日无人值守、分级自动回复（先自动引导再转人工）。
 * 输入：自动回复规则；模板内容；触发条件。
 * 输出：自动回复记录 + 转人工标记。
 * 关系：上游 = 15.1.1.3、15.4.2；下游 = 15.1.1.2 发送。 <b>自动回复必须可一键关闭并留出人工接管</b>，很多平台对纯自动回复有明确限制。
 * </blockquote>
 *
 * <p><b>本类落七条纪律</b>：
 *
 * <ol>
 *   <li><b>🔴 一键关闭是即时生效</b>：{@link #toggleSetting} 在同一个事务里改开关**并**把
 *       已排队待发的记录逐条追加一条 {@code outcome='CANCELLED'} 的撤销行。
 *       改开关之后的新触发不再自动发；<b>不得</b>在关闭后继续发。
 *       撤销信号落在**本模块自己的表**上，<b>不 UPDATE {@code crm_message_send}</b>
 *       —— 那张表的写入所有者是 15.1.1.2。</li>
 *   <li><b>🔴 人工接管是硬闸</b>：接管以<b>会话</b>（shop_id + buyer_reference，
 *       本模块自己的定义 —— 上游没有会话实体）为粒度；接管后<b>同一会话不再自动回复</b>。</li>
 *   <li><b>🔴 值班日历是租户配置，不是常量</b>：{@link #dutyDecision} 按
 *       「范围从窄到宽 + 时段覆盖」取一条，命中不了才回落规则上的默认口径。</li>
 *   <li><b>分级自动回复的阶梯语义显式</b>：第 1 级命中即发；第 N 级（N&gt;1）必须
 *       「上一级已发出 + 距上一级至少过 {@code level_wait_minutes} + 期间无人工介入」。
 *       走完一级且该级 {@code escalate_to_human} ⇒ 追加一条转人工标记。</li>
 *   <li><b>🔴 不重造发送通道</b>：一律经 {@link MessageSendService#send} 登记
 *       （模板 / 知识库话术 / 幂等键全部走它）。平台侧发送接口未接入 ⇒ transport 恒为
 *       MANUAL_HANDOFF，<b>不假装已发出</b>。</li>
 *   <li><b>🔴 记录只追加</b>：自动回复记录、转人工标记、人工接管标记三张表都由 DB 触发器
 *       拒绝 UPDATE / DELETE —— <b>改规则不追溯改变历史记录</b>。</li>
 *   <li><b>不编造指向不存在入口的形状</b>：平台自动回复准入清单、平台站内信 webhook
 *       <b>未交付</b> ⇒ 能力声明里如实标 {@code available=false}
 *       + {@code ownerModule}，<b>不编</b>一份假的白名单；
 *       15.4.1.2 工单介入动作<b>已交付</b>（同服务同库），但<b>本模块当前仍未把它纳入豁免判定</b>
 *       ⇒ 该维仍然如实标 {@code available=false}。</li>
 * </ol>
 *
 * <p><b>人工介入的判定口径</b>（可审阅）：一条消息被认为「已被人工介入」⇔
 * 15.1.1.2 里存在一条 {@code status='SENT'} 的发送记录指向它，<b>且</b>该发送记录
 * <b>不是</b>本模块产生的（{@code crm_auto_reply_record.send_id} 里没有它）。
 * 15.4.1.2 工单处理<b>已交付</b>（同服务同库，{@code crm_service_ticket_action} 是工单侧
 * 介入动作的事实表），但<b>本模块当前仍未把工单侧介入动作纳入豁免判定</b> ⇒
 * 工单侧的介入动作本模块仍<b>读不到</b>（口径统一是另一条车道的活）。
 */
@Service
public class AutoReplyService {

  // ================================================================ 常量

  /** 列表分页上限（真分页的上限，防止一次拉爆）。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 自动回复记录的 INSERT 语句。
   *
   * <p>⚠️ 占位符用 {@link java.util.Collections#nCopies} 数出来，不用手数 ——
   * 手数过一版，多写了一个 {@code ?}，jOOQ 报的是「未设定参数值 33 的内容」，
   * 而列名与参数的对应关系完全看不出来（列多一个少一个都看不出）。
   */
  private static final String INSERT_RECORD =
      "insert into crm_auto_reply_record"
          + "(id,tenant_id,record_no,rule_id,rule_code,rule_name,ladder_code,level,trigger_type,"
          + " trigger_reason,message_id,message_no,shop_id,shop_name,platform_code,"
          + " buyer_reference,order_reference,classification_id,classification_label,"
          + " template_id,template_code,template_body_snapshot,knowledge_version_id,"
          + " knowledge_version_no,body_snapshot,outcome,reason,send_id,send_no,escalated,"
          + " occurred_at,created_at)"
          + " values ("
          + String.join(",", java.util.Collections.nCopies(30, "?"))
          + ",?::timestamptz,?::timestamptz)";

  /**
   * 撤销行的 INSERT 语句（{@code outcome} 与 {@code escalated} 是字面量，不是参数）。
   *
   * <p>列 33 个：{@code outcome='CANCELLED'} 与 {@code escalated=false} 两个字面量，
   * 其余 31 个是参数（其中两个是 timestamptz）。
   */
  private static final String INSERT_CANCELLED_RECORD =
      "insert into crm_auto_reply_record"
          + "(id,tenant_id,record_no,rule_id,rule_code,rule_name,ladder_code,level,trigger_type,"
          + " trigger_reason,message_id,message_no,shop_id,shop_name,platform_code,"
          + " buyer_reference,order_reference,classification_id,classification_label,"
          + " template_id,template_code,template_body_snapshot,knowledge_version_id,"
          + " knowledge_version_no,body_snapshot,outcome,reason,send_id,send_no,origin_record_id,"
          + " escalated,occurred_at,created_at)"
          + " values ("
          + String.join(",", java.util.Collections.nCopies(25, "?"))
          + ",'CANCELLED',?,?,?,?,false,?::timestamptz,?::timestamptz)";

  /** 15.1.1.3 的分类标签词表（与 V301 的 CHECK 同一份）。 */
  static final List<String> CLASSIFICATION_LABELS =
      List.of(
          "PRE_SALE",
          "LOGISTICS",
          "RETURN_REFUND",
          "COMPLAINT",
          "PLATFORM_COMPLIANCE",
          "SPAM");

  /** 值班日历的范围优先级：数字大 = 更窄 = 优先。 */
  private static final Map<String, Integer> SCOPE_PRIORITY =
      Map.of("GLOBAL", 1, "PLATFORM", 2, "RULE", 3);

  /** 触发类型的取值域（服务层与 DB 的 CHECK 同一份）。 */
  static final List<String> TRIGGER_TYPES =
      List.of("CLASSIFICATION", "KEYWORD", "HUMAN_TIMEOUT");

  private final DSLContext sql;
  private final MessageSendService sends;

  public AutoReplyService(DSLContext sql, MessageSendService sends) {
    this.sql = sql;
    this.sends = sends;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 {@code platform_auto_reply_policy} 维度刻意是 {@code available=false}：
   * 本仓**拿不到**任何平台对纯自动回复的限制（channel/vendor 下只有 OAuth 适配器，
   * 没有买家站内信适配器，也没有自动回复政策清单）⇒ 本模块<b>不编</b>一份平台白名单，
   * 编出来的白名单会让人以为「这个平台可以放心全自动」。
   */
  public CrmAutoReplyCapabilities capabilities() {
    List<CrmAutoReplyCapabilityDimension> dimensions =
        List.of(
            new CrmAutoReplyCapabilityDimension(
                "auto_reply_rule",
                "自动回复规则（触发条件三选一 / 阶梯位置 / 话术 / 平台 / 超时口径）",
                true,
                "本服务 V307 crm_auto_reply_rule",
                "",
                "触发条件**互斥**（分类 / 关键词 / 无人工介入超时）：同时写两种，命中口径就没法复现。"
                    + "阶梯显式落在 ladder_code + level + escalate_to_human 上"),
            new CrmAutoReplyCapabilityDimension(
                "auto_reply_record",
                "自动回复记录（触发时间 / 命中规则 / 模板版本快照 / 实际发送结果 / 未发送原因）",
                true,
                "本服务 V307 crm_auto_reply_record",
                "",
                "🔴 **只追加**：改规则不追溯改变历史记录。"
                    + "模板版本与正文都做了快照 —— 事后能复核「当时到底回了什么」"),
            new CrmAutoReplyCapabilityDimension(
                "escalation_marker",
                "转人工标记（阶梯走完 / 值班闸挡下 / 发送登记失败 / 人工手动标记）",
                true,
                "本服务 V307 crm_auto_reply_escalation",
                "",
                "🔴 只追加，且一条消息只转人工一次。"
                    + "「该找人了」这件事被改掉 = 待办凭空消失"),
            new CrmAutoReplyCapabilityDimension(
                "global_kill_switch",
                "🔴 一键关闭全局自动回复（关闭即生效，待发撤销）",
                true,
                "本服务 V307 crm_auto_reply_setting",
                "",
                "🔴 关闭是**即时生效**：改开关后新触发不再自动发；"
                    + "已排队待发的逐条追加一条撤销行（不得再发）"),
            new CrmAutoReplyCapabilityDimension(
                "human_takeover",
                "🔴 人工接管（接管后同一会话不再自动回复）",
                true,
                "本服务 V307 crm_auto_reply_takeover",
                "",
                "接管是**硬闸**，每次触发都回查；接管原因必填 —— "
                    + "没有理由的接管，事后没人知道客服为什么不再让系统回"),
            new CrmAutoReplyCapabilityDimension(
                "duty_calendar",
                "🔴 节假日 / 非营业时段值班日历（租户可配，不是写死常量）",
                true,
                "本服务 V307 crm_auto_reply_duty_calendar",
                "",
                "「无人值守要不要自动回复」= 日历上的 <b>auto_reply_allowed</b> 列，可配置。"
                    + "范围从窄到宽取一条（RULE > PLATFORM > GLOBAL）；命中不了回落规则的默认口径"),
            new CrmAutoReplyCapabilityDimension(
                "tiered_auto_reply",
                "分级自动回复（先自动引导 → 仍无人工介入 → 再发一级 → 转人工）",
                true,
                "本服务 V307 crm_auto_reply_rule(level / level_wait_minutes / escalate_to_human)",
                "",
                "第 N 级必须「上一级已发出 + 距上一级至少过 level_wait_minutes + 期间无人工介入」；"
                    + "间隔为 0 被 DB 挡住 —— 那会把整条阶梯一次走完"),
            new CrmAutoReplyCapabilityDimension(
                "downstream_message_send",
                "下游 15.1.1.2 消息发送（复用它的发送与模板，不重造通道）",
                true,
                "同服务 V297 crm_message_send（经 MessageSendService）",
                "15.1.1.2",
                "模板 / 知识库话术 / 幂等键全部走它；本模块**不写**它的表"),
            new CrmAutoReplyCapabilityDimension(
                "platform_delivery",
                "平台侧自动投递（真正把消息发到买家那里）",
                false,
                "channel/vendor 下只有 OAuth 适配器，没有买家站内信适配器",
                "2",
                "🔴 平台发送接口**未接入** ⇒ transport 恒为 MANUAL_HANDOFF，"
                    + "记录停在「已登记待发」，**不假装已发出**"),
            new CrmAutoReplyCapabilityDimension(
                "platform_auto_reply_policy",
                "平台对纯自动回复的限制（哪些平台允许全自动、频次上限）",
                false,
                "本仓**没有**任何平台的自动回复政策清单；不编假白名单",
                "2",
                "🔴 拿不到平台限制 ⇒ 平台维度只能由运营自己在规则上配。"
                    + "页面据此提示「先小范围试、随时能一键关」"),
            new CrmAutoReplyCapabilityDimension(
                "human_intervention_signal",
                "人工介入信号（客服回复 / 工单处理动作）",
                false,
                "15.4.1.2 工单处理**已交付**（同服务同库，crm_service_ticket_action 是工单侧"
                    + "介入动作的事实表）；但**本模块当前仍未把工单侧介入动作纳入豁免判定** ⇒ "
                    + "目前仍只有 15.1.1.2 的 SENT 发送记录可判定",
                "15.4.1.2",
                "「无人工介入超时」目前**只能**依据发送记录判定；"
                    + "工单侧的介入动作本模块仍读不到（口径统一是另一条车道的活），"
                    + "超时口径会偏长"),
            new CrmAutoReplyCapabilityDimension(
                "platform_message_webhook",
                "平台站内信 webhook（收到消息即自动评估）",
                false,
                "15.1.1.1 的 PLATFORM_SYNC **未接入**",
                "2",
                "触发入口由调用方**显式**调用；平台适配器接入后调**同一个**入口"),
            new CrmAutoReplyCapabilityDimension(
                "conversation_master_data",
                "会话主数据（买家会话线程）",
                false,
                "15.1.1.1 只到「消息」这一层，**没有**会话实体",
                "15.1.1.1",
                "「同一会话」= (shop_id, buyer_reference)，是**本模块自己的定义**；"
                    + "上游若补上会话实体，这一定义要跟着改"));
    return new CrmAutoReplyCapabilities(
        dimensions,
        TRIGGER_TYPES,
        List.of("SEND_REGISTERED", "SUPPRESSED", "CANCELLED"),
        List.of("UNATTENDED", "ATTENDED", "CLOSED"),
        List.of("GLOBAL", "PLATFORM", "RULE"),
        List.of("LADDER_EXHAUSTED", "DUTY_GATE", "SEND_FAILED", "MANUAL_ESCALATION"),
        CLASSIFICATION_LABELS,
        "🔴 一键关闭是**即时生效**的：改开关后新触发不再自动发；"
            + "已排队待发的逐条追加一条撤销行（不得再发）；关闭必须写明原因与操作人。",
        "🔴 本仓**拿不到**平台对纯自动回复的限制（channel/vendor 下没有买家站内信适配器，"
            + "也没有任何平台的自动回复政策清单）⇒ 本模块**不编**平台白名单。"
            + "运营请先在单个平台上小范围试，并保留一键关闭。",
        "「同一会话」= (shop_id, buyer_reference)：同一店铺里的同一位买家。"
            + "上游**没有**会话实体，这是本模块自己的定义。",
        "人工介入 = 15.1.1.2 里一条**不是本模块产生**的 SENT 发送记录；"
            + "15.4.1.2 工单处理**已交付**（同服务同库），但**本模块当前仍未把工单侧"
            + "介入动作纳入豁免判定** ⇒ 工单侧的介入动作仍读不到。",
        "本模块**不重造发送**：一律经 15.1.1.2 登记发送；"
            + "平台侧投递未接入 ⇒ transport 恒为 MANUAL_HANDOFF，记录停在「已登记待发」。",
        "上游 15.1.1.3 分类结论**只读**（不另造平行分类表）；"
            + "上游 15.4.2.1 知识库**只读**；下游 15.1.1.2 发送表**只调服务层、不写表**。",
        "🔴 自动回复**必须能一键关闭**，也**必须留出人工接管**；"
            + "很多平台对纯自动回复有明确限制 —— 本仓拿不到那些限制，"
            + "所以默认口径是「先小范围试 + 随时能关 + 随时能接管」。");
  }

  // ================================================================ 全局开关

  /** 全局开关（没有行 = 默认开着；第一次切换时建行）。 */
  public CrmAutoReplySettingRow settings() {
    UUID tenant = currentTenant();
    var row = loadSetting();
    boolean enabled = row == null || Boolean.TRUE.equals(row.get("auto_reply_enabled", Boolean.class));
    String reason = row == null ? "" : nz(row.get("closed_reason", String.class));
    String closedAt = row == null ? null : str(row.get("closed_at", OffsetDateTime.class));
    UUID closedBy = row == null ? null : row.get("closed_by", UUID.class);
    String updatedAt = row == null ? "" : str(row.get("updated_at", OffsetDateTime.class));
    int pending = pendingSendRegisteredCount();
    return new CrmAutoReplySettingRow(
        enabled,
        reason,
        closedAt,
        closedBy,
        pending,
        updatedAt,
        enabled
            ? "自动回复当前**开着**。关闭后新触发不再自动发，已排队待发的会逐条撤销。"
            : "自动回复当前**已关闭**（关闭原因见下）。关闭期间不会评估、不会发送任何回复。");
  }

  /**
   * 🔴 一键开关（蓝图加粗句「必须可一键关闭」）。
   *
   * <p>关闭时：改开关**并**把已排队待发的记录逐条追加一条 {@code outcome='CANCELLED'}
   * 的撤销行 —— <b>不得</b>在关闭后继续发。撤销信号落在**本模块自己的表**上，
   * <b>不 UPDATE {@code crm_message_send}</b>（那张表的写入所有者是 15.1.1.2）。
   */
  @Transactional
  public CrmAutoReplySettingRow toggleSetting(CrmAutoReplySettingToggleInput input, UUID actorId) {
    boolean enabled = Boolean.TRUE.equals(input.enabled());
    String reason = nz(input.reason()).trim();
    if (!enabled && reason.isEmpty()) {
      throw bad(
          "关闭自动回复必须写明关闭原因：事后没人知道当时为什么关、该什么时候开回来。");
    }
    UUID tenant = currentTenant();
    OffsetDateTime moment = now();
    UUID id = UUID.randomUUID();
    var existing = loadSetting();
    if (existing == null) {
      sql.execute(
          "insert into crm_auto_reply_setting"
              + "(id,tenant_id,auto_reply_enabled,closed_reason,closed_at,closed_by,created_at,updated_at)"
              + " values (?,?,?,?,?::timestamptz,?,?::timestamptz,?::timestamptz)",
          id,
          tenant,
          enabled,
          enabled ? "" : reason,
          enabled ? null : moment,
          enabled ? null : actorId,
          moment,
          moment);
    } else {
      sql.execute(
          "update crm_auto_reply_setting set auto_reply_enabled=?,closed_reason=?,"
              + " closed_at=?::timestamptz,closed_by=?,"
              + " updated_at=?::timestamptz where tenant_id=? and id=?",
          enabled,
          enabled ? "" : reason,
          enabled ? null : moment,
          enabled ? null : actorId,
          moment,
          tenant,
          existing.get("id", UUID.class));
    }
    int cancelled = 0;
    if (!enabled) {
      cancelled = cancelPendingRecords(moment);
    }
    var row = loadSetting();
    return new CrmAutoReplySettingRow(
        enabled,
        enabled ? "" : reason,
        enabled ? null : str(row.get("closed_at", OffsetDateTime.class)),
        enabled ? null : row.get("closed_by", UUID.class),
        pendingSendRegisteredCount(),
        str(row.get("updated_at", OffsetDateTime.class)),
        enabled
            ? "自动回复已**打开**：新触发会重新评估并自动回复。"
            : "自动回复已**关闭**：本次同时撤销了 "
                + cancelled
                + " 条待发记录（已排队待发的不得再发）。关闭期间不会评估、不会发送任何回复。");
  }

  /** 把「已登记发送但还没发出去」的记录逐条追加撤销行。返回撤销条数。 */
  private int cancelPendingRecords(OffsetDateTime moment) {
    UUID tenant = currentTenant();
    var pending =
        sql.fetch(
            "select r.id,r.record_no,r.rule_id,r.rule_code,r.rule_name,r.ladder_code,r.level,"
                + " r.trigger_type,r.trigger_reason,r.message_id,r.message_no,r.shop_id,r.shop_name,"
                + " r.platform_code,r.buyer_reference,r.order_reference,r.classification_id,"
                + " r.classification_label,r.template_id,r.template_code,r.template_body_snapshot,"
                + " r.knowledge_version_id,r.knowledge_version_no,r.body_snapshot,r.send_id,r.send_no,"
                + " r.occurred_at"
                + " from crm_auto_reply_record r"
                + " join crm_message_send s on s.tenant_id=r.tenant_id and s.id=r.send_id"
                + " where r.tenant_id=? and r.outcome='SEND_REGISTERED' and s.status='QUEUED'",
            tenant);
    int cancelled = 0;
    for (Record record : pending) {
      sql.execute(
          INSERT_CANCELLED_RECORD,
          UUID.randomUUID(),
          tenant,
          nextRecordNo(),
          record.get("rule_id", UUID.class),
          nz(record.get("rule_code", String.class)),
          nz(record.get("rule_name", String.class)),
          nz(record.get("ladder_code", String.class)),
          record.get("level", Integer.class),
          nz(record.get("trigger_type", String.class)),
          nz(record.get("trigger_reason", String.class)),
          record.get("message_id", UUID.class),
          nz(record.get("message_no", String.class)),
          record.get("shop_id", UUID.class),
          nz(record.get("shop_name", String.class)),
          nz(record.get("platform_code", String.class)),
          nz(record.get("buyer_reference", String.class)),
          nz(record.get("order_reference", String.class)),
          record.get("classification_id", UUID.class),
          nz(record.get("classification_label", String.class)),
          record.get("template_id", UUID.class),
          nz(record.get("template_code", String.class)),
          nz(record.get("template_body_snapshot", String.class)),
          record.get("knowledge_version_id", UUID.class),
          record.get("knowledge_version_no", Integer.class),
          nz(record.get("body_snapshot", String.class)),
          "全局自动回复已关闭：本条已撤销，**不得再发**。",
          record.get("send_id", UUID.class),
          nz(record.get("send_no", String.class)),
          record.get("id", UUID.class),
          moment,
          moment);
      cancelled++;
    }
    return cancelled;
  }

  // ================================================================ 规则

  /** 规则列表（真分页 + 真筛选）。 */
  public CrmAutoReplyRulePage rules(
      String triggerType, String platformCode, Boolean enabled, String ladderCode, String keyword,
      int page, int size) {
    UUID tenant = currentTenant();
    int limit = clampSize(size);
    int offset = Math.max(page, 0) * limit;
    StringBuilder where = new StringBuilder(" where r.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(
          " and (r.rule_code ilike ? escape '\\' or r.rule_name ilike ? escape '\\'"
              + " or r.ladder_code ilike ? escape '\\')");
      binds.add("%" + word + "%");
      binds.add("%" + word + "%");
      binds.add("%" + word + "%");
    }
    String type = blankToNull(triggerType);
    if (type != null) {
      where.append(" and r.trigger_type=?");
      binds.add(type);
    }
    String ladder = blankToNull(ladderCode);
    if (ladder != null) {
      where.append(" and r.ladder_code=?");
      binds.add(ladder);
    }
    String platform = blankToNull(platformCode);
    if (platform != null) {
      where.append(
          " and (r.platform_codes = '' or r.platform_codes like ? escape '\\')");
      binds.add("%" + platform + "%");
    }
    if (enabled != null) {
      where.append(" and r.enabled=?");
      binds.add(enabled);
    }
    long total =
        sql.fetchOne("select count(*) from crm_auto_reply_rule r" + where, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select r.id,r.rule_code,r.rule_name,r.ladder_code,r.level,r.escalate_to_human,r.enabled,"
                + " r.trigger_type,r.classification_labels,r.keywords,r.match_mode,r.platform_codes,"
                + " r.human_timeout_minutes,r.level_wait_minutes,r.unattended_auto_reply_allowed,"
                + " r.template_id,r.template_code,r.knowledge_version_id,r.subject,r.body,r.note,"
                + " r.created_by,r.created_at,r.updated_at"
                + " from crm_auto_reply_rule r"
                + where
                + " order by r.ladder_code, r.level, r.rule_code"
                + " limit ? offset ?",
            bindings(binds, limit, offset));
    List<CrmAutoReplyRuleRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(ruleRow(row));
    }
    return new CrmAutoReplyRulePage(items, Math.max(page, 0), limit, total, pages(total, limit));
  }

  /** 单条规则。 */
  public CrmAutoReplyRuleRow rule(UUID ruleId) {
    var row =
        sql.fetchOne(
            "select r.id,r.rule_code,r.rule_name,r.ladder_code,r.level,r.escalate_to_human,r.enabled,"
                + " r.trigger_type,r.classification_labels,r.keywords,r.match_mode,r.platform_codes,"
                + " r.human_timeout_minutes,r.level_wait_minutes,r.unattended_auto_reply_allowed,"
                + " r.template_id,r.template_code,r.knowledge_version_id,r.subject,r.body,r.note,"
                + " r.created_by,r.created_at,r.updated_at"
                + " from crm_auto_reply_rule r where r.tenant_id=? and r.id=?",
            currentTenant(),
            ruleId);
    if (row == null) {
      throw notFound("自动回复规则不存在，或不属于当前租户");
    }
    return ruleRow(row);
  }

  /** 新建一条自动回复规则。 */
  @Transactional
  public CrmAutoReplyRuleRow createRule(CrmAutoReplyRuleInput input, UUID actorId) {
    UUID tenant = currentTenant();
    RuleShape shape = readRuleShape(input, 1);
    if (shape.level() > 1) {
      var previous =
          sql.fetchOne(
              "select id from crm_auto_reply_rule where tenant_id=? and ladder_code=? and level=?",
              tenant,
              shape.ladderCode(),
              shape.level() - 1);
      if (previous == null) {
        throw bad(
            "阶梯「"
                + shape.ladderCode()
                + "」的第 "
                + shape.level()
                + " 级不能先于第 "
                + (shape.level() - 1)
                + " 级存在：阶梯必须连号，否则会跳过一级引导。");
      }
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_auto_reply_rule"
            + "(id,tenant_id,rule_code,rule_name,ladder_code,level,escalate_to_human,enabled,"
            + " trigger_type,classification_labels,keywords,match_mode,platform_codes,"
            + " human_timeout_minutes,level_wait_minutes,unattended_auto_reply_allowed,"
            + " template_id,template_code,knowledge_version_id,subject,body,note,created_by,"
            + " created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        shape.ruleCode(),
        shape.ruleName(),
        shape.ladderCode(),
        shape.level(),
        shape.escalateToHuman(),
        shape.enabled(),
        shape.triggerType(),
        shape.classificationLabels(),
        shape.keywords(),
        shape.matchMode(),
        shape.platformCodes(),
        shape.humanTimeoutMinutes(),
        shape.levelWaitMinutes(),
        shape.unattendedAutoReplyAllowed(),
        shape.templateId(),
        shape.templateCode(),
        shape.knowledgeVersionId(),
        shape.subject(),
        shape.body(),
        shape.note(),
        actorId,
        moment,
        moment);
    return rule(id);
  }

  /** 修改一条规则（不回写历史记录 —— 记录只追加，且快照了当时的话术）。 */
  @Transactional
  public CrmAutoReplyRuleRow updateRule(UUID ruleId, CrmAutoReplyRuleInput input, UUID actorId) {
    UUID tenant = currentTenant();
    var existing =
        sql.fetchOne(
            "select level,ladder_code from crm_auto_reply_rule where tenant_id=? and id=?",
            tenant,
            ruleId);
    if (existing == null) {
      throw notFound("自动回复规则不存在，或不属于当前租户");
    }
    int currentLevel = existing.get("level", Integer.class);
    RuleShape shape = readRuleShape(input, currentLevel);
    if (shape.level() != currentLevel) {
      throw bad(
          "阶梯位置（level）建后不可改：级别变了，历史记录里的「第几级」就对不上"
              + "（要改请新建一条规则并停用旧的）。");
    }
    if (!shape.ladderCode().equals(existing.get("ladder_code", String.class))) {
      throw bad("阶梯标识（ladderCode）建后不可改：历史记录里存的是当时的阶梯。");
    }
    OffsetDateTime moment = now();
    sql.execute(
        "update crm_auto_reply_rule set rule_name=?,escalate_to_human=?,enabled=?,trigger_type=?,"
            + " classification_labels=?,keywords=?,match_mode=?,platform_codes=?,"
            + " human_timeout_minutes=?,level_wait_minutes=?,unattended_auto_reply_allowed=?,"
            + " template_id=?,template_code=?,knowledge_version_id=?,subject=?,body=?,note=?,"
            + " updated_at=?::timestamptz where tenant_id=? and id=?",
        shape.ruleName(),
        shape.escalateToHuman(),
        shape.enabled(),
        shape.triggerType(),
        shape.classificationLabels(),
        shape.keywords(),
        shape.matchMode(),
        shape.platformCodes(),
        shape.humanTimeoutMinutes(),
        shape.levelWaitMinutes(),
        shape.unattendedAutoReplyAllowed(),
        shape.templateId(),
        shape.templateCode(),
        shape.knowledgeVersionId(),
        shape.subject(),
        shape.body(),
        shape.note(),
        moment,
        tenant,
        ruleId);
    return rule(ruleId);
  }

  // ================================================================ 值班日历

  /** 值班日历列表（真分页 + 真筛选）。 */
  public CrmAutoReplyDutyCalendarPage calendar(
      String scope, String platformCode, String from, String to, int page, int size) {
    UUID tenant = currentTenant();
    int limit = clampSize(size);
    int offset = Math.max(page, 0) * limit;
    StringBuilder where = new StringBuilder(" where c.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    String scopeValue = blankToNull(scope);
    if (scopeValue != null) {
      where.append(" and c.scope=?");
      binds.add(scopeValue);
    }
    String platform = blankToNull(platformCode);
    if (platform != null) {
      where.append(" and c.platform_code=?");
      binds.add(platform);
    }
    LocalDate fromDate = parseDate(from);
    if (fromDate != null) {
      where.append(" and c.calendar_date >= ?::date");
      binds.add(fromDate);
    }
    LocalDate toDate = parseDate(to);
    if (toDate != null) {
      where.append(" and c.calendar_date <= ?::date");
      binds.add(toDate);
    }
    long total =
        sql.fetchOne("select count(*) from crm_auto_reply_duty_calendar c" + where, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select c.id,c.scope,c.platform_code,c.rule_id,c.calendar_date,c.duty_mode,c.duty_start,"
                + " c.duty_end,c.auto_reply_allowed,c.timezone,c.holiday_name,c.note,c.created_by,"
                + " c.created_at,c.updated_at,(select r.rule_code from crm_auto_reply_rule r"
                + "   where r.tenant_id=c.tenant_id and r.id=c.rule_id) as rule_code"
                + " from crm_auto_reply_duty_calendar c"
                + where
                + " order by c.calendar_date desc, c.scope, c.id"
                + " limit ? offset ?",
            bindings(binds, limit, offset));
    List<CrmAutoReplyDutyCalendarRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(calendarRow(row));
    }
    return new CrmAutoReplyDutyCalendarPage(
        items, Math.max(page, 0), limit, total, pages(total, limit));
  }

  /**
   * 新建一条值班日历。
   *
   * <p>🔴 「节假日无人值守要不要自动回复」= 这一行的 {@code autoReplyAllowed}，
   * <b>租户可配</b>，不是写死的常量。
   */
  @Transactional
  public CrmAutoReplyDutyCalendarRow createCalendar(
      CrmAutoReplyDutyCalendarInput input, UUID actorId) {
    UUID tenant = currentTenant();
    String scope = oneOf(List.of("GLOBAL", "PLATFORM", "RULE"), input.scope(), "生效范围");
    String platformCode = blankToNull(input.platformCode());
    UUID ruleId = input.ruleId();
    if ("PLATFORM".equals(scope) && platformCode == null) {
      throw bad("生效范围是「按平台」时必须给出 platformCode。");
    }
    if ("RULE".equals(scope) && ruleId == null) {
      throw bad("生效范围是「按规则」时必须给出 ruleId。");
    }
    if (ruleId != null) {
      var rule =
          sql.fetchOne(
              "select id from crm_auto_reply_rule where tenant_id=? and id=?", tenant, ruleId);
      if (rule == null) {
        throw notFound("值班日历引用的规则不存在，或不属于当前租户");
      }
    }
    LocalDate date = parseDate(input.calendarDate());
    if (date == null) {
      throw bad("生效日期必须写成 ISO 日期（形如 2026-10-23）。");
    }
    String dutyMode = oneOf(List.of("UNATTENDED", "ATTENDED", "CLOSED"), input.dutyMode(), "值班安排");
    LocalTime start = parseTime(input.dutyStart(), "值班时段起");
    LocalTime end = parseTime(input.dutyEnd(), "值班时段止");
    if ((start == null) != (end == null)) {
      throw bad("值班时段的起与止要么都填、要么都不填。");
    }
    if (start != null && start.equals(end)) {
      throw bad("值班时段的起与止不能相同：那等于一个一次都不会命中的空时段。");
    }
    boolean allowed = input.autoReplyAllowed() == null || input.autoReplyAllowed();
    if ("CLOSED".equals(dutyMode) && allowed) {
      throw bad("值班安排是「休息」时不允许自动回复 —— 休息就是没人值班。");
    }
    String timezone = blankToNull(input.timezone()) == null ? "UTC" : input.timezone().trim();
    try {
      ZoneId.of(timezone);
    } catch (RuntimeException e) {
      throw bad("时区「" + timezone + "」无法识别（请用 IANA 时区名，如 Asia/Shanghai）。");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_auto_reply_duty_calendar"
            + "(id,tenant_id,scope,platform_code,rule_id,calendar_date,duty_mode,duty_start,duty_end,"
            + " auto_reply_allowed,timezone,holiday_name,note,created_by,created_at,updated_at)"
            + " values (?,?,?,?,?,?::date,?,?::time,?::time,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        scope,
        platformCode,
        ruleId,
        date,
        dutyMode,
        start,
        end,
        allowed,
        timezone,
        nz(input.holidayName()).trim(),
        nz(input.note()),
        actorId,
        moment,
        moment);
    var row =
        sql.fetchOne(
            "select c.id,c.scope,c.platform_code,c.rule_id,c.calendar_date,c.duty_mode,c.duty_start,"
                + " c.duty_end,c.auto_reply_allowed,c.timezone,c.holiday_name,c.note,c.created_by,"
                + " c.created_at,c.updated_at,(select r.rule_code from crm_auto_reply_rule r"
                + "   where r.tenant_id=c.tenant_id and r.id=c.rule_id) as rule_code"
                + " from crm_auto_reply_duty_calendar c where c.tenant_id=? and c.id=?",
            tenant,
            id);
    return calendarRow(row);
  }

  /** 删除一条值班日历（它是**配置**不是事实，可以删；删了只是不再按它判定）。 */
  @Transactional
  public void deleteCalendar(UUID entryId) {
    int deleted =
        sql.execute(
            "delete from crm_auto_reply_duty_calendar where tenant_id=? and id=?",
            currentTenant(),
            entryId);
    if (deleted == 0) {
      throw notFound("值班日历条目不存在，或不属于当前租户");
    }
  }

  // ================================================================ 触发

  /**
   * 🔴 触发一次自动回复评估（蓝图职责句的落点）。
   *
   * <p>⚠️ 平台站内信同步（15.1.1.1 的 PLATFORM_SYNC）**未接入** ⇒ 本入口由调用方
   * <b>显式</b>调用；平台适配器接入后由它调用**同一个**入口。
   *
   * <p>🔴 全局开关**先看**：关着的时候本方法<b>不评估、不写任何记录</b> ——
   * 「一键关闭」必须是真正的关闭，不是「照常评估只是不发」。
   */
  @Transactional
  public CrmAutoReplyTriggerResult trigger(
      CrmAutoReplyTriggerInput input, UUID actorId, String bearer) {
    UUID tenant = currentTenant();
    OffsetDateTime at = parseInstant(input.at(), "评估时刻");
    if (at == null) {
      at = now();
    }
    var message =
        sql.fetchOne(
            "select id,message_no,shop_id,shop_name,platform_code,buyer_reference,order_reference,"
                + " subject,body,direction,platform_time"
                + " from crm_message where tenant_id=? and id=?",
            tenant,
            input.messageId());
    if (message == null) {
      throw notFound("消息不存在，或不属于当前租户");
    }
    UUID messageId = message.get("id", UUID.class);
    String messageNo = nz(message.get("message_no", String.class));
    if (!"INBOUND".equals(nz(message.get("direction", String.class)))) {
      throw bad("只能对**收到的**消息做自动回复评估：这条是发出去的方向。");
    }

    // ---- 全局开关：关着就不评估、不写记录 ----
    var setting = loadSetting();
    boolean globallyEnabled =
        setting == null || Boolean.TRUE.equals(setting.get("auto_reply_enabled", Boolean.class));
    if (!globallyEnabled) {
      String closedReason = nz(setting.get("closed_reason", String.class));
      return new CrmAutoReplyTriggerResult(
          messageId,
          messageNo,
          false,
          true,
          closedReason,
          false,
          "",
          false,
          at.toString(),
          0,
          0,
          0,
          0,
          List.of(),
          "🔴 自动回复**全局已关闭**（关闭原因："
              + closedReason
              + "）：本次**没有**评估、**没有**发送任何回复，也没有写任何自动回复记录。");
    }

    UUID shopId = message.get("shop_id", UUID.class);
    String platformCode = nz(message.get("platform_code", String.class));
    String buyerReference = nz(message.get("buyer_reference", String.class));

    // ---- 🔴 人工接管闸（会话级硬闸）----
    var takeover = takeoverOf(shopId, buyerReference);
    boolean takenOver = takeover != null;

    // ---- 上游 15.1.1.3 的分类结论（只读）----
    var classification =
        sql.fetchOne(
            "select id,label,auto_reply_eligible from crm_message_classification"
                + " where tenant_id=? and message_id=?",
            tenant,
            messageId);
    String label = classification == null ? "" : nz(classification.get("label", String.class));
    boolean eligible =
        classification != null
            && Boolean.TRUE.equals(classification.get("auto_reply_eligible", Boolean.class));
    UUID classificationId = classification == null ? null : classification.get("id", UUID.class);

    boolean humanIntervened = humanIntervened(messageId);

    // ---- 候选规则：启用中的全部规则，逐条判定 ----
    var rules =
        sql.fetch(
            "select id,rule_code,rule_name,ladder_code,level,escalate_to_human,trigger_type,"
                + " classification_labels,keywords,match_mode,platform_codes,human_timeout_minutes,"
                + " level_wait_minutes,unattended_auto_reply_allowed,template_id,template_code,"
                + " knowledge_version_id,subject,body"
                + " from crm_auto_reply_rule where tenant_id=? and enabled=true"
                + " order by ladder_code, level, rule_code",
            tenant);

    List<CrmAutoReplyTriggeredItem> items = new ArrayList<>();
    int matchedCount = 0;
    int firedCount = 0;
    int suppressedCount = 0;
    int escalatedCount = 0;

    for (Record rule : rules) {
      UUID ruleId = rule.get("id", UUID.class);
      String ruleCode = nz(rule.get("rule_code", String.class));
      String ruleName = nz(rule.get("rule_name", String.class));
      String ladderCode = nz(rule.get("ladder_code", String.class));
      int level = rule.get("level", Integer.class);
      String triggerType = nz(rule.get("trigger_type", String.class));

      // (1) 平台闸
      List<String> platformCodes = splitCommas(nz(rule.get("platform_codes", String.class)));
      if (!platformCodes.isEmpty() && !platformCodes.contains(platformCode)) {
        items.add(
            unmatched(rule, "适用平台不含 " + platformCode + "（本条规则只适用："
                + String.join(" / ", platformCodes) + "）"));
        continue;
      }

      // (2) 触发条件
      TriggerVerdict trigger = evaluateTrigger(rule, message, label, at, humanIntervened);
      if (!trigger.matched()) {
        items.add(unmatched(rule, trigger.reason()));
        continue;
      }

      // (3) 阶梯闸：同一级**只发一次**；第 N 级还必须「上一级已发出 + 距上一级至少过
      //     level_wait_minutes + 期间无人工介入」。
      var sentAtThisLevel =
          sql.fetchOne(
              "select id from crm_auto_reply_record where tenant_id=? and message_id=?"
                  + " and ladder_code=? and level=? and outcome='SEND_REGISTERED' limit 1",
              tenant,
              messageId,
              ladderCode,
              level);
      if (sentAtThisLevel != null) {
        items.add(unmatched(rule, "阶梯「" + ladderCode + "」的第 " + level + " 级**已经发过**了。"));
        continue;
      }
      if (level > 1) {
        var previous =
            sql.fetchOne(
                "select occurred_at from crm_auto_reply_record"
                    + " where tenant_id=? and message_id=? and ladder_code=? and level=?"
                    + " and outcome='SEND_REGISTERED' order by occurred_at desc limit 1",
                tenant,
                messageId,
                ladderCode,
                level - 1);
        if (previous == null) {
          items.add(
              unmatched(
                  rule,
                  "阶梯「"
                      + ladderCode
                      + "」的第 "
                      + (level - 1)
                      + " 级还没发出过：先自动引导，再发下一级 —— 不能跳过。"));
          continue;
        }
        OffsetDateTime previousAt = previous.get("occurred_at", OffsetDateTime.class);
        long waitMinutes = rule.get("level_wait_minutes", Integer.class);
        long elapsed = Duration.between(previousAt, at).toMinutes();
        if (elapsed < waitMinutes) {
          items.add(
              unmatched(
                  rule,
                  "距上一级自动回复才过去 "
                      + elapsed
                      + " 分钟，本级要求至少 "
                      + waitMinutes
                      + " 分钟（间隔口径可配置）。"));
          continue;
        }
        if (humanIntervened) {
          items.add(unmatched(rule, "期间已有**人工介入**（客服已回复），不再自动回复。"));
          continue;
        }
      }

      matchedCount++;

      // (4) 🔴 人工接管闸
      if (takenOver) {
        var suppressed =
            writeRecord(
                tenant,
                rule,
                message,
                classificationId,
                label,
                trigger.reason(),
                "SUPPRESSED",
                "🔴 该会话已由人工接管（接管人 "
                    + takeover.get("taken_over_by", UUID.class)
                    + "，接管时刻 "
                    + str(takeover.get("taken_over_at", OffsetDateTime.class))
                    + "）：接管后**不再自动回复同一会话**。",
                null,
                null,
                null,
                false,
                at);
        suppressedCount++;
        items.add(
            new CrmAutoReplyTriggeredItem(
                ruleId,
                ruleCode,
                ruleName,
                ladderCode,
                level,
                triggerType,
                triggerTypeLabel(triggerType),
                true,
                false,
                "SUPPRESSED",
                outcomeLabel("SUPPRESSED"),
                "该会话已被人工接管，本次不再自动回复。",
                suppressed.id(),
                suppressed.recordNo(),
                null,
                null,
                false,
                null));
        continue;
      }

      // (5) 上游 15.1.1.3 的安全闸：auto_reply_eligible=false 的消息不许自动回
      if ("CLASSIFICATION".equals(triggerType) && !eligible) {
        var suppressed =
            writeRecord(
                tenant,
                rule,
                message,
                classificationId,
                label,
                trigger.reason(),
                "SUPPRESSED",
                "上游 15.1.1.3 判定该消息**不可自动回复**（label="
                    + (label.isEmpty() ? "（无分类结论）" : label)
                    + "，auto_reply_eligible=false）：说不清是什么的消息就自动回，风险由买家承担。",
                null,
                null,
                null,
                false,
                at);
        suppressedCount++;
        items.add(
            new CrmAutoReplyTriggeredItem(
                ruleId,
                ruleCode,
                ruleName,
                ladderCode,
                level,
                triggerType,
                triggerTypeLabel(triggerType),
                true,
                false,
                "SUPPRESSED",
                outcomeLabel("SUPPRESSED"),
                "上游 15.1.1.3 判定该消息不可自动回复。",
                suppressed.id(),
                suppressed.recordNo(),
                null,
                null,
                false,
                null));
        continue;
      }

      // (6) 🔴 值班闸（节假日 / 非营业时段）
      DutyDecision duty =
          dutyDecision(ruleId, platformCode, at, rule.get("unattended_auto_reply_allowed", Boolean.class));
      if (!duty.allowed()) {
        var suppressed =
            writeRecord(
                tenant,
                rule,
                message,
                classificationId,
                label,
                trigger.reason(),
                "SUPPRESSED",
                "🔴 值班闸挡下：" + duty.reason(),
                null,
                null,
                null,
                true,
                at);
        suppressedCount++;
        var escalation =
            escalate(
                tenant,
                message,
                suppressed.id(),
                ruleId,
                ruleCode,
                level,
                "DUTY_GATE",
                "值班闸挡下自动回复（" + duty.reason() + "），需人工接管。",
                null,
                at);
        if (escalation != null) {
          escalatedCount++;
        }
        items.add(
            new CrmAutoReplyTriggeredItem(
                ruleId,
                ruleCode,
                ruleName,
                ladderCode,
                level,
                triggerType,
                triggerTypeLabel(triggerType),
                true,
                false,
                "SUPPRESSED",
                outcomeLabel("SUPPRESSED"),
                duty.reason(),
                suppressed.id(),
                suppressed.recordNo(),
                null,
                null,
                true,
                escalation == null ? null : escalation.id()));
        continue;
      }

      // (7) 发出去：🔴 复用 15.1.1.2 的发送通道，不重造。
      try {
        CrmMessageSendOutcome outcome =
            sends.send(
                new CrmMessageSendInput(
                    messageId,
                    rule.get("template_id", UUID.class),
                    rule.get("knowledge_version_id", UUID.class),
                    nz(rule.get("subject", String.class)),
                    nz(rule.get("body", String.class)),
                    "MANUAL_HANDOFF",
                    "QUEUED",
                    null,
                    null,
                    null,
                    null,
                    "auto-reply:" + ruleId + ":" + messageId + ":" + level,
                    "自动回复（规则 " + ruleCode + "，第 " + level + " 级）"),
                actorId,
                bearer);
        var sent = outcome.send();
        var record =
            writeRecord(
                tenant,
                rule,
                message,
                classificationId,
                label,
                trigger.reason(),
                "SEND_REGISTERED",
                "",
                sent.id(),
                sent.sendNo(),
                sent.body(),
                Boolean.TRUE.equals(rule.get("escalate_to_human", Boolean.class)),
                at);
        firedCount++;
        UUID escalationId = null;
        if (Boolean.TRUE.equals(rule.get("escalate_to_human", Boolean.class))) {
          var escalation =
              escalate(
                  tenant,
                  message,
                  record.id(),
                  ruleId,
                  ruleCode,
                  level,
                  "LADDER_EXHAUSTED",
                  "阶梯「"
                      + ladderCode
                      + "」已走到第 "
                      + level
                      + " 级（自动引导到此为止），需人工接管。",
                  null,
                  at);
          if (escalation != null) {
            escalationId = escalation.id();
            escalatedCount++;
          }
        }
        items.add(
            new CrmAutoReplyTriggeredItem(
                ruleId,
                ruleCode,
                ruleName,
                ladderCode,
                level,
                triggerType,
                triggerTypeLabel(triggerType),
                true,
                true,
                "SEND_REGISTERED",
                outcomeLabel("SEND_REGISTERED"),
                "已登记发送（发送记录 " + sent.sendNo() + "）：平台侧发送接口未接入，"
                    + "运营在平台侧实际发出后回登记结果。",
                record.id(),
                record.recordNo(),
                sent.id(),
                sent.sendNo(),
                escalationId != null || Boolean.TRUE.equals(rule.get("escalate_to_human", Boolean.class)),
                escalationId));
      } catch (RuntimeException e) {
        String reason = reasonOf(e);
        var suppressed =
            writeRecord(
                tenant,
                rule,
                message,
                classificationId,
                label,
                trigger.reason(),
                "SUPPRESSED",
                "发送登记失败：" + reason,
                null,
                null,
                null,
                true,
                at);
        suppressedCount++;
        var escalation =
            escalate(
                tenant,
                message,
                suppressed.id(),
                ruleId,
                ruleCode,
                level,
                "SEND_FAILED",
                "自动回复登记发送失败（" + reason + "），买家没有收到任何回复，需人工接管。",
                null,
                at);
        if (escalation != null) {
          escalatedCount++;
        }
        items.add(
            new CrmAutoReplyTriggeredItem(
                ruleId,
                ruleCode,
                ruleName,
                ladderCode,
                level,
                triggerType,
                triggerTypeLabel(triggerType),
                true,
                false,
                "SUPPRESSED",
                outcomeLabel("SUPPRESSED"),
                "发送登记失败：" + reason,
                suppressed.id(),
                suppressed.recordNo(),
                null,
                null,
                escalation != null,
                escalation == null ? null : escalation.id()));
      }
    }

    String note =
        globallyEnabled
            ? "评估完成：命中 "
                + matchedCount
                + " 条规则，发出 "
                + firedCount
                + " 条，被闸门挡下 "
                + suppressedCount
                + " 条，转人工 "
                + escalatedCount
                + " 条。"
            : "自动回复全局已关闭，本次没有评估。";
    return new CrmAutoReplyTriggerResult(
        messageId,
        messageNo,
        true,
        false,
        "",
        takenOver,
        label,
        eligible,
        at.toString(),
        matchedCount,
        firedCount,
        suppressedCount,
        escalatedCount,
        items,
        note);
  }

  // ================================================================ 自动回复记录

  /** 自动回复记录列表（真分页 + 真筛选 + 与分页无关的全量汇总）。 */
  public CrmAutoReplyRecordPage records(
      String outcome,
      String triggerType,
      UUID ruleId,
      UUID messageId,
      String ladderCode,
      Boolean escalated,
      String keyword,
      String from,
      String to,
      int page,
      int size) {
    UUID tenant = currentTenant();
    int limit = clampSize(size);
    int offset = Math.max(page, 0) * limit;
    StringBuilder where = new StringBuilder(" where r.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    String outcomeValue = blankToNull(outcome);
    if (outcomeValue != null) {
      where.append(" and r.outcome=?");
      binds.add(outcomeValue);
    }
    String type = blankToNull(triggerType);
    if (type != null) {
      where.append(" and r.trigger_type=?");
      binds.add(type);
    }
    if (ruleId != null) {
      where.append(" and r.rule_id=?");
      binds.add(ruleId);
    }
    if (messageId != null) {
      where.append(" and r.message_id=?");
      binds.add(messageId);
    }
    String ladder = blankToNull(ladderCode);
    if (ladder != null) {
      where.append(" and r.ladder_code=?");
      binds.add(ladder);
    }
    if (escalated != null) {
      where.append(" and r.escalated=?");
      binds.add(escalated);
    }
    OffsetDateTime fromAt = parseInstant(from, "起始时刻");
    if (fromAt != null) {
      where.append(" and r.occurred_at >= ?::timestamptz");
      binds.add(fromAt);
    }
    OffsetDateTime toAt = parseInstant(to, "结束时刻");
    if (toAt != null) {
      where.append(" and r.occurred_at <= ?::timestamptz");
      binds.add(toAt);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(
          " and (r.rule_code ilike ? escape '\\' or r.rule_name ilike ? escape '\\'"
              + " or r.message_no ilike ? escape '\\' or r.buyer_reference ilike ? escape '\\'"
              + " or r.record_no ilike ? escape '\\')");
      for (int i = 0; i < 5; i++) {
        binds.add("%" + word + "%");
      }
    }
    long total =
        sql.fetchOne("select count(*) from crm_auto_reply_record r" + where, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select r.id,r.record_no,r.rule_id,r.rule_code,r.rule_name,r.ladder_code,r.level,"
                + " r.trigger_type,r.trigger_reason,r.message_id,r.message_no,r.shop_id,r.shop_name,"
                + " r.platform_code,r.buyer_reference,r.order_reference,r.classification_id,"
                + " r.classification_label,r.template_id,r.template_code,r.template_body_snapshot,"
                + " r.knowledge_version_id,r.knowledge_version_no,r.body_snapshot,r.outcome,r.reason,"
                + " r.send_id,r.send_no,r.origin_record_id,r.escalated,r.occurred_at,r.created_at,"
                + " s.status as send_status,s.failure_reason as send_failure_reason"
                + " from crm_auto_reply_record r"
                + " left join crm_message_send s on s.tenant_id=r.tenant_id and s.id=r.send_id"
                + where
                + " order by r.occurred_at desc, r.id desc"
                + " limit ? offset ?",
            bindings(binds, limit, offset));
    List<CrmAutoReplyRecordRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(recordRow(row));
    }
    return new CrmAutoReplyRecordPage(items, Math.max(page, 0), limit, total, pages(total, limit));
  }

  /** 单条自动回复记录。 */
  public CrmAutoReplyRecordRow record(UUID recordId) {
    var row =
        sql.fetchOne(
            "select r.id,r.record_no,r.rule_id,r.rule_code,r.rule_name,r.ladder_code,r.level,"
                + " r.trigger_type,r.trigger_reason,r.message_id,r.message_no,r.shop_id,r.shop_name,"
                + " r.platform_code,r.buyer_reference,r.order_reference,r.classification_id,"
                + " r.classification_label,r.template_id,r.template_code,r.template_body_snapshot,"
                + " r.knowledge_version_id,r.knowledge_version_no,r.body_snapshot,r.outcome,r.reason,"
                + " r.send_id,r.send_no,r.origin_record_id,r.escalated,r.occurred_at,r.created_at,"
                + " s.status as send_status,s.failure_reason as send_failure_reason"
                + " from crm_auto_reply_record r"
                + " left join crm_message_send s on s.tenant_id=r.tenant_id and s.id=r.send_id"
                + " where r.tenant_id=? and r.id=?",
            currentTenant(),
            recordId);
    if (row == null) {
      throw notFound("自动回复记录不存在，或不属于当前租户");
    }
    return recordRow(row);
  }

  // ================================================================ 转人工标记

  /** 转人工标记列表（真分页 + 真筛选）。 */
  public CrmAutoReplyEscalationPage escalations(
      String reasonCode, UUID shopId, Boolean takenOver, String keyword, int page, int size) {
    UUID tenant = currentTenant();
    int limit = clampSize(size);
    int offset = Math.max(page, 0) * limit;
    StringBuilder where = new StringBuilder(" where e.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    String code = blankToNull(reasonCode);
    if (code != null) {
      where.append(" and e.reason_code=?");
      binds.add(code);
    }
    if (shopId != null) {
      where.append(" and e.shop_id=?");
      binds.add(shopId);
    }
    if (takenOver != null) {
      where.append(
          takenOver
              ? " and exists (select 1 from crm_auto_reply_takeover t where t.tenant_id=e.tenant_id"
                  + " and t.shop_id=e.shop_id and t.buyer_reference=e.buyer_reference)"
              : " and not exists (select 1 from crm_auto_reply_takeover t where t.tenant_id=e.tenant_id"
                  + " and t.shop_id=e.shop_id and t.buyer_reference=e.buyer_reference)");
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(
          " and (e.message_no ilike ? escape '\\' or e.buyer_reference ilike ? escape '\\'"
              + " or e.rule_code ilike ? escape '\\' or e.reason ilike ? escape '\\')");
      for (int i = 0; i < 4; i++) {
        binds.add("%" + word + "%");
      }
    }
    long total =
        sql.fetchOne("select count(*) from crm_auto_reply_escalation e" + where, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select e.id,e.message_id,e.message_no,e.shop_id,e.shop_name,e.platform_code,"
                + " e.buyer_reference,e.order_reference,e.record_id,e.rule_id,e.rule_code,e.level,"
                + " e.reason_code,e.reason,e.escalated_by,e.escalated_at,e.created_at,"
                + " t.id as takeover_id"
                + " from crm_auto_reply_escalation e"
                + " left join crm_auto_reply_takeover t on t.tenant_id=e.tenant_id"
                + "   and t.shop_id=e.shop_id and t.buyer_reference=e.buyer_reference"
                + where
                + " order by e.escalated_at desc, e.id desc"
                + " limit ? offset ?",
            bindings(binds, limit, offset));
    List<CrmAutoReplyEscalationRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(escalationRow(row));
    }
    return new CrmAutoReplyEscalationPage(
        items, Math.max(page, 0), limit, total, pages(total, limit));
  }

  /**
   * 人工手动标记转人工（蓝图输出句里的「转人工标记」的人工入口）。
   *
   * <p>🔴 一条消息只转人工一次：重复标记返回**既有**标记，不制造第二条待办。
   */
  @Transactional
  public CrmAutoReplyEscalationRow escalate(
      UUID recordId, CrmAutoReplyEscalateInput input, UUID actorId) {
    UUID tenant = currentTenant();
    var record =
        sql.fetchOne(
            "select id,message_id,rule_id,rule_code,level from crm_auto_reply_record"
                + " where tenant_id=? and id=?",
            tenant,
            recordId);
    if (record == null) {
      throw notFound("自动回复记录不存在，或不属于当前租户");
    }
    var message =
        sql.fetchOne(
            "select id,message_no,shop_id,shop_name,platform_code,buyer_reference,order_reference"
                + " from crm_message where tenant_id=? and id=?",
            tenant,
            record.get("message_id", UUID.class));
    if (message == null) {
      throw notFound("自动回复记录指向的消息不存在，或不属于当前租户");
    }
    OffsetDateTime at = now();
    String reason = nz(input.reason()).trim();
    if (reason.isEmpty()) {
      throw bad("标记转人工必须写明原因：没有理由的转人工没法复核。");
    }
    var escalation =
        escalate(
            tenant,
            message,
            record.get("id", UUID.class),
            record.get("rule_id", UUID.class),
            nz(record.get("rule_code", String.class)),
            record.get("level", Integer.class),
            "MANUAL_ESCALATION",
            reason,
            actorId,
            at);
    if (escalation == null) {
      throw conflict("这条消息此前已经转过人工了（重复标记不会制造第二条待办）。");
    }
    return escalation;
  }

  // ================================================================ 人工接管

  /** 人工接管标记列表（真分页 + 真筛选）。 */
  public CrmAutoReplyTakeoverPage takeovers(
      UUID shopId, String buyerReference, String keyword, int page, int size) {
    UUID tenant = currentTenant();
    int limit = clampSize(size);
    int offset = Math.max(page, 0) * limit;
    StringBuilder where = new StringBuilder(" where t.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    if (shopId != null) {
      where.append(" and t.shop_id=?");
      binds.add(shopId);
    }
    String buyer = blankToNull(buyerReference);
    if (buyer != null) {
      where.append(" and t.buyer_reference=?");
      binds.add(buyer);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      where.append(
          " and (t.buyer_reference ilike ? escape '\\' or t.reason ilike ? escape '\\')");
      binds.add("%" + word + "%");
      binds.add("%" + word + "%");
    }
    long total =
        sql.fetchOne("select count(*) from crm_auto_reply_takeover t" + where, binds.toArray())
            .get(0, Long.class);
    List<Record> rows =
        sql.fetch(
            "select t.id,t.shop_id,t.shop_name,t.platform_code,t.buyer_reference,t.message_id,"
                + " t.record_id,t.escalation_id,t.taken_over_by,t.taken_over_at,t.reason,t.created_at"
                + " from crm_auto_reply_takeover t"
                + where
                + " order by t.taken_over_at desc, t.id desc"
                + " limit ? offset ?",
            bindings(binds, limit, offset));
    List<CrmAutoReplyTakeoverRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(takeoverRow(row));
    }
    return new CrmAutoReplyTakeoverPage(items, Math.max(page, 0), limit, total, pages(total, limit));
  }

  /**
   * 🔴 标记人工接管（蓝图加粗句「留出人工接管」）。
   *
   * <p>接管以<b>会话</b>（shop_id + buyer_reference）为粒度 ⇒ 接管后<b>同一会话
   * 不再自动回复</b>。同一个会话重复接管返回**既有**行（幂等），不制造第二条。
   */
  @Transactional
  public CrmAutoReplyTakeoverRow takeover(CrmAutoReplyTakeoverInput input, UUID actorId) {
    UUID tenant = currentTenant();
    String reason = nz(input.reason()).trim();
    if (reason.isEmpty()) {
      throw bad("标记人工接管必须写明原因：没有理由的接管，事后没人知道客服为什么不再让系统回。");
    }
    UUID shopId = input.shopId();
    String platformCode = "";
    String buyerReference = "";
    UUID messageId = input.messageId();
    Record message = null;
    if (messageId != null) {
      message =
          sql.fetchOne(
              "select id,shop_id,shop_name,platform_code,buyer_reference from crm_message"
                  + " where tenant_id=? and id=?",
              tenant,
              messageId);
      if (message == null) {
        throw notFound("消息不存在，或不属于当前租户");
      }
      shopId = message.get("shop_id", UUID.class);
      platformCode = nz(message.get("platform_code", String.class));
      buyerReference = nz(message.get("buyer_reference", String.class));
    } else if (input.recordId() != null) {
      var record =
          sql.fetchOne(
              "select message_id from crm_auto_reply_record where tenant_id=? and id=?",
              tenant,
              input.recordId());
      if (record == null) {
        throw notFound("自动回复记录不存在，或不属于当前租户");
      }
      messageId = record.get("message_id", UUID.class);
      message =
          sql.fetchOne(
              "select id,shop_id,shop_name,platform_code,buyer_reference from crm_message"
                  + " where tenant_id=? and id=?",
              tenant,
              messageId);
      if (message == null) {
        throw notFound("自动回复记录指向的消息不存在，或不属于当前租户");
      }
      shopId = message.get("shop_id", UUID.class);
      platformCode = nz(message.get("platform_code", String.class));
      buyerReference = nz(message.get("buyer_reference", String.class));
    } else if (input.escalationId() != null) {
      var escalation =
          sql.fetchOne(
              "select message_id from crm_auto_reply_escalation where tenant_id=? and id=?",
              tenant,
              input.escalationId());
      if (escalation == null) {
        throw notFound("转人工标记不存在，或不属于当前租户");
      }
      messageId = escalation.get("message_id", UUID.class);
      message =
          sql.fetchOne(
              "select id,shop_id,shop_name,platform_code,buyer_reference from crm_message"
                  + " where tenant_id=? and id=?",
              tenant,
              messageId);
      if (message == null) {
        throw notFound("转人工标记指向的消息不存在，或不属于当前租户");
      }
      shopId = message.get("shop_id", UUID.class);
      platformCode = nz(message.get("platform_code", String.class));
      buyerReference = nz(message.get("buyer_reference", String.class));
    }
    if (message == null || shopId == null || buyerReference.isEmpty()) {
      throw bad(
          "标记人工接管必须指明来源：给 messageId / recordId / escalationId 其一即可"
              + "（会话标识 = 店铺 + 买家标识，缺一无法定位会话）。");
    }
    var existing = takeoverOf(shopId, buyerReference);
    if (existing != null) {
      return takeoverRow(existing);
    }
    OffsetDateTime at = parseInstant(input.takenOverAt(), "接管时刻");
    if (at == null) {
      at = now();
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into crm_auto_reply_takeover"
            + "(id,tenant_id,shop_id,shop_name,platform_code,buyer_reference,message_id,record_id,"
            + " escalation_id,taken_over_by,taken_over_at,reason,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?::timestamptz)",
        id,
        tenant,
        shopId,
        nz(message.get("shop_name", String.class)),
        platformCode,
        buyerReference,
        messageId,
        input.recordId(),
        input.escalationId(),
        actorId,
        at,
        reason,
        at);
    var row =
        sql.fetchOne(
            "select t.id,t.shop_id,t.shop_name,t.platform_code,t.buyer_reference,t.message_id,"
                + " t.record_id,t.escalation_id,t.taken_over_by,t.taken_over_at,t.reason,t.created_at"
                + " from crm_auto_reply_takeover t where t.tenant_id=? and t.id=?",
            tenant,
        id);
    return takeoverRow(row);
  }

  // ================================================================ 统计

  /** 统计汇总（**服务端算**：前端不重算、不做算术）。 */
  public CrmAutoReplyStats stats(String from, String to) {
    UUID tenant = currentTenant();
    StringBuilder where = new StringBuilder(" where tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    OffsetDateTime fromAt = parseInstant(from, "起始时刻");
    if (fromAt != null) {
      where.append(" and occurred_at >= ?::timestamptz");
      binds.add(fromAt);
    }
    OffsetDateTime toAt = parseInstant(to, "结束时刻");
    if (toAt != null) {
      where.append(" and occurred_at <= ?::timestamptz");
      binds.add(toAt);
    }
    long total =
        sql.fetchOne("select count(*) from crm_auto_reply_record" + where, binds.toArray())
            .get(0, Long.class);
    long sendRegistered =
        countRecords(where, binds, "outcome='SEND_REGISTERED'");
    long suppressed = countRecords(where, binds, "outcome='SUPPRESSED'");
    long cancelled = countRecords(where, binds, "outcome='CANCELLED'");
    long escalations =
        sql.fetchOne(
                "select count(*) from crm_auto_reply_escalation where tenant_id=?", tenant)
            .get(0, Long.class);
    long escalationsTakenOver =
        sql.fetchOne(
                "select count(*) from crm_auto_reply_escalation e where e.tenant_id=? and exists ("
                    + " select 1 from crm_auto_reply_takeover t where t.tenant_id=e.tenant_id"
                    + " and t.shop_id=e.shop_id and t.buyer_reference=e.buyer_reference)",
                tenant)
            .get(0, Long.class);
    long takeovers =
        sql.fetchOne("select count(*) from crm_auto_reply_takeover where tenant_id=?", tenant)
            .get(0, Long.class);
    long levelOne = countRecords(where, binds, "level=1");
    long levelLater = countRecords(where, binds, "level>1");
    long escalatedRecords = countRecords(where, binds, "escalated=true");
    List<CrmAutoReplyBucket> byOutcome = new ArrayList<>();
    for (var row :
        sql.fetch(
            "select outcome, count(*) as c from crm_auto_reply_record"
                + where
                + " group by outcome order by outcome",
            binds.toArray())) {
      String code = nz(row.get("outcome", String.class));
      byOutcome.add(new CrmAutoReplyBucket(code, outcomeLabel(code), row.get("c", Long.class)));
    }
    List<CrmAutoReplyBucket> byTriggerType = new ArrayList<>();
    for (var row :
        sql.fetch(
            "select trigger_type, count(*) as c from crm_auto_reply_record"
                + where
                + " group by trigger_type order by trigger_type",
            binds.toArray())) {
      String code = nz(row.get("trigger_type", String.class));
      byTriggerType.add(
          new CrmAutoReplyBucket(code, triggerTypeLabel(code), row.get("c", Long.class)));
    }
    return new CrmAutoReplyStats(
        total,
        sendRegistered,
        suppressed,
        escalatedRecords,
        cancelled,
        escalations,
        escalationsTakenOver,
        takeovers,
        levelOne,
        levelLater,
        byOutcome,
        byTriggerType,
        "统计口径由服务端算好，页面**不重算**任何比率。");
  }

  private long countRecords(StringBuilder where, List<Object> binds, String extra) {
    List<Object> all = new ArrayList<>(binds);
    return sql.fetchOne(
            "select count(*) from crm_auto_reply_record" + where + " and " + extra, all.toArray())
        .get(0, Long.class);
  }

  // ================================================================ 触发条件的判定

  /** 触发条件的判定结果。 */
  private record TriggerVerdict(boolean matched, String reason) {}

  /**
   * 判定一条规则的触发条件。
   *
   * <p>🔴 三种口径互斥：
   *
   * <ul>
   *   <li>CLASSIFICATION —— 复用 15.1.1.3 的分类结论（label 命中规则的标签清单）；
   *   <li>KEYWORD —— subject / body 的大小写不敏感 contains，与 15.1.1.3 的关键词口径一致；
   *   <li>HUMAN_TIMEOUT —— 「无人工介入超时」：距参考时刻已过 human_timeout_minutes
   *       且期间没有人工介入。
   * </ul>
   */
  private TriggerVerdict evaluateTrigger(
      Record rule,
      Record message,
      String label,
      OffsetDateTime at,
      boolean humanIntervened) {
    String triggerType = nz(rule.get("trigger_type", String.class));
    if ("CLASSIFICATION".equals(triggerType)) {
      List<String> labels = splitCommas(nz(rule.get("classification_labels", String.class)));
      if (label.isEmpty()) {
        return new TriggerVerdict(false, "上游 15.1.1.3 还没有这条消息的分类结论，分类触发条件无法判定。");
      }
      if (!labels.contains(label)) {
        return new TriggerVerdict(
            false, "分类「" + label + "」不在本条规则的命中清单里（" + String.join(" / ", labels) + "）。");
      }
      return new TriggerVerdict(true, "命中分类「" + label + "」（15.1.1.3 的分类结论）。");
    }
    if ("KEYWORD".equals(triggerType)) {
      List<String> keywords = splitLines(nz(rule.get("keywords", String.class)));
      if (keywords.isEmpty()) {
        return new TriggerVerdict(false, "本条规则没有配关键词。");
      }
      String subject = nz(message.get("subject", String.class)).toLowerCase(Locale.ROOT);
      String body = nz(message.get("body", String.class)).toLowerCase(Locale.ROOT);
      List<String> hits = new ArrayList<>();
      for (String keyword : keywords) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        if (subject.contains(lower) || body.contains(lower)) {
          hits.add(keyword);
        }
      }
      boolean all = "ALL".equals(nz(rule.get("match_mode", String.class)));
      if (all && hits.size() < keywords.size()) {
        List<String> missing = new ArrayList<>(keywords);
        missing.removeAll(hits);
        return new TriggerVerdict(
            false,
            "关键词需要**全部**命中，缺少：" + String.join(" / ", missing) + "。");
      }
      if (hits.isEmpty()) {
        return new TriggerVerdict(false, "关键词未命中（" + String.join(" / ", keywords) + "）。");
      }
      return new TriggerVerdict(true, "命中关键词：" + String.join(" / ", hits) + "。");
    }
    if ("HUMAN_TIMEOUT".equals(triggerType)) {
      if (humanIntervened) {
        return new TriggerVerdict(false, "这条消息**已有**人工介入（客服已回复），不构成「无人工介入超时」。");
      }
      int timeoutMinutes = rule.get("human_timeout_minutes", Integer.class);
      OffsetDateTime reference = lastAutoReplyAt(message.get("id", UUID.class));
      OffsetDateTime platformTime = message.get("platform_time", OffsetDateTime.class);
      if (reference == null || platformTime.isAfter(reference)) {
        reference = platformTime;
      }
      long elapsed = Duration.between(reference, at).toMinutes();
      if (elapsed < timeoutMinutes) {
        return new TriggerVerdict(
            false,
            "距参考时刻才过去 "
                + elapsed
                + " 分钟，无人工介入超时的口径是 "
                + timeoutMinutes
                + " 分钟（口径可配置）。");
      }
      return new TriggerVerdict(
          true,
          "已无人工介入 "
              + elapsed
              + " 分钟（超过 "
              + timeoutMinutes
              + " 分钟口径）。");
    }
    return new TriggerVerdict(false, "触发条件未知：" + triggerType);
  }

  /** 这条消息最近一次**本模块**自动回复的时刻（没有则 null）。 */
  private OffsetDateTime lastAutoReplyAt(UUID messageId) {
    var row =
        sql.fetchOne(
            "select occurred_at from crm_auto_reply_record where tenant_id=? and message_id=?"
                + " and outcome='SEND_REGISTERED' order by occurred_at desc limit 1",
            currentTenant(),
            messageId);
    return row == null ? null : row.get("occurred_at", OffsetDateTime.class);
  }

  /** 这条消息是否已被**人工**介入（客服已回复）。 */
  private boolean humanIntervened(UUID messageId) {
    var row =
        sql.fetchOne(
            "select count(*) from crm_message_send s where s.tenant_id=? and s.target_message_id=?"
                + " and s.status='SENT'"
                + " and not exists (select 1 from crm_auto_reply_record r"
                + "   where r.tenant_id=s.tenant_id and r.send_id=s.id)",
            currentTenant(),
            messageId);
    return row != null && row.get(0, Long.class) > 0;
  }

  // ================================================================ 值班闸

  /** 值班闸的判定结果。 */
  private record DutyDecision(boolean allowed, String reason) {}

  /**
   * 🔴 值班闸判定：节假日 / 非营业时段要不要自动回复。
   *
   * <p>命中口径：同一天里命中的条目按**范围从窄到宽**取一条（RULE &gt; PLATFORM &gt; GLOBAL）；
   * 同范围内取时段最窄的一条；时段没覆盖当前时刻的条目**不适用**。
   * 一条都不命中 ⇒ 回落规则上的默认口径 {@code unattended_auto_reply_allowed}。
   */
  private DutyDecision dutyDecision(
      UUID ruleId, String platformCode, OffsetDateTime at, Boolean defaultAllowed) {
    UUID tenant = currentTenant();
    var candidates =
        sql.fetch(
            "select id,scope,platform_code,rule_id,calendar_date,duty_mode,duty_start,duty_end,"
                + " auto_reply_allowed,timezone,holiday_name"
                + " from crm_auto_reply_duty_calendar"
                + " where tenant_id=? and calendar_date between (?::date - 2) and (?::date + 2)",
            tenant,
            at.toLocalDate(),
            at.toLocalDate());
    Record best = null;
    int bestPriority = -1;
    int bestWidth = Integer.MAX_VALUE;
    for (Record candidate : candidates) {
      String scope = nz(candidate.get("scope", String.class));
      if ("PLATFORM".equals(scope)) {
        if (!nz(candidate.get("platform_code", String.class)).equals(platformCode)) {
          continue;
        }
      } else if ("RULE".equals(scope)) {
        UUID scopedRule = candidate.get("rule_id", UUID.class);
        if (scopedRule == null || !scopedRule.equals(ruleId)) {
          continue;
        }
      }
      String timezone = nz(candidate.get("timezone", String.class));
      if (timezone.isEmpty()) {
        timezone = "UTC";
      }
      ZoneId zone;
      try {
        zone = ZoneId.of(timezone);
      } catch (RuntimeException e) {
        continue;
      }
      var local = at.atZoneSameInstant(zone);
      LocalDate localDate = local.toLocalDate();
      LocalDate calendarDate = candidate.get("calendar_date", LocalDate.class);
      if (calendarDate == null || !calendarDate.equals(localDate)) {
        continue;
      }
      LocalTime start = candidate.get("duty_start", LocalTime.class);
      LocalTime end = candidate.get("duty_end", LocalTime.class);
      int width = Integer.MAX_VALUE;
      if (start != null && end != null) {
        LocalTime current = local.toLocalTime();
        boolean inside =
            end.isAfter(start)
                ? (!current.isBefore(start) && current.isBefore(end))
                : (!current.isBefore(start) || current.isBefore(end));
        if (!inside) {
          continue;
        }
        width = (int) Math.abs(Duration.between(start, end).toMinutes());
      }
      int priority = SCOPE_PRIORITY.getOrDefault(scope, 0);
      if (priority > bestPriority
          || (priority == bestPriority && width < bestWidth)) {
        best = candidate;
        bestPriority = priority;
        bestWidth = width;
      }
    }
    if (best == null) {
      boolean allowed = defaultAllowed == null || defaultAllowed;
      return new DutyDecision(
          allowed,
          allowed
              ? "没有任何排班记录覆盖当前时刻，按规则的默认口径允许自动回复。"
              : "没有任何排班记录覆盖当前时刻，但规则的默认口径是**不允许**自动回复。");
    }
    String dutyMode = nz(best.get("duty_mode", String.class));
    boolean allowed = "CLOSED".equals(dutyMode) ? false : best.get("auto_reply_allowed", Boolean.class);
    String window =
        best.get("duty_start", LocalTime.class) == null
            ? "全天"
            : best.get("duty_start", LocalTime.class)
                + "–"
                + best.get("duty_end", LocalTime.class);
    String holiday = nz(best.get("holiday_name", String.class));
    String detail =
        dutyModeLabel(dutyMode)
            + "（"
            + window
            + (holiday.isEmpty() ? "" : " · " + holiday)
            + "，范围 "
            + scopeLabel(nz(best.get("scope", String.class)))
            + "）";
    return new DutyDecision(
        allowed,
        allowed
            ? "值班日历判定为" + detail + "，允许自动回复。"
            : "值班日历判定为" + detail + "，**不允许**自动回复。");
  }

  // ================================================================ 写入

  /** 写一条自动回复记录（只追加）。 */
  private CrmAutoReplyRecordRow writeRecord(
      UUID tenant,
      Record rule,
      Record message,
      UUID classificationId,
      String classificationLabel,
      String triggerReason,
      String outcome,
      String reason,
      UUID sendId,
      String sendNo,
      String bodySnapshot,
      boolean escalated,
      OffsetDateTime at) {
    UUID id = UUID.randomUUID();
    String recordNo = nextRecordNo();
    UUID templateUuid = rule.get("template_id", UUID.class);
    UUID knowledgeVersionId = rule.get("knowledge_version_id", UUID.class);
    String body = bodySnapshot == null ? nz(rule.get("body", String.class)) : bodySnapshot;
    sql.execute(
        INSERT_RECORD,
        id,
        tenant,
        recordNo,
        rule.get("id", UUID.class),
        nz(rule.get("rule_code", String.class)),
        nz(rule.get("rule_name", String.class)),
        nz(rule.get("ladder_code", String.class)),
        rule.get("level", Integer.class),
        nz(rule.get("trigger_type", String.class)),
        nz(triggerReason),
        message.get("id", UUID.class),
        nz(message.get("message_no", String.class)),
        message.get("shop_id", UUID.class),
        nz(message.get("shop_name", String.class)),
        nz(message.get("platform_code", String.class)),
        nz(message.get("buyer_reference", String.class)),
        nz(message.get("order_reference", String.class)),
        classificationId,
        nz(classificationLabel),
        templateUuid,
        nz(rule.get("template_code", String.class)),
        nz(rule.get("body", String.class)),
        knowledgeVersionId,
        knowledgeVersionNo(knowledgeVersionId),
        nz(body),
        outcome,
        nz(reason),
        sendId,
        nz(sendNo),
        escalated,
        at,
        at);
    return new CrmAutoReplyRecordRow(
        id,
        recordNo,
        rule.get("id", UUID.class),
        nz(rule.get("rule_code", String.class)),
        nz(rule.get("rule_name", String.class)),
        nz(rule.get("ladder_code", String.class)),
        rule.get("level", Integer.class),
        nz(rule.get("trigger_type", String.class)),
        triggerTypeLabel(nz(rule.get("trigger_type", String.class))),
        nz(triggerReason),
        message.get("id", UUID.class),
        nz(message.get("message_no", String.class)),
        message.get("shop_id", UUID.class),
        nz(message.get("shop_name", String.class)),
        nz(message.get("platform_code", String.class)),
        nz(message.get("buyer_reference", String.class)),
        nz(message.get("order_reference", String.class)),
        classificationId,
        nz(classificationLabel),
        templateUuid,
        nz(rule.get("template_code", String.class)),
        nz(rule.get("body", String.class)),
        knowledgeVersionId,
        knowledgeVersionNo(knowledgeVersionId),
        nz(body),
        outcome,
        outcomeLabel(outcome),
        nz(reason),
        sendId,
        nz(sendNo),
        "",
        "",
        null,
        escalated,
        at.toString(),
        at.toString());
  }

  /**
   * 追加一条转人工标记。
   *
   * <p>🔴 一条消息只转人工一次（DB 层 UNIQUE）：已经转过就返回 {@code null}
   * （<b>不</b>制造第二条待办），由调用方按「已有标记」处理。
   */
  private CrmAutoReplyEscalationRow escalate(
      UUID tenant,
      Record message,
      UUID recordId,
      UUID ruleId,
      String ruleCode,
      int level,
      String reasonCode,
      String reason,
      UUID actorId,
      OffsetDateTime at) {
    UUID messageId = message.get("id", UUID.class);
    var existing =
        sql.fetchOne(
            "select id from crm_auto_reply_escalation where tenant_id=? and message_id=?",
            tenant,
            messageId);
    if (existing != null) {
      return null;
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into crm_auto_reply_escalation"
            + "(id,tenant_id,message_id,message_no,shop_id,shop_name,platform_code,"
            + " buyer_reference,order_reference,record_id,rule_id,rule_code,level,reason_code,reason,"
            + " escalated_by,escalated_at,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        messageId,
        nz(message.get("message_no", String.class)),
        message.get("shop_id", UUID.class),
        nz(message.get("shop_name", String.class)),
        nz(message.get("platform_code", String.class)),
        nz(message.get("buyer_reference", String.class)),
        nz(message.get("order_reference", String.class)),
        recordId,
        ruleId,
        nz(ruleCode),
        level,
        reasonCode,
        reason,
        actorId,
        at,
        at);
    var row =
        sql.fetchOne(
            "select e.id,e.message_id,e.message_no,e.shop_id,e.shop_name,e.platform_code,"
                + " e.buyer_reference,e.order_reference,e.record_id,e.rule_id,e.rule_code,e.level,"
                + " e.reason_code,e.reason,e.escalated_by,e.escalated_at,e.created_at,"
                + " t.id as takeover_id"
                + " from crm_auto_reply_escalation e"
                + " left join crm_auto_reply_takeover t on t.tenant_id=e.tenant_id"
                + "   and t.shop_id=e.shop_id and t.buyer_reference=e.buyer_reference"
                + " where e.tenant_id=? and e.id=?",
            tenant,
        id);
    return escalationRow(row);
  }

  // ================================================================ 行映射

  private CrmAutoReplyRuleRow ruleRow(Record row) {
    String triggerType = nz(row.get("trigger_type", String.class));
    return new CrmAutoReplyRuleRow(
        row.get("id", UUID.class),
        nz(row.get("rule_code", String.class)),
        nz(row.get("rule_name", String.class)),
        nz(row.get("ladder_code", String.class)),
        row.get("level", Integer.class),
        Boolean.TRUE.equals(row.get("escalate_to_human", Boolean.class)),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        triggerType,
        triggerTypeLabel(triggerType),
        nz(row.get("classification_labels", String.class)),
        nz(row.get("keywords", String.class)),
        nz(row.get("match_mode", String.class)),
        nz(row.get("platform_codes", String.class)),
        row.get("human_timeout_minutes", Integer.class),
        row.get("level_wait_minutes", Integer.class),
        Boolean.TRUE.equals(row.get("unattended_auto_reply_allowed", Boolean.class)),
        row.get("template_id", UUID.class),
        nz(row.get("template_code", String.class)),
        row.get("knowledge_version_id", UUID.class),
        nz(row.get("subject", String.class)),
        nz(row.get("body", String.class)),
        nz(row.get("note", String.class)),
        row.get("created_by", UUID.class),
        str(row.get("created_at", OffsetDateTime.class)),
        str(row.get("updated_at", OffsetDateTime.class)));
  }

  private CrmAutoReplyDutyCalendarRow calendarRow(Record row) {
    String scope = nz(row.get("scope", String.class));
    String dutyMode = nz(row.get("duty_mode", String.class));
    LocalTime start = row.get("duty_start", LocalTime.class);
    LocalTime end = row.get("duty_end", LocalTime.class);
    LocalDate date = row.get("calendar_date", LocalDate.class);
    return new CrmAutoReplyDutyCalendarRow(
        row.get("id", UUID.class),
        scope,
        scopeLabel(scope),
        nz(row.get("platform_code", String.class)),
        row.get("rule_id", UUID.class),
        nz(row.get("rule_code", String.class)),
        date == null ? "" : date.toString(),
        dutyMode,
        dutyModeLabel(dutyMode),
        start == null ? null : start.toString(),
        end == null ? null : end.toString(),
        Boolean.TRUE.equals(row.get("auto_reply_allowed", Boolean.class)),
        nz(row.get("timezone", String.class)),
        nz(row.get("holiday_name", String.class)),
        nz(row.get("note", String.class)),
        row.get("created_by", UUID.class),
        str(row.get("created_at", OffsetDateTime.class)),
        str(row.get("updated_at", OffsetDateTime.class)));
  }

  private CrmAutoReplyRecordRow recordRow(Record row) {
    String triggerType = nz(row.get("trigger_type", String.class));
    String outcome = nz(row.get("outcome", String.class));
    UUID sendId = row.get("send_id", UUID.class);
    String sendStatus = "";
    String sendFailure = "";
    if (sendId != null) {
      sendStatus = nz(row.get("send_status", String.class));
      sendFailure = nz(row.get("send_failure_reason", String.class));
    }
    return new CrmAutoReplyRecordRow(
        row.get("id", UUID.class),
        nz(row.get("record_no", String.class)),
        row.get("rule_id", UUID.class),
        nz(row.get("rule_code", String.class)),
        nz(row.get("rule_name", String.class)),
        nz(row.get("ladder_code", String.class)),
        row.get("level", Integer.class),
        triggerType,
        triggerTypeLabel(triggerType),
        nz(row.get("trigger_reason", String.class)),
        row.get("message_id", UUID.class),
        nz(row.get("message_no", String.class)),
        row.get("shop_id", UUID.class),
        nz(row.get("shop_name", String.class)),
        nz(row.get("platform_code", String.class)),
        nz(row.get("buyer_reference", String.class)),
        nz(row.get("order_reference", String.class)),
        row.get("classification_id", UUID.class),
        nz(row.get("classification_label", String.class)),
        row.get("template_id", UUID.class),
        nz(row.get("template_code", String.class)),
        nz(row.get("template_body_snapshot", String.class)),
        row.get("knowledge_version_id", UUID.class),
        row.get("knowledge_version_no", Integer.class),
        nz(row.get("body_snapshot", String.class)),
        outcome,
        outcomeLabel(outcome),
        nz(row.get("reason", String.class)),
        sendId,
        nz(row.get("send_no", String.class)),
        sendStatus,
        sendFailure,
        row.get("origin_record_id", UUID.class),
        Boolean.TRUE.equals(row.get("escalated", Boolean.class)),
        str(row.get("occurred_at", OffsetDateTime.class)),
        str(row.get("created_at", OffsetDateTime.class)));
  }

  private CrmAutoReplyEscalationRow escalationRow(Record row) {
    String reasonCode = nz(row.get("reason_code", String.class));
    UUID takeoverId = row.get("takeover_id", UUID.class);
    return new CrmAutoReplyEscalationRow(
        row.get("id", UUID.class),
        row.get("message_id", UUID.class),
        nz(row.get("message_no", String.class)),
        row.get("shop_id", UUID.class),
        nz(row.get("shop_name", String.class)),
        nz(row.get("platform_code", String.class)),
        nz(row.get("buyer_reference", String.class)),
        nz(row.get("order_reference", String.class)),
        row.get("record_id", UUID.class),
        row.get("rule_id", UUID.class),
        nz(row.get("rule_code", String.class)),
        row.get("level", Integer.class),
        reasonCode,
        escalationReasonLabel(reasonCode),
        nz(row.get("reason", String.class)),
        row.get("escalated_by", UUID.class),
        str(row.get("escalated_at", OffsetDateTime.class)),
        takeoverId != null,
        takeoverId,
        str(row.get("created_at", OffsetDateTime.class)));
  }

  private CrmAutoReplyTakeoverRow takeoverRow(Record row) {
    UUID shopId = row.get("shop_id", UUID.class);
    String buyer = nz(row.get("buyer_reference", String.class));
    return new CrmAutoReplyTakeoverRow(
        row.get("id", UUID.class),
        shopId,
        nz(row.get("shop_name", String.class)),
        nz(row.get("platform_code", String.class)),
        buyer,
        shopId + " / " + buyer,
        row.get("message_id", UUID.class),
        row.get("record_id", UUID.class),
        row.get("escalation_id", UUID.class),
        row.get("taken_over_by", UUID.class),
        str(row.get("taken_over_at", OffsetDateTime.class)),
        nz(row.get("reason", String.class)),
        str(row.get("created_at", OffsetDateTime.class)));
  }

  private CrmAutoReplyTriggeredItem unmatched(Record rule, String reason) {
    return new CrmAutoReplyTriggeredItem(
        rule.get("id", UUID.class),
        nz(rule.get("rule_code", String.class)),
        nz(rule.get("rule_name", String.class)),
        nz(rule.get("ladder_code", String.class)),
        rule.get("level", Integer.class),
        nz(rule.get("trigger_type", String.class)),
        triggerTypeLabel(nz(rule.get("trigger_type", String.class))),
        false,
        false,
        "",
        "",
        reason,
        null,
        null,
        null,
        null,
        false,
        null);
  }

  // ================================================================ 规则的写入形状

  /** 规则入参校验后的形状。 */
  private record RuleShape(
      String ruleCode,
      String ruleName,
      String ladderCode,
      int level,
      boolean escalateToHuman,
      boolean enabled,
      String triggerType,
      String classificationLabels,
      String keywords,
      String matchMode,
      String platformCodes,
      int humanTimeoutMinutes,
      int levelWaitMinutes,
      boolean unattendedAutoReplyAllowed,
      UUID templateId,
      String templateCode,
      UUID knowledgeVersionId,
      String subject,
      String body,
      String note) {}

  /** 校验并归一规则入参（三选一触发条件 + 阶梯位置 + 超时口径）。 */
  private RuleShape readRuleShape(CrmAutoReplyRuleInput input, int fallbackLevel) {
    String triggerType = oneOf(TRIGGER_TYPES, input.triggerType(), "触发条件");
    int level = input.level() == null ? fallbackLevel : input.level();
    if (level < 1) {
      throw bad("阶梯级别必须从 1 开始。");
    }
    int humanTimeout = input.humanTimeoutMinutes() == null ? 0 : input.humanTimeoutMinutes();
    int levelWait = input.levelWaitMinutes() == null ? 0 : input.levelWaitMinutes();
    if (humanTimeout < 0 || levelWait < 0) {
      throw bad("超时口径不能是负数分钟。");
    }
    if ("HUMAN_TIMEOUT".equals(triggerType) && humanTimeout <= 0) {
      throw bad("触发条件是「无人工介入超时」时，必须给出大于 0 的超时分钟数。");
    }
    if (level > 1 && levelWait <= 0) {
      throw bad(
          "阶梯第 "
              + level
              + " 级必须给出大于 0 的最短间隔分钟数：间隔为 0 会让一次触发把整条阶梯一次走完。");
    }
    String labels = nz(input.classificationLabels()).trim();
    String keywords = nz(input.keywords()).trim();
    if ("CLASSIFICATION".equals(triggerType) && labels.isEmpty()) {
      throw bad("触发条件是「收到消息分类命中」时，必须给出 classificationLabels。");
    }
    if ("KEYWORD".equals(triggerType) && keywords.isEmpty()) {
      throw bad("触发条件是「关键词命中」时，必须给出 keywords。");
    }
    for (String label : splitCommas(labels)) {
      if (!CLASSIFICATION_LABELS.contains(label)) {
        throw bad(
            "分类标签「"
                + label
                + "」不在允许取值里："
                + String.join(" / ", CLASSIFICATION_LABELS)
                + "（标签来自上游 15.1.1.3）。");
      }
    }
    String templateCode = "";
    UUID templateId = input.templateId();
    if (templateId != null) {
      var template =
          sql.fetchOne(
              "select template_code from crm_message_send_template where tenant_id=? and id=?",
              currentTenant(),
              templateId);
      if (template == null) {
        throw notFound("回复模板不存在，或不属于当前租户（15.1.1.2 的模板）");
      }
      templateCode = nz(template.get("template_code", String.class));
    }
    if (templateId != null && input.knowledgeVersionId() != null) {
      throw bad("一次回复只能选一个话术来源：要么用回复模板，要么用知识库条目。");
    }
    return new RuleShape(
        nz(input.ruleCode()).trim(),
        nz(input.ruleName()).trim(),
        nz(input.ladderCode()).trim(),
        level,
        Boolean.TRUE.equals(input.escalateToHuman()),
        input.enabled() == null || input.enabled(),
        triggerType,
        labels,
        keywords,
        input.matchMode() == null || input.matchMode().isBlank()
            ? "ANY"
            : oneOf(List.of("ANY", "ALL"), input.matchMode(), "命中方式"),
        nz(input.platformCodes()).trim(),
        humanTimeout,
        levelWait,
        input.unattendedAutoReplyAllowed() == null || input.unattendedAutoReplyAllowed(),
        templateId,
        templateCode,
        input.knowledgeVersionId(),
        nz(input.subject()),
        nz(input.body()),
        nz(input.note()));
  }

  // ================================================================ 小工具

  private Record loadSetting() {
    return sql.fetchOne(
        "select id,auto_reply_enabled,closed_reason,closed_at,closed_by,updated_at"
            + " from crm_auto_reply_setting where tenant_id=?",
        currentTenant());
  }

  private int pendingSendRegisteredCount() {
    return sql.fetchOne(
            "select count(*) from crm_auto_reply_record r"
                + " join crm_message_send s on s.tenant_id=r.tenant_id and s.id=r.send_id"
                + " where r.tenant_id=? and r.outcome='SEND_REGISTERED' and s.status='QUEUED'",
            currentTenant())
        .get(0, Integer.class);
  }

  private Record takeoverOf(UUID shopId, String buyerReference) {
    if (shopId == null || buyerReference == null || buyerReference.isEmpty()) {
      return null;
    }
    return sql.fetchOne(
        "select id,shop_id,shop_name,platform_code,buyer_reference,message_id,record_id,"
            + " escalation_id,taken_over_by,taken_over_at,reason,created_at"
            + " from crm_auto_reply_takeover where tenant_id=? and shop_id=? and buyer_reference=?",
        currentTenant(),
        shopId,
        buyerReference);
  }

  private int knowledgeVersionNo(UUID knowledgeVersionId) {
    if (knowledgeVersionId == null) {
      return 0;
    }
    var row =
        sql.fetchOne(
            "select version_no from crm_knowledge_version where tenant_id=? and id=?",
            currentTenant(),
            knowledgeVersionId);
    return row == null ? 0 : row.get("version_no", Integer.class);
  }

  private String nextRecordNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_auto_reply_record_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "AR-" + day + "-" + String.format("%06d", seq);
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static String str(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String oneOf(List<String> allowed, String value, String label) {
    String candidate = nz(value).trim();
    if (!allowed.contains(candidate)) {
      throw bad(label + "「" + candidate + "」不在允许取值里：" + String.join(" / ", allowed));
    }
    return candidate;
  }

  private static List<String> splitCommas(String value) {
    List<String> out = new ArrayList<>();
    for (String part : nz(value).split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }

  private static List<String> splitLines(String value) {
    List<String> out = new ArrayList<>();
    for (String part : nz(value).split("\\r?\\n")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }

  private static LocalDate parseDate(String value) {
    String text = blankToNull(value);
    if (text == null) {
      return null;
    }
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException e) {
      throw bad("日期「" + text + "」无法解析，请用 ISO 日期（形如 2026-10-23）。");
    }
  }

  private static LocalTime parseTime(String value, String label) {
    String text = blankToNull(value);
    if (text == null) {
      return null;
    }
    try {
      return LocalTime.parse(text);
    } catch (DateTimeParseException e) {
      throw bad(label + "「" + text + "」无法解析，请用 HH:mm 形状的本地时刻。");
    }
  }

  private static OffsetDateTime parseInstant(String value, String label) {
    String text = blankToNull(value);
    if (text == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(text);
    } catch (DateTimeParseException e) {
      throw bad(label + "「" + text + "」无法解析，请用带时区的 ISO 时刻（形如 2026-10-23T10:00:00+08:00）。");
    }
  }

  private static int clampSize(int size) {
    return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
  }

  private static int pages(long total, int size) {
    return (int) (total == 0 ? 0 : (total + size - 1) / size);
  }

  private static Object[] bindings(List<Object> binds, Object... tail) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(tail));
    return all.toArray();
  }

  private static String triggerTypeLabel(String code) {
    return switch (code) {
      case "CLASSIFICATION" -> "收到消息分类命中";
      case "KEYWORD" -> "关键词命中";
      case "HUMAN_TIMEOUT" -> "无人工介入超时";
      default -> code;
    };
  }

  private static String outcomeLabel(String code) {
    return switch (code) {
      case "SEND_REGISTERED" -> "已登记发送";
      case "SUPPRESSED" -> "被闸门挡下（未发送）";
      case "CANCELLED" -> "已撤销（不得再发）";
      default -> code;
    };
  }

  private static String dutyModeLabel(String code) {
    return switch (code) {
      case "UNATTENDED" -> "无人值守";
      case "ATTENDED" -> "有人值班";
      case "CLOSED" -> "休息";
      default -> code;
    };
  }

  private static String scopeLabel(String code) {
    return switch (code) {
      case "GLOBAL" -> "整租户";
      case "PLATFORM" -> "按平台";
      case "RULE" -> "按规则";
      default -> code;
    };
  }

  private static String escalationReasonLabel(String code) {
    return switch (code) {
      case "LADDER_EXHAUSTED" -> "阶梯走完";
      case "DUTY_GATE" -> "值班闸挡下";
      case "SEND_FAILED" -> "发送登记失败";
      case "MANUAL_ESCALATION" -> "人工手动标记";
      default -> code;
    };
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  /** 失败原因（人话）：ResponseStatusException 取它的 reason，其它异常取 message。 */
  private static String reasonOf(RuntimeException e) {
    if (e instanceof ResponseStatusException status && status.getReason() != null) {
      return status.getReason();
    }
    String message = e.getMessage();
    return message == null || message.isBlank() ? "发送登记失败" : message;
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
