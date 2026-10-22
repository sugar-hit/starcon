package com.starcon.crm;

import static com.starcon.crm.EmailRuleModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleAssignmentPage;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleAssignmentRow;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleBucket;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleCapabilities;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleCapabilityDimension;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleConditionDetail;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleEvaluateBatchInput;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleEvaluateBatchResult;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleEvaluateInput;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleEvaluateResult;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleHitDetail;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleHitPage;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleHitRow;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleInput;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleKeywordCondition;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleMatch;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleMatchBasis;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleOptions;
import com.starcon.crm.EmailRuleModels.CrmEmailRulePage;
import com.starcon.crm.EmailRuleModels.CrmEmailRulePreviewResult;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleResult;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleRow;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleSenderCondition;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleSnapshot;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleStat;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleStatistics;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleTicketTagCondition;
import com.starcon.crm.EmailRuleModels.CrmEmailRuleCancelAssignmentInput;
import com.starcon.crm.EmailRuleModels.CrmEmailTagAssignmentInput;
import com.starcon.crm.EmailRuleModels.CrmEmailTagAssignmentRow;
import com.starcon.crm.EmailRuleModels.CrmEmailTicketTagInput;
import com.starcon.crm.EmailRuleModels.CrmEmailTicketTagPage;
import com.starcon.crm.EmailRuleModels.CrmEmailTicketTagResult;
import com.starcon.crm.EmailRuleModels.CrmEmailTicketTagRow;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 15.1.2.2 邮件规则：配置邮件自动处理规则（按发件人 / 关键词 / 工单标签），
 * 命中后自动分配 / 自动回复 / 静默；**关键沟通静默会造成平台违规**，规则需强制留痕。
 *
 * <p>蓝图 15 卷 15.1.2.2 原话：
 *
 * <blockquote>
 *
 * 职责：配置邮件自动处理规则：按发件人/关键词/工单标签自动分配、自动回复、静默规则（重要沟通不可静默）。 输入：规则配置；15.1.1.3 分类结果。 输出：规则命中记录；自动分配结果。 关系：上游 = 15.1.1.3；下游 = 15.4.1.1 工单分配。 <b>关键沟通静默会造成平台违规</b>，规则需强制留痕。
 *
 * </blockquote>
 *
 * <p><b>加粗句「关键沟通静默会造成平台违规，规则需强制留痕」在本服务落成四件事</b>：
 *
 * <ol>
 *   <li><b>静默被服务端强制降级</b>：命中记录同时存 {@code action}（规则声明的）与
 *       {@code effectiveAction}（实际生效的）。命中<b>重要沟通</b>而规则要求静默时，
 *       实际生效的动作只能是 {@code ESCALATE_MANUAL}（不静默 + 转人工），
 *       并且 {@code silenceRefused=true} + 写明理由 —— <b>不靠前端约定</b>，
 *       DB 层 CHECK 也钉死了这两者的配套关系。</li>
 *   <li><b>重要沟通有可判定的标识</b>：① 上游 15.1.1.3 分类结果的关键类别
 *       （平台合规 / 投诉）；② 规则自己声明的 {@code importantMarkers} 标记。
 *       两者任一命中即判为重要沟通，判定结果与来源都<b>留痕</b>在命中记录上。</li>
 *   <li><b>命中记录只追加</b>：DB 触发器拒绝 UPDATE / DELETE ——
 *       改得掉的命中记录等于「当时静默过这封重要邮件」这句话可以随时被抹掉。</li>
 *   <li><b>命中凭据冻结当时的规则</b>：命中记录带 {@code ruleVersion} +
 *       {@code ruleSnapshot} + {@code matchBasis}（命中条件明细），
 *       ⇒ <b>改规则不追溯改变历史命中记录</b>。</li>
 * </ol>
 *
 * <p><b>下游未交付</b>：15.4.1.1 工单分配与 15.5.1 自动回复在本车道基线里都<b>没有</b>
 * 可调的授权 API ⇒ 本模块只产出<b>自动分配结果</b>（分给谁、依据哪条规则、命中了哪些条件），
 * <b>不创建工单、不写工单 id</b>；自动回复只<b>留痕意图</b>，
 * {@code deliveryStatus} 恒为 {@code PENDING_DOWNSTREAM}，<b>不谎称已发出</b>。
 *
 * <p><b>跨服务纪律</b>：上游 {@code crm_buyer_email}（15.1.2.1）与
 * {@code crm_message_classification}（15.1.1.3）都在<b>本服务本库</b>，
 * 所以直接 jOOQ 查，<b>只读</b>；本服务<b>一条 SQL 都不碰</b>别的服务的库。
 */
@Service
public class EmailRuleService {

  // ================================================================ 常量

  /** 实际生效的动作：静默被拒时的降级动作（不静默 + 转人工）。 */
  static final String ESCALATE_MANUAL = "ESCALATE_MANUAL";

  /** 规则声明的动作：静默。 */
  static final String SILENCE = "SILENCE";

  /** 自动分配。 */
  static final String AUTO_ASSIGN = "AUTO_ASSIGN";

  /** 自动回复。 */
  static final String AUTO_REPLY = "AUTO_REPLY";

  /** 自动回复的投递状态：只有 15.5.1 自动回复交付后才可能变别的值。 */
  static final String PENDING_DOWNSTREAM = "PENDING_DOWNSTREAM";

  /** 列表分页上限（真分页的上限，防止一次拉爆）。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 规则快照的 jsonb 列名（写回读一致）。 */
  private static final String HIT_FROM =
      " from crm_email_rule_hit h"
          + " join crm_buyer_email e on e.tenant_id = h.tenant_id and e.id = h.email_id"
          + " left join lateral ("
          + "   select a.id as assignment_id from crm_email_rule_assignment a"
          + "   where a.tenant_id = h.tenant_id and a.hit_id = h.id"
          + " ) asg on true";

  private final DSLContext sql;
  private final ObjectMapper json;

  public EmailRuleService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 两维刻意是 {@code available=false}：
   *
   * <ul>
   *   <li>{@code ticket_assignment} —— 15.4.1.1 工单分配**未交付**。本模块只产出
   *       <b>分配结果</b>，<b>不创建工单、不写工单 id</b>；</li>
   *   <li>{@code auto_reply_delivery} —— 15.5.1 自动回复**未交付**。本模块只留痕意图，
   *       <b>不真的发出</b>。</li>
   * </ul>
   */
  public CrmEmailRuleCapabilities capabilities() {
    List<CrmEmailRuleCapabilityDimension> dimensions =
        List.of(
            new CrmEmailRuleCapabilityDimension(
                "rule_configuration",
                "邮件规则可配置（发件人 / 关键词 / 工单标签 + 顺序 + 启用 + 版本）",
                true,
                "本服务 V305 crm_email_rule + POST/PUT/DELETE /rules",
                "15.1.2.2",
                "规则**真的落库**：三个条件维度都能配，顺序显式，"
                    + "命中依据里能逐条看到「命中了哪个维度、命中了哪些值」"),
            new CrmEmailRuleCapabilityDimension(
                "ticket_tag_dimension",
                "工单标签维度（规则的第三个条件维度）",
                true,
                "本服务 V305 crm_email_ticket_tag + crm_email_ticket_tag_assignment",
                "15.1.2.2",
                "标签先落在**邮件**上（15.4.1.1 工单未交付）："
                    + "规则条件引用的标签码必须在字典里真实存在，"
                    + "否则那是一条永远不会命中的规则"),
            new CrmEmailRuleCapabilityDimension(
                "rule_evaluation",
                "规则评估（按顺序评估、第一条命中即生效）",
                true,
                "本服务 POST /emails/{emailId}/evaluate + POST /evaluate-batch",
                "15.1.2.2",
                "评估**真的写命中记录**（只追加）：命中规则、命中条件、"
                    + "当时的规则版本与快照、动作结果四件事都在一条记录里"),
            new CrmEmailRuleCapabilityDimension(
                "silence_guard",
                "🔴 重要沟通不可静默（静默被强制降级为不静默 + 转人工）",
                true,
                "本服务评估逻辑 + V305 CHECK 约束",
                "15.1.2.2",
                "判定依据可查：分类结果的关键类别（平台合规 / 投诉）或规则自己标记的关键词；"
                    + "被拒时 effectiveAction=ESCALATE_MANUAL + silenceRefused=true + 理由留痕 —— "
                    + "**不靠前端约定**，DB 层也钉死了配套关系"),
            new CrmEmailRuleCapabilityDimension(
                "auto_assignment_result",
                "自动分配结果（分给谁 / 依据哪条规则 / 命中了哪些条件）",
                true,
                "本服务 V305 crm_email_rule_assignment + GET /assignments",
                "15.1.2.2",
                "分配结果是**本模块自己的产出**（蓝图输出句的第二项）；"
                    + "状态恒为 PENDING_DISPATCH（等下游来取）"),
            new CrmEmailRuleCapabilityDimension(
                "ticket_assignment",
                "工单分配（15.4.1.1）",
                false,
                "15.4.1.1 工单分配在本车道基线里**不可调用**",
                "15.4.1.1",
                "本模块只产出**分配结果**，**不创建工单、不写工单 id** —— "
                    + "编一个工单 id 会让「已经转工单了」看起来发生过"),
            new CrmEmailRuleCapabilityDimension(
                "auto_reply_delivery",
                "自动回复的真正投递（15.5.1）",
                false,
                "15.5.1 自动回复在本车道基线里**不可调用**",
                "15.5.1",
                "本模块只**判定并留痕**自动回复意图（模板码 + 标题 / 正文快照），"
                    + "deliveryStatus 恒为 PENDING_DOWNSTREAM，**不谎称已发出**"),
            new CrmEmailRuleCapabilityDimension(
                "auto_evaluation_on_receive",
                "收到邮件即自动评估",
                false,
                "15.1.2.1 邮件收发的同步 / 登记流程**没有**调用本模块的评估入口",
                "15.1.2.1",
                "评估由调用方显式触发（POST /emails/{emailId}/evaluate）；"
                    + "本模块**不**去改上游代码，也**不**假装「邮件一进来就自动评估过了」"),
            new CrmEmailRuleCapabilityDimension(
                "classification_source",
                "重要沟通的分类来源（15.1.1.3）",
                true,
                "本服务 V301 crm_message_classification（同库同服务，只读）",
                "15.1.1.3",
                "邮件通过 source_message_id 关联到站内信，再关联到分类结论；"
                    + "平台合规 / 投诉两类判为重要沟通 —— **只读，不造平行分类表**"));
    return new CrmEmailRuleCapabilities(
        dimensions,
        ACTIONS,
        EFFECTIVE_ACTIONS,
        ASSIGN_TARGET_TYPES,
        MATCH_MODES,
        IMPORTANT_LABELS,
        "上游 15.1.2.1 邮件收发与 15.1.1.3 消息分类在**本服务本库**（同库同服务，直接 jOOQ 查，只读）；"
            + "下游 15.4.1.1 工单分配与 15.5.1 自动回复**均未交付**，"
            + "本模块只产出**分配结果**与**自动回复意图**，不创建工单、不发送回复。",
        "🔴 关键沟通静默会造成平台违规：命中重要沟通的静默规则时，"
            + "实际生效的动作被**强制降级**为「不静默 + 转人工」，并写明被拒理由 —— "
            + "命中记录只追加，改不掉。");
  }

  // ================================================================ 词表

  /** 筛选词表（动作与命中方式是 DB CHECK 允许的取值域；标签码来自本租户真实数据）。 */
  public CrmEmailRuleOptions options() {
    List<String> tagCodes =
        sql.fetch(
                "select tag_code from crm_email_ticket_tag where tenant_id = ? and status = 'ACTIVE'"
                    + " order by tag_code",
                currentTenant())
            .map(r -> r.get(0, String.class));
    return new CrmEmailRuleOptions(
        ACTIONS,
        EFFECTIVE_ACTIONS,
        ASSIGN_TARGET_TYPES,
        MATCH_MODES,
        IMPORTANT_LABELS,
        tagCodes,
        "动作 / 命中方式 / 重要类别是 DB 层允许的取值域；标签码来自本租户**真实数据**（ACTIVE 的字典项）。");
  }

  // ================================================================ 规则

  /** 规则列表：真分页 + 真筛选。 */
  public CrmEmailRulePage rules(String keyword, String action, Boolean enabled, int page, int size) {
    int effectiveSize = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    int effectivePage = Math.max(page, 0);
    Filter filter = ruleFilter(keyword, action, enabled);
    var rows =
        sql.fetch(
            "select r.id,r.rule_code,r.rule_name,r.priority_order,r.enabled,r.action,"
                + " r.sender_emails,r.sender_domains,r.keywords,r.ticket_tag_codes,r.match_mode,"
                + " r.assign_target_type,r.assign_target_code,r.reply_template_code,r.reply_subject,"
                + " r.reply_body,r.important_markers,r.version,r.note,r.created_at,r.updated_at"
                + " from crm_email_rule r"
                + filter.where()
                + " order by r.priority_order, r.rule_code"
                + " limit ? offset ?",
            bindings(filter.binds(), effectiveSize, effectivePage * effectiveSize));
    long total =
        sql.fetchOne("select count(*) from crm_email_rule r" + filter.where(), filter.binds().toArray())
            .get(0, Long.class);
    return new CrmEmailRulePage(rows.map(EmailRuleService::rule), effectivePage, effectiveSize, total);
  }

  /** 单条规则。 */
  public CrmEmailRuleRow requireRule(UUID ruleId) {
    var row =
        sql.fetchOne(
            "select r.id,r.rule_code,r.rule_name,r.priority_order,r.enabled,r.action,"
                + " r.sender_emails,r.sender_domains,r.keywords,r.ticket_tag_codes,r.match_mode,"
                + " r.assign_target_type,r.assign_target_code,r.reply_template_code,r.reply_subject,"
                + " r.reply_body,r.important_markers,r.version,r.note,r.created_at,r.updated_at"
                + " from crm_email_rule r where r.tenant_id=? and r.id=?",
            currentTenant(),
            ruleId);
    if (row == null) {
      throw notFound("规则不存在，或不属于当前租户");
    }
    return rule(row);
  }

  /** 新建一条规则。 */
  @Transactional
  public CrmEmailRuleResult createRule(CrmEmailRuleInput input, UUID actorId) {
    String code = nz(input.ruleCode()).trim();
    if (code.isEmpty()) {
      throw bad("规则编码不能为空");
    }
    if (ruleExists(code)) {
      throw bad("规则编码「" + code + "」已存在：编码是运营引用规则的唯一名字，不能重名。");
    }
    RuleSpec spec = specOf(input, code, 1);
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_email_rule"
            + "(id,tenant_id,rule_code,rule_name,priority_order,enabled,action,"
            + " sender_emails,sender_domains,keywords,ticket_tag_codes,match_mode,"
            + " assign_target_type,assign_target_code,reply_template_code,reply_subject,reply_body,"
            + " important_markers,version,note,created_by,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        code,
        nz(input.ruleName()).trim(),
        input.priorityOrder() == null ? 100 : input.priorityOrder(),
        input.enabled() == null || input.enabled(),
        oneOf(ACTIONS, input.action(), "命中动作"),
        String.join("\n", spec.senderEmails()),
        String.join(",", spec.senderDomains()),
        String.join("\n", spec.keywords()),
        String.join(",", spec.ticketTagCodes()),
        spec.matchMode(),
        spec.assignTargetType(),
        spec.assignTargetCode(),
        spec.replyTemplateCode(),
        spec.replySubject(),
        spec.replyBody(),
        String.join("\n", spec.importantMarkers()),
        1,
        nz(input.note()),
        actorId,
        moment,
        moment);
    return new CrmEmailRuleResult(
        requireRule(id),
        "规则已创建。评估时按顺序（数字小的先）逐条匹配，命中第一条即生效；"
            + "命中记录会冻结当时的规则版本与内容 —— 之后改规则**不会**追溯改变已有的命中记录。");
  }

  /** 修改一条规则（编码不可改；版本 +1）。 */
  @Transactional
  public CrmEmailRuleResult updateRule(UUID ruleId, CrmEmailRuleInput input, UUID actorId) {
    CrmEmailRuleRow existing = requireRule(ruleId);
    String code = nz(input.ruleCode()).trim();
    if (!code.isEmpty() && !code.equals(existing.ruleCode())) {
      throw bad(
          "规则编码建后不可改：历史命中记录里存的是编码「"
              + existing.ruleCode()
              + "」，改了它历史记录就对不上。请新建一条规则再停用旧的。");
    }
    RuleSpec spec = specOf(input, existing.ruleCode(), existing.version() + 1);
    OffsetDateTime moment = now();
    sql.execute(
        "update crm_email_rule"
            + " set rule_name=?, priority_order=?, enabled=?, action=?, sender_emails=?,"
            + " sender_domains=?, keywords=?, ticket_tag_codes=?, match_mode=?,"
            + " assign_target_type=?, assign_target_code=?, reply_template_code=?, reply_subject=?,"
            + " reply_body=?, important_markers=?, version=?, note=?, updated_at=?::timestamptz"
            + " where tenant_id=? and id=?",
        nz(input.ruleName()).trim(),
        input.priorityOrder() == null ? 100 : input.priorityOrder(),
        input.enabled() == null || input.enabled(),
        oneOf(ACTIONS, input.action(), "命中动作"),
        String.join("\n", spec.senderEmails()),
        String.join(",", spec.senderDomains()),
        String.join("\n", spec.keywords()),
        String.join(",", spec.ticketTagCodes()),
        spec.matchMode(),
        spec.assignTargetType(),
        spec.assignTargetCode(),
        spec.replyTemplateCode(),
        spec.replySubject(),
        spec.replyBody(),
        String.join("\n", spec.importantMarkers()),
        existing.version() + 1,
        nz(input.note()),
        moment,
        currentTenant(),
        ruleId);
    return new CrmEmailRuleResult(
        requireRule(ruleId),
        "规则已更新（版本 "
            + (existing.version() + 1)
            + "）。**已产生的命中记录不会被改写** —— 它们冻结的是当时那一版规则。");
  }

  /**
   * 删除一条规则。
   *
   * <p>⚠️ 只删**规则定义**，不动已产生的命中记录与分配结果 ——
   * 命中记录自带规则编码、名称、版本与快照，删了规则也查得到「当时命中的是哪一版」。
   */
  @Transactional
  public CrmEmailRuleResult deleteRule(UUID ruleId) {
    CrmEmailRuleRow rule = requireRule(ruleId);
    sql.execute("delete from crm_email_rule where tenant_id=? and id=?", currentTenant(), ruleId);
    return new CrmEmailRuleResult(
        rule,
        "规则已删除。已产生的命中记录与自动分配结果**不受影响**"
            + "（命中记录自带规则编码、名称、版本与快照）。");
  }

  // ================================================================ 工单标签

  /** 标签列表：真分页 + 真筛选。 */
  public CrmEmailTicketTagPage tags(String keyword, String status, int page, int size) {
    int effectiveSize = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    int effectivePage = Math.max(page, 0);
    Filter filter = tagFilter(keyword, status);
    var rows =
        sql.fetch(
            "select t.id,t.tag_code,t.name,t.status,t.description,t.created_at,t.updated_at,"
                + " coalesce(a.cnt,0) as usage_count"
                + " from crm_email_ticket_tag t"
                + " left join ("
                + "   select tag_id, count(*) as cnt from crm_email_ticket_tag_assignment"
                + "   where tenant_id = ? group by tag_id"
                + " ) a on a.tag_id = t.id"
                + filter.where()
                + " order by t.tag_code"
                + " limit ? offset ?",
            tagBindings(filter.binds(), effectiveSize, effectivePage * effectiveSize));
    long total =
        sql.fetchOne(
                "select count(*) from crm_email_ticket_tag t" + filter.where(),
                filter.binds().toArray())
            .get(0, Long.class);
    return new CrmEmailTicketTagPage(
        rows.map(EmailRuleService::tag), effectivePage, effectiveSize, total);
  }

  /** 新建一个工单标签。 */
  @Transactional
  public CrmEmailTicketTagResult createTag(CrmEmailTicketTagInput input, UUID actorId) {
    String code = nz(input.tagCode()).trim();
    if (code.isEmpty()) {
      throw bad("标签码不能为空");
    }
    if (tagExists(code)) {
      throw bad("标签码「" + code + "」已存在：标签码是规则条件引用的名字，不能重名。");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_email_ticket_tag"
            + "(id,tenant_id,tag_code,name,status,description,created_by,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        code,
        nz(input.name()).trim(),
        oneOf(List.of("ACTIVE", "DISABLED"), input.status() == null ? "ACTIVE" : input.status(), "标签状态"),
        nz(input.description()),
        actorId,
        moment,
        moment);
    return new CrmEmailTicketTagResult(
        requireTag(id),
        "标签已创建。规则条件可以引用它了 —— 引用不存在的标签码会**当场被拒**（那是一条永远不会命中的规则）。");
  }

  /** 修改一个工单标签（标签码建后不可改）。 */
  @Transactional
  public CrmEmailTicketTagResult updateTag(UUID tagId, CrmEmailTicketTagInput input, UUID actorId) {
    CrmEmailTicketTagRow existing = requireTag(tagId);
    String code = nz(input.tagCode()).trim();
    if (!code.isEmpty() && !code.equals(existing.tagCode())) {
      throw bad(
          "标签码建后不可改：打标记录与规则条件里存的是编码「"
              + existing.tagCode()
              + "」，改了历史记录就对不上。请新建一个标签再停用旧的。");
    }
    OffsetDateTime moment = now();
    sql.execute(
        "update crm_email_ticket_tag"
            + " set name=?, status=?, description=?, updated_at=?::timestamptz"
            + " where tenant_id=? and id=?",
        nz(input.name()).trim(),
        oneOf(List.of("ACTIVE", "DISABLED"), input.status() == null ? "ACTIVE" : input.status(), "标签状态"),
        nz(input.description()),
        moment,
        currentTenant(),
        tagId);
    return new CrmEmailTicketTagResult(requireTag(tagId), "标签已更新。");
  }

  /**
   * 删除一个工单标签。
   *
   * <p>⚠️ 被打标记录或规则条件引用过的标签**删不掉**（DB 触发器拒绝）：
   * 删了它，那些规则条件就永远命中不了，历史打标记录也对不上。
   */
  @Transactional
  public CrmEmailTicketTagResult deleteTag(UUID tagId) {
    CrmEmailTicketTagRow tag = requireTag(tagId);
    sql.execute("delete from crm_email_ticket_tag where tenant_id=? and id=?", currentTenant(), tagId);
    return new CrmEmailTicketTagResult(tag, "标签已删除。");
  }

  /** 单个标签。 */
  public CrmEmailTicketTagRow requireTag(UUID tagId) {
    var row =
        sql.fetchOne(
            "select t.id,t.tag_code,t.name,t.status,t.description,t.created_at,t.updated_at,"
                + " (select count(*) from crm_email_ticket_tag_assignment a"
                + "   where a.tenant_id = t.tenant_id and a.tag_id = t.id) as usage_count"
                + " from crm_email_ticket_tag t where t.tenant_id=? and t.id=?",
            currentTenant(),
            tagId);
    if (row == null) {
      throw notFound("工单标签不存在，或不属于当前租户");
    }
    return tag(row);
  }

  /** 某封邮件当前挂的工单标签。 */
  public List<CrmEmailTagAssignmentRow> tagsOf(UUID emailId) {
    List<CrmEmailTagAssignmentRow> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select a.id,a.email_id,a.tag_id,a.tag_code,a.source,a.assigned_by,a.assigned_at,"
                + " a.created_at,t.name as tag_name"
                + " from crm_email_ticket_tag_assignment a"
                + " join crm_email_ticket_tag t on t.tenant_id = a.tenant_id and t.id = a.tag_id"
                + " where a.tenant_id=? and a.email_id=? order by a.tag_code",
            currentTenant(),
            emailId)) {
      out.add(
          new CrmEmailTagAssignmentRow(
              r.get("id", UUID.class),
              r.get("email_id", UUID.class),
              r.get("tag_id", UUID.class),
              nz(r.get("tag_code", String.class)),
              nz(r.get("tag_name", String.class)),
              nz(r.get("source", String.class)),
              r.get("assigned_by", UUID.class),
              str(r.get("assigned_at", OffsetDateTime.class)),
              str(r.get("created_at", OffsetDateTime.class))));
    }
    return out;
  }

  /** 给一封邮件打上工单标签（幂等：同一标签重复打不重复记账）。 */
  @Transactional
  public CrmEmailTagAssignmentRow assignTag(
      UUID emailId, CrmEmailTagAssignmentInput input, UUID actorId) {
    if (requireEmail(emailId) == null) {
      throw notFound("邮件不存在，或不属于当前租户");
    }
    String code = nz(input.tagCode()).trim();
    var tag =
        sql.fetchOne(
            "select id, tag_code, name from crm_email_ticket_tag where tenant_id=? and tag_code=?",
            currentTenant(),
            code);
    if (tag == null) {
      throw bad("工单标签「" + code + "」不存在：先建标签再打标（引用不存在的标签等于一条永远不会命中的规则）。");
    }
    UUID tagId = tag.get("id", UUID.class);
    String source = oneOf(List.of("MANUAL", "RULE"), input.source() == null ? "MANUAL" : input.source(), "打标来源");
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_email_ticket_tag_assignment"
            + "(id,tenant_id,email_id,tag_id,tag_code,source,assigned_by,assigned_at,created_at)"
            + " values (?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id, email_id, tag_code) do nothing",
        UUID.randomUUID(),
        currentTenant(),
        emailId,
        tagId,
        code,
        source,
        actorId,
        moment,
        moment);
    return tagsOf(emailId).stream()
        .filter(t -> t.tagCode().equals(code))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("打标写入后读不回来"));
  }

  // ================================================================ 评估

  /** 评估一封邮件（触发一次规则评估）。 */
  @Transactional
  public CrmEmailRuleEvaluateResult evaluate(CrmEmailRuleEvaluateInput input, UUID actorId) {
    return evaluateOne(input.emailId(), input.ruleId(), actorId);
  }

  /** 批量评估：逐条给出结论与原因，失败的条目不会被悄悄跳过。 */
  @Transactional
  public CrmEmailRuleEvaluateBatchResult evaluateBatch(
      CrmEmailRuleEvaluateBatchInput input, UUID actorId) {
    List<CrmEmailRuleEvaluateResult> items = new ArrayList<>();
    int succeeded = 0;
    int matched = 0;
    int refused = 0;
    for (UUID emailId : input.emailIds()) {
      CrmEmailRuleEvaluateResult item = evaluateOne(emailId, null, actorId);
      if (item.success()) {
        succeeded++;
      }
      if (Boolean.TRUE.equals(item.matched())) {
        matched++;
      }
      if (Boolean.TRUE.equals(item.silenceRefused())) {
        refused++;
      }
      items.add(item);
    }
    return new CrmEmailRuleEvaluateBatchResult(
        input.emailIds().size(),
        succeeded,
        input.emailIds().size() - succeeded,
        matched,
        refused,
        items,
        "评估**只写命中记录**（只追加），不改邮件本身；"
            + "自动分配只产出**分配结果**（15.4.1.1 工单未交付 ⇒ 不创建工单），"
            + "自动回复只留痕意图（15.5.1 未交付 ⇒ 不真的发出）。");
  }

  /** 单条评估的执行体。 */
  private CrmEmailRuleEvaluateResult evaluateOne(UUID emailId, UUID onlyRuleId, UUID actorId) {
    var email = requireEmail(emailId);
    if (email == null) {
      return failed(emailId, "", "邮件不存在，或不属于当前租户");
    }
    String emailNo = nz(email.get("email_no", String.class));
    String direction = nz(email.get("direction", String.class));
    if (!"INBOUND".equals(direction)) {
      return failed(
          emailId,
          emailNo,
          "规则只对**收到的**邮件生效：这封是发出去的邮件（" + direction + "），评估它没有意义。");
    }
    String senderEmail = nz(email.get("buyer_email", String.class));
    String subject = nz(email.get("subject", String.class));
    String body = nz(email.get("body", String.class));

    List<String> emailTags = tagCodesOf(emailId);
    Classification classification = classificationOf(email);

    // 规则集：**只取启用的**，按 (顺序, 编码) 走 —— 顺序显式，命中结果可复现。
    List<RuleSpec> rules = enabledRules();
    if (onlyRuleId != null) {
      var only =
          sql.fetchOne(
              "select r.id,r.rule_code,r.rule_name,r.priority_order,r.enabled,r.action,"
                  + " r.sender_emails,r.sender_domains,r.keywords,r.ticket_tag_codes,r.match_mode,"
                  + " r.assign_target_type,r.assign_target_code,r.reply_template_code,r.reply_subject,"
                  + " r.reply_body,r.important_markers,r.version"
                  + " from crm_email_rule r where r.tenant_id=? and r.id=?",
              currentTenant(),
              onlyRuleId);
      if (only == null) {
        return failed(emailId, emailNo, "规则不存在，或不属于当前租户");
      }
      RuleSpec spec = toSpec(only);
      if (!spec.enabled()) {
        return failed(
            emailId, emailNo, "规则「" + spec.ruleCode() + "」已停用，不参与命中。");
      }
      rules = List.of(spec);
    }

    List<CrmEmailRuleMatch> matches = new ArrayList<>();
    RuleSpec winner = null;
    CrmEmailRuleConditionDetail winnerConditions = null;
    for (RuleSpec rule : rules) {
      CrmEmailRuleConditionDetail conditions =
          matchConditions(rule, senderEmail, subject, body, emailTags);
      if (!conditionsMatched(conditions)) {
        continue;
      }
      boolean won = winner == null;
      if (won) {
        winner = rule;
        winnerConditions = conditions;
      }
      matches.add(
          new CrmEmailRuleMatch(
              rule.id(),
              rule.ruleCode(),
              rule.ruleName(),
              rule.priorityOrder(),
              rule.version(),
              rule.action(),
              won,
              conditions));
    }

    if (winner == null) {
      return new CrmEmailRuleEvaluateResult(
          emailId,
          emailNo,
          true,
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
          false,
          "没有规则命中：邮件保持原样（**不静默、不分配、不回复**）。");
    }

    // 🔴 重要沟通判定（两个可判定来源，任一命中即算）。
    boolean important = false;
    String importantSource = "";
    if (classification != null && IMPORTANT_LABELS.contains(classification.label())) {
      important = true;
      importantSource = "CLASSIFICATION";
    } else {
      List<String> markers = matchedMarkers(winner, subject, body);
      if (!markers.isEmpty()) {
        important = true;
        importantSource = "RULE_MARKER";
      }
    }

    // 🔴 静默降级：重要沟通命中静默规则 ⇒ 不静默 + 转人工（服务端强制，不靠前端约定）。
    String action = winner.action();
    String effectiveAction = action;
    boolean silenceRefused = false;
    String refusalReason = "";
    if (SILENCE.equals(action) && important) {
      effectiveAction = ESCALATE_MANUAL;
      silenceRefused = true;
      refusalReason =
          "重要沟通不可静默：静默重要沟通会造成平台违规 —— 已降级为「不静默 + 转人工」。"
              + "判定来源："
              + ("CLASSIFICATION".equals(importantSource)
                  ? "分类结果「" + classification.label() + "」（上游 15.1.1.3）"
                  : "规则标记的关键词");
    }

    OffsetDateTime moment = now();
    String basis = writeBasis(winner, matches);

    // 幂等：同一封邮件 + 同一条规则的**同一版本**只记一次。
    var existing =
        sql.fetchOne(
            "select id, hit_no from crm_email_rule_hit"
                + " where tenant_id=? and email_id=? and rule_id=? and rule_version=?",
            currentTenant(),
            emailId,
            winner.id(),
            winner.version());
    if (existing != null) {
      return new CrmEmailRuleEvaluateResult(
          emailId,
          emailNo,
          true,
          true,
          existing.get("id", UUID.class),
          nz(existing.get("hit_no", String.class)),
          winner.ruleCode(),
          action,
          effectiveAction,
          silenceRefused,
          refusalReason,
          important,
          importantSource,
          classification == null ? null : classification.label(),
          null,
          null,
          true,
          "这一版规则已经命中过这封邮件（命中记录只追加，不重复记账）。");
    }

    UUID hitId = UUID.randomUUID();
    String hitNo = nextHitNo();
    String deliveryStatus = AUTO_REPLY.equals(effectiveAction) ? PENDING_DOWNSTREAM : null;
    sql.execute(
        "insert into crm_email_rule_hit"
            + "(id,tenant_id,hit_no,rule_id,rule_code,rule_name,rule_version,rule_snapshot,"
            + " email_id,email_no,buyer_email,subject,match_basis,action,effective_action,"
            + " silence_refused,refusal_reason,important,important_source,classification_id,"
            + " classification_label,delivery_status,matched_at,created_at)"
            + " values (?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        hitId,
        currentTenant(),
        hitNo,
        winner.id(),
        winner.ruleCode(),
        winner.ruleName(),
        winner.version(),
        writeSnapshot(winner),
        emailId,
        emailNo,
        senderEmail,
        subject,
        basis,
        action,
        effectiveAction,
        silenceRefused,
        refusalReason,
        important,
        importantSource,
        classification == null ? null : classification.id(),
        classification == null ? "" : classification.label(),
        deliveryStatus,
        moment,
        moment);

    // 自动分配结果：只有真的生效为自动分配时才产出（静默被降级后不产出）。
    UUID assignmentId = null;
    String assignTargetCode = null;
    if (AUTO_ASSIGN.equals(effectiveAction)) {
      UUID assignmentIdNew = UUID.randomUUID();
      String assignmentNo = nextAssignmentNo();
      sql.execute(
          "insert into crm_email_rule_assignment"
              + "(id,tenant_id,assignment_no,hit_id,rule_id,rule_code,rule_version,"
              + " email_id,email_no,buyer_email,subject,assign_target_type,assign_target_code,"
              + " status,dispatch_note,assigned_at,created_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          assignmentIdNew,
          currentTenant(),
          assignmentNo,
          hitId,
          winner.id(),
          winner.ruleCode(),
          winner.version(),
          emailId,
          emailNo,
          senderEmail,
          subject,
          winner.assignTargetType(),
          winner.assignTargetCode(),
          "PENDING_DISPATCH",
          "等下游 15.4.1.1 工单分配来取；本模块**不创建工单、不写工单 id**。",
          moment,
          moment);
      // ⚠️ **不**回填命中记录上的 assignment_id：命中记录只追加（DB 触发器拒绝 UPDATE），
      //   「这条命中产出了哪条分配结果」改成**反查**分配结果表（hit_id 单向引用）。
      assignmentId = assignmentIdNew;
      assignTargetCode = winner.assignTargetCode();
    }

    return new CrmEmailRuleEvaluateResult(
        emailId,
        emailNo,
        true,
        true,
        hitId,
        hitNo,
        winner.ruleCode(),
        action,
        effectiveAction,
        silenceRefused,
        refusalReason,
        important,
        importantSource,
        classification == null ? null : classification.label(),
        assignmentId,
        assignTargetCode,
        false,
        describe(winner, winnerConditions, action, effectiveAction, silenceRefused, refusalReason, important));
  }

  /**
   * 试算：这条规则对这封邮件会不会命中（**不写命中记录**）。
   *
   * <p>规则配错时先试算 —— 「配了但永远不命中」是最常见的错配。
   */
  public CrmEmailRulePreviewResult preview(UUID ruleId, UUID emailId) {
    CrmEmailRuleRow ruleRow = requireRule(ruleId);
    var email = requireEmail(emailId);
    if (email == null) {
      throw notFound("邮件不存在，或不属于当前租户");
    }
    RuleSpec rule = specOf(ruleRow);
    String senderEmail = nz(email.get("buyer_email", String.class));
    String subject = nz(email.get("subject", String.class));
    String body = nz(email.get("body", String.class));
    List<String> emailTags = tagCodesOf(emailId);
    Classification classification = classificationOf(email);
    CrmEmailRuleConditionDetail conditions =
        matchConditions(rule, senderEmail, subject, body, emailTags);
    boolean matched = rule.enabled() && conditionsMatched(conditions);

    boolean important = false;
    String importantSource = "";
    if (classification != null && IMPORTANT_LABELS.contains(classification.label())) {
      important = true;
      importantSource = "CLASSIFICATION";
    } else if (!matchedMarkers(rule, subject, body).isEmpty()) {
      important = true;
      importantSource = "RULE_MARKER";
    }
    String effectiveAction = matched ? rule.action() : null;
    Boolean refused = Boolean.FALSE;
    if (matched && SILENCE.equals(rule.action()) && important) {
      effectiveAction = ESCALATE_MANUAL;
      refused = Boolean.TRUE;
    }
    return new CrmEmailRulePreviewResult(
        rule.id(),
        rule.ruleCode(),
        emailId,
        nz(email.get("email_no", String.class)),
        matched,
        conditions,
        important,
        importantSource,
        effectiveAction,
        refused,
        matched
            ? "命中" + (important && SILENCE.equals(rule.action())
                ? "，但这是**重要沟通** ⇒ 静默会被降级为「不静默 + 转人工」"
                : "")
            : "不命中：" + missReason(conditions));
  }

  // ================================================================ 命中记录

  /** 命中记录列表：真分页 + 真筛选。 */
  public CrmEmailRuleHitPage hits(
      UUID emailId,
      String ruleCode,
      String action,
      String effectiveAction,
      Boolean silenceRefused,
      Boolean important,
      String keyword,
      int page,
      int size) {
    int effectiveSize = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    int effectivePage = Math.max(page, 0);
    Filter filter =
        hitFilter(emailId, ruleCode, action, effectiveAction, silenceRefused, important, keyword);
    var rows =
        sql.fetch(
            "select h.id,h.hit_no,h.rule_id,h.rule_code,h.rule_name,h.rule_version,"
                + " h.email_id,h.email_no,h.buyer_email,h.subject,h.match_basis::text as match_basis,"
                + " h.action,h.effective_action,h.silence_refused,h.refusal_reason,h.important,"
                + " h.important_source,h.classification_id,h.classification_label,"
                + " asg.assignment_id,h.delivery_status,h.matched_at,h.created_at"
                + HIT_FROM
                + filter.where()
                + " order by h.matched_at desc, h.id"
                + " limit ? offset ?",
            bindings(filter.binds(), effectiveSize, effectivePage * effectiveSize));
    long total =
        sql.fetchOne("select count(*)" + HIT_FROM + filter.where(), filter.binds().toArray())
            .get(0, Long.class);
    return new CrmEmailRuleHitPage(rows.map(this::hitRow), effectivePage, effectiveSize, total);
  }

  /** 单条命中详情 = 命中记录 + 邮件快照 + 当时的规则快照 + 分配结果。 */
  public CrmEmailRuleHitDetail hitDetail(UUID hitId) {
    CrmEmailRuleHitRow row = requireHit(hitId);
    var email =
        sql.fetchOne(
            "select e.id,e.email_no,e.shop_name,e.platform_code,e.buyer_ref,e.buyer_name,"
                + " e.buyer_email,e.subject,e.body,e.direction,e.status,e.sales_order_no,e.received_at"
                + " from crm_buyer_email e where e.tenant_id=? and e.id=?",
            currentTenant(),
            row.emailId());
    CrmEmailRuleSnapshot snapshot =
        readSnapshot(
            sql.fetchOne(
                    "select rule_snapshot::text as rule_snapshot from crm_email_rule_hit"
                        + " where tenant_id=? and id=?",
                    currentTenant(),
                    hitId)
                .get("rule_snapshot", String.class));
    CrmEmailRuleAssignmentRow assignment =
        row.assignmentId() == null ? null : assignmentDetail(row.assignmentId());
    return new CrmEmailRuleHitDetail(row, emailView(email), snapshot, assignment);
  }

  // ================================================================ 自动分配结果

  /** 自动分配结果列表：真分页 + 真筛选。 */
  public CrmEmailRuleAssignmentPage assignments(
      UUID emailId, String ruleCode, String targetCode, String status, int page, int size) {
    int effectiveSize = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    int effectivePage = Math.max(page, 0);
    Filter filter = assignmentFilter(emailId, ruleCode, targetCode, status);
    var rows =
        sql.fetch(
            "select a.id,a.assignment_no,a.hit_id,a.rule_id,a.rule_code,a.rule_version,"
                + " a.email_id,a.email_no,a.buyer_email,a.subject,a.assign_target_type,"
                + " a.assign_target_code,a.status,a.dispatch_note,a.cancel_reason,a.cancelled_by,"
                + " a.cancelled_at,a.assigned_at,a.created_at"
                + " from crm_email_rule_assignment a"
                + filter.where()
                + " order by a.assigned_at desc, a.id"
                + " limit ? offset ?",
            bindings(filter.binds(), effectiveSize, effectivePage * effectiveSize));
    long total =
        sql.fetchOne(
                "select count(*) from crm_email_rule_assignment a" + filter.where(),
                filter.binds().toArray())
            .get(0, Long.class);
    return new CrmEmailRuleAssignmentPage(
        rows.map(EmailRuleService::assignmentRow), effectivePage, effectiveSize, total);
  }

  /** 单条自动分配结果。 */
  public CrmEmailRuleAssignmentRow assignmentDetail(UUID assignmentId) {
    var row =
        sql.fetchOne(
            "select a.id,a.assignment_no,a.hit_id,a.rule_id,a.rule_code,a.rule_version,"
                + " a.email_id,a.email_no,a.buyer_email,a.subject,a.assign_target_type,"
                + " a.assign_target_code,a.status,a.dispatch_note,a.cancel_reason,a.cancelled_by,"
                + " a.cancelled_at,a.assigned_at,a.created_at"
                + " from crm_email_rule_assignment a where a.tenant_id=? and a.id=?",
            currentTenant(),
            assignmentId);
    if (row == null) {
      throw notFound("自动分配结果不存在，或不属于当前租户");
    }
    return assignmentRow(row);
  }

  /** 取消一条自动分配结果（必须写理由；已投递 / 已取消的不可再动）。 */
  @Transactional
  public CrmEmailRuleAssignmentRow cancelAssignment(
      UUID assignmentId, CrmEmailRuleCancelAssignmentInput input, UUID actorId) {
    CrmEmailRuleAssignmentRow existing = assignmentDetail(assignmentId);
    String reason = nz(input.cancelReason()).trim();
    if (reason.isEmpty()) {
      throw bad("取消必须写理由：没有理由的取消没法复核。");
    }
    if (!"PENDING_DISPATCH".equals(existing.status())) {
      throw bad(
          "这条分配结果已是「"
              + existing.status()
              + "」，不能再取消（已投递的进过下游工单，已取消的就是取消）。");
    }
    OffsetDateTime moment = now();
    sql.execute(
        "update crm_email_rule_assignment"
            + " set status='CANCELLED', cancel_reason=?, cancelled_by=?, cancelled_at=?::timestamptz"
            + " where tenant_id=? and id=?",
        reason,
        actorId,
        moment,
        currentTenant(),
        assignmentId);
    return assignmentDetail(assignmentId);
  }

  // ================================================================ 统计

  /** 规则命中统计 + 动作分布 + 静默被拒数。 */
  public CrmEmailRuleStatistics statistics() {
    UUID tenant = currentTenant();
    var totals =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where silence_refused) as refused,"
                + " count(*) filter (where important) as important"
                + " from crm_email_rule_hit where tenant_id=?",
            tenant);
    long totalHitCount = totals.get("total", Long.class);
    long refusedCount = totals.get("refused", Long.class);
    long importantCount = totals.get("important", Long.class);
    long pendingAssignmentCount =
        sql.fetchOne(
                "select count(*) from crm_email_rule_assignment where tenant_id=? and status='PENDING_DISPATCH'",
                tenant)
            .get(0, Long.class);

    List<CrmEmailRuleBucket> byAction = buckets("action", tenant);
    List<CrmEmailRuleBucket> byEffectiveAction = buckets("effective_action", tenant);

    List<CrmEmailRuleStat> byRule = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select r.id,r.rule_code,r.rule_name,r.action,r.priority_order,r.enabled,"
                + " coalesce(h.hit_count,0) as hit_count,"
                + " coalesce(h.refused_count,0) as refused_count,"
                + " coalesce(a.assignment_count,0) as assignment_count"
                + " from crm_email_rule r"
                + " left join ("
                + "   select rule_id, count(*) as hit_count,"
                + "     count(*) filter (where silence_refused) as refused_count"
                + "   from crm_email_rule_hit where tenant_id = ? group by rule_id"
                + " ) h on h.rule_id = r.id"
                + " left join ("
                + "   select rule_id, count(*) as assignment_count"
                + "   from crm_email_rule_assignment where tenant_id = ? group by rule_id"
                + " ) a on a.rule_id = r.id"
                + " where r.tenant_id = ? order by r.priority_order, r.rule_code",
            tenant,
            tenant,
            tenant)) {
      byRule.add(
          new CrmEmailRuleStat(
              r.get("id", UUID.class),
              nz(r.get("rule_code", String.class)),
              nz(r.get("rule_name", String.class)),
              nz(r.get("action", String.class)),
              r.get("priority_order", Integer.class) == null ? 0 : r.get("priority_order", Integer.class),
              Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
              r.get("hit_count", Long.class) == null ? 0L : r.get("hit_count", Long.class),
              r.get("refused_count", Long.class) == null ? 0L : r.get("refused_count", Long.class),
              r.get("assignment_count", Long.class) == null ? 0L : r.get("assignment_count", Long.class)));
    }

    return new CrmEmailRuleStatistics(
        totalHitCount,
        refusedCount,
        importantCount,
        pendingAssignmentCount,
        byAction,
        byEffectiveAction,
        byRule,
        "「静默被拒」= 重要沟通命中了静默规则、被强制降级为「不静默 + 转人工」的次数 —— "
            + "这个数越大，说明静默规则与重要沟通的冲突越多，规则该改了。");
  }

  // ================================================================ 内部：规则与条件

  /** 筛选条件（WHERE 片段 + 绑定值）。 */
  private record Filter(String where, List<Object> binds) {}

  /** 规则（来自 DB 的真实数据）。 */
  private record RuleSpec(
      UUID id,
      String ruleCode,
      String ruleName,
      int priorityOrder,
      boolean enabled,
      String action,
      List<String> senderEmails,
      List<String> senderDomains,
      List<String> keywords,
      List<String> ticketTagCodes,
      String matchMode,
      String assignTargetType,
      String assignTargetCode,
      String replyTemplateCode,
      String replySubject,
      String replyBody,
      List<String> importantMarkers,
      int version) {}

  /** 分类结论（上游 15.1.1.3，只读）。 */
  private record Classification(UUID id, String label) {}

  /** 规则筛选。 */
  private Filter ruleFilter(String keyword, String action, Boolean enabled) {
    var where = new StringBuilder(" where r.tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    String actionValue = blankToNull(action);
    if (actionValue != null) {
      where.append(" and r.action = ?");
      binds.add(actionValue);
    }
    if (enabled != null) {
      where.append(" and r.enabled = ?");
      binds.add(enabled);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      String pattern = likePattern(word);
      where.append(
          " and (r.rule_code ilike ? escape '\\' or r.rule_name ilike ? escape '\\'"
              + " or r.keywords ilike ? escape '\\' or r.sender_emails ilike ? escape '\\'"
              + " or r.sender_domains ilike ? escape '\\' or r.ticket_tag_codes ilike ? escape '\\')");
      for (int i = 0; i < 6; i++) {
        binds.add(pattern);
      }
    }
    return new Filter(where.toString(), binds);
  }

  /** 标签筛选。 */
  private Filter tagFilter(String keyword, String status) {
    var where = new StringBuilder(" where t.tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    String statusValue = blankToNull(status);
    if (statusValue != null) {
      where.append(" and t.status = ?");
      binds.add(statusValue);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      String pattern = likePattern(word);
      where.append(" and (t.tag_code ilike ? escape '\\' or t.name ilike ? escape '\\')");
      binds.add(pattern);
      binds.add(pattern);
    }
    return new Filter(where.toString(), binds);
  }

  /** 命中记录筛选。 */
  private Filter hitFilter(
      UUID emailId,
      String ruleCode,
      String action,
      String effectiveAction,
      Boolean silenceRefused,
      Boolean important,
      String keyword) {
    var where = new StringBuilder(" where h.tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (emailId != null) {
      where.append(" and h.email_id = ?");
      binds.add(emailId);
    }
    String rule = blankToNull(ruleCode);
    if (rule != null) {
      where.append(" and h.rule_code = ?");
      binds.add(rule);
    }
    String actionValue = blankToNull(action);
    if (actionValue != null) {
      where.append(" and h.action = ?");
      binds.add(actionValue);
    }
    String effective = blankToNull(effectiveAction);
    if (effective != null) {
      where.append(" and h.effective_action = ?");
      binds.add(effective);
    }
    if (silenceRefused != null) {
      where.append(" and h.silence_refused = ?");
      binds.add(silenceRefused);
    }
    if (important != null) {
      where.append(" and h.important = ?");
      binds.add(important);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      String pattern = likePattern(word);
      where.append(
          " and (h.rule_code ilike ? escape '\\' or h.rule_name ilike ? escape '\\'"
              + " or h.email_no ilike ? escape '\\' or h.buyer_email ilike ? escape '\\'"
              + " or h.subject ilike ? escape '\\')");
      for (int i = 0; i < 5; i++) {
        binds.add(pattern);
      }
    }
    return new Filter(where.toString(), binds);
  }

  /** 自动分配结果筛选。 */
  private Filter assignmentFilter(
      UUID emailId, String ruleCode, String targetCode, String status) {
    var where = new StringBuilder(" where a.tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (emailId != null) {
      where.append(" and a.email_id = ?");
      binds.add(emailId);
    }
    String rule = blankToNull(ruleCode);
    if (rule != null) {
      where.append(" and a.rule_code = ?");
      binds.add(rule);
    }
    String target = blankToNull(targetCode);
    if (target != null) {
      where.append(" and a.assign_target_code = ?");
      binds.add(target);
    }
    String statusValue = blankToNull(status);
    if (statusValue != null) {
      where.append(" and a.status = ?");
      binds.add(statusValue);
    }
    return new Filter(where.toString(), binds);
  }

  // ================================================================ 内部：条件判定

  /**
   * 三个维度的命中判定。
   *
   * <p>⚠️ **没配的维度不参与判定**（算作满足）：一条只配了关键词的规则，
   * 不该因为「发件人不匹配」而不命中。
   */
  private CrmEmailRuleConditionDetail matchConditions(
      RuleSpec rule, String senderEmail, String subject, String body, List<String> emailTags) {
    String sender = nz(senderEmail).trim().toLowerCase(Locale.ROOT);
    String domain = sender.contains("@") ? sender.substring(sender.indexOf('@') + 1) : "";

    List<String> matchedEmails = new ArrayList<>();
    for (String value : rule.senderEmails()) {
      if (!value.isEmpty() && value.equalsIgnoreCase(sender)) {
        matchedEmails.add(value);
      }
    }
    List<String> matchedDomains = new ArrayList<>();
    for (String value : rule.senderDomains()) {
      if (!value.isEmpty() && value.equalsIgnoreCase(domain)) {
        matchedDomains.add(value);
      }
    }
    boolean senderMatched =
        (rule.senderEmails().isEmpty() || listMatched(rule.matchMode(), rule.senderEmails(), matchedEmails))
            && (rule.senderDomains().isEmpty()
                || listMatched(rule.matchMode(), rule.senderDomains(), matchedDomains));

    List<String> matchedKeywords = new ArrayList<>();
    String subjectLower = subject.toLowerCase(Locale.ROOT);
    String bodyLower = body.toLowerCase(Locale.ROOT);
    for (String keyword : rule.keywords()) {
      String lower = keyword.toLowerCase(Locale.ROOT);
      if (subjectLower.contains(lower) || bodyLower.contains(lower)) {
        matchedKeywords.add(keyword);
      }
    }
    boolean keywordsMatched =
        rule.keywords().isEmpty() || listMatched(rule.matchMode(), rule.keywords(), matchedKeywords);

    List<String> matchedTags = new ArrayList<>();
    for (String code : rule.ticketTagCodes()) {
      if (emailTags.contains(code)) {
        matchedTags.add(code);
      }
    }
    boolean tagsMatched =
        rule.ticketTagCodes().isEmpty()
            || listMatched(rule.matchMode(), rule.ticketTagCodes(), matchedTags);

    return new CrmEmailRuleConditionDetail(
        new CrmEmailRuleSenderCondition(
            senderMatched, rule.senderEmails(), rule.senderDomains(), matchedEmails, matchedDomains),
        new CrmEmailRuleKeywordCondition(keywordsMatched, rule.keywords(), matchedKeywords),
        new CrmEmailRuleTicketTagCondition(tagsMatched, rule.ticketTagCodes(), matchedTags));
  }

  /** 条件是否整体命中（配了哪个维度就必须满足哪个维度；没配的维度不参与）。 */
  private boolean conditionsMatched(CrmEmailRuleConditionDetail conditions) {
    return conditions != null
        && conditions.sender() != null
        && conditions.sender().matched()
        && conditions.keywords() != null
        && conditions.keywords().matched()
        && conditions.ticketTags() != null
        && conditions.ticketTags().matched();
  }

  /** 同一维度内多个值的命中方式。 */
  private static boolean listMatched(String mode, List<String> configured, List<String> matched) {
    if ("ALL".equals(mode)) {
      return matched.size() == configured.size();
    }
    return !matched.isEmpty();
  }

  /** 规则自己声明的重要标记，命中了哪些。 */
  private static List<String> matchedMarkers(RuleSpec rule, String subject, String body) {
    String subjectLower = subject.toLowerCase(Locale.ROOT);
    String bodyLower = body.toLowerCase(Locale.ROOT);
    List<String> out = new ArrayList<>();
    for (String marker : rule.importantMarkers()) {
      String lower = marker.toLowerCase(Locale.ROOT);
      if (!lower.isEmpty() && (subjectLower.contains(lower) || bodyLower.contains(lower))) {
        out.add(marker);
      }
    }
    return out;
  }

  /** 不命中的原因（说清是哪个维度没满足）。 */
  private static String missReason(CrmEmailRuleConditionDetail conditions) {
    List<String> parts = new ArrayList<>();
    var sender = conditions.sender();
    var keywords = conditions.keywords();
    var tags = conditions.ticketTags();
    if (sender != null && !sender.matched() && (sender.emails().size() + sender.domains().size()) > 0) {
      parts.add("发件人不匹配");
    }
    if (keywords != null && !keywords.matched() && keywords.keywords().size() > 0) {
      parts.add("关键词没命中");
    }
    if (tags != null && !tags.matched() && tags.codes().size() > 0) {
      parts.add("工单标签没命中");
    }
    return parts.isEmpty() ? "规则没有配置任何条件维度" : String.join("、", parts);
  }

  // ================================================================ 内部：数据读取

  /** 邮件（15.1.2.1，同库同服务，只读）。 */
  private Record requireEmail(UUID emailId) {
    return sql.fetchOne(
        "select e.id,e.email_no,e.buyer_email,e.subject,e.body,e.direction,e.source_message_id"
            + " from crm_buyer_email e where e.tenant_id=? and e.id=?",
        currentTenant(),
        emailId);
  }

  /** 邮件上当前挂的工单标签码。 */
  private List<String> tagCodesOf(UUID emailId) {
    return sql.fetch(
            "select tag_code from crm_email_ticket_tag_assignment where tenant_id=? and email_id=?",
            currentTenant(),
            emailId)
        .map(r -> r.get(0, String.class));
  }

  /** 上游 15.1.1.3 的分类结论（只读；没有就返回 null）。 */
  private Classification classificationOf(Record email) {
    UUID sourceMessageId = email.get("source_message_id", UUID.class);
    if (sourceMessageId == null) {
      return null;
    }
    var row =
        sql.fetchOne(
            "select c.id, c.label from crm_message_classification c"
                + " where c.tenant_id=? and c.message_id=?"
                + " order by c.classified_at desc limit 1",
            currentTenant(),
            sourceMessageId);
    if (row == null) {
      return null;
    }
    return new Classification(row.get("id", UUID.class), nz(row.get("label", String.class)));
  }

  /** 启用中的规则（按顺序）。 */
  private List<RuleSpec> enabledRules() {
    return sql.fetch(
            "select r.id,r.rule_code,r.rule_name,r.priority_order,r.enabled,r.action,"
                + " r.sender_emails,r.sender_domains,r.keywords,r.ticket_tag_codes,r.match_mode,"
                + " r.assign_target_type,r.assign_target_code,r.reply_template_code,r.reply_subject,"
                + " r.reply_body,r.important_markers,r.version"
                + " from crm_email_rule r"
                + " where r.tenant_id=? and r.enabled order by r.priority_order, r.rule_code",
            currentTenant())
        .map(EmailRuleService::toSpec);
  }

  /** 规则是否已存在（按编码）。 */
  private boolean ruleExists(String code) {
    return sql.fetchOne(
            "select id from crm_email_rule where tenant_id=? and rule_code=?", currentTenant(), code)
        != null;
  }

  /** 标签是否已存在（按编码）。 */
  private boolean tagExists(String code) {
    return sql.fetchOne(
            "select id from crm_email_ticket_tag where tenant_id=? and tag_code=?",
            currentTenant(),
            code)
        != null;
  }

  /** 单条命中记录。 */
  private CrmEmailRuleHitRow requireHit(UUID hitId) {
    var row =
        sql.fetchOne(
            "select h.id,h.hit_no,h.rule_id,h.rule_code,h.rule_name,h.rule_version,"
                + " h.email_id,h.email_no,h.buyer_email,h.subject,h.match_basis::text as match_basis,"
                + " h.action,h.effective_action,h.silence_refused,h.refusal_reason,h.important,"
                + " h.important_source,h.classification_id,h.classification_label,"
                + " asg.assignment_id,h.delivery_status,h.matched_at,h.created_at"
                + HIT_FROM
                + " where h.tenant_id=? and h.id=?",
            currentTenant(),
            hitId);
    if (row == null) {
      throw notFound("命中记录不存在，或不属于当前租户");
    }
    return hitRow(row);
  }

  // ================================================================ 内部：转换

  /** 记录 → 规则行。 */
  private static CrmEmailRuleRow rule(Record r) {
    return new CrmEmailRuleRow(
        r.get("id", UUID.class),
        nz(r.get("rule_code", String.class)),
        nz(r.get("rule_name", String.class)),
        r.get("priority_order", Integer.class) == null ? 0 : r.get("priority_order", Integer.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        nz(r.get("action", String.class)),
        splitLines(r.get("sender_emails", String.class)),
        splitCodes(r.get("sender_domains", String.class)),
        splitLines(r.get("keywords", String.class)),
        splitCodes(r.get("ticket_tag_codes", String.class)),
        nz(r.get("match_mode", String.class)),
        nz(r.get("assign_target_type", String.class)),
        nz(r.get("assign_target_code", String.class)),
        nz(r.get("reply_template_code", String.class)),
        nz(r.get("reply_subject", String.class)),
        nz(r.get("reply_body", String.class)),
        splitLines(r.get("important_markers", String.class)),
        r.get("version", Integer.class) == null ? 1 : r.get("version", Integer.class),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  /** 记录 → 规则（内部形态）。 */
  private static RuleSpec toSpec(Record r) {
    return new RuleSpec(
        r.get("id", UUID.class),
        nz(r.get("rule_code", String.class)),
        nz(r.get("rule_name", String.class)),
        r.get("priority_order", Integer.class) == null ? 0 : r.get("priority_order", Integer.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        nz(r.get("action", String.class)),
        splitLines(r.get("sender_emails", String.class)),
        splitCodes(r.get("sender_domains", String.class)),
        splitLines(r.get("keywords", String.class)),
        splitCodes(r.get("ticket_tag_codes", String.class)),
        nz(r.get("match_mode", String.class)),
        nz(r.get("assign_target_type", String.class)),
        nz(r.get("assign_target_code", String.class)),
        nz(r.get("reply_template_code", String.class)),
        nz(r.get("reply_subject", String.class)),
        nz(r.get("reply_body", String.class)),
        splitLines(r.get("important_markers", String.class)),
        r.get("version", Integer.class) == null ? 1 : r.get("version", Integer.class));
  }

  /** 规则行 → 规则（内部形态）。 */
  private static RuleSpec specOf(CrmEmailRuleRow row) {
    return new RuleSpec(
        row.id(),
        row.ruleCode(),
        row.ruleName(),
        row.priorityOrder(),
        row.enabled(),
        row.action(),
        row.senderEmails(),
        row.senderDomains(),
        row.keywords(),
        row.ticketTagCodes(),
        row.matchMode(),
        row.assignTargetType(),
        row.assignTargetCode(),
        row.replyTemplateCode(),
        row.replySubject(),
        row.replyBody(),
        row.importantMarkers(),
        row.version());
  }

  /** 输入 → 规则（内部形态），并做**服务层的真校验**。 */
  private RuleSpec specOf(CrmEmailRuleInput input, String code, int version) {
    List<String> senderEmails = cleanList(input.senderEmails());
    List<String> senderDomains = cleanList(input.senderDomains());
    List<String> keywords = cleanList(input.keywords());
    List<String> tagCodes = cleanList(input.ticketTagCodes());
    List<String> markers = cleanList(input.importantMarkers());
    if (senderEmails.isEmpty() && senderDomains.isEmpty() && keywords.isEmpty() && tagCodes.isEmpty()) {
      throw bad(
          "规则至少要有一个条件维度（发件人 / 关键词 / 工单标签）："
              + "全空的规则永远不会命中，却会占掉一个顺序位 —— 那是一条看起来配了、其实没生效的规则。");
    }
    String action = oneOf(ACTIONS, input.action(), "命中动作");
    String assignTargetType =
        oneOf(ASSIGN_TARGET_TYPES, input.assignTargetType() == null ? "TEAM" : input.assignTargetType(), "分配目标类型");
    String assignTargetCode = nz(input.assignTargetCode()).trim();
    String replyTemplateCode = nz(input.replyTemplateCode()).trim();
    String replyBody = nz(input.replyBody());
    if (AUTO_ASSIGN.equals(action) && assignTargetCode.isEmpty()) {
      throw bad("自动分配必须写清楚分给谁（组码 / 人工号）：没有目标的分配结果没法执行。");
    }
    if (AUTO_REPLY.equals(action) && replyTemplateCode.isEmpty() && replyBody.trim().isEmpty()) {
      throw bad("自动回复必须给出话术（模板码或正文）：没有话术的自动回复发不出东西。");
    }
    // 工单标签码必须真实存在（DB 层也会再挡一次 —— 这里是给人一句能读的话）。
    for (String tagCode : tagCodes) {
      if (!tagExists(tagCode)) {
        throw bad(
            "工单标签「"
                + tagCode
                + "」不存在：先建标签再引用它 —— 引用不存在的标签码，那是一条永远不会命中的规则。");
      }
    }
    return new RuleSpec(
        null,
        code,
        nz(input.ruleName()).trim(),
        input.priorityOrder() == null ? 100 : input.priorityOrder(),
        input.enabled() == null || input.enabled(),
        action,
        senderEmails,
        senderDomains,
        keywords,
        tagCodes,
        oneOf(MATCH_MODES, input.matchMode() == null ? "ANY" : input.matchMode(), "命中方式"),
        assignTargetType,
        assignTargetCode,
        replyTemplateCode,
        nz(input.replySubject()),
        replyBody,
        markers,
        version);
  }

  /** 记录 → 标签行。 */
  private static CrmEmailTicketTagRow tag(Record r) {
    return new CrmEmailTicketTagRow(
        r.get("id", UUID.class),
        nz(r.get("tag_code", String.class)),
        nz(r.get("name", String.class)),
        nz(r.get("status", String.class)),
        nz(r.get("description", String.class)),
        r.get("usage_count", Long.class) == null ? 0L : r.get("usage_count", Long.class),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  /** 记录 → 命中记录。 */
  private CrmEmailRuleHitRow hitRow(Record r) {
    return new CrmEmailRuleHitRow(
        r.get("id", UUID.class),
        nz(r.get("hit_no", String.class)),
        r.get("rule_id", UUID.class),
        nz(r.get("rule_code", String.class)),
        nz(r.get("rule_name", String.class)),
        r.get("rule_version", Integer.class) == null ? 1 : r.get("rule_version", Integer.class),
        r.get("email_id", UUID.class),
        nz(r.get("email_no", String.class)),
        nz(r.get("buyer_email", String.class)),
        nz(r.get("subject", String.class)),
        readBasis(r.get("match_basis", String.class)),
        nz(r.get("action", String.class)),
        nz(r.get("effective_action", String.class)),
        Boolean.TRUE.equals(r.get("silence_refused", Boolean.class)),
        nz(r.get("refusal_reason", String.class)),
        Boolean.TRUE.equals(r.get("important", Boolean.class)),
        nz(r.get("important_source", String.class)),
        r.get("classification_id", UUID.class),
        nz(r.get("classification_label", String.class)),
        r.get("assignment_id", UUID.class),
        r.get("delivery_status", String.class),
        str(r.get("matched_at", OffsetDateTime.class)),
        str(r.get("created_at", OffsetDateTime.class)));
  }

  /** 记录 → 自动分配结果。 */
  private static CrmEmailRuleAssignmentRow assignmentRow(Record r) {
    return new CrmEmailRuleAssignmentRow(
        r.get("id", UUID.class),
        nz(r.get("assignment_no", String.class)),
        r.get("hit_id", UUID.class),
        r.get("rule_id", UUID.class),
        nz(r.get("rule_code", String.class)),
        r.get("rule_version", Integer.class) == null ? 1 : r.get("rule_version", Integer.class),
        r.get("email_id", UUID.class),
        nz(r.get("email_no", String.class)),
        nz(r.get("buyer_email", String.class)),
        nz(r.get("subject", String.class)),
        nz(r.get("assign_target_type", String.class)),
        nz(r.get("assign_target_code", String.class)),
        nz(r.get("status", String.class)),
        nz(r.get("dispatch_note", String.class)),
        nz(r.get("cancel_reason", String.class)),
        r.get("cancelled_by", UUID.class),
        str(r.get("cancelled_at", OffsetDateTime.class)),
        str(r.get("assigned_at", OffsetDateTime.class)),
        str(r.get("created_at", OffsetDateTime.class)));
  }

  /** 邮件记录 → 邮件快照。 */
  private static CrmEmailRuleEmailView emailView(Record r) {
    if (r == null) {
      return null;
    }
    return new CrmEmailRuleEmailView(
        r.get("id", UUID.class),
        nz(r.get("email_no", String.class)),
        nz(r.get("shop_name", String.class)),
        nz(r.get("platform_code", String.class)),
        nz(r.get("buyer_ref", String.class)),
        nz(r.get("buyer_name", String.class)),
        nz(r.get("buyer_email", String.class)),
        nz(r.get("subject", String.class)),
        nz(r.get("body", String.class)),
        nz(r.get("direction", String.class)),
        nz(r.get("status", String.class)),
        nz(r.get("sales_order_no", String.class)),
        str(r.get("received_at", OffsetDateTime.class)));
  }

  // ================================================================ 内部：JSON

  /** 命中依据 → JSON（落库在 jsonb 列上）。 */
  private String writeBasis(RuleSpec winner, List<CrmEmailRuleMatch> matches) {
    Map<String, Object> basis = new LinkedHashMap<>();
    basis.put("winningRuleId", winner == null ? null : winner.id().toString());
    basis.put("winningRuleCode", winner == null ? "" : winner.ruleCode());
    List<Map<String, Object>> items = new ArrayList<>();
    for (CrmEmailRuleMatch match : matches) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("ruleId", match.ruleId().toString());
      item.put("ruleCode", match.ruleCode());
      item.put("ruleName", match.ruleName());
      item.put("ruleOrder", match.ruleOrder());
      item.put("ruleVersion", match.ruleVersion());
      item.put("action", match.action());
      item.put("won", match.won());
      item.put("conditions", match.conditions());
      items.add(item);
    }
    basis.put("matches", items);
    return writeJson(basis);
  }

  /** 规则快照 → JSON（冻结当时的规则内容）。 */
  private String writeSnapshot(RuleSpec rule) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("ruleCode", rule.ruleCode());
    snapshot.put("ruleName", rule.ruleName());
    snapshot.put("priorityOrder", rule.priorityOrder());
    snapshot.put("enabled", rule.enabled());
    snapshot.put("action", rule.action());
    snapshot.put("senderEmails", rule.senderEmails());
    snapshot.put("senderDomains", rule.senderDomains());
    snapshot.put("keywords", rule.keywords());
    snapshot.put("ticketTagCodes", rule.ticketTagCodes());
    snapshot.put("matchMode", rule.matchMode());
    snapshot.put("assignTargetType", rule.assignTargetType());
    snapshot.put("assignTargetCode", rule.assignTargetCode());
    snapshot.put("replyTemplateCode", rule.replyTemplateCode());
    snapshot.put("replySubject", rule.replySubject());
    snapshot.put("replyBody", rule.replyBody());
    snapshot.put("importantMarkers", rule.importantMarkers());
    snapshot.put("version", rule.version());
    return writeJson(snapshot);
  }

  /** 规则快照 JSON → 传输模型（结构化，页面直接显示，不用自己 parse）。 */
  private CrmEmailRuleSnapshot readSnapshot(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return json.readValue(raw, CrmEmailRuleSnapshot.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("规则快照解析失败：" + raw, e);
    }
  }

  /** 命中依据 JSON → 传输模型。 */
  private CrmEmailRuleMatchBasis readBasis(String raw) {
    if (raw == null || raw.isBlank()) {
      return new CrmEmailRuleMatchBasis(null, null, List.of());
    }
    try {
      CrmEmailRuleMatchBasis parsed =
          json.readValue(raw, new TypeReference<CrmEmailRuleMatchBasis>() {});
      return new CrmEmailRuleMatchBasis(
          parsed.winningRuleId(),
          parsed.winningRuleCode(),
          parsed.matches() == null ? List.of() : parsed.matches());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("命中依据解析失败：" + raw, e);
    }
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("JSON 序列化失败", e);
    }
  }

  // ================================================================ 内部：工具

  /** 命中结论的可读描述。 */
  private static String describe(
      RuleSpec winner,
      CrmEmailRuleConditionDetail conditions,
      String action,
      String effectiveAction,
      boolean silenceRefused,
      String refusalReason,
      boolean important) {
    StringBuilder sb = new StringBuilder();
    sb.append("命中规则 ").append(winner.ruleCode()).append("（").append(winner.ruleName()).append("）：");
    sb.append(describeConditions(conditions));
    sb.append("；动作 ").append(actionLabel(action));
    if (!action.equals(effectiveAction)) {
      sb.append(" → 实际生效 ").append(actionLabel(effectiveAction));
    }
    if (important) {
      sb.append("；🔴 判定为重要沟通");
    }
    if (silenceRefused) {
      sb.append("；").append(refusalReason);
    }
    return sb.toString();
  }

  /** 命中条件的可读描述。 */
  private static String describeConditions(CrmEmailRuleConditionDetail conditions) {
    if (conditions == null) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    var sender = conditions.sender();
    if (sender != null && !sender.emails().isEmpty()) {
      parts.add("发件人邮箱命中 " + String.join("、", sender.matchedEmails()));
    }
    if (sender != null && !sender.domains().isEmpty()) {
      parts.add("发件人域名命中 " + String.join("、", sender.matchedDomains()));
    }
    var keywords = conditions.keywords();
    if (keywords != null && !keywords.keywords().isEmpty()) {
      parts.add("关键词命中 " + String.join("、", keywords.matchedKeywords()));
    }
    var tags = conditions.ticketTags();
    if (tags != null && !tags.codes().isEmpty()) {
      parts.add("工单标签命中 " + String.join("、", tags.matchedCodes()));
    }
    return parts.isEmpty() ? "没有维度命中" : String.join("、", parts);
  }

  /** 动作的可读名。 */
  static String actionLabel(String action) {
    return switch (action == null ? "" : action) {
      case AUTO_ASSIGN -> "自动分配";
      case AUTO_REPLY -> "自动回复";
      case SILENCE -> "静默";
      case ESCALATE_MANUAL -> "不静默 + 转人工";
      default -> action;
    };
  }

  /** 命中号：ERT-yyyyMMdd-000001（序列单独授权，见 V305 授权段）。 */
  private String nextHitNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_email_rule_hit_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "ERT-" + day + "-" + String.format("%06d", seq);
  }

  /** 分配号：EAS-yyyyMMdd-000001。 */
  private String nextAssignmentNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_email_rule_assignment_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "EAS-" + day + "-" + String.format("%06d", seq);
  }

  /** 按动作分组的统计（只列**真实出现过**的取值域）。 */
  private List<CrmEmailRuleBucket> buckets(String column, UUID tenant) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record record :
        sql.fetch(
            "select h." + column + " as code, count(*) as cnt from crm_email_rule_hit h"
                + " where h.tenant_id = ? group by h." + column + " order by cnt desc, code",
            tenant)) {
      counts.put(record.get("code", String.class), record.get("cnt", Long.class));
    }
    List<CrmEmailRuleBucket> out = new ArrayList<>();
    counts.forEach(
        (code, count) ->
            out.add(new CrmEmailRuleBucket(code, actionLabel(code), count)));
    return out;
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

  /**
   * 标签列表的绑定顺序：JOIN 子句里的租户占位符排在筛选条件**之前**。
   *
   * <p>⚠️ 顺序错了不会报错，只会把租户 id 当成筛选值传进去 ——
   * 那种 bug 在页面上表现为「标签一条都查不到」。
   */
  private Object[] tagBindings(List<Object> filterBinds, Object... tail) {
    List<Object> all = new ArrayList<>();
    all.add(currentTenant());
    all.addAll(filterBinds);
    all.addAll(List.of(tail));
    return all.toArray();
  }

  /** 评估失败的逐条结果。 */
  private static CrmEmailRuleEvaluateResult failed(UUID emailId, String emailNo, String reason) {
    return new CrmEmailRuleEvaluateResult(
        emailId,
        emailNo,
        false,
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
        false,
        reason);
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
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

  /** 换行分隔文本 → 列表。 */
  private static List<String> splitLines(String value) {
    List<String> out = new ArrayList<>();
    for (String part : nz(value).split("\n")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }

  /** 逗号分隔文本 → 列表。 */
  private static List<String> splitCodes(String value) {
    List<String> out = new ArrayList<>();
    for (String part : nz(value).split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }

  /** 列表（去空白、去空串）。 */
  private static List<String> cleanList(List<String> values) {
    List<String> out = new ArrayList<>();
    if (values == null) {
      return out;
    }
    for (String value : values) {
      String trimmed = nz(value).trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }
}
