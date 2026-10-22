package com.starcon.crm;

import static com.starcon.crm.CustomerTagModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.crm.CustomerFactsPort.AftersalesCasePage;
import com.starcon.crm.CustomerFactsPort.AftersalesCaseView;
import com.starcon.crm.CustomerFactsPort.OrderPage;
import com.starcon.crm.CustomerFactsPort.OrderView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.3.2.1 客户标签。
 *
 * <p>蓝图 15 卷 15.3.2.1 原话：
 *
 * <blockquote>
 * 职责：维护客户标签体系（VIP、复购、退货率高、高客单、风险、渠道来源等），支持自动规则打标（按订单行为）与手工打标，用于筛选与营销。
 * 输入：标签定义；自动打标规则；手工操作。
 * 输出：标签记录 + 标签分布统计。
 * 关系：上游 = 9 订单、11 售后；下游 = 15.3.1.1 筛选、14.4 促销定向、15.5.2 关怀邮件。
 * <b>"退货率高"标签要谨慎使用</b>，避免对正常买家产生歧视性动作。
 * </blockquote>
 *
 * <p><b>本类落四条纪律</b>：
 *
 * <ol>
 *   <li><b>判定基于「拉取到的事实」</b>：{@link CustomerFactsPort} 经 trade 的授权 HTTP API
 *       读订单与售后事实，<b>不直连对方库</b>；读不到就<b>显式失败</b>（503），
 *       绝不返回空集合冒充「上游没有数据」。</li>
 *   <li><b>可审计的判定依据快照</b>：每一条自动标签都带 {@code evidence} ——
 *       依据哪几笔订单 / 哪几张售后单算出来的，运营能点开看，也能据此纠正。</li>
 *   <li><b>幂等 + 不覆盖人工</b>：标签记录的唯一键含 {@code origin_key}
 *       （人工恒为 {@code MANUAL}，自动恒为 {@code RULE:<rule_code>}），
 *       所以自动重跑<b>只更新自己产出的那一行</b>；人工已撤销的那一枚，自动<b>不恢复</b>。</li>
 *   <li><b>加粗句「"退货率高"标签要谨慎使用」</b>：
 *       <ul>
 *         <li>样本不足 ⇒ 结论是 {@code INDETERMINATE}（<b>不可判定</b>），不是「是」；</li>
 *         <li>必须<b>显著高于租户基线</b>（基线由同窗真实事实算出，不是写死的数字）；</li>
 *         <li>可争议标签（退货率高 / 风险）<b>必须带原因</b>，可人工复核与纠正；</li>
 *         <li>标签<b>不触发</b>任何对买家的动作（自动屏蔽 / 拒绝 / 降权一律不实现）。</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>跨服务纪律</b>：上游 9 订单 / 11 售后在 <b>trade</b> 服务，本服务<b>一条 SQL 都不碰</b>
 * 对方的库；本库只存客户与标签的逻辑引用 + 判定依据快照，<b>不建跨库 FK</b>。
 * 下游 15.3.1.1 筛选 / 14.4 促销定向 / 15.5.2.1 关怀邮件<b>全部未交付</b> ⇒ 本模块
 * <b>只产出标签事实</b>，不假装已经筛出人群、不代下游执行动作。
 */
@Service
public class CustomerTagService {

  // ================================================================ 常量

  /** 列表页最大条数（与本服务其它模块同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 订单回看天数。
   *
   * <p>售后单<b>不带买家维度</b>，只有 {@code orderId} ⇒ 归因要靠订单。
   * 若只拉窗口内的订单，「窗口内受理、但订单更早」的售后单就<b>归不到人**
   * （分母归零或被人为低估）。多拉一段回看窗口专门用来建归因索引，
   * <b>回看段的订单不进分母</b>（分母只算窗口内的订单）。
   */
  static final int ORDER_LOOKBACK_DAYS = 365;

  /** 候选客户清单的默认统计窗口（天）。 */
  static final int CANDIDATE_WINDOW_DAYS = 365;

  /**
   * 计入「退货率」的售后单类型。
   *
   * <p>⚠️ 换货 / 补发 / 维修**不算退货** —— 它们是「货有问题但没退回来」。
   * 口径写死在这里是为了让「退货率高」这枚标签在每个租户、每一次运行里指的是同一件事。
   */
  static final Set<String> RETURN_CASE_TYPES = Set.of("RETURN_REFUND", "REFUND_ONLY");

  /** 一次运行最多落多少条逐客户判定结论（超出则如实报 truncated）。 */
  static final int MAX_EVALUATIONS_PER_RUN = 5000;

  private final DSLContext sql;
  private final CustomerFactsPort facts;

  public CustomerTagService(DSLContext sql, CustomerFactsPort facts) {
    this.sql = sql;
    this.facts = facts;
  }

  /** 判定依据快照的 JSON mapper：BigDecimal 按原样写出（不转成浮点）。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
          // 🔴 读回来也必须是 BigDecimal：默认会把 JSON 数字读成 double，
          // 那一行金额就会掉精度（禁浮点纪律的另一半）。
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 四个维度刻意是 {@code available=false}：
   *
   * <ul>
   *   <li>{@code customer_segmentation} —— 15.3.1.1 客户筛选<b>未交付</b>。
   *       本模块<b>不提供</b>「按标签筛出人群」的端点：把那件事假装成已交付，
   *       会让运营拿着一份并不存在的人群去发促销。</li>
   *   <li>{@code promotion_targeting} —— 14.4 促销定向<b>未交付</b>。</li>
   *   <li>{@code care_email} —— 15.5.2.1 关怀邮件<b>未交付</b>。</li>
   *   <li>{@code cross_store_identity} —— 15.3.1.3 跨店合并<b>未交付</b>：
   *       本模块的 customer_ref 是单店口径的逻辑引用。</li>
   * </ul>
   */
  public CrmCustomerTagCapabilities capabilities() {
    List<CrmCustomerTagCapabilityDimension> dimensions =
        List.of(
            new CrmCustomerTagCapabilityDimension(
                "tag_definition",
                "标签定义（VIP / 复购 / 退货率高 / 高客单 / 风险 / 渠道来源 …）",
                true,
                "本服务 V295 crm_customer_tag",
                "",
                "标签是**配置**：建了它才谈得上「谁身上有这枚标签」"),
            new CrmCustomerTagCapabilityDimension(
                "manual_tagging",
                "手工打标（运营给某位客户加一枚标签）",
                true,
                "本服务 V295 crm_customer_tag_assignment（source=MANUAL）",
                "",
                "人工标签与自动标签**分开记账**：自动重跑只动自己产出的那部分，"
                    + "**绝不覆盖人工标的标签**"),
            new CrmCustomerTagCapabilityDimension(
                "rule_tagging",
                "自动规则打标（按订单行为）",
                true,
                "上游 trade 的订单 / 售后事实，经授权 HTTP API 读（GET /trade/sales-orders/list、"
                    + "GET /trade/aftersales/cases）",
                "9 / 11",
                "判定基于**拉取到的事实**，每条标签都带判定依据快照（依据哪几笔订单 / 哪几张售后单）；"
                    + "上游读不到一律 503，**绝不**返回空集合冒充「没有客户」"),
            new CrmCustomerTagCapabilityDimension(
                "distribution_statistics",
                "标签分布统计（每枚标签多少客户、占比）",
                true,
                "本服务 V295 crm_customer_tag_assignment 的真实聚合",
                "",
                "占比在**服务端**算好（分母是「被打过任意标签的客户数」）；分母为 0 时报「不计算」而不是 0%"),
            new CrmCustomerTagCapabilityDimension(
                "contestable_tag_review",
                "可争议标签（退货率高 / 风险）的人工复核与纠正",
                true,
                "本服务 V295 crm_customer_tag_assignment（reason + pending_review + 复核端点）",
                "",
                "🔴 「退货率高」这类标签**必须带原因**（DB 触发器强制），"
                    + "且可被人工确认 / 撤销；自动重跑**不会恢复**人工已撤销的那一枚"),
            new CrmCustomerTagCapabilityDimension(
                "customer_segmentation",
                "按标签筛出目标人群（客户筛选）",
                false,
                "**15.3.1.1 客户筛选未交付**",
                "15.3.1.1",
                "本模块**不提供**「按标签筛出人群」的端点 —— 筛人群是 15.3.1.1 的职责，"
                    + "在这里假装筛好会让人拿着一份并不存在的人群去发促销"),
            new CrmCustomerTagCapabilityDimension(
                "promotion_targeting",
                "按标签做促销定向",
                false,
                "**14.4 促销定向未交付**",
                "14.4",
                "本模块**不执行**任何动作：标签只是一个可读的事实，"
                    + "谁拿它去定向促销由下游模块自己实现并承担那个责任"),
            new CrmCustomerTagCapabilityDimension(
                "care_email",
                "按标签发关怀邮件",
                false,
                "**15.5.2.1 关怀与催办未交付**",
                "15.5.2.1",
                "本模块**不发送**任何邮件，也不登记「待发关怀」意图 —— 那是 15.5.2.1 的写入"),
            new CrmCustomerTagCapabilityDimension(
                "cross_store_identity",
                "跨店合并后的逻辑客户",
                false,
                "**15.3.1.3 跨店合并未交付**；本仓也**没有买家档案表**",
                "15.3.1.3",
                "本模块的 customer_ref 是**单店口径**的逻辑引用（上游 customer_party_id 指向 "
                    + "catalog 03 客户档案的逻辑 id），合并由 15.3.1.3 负责"));
    return new CrmCustomerTagCapabilities(
        dimensions,
        java.util.Arrays.stream(CustomerTagCategory.values()).map(Enum::name).toList(),
        java.util.Arrays.stream(CustomerTagMetric.values()).map(Enum::name).toList(),
        java.util.Arrays.stream(CustomerTagSource.values()).map(Enum::name).toList(),
        java.util.Arrays.stream(CustomerTagAssignmentState.values()).map(Enum::name).toList(),
        java.util.Arrays.stream(CrmCustomerTagEvaluationOutcome.values()).map(Enum::name).toList(),
        "判定口径：分母 = 统计窗口内该客户的订单数；分子 = 窗口内受理、且能归到该客户的售后单数。"
            + "退货率只计 "
            + String.join(" / ", RETURN_CASE_TYPES.stream().sorted().toList())
            + " 两类（换货 / 补发 / 维修不算退货）。"
            + "🔴 订单数低于 min_sample ⇒ 结论是**不可判定**（不是「是」）；"
            + "退货率必须**显著高于租户基线**（基线 = 同窗本租户售后单数 ÷ 订单数，倍率由 baseline_multiplier 定）。",
        "🔴 标签**不触发**任何对买家的动作：不自动屏蔽、不自动拒绝、不自动降权、不自动改价。"
            + "标签只是一个**可读的事实**；谁拿它去筛人群、去发促销、去发关怀邮件，由下游模块自己实现。",
        "上游 9 订单 / 11 售后在 trade 服务（不同服务、不同库）⇒ 本服务**只经授权 HTTP API 读**，"
            + "一条跨库 SQL / 跨库 FK / 跨库视图都没有；本库只存逻辑引用 + 判定依据快照。",
        "「退货率高」这类标签**要谨慎使用**：一个人只下一单、退了一次，退货率是 100% —— "
            + "那不是「退货率高的买家」，那是**样本不足**。所以它带样本量门槛、带租户基线、"
            + "带原因与证据，并且可以被人工复核与纠正。");
  }

  // ================================================================ 标签定义

  /** 标签定义列表（真分页 + 真筛选）。 */
  public CrmCustomerTagPage tagPage(
      String category, String status, String keyword, int page, int size) {
    List<Object> binds = new ArrayList<>();
    String where = tagWhere(category, status, keyword, binds);
    long total =
        sql.fetchOne("select count(*) from crm_customer_tag t" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmCustomerTagRow> items =
        sql.fetch(
                tagColumns() + where
                    + " order by t.category, t.tag_code limit ? offset ?",
                pageBinds.toArray())
            .map(CustomerTagService::tag);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCustomerTagPage(items, safePage, safeSize, total, totalPages);
  }

  /** 单枚标签定义。 */
  public CrmCustomerTagRow tag(UUID tagId) {
    var row =
        sql.fetchOne(tagColumns() + " where t.tenant_id = ? and t.id = ?",
            currentTenant(), tagId);
    if (row == null) throw notFound("标签不存在：" + tagId);
    return tag(row);
  }

  /** 新建标签定义。 */
  @Transactional
  public CrmCustomerTagRow createTag(CrmCustomerTagInput input, UUID actorId) {
    String tagCode = nz(input.tagCode()).trim();
    requireUniqueTagCode(tagCode, null);
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_customer_tag"
            + "(id,tenant_id,tag_code,name,category,description,color,status,contestable,"
            + " review_required,created_by,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        tagCode,
        nz(input.name()).trim(),
        category(input.category()).name(),
        nz(input.description()).trim(),
        nz(input.color()).trim(),
        tagStatus(input.status()),
        Boolean.TRUE.equals(input.contestable()),
        Boolean.TRUE.equals(input.reviewRequired()),
        actorId,
        moment,
        moment);
    return tag(id);
  }

  /** 更新标签定义（标签编码建后不可改）。 */
  @Transactional
  public CrmCustomerTagRow updateTag(UUID tagId, CrmCustomerTagInput input, UUID actorId) {
    var row =
        sql.fetchOne(
            "select id,tenant_id,tag_code from crm_customer_tag where tenant_id = ? and id = ?",
            currentTenant(), tagId);
    if (row == null) throw notFound("标签不存在：" + tagId);
    String currentCode = row.get("tag_code", String.class);
    String requested = nz(input.tagCode()).trim();
    if (!requested.isEmpty() && !requested.equals(currentCode)) {
      throw bad(
          "标签编码建后不可改（现在是 "
              + currentCode
              + "）：改它会让历史标签记录上的编码指错标签。要换一枚标签请新建。");
    }
    sql.execute(
        "update crm_customer_tag set name = ?, category = ?, description = ?, color = ?,"
            + " status = ?, contestable = ?, review_required = ?, updated_at = ?::timestamptz"
            + " where tenant_id = ? and id = ?",
        nz(input.name()).trim(),
        category(input.category()).name(),
        nz(input.description()).trim(),
        nz(input.color()).trim(),
        tagStatus(input.status()),
        Boolean.TRUE.equals(input.contestable()),
        Boolean.TRUE.equals(input.reviewRequired()),
        now(),
        currentTenant(),
        tagId);
    return tag(tagId);
  }

  /** 删除标签定义（被规则或标签记录引用的删不掉，DB 触发器会拒）。 */
  @Transactional
  public void deleteTag(UUID tagId) {
    var row =
        sql.fetchOne(
            "select id,tag_code from crm_customer_tag where tenant_id = ? and id = ?",
            currentTenant(), tagId);
    if (row == null) throw notFound("标签不存在：" + tagId);
    sql.execute("delete from crm_customer_tag where tenant_id = ? and id = ?", currentTenant(), tagId);
  }

  // ================================================================ 自动打标规则

  /** 规则列表（真分页 + 真筛选）。 */
  public CrmCustomerTagRulePage rulePage(
      UUID tagId, String metric, String status, String keyword, int page, int size) {
    List<Object> binds = new ArrayList<>();
    String where = ruleWhere(tagId, metric, status, keyword, binds);
    long total =
        sql.fetchOne("select count(*) from crm_customer_tag_rule r" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmCustomerTagRuleRow> items =
        sql.fetch(
                ruleColumns() + where
                    + " order by r.rule_code limit ? offset ?",
                pageBinds.toArray())
            .map(CustomerTagService::rule);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCustomerTagRulePage(items, safePage, safeSize, total, totalPages);
  }

  /** 单条规则（含最近一次运行结论）。 */
  public CrmCustomerTagRuleRow rule(UUID ruleId) {
    var row =
        sql.fetchOne(
            ruleColumns() + " where r.tenant_id = ? and r.id = ?",
            currentTenant(), ruleId);
    if (row == null) throw notFound("规则不存在：" + ruleId);
    return rule(row);
  }

  /** 新建规则。 */
  @Transactional
  public CrmCustomerTagRuleRow createRule(CrmCustomerTagRuleInput input, UUID actorId) {
    String ruleCode = nz(input.ruleCode()).trim();
    requireUniqueRuleCode(ruleCode, null);
    CustomerTagMetric metric = metric(input.metric());
    // 口径与阈值的一致性在**服务层先说清**，DB CHECK 再兜一道。
    // 两边都拒，错误信息才是人话（DB 抛的裸 CHECK 名对运营没有意义）。
    requireMetricArguments(metric, input);
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_customer_tag_rule"
            + "(id,tenant_id,rule_code,name,tag_id,metric,threshold,currency,condition_value,"
            + " min_sample,window_days,baseline_multiplier,status,note,created_by,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        ruleCode,
        nz(input.name()).trim(),
        input.tagId(),
        metric.name(),
        input.threshold(),
        blankToNull(input.currency()),
        blankToNull(input.conditionValue()),
        input.minSample() == null ? new BigDecimal("5") : input.minSample(),
        input.windowDays() == null ? new BigDecimal("365") : input.windowDays(),
        input.baselineMultiplier() == null ? new BigDecimal("2") : input.baselineMultiplier(),
        ruleStatus(input.status()),
        nz(input.note()).trim(),
        actorId,
        moment,
        moment);
    return rule(id);
  }

  /** 更新规则（规则编码建后不可改 —— 它是 origin_key 的一部分）。 */
  @Transactional
  public CrmCustomerTagRuleRow updateRule(UUID ruleId, CrmCustomerTagRuleInput input, UUID actorId) {
    var row =
        sql.fetchOne(
            "select id,tenant_id,rule_code from crm_customer_tag_rule where tenant_id = ? and id = ?",
            currentTenant(), ruleId);
    if (row == null) throw notFound("规则不存在：" + ruleId);
    String currentCode = row.get("rule_code", String.class);
    String requested = nz(input.ruleCode()).trim();
    if (!requested.isEmpty() && !requested.equals(currentCode)) {
      throw bad(
          "规则编码建后不可改（现在是 "
              + currentCode
              + "）：改它会让「这条自动标签是哪条规则产出的」在历史上出现两个答案。要换一条规则请新建。");
    }
    CustomerTagMetric metric = metric(input.metric());
    requireMetricArguments(metric, input);
    sql.execute(
        "update crm_customer_tag_rule set name = ?, tag_id = ?, metric = ?, threshold = ?,"
            + " currency = ?, condition_value = ?, min_sample = ?, window_days = ?,"
            + " baseline_multiplier = ?, status = ?, note = ?, updated_at = ?::timestamptz"
            + " where tenant_id = ? and id = ?",
        nz(input.name()).trim(),
        input.tagId(),
        metric.name(),
        input.threshold(),
        blankToNull(input.currency()),
        blankToNull(input.conditionValue()),
        input.minSample() == null ? new BigDecimal("5") : input.minSample(),
        input.windowDays() == null ? new BigDecimal("365") : input.windowDays(),
        input.baselineMultiplier() == null ? new BigDecimal("2") : input.baselineMultiplier(),
        ruleStatus(input.status()),
        nz(input.note()).trim(),
        now(),
        currentTenant(),
        ruleId);
    return rule(ruleId);
  }

  /** 删除规则（已有标签记录或运行记录的删不掉，DB 触发器会拒）。 */
  @Transactional
  public void deleteRule(UUID ruleId) {
    var row =
        sql.fetchOne(
            "select id,rule_code from crm_customer_tag_rule where tenant_id = ? and id = ?",
            currentTenant(), ruleId);
    if (row == null) throw notFound("规则不存在：" + ruleId);
    sql.execute("delete from crm_customer_tag_rule where tenant_id = ? and id = ?", currentTenant(), ruleId);
  }

  // ================================================================ 跑规则

  /**
   * 跑一次规则（= 蓝图「自动规则打标（按订单行为）」）。
   *
   * <p>🔴 三条纪律在这一段落地：
   *
   * <ol>
   *   <li>判定基于**拉取到的事实**（{@link CustomerFactsPort}）；上游读不到 ⇒ 503，
   *       <b>不落任何标签</b>，只把失败原因写进运行记录。</li>
   *   <li>幂等：同一批事实重跑命中同一行 ⇒ {@code unchanged_count} 涨、
   *       {@code tagged_count} 不涨；<b>人工标的标签一律不动</b>。</li>
   *   <li>样本不足 ⇒ {@code INDETERMINATE}（不可判定），<b>不是</b>「是」。</li>
   * </ol>
   */
  @Transactional
  public CrmCustomerTagRunRow executeRule(
      UUID ruleId, CrmCustomerTagRunInput input, UUID actorId, String bearer) {
    var row =
        sql.fetchOne(
            "select id,tenant_id,rule_code,tag_id,metric,threshold,currency,condition_value,"
                + " min_sample,window_days,baseline_multiplier,status"
                + " from crm_customer_tag_rule where tenant_id = ? and id = ?",
            currentTenant(),
            ruleId);
    if (row == null) throw notFound("规则不存在：" + ruleId);

    String ruleCode = row.get("rule_code", String.class);
    UUID tagId = row.get("tag_id", UUID.class);
    CustomerTagMetric metric = CustomerTagMetric.valueOf(row.get("metric", String.class));
    BigDecimal threshold = row.get("threshold", BigDecimal.class);
    String currency = row.get("currency", String.class);
    String conditionValue = row.get("condition_value", String.class);
    BigDecimal minSample = row.get("min_sample", BigDecimal.class);
    BigDecimal windowDays = row.get("window_days", BigDecimal.class);
    BigDecimal baselineMultiplier = row.get("baseline_multiplier", BigDecimal.class);
    String ruleStatus = row.get("status", String.class);

    OffsetDateTime startedAt = now();
    OffsetDateTime windowTo = startedAt;
    OffsetDateTime windowFrom = startedAt.minusDays(windowDays.longValue());
    String fromText = blankToNull(input == null ? null : input.windowFrom());
    String toText = blankToNull(input == null ? null : input.windowTo());
    if ((fromText == null) != (toText == null)) {
      throw bad("统计窗口的起止必须**成对**给：只给一个会让窗口的两端对不上。");
    }
    if (fromText != null) {
      windowFrom = parseMoment(fromText, "窗口起点");
      windowTo = parseMoment(toText, "窗口终点");
      if (!windowFrom.isBefore(windowTo)) throw bad("窗口起点必须早于终点。");
    }

    if (!"ACTIVE".equals(ruleStatus)) {
      throw conflict("规则 " + ruleCode + " 已停用，停用的规则不参与打标。");
    }

    // 标签的可争议 / 必须复核开关 —— 决定新标签是否进入待复核。
    var tagRow =
        sql.fetchOne(
            "select tag_code, name, contestable, review_required from crm_customer_tag"
                + " where tenant_id = ? and id = ?",
            currentTenant(),
            tagId);
    if (tagRow == null) throw notFound("规则引用的标签不存在：" + tagId);
    boolean reviewRequired = Boolean.TRUE.equals(tagRow.get("review_required", Boolean.class));

    FactScan scan;
    try {
      scan = scanFacts(windowFrom, windowTo, bearer);
    } catch (ResponseStatusException e) {
      // 上游读不到：把失败**如实记进运行记录**，一条标签都不打。
      String reason = e.getReason() == null ? "上游事实读不到" : e.getReason();
      return recordFailedRun(ruleId, ruleCode, windowFrom, windowTo, reason, actorId, startedAt);
    }

    BigDecimal baseline = scan.baseline();
    int tagged = 0;
    int unchanged = 0;
    int revoked = 0;
    int indeterminate = 0;
    int revokedSkipped = 0;
    int evaluated = 0;
    boolean truncated = scan.truncated() || scan.customers().size() > MAX_EVALUATIONS_PER_RUN;
    // 运行主键**先**建出来：逐客户判定结论要挂它。
    UUID runId = UUID.randomUUID();
    // ⚠️ 判定结论**先攒着、运行记录落库之后再写**：
    //   DB 触发器要求运行记录已经存在（判定结论必须挂在一个真的运行上），
    //   而运行记录里的计数要等判定跑完才知道 —— 顺序反了会被触发器拒。
    List<PendingEvaluation> pending = new ArrayList<>();

    for (CustomerFacts.Aggregate aggregate : scan.customers().values()) {
      if (evaluated >= MAX_EVALUATIONS_PER_RUN) {
        truncated = true;
        break;
      }
      evaluated++;
      Decision decision =
          decide(
              metric,
              aggregate,
              threshold,
              currency,
              conditionValue,
              minSample,
              baselineMultiplier,
              baseline);
      Map<String, Object> evidence = decision.evidence();
      CrmCustomerTagEvaluationOutcome outcome;
      if (decision.kind() == DecisionKind.INDETERMINATE) {
        outcome = CrmCustomerTagEvaluationOutcome.INDETERMINATE;
        indeterminate++;
      } else if (decision.kind() == DecisionKind.MATCH) {
        // 自动标签只动 origin_key='RULE:<rule_code>' 那一类 —— 人工标签**不受影响**。
        outcome =
            applyRuleTag(
                tagId,
                ruleId,
                ruleCode,
                aggregate.customerRef(),
                aggregate.customerName(),
                decision.reason(),
                evidence,
                reviewRequired,
                actorId);
        if (outcome == CrmCustomerTagEvaluationOutcome.TAGGED) {
          tagged++;
        } else if (outcome == CrmCustomerTagEvaluationOutcome.ALREADY_TAGGED) {
          unchanged++;
        } else if (outcome == CrmCustomerTagEvaluationOutcome.SKIPPED_REVOKED) {
          revokedSkipped++;
        }
      } else {
        outcome =
            revokeRuleTagIfAny(
                ruleId, ruleCode, aggregate.customerRef(), decision.reason(), actorId);
        if (outcome == CrmCustomerTagEvaluationOutcome.REVOKED) revoked++;
      }
      pending.add(new PendingEvaluation(aggregate, outcome, decision.reason(), evidence));
    }

    Map<String, Object> runFacts = new LinkedHashMap<>();
    runFacts.put("metric", metric.name());
    runFacts.put("windowFrom", windowFrom.toString());
    runFacts.put("windowTo", windowTo.toString());
    runFacts.put("scannedOrderCount", scan.scannedOrders());
    runFacts.put("scannedCaseCount", scan.scannedCases());
    runFacts.put("windowOrderCount", scan.windowOrderCount());
    runFacts.put("unattributedCaseCount", scan.unattributedCases());
    runFacts.put("customerCount", scan.customers().size());
    runFacts.put("evaluatedCount", evaluated);
    runFacts.put("countedCaseTypes", new ArrayList<>(RETURN_CASE_TYPES.stream().sorted().toList()));
    runFacts.put("orderLookbackDays", ORDER_LOOKBACK_DAYS);
    if (baseline != null) runFacts.put("baseline", baseline);

    sql.execute(
        "insert into crm_customer_tag_run"
            + "(id,tenant_id,run_no,rule_id,rule_code,status,window_from,window_to,customer_count,"
            + " tagged_count,unchanged_count,revoked_count,indeterminate_count,revoked_skipped_count,"
            + " truncated,baseline,facts,failure_reason,operator_id,started_at,finished_at,created_at)"
            + " values (?,?,?,?,?,'SUCCEEDED',?::timestamptz,?::timestamptz,?,?,?,?,?,?,?,?,?::jsonb,"
            + " '',?,?::timestamptz,?::timestamptz,?::timestamptz)",
        runId,
        currentTenant(),
        nextRunNo(),
        ruleId,
        ruleCode,
        windowFrom,
        windowTo,
        scan.customers().size(),
        tagged,
        unchanged,
        revoked,
        indeterminate,
        revokedSkipped,
        truncated,
        baseline,
        toJson(runFacts),
        actorId,
        startedAt,
        now(),
        now());
    for (PendingEvaluation evaluation : pending) {
      writeEvaluation(runId, ruleId, evaluation.aggregate(), evaluation.outcome(),
          evaluation.reason(), evaluation.evidence());
    }
    return run(runId);
  }

  /**
   * 上游读不到时记一条 FAILED 运行记录。
   *
   * <p>⚠️ **一条标签都不打** —— 半拉半打出来的标签集是最坏的结果：
   * 它看起来是「跑过了」，实际是「只扫到了一半」。
   */
  private CrmCustomerTagRunRow recordFailedRun(
      UUID ruleId, String ruleCode, OffsetDateTime windowFrom, OffsetDateTime windowTo,
      String reason, UUID actorId, OffsetDateTime startedAt) {
    UUID runId = UUID.randomUUID();
    sql.execute(
        "insert into crm_customer_tag_run"
            + "(id,tenant_id,run_no,rule_id,rule_code,status,window_from,window_to,customer_count,"
            + " tagged_count,unchanged_count,revoked_count,indeterminate_count,revoked_skipped_count,"
            + " truncated,baseline,facts,failure_reason,operator_id,started_at,finished_at,created_at)"
            + " values (?,?,?,?,?,'FAILED',?::timestamptz,?::timestamptz,0,0,0,0,0,0,false,null,"
            + " '{}'::jsonb,?,?,?::timestamptz,?::timestamptz,?::timestamptz)",
        runId,
        currentTenant(),
        nextRunNo(),
        ruleId,
        ruleCode,
        windowFrom,
        windowTo,
        reason,
        actorId,
        startedAt,
        now(),
        now());
    return run(runId);
  }

  /**
   * 逐客户判定。
   *
   * <p>🔴 这里是加粗句「"退货率高"标签要谨慎使用」的**判定核心**：
   * 样本不足 ⇒ {@code INDETERMINATE}；退货率必须<b>显著高于租户基线</b>。
   */
  private Decision decide(
      CustomerTagMetric metric,
      CustomerFacts.Aggregate aggregate,
      BigDecimal threshold,
      String currency,
      String conditionValue,
      BigDecimal minSample,
      BigDecimal baselineMultiplier,
      BigDecimal baseline) {

    Map<String, Object> evidence = aggregate.evidence(metric);
    int orderCount = aggregate.windowOrders().size();
    BigDecimal sample = BigDecimal.valueOf(orderCount);

    if (sample.compareTo(minSample) < 0) {
      String reason =
          "不可判定：统计窗口内只有 "
              + orderCount
              + " 单，低于最低样本量 "
              + minSample.stripTrailingZeros().toPlainString()
              + " 单。样本不足时不能说「是」。";
      evidence.put("indeterminateReason", reason);
      return new Decision(DecisionKind.INDETERMINATE, reason, evidence);
    }

    boolean matched;
    String reason;
    switch (metric) {
      case ORDER_COUNT, REPEAT_PURCHASE -> {
        matched = threshold != null && sample.compareTo(threshold) >= 0;
        reason =
            matched
                ? "统计窗口内 " + orderCount + " 单，达到阈值 " + plain(threshold) + " 单。"
                : "统计窗口内 " + orderCount + " 单，未达到阈值 " + plain(threshold) + " 单。";
      }
      case HIGH_VALUE -> {
        BigDecimal total = aggregate.amountOf(currency);
        matched = threshold != null && total != null && total.compareTo(threshold) >= 0;
        reason =
            (total == null
                    ? "统计窗口内没有 " + currency + " 币种的订单。"
                    : "统计窗口内 " + currency + " 累计消费 " + plain(total) + "，阈值 " + plain(threshold) + "。")
                + (matched ? " 达到阈值。" : " 未达到阈值。");
        evidence.put("measuredAmount", total);
        evidence.put("measuredCurrency", currency);
      }
      case RETURN_RATE -> {
        if (baseline == null) {
          String indeterminate =
              "不可判定：租户基线算不出来（统计窗口内没有可比订单），"
                  + "没有基线就没有「显著高于」可言。";
          evidence.put("indeterminateReason", indeterminate);
          return new Decision(DecisionKind.INDETERMINATE, indeterminate, evidence);
        }
        BigDecimal rate = aggregate.returnRate();
        BigDecimal bar = baseline.multiply(baselineMultiplier);
        matched = rate.compareTo(bar) >= 0;
        reason =
            "统计窗口内退货率 "
                + plain(rate)
                + "（"
                + aggregate.returnCases()
                + " / "
                + orderCount
                + " 单），租户基线 "
                + plain(baseline)
                + " × "
                + plain(baselineMultiplier)
                + " = "
                + plain(bar)
                + "，"
                + (matched ? "达到「显著高于基线」。" : "未达到「显著高于基线」。");
        evidence.put("returnRate", rate);
        evidence.put("baseline", baseline);
        evidence.put("baselineThreshold", bar);
        evidence.put("baselineMultiplier", baselineMultiplier);
      }
      case CHANNEL_SOURCE -> {
        matched =
            conditionValue != null
                && aggregate.channelSources().stream()
                    .anyMatch(s -> s.equalsIgnoreCase(conditionValue));
        reason =
            "统计窗口内的渠道来源 "
                + String.join(" / ", aggregate.channelSources())
                + (matched ? "，包含 " + conditionValue + "。" : "，不含 " + conditionValue + "。");
      }
      default -> {
        matched = false;
        reason = "未知的判定口径。";
      }
    }

    return new Decision(matched ? DecisionKind.MATCH : DecisionKind.NO_MATCH, reason, evidence);
  }

  /**
   * 把自动标签落到客户身上（**只动本规则产出的那一条**）。
   *
   * <p>幂等：已存在且生效 ⇒ <b>更新依据快照</b>（不新增行），结论是 ALREADY_TAGGED；
   * 已存在但被人工撤销 ⇒ <b>不恢复</b>（人工复核的结论优先于自动规则），
   * 结论是 SKIPPED_REVOKED。
   *
   * @return 这次落标后的结论
   */
  private CrmCustomerTagEvaluationOutcome applyRuleTag(
      UUID tagId,
      UUID ruleId,
      String ruleCode,
      String customerRef,
      String customerName,
      String reason,
      Map<String, Object> evidence,
      boolean reviewRequired,
      UUID actorId) {
    String originKey = "RULE:" + ruleCode;
    var existing =
        sql.fetchOne(
            "select id, state from crm_customer_tag_assignment"
                + " where tenant_id = ? and customer_ref = ? and tag_id = ? and origin_key = ?",
            currentTenant(),
            customerRef,
            tagId,
            originKey);
    if (existing != null) {
      if ("REVOKED".equals(existing.get("state", String.class))) {
        // 人工已撤销 ⇒ **不恢复**。人工复核的结论优先于自动规则。
        return CrmCustomerTagEvaluationOutcome.SKIPPED_REVOKED;
      }
      sql.execute(
          "update crm_customer_tag_assignment set customer_name = ?, reason = ?, evidence = ?::jsonb,"
              + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
          customerName,
          reason,
          toJson(evidence),
          now(),
          currentTenant(),
          existing.get("id", UUID.class));
      return CrmCustomerTagEvaluationOutcome.ALREADY_TAGGED;
    }
    try {
      sql.execute(
          "insert into crm_customer_tag_assignment"
              + "(id,tenant_id,customer_ref,customer_name,tag_id,source,origin_key,rule_id,state,"
              + " reason,evidence,pending_review,created_by,created_at,updated_at)"
              + " values (?,?,?,?,?,'RULE',?,?,'ACTIVE',?,?::jsonb,?,?,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          customerRef,
          customerName,
          tagId,
          originKey,
          ruleId,
          reason,
          toJson(evidence),
          reviewRequired,
          actorId,
          now(),
          now());
      return CrmCustomerTagEvaluationOutcome.TAGGED;
    } catch (DataIntegrityViolationException e) {
      // 并发跑同一条规则：唯一键挡住了第二行 —— 那说明**已经有**了，按幂等处理。
      sql.execute(
          "update crm_customer_tag_assignment set reason = ?, evidence = ?::jsonb,"
              + " updated_at = ?::timestamptz where tenant_id = ? and customer_ref = ? and tag_id = ?"
              + " and origin_key = ? and state = 'ACTIVE'",
          reason,
          toJson(evidence),
          now(),
          currentTenant(),
          customerRef,
          tagId,
          originKey);
      return CrmCustomerTagEvaluationOutcome.ALREADY_TAGGED;
    }
  }

  /**
   * 不再命中时撤销本规则产出的那枚自动标签（**不碰人工标签**）。
   *
   * @return REVOKED（确实撤了）或 NOT_MATCHED（本来就没有）
   */
  private CrmCustomerTagEvaluationOutcome revokeRuleTagIfAny(
      UUID ruleId, String ruleCode, String customerRef, String reason, UUID actorId) {
    int affected =
        sql.execute(
            "update crm_customer_tag_assignment set state = 'REVOKED', reason = ?,"
                + " decided_by = ?, decided_at = ?::timestamptz, updated_at = ?::timestamptz"
                + " where tenant_id = ? and customer_ref = ? and rule_id = ? and origin_key = ?"
                + " and state = 'ACTIVE'",
            reason,
            actorId,
            now(),
            now(),
            currentTenant(),
            customerRef,
            ruleId,
            "RULE:" + ruleCode);
    return affected > 0
        ? CrmCustomerTagEvaluationOutcome.REVOKED
        : CrmCustomerTagEvaluationOutcome.NOT_MATCHED;
  }

  /** 落一条逐客户判定结论。 */
  private void writeEvaluation(
      UUID runId,
      UUID ruleId,
      CustomerFacts.Aggregate aggregate,
      CrmCustomerTagEvaluationOutcome outcome,
      String reason,
      Map<String, Object> evidence) {
    sql.execute(
        "insert into crm_customer_tag_evaluation"
            + "(id,tenant_id,run_id,rule_id,customer_ref,customer_name,outcome,reason,evidence,created_at)"
            + " values (?,?,?,?,?,?,?,?,?::jsonb,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        runId,
        ruleId,
        aggregate.customerRef(),
        aggregate.customerName(),
        outcome.name(),
        reason,
        toJson(evidence),
        now());
  }

  // ================================================================ 标签记录

  /** 标签记录列表（真分页 + 真筛选）。 */
  public CrmCustomerTagAssignmentPage assignmentPage(
      UUID tagId, String customerRef, String source, String state, String keyword, int page, int size) {
    List<Object> binds = new ArrayList<>();
    String where = assignmentWhere(tagId, customerRef, source, state, keyword, binds);
    long total =
        sql.fetchOne(
                "select count(*) from crm_customer_tag_assignment a" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmCustomerTagAssignmentRow> items =
        sql.fetch(
                assignmentColumns() + where
                    + " order by a.created_at desc, a.id desc limit ? offset ?",
                pageBinds.toArray())
            .map(CustomerTagService::assignment);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCustomerTagAssignmentPage(items, safePage, safeSize, total, totalPages);
  }

  /** 单条标签记录。 */
  public CrmCustomerTagAssignmentRow assignment(UUID assignmentId) {
    var row =
        sql.fetchOne(
            assignmentColumns() + " where a.tenant_id = ? and a.id = ?",
            currentTenant(),
            assignmentId);
    if (row == null) throw notFound("标签记录不存在：" + assignmentId);
    return assignment(row);
  }

  /**
   * 手工打标。
   *
   * <p>🔴 可争议标签（退货率高 / 风险）**必须带原因** —— 否则 DB 触发器会拒，
   * 但服务层先说清人话错误。
   */
  @Transactional
  public CrmCustomerTagAssignmentRow manualTag(CrmCustomerTagAssignmentInput input, UUID actorId) {
    var tagRow =
        sql.fetchOne(
            "select tag_code, name, contestable, review_required from crm_customer_tag"
                + " where tenant_id = ? and id = ?",
            currentTenant(),
            input.tagId());
    if (tagRow == null) throw notFound("标签不存在：" + input.tagId());
    boolean contestable = Boolean.TRUE.equals(tagRow.get("contestable", Boolean.class));
    boolean reviewRequired = Boolean.TRUE.equals(tagRow.get("review_required", Boolean.class));
    String reason = nz(input.reason()).trim();
    if ((contestable || reviewRequired) && reason.isEmpty()) {
      throw bad(
          "这枚标签（"
              + nz(tagRow.get("name", String.class))
              + "）是**可争议标签**，必须写明原因："
              + "「他退了 3 次货」是事实，「他是个退货率高的买家」是判断 —— 后者必须有人负责，也必须能被纠正。");
    }
    String customerRef = nz(input.customerRef()).trim();
    String customerName = nz(input.customerName()).trim();
    var existing =
        sql.fetchOne(
            "select id, state from crm_customer_tag_assignment"
                + " where tenant_id = ? and customer_ref = ? and tag_id = ? and origin_key = 'MANUAL'",
            currentTenant(),
            customerRef,
            input.tagId());
    if (existing == null) {
      sql.execute(
          "insert into crm_customer_tag_assignment"
              + "(id,tenant_id,customer_ref,customer_name,tag_id,source,origin_key,rule_id,state,"
              + " reason,evidence,pending_review,created_by,created_at,updated_at)"
              + " values (?,?,?,?,?,'MANUAL','MANUAL',null,'ACTIVE',?,'{}'::jsonb,?,?,?::timestamptz,"
              + " ?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          customerRef,
          customerName,
          input.tagId(),
          reason,
          reviewRequired,
          actorId,
          now(),
          now());
    } else {
      // 人工重复打标是**幂等**的：更新原因，不新增行。
      // 之前被撤销的 ⇒ 重新打上（人工的决定优先于自动规则）。
      sql.execute(
          "update crm_customer_tag_assignment set customer_name = ?, reason = ?, state = 'ACTIVE',"
              + " pending_review = ?, decided_by = null, decided_at = null, updated_at = ?::timestamptz"
              + " where tenant_id = ? and id = ?",
          customerName,
          reason,
          reviewRequired,
          now(),
          currentTenant(),
          existing.get("id", UUID.class));
    }
    return assignmentByRef(customerRef, input.tagId(), "MANUAL");
  }

  /**
   * 人工复核一条标签记录。
   *
   * <p>🔴 {@code REVOKE} 时**必须**写原因：撤销一条「退货率高」的标签，
   * 必须说清「凭什么撤销」—— 否则这次纠正就变成了另一次没有依据的判断。
   */
  @Transactional
  public CrmCustomerTagAssignmentRow review(UUID assignmentId, CrmCustomerTagReviewInput input, UUID actorId) {
    var row =
        sql.fetchOne(
            "select id,tenant_id,customer_ref,tag_id,source,origin_key,state,reason"
                + " from crm_customer_tag_assignment where tenant_id = ? and id = ?",
            currentTenant(),
            assignmentId);
    if (row == null) throw notFound("标签记录不存在：" + assignmentId);
    CustomerTagReviewDecision decision = reviewDecision(input.decision());
    String reason = nz(input.reason()).trim();
    if (decision == CustomerTagReviewDecision.REVOKE) {
      if (reason.isEmpty()) {
        throw bad("撤销一条标签记录**必须**写明原因：没有原因的纠正只是另一次没有依据的判断。");
      }
      sql.execute(
          "update crm_customer_tag_assignment set state = 'REVOKED', reason = ?,"
              + " pending_review = false, decided_by = ?, decided_at = ?::timestamptz,"
              + " updated_at = ?::timestamptz where tenant_id = ? and id = ?",
          reason,
          actorId,
          now(),
          now(),
          currentTenant(),
          assignmentId);
    } else {
      // 确认：**不改**已有原因（可争议标签的原因必须留着可查）。
      sql.execute(
          "update crm_customer_tag_assignment set pending_review = false, decided_by = ?,"
              + " decided_at = ?::timestamptz, updated_at = ?::timestamptz"
              + " where tenant_id = ? and id = ?",
          actorId,
          now(),
          now(),
          currentTenant(),
          assignmentId);
    }
    return assignment(assignmentId);
  }

  // ================================================================ 运行记录

  /** 运行记录列表（真分页 + 真筛选）。 */
  public CrmCustomerTagRunPage runPage(UUID ruleId, String status, int page, int size) {
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    if (ruleId != null) {
      where.append(" and rule_id = ?");
      binds.add(ruleId);
    }
    if (blankToNull(status) != null) {
      where.append(" and status = ?");
      binds.add(status.trim());
    }
    long total =
        sql.fetchOne("select count(*) from crm_customer_tag_run" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmCustomerTagRunRow> items =
        sql.fetch(
                "select id,tenant_id,run_no,rule_id,rule_code,status,window_from,window_to,"
                    + " customer_count,tagged_count,unchanged_count,revoked_count,indeterminate_count,"
                    + " revoked_skipped_count,truncated,baseline,facts::text as facts,failure_reason,"
                    + " operator_id,started_at,finished_at from crm_customer_tag_run"
                    + where
                    + " order by started_at desc, id desc limit ? offset ?",
                pageBinds.toArray())
            .map(CustomerTagService::run);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCustomerTagRunPage(items, safePage, safeSize, total, totalPages);
  }

  /** 单次运行记录。 */
  public CrmCustomerTagRunRow run(UUID runId) {
    var row =
        sql.fetchOne(
            "select id,tenant_id,run_no,rule_id,rule_code,status,window_from,window_to,"
                + " customer_count,tagged_count,unchanged_count,revoked_count,indeterminate_count,"
                + " revoked_skipped_count,truncated,baseline,facts::text as facts,failure_reason,"
                + " operator_id,started_at,finished_at from crm_customer_tag_run"
                + " where tenant_id = ? and id = ?",
            currentTenant(),
            runId);
    if (row == null) throw notFound("运行记录不存在：" + runId);
    return run(row);
  }

  /** 逐客户判定结论（真分页 + 真筛选）。 */
  public CrmCustomerTagEvaluationPage evaluations(UUID runId, String outcome, int page, int size) {
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    binds.add(runId);
    StringBuilder where = new StringBuilder(" where tenant_id = ? and run_id = ?");
    if (blankToNull(outcome) != null) {
      where.append(" and outcome = ?");
      binds.add(outcome.trim());
    }
    long total =
        sql.fetchOne("select count(*) from crm_customer_tag_evaluation" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmCustomerTagEvaluationRow> items =
        sql.fetch(
                "select id,tenant_id,run_id,customer_ref,customer_name,outcome,reason,"
                    + " evidence::text as evidence,created_at from crm_customer_tag_evaluation"
                    + where
                    + " order by customer_ref limit ? offset ?",
                pageBinds.toArray())
            .map(CustomerTagService::evaluation);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCustomerTagEvaluationPage(items, safePage, safeSize, total, totalPages);
  }

  // ================================================================ 分布统计

  /**
   * 标签分布统计（蓝图输出句的第二项）。
   *
   * <p>🔴 占比在**服务端**算（前端只原样展示，不做任何前端算术）；
   * 分母为 0 时占比为 {@code null}（页面显示「不计算」而不是 0%）。
   */
  public CrmCustomerTagDistribution distribution() {
    int totalTaggedCustomers =
        sql.fetchOne(
                "select count(distinct customer_ref)::int from crm_customer_tag_assignment"
                    + " where tenant_id = ? and state = 'ACTIVE'",
                currentTenant())
            .get(0, Integer.class);
    int totalActiveAssignments =
        sql.fetchOne(
                "select count(*)::int from crm_customer_tag_assignment"
                    + " where tenant_id = ? and state = 'ACTIVE'",
                currentTenant())
            .get(0, Integer.class);
    var rows =
        sql.fetch(
                "select t.id as tag_id, t.tag_code, t.name, t.category, t.contestable,"
                    + " (count(distinct a.customer_ref) filter (where a.state = 'ACTIVE'))::int"
                    + "   as active_customers,"
                    + " (count(distinct a.customer_ref) filter (where a.state = 'ACTIVE'"
                    + "   and a.source = 'MANUAL'))::int as manual_customers,"
                    + " (count(distinct a.customer_ref) filter (where a.state = 'ACTIVE'"
                    + "   and a.source = 'RULE'))::int as rule_customers,"
                    + " (count(*) filter (where a.state = 'ACTIVE' and a.pending_review))::int"
                    + "   as pending_review,"
                    + " (count(*) filter (where a.state = 'REVOKED'))::int as revoked"
                    + " from crm_customer_tag t"
                    + " left join crm_customer_tag_assignment a"
                    + "   on a.tenant_id = t.tenant_id and a.tag_id = t.id"
                    + " where t.tenant_id = ?"
                    + " group by t.id, t.tag_code, t.name, t.category, t.contestable"
                    + " order by count(distinct a.customer_ref) filter (where a.state = 'ACTIVE') desc,"
                    + " t.tag_code",
                currentTenant())
            .map(
                r -> {
                  int active = r.get("active_customers", Integer.class);
                  BigDecimal share =
                      totalTaggedCustomers <= 0
                          ? null
                          : BigDecimal.valueOf(active)
                              .divide(BigDecimal.valueOf(totalTaggedCustomers), 6, RoundingMode.HALF_UP);
                  // ⚠️ 百分数在**服务端**算：前端只原样展示，不做「占比 × 100」这种算术。
                  BigDecimal sharePercent =
                      share == null
                          ? null
                          : share.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
                  return new CrmCustomerTagDistributionRow(
                      r.get("tag_id", UUID.class),
                      r.get("tag_code", String.class),
                      r.get("name", String.class),
                      r.get("category", String.class),
                      categoryLabel(r.get("category", String.class)),
                      Boolean.TRUE.equals(r.get("contestable", Boolean.class)),
                      active,
                      r.get("manual_customers", Integer.class),
                      r.get("rule_customers", Integer.class),
                      r.get("pending_review", Integer.class),
                      r.get("revoked", Integer.class),
                      share,
                      sharePercent);
                });
    return new CrmCustomerTagDistribution(
        totalTaggedCustomers,
        totalActiveAssignments,
        rows,
        "占比 = 持有这枚生效标签的客户数 ÷ 被打过任意标签的客户数（"
            + totalTaggedCustomers
            + " 人）。分母为 0 时不计算（报 null 而不是 0%）。"
            + "金额一律按币种分开统计，USD 与 EUR 加起来不是任何一个金额。");
  }

  // ================================================================ 候选客户（上游事实）

  /**
   * 候选客户清单（= 手工打标的「选谁」入口）。
   *
   * <p>数据来自**上游订单事实的真实聚合**（经 trade 授权 HTTP API），
   * 不是写死的名单，也不是本库的臆造。
   */
  public CrmCustomerTagCustomerCandidatePage customerCandidates(
      String keyword, int page, int size, String bearer) {
    OffsetDateTime windowTo = now();
    OffsetDateTime windowFrom = windowTo.minusDays(CANDIDATE_WINDOW_DAYS);
    FactScan scan = scanFacts(windowFrom, windowTo, bearer);
    String needle = blankToNull(keyword);
    List<CustomerFacts.Aggregate> matched = new ArrayList<>();
    for (CustomerFacts.Aggregate aggregate : scan.customers().values()) {
      if (needle == null
          || aggregate.customerRef().toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))
          || aggregate.customerName().toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
        matched.add(aggregate);
      }
    }
    matched.sort(
        Comparator.comparingInt((CustomerFacts.Aggregate a) -> a.windowOrders().size())
            .reversed()
            .thenComparing(CustomerFacts.Aggregate::customerRef));
    long total = matched.size();
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<CrmCustomerTagCustomerCandidate> items = new ArrayList<>();
    int from = Math.min(safePage * safeSize, matched.size());
    int to = Math.min(from + safeSize, matched.size());
    for (CustomerFacts.Aggregate aggregate : matched.subList(from, to)) {
      items.add(
          new CrmCustomerTagCustomerCandidate(
              aggregate.customerRef(),
              aggregate.customerName(),
              aggregate.windowOrders().size(),
              aggregate.amountsByCurrency(),
              new ArrayList<>(aggregate.channelSources()),
              aggregate.firstOrderAt() == null ? null : aggregate.firstOrderAt().toString(),
              aggregate.lastOrderAt() == null ? null : aggregate.lastOrderAt().toString(),
              taggedCountOf(aggregate.customerRef())));
    }
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCustomerTagCustomerCandidatePage(
        items,
        safePage,
        safeSize,
        total,
        totalPages,
        scan.truncated(),
        scan.scannedOrders(),
        scan.scannedCases(),
        scan.unattributedCases(),
        "候选客户由上游订单事实（trade 授权 HTTP API）实时聚合，统计窗口 "
            + CANDIDATE_WINDOW_DAYS
            + " 天。"
            + (scan.truncated()
                ? " 🔴 本次扫描**被截断**（扫到上限了），名单不是全量。"
                : "")
            + " 有 "
            + scan.unattributedCases()
            + " 张售后单归不到客户（其订单不在扫描窗口内）—— 这一部分没有进入任何人的退货率。");
  }

  /** 某位客户当前持有的生效标签数。 */
  private int taggedCountOf(String customerRef) {
    return sql.fetchOne(
            "select count(*) from crm_customer_tag_assignment"
                + " where tenant_id = ? and customer_ref = ? and state = 'ACTIVE'",
            currentTenant(),
            customerRef)
        .get(0, Integer.class);
  }

  // ================================================================ 上游事实扫描

  /**
   * 扫描上游订单与售后事实，聚合成「客户 → 事实」。
   *
   * <p>🔴 上游读不到 ⇒ <b>抛 503</b>，绝不返回空集合冒充「上游没有客户」。
   */
  private FactScan scanFacts(OffsetDateTime windowFrom, OffsetDateTime windowTo, String bearer) {
    OffsetDateTime orderFrom = windowFrom.minusDays(ORDER_LOOKBACK_DAYS);
    List<OrderView> orders = new ArrayList<>();
    boolean truncated = false;
    int page = 0;
    while (true) {
      OrderPage p = facts.orders(orderFrom, windowTo, page, bearer);
      orders.addAll(p.rows());
      long consumed = (long) (page + 1) * CustomerFactsPort.PAGE_SIZE;
      if (p.rows().isEmpty() || consumed >= p.total()) break;
      page++;
      if (page >= CustomerFactsPort.MAX_PAGES) {
        truncated = true;
        break;
      }
    }
    List<AftersalesCaseView> cases = new ArrayList<>();
    page = 0;
    while (true) {
      AftersalesCasePage p = facts.aftersalesCases(windowFrom, windowTo, page, bearer);
      cases.addAll(p.rows());
      long consumed = (long) (page + 1) * CustomerFactsPort.PAGE_SIZE;
      if (p.rows().isEmpty() || consumed >= p.total()) break;
      page++;
      if (page >= CustomerFactsPort.MAX_PAGES) {
        truncated = true;
        break;
      }
    }

    // 归因索引：orderId → 客户（**只认带 customer_party_id 的订单**）。
    Map<UUID, String> orderOwner = new HashMap<>();
    Map<String, CustomerFacts.Aggregate> aggregates = new LinkedHashMap<>();
    for (OrderView order : orders) {
      if (order.id() == null || order.customerPartyId() == null) continue;
      String ref = order.customerPartyId().toString();
      orderOwner.put(order.id(), ref);
      CustomerFacts.Aggregate aggregate =
          aggregates.computeIfAbsent(ref, r -> new CustomerFacts.Aggregate(r));
      aggregate.add(order);
    }
    int unattributed = 0;
    for (AftersalesCaseView c : cases) {
      if (c.orderId() == null) {
        unattributed++;
        continue;
      }
      String ref = orderOwner.get(c.orderId());
      if (ref == null) {
        unattributed++;
        continue;
      }
      aggregates.get(ref).addCase(c);
    }

    // 窗口内订单数（分母）—— 窗口外的订单只用来建归因索引，**不进分母**。
    for (CustomerFacts.Aggregate aggregate : aggregates.values()) {
      aggregate.markWindow(windowFrom, windowTo);
    }

    int windowOrders = 0;
    int windowCases = 0;
    for (CustomerFacts.Aggregate aggregate : aggregates.values()) {
      windowOrders += aggregate.windowOrders().size();
      windowCases += aggregate.returnCases();
    }
    BigDecimal baseline =
        windowOrders <= 0
            ? null
            : BigDecimal.valueOf(windowCases)
                .divide(BigDecimal.valueOf(windowOrders), 6, RoundingMode.HALF_UP);
    return new FactScan(
        aggregates, orders.size(), cases.size(), unattributed, windowOrders, baseline, truncated);
  }

  // ================================================================ 小工具

  private void requireUniqueTagCode(String tagCode, UUID selfId) {
    var row =
        sql.fetchOne(
            "select id from crm_customer_tag where tenant_id = ? and tag_code = ?",
            currentTenant(),
            tagCode);
    if (row != null && !row.get("id", UUID.class).equals(selfId)) {
      throw conflict("标签编码 " + tagCode + " 已存在：标签编码在租户内唯一。");
    }
  }

  private void requireUniqueRuleCode(String ruleCode, UUID selfId) {
    var row =
        sql.fetchOne(
            "select id from crm_customer_tag_rule where tenant_id = ? and rule_code = ?",
            currentTenant(),
            ruleCode);
    if (row != null && !row.get("id", UUID.class).equals(selfId)) {
      throw conflict("规则编码 " + ruleCode + " 已存在：规则编码在租户内唯一。");
    }
  }

  /**
   * 口径与阈值的一致性校验（服务层先说人话，DB CHECK 再兜一道）。
   */
  private void requireMetricArguments(CustomerTagMetric metric, CrmCustomerTagRuleInput input) {
    BigDecimal threshold = input.threshold();
    String conditionValue = blankToNull(input.conditionValue());
    if (metric == CustomerTagMetric.CHANNEL_SOURCE) {
      if (conditionValue == null) {
        throw bad("渠道来源口径必须写明 condition_value（要比哪个来源）。");
      }
      if (threshold != null) {
        throw bad(
            "渠道来源口径**不带阈值**：给了就是两套条件，得说清哪套说了算。"
                + "要按订单数 / 金额判定，请改用对应的口径。");
      }
      return;
    }
    if (threshold == null) {
      throw bad(metric.label() + "口径必须写明阈值（threshold）。");
    }
    if (metric == CustomerTagMetric.REPEAT_PURCHASE && threshold.compareTo(new BigDecimal("2")) < 0) {
      throw bad("复购口径的阈值至少是 2 单：只下一单不叫复购。");
    }
    if (metric == CustomerTagMetric.HIGH_VALUE && blankToNull(input.currency()) == null) {
      throw bad("高客单口径必须写明币种：金额按币种分开判定，USD 与 EUR 加起来不是任何一个金额。");
    }
    if (metric == CustomerTagMetric.RETURN_RATE
        && input.baselineMultiplier() != null
        && input.baselineMultiplier().compareTo(BigDecimal.ONE) <= 0) {
      throw bad("退货率口径的基线倍率必须大于 1：等于 1 是「不低于基线」，那不叫「显著高于」。");
    }
  }

  private static String tagColumns() {
    return "select t.id,t.tenant_id,t.tag_code,t.name,t.category,t.description,t.color,t.status,"
        + " t.contestable,t.review_required,t.created_by,t.created_at,t.updated_at"
        + " from crm_customer_tag t";
  }

  private static String ruleColumns() {
    return "select r.id,r.tenant_id,r.rule_code,r.name,r.tag_id,t.tag_code,t.name as tag_name,"
        + " r.metric,r.threshold,r.currency,r.condition_value,r.min_sample,r.window_days,"
        + " r.baseline_multiplier,r.status,r.note,r.created_by,r.created_at,r.updated_at,"
        + " (select lr.id from crm_customer_tag_run lr where lr.tenant_id = r.tenant_id"
        + "   and lr.rule_id = r.id order by lr.started_at desc, lr.id desc limit 1) as last_run_id,"
        + " (select lr.run_no from crm_customer_tag_run lr where lr.tenant_id = r.tenant_id"
        + "   and lr.rule_id = r.id order by lr.started_at desc, lr.id desc limit 1) as last_run_no,"
        + " (select lr.status from crm_customer_tag_run lr where lr.tenant_id = r.tenant_id"
        + "   and lr.rule_id = r.id order by lr.started_at desc, lr.id desc limit 1) as last_run_status,"
        + " (select lr.started_at from crm_customer_tag_run lr where lr.tenant_id = r.tenant_id"
        + "   and lr.rule_id = r.id order by lr.started_at desc, lr.id desc limit 1) as last_run_at"
        + " from crm_customer_tag_rule r left join crm_customer_tag t"
        + "   on t.tenant_id = r.tenant_id and t.id = r.tag_id";
  }

  private static String assignmentColumns() {
    return "select a.id,a.tenant_id,a.customer_ref,a.customer_name,a.tag_id,t.tag_code,t.name as tag_name,"
        + " t.category,t.contestable,a.source,a.origin_key,a.rule_id,r.rule_code,a.state,a.reason,"
        + " a.evidence::text as evidence,a.pending_review,a.decided_by,a.decided_at,a.created_by,"
        + " a.created_at,a.updated_at"
        + " from crm_customer_tag_assignment a"
        + " left join crm_customer_tag t on t.tenant_id = a.tenant_id and t.id = a.tag_id"
        + " left join crm_customer_tag_rule r on r.tenant_id = a.tenant_id and r.id = a.rule_id";
  }

  private String tagWhere(String category, String status, String keyword, List<Object> binds) {
    binds.add(currentTenant());
    StringBuilder where = new StringBuilder(" where t.tenant_id = ?");
    if (blankToNull(category) != null) {
      where.append(" and t.category = ?");
      binds.add(category.trim());
    }
    if (blankToNull(status) != null) {
      where.append(" and t.status = ?");
      binds.add(status.trim());
    }
    if (blankToNull(keyword) != null) {
      where.append(" and (strpos(lower(t.tag_code), lower(?)) > 0 or strpos(lower(t.name), lower(?)) > 0)");
      binds.add(keyword.trim());
      binds.add(keyword.trim());
    }
    return where.toString();
  }

  private String ruleWhere(
      UUID tagId, String metric, String status, String keyword, List<Object> binds) {
    binds.add(currentTenant());
    StringBuilder where = new StringBuilder(" where r.tenant_id = ?");
    if (tagId != null) {
      where.append(" and r.tag_id = ?");
      binds.add(tagId);
    }
    if (blankToNull(metric) != null) {
      where.append(" and r.metric = ?");
      binds.add(metric.trim());
    }
    if (blankToNull(status) != null) {
      where.append(" and r.status = ?");
      binds.add(status.trim());
    }
    if (blankToNull(keyword) != null) {
      where.append(" and (strpos(lower(r.rule_code), lower(?)) > 0 or strpos(lower(r.name), lower(?)) > 0)");
      binds.add(keyword.trim());
      binds.add(keyword.trim());
    }
    return where.toString();
  }

  private String assignmentWhere(
      UUID tagId, String customerRef, String source, String state, String keyword, List<Object> binds) {
    binds.add(currentTenant());
    StringBuilder where = new StringBuilder(" where a.tenant_id = ?");
    if (tagId != null) {
      where.append(" and a.tag_id = ?");
      binds.add(tagId);
    }
    if (blankToNull(customerRef) != null) {
      where.append(" and a.customer_ref = ?");
      binds.add(customerRef.trim());
    }
    if (blankToNull(source) != null) {
      where.append(" and a.source = ?");
      binds.add(source.trim());
    }
    if (blankToNull(state) != null) {
      where.append(" and a.state = ?");
      binds.add(state.trim());
    }
    if (blankToNull(keyword) != null) {
      where.append(" and (strpos(lower(a.customer_ref), lower(?)) > 0 or strpos(lower(a.customer_name), lower(?)) > 0)");
      binds.add(keyword.trim());
      binds.add(keyword.trim());
    }
    return where.toString();
  }

  private CrmCustomerTagAssignmentRow assignmentByRef(String customerRef, UUID tagId, String originKey) {
    var row =
        sql.fetchOne(
            assignmentColumns()
                + " where a.tenant_id = ? and a.customer_ref = ? and a.tag_id = ? and a.origin_key = ?",
            currentTenant(),
            customerRef,
            tagId,
            originKey);
    if (row == null) throw notFound("标签记录不存在。");
    return assignment(row);
  }

  private static CrmCustomerTagRow tag(Record r) {
    return new CrmCustomerTagRow(
        r.get("id", UUID.class),
        nz(r.get("tag_code", String.class)),
        nz(r.get("name", String.class)),
        nz(r.get("category", String.class)),
        categoryLabel(r.get("category", String.class)),
        nz(r.get("description", String.class)),
        nz(r.get("color", String.class)),
        nz(r.get("status", String.class)),
        Boolean.TRUE.equals(r.get("contestable", Boolean.class)),
        Boolean.TRUE.equals(r.get("review_required", Boolean.class)),
        r.get("created_by", UUID.class),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmCustomerTagRuleRow rule(Record r) {
    String metricCode = nz(r.get("metric", String.class));
    return new CrmCustomerTagRuleRow(
        r.get("id", UUID.class),
        nz(r.get("rule_code", String.class)),
        nz(r.get("name", String.class)),
        r.get("tag_id", UUID.class),
        nz(r.get("tag_code", String.class)),
        nz(r.get("tag_name", String.class)),
        metricCode,
        metricLabel(metricCode),
        r.get("threshold", BigDecimal.class),
        r.get("currency", String.class),
        r.get("condition_value", String.class),
        r.get("min_sample", BigDecimal.class),
        r.get("window_days", BigDecimal.class),
        r.get("baseline_multiplier", BigDecimal.class),
        nz(r.get("status", String.class)),
        nz(r.get("note", String.class)),
        r.get("last_run_id", UUID.class),
        r.get("last_run_no", String.class),
        r.get("last_run_status", String.class),
        str(r.get("last_run_at", OffsetDateTime.class)),
        r.get("created_by", UUID.class),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmCustomerTagAssignmentRow assignment(Record r) {
    String source = nz(r.get("source", String.class));
    String state = nz(r.get("state", String.class));
    return new CrmCustomerTagAssignmentRow(
        r.get("id", UUID.class),
        nz(r.get("customer_ref", String.class)),
        nz(r.get("customer_name", String.class)),
        r.get("tag_id", UUID.class),
        nz(r.get("tag_code", String.class)),
        nz(r.get("tag_name", String.class)),
        nz(r.get("category", String.class)),
        Boolean.TRUE.equals(r.get("contestable", Boolean.class)),
        source,
        sourceLabel(source),
        nz(r.get("origin_key", String.class)),
        r.get("rule_id", UUID.class),
        r.get("rule_code", String.class),
        state,
        stateLabel(state),
        nz(r.get("reason", String.class)),
        parseEvidence(r.get("evidence", String.class)),
        Boolean.TRUE.equals(r.get("pending_review", Boolean.class)),
        r.get("decided_by", UUID.class),
        str(r.get("decided_at", OffsetDateTime.class)),
        r.get("created_by", UUID.class),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmCustomerTagRunRow run(Record r) {
    String status = nz(r.get("status", String.class));
    return new CrmCustomerTagRunRow(
        r.get("id", UUID.class),
        nz(r.get("run_no", String.class)),
        r.get("rule_id", UUID.class),
        nz(r.get("rule_code", String.class)),
        status,
        runStatusLabel(status),
        str(r.get("window_from", OffsetDateTime.class)),
        str(r.get("window_to", OffsetDateTime.class)),
        r.get("customer_count", Integer.class),
        r.get("tagged_count", Integer.class),
        r.get("unchanged_count", Integer.class),
        r.get("revoked_count", Integer.class),
        r.get("indeterminate_count", Integer.class),
        r.get("revoked_skipped_count", Integer.class),
        Boolean.TRUE.equals(r.get("truncated", Boolean.class)),
        r.get("baseline", BigDecimal.class),
        parseEvidence(r.get("facts", String.class)),
        nz(r.get("failure_reason", String.class)),
        r.get("operator_id", UUID.class),
        str(r.get("started_at", OffsetDateTime.class)),
        str(r.get("finished_at", OffsetDateTime.class)));
  }

  private static CrmCustomerTagEvaluationRow evaluation(Record r) {
    String outcome = nz(r.get("outcome", String.class));
    return new CrmCustomerTagEvaluationRow(
        r.get("id", UUID.class),
        r.get("run_id", UUID.class),
        nz(r.get("customer_ref", String.class)),
        nz(r.get("customer_name", String.class)),
        outcome,
        outcomeLabel(outcome),
        nz(r.get("reason", String.class)),
        parseEvidence(r.get("evidence", String.class)),
        str(r.get("created_at", OffsetDateTime.class)));
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 运行号：TR-yyyyMMdd-000001（序列单独授权，见 V295 授权段）。 */
  private String nextRunNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_customer_tag_run_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "TR-" + day + "-" + String.format("%06d", seq);
  }

  /**
   * 解析带时区偏移量的时刻。
   *
   * <p>刻意用 {@code OffsetDateTime.parse}：用 {@code Timestamp.valueOf(LocalDateTime)} 会在东八区
   * **静默偏 −28800 秒**，窗口的两端会差 8 小时，而页面看上去一切正常。
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

  private static String toJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("判定依据快照无法序列化", e);
    }
  }

  private static Map<String, Object> parseEvidence(String raw) {
    if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
    try {
      return JSON.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return new LinkedHashMap<>();
    }
  }

  private static String plain(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  private static CustomerTagCategory category(String value) {
    String text = blankToNull(value);
    if (text == null) throw bad("必须指定标签分类。");
    try {
      return CustomerTagCategory.valueOf(text.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("标签分类只能是 " + String.join(" / ", names(CustomerTagCategory.values())) + "。");
    }
  }

  private static String categoryLabel(String value) {
    if (value == null) return "";
    try {
      return CustomerTagCategory.valueOf(value).label();
    } catch (IllegalArgumentException e) {
      return value;
    }
  }

  private static CustomerTagMetric metric(String value) {
    String text = blankToNull(value);
    if (text == null) throw bad("必须指定判定口径。");
    try {
      return CustomerTagMetric.valueOf(text.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("判定口径只能是 " + String.join(" / ", names(CustomerTagMetric.values())) + "。");
    }
  }

  private static String metricLabel(String value) {
    if (value == null) return "";
    try {
      return CustomerTagMetric.valueOf(value).label();
    } catch (IllegalArgumentException e) {
      return value;
    }
  }

  private static String tagStatus(String value) {
    String text = blankToNull(value);
    if (text == null) return "ACTIVE";
    return oneOf(CustomerTagStatus.values(), text, "标签状态");
  }

  private static String ruleStatus(String value) {
    String text = blankToNull(value);
    if (text == null) return "ACTIVE";
    return oneOf(CustomerTagStatus.values(), text, "规则状态");
  }

  private static CustomerTagReviewDecision reviewDecision(String value) {
    String text = blankToNull(value);
    if (text == null) throw bad("必须写明复核结论（CONFIRM 确认 / REVOKE 撤销）。");
    try {
      return CustomerTagReviewDecision.valueOf(text.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("复核结论只能是 CONFIRM（确认）/ REVOKE（撤销）。");
    }
  }

  private static String oneOf(CustomerTagStatus[] allowed, String value, String label) {
    if (value == null) throw bad(label + "不能为空。");
    for (CustomerTagStatus s : allowed) {
      if (s.name().equals(value)) return value;
    }
    throw bad(label + "「" + value + "」不在允许取值里：ACTIVE / DISABLED。");
  }

  private static List<String> names(CustomerTagCategory[] values) {
    List<String> out = new ArrayList<>();
    for (var v : values) out.add(v.name());
    return out;
  }

  private static List<String> names(CustomerTagMetric[] values) {
    List<String> out = new ArrayList<>();
    for (var v : values) out.add(v.name());
    return out;
  }

  private static String sourceLabel(String source) {
    return switch (source) {
      case "MANUAL" -> "人工打标";
      case "RULE" -> "规则打标";
      default -> source;
    };
  }

  private static String stateLabel(String state) {
    return switch (state) {
      case "ACTIVE" -> "生效";
      case "REVOKED" -> "已撤销";
      default -> state;
    };
  }

  private static String runStatusLabel(String status) {
    return switch (status) {
      case "SUCCEEDED" -> "已完成";
      case "FAILED" -> "失败";
      default -> status;
    };
  }

  private static String outcomeLabel(String outcome) {
    try {
      return CrmCustomerTagEvaluationOutcome.valueOf(outcome).label();
    } catch (IllegalArgumentException e) {
      return outcome;
    }
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

  private static String str(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }

  // ================================================================ 内部结构

  /** 判定的三种走向。 */
  private enum DecisionKind {
    /** 命中。 */
    MATCH,
    /** 判定为不命中。 */
    NO_MATCH,
    /** 不可判定（样本不足 / 没有基线）—— **不是「否」，也不是「是」**。 */
    INDETERMINATE
  }

  /** 一次判定的结论。 */
  private record Decision(DecisionKind kind, String reason, Map<String, Object> evidence) {}

  /** 一条待落库的逐客户判定结论。 */
  private record PendingEvaluation(
      CustomerFacts.Aggregate aggregate,
      CrmCustomerTagEvaluationOutcome outcome,
      String reason,
      Map<String, Object> evidence) {}

  /** 一次扫描的结果。 */
  private record FactScan(
      Map<String, CustomerFacts.Aggregate> customers,
      int scannedOrders,
      int scannedCases,
      int unattributedCases,
      int windowOrderCount,
      BigDecimal baseline,
      boolean truncated) {}

  /** 一位客户的聚合事实。 */
  static final class CustomerFacts {
    static final class Aggregate {
      private final String customerRef;
      private String customerName = "";
      private final List<OrderView> allOrders = new ArrayList<>();
      private final List<OrderView> windowOrders = new ArrayList<>();
      private final List<AftersalesCaseView> cases = new ArrayList<>();
      private final Map<String, BigDecimal> amountsByCurrency = new TreeMap<>();
      private final Set<String> channelSources = new LinkedHashSet<>();
      private OffsetDateTime firstOrderAt;
      private OffsetDateTime lastOrderAt;

      Aggregate(String customerRef) {
        this.customerRef = customerRef;
      }

      void add(OrderView order) {
        allOrders.add(order);
        if (order.buyerName() != null && !order.buyerName().isBlank()) customerName = order.buyerName();
      }

      void addCase(AftersalesCaseView c) {
        cases.add(c);
      }

      /** 把落在窗口内的订单挑出来（**分母只用这一份**）。 */
      void markWindow(OffsetDateTime from, OffsetDateTime to) {
        for (OrderView order : allOrders) {
          OffsetDateTime at = orderTime(order);
          if (at == null || at.isBefore(from) || !at.isBefore(to)) continue;
          windowOrders.add(order);
          if (order.currency() != null && !order.currency().isBlank() && order.grandTotalAmount() != null) {
            amountsByCurrency.merge(
                order.currency(), order.grandTotalAmount(), BigDecimal::add);
          }
          if (order.orderSource() != null && !order.orderSource().isBlank()) {
            channelSources.add(order.orderSource());
          }
          if (firstOrderAt == null || at.isBefore(firstOrderAt)) firstOrderAt = at;
          if (lastOrderAt == null || at.isAfter(lastOrderAt)) lastOrderAt = at;
        }
      }

      /** 计入「退货率」的售后单数（只算退货 / 退款两类）。 */
      int returnCases() {
        int count = 0;
        for (AftersalesCaseView c : cases) {
          if (c.caseType() != null && RETURN_CASE_TYPES.contains(c.caseType())) count++;
        }
        return count;
      }

      BigDecimal returnRate() {
        int denominator = windowOrders.size();
        if (denominator <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(returnCases())
            .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
      }

      BigDecimal amountOf(String currency) {
        if (currency == null) return null;
        return amountsByCurrency.get(currency);
      }

      String customerRef() {
        return customerRef;
      }

      String customerName() {
        return customerName;
      }

      List<OrderView> windowOrders() {
        return windowOrders;
      }

      List<AftersalesCaseView> cases() {
        return cases;
      }

      Map<String, BigDecimal> amountsByCurrency() {
        return amountsByCurrency;
      }

      Set<String> channelSources() {
        return channelSources;
      }

      OffsetDateTime firstOrderAt() {
        return firstOrderAt;
      }

      OffsetDateTime lastOrderAt() {
        return lastOrderAt;
      }

      /** 判定依据快照：依据哪几笔订单 / 哪几张售后单。 */
      Map<String, Object> evidence(CustomerTagMetric metric) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("customerRef", customerRef);
        evidence.put("customerName", customerName);
        evidence.put("windowOrderCount", windowOrders.size());
        List<String> orderNos = new ArrayList<>();
        for (OrderView order : windowOrders) orderNos.add(nz(order.orderNo()));
        evidence.put("orderNos", orderNos);
        List<String> orderIds = new ArrayList<>();
        for (OrderView order : windowOrders) orderIds.add(String.valueOf(order.id()));
        evidence.put("orderIds", orderIds);
        Map<String, Object> amounts = new LinkedHashMap<>();
        for (var entry : amountsByCurrency.entrySet()) amounts.put(entry.getKey(), entry.getValue());
        evidence.put("amountsByCurrency", amounts);
        evidence.put("channelSources", new ArrayList<>(channelSources));
        evidence.put("aftersalesCount", cases.size());
        List<String> caseNos = new ArrayList<>();
        List<String> caseOrderNos = new ArrayList<>();
        for (AftersalesCaseView c : cases) {
          if (c.caseType() == null || !RETURN_CASE_TYPES.contains(c.caseType())) continue;
          caseNos.add(nz(c.caseNo()));
          caseOrderNos.add(nz(c.orderNo()));
        }
        evidence.put("returnCaseNos", caseNos);
        evidence.put("returnCaseOrderNos", caseOrderNos);
        evidence.put("countedCaseTypes", new ArrayList<>(RETURN_CASE_TYPES.stream().sorted().toList()));
        evidence.put("metric", metric.name());
        return evidence;
      }
    }

    static OffsetDateTime orderTime(OrderView order) {
      return order.submittedAt() != null ? order.submittedAt() : order.createdAt();
    }
  }
}
