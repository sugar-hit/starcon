package com.starcon.crm;

import static com.starcon.crm.MessageClassificationModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationBatchInput;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationBatchResult;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationBucket;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationCapabilities;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationCapabilityDimension;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationClassifyInput;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationClassifyResult;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationDetail;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationFeedbackInput;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationFeedbackPage;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationFeedbackResult;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationFeedbackRow;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationKeywordHit;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationMatchBasis;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationMessageView;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationOptions;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationPage;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationRuleInput;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationRuleMatch;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationRulePage;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationRuleResult;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationRuleRow;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationRuleStat;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationRow;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationStatistics;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationSummary;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationTrainingSample;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationTrainingSamplePage;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 15.1.1.3 消息分类：对消息自动分类（售前咨询、物流查询、退货退款、投诉、平台合规、垃圾信息），
 * 驱动自动回复与优先级，支持人工纠正以改进分类。
 *
 * <p>蓝图 15 卷 15.1.1.3 原话：
 *
 * <blockquote>
 *
 * 职责：对消息自动分类（售前咨询、物流查询、退货退款、投诉、平台合规、垃圾信息），驱动自动回复与优先级，支持人工纠正以改进分类。 输入：消息内容；分类规则（关键词 + 平台类型 + 历史纠正数据）。 输出：分类标签 + 优先级 + 是否可自动回复。 关系：上游 = 15.1.1.1；下游 = 15.5.1 自动回复、1.3 预警。 <b>人工纠正要回流为训练样本</b>，否则分类准确率永远上不去。
 *
 * </blockquote>
 *
 * <p><b>加粗句「人工纠正要回流为训练样本」在本服务落成三件事</b>：
 *
 * <ol>
 *   <li><b>纠正与结论分开两张表</b>：{@code crm_message_classification} 是机器结论（可被重新分类覆盖），
 *       {@code crm_message_classification_feedback} 是人工纠正（<b>只追加</b>，DB 触发器拒绝改删）。
 *       纠正<b>不回写</b>结论里的标签/优先级 —— 事后能逐条比对「机器说了什么 / 人改成了什么」。</li>
 *   <li><b>纠正行自带机器预测快照</b>：{@code predictedLabel} / {@code predictedPriority} /
 *       {@code predictedRuleCode} 是纠正当时的机器输出；只有正确答案的训练样本学不出「错在哪」。</li>
 *   <li><b>训练样本可查可统计</b>：{@code GET /training-samples} 返回
 *       「机器预测 + 人工纠正 + 消息正文」三件套，{@code GET /statistics} 给出
 *       规则命中数与纠正一致率。</li>
 * </ol>
 *
 * <p><b>🔴 不覆写人工设过的优先级</b>：{@code crm_message.priority} 是人工设的，本模块
 * <b>只读不改</b>；结论里同时给 {@code suggestedPriority}（建议）、{@code manualPriority}
 * （人工值快照）、{@code effectivePriority}（= 人工值）与 {@code priorityConflict}（是否分歧）。
 *
 * <p><b>下游未交付</b>：15.5.1 自动回复与 1.3 预警在本车道基线里都<b>没有</b>可调的授权 API
 * ⇒ 本模块只<b>判定</b> {@code autoReplyEligible}，<b>不触发</b>任何自动回复、<b>不写</b>任何预警。
 *
 * <p><b>跨服务纪律</b>：上游 {@code crm_message} 在<b>本服务本库</b>（15.1.1.1，同库同服务），
 * 所以直接 jOOQ 查，<b>只读</b>；本服务<b>一条 SQL 都不碰</b>别的服务的库。
 */
@Service
public class MessageClassificationService {

  // ================================================================ 常量

  /** 分类器口径版本：规则改了要能看出「当时用的是哪一版口径」。 */
  static final String CLASSIFIER_VERSION = "RULE_MATCH_V1";

  /** 未命中任何规则时的兜底标签。 */
  static final String UNCLASSIFIED = "UNCLASSIFIED";

  /** 列表分页上限（真分页的上限，防止一次拉爆）。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 分类结论的 FROM 子句（结论 + 消息快照）。
   *
   * <p>⚠️ 关键字筛选会用到消息的 subject / body，所以**每条**查结论的 SQL 都得带上这个 JOIN ——
   * 少带一处，带关键字筛选的那一次就会报「missing FROM-clause entry for table m」。
   */
  private static final String CLASSIFICATION_FROM =
      " from crm_message_classification c"
          + " join crm_message m on m.tenant_id = c.tenant_id and m.id = c.message_id";

  private final DSLContext sql;
  private final ObjectMapper json;

  public MessageClassificationService(DSLContext sql, ObjectMapper json) {
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
   *   <li>{@code auto_reply} —— 15.5.1 自动回复**未交付**。本模块只给
   *       {@code autoReplyEligible}（是否<b>可</b>自动回复），<b>不触发</b>任何回复；
   *   <li>{@code alert} —— 1.3 预警（工作台预警）**未交付**。本模块<b>不写</b>任何预警。
   * </ul>
   */
  public CrmMessageClassificationCapabilities capabilities() {
    List<CrmMessageClassificationCapabilityDimension> dimensions =
        List.of(
            new CrmMessageClassificationCapabilityDimension(
                "rule_configuration",
                "分类规则可配置（关键词 + 适用平台 + 适用消息类型 + 顺序 + 启用）",
                true,
                "本服务 V301 crm_message_classification_rule + POST/PUT/DELETE /rules",
                "",
                "规则**真的落库**：新增、改顺序、停用都立刻改变分类结果，"
                    + "命中依据里能逐条看到「命中了哪条规则的哪个关键词」"),
            new CrmMessageClassificationCapabilityDimension(
                "message_classification",
                "消息自动分类（标签 + 优先级 + 是否可自动回复）",
                true,
                "本服务 V301 crm_message_classification + POST /classify",
                "15.1.1.3",
                "分类结论**只追加不覆写人工优先级**：建议值与人工值都返回，冲突时标记 "
                    + "priorityConflict=true，由人决定要不要改"),
            new CrmMessageClassificationCapabilityDimension(
                "human_correction",
                "人工纠正（只追加）",
                true,
                "本服务 V301 crm_message_classification_feedback + POST /feedback",
                "15.1.1.3",
                "纠正**只追加、不可改、不可删**（DB 触发器强制）；"
                    + "纠正带机器预测快照，事后能逐条比对「机器判成什么 / 人改成了什么」"),
            new CrmMessageClassificationCapabilityDimension(
                "training_sample",
                "🔴 人工纠正回流为训练样本",
                true,
                "本服务 GET /training-samples + GET /statistics",
                "15.1.1.3",
                "训练样本 = 机器预测 + 人工纠正 + 消息正文三件套，"
                    + "**正文一并带出**（少了正文就只剩标签对，训不出「哪句话被判成了什么」）"),
            new CrmMessageClassificationCapabilityDimension(
                "auto_reply",
                "自动回复（15.5.1）",
                false,
                "15.5.1 自动回复在本车道基线里**不可调用**",
                "15.5.1",
                "本模块只**判定** autoReplyEligible（是否可自动回复），"
                    + "**不触发**任何自动回复、**不写**任何回复记录 —— "
                    + "「可自动回复」是判定结果，不代表自动回复功能已存在"),
            new CrmMessageClassificationCapabilityDimension(
                "alert",
                "工作台预警（1.3）",
                false,
                "1.3 预警在本车道基线里**不可调用**",
                "1.3",
                "分类结论**不写**任何预警；页面不提供看起来能点的预警入口"),
            new CrmMessageClassificationCapabilityDimension(
                "priority_override",
                "把建议优先级写回消息",
                false,
                "crm_message.priority 是**人工**设的（15.1.1.1 的 SET_PRIORITY）",
                "15.1.1.1",
                "本模块**只读不改**它：结论里给出建议值 / 人工值 / 是否冲突三个字段，"
                    + "要不要改由人决定 —— 偷偷用建议值盖掉人工值 = 客服设的优先级无声消失"),
            new CrmMessageClassificationCapabilityDimension(
                "message_source",
                "消息来源（分类的输入）",
                false,
                "channel 服务 vendor 包下只有 13 个 OAuth 适配器，**没有**买家站内信适配器",
                "2",
                "消息只能由 15.1.1.1 人工登记进来；本模块**不**自己同步平台消息"),
            new CrmMessageClassificationCapabilityDimension(
                "model_training",
                "模型训练 / 规则学习",
                false,
                "本车道基线里**没有**训练任务调度器",
                "",
                "纠正行是**训练样本**（可查、可导出、可统计），"
                    + "但**没有**自动从样本里学出新规则的调度器 —— 那不在本模块职责里，"
                    + "本模块**不编**一个「已训练」的假状态"));
    return new CrmMessageClassificationCapabilities(
        dimensions,
        LABELS,
        RULE_LABELS,
        PRIORITIES,
        MATCH_MODES,
        "上游 15.1.1.1 消息收件箱在**本服务本库**（同库同服务，直接 jOOQ 查，只读）；"
            + "下游 15.5.1 自动回复与 1.3 预警**均未交付**，本模块只**判定**「是否可自动回复」，"
            + "**不触发**自动回复、**不写**预警，也不覆写人工设过的优先级。",
        "🔴 人工纠正要回流为训练样本：纠正**只追加**（DB 触发器拒绝改删），"
            + "并自带机器预测快照与消息正文 —— 只有正确答案的样本学不出「错在哪」。");
  }

  // ================================================================ 词表

  /**
   * 筛选词表。
   *
   * <p>标签 / 优先级 / 命中方式是 **DB CHECK 允许的取值域**（V301 钉死）；
   * 平台与消息类型来自**本租户真实数据**（{@code crm_message} 里实际出现过的取值），
   * 不是前端写死的常量。
   */
  public CrmMessageClassificationOptions options() {
    List<String> platformCodes =
        sql.fetch(
                "select distinct platform_code from crm_message where tenant_id = ? order by platform_code",
                currentTenant())
            .map(r -> r.get(0, String.class));
    List<String> messageTypes =
        sql.fetch(
                "select distinct message_type from crm_message where tenant_id = ? order by message_type",
                currentTenant())
            .map(r -> r.get(0, String.class));
    return new CrmMessageClassificationOptions(
        LABELS,
        RULE_LABELS,
        PRIORITIES,
        MATCH_MODES,
        platformCodes,
        messageTypes,
        "标签 / 优先级 / 命中方式是 DB 层允许的取值域；平台与消息类型来自本租户**真实数据**。");
  }

  // ================================================================ 规则

  /** 规则列表：真分页 + 真筛选。 */
  public CrmMessageClassificationRulePage rules(
      String keyword, String targetLabel, Boolean enabled, int page, int size) {
    int effectiveSize = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    int effectivePage = Math.max(page, 0);
    Filter filter = ruleFilter(keyword, targetLabel, enabled);
    var rows =
        sql.fetch(
            "select r.id,r.rule_code,r.rule_name,r.priority_order,r.target_label,"
                + " r.suggested_priority,r.auto_reply_eligible,r.keywords,r.match_mode,"
                + " r.platform_codes,r.message_types,r.enabled,r.note,r.created_at,r.updated_at"
                + " from crm_message_classification_rule r"
                + filter.where()
                + " order by r.priority_order, r.rule_code"
                + " limit ? offset ?",
            bindings(filter.binds(), effectiveSize, effectivePage * effectiveSize));
    long total =
        sql.fetchOne(
                "select count(*) from crm_message_classification_rule r" + filter.where(),
                filter.binds().toArray())
            .get(0, Long.class);
    return new CrmMessageClassificationRulePage(
        rows.map(MessageClassificationService::rule), effectivePage, effectiveSize, total);
  }

  /** 新建一条规则。 */
  @Transactional
  public CrmMessageClassificationRuleResult createRule(
      CrmMessageClassificationRuleInput input, UUID actorId) {
    String code = nz(input.ruleCode()).trim();
    if (code.isEmpty()) {
      throw bad("规则编码不能为空");
    }
    if (ruleExists(code)) {
      throw bad("规则编码「" + code + "」已存在：编码是运营引用规则的唯一名字，不能重名。");
    }
    List<String> keywords = cleanList(input.keywords());
    if (keywords.isEmpty()) {
      throw bad(
          "规则必须有至少一个关键词：一条没有关键词的规则永远不会命中，"
              + "却会占掉一个顺序位 —— 那是一条看起来配了、其实没生效的规则。");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_message_classification_rule"
            + "(id,tenant_id,rule_code,rule_name,priority_order,target_label,suggested_priority,"
            + " auto_reply_eligible,keywords,match_mode,platform_codes,message_types,enabled,"
            + " note,created_by,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        code,
        nz(input.ruleName()).trim(),
        input.priorityOrder() == null ? 100 : input.priorityOrder(),
        oneOf(RULE_LABELS, input.targetLabel(), "规则目标标签"),
        oneOf(PRIORITIES, input.suggestedPriority(), "规则建议优先级"),
        Boolean.TRUE.equals(input.autoReplyEligible()),
        String.join("\n", keywords),
        oneOf(MATCH_MODES, input.matchMode() == null ? "ANY" : input.matchMode(), "命中方式"),
        joinCodes(input.platformCodes()),
        joinCodes(input.messageTypes()),
        input.enabled() == null || input.enabled(),
        nz(input.note()),
        actorId,
        moment,
        moment);
    return new CrmMessageClassificationRuleResult(
        requireRule(id), "规则已创建。分类时按顺序（数字小的先）逐条匹配，命中的关键词会记进命中依据。");
  }

  /** 修改一条规则（编码不可改：命中依据里存的是编码）。 */
  @Transactional
  public CrmMessageClassificationRuleResult updateRule(
      UUID ruleId, CrmMessageClassificationRuleInput input, UUID actorId) {
    CrmMessageClassificationRuleRow existing = requireRule(ruleId);
    String code = nz(input.ruleCode()).trim();
    if (!code.isEmpty() && !code.equals(existing.ruleCode())) {
      throw bad(
          "规则编码建后不可改：历史命中依据里存的是编码「"
              + existing.ruleCode()
              + "」，改了它历史结论就对不上。请新建一条规则再停用旧的。");
    }
    List<String> keywords = cleanList(input.keywords());
    if (keywords.isEmpty()) {
      throw bad(
          "规则必须有至少一个关键词：一条没有关键词的规则永远不会命中，"
              + "却会占掉一个顺序位 —— 那是一条看起来配了、其实没生效的规则。");
    }
    OffsetDateTime moment = now();
    sql.execute(
        "update crm_message_classification_rule"
            + " set rule_name=?, priority_order=?, target_label=?, suggested_priority=?,"
            + " auto_reply_eligible=?, keywords=?, match_mode=?, platform_codes=?, message_types=?,"
            + " enabled=?, note=?, updated_at=?::timestamptz"
            + " where tenant_id=? and id=?",
        nz(input.ruleName()).trim(),
        input.priorityOrder() == null ? 100 : input.priorityOrder(),
        oneOf(RULE_LABELS, input.targetLabel(), "规则目标标签"),
        oneOf(PRIORITIES, input.suggestedPriority(), "规则建议优先级"),
        Boolean.TRUE.equals(input.autoReplyEligible()),
        String.join("\n", keywords),
        oneOf(MATCH_MODES, input.matchMode() == null ? "ANY" : input.matchMode(), "命中方式"),
        joinCodes(input.platformCodes()),
        joinCodes(input.messageTypes()),
        input.enabled() == null || input.enabled(),
        nz(input.note()),
        moment,
        currentTenant(),
        ruleId);
    return new CrmMessageClassificationRuleResult(
        requireRule(ruleId),
        "规则已更新。**已分类的消息不会被自动改写** —— 重新分类请走 POST /classify，"
            + "否则历史结论与新规则对不上。");
  }

  /**
   * 删除一条规则。
   *
   * <p>⚠️ 只删**规则定义**，不动已产生的分类结论与纠正记录 ——
   * 结论里的命中依据自带规则编码与名称快照，删了规则也查得到「当时命中的是哪条」。
   */
  @Transactional
  public CrmMessageClassificationRuleResult deleteRule(UUID ruleId) {
    CrmMessageClassificationRuleRow rule = requireRule(ruleId);
    sql.execute(
        "delete from crm_message_classification_rule where tenant_id=? and id=?",
        currentTenant(),
        ruleId);
    return new CrmMessageClassificationRuleResult(
        rule,
        "规则已删除。已产生的分类结论与人工纠正**不受影响**（命中依据自带规则编码与名称快照）。");
  }

  /** 单条规则。 */
  public CrmMessageClassificationRuleRow requireRule(UUID ruleId) {
    var row =
        sql.fetchOne(
            "select r.id,r.rule_code,r.rule_name,r.priority_order,r.target_label,"
                + " r.suggested_priority,r.auto_reply_eligible,r.keywords,r.match_mode,"
                + " r.platform_codes,r.message_types,r.enabled,r.note,r.created_at,r.updated_at"
                + " from crm_message_classification_rule r where r.tenant_id=? and r.id=?",
            currentTenant(),
            ruleId);
    if (row == null) {
      throw notFound("规则不存在，或不属于当前租户");
    }
    return rule(row);
  }

  // ================================================================ 分类

  /** 分类一条消息。 */
  @Transactional
  public CrmMessageClassificationClassifyResult classify(
      CrmMessageClassificationClassifyInput input, UUID actorId) {
    return classifyOne(input.messageId(), actorId);
  }

  /** 批量分类：逐条给出结论与原因，失败的条目不会被悄悄跳过。 */
  @Transactional
  public CrmMessageClassificationBatchResult classifyBatch(
      CrmMessageClassificationBatchInput input, UUID actorId) {
    List<CrmMessageClassificationClassifyResult> items = new ArrayList<>();
    int succeeded = 0;
    for (UUID messageId : input.messageIds()) {
      CrmMessageClassificationClassifyResult item = classifyOne(messageId, actorId);
      if (item.success()) {
        succeeded++;
      }
      items.add(item);
    }
    return new CrmMessageClassificationBatchResult(
        input.messageIds().size(),
        succeeded,
        input.messageIds().size() - succeeded,
        items,
        "分类**只写结论**，**不改** crm_message.priority（那是人工设过的值）；"
            + "是否可自动回复只是**判定结果**，15.5.1 自动回复未交付，本模块不触发任何回复。");
  }

  /** 单条分类的执行体。 */
  private CrmMessageClassificationClassifyResult classifyOne(UUID messageId, UUID actorId) {
    var message =
        sql.fetchOne(
            "select m.id,m.message_no,m.shop_name,m.platform_code,m.buyer_reference,m.order_reference,"
                + " m.subject,m.body,m.message_type,m.direction,m.priority,m.platform_time"
                + " from crm_message m where m.tenant_id=? and m.id=?",
            currentTenant(),
            messageId);
    if (message == null) {
      return failed(messageId, "", "消息不存在，或不属于当前租户");
    }
    String messageNo = nz(message.get("message_no", String.class));
    String subject = nz(message.get("subject", String.class));
    String body = nz(message.get("body", String.class));
    String platformCode = nz(message.get("platform_code", String.class));
    String messageType = nz(message.get("message_type", String.class));
    String manualPriority = nz(message.get("priority", String.class));

    // 规则集：**只取启用的**，按 (顺序, 编码) 走 —— 顺序显式，命中结果可复现。
    List<Rule> rules =
        sql.fetch(
                "select r.id,r.rule_code,r.rule_name,r.priority_order,r.target_label,"
                    + " r.suggested_priority,r.auto_reply_eligible,r.keywords,r.match_mode,"
                    + " r.platform_codes,r.message_types"
                    + " from crm_message_classification_rule r"
                    + " where r.tenant_id=? and r.enabled order by r.priority_order, r.rule_code",
                currentTenant())
            .map(MessageClassificationService::toRule);

    List<RuleMatch> matches = new ArrayList<>();
    Rule winner = null;
    for (Rule rule : rules) {
      if (!rule.appliesTo(platformCode, messageType)) {
        continue;
      }
      List<CrmMessageClassificationKeywordHit> hits = rule.hit(subject, body);
      if (hits.isEmpty()) {
        continue;
      }
      // ALL 模式：必须**全部**关键词都命中才算命中这条规则。
      if ("ALL".equals(rule.matchMode()) && hits.size() < rule.keywords().size()) {
        continue;
      }
      boolean won = winner == null;
      if (won) {
        winner = rule;
      }
      matches.add(
          new RuleMatch(
              rule.id(),
              rule.ruleCode(),
              rule.ruleName(),
              rule.priorityOrder(),
              rule.targetLabel(),
              won,
              hits));
    }

    String label = winner == null ? UNCLASSIFIED : winner.targetLabel();
    String suggestedPriority = winner == null ? "NORMAL" : winner.suggestedPriority();
    boolean autoReply = winner != null && winner.autoReplyEligible();
    // 🔴 人工值优先：**不覆写** crm_message.priority，只如实表达分歧。
    boolean conflict = winner != null && !suggestedPriority.equals(manualPriority);
    String effectivePriority = manualPriority;
    String matchedRuleCode = winner == null ? "" : winner.ruleCode();
    String matchedRuleName = winner == null ? "" : winner.ruleName();
    UUID matchedRuleId = winner == null ? null : winner.id();
    String basis = writeBasis(winner, matches);

    OffsetDateTime moment = now();
    var existing =
        sql.fetchOne(
            "select id, classification_no from crm_message_classification"
                + " where tenant_id=? and message_id=?",
            currentTenant(),
            messageId);
    UUID classificationId;
    String classificationNo;
    if (existing == null) {
      classificationId = UUID.randomUUID();
      classificationNo = nextClassificationNo();
      sql.execute(
          "insert into crm_message_classification"
              + "(id,tenant_id,classification_no,message_id,label,suggested_priority,"
              + " manual_priority,effective_priority,priority_conflict,auto_reply_eligible,"
              + " matched_rule_id,matched_rule_code,matched_rule_name,match_basis,"
              + " classifier_version,classified_at,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?::timestamptz,?::timestamptz,?::timestamptz)",
          classificationId,
          currentTenant(),
          classificationNo,
          messageId,
          label,
          suggestedPriority,
          manualPriority,
          effectivePriority,
          conflict,
          autoReply,
          matchedRuleId,
          matchedRuleCode,
          matchedRuleName,
          basis,
          CLASSIFIER_VERSION,
          moment,
          moment,
          moment);
    } else {
      classificationId = existing.get("id", UUID.class);
      classificationNo = nz(existing.get("classification_no", String.class));
      sql.execute(
          "update crm_message_classification"
              + " set label=?, suggested_priority=?, manual_priority=?, effective_priority=?,"
              + " priority_conflict=?, auto_reply_eligible=?, matched_rule_id=?, matched_rule_code=?,"
              + " matched_rule_name=?, match_basis=?::jsonb, classifier_version=?,"
              + " classified_at=?::timestamptz, updated_at=?::timestamptz"
              + " where tenant_id=? and id=?",
          label,
          suggestedPriority,
          manualPriority,
          effectivePriority,
          conflict,
          autoReply,
          matchedRuleId,
          matchedRuleCode,
          matchedRuleName,
          basis,
          CLASSIFIER_VERSION,
          moment,
          moment,
          currentTenant(),
          classificationId);
    }
    return new CrmMessageClassificationClassifyResult(
        messageId,
        messageNo,
        true,
        classificationId,
        classificationNo,
        label,
        suggestedPriority,
        manualPriority,
        effectivePriority,
        conflict,
        autoReply,
        matchedRuleCode.isEmpty() ? null : matchedRuleCode,
        moment.toString(),
        winner == null
            ? "没有规则命中：标签记为「未分类」，不建议自动回复（说不清是什么的消息自动回，风险由买家承担）。"
            : "命中规则 " + matchedRuleCode + "：" + describeHits(matches.get(0).keywords())
                + (conflict
                    ? "；建议优先级 " + suggestedPriority + " 与人工设的 " + manualPriority + " **不一致**，"
                        + "已按人工值生效（priorityConflict=true），要不要改由人决定。"
                    : "；建议优先级 " + suggestedPriority + "，与人工设的一致。"));
  }

  // ================================================================ 结论列表 / 详情

  /** 分类结论列表：真分页 + 真筛选 + 与分页无关的全量汇总。 */
  public CrmMessageClassificationPage classifications(
      UUID messageId,
      String label,
      String suggestedPriority,
      Boolean priorityConflict,
      Boolean autoReplyEligible,
      String keyword,
      int page,
      int size) {
    int effectiveSize = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    int effectivePage = Math.max(page, 0);
    Filter filter =
        classificationFilter(
            messageId, label, suggestedPriority, priorityConflict, autoReplyEligible, keyword);
    var rows =
        sql.fetch(
            "select c.id,c.classification_no,c.message_id,c.label,c.suggested_priority,"
                + " c.manual_priority,c.effective_priority,c.priority_conflict,"
                + " c.auto_reply_eligible,c.matched_rule_id,c.matched_rule_code,"
                + " c.matched_rule_name,c.match_basis::text as match_basis,c.classifier_version,"
                + " c.classified_at,c.created_at,c.updated_at,"
                + " m.message_no,m.subject,m.platform_code,m.buyer_reference,m.order_reference,"
                + " coalesce(fb.cnt,0) as correction_count, fb.latest_label as latest_corrected_label"
                + CLASSIFICATION_FROM
                + " left join lateral ("
                + "   select count(*) as cnt,"
                + "     (array_agg(f.corrected_label order by f.corrected_at desc))[1] as latest_label"
                + "   from crm_message_classification_feedback f"
                + "   where f.tenant_id = c.tenant_id and f.message_id = c.message_id"
                + " ) fb on true"
                + filter.where()
                + " order by c.classified_at desc, c.id"
                + " limit ? offset ?",
            bindings(filter.binds(), effectiveSize, effectivePage * effectiveSize));
    long total =
        sql.fetchOne(
                "select count(*)" + CLASSIFICATION_FROM + filter.where(),
                filter.binds().toArray())
            .get(0, Long.class);
    return new CrmMessageClassificationPage(
        rows.map(this::classification),
        effectivePage,
        effectiveSize,
        total,
        classificationSummary(filter));
  }

  /** 单条分类详情 = 结论 + 命中依据 + 消息正文 + 纠正历史。 */
  public CrmMessageClassificationDetail detail(UUID classificationId) {
    CrmMessageClassificationRow row = requireClassification(classificationId);
    return new CrmMessageClassificationDetail(
        row, messageView(row.messageId()), feedbackOf(row.messageId()));
  }

  /** 某条消息的当前分类结论 + 命中依据 + 纠正历史。 */
  public CrmMessageClassificationDetail ofMessage(UUID messageId) {
    var row =
        sql.fetchOne(
            "select c.id from crm_message_classification c where c.tenant_id=? and c.message_id=?",
            currentTenant(),
            messageId);
    if (row == null) {
      throw notFound(
          "这条消息还没有分类结论：先调用 POST /classify 分类，再来看结论与命中依据。");
    }
    CrmMessageClassificationRow classification =
        requireClassification(row.get("id", UUID.class));
    return new CrmMessageClassificationDetail(
        classification, messageView(messageId), feedbackOf(messageId));
  }

  // ================================================================ 人工纠正

  /**
   * 人工纠正（= 训练样本的来源）。
   *
   * <p>🔴 纠正**只追加**：不回写分类结论里的标签/优先级（结论永远是「机器当时说了什么」），
   * 并且自带机器预测快照（不然训练样本只剩正确答案，学不出错在哪）。
   */
  @Transactional
  public CrmMessageClassificationFeedbackResult correct(
      CrmMessageClassificationFeedbackInput input, UUID actorId) {
    var row =
        sql.fetchOne(
            "select c.id,c.classification_no,c.message_id,c.label,c.suggested_priority,"
                + " c.matched_rule_id,c.matched_rule_code,c.manual_priority"
                + " from crm_message_classification c where c.tenant_id=? and c.message_id=?",
            currentTenant(),
            input.messageId());
    if (row == null) {
      throw bad(
          "这条消息还没有分类结论：先分类（POST /classify）再纠正 —— "
              + "纠正必须记下「机器当时判成了什么」，否则训练样本只剩正确答案。");
    }
    String reason = nz(input.reason()).trim();
    if (reason.isEmpty()) {
      throw bad("纠正必须写明理由：没有理由的纠正没法复核，也没法当训练样本用。");
    }
    UUID classificationId = row.get("id", UUID.class);
    OffsetDateTime moment = now();
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into crm_message_classification_feedback"
            + "(id,tenant_id,message_id,classification_id,predicted_label,predicted_priority,"
            + " predicted_rule_id,predicted_rule_code,corrected_label,corrected_priority,"
            + " corrected_auto_reply_eligible,reason,corrected_by,corrected_at,created_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        input.messageId(),
        classificationId,
        nz(row.get("label", String.class)),
        nz(row.get("suggested_priority", String.class)),
        row.get("matched_rule_id", UUID.class),
        nz(row.get("matched_rule_code", String.class)),
        oneOf(LABELS, input.correctedLabel(), "人工纠正后的标签"),
        oneOf(PRIORITIES, input.correctedPriority(), "人工纠正后的优先级"),
        Boolean.TRUE.equals(input.correctedAutoReplyEligible()),
        reason,
        actorId,
        moment,
        moment);
    return new CrmMessageClassificationFeedbackResult(
        feedbackOf(input.messageId()).stream()
            .filter(f -> f.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("纠正写入后读不回来")),
        "纠正已记录（**只追加**：分类结论本身没被改写，机器判成什么仍然查得到）。"
            + "这一条已经是训练样本：机器预测 + 人工判断 + 消息正文三件套都在。");
  }

  /** 纠正记录列表：真分页 + 真筛选。 */
  public CrmMessageClassificationFeedbackPage feedback(
      UUID messageId, String correctedLabel, String predictedLabel, String ruleCode, int page, int size) {
    int effectiveSize = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    int effectivePage = Math.max(page, 0);
    Filter filter = feedbackFilter(messageId, correctedLabel, predictedLabel, ruleCode);
    var rows =
        sql.fetch(
            "select f.id,f.message_id,f.classification_id,f.predicted_label,f.predicted_priority,"
                + " f.predicted_rule_id,f.predicted_rule_code,f.corrected_label,"
                + " f.corrected_priority,f.corrected_auto_reply_eligible,f.reason,f.corrected_by,"
                + " f.corrected_at"
                + " from crm_message_classification_feedback f"
                + filter.where()
                + " order by f.corrected_at desc, f.id"
                + " limit ? offset ?",
            bindings(filter.binds(), effectiveSize, effectivePage * effectiveSize));
    long total =
        sql.fetchOne(
                "select count(*) from crm_message_classification_feedback f" + filter.where(),
                filter.binds().toArray())
            .get(0, Long.class);
    return new CrmMessageClassificationFeedbackPage(
        rows.map(MessageClassificationService::feedback), effectivePage, effectiveSize, total);
  }

  // ================================================================ 训练样本

  /**
   * 训练样本：机器预测 + 人工纠正 + 消息正文。
   *
   * <p>🔴 蓝图加粗句「人工纠正要回流为训练样本」的落点就是这个视图 ——
   * 正文必须一并带出，否则样本只剩标签对。
   */
  public CrmMessageClassificationTrainingSamplePage trainingSamples(
      String correctedLabel, String predictedLabel, String ruleCode, String keyword, int page, int size) {
    int effectiveSize = size <= 0 ? 50 : Math.min(size, MAX_PAGE_SIZE);
    int effectivePage = Math.max(page, 0);
    Filter filter = sampleFilter(correctedLabel, predictedLabel, ruleCode, keyword);
    var rows =
        sql.fetch(
            "select f.id,f.message_id,f.predicted_label,f.predicted_priority,f.predicted_rule_code,"
                + " f.corrected_label,f.corrected_priority,f.corrected_auto_reply_eligible,f.reason,"
                + " f.corrected_by,f.corrected_at,"
                + " m.message_no,m.subject,m.body,m.platform_code,m.message_type,m.buyer_reference,"
                + " m.order_reference"
                + " from crm_message_classification_feedback f"
                + " join crm_message m on m.tenant_id = f.tenant_id and m.id = f.message_id"
                + filter.where()
                + " order by f.corrected_at desc, f.id"
                + " limit ? offset ?",
            bindings(filter.binds(), effectiveSize, effectivePage * effectiveSize));
    long total =
        sql.fetchOne(
                "select count(*) from crm_message_classification_feedback f"
                    + " join crm_message m on m.tenant_id = f.tenant_id and m.id = f.message_id"
                    + filter.where(),
                filter.binds().toArray())
            .get(0, Long.class);
    return new CrmMessageClassificationTrainingSamplePage(
        rows.map(MessageClassificationService::sample), effectivePage, effectiveSize, total);
  }

  // ================================================================ 统计

  /**
   * 分类统计：规则命中数 + 标签分布 + 冲突数 + 纠正一致率。
   *
   * <p>⚠️ 「一致率」的分母是**人工纠正过的条数**，不是全部消息 ——
   * 没人纠正过的消息不会进分母，所以这个数字**不是**全量准确率，
   * 响应里带着 {@code note} 说清这一点。
   */
  public CrmMessageClassificationStatistics statistics() {
    UUID tenant = currentTenant();
    var totals =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where priority_conflict) as conflict,"
                + " count(*) filter (where auto_reply_eligible) as auto_reply"
                + " from crm_message_classification where tenant_id=?",
            tenant);
    long totalCount = totals.get("total", Long.class);
    long conflictCount = totals.get("conflict", Long.class);
    long autoReplyCount = totals.get("auto_reply", Long.class);

    var corrections =
        sql.fetchOne(
            "select count(*) as cnt,"
                + " count(*) filter (where corrected_label = predicted_label) as agreed"
                + " from crm_message_classification_feedback where tenant_id=?",
            tenant);
    long correctionCount = corrections.get("cnt", Long.class);
    long agreementCount = corrections.get("agreed", Long.class);
    BigDecimal agreementRate =
        correctionCount == 0
            ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
            : BigDecimal.valueOf(agreementCount)
                .divide(BigDecimal.valueOf(correctionCount), 4, RoundingMode.HALF_UP);

    List<CrmMessageClassificationBucket> byLabel = buckets("c.label", tenantFilter());
    List<CrmMessageClassificationBucket> byPriority =
        buckets("c.suggested_priority", tenantFilter());

    List<CrmMessageClassificationRuleStat> byRule = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select r.id,r.rule_code,r.rule_name,r.target_label,r.priority_order,r.enabled,"
                + " coalesce(h.hit_count,0) as hit_count,"
                + " coalesce(f.correction_count,0) as correction_count,"
                + " coalesce(f.disagreement_count,0) as disagreement_count"
                + " from crm_message_classification_rule r"
                + " left join ("
                + "   select (m->>'ruleId')::uuid as rule_id, count(*) as hit_count"
                + "   from crm_message_classification c,"
                + "     lateral jsonb_array_elements(coalesce(c.match_basis->'matches','[]'::jsonb)) as m"
                + "   where c.tenant_id = ? group by (m->>'ruleId')::uuid"
                + " ) h on h.rule_id = r.id"
                + " left join ("
                + "   select predicted_rule_id as rule_id, count(*) as correction_count,"
                + "     count(*) filter (where corrected_label <> predicted_label) as disagreement_count"
                + "   from crm_message_classification_feedback"
                + "   where tenant_id = ? and predicted_rule_id is not null"
                + "   group by predicted_rule_id"
                + " ) f on f.rule_id = r.id"
                + " where r.tenant_id = ? order by r.priority_order, r.rule_code",
            tenant,
            tenant,
            tenant)) {
      byRule.add(
          new CrmMessageClassificationRuleStat(
              r.get("id", UUID.class),
              nz(r.get("rule_code", String.class)),
              nz(r.get("rule_name", String.class)),
              nz(r.get("target_label", String.class)),
              r.get("priority_order", Integer.class) == null ? 0 : r.get("priority_order", Integer.class),
              Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
              r.get("hit_count", Long.class),
              r.get("correction_count", Long.class),
              r.get("disagreement_count", Long.class)));
    }

    return new CrmMessageClassificationStatistics(
        totalCount,
        conflictCount,
        autoReplyCount,
        correctionCount,
        agreementCount,
        agreementRate,
        byLabel,
        byPriority,
        byRule,
        "⚠️ 「纠正一致率」的分母是**人工纠正过的条数**，不是全部消息 —— "
            + "没人纠正过的消息不进分母，所以它**不是**全量准确率，"
            + "只说明「被纠正的那些里，机器原本判对了多少」。");
  }

  // ================================================================ 内部工具

  /** 筛选条件（WHERE 片段 + 绑定值）。 */
  private record Filter(String where, List<Object> binds) {}

  /** 只按租户过滤（统计口径用）。 */
  private Filter tenantFilter() {
    return new Filter(" where c.tenant_id = ?", new ArrayList<>(List.of(currentTenant())));
  }

  /** 规则（来自 DB 的真实数据）。 */
  private record Rule(
      UUID id,
      String ruleCode,
      String ruleName,
      int priorityOrder,
      String targetLabel,
      String suggestedPriority,
      boolean autoReplyEligible,
      List<String> keywords,
      String matchMode,
      List<String> platformCodes,
      List<String> messageTypes) {

    /** 适用范围：平台与消息类型都为空时适用全部。 */
    boolean appliesTo(String platformCode, String messageType) {
      if (!platformCodes.isEmpty() && !platformCodes.contains(platformCode)) {
        return false;
      }
      return messageTypes.isEmpty() || messageTypes.contains(messageType);
    }

    /** 关键词命中（大小写不敏感的**字面**包含；先看主题再看正文）。 */
    List<CrmMessageClassificationKeywordHit> hit(String subject, String body) {
      String subjectLower = subject.toLowerCase(Locale.ROOT);
      String bodyLower = body.toLowerCase(Locale.ROOT);
      List<CrmMessageClassificationKeywordHit> hits = new ArrayList<>();
      for (String keyword : keywords) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        if (subjectLower.contains(lower)) {
          hits.add(new CrmMessageClassificationKeywordHit(keyword, "SUBJECT"));
        } else if (bodyLower.contains(lower)) {
          hits.add(new CrmMessageClassificationKeywordHit(keyword, "BODY"));
        }
      }
      return hits;
    }
  }

  /** 一条规则的命中结果。 */
  private record RuleMatch(
      UUID ruleId,
      String ruleCode,
      String ruleName,
      int ruleOrder,
      String targetLabel,
      boolean won,
      List<CrmMessageClassificationKeywordHit> keywords) {}

  /** 规则筛选。 */
  private Filter ruleFilter(String keyword, String targetLabel, Boolean enabled) {
    var where = new StringBuilder(" where r.tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    String label = blankToNull(targetLabel);
    if (label != null) {
      where.append(" and r.target_label = ?");
      binds.add(label);
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
              + " or r.keywords ilike ? escape '\\')");
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    return new Filter(where.toString(), binds);
  }

  /** 分类结论筛选。 */
  private Filter classificationFilter(
      UUID messageId,
      String label,
      String suggestedPriority,
      Boolean priorityConflict,
      Boolean autoReplyEligible,
      String keyword) {
    var where = new StringBuilder(" where c.tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (messageId != null) {
      where.append(" and c.message_id = ?");
      binds.add(messageId);
    }
    String labelValue = blankToNull(label);
    if (labelValue != null) {
      where.append(" and c.label = ?");
      binds.add(labelValue);
    }
    String priorityValue = blankToNull(suggestedPriority);
    if (priorityValue != null) {
      where.append(" and c.suggested_priority = ?");
      binds.add(priorityValue);
    }
    if (priorityConflict != null) {
      where.append(" and c.priority_conflict = ?");
      binds.add(priorityConflict);
    }
    if (autoReplyEligible != null) {
      where.append(" and c.auto_reply_eligible = ?");
      binds.add(autoReplyEligible);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      String pattern = likePattern(word);
      where.append(
          " and (m.subject ilike ? escape '\\' or m.body ilike ? escape '\\'"
              + " or m.message_no ilike ? escape '\\' or c.matched_rule_code ilike ? escape '\\')");
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    return new Filter(where.toString(), binds);
  }

  /** 纠正记录筛选。 */
  private Filter feedbackFilter(
      UUID messageId, String correctedLabel, String predictedLabel, String ruleCode) {
    var where = new StringBuilder(" where f.tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    if (messageId != null) {
      where.append(" and f.message_id = ?");
      binds.add(messageId);
    }
    String corrected = blankToNull(correctedLabel);
    if (corrected != null) {
      where.append(" and f.corrected_label = ?");
      binds.add(corrected);
    }
    String predicted = blankToNull(predictedLabel);
    if (predicted != null) {
      where.append(" and f.predicted_label = ?");
      binds.add(predicted);
    }
    String rule = blankToNull(ruleCode);
    if (rule != null) {
      where.append(" and f.predicted_rule_code = ?");
      binds.add(rule);
    }
    return new Filter(where.toString(), binds);
  }

  /** 训练样本筛选。 */
  private Filter sampleFilter(
      String correctedLabel, String predictedLabel, String ruleCode, String keyword) {
    var where = new StringBuilder(" where f.tenant_id = ?");
    var binds = new ArrayList<Object>();
    binds.add(currentTenant());
    String corrected = blankToNull(correctedLabel);
    if (corrected != null) {
      where.append(" and f.corrected_label = ?");
      binds.add(corrected);
    }
    String predicted = blankToNull(predictedLabel);
    if (predicted != null) {
      where.append(" and f.predicted_label = ?");
      binds.add(predicted);
    }
    String rule = blankToNull(ruleCode);
    if (rule != null) {
      where.append(" and f.predicted_rule_code = ?");
      binds.add(rule);
    }
    String word = blankToNull(keyword);
    if (word != null) {
      String pattern = likePattern(word);
      where.append(
          " and (m.subject ilike ? escape '\\' or m.body ilike ? escape '\\'"
              + " or m.message_no ilike ? escape '\\')");
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    return new Filter(where.toString(), binds);
  }

  /** 分类结论汇总（与分页无关的全量口径）。 */
  private CrmMessageClassificationSummary classificationSummary(Filter filter) {
    var row =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where c.priority_conflict) as conflict,"
                + " count(*) filter (where c.auto_reply_eligible) as auto_reply,"
                + " count(*) filter (where exists ("
                + "   select 1 from crm_message_classification_feedback f"
                + "   where f.tenant_id = c.tenant_id and f.message_id = c.message_id)) as corrected"
                + CLASSIFICATION_FROM
                + filter.where(),
            filter.binds().toArray());
    return new CrmMessageClassificationSummary(
        row.get("total", Long.class),
        row.get("conflict", Long.class),
        row.get("auto_reply", Long.class),
        row.get("corrected", Long.class),
        buckets("c.label", filter),
        buckets("c.suggested_priority", filter));
  }

  /** 按一列分组的统计（只列**真实出现过**的取值域）。 */
  private List<CrmMessageClassificationBucket> buckets(String column, Filter filter) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Record record :
        sql.fetch(
            "select " + column + " as code, count(*) as cnt"
                + CLASSIFICATION_FROM
                + filter.where()
                + " group by "
                + column
                + " order by cnt desc, code",
            filter.binds().toArray())) {
      counts.put(record.get("code", String.class), record.get("cnt", Long.class));
    }
    List<CrmMessageClassificationBucket> out = new ArrayList<>();
    counts.forEach(
        (code, count) ->
            out.add(new CrmMessageClassificationBucket(code, labelsOf(code), count)));
    return out;
  }

  /** 标签取值域的中文标签（服务端给，前端只原样展示）。 */
  private static String labelsOf(String code) {
    String label = LABEL_LABELS.get(code);
    if (label != null) {
      return label;
    }
    return PRIORITY_LABELS.getOrDefault(code, code);
  }

  /** 规则是否已存在（按编码）。 */
  private boolean ruleExists(String code) {
    return sql.fetchOne(
            "select id from crm_message_classification_rule where tenant_id=? and rule_code=?",
            currentTenant(),
            code)
        != null;
  }

  /** 单条分类结论。 */
  private CrmMessageClassificationRow requireClassification(UUID classificationId) {
    var row =
        sql.fetchOne(
            "select c.id,c.classification_no,c.message_id,c.label,c.suggested_priority,"
                + " c.manual_priority,c.effective_priority,c.priority_conflict,"
                + " c.auto_reply_eligible,c.matched_rule_id,c.matched_rule_code,"
                + " c.matched_rule_name,c.match_basis::text as match_basis,c.classifier_version,"
                + " c.classified_at,c.created_at,c.updated_at,"
                + " m.message_no,m.subject,m.platform_code,m.buyer_reference,m.order_reference,"
                + " coalesce(fb.cnt,0) as correction_count, fb.latest_label as latest_corrected_label"
                + " from crm_message_classification c"
                + " join crm_message m on m.tenant_id = c.tenant_id and m.id = c.message_id"
                + " left join lateral ("
                + "   select count(*) as cnt,"
                + "     (array_agg(f.corrected_label order by f.corrected_at desc))[1] as latest_label"
                + "   from crm_message_classification_feedback f"
                + "   where f.tenant_id = c.tenant_id and f.message_id = c.message_id"
                + " ) fb on true"
                + " where c.tenant_id=? and c.id=?",
            currentTenant(),
            classificationId);
    if (row == null) {
      throw notFound("分类结论不存在，或不属于当前租户");
    }
    return classification(row);
  }

  /** 消息快照。 */
  private CrmMessageClassificationMessageView messageView(UUID messageId) {
    var row =
        sql.fetchOne(
            "select m.id,m.message_no,m.shop_name,m.platform_code,m.buyer_reference,m.order_reference,"
                + " m.subject,m.body,m.message_type,m.direction,m.priority,m.platform_time"
                + " from crm_message m where m.tenant_id=? and m.id=?",
            currentTenant(),
            messageId);
    if (row == null) {
      throw notFound("消息不存在，或不属于当前租户");
    }
    return new CrmMessageClassificationMessageView(
        row.get("id", UUID.class),
        nz(row.get("message_no", String.class)),
        nz(row.get("shop_name", String.class)),
        nz(row.get("platform_code", String.class)),
        nz(row.get("buyer_reference", String.class)),
        nz(row.get("order_reference", String.class)),
        nz(row.get("subject", String.class)),
        nz(row.get("body", String.class)),
        nz(row.get("message_type", String.class)),
        nz(row.get("direction", String.class)),
        nz(row.get("priority", String.class)),
        str(row.get("platform_time", OffsetDateTime.class)));
  }

  /** 某条消息的纠正历史。 */
  private List<CrmMessageClassificationFeedbackRow> feedbackOf(UUID messageId) {
    List<CrmMessageClassificationFeedbackRow> out = new ArrayList<>();
    for (Record r :
        sql.fetch(
            "select f.id,f.message_id,f.classification_id,f.predicted_label,f.predicted_priority,"
                + " f.predicted_rule_id,f.predicted_rule_code,f.corrected_label,"
                + " f.corrected_priority,f.corrected_auto_reply_eligible,f.reason,f.corrected_by,"
                + " f.corrected_at"
                + " from crm_message_classification_feedback f"
                + " where f.tenant_id=? and f.message_id=? order by f.corrected_at desc, f.id",
            currentTenant(),
            messageId)) {
      out.add(feedback(r));
    }
    return out;
  }

  /** 记录 → 规则行。 */
  private static CrmMessageClassificationRuleRow rule(Record r) {
    return new CrmMessageClassificationRuleRow(
        r.get("id", UUID.class),
        nz(r.get("rule_code", String.class)),
        nz(r.get("rule_name", String.class)),
        r.get("priority_order", Integer.class) == null ? 0 : r.get("priority_order", Integer.class),
        nz(r.get("target_label", String.class)),
        nz(r.get("suggested_priority", String.class)),
        Boolean.TRUE.equals(r.get("auto_reply_eligible", Boolean.class)),
        splitLines(r.get("keywords", String.class)),
        nz(r.get("match_mode", String.class)),
        splitCodes(r.get("platform_codes", String.class)),
        splitCodes(r.get("message_types", String.class)),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  /** 记录 → 规则（内部形态）。 */
  private static Rule toRule(Record r) {
    return new Rule(
        r.get("id", UUID.class),
        nz(r.get("rule_code", String.class)),
        nz(r.get("rule_name", String.class)),
        r.get("priority_order", Integer.class) == null ? 0 : r.get("priority_order", Integer.class),
        nz(r.get("target_label", String.class)),
        nz(r.get("suggested_priority", String.class)),
        Boolean.TRUE.equals(r.get("auto_reply_eligible", Boolean.class)),
        splitLines(r.get("keywords", String.class)),
        nz(r.get("match_mode", String.class)),
        splitCodes(r.get("platform_codes", String.class)),
        splitCodes(r.get("message_types", String.class)));
  }

  /** 记录 → 分类结论（命中依据要从 jsonb 解析，所以是实例方法）。 */
  private CrmMessageClassificationRow classification(Record r) {
    return new CrmMessageClassificationRow(
        r.get("id", UUID.class),
        nz(r.get("classification_no", String.class)),
        r.get("message_id", UUID.class),
        nz(r.get("message_no", String.class)),
        nz(r.get("subject", String.class)),
        nz(r.get("platform_code", String.class)),
        nz(r.get("buyer_reference", String.class)),
        nz(r.get("order_reference", String.class)),
        nz(r.get("label", String.class)),
        nz(r.get("suggested_priority", String.class)),
        nz(r.get("manual_priority", String.class)),
        nz(r.get("effective_priority", String.class)),
        Boolean.TRUE.equals(r.get("priority_conflict", Boolean.class)),
        Boolean.TRUE.equals(r.get("auto_reply_eligible", Boolean.class)),
        r.get("matched_rule_id", UUID.class),
        nz(r.get("matched_rule_code", String.class)),
        nz(r.get("matched_rule_name", String.class)),
        readBasis(r.get("match_basis", String.class)),
        nz(r.get("classifier_version", String.class)),
        str(r.get("classified_at", OffsetDateTime.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)),
        r.get("correction_count", Long.class) == null ? 0L : r.get("correction_count", Long.class),
        r.get("latest_corrected_label", String.class));
  }

  /** 记录 → 纠正行。 */
  private static CrmMessageClassificationFeedbackRow feedback(Record r) {
    return new CrmMessageClassificationFeedbackRow(
        r.get("id", UUID.class),
        r.get("message_id", UUID.class),
        r.get("classification_id", UUID.class),
        nz(r.get("predicted_label", String.class)),
        nz(r.get("predicted_priority", String.class)),
        r.get("predicted_rule_id", UUID.class),
        nz(r.get("predicted_rule_code", String.class)),
        nz(r.get("corrected_label", String.class)),
        nz(r.get("corrected_priority", String.class)),
        Boolean.TRUE.equals(r.get("corrected_auto_reply_eligible", Boolean.class)),
        nz(r.get("reason", String.class)),
        r.get("corrected_by", UUID.class),
        str(r.get("corrected_at", OffsetDateTime.class)));
  }

  /** 记录 → 训练样本。 */
  private static CrmMessageClassificationTrainingSample sample(Record r) {
    String predicted = nz(r.get("predicted_label", String.class));
    String corrected = nz(r.get("corrected_label", String.class));
    return new CrmMessageClassificationTrainingSample(
        r.get("id", UUID.class),
        r.get("message_id", UUID.class),
        nz(r.get("message_no", String.class)),
        nz(r.get("subject", String.class)),
        nz(r.get("body", String.class)),
        nz(r.get("platform_code", String.class)),
        nz(r.get("message_type", String.class)),
        nz(r.get("buyer_reference", String.class)),
        nz(r.get("order_reference", String.class)),
        predicted,
        nz(r.get("predicted_priority", String.class)),
        nz(r.get("predicted_rule_code", String.class)),
        corrected,
        nz(r.get("corrected_priority", String.class)),
        Boolean.TRUE.equals(r.get("corrected_auto_reply_eligible", Boolean.class)),
        nz(r.get("reason", String.class)),
        r.get("corrected_by", UUID.class),
        str(r.get("corrected_at", OffsetDateTime.class)),
        predicted.equals(corrected));
  }

  /** 命中依据 → JSON（落库在 jsonb 列上）。 */
  private String writeBasis(Rule winner, List<RuleMatch> matches) {
    Map<String, Object> basis = new LinkedHashMap<>();
    basis.put("winningRuleId", winner == null ? null : winner.id().toString());
    basis.put("winningRuleCode", winner == null ? "" : winner.ruleCode());
    List<Map<String, Object>> items = new ArrayList<>();
    for (RuleMatch match : matches) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("ruleId", match.ruleId().toString());
      item.put("ruleCode", match.ruleCode());
      item.put("ruleName", match.ruleName());
      item.put("ruleOrder", match.ruleOrder());
      item.put("targetLabel", match.targetLabel());
      item.put("won", match.won());
      List<Map<String, String>> keywords = new ArrayList<>();
      for (CrmMessageClassificationKeywordHit hit : match.keywords()) {
        Map<String, String> keyword = new LinkedHashMap<>();
        keyword.put("keyword", hit.keyword());
        keyword.put("field", hit.field());
        keywords.add(keyword);
      }
      item.put("keywords", keywords);
      items.add(item);
    }
    basis.put("matches", items);
    try {
      return json.writeValueAsString(basis);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("命中依据序列化失败", e);
    }
  }

  /** 命中依据 JSON → 传输模型。 */
  private CrmMessageClassificationMatchBasis readBasis(String raw) {
    if (raw == null || raw.isBlank()) {
      return new CrmMessageClassificationMatchBasis(null, null, List.of());
    }
    try {
      CrmMessageClassificationMatchBasis parsed =
          json.readValue(raw, new TypeReference<CrmMessageClassificationMatchBasis>() {});
      return new CrmMessageClassificationMatchBasis(
          parsed.winningRuleId(),
          parsed.winningRuleCode(),
          parsed.matches() == null ? List.of() : parsed.matches());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("命中依据解析失败：" + raw, e);
    }
  }

  /** 命中关键词的可读描述。 */
  private static String describeHits(List<CrmMessageClassificationKeywordHit> hits) {
    List<String> parts = new ArrayList<>();
    for (CrmMessageClassificationKeywordHit hit : hits) {
      parts.add("「" + hit.keyword() + "」(" + ("SUBJECT".equals(hit.field()) ? "主题" : "正文") + ")");
    }
    return String.join("、", parts);
  }

  /** 分类失败的逐条结果。 */
  private static CrmMessageClassificationClassifyResult failed(UUID messageId, String messageNo, String reason) {
    return new CrmMessageClassificationClassifyResult(
        messageId,
        messageNo,
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
        reason);
  }

  /** 分类号：CLS-yyyyMMdd-000001（序列单独授权，见 V301 授权段）。 */
  private String nextClassificationNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_message_classification_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "CLS-" + day + "-" + String.format("%06d", seq);
  }

  /** 关键词 → DB 里的换行分隔文本。 */
  private static String joinCodes(List<String> values) {
    if (values == null) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    for (String value : values) {
      String trimmed = nz(value).trim();
      if (!trimmed.isEmpty()) {
        parts.add(trimmed);
      }
    }
    return String.join(",", parts);
  }

  /** 关键词列表（去空白、去空串）。 */
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

  /** 标签取值域的中文标签（服务端给，前端只原样展示）。 */
  private static final Map<String, String> LABEL_LABELS =
      Map.of(
          "PRE_SALE", "售前咨询",
          "LOGISTICS", "物流查询",
          "RETURN_REFUND", "退货退款",
          "COMPLAINT", "投诉",
          "PLATFORM_COMPLIANCE", "平台合规",
          "SPAM", "垃圾信息",
          "UNCLASSIFIED", "未分类");

  /** 优先级取值域的中文标签。 */
  private static final Map<String, String> PRIORITY_LABELS =
      Map.of("LOW", "低", "NORMAL", "普通", "HIGH", "高", "URGENT", "紧急");
}
