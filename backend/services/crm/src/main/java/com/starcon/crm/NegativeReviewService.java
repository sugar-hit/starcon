package com.starcon.crm;

import static com.starcon.crm.NegativeReviewModels.*;

import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewActionInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewActionRow;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewAppealCancelInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewAppealCreateInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewAppealOutcomeInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewAppealSubmittedInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewAppealBucket;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewAppealRow;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewCapabilities;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewCapabilityDimension;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewCandidatePage;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewCandidateRow;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewCaseCreateInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewCasePage;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewCaseRow;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewCauseBucket;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewClassifyInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewDetail;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewOrderContext;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewQuotaBucket;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewRuleInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewRulePage;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewRuleRow;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewRuleUpdateInput;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewShopView;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewStats;
import com.starcon.crm.NegativeReviewModels.CrmNegativeReviewStatusBucket;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.2.1.3 差评处理：差评专项处理（标记原因 / 判定是否申诉 / 联系买家解决后请求修改 /
 * 跟踪解决率）。
 *
 * <p>蓝图 15 卷 15.2.1.3 原话：
 *
 * <blockquote>
 *
 * 职责：差评专项处理：标记原因（产品/物流/服务/误判）、判定是否申诉（部分平台支持）、联系买家解决后请求修改、跟踪解决率。 输入：差评内容；处理动作；买家沟通记录。 输出：处理记录 + 申诉任务 + 解决状态。 关系：上游 = 15.2.1.1；下游 = 15.1.1.2 联系买家、11 售后（解决问题）。 <b>解决差评的正确路径是先解决买家问题再请求修改，直接申诉容易被驳回且消耗申诉配额。</b>
 *
 * </blockquote>
 *
 * <p><b>🔴 加粗句在本服务落成服务端强制的状态机</b>（不是页面提示）：
 *
 * <ol>
 *   <li><b>没走完「联系买家」就不允许请求修改、不允许建申诉任务</b> ——
 *       {@link #requireContactBuyer} 会拒绝并给出明确原因。
 *       「走完」的判据是「处理单下存在一条 {@code outcome=SUCCEEDED} 的 CONTACT_BUYER 动作」，
 *       一次失败的联系**不算**。
 *   <li><b>申诉配额是有限资源</b> —— 配额按（租户 × 平台 × 周期）计数，
 *       <b>建申诉任务即占用</b>（撤回归还），配额用尽时明确拒绝。
 *   <li><b>判定是否可申诉要真的可配</b> —— 规则落 {@code crm_negative_review_rule}：
 *       平台不支持申诉的平台**如实区分**，不一律说能申诉。
 * </ol>
 *
 * <p><b>🔴 平台侧未接入 ⇒ 零行写入</b>：请求修改（站内信）与申诉提交（平台申诉接口）
 * 都要落到平台侧，而 {@code channel/vendor} 目前只有 OAuth 适配器。
 * ⇒ 这两条路径由 {@link PlatformMessageSendPort} / {@link PlatformNegativeReviewPort}
 * 如实答「未接入」，服务层据此返回 <b>409</b> 且<b>一行都不写</b>，
 * <b>绝不返回假的「申诉已提交」回执</b>。
 *
 * <p><b>🔴 下游 11 售后未交付 ⇒ 只落转交意图</b>：售后转交动作只记原因，
 * <b>不写下游单据 id、不编下游 id</b>。
 */
@Service
public class NegativeReviewService {

  // ================================================================ 常量

  /** 列表分页上限。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 统计默认作用域（天）。 */
  static final int DEFAULT_STATS_DAYS = 30;

  /** 统计作用域的最小 / 最大天数。 */
  static final int MIN_STATS_DAYS = 7;

  static final int MAX_STATS_DAYS = 365;

  /** 「需要触达买家」的动作类型（它们走平台侧，所以要过未接入闸）。 */
  static final List<String> OUTREACH_ACTION_TYPES = List.of("CONTACT_BUYER", "REQUEST_MODIFICATION");

  /** 配额周期词表。 */
  static final List<String> QUOTA_PERIODS = List.of("MONTHLY", "QUARTERLY", "YEARLY", "NONE");

  // ================================================================ 依赖

  private final DSLContext sql;
  private final SignoffDeliveredOrderPort shops;
  private final ReviewOrderContextPort orderContext;
  private final PlatformMessageSendPort buyerMessage;
  private final PlatformNegativeReviewPort platformAppeal;

  public NegativeReviewService(
      DSLContext sql,
      SignoffDeliveredOrderPort shops,
      ReviewOrderContextPort orderContext,
      PlatformMessageSendPort buyerMessage,
      PlatformNegativeReviewPort platformAppeal) {
    this.sql = sql;
    this.shops = shops;
    this.orderContext = orderContext;
    this.buyerMessage = buyerMessage;
    this.platformAppeal = platformAppeal;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 三维刻意是 {@code available=false}：平台差评申诉接口、买家站内信发送、
   * 11 售后转交单据 —— 三者都**没有交付**，页面据此显式灰掉，
   * 绝不提供看起来能点的假入口。
   */
  public CrmNegativeReviewCapabilities capabilities() {
    List<CrmNegativeReviewCapabilityDimension> dimensions =
        List.of(
            new CrmNegativeReviewCapabilityDimension(
                "negative_review_case",
                "🔴 差评处理单（处理记录主体：状态 / 原因 / 解决状态）",
                true,
                "本服务 V302 crm_negative_review_case",
                "",
                "一条差评一个处理单；处理单真的落库、可筛选、可统计解决率"),
            new CrmNegativeReviewCapabilityDimension(
                "cause_classification",
                "🔴 原因分类（产品 / 物流 / 服务 / 误判）+ 判定是否可申诉",
                true,
                "本服务 V302 crm_negative_review_rule（逐平台 × 逐原因，可配）",
                "",
                "分类与申诉判定**真的可配**：平台支不支持申诉、原因能不能申诉、"
                    + "判定依据模板都在规则里；判定结果与**判定依据**一起落库，可复核"),
            new CrmNegativeReviewCapabilityDimension(
                "appeal_task",
                "🔴 申诉任务（申诉类型 / 配额计数 / 提交跟踪 / 结果）",
                true,
                "本服务 V302 crm_negative_review_appeal",
                "",
                "申诉任务真的落库；配额按（租户 × 平台 × 周期）计数，"
                    + "**建任务即占用**、撤回即归还；平台判定（通过 / 驳回）如实登记"),
            new CrmNegativeReviewCapabilityDimension(
                "case_action_log",
                "处理动作流水（每一步动作 + 操作者 + 时刻 + 结果）",
                true,
                "本服务 V302 crm_negative_review_action",
                "",
                "动作是**事实**：写进去就不改写；改写等于伪造处理过程。履历靠追加新动作来更正"),
            new CrmNegativeReviewCapabilityDimension(
                "resolution_rate",
                "🔴 解决率统计（分母 = 处理单数，分子 = 已解决数）",
                true,
                "本服务 V302（明细现算，**不建汇总表**）",
                "",
                "解决率由**服务端**算，分子分母口径随响应一起给出；前端只原样展示，"
                    + "不做任何前端算术"),
            new CrmNegativeReviewCapabilityDimension(
                "contact_buyer",
                "联系买家（解决差评的第一步）",
                true,
                "本服务 V302 crm_negative_review_action（人工交接）+ 上游 15.1.1.2 站内信端口",
                "15.1.1.2",
                "**平台站内信发送适配器未接入** ⇒ 走平台侧的请求明确答「未接入」（409）"
                    + "且**一行都不写**；运营走**人工交接**登记真实发生的联系"),
            new CrmNegativeReviewCapabilityDimension(
                "request_modification",
                "请求修改（联系买家解决之后的那一步）",
                true,
                "本服务 V302 crm_negative_review_action",
                "",
                "🔴 状态机强制顺序：**没走完「联系买家」就不允许请求修改**（服务端拒绝 + 明确原因）"),
            new CrmNegativeReviewCapabilityDimension(
                "platform_appeal_submit",
                "向平台提交申诉",
                false,
                "channel 的平台适配层目前**只有 OAuth 适配器**，没有差评申诉 / 修改申请适配器",
                "2",
                "提交端点明确答「未接入」（409）且**一行都不写**，"
                    + "**绝不返回假的「申诉已提交」回执**；平台适配器接上后本端点自动放行"),
            new CrmNegativeReviewCapabilityDimension(
                "after_sales_handoff",
                "转交售后（11 售后处理买家的实际问题）",
                false,
                "11 售后未交付",
                "11",
                "本模块**只落转交意图**（原因必填），**不写下游单据 id、不编下游 id** —— "
                    + "下游没交付就没有 id，编一个会让「已转售后」看起来发生过"),
            new CrmNegativeReviewCapabilityDimension(
                "platform_review_sync",
                "平台评价同步（差评的自动来源）",
                false,
                "15.2.1.1 的平台评价适配器**未交付**",
                "2",
                "差评目前只能从 15.2.1.1 评价列表里挑（人工登记 / 后续同步）"));
    return new CrmNegativeReviewCapabilities(
        dimensions,
        CAUSE_CODES,
        CASE_STATUSES,
        RESOLUTION_STATUSES,
        ACTION_TYPES,
        TRANSPORTS,
        OUTCOMES,
        APPEAL_TYPES,
        APPEAL_STATUSES,
        RULE_SCOPES,
        "平台差评申诉接口与买家站内信发送适配器均**未接入** ⇒ 走平台侧的请求明确答「未接入」（409）"
            + "且**一行都不写**；11 售后未交付 ⇒ 只落转交意图，不编下游 id。",
        "🔴 解决差评的正确路径是先解决买家问题再请求修改：服务端**强制**这个顺序 —— "
            + "没走完「联系买家」就请求修改 / 建申诉任务会被拒绝并说明原因。");
  }

  // ================================================================ 店铺目录

  /** 店铺目录（来自上游 channel 的真实清单）+ 每个平台的差评处理策略状态。 */
  public List<CrmNegativeReviewShopView> shops(String bearer) {
    List<CrmNegativeReviewShopView> out = new ArrayList<>();
    for (var shop : shops.shops(bearer)) {
      var policy = platformPolicy(nz(shop.platformCode())).orElse(null);
      String policyNote =
          policy == null
              ? "该平台还没有配置差评处理策略：差评阈值与申诉配额都是规则，"
                  + "没有策略就没有「差评」这个口径，也无法建处理单。"
              : "差评阈值 rating <= "
                  + policy.negativeRatingThreshold()
                  + "；"
                  + (policy.appealSupported()
                      ? "支持申诉（配额 " + policy.appealQuotaLimit() + " 次/" + policy.quotaPeriod() + "）"
                      : "**不支持申诉**");
      out.add(
          new CrmNegativeReviewShopView(
              shop.id(),
              nz(shop.code()),
              nz(shop.name()),
              nz(shop.platformCode()),
              nz(shop.platformName()),
              nz(shop.status()),
              policy != null,
              policy == null ? null : policy.negativeRatingThreshold(),
              policy != null && policy.appealSupported(),
              policy == null || policy.contactBuyerRequired(),
              policy == null ? null : policy.appealQuotaLimit(),
              policy == null ? null : policy.quotaPeriod(),
              policyNote));
    }
    return out;
  }

  // ================================================================ 规则

  /** 规则列表（真分页 + 真筛选）。 */
  public CrmNegativeReviewRulePage rules(
      String ruleScope, String platformCode, String causeCode, String status, int page, int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");
    if (!blank(ruleScope)) {
      where.append(" and rule_scope = ?");
      binds.add(ruleScope.trim());
    }
    if (!blank(platformCode)) {
      where.append(" and platform_code = ?");
      binds.add(platformCode.trim());
    }
    if (!blank(causeCode)) {
      where.append(" and cause_code = ?");
      binds.add(causeCode.trim());
    }
    if (!blank(status)) {
      where.append(" and status = ?");
      binds.add(status.trim());
    }
    long total =
        sql.fetchOne("select count(*) from crm_negative_review_rule" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmNegativeReviewRuleRow> items =
        sql.fetch(
                ruleColumns()
                    + " from crm_negative_review_rule"
                    + where
                    + " order by platform_code, rule_scope, cause_code nulls first, id limit ? offset ?",
                pageBinds.toArray())
            .map(NegativeReviewService::rule);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmNegativeReviewRulePage(items, safePage, safeSize, total, totalPages);
  }

  /** 单条规则。 */
  public CrmNegativeReviewRuleRow rule(UUID ruleId) {
    var row = sql.fetchOne(ruleColumns() + " from crm_negative_review_rule where id = ?", ruleId);
    if (row == null) {
      throw notFound("差评处理规则不存在（或不在当前租户的规则里）：" + ruleId);
    }
    return rule(row);
  }

  /** 新建一条规则。 */
  public CrmNegativeReviewRuleRow createRule(CrmNegativeReviewRuleInput input, UUID userId) {
    String scope = nz(input.ruleScope()).trim().toUpperCase();
    if (!RULE_SCOPES.contains(scope)) {
      throw bad("规则作用域只能是 PLATFORM（逐平台的差评处理策略）或 CAUSE（逐「平台 × 原因」的规则）。");
    }
    String platform = nz(input.platformCode()).trim();
    if (platform.isEmpty()) throw bad("必须指定平台。");
    String cause = blankToNull(input.causeCode());
    if ("CAUSE".equals(scope) && cause == null) {
      throw bad("原因规则必须指定原因分类（PRODUCT / LOGISTICS / SERVICE / MISJUDGMENT）。");
    }
    if ("PLATFORM".equals(scope) && cause != null) {
      throw bad("平台策略不能带原因分类：平台策略管的是阈值 / 申诉支持 / 配额，原因由 CAUSE 规则管。");
    }
    if (cause != null && !CAUSE_CODES.contains(cause.trim().toUpperCase())) {
      throw bad("原因分类只能是 " + String.join(" / ", CAUSE_CODES) + "。");
    }
    String quotaPeriod = normalizeQuotaPeriod(input.quotaPeriod());
    int threshold =
        input.negativeRatingThreshold() == null ? 2 : input.negativeRatingThreshold();
    int quotaLimit = input.appealQuotaLimit() == null ? 0 : input.appealQuotaLimit();
    int windowDays = input.appealWindowDays() == null ? 30 : input.appealWindowDays();

    UUID id = UUID.randomUUID();
    try {
      sql.execute(
          "insert into crm_negative_review_rule"
              + "(id,tenant_id,rule_no,rule_scope,platform_code,cause_code,label,description,"
              + " negative_rating_threshold,appeal_supported,contact_buyer_required,"
              + " appeal_quota_limit,quota_period,appeal_window_days,appeal_eligible,"
              + " appeal_basis_template,evidence_required,priority,version,status,effective_from,"
              + " note,created_by,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,'ACTIVE',?::timestamptz,?,?,"
              + "?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          nextNo("crm_negative_review_rule_no_seq", "NVR"),
          scope,
          platform,
          cause == null ? null : cause.trim().toUpperCase(),
          nz(input.label()).trim(),
          blankToNull(input.description()),
          threshold,
          Boolean.TRUE.equals(input.appealSupported()),
          input.contactBuyerRequired() == null || input.contactBuyerRequired(),
          quotaLimit,
          quotaPeriod,
          windowDays,
          Boolean.TRUE.equals(input.appealEligible()),
          blankToNull(input.appealBasisTemplate()),
          Boolean.TRUE.equals(input.evidenceRequired()),
          input.priority() == null ? 100 : input.priority(),
          now(),
          blankToNull(input.note()),
          userId,
          now(),
          now());
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      // ⚠️ jOOQ 的 execute 会被 Spring 的异常翻译器包成 DataIntegrityViolationException
      //   （不是 jOOQ 自己的 DataAccessException）—— catch 错类型就等于没 catch。
      if (String.valueOf(e.getMessage()).contains("crm_negative_review_rule_platform_uniq")) {
        throw conflict(
            "平台「"
                + platform
                + "」已经有一条差评处理策略了：一个平台只能有一条策略规则"
                + "（否则「这条差评按哪个阈值判的」就答不了）。要改请直接更新那一条。");
      }
      if (String.valueOf(e.getMessage()).contains("crm_negative_review_rule_cause_uniq")) {
        throw conflict(
            "平台「"
                + platform
                + "」的该原因已经有一条规则了：一个（平台 × 原因）只能有一条规则。"
                + "要改请直接更新那一条。");
      }
      throw e;
    }
    return rule(id);
  }

  /** 更新一条规则（原地改 = 版本 +1）。 */
  public CrmNegativeReviewRuleRow updateRule(
      UUID ruleId, CrmNegativeReviewRuleUpdateInput input, UUID userId) {
    var current = rule(ruleId);
    String label = input.label() == null ? current.label() : nz(input.label()).trim();
    if (label.isEmpty()) throw bad("规则名称不能为空。");
    String status = input.status() == null ? current.status() : nz(input.status()).trim().toUpperCase();
    if (!status.equals("ACTIVE") && !status.equals("ARCHIVED")) {
      throw bad("规则状态只能是 ACTIVE 或 ARCHIVED。");
    }
    Integer threshold =
        input.negativeRatingThreshold() == null
            ? current.negativeRatingThreshold()
            : input.negativeRatingThreshold();
    Integer quotaLimit =
        input.appealQuotaLimit() == null ? current.appealQuotaLimit() : input.appealQuotaLimit();
    Integer windowDays =
        input.appealWindowDays() == null ? current.appealWindowDays() : input.appealWindowDays();
    String quotaPeriod =
        input.quotaPeriod() == null ? current.quotaPeriod() : normalizeQuotaPeriod(input.quotaPeriod());
    sql.execute(
        "update crm_negative_review_rule set label = ?, description = ?,"
            + " negative_rating_threshold = ?, appeal_supported = ?, contact_buyer_required = ?,"
            + " appeal_quota_limit = ?, quota_period = ?, appeal_window_days = ?, appeal_eligible = ?,"
            + " appeal_basis_template = ?, evidence_required = ?, priority = ?, status = ?,"
            + " note = ?, version = version + 1, updated_at = ?::timestamptz where id = ?",
        label,
        input.description() == null ? current.description() : blankToNull(input.description()),
        threshold,
        input.appealSupported() == null ? current.appealSupported() : input.appealSupported(),
        input.contactBuyerRequired() == null
            ? current.contactBuyerRequired()
            : input.contactBuyerRequired(),
        quotaLimit,
        quotaPeriod,
        windowDays,
        input.appealEligible() == null ? current.appealEligible() : input.appealEligible(),
        input.appealBasisTemplate() == null
            ? current.appealBasisTemplate()
            : blankToNull(input.appealBasisTemplate()),
        input.evidenceRequired() == null
            ? current.evidenceRequired()
            : input.evidenceRequired(),
        input.priority() == null ? current.priority() : input.priority(),
        status,
        input.note() == null ? current.note() : blankToNull(input.note()),
        now(),
        ruleId);
    return rule(ruleId);
  }

  // ================================================================ 候选差评（还没建处理单的）

  /**
   * 候选差评清单（来自 15.2.1.1 的评价列表，**还没建处理单**的那些）。
   *
   * <p>🔴 「差评清单」与「处理单清单」是**两件事**：处理单是**我们已经开始处理**的那些，
   * 候选清单是**还没人接手**的那些 —— 没有候选清单，第一张处理单永远建不出来。
   *
   * <p>默认只列**该平台阈值以内的评价**（{@code negativeOnly=true}）：
   * 差评阈值是**规则**（逐平台可配），没有规则就没有「差评」这个口径。
   */
  public CrmNegativeReviewCandidatePage candidates(
      UUID shopId,
      String platformCode,
      Integer rating,
      String keyword,
      boolean negativeOnly,
      int page,
      int size) {
    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");
    if (shopId != null) {
      where.append(" and r.shop_id = ?");
      binds.add(shopId);
    }
    if (!blank(platformCode)) {
      where.append(" and r.platform_code = ?");
      binds.add(platformCode.trim());
    }
    if (rating != null) {
      where.append(" and r.rating = ?");
      binds.add(rating);
    }
    if (!blank(keyword)) {
      where.append(" and (r.content ilike ? or r.title ilike ? or r.review_no ilike ?)");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    if (negativeOnly) {
      where.append(
          " and p.id is not null and r.rating <= p.negative_rating_threshold");
    }
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    long total =
        sql.fetchOne(
                "select count(*) from crm_product_review r"
                    + " left join lateral (select id, negative_rating_threshold"
                    + "   from crm_negative_review_rule"
                    + "  where platform_code = r.platform_code and rule_scope = 'PLATFORM'"
                    + "    and status = 'ACTIVE' and effective_from <= now()"
                    + "    and (effective_to is null or effective_to > now())) p on true"
                    + where,
                binds.toArray())
            .get(0, Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmNegativeReviewCandidateRow> items =
        sql.fetch(
                "select r.id as review_id, r.review_no, r.rating, r.title, r.content, r.shop_id,"
                    + " r.shop_name, r.platform_code, r.product_ref, r.product_title, r.variant_ref,"
                    + " r.buyer_ref, r.buyer_name, r.sales_order_no, r.platform_reviewed_at,"
                    + " p.id as policy_id, p.negative_rating_threshold,"
                    + " c.id as case_id, c.case_no"
                    + " from crm_product_review r"
                    + " left join lateral (select id, negative_rating_threshold"
                    + "   from crm_negative_review_rule"
                    + "  where platform_code = r.platform_code and rule_scope = 'PLATFORM'"
                    + "    and status = 'ACTIVE' and effective_from <= now()"
                    + "    and (effective_to is null or effective_to > now())) p on true"
                    + " left join crm_negative_review_case c on c.review_id = r.id"
                    + where
                    + " order by r.platform_reviewed_at desc, r.id limit ? offset ?",
                pageBinds.toArray())
            .map(NegativeReviewService::candidate);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmNegativeReviewCandidatePage(items, safePage, safeSize, total, totalPages);
  }

  // ================================================================ 处理单

  /**
   * 为一条差评建处理单。
   *
   * <p>🔴 建单前先取该平台的**策略规则**：差评阈值、申诉配额、是否必须先联系买家
   * 全部来自规则，并**快照**到处理单上 —— 之后规则改了，本单的口径不变，
   * 「这条差评当时按哪个阈值判的」永远答得上来。
   */
  public CrmNegativeReviewCaseRow createCase(
      CrmNegativeReviewCaseCreateInput input, UUID userId, String bearer) {
    var review =
        sql.fetchOne(
            "select id, review_no, shop_id, shop_name, platform_code, rating, title, content,"
                + " external_review_id, buyer_ref, buyer_name, sales_order_no"
                + " from crm_product_review where id = ?",
            input.reviewId());
    if (review == null) {
      throw notFound(
          "评价不存在（或不在当前租户的评价里）："
              + input.reviewId()
              + "。差评处理单必须挂在一条真实存在的评价上。");
    }
    String platform = nz(review.get("platform_code", String.class));
    var policy =
        platformPolicy(platform)
            .orElseThrow(
                () ->
                    bad(
                        "平台「"
                            + platform
                            + "」还没有配置差评处理策略：差评阈值与申诉配额都是规则，"
                            + "没有策略就没有「差评」这个口径，也无法建处理单。"
                            + "请先到「处理规则」里为该平台建一条 PLATFORM 策略。"));
    int rating = review.get("rating", Integer.class);
    int threshold = policy.negativeRatingThreshold();
    if (rating > threshold) {
      throw bad(
          "这条评价不是差评：rating "
              + rating
              + " > 平台「"
              + platform
              + "」的差评阈值 "
              + threshold
              + "（阈值在差评处理规则里按平台配置）。");
    }
    var existing =
        sql.fetchOne("select id, case_no from crm_negative_review_case where review_id = ?", review.get("id", UUID.class));
    if (existing != null) {
      throw conflict(
          "这条评价已经有处理单了（"
              + existing.get("case_no", String.class)
              + "）：同一条差评只允许一个处理单 —— "
              + "两条处理单会让解决率的分母翻倍，也让「这条差评到底谁在跟」说不清。");
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into crm_negative_review_case"
            + "(id,tenant_id,case_no,review_id,review_no,review_rating,review_title,"
            + " review_content,review_external_id,shop_id,shop_name,platform_code,buyer_ref,"
            + " buyer_name,sales_order_no,policy_rule_id,policy_rule_version,"
            + " negative_rating_threshold,appeal_supported,contact_buyer_required,"
            + " appeal_quota_limit,quota_period,appeal_window_days,appeal_eligible,"
            + " case_status,resolution_status,note,created_by,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,false,"
            + " 'OPEN','UNRESOLVED',?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        nextNo("crm_negative_review_case_no_seq", "NVC"),
        review.get("id", UUID.class),
        review.get("review_no", String.class),
        rating,
        review.get("title", String.class),
        review.get("content", String.class),
        review.get("external_review_id", String.class),
        review.get("shop_id", UUID.class),
        review.get("shop_name", String.class),
        platform,
        review.get("buyer_ref", String.class),
        review.get("buyer_name", String.class),
        review.get("sales_order_no", String.class),
        policy.id(),
        policy.version(),
        threshold,
        policy.appealSupported(),
        policy.contactBuyerRequired(),
        policy.appealQuotaLimit(),
        policy.quotaPeriod(),
        policy.appealWindowDays(),
        blankToNull(input.note()),
        userId,
        now(),
        now());
    return caseRow(id);
  }

  /** 处理单列表（真分页 + 真筛选）。 */
  public CrmNegativeReviewCasePage cases(
      UUID shopId,
      String platformCode,
      String causeCode,
      String caseStatus,
      String resolutionStatus,
      Boolean appealEligible,
      Integer rating,
      String keyword,
      int page,
      int size) {
    List<Object> binds = new ArrayList<>();
    String where = caseWhere(
        shopId, platformCode, causeCode, caseStatus, resolutionStatus, appealEligible, rating, keyword, binds);
    long total =
        sql.fetchOne("select count(*) from crm_negative_review_case c" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmNegativeReviewCaseRow> items =
        sql.fetch(
                caseColumns()
                    + " from crm_negative_review_case c"
                    + where
                    + " order by c.created_at desc, c.id limit ? offset ?",
                pageBinds.toArray())
            .map(NegativeReviewService::caseOf);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmNegativeReviewCasePage(items, safePage, safeSize, total, totalPages);
  }

  /**
   * 处理单详情 = 处理单 + 动作流水 + 申诉任务 + 关联订单上下文 + 当时用的规则。
   *
   * <p>🔴 订单上下文读不到时**明确说明原因**，绝不返回空白（空白会被客服读成
   * 「这条差评没有关联订单」）。
   */
  public CrmNegativeReviewDetail detail(UUID caseId, String bearer) {
    var row = sql.fetchOne(caseColumns() + " from crm_negative_review_case c where c.id = ?", caseId);
    if (row == null) {
      throw notFound("差评处理单不存在（或不在当前租户的处理单里）：" + caseId);
    }
    var theCase = caseOf(row);
    var actions =
        sql.fetch(
                "select id, action_no, case_id, case_no, action_type, transport, outcome,"
                    + " outcome_note, summary, detail, after_sales_reason, appeal_id, appeal_no,"
                    + " operator_id, operator_name, action_at, created_at"
                    + " from crm_negative_review_action where case_id = ?"
                    + " order by action_at desc, created_at desc, id desc",
                caseId)
            .map(NegativeReviewService::action);
    var appeals =
        sql.fetch(
                "select id, appeal_no, case_id, case_no, review_id, review_no, shop_id,"
                    + " platform_code, appeal_type, cause_code, reason, content, appeal_status,"
                    + " transport, submitted_at, platform_reference, platform_receipt, decided_at,"
                    + " decided_result, decided_reason, cancelled_at, cancel_reason,"
                    + " quota_period_key, quota_index, quota_limit, quota_used_before, created_by,"
                    + " created_at, updated_at"
                    + " from crm_negative_review_appeal where case_id = ?"
                    + " order by created_at desc, id desc",
                caseId)
            .map(NegativeReviewService::appeal);

    CrmNegativeReviewOrderContext orderCtx;
    if (theCase.salesOrderNo() == null || theCase.salesOrderNo().isBlank()) {
      orderCtx =
          new CrmNegativeReviewOrderContext(
              false,
              "这条差评没有登记关联订单号：它可能来自没有订单的浏览/互动，"
                  + "或者登记评价时漏填了订单号（可在评价列表里补上）。",
              null, null, null, null, null, null, null);
    } else {
      var ctx = orderContext.context(theCase.salesOrderNo(), bearer);
      orderCtx =
          new CrmNegativeReviewOrderContext(
              ctx.available(),
              ctx.reason(),
              ctx.salesOrderNo(),
              ctx.orderNo(),
              ctx.orderStatus(),
              ctx.deliveredAt(),
              ctx.deliveryState(),
              ctx.carrierCode(),
              ctx.caseNo());
    }

    var policy = platformPolicy(theCase.platformCode()).orElse(null);
    var causeRule =
        theCase.causeCode() == null
            ? null
            : causeRule(theCase.platformCode(), theCase.causeCode()).orElse(null);
    return new CrmNegativeReviewDetail(
        theCase, actions, appeals, orderCtx, policy, causeRule);
  }

  // ================================================================ 原因分类与申诉判定

  /**
   * 标记原因分类，**顺带**按规则判定是否可申诉（并落下判定依据）。
   *
   * <p>🔴 判定不是硬编码的：能不能申诉取决于「平台支不支持申诉」×「这个原因能不能申诉」，
   * 两条都来自**规则**。判定**结果**与判定**依据**一起落库 ——
   * 只给结论不给依据，这条判定永远无法复核。
   */
  public CrmNegativeReviewCaseRow classify(
      UUID caseId, CrmNegativeReviewClassifyInput input, UUID userId) {
    var theCase = caseRow(caseId);
    if (theCase.caseStatus().equals("RESOLVED") || theCase.caseStatus().equals("CLOSED")) {
      throw conflict(
          "处理单已"
              + ("RESOLVED".equals(theCase.caseStatus()) ? "解决" : "关闭")
              + "，不能再改原因分类：改它会让「这条差评当初为什么被这么判」在历史里出现两个答案。");
    }
    String cause = nz(input.causeCode()).trim().toUpperCase();
    if (!CAUSE_CODES.contains(cause)) {
      throw bad("原因分类只能是 " + String.join(" / ", CAUSE_CODES) + "。");
    }
    var policy =
        platformPolicy(theCase.platformCode())
            .orElseThrow(
                () ->
                    bad(
                        "平台「"
                            + theCase.platformCode()
                            + "」还没有配置差评处理策略，无法判定是否可申诉。"));
    var causeRule =
        causeRule(theCase.platformCode(), cause)
            .orElseThrow(
                () ->
                    bad(
                        "平台「"
                            + theCase.platformCode()
                            + "」还没有配置「"
                            + cause
                            + "」这个原因分类的规则："
                            + "原因分类与申诉判定都是**可配的规则**，"
                            + "没有规则就不能凭硬编码去判「这条差评能不能申诉」。"
                            + "请先到「处理规则」里为该平台建一条 CAUSE 规则。"));
    if (causeRule.evidenceRequired()
        && (input.causeNote() == null || input.causeNote().isBlank())) {
      throw bad(
          "原因「"
              + cause
              + "」在平台「"
              + theCase.platformCode()
              + "」要求留证据：请写清判定依据（买家说了什么 / 物流事实是什么）。");
    }

    boolean eligible = policy.appealSupported() && causeRule.appealEligible();
    String basis = appealBasis(policy, causeRule, cause, eligible);

    sql.execute(
        "update crm_negative_review_case set cause_code = ?, cause_label = ?, cause_note = ?,"
            + " cause_rule_id = ?, cause_rule_version = ?, appeal_eligible = ?, appeal_basis = ?,"
            + " appeal_assessed_at = ?::timestamptz, updated_at = ?::timestamptz where id = ?",
        cause,
        causeRule.label(),
        blankToNull(input.causeNote()),
        causeRule.id(),
        causeRule.version(),
        eligible,
        basis,
        now(),
        now(),
        caseId);
    return caseRow(caseId);
  }

  /** 判定依据的文案：说清「哪些条件导致可 / 不可申诉」。 */
  private String appealBasis(
      CrmNegativeReviewRuleRow policy,
      CrmNegativeReviewRuleRow causeRule,
      String cause,
      boolean eligible) {
    StringBuilder sb = new StringBuilder();
    sb.append("平台「")
        .append(policy.platformCode())
        .append("」")
        .append(policy.appealSupported() ? "支持" : "**不支持**")
        .append("申诉");
    sb.append("；原因「").append(causeRule.label()).append("」");
    sb.append(causeRule.appealEligible() ? "在该平台**可**申诉" : "在该平台**不可**申诉");
    sb.append("。配额：每 ").append(policy.quotaPeriod()).append(" ").append(policy.appealQuotaLimit()).append(" 次");
    if (!policy.appealSupported()) {
      sb.append("（该平台未开放申诉通道，配额不生效）");
    }
    if (causeRule.appealBasisTemplate() != null && !causeRule.appealBasisTemplate().isBlank()) {
      sb.append("。判定依据（规则模板）：").append(causeRule.appealBasisTemplate());
    }
    sb.append("。结论：").append(eligible ? "**可申诉**" : "**不可申诉**");
    return sb.toString();
  }

  // ================================================================ 处理动作

  /** 处理单的动作流水。 */
  public List<CrmNegativeReviewActionRow> actions(UUID caseId) {
    return sql.fetch(
            "select id, action_no, case_id, case_no, action_type, transport, outcome,"
                + " outcome_note, summary, detail, after_sales_reason, appeal_id, appeal_no,"
                + " operator_id, operator_name, action_at, created_at"
                + " from crm_negative_review_action where case_id = ?"
                + " order by action_at desc, created_at desc, id desc",
            caseId)
        .map(NegativeReviewService::action);
  }

  /**
   * 记录一个处理动作。
   *
   * <p>🔴 <b>走平台侧的动作（transport=PLATFORM_API）先过未接入闸</b>：
   * 平台适配器没接上 ⇒ 返回 <b>409</b> 且<b>一行都不写</b> ——
   * 一条返回「已联系买家」的假记录会让加粗句要的顺序形同虚设。
   *
   * <p>🔴 <b>请求修改必须先走完「联系买家」</b>：没成功联系过买家就请求修改，
   * 直接被拒并说明原因。
   */
  public CrmNegativeReviewActionRow recordAction(
      UUID caseId,
      CrmNegativeReviewActionInput input,
      UUID userId,
      String bearer) {
    var theCase = caseRow(caseId);
    String actionType = nz(input.actionType()).trim().toUpperCase();
    if (!ACTION_TYPES.contains(actionType)) {
      throw bad("处理动作类型只能是 " + String.join(" / ", ACTION_TYPES) + "。");
    }
    String transport = nz(input.transport()).trim().toUpperCase();
    if (transport.isEmpty()) transport = "INTERNAL";
    if (!TRANSPORTS.contains(transport)) {
      throw bad("触达方式只能是 " + String.join(" / ", TRANSPORTS) + "。");
    }
    String outcome = nz(input.outcome()).trim().toUpperCase();
    if (!OUTCOMES.contains(outcome)) {
      throw bad("动作结果只能是 " + String.join(" / ", OUTCOMES) + "。");
    }
    if (theCase.caseStatus().equals("CLOSED")) {
      throw conflict("处理单已关闭，不能再登记动作。");
    }

    // ---- 平台侧未接入闸（🔴 零行写入）----
    if ("PLATFORM_API".equals(transport)) {
      if (!OUTREACH_ACTION_TYPES.contains(actionType)) {
        throw bad(
            "动作「"
                + actionType
                + "」不是触达买家的动作，没有平台侧通道："
                + "只有「联系买家」与「请求修改」会走到平台。");
      }
      var availability = buyerMessage.availability(List.of(theCase.platformCode()));
      boolean connected = false;
      String reason = "";
      for (var item : availability) {
        if (theCase.platformCode().equals(item.platformCode())) {
          connected = item.connected();
          reason = item.reason();
        }
      }
      if (!connected) {
        // 🔴 一行都不写：连「尝试记录」都不写 —— 写一条「试过了」的行本身没错，
        //   但那样会让「已联系买家 / 已请求修改」的语义被稀释。
        throw conflict(
            (reason == null || reason.isBlank()
                    ? "买家站内信发送适配器未接入：平台侧还没有发送通道。"
                    : reason)
                + " 这次请求**没有写入任何数据**，处理单状态保持不变。"
                + "运营可在平台侧实际联系买家后，用 transport=MANUAL_HANDOFF 登记这一步。");
      }
      // 适配器接上之后才会走到这里。
      var sent =
          buyerMessage.send(
              new PlatformMessageSendPort.PlatformSendRequest(
                  theCase.platformCode(),
                  theCase.shopId(),
                  theCase.buyerRef(),
                  theCase.salesOrderNo(),
                  "关于您的评价",
                  nz(input.summary()),
                  List.of()),
              bearer);
      if (!sent.sent()) {
        throw conflict(
            "平台侧发送失败："
                + (sent.failureReason() == null || sent.failureReason().isBlank()
                    ? "适配器没有给出原因。"
                    : sent.failureReason())
                + " 这次请求**没有写入任何数据**。");
      }
    }

    // ---- 顺序闸：请求修改必须先走完「联系买家」----
    if ("REQUEST_MODIFICATION".equals(actionType)) {
      requireContactBuyer(theCase);
    }

    // ---- 售后转交必须写原因（🔴 不落下游 id）----
    if ("AFTER_SALES_HANDOFF".equals(actionType)) {
      if (input.afterSalesReason() == null || input.afterSalesReason().isBlank()) {
        throw bad(
            "转交售后必须写原因：下游 11 售后未交付，本模块只落**转交意图** —— "
                + "没有原因的转交等于没转。");
      }
    }

    OffsetDateTime actionAt =
        input.occurredAt() == null || input.occurredAt().isBlank()
            ? now()
            : parseMoment(input.occurredAt(), "动作发生时刻");
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into crm_negative_review_action"
            + "(id,tenant_id,action_no,case_id,case_no,action_type,transport,outcome,"
            + " outcome_note,summary,detail,after_sales_reason,operator_id,operator_name,"
            + " action_at,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        nextNo("crm_negative_review_action_no_seq", "NVA"),
        caseId,
        theCase.caseNo(),
        actionType,
        transport,
        outcome,
        blankToNull(input.outcomeNote()),
        blankToNull(input.summary()),
        blankToNull(input.detail()),
        blankToNull(input.afterSalesReason()),
        userId,
        blankToNull(input.operatorName()),
        actionAt,
        now(),
        now());

    // ---- 处理单状态随动作推进 ----
    if ("RESOLVE".equals(actionType)) {
      applyResolution(theCase, "RESOLVED", input.summary(), actionAt);
    } else if ("CLOSE".equals(actionType)) {
      applyResolution(theCase, "CLOSED_UNRESOLVED", input.summary(), actionAt);
    } else if (theCase.caseStatus().equals("OPEN")) {
      sql.execute(
          "update crm_negative_review_case set case_status = 'IN_PROGRESS',"
              + " updated_at = ?::timestamptz where id = ?",
          now(),
          caseId);
    }
    return action(id);
  }

  /** 把处理单落到「已解决 / 已关闭未解决」。 */
  private void applyResolution(
      CrmNegativeReviewCaseRow theCase, String resolutionStatus, String summary, OffsetDateTime at) {
    if (theCase.caseStatus().equals("RESOLVED") || theCase.caseStatus().equals("CLOSED")) {
      throw conflict("处理单已经" + ("RESOLVED".equals(theCase.caseStatus()) ? "解决" : "关闭") + "了。");
    }
    String note = summary == null || summary.isBlank() ? null : summary.trim();
    if (note == null) {
      throw bad("标记解决 / 关闭必须写说明：否则「已解决」是一个查无实据的说法，解决率分子也会跟着虚高。");
    }
    String caseStatus = "RESOLVED".equals(resolutionStatus) ? "RESOLVED" : "CLOSED";
    sql.execute(
        "update crm_negative_review_case set case_status = ?, resolution_status = ?,"
            + " resolution_note = ?, resolution_at = ?::timestamptz, updated_at = ?::timestamptz"
            + " where id = ?",
        caseStatus,
        resolutionStatus,
        note,
        at,
        now(),
        theCase.id());
  }

  /**
   * 🔴 顺序闸：没走完「联系买家」就不允许请求修改 / 建申诉任务。
   *
   * <p>判据：处理单下存在一条 {@code outcome='SUCCEEDED'} 的 CONTACT_BUYER 动作。
   * 一次失败的联系**不算** —— 买家没被联系上就请求修改，正是加粗句警告的
   * 「直接申诉容易被驳回」的同一种错。
   */
  private void requireContactBuyer(CrmNegativeReviewCaseRow theCase) {
    if (!theCase.contactBuyerRequired()) return;
    long done =
        sql.fetchOne(
                "select count(*) from crm_negative_review_action"
                    + " where case_id = ? and action_type = 'CONTACT_BUYER' and outcome = 'SUCCEEDED'",
                theCase.id())
            .get(0, Long.class);
    if (done > 0) return;
    throw conflict(
        "还没走完「联系买家」这一步：按蓝图的正确路径，必须**先联系买家解决问题**，"
            + "才能请求修改 / 提交申诉 —— 直接申诉容易被驳回且消耗申诉配额。"
            + "请先登记一条 outcome=SUCCEEDED 的「联系买家」动作。");
  }

  // ================================================================ 申诉任务

  /** 处理单的申诉任务列表。 */
  public List<CrmNegativeReviewAppealRow> appeals(UUID caseId) {
    return sql.fetch(
            "select id, appeal_no, case_id, case_no, review_id, review_no, shop_id,"
                + " platform_code, appeal_type, cause_code, reason, content, appeal_status,"
                + " transport, submitted_at, platform_reference, platform_receipt, decided_at,"
                + " decided_result, decided_reason, cancelled_at, cancel_reason,"
                + " quota_period_key, quota_index, quota_limit, quota_used_before, created_by,"
                + " created_at, updated_at"
                + " from crm_negative_review_appeal where case_id = ?"
                + " order by created_at desc, id desc",
            caseId)
        .map(NegativeReviewService::appeal);
  }

  /**
   * 建一个申诉任务。
   *
   * <p>🔴 三道闸，顺序固定：
   *
   * <ol>
   *   <li>处理单必须**已分类**（没有原因就没有「为什么可申诉」）；
   *   <li>必须**走完「联系买家」**（加粗句的强制顺序）；
   *   <li>**申诉配额**必须还有 —— 配额用尽时明确拒绝（配额是有限资源）。
   * </ol>
   */
  public CrmNegativeReviewAppealRow createAppeal(
      UUID caseId,
      CrmNegativeReviewAppealCreateInput input,
      UUID userId) {
    var theCase = caseRow(caseId);
    if (theCase.causeCode() == null) {
      throw conflict(
          "处理单还没有标记原因分类：申诉必须说清「为什么这条差评可以申诉」，"
              + "而原因来自分类与判定依据。");
    }
    String appealType = nz(input.appealType()).trim().toUpperCase();
    if (!APPEAL_TYPES.contains(appealType)) {
      throw bad("申诉类型只能是 " + String.join(" / ", APPEAL_TYPES) + "。");
    }
    String reason = nz(input.reason()).trim();
    if (reason.isEmpty()) throw bad("申诉理由必填：申诉是要提交给平台的事实。");

    requireContactBuyer(theCase);

    // 重新按**当前规则**判定一次：规则可能已被改过。
    var policy =
        platformPolicy(theCase.platformCode())
            .orElseThrow(
                () -> bad("平台「" + theCase.platformCode() + "」还没有配置差评处理策略。"));
    var causeRule =
        causeRule(theCase.platformCode(), theCase.causeCode())
            .orElseThrow(
                () ->
                    bad(
                        "平台「"
                            + theCase.platformCode()
                            + "」的「"
                            + theCase.causeCode()
                            + "」原因规则已不存在，无法判定是否可申诉。"));
    if (!policy.appealSupported()) {
      throw conflict(
          "平台「"
              + theCase.platformCode()
              + "」**不支持申诉**（该平台的差评处理策略里没有开放申诉通道）："
              + "这条差评只能走「联系买家 → 请求修改」这条路。");
    }
    if (!causeRule.appealEligible()) {
      throw conflict(
          "原因「"
              + causeRule.label()
              + "」在平台「"
              + theCase.platformCode()
              + "」**不可申诉**（原因规则里判定为不可申诉）："
              + "判定依据：" + causeRule.appealBasisTemplate());
    }
    if (policy.appealQuotaLimit() <= 0) {
      throw conflict(
          "平台「"
              + theCase.platformCode()
              + "」没有开放申诉配额（配额为 0）：不能建申诉任务。");
    }

    String periodKey = quotaPeriodKey(policy.quotaPeriod(), now());
    long used = quotaUsed(theCase.platformCode(), periodKey);
    if (used >= policy.appealQuotaLimit()) {
      throw conflict(
          "申诉配额已用尽：平台「"
              + theCase.platformCode()
              + "」每 "
              + policy.quotaPeriod()
              + " 可申诉 "
              + policy.appealQuotaLimit()
              + " 次，本周期（"
              + periodKey
              + "）已用 "
              + used
              + " 次。"
              + "配额用尽时硬申诉既容易被驳回，又会白白消耗额度。");
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into crm_negative_review_appeal"
            + "(id,tenant_id,appeal_no,case_id,case_no,review_id,review_no,shop_id,"
            + " platform_code,appeal_type,cause_code,reason,content,appeal_status,transport,"
            + " quota_period_key,quota_index,quota_limit,quota_used_before,created_by,created_at,"
            + " updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT','MANUAL_HANDOFF',?,?,?,?,?,"
            + "?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        nextNo("crm_negative_review_appeal_no_seq", "NVA"),
        caseId,
        theCase.caseNo(),
        theCase.reviewId(),
        theCase.reviewNo(),
        theCase.shopId(),
        theCase.platformCode(),
        appealType,
        theCase.causeCode(),
        reason,
        blankToNull(input.content()),
        periodKey,
        (int) (used + 1),
        policy.appealQuotaLimit(),
        (int) used,
        userId,
        now(),
        now());
    return appealRow(id);
  }

  /**
   * 向平台提交申诉。
   *
   * <p>🔴 <b>平台适配器未接入 ⇒ 409 且一行都不写</b>：申诉状态**不会**变成「已提交」，
   * 也**不会**出现任何平台回执。一条假的「申诉已提交」会让「差评已经处理掉了」
   * 看起来发生过，而现场一条真申诉都没提交。
   */
  public CrmNegativeReviewAppealRow submitAppeal(UUID caseId, UUID appealId, String bearer) {
    var theCase = caseRow(caseId);
    var theAppeal = appealRow(appealId);
    if (!theAppeal.caseId().equals(caseId)) {
      throw notFound("这个申诉任务不属于这张处理单：" + appealId);
    }
    if (!theAppeal.appealStatus().equals("DRAFT")) {
      throw conflict(
          "只有「待提交」的申诉任务能提交，当前状态是 " + theAppeal.appealStatus() + "。");
    }
    var outcome =
        platformAppeal.submitAppeal(
            new PlatformNegativeReviewPort.NegativeReviewAppealRequest(
                theCase.platformCode(),
                theCase.shopId(),
                theCase.reviewNo(),
                theCase.reviewExternalId(),
                theAppeal.appealType(),
                theAppeal.causeCode(),
                theAppeal.reason(),
                theAppeal.content()),
            bearer);
    if (!outcome.connected() || !outcome.submitted()) {
      // 🔴 一行都不写：状态保持 DRAFT，没有任何回执。
      throw conflict(
          (outcome.failureReason() == null || outcome.failureReason().isBlank()
                  ? "平台差评申诉适配器未交付：平台侧还没有申诉通道。"
                  : outcome.failureReason())
              + " 这次提交**没有写入任何数据**，申诉任务仍是「待提交」。"
              + "运营可在平台侧实际提交后，用「登记已提交」回填真实的提交时刻与平台判定。");
    }
    // 适配器接上之后才会走到这里（当前实现永远到不了）。
    sql.execute(
        "update crm_negative_review_appeal set appeal_status = 'SUBMITTED', transport = 'PLATFORM_API',"
            + " submitted_at = ?::timestamptz, platform_reference = ?, platform_receipt = ?,"
            + " updated_at = ?::timestamptz where id = ?",
        now(),
        blankToNull(outcome.platformAppealId()),
        blankToNull(outcome.receipt()),
        now(),
        appealId);
    return appealRow(appealId);
  }

  /**
   * 人工交接登记：运营已在**平台侧实际提交**申诉，回填真实的提交时刻。
   *
   * <p>🔴 这不是「假回执」：提交是**人**在平台上做的，本模块只是把**人观察到的事实**
   * 记下来（提交时刻 + 平台侧单号）。平台适配器接上后这条路径会自动让位。
   */
  public CrmNegativeReviewAppealRow markAppealSubmitted(
      UUID caseId,
      UUID appealId,
      CrmNegativeReviewAppealSubmittedInput input,
      UUID userId) {
    var theCase = caseRow(caseId);
    var theAppeal = appealRow(appealId);
    if (!theAppeal.caseId().equals(caseId)) {
      throw notFound("这个申诉任务不属于这张处理单：" + appealId);
    }
    if (!theAppeal.appealStatus().equals("DRAFT")) {
      throw conflict("只有「待提交」的申诉任务能登记提交，当前状态是 " + theAppeal.appealStatus() + "。");
    }
    OffsetDateTime submittedAt = parseMoment(input.submittedAt(), "平台侧实际提交时刻");
    sql.execute(
        "update crm_negative_review_appeal set appeal_status = 'SUBMITTED',"
            + " transport = 'MANUAL_HANDOFF', submitted_at = ?::timestamptz,"
            + " platform_reference = ?, updated_at = ?::timestamptz where id = ?",
        submittedAt,
        blankToNull(input.platformReference()),
        now(),
        appealId);
    insertAppealAction(theCase, appealId, theAppeal.appealNo(), "APPEAL_SUBMISSION", input.note(), userId);
    return appealRow(appealId);
  }

  /**
   * 人工交接登记：平台对申诉的判定（通过 / 驳回）。
   *
   * <p>🔴 申诉**通过** ⇒ 这条差评算「已解决」，处理单落到 RESOLVED ——
   * 解决率的分子随之增加；**驳回**则处理单保持未解决（可以继续走「联系买家 → 请求修改」）。
   */
  public CrmNegativeReviewAppealRow recordAppealOutcome(
      UUID caseId,
      UUID appealId,
      CrmNegativeReviewAppealOutcomeInput input,
      UUID userId) {
    var theCase = caseRow(caseId);
    var theAppeal = appealRow(appealId);
    if (!theAppeal.caseId().equals(caseId)) {
      throw notFound("这个申诉任务不属于这张处理单：" + appealId);
    }
    if (!theAppeal.appealStatus().equals("SUBMITTED")) {
      throw conflict(
          "只有「已提交」的申诉任务能登记平台判定，当前状态是 " + theAppeal.appealStatus() + "。");
    }
    String result = nz(input.result()).trim().toUpperCase();
    if (!result.equals("APPROVED") && !result.equals("REJECTED")) {
      throw bad("申诉判定只能是 APPROVED（通过）或 REJECTED（驳回）。");
    }
    OffsetDateTime decidedAt = parseMoment(input.decidedAt(), "平台判定时刻");
    sql.execute(
        "update crm_negative_review_appeal set appeal_status = ?, decided_result = ?,"
            + " decided_at = ?::timestamptz, decided_reason = ?, platform_receipt = ?,"
            + " updated_at = ?::timestamptz where id = ?",
        result,
        result,
        decidedAt,
        blankToNull(input.reason()),
        blankToNull(input.platformReceipt()),
        now(),
        appealId);
    insertAppealAction(theCase, appealId, theAppeal.appealNo(), "APPEAL_RESULT", input.reason(), userId);
    if (result.equals("APPROVED")
        && (theCase.caseStatus().equals("OPEN") || theCase.caseStatus().equals("IN_PROGRESS"))) {
      applyResolution(
          theCase,
          "RESOLVED",
          "申诉通过（" + theAppeal.appealNo() + "）" + (nz(input.reason()).isBlank() ? "" : "：" + nz(input.reason())),
          decidedAt);
    }
    return appealRow(appealId);
  }

  /** 撤回申诉任务（撤回归还申诉配额）。 */
  public CrmNegativeReviewAppealRow cancelAppeal(
      UUID caseId,
      UUID appealId,
      CrmNegativeReviewAppealCancelInput input,
      UUID userId) {
    var theCase = caseRow(caseId);
    var theAppeal = appealRow(appealId);
    if (!theAppeal.caseId().equals(caseId)) {
      throw notFound("这个申诉任务不属于这张处理单：" + appealId);
    }
    if (!theAppeal.appealStatus().equals("DRAFT")) {
      throw conflict("只有「待提交」的申诉任务能撤回，当前状态是 " + theAppeal.appealStatus() + "。");
    }
    String reason = nz(input.reason()).trim();
    if (reason.isEmpty()) throw bad("撤回申诉必须写原因。");
    sql.execute(
        "update crm_negative_review_appeal set appeal_status = 'CANCELLED',"
            + " cancelled_at = ?::timestamptz, cancel_reason = ?, updated_at = ?::timestamptz"
            + " where id = ?",
        now(),
        reason,
        now(),
        appealId);
    insertAppealAction(theCase, appealId, theAppeal.appealNo(), "APPEAL_CANCEL", reason, userId);
    return appealRow(appealId);
  }

  /** 申诉相关动作写进动作流水（动作表 = 完整时间线）。 */
  private void insertAppealAction(
      CrmNegativeReviewCaseRow theCase,
      UUID appealId,
      String appealNo,
      String actionType,
      String note,
      UUID userId) {
    sql.execute(
        "insert into crm_negative_review_action"
            + "(id,tenant_id,action_no,case_id,case_no,action_type,transport,outcome,summary,"
            + " appeal_id,appeal_no,operator_id,action_at,created_at,updated_at)"
            + " values (?,?,?,?,?,?,'MANUAL_HANDOFF','SUCCEEDED',?,?,?,?,?::timestamptz,"
            + "?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        nextNo("crm_negative_review_action_no_seq", "NVA"),
        theCase.id(),
        theCase.caseNo(),
        actionType,
        blankToNull(note),
        appealId,
        appealNo,
        userId,
        now(),
        now(),
        now());
  }

  // ================================================================ 解决率统计

  /**
   * 解决率统计（= 蓝图输出句里的「跟踪解决率」）。
   *
   * <p>🔴 <b>分母口径 = 本模块的处理单数</b>（作用域内建单的处理单），
   * <b>分子口径 = 已解决的处理单数</b>（{@code resolution_status='RESOLVED'}）。
   * 两个口径都随响应一起给出，<b>前端不重算</b>。
   */
  public CrmNegativeReviewStats stats(
      UUID shopId,
      String platformCode,
      String causeCode,
      String caseStatus,
      String resolutionStatus,
      Boolean appealEligible,
      Integer rating,
      String keyword,
      int days) {
    int effectiveDays = Math.min(Math.max(days, MIN_STATS_DAYS), MAX_STATS_DAYS);
    OffsetDateTime end = now();
    OffsetDateTime start = end.minusDays(effectiveDays);
    List<Object> binds = new ArrayList<>();
    // 🔴 统计里的 JOIN 查询要把**同一套条件**接到自己的别名上 ⇒ 条件必须能脱离
    //   `where` 关键字单独复用（见 caseConditions）。
    String conditions =
        caseConditions(
            "c",
            shopId,
            platformCode,
            causeCode,
            caseStatus,
            resolutionStatus,
            appealEligible,
            rating,
            keyword,
            binds);
    conditions += " and c.created_at >= ?::timestamptz and c.created_at <= ?::timestamptz";
    binds.add(start);
    binds.add(end);
    String where = " where 1=1" + conditions;

    Record overall =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where c.resolution_status = 'RESOLVED') as resolved,"
                + " count(*) filter (where c.resolution_status <> 'RESOLVED') as unresolved,"
                + " count(*) filter (where c.case_status = 'OPEN') as open_cases,"
                + " count(*) filter (where c.case_status = 'IN_PROGRESS') as in_progress,"
                + " count(*) filter (where c.case_status = 'CLOSED') as closed,"
                + " count(*) filter (where c.appeal_eligible) as eligible,"
                + " count(*) filter (where not c.appeal_eligible) as ineligible"
                + " from crm_negative_review_case c"
                + where,
            binds.toArray());
    long total = overall.get("total", Long.class);
    long resolved = overall.get("resolved", Long.class);
    long unresolved = overall.get("unresolved", Long.class);

    // 已走完「联系买家」的处理单数（顺序闸的达成情况）。
    long contacted =
        sql.fetchOne(
                "select count(distinct c.id) from crm_negative_review_case c"
                    + " join crm_negative_review_action a on a.case_id = c.id"
                    + " where a.action_type = 'CONTACT_BUYER' and a.outcome = 'SUCCEEDED'"
                    + conditions,
                binds.toArray())
            .get(0, Long.class);
    long appealed =
        sql.fetchOne(
                "select count(distinct c.id) from crm_negative_review_case c"
                    + " join crm_negative_review_appeal ap on ap.case_id = c.id"
                    + " where 1=1"
                    + conditions,
                binds.toArray())
            .get(0, Long.class);

    List<CrmNegativeReviewCauseBucket> byCause =
        sql.fetch(
                "select c.cause_code as cause_code,"
                    + " coalesce(max(c.cause_label),c.cause_code,'（未分类）') as label,"
                    + " count(*) as total,"
                    + " count(*) filter (where c.resolution_status = 'RESOLVED') as resolved,"
                    + " count(*) filter (where c.appeal_eligible) as eligible"
                    + " from crm_negative_review_case c"
                    + where
                    + " group by c.cause_code order by count(*) desc",
                binds.toArray())
            .map(
                r ->
                    new CrmNegativeReviewCauseBucket(
                        r.get("cause_code", String.class),
                        r.get("label", String.class),
                        r.get("total", Long.class),
                        r.get("resolved", Long.class),
                        r.get("eligible", Long.class)));

    List<CrmNegativeReviewStatusBucket> byStatus =
        sql.fetch(
                "select c.case_status as status, count(*) as total"
                    + " from crm_negative_review_case c"
                    + where
                    + " group by c.case_status order by c.case_status",
                binds.toArray())
            .map(r -> new CrmNegativeReviewStatusBucket(r.get("status", String.class), r.get("total", Long.class)));

    List<CrmNegativeReviewAppealBucket> byAppealStatus =
        sql.fetch(
                "select ap.appeal_status as status, count(*) as total"
                    + " from crm_negative_review_appeal ap"
                    + " join crm_negative_review_case c on c.id = ap.case_id"
                    + " where 1=1"
                    + conditions
                    + " group by ap.appeal_status order by ap.appeal_status",
                binds.toArray())
            .map(r -> new CrmNegativeReviewAppealBucket(r.get("status", String.class), r.get("total", Long.class)));

    List<CrmNegativeReviewQuotaBucket> byQuota = quotaBuckets(platformCode);

    BigDecimal rate =
        total <= 0
            ? null
            : BigDecimal.valueOf(resolved)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);

    return new CrmNegativeReviewStats(
        effectiveDays,
        start.toString(),
        end.toString(),
        total,
        resolved,
        unresolved,
        overall.get("open_cases", Long.class),
        overall.get("in_progress", Long.class),
        overall.get("closed", Long.class),
        rate,
        "分母 = 本模块的处理单数（作用域 " + effectiveDays + " 天内建单、且命中当前筛选条件的处理单，共 "
            + total + " 单）",
        "分子 = 解决状态为「已解决」（resolution_status=RESOLVED）的处理单数，共 " + resolved + " 单",
        overall.get("eligible", Long.class),
        overall.get("ineligible", Long.class),
        appealed,
        contacted,
        byCause,
        byStatus,
        byAppealStatus,
        byQuota,
        "解决率由**服务端**按上面的口径现算，前端只原样展示；"
            + "作用域会随筛选条件变化 —— 换一个筛选面就是另一个口径，别把两处的数放在一起比。");
  }

  /** 各平台的申诉配额使用情况（当前周期）。 */
  private List<CrmNegativeReviewQuotaBucket> quotaBuckets(String platformFilter) {
    List<Object> binds = new ArrayList<>();
    String where = " where rule_scope = 'PLATFORM' and status = 'ACTIVE'";
    if (!blank(platformFilter)) {
      where += " and platform_code = ?";
      binds.add(platformFilter.trim());
    }
    List<CrmNegativeReviewQuotaBucket> out = new ArrayList<>();
    for (var rule : sql.fetch(ruleColumns() + " from crm_negative_review_rule" + where, binds.toArray())) {
      var row = rule(rule);
      String periodKey = quotaPeriodKey(row.quotaPeriod(), now());
      long used = quotaUsed(row.platformCode(), periodKey);
      Long remaining = row.appealQuotaLimit() <= 0 ? null : Math.max(0, row.appealQuotaLimit() - used);
      out.add(
          new CrmNegativeReviewQuotaBucket(
              row.platformCode(),
              row.quotaPeriod(),
              periodKey,
              row.appealQuotaLimit(),
              used,
              remaining));
    }
    return out;
  }

  // ================================================================ 规则查找

  /** 该平台的策略规则（当前生效的）。 */
  private java.util.Optional<CrmNegativeReviewRuleRow> platformPolicy(String platformCode) {
    if (blank(platformCode)) return java.util.Optional.empty();
    var row =
        sql.fetchOne(
            ruleColumns()
                + " from crm_negative_review_rule where platform_code = ? and rule_scope = 'PLATFORM'"
                + " and status = 'ACTIVE' and effective_from <= now()"
                + " and (effective_to is null or effective_to > now())",
            platformCode);
    return row == null ? java.util.Optional.empty() : java.util.Optional.of(rule(row));
  }

  /** 该（平台 × 原因）的规则（当前生效的）。 */
  private java.util.Optional<CrmNegativeReviewRuleRow> causeRule(String platformCode, String causeCode) {
    if (blank(platformCode) || blank(causeCode)) return java.util.Optional.empty();
    var row =
        sql.fetchOne(
            ruleColumns()
                + " from crm_negative_review_rule where platform_code = ? and cause_code = ?"
                + " and rule_scope = 'CAUSE' and status = 'ACTIVE' and effective_from <= now()"
                + " and (effective_to is null or effective_to > now())",
            platformCode,
            causeCode);
    return row == null ? java.util.Optional.empty() : java.util.Optional.of(rule(row));
  }

  // ================================================================ 配额

  /** 配额周期键：MONTHLY → 2026-10；QUARTERLY → 2026-Q4；YEARLY → 2026；NONE → 'NONE'。 */
  private String quotaPeriodKey(String quotaPeriod, OffsetDateTime at) {
    String period = quotaPeriod == null ? "MONTHLY" : quotaPeriod;
    return switch (period) {
      case "QUARTERLY" ->
          at.getYear() + "-Q" + (((at.getMonthValue() - 1) / 3) + 1);
      case "YEARLY" -> String.valueOf(at.getYear());
      case "NONE" -> "NONE";
      default -> String.format("%04d-%02d", at.getYear(), at.getMonthValue());
    };
  }

  /** 本周期已占用的申诉配额（撤回的不算占用）。 */
  private long quotaUsed(String platformCode, String periodKey) {
    return sql.fetchOne(
            "select count(*) from crm_negative_review_appeal"
                + " where platform_code = ? and quota_period_key = ? and appeal_status <> 'CANCELLED'",
            platformCode,
            periodKey)
        .get(0, Long.class);
  }

  // ================================================================ SQL 片段

  private String caseWhere(
      UUID shopId,
      String platformCode,
      String causeCode,
      String caseStatus,
      String resolutionStatus,
      Boolean appealEligible,
      Integer rating,
      String keyword,
      List<Object> binds) {
    return " where 1=1" + caseConditions("c", shopId, platformCode, causeCode, caseStatus,
        resolutionStatus, appealEligible, rating, keyword, binds);
  }

  /**
   * 处理单的筛选条件（**不带 {@code where} 关键字**）。
   *
   * <p>拆开是为了让 JOIN 的统计查询能把同一套条件接到自己的别名上 ——
   * 一旦条件里带了 {@code where}，接上去就会出现两个 {@code where}，SQL 直接语法错。
   */
  private String caseConditions(
      String alias,
      UUID shopId,
      String platformCode,
      String causeCode,
      String caseStatus,
      String resolutionStatus,
      Boolean appealEligible,
      Integer rating,
      String keyword,
      List<Object> binds) {
    StringBuilder where = new StringBuilder();
    if (shopId != null) {
      where.append(" and " + alias + ".shop_id = ?");
      binds.add(shopId);
    }
    if (!blank(platformCode)) {
      where.append(" and " + alias + ".platform_code = ?");
      binds.add(platformCode.trim());
    }
    if (!blank(causeCode)) {
      where.append(" and " + alias + ".cause_code = ?");
      binds.add(causeCode.trim().toUpperCase());
    }
    if (!blank(caseStatus)) {
      where.append(" and " + alias + ".case_status = ?");
      binds.add(caseStatus.trim().toUpperCase());
    }
    if (!blank(resolutionStatus)) {
      where.append(" and " + alias + ".resolution_status = ?");
      binds.add(resolutionStatus.trim().toUpperCase());
    }
    if (appealEligible != null) {
      where.append(" and " + alias + ".appeal_eligible = ?");
      binds.add(appealEligible);
    }
    if (rating != null) {
      where.append(" and " + alias + ".review_rating = ?");
      binds.add(rating);
    }
    if (!blank(keyword)) {
      where.append(
          " and ("
              + alias
              + ".case_no ilike ? or "
              + alias
              + ".review_no ilike ? or "
              + alias
              + ".review_content ilike ?)");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    return where.toString();
  }

  private static String ruleColumns() {
    return "select id, rule_no, rule_scope, platform_code, cause_code, label, description,"
        + " negative_rating_threshold, appeal_supported, contact_buyer_required,"
        + " appeal_quota_limit, quota_period, appeal_window_days, appeal_eligible,"
        + " appeal_basis_template, evidence_required, priority, version, status, effective_from,"
        + " effective_to, note, created_by, created_at, updated_at";
  }

  private static String caseColumns() {
    return "select c.id, c.case_no, c.review_id, c.review_no, c.review_rating, c.review_title,"
        + " c.review_content, c.review_external_id, c.shop_id, c.shop_name, c.platform_code,"
        + " c.buyer_ref, c.buyer_name, c.sales_order_no, c.policy_rule_id, c.policy_rule_version,"
        + " c.negative_rating_threshold, c.appeal_supported, c.contact_buyer_required,"
        + " c.appeal_quota_limit, c.quota_period, c.appeal_window_days, c.cause_code,"
        + " c.cause_label, c.cause_note, c.cause_rule_id, c.cause_rule_version, c.appeal_eligible,"
        + " c.appeal_basis, c.appeal_assessed_at, c.case_status, c.resolution_status,"
        + " c.resolution_note, c.resolution_at, c.assigned_to, c.note, c.created_by, c.created_at,"
        + " c.updated_at";
  }

  // ================================================================ 行映射

  private static CrmNegativeReviewRuleRow rule(Record r) {
    return new CrmNegativeReviewRuleRow(
        r.get("id", UUID.class),
        r.get("rule_no", String.class),
        r.get("rule_scope", String.class),
        r.get("platform_code", String.class),
        r.get("cause_code", String.class),
        r.get("label", String.class),
        r.get("description", String.class),
        r.get("negative_rating_threshold", Integer.class),
        Boolean.TRUE.equals(r.get("appeal_supported", Boolean.class)),
        Boolean.TRUE.equals(r.get("contact_buyer_required", Boolean.class)),
        r.get("appeal_quota_limit", Integer.class),
        r.get("quota_period", String.class),
        r.get("appeal_window_days", Integer.class),
        Boolean.TRUE.equals(r.get("appeal_eligible", Boolean.class)),
        r.get("appeal_basis_template", String.class),
        Boolean.TRUE.equals(r.get("evidence_required", Boolean.class)),
        r.get("priority", Integer.class),
        r.get("version", Integer.class),
        r.get("status", String.class),
        moment(r.get("effective_from")),
        moment(r.get("effective_to")),
        r.get("note", String.class),
        r.get("created_by", UUID.class),
        moment(r.get("created_at")),
        moment(r.get("updated_at")));
  }

  private static CrmNegativeReviewCaseRow caseOf(Record r) {
    return new CrmNegativeReviewCaseRow(
        r.get("id", UUID.class),
        r.get("case_no", String.class),
        r.get("review_id", UUID.class),
        r.get("review_no", String.class),
        r.get("review_rating", Integer.class),
        r.get("review_title", String.class),
        r.get("review_content", String.class),
        r.get("review_external_id", String.class),
        r.get("shop_id", UUID.class),
        r.get("shop_name", String.class),
        r.get("platform_code", String.class),
        r.get("buyer_ref", String.class),
        r.get("buyer_name", String.class),
        r.get("sales_order_no", String.class),
        r.get("policy_rule_id", UUID.class),
        r.get("policy_rule_version", Integer.class),
        r.get("negative_rating_threshold", Integer.class),
        Boolean.TRUE.equals(r.get("appeal_supported", Boolean.class)),
        Boolean.TRUE.equals(r.get("contact_buyer_required", Boolean.class)),
        r.get("appeal_quota_limit", Integer.class),
        r.get("quota_period", String.class),
        r.get("appeal_window_days", Integer.class),
        r.get("cause_code", String.class),
        r.get("cause_label", String.class),
        r.get("cause_note", String.class),
        r.get("cause_rule_id", UUID.class),
        r.get("cause_rule_version", Integer.class),
        Boolean.TRUE.equals(r.get("appeal_eligible", Boolean.class)),
        r.get("appeal_basis", String.class),
        moment(r.get("appeal_assessed_at")),
        r.get("case_status", String.class),
        r.get("resolution_status", String.class),
        r.get("resolution_note", String.class),
        moment(r.get("resolution_at")),
        r.get("assigned_to", UUID.class),
        r.get("note", String.class),
        r.get("created_by", UUID.class),
        moment(r.get("created_at")),
        moment(r.get("updated_at")));
  }

  private static CrmNegativeReviewCandidateRow candidate(Record r) {
    Integer threshold = r.get("negative_rating_threshold", Integer.class);
    Integer rating = r.get("rating", Integer.class);
    boolean configured = r.get("policy_id", UUID.class) != null;
    return new CrmNegativeReviewCandidateRow(
        r.get("review_id", UUID.class),
        r.get("review_no", String.class),
        rating,
        r.get("title", String.class),
        r.get("content", String.class),
        r.get("shop_id", UUID.class),
        r.get("shop_name", String.class),
        r.get("platform_code", String.class),
        r.get("product_ref", String.class),
        r.get("product_title", String.class),
        r.get("variant_ref", String.class),
        r.get("buyer_ref", String.class),
        r.get("buyer_name", String.class),
        r.get("sales_order_no", String.class),
        moment(r.get("platform_reviewed_at")),
        configured,
        threshold,
        r.get("case_id", UUID.class) != null,
        r.get("case_id", UUID.class),
        r.get("case_no", String.class));
  }

  private static CrmNegativeReviewActionRow action(Record r) {
    return new CrmNegativeReviewActionRow(
        r.get("id", UUID.class),
        r.get("action_no", String.class),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("action_type", String.class),
        r.get("transport", String.class),
        r.get("outcome", String.class),
        r.get("outcome_note", String.class),
        r.get("summary", String.class),
        r.get("detail", String.class),
        r.get("after_sales_reason", String.class),
        r.get("appeal_id", UUID.class),
        r.get("appeal_no", String.class),
        r.get("operator_id", UUID.class),
        r.get("operator_name", String.class),
        moment(r.get("action_at")),
        moment(r.get("created_at")));
  }

  private static CrmNegativeReviewAppealRow appeal(Record r) {
    return new CrmNegativeReviewAppealRow(
        r.get("id", UUID.class),
        r.get("appeal_no", String.class),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("review_id", UUID.class),
        r.get("review_no", String.class),
        r.get("shop_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("appeal_type", String.class),
        r.get("cause_code", String.class),
        r.get("reason", String.class),
        r.get("content", String.class),
        r.get("appeal_status", String.class),
        r.get("transport", String.class),
        moment(r.get("submitted_at")),
        r.get("platform_reference", String.class),
        r.get("platform_receipt", String.class),
        moment(r.get("decided_at")),
        r.get("decided_result", String.class),
        r.get("decided_reason", String.class),
        moment(r.get("cancelled_at")),
        r.get("cancel_reason", String.class),
        r.get("quota_period_key", String.class),
        r.get("quota_index", Integer.class),
        r.get("quota_limit", Integer.class),
        r.get("quota_used_before", Integer.class),
        r.get("created_by", UUID.class),
        moment(r.get("created_at")),
        moment(r.get("updated_at")));
  }

  private CrmNegativeReviewCaseRow caseRow(UUID caseId) {
    var row = sql.fetchOne(caseColumns() + " from crm_negative_review_case c where c.id = ?", caseId);
    if (row == null) {
      throw notFound("差评处理单不存在（或不在当前租户的处理单里）：" + caseId);
    }
    return caseOf(row);
  }

  private CrmNegativeReviewAppealRow appealRow(UUID appealId) {
    var row =
        sql.fetchOne(
            "select id, appeal_no, case_id, case_no, review_id, review_no, shop_id,"
                + " platform_code, appeal_type, cause_code, reason, content, appeal_status,"
                + " transport, submitted_at, platform_reference, platform_receipt, decided_at,"
                + " decided_result, decided_reason, cancelled_at, cancel_reason,"
                + " quota_period_key, quota_index, quota_limit, quota_used_before, created_by,"
                + " created_at, updated_at"
                + " from crm_negative_review_appeal where id = ?",
            appealId);
    if (row == null) {
      throw notFound("申诉任务不存在（或不在当前租户的任务里）：" + appealId);
    }
    return appeal(row);
  }

  private CrmNegativeReviewActionRow action(UUID actionId) {
    var row =
        sql.fetchOne(
            "select id, action_no, case_id, case_no, action_type, transport, outcome,"
                + " outcome_note, summary, detail, after_sales_reason, appeal_id, appeal_no,"
                + " operator_id, operator_name, action_at, created_at"
                + " from crm_negative_review_action where id = ?",
            actionId);
    if (row == null) {
      throw notFound("处理动作不存在（或不在当前租户的流水里）：" + actionId);
    }
    return action(row);
  }

  // ================================================================ 工具

  /** 业务号：前缀-yyyyMMdd-000001（序列单独授权，见 V302 授权段）。 */
  private String nextNo(String sequence, String prefix) {
    var row =
        sql.fetchOne(
            "select nextval('" + sequence + "') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return prefix + "-" + day + "-" + String.format("%06d", seq);
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 读一个 timestamptz 列为带偏移量的 ISO-8601 字符串。 */
  private static String moment(Object value) {
    if (value == null) return null;
    if (value instanceof OffsetDateTime odt) return odt.toString();
    if (value instanceof java.time.Instant instant) return instant.atOffset(ZoneOffset.UTC).toString();
    return String.valueOf(value);
  }

  /**
   * 解析带时区偏移量的时刻。
   *
   * <p>刻意用 {@code OffsetDateTime.parse}：用 {@code Timestamp.valueOf(LocalDateTime)} 会在东八区
   * <b>静默偏 −28800 秒</b>，写进去的时刻与运营填的差 8 小时，而页面看上去一切正常。
   */
  private static OffsetDateTime parseMoment(String value, String label) {
    String text = nz(value).trim();
    if (text.isEmpty()) {
      throw bad("必须指定" + label + "，例如 2026-10-10T08:00:00+08:00");
    }
    try {
      return OffsetDateTime.parse(text);
    } catch (java.time.format.DateTimeParseException e) {
      throw bad(label + "「" + text + "」不是合法的带偏移量时刻（要写成 2026-10-10T08:00:00+08:00）。");
    }
  }

  private static String normalizeQuotaPeriod(String value) {
    String period = blank(value) ? "MONTHLY" : value.trim().toUpperCase();
    if (!QUOTA_PERIODS.contains(period)) {
      throw bad("申诉配额周期只能是 " + String.join(" / ", QUOTA_PERIODS) + "。");
    }
    return period;
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

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
