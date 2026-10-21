package com.starcon.crm;

import static com.starcon.crm.ReviewRuleModels.*;

import com.starcon.crm.ReviewRuleModels.CrmReviewAssignmentInput;
import com.starcon.crm.ReviewRuleModels.CrmReviewAssignmentRow;
import com.starcon.crm.ReviewRuleModels.CrmReviewHolidayInput;
import com.starcon.crm.ReviewRuleModels.CrmReviewHolidayRow;
import com.starcon.crm.ReviewRuleModels.CrmReviewRuleCapabilities;
import com.starcon.crm.ReviewRuleModels.CrmReviewRuleCapabilityDimension;
import com.starcon.crm.ReviewRuleModels.CrmReviewRuleConfigInput;
import com.starcon.crm.ReviewRuleModels.CrmReviewRuleRow;
import com.starcon.crm.ReviewRuleModels.CrmReviewRuleShopView;
import com.starcon.crm.ReviewRuleModels.CrmReviewTemplateInput;
import com.starcon.crm.ReviewRuleModels.CrmReviewTemplateRow;
import com.starcon.crm.ReviewRuleModels.CrmReviewVariantRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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
 * 15.2.2.2 索评规则：配置索评策略 —— 按店铺、按物流方式（自发货 / 平台仓）设置不同延后时间，
 * 避开节假日，支持 A/B 测试不同话术。
 *
 * <p>蓝图 15 卷 15.2.2.2 原话：
 *
 * <blockquote>
 *
 * 职责：配置索评策略：按店铺、按物流方式（自发货/平台仓）设置不同延后时间，避开节假日，支持 A/B 测试不同话术。 输入：规则配置；话术模板。 输出：生效策略 + 效果对比。 关系：上游 = 15.2.2.1；下游 = 15.1.1.2 发送。
 *
 * </blockquote>
 *
 * <p><b>本模块交付的三件事</b>：
 *
 * <ol>
 *   <li><b>规则配置</b>（{@code crm_review_rule}）：按店铺 × 按物流方式的延后时间 + 节假日规避 + A/B 开关。
 *       「生效」的定义写死在 DB 的唯一索引里：同一适用范围同一优先级不允许有两条 ACTIVE 规则，
 *       否则「生效策略」会有两个答案。
 *   <li><b>话术模板</b>（{@code crm_review_message_template} + {@code crm_review_message_variant}）：
 *       模板是身份，变体是内容（A / B 两臂 + 权重）。
 *   <li><b>生效策略 + 效果对比</b>（{@code crm_review_rule_assignment}）：
 *       把解析出来的策略**落到**具体的索评请求上（哪个变体、排期到什么时候），
 *       效果对比就是按这条归属事实把 15.2.2.1 的回收拆到 A / B 两臂上。
 * </ol>
 *
 * <p><b>跨服务纪律</b>：
 *
 * <ul>
 *   <li>店铺目录在 <b>channel</b> 服务（2.3.1.1）⇒ 只经 {@link SignoffDeliveredOrderPort} 读，
 *       本库只存 {@code shop_id} + {@code shop_name} 快照，**不建跨库 FK**。
 *   <li>上游 <b>15.2.2.1</b> 索评任务（{@code crm_review_request}）与本模块<b>同服务、同数据库</b>：
 *       本模块<b>只读</b>它（取店铺 / 买家 / 订单 / 签收时刻 / 发送状态 / 评价回收），
 *       写入所有者仍然只有 15.2.2.1 一个。
 *   <li>下游 <b>15.1.1.2 消息发送</b>（蓝图写明本模块的下游）<b>未交付</b> ⇒
 *       本模块只<b>产出</b>话术与生效策略，<b>不发送</b>，也<b>不写</b>「已发送」这类回执 ——
 *       自己写的「已发送」会让「买家到底收到没收到」看起来发生过，
 *       而那正是过度索评投诉的源头。
 * </ul>
 *
 * <p><b>禁浮点</b>：延后小时数与权重一律 {@code numeric(19,6)} + BigDecimal；
 * <b>时间</b>一律 {@code timestamptz} + OffsetDateTime，绑定写 {@code ?::timestamptz}。
 */
@Service
public class ReviewRuleService {

  // ================================================================ 常量

  /** 规则状态（与 V228 的 CHECK 同词表）。 */
  static final List<String> RULE_STATUSES = List.of("DRAFT", "ACTIVE", "PAUSED");

  /** 适用物流方式。ANY = 不限。 */
  static final List<String> LOGISTICS_MODES =
      List.of("ANY", "SELF_FULFILLED", "PLATFORM_WAREHOUSE");

  /** 模板 / 变体状态。 */
  static final List<String> TEMPLATE_STATUSES = List.of("ACTIVE", "PAUSED");

  /** A/B 两个臂。 */
  static final List<String> VARIANT_CODES = List.of("A", "B");

  /** 节假日类型。 */
  static final List<String> HOLIDAY_KINDS = List.of("HOLIDAY", "PROMOTION");

  /** 索评请求状态（读 15.2.2.1 的词表，本模块只读不改）。 */
  static final List<String> REQUEST_STATUSES =
      List.of("SCHEDULED", "SENT", "CANCELLED", "REJECTED");

  /** 效果对比默认时间窗（天）。 */
  static final int DEFAULT_EFFECT_DAYS = 30;

  /** 命中节假日后最多往后找多少天（防止病态日历把排期推到无穷远）。 */
  static final int MAX_HOLIDAY_LOOKAHEAD = 30;

  /** 分桶桶数：权重按百分比换算成万分位。 */
  static final int BUCKET_COUNT = 10_000;

  private final DSLContext sql;
  private final SignoffDeliveredOrderPort port;

  public ReviewRuleService(DSLContext sql, SignoffDeliveredOrderPort port) {
    this.sql = sql;
    this.port = port;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 三维刻意是 {@code available=false}：
   *
   * <ul>
   *   <li>{@code message_dispatch} —— 下游 15.1.1.2 消息发送**未交付**，本模块只产出话术，**不发送**；
   *   <li>{@code review_sync} —— 平台评价同步**未交付**，效果对比的「回收」来自 15.2.2.1 的
   *       <b>人工登记</b>的回收桶；
   *   <li>{@code order_fulfilment_mode} —— 订单的履约方式（自发货 / 平台仓）<b>不可从上游核实</b>：
   *       15.2.2.1 的索评请求不带这个维度，本模块的物流方式由运营在应用策略时给出，
   *       店铺的经营模式只作<b>提示</b>不作依据。
   * </ul>
   */
  public CrmReviewRuleCapabilities capabilities() {
    List<CrmReviewRuleCapabilityDimension> dimensions =
        List.of(
            new CrmReviewRuleCapabilityDimension(
                "rule_configuration",
                "索评规则配置（按店铺 / 按物流方式的延后时间、节假日规避、A/B 开关）",
                true,
                "本服务 V228 crm_review_rule（生效判定写死在 DB 的唯一索引与查询口径里）",
                "",
                "同一适用范围同一优先级**不允许**有两条 ACTIVE 规则 —— 否则「生效策略」会有两个答案"),
            new CrmReviewRuleCapabilityDimension(
                "message_templates",
                "索评话术模板与 A / B 变体（含权重）",
                true,
                "本服务 V228 crm_review_message_template + crm_review_message_variant",
                "",
                "A/B 分桶是<b>确定性</b>的：同一个种子永远落到同一个臂，因此效果对比可复算"),
            new CrmReviewRuleCapabilityDimension(
                "holiday_calendar",
                "节假日日历（索评要避开的日子，支持连休）",
                true,
                "本服务 V228 crm_review_holiday",
                "",
                "按规则的 timezone 换算（默认东八区）：用 UTC 日界会把「东八区 10-02 07:00」算成 10-01，节假日错开一天"),
            new CrmReviewRuleCapabilityDimension(
                "shop_directory",
                "店铺目录（规则的适用对象）",
                true,
                "上游 channel V1 shop，经授权 HTTP API 读（GET /channel/shops）",
                "2.3.1.1",
                "适用店铺从**真实清单**里选，不是前端写死的常量；上游读不到一律 503"),
            new CrmReviewRuleCapabilityDimension(
                "review_request_source",
                "被应用策略的索评任务（上游 15.2.2.1）",
                true,
                "本服务 V220 crm_review_request（**同库只读**，写入所有者仍是 15.2.2.1）",
                "15.2.2.1",
                "效果对比的「回收」来自它的评价回收桶；本模块**只读**，不改它的频次硬上限"),
            new CrmReviewRuleCapabilityDimension(
                "message_dispatch",
                "🔴 索评话术的发送（下游 15.1.1.2 消息发送）",
                false,
                "15.1.1.2 未交付",
                "15.1.1.2",
                "本模块只**产出**话术与生效策略，**不发送**、也**不写**「已发送」这类回执 —— "
                    + "自己写的「已发送」会让「买家到底收到没收到」看起来发生过，"
                    + "而那正是过度索评投诉的源头"),
            new CrmReviewRuleCapabilityDimension(
                "review_sync",
                "平台评价同步（自动把平台评价拉进来）",
                false,
                "2 同步未交付平台评价；15.2.1.1 评价列表未交付",
                "15.2.1.1",
                "效果对比的回收数来自 15.2.2.1 的**人工登记**回收桶，"
                    + "**不是**平台自动同步的结果"),
            new CrmReviewRuleCapabilityDimension(
                "order_fulfilment_mode",
                "订单的履约方式（自发货 / 平台仓）",
                false,
                "15.2.2.1 的索评请求不带履约方式维度（capabilities 里 shop_attribution 维度 available=false）",
                "15.2.2.1",
                "应用策略时由运营给出物流方式；店铺的 operating_mode 只作**提示**不作依据 —— "
                    + "一家店两种单都发时，店铺级属性答不了「这一单是自发货还是平台仓」"));
    return new CrmReviewRuleCapabilities(
        dimensions,
        RULE_STATUSES,
        LOGISTICS_MODES,
        TEMPLATE_STATUSES,
        VARIANT_CODES,
        HOLIDAY_KINDS,
        REQUEST_STATUSES,
        "上游店铺目录在 **channel** 服务，本服务一条 SQL 都不碰对方的库，只经授权 HTTP API 读；"
            + "上游 15.2.2.1 的索评任务与本模块**同库**，本模块**只读**它；"
            + "下游 15.1.1.2 消息发送**未交付**，本模块只产出话术与生效策略，**不发送**。",
        "🔴 规则是**配置**，不是快照：改了之后**新**的索评请求按新策略走，"
            + "已经落库的分配记录仍按当时的快照算（快照列建后不可改）—— "
            + "否则「哪条策略带来了这次回收」在历史上会有两个答案，效果对比就不可信。");
  }

  // ================================================================ 规则配置

  /** 规则列表（真分页 + 真筛选）。 */
  public List<CrmReviewRuleRow> listRules(
      UUID shopId, String logisticsMode, String status, String keyword, int page, int size) {
    int limit = size <= 0 ? 50 : Math.min(size, 200);
    var rows =
        sql.fetch(
            ruleColumns()
                + " where r.tenant_id = ?"
                + (shopId == null ? "" : " and r.shop_id = ?")
                + (blankToNull(logisticsMode) == null ? "" : " and r.logistics_mode = ?")
                + (blankToNull(status) == null ? "" : " and r.status = ?")
                + (blankToNull(keyword) == null
                    ? ""
                    : " and (r.rule_name ilike ? or r.rule_no ilike ? or r.shop_name ilike ?)")
                + " order by r.status, r.priority, r.created_at desc, r.id"
                + " limit ? offset ?",
            ruleBindings(shopId, logisticsMode, status, keyword, limit, Math.max(page, 0) * limit));
    return rows.map(ReviewRuleService::rule);
  }

  /** 规则总数（与 {@link #listRules} 同一套筛选口径）。 */
  public long countRules(UUID shopId, String logisticsMode, String status, String keyword) {
    return sql.fetchOne(
            "select count(*) from crm_review_rule r"
                + " where r.tenant_id = ?"
                + (shopId == null ? "" : " and r.shop_id = ?")
                + (blankToNull(logisticsMode) == null ? "" : " and r.logistics_mode = ?")
                + (blankToNull(status) == null ? "" : " and r.status = ?")
                + (blankToNull(keyword) == null
                    ? ""
                    : " and (r.rule_name ilike ? or r.rule_no ilike ? or r.shop_name ilike ?)"),
            ruleBindings(shopId, logisticsMode, status, keyword))
        .get(0, Long.class);
  }

  /** 单条规则。 */
  public CrmReviewRuleRow rule(UUID ruleId) {
    var row =
        sql.fetchOne(
            ruleColumns() + " where r.tenant_id = ? and r.id = ?", currentTenant(), ruleId);
    if (row == null) throw notFound("索评规则不存在");
    return rule(row);
  }

  /**
   * 新建规则。
   *
   * <p>指定店铺时，店铺必须在<b>上游 channel 的真实清单</b>里 ——
   * 否则运营会对着一家不存在的店配一条永远不生效的规则。上游读不到一律 503。
   */
  @Transactional
  public CrmReviewRuleRow createRule(CrmReviewRuleConfigInput input, UUID actorId, String bearer) {
    String mode = oneOf(LOGISTICS_MODES, input.logisticsMode(), "物流方式");
    String status = oneOf(RULE_STATUSES, input.status(), "规则状态");
    UUID shopId = input.shopId();
    String shopName = "";
    if (shopId != null) {
      shopName = requireShopName(shopId, bearer);
    }
    UUID templateId = input.templateId();
    if (templateId != null) {
      requireTemplate(templateId);
    }
    ZoneId zone = zoneOf(input.timezone());
    OffsetDateTime from = optionalMoment(input.effectiveFrom(), "生效起点");
    OffsetDateTime to = optionalMoment(input.effectiveTo(), "生效终点");
    if (from != null && to != null && !to.isAfter(from)) {
      throw bad("有效期是空的：终点「" + to + "」不晚于起点「" + from + "」。");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    try {
      sql.execute(
          "insert into crm_review_rule"
              + "(id,tenant_id,rule_no,rule_name,shop_id,shop_name,logistics_mode,delay_hours,"
              + " template_id,ab_test_enabled,avoid_holidays,holiday_region,holiday_shift_days,"
              + " timezone,priority,status,effective_from,effective_to,note,created_by,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          nextRuleNo(),
          input.ruleName().trim(),
          shopId,
          shopName,
          mode,
          input.delayHours(),
          templateId,
          Boolean.TRUE.equals(input.abTestEnabled()),
          input.avoidHolidays() == null || input.avoidHolidays(),
          nz(input.holidayRegion()).trim(),
          input.holidayShiftDays() == null ? 1 : input.holidayShiftDays(),
          zone.getId(),
          input.priority() == null ? 100 : input.priority(),
          status,
          from,
          to,
          nz(input.note()),
          actorId,
          moment,
          moment);
    } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "索评规则写入被数据库层拒绝：" + rootMessage(e), e);
    }
    return rule(id);
  }

  /** 更新规则。 */
  @Transactional
  public CrmReviewRuleRow updateRule(
      UUID ruleId, CrmReviewRuleConfigInput input, UUID actorId, String bearer) {
    rule(ruleId);
    String mode = oneOf(LOGISTICS_MODES, input.logisticsMode(), "物流方式");
    String status = oneOf(RULE_STATUSES, input.status(), "规则状态");
    UUID shopId = input.shopId();
    String shopName = shopId == null ? "" : requireShopName(shopId, bearer);
    UUID templateId = input.templateId();
    if (templateId != null) {
      requireTemplate(templateId);
    }
    ZoneId zone = zoneOf(input.timezone());
    OffsetDateTime from = optionalMoment(input.effectiveFrom(), "生效起点");
    OffsetDateTime to = optionalMoment(input.effectiveTo(), "生效终点");
    if (from != null && to != null && !to.isAfter(from)) {
      throw bad("有效期是空的：终点「" + to + "」不晚于起点「" + from + "」。");
    }
    try {
      sql.execute(
          "update crm_review_rule set rule_name=?,shop_id=?,shop_name=?,logistics_mode=?,"
              + " delay_hours=?,template_id=?,ab_test_enabled=?,avoid_holidays=?,holiday_region=?,"
              + " holiday_shift_days=?,timezone=?,priority=?,status=?,effective_from=?::timestamptz,"
              + " effective_to=?::timestamptz,note=?,updated_at=?::timestamptz"
              + " where tenant_id=? and id=?",
          input.ruleName().trim(),
          shopId,
          shopName,
          mode,
          input.delayHours(),
          templateId,
          Boolean.TRUE.equals(input.abTestEnabled()),
          input.avoidHolidays() == null || input.avoidHolidays(),
          nz(input.holidayRegion()).trim(),
          input.holidayShiftDays() == null ? 1 : input.holidayShiftDays(),
          zone.getId(),
          input.priority() == null ? 100 : input.priority(),
          status,
          from,
          to,
          nz(input.note()),
          now(),
          currentTenant(),
          ruleId);
    } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "索评规则更新被数据库层拒绝：" + rootMessage(e), e);
    }
    return rule(ruleId);
  }

  /**
   * 删除规则。
   *
   * <p>已被生效策略落地引用过的规则**删不掉**（DB 触发器会拒）——
   * 删掉之后效果对比里那一段历史会凭空消失，结论被改写而不是少了几条。
   */
  @Transactional
  public void deleteRule(UUID ruleId) {
    rule(ruleId);
    try {
      sql.execute("delete from crm_review_rule where tenant_id=? and id=?", currentTenant(), ruleId);
    } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "索评规则删除被数据库层拒绝：" + rootMessage(e), e);
    }
  }

  // ================================================================ 话术模板与变体

  /** 规则列表分页（真分页：总数与页数都来自真实计数）。 */
  public CrmReviewRulePage rulePage(
      UUID shopId, String logisticsMode, String status, String keyword, int page, int size) {
    int limit = size <= 0 ? 50 : Math.min(size, 200);
    long total = countRules(shopId, logisticsMode, status, keyword);
    return new CrmReviewRulePage(
        listRules(shopId, logisticsMode, status, keyword, page, limit),
        Math.max(page, 0),
        limit,
        total,
        (int) Math.ceil(total / (double) limit));
  }

  /** 话术模板列表分页。 */
  public CrmReviewTemplatePage templatePage(String keyword, String locale, int page, int size) {
    int limit = size <= 0 ? 50 : Math.min(size, 200);
    long total = countTemplates(keyword, locale);
    return new CrmReviewTemplatePage(
        listTemplates(keyword, locale, page, limit),
        Math.max(page, 0),
        limit,
        total,
        (int) Math.ceil(total / (double) limit));
  }

  /** 节假日列表分页。 */
  public CrmReviewHolidayPage holidayPage(
      String year, String region, String kind, int page, int size) {
    int limit = size <= 0 ? 50 : Math.min(size, 200);
    long total = countHolidays(year, region, kind);
    return new CrmReviewHolidayPage(
        listHolidays(year, region, kind, page, limit),
        Math.max(page, 0),
        limit,
        total,
        (int) Math.ceil(total / (double) limit));
  }

  /** 落地记录列表分页。 */
  public CrmReviewAssignmentPage assignmentPage(
      UUID shopId, UUID ruleId, UUID variantId, int page, int size) {
    int limit = size <= 0 ? 50 : Math.min(size, 200);
    long total = countAssignments(shopId, ruleId, variantId);
    return new CrmReviewAssignmentPage(
        listAssignments(shopId, ruleId, variantId, page, limit),
        Math.max(page, 0),
        limit,
        total,
        (int) Math.ceil(total / (double) limit));
  }

  /** 话术模板列表（真分页 + 真筛选），每个模板带它的变体。 */
  public List<CrmReviewTemplateRow> listTemplates(String keyword, String locale, int page, int size) {
    int limit = size <= 0 ? 50 : Math.min(size, 200);
    var rows =
        sql.fetch(
            "select t.id,t.template_no,t.template_name,t.locale,t.status,t.note,t.created_at,t.updated_at"
                + " from crm_review_message_template t"
                + " where t.tenant_id = ?"
                + (blankToNull(keyword) == null ? "" : " and t.template_name ilike ?")
                + (blankToNull(locale) == null ? "" : " and t.locale = ?")
                + " order by t.created_at desc, t.id"
                + " limit ? offset ?",
            templateBindings(keyword, locale, limit, Math.max(page, 0) * limit));
    List<CrmReviewTemplateRow> out = new ArrayList<>();
    for (Record r : rows) {
      out.add(template(r, variantsOf(r.get("id", UUID.class))));
    }
    return out;
  }

  /** 话术模板总数。 */
  public long countTemplates(String keyword, String locale) {
    return sql.fetchOne(
            "select count(*) from crm_review_message_template t"
                + " where t.tenant_id = ?"
                + (blankToNull(keyword) == null ? "" : " and t.template_name ilike ?")
                + (blankToNull(locale) == null ? "" : " and t.locale = ?"),
            templateBindings(keyword, locale))
        .get(0, Long.class);
  }

  /** 单个话术模板。 */
  public CrmReviewTemplateRow template(UUID templateId) {
    var row =
        sql.fetchOne(
            "select id,template_no,template_name,locale,status,note,created_at,updated_at"
                + " from crm_review_message_template where tenant_id=? and id=?",
            currentTenant(),
            templateId);
    if (row == null) throw notFound("话术模板不存在");
    return template(row, variantsOf(templateId));
  }

  /** 新建话术模板（含变体）。 */
  @Transactional
  public CrmReviewTemplateRow createTemplate(CrmReviewTemplateInput input, UUID actorId) {
    String status = oneOf(TEMPLATE_STATUSES, input.status(), "模板状态");
    List<ReviewRuleModels.CrmReviewTemplateVariantInput> variants = input.variants();
    // 变体码不能重复：同一模板下 A / B 各一条，重复会让分桶落到「两个 A」。
    Map<String, Boolean> seen = new HashMap<>();
    for (var variant : variants) {
      String code = oneOf(VARIANT_CODES, variant.variantCode(), "话术变体码");
      if (seen.put(code, Boolean.TRUE) != null) {
        throw bad("话术模板里变体码「" + code + "」重复了：同一个模板下 A / B 各一条。");
      }
      oneOf(TEMPLATE_STATUSES, variant.status(), "变体状态");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    sql.execute(
        "insert into crm_review_message_template"
            + "(id,tenant_id,template_no,template_name,locale,status,note,created_by,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        nextTemplateNo(),
        input.templateName().trim(),
        input.locale().trim(),
        status,
        nz(input.note()),
        actorId,
        moment,
        moment);
    for (var variant : variants) {
      insertVariant(id, variant, moment);
    }
    return template(id);
  }

  /**
   * 更新话术模板。
   *
   * <p>变体按「变体码」对齐：模板里多出来的变体被**删掉**，少掉的变体被**新增**，
   * 两边都有的就更新正文与权重 —— 所以「改话术」不需要先删再建（那会丢掉归属历史）。
   */
  @Transactional
  public CrmReviewTemplateRow updateTemplate(
      UUID templateId, CrmReviewTemplateInput input, UUID actorId) {
    template(templateId);
    String status = oneOf(TEMPLATE_STATUSES, input.status(), "模板状态");
    Map<String, Boolean> seen = new HashMap<>();
    for (var variant : input.variants()) {
      String code = oneOf(VARIANT_CODES, variant.variantCode(), "话术变体码");
      if (seen.put(code, Boolean.TRUE) != null) {
        throw bad("话术模板里变体码「" + code + "」重复了：同一个模板下 A / B 各一条。");
      }
      oneOf(TEMPLATE_STATUSES, variant.status(), "变体状态");
    }
    OffsetDateTime moment = now();
    sql.execute(
        "update crm_review_message_template set template_name=?,locale=?,status=?,note=?,"
            + " updated_at=?::timestamptz where tenant_id=? and id=?",
        input.templateName().trim(),
        input.locale().trim(),
        status,
        nz(input.note()),
        moment,
        currentTenant(),
        templateId);

    List<CrmReviewVariantRow> existing = variantsOf(templateId);
    Map<String, CrmReviewVariantRow> byCode = new HashMap<>();
    for (var variant : existing) {
      byCode.put(variant.variantCode(), variant);
    }
    for (var variant : input.variants()) {
      String code = oneOf(VARIANT_CODES, variant.variantCode(), "话术变体码");
      var current = byCode.get(code);
      if (current == null) {
        insertVariant(templateId, variant, moment);
        continue;
      }
      try {
        sql.execute(
            "update crm_review_message_variant set subject=?,body=?,weight=?,status=?,"
                + " updated_at=?::timestamptz where tenant_id=? and id=?",
            nz(variant.subject()),
            nz(variant.body()),
            variant.weight(),
            oneOf(TEMPLATE_STATUSES, variant.status(), "变体状态"),
            moment,
            currentTenant(),
            current.id());
      } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "话术变体更新被数据库层拒绝：" + rootMessage(e), e);
      }
    }
    // 模板里被删掉的变体：有归属历史的删不掉（DB 触发器会拒），如实告诉运营。
    for (var variant : existing) {
      if (!seen.containsKey(variant.variantCode())) {
        try {
          sql.execute(
              "delete from crm_review_message_variant where tenant_id=? and id=?",
              currentTenant(),
              variant.id());
        } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "话术变体 "
                  + variant.variantCode()
                  + " 已被生效策略落地引用，不能删：请改为把它置为 PAUSED（"
                  + rootMessage(e)
                  + "）",
              e);
        }
      }
    }
    return template(templateId);
  }

  /**
   * 删除话术模板（连同它下面的变体）。
   *
   * <p>变体是**独立行**，所以删模板时先删变体 —— 否则「模板下面还挂着变体」
   * 会让模板永远删不掉，界面上就成了一个删不掉又不能改的僵尸配置。
   * 被生效策略落地引用过的变体仍然删不掉（DB 触发器会拒），如实告诉运营。
   */
  @Transactional
  public void deleteTemplate(UUID templateId) {
    template(templateId);
    try {
      sql.execute(
          "delete from crm_review_message_variant where tenant_id=? and template_id=?",
          currentTenant(),
          templateId);
      sql.execute(
          "delete from crm_review_message_template where tenant_id=? and id=?",
          currentTenant(),
          templateId);
    } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "话术模板删除被数据库层拒绝：" + rootMessage(e), e);
    }
  }

  // ================================================================ 节假日

  /** 节假日列表（真分页 + 真筛选）。 */
  public List<CrmReviewHolidayRow> listHolidays(
      String year, String region, String kind, int page, int size) {
    int limit = size <= 0 ? 50 : Math.min(size, 200);
    var rows =
        sql.fetch(
            "select id,holiday_date,holiday_end_date,holiday_name,region,kind,note,created_at,updated_at"
                + " from crm_review_holiday where tenant_id = ?"
                + (blankToNull(year) == null ? "" : " and extract(year from holiday_date) = ?::int")
                + (blankToNull(region) == null ? "" : " and region = ?")
                + (blankToNull(kind) == null ? "" : " and kind = ?")
                + " order by holiday_date, region, id"
                + " limit ? offset ?",
            holidayBindings(year, region, kind, limit, Math.max(page, 0) * limit));
    return rows.map(ReviewRuleService::holiday);
  }

  /** 节假日总数。 */
  public long countHolidays(String year, String region, String kind) {
    return sql.fetchOne(
            "select count(*) from crm_review_holiday where tenant_id = ?"
                + (blankToNull(year) == null ? "" : " and extract(year from holiday_date) = ?::int")
                + (blankToNull(region) == null ? "" : " and region = ?")
                + (blankToNull(kind) == null ? "" : " and kind = ?"),
            holidayBindings(year, region, kind))
        .get(0, Long.class);
  }

  /** 新建节假日。 */
  @Transactional
  public CrmReviewHolidayRow createHoliday(CrmReviewHolidayInput input, UUID actorId) {
    LocalDate date = parseDate(input.holidayDate(), "节假日开始日期");
    LocalDate end = blankToNull(input.holidayEndDate()) == null ? null : parseDate(input.holidayEndDate(), "节假日结束日期");
    if (end != null && end.isBefore(date)) {
      throw bad("节假日的结束日期「" + end + "」早于开始日期「" + date + "」。");
    }
    String kind = oneOf(HOLIDAY_KINDS, input.kind(), "节假日类型");
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    try {
      sql.execute(
          "insert into crm_review_holiday"
              + "(id,tenant_id,holiday_date,holiday_end_date,holiday_name,region,kind,note,"
              + " created_by,created_at,updated_at)"
              + " values (?,?,?::date,?::date,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          date,
          end,
          input.holidayName().trim(),
          nz(input.region()).trim(),
          kind,
          nz(input.note()),
          actorId,
          moment,
          moment);
    } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "节假日写入被数据库层拒绝：" + rootMessage(e), e);
    }
    return holidayById(id);
  }

  /** 更新节假日。 */
  @Transactional
  public CrmReviewHolidayRow updateHoliday(UUID holidayId, CrmReviewHolidayInput input, UUID actorId) {
    holidayById(holidayId);
    LocalDate date = parseDate(input.holidayDate(), "节假日开始日期");
    LocalDate end =
        blankToNull(input.holidayEndDate()) == null
            ? null
            : parseDate(input.holidayEndDate(), "节假日结束日期");
    if (end != null && end.isBefore(date)) {
      throw bad("节假日的结束日期「" + end + "」早于开始日期「" + date + "」。");
    }
    String kind = oneOf(HOLIDAY_KINDS, input.kind(), "节假日类型");
    try {
      sql.execute(
          "update crm_review_holiday set holiday_date=?::date,holiday_end_date=?::date,"
              + " holiday_name=?,region=?,kind=?,note=?,updated_at=?::timestamptz"
              + " where tenant_id=? and id=?",
          date,
          end,
          input.holidayName().trim(),
          nz(input.region()).trim(),
          kind,
          nz(input.note()),
          now(),
          currentTenant(),
          holidayId);
    } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "节假日更新被数据库层拒绝：" + rootMessage(e), e);
    }
    return holidayById(holidayId);
  }

  /** 删除节假日（节假日不被任何表引用，删了不影响已落库的排期快照）。 */
  @Transactional
  public void deleteHoliday(UUID holidayId) {
    holidayById(holidayId);
    sql.execute("delete from crm_review_holiday where tenant_id=? and id=?", currentTenant(), holidayId);
  }

  // ================================================================ 店铺目录

  /** 店铺目录（来自上游 channel 的真实清单）。上游读不到一律 503。 */
  public List<CrmReviewRuleShopView> shops(String bearer) {
    List<CrmReviewRuleShopView> out = new ArrayList<>();
    for (var shop : port.shops(bearer)) {
      out.add(
          new CrmReviewRuleShopView(
              shop.id(),
              nz(shop.code()),
              nz(shop.name()),
              nz(shop.platformCode()),
              nz(shop.platformName()),
              nz(shop.operatingMode()),
              nz(shop.status())));
    }
    return out;
  }

  // ================================================================ 生效策略

  /**
   * 可被应用策略的索评请求（上游 15.2.2.1 的索评任务，**同库只读**）。
   *
   * <p>它们的状态原样带出来 —— 被频次规则拒绝的请求也列出来并标明状态，
   * 因为「为什么这条不能分配话术」是运营需要看到的答案，
   * 而不是在列表里悄悄消失一条。
   */
  public CrmReviewRuleCandidatePage candidates(UUID shopId, String status, int page, int size) {
    int limit = size <= 0 ? 50 : Math.min(size, 200);
    long total =
        sql.fetchOne(
                "select count(*) from crm_review_request r"
                    + " where r.tenant_id = ?"
                    + (shopId == null ? "" : " and r.shop_id = ?")
                    + (blankToNull(status) == null ? "" : " and r.status = ?"),
                candidateBindings(shopId, status))
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select r.id,r.request_no,r.shop_id,r.shop_name,r.buyer_ref,r.sales_order_no,"
                + " r.platform_code,r.delivered_at,r.status,r.reject_reason"
                + " from crm_review_request r"
                + " where r.tenant_id = ?"
                + (shopId == null ? "" : " and r.shop_id = ?")
                + (blankToNull(status) == null ? "" : " and r.status = ?")
                + " order by r.created_at desc, r.id"
                + " limit ? offset ?",
            candidateBindings(shopId, status, limit, Math.max(page, 0) * limit));
    List<CrmReviewRuleCandidate> items = new ArrayList<>();
    for (Record r : rows) {
      items.add(
          new CrmReviewRuleCandidate(
              r.get("id", UUID.class),
              nz(r.get("request_no", String.class)),
              r.get("shop_id", UUID.class),
              nz(r.get("shop_name", String.class)),
              nz(r.get("buyer_ref", String.class)),
              nz(r.get("sales_order_no", String.class)),
              nz(r.get("platform_code", String.class)),
              str(r.get("delivered_at", OffsetDateTime.class)),
              nz(r.get("status", String.class)),
              nz(r.get("reject_reason", String.class))));
    }
    return new CrmReviewRuleCandidatePage(
        items, Math.max(page, 0), limit, total, (int) Math.ceil(total / (double) limit));
  }

  /**
   * 生效策略（蓝图输出句的第一项）。
   *
   * <p>规则**由服务层解析**得出，运营不指定规则 —— 指定规则就绕过了「生效策略」这件事本身。
   *
   * <p>命中口径：范围越具体越优先（店铺 + 物流方式 &gt; 店铺 &gt; 物流方式 &gt; 全局），
   * 同一范围按 priority 升序。DB 层另有唯一索引保证同一范围同一优先级不会有两条 ACTIVE 规则。
   *
   * @param at 按哪个时刻判定（默认为现在）
   * @param seedKey A/B 分桶种子；为空时只给权重分布，不给具体变体
   */
  public CrmReviewEffectivePolicy effective(
      UUID shopId, String logisticsMode, String at, String seedKey) {
    String mode = blankToNull(logisticsMode) == null ? "ANY" : logisticsMode.trim();
    if (!LOGISTICS_MODES.contains(mode)) {
      throw bad("物流方式「" + mode + "」不在允许取值里：" + String.join(" / ", LOGISTICS_MODES));
    }
    OffsetDateTime resolvedAt = blankToNull(at) == null ? now() : parseMoment(at, "判定时刻");
    RuleMatch match = resolveRule(shopId, mode, resolvedAt);
    if (match == null) {
      return new CrmReviewEffectivePolicy(
          null,
          "",
          "",
          shopId,
          "",
          mode,
          null,
          null,
          "",
          false,
          List.of(),
          null,
          "",
          "",
          0,
          "",
          false,
          "没有命中任何规则",
          resolvedAt.toString(),
          "本租户没有匹配的生效规则：适用店铺（"
              + (shopId == null ? "全部店铺" : shopId)
              + "）× 物流方式（"
              + mode
              + "）下没有 ACTIVE 规则。先去配一条规则，否则索评没有时点可依。");
    }
    return buildPolicy(match, resolvedAt, seedKey, null);
  }

  // ================================================================ 应用策略（生效策略的落地）

  /**
   * 把当前生效策略应用到一条索评请求上。
   *
   * <p>这是本模块的**产出事实**：哪条索评请求用了哪条规则、哪个话术变体、排期到什么时候。
   * 效果对比就是按这层归属把 15.2.2.1 的评价回收拆到 A / B 两臂上 ——
   * 没有这层归属，回收率与平均星级就无从按变体拆开。
   */
  @Transactional
  public CrmReviewAssignmentRow applyPolicy(
      CrmReviewAssignmentInput input, UUID actorId, String bearer) {
    String mode = oneOf(LOGISTICS_MODES, input.logisticsMode(), "物流方式");
    if ("ANY".equals(mode)) {
      throw bad("应用策略时必须给出**具体**的物流方式（自发货 / 平台仓）："
          + "「不限」答不了「这一单该按哪条规则排期」。");
    }
    ReviewRequestView request = requireRequest(input.reviewRequestId());
    if ("REJECTED".equals(request.status())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "索评请求 "
              + request.requestNo()
              + " 被频次规则**拒绝**了（"
              + nz(request.rejectReason())
              + "），没有真正发出去过，给它分配话术变体等于伪造一次触达。");
    }
    OffsetDateTime base = request.deliveredAt() == null ? now() : request.deliveredAt();
    RuleMatch match = resolveRule(request.shopId(), mode, base);
    if (match == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "没有命中任何生效规则：店铺「"
              + nz(request.shopName())
              + "」× 物流方式「"
              + mode
              + "」下没有 ACTIVE 规则。先去配一条规则，再谈给这次索评分话术。");
    }
    CrmReviewEffectivePolicy policy = buildPolicy(match, base, assignmentSeed(request), request);
    OffsetDateTime scheduledAt = parseMoment(policy.scheduledAt(), "排期时刻");
    UUID id = UUID.randomUUID();
    OffsetDateTime moment = now();
    try {
      sql.execute(
          "insert into crm_review_rule_assignment"
              + "(id,tenant_id,rule_id,template_id,variant_id,review_request_id,shop_id,shop_name,"
              + " logistics_mode,buyer_ref,sales_order_no,delivered_at,delay_hours,scheduled_at,"
              + " shifted_days,holiday_name,note,created_by,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?::timestamptz,?,?,?,?,?::timestamptz,?::timestamptz)",
          id,
          currentTenant(),
          match.id(),
          match.templateId(),
          policy.pickedVariantId(),
          request.id(),
          request.shopId(),
          nz(request.shopName()),
          mode,
          nz(request.buyerRef()),
          nz(request.salesOrderNo()),
          request.deliveredAt(),
          match.delayHours(),
          scheduledAt,
          policy.shiftedDays(),
          nz(policy.holidayName()),
          nz(input.note()),
          actorId,
          moment,
          moment);
    } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "生效策略落地被数据库层拒绝："
              + rootMessage(e)
              + "（同一条索评请求只能被分配一次 —— 重复分配会让「哪个变体带来了这次回收」有两个答案）",
          e);
    }
    return assignmentById(id);
  }

  /** 落地记录列表（真分页 + 真筛选）。 */
  public List<CrmReviewAssignmentRow> listAssignments(
      UUID shopId, UUID ruleId, UUID variantId, int page, int size) {
    int limit = size <= 0 ? 50 : Math.min(size, 200);
    var rows =
        sql.fetch(
            assignmentColumns()
                + " where a.tenant_id = ?"
                + (shopId == null ? "" : " and a.shop_id = ?")
                + (ruleId == null ? "" : " and a.rule_id = ?")
                + (variantId == null ? "" : " and a.variant_id = ?")
                + " order by a.created_at desc, a.id"
                + " limit ? offset ?",
            assignmentBindings(shopId, ruleId, variantId, limit, Math.max(page, 0) * limit));
    return rows.map(ReviewRuleService::assignment);
  }

  /** 落地记录总数。 */
  public long countAssignments(UUID shopId, UUID ruleId, UUID variantId) {
    return sql.fetchOne(
            "select count(*) from crm_review_rule_assignment a"
                + " where a.tenant_id = ?"
                + (shopId == null ? "" : " and a.shop_id = ?")
                + (ruleId == null ? "" : " and a.rule_id = ?")
                + (variantId == null ? "" : " and a.variant_id = ?"),
            assignmentBindings(shopId, ruleId, variantId))
        .get(0, Long.class);
  }

  /** 单条落地记录。 */
  public CrmReviewAssignmentRow assignment(UUID assignmentId) {
    var row =
        sql.fetchOne(
            assignmentColumns() + " where a.tenant_id = ? and a.id = ?",
            currentTenant(),
            assignmentId);
    if (row == null) throw notFound("生效策略落地记录不存在");
    return assignment(row);
  }

  // ================================================================ 效果对比

  /**
   * 效果对比（蓝图输出句的第二项）：按话术变体把 15.2.2.1 的索评与评价回收拆开。
   *
   * <p>口径全部来自<b>真实明细</b>：
   *
   * <ul>
   *   <li>分配数 = 本租户的生效策略落地条数（{@code crm_review_rule_assignment}）；
   *   <li>已发出 = 其中关联的索评请求状态为 {@code SENT} 的条数；
   *   <li>已回收 = 其中关联到评价回收的索评请求条数（{@code crm_review_recovery}）；
   *   <li>回收率 = 已回收 / 已发出。<b>分母为 0 时报 null</b>（界面显示「不计算」）
   *       而不是 0% —— 0% 会被读成「发出去了但一个评价都没回来」，那是两件完全不同的事。
   * </ul>
   *
   * <p>⚠️ 回收数据来自 15.2.2.1 的<b>人工登记</b>回收桶（平台评价同步未交付），
   * 所以这个对比**不能**当作平台侧真实回收率来用。
   */
  public CrmReviewEffectComparison effectComparison(Integer days, UUID shopId, UUID ruleId) {
    int window = days == null || days <= 0 ? DEFAULT_EFFECT_DAYS : Math.min(days, 365);
    OffsetDateTime from = now().minus(Duration.ofDays(window));
    OffsetDateTime to = now();

    // ① 每个变体的分配数与已发出数。
    Map<UUID, long[]> byVariant = new HashMap<>();
    var variantCounts =
        sql.fetch(
            "select a.variant_id as variant_id, count(*) as assigned,"
                + " count(*) filter (where req.status = 'SENT') as sent"
                + " from crm_review_rule_assignment a"
                + " left join crm_review_request req"
                + "   on req.tenant_id = a.tenant_id and req.id = a.review_request_id"
                + " where a.tenant_id = ? and a.created_at >= ?::timestamptz and a.created_at < ?::timestamptz"
                + (shopId == null ? "" : " and a.shop_id = ?")
                + (ruleId == null ? "" : " and a.rule_id = ?")
                + " and a.variant_id is not null"
                + " group by a.variant_id",
            effectBindings(from, to, shopId, ruleId));
    for (Record r : variantCounts) {
      byVariant.put(
          r.get("variant_id", UUID.class),
          new long[] {
            nz(r.get("assigned", Long.class)), nz(r.get("sent", Long.class)), 0L, 0L
          });
    }

    // ② 每个变体的回收数与平均星级（独立查询，避免 join 放大计数）。
    var variantRecoveries =
        sql.fetch(
            "select a.variant_id as variant_id, count(distinct a.review_request_id) as recovered,"
                + " avg(rec.rating) as average"
                + " from crm_review_rule_assignment a"
                + " join crm_review_recovery rec"
                + "   on rec.tenant_id = a.tenant_id and rec.request_id = a.review_request_id"
                + " where a.tenant_id = ? and a.created_at >= ?::timestamptz and a.created_at < ?::timestamptz"
                + (shopId == null ? "" : " and a.shop_id = ?")
                + (ruleId == null ? "" : " and a.rule_id = ?")
                + " and a.variant_id is not null"
                + " group by a.variant_id",
            effectBindings(from, to, shopId, ruleId));
    for (Record r : variantRecoveries) {
      var key = r.get("variant_id", UUID.class);
      var counters = byVariant.get(key);
      if (counters == null) {
        counters = new long[] {0L, 0L, 0L, 0L};
        byVariant.put(key, counters);
      }
      counters[2] = nz(r.get("recovered", Long.class));
      var average = r.get("average", BigDecimal.class);
      counters[3] = average == null ? 0L : average.setScale(2, RoundingMode.HALF_UP).longValue();
    }

    // ③ 变体清单：本租户规则引用到的模板的全部变体 + 本窗口内被落地引用过的变体。
    List<CrmReviewVariantRow> variants = comparableVariants(shopId, ruleId);
    List<CrmReviewEffectVariantRow> variantRows = new ArrayList<>();
    for (var variant : variants) {
      var counters = byVariant.get(variant.id());
      long assigned = counters == null ? 0L : counters[0];
      long sent = counters == null ? 0L : counters[1];
      long recovered = counters == null ? 0L : counters[2];
      BigDecimal average =
          counters == null || counters[3] == 0L
              ? null
              : BigDecimal.valueOf(counters[3]).setScale(2, RoundingMode.HALF_UP);
      BigDecimal rate = recoveryRate(recovered, sent);
      variantRows.add(
          new CrmReviewEffectVariantRow(
              variant.id(),
              variant.variantCode(),
              variant.templateId(),
              templateNameOf(variant.templateId()),
              variant.subject(),
              variant.weight(),
              assigned,
              sent,
              recovered,
              rate,
              average,
              sent <= 0
                  ? "本变体在时间窗内**没有已发出的索评**，回收率**不计算**（报空而不是报 0%）。"
                  : "回收率 = 已有回收的索评数 / 已发出的索评数；回收数据来自 15.2.2.1 的**人工登记**回收桶。"));
    }

    // ④ 按规则的汇总。
    Map<UUID, long[]> ruleCounters = new HashMap<>();
    var ruleCounts =
        sql.fetch(
            "select a.rule_id as rule_id, count(*) as assigned,"
                + " count(*) filter (where req.status = 'SENT') as sent"
                + " from crm_review_rule_assignment a"
                + " left join crm_review_request req"
                + "   on req.tenant_id = a.tenant_id and req.id = a.review_request_id"
                + " where a.tenant_id = ? and a.created_at >= ?::timestamptz and a.created_at < ?::timestamptz"
                + (shopId == null ? "" : " and a.shop_id = ?")
                + (ruleId == null ? "" : " and a.rule_id = ?")
                + " group by a.rule_id",
            effectBindings(from, to, shopId, ruleId));
    for (Record r : ruleCounts) {
      ruleCounters.put(
          r.get("rule_id", UUID.class),
          new long[] {nz(r.get("assigned", Long.class)), nz(r.get("sent", Long.class)), 0L, 0L});
    }
    var ruleRecoveries =
        sql.fetch(
            "select a.rule_id as rule_id, count(distinct a.review_request_id) as recovered,"
                + " avg(rec.rating) as average"
                + " from crm_review_rule_assignment a"
                + " join crm_review_recovery rec"
                + "   on rec.tenant_id = a.tenant_id and rec.request_id = a.review_request_id"
                + " where a.tenant_id = ? and a.created_at >= ?::timestamptz and a.created_at < ?::timestamptz"
                + (shopId == null ? "" : " and a.shop_id = ?")
                + (ruleId == null ? "" : " and a.rule_id = ?")
                + " group by a.rule_id",
            effectBindings(from, to, shopId, ruleId));
    for (Record r : ruleRecoveries) {
      var key = r.get("rule_id", UUID.class);
      var counters = ruleCounters.get(key);
      if (counters == null) {
        counters = new long[] {0L, 0L, 0L, 0L};
        ruleCounters.put(key, counters);
      }
      counters[2] = nz(r.get("recovered", Long.class));
      var average = r.get("average", BigDecimal.class);
      counters[3] = average == null ? 0L : average.setScale(2, RoundingMode.HALF_UP).longValue();
    }

    List<CrmReviewEffectRuleRow> byRule = new ArrayList<>();
    for (var entry : ruleCounters.entrySet()) {
      var row = sql.fetchOne(ruleColumns() + " where r.tenant_id = ? and r.id = ?", currentTenant(), entry.getKey());
      if (row == null) continue;
      var counters = entry.getValue();
      long recovered = counters[2];
      long sent = counters[1];
      BigDecimal average =
          counters[3] == 0L ? null : BigDecimal.valueOf(counters[3]).setScale(2, RoundingMode.HALF_UP);
      byRule.add(
          new CrmReviewEffectRuleRow(
              entry.getKey(),
              nz(row.get("rule_no", String.class)),
              nz(row.get("rule_name", String.class)),
              counters[0],
              sent,
              recovered,
              recoveryRate(recovered, sent),
              average));
    }
    byRule.sort((a, b) -> Long.compare(b.assigned(), a.assigned()));

    long total =
        sql.fetchOne(
                "select count(*) from crm_review_rule_assignment a"
                    + " where a.tenant_id = ? and a.created_at >= ?::timestamptz and a.created_at < ?::timestamptz"
                    + (shopId == null ? "" : " and a.shop_id = ?")
                    + (ruleId == null ? "" : " and a.rule_id = ?"),
                effectBindings(from, to, shopId, ruleId))
            .get(0, Long.class);

    return new CrmReviewEffectComparison(
        window,
        from.toString(),
        to.toString(),
        total,
        variantRows,
        byRule,
        total == 0
            ? "本时间窗内**没有生效策略落地记录**：还没有把策略应用到任何索评请求上，"
                + "所以没有可对比的样本。"
            : "回收率 = 已有回收的索评数 / 已发出的索评数；分母为 0 时报空而不是 0%。"
                + "⚠️ 回收数据来自 15.2.2.1 的**人工登记**回收桶（平台评价同步未交付），"
                + "这个对比**不能**当作平台侧真实回收率。");
  }

  // ================================================================ 内部

  /** 命中的一条规则（解析结果）。 */
  private record RuleMatch(
      UUID id,
      String ruleNo,
      String ruleName,
      UUID shopId,
      String shopName,
      String logisticsMode,
      BigDecimal delayHours,
      UUID templateId,
      String templateName,
      boolean abTestEnabled,
      boolean avoidHolidays,
      String holidayRegion,
      int holidayShiftDays,
      ZoneId zone,
      int priority,
      String matchedScope) {}

  /** 15.2.2.1 的一条索评请求（**只读**）。 */
  private record ReviewRequestView(
      UUID id,
      String requestNo,
      UUID shopId,
      String shopName,
      String buyerRef,
      String salesOrderNo,
      OffsetDateTime deliveredAt,
      String status,
      String rejectReason) {}

  /** 命中节假日的顺延结果。 */
  private record ShiftResult(OffsetDateTime scheduledAt, int shiftedDays, String holidayName, boolean avoided) {}

  /**
   * 解析生效规则。
   *
   * <p>命中口径：范围越具体越优先 → 同范围 priority 升序 → created_at 升序 → id。
   * DB 层保证「同一范围同一优先级不会有两条 ACTIVE 规则」，所以这里是<b>确定的</b>。
   */
  private RuleMatch resolveRule(UUID shopId, String logisticsMode, OffsetDateTime at) {
    var values = new ArrayList<Object>();
    values.add(currentTenant());
    values.add(at);
    values.add(at);
    if (shopId != null) {
      values.add(shopId);
    }
    values.add(logisticsMode);
    var row =
        sql.fetchOne(
            "select r.id,r.rule_no,r.rule_name,r.shop_id,r.shop_name,r.logistics_mode,"
                + " r.delay_hours,r.template_id,r.ab_test_enabled,r.avoid_holidays,r.holiday_region,"
                + " r.holiday_shift_days,r.timezone,r.priority,"
                + " (select max(t.template_name) from crm_review_message_template t"
                + "   where t.tenant_id = r.tenant_id and t.id = r.template_id) as template_name"
                + " from crm_review_rule r"
                + " where r.tenant_id = ? and r.status = 'ACTIVE'"
                + " and (r.effective_from is null or r.effective_from <= ?::timestamptz)"
                + " and (r.effective_to is null or r.effective_to > ?::timestamptz)"
                + (shopId == null ? " and r.shop_id is null" : " and (r.shop_id is null or r.shop_id = ?)")
                + " and (r.logistics_mode = 'ANY' or r.logistics_mode = ?)"
                + " order by case when r.shop_id is not null and r.logistics_mode <> 'ANY' then 3"
                + "          when r.shop_id is not null then 2"
                + "          when r.logistics_mode <> 'ANY' then 1"
                + "          else 0 end desc,"
                + "   r.priority asc, r.created_at asc, r.id"
                + " limit 1",
            values.toArray());
    if (row == null) return null;
    String scope =
        row.get("shop_id", UUID.class) != null
            ? ("ANY".equals(row.get("logistics_mode", String.class))
                ? "店铺"
                : "店铺 + 物流方式")
            : ("ANY".equals(row.get("logistics_mode", String.class)) ? "全局" : "物流方式");
    return new RuleMatch(
        row.get("id", UUID.class),
        nz(row.get("rule_no", String.class)),
        nz(row.get("rule_name", String.class)),
        row.get("shop_id", UUID.class),
        nz(row.get("shop_name", String.class)),
        nz(row.get("logistics_mode", String.class)),
        row.get("delay_hours", BigDecimal.class),
        row.get("template_id", UUID.class),
        nz(row.get("template_name", String.class)),
        Boolean.TRUE.equals(row.get("ab_test_enabled", Boolean.class)),
        Boolean.TRUE.equals(row.get("avoid_holidays", Boolean.class)),
        nz(row.get("holiday_region", String.class)),
        row.get("holiday_shift_days", Integer.class) == null ? 1 : row.get("holiday_shift_days", Integer.class),
        zoneOf(row.get("timezone", String.class)),
        row.get("priority", Integer.class) == null ? 100 : row.get("priority", Integer.class),
        scope);
  }

  /** 把命中的规则展开成一份「生效策略」。 */
  private CrmReviewEffectivePolicy buildPolicy(
      RuleMatch match, OffsetDateTime base, String seedKey, ReviewRequestView request) {
    OffsetDateTime baseScheduled = base.plus(hours(match.delayHours()));
    ShiftResult shift =
        match.avoidHolidays()
            ? avoidHolidays(baseScheduled, match.holidayShiftDays(), match.holidayRegion(), match.zone())
            : new ShiftResult(baseScheduled, 0, "", false);

    List<CrmReviewVariantRow> variants =
        match.templateId() == null ? List.of() : activeVariantsOf(match.templateId());
    CrmReviewVariantRow picked = null;
    if (!variants.isEmpty()) {
      picked =
          match.abTestEnabled()
              ? pickVariant(variants, seedKey)
              : defaultVariant(variants);
    }

    String note =
        match.templateId() == null
            ? "这条规则没有绑定话术模板：只定「什么时候问」，不定「怎么问」。"
            : variants.isEmpty()
                ? "规则绑定了话术模板，但模板下**没有启用中的变体**：话术发不出去，请先给模板加一个启用的变体。"
                : match.abTestEnabled()
                    ? "A/B 已开启：变体按权重分桶，同一个种子永远落到同一个臂（可复算）。"
                    : "A/B 未开启：使用默认臂（权重最高的变体；同权重按变体码升序）。";

    return new CrmReviewEffectivePolicy(
        match.id(),
        match.ruleNo(),
        match.ruleName(),
        match.shopId(),
        match.shopName(),
        match.logisticsMode(),
        match.delayHours(),
        match.templateId(),
        match.templateName(),
        match.abTestEnabled(),
        variants,
        picked == null ? null : picked.id(),
        baseScheduled.toString(),
        shift.scheduledAt().toString(),
        shift.shiftedDays(),
        shift.holidayName(),
        shift.avoided(),
        match.matchedScope(),
        base.toString(),
        note
            + (shift.avoided()
                ? " 已避开节假日「" + shift.holidayName() + "」，排期顺延 " + shift.shiftedDays() + " 天。"
                : "")
            + (request == null ? "" : " 目标索评请求 " + request.requestNo() + "。"));
  }

  /**
   * 避开节假日：命中时把排期推到**第一个不是节假日的日子**。
   *
   * <p>候选日 = 排期日 + holidayShiftDays；然后往后逐日找，直到不是节假日为止
   * （最多找 {@value #MAX_HOLIDAY_LOOKAHEAD} 天，防止病态日历把排期推到无穷远）。
   * holidayShiftDays = 0 时候选日就是排期日本身，而排期日命中节假日 ⇒ 自然落到下一天。
   *
   * <p>⚠️ 日界按规则的 timezone 换算（默认东八区）：用 UTC 日界会把「东八区 10-02 07:00」
   * 算成 10-01，节假日就错开了一天。
   */
  private ShiftResult avoidHolidays(
      OffsetDateTime scheduledAt, int shiftDays, String region, ZoneId zone) {
    LocalDate date = scheduledAt.atZoneSameInstant(zone).toLocalDate();
    var hit = findHoliday(date, region);
    if (hit == null) return new ShiftResult(scheduledAt, 0, "", false);
    LocalDate target = date.plusDays(Math.max(shiftDays, 0));
    for (int i = 0; i < MAX_HOLIDAY_LOOKAHEAD; i++) {
      if (findHoliday(target, region) == null) break;
      target = target.plusDays(1);
    }
    LocalTime timeOfDay = scheduledAt.atZoneSameInstant(zone).toLocalTime();
    OffsetDateTime shifted = ZonedDateTime.of(target, timeOfDay, zone).toOffsetDateTime();
    return new ShiftResult(
        shifted, (int) ChronoUnit.DAYS.between(date, target), nz(hit), true);
  }

  /** 命中一个覆盖该日期的节假日（区间为空 = 单日；区域为空 = 全部区域）。 */
  private String findHoliday(LocalDate date, String region) {
    var row =
        sql.fetchOne(
            "select holiday_name from crm_review_holiday"
                + " where tenant_id = ? and holiday_date <= ?::date"
                + " and coalesce(holiday_end_date, holiday_date) >= ?::date"
                + " and (region = '' or region = ?)"
                + " order by holiday_date limit 1",
            currentTenant(),
            date,
            date,
            nz(region));
    return row == null ? null : row.get(0, String.class);
  }

  /**
   * A/B 分桶：按权重把种子落到 0..9999 的桶里。
   *
   * <p>用 SHA-256 而不是 {@code String.hashCode()}：后者在 JVM 之间是稳定的，
   * 但分布很差（短前缀高度相关），会让某一臂几乎拿不到样本。
   */
  private static CrmReviewVariantRow pickVariant(List<CrmReviewVariantRow> variants, String seedKey) {
    if (variants.isEmpty()) return null;
    if (seedKey == null || seedKey.isBlank()) return null;
    long bucket = bucketOf(seedKey);
    BigDecimal cumulative = BigDecimal.ZERO;
    for (var variant : variants) {
      cumulative = cumulative.add(variant.weight().multiply(BigDecimal.valueOf(BUCKET_COUNT / 100)));
      if (bucket < cumulative.longValue()) return variant;
    }
    return variants.get(variants.size() - 1);
  }

  /** 默认臂：权重最高的变体（同权重按变体码升序）。 */
  private static CrmReviewVariantRow defaultVariant(List<CrmReviewVariantRow> variants) {
    CrmReviewVariantRow best = null;
    for (var variant : variants) {
      if (best == null
          || variant.weight().compareTo(best.weight()) > 0
          || (variant.weight().compareTo(best.weight()) == 0
              && variant.variantCode().compareTo(best.variantCode()) < 0)) {
        best = variant;
      }
    }
    return best;
  }

  /** 种子 → 桶号（0..9999）。 */
  private static long bucketOf(String seed) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
      long value = 0;
      for (int i = 0; i < 8; i++) {
        value = (value << 8) | (hash[i] & 0xffL);
      }
      return Long.remainderUnsigned(value, BUCKET_COUNT);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  /** 分桶种子：买家标识 + 订单号 + 请求 id（稳定、可复算）。 */
  private static String assignmentSeed(ReviewRequestView request) {
    return nz(request.buyerRef()) + "|" + nz(request.salesOrderNo()) + "|" + request.id();
  }

  /** 可对比的变体清单：规则引用到的模板的全部变体 + 窗口内被落地引用过的变体。 */
  private List<CrmReviewVariantRow> comparableVariants(UUID shopId, UUID ruleId) {
    Map<UUID, CrmReviewVariantRow> out = new LinkedHashMap<>();
    var templateIds =
        sql.fetch(
            "select distinct template_id from crm_review_rule"
                + " where tenant_id = ? and template_id is not null"
                + (shopId == null ? "" : " and (shop_id is null or shop_id = ?)")
                + (ruleId == null ? "" : " and id = ?"),
            effectTemplateBindings(shopId, ruleId));
    for (Record r : templateIds) {
      for (var variant : variantsOf(r.get("template_id", UUID.class))) {
        out.put(variant.id(), variant);
      }
    }
    var assigned =
        sql.fetch(
            "select distinct a.variant_id as variant_id from crm_review_rule_assignment a"
                + " join crm_review_request req"
                + "   on req.tenant_id = a.tenant_id and req.id = a.review_request_id"
                + " where a.tenant_id = ? and a.created_at >= ?::timestamptz and a.created_at < ?::timestamptz"
                + (shopId == null ? "" : " and a.shop_id = ?")
                + (ruleId == null ? "" : " and a.rule_id = ?")
                + " and a.variant_id is not null",
            effectBindings(now().minus(Duration.ofDays(DEFAULT_EFFECT_DAYS)), now(), shopId, ruleId));
    for (Record r : assigned) {
      var variant = variantById(r.get("variant_id", UUID.class));
      if (variant != null) out.put(variant.id(), variant);
    }
    return new ArrayList<>(out.values());
  }

  /** 规则引用的模板名补齐（效果对比按变体展示时用）。 */
  private String templateNameOf(UUID templateId) {
    if (templateId == null) return "";
    var row =
        sql.fetchOne(
            "select template_name from crm_review_message_template where tenant_id=? and id=?",
            currentTenant(),
            templateId);
    return row == null ? "" : nz(row.get(0, String.class));
  }

  /** 回收率：分母为 0 时报 null（界面显示「不计算」），不是 0%。 */
  private static BigDecimal recoveryRate(long recovered, long sent) {
    if (sent <= 0) return null;
    return BigDecimal.valueOf(recovered)
        .divide(BigDecimal.valueOf(sent), 4, RoundingMode.HALF_UP);
  }

  private void insertVariant(
      UUID templateId, ReviewRuleModels.CrmReviewTemplateVariantInput variant, OffsetDateTime moment) {
    try {
      sql.execute(
          "insert into crm_review_message_variant"
              + "(id,tenant_id,template_id,variant_code,subject,body,weight,status,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          templateId,
          oneOf(VARIANT_CODES, variant.variantCode(), "话术变体码"),
          nz(variant.subject()),
          nz(variant.body()),
          variant.weight(),
          oneOf(TEMPLATE_STATUSES, variant.status(), "变体状态"),
          moment,
          moment);
    } catch (org.springframework.dao.DataAccessException | org.jooq.exception.DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "话术变体写入被数据库层拒绝：" + rootMessage(e), e);
    }
  }

  /** 模板下的全部变体（按变体码升序）。 */
  private List<CrmReviewVariantRow> variantsOf(UUID templateId) {
    return sql.fetch(
            "select id,template_id,variant_code,subject,body,weight,status,created_at,updated_at"
                + " from crm_review_message_variant where tenant_id=? and template_id=?"
                + " order by variant_code",
            currentTenant(),
            templateId)
        .map(ReviewRuleService::variant);
  }

  /** 模板下**启用中**的变体（分桶只用这些）。 */
  private List<CrmReviewVariantRow> activeVariantsOf(UUID templateId) {
    return sql.fetch(
            "select id,template_id,variant_code,subject,body,weight,status,created_at,updated_at"
                + " from crm_review_message_variant"
                + " where tenant_id=? and template_id=? and status='ACTIVE'"
                + " order by variant_code",
            currentTenant(),
            templateId)
        .map(ReviewRuleService::variant);
  }

  private CrmReviewVariantRow variantById(UUID variantId) {
    var row =
        sql.fetchOne(
            "select id,template_id,variant_code,subject,body,weight,status,created_at,updated_at"
                + " from crm_review_message_variant where tenant_id=? and id=?",
            currentTenant(),
            variantId);
    return row == null ? null : variant(row);
  }

  private CrmReviewTemplateRow requireTemplate(UUID templateId) {
    var row =
        sql.fetchOne(
            "select id,template_no,template_name,locale,status,note,created_at,updated_at"
                + " from crm_review_message_template where tenant_id=? and id=?",
            currentTenant(),
            templateId);
    if (row == null) {
      throw bad("话术模板 " + templateId + " 不存在：规则不能引用不存在的模板。");
    }
    return template(row, variantsOf(templateId));
  }

  /** 店铺必须在上游 channel 的真实清单里。 */
  private String requireShopName(UUID shopId, String bearer) {
    for (var shop : shops(bearer)) {
      if (shop.id().equals(shopId)) {
        return shop.name();
      }
    }
    throw bad("店铺 " + shopId + " 不在上游的店铺清单里：请从真实店铺目录里选一个适用店铺。");
  }

  /** 15.2.2.1 的索评请求（**只读**）。 */
  private ReviewRequestView requireRequest(UUID requestId) {
    var row =
        sql.fetchOne(
            "select id,request_no,shop_id,shop_name,buyer_ref,sales_order_no,delivered_at,status,"
                + " reject_reason from crm_review_request where tenant_id=? and id=?",
            currentTenant(),
            requestId);
    if (row == null) throw notFound("索评请求不存在（它可能属于别的租户）");
    return new ReviewRequestView(
        row.get("id", UUID.class),
        nz(row.get("request_no", String.class)),
        row.get("shop_id", UUID.class),
        nz(row.get("shop_name", String.class)),
        nz(row.get("buyer_ref", String.class)),
        nz(row.get("sales_order_no", String.class)),
        row.get("delivered_at", OffsetDateTime.class),
        nz(row.get("status", String.class)),
        nz(row.get("reject_reason", String.class)));
  }

  /** 规则号：RULE-yyyyMMdd-000001（序列单独授权，见 V228 授权段）。 */
  private String nextRuleNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_review_rule_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "RULE-" + day + "-" + String.format("%06d", seq);
  }

  /** 模板号：TPL-yyyyMMdd-000001（序列单独授权，见 V228 授权段）。 */
  private String nextTemplateNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_review_message_template_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "TPL-" + day + "-" + String.format("%06d", seq);
  }

  private CrmReviewHolidayRow holidayById(UUID holidayId) {
    var row =
        sql.fetchOne(
            "select id,holiday_date,holiday_end_date,holiday_name,region,kind,note,created_at,updated_at"
                + " from crm_review_holiday where tenant_id=? and id=?",
            currentTenant(),
            holidayId);
    if (row == null) throw notFound("节假日不存在");
    return holiday(row);
  }

  private CrmReviewAssignmentRow assignmentById(UUID assignmentId) {
    var row =
        sql.fetchOne(
            assignmentColumns() + " where a.tenant_id = ? and a.id = ?",
            currentTenant(),
            assignmentId);
    if (row == null) throw notFound("生效策略落地记录不存在");
    return assignment(row);
  }

  private static String ruleColumns() {
    return "select r.id,r.rule_no,r.rule_name,r.shop_id,r.shop_name,r.logistics_mode,"
        + " r.delay_hours,r.template_id,r.ab_test_enabled,r.avoid_holidays,r.holiday_region,"
        + " r.holiday_shift_days,r.timezone,r.priority,r.status,r.effective_from,r.effective_to,"
        + " r.note,r.created_at,r.updated_at,"
        + " (select max(t.template_name) from crm_review_message_template t"
        + "   where t.tenant_id = r.tenant_id and t.id = r.template_id) as template_name,"
        + " (select count(*) from crm_review_rule_assignment a"
        + "   where a.tenant_id = r.tenant_id and a.rule_id = r.id) as assignment_count"
        + " from crm_review_rule r";
  }

  private static String assignmentColumns() {
    return "select a.id,a.rule_id,a.template_id,a.variant_id,a.review_request_id,a.shop_id,"
        + " a.shop_name,a.logistics_mode,a.buyer_ref,a.sales_order_no,a.delivered_at,a.delay_hours,"
        + " a.scheduled_at,a.shifted_days,a.holiday_name,a.note,a.created_at,a.updated_at,"
        + " (select max(r.rule_no) from crm_review_rule r"
        + "   where r.tenant_id = a.tenant_id and r.id = a.rule_id) as rule_no,"
        + " (select max(r.rule_name) from crm_review_rule r"
        + "   where r.tenant_id = a.tenant_id and r.id = a.rule_id) as rule_name,"
        + " (select max(t.template_name) from crm_review_message_template t"
        + "   where t.tenant_id = a.tenant_id and t.id = a.template_id) as template_name,"
        + " (select max(v.variant_code) from crm_review_message_variant v"
        + "   where v.tenant_id = a.tenant_id and v.id = a.variant_id) as variant_code,"
        + " (select max(q.request_no) from crm_review_request q"
        + "   where q.tenant_id = a.tenant_id and q.id = a.review_request_id) as request_no,"
        + " (select max(q.status) from crm_review_request q"
        + "   where q.tenant_id = a.tenant_id and q.id = a.review_request_id) as request_status"
        + " from crm_review_rule_assignment a";
  }

  private static CrmReviewRuleRow rule(Record r) {
    return new CrmReviewRuleRow(
        r.get("id", UUID.class),
        nz(r.get("rule_no", String.class)),
        nz(r.get("rule_name", String.class)),
        r.get("shop_id", UUID.class),
        nz(r.get("shop_name", String.class)),
        nz(r.get("logistics_mode", String.class)),
        r.get("delay_hours", BigDecimal.class),
        r.get("template_id", UUID.class),
        nz(r.get("template_name", String.class)),
        Boolean.TRUE.equals(r.get("ab_test_enabled", Boolean.class)),
        Boolean.TRUE.equals(r.get("avoid_holidays", Boolean.class)),
        nz(r.get("holiday_region", String.class)),
        r.get("holiday_shift_days", Integer.class),
        nz(r.get("timezone", String.class)),
        r.get("priority", Integer.class),
        nz(r.get("status", String.class)),
        str(r.get("effective_from", OffsetDateTime.class)),
        str(r.get("effective_to", OffsetDateTime.class)),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)),
        nz(r.get("assignment_count", Long.class)));
  }

  private static CrmReviewTemplateRow template(Record r, List<CrmReviewVariantRow> variants) {
    return new CrmReviewTemplateRow(
        r.get("id", UUID.class),
        nz(r.get("template_no", String.class)),
        nz(r.get("template_name", String.class)),
        nz(r.get("locale", String.class)),
        nz(r.get("status", String.class)),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)),
        variants);
  }

  private static CrmReviewVariantRow variant(Record r) {
    return new CrmReviewVariantRow(
        r.get("id", UUID.class),
        r.get("template_id", UUID.class),
        nz(r.get("variant_code", String.class)),
        nz(r.get("subject", String.class)),
        nz(r.get("body", String.class)),
        r.get("weight", BigDecimal.class),
        nz(r.get("status", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmReviewHolidayRow holiday(Record r) {
    return new CrmReviewHolidayRow(
        r.get("id", UUID.class),
        str(r.get("holiday_date", LocalDate.class)),
        str(r.get("holiday_end_date", LocalDate.class)),
        nz(r.get("holiday_name", String.class)),
        nz(r.get("region", String.class)),
        nz(r.get("kind", String.class)),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmReviewAssignmentRow assignment(Record r) {
    return new CrmReviewAssignmentRow(
        r.get("id", UUID.class),
        r.get("rule_id", UUID.class),
        nz(r.get("rule_no", String.class)),
        nz(r.get("rule_name", String.class)),
        r.get("template_id", UUID.class),
        nz(r.get("template_name", String.class)),
        r.get("variant_id", UUID.class),
        nz(r.get("variant_code", String.class)),
        r.get("review_request_id", UUID.class),
        nz(r.get("request_no", String.class)),
        r.get("shop_id", UUID.class),
        nz(r.get("shop_name", String.class)),
        nz(r.get("logistics_mode", String.class)),
        nz(r.get("buyer_ref", String.class)),
        nz(r.get("sales_order_no", String.class)),
        str(r.get("delivered_at", OffsetDateTime.class)),
        r.get("delay_hours", BigDecimal.class),
        str(r.get("scheduled_at", OffsetDateTime.class)),
        r.get("shifted_days", Integer.class),
        nz(r.get("holiday_name", String.class)),
        nz(r.get("request_status", String.class)),
        nz(r.get("note", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private Object[] ruleBindings(
      UUID shopId, String logisticsMode, String status, String keyword, Integer... tail) {
    var values = new ArrayList<Object>();
    values.add(currentTenant());
    if (shopId != null) values.add(shopId);
    String mode = blankToNull(logisticsMode);
    if (mode != null) values.add(mode);
    String st = blankToNull(status);
    if (st != null) values.add(st);
    String key = blankToNull(keyword);
    if (key != null) {
      values.add("%" + key + "%");
      values.add("%" + key + "%");
      values.add("%" + key + "%");
    }
    for (Integer t : tail) values.add(t);
    return values.toArray();
  }

  private Object[] templateBindings(String keyword, String locale, Integer... tail) {
    var values = new ArrayList<Object>();
    values.add(currentTenant());
    String key = blankToNull(keyword);
    if (key != null) values.add("%" + key + "%");
    String loc = blankToNull(locale);
    if (loc != null) values.add(loc);
    for (Integer t : tail) values.add(t);
    return values.toArray();
  }

  private Object[] holidayBindings(String year, String region, String kind, Integer... tail) {
    var values = new ArrayList<Object>();
    values.add(currentTenant());
    String y = blankToNull(year);
    if (y != null) values.add(Integer.valueOf(y));
    String reg = blankToNull(region);
    if (reg != null) values.add(reg);
    String k = blankToNull(kind);
    if (k != null) values.add(k);
    for (Integer t : tail) values.add(t);
    return values.toArray();
  }

  private Object[] assignmentBindings(UUID shopId, UUID ruleId, UUID variantId, Integer... tail) {
    var values = new ArrayList<Object>();
    values.add(currentTenant());
    if (shopId != null) values.add(shopId);
    if (ruleId != null) values.add(ruleId);
    if (variantId != null) values.add(variantId);
    for (Integer t : tail) values.add(t);
    return values.toArray();
  }

  private Object[] candidateBindings(UUID shopId, String status, Integer... tail) {
    var values = new ArrayList<Object>();
    values.add(currentTenant());
    if (shopId != null) values.add(shopId);
    String st = blankToNull(status);
    if (st != null) values.add(st);
    for (Integer t : tail) values.add(t);
    return values.toArray();
  }

  private Object[] effectBindings(
      OffsetDateTime from, OffsetDateTime to, UUID shopId, UUID ruleId) {    var values = new ArrayList<Object>();
    values.add(currentTenant());
    values.add(from);
    values.add(to);
    if (shopId != null) values.add(shopId);
    if (ruleId != null) values.add(ruleId);
    return values.toArray();
  }

  private Object[] effectTemplateBindings(UUID shopId, UUID ruleId) {
    var values = new ArrayList<Object>();
    values.add(currentTenant());
    if (shopId != null) values.add(shopId);
    if (ruleId != null) values.add(ruleId);
    return values.toArray();
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 小时数 → Duration（BigDecimal 小时 → 纳秒，不经 float，保留 6 位小数精度）。 */
  private static Duration hours(BigDecimal value) {
    return Duration.ofNanos(
        value.multiply(BigDecimal.valueOf(3_600_000_000_000L)).longValueExact());
  }

  /** 时区：默认东八区；未知时区直接报错（静默回退会让节假日错开一天）。 */
  private static ZoneId zoneOf(String timezone) {
    String text = blankToNull(timezone);
    if (text == null) return ZoneId.of("Asia/Shanghai");
    try {
      return ZoneId.of(text);
    } catch (java.time.DateTimeException e) {
      throw bad("时区「" + text + "」不是一个已知时区：请用 IANA 时区名，例如 Asia/Shanghai。");
    }
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
    } catch (DateTimeParseException e) {
      throw bad(
          label
              + "「"
              + text
              + "」格式不对：请带时区偏移量，例如 2026-10-10T08:00:00+08:00。"
              + "缺偏移量的时间会在东八区静默偏 8 小时，页面却看不出异常。");
    }
  }

  /** 可选时刻：空白即「不限」。 */
  private static OffsetDateTime optionalMoment(String value, String label) {
    String text = nz(value).trim();
    return text.isEmpty() ? null : parseMoment(text, label);
  }

  /** 可选日历日期：空白即「没有」。 */
  private static LocalDate parseDate(String value, String label) {
    String text = nz(value).trim();
    if (text.isEmpty()) throw bad("必须指定" + label + "，例如 2026-10-01");
    try {
      return LocalDate.parse(text);
    } catch (DateTimeParseException e) {
      throw bad(label + "「" + text + "」格式不对：请用 YYYY-MM-DD，例如 2026-10-01。");
    }
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

  private static long nz(Long value) {
    return value == null ? 0L : value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String str(OffsetDateTime value) {
    return value == null ? null : value.toString();
  }

  private static String str(LocalDate value) {
    return value == null ? null : value.toString();
  }

  /** 从 DataAccessException 里取最内层的人话（PG 的 RAISE EXCEPTION 消息）。 */
  private static String rootMessage(Throwable e) {
    Throwable current = e;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return message == null ? e.getMessage() : message;
  }
}
