package com.starcon.channel;

import static com.starcon.channel.FilingCalendarModels.*;
import static com.starcon.channel.FilingCalendarRules.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.channel.FilingCalendarRules.Period;
import com.starcon.tenancy.Ids;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.3.1.2 申报日历。
 *
 * <p>蓝图：「管理各辖区申报日历：申报周期、截止日、所需数据、状态（未开始/数据就绪/已提交/已确认），
 * 到期提醒与责任人。输入：申报周期配置；辖区要求；12.6.1.2 数据就绪状态。
 * 输出：申报日历视图 + 提醒 + 状态跟踪。关系：上游 = 16.3.1.1、12.6.1.2；下游 = 1 工作台待办。
 * <b>漏报罚款远高于系统能提供的价值</b>，日历提醒是最低成本的合规保障。」
 *
 * <p>🔴🔴 本类里有六处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>辖区不另建码表</b>：辖区码的真值源是 16.3.1.1 的
 *       {@code tax_registration_jurisdiction}（<b>同一个库</b>，本模块直接读它，
 *       <b>不复制它的行</b>），落库前由 DB 触发器钉死 ⇒ 辖区码不是自由文本。</li>
 *   <li><b>「数据就绪」是投影，不是抄一份底稿</b>：12.6.1.2 的底稿在 <b>finance 服务</b>
 *       （另一个库）⇒ 只经授权只读 HTTP API（{@link FilingDraftReadinessPort}）读。
 *       本表<b>只存状态，不存金额</b> —— 抄金额就是两个真值源。</li>
 *   <li><b>状态是受控词表 + 单调</b>：未开始 → 数据就绪 → 已提交 → 已确认，
 *       DB 触发器钉死不许倒退；推到「已提交 / 已确认」<b>必须带依据</b>（谁 / 何时 / 凭据号）。</li>
 *   <li><b>本模块不执行申报</b>：不向任何税务机构提交、不代造受理号；
 *       凭据号是<b>用户填的</b>外部凭据。</li>
 *   <li><b>责任人 = 租户内成员</b>：只存 identity 的成员逻辑 ID（跨库软引用），
 *       落库前经授权只读端口校验；<b>不复制成员姓名</b>。</li>
 *   <li><b>到期判定在服务端</b>：「是否逾期」按 DB 当前时刻算，<b>不信任客户端时间</b>；
 *       提醒清单<b>如实报总数</b>（「只取回前 N 条（共 M 条）」），不许让分页把提醒吞掉。</li>
 * </ol>
 */
@Service
public class FilingCalendarService {

  /** 状态语义（能力声明与页面共用同一句话）。 */
  private static final String STATUS_SEMANTICS =
      "🔴 状态是**受控词表**，只有四档：未开始（NOT_STARTED）/ 数据就绪（DATA_READY）/ "
          + "已提交（SUBMITTED）/ 已确认（CONFIRMED）。**推进单调**：只许往前，不许倒退 —— "
          + "日历是用来盯「有没有漏报」的，状态倒退 = 漏报被悄悄抹掉（DB 触发器钉死）。"
          + "**「已提交 / 已确认」必须带依据**：谁、何时、凭据号；系统**不代造受理号**，"
          + "凭据号是用户从税务机构那边拿到的外部凭据。"
          + "「数据就绪」由 12.6.1.2 的生效底稿**投影**而来，不是手工勾的。";

  /** 辖区语义。 */
  private static final String JURISDICTION_SEMANTICS =
      "🔴 辖区是**受控值域**，真值源是 16.3.1.1 的 tax_registration_jurisdiction 码表"
          + "（同一个库，直接读，**不复制它的行**），落库前由 DB 触发器钉死 ⇒ **辖区码不是自由文本**。"
          + "申报线（税种）另有受控词表：VAT / IOSS / SALES_TAX / GST / CONSUMPTION_TAX。"
          + "其中**只有 VAT / IOSS 有数据就绪的来源**（12.6.1.2 只做这两条申报线），"
          + "其余申报线**判不了就绪**，界面上如实写明「12.6.1.2 未覆盖该申报线」，不假装就绪。";

  /** 数据就绪语义。 */
  private static final String DATA_READINESS_SEMANTICS =
      "🔴 「数据就绪」= 该辖区该申报期**当前存在一份生效中的底稿**"
          + "（12.6.1.2 保证同时只有一份生效底稿：GENERATED 有效、SUPERSEDED 已被取代）。"
          + "它是**投影**，不是抄一份底稿：底稿的金额留在 finance，本模块**只存状态**。"
          + "🔴 **「读不到」与「没就绪」是两个状态**：上游读不到时显示「读不到」并给出原因，"
          + "**不显示成「数据没就绪」** —— 前者要去查上游，后者是「查过了，确实没有」，"
          + "处置动作完全相反。";

  /** 责任人语义。 */
  private static final String OWNER_SEMANTICS =
      "🔴 责任人是**租户内的成员**（identity 的 membership），本模块只存它的逻辑 ID"
          + "（跨库**软引用**、**不建跨库外键**），落库前经 identity 的授权只读 API 校验"
          + "它确实是本租户的成员；**成员姓名不复制**到本库，显示时现读，读不到就显示「读不到」。"
          + "租户来自**已认证成员关系**，**不信任 X-Tenant-Id 头**。";

  /** 上游说明。 */
  private static final String UPSTREAM_NOTE =
      "上游 = 16.3.1.1 税号与辖区（**同一个库**：tax_registration_jurisdiction 受控码表直接读，"
          + "**不复制、不另建码表**）+ 12.6.1.2 VAT/IOSS 申报数据（**finance 服务，另一个库** ⇒ "
          + "只经授权只读 HTTP API `GET /finance/filing-draft/drafts` 读，"
          + "**不跨库 SQL / FK / 视图**）+ identity 成员目录（责任人，只读）。";

  /** 下游说明。 */
  private static final String DOWNSTREAM_NOTE =
      "下游 = 1 工作台待办（1.1.1.x，**未接线**）：本模块只交付**提醒 + 状态**的端点与页面，"
          + "**不改**工作台页面（那是别的模块已交付的页面）。"
          + "同卷的 16.3.2.x VAT/EPR/IOSS 申报执行（16.3.2.1 已交付，其余未开发）"
          + "负责**执行申报**；本模块只负责**跟踪**日历上的状态。";

  /** 申报边界。 */
  private static final String SCOPE_NOTE =
      "🔴 本模块管的是「**什么时候该申报、到没到期、数据齐没齐、谁负责、报没报**」，"
          + "**不执行申报**：不向任何税务机构提交、不代造受理号、不算税额、不产生任何金额。"
          + "申报底稿与金额归 12.6 财务（finance 服务）；"
          + "「提交 / 确认」这两个动作的真实执行归 16.3.2.x 申报执行（未交付）。"
          + "🔴 **漏报是本模块最贵的错误**：日历视图把「逾期未提交 / 临近截止未提交」排在最前，"
          + "提醒清单**如实报总数**，不许让分页把提醒吞掉。";

  /** 提前多少天算「临近截止」。 */
  private static final int DUE_SOON_DAYS = 7;

  private final DSLContext sql;
  private final ObjectMapper json;
  private final FilingDraftReadinessPort drafts;
  private final MemberDirectorySourcePort members;

  public FilingCalendarService(
      DSLContext sql,
      ObjectMapper json,
      FilingDraftReadinessPort drafts,
      MemberDirectorySourcePort members) {
    this.sql = sql;
    this.json = json;
    this.drafts = drafts;
    this.members = members;
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  /** 🔴 <b>同源 DB 时钟</b>：判定时刻 / 创建时刻一律从这里取，不信任客户端时间。 */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static LocalDate parseDate(String raw, String field) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException e) {
      throw unprocessable(field + " 不是合法日期（应为 YYYY-MM-DD，例如 2026-01-31）");
    }
  }

  /** 🔴 解析带偏移量的 ISO 时间串（时间列是 timestamptz，值用 OffsetDateTime 绑定）。 */
  private static OffsetDateTime parseTime(String raw, String field) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (DateTimeParseException e) {
      throw unprocessable(
          field + " 不是合法时间（应为带偏移量的 ISO-8601，例如 2026-12-31T23:59:59+01:00）");
    }
  }

  private static UUID parseUuid(String raw, String field) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (RuntimeException e) {
      throw unprocessable(field + " 不是合法 ID");
    }
  }

  /** 非法 UUID 按「筛不出东西」处理而不是报错（筛选参数是用户敲进来的）。 */
  private static final UUID NO_SUCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private static UUID parseFilterUuid(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (RuntimeException e) {
      return NO_SUCH_ID;
    }
  }

  private static String offsetText(OffsetDateTime value) {
    return value == null ? "" : value.toString();
  }

  /**
   * 🔴 把瞬时按**条目自己的当地时区**显示出来。
   *
   * <p>数据库里的 {@code timestamptz} 读出来时，JDBC 会换算成 JVM 默认时区
   * （本机是 +08:00）—— 那样页面上的德国截止日会变成「2026-07-11 05:00」，
   * 看起来像差了一天。**截止日是一个当地时刻**，显示口径必须跟着条目的时区走。
   */
  private static String localText(OffsetDateTime value, String zone) {
    if (value == null) {
      return "";
    }
    try {
      return value.atZoneSameInstant(ZoneId.of(zone)).toOffsetDateTime().toString();
    } catch (RuntimeException e) {
      return value.toString();
    }
  }

  // ==================================================================
  // 辖区码表（16.3.1.1，同一个库 —— 直接读，不复制）
  // ==================================================================

  /** 辖区码 → 名称 / 税制 / 默认周期。 */
  private record JurisdictionFact(String code, String name, String taxRegime, String defaultCycle) {}

  private Map<String, JurisdictionFact> jurisdictionFacts() {
    Map<String, JurisdictionFact> facts = new LinkedHashMap<>();
    for (Record row :
        sql.fetch(
            """
            select code, name, tax_regime, default_filing_cycle
            from tax_registration_jurisdiction
            order by position, code
            """)) {
      facts.put(
          row.get("code", String.class),
          new JurisdictionFact(
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("tax_regime", String.class),
              row.get("default_filing_cycle", String.class)));
    }
    return facts;
  }

  /** 辖区码必须真实存在（服务层这一道 + DB 触发器那一道）。 */
  private JurisdictionFact requireJurisdiction(String code) {
    String value = text(code);
    if (value.isEmpty()) {
      throw unprocessable("辖区不能为空");
    }
    JurisdictionFact fact = jurisdictionFacts().get(value);
    if (fact == null) {
      throw unprocessable("辖区 " + value + " 不在受控辖区码表里（16.3.1.1）—— 辖区码不是自由文本");
    }
    return fact;
  }

  // ==================================================================
  // 能力声明 / 选项
  // ==================================================================

  /** 能力声明：上游真读得到吗、下游归谁、判定口径是什么。 */
  public FilingCalendarCapabilities capabilities(String bearer) {
    List<FilingCalendarCapabilityGap> gaps =
        List.of(
            new FilingCalendarCapabilityGap(
                "1.1.1.1",
                "我的待办（工作台待办）",
                "1 工作台与门户系统",
                false,
                "本模块**只交付提醒与状态的端点与页面**，**没有**把提醒推进工作台待办 —— "
                    + "工作台页面是别的模块已交付的，本车道不改它。"
                    + "要接待办：读本模块的 `GET /channel/filing-calendar/reminders`（未确认的提醒）。"),
            new FilingCalendarCapabilityGap(
                "16.3.2.1",
                "VAT/IOSS",
                "16 合规与风控系统",
                true,
                "已交付 16.3.2.1 的 IOSS 登记与统计；**执行申报**的动作归它，"
                    + "本模块只跟踪日历状态，不重复实现申报提交。"),
            new FilingCalendarCapabilityGap(
                "16.3.2.2",
                "EPR/包装法",
                "16 合规与风控系统",
                false,
                "未交付：EPR 申报有自己的一套周期与费用口径，本模块**不编造**它的申报线；"
                    + "EPR 辖区的申报义务请在有归属后再登记。"),
            new FilingCalendarCapabilityGap(
                "16.3.2.3",
                "销售税与 marketplace facilitator",
                "16 合规与风控系统",
                true,
                "已交付 16.3.2.3 的各州申报义务与对账；**申报执行**归它，"
                    + "本模块的 SALES_TAX 申报线只跟踪状态。"));
    List<FilingCalendarUpstream> upstreams = new ArrayList<>();
    upstreams.add(
        new FilingCalendarUpstream(
            "16.3.1.1",
            "税号与辖区",
            true,
            "16 合规与风控系统",
            "受控辖区码表 tax_registration_jurisdiction **在同一个库里**，本模块直接读、"
                + "**不复制它的行**；辖区码落库前由 DB 触发器钉死。"));
    FilingDraftReadinessPort.DraftSet draftProbe = drafts.probe(bearer);
    upstreams.add(
        new FilingCalendarUpstream(
            "12.6.1.2",
            "VAT/IOSS 申报数据",
            draftProbe.available(),
            "12 财务核算系统",
            draftProbe.available()
                ? "已连通 finance 的 `GET /finance/filing-draft/drafts`（授权只读，"
                    + "转发调用方自己的会话；租户由 finance 按已认证成员关系解析）。"
                : draftProbe.note()));
    MemberDirectorySourcePort.MemberSet memberProbe = members.members(bearer);
    upstreams.add(
        new FilingCalendarUpstream(
            "17.1",
            "成员目录（责任人）",
            memberProbe.available(),
            "17 组织权限与系统设置系统",
            memberProbe.available()
                ? "已连通 identity 的 `GET /identity/members`（授权只读）。"
                : memberProbe.note()));
    return new FilingCalendarCapabilities(
        gaps,
        upstreams,
        TAX_TYPES,
        FILING_CYCLES,
        STATUSES,
        REMINDER_KINDS,
        STATUS_SEMANTICS,
        JURISDICTION_SEMANTICS,
        DATA_READINESS_SEMANTICS,
        OWNER_SEMANTICS,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE,
        SCOPE_NOTE);
  }

  /** 筛选项：辖区选项 + 责任人候选 + 词表。 */
  public FilingCalendarOptions options(String bearer) {
    List<FilingCalendarJurisdiction> jurisdictions = new ArrayList<>();
    for (JurisdictionFact fact : jurisdictionFacts().values()) {
      jurisdictions.add(
          new FilingCalendarJurisdiction(
              fact.code(), fact.name(), "", fact.taxRegime(), fact.defaultCycle()));
    }
    MemberDirectorySourcePort.MemberSet memberSet = members.members(bearer);
    List<FilingCalendarMember> memberItems = new ArrayList<>();
    for (MemberDirectorySourcePort.MemberRef ref : memberSet.items()) {
      memberItems.add(
          new FilingCalendarMember(
              ref.membershipId().toString(), ref.username(), ref.status()));
    }
    return new FilingCalendarOptions(
        jurisdictions,
        new FilingCalendarMemberList(memberItems, memberSet.available(), memberSet.note()),
        TAX_TYPES,
        FILING_CYCLES,
        STATUSES,
        REMINDER_KINDS,
        STATUS_LABELS,
        "辖区来自 16.3.1.1 的受控码表（**不是自由输入**）；"
            + "责任人来自 identity 的成员目录（**读不到时下面会写明原因**，不是「没有成员」）。");
  }

  // ==================================================================
  // 申报周期配置
  // ==================================================================

  /** 申报周期配置清单（真分页 + 真筛选）。 */
  public FilingCalendarCycleConfigPage cycleConfigs(
      String jurisdictionCode, String taxType, String filingCycle, String status, int page, int size) {
    UUID tenant = currentTenant();
    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    if (!text(jurisdictionCode).isEmpty()) {
      where.append(" and jurisdiction_code = ?");
      params.add(text(jurisdictionCode));
    }
    if (!text(taxType).isEmpty()) {
      where.append(" and tax_type = ?");
      params.add(text(taxType));
    }
    if (!text(filingCycle).isEmpty()) {
      where.append(" and filing_cycle = ?");
      params.add(text(filingCycle));
    }
    if (!text(status).isEmpty()) {
      where.append(" and status = ?");
      params.add(text(status));
    }
    long total =
        ((Number) sql.fetchValue("select count(*) from filing_calendar_cycle_config" + where,
            params.toArray())).longValue();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(limit);
    pageParams.add(offset);
    Map<String, JurisdictionFact> facts = jurisdictionFacts();
    List<FilingCalendarCycleConfigView> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select id, jurisdiction_code, tax_type, filing_cycle, period_anchor_day,"
                + " due_offset_days, due_hour, due_timezone, reminder_lead_days, effective_from,"
                + " status, note, created_at, updated_at"
                + " from filing_calendar_cycle_config"
                + where
                + " order by jurisdiction_code, tax_type, filing_cycle"
                + " limit ? offset ?",
            pageParams.toArray())) {
      JurisdictionFact fact = facts.get(row.get("jurisdiction_code", String.class));
      items.add(
          new FilingCalendarCycleConfigView(
              row.get("id", UUID.class).toString(),
              row.get("jurisdiction_code", String.class),
              fact == null ? "" : fact.name(),
              row.get("tax_type", String.class),
              row.get("filing_cycle", String.class),
              row.get("period_anchor_day", Integer.class),
              row.get("due_offset_days", Integer.class),
              row.get("due_hour", Integer.class),
              row.get("due_timezone", String.class),
              row.get("reminder_lead_days", Integer.class),
              row.get("effective_from", LocalDate.class).toString(),
              row.get("status", String.class),
              row.get("note", String.class),
              offsetText(row.get("created_at", OffsetDateTime.class)),
              offsetText(row.get("updated_at", OffsetDateTime.class))));
    }
    return new FilingCalendarCycleConfigPage(items, (int) total, Math.max(page, 0), limit);
  }

  /** 登记一份申报周期配置。 */
  public FilingCalendarCycleConfigView createCycleConfig(FilingCalendarCycleConfigInput input) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    JurisdictionFact fact = requireJurisdiction(input.jurisdictionCode());
    String taxType = requireTaxType(input.taxType());
    String cycle = requireCycle(input.filingCycle());
    int anchorDay = requireInt(input.periodAnchorDay(), 1, 28, "期起始日", 1);
    int dueOffset = requireInt(input.dueOffsetDays(), 0, 120, "截止偏移天数", 10);
    int dueHour = requireInt(input.dueHour(), 0, 23, "截止时刻（小时）", 23);
    int leadDays = requireInt(input.reminderLeadDays(), 0, 180, "提前提醒天数", 7);
    ZoneId zone = requireZone(input.dueTimezone());
    LocalDate effectiveFrom = parseDate(input.effectiveFrom(), "生效起始日");
    if (effectiveFrom == null) {
      effectiveFrom = now.toLocalDate();
    }
    if (exists(
        "select 1 from filing_calendar_cycle_config where tenant_id = ? and jurisdiction_code = ?"
            + " and tax_type = ? and filing_cycle = ?",
        tenant, fact.code(), taxType, cycle)) {
      throw conflict("这个辖区 × 申报线 × 周期已经有一份配置了（改那一行，不要插第二条）");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into filing_calendar_cycle_config"
            + " (id,tenant_id,jurisdiction_code,tax_type,filing_cycle,period_anchor_day,"
            + " due_offset_days,due_hour,due_timezone,reminder_lead_days,effective_from,status,note,"
            + " created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::int,?::int,?::int,"
            + "?::varchar,?::int,?::date,'ACTIVE',?::text,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        fact.code(),
        taxType,
        cycle,
        anchorDay,
        dueOffset,
        dueHour,
        zone.getId(),
        leadDays,
        effectiveFrom,
        text(input.note()),
        now,
        now);
    return cycleConfig(id);
  }

  /** 改一份申报周期配置（撤下 = status=ARCHIVED，**不做物理删除**）。 */
  public FilingCalendarCycleConfigView updateCycleConfig(
      UUID id, FilingCalendarCycleConfigInput input) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    Record row = loadCycleConfig(id, tenant);
    if ("ARCHIVED".equals(row.get("status", String.class))) {
      throw unprocessable("这份配置已经撤下，**撤下的配置不可再改内容**（要改请重新登记一份）");
    }
    StringBuilder sets = new StringBuilder(" set updated_at = ?::timestamptz");
    List<Object> params = new ArrayList<>();
    params.add(now);
    if (!text(input.jurisdictionCode()).isEmpty()) {
      JurisdictionFact fact = requireJurisdiction(input.jurisdictionCode());
      sets.append(", jurisdiction_code = ?::varchar");
      params.add(fact.code());
    }
    if (!text(input.taxType()).isEmpty()) {
      sets.append(", tax_type = ?::varchar");
      params.add(requireTaxType(input.taxType()));
    }
    if (!text(input.filingCycle()).isEmpty()) {
      sets.append(", filing_cycle = ?::varchar");
      params.add(requireCycle(input.filingCycle()));
    }
    if (input.periodAnchorDay() != null) {
      sets.append(", period_anchor_day = ?::int");
      params.add(requireInt(input.periodAnchorDay(), 1, 28, "期起始日", 1));
    }
    if (input.dueOffsetDays() != null) {
      sets.append(", due_offset_days = ?::int");
      params.add(requireInt(input.dueOffsetDays(), 0, 120, "截止偏移天数", 10));
    }
    if (input.dueHour() != null) {
      sets.append(", due_hour = ?::int");
      params.add(requireInt(input.dueHour(), 0, 23, "截止时刻（小时）", 23));
    }
    if (!text(input.dueTimezone()).isEmpty()) {
      sets.append(", due_timezone = ?::varchar");
      params.add(requireZone(input.dueTimezone()).getId());
    }
    if (input.reminderLeadDays() != null) {
      sets.append(", reminder_lead_days = ?::int");
      params.add(requireInt(input.reminderLeadDays(), 0, 180, "提前提醒天数", 7));
    }
    if (!text(input.effectiveFrom()).isEmpty()) {
      sets.append(", effective_from = ?::date");
      params.add(parseDate(input.effectiveFrom(), "生效起始日"));
    }
    if (input.note() != null) {
      sets.append(", note = ?::text");
      params.add(text(input.note()));
    }
    if ("ARCHIVED".equals(text(input.status()))) {
      sets.append(", status = ?::varchar");
      params.add("ARCHIVED");
    }
    params.add(id);
    params.add(tenant);
    sql.execute(
        "update filing_calendar_cycle_config" + sets + " where id = ?::uuid and tenant_id = ?::uuid",
        params.toArray());
    return cycleConfig(id);
  }

  private FilingCalendarCycleConfigView cycleConfig(UUID id) {
    UUID tenant = currentTenant();
    Record row = loadCycleConfig(id, tenant);
    Map<String, JurisdictionFact> facts = jurisdictionFacts();
    JurisdictionFact fact = facts.get(row.get("jurisdiction_code", String.class));
    return new FilingCalendarCycleConfigView(
        id.toString(),
        row.get("jurisdiction_code", String.class),
        fact == null ? "" : fact.name(),
        row.get("tax_type", String.class),
        row.get("filing_cycle", String.class),
        row.get("period_anchor_day", Integer.class),
        row.get("due_offset_days", Integer.class),
        row.get("due_hour", Integer.class),
        row.get("due_timezone", String.class),
        row.get("reminder_lead_days", Integer.class),
        row.get("effective_from", LocalDate.class).toString(),
        row.get("status", String.class),
        row.get("note", String.class),
        offsetText(row.get("created_at", OffsetDateTime.class)),
        offsetText(row.get("updated_at", OffsetDateTime.class)));
  }

  private Record loadCycleConfig(UUID id, UUID tenant) {
    Record row =
        sql.fetchOne(
            "select id, jurisdiction_code, tax_type, filing_cycle, period_anchor_day,"
                + " due_offset_days, due_hour, due_timezone, reminder_lead_days, effective_from,"
                + " status, note, created_at, updated_at"
                + " from filing_calendar_cycle_config where id = ?::uuid and tenant_id = ?::uuid",
            id,
            tenant);
    if (row == null) {
      throw notFound();
    }
    return row;
  }

  private static String requireTaxType(String raw) {
    String value = text(raw).toUpperCase(Locale.ROOT);
    if (!TAX_TYPES.contains(value)) {
      throw unprocessable("申报线（���种）不合法：" + raw + " —— 只能是 " + String.join(" / ", TAX_TYPES));
    }
    return value;
  }

  private static String requireCycle(String raw) {
    String value = text(raw).toUpperCase(Locale.ROOT);
    if (!FILING_CYCLES.contains(value)) {
      throw unprocessable("申报周期不合法：" + raw + " —— 只能是 " + String.join(" / ", FILING_CYCLES));
    }
    return value;
  }

  private static int requireInt(Integer value, int min, int max, String field, int fallback) {
    int actual = value == null ? fallback : value;
    if (actual < min || actual > max) {
      throw unprocessable(field + "必须在 " + min + " 到 " + max + " 之间（收到 " + actual + "）");
    }
    return actual;
  }

  private static ZoneId requireZone(String raw) {
    try {
      return zoneOf(raw);
    } catch (IllegalArgumentException e) {
      throw unprocessable(e.getMessage());
    }
  }

  // ==================================================================
  // 日历条目
  // ==================================================================

  /** 日历清单（真分页 + 真筛选 + **逾期/临近截止排最前**）。 */
  public FilingCalendarEntryPage entries(
      String jurisdictionCode,
      String taxType,
      String filingCycle,
      String status,
      String keyword,
      String responsibleMembershipId,
      Integer dueWithinDays,
      int page,
      int size,
      String bearer) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    if (!text(jurisdictionCode).isEmpty()) {
      where.append(" and jurisdiction_code = ?");
      params.add(text(jurisdictionCode));
    }
    if (!text(taxType).isEmpty()) {
      where.append(" and tax_type = ?");
      params.add(text(taxType));
    }
    if (!text(filingCycle).isEmpty()) {
      where.append(" and filing_cycle = ?");
      params.add(text(filingCycle));
    }
    if (!text(status).isEmpty()) {
      where.append(" and status = ?");
      params.add(text(status));
    }
    if (!text(responsibleMembershipId).isEmpty()) {
      where.append(" and responsible_membership_id = ?::uuid");
      params.add(parseFilterUuid(responsibleMembershipId));
    }
    if (!text(keyword).isEmpty()) {
      where.append(" and (note ilike '%' || ? || '%' or submission_reference ilike '%' || ? || '%'");
      params.add(text(keyword));
      params.add(text(keyword));
      where.append(" or confirmation_reference ilike '%' || ? || '%')");
      params.add(text(keyword));
    }
    if (dueWithinDays != null) {
      where.append(" and due_at <= ?::timestamptz");
      params.add(now.plusDays(Math.max(dueWithinDays, 0)));
    }
    long total =
        ((Number) sql.fetchValue("select count(*) from filing_calendar_entry" + where,
            params.toArray())).longValue();
    long overdueCount =
        countWhere(
            where.toString(),
            params,
            " and due_at < ?::timestamptz and status not in ('SUBMITTED','CONFIRMED')",
            now);
    long dueSoonCount =
        countWhere(
            where.toString(),
            params,
            " and due_at >= ?::timestamptz and due_at <= ?::timestamptz"
                + " and status not in ('SUBMITTED','CONFIRMED')",
            now,
            now.plusDays(DUE_SOON_DAYS));
    long notSubmittedCount =
        countWhere(where.toString(), params, " and status not in ('SUBMITTED','CONFIRMED')");
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(limit);
    pageParams.add(offset);
    List<Record> rows =
        sql.fetch(
            "select id, jurisdiction_code, tax_type, filing_cycle, period_start, period_end, due_at,"
                + " due_timezone, status, status_advanced_at, required_data::text as required_data,"
                + " data_ready,"
                + " data_ready_draft_id, data_readiness_available, data_readiness_note,"
                + " data_ready_checked_at, responsible_membership_id, reminder_lead_days,"
                + " submitted_at, submitted_by, submission_reference, confirmed_at, confirmed_by,"
                + " confirmation_reference, note, created_at, updated_at"
                + " from filing_calendar_entry"
                + where
                + " order by (case when status in ('SUBMITTED','CONFIRMED') then 1 else 0 end),"
                + " due_at asc, jurisdiction_code, period_start desc"
                + " limit ? offset ?",
            pageParams.toArray());
    List<FilingCalendarEntryView> items = buildEntryViews(rows, now, bearer);
    String notice =
        total > items.size()
            ? "只取回前 " + items.size() + " 条（共 " + total + " 条）—— **日历不会替你藏掉漏报**，"
                + "还有 " + (total - items.size()) + " 条在后面。"
            : "共 " + total + " 条，已全部取回。";
    return new FilingCalendarEntryPage(
        items, (int) total, Math.max(page, 0), limit, overdueCount, dueSoonCount, notSubmittedCount,
        notice);
  }

  /**
   * 「有没有这样的行」。
   *
   * <p>jOOQ 的 {@code fetchExists} 不接受「纯 SQL + 绑定参数」这个组合，这里用
   * {@code fetchValue} 取第一行第一列。🔴 <b>注意返回值可能是 {@code 1}（Integer）而不是
   * {@code true}（Boolean）</b> —— 只认 Boolean 会把「有」判成「没有」，那会让「重复申报期」
   * 的检查静默失效。
   */
  private boolean exists(String statement, Object... params) {
    Object raw = sql.fetchValue(statement, params);
    if (raw instanceof Boolean value) {
      return value;
    }
    if (raw instanceof Number value) {
      return value.longValue() != 0L;
    }
    return Boolean.parseBoolean(String.valueOf(raw));
  }

  private long countWhere(String where, List<Object> params, String extra, Object... extraParams) {
    List<Object> all = new ArrayList<>(params);
    all.addAll(List.of(extraParams));
    Object raw = sql.fetchValue("select count(*) from filing_calendar_entry" + where + extra,
        all.toArray());
    return ((Number) raw).longValue();
  }

  /** 单条日历条目。 */
  public FilingCalendarEntryView entry(UUID id, String bearer) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    Record row = loadEntry(id, tenant);
    List<FilingCalendarEntryView> views = buildEntryViews(List.of(row), now, bearer);
    return views.get(0);
  }

  private Record loadEntry(UUID id, UUID tenant) {
    Record row =
        sql.fetchOne(
            "select id, jurisdiction_code, tax_type, filing_cycle, period_start, period_end, due_at,"
                + " due_timezone, status, status_advanced_at, required_data::text as required_data,"
                + " data_ready,"
                + " data_ready_draft_id, data_readiness_available, data_readiness_note,"
                + " data_ready_checked_at, responsible_membership_id, reminder_lead_days,"
                + " submitted_at, submitted_by, submission_reference, confirmed_at, confirmed_by,"
                + " confirmation_reference, note, created_at, updated_at"
                + " from filing_calendar_entry where id = ?::uuid and tenant_id = ?::uuid",
            id,
            tenant);
    if (row == null) {
      throw notFound();
    }
    return row;
  }

  /** 登记一条申报日历条目。 */
  public FilingCalendarEntryView createEntry(
      FilingCalendarEntryInput input, AccessService.Access actor, String bearer) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    JurisdictionFact fact = requireJurisdiction(input.jurisdictionCode());
    String taxType = requireTaxType(input.taxType());
    String cycle = requireCycle(input.filingCycle());
    LocalDate periodStart = parseDate(input.periodStart(), "申报期起");
    LocalDate periodEnd = parseDate(input.periodEnd(), "申报期止");
    if (periodStart == null || periodEnd == null) {
      throw unprocessable("申报期起止必须都填（日期，YYYY-MM-DD）");
    }
    if (periodEnd.isBefore(periodStart)) {
      throw unprocessable("申报期止不能早于申报期起");
    }
    Record config = findCycleConfig(tenant, fact.code(), taxType, cycle);
    ZoneId zone = config == null ? null : ZoneId.of(config.get("due_timezone", String.class));
    OffsetDateTime dueMoment = parseTime(input.dueAt(), "截止日");
    if (dueMoment == null) {
      if (config == null) {
        throw unprocessable(
            "没有给截止日，**也没有**这个辖区 × 申报线 × 周期的周期配置可供推算 —— "
                + "请先登记周期配置，或直接给截止日");
      }
      dueMoment =
          dueAt(
              periodEnd,
              config.get("due_offset_days", Integer.class),
              config.get("due_hour", Integer.class),
              zone);
    } else if (zone == null) {
      // 没配置 ⇒ 截止日自带偏移量，就用它当显示口径（'+02:00' 这种固定偏移也是合法时区名）
      zone = ZoneId.of(dueMoment.getOffset().getId());
    }
    int leadDays =
        input.reminderLeadDays() != null
            ? requireInt(input.reminderLeadDays(), 0, 180, "提前提醒天数", 7)
            : (config == null
                ? 7
                : config.get("reminder_lead_days", Integer.class));
    UUID responsible = resolveResponsible(input.responsibleMembershipId(), actor, bearer);
    List<FilingCalendarRequiredData> requiredData =
        input.requiredData() == null ? List.of() : input.requiredData();
    if (exists(
        "select 1 from filing_calendar_entry where tenant_id = ? and jurisdiction_code = ?"
            + " and tax_type = ? and filing_cycle = ? and period_start = ?::date and period_end = ?::date",
        tenant,
        fact.code(),
        taxType,
        cycle,
        periodStart,
        periodEnd)) {
      throw conflict("这个辖区 × 申报线 × 周期 × 申报期已经有一条了（改那一行，不要插第二条）");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into filing_calendar_entry"
            + " (id,tenant_id,jurisdiction_code,tax_type,filing_cycle,period_start,period_end,due_at,"
            + " due_timezone,status,status_advanced_at,required_data,data_ready,data_readiness_available,"
            + " data_readiness_note,responsible_membership_id,reminder_lead_days,note,"
            + " created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::date,?::date,?::timestamptz,"
            + " ?::varchar,'NOT_STARTED',?::timestamptz,?::jsonb,false,false,?::text,?::uuid,?::int,"
            + " ?::text,?::timestamptz,?::timestamptz)",
        id,
        tenant,
        fact.code(),
        taxType,
        cycle,
        periodStart,
        periodEnd,
        dueMoment,
        zone.getId(),
        now,
        requiredDataJson(requiredData),
        "还没核对过数据就绪状态（12.6.1.2 申报底稿）。",
        responsible,
        leadDays,
        text(input.note()),
        now,
        now);
    materializeReminders(id, tenant, dueMoment, leadDays, now);
    return entry(id, bearer);
  }

  /** 改一条申报日历条目（状态推进走 {@link #advance}，不在这里）。 */
  public FilingCalendarEntryView updateEntry(
      UUID id, FilingCalendarEntryInput input, AccessService.Access actor, String bearer) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    Record existing = loadEntry(id, tenant);
    String currentStatus = existing.get("status", String.class);
    boolean pristine = "NOT_STARTED".equals(currentStatus);
    StringBuilder sets = new StringBuilder(" set updated_at = ?::timestamptz");
    List<Object> params = new ArrayList<>();
    params.add(now);
    boolean identityChanged = false;
    if (!text(input.jurisdictionCode()).isEmpty()) {
      JurisdictionFact fact = requireJurisdiction(input.jurisdictionCode());
      if (!fact.code().equals(existing.get("jurisdiction_code", String.class))) {
        identityChanged = true;
      }
      sets.append(", jurisdiction_code = ?::varchar");
      params.add(fact.code());
    }
    if (!text(input.taxType()).isEmpty()) {
      String taxType = requireTaxType(input.taxType());
      if (!taxType.equals(existing.get("tax_type", String.class))) {
        identityChanged = true;
      }
      sets.append(", tax_type = ?::varchar");
      params.add(taxType);
    }
    if (!text(input.filingCycle()).isEmpty()) {
      String cycle = requireCycle(input.filingCycle());
      if (!cycle.equals(existing.get("filing_cycle", String.class))) {
        identityChanged = true;
      }
      sets.append(", filing_cycle = ?::varchar");
      params.add(cycle);
    }
    LocalDate periodStart = parseDate(input.periodStart(), "申报期起");
    LocalDate periodEnd = parseDate(input.periodEnd(), "申报期止");
    if (periodStart != null && !periodStart.equals(existing.get("period_start", LocalDate.class))) {
      identityChanged = true;
    }
    if (periodEnd != null && !periodEnd.equals(existing.get("period_end", LocalDate.class))) {
      identityChanged = true;
    }
    if (identityChanged && !pristine) {
      throw unprocessable(
          "申报义务的身份字段（辖区 / 申报线 / 周期 / 申报期）**在开始申报义务之后不可改** —— "
              + "改它就等于改写「当初那一次申报是什么时候的事」。要改请新建一条。");
    }
    if (periodStart != null) {
      sets.append(", period_start = ?::date");
      params.add(periodStart);
    }
    if (periodEnd != null) {
      sets.append(", period_end = ?::date");
      params.add(periodEnd);
    }
    if (periodStart != null && periodEnd != null && periodEnd.isBefore(periodStart)) {
      throw unprocessable("申报期止不能早于申报期起");
    }
    OffsetDateTime dueMoment = parseTime(input.dueAt(), "截止日");
    if (dueMoment != null) {
      Record effectiveConfig =
          findCycleConfig(
              tenant,
              text(input.jurisdictionCode()).isEmpty()
                  ? existing.get("jurisdiction_code", String.class)
                  : requireJurisdiction(input.jurisdictionCode()).code(),
              text(input.taxType()).isEmpty()
                  ? existing.get("tax_type", String.class)
                  : requireTaxType(input.taxType()),
              text(input.filingCycle()).isEmpty()
                  ? existing.get("filing_cycle", String.class)
                  : requireCycle(input.filingCycle()));
      sets.append(", due_at = ?::timestamptz, due_timezone = ?::varchar");
      params.add(dueMoment);
      params.add(
          effectiveConfig == null
              ? ZoneId.of(dueMoment.getOffset().getId()).getId()
              : effectiveConfig.get("due_timezone", String.class));
    }
    if (input.requiredData() != null) {
      sets.append(", required_data = ?::jsonb");
      params.add(requiredDataJson(input.requiredData()));
    }
    if (input.responsibleMembershipId() != null) {
      UUID responsible = resolveResponsible(input.responsibleMembershipId(), actor, bearer);
      sets.append(", responsible_membership_id = ?::uuid");
      params.add(responsible);
    }
    int leadDays =
        input.reminderLeadDays() != null
            ? requireInt(input.reminderLeadDays(), 0, 180, "提前提醒天数", 7)
            : existing.get("reminder_lead_days", Integer.class);
    if (input.reminderLeadDays() != null) {
      sets.append(", reminder_lead_days = ?::int");
      params.add(leadDays);
    }
    if (input.note() != null) {
      sets.append(", note = ?::text");
      params.add(text(input.note()));
    }
    params.add(id);
    params.add(tenant);
    sql.execute(
        "update filing_calendar_entry" + sets + " where id = ?::uuid and tenant_id = ?::uuid",
        params.toArray());
    if (dueMoment != null || input.reminderLeadDays() != null) {
      OffsetDateTime effectiveDue =
          dueMoment != null ? dueMoment : existing.get("due_at", OffsetDateTime.class);
      sql.execute(
          "delete from filing_calendar_reminder where tenant_id = ?::uuid and entry_id = ?::uuid",
          tenant,
          id);
      materializeReminders(id, tenant, effectiveDue, leadDays, now);
    }
    return entry(id, bearer);
  }

  /**
   * 按周期配置**批量生成**当前申报期的日历条目。
   *
   * @param referenceDate 参考日（留空 = 今天，**服务端时钟**）
   */
  public FilingCalendarGenerateResult generateEntries(
      String referenceDate, AccessService.Access actor, String bearer) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    LocalDate reference = parseDate(referenceDate, "参考日");
    if (reference == null) {
      reference = now.toLocalDate();
    }
    int created = 0;
    int skipped = 0;
    List<UUID> touched = new ArrayList<>();
    for (Record config :
        sql.fetch(
            "select id, jurisdiction_code, tax_type, filing_cycle, period_anchor_day, due_offset_days,"
                + " due_hour, due_timezone, reminder_lead_days, effective_from"
                + " from filing_calendar_cycle_config where tenant_id = ?::uuid and status = 'ACTIVE'"
                + " order by jurisdiction_code, tax_type, filing_cycle",
            tenant)) {
      LocalDate effectiveFrom = config.get("effective_from", LocalDate.class);
      if (effectiveFrom.isAfter(reference)) {
        continue;
      }
      Period period =
          periodFor(
              reference,
              effectiveFrom,
              config.get("period_anchor_day", Integer.class),
              config.get("filing_cycle", String.class));
      String jurisdiction = config.get("jurisdiction_code", String.class);
      String taxType = config.get("tax_type", String.class);
      String cycle = config.get("filing_cycle", String.class);
      if (exists(
          "select 1 from filing_calendar_entry where tenant_id = ?::uuid and jurisdiction_code = ?"
              + " and tax_type = ? and filing_cycle = ? and period_start = ?::date"
              + " and period_end = ?::date",
          tenant,
          jurisdiction,
          taxType,
          cycle,
          period.start(),
          period.end())) {
        skipped++;
        continue;
      }
      OffsetDateTime dueMoment =
          dueAt(
              period.end(),
              config.get("due_offset_days", Integer.class),
              config.get("due_hour", Integer.class),
              ZoneId.of(config.get("due_timezone", String.class)));
      UUID id = Ids.next();
      sql.execute(
          "insert into filing_calendar_entry"
              + " (id,tenant_id,jurisdiction_code,tax_type,filing_cycle,period_start,period_end,due_at,"
              + " due_timezone,status,status_advanced_at,required_data,data_ready,"
              + " data_readiness_available,data_readiness_note,reminder_lead_days,note,created_at,"
              + " updated_at)"
              + " values (?::uuid,?::uuid,?::varchar,?::varchar,?::varchar,?::date,?::date,"
              + " ?::timestamptz,?::varchar,'NOT_STARTED',?::timestamptz,?::jsonb,false,false,?::text,"
              + " ?::int,?::text,?::timestamptz,?::timestamptz)",
          id,
          tenant,
          jurisdiction,
          taxType,
          cycle,
          period.start(),
          period.end(),
          dueMoment,
          ZoneId.of(config.get("due_timezone", String.class)).getId(),
          now,
          "[]",
          "还没核对过数据就绪状态（12.6.1.2 申报底稿）。",
          config.get("reminder_lead_days", Integer.class),
          "按申报周期配置自动生成。",
          now,
          now);
      materializeReminders(
          id,
          tenant,
          dueMoment,
          config.get("reminder_lead_days", Integer.class),
          now);
      created++;
      touched.add(id);
    }
    List<FilingCalendarEntryView> items = new ArrayList<>();
    OffsetDateTime viewNow = dbNow();
    for (UUID id : touched) {
      items.add(entry(id, bearer));
    }
    return new FilingCalendarGenerateResult(
        created,
        skipped,
        items,
        "按**生效中的申报周期配置**生成当前申报期的条目（参考日 " + reference + "）。"
            + "新建 " + created + " 条，跳过 " + skipped + " 条（那一期已经有了）。"
            + "生成后请**核对数据就绪状态**（读 12.6.1.2 的生效底稿）。");
  }

  /**
   * 推进状态。
   *
   * @param id 条目 ID
   * @param input 目标状态 + 凭据号
   */
  public FilingCalendarEntryView advance(
      UUID id, FilingCalendarAdvanceInput input, AccessService.Access actor, String bearer) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    Record existing = loadEntry(id, tenant);
    String from = existing.get("status", String.class);
    String target = text(input.targetStatus()).toUpperCase(Locale.ROOT);
    if (!Set.of("DATA_READY", "SUBMITTED", "CONFIRMED").contains(target)) {
      throw unprocessable(
          "目标状态不合法：" + input.targetStatus() + " —— 只能是 DATA_READY / SUBMITTED / CONFIRMED"
              + "（NOT_STARTED 是初始状态，不是推进目标）");
    }
    if (!canAdvance(from, target)) {
      throw unprocessable(
          "状态不能倒退（当前 " + statusLabel(from) + " → 目标 " + statusLabel(target) + "）—— "
              + "日历是用来盯漏报的，倒退等于把漏报抹掉");
    }
    StringBuilder sets = new StringBuilder(" set status = ?::varchar, status_advanced_at = ?::timestamptz,"
        + " updated_at = ?::timestamptz");
    List<Object> params = new ArrayList<>();
    params.add(target);
    params.add(now);
    params.add(now);
    UUID membership = actor == null ? null : actor.membershipId();
    switch (target) {
      case "DATA_READY" -> {
        ReadinessProjection projection = projectReadiness(existing, bearer, now);
        if (!projection.dataReady()) {
          throw unprocessable(
              "这个辖区这个申报期**当前没有一份生效中的底稿**（12.6.1.2）—— 不能标成「数据就绪」。"
                  + projection.note());
        }
        sets.append(", data_ready = ?, data_ready_draft_id = ?::uuid, data_ready_checked_at = ?::timestamptz,"
            + " data_readiness_available = true, data_readiness_note = ?::text");
        params.add(true);
        params.add(projection.draftId());
        params.add(now);
        params.add(projection.note());
      }
      case "SUBMITTED" -> {
        String reference = text(input.reference());
        if (reference.isEmpty()) {
          throw unprocessable(
              "推进到「已提交」必须给**凭据号**（谁、何时、凭据号）—— "
                  + "系统**不代造受理号**，凭据号是你从税务机构那边拿到的");
        }
        sets.append(", submitted_at = ?::timestamptz, submitted_by = ?::uuid,"
            + " submission_reference = ?::varchar");
        params.add(now);
        params.add(membership);
        params.add(reference);
      }
      case "CONFIRMED" -> {
        if (statusRank(from) < statusRank("SUBMITTED")) {
          throw unprocessable("必须先标记为「已提交」才能标「已确认」（当前 " + statusLabel(from) + "）");
        }
        String reference = text(input.reference());
        if (reference.isEmpty()) {
          throw unprocessable("推进到「已确认」必须给**确认凭据号**（谁、何时、凭据号）");
        }
        sets.append(", confirmed_at = ?::timestamptz, confirmed_by = ?::uuid,"
            + " confirmation_reference = ?::varchar");
        params.add(now);
        params.add(membership);
        params.add(reference);
      }
      default -> throw unprocessable("目标状态不合法");
    }
    if (input.note() != null && !text(input.note()).isEmpty()) {
      sets.append(", note = ?::text");
      params.add(text(input.note()));
    }
    params.add(id);
    params.add(tenant);
    sql.execute(
        "update filing_calendar_entry" + sets + " where id = ?::uuid and tenant_id = ?::uuid",
        params.toArray());
    if (statusRank(target) >= statusRank("SUBMITTED")) {
      sql.execute(
          "delete from filing_calendar_reminder where tenant_id = ?::uuid and entry_id = ?::uuid"
              + " and acknowledged_at is null",
          tenant,
          id);
    }
    return entry(id, bearer);
  }

  /**
   * 刷新**全部**条目的数据就绪投影（读 12.6.1.2 的生效底稿）。
   *
   * @return 被刷新的条目数
   */
  public int refreshReadiness(String bearer) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    List<Record> rows =
        sql.fetch(
            "select id, jurisdiction_code, tax_type, period_start, period_end, status"
                + " from filing_calendar_entry where tenant_id = ?::uuid",
            tenant);
    int refreshed = 0;
    Map<String, FilingDraftReadinessPort.DraftSet> cache = new LinkedHashMap<>();
    for (Record row : rows) {
      String jurisdiction = row.get("jurisdiction_code", String.class);
      String taxType = row.get("tax_type", String.class);
      String key = jurisdiction + "|" + taxType;
      FilingDraftReadinessPort.DraftSet set = cache.get(key);
      if (set == null) {
        set = drafts.liveDrafts(jurisdiction, taxType, bearer);
        cache.put(key, set);
      }
      ReadinessProjection projection = projectReadiness(row, set, now);
      sql.execute(
          "update filing_calendar_entry set data_ready = ?, data_ready_draft_id = ?::uuid,"
              + " data_ready_checked_at = ?::timestamptz, data_readiness_available = ?,"
              + " data_readiness_note = ?::text, updated_at = ?::timestamptz"
              + " where id = ?::uuid and tenant_id = ?::uuid",
          projection.dataReady(),
          projection.draftId(),
          now,
          projection.available(),
          projection.note(),
          now,
          row.get("id", UUID.class),
          tenant);
      if (projection.dataReady()
          && "NOT_STARTED".equals(row.get("status", String.class))) {
        sql.execute(
            "update filing_calendar_entry set status = 'DATA_READY', status_advanced_at = ?::timestamptz"
                + " where id = ?::uuid and tenant_id = ?::uuid and status = 'NOT_STARTED'",
            now,
            row.get("id", UUID.class),
            tenant);
      }
      refreshed++;
    }
    return refreshed;
  }

  // ==================================================================
  // 数据就绪投影
  // ==================================================================

  /**
   * 一次就绪判定的结果。
   *
   * @param available 上游读到了吗
   * @param dataReady 有没有一份生效中的底稿
   * @param draftId 命中的底稿 ID（null = 没有）
   * @param note 原因说明
   */
  private record ReadinessProjection(
      boolean available, boolean dataReady, UUID draftId, String note) {}

  private ReadinessProjection projectReadiness(Record entryRow, String bearer, OffsetDateTime now) {
    String jurisdiction = entryRow.get("jurisdiction_code", String.class);
    String taxType = entryRow.get("tax_type", String.class);
    FilingDraftReadinessPort.DraftSet set = drafts.liveDrafts(jurisdiction, taxType, bearer);
    return projectReadiness(entryRow, set, now);
  }

  private ReadinessProjection projectReadiness(
      Record entryRow, FilingDraftReadinessPort.DraftSet set, OffsetDateTime now) {
    String jurisdiction = entryRow.get("jurisdiction_code", String.class);
    String taxType = entryRow.get("tax_type", String.class);
    LocalDate periodStart = entryRow.get("period_start", LocalDate.class);
    LocalDate periodEnd = entryRow.get("period_end", LocalDate.class);
    if (!set.available()) {
      return new ReadinessProjection(false, false, null, set.note());
    }
    if (!Set.of("VAT", "IOSS").contains(taxType)) {
      return new ReadinessProjection(
          true,
          false,
          null,
          "12.6.1.2 只做 VAT / IOSS 两条申报线 —— **" + taxTypeLabel(taxType) + " 没有数据就绪的来源**，"
              + "判不了就绪（不是「没就绪」）。");
    }
    for (FilingDraftReadinessPort.DraftRef draft : set.drafts()) {
      if (!jurisdiction.equals(draft.jurisdictionCode())
          || !taxType.equals(draft.taxType())) {
        continue;
      }
      if (periodStart.toString().equals(draft.periodStart())
          && periodEnd.toString().equals(draft.periodEnd())) {
        return new ReadinessProjection(
            true,
            true,
            draft.id(),
            "12.6.1.2 有一份**生效中**的底稿覆盖这个申报期（底稿 " + draft.id() + "）。");
      }
    }
    return new ReadinessProjection(
        true,
        false,
        null,
        "这个辖区这个申报期**当前没有生效中的底稿**（12.6.1.2 里该辖区该申报线共有 "
            + set.drafts().size() + " 份其他期间的生效底稿）。");
  }

  // ==================================================================
  // 提醒
  // ==================================================================

  /** 到期提醒清单（真分页 + 真筛选，**如实报总数**）。 */
  public FilingCalendarReminderPage reminders(
      String kind,
      String jurisdictionCode,
      Boolean acknowledged,
      Integer withinDays,
      int page,
      int size,
      String bearer) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    StringBuilder where = new StringBuilder(" where r.tenant_id = ?::uuid");
    List<Object> params = new ArrayList<>();
    params.add(tenant);
    if (!text(kind).isEmpty()) {
      where.append(" and r.kind = ?::varchar");
      params.add(text(kind));
    }
    if (!text(jurisdictionCode).isEmpty()) {
      where.append(" and e.jurisdiction_code = ?::varchar");
      params.add(text(jurisdictionCode));
    }
    if (acknowledged != null) {
      where.append(acknowledged ? " and r.acknowledged_at is not null" : " and r.acknowledged_at is null");
    }
    if (withinDays != null) {
      where.append(" and r.remind_at <= ?::timestamptz");
      params.add(now.plusDays(Math.max(withinDays, 0)));
    }
    long total =
        ((Number)
                sql.fetchValue(
                    "select count(*) from filing_calendar_reminder r"
                        + " join filing_calendar_entry e on e.id = r.entry_id and e.tenant_id = r.tenant_id"
                        + where,
                    params.toArray()))
            .longValue();
    long unacknowledged =
        ((Number)
                sql.fetchValue(
                    "select count(*) from filing_calendar_reminder r"
                        + " join filing_calendar_entry e on e.id = r.entry_id and e.tenant_id = r.tenant_id"
                        + where
                        + (acknowledged != null && acknowledged ? "" : " and r.acknowledged_at is null"),
                    params.toArray()))
            .longValue();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> pageParams = new ArrayList<>(params);
    pageParams.add(limit);
    pageParams.add(offset);
    List<Record> rows =
        sql.fetch(
            "select r.id, r.entry_id, r.kind, r.remind_at, r.acknowledged_at, r.acknowledged_by,"
                + " e.jurisdiction_code, e.tax_type, e.filing_cycle, e.period_start, e.period_end,"
                + " e.due_at, e.due_timezone, e.status, e.responsible_membership_id"
                + " from filing_calendar_reminder r"
                + " join filing_calendar_entry e on e.id = r.entry_id and e.tenant_id = r.tenant_id"
                + where
                + " order by (case when r.acknowledged_at is null then 0 else 1 end), r.remind_at asc"
                + " limit ? offset ?",
            pageParams.toArray());
    List<FilingCalendarReminderView> items = buildReminderViews(rows, bearer);
    String notice =
        total > items.size()
            ? "只取回前 " + items.size() + " 条（共 " + total + " 条，未确认 " + unacknowledged
                + " 条）—— **到期提醒不能被分页吞掉**，还有 " + (total - items.size()) + " 条在后面。"
            : "共 " + total + " 条，未确认 " + unacknowledged + " 条，已全部取回。";
    return new FilingCalendarReminderPage(
        items, (int) total, Math.max(page, 0), limit, unacknowledged, notice);
  }

  /** 确认一条提醒「已知悉」。 */
  public FilingCalendarReminderView acknowledgeReminder(
      UUID id, AccessService.Access actor, String bearer) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    Record row =
        sql.fetchOne(
            "select r.id, r.entry_id, r.kind, r.remind_at, r.acknowledged_at, r.acknowledged_by,"
                + " e.jurisdiction_code, e.tax_type, e.filing_cycle, e.period_start, e.period_end,"
                + " e.due_at, e.due_timezone, e.status, e.responsible_membership_id"
                + " from filing_calendar_reminder r"
                + " join filing_calendar_entry e on e.id = r.entry_id and e.tenant_id = r.tenant_id"
                + " where r.id = ?::uuid and r.tenant_id = ?::uuid",
            id,
            tenant);
    if (row == null) {
      throw notFound();
    }
    sql.execute(
        "update filing_calendar_reminder set acknowledged_at = ?::timestamptz, acknowledged_by = ?::uuid"
            + " where id = ?::uuid and tenant_id = ?::uuid",
        now,
        actor == null ? null : actor.membershipId(),
        id,
        tenant);
    Record updated =
        sql.fetchOne(
            "select r.id, r.entry_id, r.kind, r.remind_at, r.acknowledged_at, r.acknowledged_by,"
                + " e.jurisdiction_code, e.tax_type, e.filing_cycle, e.period_start, e.period_end,"
                + " e.due_at, e.due_timezone, e.status, e.responsible_membership_id"
                + " from filing_calendar_reminder r"
                + " join filing_calendar_entry e on e.id = r.entry_id and e.tenant_id = r.tenant_id"
                + " where r.id = ?::uuid and r.tenant_id = ?::uuid",
            id,
            tenant);
    return buildReminderViews(List.of(updated), bearer).get(0);
  }

  /** 派生提醒行（提前提醒 + 截止提醒）。 */
  private void materializeReminders(
      UUID entryId, UUID tenant, OffsetDateTime dueAt, int leadDays, OffsetDateTime now) {
    if (leadDays > 0) {
      sql.execute(
          "insert into filing_calendar_reminder (id,tenant_id,entry_id,kind,remind_at,created_at)"
              + " values (?::uuid,?::uuid,?::uuid,'DUE_SOON',?::timestamptz,?::timestamptz)"
              + " on conflict (tenant_id,entry_id,kind) do update set remind_at = excluded.remind_at",
          Ids.next(),
          tenant,
          entryId,
          dueAt.minusDays(leadDays),
          now);
    }
    sql.execute(
        "insert into filing_calendar_reminder (id,tenant_id,entry_id,kind,remind_at,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,'DUE',?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id,entry_id,kind) do update set remind_at = excluded.remind_at",
        Ids.next(),
        tenant,
        entryId,
        dueAt,
        now);
  }

  // ==================================================================
  // 视图装配
  // ==================================================================

  private List<FilingCalendarEntryView> buildEntryViews(
      List<Record> rows, OffsetDateTime now, String bearer) {
    if (rows.isEmpty()) {
      return List.of();
    }
    Set<UUID> responsibleIds = new LinkedHashSet<>();
    for (Record row : rows) {
      UUID id = row.get("responsible_membership_id", UUID.class);
      if (id != null) {
        responsibleIds.add(id);
      }
    }
    Map<UUID, MemberDirectorySourcePort.MemberRef> memberMap = new LinkedHashMap<>();
    MemberDirectorySourcePort.MemberSet memberSet = null;
    if (!responsibleIds.isEmpty()) {
      memberSet = members.members(bearer);
      for (MemberDirectorySourcePort.MemberRef ref : memberSet.items()) {
        memberMap.put(ref.membershipId(), ref);
      }
    }
    Map<String, JurisdictionFact> facts = jurisdictionFacts();
    List<FilingCalendarEntryView> items = new ArrayList<>();
    for (Record row : rows) {
      JurisdictionFact fact = facts.get(row.get("jurisdiction_code", String.class));
      UUID responsible = row.get("responsible_membership_id", UUID.class);
      MemberDirectorySourcePort.MemberRef responsibleRef =
          responsible == null ? null : memberMap.get(responsible);
      boolean responsibleAvailable = responsible != null && responsibleRef != null;
      String responsibleNote = "";
      if (responsible == null) {
        responsibleNote = "这条申报义务**还没指定责任人**。";
      } else if (!responsibleAvailable) {
        responsibleNote =
            memberSet == null
                ? "成员目录没读到（责任人姓名**读不到**，不是「没有责任人」）。"
                : memberSet.note();
      }
      OffsetDateTime dueAt = row.get("due_at", OffsetDateTime.class);
      items.add(
          new FilingCalendarEntryView(
              row.get("id", UUID.class).toString(),
              row.get("jurisdiction_code", String.class),
              fact == null ? "" : fact.name(),
              row.get("tax_type", String.class),
              row.get("filing_cycle", String.class),
              row.get("period_start", LocalDate.class).toString(),
              row.get("period_end", LocalDate.class).toString(),
              localText(dueAt, row.get("due_timezone", String.class)),
              row.get("due_timezone", String.class),
              row.get("status", String.class),
              statusLabel(row.get("status", String.class)),
              offsetText(row.get("status_advanced_at", OffsetDateTime.class)),
              requiredDataOf(row),
              Boolean.TRUE.equals(row.get("data_ready", Boolean.class)),
              row.get("data_ready_draft_id", UUID.class) == null
                  ? ""
                  : row.get("data_ready_draft_id", UUID.class).toString(),
              Boolean.TRUE.equals(row.get("data_readiness_available", Boolean.class)),
              row.get("data_readiness_note", String.class),
              offsetText(row.get("data_ready_checked_at", OffsetDateTime.class)),
              responsible == null ? "" : responsible.toString(),
              responsibleAvailable ? responsibleRef.username() : "",
              responsibleAvailable,
              responsibleNote,
              row.get("reminder_lead_days", Integer.class),
              daysToDue(dueAt, now),
              overdue(dueAt, now),
              offsetText(row.get("submitted_at", OffsetDateTime.class)),
              row.get("submitted_by", UUID.class) == null
                  ? ""
                  : row.get("submitted_by", UUID.class).toString(),
              row.get("submission_reference", String.class),
              offsetText(row.get("confirmed_at", OffsetDateTime.class)),
              row.get("confirmed_by", UUID.class) == null
                  ? ""
                  : row.get("confirmed_by", UUID.class).toString(),
              row.get("confirmation_reference", String.class),
              row.get("note", String.class),
              offsetText(row.get("created_at", OffsetDateTime.class)),
              offsetText(row.get("updated_at", OffsetDateTime.class))));
    }
    return items;
  }

  private List<FilingCalendarReminderView> buildReminderViews(List<Record> rows, String bearer) {
    if (rows.isEmpty()) {
      return List.of();
    }
    Set<UUID> responsibleIds = new LinkedHashSet<>();
    for (Record row : rows) {
      UUID id = row.get("responsible_membership_id", UUID.class);
      if (id != null) {
        responsibleIds.add(id);
      }
    }
    Map<UUID, MemberDirectorySourcePort.MemberRef> memberMap = new LinkedHashMap<>();
    MemberDirectorySourcePort.MemberSet memberSet = null;
    if (!responsibleIds.isEmpty()) {
      memberSet = members.members(bearer);
      for (MemberDirectorySourcePort.MemberRef ref : memberSet.items()) {
        memberMap.put(ref.membershipId(), ref);
      }
    }
    Map<String, JurisdictionFact> facts = jurisdictionFacts();
    List<FilingCalendarReminderView> items = new ArrayList<>();
    for (Record row : rows) {
      JurisdictionFact fact = facts.get(row.get("jurisdiction_code", String.class));
      UUID responsible = row.get("responsible_membership_id", UUID.class);
      MemberDirectorySourcePort.MemberRef responsibleRef =
          responsible == null ? null : memberMap.get(responsible);
      boolean responsibleAvailable = responsible != null && responsibleRef != null;
      String responsibleNote = "";
      if (responsible == null) {
        responsibleNote = "这条申报义务**还没指定责任人**。";
      } else if (!responsibleAvailable) {
        responsibleNote =
            memberSet == null
                ? "成员目录没读到（责任人姓名**读不到**，不是「没有责任人」）。"
                : memberSet.note();
      }
      items.add(
          new FilingCalendarReminderView(
              row.get("id", UUID.class).toString(),
              row.get("entry_id", UUID.class).toString(),
              row.get("jurisdiction_code", String.class),
              fact == null ? "" : fact.name(),
              row.get("tax_type", String.class),
              row.get("filing_cycle", String.class),
              row.get("period_start", LocalDate.class).toString(),
              row.get("period_end", LocalDate.class).toString(),
              localText(
                  row.get("due_at", OffsetDateTime.class), row.get("due_timezone", String.class)),
              row.get("due_timezone", String.class),
              row.get("kind", String.class),
              reminderKindLabel(row.get("kind", String.class)),
              offsetText(row.get("remind_at", OffsetDateTime.class)),
              row.get("acknowledged_at", OffsetDateTime.class) != null,
              offsetText(row.get("acknowledged_at", OffsetDateTime.class)),
              row.get("acknowledged_by", UUID.class) == null
                  ? ""
                  : row.get("acknowledged_by", UUID.class).toString(),
              row.get("status", String.class),
              statusLabel(row.get("status", String.class)),
              responsible == null ? "" : responsible.toString(),
              responsibleAvailable ? responsibleRef.username() : "",
              responsibleAvailable,
              responsibleNote));
    }
    return items;
  }

  // ==================================================================
  // 责任人校验
  // ==================================================================

  /**
   * 🔴 落库前校验责任人：**必须真的是本租户的成员**。
   *
   * <p>两条路：① 调用方自己（已认证会话里的 membership）**永远合法**；② 其它成员要经
   * identity 的成员目录核对。目录**读不到** ⇒ 拒绝落库（而不是存一个查无此人的 id）。
   */
  private UUID resolveResponsible(
      String raw, AccessService.Access actor, String bearer) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    UUID membership = parseUuid(value, "责任人");
    if (actor != null && actor.membershipId() != null && actor.membershipId().equals(membership)) {
      return membership;
    }
    MemberDirectorySourcePort.MemberSet set = members.members(bearer);
    if (!set.available()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "成员目录**读不到**，无法校验责任人 —— "
              + set.note()
              + "（不落库一个查无此人的 id；可以先把自己设为责任人）");
    }
    for (MemberDirectorySourcePort.MemberRef ref : set.items()) {
      if (ref.membershipId().equals(membership)) {
        if (!"ACTIVE".equals(ref.status())) {
          throw unprocessable("责任人是**已停用**的成员（" + ref.username() + "）—— 换个人吧");
        }
        return membership;
      }
    }
    throw unprocessable("这个成员**不在本租户**（或不存在）—— 责任人必须是租户内的成员");
  }

  // ==================================================================
  // 周期配置查找 / JSONB
  // ==================================================================

  private Record findCycleConfig(UUID tenant, String jurisdiction, String taxType, String cycle) {
    return sql.fetchOne(
        "select id, jurisdiction_code, tax_type, filing_cycle, period_anchor_day, due_offset_days,"
            + " due_hour, due_timezone, reminder_lead_days, effective_from"
            + " from filing_calendar_cycle_config where tenant_id = ?::uuid and jurisdiction_code = ?"
            + " and tax_type = ? and filing_cycle = ? and status = 'ACTIVE'",
        tenant,
        jurisdiction,
        taxType,
        cycle);
  }

  private String requiredDataJson(List<FilingCalendarRequiredData> items) {
    try {
      return json.writeValueAsString(items == null ? List.of() : items);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw unprocessable("所需数据清单写不出去：" + e.getMessage());
    }
  }

  private List<FilingCalendarRequiredData> requiredDataOf(Record row) {
    String raw = row.get("required_data", String.class);
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    try {
      return json.readValue(raw, new TypeReference<List<FilingCalendarRequiredData>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return List.of();
    }
  }
}
