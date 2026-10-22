package com.starcon.crm;

import static com.starcon.crm.CareReminderModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 15.5.2.1 关怀与催办。
 *
 * <p>蓝图 15 卷 15.5.2.1 原话：
 *
 * <blockquote>
 * 职责：主动触达买家：发货通知、物流提醒、催付（未付款订单）、催评、售后进度通知、关怀邮件（节假日/生日），按规则定时发送并遵守频次上限与退订要求。
 * 输入：触达规则；目标买家集合；退订名单。
 * 输出：触达发送记录 + 打开/点击统计 + 退订记录。
 * 关系：上游 = 9 订单、10 物流、15.3.2.2 接触记录；下游 = 15.1.1.2 发送、12 财务（催付加速回款）。
 * <b>催付有合规边界</b>：部分平台限制催付话术与频次，规则需按平台配置，过度催付会被投诉。
 * </blockquote>
 *
 * <p><b>本类落五条纪律</b>：
 *
 * <ol>
 *   <li><b>🔴 遵守频次上限与退订要求 = 服务端强制两道闸</b>：
 *       <ul>
 *         <li><b>退订闸</b> —— 走 {@link CustomerContactService#optOutCheck}（<b>同一条判定路径</b>，
 *             读 V299 的 {@code crm_customer_opt_out}，<b>不另建退订表</b>）；
 *             scope='ALL' 一律排除，scope='MARKETING' 排除营销类触达。</li>
 *         <li><b>频次闸</b> —— 该买家在该触达类型下的窗口内触达次数 ≥ 规则上的频次上限。</li>
 *       </ul>
 *       任一命中 ⇒ <b>拒绝并给出明确原因</b>：落成一条 {@code status='BLOCKED'} 的任务
 *       （block_code + block_reason），<b>不是静默跳过</b>。拦截本身也是要留痕的事实。</li>
 *   <li><b>🔴 催付有合规边界</b>：催付规则<b>必须按平台配置</b>（规则上的 platform_code 单值），
 *       且平台必须在 {@link #PAYMENT_DEMAND_ALLOWED_PLATFORMS} 白名单里 ——
 *       白名单外的平台，规则<b>建不出来</b>（表级 CHECK），生成时也<b>如实答「该平台不支持催付」</b>。</li>
 *   <li><b>🔴 催评只对已签收订单成立</b>：目标集合 = 已签收订单（channel 的
 *       {@link SignoffDeliveredOrderPort}）<b>∩</b> 订单事实（trade 的
 *       {@link CustomerFactsPort}，用于取买家标识）。<b>未签收不生成催评任务</b>。</li>
 *   <li><b>未交付的下游一律不编</b>：12 财务（催付加速回款）未交付 ⇒ 只落催付任务本身与
 *       「已提醒回款」的意图，<b>不写财务单据 id、不编下游 id</b>；
 *       平台打开/点击回执未接入 ⇒ 统计只能靠 webhook 或人工录入，
 *       自动采集端点<b>如实答「未接入」且不写数据</b>。</li>
 *   <li><b>统计口径服务端算</b>：触达总数 / 成功数 / 失败数 / 打开数 / 点击数 /
 *       退订拦截数 / 频次拦截数全部由服务端聚合（BigDecimal / long），
 *       <b>前端不重算、不做算术</b>。</li>
 * </ol>
 *
 * <p><b>跨服务纪律</b>（一字不改既有语义）：转发<b>调用方自己的</b> {@code Authorization}
 * bearer、<b>不传租户号</b>、连接 3s / 读 10s 显式超时、
 * <b>取不到就显式失败 503，绝不返回空集合冒充「上游没有数据」</b>。
 */
@Service
public class CareReminderService {

  // ================================================================ 常量

  /** 列表页最大条数（与本服务其它模块同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 🔴 <b>允许催付的平台白名单</b> —— 加粗句「催付有合规边界」的落点。
   *
   * <p>本仓当前口径：只有这四个平台允许**卖家主动催付**。
   * Amazon 等平台的买家消息由平台托管、且对催付话术与频次有额外限制
   * ⇒ 这些平台<b>不允许</b>主动催付（走平台自己的催款工具）。
   *
   * <p>⚠️ 这份名单在 V303 的表级 CHECK 里也有一份（同值）——
   * DB 挡住「建出来」，服务层给出「为什么」的人话。两处必须一致。
   */
  static final List<String> PAYMENT_DEMAND_ALLOWED_PLATFORMS =
      List.of("SHOPIFY", "EBAY", "TIKTOK_SHOP", "ALIBABA_1688");

  /**
   * <b>营销类触达</b> —— scope='MARKETING' 的退订会排除它们。
   *
   * <p>关怀邮件（节假日 / 生日）与催评都带**索取**性质（要好评、要回访），
   * 属于「过度索要被投诉」的那一类 ⇒ 营销类退订必须挡住。
   * 发货通知 / 物流提醒 / 催付 / 售后进度通知是<b>服务性通知</b>，
   * 只有 scope='ALL'（不要再联系我）才挡 —— 与 V299 自己的营销豁免口径一致。
   */
  static final Set<CareReminderType> MARKETING_TYPES =
      Set.of(CareReminderType.CARE_MAIL, CareReminderType.REVIEW_REQUEST);

  /** 目标窗口的默认回看天数（规则与生成入参都没给时）。 */
  static final int DEFAULT_LOOKBACK_DAYS = 30;

  /** 生成时的目标条数上限：一次生成不该把整个租户的目标都拉进来。 */
  static final int MAX_TARGETS = 5000;

  private final DSLContext sql;
  private final CustomerContactService contacts;
  private final CustomerFactsPort facts;
  private final SignoffDeliveredOrderPort signoff;
  private final OrderPaymentStatusPort payments;

  public CareReminderService(
      DSLContext sql,
      CustomerContactService contacts,
      CustomerFactsPort facts,
      SignoffDeliveredOrderPort signoff,
      OrderPaymentStatusPort payments) {
    this.sql = sql;
    this.contacts = contacts;
    this.facts = facts;
    this.signoff = signoff;
    this.payments = payments;
  }

  /** 补充信息快照的 JSON mapper：BigDecimal 按原样写出（不转成浮点）。 */
  private static final ObjectMapper JSON =
      com.fasterxml.jackson.databind.json.JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
          // 🔴 读回来也必须是 BigDecimal：默认会把 JSON 数字读成 double，那一行金额就会掉精度。
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .build();

  // ================================================================ 能力声明

  /**
   * 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>🔴 三个维度刻意是 {@code available=false}：
   *
   * <ul>
   *   <li>{@code platform_delivery} —— 平台侧发送接口未接入（channel/vendor 只有 OAuth 适配器）
   *       ⇒ 本模块只排期与登记，不投递。</li>
   *   <li>{@code open_click_receipt} —— 平台打开/点击回执未接入
   *       ⇒ 统计只能靠 webhook 或人工录入。</li>
   *   <li>{@code finance_receivable} —— 12 财务（催付加速回款）未交付
   *       ⇒ 只落催付任务与「已提醒回款」的意图，<b>不写财务单据 id</b>。</li>
   * </ul>
   */
  public CrmCareReminderCapabilities capabilities() {
    List<CrmCareReminderCapabilityDimension> dimensions =
        List.of(
            new CrmCareReminderCapabilityDimension(
                "care_reminder_rule",
                "触达规则（类型 / 平台 / 频次上限 / 静默时段 / 定时发送 / 话术）",
                true,
                "本服务 V303 crm_care_reminder_rule",
                "",
                "规则**逐平台配置**：不同平台可以各配各的催付话术与频次上限，"
                    + "不允许一个全局规则套所有平台"),
            new CrmCareReminderCapabilityDimension(
                "care_reminder_task",
                "触达任务与发送记录（计划时刻 / 实际发送时刻 / 发送结果 / 失败原因 / 拦截原因）",
                true,
                "本服务 V303 crm_care_reminder_task",
                "",
                "🔴 被拦截的触达**也留痕**（status=BLOCKED + 明确原因）："
                    + "「我们想在什么时候给他发、被什么挡住了」必须查得到"),
            new CrmCareReminderCapabilityDimension(
                "opt_out_gate",
                "退订闸（发送前拦退订客户）",
                true,
                "本服务 V303 + **V299 crm_customer_opt_out**（唯一事实源，只读）",
                "",
                "🔴 退订名单**只有一份**（15.3.2.2 的），本模块**只读不写**："
                    + "scope=ALL 一律排除，scope=MARKETING 排除营销类触达"),
            new CrmCareReminderCapabilityDimension(
                "frequency_gate",
                "频次闸（窗口内触达次数超限就拒绝）",
                true,
                "本服务 V303 crm_care_reminder_task + 规则上的 frequency_limit",
                "",
                "🔴 超限**不是静默跳过**：落成一条 BLOCKED 任务并写明"
                    + "「近 N 小时内已触达 M 次，上限 K 次」"),
            new CrmCareReminderCapabilityDimension(
                "payment_demand_boundary",
                "催付合规边界（按平台配置 + 平台白名单）",
                true,
                "本服务 V303 表级 CHECK + CareReminderService.PAYMENT_DEMAND_ALLOWED_PLATFORMS",
                "",
                "🔴 白名单外的平台**建不出催付规则**，生成时也如实答「该平台不支持催付」"),
            new CrmCareReminderCapabilityDimension(
                "review_request_signoff",
                "催评只对已签收订单成立",
                true,
                "channel GET /channel/logistics-pickup-signoff/cases?status=DELIVERED"
                    + " ∩ trade GET /trade/sales-orders/list",
                "",
                "未签收的订单**不生成催评任务**（生成结果里如实报出未签收的条数）"),
            new CrmCareReminderCapabilityDimension(
                "open_click_stat",
                "打开 / 点击统计",
                true,
                "本服务 V303 crm_care_reminder_stat",
                "",
                "统计口径**由服务端算**（总数 / 成功 / 失败 / 打开 / 点击 / 两类拦截数），"
                    + "前端不重算、不做算术"),
            new CrmCareReminderCapabilityDimension(
                "platform_delivery",
                "平台侧发送（站内信 / 邮件适配器投递）",
                false,
                "**channel 的 vendor 包下当前只有 OAuth 适配器，没有买家消息发送适配器**",
                "2",
                "本模块**不重造发送**：transport 恒为 MANUAL_HANDOFF（人工交接），"
                    + "运营在平台侧实际发出后回本服务登记结果与平台回执"),
            new CrmCareReminderCapabilityDimension(
                "open_click_receipt",
                "平台打开 / 点击回执的**自动**采集",
                false,
                "**平台未提供打开/点击回执接口**",
                "2",
                "🔴 自动采集端点**如实答「未接入」且不写数据**："
                    + "统计只能靠平台 webhook 或人工录入，平台接口未接入时**不伪造统计数据**"),
            new CrmCareReminderCapabilityDimension(
                "finance_receivable",
                "催付回款加速（12 财务）",
                false,
                "**12 财务未交付**",
                "12",
                "本模块只落**催付任务本身**与「已提醒回款」的意图，"
                    + "**不写财务单据 id、不编下游 id**"),
            new CrmCareReminderCapabilityDimension(
                "customer_profile",
                "按客户主档筛人群（生日 / 会员等级 / 标签）",
                false,
                "**15.3.1.1 客户列表 / 15.3.1.2 客户详情未交付**；本仓没有买家档案主数据",
                "15.3.1.2",
                "客户标识是**逻辑引用**：本模块按订单事实与显式客户清单圈目标，"
                    + "不假装能按生日或会员等级圈人"),
            new CrmCareReminderCapabilityDimension(
                "logistics_shipped_fact",
                "「已发货」订单集合（发货通知的精确触发源）",
                false,
                "**本仓没有可读的「已发货订单集合」端口**（channel 的揽收签收端口只提供已签收档）",
                "10",
                "发货通知 / 物流提醒的目标是**窗口内的订单**；"
                    + "真正的发货触发需 10 物流的已发货事实接入后才有 —— 页面不假装已打通"),
            new CrmCareReminderCapabilityDimension(
                "auto_reply",
                "消息自动回复（15.5.1.1）",
                false,
                "**15.5.1.1 自动回复未开发**",
                "15.5.1.1",
                "本模块**不碰**自动回复：自动回复是「收到消息后回」，"
                    + "本模块是「按规则主动触达」，两件事不混"));
    return new CrmCareReminderCapabilities(
        dimensions,
        List.of(
            CareReminderType.SHIPMENT_NOTICE.name(),
            CareReminderType.LOGISTICS_REMINDER.name(),
            CareReminderType.PAYMENT_DEMAND.name(),
            CareReminderType.REVIEW_REQUEST.name(),
            CareReminderType.AFTERSALES_PROGRESS.name(),
            CareReminderType.CARE_MAIL.name()),
        List.of(
            CareReminderStatus.QUEUED.name(),
            CareReminderStatus.SENT.name(),
            CareReminderStatus.FAILED.name(),
            CareReminderStatus.BLOCKED.name(),
            CareReminderStatus.CANCELLED.name()),
        List.of(CareReminderBlockCode.OPT_OUT.name(), CareReminderBlockCode.FREQUENCY_LIMIT.name()),
        PAYMENT_DEMAND_ALLOWED_PLATFORMS,
        MARKETING_TYPES.stream().map(Enum::name).sorted().toList(),
        "退订名单的唯一事实源是 15.3.2.2 的 crm_customer_opt_out（只读）："
            + "scope=ALL 一律排除；scope=MARKETING 排除营销类触达（关怀邮件 / 催评）。"
            + "本模块**不另建退订表**，也不写入既有退订表。",
        "频次上限由规则给出（frequency_limit + frequency_window_hours）："
            + "该买家在该触达类型下的窗口内触达次数达到上限即拒绝，并写明"
            + "「近 N 小时内已触达 M 次，上限 K 次」。",
        "本模块**不重造发送**：平台侧发送接口未接入，transport 恒为 MANUAL_HANDOFF；"
            + "平台打开/点击回执未接入，自动采集端点如实答「未接入」且不写数据；"
            + "12 财务未交付，催付只落任务与「已提醒回款」的意图，不写财务单据 id。",
        "催付合规边界：催付规则必须按平台配置，且平台必须在白名单 "
            + String.join(" / ", PAYMENT_DEMAND_ALLOWED_PLATFORMS)
            + " 内；白名单外的平台建不出催付规则（表级 CHECK），"
            + "生成时如实答「该平台不支持催付」。",
        "主动触达买家：发货通知、物流提醒、催付、催评、售后进度通知、关怀邮件，"
            + "按规则定时发送并遵守频次上限与退订要求。");
  }

  // ================================================================ 触达规则

  /** 触达规则列表（真分页 + 真筛选）。 */
  public CrmCareReminderRulePage rulePage(
      String reminderType, String platformCode, String status, String keyword, int page, int size) {
    List<Object> binds = new ArrayList<>();
    String where = ruleWhere(reminderType, platformCode, status, keyword, binds);
    long total =
        sql.fetchOne("select count(*) from crm_care_reminder_rule r" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmCareReminderRuleRow> items =
        sql.fetch(
                ruleColumns() + where + " order by r.created_at desc, r.id desc limit ? offset ?",
                pageBinds.toArray())
            .map(CareReminderService::rule);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCareReminderRulePage(items, safePage, safeSize, total, totalPages);
  }

  /** 单条触达规则。 */
  public CrmCareReminderRuleRow rule(UUID ruleId) {
    var row =
        sql.fetchOne(ruleColumns() + " where r.tenant_id = ? and r.id = ?", currentTenant(), ruleId);
    if (row == null) throw notFound("触达规则不存在：" + ruleId);
    return rule(row);
  }

  /**
   * 新建一条触达规则。
   *
   * <p>🔴 校验三件事（不合规**一律 400 且不写任何数据**）：
   *
   * <ol>
   *   <li>触达类型在词表内；</li>
   *   <li><b>催付的平台在白名单里</b> —— 加粗句「催付有合规边界」的落点；
   *       不在白名单 ⇒ 「该平台不支持催付」；</li>
   *   <li>频次上限 / 频次窗口 / 静默时段 / 发送时刻 / 时区的形状。</li>
   * </ol>
   */
  @Transactional
  public CrmCareReminderRuleRow createRule(CrmCareReminderRuleInput input, UUID actorId) {
    CareReminderType type = reminderType(input.reminderType());
    String platform = platformCode(input.platformCode());
    requirePaymentDemandPlatform(type, platform);
    int frequencyLimit = frequencyLimit(input.frequencyLimit());
    int frequencyWindow = frequencyWindow(input.frequencyWindowHours());
    LocalTime[] quiet = quietHours(input.quietHoursStart(), input.quietHoursEnd());
    LocalTime sendTime = sendTime(input.sendTime());
    String timezone = timezone(input.timezone());
    String ruleCode = input.ruleCode().trim();
    if (ruleCode.isEmpty()) throw bad("规则编码不能为空。");
    var existing =
        sql.fetchOne(
            "select id from crm_care_reminder_rule where tenant_id = ? and rule_code = ?",
            currentTenant(),
            ruleCode);
    if (existing != null) {
      throw conflict("同一个租户下已经有编码为「" + ruleCode + "」的触达规则了，换一个编码。");
    }
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into crm_care_reminder_rule"
            + "(id,tenant_id,rule_code,rule_name,reminder_type,platform_code,shop_id,shop_name,"
            + " target_filter,frequency_limit,frequency_window_hours,quiet_hours_start,quiet_hours_end,"
            + " send_time,timezone,template_code,subject,body,status,created_by,note,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?::jsonb,?,?,?::time,?::time,?::time,?,?,?,?,"
            + "'ACTIVE',?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        ruleCode,
        input.ruleName().trim(),
        type.name(),
        platform,
        input.shopId(),
        nz(input.shopName()).trim(),
        toJson(input.targetFilter() == null ? Map.of() : input.targetFilter()),
        frequencyLimit,
        frequencyWindow,
        quiet[0],
        quiet[1],
        sendTime,
        timezone,
        nz(input.templateCode()).trim(),
        nz(input.subject()).trim(),
        nz(input.body()),
        actorId,
        nz(input.note()).trim(),
        now(),
        now());
    return rule(id);
  }

  /**
   * 修改一条触达规则。
   *
   * <p>⚠️ 改频次上限 / 静默时段**不回写**历史任务：历史任务保留**当时**的
   * 话术与计划时刻（快照），否则「当时那条触达是按哪套配置发出去的」永远答不了。
   */
  @Transactional
  public CrmCareReminderRuleRow updateRule(
      UUID ruleId, CrmCareReminderRuleInput input, UUID actorId) {
    CrmCareReminderRuleRow existing = rule(ruleId);
    CareReminderType type = reminderType(input.reminderType());
    String platform = platformCode(input.platformCode());
    requirePaymentDemandPlatform(type, platform);
    int frequencyLimit = frequencyLimit(input.frequencyLimit());
    int frequencyWindow = frequencyWindow(input.frequencyWindowHours());
    LocalTime[] quiet = quietHours(input.quietHoursStart(), input.quietHoursEnd());
    LocalTime sendTime = sendTime(input.sendTime());
    String timezone = timezone(input.timezone());
    String ruleCode = input.ruleCode().trim();
    if (ruleCode.isEmpty()) throw bad("规则编码不能为空。");
    var clash =
        sql.fetchOne(
            "select id from crm_care_reminder_rule where tenant_id = ? and rule_code = ? and id <> ?",
            currentTenant(),
            ruleCode,
            ruleId);
    if (clash != null) {
      throw conflict("同一个租户下已经有编码为「" + ruleCode + "」的触达规则了，换一个编码。");
    }
    sql.execute(
        "update crm_care_reminder_rule set"
            + " rule_code=?,rule_name=?,reminder_type=?,platform_code=?,shop_id=?,shop_name=?,"
            + " target_filter=?::jsonb,frequency_limit=?,frequency_window_hours=?,"
            + " quiet_hours_start=?::time,quiet_hours_end=?::time,send_time=?::time,timezone=?,"
            + " template_code=?,subject=?,body=?,note=?,updated_at=?::timestamptz"
            + " where tenant_id=? and id=?",
        ruleCode,
        input.ruleName().trim(),
        type.name(),
        platform,
        input.shopId(),
        nz(input.shopName()).trim(),
        toJson(input.targetFilter() == null ? Map.of() : input.targetFilter()),
        frequencyLimit,
        frequencyWindow,
        quiet[0],
        quiet[1],
        sendTime,
        timezone,
        nz(input.templateCode()).trim(),
        nz(input.subject()).trim(),
        nz(input.body()),
        nz(input.note()).trim(),
        now(),
        currentTenant(),
        ruleId);
    return rule(ruleId);
  }

  /** 停用 / 启用一条触达规则（停用后不能生成新任务，历史任务仍可读）。 */
  @Transactional
  public CrmCareReminderRuleRow setRuleStatus(UUID ruleId, String status, UUID actorId) {
    CrmCareReminderRuleRow existing = rule(ruleId);
    String next = nz(status).trim().toUpperCase(Locale.ROOT);
    if (!next.equals("ACTIVE") && !next.equals("DISABLED")) {
      throw bad("规则状态只能是 ACTIVE 或 DISABLED。");
    }
    sql.execute(
        "update crm_care_reminder_rule set status=?,updated_at=?::timestamptz"
            + " where tenant_id=? and id=?",
        next,
        now(),
        currentTenant(),
        ruleId);
    return rule(ruleId);
  }

  // ================================================================ 触达任务

  /** 触达任务列表（真分页 + 真筛选）。 */
  public CrmCareReminderTaskPage taskPage(
      UUID ruleId,
      String reminderType,
      String status,
      String blockCode,
      String customerRef,
      String platformCode,
      String from,
      String to,
      int page,
      int size) {
    List<Object> binds = new ArrayList<>();
    String where = taskWhere(ruleId, reminderType, status, blockCode, customerRef, platformCode, from, to, binds);
    long total =
        sql.fetchOne("select count(*) from crm_care_reminder_task t" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmCareReminderTaskRow> items =
        sql.fetch(
                taskColumns() + where
                    + " order by t.created_at desc, t.id desc limit ? offset ?",
                pageBinds.toArray())
            .map(CareReminderService::task);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCareReminderTaskPage(items, safePage, safeSize, total, totalPages);
  }

  /** 单条触达任务（含它自己的打开数与点击数）。 */
  public CrmCareReminderTaskRow task(UUID taskId) {
    var row =
        sql.fetchOne(taskColumns() + " where t.tenant_id = ? and t.id = ?", currentTenant(), taskId);
    if (row == null) throw notFound("触达任务不存在：" + taskId);
    return task(row);
  }

  // ================================================================ 生成触达任务

  /**
   * 按规则生成触达任务 —— 蓝图输入句的「目标买家集合」真正落地的地方。
   *
   * <p>🔴 生成的每一行都要过**两道闸**（加粗句「遵守频次上限与退订要求」）：
   *
   * <ol>
   *   <li><b>退订闸</b>：走 {@link CustomerContactService#optOutCheck}（同一条判定路径，
   *       读 V299 的退订名单）。命中 ⇒ 落一条 {@code BLOCKED} 任务，
   *       reason 写明「客户已退订（ALL / MARKETING）」。</li>
   *   <li><b>频次闸</b>：该买家在该触达类型下的窗口内触达次数 ≥ 上限 ⇒
   *       落一条 {@code BLOCKED} 任务，reason 写明
   *       「近 N 小时内已触达 M 次，上限 K 次」。</li>
   * </ol>
   *
   * <p>🔴 <b>静默时段</b>不是闸，是**顺延**：计划时刻推到静默结束，
   * 并在 note 里写明顺延了多少（静默时段的意思是「这段时间别发」，不是「不许排期」）。
   *
   * <p>⚠️ 幂等：同一条规则 + 同一个买家 + 同一张订单 + 同一天，重复生成**只会有一条**任务
   * （重复生成不重复记账）。
   */
  @Transactional
  public CrmCareReminderGenerateResult generate(
      UUID ruleId, CrmCareReminderGenerateInput input, UUID actorId, String bearer) {
    CrmCareReminderRuleRow rule = rule(ruleId);
    if (!"ACTIVE".equals(rule.status())) {
      throw conflict(
          "触达规则「"
              + rule.ruleCode()
              + "」已停用（"
              + rule.statusLabel()
              + "）：停用的规则不能生成新的触达任务，历史任务仍可读。");
    }
    // 🔴 催付合规边界（规则级）：平台不在白名单 ⇒ 如实答「该平台不支持催付」。
    requirePaymentDemandPlatform(
        CareReminderType.valueOf(rule.reminderType()), rule.platformCode());

    CareReminderType type = CareReminderType.valueOf(rule.reminderType());
    OffsetDateTime plannedAt = resolvePlannedAt(rule, input);
    TargetSet targetSet = resolveTargets(rule, type, input, bearer);

    Map<String, Integer> reasons = new LinkedHashMap<>();
    int created = 0;
    int blocked = 0;
    int idempotentHits = 0;
    OffsetDateTime windowStart = plannedAt.minusHours(rule.frequencyWindowHours());

    for (TargetBuyer target : targetSet.targets()) {
      String idempotencyKey =
          idempotencyKey(rule.id(), target.customerRef(), target.orderReference(), plannedAt);
      // ---- 闸 1：退订 ----
      var optOut = contacts.optOutCheck(target.customerRef());
      String blockCode = "";
      String blockReason = "";
      if (optOut.suppressed()) {
        blockCode = CareReminderBlockCode.OPT_OUT.name();
        blockReason =
            "客户已退订（范围 ALL："
                + nz(optOut.reason())
                + "，登记于 "
                + nz(optOut.recordedAt())
                + "）：不要再联系我，全部触达都排除，本次不发送。";
      } else if (optOut.marketingSuppressed() && MARKETING_TYPES.contains(type)) {
        blockCode = CareReminderBlockCode.OPT_OUT.name();
        blockReason =
            "客户已退订（范围 MARKETING："
                + nz(optOut.reason())
                + "，登记于 "
                + nz(optOut.recordedAt())
                + "）：营销类触达（"
                + type.label()
                + "）必须排除，本次不发送。";
      }
      // ---- 闸 2：频次 ----
      if (blockCode.isEmpty() && rule.frequencyLimit() <= 0) {
        blockCode = CareReminderBlockCode.FREQUENCY_LIMIT.name();
        blockReason =
            "规则「"
                + rule.ruleCode()
                + "」的频次上限是 0：这条规则不允许任何触达，本次不发送。";
      }
      if (blockCode.isEmpty()) {
        FrequencyCounts counts = frequencyCounts(target.customerRef(), type, windowStart);
        if (counts.total() >= rule.frequencyLimit()) {
          blockCode = CareReminderBlockCode.FREQUENCY_LIMIT.name();
          blockReason =
              "近 "
                  + rule.frequencyWindowHours()
                  + " 小时内该买家在【"
                  + type.label()
                  + "】下已触达 "
                  + counts.total()
                  + " 次（已发 "
                  + counts.sent()
                  + " 次 / 已排期 "
                  + counts.queued()
                  + " 次），上限 "
                  + rule.frequencyLimit()
                  + " 次 —— 过度触达会被投诉，本次不发送。";
        }
      }

      // ---- 静默时段：顺延（不是闸）----
      OffsetDateTime effectivePlannedAt = applyQuietHours(rule, plannedAt);
      String note = "";
      if (!effectivePlannedAt.equals(plannedAt)) {
        note =
            "计划时刻落在静默时段（"
                + rule.quietHoursStart()
                + " - "
                + rule.quietHoursEnd()
                + "，"
                + rule.timezone()
                + "），已顺延到 "
                + effectivePlannedAt
                + "。";
      }

      boolean inserted =
          insertTask(
              rule,
              type,
              target,
              blockCode,
              blockReason,
              effectivePlannedAt,
              idempotencyKey,
              note,
              actorId);
      if (inserted) {
        if (blockCode.isEmpty()) {
          created++;
        } else {
          blocked++;
          reasons.merge(blockReason, 1, Integer::sum);
        }
      } else {
        idempotentHits++;
      }
    }

    String note =
        targetSet.note()
            + (created + blocked == 0 && targetSet.targets().isEmpty()
                ? " 本次没有生成任何触达任务（上游没有符合条件的目标）。"
                : "")
            + " 已排期 "
            + created
            + " 条，被拦截 "
            + blocked
            + " 条（每条都带明确原因），幂等命中 "
            + idempotentHits
            + " 条（当天已生成过，没有重复记账）。";
    return new CrmCareReminderGenerateResult(
        rule.id(),
        rule.ruleCode(),
        created,
        blocked,
        idempotentHits,
        targetSet.upstreamCount(),
        targetSet.targets().size(),
        reasons,
        plannedAt.toString(),
        note);
  }

  // ================================================================ 发送结果登记

  /**
   * 人工交接登记发送结果（**当前唯一可用的发送路径**）。
   *
   * <p>🔴 {@code outcome=FAILED} 时 {@code failureReason} **必填**：
   * 只写「失败」而不写为什么，事后无法判断该不该重试。
   */
  @Transactional
  public CrmCareReminderTaskRow dispatch(
      UUID taskId, CrmCareReminderDispatchInput input, UUID actorId) {
    CrmCareReminderTaskRow existing = task(taskId);
    if ("SENT".equals(existing.status()) || "FAILED".equals(existing.status())) {
      throw conflict(
          "这条触达已经登记过发送结果了（"
              + existing.statusLabel()
              + "）：重复登记会让「这一条到底发没发出去」出现两个答案。");
    }
    if ("BLOCKED".equals(existing.status())) {
      throw conflict(
          "这条触达被拦截了（"
              + nz(existing.blockReason())
              + "）：被拦截的触达不能登记发送结果。");
    }
    if ("CANCELLED".equals(existing.status())) {
      throw conflict("这条触达已作废，不能登记发送结果。");
    }
    String outcome = nz(input.outcome()).trim().toUpperCase(Locale.ROOT);
    if (outcome.equals(CareReminderDispatchOutcome.SENT.name())) {
      OffsetDateTime sentAt =
          input.sentAt() == null || input.sentAt().isBlank()
              ? now()
              : parseMoment(input.sentAt(), "实际发送时刻");
      sql.execute(
          "update crm_care_reminder_task set status='SENT',sent_at=?::timestamptz,"
              + " platform_receipt=?,note=?,updated_at=?::timestamptz"
              + " where tenant_id=? and id=?",
          sentAt,
          nz(input.platformReceipt()).trim(),
          mergeNote(existing.note(), nz(input.note()).trim()),
          now(),
          currentTenant(),
          taskId);
    } else if (outcome.equals(CareReminderDispatchOutcome.FAILED.name())) {
      String failure = nz(input.failureReason()).trim();
      if (failure.isEmpty()) {
        throw bad("登记发送失败必须写明失败原因：只写「失败」而不写为什么，事后无法判断该不该重试。");
      }
      sql.execute(
          "update crm_care_reminder_task set status='FAILED',failure_reason=?,"
              + " platform_receipt=?,note=?,updated_at=?::timestamptz"
              + " where tenant_id=? and id=?",
          failure,
          nz(input.platformReceipt()).trim(),
          mergeNote(existing.note(), nz(input.note()).trim()),
          now(),
          currentTenant(),
          taskId);
    } else {
      throw bad("发送结果只能是 SENT 或 FAILED。");
    }
    return task(taskId);
  }

  // ================================================================ 打开 / 点击统计

  /** 某条触达任务的打开 / 点击事件（真分页）。 */
  public CrmCareReminderStatEventPage statEvents(UUID taskId, int page, int size) {
    task(taskId);
    long total =
        sql.fetchOne(
                "select count(*) from crm_care_reminder_stat where tenant_id = ? and task_id = ?",
                currentTenant(),
                taskId)
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<CrmCareReminderStatEventRow> items =
        sql.fetch(
                "select s.id,s.task_id,t.task_no,s.event_type,s.occurred_at,s.source,s.created_at"
                    + " from crm_care_reminder_stat s"
                    + " join crm_care_reminder_task t on t.id=s.task_id and t.tenant_id=s.tenant_id"
                    + " where s.tenant_id=? and s.task_id=?"
                    + " order by s.occurred_at desc, s.id desc limit ? offset ?",
                currentTenant(),
                taskId,
                safeSize,
                safePage * safeSize)
            .map(CareReminderService::statEvent);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCareReminderStatEventPage(items, safePage, safeSize, total, totalPages);
  }

  /**
   * 录入一条打开 / 点击事件（**平台 webhook 或人工录入**）。
   *
   * <p>⚠️ 平台打开/点击回执**未接入** ⇒ 自动采集走不了这条路；
   * 这里只接受**显式录入**的观察值，且必须指明来源（source）。
   */
  @Transactional
  public CrmCareReminderStatEventRow recordStatEvent(
      CrmCareReminderStatEventInput input, UUID actorId) {
    UUID taskId;
    try {
      taskId = UUID.fromString(input.taskId().trim());
    } catch (IllegalArgumentException e) {
      throw bad("taskId 不是合法的 UUID：" + input.taskId());
    }
    task(taskId);
    String eventType = nz(input.eventType()).trim().toUpperCase(Locale.ROOT);
    if (!eventType.equals(CareReminderEventType.OPEN.name())
        && !eventType.equals(CareReminderEventType.CLICK.name())) {
      throw bad("统计事件类型只能是 OPEN 或 CLICK。");
    }
    OffsetDateTime occurredAt =
        input.occurredAt() == null || input.occurredAt().isBlank()
            ? now()
            : parseMoment(input.occurredAt(), "事件发生时刻");
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into crm_care_reminder_stat"
            + "(id,tenant_id,task_id,event_type,occurred_at,source,created_at)"
            + " values (?,?,?,?,?::timestamptz,?,?::timestamptz)",
        id,
        currentTenant(),
        taskId,
        eventType,
        occurredAt,
        nz(input.source()).trim(),
        now());
    var row =
        sql.fetchOne(
            "select s.id,s.task_id,t.task_no,s.event_type,s.occurred_at,s.source,s.created_at"
                + " from crm_care_reminder_stat s"
                + " join crm_care_reminder_task t on t.id=s.task_id and t.tenant_id=s.tenant_id"
                + " where s.tenant_id=? and s.id=?",
            currentTenant(),
            id);
    return statEvent(row);
  }

  /**
   * 平台打开 / 点击回执的**接入状态** —— 恒「未接入」。
   *
   * <p>🔴 平台未提供打开/点击回执接口 ⇒ 这里<b>如实答「未接入」且不写任何数据</b>。
   *
   * <p>⚠️ 刻意**不抛异常**（用 {@code Unavailable*Port} 同款写法）：异常会走 Spring 的
   * {@code sendError}，响应体是空的、原因就丢了 —— 调用方只剩一句「保存失败」，
   * 那等于没答「未接入」。返回一份带原因的结论，页面才讲得清
   * 「为什么统计只能人工录入」。
   */
  public CrmCareReminderPlatformReceiptStatus platformEvents() {
    return new CrmCareReminderPlatformReceiptStatus(
        false,
        "平台打开 / 点击回执**未接入**（归 2 渠道与店铺：平台适配器在 channel 的 vendor 包下，"
            + "当前没有买家消息的打开/点击回执接口）。"
            + "统计只能靠**平台 webhook** 或**人工录入**（POST /crm/care-reminders/stats/events）；"
            + "本端点**不会写任何数据** —— 平台接口未接入时**不伪造统计数据**。",
        "2");
  }

  // ================================================================ 统计汇总

  /**
   * 统计汇总 —— <b>全部由服务端算</b>。
   *
   * <p>🔴 前端<b>不重算、不做算术</b>：这里给什么页面就显示什么。
   */
  public CrmCareReminderStats stats(
      UUID ruleId, String reminderType, String status, String from, String to) {
    List<Object> binds = new ArrayList<>();
    String where = taskWhere(ruleId, reminderType, status, null, null, null, from, to, binds);
    var row =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where t.status='QUEUED') as queued,"
                + " count(*) filter (where t.status='SENT') as sent,"
                + " count(*) filter (where t.status='FAILED') as failed,"
                + " count(*) filter (where t.status='BLOCKED') as blocked,"
                + " count(*) filter (where t.status='CANCELLED') as cancelled,"
                + " count(*) filter (where t.block_code='OPT_OUT') as opt_out_blocked,"
                + " count(*) filter (where t.block_code='FREQUENCY_LIMIT') as frequency_blocked"
                + " from crm_care_reminder_task t"
                + where,
            binds.toArray());
    long total = row.get("total", Long.class);
    long queued = row.get("queued", Long.class);
    long sent = row.get("sent", Long.class);
    long failed = row.get("failed", Long.class);
    long blocked = row.get("blocked", Long.class);
    long cancelled = row.get("cancelled", Long.class);
    long optOutBlocked = row.get("opt_out_blocked", Long.class);
    long frequencyBlocked = row.get("frequency_blocked", Long.class);

    // 打开 / 点击数：按同一套筛选口径聚合（join 任务表，保证只算本租户的任务）。
    List<Object> statBinds = new ArrayList<>();
    String statWhere =
        taskWhere(ruleId, reminderType, status, null, null, null, from, to, statBinds);
    var statRow =
        sql.fetchOne(
            "select count(*) filter (where s.event_type='OPEN') as opens,"
                + " count(*) filter (where s.event_type='CLICK') as clicks"
                + " from crm_care_reminder_stat s"
                + " join crm_care_reminder_task t on t.id=s.task_id and t.tenant_id=s.tenant_id"
                + statWhere,
            statBinds.toArray());
    long openCount = statRow.get("opens", Long.class);
    long clickCount = statRow.get("clicks", Long.class);

    String note =
        "触达总数 "
            + total
            + "（已发 "
            + sent
            + " / 失败 "
            + failed
            + " / 被拦截 "
            + blocked
            + " / 待发 "
            + queued
            + " / 已作废 "
            + cancelled
            + "）；拦截构成：退订 "
            + optOutBlocked
            + " 条、频次超限 "
            + frequencyBlocked
            + " 条；打开 "
            + openCount
            + " 次、点击 "
            + clickCount
            + " 次。";
    return new CrmCareReminderStats(
        total,
        queued,
        sent,
        failed,
        blocked,
        cancelled,
        openCount,
        clickCount,
        optOutBlocked,
        frequencyBlocked,
        note);
  }

  // ================================================================ 目标解析（上游读）

  /** 一个目标买家（归因链路的终点）。 */
  private record TargetBuyer(
      String customerRef,
      String customerName,
      UUID shopId,
      String shopName,
      String platformCode,
      String orderReference,
      BigDecimal amount) {}

  /** 一次目标解析的结果（真实读到的上游数量 + 逐条说明）。 */
  private record TargetSet(List<TargetBuyer> targets, int upstreamCount, String note) {}

  /** 窗口内的触达次数（已发 + 已排期）。 */
  private record FrequencyCounts(long sent, long queued) {
    long total() {
      return sent + queued;
    }
  }

  /**
   * 解析目标买家集合。
   *
   * <p>🔴 每一种触达类型的**目标来源**不同，而且都必须来自**真实上游**：
   *
   * <ul>
   *   <li>{@code REVIEW_REQUEST} 催评 → <b>已签收订单</b>（channel）
   *       <b>∩</b> 订单事实（trade，用于取买家标识）。
   *       <b>未签收不生成催评任务</b> —— 没签收就催评等于骚扰还没收到货的买家。</li>
   *   <li>{@code PAYMENT_DEMAND} 催付 → 订单事实（trade）
   *       <b>∩</b> 支付状态观察值 = UNPAID（trade 的 order_address_check）。
   *       <b>没有支付状态观察值的订单不纳入</b> —— 把「没量到」当成「未付款」去催款，
   *       是会引发投诉与平台处罚的一类事故。</li>
   *   <li>{@code AFTERSALES_PROGRESS} 售后进度通知 → 售后单（trade）
   *       → orderId → 订单事实 → 买家。</li>
   *   <li>{@code SHIPMENT_NOTICE} / {@code LOGISTICS_REMINDER} / {@code CARE_MAIL}
   *       → 窗口内的订单事实（trade）。</li>
   * </ul>
   *
   * <p>⚠️ 上游读不到一律 503（端口自己抛），<b>绝不</b>返回空集合冒充「上游没有数据」。
   */
  private TargetSet resolveTargets(
      CrmCareReminderRuleRow rule,
      CareReminderType type,
      CrmCareReminderGenerateInput input,
      String bearer) {
    int lookbackDays = lookbackDays(rule, input);
    OffsetDateTime from = now().minusDays(lookbackDays);
    OffsetDateTime to = now();
    BigDecimal minAmount = minAmount(rule, input);
    String orderSource = orderSource(rule, input);
    Set<String> explicitRefs = explicitRefs(input);

    // ---- 显式指定买家：不走上游推导，但仍要过闸 ----
    if (!explicitRefs.isEmpty()) {
      List<TargetBuyer> targets = new ArrayList<>();
      for (String ref : explicitRefs) {
        targets.add(
            new TargetBuyer(ref, "", rule.shopId(), rule.shopName(), rule.platformCode(), "", null));
      }
      return new TargetSet(
          targets,
          targets.size(),
          "本次按运营指定的 " + targets.size() + " 位买家生成（没有走上游推导）。");
    }

    switch (type) {
      case REVIEW_REQUEST -> {
        return reviewRequestTargets(rule, from, to, minAmount, orderSource, bearer);
      }
      case PAYMENT_DEMAND -> {
        return paymentDemandTargets(rule, from, to, minAmount, orderSource, bearer);
      }
      case AFTERSALES_PROGRESS -> {
        return aftersalesTargets(rule, from, to, minAmount, orderSource, bearer);
      }
      default -> {
        return orderTargets(rule, from, to, minAmount, orderSource, bearer, type.label());
      }
    }
  }

  /** 催评目标：已签收订单 ∩ 订单事实。 */
  private TargetSet reviewRequestTargets(
      CrmCareReminderRuleRow rule,
      OffsetDateTime from,
      OffsetDateTime to,
      BigDecimal minAmount,
      String orderSource,
      String bearer) {
    // 1. 已签收订单（channel）
    Map<String, SignoffDeliveredOrderPort.DeliveredCaseView> delivered = new LinkedHashMap<>();
    int deliveredScanned = 0;
    for (int page = 0; page < SignoffDeliveredOrderPort.MAX_PAGES; page++) {
      var upstream =
          signoff.deliveredCases(
              "", "", "", page, SignoffDeliveredOrderPort.PAGE_SIZE, bearer);
      if (upstream.items() == null || upstream.items().isEmpty()) break;
      deliveredScanned += upstream.items().size();
      for (var c : upstream.items()) {
        String no = nz(c.salesOrderNo()).trim();
        if (!no.isEmpty()) delivered.putIfAbsent(no, c);
      }
      if (upstream.items().size() < SignoffDeliveredOrderPort.PAGE_SIZE) break;
    }
    // 2. 店铺目录（channel）—— 用来把 channelId 变成「店铺名 + 平台」
    Map<UUID, SignoffDeliveredOrderPort.ShopView> shops = new LinkedHashMap<>();
    for (var shop : signoff.shops(bearer)) {
      shops.put(shop.id(), shop);
    }
    // 3. 订单事实（trade）—— 取买家标识
    List<CustomerFactsPort.OrderView> orders = readOrders(from, to, bearer);

    List<TargetBuyer> targets = new ArrayList<>();
    int notDelivered = 0;
    int noBuyer = 0;
    for (var order : orders) {
      String orderNo = nz(order.orderNo()).trim();
      if (orderNo.isEmpty()) continue;
      if (matchesFilter(order, minAmount, orderSource)) continue;
      var signoffCase = delivered.get(orderNo);
      if (signoffCase == null) {
        // 🔴 未签收 → **不生成催评任务**（但如实计数，不是静默跳过）。
        notDelivered++;
        continue;
      }
      if (order.customerPartyId() == null) {
        noBuyer++;
        continue;
      }
      var shop = signoffCase.channelId() == null ? null : shops.get(signoffCase.channelId());
      String platform =
          shop != null && !nz(shop.platformCode()).isBlank()
              ? nz(shop.platformCode()).trim()
              : nz(order.orderSource()).trim();
      if (!platform.equals(rule.platformCode())) continue;
      targets.add(
          new TargetBuyer(
              order.customerPartyId().toString(),
              nz(order.buyerName()).trim(),
              signoffCase.channelId(),
              shop == null ? rule.shopName() : nz(shop.name()).trim(),
              platform,
              orderNo,
              order.grandTotalAmount()));
      if (targets.size() >= MAX_TARGETS) break;
    }
    String note =
        "催评目标：上游已签收订单 "
            + deliveredScanned
            + " 条，窗口内订单 "
            + orders.size()
            + " 张，其中未签收 "
            + notDelivered
            + " 张（**未签收不生成催评任务**）"
            + (noBuyer > 0 ? "、订单上没有买家标识 " + noBuyer + " 张" : "")
            + "，按平台 "
            + rule.platformCode()
            + " 收窄后命中 "
            + targets.size()
            + " 位买家。";
    return new TargetSet(targets, deliveredScanned, note);
  }

  /** 催付目标：订单事实 ∩ 支付状态观察值 = UNPAID。 */
  private TargetSet paymentDemandTargets(
      CrmCareReminderRuleRow rule,
      OffsetDateTime from,
      OffsetDateTime to,
      BigDecimal minAmount,
      String orderSource,
      String bearer) {
    List<CustomerFactsPort.OrderView> orders = readOrders(from, to, bearer);
    // 支付状态观察值（trade 的 order_address_check）：按订单号索引
    Map<String, String> paymentStatusByOrderNo = new LinkedHashMap<>();
    int checksScanned = 0;
    for (int page = 0; page < OrderPaymentStatusPort.MAX_PAGES; page++) {
      var upstream = payments.checks("", page, bearer);
      if (upstream.items() == null || upstream.items().isEmpty()) break;
      checksScanned += upstream.items().size();
      for (var c : upstream.items()) {
        String no = nz(c.orderNo()).trim();
        if (!no.isEmpty() && !nz(c.paymentStatus()).isBlank()) {
          // 同一个订单可能有多次校验：**取最新的一次**（上游按判定基准时刻倒序）。
          paymentStatusByOrderNo.putIfAbsent(no, nz(c.paymentStatus()).trim());
        }
      }
      if (upstream.items().size() < OrderPaymentStatusPort.PAGE_SIZE) break;
    }

    List<TargetBuyer> targets = new ArrayList<>();
    int unknownPayment = 0;
    int paid = 0;
    for (var order : orders) {
      String orderNo = nz(order.orderNo()).trim();
      if (orderNo.isEmpty()) continue;
      if (matchesFilter(order, minAmount, orderSource)) continue;
      String platform = nz(order.orderSource()).trim();
      if (!platform.equals(rule.platformCode())) continue;
      if (order.customerPartyId() == null) continue;
      String status = paymentStatusByOrderNo.get(orderNo);
      if (status == null) {
        // ⚠️ 「没量到」不等于「未付款」：没有支付状态观察值的订单**不纳入催付**。
        unknownPayment++;
        continue;
      }
      if (!"UNPAID".equals(status)) {
        paid++;
        continue;
      }
      targets.add(
          new TargetBuyer(
              order.customerPartyId().toString(),
              nz(order.buyerName()).trim(),
              rule.shopId(),
              rule.shopName(),
              platform,
              orderNo,
              order.grandTotalAmount()));
      if (targets.size() >= MAX_TARGETS) break;
    }
    String note =
        "催付目标：窗口内订单 "
            + orders.size()
            + " 张，读到支付状态观察值 "
            + checksScanned
            + " 条；其中未付款 "
            + targets.size()
            + " 张进入催付，已付款 "
            + paid
            + " 张跳过，支付状态未知 "
            + unknownPayment
            + " 张跳过（**没量到不等于未付款**，不纳入催付）。";
    return new TargetSet(targets, checksScanned, note);
  }

  /** 售后进度通知目标：售后单 → 订单事实 → 买家。 */
  private TargetSet aftersalesTargets(
      CrmCareReminderRuleRow rule,
      OffsetDateTime from,
      OffsetDateTime to,
      BigDecimal minAmount,
      String orderSource,
      String bearer) {
    Map<UUID, CustomerFactsPort.OrderView> ordersById = new LinkedHashMap<>();
    Map<String, CustomerFactsPort.OrderView> ordersByNo = new LinkedHashMap<>();
    List<CustomerFactsPort.OrderView> orders = readOrders(from, to, bearer);
    for (var order : orders) {
      if (order.id() != null) ordersById.put(order.id(), order);
      String no = nz(order.orderNo()).trim();
      if (!no.isEmpty()) ordersByNo.putIfAbsent(no, order);
    }
    List<CustomerFactsPort.AftersalesCaseView> cases = new ArrayList<>();
    for (int page = 0; page < CustomerFactsPort.MAX_PAGES; page++) {
      var upstream = facts.aftersalesCases(from, to, page, bearer);
      if (upstream.rows() == null || upstream.rows().isEmpty()) break;
      cases.addAll(upstream.rows());
      if (upstream.rows().size() < CustomerFactsPort.PAGE_SIZE) break;
    }
    List<TargetBuyer> targets = new ArrayList<>();
    for (var c : cases) {
      var order = c.orderId() == null ? null : ordersById.get(c.orderId());
      if (order == null) {
        String no = nz(c.orderNo()).trim();
        if (!no.isEmpty()) order = ordersByNo.get(no);
      }
      if (order == null) continue;
      if (matchesFilter(order, minAmount, orderSource)) continue;
      String platform = nz(order.orderSource()).trim();
      if (!platform.equals(rule.platformCode())) continue;
      if (order.customerPartyId() == null) continue;
      targets.add(
          new TargetBuyer(
              order.customerPartyId().toString(),
              nz(order.buyerName()).trim(),
              rule.shopId(),
              rule.shopName(),
              platform,
              nz(c.orderNo()).trim().isEmpty() ? nz(order.orderNo()).trim() : nz(c.orderNo()).trim(),
              order.grandTotalAmount()));
      if (targets.size() >= MAX_TARGETS) break;
    }
    String note =
        "售后进度通知目标：窗口内售后单 "
            + cases.size()
            + " 条，关联到订单事实后命中 "
            + targets.size()
            + " 位买家（售后单不带买家维度，归因链路是「售后单 → orderId → 订单 → 买家」）。";
    return new TargetSet(targets, cases.size(), note);
  }

  /** 其余类型的目标：窗口内的订单事实。 */
  private TargetSet orderTargets(
      CrmCareReminderRuleRow rule,
      OffsetDateTime from,
      OffsetDateTime to,
      BigDecimal minAmount,
      String orderSource,
      String bearer,
      String typeLabel) {
    List<CustomerFactsPort.OrderView> orders = readOrders(from, to, bearer);
    List<TargetBuyer> targets = new ArrayList<>();
    for (var order : orders) {
      if (matchesFilter(order, minAmount, orderSource)) continue;
      String platform = nz(order.orderSource()).trim();
      if (!platform.equals(rule.platformCode())) continue;
      if (order.customerPartyId() == null) continue;
      targets.add(
          new TargetBuyer(
              order.customerPartyId().toString(),
              nz(order.buyerName()).trim(),
              rule.shopId(),
              rule.shopName(),
              platform,
              nz(order.orderNo()).trim(),
              order.grandTotalAmount()));
      if (targets.size() >= MAX_TARGETS) break;
    }
    String note =
        typeLabel
            + "目标：窗口内订单 "
            + orders.size()
            + " 张，按平台 "
            + rule.platformCode()
            + " 与其它筛选条件收窄后命中 "
            + targets.size()
            + " 位买家。";
    return new TargetSet(targets, orders.size(), note);
  }

  /** 读订单事实（真翻页，有上限；上游读不到端口自己抛 503）。 */
  private List<CustomerFactsPort.OrderView> readOrders(
      OffsetDateTime from, OffsetDateTime to, String bearer) {
    List<CustomerFactsPort.OrderView> orders = new ArrayList<>();
    for (int page = 0; page < CustomerFactsPort.MAX_PAGES; page++) {
      var upstream = facts.orders(from, to, page, bearer);
      if (upstream.rows() == null || upstream.rows().isEmpty()) break;
      orders.addAll(upstream.rows());
      if (upstream.rows().size() < CustomerFactsPort.PAGE_SIZE) break;
    }
    return orders;
  }

  /** 订单是否被筛选条件排除。 */
  private static boolean matchesFilter(
      CustomerFactsPort.OrderView order, BigDecimal minAmount, String orderSource) {
    if (minAmount != null) {
      BigDecimal amount = order.grandTotalAmount();
      // ⚠️ 金额缺失时**不**当成 0（那是把「不知道」当成「不够」）；
      //   缺金额的订单按「无法确认达到门槛」处理 ⇒ 不纳入。
      if (amount == null || amount.compareTo(minAmount) < 0) return true;
    }
    if (orderSource != null && !orderSource.isBlank()) {
      if (!orderSource.equals(nz(order.orderSource()).trim())) return true;
    }
    return false;
  }

  // ================================================================ 写入

  /**
   * 写一条触达任务（幂等：同一条幂等键只会有一条）。
   *
   * @return true = 真的写进去了；false = 幂等命中（当天已生成过）
   */
  private boolean insertTask(
      CrmCareReminderRuleRow rule,
      CareReminderType type,
      TargetBuyer target,
      String blockCode,
      String blockReason,
      OffsetDateTime plannedAt,
      String idempotencyKey,
      String note,
      UUID actorId) {
    UUID id = UUID.randomUUID();
    int affected =
        sql.execute(
            "insert into crm_care_reminder_task"
                + "(id,tenant_id,task_no,rule_id,reminder_type,customer_ref,customer_name,"
                + " shop_id,shop_name,platform_code,order_reference,subject,body,transport,status,"
                + " planned_at,sent_at,failure_reason,block_code,block_reason,platform_receipt,"
                + " idempotency_key,created_by,note,created_at,updated_at)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,'MANUAL_HANDOFF',?,"
                + " ?::timestamptz,null,'',?,?,'',?,?,?,?::timestamptz,?::timestamptz)"
                + " on conflict do nothing",
            id,
            currentTenant(),
            nextTaskNo(),
            rule.id(),
            type.name(),
            target.customerRef(),
            target.customerName(),
            target.shopId(),
            target.shopName(),
            target.platformCode(),
            target.orderReference(),
            rule.subject(),
            rule.body(),
            blockCode.isEmpty() ? "QUEUED" : "BLOCKED",
            plannedAt,
            blockCode,
            blockReason,
            idempotencyKey,
            actorId,
            note,
            now(),
            now());
    return affected > 0;
  }

  // ================================================================ 频次闸

  /** 该买家在该触达类型下的窗口内触达次数（已发 + 已排期）。 */
  private FrequencyCounts frequencyCounts(
      String customerRef, CareReminderType type, OffsetDateTime windowStart) {
    var row =
        sql.fetchOne(
            "select count(*) filter (where t.status='SENT') as sent,"
                + " count(*) filter (where t.status='QUEUED') as queued"
                + " from crm_care_reminder_task t"
                + " where t.tenant_id=? and t.customer_ref=? and t.reminder_type=?"
                + " and ((t.status='SENT' and t.sent_at >= ?::timestamptz)"
                + "   or (t.status='QUEUED' and t.planned_at >= ?::timestamptz))",
            currentTenant(),
            customerRef,
            type.name(),
            windowStart,
            windowStart);
    return new FrequencyCounts(row.get("sent", Long.class), row.get("queued", Long.class));
  }

  // ================================================================ 计划时刻与静默时段

  /** 计划发送时刻：入参给了就用入参，否则按规则的每日发送时刻算下一次。 */
  private OffsetDateTime resolvePlannedAt(
      CrmCareReminderRuleRow rule, CrmCareReminderGenerateInput input) {
    if (input != null && input.plannedAt() != null && !input.plannedAt().isBlank()) {
      return parseMoment(input.plannedAt(), "计划发送时刻");
    }
    LocalTime sendTime = LocalTime.parse(rule.sendTime());
    ZoneId zone = ZoneId.of(rule.timezone());
    OffsetDateTime base = now();
    ZonedDateTime local = base.atZoneSameInstant(zone);
    ZonedDateTime candidate =
        local
            .withHour(sendTime.getHour())
            .withMinute(sendTime.getMinute())
            .withSecond(sendTime.getSecond())
            .withNano(0);
    if (!candidate.isAfter(local)) candidate = candidate.plusDays(1);
    return candidate.toOffsetDateTime();
  }

  /**
   * 静默时段：落在里面就**顺延**到静默结束（不是拒绝 —— 静默的意思是「这段时间别发」）。
   */
  private OffsetDateTime applyQuietHours(CrmCareReminderRuleRow rule, OffsetDateTime plannedAt) {
    if (rule.quietHoursStart() == null || rule.quietHoursEnd() == null) return plannedAt;
    LocalTime start = LocalTime.parse(rule.quietHoursStart());
    LocalTime end = LocalTime.parse(rule.quietHoursEnd());
    ZoneId zone = ZoneId.of(rule.timezone());
    ZonedDateTime local = plannedAt.atZoneSameInstant(zone);
    LocalTime at = local.toLocalTime();
    boolean inside =
        start.isBefore(end)
            ? (!at.isBefore(start) && at.isBefore(end))
            : (!at.isBefore(start) || at.isBefore(end));
    if (!inside) return plannedAt;
    ZonedDateTime endOfQuiet = local.toLocalDate().atTime(end).atZone(zone);
    if (!endOfQuiet.isAfter(local)) endOfQuiet = endOfQuiet.plusDays(1);
    return endOfQuiet.toOffsetDateTime();
  }

  // ================================================================ 校验与解析

  /** 🔴 催付的平台白名单 —— 不在白名单 ⇒ 「该平台不支持催付」。 */
  private void requirePaymentDemandPlatform(CareReminderType type, String platform) {
    if (type != CareReminderType.PAYMENT_DEMAND) return;
    if (!PAYMENT_DEMAND_ALLOWED_PLATFORMS.contains(platform)) {
      throw bad(
          "该平台不支持催付：平台「"
              + platform
              + "」不在允许催付的白名单（"
              + String.join(" / ", PAYMENT_DEMAND_ALLOWED_PLATFORMS)
              + "）里。部分平台限制催付话术与频次，催付规则必须按平台配置 —— "
              + "请给这个平台单独配一条规则，或改用平台自己的催款工具。");
    }
  }

  private static CareReminderType reminderType(String raw) {
    String text = nz(raw).trim().toUpperCase(Locale.ROOT);
    for (CareReminderType type : CareReminderType.values()) {
      if (type.name().equals(text)) return type;
    }
    throw bad(
        "触达类型不对：只能是 "
            + String.join(" / ", List.of("SHIPMENT_NOTICE", "LOGISTICS_REMINDER", "PAYMENT_DEMAND",
                "REVIEW_REQUEST", "AFTERSALES_PROGRESS", "CARE_MAIL"))
            + "。");
  }

  private static String platformCode(String raw) {
    String text = nz(raw).trim().toUpperCase(Locale.ROOT);
    if (!text.matches("^[A-Z][A-Z0-9_]*$")) {
      throw bad("平台码格式不对：要用大写字母开头，例如 AMAZON / SHOPIFY。");
    }
    return text;
  }

  private static int frequencyLimit(Integer raw) {
    int value = raw == null ? 1 : raw;
    if (value < 0) throw bad("频次上限不能是负数。");
    if (value > 1000) throw bad("频次上限太大了（最多 1000）：频次上限是用来限制打扰的，不是用来放开的。");
    return value;
  }

  private static int frequencyWindow(Integer raw) {
    int value = raw == null ? 24 : raw;
    if (value < 1 || value > 8760) {
      throw bad("频次窗口必须是 1 - 8760 小时（1 小时到 1 年）。");
    }
    return value;
  }

  /** 静默时段：要么都填要么都不填；都填且相等 ⇒ 拒绝（那不是静默，是写错了）。 */
  private static LocalTime[] quietHours(String rawStart, String rawEnd) {
    String start = nz(rawStart).trim();
    String end = nz(rawEnd).trim();
    if (start.isEmpty() && end.isEmpty()) return new LocalTime[] {null, null};
    if (start.isEmpty() || end.isEmpty()) {
      throw bad("静默时段要么起止都填，要么都不填：只填一半没法判断到底哪段时间不打扰客户。");
    }
    LocalTime from = parseLocalTime(start, "静默时段起");
    LocalTime to = parseLocalTime(end, "静默时段止");
    if (from.equals(to)) {
      throw bad("静默时段的起止时刻相同：这等于没有静默时段，请检查是不是写错了。");
    }
    return new LocalTime[] {from, to};
  }

  private static LocalTime sendTime(String raw) {
    String text = nz(raw).trim();
    if (text.isEmpty()) return LocalTime.of(9, 0);
    return parseLocalTime(text, "每日发送时刻");
  }

  private static LocalTime parseLocalTime(String raw, String label) {
    try {
      return LocalTime.parse(raw.length() == 4 ? "0" + raw : raw);
    } catch (java.time.format.DateTimeParseException e) {
      throw bad(label + "「" + raw + "」格式不对：请用 HH:mm（例如 09:00、22:30）。");
    }
  }

  private static String timezone(String raw) {
    String text = nz(raw).trim();
    if (text.isEmpty()) return "UTC";
    try {
      ZoneId.of(text);
    } catch (RuntimeException e) {
      throw bad("时区「" + text + "」不是一个合法的 IANA 时区名，例如 Asia/Shanghai / UTC。");
    }
    return text;
  }

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

  /** 回看天数：生成入参优先，其次规则的 target_filter，最后默认值。 */
  private static int lookbackDays(CrmCareReminderRuleRow rule, CrmCareReminderGenerateInput input) {
    if (input != null && input.lookbackDays() != null) {
      if (input.lookbackDays() < 1 || input.lookbackDays() > 365) {
        throw bad("目标回看天数必须是 1 - 365 天。");
      }
      return input.lookbackDays();
    }
    Object fromRule = rule.targetFilter().get("lookbackDays");
    if (fromRule instanceof Number n && n.intValue() >= 1 && n.intValue() <= 365) {
      return n.intValue();
    }
    return DEFAULT_LOOKBACK_DAYS;
  }

  private static BigDecimal minAmount(CrmCareReminderRuleRow rule, CrmCareReminderGenerateInput input) {
    if (input != null && input.minAmount() != null) {
      if (input.minAmount().signum() < 0) throw bad("最低订单金额不能是负数。");
      return input.minAmount();
    }
    Object fromRule = rule.targetFilter().get("minAmount");
    if (fromRule instanceof BigDecimal d && d.signum() >= 0) return d;
    if (fromRule instanceof Number n && n.doubleValue() >= 0) return new BigDecimal(n.toString());
    return null;
  }

  private static String orderSource(CrmCareReminderRuleRow rule, CrmCareReminderGenerateInput input) {
    if (input != null && input.orderSource() != null && !input.orderSource().isBlank()) {
      return input.orderSource().trim().toUpperCase(Locale.ROOT);
    }
    Object fromRule = rule.targetFilter().get("orderSource");
    if (fromRule instanceof String s && !s.isBlank()) return s.trim().toUpperCase(Locale.ROOT);
    return null;
  }

  private static Set<String> explicitRefs(CrmCareReminderGenerateInput input) {
    Set<String> refs = new LinkedHashSet<>();
    if (input == null || input.customerRefs() == null) return refs;
    for (String value : input.customerRefs()) {
      if (value != null && !value.isBlank()) refs.add(value.trim());
    }
    return refs;
  }

  /**
   * 幂等键：规则 + 买家 + 订单 + 计划日期。
   *
   * <p>同一条规则对同一个买家、同一张订单、同一天重复生成 ⇒ 只有一条任务
   * （重复生成不重复记账）。
   */
  private static String idempotencyKey(
      UUID ruleId, String customerRef, String orderReference, OffsetDateTime plannedAt) {
    return "rule:" + ruleId
        + "|cust:" + customerRef
        + "|order:" + nz(orderReference)
        + "|day:" + plannedAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
  }

  // ================================================================ SQL 片段

  private String ruleWhere(
      String reminderType,
      String platformCode,
      String status,
      String keyword,
      List<Object> binds) {
    StringBuilder where = new StringBuilder(" where r.tenant_id = ?");
    binds.add(currentTenant());
    if (!nz(reminderType).isBlank()) {
      where.append(" and r.reminder_type = ?");
      binds.add(nz(reminderType).trim().toUpperCase(Locale.ROOT));
    }
    if (!nz(platformCode).isBlank()) {
      where.append(" and r.platform_code = ?");
      binds.add(nz(platformCode).trim().toUpperCase(Locale.ROOT));
    }
    if (!nz(status).isBlank()) {
      where.append(" and r.status = ?");
      binds.add(nz(status).trim().toUpperCase(Locale.ROOT));
    }
    if (!nz(keyword).isBlank()) {
      where.append(" and (r.rule_code ilike ? or r.rule_name ilike ?)");
      String like = "%" + nz(keyword).trim() + "%";
      binds.add(like);
      binds.add(like);
    }
    return where.toString();
  }

  private String taskWhere(
      UUID ruleId,
      String reminderType,
      String status,
      String blockCode,
      String customerRef,
      String platformCode,
      String from,
      String to,
      List<Object> binds) {
    StringBuilder where = new StringBuilder(" where t.tenant_id = ?");
    binds.add(currentTenant());
    if (ruleId != null) {
      where.append(" and t.rule_id = ?");
      binds.add(ruleId);
    }
    if (!nz(reminderType).isBlank()) {
      where.append(" and t.reminder_type = ?");
      binds.add(nz(reminderType).trim().toUpperCase(Locale.ROOT));
    }
    if (!nz(status).isBlank()) {
      where.append(" and t.status = ?");
      binds.add(nz(status).trim().toUpperCase(Locale.ROOT));
    }
    if (!nz(blockCode).isBlank()) {
      where.append(" and t.block_code = ?");
      binds.add(nz(blockCode).trim().toUpperCase(Locale.ROOT));
    }
    if (!nz(customerRef).isBlank()) {
      where.append(" and t.customer_ref = ?");
      binds.add(nz(customerRef).trim());
    }
    if (!nz(platformCode).isBlank()) {
      where.append(" and t.platform_code = ?");
      binds.add(nz(platformCode).trim().toUpperCase(Locale.ROOT));
    }
    if (!nz(from).isBlank()) {
      where.append(" and t.created_at >= ?::timestamptz");
      binds.add(parseMoment(from, "创建起点"));
    }
    if (!nz(to).isBlank()) {
      where.append(" and t.created_at < ?::timestamptz");
      binds.add(parseMoment(to, "创建终点"));
    }
    return where.toString();
  }

  private static String ruleColumns() {
    return "select r.id,r.rule_code,r.rule_name,r.reminder_type,r.platform_code,r.shop_id,r.shop_name,"
        + " r.target_filter::text as target_filter,r.frequency_limit,r.frequency_window_hours,"
        + " r.quiet_hours_start,r.quiet_hours_end,r.send_time,r.timezone,r.template_code,r.subject,"
        + " r.body,r.status,r.created_by,r.note,r.created_at,r.updated_at"
        + " from crm_care_reminder_rule r";
  }

  private static String taskColumns() {
    // 🔴 规则编码住在**规则表**上（任务只存 rule_id）⇒ 这里 join 一次，
    //   让「这条触达是哪条规则排的」在列表里一眼看得见。
    return "select t.id,t.task_no,t.rule_id,r.rule_code,t.reminder_type,t.customer_ref,"
        + " t.customer_name,t.shop_id,t.shop_name,t.platform_code,t.order_reference,t.subject,"
        + " t.body,t.transport,t.status,t.planned_at,t.sent_at,t.failure_reason,t.block_code,"
        + " t.block_reason,t.platform_receipt,t.created_by,t.note,t.created_at,t.updated_at,"
        + " (select count(*) from crm_care_reminder_stat s where s.tenant_id=t.tenant_id"
        + "   and s.task_id=t.id and s.event_type='OPEN') as open_count,"
        + " (select count(*) from crm_care_reminder_stat s where s.tenant_id=t.tenant_id"
        + "   and s.task_id=t.id and s.event_type='CLICK') as click_count"
        + " from crm_care_reminder_task t"
        + " join crm_care_reminder_rule r on r.id = t.rule_id and r.tenant_id = t.tenant_id";
  }

  // ================================================================ 行映射

  private static CrmCareReminderRuleRow rule(Record r) {
    CareReminderType type = CareReminderType.valueOf(r.get("reminder_type", String.class));
    String status = r.get("status", String.class);
    return new CrmCareReminderRuleRow(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_name", String.class),
        type.name(),
        type.label(),
        r.get("platform_code", String.class),
        r.get("shop_id", UUID.class),
        r.get("shop_name", String.class),
        parseJson(r.get("target_filter", String.class)),
        r.get("frequency_limit", Integer.class),
        r.get("frequency_window_hours", Integer.class),
        timeText(r.get("quiet_hours_start", LocalTime.class)),
        timeText(r.get("quiet_hours_end", LocalTime.class)),
        timeText(r.get("send_time", LocalTime.class)),
        r.get("timezone", String.class),
        r.get("template_code", String.class),
        r.get("subject", String.class),
        r.get("body", String.class),
        status,
        statusLabel(status),
        r.get("created_by", UUID.class),
        r.get("note", String.class),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmCareReminderTaskRow task(Record r) {
    CareReminderType type = CareReminderType.valueOf(r.get("reminder_type", String.class));
    String status = r.get("status", String.class);
    String transport = r.get("transport", String.class);
    String blockCode = r.get("block_code", String.class);
    return new CrmCareReminderTaskRow(
        r.get("id", UUID.class),
        r.get("task_no", String.class),
        r.get("rule_id", UUID.class),
        r.get("rule_code", String.class),
        type.name(),
        type.label(),
        r.get("customer_ref", String.class),
        r.get("customer_name", String.class),
        r.get("shop_id", UUID.class),
        r.get("shop_name", String.class),
        r.get("platform_code", String.class),
        r.get("order_reference", String.class),
        r.get("subject", String.class),
        r.get("body", String.class),
        transport,
        transportLabel(transport),
        status,
        statusLabel(status),
        str(r.get("planned_at", OffsetDateTime.class)),
        str(r.get("sent_at", OffsetDateTime.class)),
        r.get("failure_reason", String.class),
        blockCode,
        r.get("block_reason", String.class),
        r.get("platform_receipt", String.class),
        r.get("open_count", Long.class),
        r.get("click_count", Long.class),
        r.get("created_by", UUID.class),
        r.get("note", String.class),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmCareReminderStatEventRow statEvent(Record r) {
    String eventType = r.get("event_type", String.class);
    CareReminderEventType type = CareReminderEventType.valueOf(eventType);
    return new CrmCareReminderStatEventRow(
        r.get("id", UUID.class),
        r.get("task_id", UUID.class),
        r.get("task_no", String.class),
        eventType,
        type.label(),
        str(r.get("occurred_at", OffsetDateTime.class)),
        r.get("source", String.class),
        str(r.get("created_at", OffsetDateTime.class)));
  }

  private static String statusLabel(String status) {
    for (CareReminderStatus value : CareReminderStatus.values()) {
      if (value.name().equals(status)) return value.label();
    }
    return status;
  }

  private static String transportLabel(String transport) {
    for (CareReminderTransport value : CareReminderTransport.values()) {
      if (value.name().equals(transport)) return value.label();
    }
    return transport;
  }

  private static String timeText(LocalTime value) {
    return value == null ? null : value.toString();
  }

  private static Map<String, Object> parseJson(String raw) {
    if (raw == null || raw.isBlank()) return Map.of();
    try {
      return JSON.readValue(raw, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      return Map.of();
    }
  }

  private static String toJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("目标人群筛选条件无法序列化", e);
    }
  }

  private static String mergeNote(String existing, String addition) {
    String left = nz(existing).trim();
    String right = nz(addition).trim();
    if (left.isEmpty()) return right;
    if (right.isEmpty()) return left;
    return left + " " + right;
  }

  /** 触达任务号：CR-yyyyMMdd-000001（序列单独授权，见 V303 授权段）。 */
  private String nextTaskNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_care_reminder_task_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "CR-" + day + "-" + String.format("%06d", seq);
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

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
