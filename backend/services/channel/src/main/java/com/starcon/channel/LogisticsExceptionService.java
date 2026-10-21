package com.starcon.channel;

import static com.starcon.channel.LogisticsExceptionModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.3.1.2 异常件。
 *
 * <p>蓝图：「识别与处理异常件：轨迹停滞、妥投失败、地址问题、清关滞留、破损丢件、
 * 买家投诉；支持<b>联系承运商、发起理赔、改址、补发</b>。输入：轨迹异常规则；被动异常标记；
 * 客服反馈。输出：异常件清单 + 处置记录 + 理赔/补发单。关系：上游 = 10.3.1.1；
 * 下游 = 11 售后（补发/退款）、15 客服、7 库存（补发出库）。
 * <b>异常件必须有人负责到底</b>，否则变成买家差评与平台纠纷。」
 *
 * <p>🔴 本类唯一不能妥协的约束是那句加粗句。它的落地是三件事同时做，缺一件就退化成
 * 「异常池」那种假活：
 *
 * <ol>
 *   <li><b>建件即有责任人</b>：{@code owner_membership_id NOT NULL}（V216）——
 *       库里不存在「无人认领的异常件」，不用「待分配」这种状态糊过去。</li>
 *   <li><b>每件都有责任时限</b>：{@code due_at} 来自规则的 resolve_sla_minutes，
 *       逾期是**可统计的事实**（只对未结案成立），不是界面上的一句话。</li>
 *   <li><b>结案必须有凭据</b>：DB CHECK 钉死「结案 ⇒ 有 closed_at 且责任人在位」，
 *       状态机由 BEFORE UPDATE 触发器守（终态不可回退）。</li>
 * </ol>
 *
 * <p>🔴 第二个不能妥协的是「来源是事实不是说法」：{@link #createCase} 的请求模型里
 * <b>没有 source 字段</b>，来源由服务端按「有没有异常节点 / 有没有客服反馈」派生。
 * 让调用方自己填来源，等于让「这批异常是怎么来的」变成一句可以随手编的话。
 *
 * <p>🔴 第三个是「下游未交付就不假装已交付」：11 售后 / 15 客服 / 7 库存**当前都不存在**，
 * 本模块<b>一条跨服务 SQL 都没有</b>，处置结果落在本服务自己的处置记录与理赔/补发单上，
 * 需要下游接手的事逐条落 {@code logistics_exception_downstream_task}，
 * 状态恒从 {@code PENDING_NOTIFY} 起步 —— 写「已通知」「已受理」是伪造凭据。
 *
 * <p>数据来源纪律：运单与轨迹的真值在 <b>10.3.1.1</b>（同库同服务，V212），
 * 本模块只经<b>同库复合外键</b>引用（带 tenant_id ⇒ 结构上不可能跨租户挂载），
 * 不重复造一份轨迹状态。
 */
@Service
public class LogisticsExceptionService {

  private final DSLContext sql;

  public LogisticsExceptionService(DSLContext sql) {
    this.sql = sql;
  }

  // ==================================================================
  // 基础工具
  // ==================================================================

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定）。 */
  private UUID currentTenant() {
    Record row = sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')");
    String raw = row == null ? null : row.get(0, String.class);
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  /** 跨租户与不存在返回<b>同一个 404 且理由逐字相同</b>。 */
  private static ResponseStatusException notFound(String what) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, what + "不存在");
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 币种必须是三个大写字母（DB 列级 CHECK 再钉一道）。 */
  private static String currency(String raw, String field) {
    String value = text(raw).toUpperCase(Locale.ROOT);
    if (!value.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, field + " 必须是三个大写字母的币种代码（例如 USD / EUR）");
    }
    return value;
  }

  private static BigDecimal positive(BigDecimal value, String field) {
    if (value == null || value.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 必须大于 0");
    }
    return value;
  }

  private static BigDecimal nonNegative(BigDecimal value, String field) {
    if (value == null || value.signum() < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 不能小于 0");
    }
    return value;
  }

  private static String enumCode(String raw, Class<? extends Enum<?>> type, String field) {
    String value = text(raw).toUpperCase(Locale.ROOT);
    for (Enum<?> e : type.getEnumConstants()) {
      if (e.name().equals(value)) {
        return value;
      }
    }
    throw new ResponseStatusException(
        HttpStatus.UNPROCESSABLE_ENTITY, field + " 取值不在白名单内：" + value);
  }

  private static String typeLabel(String raw) {
    for (ExceptionType t : ExceptionType.values()) {
      if (t.name().equals(raw)) {
        return t.label();
      }
    }
    return raw;
  }

  private static String severityLabel(String raw) {
    for (Severity s : Severity.values()) {
      if (s.name().equals(raw)) {
        return s.label();
      }
    }
    return raw;
  }

  private static String caseStatusLabel(String raw) {
    for (CaseStatus s : CaseStatus.values()) {
      if (s.name().equals(raw)) {
        return s.label();
      }
    }
    return raw;
  }

  private static String actionLabel(String raw) {
    for (ActionType a : ActionType.values()) {
      if (a.name().equals(raw)) {
        return a.label();
      }
    }
    return raw;
  }

  private static String dispositionStatusLabel(String raw) {
    for (DispositionStatus s : DispositionStatus.values()) {
      if (s.name().equals(raw)) {
        return s.label();
      }
    }
    return raw;
  }

  private static String claimTypeLabel(String raw) {
    for (ClaimType t : ClaimType.values()) {
      if (t.name().equals(raw)) {
        return t.label();
      }
    }
    return raw;
  }

  private static String claimStatusLabel(String raw) {
    for (ClaimStatus s : ClaimStatus.values()) {
      if (s.name().equals(raw)) {
        return s.label();
      }
    }
    return raw;
  }

  private static String downstreamModuleLabel(String code) {
    for (DownstreamModule m : DownstreamModule.values()) {
      if (m.code().equals(code)) {
        return m.label();
      }
    }
    return code;
  }

  private static String downstreamStatusLabel(String raw) {
    for (DownstreamStatus s : DownstreamStatus.values()) {
      if (s.name().equals(raw)) {
        return s.label();
      }
    }
    return raw;
  }

  private static String sourceLabel(String raw) {
    return switch (raw) {
      case "TRACKING_FLAG" -> "被动异常标记";
      case "RULE_SCAN" -> "轨迹异常规则";
      case "CUSTOMER_FEEDBACK" -> "客服反馈";
      case "MANUAL" -> "人工登记";
      default -> raw;
    };
  }

  private static long count(Record row) {
    return row == null ? 0L : row.get(0, Long.class);
  }

  /** 路径 / 筛选里的 UUID 参数：格式不对直接 400，不进 DB（避免 500）。 */
  private static UUID parseUuid(String raw, String message) {
    try {
      return UUID.fromString(text(raw));
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
  }

  private static Object[] concat(List<Object> args, Object... more) {
    Object[] all = new Object[args.size() + more.length];
    for (int i = 0; i < args.size(); i++) {
      all[i] = args.get(i);
    }
    for (int i = 0; i < more.length; i++) {
      all[args.size() + i] = more[i];
    }
    return all;
  }

  /**
   * 🔴 时长一律**整数分钟**（DB CHECK 禁负值）。
   *
   * <p>用 {@link Duration#toMinutes()} 而不是浮点小时：0.5 小时写成 0.5 会有二进制误差，
   * 累加几票就对不上；而负时长意味着锚点时刻算错了（事件时刻晚于判定时刻），
   * 那会让 SLA 判定整个反转 —— 所以这里<b>截断到 0 并在 note 里写明</b>，
   * 而不是让一个负数悄悄进库。
   */
  private static long minutesSince(OffsetDateTime anchor, OffsetDateTime reference) {
    if (anchor == null) {
      return 0L;
    }
    long minutes = Duration.between(anchor, reference).toMinutes();
    return Math.max(minutes, 0L);
  }

  private static BigDecimal hoursOf(long minutes) {
    return BigDecimal.valueOf(minutes)
        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public LogisticsExceptionCapabilities capabilities() {
    List<CapabilityDimension> dimensions =
        List.of(
            new CapabilityDimension(
                "exception_case",
                "异常件清单（轨迹停滞 / 妥投失败 / 地址问题 / 清关滞留 / 破损丢件 / 买家投诉）",
                true,
                "本服务 V216 logistics_exception_case",
                "",
                "建件即有责任人与责任时限；逾期是可统计的事实"),
            new CapabilityDimension(
                "exception_rule",
                "轨迹异常规则（租户级：命中条件 + 严重度 + 责任时限）",
                true,
                "本服务 V216 logistics_exception_rule",
                "",
                "🔴 规则是**租户可维护的表**不是代码常量：各承运商的停滞口径本来就不一样"),
            new CapabilityDimension(
                "passive_flag_intake",
                "被动异常标记（消费 10.3.1.1 的异常节点建件）",
                true,
                "本服务 V216 + 同库 logistics_tracking_event（V212）",
                "",
                "🔴 来源是**服务端派生的事实**，调用方填的 source 一律不认"),
            new CapabilityDimension(
                "customer_feedback_intake",
                "客服反馈建件（投诉原文入库）",
                true,
                "本服务 V216 logistics_exception_case.customer_feedback",
                "",
                "客服反馈入口 = 15 客服未交付时的**兜底登记口**，不是它的替代品"),
            new CapabilityDimension(
                "disposition",
                "处置记录（联系承运商 / 联系买家 / 联系客服 / 发起理赔 / 请求改址 / 请求补发 / 升级 / 转派）",
                true,
                "本服务 V216 logistics_exception_disposition",
                "",
                "处置留痕**append-only**：事后改写等于伪造处置史，要更正就追加新的一条"),
            new CapabilityDimension(
                "claim_and_reship_order",
                "理赔 / 补发单（金额、数量、凭据、状态机）",
                true,
                "本服务 V216 logistics_exception_claim",
                "",
                "🔴 金额与数量是 NUMERIC(19,6) + BigDecimal，禁浮点；跨币种绝不相加"),
            new CapabilityDimension(
                "responsibility",
                "🔴 责任到底：责任人 + 责任时限 + 逾期统计 + 结案凭据",
                true,
                "本服务 V216 列级 NOT NULL / CHECK + 状态机触发器",
                "",
                "库里不存在「无人认领的异常件」；结案必须有 closed_at 且责任人在位"),
            new CapabilityDimension(
                "carrier_contact_dispatch",
                "联系承运商（真正把请求发给承运商）",
                false,
                "承运商出网通道未交付（10.1.1.1 连接与凭据未交付）",
                "10.1.1.1",
                "本模块只落**联系承运商这条处置记录**（谁、什么时候、说了什么），"
                    + "**不假装消息已送达承运商**"),
            new CapabilityDimension(
                "after_sale_reship",
                "11 售后（补发 / 退款受理）",
                false,
                "下游 11 售后未交付",
                "11",
                "🔴 本模块只产出补发单 + 一条 PENDING_NOTIFY 任务，"
                    + "**不跨服务调用、不引用对方的表**"),
            new CapabilityDimension(
                "inventory_reship_issue",
                "7 库存（补发出库）",
                false,
                "下游 7 库存调整单管理未交付",
                "7",
                "🔴 同上：只产出 PENDING_NOTIFY 任务，库存一克不动"),
            new CapabilityDimension(
                "customer_service_ticket",
                "15 客服（工单 / 买家通知）",
                false,
                "下游 15 客服未交付",
                "15",
                "🔴 同上：只产出 PENDING_NOTIFY 任务"),
            new CapabilityDimension(
                "address_change_dispatch",
                "改址（10.2.1.3，trade 已交付）",
                false,
                "改址单的写入所有者是 10.2.1.3（另一个服务）",
                "10.2.1.3",
                "本模块只提一条 PENDING_NOTIFY 任务，"
                    + "**不跨服务改别人的运单文档**（改址单的写入所有者永远是 10.2.1.3）"),
            new CapabilityDimension(
                "finance_settlement",
                "12 财务（赔付入账 / 成本归集）",
                false,
                "下游 12 财务服务当前不存在",
                "12",
                "理赔金额落在本服务自有单据上，与 12 的最终对账留待 finance 落地后衔接"));
    return new LogisticsExceptionCapabilities(
        dimensions,
        ExceptionType.codes(),
        Severity.codes(),
        CaseStatus.codes(),
        ActionType.codes(),
        DispositionStatus.codes(),
        ClaimType.codes(),
        ClaimStatus.codes(),
        DownstreamModule.codes(),
        DownstreamStatus.codes(),
        "🔴 异常件**必须有人负责到底**：建件即有责任人（NOT NULL），"
            + "每件都有责任时限（due_at），逾期只对未结案成立，"
            + "结案必须有 closed_at 且责任人在位（DB CHECK 钉死）。"
            + "库里**不存在**「无人认领的异常件」。",
        "🔴 时长一律**整数分钟**（stalled_duration_minutes bigint + CHECK >= 0）："
            + "禁浮点小时数，禁负时长。负时长意味着锚点时刻算错了，"
            + "会让 SLA 判定整个反转 —— 所以宁可截断到 0 并写明，也不让负数进库。",
        "🔴 金额与数量：NUMERIC(19,6) + BigDecimal，禁浮点。"
            + "钱**只住在理赔/补发单上**，异常件本体不存金额 —— "
            + "抄一份就会出现两个真值，而「跨币种绝不相加」一旦被破坏就再也说不清。",
        "上游 = 10.3.1.1 轨迹查询（同库同服务，V212）："
            + "本模块经**同库复合外键**引用 logistics_tracking_subject / "
            + "logistics_tracking_event（带 tenant_id ⇒ 结构上不可能跨租户挂载），"
            + "**不重复造一份轨迹状态**。",
        "🔴 下游 11 售后 / 15 客服 / 7 库存**当前全部未交付**："
            + "本模块**一条跨服务 SQL 都没有**，处置结果落在本服务自有事实上，"
            + "需下游接手的事逐条落 downstream_task 且状态恒从 PENDING_NOTIFY 起步 —— "
            + "写「已通知 / 已受理」是伪造凭据。",
        "本模块站在 10.3.1.1 之上做**异常件的识别与处置**，不重做轨迹："
            + "轨迹事件、时效对比、异常节点标记都是上游 10.3.1.1 的事实。");
  }

  // ==================================================================
  // 概览
  // ==================================================================

  public LogisticsExceptionOverview overview() {
    UUID tenant = currentTenant();
    long total =
        count(
            sql.fetchOne(
                "select count(*) from logistics_exception_case where tenant_id=?::uuid", tenant));
    long open =
        statusCount(tenant, "OPEN");
    long investigating = statusCount(tenant, "INVESTIGATING");
    long actioned = statusCount(tenant, "ACTIONED");
    long resolved = statusCount(tenant, "RESOLVED");
    long voided = statusCount(tenant, "VOID");
    long overdue =
        count(
            sql.fetchOne(
                "select count(*) from logistics_exception_case"
                    + " where tenant_id=?::uuid and due_at < now()"
                    + " and status not in ('RESOLVED','VOID')",
                tenant));
    long pendingDownstream =
        count(
            sql.fetchOne(
                "select count(*) from logistics_exception_downstream_task"
                    + " where tenant_id=?::uuid and downstream_status='PENDING_NOTIFY'",
                tenant));
    long claimCount =
        count(
            sql.fetchOne(
                "select count(*) from logistics_exception_claim"
                    + " where tenant_id=?::uuid and status <> 'CANCELLED'",
                tenant));
    long reship =
        count(
            sql.fetchOne(
                "select count(*) from logistics_exception_claim"
                    + " where tenant_id=?::uuid and claim_type='RESHIP' and status <> 'CANCELLED'",
                tenant));
    long carrierClaim =
        count(
            sql.fetchOne(
                "select count(*) from logistics_exception_claim"
                    + " where tenant_id=?::uuid and claim_type='CARRIER_CLAIM' and status <> 'CANCELLED'",
                tenant));
    List<CurrencyTotal> totals = claimTotals(tenant);

    List<TypeCount> byType = new ArrayList<>();
    for (ExceptionType t : ExceptionType.values()) {
      byType.add(
          new TypeCount(
              t.name(),
              t.label(),
              count(
                  sql.fetchOne(
                      "select count(*) from logistics_exception_case"
                          + " where tenant_id=?::uuid and exception_type=?",
                      tenant,
                      t.name()))));
    }
    List<StatusCount> byStatus = new ArrayList<>();
    for (CaseStatus s : CaseStatus.values()) {
      byStatus.add(
          new StatusCount(
              s.name(), s.label(), statusCount(tenant, s.name())));
    }
    List<SeverityCount> bySeverity = new ArrayList<>();
    for (Severity s : Severity.values()) {
      bySeverity.add(
          new SeverityCount(
              s.name(),
              s.label(),
              count(
                  sql.fetchOne(
                      "select count(*) from logistics_exception_case"
                          + " where tenant_id=?::uuid and severity=?",
                      tenant,
                      s.name()))));
    }
    return new LogisticsExceptionOverview(
        total,
        open,
        investigating,
        actioned,
        resolved,
        voided,
        overdue,
        pendingDownstream,
        claimCount,
        reship,
        carrierClaim,
        totals,
        byType,
        byStatus,
        bySeverity,
        now(),
        "概览是**全量口径**，不受清单筛选与分页影响；"
            + "逾期只对未结案成立（已结案 / 已作废不再计时）。",
        "🔴 理赔金额**按币种分行**：本模块不做汇率换算，跨币种绝不相加。");
  }

  private long statusCount(UUID tenant, String status) {
    return count(
        sql.fetchOne(
            "select count(*) from logistics_exception_case"
                + " where tenant_id=?::uuid and status=?",
            tenant,
            status));
  }

  /** 理赔金额**按币种分行**（🔴 跨币种绝不相加）。 */
  private List<CurrencyTotal> claimTotals(UUID tenant) {
    return sql
        .fetch(
            "select currency, sum(amount) as amount, count(*) as claim_count"
                + "  from logistics_exception_claim"
                + " where tenant_id=?::uuid and status <> 'CANCELLED' and btrim(currency) <> ''"
                + " group by currency order by currency",
            tenant)
        .map(
            r ->
                new CurrencyTotal(
                    r.get("currency", String.class),
                    r.get("amount", BigDecimal.class),
                    r.get("claim_count", Long.class)));
  }

  // ==================================================================
  // 异常件清单
  // ==================================================================

  private static final String CASE_COLUMNS =
      "c.id,c.case_no,c.source,c.exception_type,c.severity,c.status,c.subject_id,c.source_event_id,"
          + "c.waybill_no,c.carrier_code,c.sales_order_no,c.stalled_duration_minutes,"
          + "c.owner_membership_id,c.detected_at,c.due_at,c.customer_feedback,c.closed_at,"
          + "c.close_reason,c.note,c.version,"
          + "(c.due_at < now() and c.status not in ('RESOLVED','VOID')) as overdue,"
          + "(select count(*) from logistics_exception_disposition d"
          + "  where d.tenant_id=c.tenant_id and d.case_id=c.id) as disposition_count,"
          + "(select count(*) from logistics_exception_claim cl"
          + "  where cl.tenant_id=c.tenant_id and cl.case_id=c.id) as claim_count";

  public ExceptionCasePage cases(
      String exceptionType,
      String severity,
      String status,
      String carrierCode,
      String keyword,
      String ownerMembershipId,
      boolean overdueOnly,
      int page,
      int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder(" where c.tenant_id=?::uuid");

    String et = text(exceptionType).toUpperCase(Locale.ROOT);
    if (!et.isEmpty()) {
      where.append(" and c.exception_type=?");
      args.add(et);
    }
    String sv = text(severity).toUpperCase(Locale.ROOT);
    if (!sv.isEmpty()) {
      where.append(" and c.severity=?");
      args.add(sv);
    }
    String st = text(status).toUpperCase(Locale.ROOT);
    if (!st.isEmpty()) {
      where.append(" and c.status=?");
      args.add(st);
    }
    String cc = text(carrierCode);
    if (!cc.isEmpty()) {
      where.append(" and c.carrier_code=?");
      args.add(cc);
    }
    String kw = text(keyword);
    if (!kw.isEmpty()) {
      where.append(
          " and (c.case_no ilike ? or c.waybill_no ilike ? or c.sales_order_no ilike ?"
              + " or c.note ilike ? or c.customer_feedback ilike ?)");
      for (int i = 0; i < 5; i++) {
        args.add("%" + kw + "%");
      }
    }
    String owner = text(ownerMembershipId);
    if (!owner.isEmpty()) {
      where.append(" and c.owner_membership_id=?::uuid");
      args.add(parseUuid(owner, "ownerMembershipId 必须是 UUID"));
    }
    if (overdueOnly) {
      where.append(" and c.due_at < now() and c.status not in ('RESOLVED','VOID')");
    }

    long total =
        count(sql.fetchOne("select count(*) from logistics_exception_case c" + where, args.toArray()));
    var rows =
        sql.fetch(
            "select " + CASE_COLUMNS + " from logistics_exception_case c" + where
                + " order by c.detected_at desc, c.id desc limit ? offset ?",
            concat(args, limit, offset));
    List<ExceptionCaseRow> items = rows.map(this::caseRow);
    return new ExceptionCasePage(
        items,
        Math.max(page, 0),
        limit,
        total,
        now(),
        "异常件清单 = **有责任人、有时限**的待办；同一票同一类型同时最多一件未结（DB 部分唯一索引）。",
        "🔴 逾期只对未结案成立；已结案 / 已作废的件不再计时。");
  }

  private ExceptionCaseRow caseRow(Record r) {
    long minutes = r.get("stalled_duration_minutes", Long.class);
    return new ExceptionCaseRow(
        r.get("id", UUID.class),
        r.get("case_no", String.class),
        r.get("source", String.class),
        sourceLabel(r.get("source", String.class)),
        r.get("exception_type", String.class),
        typeLabel(r.get("exception_type", String.class)),
        r.get("severity", String.class),
        severityLabel(r.get("severity", String.class)),
        r.get("status", String.class),
        caseStatusLabel(r.get("status", String.class)),
        r.get("subject_id", UUID.class),
        r.get("source_event_id", UUID.class),
        r.get("waybill_no", String.class),
        r.get("carrier_code", String.class),
        r.get("sales_order_no", String.class),
        minutes,
        hoursOf(minutes),
        r.get("owner_membership_id", UUID.class),
        r.get("detected_at", OffsetDateTime.class),
        r.get("due_at", OffsetDateTime.class),
        Boolean.TRUE.equals(r.get("overdue", Boolean.class)),
        r.get("customer_feedback", String.class),
        r.get("closed_at", OffsetDateTime.class),
        r.get("close_reason", String.class),
        r.get("disposition_count", Long.class),
        r.get("claim_count", Long.class),
        r.get("note", String.class),
        r.get("version", Long.class));
  }

  // ==================================================================
  // 异常件详情
  // ==================================================================

  public ExceptionCaseDetail caseDetail(UUID id) {
    UUID tenant = currentTenant();
    Record head =
        sql.fetchOne(
            "select " + CASE_COLUMNS + " from logistics_exception_case c"
                + " where c.tenant_id=?::uuid and c.id=?::uuid",
            tenant,
            id);
    if (head == null) {
      throw notFound("异常件");
    }
    List<ExceptionDispositionRow> dispositions =
        sql.fetch(
                "select d.*, c.case_no, c.waybill_no,"
                    + " (select count(*) from logistics_exception_downstream_task t"
                    + "   where t.tenant_id=d.tenant_id and t.disposition_id=d.id) as downstream_task_count"
                    + "  from logistics_exception_disposition d"
                    + "  join logistics_exception_case c on c.tenant_id=d.tenant_id and c.id=d.case_id"
                    + " where d.tenant_id=?::uuid and d.case_id=?::uuid"
                    + " order by d.occurred_at desc, d.created_at desc",
                tenant,
                id)
            .map(this::dispositionRow);
    List<ExceptionClaimRow> claims =
        sql.fetch(
                "select cl.*, c.case_no, c.waybill_no"
                    + "  from logistics_exception_claim cl"
                    + "  join logistics_exception_case c on c.tenant_id=cl.tenant_id and c.id=cl.case_id"
                    + " where cl.tenant_id=?::uuid and cl.case_id=?::uuid"
                    + " order by cl.created_at desc",
                tenant,
                id)
            .map(this::claimRow);
    List<DownstreamTaskRow> tasks = downstreamTasks(tenant, id);
    ExceptionUpstreamContext upstream = upstreamContext(tenant, head.get("subject_id", UUID.class));
    return new ExceptionCaseDetail(caseRow(head), dispositions, claims, tasks, upstream);
  }

  /** 上游 10.3.1.1 的轨迹上下文（只读快照，不可写）。 */
  private ExceptionUpstreamContext upstreamContext(UUID tenant, UUID subjectId) {
    if (subjectId == null) {
      return null;
    }
    Record r =
        sql.fetchOne(
            "select s.id,s.master_waybill_no,s.carrier_code,s.latest_node,s.latest_occurred_at,"
                + " s.event_count,s.exception_flag,s.last_exception_code,s.last_exception_at,s.status"
                + "  from logistics_tracking_subject s"
                + " where s.tenant_id=?::uuid and s.id=?::uuid",
            tenant,
            subjectId);
    if (r == null) {
      return null;
    }
    return new ExceptionUpstreamContext(
        r.get("id", UUID.class),
        r.get("master_waybill_no", String.class),
        r.get("carrier_code", String.class),
        r.get("latest_node", String.class),
        r.get("latest_occurred_at", OffsetDateTime.class),
        r.get("event_count", Long.class),
        Boolean.TRUE.equals(r.get("exception_flag", Boolean.class)),
        r.get("last_exception_code", String.class),
        r.get("last_exception_at", OffsetDateTime.class),
        r.get("status", String.class));
  }

  // ==================================================================
  // 登记异常件
  // ==================================================================

  /**
   * 登记一个异常件。
   *
   * <p>🔴 来源由服务端派生：有异常节点 ⇒ TRACKING_FLAG；有客服反馈 ⇒ CUSTOMER_FEEDBACK；
   * 其余 ⇒ MANUAL（规则命中那条路走 {@link #scan}，来源是 RULE_SCAN）。
   */
  public ExceptionCaseRow createCase(CreateExceptionCaseRequest request, UUID membershipId) {
    UUID tenant = currentTenant();
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }

    // ---- 规则（可选）：带出类型 / 严重度 / 责任时限 ----
    Record rule = null;
    if (request.ruleId() != null) {
      rule =
          sql.fetchOne(
              "select * from logistics_exception_rule where tenant_id=?::uuid and id=?::uuid",
              tenant,
              request.ruleId());
      if (rule == null) {
        throw notFound("异常规则");
      }
      if (!rule.get("enabled", Boolean.class)) {
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "该异常规则已停用，扫描与建件都不走它");
      }
    }

    // ---- 异常节点（可选）：被动异常标记 ----
    Record event = null;
    if (request.sourceEventId() != null) {
      event =
          sql.fetchOne(
              "select * from logistics_tracking_event where tenant_id=?::uuid and id=?::uuid",
              tenant,
              request.sourceEventId());
      if (event == null) {
        throw notFound("轨迹事件");
      }
    }

    // ---- 轨迹主体（可选）：运单快照 + 停滞锚点 ----
    UUID subjectId = request.subjectId();
    if (event != null) {
      UUID eventSubject = event.get("subject_id", UUID.class);
      if (subjectId != null && !subjectId.equals(eventSubject)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "异常节点不属于指定的轨迹主体");
      }
      subjectId = eventSubject;
    }
    Record subject = null;
    if (subjectId != null) {
      subject =
          sql.fetchOne(
              "select * from logistics_tracking_subject where tenant_id=?::uuid and id=?::uuid",
              tenant,
              subjectId);
      if (subject == null) {
        throw notFound("轨迹主体");
      }
    }

    // ---- 来源：服务端派生的事实 ----
    String feedback = text(request.customerFeedback());
    String source;
    if (event != null) {
      source = "TRACKING_FLAG";
    } else if (!feedback.isEmpty()) {
      source = "CUSTOMER_FEEDBACK";
    } else {
      source = "MANUAL";
    }
    if (source.equals("CUSTOMER_FEEDBACK") && feedback.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客服反馈建件必须给出反馈原文");
    }

    // ---- 类型 / 严重度 ----
    String type;
    String severity;
    int slaMinutes;
    if (rule != null) {
      type = rule.get("rule_type", String.class);
      severity = rule.get("severity", String.class);
      slaMinutes = rule.get("resolve_sla_minutes", Integer.class);
    } else {
      String requested = text(request.exceptionType());
      if (requested.isEmpty()) {
        // 🔴 承运商的异常码（ADDRESS_INVALID / DELIVERY_FAILED / ...）是**自由文本**，
        //    本模块**不猜**它对应哪一种异常类型 —— 猜错就是把「地址问题」记成
        //    「妥投失败」，后面所有处置都跑偏。两条正路：显式给类型，
        //    或者给一条规则（ruleId）由规则带出类型 / 严重度 / 责任时限。
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "异常类型必须显式给出，或指定一条异常规则（ruleId）由规则带出："
                + "承运商的异常码是自由文本，本模块不猜它对应哪一种异常类型");
      }
      type = enumCode(requested, ExceptionType.class, "异常类型");
      severity =
          text(request.severity()).isEmpty()
              ? "MEDIUM"
              : enumCode(request.severity(), Severity.class, "严重度");
      slaMinutes = 1440;
    }

    // ---- 停滞时长（整数分钟；锚点 = 异常节点时刻 → 轨迹最后时刻 → 发货时刻）----
    OffsetDateTime anchor =
        event != null
            ? event.get("occurred_at", OffsetDateTime.class)
            : (subject != null
                ? firstNonNull(
                    subject.get("latest_occurred_at", OffsetDateTime.class),
                    subject.get("shipped_at", OffsetDateTime.class))
                : null);
    OffsetDateTime detectedAt = now();
    long minutes = minutesSince(anchor, detectedAt);
    StringBuilder note = new StringBuilder(text(request.note()));
    if (anchor != null && anchor.isAfter(detectedAt)) {
      // 🔴 锚点晚于判定时刻 = 上游数据有问题（事件时刻在将来）。负时长会让 SLA
      //    判定整个反转，所以这里截断到 0 并**写明**，而不是让负数悄悄进库。
      if (note.length() > 0) {
        note.append("；");
      }
      note.append("锚点时刻晚于判定时刻（上游数据异常），停滞时长按 0 计");
    }
    if (anchor == null) {
      if (note.length() > 0) {
        note.append("；");
      }
      note.append("无轨迹锚点，停滞时长按 0 计");
    }
    if (event != null) {
      String carrierCode0 = text(event.get("exception_code", String.class));
      if (!carrierCode0.isEmpty()) {
        // 承运商的异常码**只作参考**：它是自由文本，本模块不据此推断异常类型。
        if (note.length() > 0) {
          note.append("；");
        }
        note.append("承运商异常码 ").append(carrierCode0);
      }
    }

    // ---- 运单快照 ----
    String waybill = subject != null ? subject.get("master_waybill_no", String.class) : "";
    if (waybill.isEmpty()) {
      waybill = text(request.waybillNo());
    }
    String carrier = subject != null ? subject.get("carrier_code", String.class) : "";
    String orderNo = subject != null ? subject.get("sales_order_no", String.class) : "";

    // ---- 防重复建件（DB 部分唯一索引是兜底，这里给出可读的理由）----
    if (subjectId != null) {
      Record open =
          sql.fetchOne(
              "select case_no from logistics_exception_case"
                  + " where tenant_id=?::uuid and subject_id=?::uuid and exception_type=?"
                  + " and status in ('OPEN','INVESTIGATING','ACTIONED')",
              tenant,
              subjectId,
              type);
      if (open != null) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "该运单已有一件未结的同类型异常件（"
                + open.get("case_no", String.class)
                + "）；同一票同一类型同时只允许一件未结，请先处置那件");
      }
    }

    UUID id = Ids.next();
    String caseNo = nextNo("logistics_exception_case_no_seq", "EXC", detectedAt);
    OffsetDateTime dueAt = detectedAt.plusMinutes(slaMinutes);
    sql.execute(
        "insert into logistics_exception_case"
            + " (id,tenant_id,case_no,source,exception_type,severity,status,subject_id,source_event_id,"
            + "  waybill_no,carrier_code,sales_order_no,stalled_duration_minutes,owner_membership_id,"
            + "  detected_at,due_at,customer_feedback,note,created_by_membership_id)"
            + " values(?::uuid,?::uuid,?,?,?,?,'OPEN',?::uuid,?::uuid,?,?,?,?::bigint,?::uuid,"
            + "  ?::timestamptz,?::timestamptz,?,?,?::uuid)",
        id,
        tenant,
        caseNo,
        source,
        type,
        severity,
        subjectId,
        request.sourceEventId(),
        waybill,
        carrier,
        orderNo,
        minutes,
        membershipId,
        detectedAt,
        dueAt,
        feedback,
        note.toString(),
        membershipId);
    return caseDetail(id).head();
  }

  private static OffsetDateTime firstNonNull(OffsetDateTime a, OffsetDateTime b) {
    return a != null ? a : b;
  }

  private String nextNo(String sequence, String prefix, OffsetDateTime at) {
    long n = sql.fetchOne("select nextval('" + sequence + "')").get(0, Long.class);
    return String.format(
        Locale.ROOT,
        "%s-%s%06d",
        prefix,
        at.atZoneSameInstant(ZoneOffset.UTC).toLocalDate().toString().replace("-", ""),
        n);
  }

  // ==================================================================
  // 规则
  // ==================================================================

  public ExceptionRulePage rules(String ruleType, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder(" where tenant_id=?::uuid");
    String rt = text(ruleType).toUpperCase(Locale.ROOT);
    if (!rt.isEmpty()) {
      where.append(" and rule_type=?");
      args.add(rt);
    }
    long total =
        count(sql.fetchOne("select count(*) from logistics_exception_rule" + where, args.toArray()));
    var rows =
        sql.fetch(
            "select * from logistics_exception_rule" + where
                + " order by created_at desc, id desc limit ? offset ?",
            concat(args, limit, offset));
    return new ExceptionRulePage(
        rows.map(this::ruleRow),
        Math.max(page, 0),
        limit,
        total,
        now(),
        "规则 = **租户可维护的表**：各承运商的停滞口径不一样，写死等于宣称「所有渠道一个阈值」。");
  }

  private ExceptionRuleRow ruleRow(Record r) {
    return new ExceptionRuleRow(
        r.get("id", UUID.class),
        r.get("rule_code", String.class),
        r.get("rule_type", String.class),
        typeLabel(r.get("rule_type", String.class)),
        r.get("match_node", String.class),
        r.get("match_exception_code", String.class),
        r.get("threshold_minutes", Integer.class),
        r.get("severity", String.class),
        severityLabel(r.get("severity", String.class)),
        r.get("resolve_sla_minutes", Integer.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("note", String.class),
        r.get("created_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  public ExceptionRuleRow saveRule(SaveExceptionRuleRequest request, UUID membershipId) {
    UUID tenant = currentTenant();
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    String code = text(request.ruleCode());
    if (code.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则编码不能为空");
    }
    String type = enumCode(request.ruleType(), ExceptionType.class, "异常类型");
    String node = text(request.matchNode()).toUpperCase(Locale.ROOT);
    if (!node.isEmpty()) {
      node = enumCode(node, ExceptionType.class, "匹配节点");
      // 节点白名单与异常类型不是同一套，写错就静默不命中 —— 这里显式校验。
      if (!List.of(
              "NO_TRACE",
              "PICKED_UP",
              "IN_TRANSIT",
              "CUSTOMS",
              "TRANSFER",
              "OUT_FOR_DELIVERY",
              "DELIVERED",
              "EXCEPTION",
              "RETURNED")
          .contains(node)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "匹配节点不在轨迹节点白名单内");
      }
    }
    String exceptionCode = text(request.matchExceptionCode());
    if (exceptionCode.length() > 40) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "匹配异常码最长 40 个字符");
    }
    if (request.thresholdMinutes() != null && request.thresholdMinutes() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时长阈值必须大于 0 分钟");
    }
    String severity = enumCode(request.severity(), Severity.class, "严重度");
    int sla =
        request.resolveSlaMinutes() == null ? 1440 : request.resolveSlaMinutes();
    if (sla <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "责任时限必须大于 0 分钟");
    }
    boolean enabled = request.enabled() == null || request.enabled();

    Record existing =
        sql.fetchOne(
            "select id from logistics_exception_rule where tenant_id=?::uuid and rule_code=?",
            tenant,
            code);
    if (existing != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "规则编码已存在：" + code + "（编码是租户内唯一的）");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into logistics_exception_rule"
            + " (id,tenant_id,rule_code,rule_type,match_node,match_exception_code,threshold_minutes,"
            + "  severity,resolve_sla_minutes,enabled,note,created_by_membership_id)"
            + " values(?::uuid,?::uuid,?,?,?,?,?::int,?,?::int,?,?,?::uuid)",
        id,
        tenant,
        code,
        type,
        node,
        exceptionCode,
        request.thresholdMinutes(),
        severity,
        sla,
        enabled,
        text(request.note()),
        membershipId);
    return ruleRow(
        sql.fetchOne(
            "select * from logistics_exception_rule where tenant_id=?::uuid and id=?::uuid",
            tenant,
            id));
  }

  // ==================================================================
  // 规则扫描（按规则把轨迹异常变成异常件）
  // ==================================================================

  /**
   * 按规则扫描在途轨迹，把命中的票变成异常件。
   *
   * <p>🔴 幂等：同一票 + 同一类型已有未结异常件 ⇒ 跳过并写明「已有未结异常件」，
   * 而不是插出第二张。DB 的部分唯一索引是兜底。
   */
  public ExceptionScanResult scan(UUID ruleId, UUID membershipId) {
    UUID tenant = currentTenant();
    List<Record> rules;
    if (ruleId != null) {
      Record one =
          sql.fetchOne(
              "select * from logistics_exception_rule where tenant_id=?::uuid and id=?::uuid",
              tenant,
              ruleId);
      if (one == null) {
        throw notFound("异常规则");
      }
      rules = List.of(one);
    } else {
      rules =
          sql.fetch(
              "select * from logistics_exception_rule where tenant_id=?::uuid and enabled"
                  + " order by created_at asc",
              tenant);
    }

    OffsetDateTime scannedAt = now();
    int candidates = 0;
    int created = 0;
    List<String> createdCaseNos = new ArrayList<>();
    List<ExceptionScanSkip> skips = new ArrayList<>();

    for (Record rule : rules) {
      String ruleType = rule.get("rule_type", String.class);
      String ruleCode = rule.get("rule_code", String.class);
      String matchNode = rule.get("match_node", String.class);
      String matchCode = rule.get("match_exception_code", String.class);
      Integer threshold = rule.get("threshold_minutes", Integer.class);
      String severity = rule.get("severity", String.class);
      int sla = rule.get("resolve_sla_minutes", Integer.class);

      List<Object> args = new ArrayList<>();
      // 🔴 绑定顺序必须**逐字对应 SQL 里的占位符出现顺序**：
      //    第一个 `?::text` 出现在 lateral 子查询里（在 WHERE 之前），
      //    第二个才是 WHERE 里的 `?::uuid`。顺序写反的症状是
      //    「invalid input syntax for type uuid: ADDRESS_ISSUE」——
      //    规则类型被当成 uuid 绑了进去。
      args.add(ruleType);
      args.add(tenant);
      StringBuilder where = new StringBuilder(" where s.tenant_id=?::uuid and s.status='ACTIVE'");
      if (!matchNode.isEmpty()) {
        where.append(" and s.latest_node=?");
        args.add(matchNode);
      }
      if (!matchCode.isEmpty()) {
        where.append(" and s.last_exception_code=?");
        args.add(matchCode);
      }
      if (threshold != null) {
        // 🔴 阈值比较用 make_interval(mins => ?)：不把分钟数拼进 SQL 字符串
        //    （拼字符串 = 注入面），也不落一个浮点小时列。
        where.append(
            " and coalesce(s.latest_occurred_at, s.shipped_at) is not null"
                + " and coalesce(s.latest_occurred_at, s.shipped_at)"
                + " <= now() - make_interval(mins => ?::int)");
        args.add(threshold);
      }
      var rows =
          sql.fetch(
              "select s.id,s.master_waybill_no,s.carrier_code,s.sales_order_no,"
                  + " s.latest_occurred_at,s.shipped_at,"
                  + " oc.id as open_case_id, oc.case_no as open_case_no"
                  + "  from logistics_tracking_subject s"
                  + "  left join lateral ("
                  + "    select c.id, c.case_no from logistics_exception_case c"
                  + "     where c.tenant_id=s.tenant_id and c.subject_id=s.id"
                  + "       and c.exception_type=?::text"
                  + "       and c.status in ('OPEN','INVESTIGATING','ACTIONED')"
                  + "     order by c.detected_at desc limit 1) oc on true"
                  + where,
              concat(args));

      for (Record s : rows) {
        candidates++;
        String waybill = s.get("master_waybill_no", String.class);
        if (s.get("open_case_id", UUID.class) != null) {
          if (skips.size() < 50) {
            skips.add(
                new ExceptionScanSkip(
                    ruleCode,
                    waybill,
                    "已有未结异常件",
                    s.get("open_case_no", String.class)));
          }
          continue;
        }
        UUID subjectId = s.get("id", UUID.class);
        OffsetDateTime anchor =
            firstNonNull(
                s.get("latest_occurred_at", OffsetDateTime.class),
                s.get("shipped_at", OffsetDateTime.class));
        long minutes = minutesSince(anchor, scannedAt);
        UUID id = Ids.next();
        String caseNo = nextNo("logistics_exception_case_no_seq", "EXC", scannedAt);
        OffsetDateTime dueAt = scannedAt.plusMinutes(sla);
        String note =
            "规则 " + ruleCode + " 命中"
                + (threshold != null ? "（阈值 " + threshold + " 分钟）" : "")
                + (anchor == null ? "；无轨迹锚点，停滞时长按 0 计" : "");
        sql.execute(
            "insert into logistics_exception_case"
                + " (id,tenant_id,case_no,source,exception_type,severity,status,subject_id,"
                + "  waybill_no,carrier_code,sales_order_no,stalled_duration_minutes,"
                + "  owner_membership_id,detected_at,due_at,note,created_by_membership_id)"
                + " values(?::uuid,?::uuid,?,'RULE_SCAN',?,?,'OPEN',?::uuid,?,?,?,?::bigint,"
                + "  ?::uuid,?::timestamptz,?::timestamptz,?,?::uuid)",
            id,
            tenant,
            caseNo,
            ruleType,
            severity,
            subjectId,
            waybill,
            s.get("carrier_code", String.class),
            s.get("sales_order_no", String.class),
            minutes,
            membershipId,
            scannedAt,
            dueAt,
            note,
            membershipId);
        created++;
        createdCaseNos.add(caseNo);
      }
    }
    return new ExceptionScanResult(
        rules.size(),
        candidates,
        created,
        skips.size(),
        skips,
        createdCaseNos,
        scannedAt,
        "扫描只覆盖**在途**轨迹主体（status='ACTIVE'）；已签收 / 已退回 / 已作废的票"
            + "不产生异常件。已有未结同类型异常件的票会被跳过（幂等），不插第二张。");
  }

  // ==================================================================
  // 处置记录
  // ==================================================================

  public ExceptionDispositionPage dispositions(
      String actionType, String status, String caseId, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder(" where d.tenant_id=?::uuid");
    String at = text(actionType).toUpperCase(Locale.ROOT);
    if (!at.isEmpty()) {
      where.append(" and d.action_type=?");
      args.add(at);
    }
    String st = text(status).toUpperCase(Locale.ROOT);
    if (!st.isEmpty()) {
      where.append(" and d.status=?");
      args.add(st);
    }
    if (!text(caseId).isEmpty()) {
      where.append(" and d.case_id=?::uuid");
      args.add(parseUuid(caseId, "caseId 必须是 UUID"));
    }
    long total =
        count(
            sql.fetchOne(
                "select count(*) from logistics_exception_disposition d" + where, args.toArray()));
    var rows =
        sql.fetch(
            "select d.*, c.case_no, c.waybill_no,"
                + " (select count(*) from logistics_exception_downstream_task t"
                + "   where t.tenant_id=d.tenant_id and t.disposition_id=d.id) as downstream_task_count"
                + "  from logistics_exception_disposition d"
                + "  join logistics_exception_case c on c.tenant_id=d.tenant_id and c.id=d.case_id"
                + where
                + " order by d.occurred_at desc, d.created_at desc limit ? offset ?",
            concat(args, limit, offset));
    return new ExceptionDispositionPage(
        rows.map(this::dispositionRow),
        Math.max(page, 0),
        limit,
        total,
        now(),
        "处置记录 = **当时做了什么**的留痕（append-only）：要更正就追加新的一条，不改旧的。");
  }

  private ExceptionDispositionRow dispositionRow(Record r) {
    return new ExceptionDispositionRow(
        r.get("id", UUID.class),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("waybill_no", String.class),
        r.get("action_type", String.class),
        actionLabel(r.get("action_type", String.class)),
        r.get("status", String.class),
        dispositionStatusLabel(r.get("status", String.class)),
        r.get("target_owner_membership_id", UUID.class),
        r.get("amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("detail", String.class),
        r.get("result_note", String.class),
        r.get("operator_membership_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class),
        r.get("downstream_task_count", Long.class));
  }

  public ExceptionDispositionDetail dispositionDetail(UUID id) {
    UUID tenant = currentTenant();
    Record r =
        sql.fetchOne(
            "select d.*, c.case_no, c.waybill_no,"
                + " (select count(*) from logistics_exception_downstream_task t"
                + "   where t.tenant_id=d.tenant_id and t.disposition_id=d.id) as downstream_task_count"
                + "  from logistics_exception_disposition d"
                + "  join logistics_exception_case c on c.tenant_id=d.tenant_id and c.id=d.case_id"
                + " where d.tenant_id=?::uuid and d.id=?::uuid",
            tenant,
            id);
    if (r == null) {
      throw notFound("处置记录");
    }
    List<DownstreamTaskRow> tasks =
        sql.fetch(
                "select * from logistics_exception_downstream_task"
                    + " where tenant_id=?::uuid and disposition_id=?::uuid order by created_at desc",
                tenant,
                id)
            .map(this::downstreamRow);
    return new ExceptionDispositionDetail(dispositionRow(r), tasks);
  }

  /**
   * 追加一条处置记录。
   *
   * <p>🔴 处置动作会**推进异常件状态**（状态机在 DB 层钉死，终态不可回退），
   * 并按动作类型产生**下游待通知任务**（状态恒从 PENDING_NOTIFY 起步）。
   */
  public ExceptionDispositionRow addDisposition(
      UUID caseId, AddDispositionRequest request, UUID membershipId) {
    UUID tenant = currentTenant();
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    Record c =
        sql.fetchOne(
            "select * from logistics_exception_case where tenant_id=?::uuid and id=?::uuid",
            tenant,
            caseId);
    if (c == null) {
      throw notFound("异常件");
    }
    String caseStatus = c.get("status", String.class);
    if (caseStatus.equals("RESOLVED") || caseStatus.equals("VOID")) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该异常件已"
              + (caseStatus.equals("RESOLVED") ? "结案" : "作废")
              + "，不再接受处置动作（终态不可回退）");
    }
    String action = enumCode(request.actionType(), ActionType.class, "处置动作");
    String status =
        text(request.status()).isEmpty()
            ? "PENDING"
            : enumCode(request.status(), DispositionStatus.class, "处置状态");
    UUID targetOwner = request.targetOwnerMembershipId();
    if (action.equals("ASSIGN") && targetOwner == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "转派责任人必须给出目标责任人");
    }
    BigDecimal amount =
        request.amount() == null ? BigDecimal.ZERO : nonNegative(request.amount(), "金额");
    String ccy = text(request.currency());
    if (!ccy.isEmpty()) {
      ccy = currency(ccy, "币种");
    } else if (amount.signum() > 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "金额大于 0 时必须给出币种");
    }
    String detail = text(request.detail());
    if (action.equals("RESOLVE") && detail.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "结案必须写明结案理由");
    }
    if (action.equals("VOID") && detail.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "作废必须写明作废理由");
    }
    OffsetDateTime occurredAt = request.occurredAt() == null ? now() : request.occurredAt();

    UUID id = Ids.next();
    sql.execute(
        "insert into logistics_exception_disposition"
            + " (id,tenant_id,case_id,action_type,status,target_owner_membership_id,amount,currency,"
            + "  detail,result_note,operator_membership_id,occurred_at)"
            + " values(?::uuid,?::uuid,?::uuid,?,?,?::uuid,?::numeric,?,?,?,?::uuid,?::timestamptz)",
        id,
        tenant,
        caseId,
        action,
        status,
        targetOwner,
        amount,
        ccy,
        detail,
        text(request.resultNote()),
        membershipId,
        occurredAt);

    // ---- 下游待通知任务：状态恒从 PENDING_NOTIFY 起步 ----
    for (String module : downstreamFor(action)) {
      sql.execute(
          "insert into logistics_exception_downstream_task"
              + " (id,tenant_id,case_id,disposition_id,downstream_module,downstream_status,note)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,'PENDING_NOTIFY',?)",
          Ids.next(),
          tenant,
          caseId,
          id,
          module,
          "由处置动作 " + actionLabel(action) + " 触发；下游模块未交付，本模块不跨服务调用");
    }

    // ---- 推进异常件状态（状态机由 DB 触发器守）----
    if (action.equals("RESOLVE")) {
      sql.execute(
          "update logistics_exception_case set status='RESOLVED', closed_at=?::timestamptz,"
              + " close_reason=? where tenant_id=?::uuid and id=?::uuid",
          occurredAt,
          detail,
          tenant,
          caseId);
    } else if (action.equals("VOID")) {
      sql.execute(
          "update logistics_exception_case set status='VOID', closed_at=?::timestamptz,"
              + " close_reason=? where tenant_id=?::uuid and id=?::uuid",
          occurredAt,
          detail,
          tenant,
          caseId);
    } else if (action.equals("ASSIGN")) {
      sql.execute(
          "update logistics_exception_case set owner_membership_id=?::uuid,"
              + " status=case when status='OPEN' then 'INVESTIGATING' else status end"
              + " where tenant_id=?::uuid and id=?::uuid",
          targetOwner,
          tenant,
          caseId);
    } else {
      sql.execute(
          "update logistics_exception_case set status='ACTIONED'"
              + " where tenant_id=?::uuid and id=?::uuid and status in ('OPEN','INVESTIGATING')",
          tenant,
          caseId);
    }
    Record saved =
        sql.fetchOne(
            "select d.*, c.case_no, c.waybill_no,"
                + " (select count(*) from logistics_exception_downstream_task t"
                + "   where t.tenant_id=d.tenant_id and t.disposition_id=d.id) as downstream_task_count"
                + "  from logistics_exception_disposition d"
                + "  join logistics_exception_case c on c.tenant_id=d.tenant_id and c.id=d.case_id"
                + " where d.tenant_id=?::uuid and d.id=?::uuid",
            tenant,
            id);
    return dispositionRow(saved);
  }

  /**
   * 某个处置动作要通知哪些下游模块。
   *
   * <p>🔴 只有**确实需要另一个模块接手**的动作才产生任务：
   * 承运商是外部对手方（不是内部模块）⇒ 联系承运商 / 发起理赔不产生内部任务。
   */
  private static List<String> downstreamFor(String action) {
    return switch (action) {
      case "CONTACT_CS" -> List.of("15");
      case "REQUEST_ADDRESS_CHANGE" -> List.of("10.2.1.3");
      case "REQUEST_RESHIP" -> List.of("7");
      default -> List.of();
    };
  }

  // ==================================================================
  // 理赔 / 补发单
  // ==================================================================

  public ExceptionClaimPage claims(
      UUID caseId, String claimType, String status, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.min(Math.max(size, 1), 200);
    int offset = Math.max(page, 0) * limit;
    List<Object> args = new ArrayList<>();
    args.add(tenant);
    StringBuilder where = new StringBuilder(" where cl.tenant_id=?::uuid");
    if (caseId != null) {
      where.append(" and cl.case_id=?::uuid");
      args.add(caseId);
    }
    String ct = text(claimType).toUpperCase(Locale.ROOT);
    if (!ct.isEmpty()) {
      where.append(" and cl.claim_type=?");
      args.add(ct);
    }
    String st = text(status).toUpperCase(Locale.ROOT);
    if (!st.isEmpty()) {
      where.append(" and cl.status=?");
      args.add(st);
    }
    long total =
        count(
            sql.fetchOne(
                "select count(*) from logistics_exception_claim cl" + where, args.toArray()));
    var rows =
        sql.fetch(
            "select cl.*, c.case_no, c.waybill_no"
                + "  from logistics_exception_claim cl"
                + "  join logistics_exception_case c on c.tenant_id=cl.tenant_id and c.id=cl.case_id"
                + where
                + " order by cl.created_at desc, cl.id desc limit ? offset ?",
            concat(args, limit, offset));
    return new ExceptionClaimPage(
        rows.map(this::claimRow),
        Math.max(page, 0),
        limit,
        total,
        now(),
        "理赔 / 补发单 = 处置结果的**单据**；状态机在 DB 层，终态不可回退。",
        "🔴 金额与数量是 NUMERIC(19,6) + BigDecimal，禁浮点；跨币种绝不相加。");
  }

  private ExceptionClaimRow claimRow(Record r) {
    return new ExceptionClaimRow(
        r.get("id", UUID.class),
        r.get("claim_no", String.class),
        r.get("case_id", UUID.class),
        r.get("case_no", String.class),
        r.get("disposition_id", UUID.class),
        r.get("waybill_no", String.class),
        r.get("claim_type", String.class),
        claimTypeLabel(r.get("claim_type", String.class)),
        r.get("status", String.class),
        claimStatusLabel(r.get("status", String.class)),
        r.get("amount", BigDecimal.class),
        r.get("currency", String.class),
        r.get("qty", BigDecimal.class),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("decided_at", OffsetDateTime.class),
        r.get("decision_note", String.class),
        r.get("note", String.class),
        r.get("created_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("version", Long.class));
  }

  public ExceptionClaimDetail claimDetail(UUID id) {
    UUID tenant = currentTenant();
    Record r =
        sql.fetchOne(
            "select cl.*, c.case_no, c.waybill_no"
                + "  from logistics_exception_claim cl"
                + "  join logistics_exception_case c on c.tenant_id=cl.tenant_id and c.id=cl.case_id"
                + " where cl.tenant_id=?::uuid and cl.id=?::uuid",
            tenant,
            id);
    if (r == null) {
      throw notFound("理赔/补发单");
    }
    List<DownstreamTaskRow> tasks =
        sql.fetch(
                "select * from logistics_exception_downstream_task"
                    + " where tenant_id=?::uuid and claim_id=?::uuid order by created_at desc",
                tenant,
                id)
            .map(this::downstreamRow);
    return new ExceptionClaimDetail(claimRow(r), tasks);
  }

  /**
   * 发起一张理赔 / 补发单。
   *
   * <p>🔴 补发单会产出**两条**下游待通知任务：7 库存（补发出库）+ 11 售后（补发/退款受理）。
   * 两者都**未交付**，状态恒为 PENDING_NOTIFY —— 本模块不跨服务调用、不动对方任何表。
   */
  public ExceptionClaimRow createClaim(
      UUID caseId, CreateExceptionClaimRequest request, UUID membershipId) {
    UUID tenant = currentTenant();
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
    }
    Record c =
        sql.fetchOne(
            "select * from logistics_exception_case where tenant_id=?::uuid and id=?::uuid",
            tenant,
            caseId);
    if (c == null) {
      throw notFound("异常件");
    }
    String caseStatus = c.get("status", String.class);
    if (caseStatus.equals("RESOLVED") || caseStatus.equals("VOID")) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该异常件已结案或作废，不再接受新单据");
    }
    String type = enumCode(request.claimType(), ClaimType.class, "单据类型");
    UUID dispositionId = request.dispositionId();
    if (dispositionId != null) {
      Record d =
          sql.fetchOne(
              "select id from logistics_exception_disposition where tenant_id=?::uuid and id=?::uuid",
              tenant,
              dispositionId);
      if (d == null) {
        throw notFound("处置记录");
      }
    }
    BigDecimal amount = BigDecimal.ZERO;
    BigDecimal qty = BigDecimal.ZERO;
    String ccy = "";
    if (type.equals("CARRIER_CLAIM")) {
      amount = positive(request.amount(), "理赔金额");
      ccy = currency(request.currency(), "理赔币种");
    } else {
      qty = positive(request.qty(), "补发数量");
    }

    UUID id = Ids.next();
    OffsetDateTime createdAt = now();
    String claimNo = nextNo("logistics_exception_claim_no_seq", "CLM", createdAt);
    sql.execute(
        "insert into logistics_exception_claim"
            + " (id,tenant_id,case_id,disposition_id,claim_no,claim_type,status,amount,currency,qty,"
            + "  evidence,note,created_by_membership_id)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,'DRAFT',?::numeric,?,?::numeric,"
            + "  ?::jsonb,?,?::uuid)",
        id,
        tenant,
        caseId,
        dispositionId,
        claimNo,
        type,
        amount,
        ccy,
        qty,
        text(request.evidenceNote()).isEmpty()
            ? "{}"
            : "{\"note\":\"" + jsonEscape(text(request.evidenceNote())) + "\"}",
        text(request.note()),
        membershipId);

    // ---- 补发单的下游待通知任务（7 库存 / 11 售后，均未交付）----
    if (type.equals("RESHIP")) {
      for (String module : List.of("7", "11")) {
        sql.execute(
            "insert into logistics_exception_downstream_task"
                + " (id,tenant_id,case_id,claim_id,downstream_module,downstream_status,note)"
                + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,'PENDING_NOTIFY',?)",
            Ids.next(),
            tenant,
            caseId,
            id,
            module,
            "补发单 " + claimNo + " 需要下游接手；下游模块未交付，本模块不跨服务调用");
      }
    }
    return claimDetail(id).head();
  }

  private static String jsonEscape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
  }

  // ==================================================================
  // 下游任务
  // ==================================================================

  private List<DownstreamTaskRow> downstreamTasks(UUID tenant, UUID caseId) {
    return sql.fetch(
            "select * from logistics_exception_downstream_task"
                + " where tenant_id=?::uuid and case_id=?::uuid order by created_at desc",
            tenant,
            caseId)
        .map(this::downstreamRow);
  }

  private DownstreamTaskRow downstreamRow(Record r) {
    String module = r.get("downstream_module", String.class);
    return new DownstreamTaskRow(
        r.get("id", UUID.class),
        r.get("case_id", UUID.class),
        "",
        r.get("disposition_id", UUID.class),
        r.get("claim_id", UUID.class),
        module,
        downstreamModuleLabel(module),
        r.get("downstream_status", String.class),
        downstreamStatusLabel(r.get("downstream_status", String.class)),
        r.get("reference_no", String.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("notified_at", OffsetDateTime.class),
        r.get("acknowledged_at", OffsetDateTime.class));
  }
}
