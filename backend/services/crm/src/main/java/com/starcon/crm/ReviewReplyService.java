package com.starcon.crm;

import static com.starcon.crm.ReviewReplyModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyBatchDetail;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyBatchInput;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyBatchPage;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyBatchResult;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyBatchRow;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyCapabilities;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyCapabilityDimension;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyCreateInput;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyDeliveryResult;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyDeliveryRow;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyDetail;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyPage;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyPolicyInput;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyPolicyPage;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyPolicyRow;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyRow;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplySkipped;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyTemplateDetail;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyTemplateInput;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyTemplatePage;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyTemplateRow;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyTemplateUpdateInput;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyTemplateVariant;
import com.starcon.crm.ReviewReplyModels.CrmReviewReplyTemplateVariantInput;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * 15.2.1.2 评价回复：回复评价（公开回复 + 部分平台的卖家评论追加），支持模板、多语言、
 * 批量回复同类评价。
 *
 * <p>蓝图 15 卷 15.2.1.2 原话：
 *
 * <blockquote>
 *
 * 职责：回复评价（公开回复 + 部分平台的卖家评论追加），支持模板、多语言、批量回复同类评价。 输入：回复内容；目标评价。 输出：回复记录 + 平台回执。 关系：上游 = 15.2.1.1；下游 = 平台接口。 <b>差评回复是转化机会也是合规风险</b>，模板必须符合平台措辞规范。
 *
 * </blockquote>
 *
 * <p><b>🔴 加粗句「差评回复是转化机会也是合规风险，模板必须符合平台措辞规范」在本服务落成四件事</b>：
 *
 * <ol>
 *   <li><b>规范可配置且版本化</b>：{@code crm_review_reply_policy}（逐平台 × 逐回复形态，
 *       带版本与生效时间）。平台政策会变 ⇒ 规范也版本化。</li>
 *   <li><b>校验真的拦得住</b>：模板的每条多语言正文与每条回复正文都跑
 *       {@link ReplyWordingChecker}；不合规直接 <b>400</b> 拒绝，
 *       <b>不合规的模板存不进来</b>。</li>
 *   <li><b>差评必致歉</b>：1-2 星的回复必须带致歉表达（规范里 {@code requireApology} 开关）。
 *       「转化机会」的那一半就落在这里。</li>
 *   <li><b>历史可追溯</b>：回复记录落 {@code templateId} + {@code templateVersion} +
 *       {@code templateKey} + {@code templateLocale} + {@code policyVersion}
 *       ⇒ 事后能回答「当时用的哪一版模板、当时的规范是哪一版」。
 *       没有这几个字段，「平台政策变了之后这条回复算不算违规」永远答不了。</li>
 * </ol>
 *
 * <p><b>🔴 平台投递未接入</b>：投递端点经 {@link PlatformReviewReplyPort} 接缝调用，
 * 当前实现 {@link UnavailablePlatformReviewReplyPort} 对所有平台答「未接入」。
 * 服务层据此：投递端点返回 <b>409</b>，回复的 {@code status} 停在 {@code PENDING}，
 * {@code platformReceipt} <b>恒为空</b>，并把这次尝试记进 {@code crm_review_reply_delivery}
 * （{@code outcome = NOT_INTEGRATED}）——「我们试过、没发出去、为什么」必须查得到。
 *
 * <p><b>🔴 本模块不改 15.2.1.1 的 {@code crm_product_review}</b>：那张表的 {@code replied}
 * 列语义是「**平台侧**是否已有卖家公开回复」；本模块的回复尚未投递到平台 ⇒ 平台侧仍然没有回复
 * ⇒ 该列必须保持 false。把没发出去的回复算成「平台已回复」，比不更新更糟。
 * 本模块自己的「有没有回复记录」由本模块的表回答。
 *
 * <p><b>🔴 差评处理（15.2.1.3）未交付</b>：本模块<b>不</b>实现差评的专门处理流程
 * （标记原因 / 判定申诉 / 联系买家解决 / 跟踪解决率），但<b>能对任意评价（含差评）回复</b> ——
 * 那正是本模块的职责。
 */
@Service
public class ReviewReplyService {

  // ================================================================ 常量

  /** 列表分页上限。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 批量回复一次最多处理多少条。 */
  static final int MAX_BATCH_SIZE = 200;

  /** 批量回复的默认条数。 */
  static final int DEFAULT_BATCH_SIZE = 50;

  /** 解析 jsonb 用的 mapper。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  private final DSLContext sql;
  private final PlatformReviewReplyPort platformReply;
  private final ObjectMapper json;

  public ReviewReplyService(DSLContext sql, PlatformReviewReplyPort platformReply, ObjectMapper json) {
    this.sql = sql;
    this.platformReply = platformReply;
    this.json = json;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 三维刻意是 {@code available=false}：
   *
   * <ul>
   *   <li>{@code platform_delivery} —— 平台评价回复适配器<b>未交付</b>（channel 的适配层
   *       只有 OAuth 适配器，没有买家评价回复接口）。</li>
   *   <li>{@code negative_review_handling} —— 15.2.1.3 差评处理<b>未交付</b>。</li>
   *   <li>{@code buyer_care} —— 15.5.2 买家关怀<b>不在本模块范围</b>。</li>
   * </ul>
   */
  public CrmReviewReplyCapabilities capabilities() {
    List<CrmReviewReplyCapabilityDimension> dimensions =
        List.of(
            new CrmReviewReplyCapabilityDimension(
                "reply_compose",
                "写一条回复（公开回复 / 部分平台的卖家评论追加）",
                true,
                "本服务 V298 crm_review_reply",
                "",
                "两种形态都真的落库、真的校验；回复记录里能追到当时用的模板版本与规范版本"),
            new CrmReviewReplyCapabilityDimension(
                "reply_template",
                "回复模板（版本化 + 生效时间 + 多语言）",
                true,
                "本服务 V298 crm_review_reply_template / crm_review_reply_template_variant",
                "",
                "一个模板 = 一个版本；改内容要发新版本，旧版本留在历史里，"
                    + "「当时用的哪一版」永远答得出来"),
            new CrmReviewReplyCapabilityDimension(
                "wording_policy",
                "🔴 平台措辞规范（逐平台 × 逐形态，带版本与生效时间）",
                true,
                "本服务 V298 crm_review_reply_policy",
                "",
                "加粗句「模板必须符合平台措辞规范」的载体：规范可配置、可版本化；"
                    + "未配置该平台的规范 ⇒ 该平台的回复写不出来（而不是写出来再违规）"),
            new CrmReviewReplyCapabilityDimension(
                "wording_enforcement",
                "🔴 措辞校验（长度 / 导流联系方式 / 促销措辞 / 外链 / 标点上限 / 差评必致歉 / 质疑买家）",
                true,
                "本服务 ReplyWordingChecker",
                "",
                "不合规的模板存不进来、不合规的回复发不出去；"
                    + "质疑买家的措辞**不可关闭** —— 它是平台与法律风险最高的一类措辞"),
            new CrmReviewReplyCapabilityDimension(
                "batch_reply",
                "批量回复同类评价（同平台 × 同店铺 × 同档位 × 同形态 × 同语言）",
                true,
                "本服务 V298 crm_review_reply_batch",
                "",
                "按真实筛选挑出目标评价并逐条落库；被跳过的评价与原因**逐条报出来**，"
                    + "只报成功数会让人以为「这一批全都回上了」"),
            new CrmReviewReplyCapabilityDimension(
                "platform_delivery",
                "平台投递（把回复真的发到平台上）",
                false,
                "channel 的平台适配层目前**只有 OAuth 授权适配器**，没有买家评价回复接口",
                "2",
                "投递端点明确答「未接入」（409），回复状态停在 PENDING，**平台回执恒为空** —— "
                    + "一条返回「已回复」的假回执会让「评价已经回复给买家了」看起来发生过，"
                    + "而现场一条真回复都没发出去。每次尝试都记进投递记录（outcome=NOT_INTEGRATED），"
                    + "「我们试过、没发出去、为什么」查得到"),
            new CrmReviewReplyCapabilityDimension(
                "negative_review_handling",
                "差评处理（标记原因 / 判定申诉 / 联系买家解决 / 跟踪解决率）",
                false,
                "15.2.1.3 差评处理未交付",
                "15.2.1.3",
                "本模块**能对差评回复**（那是回复的职责），但**不实现**差评的专门处理流程 —— "
                    + "申诉、补偿、升级都属 15.2.1.3"),
            new CrmReviewReplyCapabilityDimension(
                "review_request_readonly",
                "索评请求（15.2.2.1 已交付，只读引用）",
                true,
                "同库 crm_review_request（V220）",
                "15.2.2.1",
                "本模块**只读**引用索评事实，**不改**它的表结构、规则表与端点；"
                    + "「回复一条已经收到的评价」与「要不要向买家发起一次索评」是两件事"),
            new CrmReviewReplyCapabilityDimension(
                "buyer_care",
                "买家关怀（主动触达 / 售后安抚）",
                false,
                "15.5.2 买家关怀不在本模块范围",
                "15.5.2",
                "本模块只回**已经收到的评价**，不主动联系买家 —— 主动触达属 15.5.2"));
    return new CrmReviewReplyCapabilities(
        dimensions,
        REPLY_TYPES,
        RATING_SCOPES,
        REPLY_STATUSES,
        TEMPLATE_STATUSES,
        DELIVERY_OUTCOMES,
        "🔴 平台投递未接入：投递端点明确答「未接入」（409），回复状态停在 PENDING，"
            + "平台回执恒为空；每次尝试都记进 crm_review_reply_delivery（outcome=NOT_INTEGRATED），"
            + "「我们试过、没发出去、为什么」查得到。"
            + "本模块**不改** 15.2.1.1 的 crm_product_review —— 那张表的 replied 说的是"
            + "「平台侧是否已有公开回复」，而本模块的回复尚未投递到平台。",
        "🔴 差评回复是转化机会也是合规风险：一个模板 = 一个版本（带生效时间），"
            + "回复记录追到当时用的模板版本与规范版本；措辞不合规的模板存不进来，"
            + "不合规的回复发不出去；差评回复必须致歉。");
  }

  // ================================================================ 措辞规范

  /** 规范列表（真分页 + 真筛选）。 */
  public CrmReviewReplyPolicyPage listPolicies(
      String platformCode, String replyType, String status, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where p.tenant_id = ?");
    binds.add(currentTenant());
    if (platformCode != null) {
      where.append(" and p.platform_code = ?");
      binds.add(platformCode);
    }
    if (replyType != null) {
      where.append(" and p.reply_type = ?");
      binds.add(replyType);
    }
    if (status != null) {
      where.append(" and p.status = ?");
      binds.add(status);
    }
    long total =
        sql.fetchOne("select count(*) from crm_review_reply_policy p" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmReviewReplyPolicyRow> items =
        sql.fetch(
                POLICY_COLUMNS + " from crm_review_reply_policy p" + where
                    + " order by p.platform_code, p.reply_type, p.version desc limit ? offset ?",
                pageBinds.toArray())
            .map(ReviewReplyService::policy);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmReviewReplyPolicyPage(items, safePage, safeSize, total, totalPages);
  }

  /** 单条规范。 */
  public CrmReviewReplyPolicyRow policy(UUID policyId) {
    var row =
        sql.fetchOne(
            POLICY_COLUMNS + " from crm_review_reply_policy p where p.tenant_id = ? and p.id = ?",
            currentTenant(),
            policyId);
    if (row == null) {
      throw notFound("措辞规范不存在（或不属于当前租户）：" + policyId);
    }
    return policy(row);
  }

  /**
   * 配置平台措辞规范 —— <b>落成新的一版</b>。
   *
   * <p>⚠️ 规范**不原地改**：平台政策会变，改规范 = 发新版本，旧版本保留在历史里
   * （否则「这条回复当时是按哪一版规范审的」答不了）。
   * 新版本的生效起点必须晚于当前版本，并把当前版本的失效时间收到同一时刻
   * （生效区间不允许重叠 —— 「当时生效的是哪一版」必须唯一）。
   */
  @Transactional
  public CrmReviewReplyPolicyRow savePolicy(CrmReviewReplyPolicyInput input, UUID actorId) {
    String platformCode = requirePlatformCode(input.platformCode());
    String replyType = requireReplyType(input.replyType());
    int minLength = input.minLength() == null ? 10 : input.minLength();
    int maxLength = input.maxLength() == null ? 500 : input.maxLength();
    if (maxLength < minLength) {
      throw bad("正文长度上限（" + maxLength + "）不能小于下限（" + minLength + "）。");
    }
    int maxExclamation = input.maxExclamation() == null ? 1 : input.maxExclamation();
    int maxQuestionMarks = input.maxQuestionMarks() == null ? 2 : input.maxQuestionMarks();
    OffsetDateTime effectiveFrom = parseMoment(input.effectiveFrom(), "生效起点");
    List<String> bannedTerms = normalizeBannedTerms(input.bannedTerms());

    var previous =
        sql.fetchOne(
            "select p.id,p.version,p.effective_from,p.effective_to from crm_review_reply_policy p"
                + " where p.tenant_id = ? and p.platform_code = ? and p.reply_type = ?"
                + " order by p.version desc limit 1",
            currentTenant(),
            platformCode,
            replyType);
    int version = 1;
    OffsetDateTime moment = now();
    if (previous != null) {
      version = previous.get("version", Integer.class) + 1;
      OffsetDateTime previousFrom = previous.get("effective_from", OffsetDateTime.class);
      if (!effectiveFrom.isAfter(previousFrom)) {
        throw bad(
            "新版本的生效起点（"
                + effectiveFrom
                + "）必须晚于当前版本的生效起点（"
                + previousFrom
                + "）：规范的生效区间不允许重叠，否则「当时生效的是哪一版」会有两个答案。");
      }
      if (previous.get("effective_to", OffsetDateTime.class) == null) {
        sql.execute(
            "update crm_review_reply_policy set effective_to = ?::timestamptz, updated_at = ?::timestamptz"
                + " where tenant_id = ? and id = ?",
            effectiveFrom,
            moment,
            currentTenant(),
            previous.get("id", UUID.class));
      }
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into crm_review_reply_policy"
            + "(id,tenant_id,policy_no,platform_code,reply_type,version,effective_from,effective_to,"
            + " min_length,max_length,allow_contact_info,allow_promotion,allow_external_link,"
            + " max_exclamation,max_question_marks,require_apology,banned_terms,status,note,"
            + " created_by,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?::timestamptz,null,?,?,?,?,?,?,?,?,?::jsonb,'ACTIVE',?,"
            + "?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        nextPolicyNo(),
        platformCode,
        replyType,
        version,
        effectiveFrom,
        minLength,
        maxLength,
        Boolean.TRUE.equals(input.allowContactInfo()),
        Boolean.TRUE.equals(input.allowPromotion()),
        Boolean.TRUE.equals(input.allowExternalLink()),
        maxExclamation,
        maxQuestionMarks,
        input.requireApology() == null || input.requireApology(),
        bannedTermsJson(bannedTerms),
        blankToNull(input.note()),
        actorId,
        moment,
        moment);
    return policy(id);
  }

  /** 某平台 × 某形态在 {@code at} 时刻生效的规范（没有就是 null）。 */
  private Record effectivePolicyRow(String platformCode, String replyType, OffsetDateTime at) {
    return sql.fetchOne(
        POLICY_COLUMNS
            + " from crm_review_reply_policy p"
            + " where p.tenant_id = ? and p.platform_code = ? and p.reply_type = ?"
            + " and p.status = 'ACTIVE'"
            + " and p.effective_from <= ?::timestamptz"
            + " and (p.effective_to is null or p.effective_to > ?::timestamptz)"
            + " order by p.version desc limit 1",
        currentTenant(),
        platformCode,
        replyType,
        at,
        at);
  }

  /**
   * 取生效的规范；没有就<b>明确失败</b>。
   *
   * <p>🔴 「未配置规范」既意味着「还没开通这个平台这个形态」，也意味着「没法校验合规」。
   * 两种情况都**不能**默认放行 —— 放行等于在没有规范的情况下往公开平台发内容。
   */
  private Record requireEffectivePolicy(String platformCode, String replyType) {
    var row = effectivePolicyRow(platformCode, replyType, now());
    if (row == null) {
      throw conflict(
          "平台「"
              + platformCode
              + "」的「"
              + replyType
              + "」形态尚未配置措辞规范 —— 未配置既意味着这个平台这个形态还没开通，"
              + "也意味着没法校验合规。请先调用 PUT /crm/review-replies/policies 配置。"
              + "（规范按平台 × 形态分别配置，因为两者的措辞红线并不相同。）");
    }
    return row;
  }

  // ================================================================ 模板

  /** 模板列表（真分页 + 真筛选）。 */
  public CrmReviewReplyTemplatePage listTemplates(
      String platformCode,
      String replyType,
      String ratingScope,
      String status,
      String keyword,
      int page,
      int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where t.tenant_id = ?");
    binds.add(currentTenant());
    if (platformCode != null) {
      where.append(" and t.platform_code = ?");
      binds.add(platformCode);
    }
    if (replyType != null) {
      where.append(" and t.reply_type = ?");
      binds.add(replyType);
    }
    if (ratingScope != null) {
      where.append(" and t.rating_scope = ?");
      binds.add(ratingScope);
    }
    if (status != null) {
      where.append(" and t.status = ?");
      binds.add(status);
    }
    if (keyword != null) {
      where.append(
          " and (t.name ilike ? escape '\\' or t.template_key ilike ? escape '\\'"
              + " or t.template_no ilike ? escape '\\')");
      String like = "%" + escapeLike(keyword) + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    long total =
        sql.fetchOne("select count(*) from crm_review_reply_template t" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmReviewReplyTemplateRow> items =
        sql.fetch(
                TEMPLATE_COLUMNS + " from crm_review_reply_template t" + where
                    + " order by t.created_at desc, t.id limit ? offset ?",
                pageBinds.toArray())
            .map(ReviewReplyService::template);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmReviewReplyTemplatePage(items, safePage, safeSize, total, totalPages);
  }

  /** 模板详情 = 模板本体 + 多语言条目。 */
  public CrmReviewReplyTemplateDetail templateDetail(UUID templateId) {
    var row =
        sql.fetchOne(
            TEMPLATE_COLUMNS + " from crm_review_reply_template t where t.tenant_id = ? and t.id = ?",
            currentTenant(),
            templateId);
    if (row == null) {
      throw notFound("回复模板不存在（或不属于当前租户）：" + templateId);
    }
    return new CrmReviewReplyTemplateDetail(template(row), variants(templateId));
  }

  /**
   * 新建回复模板（落地为 <b>DRAFT</b>）。
   *
   * <p>版本号由服务层递增（同一 {@code templateKey} × 平台 × 形态下 +1）：
   * 版本号是「当时用的哪一版」的唯一定位，不能由调用方随手指定。
   */
  @Transactional
  public CrmReviewReplyTemplateDetail createTemplate(
      CrmReviewReplyTemplateInput input, UUID actorId) {
    String platformCode = requirePlatformCode(input.platformCode());
    String replyType = requireReplyType(input.replyType());
    String ratingScope = requireScope(input.ratingScope() == null ? "ALL" : input.ratingScope());
    OffsetDateTime effectiveFrom = parseMoment(input.effectiveFrom(), "生效起点");
    OffsetDateTime effectiveTo = optionalMoment(input.effectiveTo(), "生效终点");
    if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
      throw bad("生效终点必须晚于生效起点。");
    }
    List<CrmReviewReplyTemplateVariantInput> variants =
        normalizeVariants(input.variants());

    // 版本号：同一（键 × 平台 × 形态）下递增。
    var maxRow =
        sql.fetchOne(
            "select max(t.version) as v from crm_review_reply_template t"
                + " where t.tenant_id = ? and t.template_key = ? and t.platform_code = ? and t.reply_type = ?",
            currentTenant(),
            input.templateKey().trim(),
            platformCode,
            replyType);
    Integer maxVersion = maxRow == null ? null : maxRow.get("v", Integer.class);
    int version = maxVersion == null ? 1 : maxVersion + 1;

    // 措辞校验：已配置规范就当场校验（不合规的模板存不进来）。
    var policy = effectivePolicyRow(platformCode, replyType, now());
    if (policy != null) {
      var context = wordingContext(policy, "NEGATIVE".equals(ratingScope));
      for (var variant : variants) {
        var violations = ReplyWordingChecker.check(variant.body(), context);
        if (!violations.isEmpty()) {
          throw bad(
              "模板正文（"
                  + variant.locale()
                  + "）不符合该平台的措辞规范："
                  + ReplyWordingChecker.describe(violations));
        }
      }
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_review_reply_template"
            + "(id,tenant_id,template_no,template_key,version,name,platform_code,reply_type,"
            + " rating_scope,effective_from,effective_to,status,note,created_by,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,'DRAFT',?,?,"
            + "?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        nextTemplateNo(),
        input.templateKey().trim(),
        version,
        input.name().trim(),
        platformCode,
        replyType,
        ratingScope,
        effectiveFrom,
        effectiveTo,
        blankToNull(input.note()),
        actorId,
        moment,
        moment);
    for (var variant : variants) {
      sql.execute(
          "insert into crm_review_reply_template_variant"
              + "(id,tenant_id,template_id,locale,body,is_default,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          id,
          variant.locale(),
          variant.body().trim(),
          Boolean.TRUE.equals(variant.isDefault()),
          moment,
          moment);
    }
    return templateDetail(id);
  }

  /**
   * 改草稿模板（<b>只有 DRAFT 可改</b>；已生效的模板内容冻结）。
   *
   * <p>⚠️ 改内容 = 发新版本，而不是原地改 —— 原地改会让「当时用的哪一版」当场失效。
   */
  @Transactional
  public CrmReviewReplyTemplateDetail updateTemplate(
      UUID templateId, CrmReviewReplyTemplateUpdateInput input) {
    var row = requireTemplateRow(templateId);
    if (!"DRAFT".equals(row.get("status", String.class))) {
      throw conflict(
          "模板当前状态是 "
              + row.get("status", String.class)
              + "，已生效的模板内容冻结：要改内容请用同一个 templateKey 发新版本。");
    }
    List<CrmReviewReplyTemplateVariantInput> variants = null;
    // ⚠️ 空数组视为「不改多语言条目」：契约里这个字段是必填数组，
    //   前端只想改个名字时传空数组，不该被当成「把正文清空」。
    if (input.variants() != null && !input.variants().isEmpty()) {
      variants = normalizeVariants(input.variants());
      var policy = effectivePolicyRow(
          row.get("platform_code", String.class), row.get("reply_type", String.class), now());
      if (policy != null) {
        var context =
            wordingContext(policy, "NEGATIVE".equals(row.get("rating_scope", String.class)));
        for (var variant : variants) {
          var violations = ReplyWordingChecker.check(variant.body(), context);
          if (!violations.isEmpty()) {
            throw bad(
                "模板正文（"
                    + variant.locale()
                    + "）不符合该平台的措辞规范："
                    + ReplyWordingChecker.describe(violations));
          }
        }
      }
      sql.execute(
          "delete from crm_review_reply_template_variant where tenant_id = ? and template_id = ?",
          currentTenant(),
          templateId);
      OffsetDateTime moment = now();
      for (var variant : variants) {
        sql.execute(
            "insert into crm_review_reply_template_variant"
                + "(id,tenant_id,template_id,locale,body,is_default,created_at,updated_at)"
                + " values (?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
            UUID.randomUUID(),
            currentTenant(),
            templateId,
            variant.locale(),
            variant.body().trim(),
            Boolean.TRUE.equals(variant.isDefault()),
            moment,
            moment);
      }
    }
    if (input.name() != null && !input.name().isBlank()) {
      sql.execute(
          "update crm_review_reply_template set name = ?, note = coalesce(?, note), updated_at = ?::timestamptz"
              + " where tenant_id = ? and id = ?",
          input.name().trim(),
          blankToNull(input.note()),
          now(),
          currentTenant(),
          templateId);
    } else if (input.note() != null) {
      sql.execute(
          "update crm_review_reply_template set note = ?, updated_at = ?::timestamptz"
              + " where tenant_id = ? and id = ?",
          blankToNull(input.note()),
          now(),
          currentTenant(),
          templateId);
    }
    return templateDetail(templateId);
  }

  /**
   * 发布模板（DRAFT → ACTIVE）。
   *
   * <p>🔴 发布时**必须**有生效的措辞规范，且每条多语言正文都要过校验 ——
   * 未发布的草稿可以先攒着，发布的那一刻才是「这条话术要拿出去用了」。
   */
  @Transactional
  public CrmReviewReplyTemplateDetail publishTemplate(UUID templateId) {
    var row = requireTemplateRow(templateId);
    if (!"DRAFT".equals(row.get("status", String.class))) {
      throw conflict("模板当前状态是 " + row.get("status", String.class) + "，只有草稿可以发布。");
    }
    String platformCode = row.get("platform_code", String.class);
    String replyType = row.get("reply_type", String.class);
    var policy = requireEffectivePolicy(platformCode, replyType);
    List<CrmReviewReplyTemplateVariant> variants = variants(templateId);
    if (variants.isEmpty()) {
      throw bad("模板至少要有一条多语言正文才能发布：一条正文都没有的模板发出去只会得到一个空回复。");
    }
    var context = wordingContext(policy, "NEGATIVE".equals(row.get("rating_scope", String.class)));
    for (var variant : variants) {
      var violations = ReplyWordingChecker.check(variant.body(), context);
      if (!violations.isEmpty()) {
        throw bad(
            "模板正文（" + variant.locale() + "）不符合该平台的措辞规范："
                + ReplyWordingChecker.describe(violations));
      }
    }
    sql.execute(
        "update crm_review_reply_template set status = 'ACTIVE', updated_at = ?::timestamptz"
            + " where tenant_id = ? and id = ?",
        now(),
        currentTenant(),
        templateId);
    return templateDetail(templateId);
  }

  /** 下架模板（ACTIVE → ARCHIVED）：失效时间收到此刻。 */
  @Transactional
  public CrmReviewReplyTemplateDetail archiveTemplate(UUID templateId) {
    var row = requireTemplateRow(templateId);
    String status = row.get("status", String.class);
    if ("ARCHIVED".equals(status)) {
      return templateDetail(templateId);
    }
    if (!"ACTIVE".equals(status)) {
      throw conflict("模板当前状态是 " + status + "，只有已生效的模板可以下架。");
    }
    sql.execute(
        "update crm_review_reply_template set status = 'ARCHIVED',"
            + " effective_to = coalesce(effective_to, ?::timestamptz), updated_at = ?::timestamptz"
            + " where tenant_id = ? and id = ?",
        now(),
        now(),
        currentTenant(),
        templateId);
    return templateDetail(templateId);
  }

  // ================================================================ 回复记录

  /** 回复记录列表（真分页 + 真筛选）。 */
  public CrmReviewReplyPage listReplies(
      UUID reviewId,
      UUID shopId,
      String platformCode,
      String reviewScope,
      String replyType,
      String locale,
      String status,
      String templateKey,
      UUID batchId,
      String keyword,
      int page,
      int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where rp.tenant_id = ?");
    binds.add(currentTenant());
    if (reviewId != null) {
      where.append(" and rp.review_id = ?");
      binds.add(reviewId);
    }
    if (shopId != null) {
      where.append(" and rp.shop_id = ?");
      binds.add(shopId);
    }
    if (platformCode != null) {
      where.append(" and rp.platform_code = ?");
      binds.add(platformCode);
    }
    if (reviewScope != null) {
      where.append(" and rp.review_scope = ?");
      binds.add(reviewScope);
    }
    if (replyType != null) {
      where.append(" and rp.reply_type = ?");
      binds.add(replyType);
    }
    if (locale != null) {
      where.append(" and rp.locale = ?");
      binds.add(locale);
    }
    if (status != null) {
      where.append(" and rp.status = ?");
      binds.add(status);
    }
    if (templateKey != null) {
      where.append(" and rp.template_key = ?");
      binds.add(templateKey);
    }
    if (batchId != null) {
      where.append(" and rp.batch_id = ?");
      binds.add(batchId);
    }
    if (keyword != null) {
      where.append(
          " and (rp.body ilike ? escape '\\' or rp.reply_no ilike ? escape '\\'"
              + " or rp.review_no ilike ? escape '\\' or rp.batch_no ilike ? escape '\\')");
      String like = "%" + escapeLike(keyword) + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    long total =
        sql.fetchOne("select count(*) from crm_review_reply rp" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmReviewReplyRow> items =
        sql.fetch(
                REPLY_COLUMNS + " from crm_review_reply rp" + where
                    + " order by rp.created_at desc, rp.id limit ? offset ?",
                pageBinds.toArray())
            .map(ReviewReplyService::reply);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmReviewReplyPage(items, safePage, safeSize, total, totalPages);
  }

  /** 单条回复详情 = 回复本体 + 投递历史。 */
  public CrmReviewReplyDetail replyDetail(UUID replyId) {
    var row = requireReplyRow(replyId);
    return new CrmReviewReplyDetail(reply(row), deliveries(replyId));
  }

  /**
   * 写一条回复。
   *
   * <p>🔴 <b>回复内容与「当时用的模板版本」一起落库</b>：模板给了就用模板该语言的正文，
   * 并把 {@code templateId / templateVersion / templateKey / templateLocale / policyVersion}
   * 一起记下来 —— 事后能回答「这条回复当时用的哪一版模板、当时的规范是哪一版」。
   */
  @Transactional
  public CrmReviewReplyRow createReply(CrmReviewReplyCreateInput input, UUID actorId) {
    var review = requireReview(input.reviewId());
    String replyType = requireReplyType(input.replyType());
    var policy = requireEffectivePolicy(review.platformCode(), replyType);

    String body;
    String locale;
    UUID templateId = null;
    Integer templateVersion = null;
    String templateKey = null;
    String templateLocale = null;

    if (input.templateId() != null) {
      var templateRow = requireTemplateRow(input.templateId());
      if (!review.platformCode().equals(templateRow.get("platform_code", String.class))) {
        throw bad(
            "模板属于平台「"
                + templateRow.get("platform_code", String.class)
                + "」，而这条评价属于平台「"
                + review.platformCode()
                + "」：给 A 平台的评价回一句 B 平台的话，在公开页面上是事故。");
      }
      if (!replyType.equals(templateRow.get("reply_type", String.class))) {
        throw bad(
            "模板的回复形态是「"
                + templateRow.get("reply_type", String.class)
                + "」，与本次请求的「"
                + replyType
                + "」不一致。");
      }
      if (!"ACTIVE".equals(templateRow.get("status", String.class))) {
        throw bad(
            "模板当前状态是 "
                + templateRow.get("status", String.class)
                + "，只有已生效（ACTIVE）的模板可以用来回复。");
      }
      String scope = templateRow.get("rating_scope", String.class);
      if (!"ALL".equals(scope) && !scope.equals(review.scope())) {
        throw bad(
            "模板适用「"
                + scope
                + "」档的评价，而这条评价是「"
                + review.scope()
                + "」档：档位对不上就不要套用。");
      }
      var variants = variants(templateRow.get("id", UUID.class));
      var wanted = blankToNull(input.locale());
      var variant = wanted == null ? defaultVariant(variants) : variantOf(variants, wanted);
      if (variant == null && wanted != null) {
        variant = defaultVariant(variants);
      }
      if (variant == null) {
        throw bad(
            "模板「"
                + templateRow.get("template_key", String.class)
                + "」没有语言「"
                + (wanted == null ? "（未指定）" : wanted)
                + "」的正文，也没有兜底语言：宁可不回，也不要用错语言回买家。");
      }
      body = variant.body();
      locale = variant.locale();
      templateId = templateRow.get("id", UUID.class);
      templateVersion = templateRow.get("version", Integer.class);
      templateKey = templateRow.get("template_key", String.class);
      templateLocale = variant.locale();
    } else {
      body = nz(input.body()).trim();
      if (body.isEmpty()) {
        throw bad("必须写明回复正文，或指定一个回复模板：两者都不给就没有可回复的内容。");
      }
      locale = blankToNull(input.locale());
      if (locale == null) {
        throw bad("手写回复必须写明语言（locale，例如 en-US）：语言不明会被当成「随便哪种都行」。");
      }
      requireLocale(locale);
    }

    // 措辞校验（不合规的回复发不出去）。
    var context = wordingContext(policy, "NEGATIVE".equals(review.scope()));
    var violations = ReplyWordingChecker.check(body, context);
    if (!violations.isEmpty()) {
      throw bad(ReplyWordingChecker.describe(violations));
    }

    if (hasActiveReply(review.id(), replyType)) {
      throw conflict(
          "这条评价已经有一条「"
              + replyType
              + "」形态的在途或已送达回复了：重复回复买家是平台违规与买家投诉的常见来源。"
              + "要再回一条，请先取消原来那条。");
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_review_reply"
            + "(id,tenant_id,reply_no,review_id,review_no,shop_id,shop_name,platform_code,"
            + " review_rating,review_scope,reply_type,locale,body,"
            + " template_id,template_version,template_key,template_locale,policy_version,"
            + " batch_id,batch_no,status,note,created_by,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?, ?,?,?,?,?, ?,?,?,?,?, ?,?, 'PENDING', ?,?, ?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        nextReplyNo(),
        review.id(),
        review.reviewNo(),
        review.shopId(),
        review.shopName(),
        review.platformCode(),
        review.rating(),
        review.scope(),
        replyType,
        locale,
        body,
        templateId,
        templateVersion,
        templateKey,
        templateLocale,
        policy.get("version", Integer.class),
        null,
        null,
        blankToNull(input.note()),
        actorId,
        moment,
        moment);
    return requireReply(id);
  }

  /** 取消一条待投递的回复（撤回了可以再回一次）。 */
  @Transactional
  public CrmReviewReplyRow cancelReply(UUID replyId, String note) {
    var row = requireReplyRow(replyId);
    String status = row.get("status", String.class);
    if ("CANCELLED".equals(status)) {
      return reply(row);
    }
    if (!"PENDING".equals(status)) {
      throw conflict("回复当前状态是 " + status + "，只有待投递（PENDING）的回复可以撤回。");
    }
    sql.execute(
        "update crm_review_reply set status = 'CANCELLED', note = coalesce(?, note),"
            + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
        blankToNull(note),
        now(),
        currentTenant(),
        replyId);
    return requireReply(replyId);
  }

  /**
   * 把一条回复投递到平台。
   *
   * <p>🔴 <b>当前恒为「未接入」</b>：端口答不可用 ⇒ 本方法
   * <b>记录一次投递尝试</b>（{@code outcome = NOT_INTEGRATED}）、
   * <b>把回复留在 PENDING</b>、<b>不写任何回执</b>，并返回
   * {@code available=false} + 原因；控制器据此回 <b>409</b>。
   *
   * <p>⚠️ 这里<b>不抛异常</b>：抛异常会连带回滚掉刚记下的那次尝试，
   * 而「我们试过、没发出去、为什么」恰恰是必须查得到的东西。
   * 409 由控制器在事务提交之后抛出。
   */
  public CrmReviewReplyDeliveryResult deliver(UUID replyId, String bearer) {
    var row = requireReplyRow(replyId);
    String status = row.get("status", String.class);
    if (!"PENDING".equals(status)) {
      throw conflict("回复当前状态是 " + status + "，只有待投递（PENDING）的回复可以投递。");
    }
    String platformCode = row.get("platform_code", String.class);
    int attemptNo = nextAttemptNo(replyId);
    var availability = platformReply.availability(platformCode);
    if (!availability.available()) {
      var delivery =
          insertDelivery(
              replyId,
              row.get("reply_no", String.class),
              platformCode,
              attemptNo,
              "NOT_INTEGRATED",
              null,
              null,
              availability.reason());
      sql.execute(
          "update crm_review_reply set last_delivery_note = ?, updated_at = ?::timestamptz"
              + " where tenant_id = ? and id = ?",
          availability.reason(),
          now(),
          currentTenant(),
          replyId);
      return new CrmReviewReplyDeliveryResult(
          delivery,
          false,
          availability.reason(),
          "PENDING",
          "这次尝试已经记进投递记录（outcome=NOT_INTEGRATED），但回复**没有发到平台** —— "
              + "买家此刻还没收到任何回复。");
    }

    var outcome =
        platformReply.deliver(
            new PlatformReviewReplyPort.DeliveryRequest(
                platformCode,
                row.get("reply_no", String.class),
                row.get("review_id", UUID.class),
                reviewExternalId(row.get("review_id", UUID.class)),
                row.get("body", String.class),
                row.get("locale", String.class),
                row.get("reply_type", String.class)),
            bearer);
    String result =
        outcome.delivered()
            ? "DELIVERED"
            : (outcome.outcome() == null ? "FAILED" : outcome.outcome());
    var delivery =
        insertDelivery(
            replyId,
            row.get("reply_no", String.class),
            platformCode,
            attemptNo,
            result,
            outcome.platformReplyId(),
            outcome.platformReceipt(),
            outcome.reason());
    if (outcome.delivered()) {
      sql.execute(
          "update crm_review_reply set status = 'DELIVERED', platform_reply_id = ?,"
              + " platform_receipt = ?, delivered_at = ?::timestamptz, last_delivery_note = ?,"
              + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
          outcome.platformReplyId(),
          outcome.platformReceipt(),
          outcome.deliveredAt(),
          outcome.reason(),
          now(),
          currentTenant(),
          replyId);
    } else {
      sql.execute(
          "update crm_review_reply set status = 'REJECTED', platform_reply_id = ?,"
              + " last_delivery_note = ?, updated_at = ?::timestamptz where tenant_id = ? and id = ?",
          outcome.platformReplyId(),
          outcome.reason(),
          now(),
          currentTenant(),
          replyId);
    }
    return new CrmReviewReplyDeliveryResult(
        delivery,
        outcome.delivered(),
        outcome.reason(),
        outcome.delivered() ? "DELIVERED" : "REJECTED",
        outcome.delivered()
            ? "平台已确认这条回复送达。"
            : "平台没有确认送达：回复状态已记为 REJECTED，回执字段保持为空。");
  }

  // ================================================================ 批量回复

  /** 批次列表（真分页 + 真筛选）。 */
  public CrmReviewReplyBatchPage listBatches(
      String platformCode, String replyType, String status, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where b.tenant_id = ?");
    binds.add(currentTenant());
    if (platformCode != null) {
      where.append(" and b.platform_code = ?");
      binds.add(platformCode);
    }
    if (replyType != null) {
      where.append(" and b.reply_type = ?");
      binds.add(replyType);
    }
    if (status != null) {
      where.append(" and b.status = ?");
      binds.add(status);
    }
    long total =
        sql.fetchOne("select count(*) from crm_review_reply_batch b" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmReviewReplyBatchRow> items =
        sql.fetch(
                BATCH_COLUMNS + " from crm_review_reply_batch b" + where
                    + " order by b.created_at desc, b.id limit ? offset ?",
                pageBinds.toArray())
            .map(ReviewReplyService::batch);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmReviewReplyBatchPage(items, safePage, safeSize, total, totalPages);
  }

  /** 批次详情 = 批次本体 + 该批次下的回复 + 被跳过的评价与原因。 */
  public CrmReviewReplyBatchDetail batchDetail(UUID batchId) {
    var row = requireBatchRow(batchId);
    List<CrmReviewReplyRow> replies =
        sql.fetch(
                REPLY_COLUMNS + " from crm_review_reply rp where rp.tenant_id = ? and rp.batch_id = ?"
                    + " order by rp.created_at asc, rp.id",
                currentTenant(),
                batchId)
            .map(ReviewReplyService::reply);
    return new CrmReviewReplyBatchDetail(batch(row), replies, skippedOf(row));
  }

  /**
   * 批量回复「同类评价」。
   *
   * <p>「同类」的判定口径：<b>同平台 × 同店铺（可选）× 同档位 × 同形态 × 同语言</b>。
   * 目标评价从 {@code crm_product_review} 里<b>真的筛出来</b>，
   * 已经有在途回复的自动跳过，逐条落库。
   *
   * <p>🔴 <b>被跳过的评价与原因逐条报出来</b>：只报成功数会让人以为「这一批全都回上了」。
   */
  @Transactional
  public CrmReviewReplyBatchResult createBatch(
      CrmReviewReplyBatchInput input, UUID actorId) {
    String platformCode = requirePlatformCode(input.platformCode());
    String replyType = requireReplyType(input.replyType());
    String ratingScope = requireScope(input.ratingScope());
    String locale = requireLocale(blankToNull(input.locale()));
    int limit =
        input.limit() == null
            ? DEFAULT_BATCH_SIZE
            : Math.min(Math.max(input.limit(), 1), MAX_BATCH_SIZE);

    var templateRow = requireTemplateRow(input.templateId());
    if (!platformCode.equals(templateRow.get("platform_code", String.class))) {
      throw bad(
          "模板属于平台「"
              + templateRow.get("platform_code", String.class)
              + "」，与本次批量的平台「"
              + platformCode
              + "」不一致。");
    }
    if (!replyType.equals(templateRow.get("reply_type", String.class))) {
      throw bad(
          "模板的回复形态是「"
              + templateRow.get("reply_type", String.class)
              + "」，与本次批量的「"
              + replyType
              + "」不一致。");
    }
    if (!"ACTIVE".equals(templateRow.get("status", String.class))) {
      throw bad(
          "模板当前状态是 "
              + templateRow.get("status", String.class)
              + "，只有已生效（ACTIVE）的模板可以用于批量回复。");
    }
    String templateScope = templateRow.get("rating_scope", String.class);
    if (!"ALL".equals(templateScope) && !templateScope.equals(ratingScope)) {
      throw bad(
          "模板适用「"
              + templateScope
              + "」档，而本次批量要回「"
              + ratingScope
              + "」档：档位对不上就不要套用。");
    }
    var policy = requireEffectivePolicy(platformCode, replyType);
    var variants = variants(templateRow.get("id", UUID.class));
    var variant = variantOf(variants, locale);
    if (variant == null) {
      variant = defaultVariant(variants);
    }
    if (variant == null) {
      throw bad(
          "模板「"
              + templateRow.get("template_key", String.class)
              + "」没有语言「"
              + locale
              + "」的正文，也没有兜底语言：宁可不回，也不要用错语言回买家。");
    }

    List<Target> targets =
        selectBatchTargets(platformCode, input.shopId(), ratingScope, replyType, limit);

    // 先落批次（回复要挂在批次上），再落回复，最后回填统计。
    UUID batchId = UUID.randomUUID();
    String batchNo = nextBatchNo();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_review_reply_batch"
            + "(id,tenant_id,batch_no,platform_code,shop_id,reply_type,locale,template_id,"
            + " template_version,template_key,rating_scope,selection,target_count,created_count,"
            + " skipped_count,skipped,status,note,created_by,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?::jsonb,0,0,0,'[]'::jsonb,'EMPTY',?,?,"
            + "?::timestamptz,?::timestamptz)",
        batchId,
        currentTenant(),
        batchNo,
        platformCode,
        input.shopId(),
        replyType,
        variant.locale(),
        templateRow.get("id", UUID.class),
        templateRow.get("version", Integer.class),
        templateRow.get("template_key", String.class),
        ratingScope,
        selectionJson(platformCode, input.shopId(), ratingScope, replyType, variant.locale()),
        blankToNull(input.note()),
        actorId,
        moment,
        moment);

    List<CrmReviewReplyRow> created = new ArrayList<>();
    List<CrmReviewReplySkipped> skipped = new ArrayList<>();
    for (var target : targets) {
      var context = wordingContext(policy, "NEGATIVE".equals(target.scope()));
      var violations = ReplyWordingChecker.check(variant.body(), context);
      if (!violations.isEmpty()) {
        skipped.add(
            new CrmReviewReplySkipped(
                target.id().toString(),
                target.reviewNo(),
                ReplyWordingChecker.describe(violations)));
        continue;
      }
      UUID replyId = UUID.randomUUID();
      sql.execute(
          "insert into crm_review_reply"
              + "(id,tenant_id,reply_no,review_id,review_no,shop_id,shop_name,platform_code,"
              + " review_rating,review_scope,reply_type,locale,body,"
              + " template_id,template_version,template_key,template_locale,policy_version,"
              + " batch_id,batch_no,status,note,created_by,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?, ?,?,?,?,?, ?,?,?,?,?, ?,?, 'PENDING',null, ?,?::timestamptz,?::timestamptz)",
          replyId,
          currentTenant(),
          nextReplyNo(),
          target.id(),
          target.reviewNo(),
          target.shopId(),
          target.shopName(),
          target.platformCode(),
          target.rating(),
          target.scope(),
          replyType,
          variant.locale(),
          variant.body(),
          templateRow.get("id", UUID.class),
          templateRow.get("version", Integer.class),
          templateRow.get("template_key", String.class),
          variant.locale(),
          policy.get("version", Integer.class),
          batchId,
          batchNo,
          actorId,
          moment,
          moment);
      created.add(requireReply(replyId));
    }

    String status = created.isEmpty() ? "EMPTY" : (skipped.isEmpty() ? "COMPOSED" : "PARTIAL");
    sql.execute(
        "update crm_review_reply_batch set target_count = ?, created_count = ?, skipped_count = ?,"
            + " skipped = ?::jsonb, status = ?, updated_at = ?::timestamptz"
            + " where tenant_id = ? and id = ?",
        targets.size(),
        created.size(),
        skipped.size(),
        skippedJson(skipped),
        status,
        moment,
        currentTenant(),
        batchId);

    return new CrmReviewReplyBatchResult(
        requireBatch(batchId),
        created,
        skipped,
        "本次按「"
            + platformCode
            + " / "
            + (input.shopId() == null ? "全部店铺" : input.shopId())
            + " / "
            + ratingScope
            + " / "
            + replyType
            + " / "
            + variant.locale()
            + "」挑出 "
            + targets.size()
            + " 条目标评价，写成 "
            + created.size()
            + " 条回复，跳过 "
            + skipped.size()
            + " 条。"
            + (skipped.isEmpty()
                ? ""
                : "跳过的每一条都带原因 —— 只看成功数会以为「这一批全都回上了」。"));
  }

  /** 挑出批量回复的目标评价（真的从评价表里筛）。 */
  private List<Target> selectBatchTargets(
      String platformCode, UUID shopId, String ratingScope, String replyType, int limit) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where r.tenant_id = ? and r.platform_code = ?");
    binds.add(currentTenant());
    binds.add(platformCode);
    if (shopId != null) {
      where.append(" and r.shop_id = ?");
      binds.add(shopId);
    }
    int[] range = ratingRange(ratingScope);
    where.append(" and r.rating between ? and ?");
    binds.add(range[0]);
    binds.add(range[1]);
    where.append(
        " and not exists (select 1 from crm_review_reply rp where rp.tenant_id = r.tenant_id"
            + " and rp.review_id = r.id and rp.reply_type = ?"
            + " and rp.status in ('PENDING','DELIVERED'))");
    binds.add(replyType);
    List<Object> ordered = new ArrayList<>(binds);
    ordered.add(limit);
    List<Target> out = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select r.id,r.review_no,r.shop_id,r.shop_name,r.platform_code,r.rating"
                + " from crm_product_review r"
                + where
                + " order by r.platform_reviewed_at desc, r.id limit ?",
            ordered.toArray())) {
      out.add(
          new Target(
              row.get("id", UUID.class),
              nz(row.get("review_no", String.class)),
              row.get("shop_id", UUID.class),
              nz(row.get("shop_name", String.class)),
              nz(row.get("platform_code", String.class)),
              row.get("rating", Integer.class),
              scopeOf(row.get("rating", Integer.class))));
    }
    return out;
  }

  // ================================================================ 行映射与小工具

  private static final String POLICY_COLUMNS =
      "select p.id,p.policy_no,p.platform_code,p.reply_type,p.version,p.effective_from,"
          + " p.effective_to,p.min_length,p.max_length,p.allow_contact_info,p.allow_promotion,"
          + " p.allow_external_link,p.max_exclamation,p.max_question_marks,p.require_apology,"
          + " p.banned_terms,p.status,p.note,p.created_at,p.updated_at";

  private static final String TEMPLATE_COLUMNS =
      "select t.id,t.template_no,t.template_key,t.version,t.name,t.platform_code,t.reply_type,"
          + " t.rating_scope,t.effective_from,t.effective_to,t.status,t.note,t.created_by,"
          + " t.created_at,t.updated_at";

  private static final String REPLY_COLUMNS =
      "select rp.id,rp.reply_no,rp.review_id,rp.review_no,rp.shop_id,rp.shop_name,"
          + " rp.platform_code,rp.review_rating,rp.review_scope,rp.reply_type,rp.locale,rp.body,"
          + " rp.template_id,rp.template_version,rp.template_key,rp.template_locale,rp.policy_version,"
          + " rp.batch_id,rp.batch_no,rp.status,rp.platform_reply_id,rp.platform_receipt,"
          + " rp.delivered_at,rp.last_delivery_note,rp.note,rp.created_by,rp.created_at,rp.updated_at";

  private static final String BATCH_COLUMNS =
      "select b.id,b.batch_no,b.platform_code,b.shop_id,b.reply_type,b.locale,b.template_id,"
          + " b.template_version,b.template_key,b.rating_scope,b.target_count,b.created_count,"
          + " b.skipped_count,b.status,b.note,b.created_by,b.created_at,b.updated_at";

  private static CrmReviewReplyPolicyRow policy(Record row) {
    return new CrmReviewReplyPolicyRow(
        row.get("id", UUID.class),
        nz(row.get("policy_no", String.class)),
        nz(row.get("platform_code", String.class)),
        nz(row.get("reply_type", String.class)),
        row.get("version", Integer.class),
        momentText(row.get("effective_from", OffsetDateTime.class)),
        momentText(row.get("effective_to", OffsetDateTime.class)),
        row.get("min_length", Integer.class),
        row.get("max_length", Integer.class),
        Boolean.TRUE.equals(row.get("allow_contact_info", Boolean.class)),
        Boolean.TRUE.equals(row.get("allow_promotion", Boolean.class)),
        Boolean.TRUE.equals(row.get("allow_external_link", Boolean.class)),
        row.get("max_exclamation", Integer.class),
        row.get("max_question_marks", Integer.class),
        Boolean.TRUE.equals(row.get("require_apology", Boolean.class)),
        parseTerms(row.get("banned_terms", String.class)),
        nz(row.get("status", String.class)),
        row.get("note", String.class),
        momentText(row.get("created_at", OffsetDateTime.class)),
        momentText(row.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmReviewReplyTemplateRow template(Record row) {
    return new CrmReviewReplyTemplateRow(
        row.get("id", UUID.class),
        nz(row.get("template_no", String.class)),
        nz(row.get("template_key", String.class)),
        row.get("version", Integer.class),
        nz(row.get("name", String.class)),
        nz(row.get("platform_code", String.class)),
        nz(row.get("reply_type", String.class)),
        nz(row.get("rating_scope", String.class)),
        momentText(row.get("effective_from", OffsetDateTime.class)),
        momentText(row.get("effective_to", OffsetDateTime.class)),
        nz(row.get("status", String.class)),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        momentText(row.get("created_at", OffsetDateTime.class)),
        momentText(row.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmReviewReplyRow reply(Record row) {
    return new CrmReviewReplyRow(
        row.get("id", UUID.class),
        nz(row.get("reply_no", String.class)),
        row.get("review_id", UUID.class),
        nz(row.get("review_no", String.class)),
        row.get("shop_id", UUID.class),
        nz(row.get("shop_name", String.class)),
        nz(row.get("platform_code", String.class)),
        row.get("review_rating", Integer.class),
        nz(row.get("review_scope", String.class)),
        nz(row.get("reply_type", String.class)),
        nz(row.get("locale", String.class)),
        nz(row.get("body", String.class)),
        row.get("template_id", UUID.class),
        row.get("template_version", Integer.class),
        row.get("template_key", String.class),
        row.get("template_locale", String.class),
        row.get("policy_version", Integer.class),
        row.get("batch_id", UUID.class),
        row.get("batch_no", String.class),
        nz(row.get("status", String.class)),
        row.get("platform_reply_id", String.class),
        row.get("platform_receipt", String.class),
        momentText(row.get("delivered_at", OffsetDateTime.class)),
        row.get("last_delivery_note", String.class),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        momentText(row.get("created_at", OffsetDateTime.class)),
        momentText(row.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmReviewReplyBatchRow batch(Record row) {
    return new CrmReviewReplyBatchRow(
        row.get("id", UUID.class),
        nz(row.get("batch_no", String.class)),
        nz(row.get("platform_code", String.class)),
        row.get("shop_id", UUID.class),
        nz(row.get("reply_type", String.class)),
        nz(row.get("locale", String.class)),
        row.get("template_id", UUID.class),
        row.get("template_version", Integer.class),
        row.get("template_key", String.class),
        nz(row.get("rating_scope", String.class)),
        row.get("target_count", Integer.class),
        row.get("created_count", Integer.class),
        row.get("skipped_count", Integer.class),
        nz(row.get("status", String.class)),
        row.get("note", String.class),
        row.get("created_by", UUID.class),
        momentText(row.get("created_at", OffsetDateTime.class)),
        momentText(row.get("updated_at", OffsetDateTime.class)));
  }

  /** 目标评价（批量回复用）。 */
  private record Target(
      UUID id,
      String reviewNo,
      UUID shopId,
      String shopName,
      String platformCode,
      Integer rating,
      String scope) {}

  /** 目标评价（单条回复用）。 */
  private record ReviewTarget(
      UUID id,
      String reviewNo,
      UUID shopId,
      String shopName,
      String platformCode,
      Integer rating,
      String scope) {}

  private Record requireTemplateRow(UUID templateId) {
    var row =
        sql.fetchOne(
            "select t.id,t.template_no,t.template_key,t.version,t.name,t.platform_code,t.reply_type,"
                + " t.rating_scope,t.effective_from,t.effective_to,t.status,t.note,t.created_by,"
                + " t.created_at,t.updated_at from crm_review_reply_template t"
                + " where t.tenant_id = ? and t.id = ?",
            currentTenant(),
            templateId);
    if (row == null) {
      throw notFound("回复模板不存在（或不属于当前租户）：" + templateId);
    }
    return row;
  }

  private Record requireReplyRow(UUID replyId) {
    var row =
        sql.fetchOne(
            "select rp.id,rp.reply_no,rp.review_id,rp.review_no,rp.shop_id,rp.shop_name,"
                + " rp.platform_code,rp.review_rating,rp.review_scope,rp.reply_type,rp.locale,"
                + " rp.body,rp.template_id,rp.template_version,rp.template_key,rp.template_locale,"
                + " rp.policy_version,rp.batch_id,rp.batch_no,rp.status,rp.platform_reply_id,"
                + " rp.platform_receipt,rp.delivered_at,rp.last_delivery_note,rp.note,"
                + " rp.created_by,rp.created_at,rp.updated_at from crm_review_reply rp"
                + " where rp.tenant_id = ? and rp.id = ?",
            currentTenant(),
            replyId);
    if (row == null) {
      throw notFound("回复记录不存在（或不属于当前租户）：" + replyId);
    }
    return row;
  }

  private Record requireBatchRow(UUID batchId) {
    var row =
        sql.fetchOne(
            "select b.id,b.batch_no,b.platform_code,b.shop_id,b.reply_type,b.locale,b.template_id,"
                + " b.template_version,b.template_key,b.rating_scope,b.target_count,b.created_count,"
                + " b.skipped_count,b.status,b.note,b.created_by,b.created_at,b.updated_at"
                + " from crm_review_reply_batch b where b.tenant_id = ? and b.id = ?",
            currentTenant(),
            batchId);
    if (row == null) {
      throw notFound("批量回复批次不存在（或不属于当前租户）：" + batchId);
    }
    return row;
  }

  private CrmReviewReplyRow requireReply(UUID replyId) {
    return reply(requireReplyRow(replyId));
  }

  private CrmReviewReplyBatchRow requireBatch(UUID batchId) {
    return batch(requireBatchRow(batchId));
  }

  /** 目标评价（15.2.1.1 的评价本体，本模块只读引用）。 */
  private ReviewTarget requireReview(UUID reviewId) {
    var row =
        sql.fetchOne(
            "select r.id,r.review_no,r.shop_id,r.shop_name,r.platform_code,r.rating"
                + " from crm_product_review r where r.tenant_id = ? and r.id = ?",
            currentTenant(),
            reviewId);
    if (row == null) {
      throw notFound(
          "评价不存在（或不属于当前租户）："
              + reviewId
              + "。回复必须挂在一条真实存在的评价上 —— 否则这条回复没有对象。");
    }
    Integer rating = row.get("rating", Integer.class);
    return new ReviewTarget(
        row.get("id", UUID.class),
        nz(row.get("review_no", String.class)),
        row.get("shop_id", UUID.class),
        nz(row.get("shop_name", String.class)),
        nz(row.get("platform_code", String.class)),
        rating,
        scopeOf(rating));
  }

  /** 平台侧的评价 id（用于投递请求；人工登记的评价可能没有）。 */
  private String reviewExternalId(UUID reviewId) {
    var row =
        sql.fetchOne(
            "select r.external_review_id from crm_product_review r where r.tenant_id = ? and r.id = ?",
            currentTenant(),
            reviewId);
    return row == null ? null : row.get("external_review_id", String.class);
  }

  /** 模板的多语言条目。 */
  private List<CrmReviewReplyTemplateVariant> variants(UUID templateId) {
    List<CrmReviewReplyTemplateVariant> out = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select v.id,v.locale,v.body,v.is_default,v.created_at,v.updated_at"
                + " from crm_review_reply_template_variant v"
                + " where v.tenant_id = ? and v.template_id = ? order by v.locale",
            currentTenant(),
            templateId)) {
      out.add(
          new CrmReviewReplyTemplateVariant(
              row.get("id", UUID.class),
              nz(row.get("locale", String.class)),
              nz(row.get("body", String.class)),
              Boolean.TRUE.equals(row.get("is_default", Boolean.class)),
              momentText(row.get("created_at", OffsetDateTime.class)),
              momentText(row.get("updated_at", OffsetDateTime.class))));
    }
    return out;
  }

  private static CrmReviewReplyTemplateVariant variantOf(
      List<CrmReviewReplyTemplateVariant> variants, String locale) {
    for (var variant : variants) {
      if (variant.locale().equalsIgnoreCase(locale)) return variant;
    }
    return null;
  }

  private static CrmReviewReplyTemplateVariant defaultVariant(
      List<CrmReviewReplyTemplateVariant> variants) {
    for (var variant : variants) {
      if (variant.isDefault()) return variant;
    }
    return null;
  }

  /** 投递历史。 */
  private List<CrmReviewReplyDeliveryRow> deliveries(UUID replyId) {
    List<CrmReviewReplyDeliveryRow> out = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select d.id,d.delivery_no,d.reply_id,d.reply_no,d.attempt_no,d.platform_code,d.outcome,"
                + " d.platform_reply_id,d.platform_receipt,d.attempted_at,d.note"
                + " from crm_review_reply_delivery d where d.tenant_id = ? and d.reply_id = ?"
                + " order by d.attempt_no desc",
            currentTenant(),
            replyId)) {
      out.add(
          new CrmReviewReplyDeliveryRow(
              row.get("id", UUID.class),
              nz(row.get("delivery_no", String.class)),
              row.get("reply_id", UUID.class),
              nz(row.get("reply_no", String.class)),
              row.get("attempt_no", Integer.class),
              nz(row.get("platform_code", String.class)),
              nz(row.get("outcome", String.class)),
              row.get("platform_reply_id", String.class),
              row.get("platform_receipt", String.class),
              momentText(row.get("attempted_at", OffsetDateTime.class)),
              row.get("note", String.class)));
    }
    return out;
  }

  /** 记一次投递尝试。 */
  private CrmReviewReplyDeliveryRow insertDelivery(
      UUID replyId,
      String replyNo,
      String platformCode,
      int attemptNo,
      String outcome,
      String platformReplyId,
      String platformReceipt,
      String note) {
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_review_reply_delivery"
            + "(id,tenant_id,delivery_no,reply_id,reply_no,attempt_no,platform_code,outcome,"
            + " platform_reply_id,platform_receipt,attempted_at,note)"
            + " values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,?)",
        id,
        currentTenant(),
        nextDeliveryNo(),
        replyId,
        replyNo,
        attemptNo,
        platformCode,
        outcome,
        platformReplyId,
        platformReceipt,
        moment,
        blankToNull(note));
    var row =
        sql.fetchOne(
            "select d.id,d.delivery_no,d.reply_id,d.reply_no,d.attempt_no,d.platform_code,d.outcome,"
                + " d.platform_reply_id,d.platform_receipt,d.attempted_at,d.note"
                + " from crm_review_reply_delivery d where d.tenant_id = ? and d.id = ?",
            currentTenant(),
            id);
    return new CrmReviewReplyDeliveryRow(
        row.get("id", UUID.class),
        nz(row.get("delivery_no", String.class)),
        row.get("reply_id", UUID.class),
        nz(row.get("reply_no", String.class)),
        row.get("attempt_no", Integer.class),
        nz(row.get("platform_code", String.class)),
        nz(row.get("outcome", String.class)),
        row.get("platform_reply_id", String.class),
        row.get("platform_receipt", String.class),
        momentText(row.get("attempted_at", OffsetDateTime.class)),
        row.get("note", String.class));
  }

  /** 下一次尝试号。 */
  private int nextAttemptNo(UUID replyId) {
    var row =
        sql.fetchOne(
            "select coalesce(max(d.attempt_no),0) as n from crm_review_reply_delivery d"
                + " where d.tenant_id = ? and d.reply_id = ?",
            currentTenant(),
            replyId);
    return (row == null ? 0 : row.get("n", Integer.class)) + 1;
  }

  /** 这条评价在这个形态下是否已有在途或已送达的回复。 */
  private boolean hasActiveReply(UUID reviewId, String replyType) {
    return sql.fetchOne(
            "select count(*) from crm_review_reply where tenant_id = ? and review_id = ?"
                + " and reply_type = ? and status in ('PENDING','DELIVERED')",
            currentTenant(),
            reviewId,
            replyType)
        .get(0, Long.class)
        > 0;
  }

  /** 批次里被跳过的评价（从 jsonb 读回）。 */
  private List<CrmReviewReplySkipped> skippedOf(Record batchRow) {
    List<CrmReviewReplySkipped> out = new ArrayList<>();
    var row =
        sql.fetchOne(
            "select b.skipped from crm_review_reply_batch b where b.tenant_id = ? and b.id = ?",
            currentTenant(),
            batchRow.get("id", UUID.class));
    if (row == null) return out;
    String raw = row.get("skipped", String.class);
    if (raw == null || raw.isBlank()) return out;
    try {
      JsonNode node = JSON.readTree(raw);
      if (node != null && node.isArray()) {
        for (JsonNode item : node) {
          out.add(
              new CrmReviewReplySkipped(
                  item.path("reviewId").asText(null),
                  item.path("reviewNo").asText(null),
                  item.path("reason").asText(null)));
        }
      }
    } catch (Exception e) {
      return List.of();
    }
    return out;
  }

  // ================================================================ 校验小工具

  /** 措辞校验的上下文（从规范行装配）。 */
  private ReplyWordingChecker.Context wordingContext(Record policy, boolean negativeReview) {
    return new ReplyWordingChecker.Context(
        policy.get("min_length", Integer.class),
        policy.get("max_length", Integer.class),
        Boolean.TRUE.equals(policy.get("allow_contact_info", Boolean.class)),
        Boolean.TRUE.equals(policy.get("allow_promotion", Boolean.class)),
        Boolean.TRUE.equals(policy.get("allow_external_link", Boolean.class)),
        policy.get("max_exclamation", Integer.class),
        policy.get("max_question_marks", Integer.class),
        Boolean.TRUE.equals(policy.get("require_apology", Boolean.class)),
        parseTerms(policy.get("banned_terms", String.class)),
        negativeReview);
  }

  /** 多语言条目规范化：校验 locale、去空白、只能有一个兜底语言。 */
  private List<CrmReviewReplyTemplateVariantInput> normalizeVariants(
      List<CrmReviewReplyTemplateVariantInput> raw) {
    if (raw == null || raw.isEmpty()) {
      throw bad("模板至少要有一条多语言正文：一条正文都没有的模板发出去只会得到一个空回复。");
    }
    List<CrmReviewReplyTemplateVariantInput> out = new ArrayList<>();
    int defaults = 0;
    for (var variant : raw) {
      String locale = requireLocale(blankToNull(variant.locale()));
      String body = nz(variant.body()).trim();
      if (body.isEmpty()) {
        throw bad("模板正文（" + locale + "）不能为空。");
      }
      boolean isDefault = Boolean.TRUE.equals(variant.isDefault());
      if (isDefault) defaults++;
      out.add(new CrmReviewReplyTemplateVariantInput(locale, body, isDefault));
    }
    if (defaults > 1) {
      throw bad("只能有一条多语言正文是兜底语言（isDefault）：兜底语言有两个的话，「找不到请求语言时用哪条」就没有答案了。");
    }
    return out;
  }

  /** 禁用词规范化。 */
  private List<String> normalizeBannedTerms(List<String> raw) {
    List<String> out = new ArrayList<>();
    if (raw == null) return out;
    for (String term : raw) {
      String value = blankToNull(term);
      if (value == null) continue;
      if (value.length() > 100) {
        throw bad("禁用词过长（最多 100 个字符）：" + value.substring(0, 40) + "…");
      }
      out.add(value);
    }
    return out;
  }

  /** 星级 → 档位：4-5 星 POSITIVE / 3 星 NEUTRAL / 1-2 星 NEGATIVE。 */
  static String scopeOf(Integer rating) {
    if (rating == null) return "NEUTRAL";
    if (rating >= 4) return "POSITIVE";
    if (rating == 3) return "NEUTRAL";
    return "NEGATIVE";
  }

  /** 档位 → 星级区间。 */
  static int[] ratingRange(String scope) {
    return switch (scope) {
      case "POSITIVE" -> new int[] {4, 5};
      case "NEUTRAL" -> new int[] {3, 3};
      case "NEGATIVE" -> new int[] {1, 2};
      default -> new int[] {1, 5};
    };
  }

  private static String requireReplyType(String value) {
    String type = nz(value).trim();
    if (!REPLY_TYPES.contains(type)) {
      throw bad(
          "回复形态只能是 "
              + String.join(" / ", REPLY_TYPES)
              + "，收到的是「"
              + nz(value)
              + "」。");
    }
    return type;
  }

  private static String requireScope(String value) {
    String scope = nz(value).trim();
    if (!RATING_SCOPES.contains(scope)) {
      throw bad("适用档位只能是 " + String.join(" / ", RATING_SCOPES) + "，收到的是「" + nz(value) + "」。");
    }
    return scope;
  }

  private static String requirePlatformCode(String value) {
    String code = nz(value).trim().toUpperCase(Locale.ROOT);
    if (code.isEmpty()) {
      throw bad("必须写明平台：评价回复的措辞规范是逐平台的。");
    }
    return code;
  }

  private static String requireLocale(String value) {
    String locale = nz(value).trim();
    if (locale.isEmpty()) {
      throw bad("必须写明语言（locale，例如 en-US / zh-CN）。");
    }
    if (!locale.matches("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")) {
      throw bad(
          "语言标签「"
              + locale
              + "」格式不对：请用 BCP-47 形式（en-US / zh-CN / de-DE）。"
              + "语言标签错了，模板就会被发到买家看不懂的语言里。");
    }
    return locale;
  }

  // ================================================================ 编号 / JSON / 时间

  private String nextPolicyNo() {
    return nextNo("crm_review_reply_policy_no_seq", "PL");
  }

  private String nextTemplateNo() {
    return nextNo("crm_review_reply_template_no_seq", "TPL");
  }

  private String nextBatchNo() {
    return nextNo("crm_review_reply_batch_no_seq", "BAT");
  }

  private String nextReplyNo() {
    return nextNo("crm_review_reply_no_seq", "RPL");
  }

  private String nextDeliveryNo() {
    return nextNo("crm_review_reply_delivery_no_seq", "DLV");
  }

  /** 业务号：前缀-yyyyMMdd-000001（序列单独授权，见 V298 授权段）。 */
  private String nextNo(String sequence, String prefix) {
    var row =
        sql.fetchOne(
            "select nextval('" + sequence + "') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return prefix + "-" + day + "-" + String.format("%06d", seq);
  }

  private String bannedTermsJson(List<String> terms) {
    ArrayNode array = json.createArrayNode();
    for (String term : terms) array.add(term);
    try {
      return json.writeValueAsString(array);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<String> parseTerms(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    try {
      JsonNode node = JSON.readTree(raw);
      List<String> out = new ArrayList<>();
      if (node != null && node.isArray()) {
        node.forEach(item -> out.add(item.asText()));
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  private String selectionJson(
      String platformCode, UUID shopId, String ratingScope, String replyType, String locale) {
    var node = json.createObjectNode();
    node.put("platformCode", platformCode);
    if (shopId != null) node.put("shopId", shopId.toString());
    node.put("ratingScope", ratingScope);
    node.put("replyType", replyType);
    node.put("locale", locale);
    try {
      return json.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private String skippedJson(List<CrmReviewReplySkipped> skipped) {
    var array = json.createArrayNode();
    for (var item : skipped) {
      var node = json.createObjectNode();
      if (item.reviewId() != null) node.put("reviewId", item.reviewId());
      if (item.reviewNo() != null) node.put("reviewNo", item.reviewNo());
      if (item.reason() != null) node.put("reason", item.reason());
      array.add(node);
    }
    try {
      return json.writeValueAsString(array);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
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
   * **静默偏 −28800 秒**，写进去的时刻与运营填的差 8 小时，而页面看上去一切正常。
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

  /** 可选时刻：空白即「不填」。 */
  private static OffsetDateTime optionalMoment(String value, String label) {
    String text = nz(value).trim();
    return text.isEmpty() ? null : parseMoment(text, label);
  }

  private static String momentText(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }

  /** LIKE 里的通配符要转义，否则用户搜 {@code %} 会命中全表。 */
  private static String escapeLike(String value) {
    return nz(value).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
