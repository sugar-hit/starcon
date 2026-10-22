package com.starcon.crm;

import static com.starcon.crm.CustomerContactModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
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
 * 15.3.2.2 接触记录。
 *
 * <p>蓝图 15 卷 15.3.2.2 原话：
 *
 * <blockquote>
 * 职责：记录与客户的所有接触（客服通话、消息、邮件、营销触达、售后沟通），可由各模块自动写入，支持时间线检索。
 * 输入：各模块接触事件；手工补录。
 * 输出：接触时间线。
 * 关系：上游 = 15.1、11 售后、15.5.2；下游 = 15.3.1.2。
 * <b>接触记录是自动化的豁免依据</b>——曾明确表示"不要再联系我"的客户，营销触达需排除。
 * </blockquote>
 *
 * <p><b>本类落四条纪律</b>：
 *
 * <ol>
 *   <li><b>手工补录与自动写入分开记账</b>：{@code origin} = MANUAL / AUTO；
 *       自动写入必须带 {@code sourceModule} + {@code sourceRef}，并以
 *       {@code (tenant_id, source_module, source_ref)} 为<b>幂等键</b> ——
 *       同一条上游记录重复写入只记一次（重试与平台重复推送不重复记账）。</li>
 *   <li><b>加粗句「接触记录是自动化的豁免依据」</b>：
 *       <ul>
 *         <li>退订（{@code ALL} / {@code MARKETING}）是<b>独立的一等状态</b>，
 *             登记必须带<b>依据</b>（reason 必填 + evidence 快照）；</li>
 *         <li>退订<b>不可被普通接触记录悄悄覆盖或撤销</b> ——
 *             写一条接触记录根本碰不到退订状态（模型层就不给这个口子）；</li>
 *         <li>撤销退订是<b>显式动作</b>（{@code POST …/opt-outs/{id}/revoke}），
 *             必须带撤销原因，并留下<b>只追加</b>的审计事件与一条时间线记录；</li>
 *         <li>有一个<b>可查询的判定</b>（{@code opt-out-check}）：
 *             某个客户当前是否处于「不可营销」状态 —— 这正是 15.5.2 要消费的豁免依据。</li>
 *       </ul>
 *   </li>
 *   <li><b>真分页真筛选</b>：列表与时间线的总数、页数都来自真实查询；
 *       筛选条件（客户 / 类型 / 方向 / 来源 / 合规 / 时间窗 / 关键字）真的进 WHERE。</li>
 *   <li><b>禁浮点与时区不静默偏移</b>：数量用 {@code numeric(19,6)} + BigDecimal；
 *       时间一律 {@code timestamptz} + OffsetDateTime + {@code ?::timestamptz}。</li>
 * </ol>
 *
 * <p><b>跨服务纪律</b>：11 售后在 <b>trade</b> 服务 ⇒ 跨服务只能走<b>授权 HTTP</b>
 * （本类提供 {@code POST /crm/customer-contacts/ingest} 入口，trade 侧<b>尚未接线</b>）。
 * 15.1 消息与 15.1.2.1 邮件与本模块<b>同服务同库</b> ⇒ 自动写入由<b>库内物化触发器</b>
 * 完成（见 V299），本类<b>不</b>跨库读它们。
 */
@Service
public class CustomerContactService {

  // ================================================================ 常量

  /** 列表页最大条数（与本服务其它模块同一上限）。 */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * 批量豁免判定一次最多查多少人。
   *
   * <p>营销触达前做人群豁免检查时，人数可能很大；一次查太多会把
   * {@code IN (...)} 变成一条巨大的 SQL。超限直接说清，不静默截断。
   */
  static final int MAX_OPT_OUT_CHECK = 500;

  private final DSLContext sql;

  public CustomerContactService(DSLContext sql) {
    this.sql = sql;
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
   *   <li>{@code aftersales_auto_write} —— 11 售后在 <b>trade</b> 服务（跨服务），
   *       必须走<b>授权 HTTP</b>；trade 侧<b>尚未接线</b> ⇒ 本模块只提供入口，不假装已打通。</li>
   *   <li>{@code marketing_outreach} —— 15.5.2.1 关怀与催办<b>未交付</b>。
   *       本模块<b>不发送</b>任何触达，只产出<b>判定与记录</b>。</li>
   *   <li>{@code customer_profile} —— 15.3.1.2 客户详情<b>未交付</b>；
   *       本仓<b>没有买家档案主数据</b> ⇒ {@code customer_ref} 是逻辑引用。</li>
   * </ul>
   */
  public CrmCustomerContactCapabilities capabilities() {
    List<CrmCustomerContactCapabilityDimension> dimensions =
        List.of(
            new CrmCustomerContactCapabilityDimension(
                "contact_record",
                "接触记录落库（客服通话 / 消息 / 邮件 / 营销触达 / 售后沟通）",
                true,
                "本服务 V299 crm_customer_contact",
                "",
                "接触记录是**凭据**：客户标识是逻辑引用（本仓没有买家档案主数据），"
                    + "但每一次接触都真的落库、真的可查"),
            new CrmCustomerContactCapabilityDimension(
                "manual_backfill",
                "手工补录（运营补登一次通话、一次线下沟通）",
                true,
                "本服务 V299 crm_customer_contact（origin=MANUAL）",
                "",
                "补录与自动写入**分开记账**：人工补的与机器写的不是同一条记录，"
                    + "谁记的一眼分得清"),
            new CrmCustomerContactCapabilityDimension(
                "auto_write_message",
                "15.1 消息收件箱自动写入接触记录",
                true,
                "本服务 V299 的库内物化触发器（crm_message → crm_customer_contact）",
                "15.1.1.1",
                "15.1 与本模块**同服务同库** ⇒ 自动物化（不改上游代码、不改上游表结构）；"
                    + "只物化真正与客户的消息，平台通知 / 合规通知不算接触"),
            new CrmCustomerContactCapabilityDimension(
                "auto_write_email",
                "15.1.2.1 买家邮件自动写入接触记录",
                true,
                "本服务 V299 的库内物化触发器（crm_buyer_email → crm_customer_contact）",
                "15.1.2.1",
                "收到的邮件、以及**已发出**的回复各算一次触达；"
                    + "待发（QUEUED）与发送失败（FAILED）不算触达"),
            new CrmCustomerContactCapabilityDimension(
                "auto_write_aftersales",
                "11 售后沟通自动写入接触记录",
                false,
                "**11 售后在 trade 服务（跨服务）**，trade 侧尚未接线",
                "11.1.1.1",
                "跨服务只走**授权 HTTP**：本模块已提供 POST /crm/customer-contacts/ingest "
                    + "作为写入入口，但 trade 还没有调用它 ⇒ 售后的自动写入**尚未打通**，"
                    + "页面不假装已打通"),
            new CrmCustomerContactCapabilityDimension(
                "opt_out_audit",
                "退订 / 免打扰的登记与审计",
                true,
                "本服务 V299 crm_customer_opt_out + crm_customer_opt_out_event",
                "",
                "🔴 登记**必须带依据**（reason 必填 + evidence 快照），"
                    + "并留下「谁在什么时候记下的」；退订**不可被普通接触记录悄悄覆盖或撤销**"),
            new CrmCustomerContactCapabilityDimension(
                "opt_out_revocation",
                "撤销退订（显式动作 + 留痕）",
                true,
                "本服务 V299 POST /crm/customer-contacts/opt-outs/{id}/revoke",
                "",
                "撤销必须写撤销原因，并追加一条**只追加**的审计事件与一条时间线记录；"
                    + "已撤销的退订**不能**被改回生效"),
            new CrmCustomerContactCapabilityDimension(
                "opt_out_exemption_check",
                "可查询的豁免判定（这个客户现在还能不能营销）",
                true,
                "本服务 V299 GET /crm/customer-contacts/opt-out-check",
                "",
                "🔴 这是 15.5.2 将来要消费的**豁免依据**：判定只看**生效中**的退订，"
                    + "已撤销的不再豁免（但留痕还在）"),
            new CrmCustomerContactCapabilityDimension(
                "marketing_outreach",
                "营销触达（关怀 / 催办）",
                false,
                "**15.5.2.1 关怀与催办未交付**",
                "15.5.2.1",
                "本模块**不发送**任何触达，也不登记「待发触达」意图 —— 那是 15.5.2.1 的写入；"
                    + "本模块只产出**判定与记录**"),
            new CrmCustomerContactCapabilityDimension(
                "customer_profile",
                "按客户主档筛选 / 客户详情",
                false,
                "**15.3.1.1 客户列表 / 15.3.1.2 客户详情未交付**；本仓没有买家档案主数据",
                "15.3.1.2",
                "客户标识是**逻辑引用**，本模块按引用维度检索；"
                    + "不假装能按客户主档筛出人群"),
            new CrmCustomerContactCapabilityDimension(
                "timeline_search",
                "接触时间线检索（真分页 + 真筛选）",
                true,
                "本服务 V299 crm_customer_contact 的真实查询",
                "",
                "时间线按**接触发生时刻**排序（不是登记时刻）；"
                    + "筛选（客户 / 类型 / 方向 / 来源 / 合规 / 时间窗 / 关键字）真的进 WHERE"));
    return new CrmCustomerContactCapabilities(
        dimensions,
        names(CustomerContactType.values()),
        names(CustomerContactDirection.values()),
        names(CustomerContactOrigin.values()),
        names(CustomerOptOutScope.values()),
        names(CustomerOptOutState.values()),
        "🔴 豁免依据：客户**明确表示不要再联系我**之后，营销触达必须被排除。"
            + "本模块只负责**判定与留痕**（谁在什么时候记下的、依据是什么、撤销有没有留痕），"
            + "**不发送**任何触达 —— 排除动作由 15.5.2.1 执行。"
            + "退订只能被两个**显式**动作改变：登记退订、撤销退订；"
            + "写一条普通接触记录改不了它。",
        "本模块**不发送**任何消息 / 邮件 / 营销触达，也不向任何平台发起调用；"
            + "它只产出接触记录与退订判定。",
        "客户标识是**逻辑引用**（本仓没有买家档案主数据，15.3.1.1 未交付）；"
            + "11 售后在 trade 服务，其自动写入**尚未接线**（跨服务须走授权 HTTP）；"
            + "15.5.2.1 营销触达**未交付**。",
        "接触记录记录我们与客户的**每一次**接触，并给出「他现在还能不能被营销」的可查询判定。");
  }

  // ================================================================ 接触记录

  /** 接触记录列表（真分页 + 真筛选）。 */
  public CrmCustomerContactPage contactPage(
      String customerRef,
      String contactType,
      String direction,
      String origin,
      String sourceModule,
      String complianceState,
      String from,
      String to,
      String keyword,
      int page,
      int size) {
    List<Object> binds = new ArrayList<>();
    String where = contactWhere(
        customerRef, contactType, direction, origin, sourceModule, complianceState, from, to,
        keyword, binds);
    long total =
        sql.fetchOne("select count(*) from crm_customer_contact c" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmCustomerContactRow> items =
        sql.fetch(
                contactColumns() + where + " order by c.occurred_at desc, c.id desc limit ? offset ?",
                pageBinds.toArray())
            .map(CustomerContactService::contact);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCustomerContactPage(items, safePage, safeSize, total, totalPages);
  }

  /** 单条接触记录。 */
  public CrmCustomerContactRow contact(UUID contactId) {
    var row =
        sql.fetchOne(
            contactColumns() + " where c.tenant_id = ? and c.id = ?", currentTenant(), contactId);
    if (row == null) throw notFound("接触记录不存在：" + contactId);
    return contact(row);
  }

  /**
   * 接触时间线（蓝图输出句的第一项）。
   *
   * <p>🔴 时间线按<b>接触发生时刻</b>排序，不是登记时刻 —— 补录上周的通话，
   * 它就出现在上周的位置上。
   */
  public CrmCustomerTimelinePage timeline(
      String customerRef, String contactType, String from, String to, int page, int size) {
    String ref = blankToNull(customerRef);
    if (ref == null) throw bad("必须指定客户标识（customerRef）才能看时间线。");
    CrmCustomerContactPage contacts =
        contactPage(ref, contactType, null, null, null, null, from, to, null, page, size);
    CrmCustomerOptOutCheck check = optOutCheck(ref);
    String name = contacts.items().isEmpty() ? "" : contacts.items().get(0).customerName();
    return new CrmCustomerTimelinePage(
        ref, name, check, contacts.items(), contacts.page(), contacts.size(),
        contacts.totalElements(), contacts.totalPages());
  }

  /**
   * 手工补录一条接触记录。
   *
   * <p>⚠️ {@code contactType} **不含** OPT_OUT：退订是合规状态，
   * 只能由退订端点（显式动作）改 —— 「退订不可被后续的普通接触记录悄悄覆盖或撤销」
   * 在这里落地。
   */
  @Transactional
  public CrmCustomerContactRow createManual(CrmCustomerContactInput input, UUID actorId) {
    CustomerContactType type = contactType(input.contactType());
    if (type == CustomerContactType.OPT_OUT) {
      throw bad(
          "退订 / 免打扰不是一个「接触类型」，它是一个合规状态："
              + "请走 POST /crm/customer-contacts/opt-outs 登记退订，"
              + "或 POST /crm/customer-contacts/opt-outs/{id}/revoke 显式撤销。"
              + "这样退订的每一次变化都有留痕。");
    }
    CustomerContactDirection direction = contactDirection(input.direction());
    OffsetDateTime occurredAt = parseMoment(input.occurredAt(), "接触发生时刻");
    if (input.durationSeconds() != null && input.durationSeconds().signum() < 0) {
      throw bad("通话时长不能是负数。");
    }
    CustomerContactCompliance compliance = compliance(input.complianceState());
    requireMarketingExempt(input.customerRef().trim(), type, occurredAt, compliance);
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into crm_customer_contact"
            + "(id,tenant_id,contact_no,customer_ref,customer_name,contact_type,direction,origin,"
            + " source_module,source_ref,source_no,source_label,shop_id,shop_name,platform_code,"
            + " order_reference,subject,summary,duration_seconds,occurred_at,operator_id,created_by,"
            + " detail,compliance_state,created_at,updated_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?::jsonb,"
            + " ?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        nextContactNo(),
        input.customerRef().trim(),
        nz(input.customerName()).trim(),
        type.name(),
        direction.name(),
        CustomerContactOrigin.MANUAL.name(),
        nz(input.sourceModule()).trim(),
        nz(input.sourceRef()).trim(),
        nz(input.sourceNo()).trim(),
        nz(input.sourceModule()).isEmpty() ? "手工补录" : nz(input.sourceModule()).trim(),
        input.shopId(),
        nz(input.shopName()).trim(),
        nz(input.platformCode()).trim(),
        nz(input.orderReference()).trim(),
        nz(input.subject()).trim(),
        nz(input.summary()).trim(),
        input.durationSeconds(),
        occurredAt,
        actorId,
        actorId,
        toJson(input.detail() == null ? Map.of() : input.detail()),
        compliance.name(),
        now(),
        now());
    return contact(id);
  }

  /**
   * 上游模块自动写入一条接触记录（<b>幂等</b>）。
   *
   * <p>🔴 幂等键 = {@code (tenant_id, source_module, source_ref)}：
   * 同一条上游记录重复写入（重试、平台重复推送）<b>不会重复记账</b>，
   * 第二次直接返回既有那条并说清「幂等命中」。
   *
   * <p>⚠️ 走 {@code on conflict do nothing} 而不是「先查再插」：
   * 先查再插在并发下有窗口，而唯一索引冲突在 PostgreSQL 里会**中止整个事务**，
   * 事务里再查一次就查不动了。{@code on conflict do nothing} 不会中止事务。
   */
  @Transactional
  public CrmCustomerContactIngestResult ingest(
      CrmCustomerContactIngestInput input, UUID actorId) {
    CustomerContactType type = contactType(input.contactType());
    if (type == CustomerContactType.OPT_OUT) {
      throw bad(
          "自动写入不能登记退订：退订必须由**显式动作**登记（POST /crm/customer-contacts/opt-outs），"
              + "否则一次机器写入就能悄悄把人排除掉、或悄悄把人加回来。");
    }
    CustomerContactDirection direction = contactDirection(input.direction());
    OffsetDateTime occurredAt = parseMoment(input.occurredAt(), "接触发生时刻");
    String sourceModule = input.sourceModule().trim();
    String sourceRef = input.sourceRef().trim();
    if (sourceModule.isEmpty() || sourceRef.isEmpty()) {
      throw bad("自动写入必须给出 sourceModule（上游模块号）与 sourceRef（上游记录 id），否则无法追溯与去重。");
    }
    CustomerContactCompliance compliance = compliance(input.complianceState());
    requireMarketingExempt(input.customerRef().trim(), type, occurredAt, compliance);
    UUID id = UUID.randomUUID();
    int affected =
        sql.execute(
            "insert into crm_customer_contact"
                + "(id,tenant_id,contact_no,customer_ref,customer_name,contact_type,direction,origin,"
                + " source_module,source_ref,source_no,source_label,shop_id,shop_name,platform_code,"
                + " order_reference,subject,summary,duration_seconds,occurred_at,operator_id,created_by,"
                + " detail,compliance_state,created_at,updated_at)"
                + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?::jsonb,"
                + " ?,?::timestamptz,?::timestamptz)"
                + " on conflict do nothing",
            id,
            currentTenant(),
            nextContactNo(),
            input.customerRef().trim(),
            nz(input.customerName()).trim(),
            type.name(),
            direction.name(),
            CustomerContactOrigin.AUTO.name(),
            sourceModule,
            sourceRef,
            nz(input.sourceNo()).trim(),
            nz(input.sourceLabel()).trim(),
            input.shopId(),
            nz(input.shopName()).trim(),
            nz(input.platformCode()).trim(),
            nz(input.orderReference()).trim(),
            nz(input.subject()).trim(),
            nz(input.summary()).trim(),
            input.durationSeconds(),
            occurredAt,
            actorId,
            actorId,
            toJson(input.detail() == null ? Map.of() : input.detail()),
            compliance.name(),
            now(),
            now());
    if (affected > 0) {
      return new CrmCustomerContactIngestResult(contact(id), true, "已写入接触记录。");
    }
    var existing =
        sql.fetchOne(
            contactColumns()
                + " where c.tenant_id = ? and c.source_module = ? and c.source_ref = ?",
            currentTenant(),
            sourceModule,
            sourceRef);
    if (existing == null) {
      // 理论上到不了这里（唯一键刚挡住了插入）；真到了说明有别的约束冲突，如实报错。
      throw conflict("接触记录写入冲突，且没能找到已存在的那一条，请刷新后重试。");
    }
    return new CrmCustomerContactIngestResult(
        contact(existing),
        false,
        "幂等命中：上游记录 " + sourceModule + "/" + sourceRef + " 已经有一条接触记录，未重复记账。");
  }

  // ================================================================ 退订 / 免打扰

  /** 退订记录列表（真分页 + 真筛选）。 */
  public CrmCustomerOptOutPage optOutPage(
      String customerRef, String scope, String state, int page, int size) {
    List<Object> binds = new ArrayList<>();
    String where = optOutWhere(customerRef, scope, state, binds);
    long total =
        sql.fetchOne("select count(*) from crm_customer_opt_out o" + where, binds.toArray())
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(safeSize);
    pageBinds.add(safePage * safeSize);
    List<CrmCustomerOptOutRow> items =
        sql.fetch(
                optOutColumns() + where + " order by o.recorded_at desc, o.id desc limit ? offset ?",
                pageBinds.toArray())
            .map(CustomerContactService::optOut);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCustomerOptOutPage(items, safePage, safeSize, total, totalPages);
  }

  /** 单条退订记录。 */
  public CrmCustomerOptOutRow optOut(UUID optOutId) {
    var row =
        sql.fetchOne(
            optOutColumns() + " where o.tenant_id = ? and o.id = ?", currentTenant(), optOutId);
    if (row == null) throw notFound("退订记录不存在：" + optOutId);
    return optOut(row);
  }

  /** 退订审计流水（只追加）。 */
  public CrmCustomerOptOutEventPage optOutEvents(UUID optOutId, int page, int size) {
    long total =
        sql.fetchOne(
                "select count(*) from crm_customer_opt_out_event where tenant_id = ? and opt_out_id = ?",
                currentTenant(),
                optOutId)
            .get(0, Long.class);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    List<CrmCustomerOptOutEventRow> items =
        sql.fetch(
                "select e.id,e.opt_out_id,e.customer_ref,e.event_type,e.actor_id,e.occurred_at,"
                    + " e.reason,e.detail::text as detail,e.created_at"
                    + " from crm_customer_opt_out_event e"
                    + " where e.tenant_id = ? and e.opt_out_id = ?"
                    + " order by e.occurred_at desc, e.id desc limit ? offset ?",
                currentTenant(),
                optOutId,
                safeSize,
                safePage * safeSize)
            .map(CustomerContactService::optOutEvent);
    int totalPages = total <= 0 ? 0 : (int) ((total + safeSize - 1) / safeSize);
    return new CrmCustomerOptOutEventPage(items, safePage, safeSize, total, totalPages);
  }

  /**
   * 登记退订 / 免打扰（<b>显式动作</b>）。
   *
   * <p>🔴 一次调用做三件事，且**在同一个事务里**：
   *
   * <ol>
   *   <li>写一条生效中的退订状态（带依据 reason + evidence 快照）；</li>
   *   <li>追加一条**只追加**的审计事件（谁在什么时候记下的）；</li>
   *   <li>在接触时间线上补一条 {@code OPT_OUT} 记录 ——
   *       「客户明确要求不要再联系」本身就是一次接触。</li>
   * </ol>
   */
  @Transactional
  public CrmCustomerOptOutRow grantOptOut(CrmCustomerOptOutInput input, UUID actorId) {
    CustomerOptOutScope scope = optOutScope(input.scope());
    String reason = nz(input.reason()).trim();
    if (reason.isEmpty()) throw bad("登记退订必须写明依据：他凭什么说不要再联系。");
    String customerRef = input.customerRef().trim();
    var existing =
        sql.fetchOne(
            "select id, scope from crm_customer_opt_out"
                + " where tenant_id = ? and customer_ref = ? and state = 'ACTIVE'",
            currentTenant(),
            customerRef);
    if (existing != null) {
      throw conflict(
          "客户 "
              + customerRef
              + " 已经有一条生效中的退订（范围 "
              + existing.get("scope", String.class)
              + "）。要改范围请先显式撤销那一条，再登记新的。");
    }
    OffsetDateTime moment = now();
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into crm_customer_opt_out"
            + "(id,tenant_id,customer_ref,customer_name,scope,state,reason,evidence,"
            + " recorded_by,recorded_at,created_at,updated_at)"
            + " values (?,?,?,?,?,'ACTIVE',?,?::jsonb,?,?::timestamptz,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        customerRef,
        nz(input.customerName()).trim(),
        scope.name(),
        reason,
        toJson(input.evidence() == null ? Map.of() : input.evidence()),
        actorId,
        moment,
        moment,
        moment);
    sql.execute(
        "insert into crm_customer_opt_out_event"
            + "(id,tenant_id,opt_out_id,customer_ref,event_type,actor_id,occurred_at,reason,detail,created_at)"
            + " values (?,?,?,?,'GRANTED',?,?::timestamptz,?,?::jsonb,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        id,
        customerRef,
        actorId,
        moment,
        reason,
        toJson(input.evidence() == null ? Map.of() : input.evidence()),
        moment);
    writeOptOutTimelineContact(
        customerRef,
        nz(input.customerName()).trim(),
        scope,
        reason,
        "客户明确表示不要再联系我",
        id,
        moment);
    return optOut(id);
  }

  /**
   * 显式撤销退订（<b>必须留痕</b>）。
   *
   * <p>🔴 撤销走的是「显式动作 + 审计事件 + 时间线记录」三件套：
   * 只有这样，「为什么又给他发营销邮件了」才查得清。
   */
  @Transactional
  public CrmCustomerOptOutRow revokeOptOut(
      UUID optOutId, CrmCustomerOptOutRevokeInput input, UUID actorId) {
    String reason = nz(input.reason()).trim();
    if (reason.isEmpty()) throw bad("撤销退订必须写明原因：没有理由的撤销，下次没人说得清。");
    var row =
        sql.fetchOne(
            "select id, customer_ref, customer_name, scope, state from crm_customer_opt_out"
                + " where tenant_id = ? and id = ?",
            currentTenant(),
            optOutId);
    if (row == null) throw notFound("退订记录不存在：" + optOutId);
    if (!"ACTIVE".equals(row.get("state", String.class))) {
      throw conflict("这条退订已经不是生效状态（现在是 " + row.get("state", String.class) + "），不必再撤销。");
    }
    OffsetDateTime moment = now();
    int affected =
        sql.execute(
            "update crm_customer_opt_out set state = 'REVOKED', revoked_by = ?,"
                + " revoked_at = ?::timestamptz, revoke_reason = ?, updated_at = ?::timestamptz"
                + " where tenant_id = ? and id = ? and state = 'ACTIVE'",
            actorId,
            moment,
            reason,
            moment,
            currentTenant(),
            optOutId);
    if (affected == 0) throw conflict("这条退订刚刚被撤销了，请刷新后重试。");
    String customerRef = row.get("customer_ref", String.class);
    sql.execute(
        "insert into crm_customer_opt_out_event"
            + "(id,tenant_id,opt_out_id,customer_ref,event_type,actor_id,occurred_at,reason,detail,created_at)"
            + " values (?,?,?,?,'REVOKED',?,?::timestamptz,?,?::jsonb,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        optOutId,
        customerRef,
        actorId,
        moment,
        reason,
        toJson(Map.of("scope", row.get("scope", String.class))),
        moment);
    writeOptOutTimelineContact(
        customerRef,
        row.get("customer_name", String.class),
        optOutScope(row.get("scope", String.class)),
        reason,
        "撤销退订",
        optOutId,
        moment);
    return optOut(optOutId);
  }

  /**
   * 可查询的豁免判定 —— <b>某个客户当前是否处于「不可营销」状态</b>。
   *
   * <p>🔴 这是加粗句「接触记录是自动化的豁免依据」的<b>对外接口</b>：
   * 15.5.2 将来在发起营销触达前问它一句「这个人现在还能发吗」。
   */
  public CrmCustomerOptOutCheck optOutCheck(String customerRef) {
    String ref = blankToNull(customerRef);
    if (ref == null) throw bad("必须指定客户标识（customerRef）。");
    var row =
        sql.fetchOne(
            "select o.id,o.scope,o.reason,o.recorded_at,o.recorded_by,o.customer_name"
                + " from crm_customer_opt_out o"
                + " where o.tenant_id = ? and o.customer_ref = ? and o.state = 'ACTIVE'"
                + " order by case o.scope when 'ALL' then 0 else 1 end, o.recorded_at desc limit 1",
            currentTenant(),
            ref);
    if (row == null) {
      return new CrmCustomerOptOutCheck(
          ref,
          "",
          false,
          false,
          "",
          "",
          null,
          "",
          null,
          null,
          "该客户当前没有生效中的退订：营销触达不被本模块豁免（是否真的可以发，仍由 15.5.2.1 负责）。");
    }
    String scope = row.get("scope", String.class);
    boolean suppressed = true;
    boolean marketingSuppressed = "ALL".equals(scope) || "MARKETING".equals(scope);
    return new CrmCustomerOptOutCheck(
        ref,
        nz(row.get("customer_name", String.class)),
        suppressed,
        marketingSuppressed,
        scope,
        optOutScope(scope).label(),
        row.get("id", UUID.class),
        row.get("reason", String.class),
        str(row.get("recorded_at", OffsetDateTime.class)),
        row.get("recorded_by", UUID.class),
        "该客户有生效中的退订（范围 " + optOutScope(scope).label() + "）：营销触达必须排除。");
  }

  /** 批量豁免判定（营销触达前对一批人做检查）。 */
  public CrmCustomerOptOutCheckResult optOutCheckBatch(List<String> customerRefs) {
    List<String> refs = new ArrayList<>();
    for (String value : customerRefs) {
      String ref = blankToNull(value);
      if (ref != null && !refs.contains(ref)) refs.add(ref);
    }
    if (refs.isEmpty()) throw bad("必须给出至少一个客户标识（customerRefs）。");
    if (refs.size() > MAX_OPT_OUT_CHECK) {
      throw bad(
          "一次最多判定 "
              + MAX_OPT_OUT_CHECK
              + " 位客户（当前 "
              + refs.size()
              + " 位）。请分批判定 —— 静默截断会让人以为「这批人都能营销」。");
    }
    List<String> placeholders = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    binds.add(currentTenant());
    for (String ref : refs) {
      placeholders.add("?");
      binds.add(ref);
    }
    var rows =
        sql.fetch(
            "select o.customer_ref,o.customer_name,o.scope,o.id,o.reason,o.recorded_at,o.recorded_by"
                + " from crm_customer_opt_out o"
                + " where o.tenant_id = ? and o.state = 'ACTIVE' and o.customer_ref in ("
                + String.join(",", placeholders)
                + ")",
            binds.toArray());
    Map<String, Record> byRef = new LinkedHashMap<>();
    for (Record row : rows) {
      String ref = row.get("customer_ref", String.class);
      Record current = byRef.get(ref);
      if (current == null
          || ("ALL".equals(row.get("scope", String.class))
              && !"ALL".equals(current.get("scope", String.class)))) {
        byRef.put(ref, row);
      }
    }
    List<CrmCustomerOptOutCheck> items = new ArrayList<>();
    int suppressed = 0;
    for (String ref : refs) {
      Record row = byRef.get(ref);
      if (row == null) {
        items.add(optOutCheck(ref));
        continue;
      }
      String scope = row.get("scope", String.class);
      suppressed++;
      items.add(
          new CrmCustomerOptOutCheck(
              ref,
              nz(row.get("customer_name", String.class)),
              true,
              true,
              scope,
              optOutScope(scope).label(),
              row.get("id", UUID.class),
              row.get("reason", String.class),
              str(row.get("recorded_at", OffsetDateTime.class)),
              row.get("recorded_by", UUID.class),
              "该客户有生效中的退订（范围 " + optOutScope(scope).label() + "）：营销触达必须排除。"));
    }
    return new CrmCustomerOptOutCheckResult(
        refs.size(),
        suppressed,
        items,
        "本次判定 " + refs.size() + " 位客户，其中 " + suppressed + " 位处于生效中的退订（不可营销）。");
  }

  // ================================================================ 内部

  /**
   * 退订 / 撤销退订在时间线上留一条接触记录。
   *
   * <p>「客户明确要求不要再联系」本身就是一次接触 ——
   * 不留在时间线上，这条最重要的接触就凭空消失了。
   */
  private void writeOptOutTimelineContact(
      String customerRef,
      String customerName,
      CustomerOptOutScope scope,
      String reason,
      String subject,
      UUID optOutId,
      OffsetDateTime moment) {
    sql.execute(
        "insert into crm_customer_contact"
            + "(id,tenant_id,contact_no,customer_ref,customer_name,contact_type,direction,origin,"
            + " source_module,source_ref,source_no,source_label,shop_id,shop_name,platform_code,"
            + " order_reference,subject,summary,duration_seconds,occurred_at,operator_id,created_by,"
            + " detail,compliance_state,created_at,updated_at)"
            + " values (?,?,?,?,?,'OPT_OUT','INBOUND','MANUAL',"
            + " '15.3.2.2','',?,'接触记录',null,'','','',?,?,null,?::timestamptz,null,?,"
            + " ?::jsonb,'COMPLIANT',?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        nextContactNo(),
        customerRef,
        customerName,
        optOutId.toString(),
        subject,
        reason,
        moment,
        currentActor(),
        toJson(Map.of("optOutId", optOutId.toString(), "scope", scope.name())),
        moment,
        moment);
  }

  /** 当前已认证的操作人（app.actor 由 AccessService 绑定）。 */
  private UUID currentActor() {
    var row = sql.fetchOne("select nullif(current_setting('app.actor', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  private String contactWhere(
      String customerRef,
      String contactType,
      String direction,
      String origin,
      String sourceModule,
      String complianceState,
      String from,
      String to,
      String keyword,
      List<Object> binds) {
    binds.add(currentTenant());
    StringBuilder where = new StringBuilder(" where c.tenant_id = ?");
    if (blankToNull(customerRef) != null) {
      where.append(" and c.customer_ref = ?");
      binds.add(customerRef.trim());
    }
    if (blankToNull(contactType) != null) {
      where.append(" and c.contact_type = ?");
      binds.add(contactType.trim());
    }
    if (blankToNull(direction) != null) {
      where.append(" and c.direction = ?");
      binds.add(direction.trim());
    }
    if (blankToNull(origin) != null) {
      where.append(" and c.origin = ?");
      binds.add(origin.trim());
    }
    if (blankToNull(sourceModule) != null) {
      where.append(" and c.source_module = ?");
      binds.add(sourceModule.trim());
    }
    if (blankToNull(complianceState) != null) {
      where.append(" and c.compliance_state = ?");
      binds.add(complianceState.trim());
    }
    OffsetDateTime fromAt = blankToNull(from) == null ? null : parseMoment(from, "起始时刻");
    if (fromAt != null) {
      where.append(" and c.occurred_at >= ?::timestamptz");
      binds.add(fromAt);
    }
    OffsetDateTime toAt = blankToNull(to) == null ? null : parseMoment(to, "结束时刻");
    if (toAt != null) {
      where.append(" and c.occurred_at < ?::timestamptz");
      binds.add(toAt);
    }
    if (blankToNull(keyword) != null) {
      where.append(
          " and (strpos(lower(c.customer_ref), lower(?)) > 0"
              + " or strpos(lower(c.customer_name), lower(?)) > 0"
              + " or strpos(lower(c.contact_no), lower(?)) > 0"
              + " or strpos(lower(c.subject), lower(?)) > 0"
              + " or strpos(lower(c.summary), lower(?)) > 0)");
      for (int i = 0; i < 5; i++) binds.add(keyword.trim());
    }
    return where.toString();
  }

  private String optOutWhere(String customerRef, String scope, String state, List<Object> binds) {
    binds.add(currentTenant());
    StringBuilder where = new StringBuilder(" where o.tenant_id = ?");
    if (blankToNull(customerRef) != null) {
      where.append(" and o.customer_ref = ?");
      binds.add(customerRef.trim());
    }
    if (blankToNull(scope) != null) {
      where.append(" and o.scope = ?");
      binds.add(scope.trim());
    }
    if (blankToNull(state) != null) {
      where.append(" and o.state = ?");
      binds.add(state.trim());
    }
    return where.toString();
  }

  private static String contactColumns() {
    return "select c.id,c.tenant_id,c.contact_no,c.customer_ref,c.customer_name,c.contact_type,"
        + " c.direction,c.origin,c.source_module,c.source_ref,c.source_no,c.source_label,c.shop_id,"
        + " c.shop_name,c.platform_code,c.order_reference,c.subject,c.summary,c.duration_seconds,"
        + " c.occurred_at,c.operator_id,c.detail::text as detail,c.compliance_state,c.created_by,"
        + " c.created_at,c.updated_at"
        + " from crm_customer_contact c";
  }

  private static String optOutColumns() {
    return "select o.id,o.tenant_id,o.customer_ref,o.customer_name,o.scope,o.state,o.reason,"
        + " o.evidence::text as evidence,o.recorded_by,o.recorded_at,o.revoked_by,o.revoked_at,"
        + " o.revoke_reason,o.created_at,o.updated_at"
        + " from crm_customer_opt_out o";
  }

  private static CrmCustomerContactRow contact(Record r) {
    String type = nz(r.get("contact_type", String.class));
    String direction = nz(r.get("direction", String.class));
    String origin = nz(r.get("origin", String.class));
    String compliance = nz(r.get("compliance_state", String.class));
    return new CrmCustomerContactRow(
        r.get("id", UUID.class),
        nz(r.get("contact_no", String.class)),
        nz(r.get("customer_ref", String.class)),
        nz(r.get("customer_name", String.class)),
        type,
        contactTypeLabel(type),
        direction,
        directionLabel(direction),
        origin,
        originLabel(origin),
        nz(r.get("source_module", String.class)),
        nz(r.get("source_ref", String.class)),
        nz(r.get("source_no", String.class)),
        nz(r.get("source_label", String.class)),
        r.get("shop_id", UUID.class),
        nz(r.get("shop_name", String.class)),
        nz(r.get("platform_code", String.class)),
        nz(r.get("order_reference", String.class)),
        nz(r.get("subject", String.class)),
        nz(r.get("summary", String.class)),
        r.get("duration_seconds", BigDecimal.class),
        str(r.get("occurred_at", OffsetDateTime.class)),
        r.get("operator_id", UUID.class),
        parseJson(r.get("detail", String.class)),
        compliance,
        complianceLabel(compliance),
        r.get("created_by", UUID.class),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmCustomerOptOutRow optOut(Record r) {
    String scope = nz(r.get("scope", String.class));
    String state = nz(r.get("state", String.class));
    return new CrmCustomerOptOutRow(
        r.get("id", UUID.class),
        nz(r.get("customer_ref", String.class)),
        nz(r.get("customer_name", String.class)),
        scope,
        optOutScopeLabel(scope),
        state,
        optOutStateLabel(state),
        nz(r.get("reason", String.class)),
        parseJson(r.get("evidence", String.class)),
        r.get("recorded_by", UUID.class),
        str(r.get("recorded_at", OffsetDateTime.class)),
        r.get("revoked_by", UUID.class),
        str(r.get("revoked_at", OffsetDateTime.class)),
        nz(r.get("revoke_reason", String.class)),
        str(r.get("created_at", OffsetDateTime.class)),
        str(r.get("updated_at", OffsetDateTime.class)));
  }

  private static CrmCustomerOptOutEventRow optOutEvent(Record r) {
    String eventType = nz(r.get("event_type", String.class));
    return new CrmCustomerOptOutEventRow(
        r.get("id", UUID.class),
        r.get("opt_out_id", UUID.class),
        nz(r.get("customer_ref", String.class)),
        eventType,
        optOutEventLabel(eventType),
        r.get("actor_id", UUID.class),
        str(r.get("occurred_at", OffsetDateTime.class)),
        nz(r.get("reason", String.class)),
        parseJson(r.get("detail", String.class)),
        str(r.get("created_at", OffsetDateTime.class)));
  }

  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 接触记录号：CT-yyyyMMdd-000001（序列单独授权，见 V299 授权段）。 */
  private String nextContactNo() {
    var row =
        sql.fetchOne(
            "select nextval('crm_customer_contact_no_seq') as seq,"
                + " to_char(now() at time zone 'utc','YYYYMMDD') as day");
    long seq = row.get("seq", Long.class);
    String day = row.get("day", String.class);
    return "CT-" + day + "-" + String.format("%06d", seq);
  }

  /**
   * 解析带时区偏移量的时刻。
   *
   * <p>刻意用 {@code OffsetDateTime.parse}：用 {@code Timestamp.valueOf(LocalDateTime)} 会在东八区
   * **静默偏 −28800 秒**，而页面看上去一切正常。
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
      throw new IllegalStateException("补充信息快照无法序列化", e);
    }
  }

  private static Map<String, Object> parseJson(String raw) {
    if (raw == null || raw.isBlank()) return new LinkedHashMap<>();
    try {
      return JSON.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return new LinkedHashMap<>();
    }
  }

  private static CustomerContactType contactType(String value) {
    String text = blankToNull(value);
    if (text == null) throw bad("必须指定接触类型。");
    try {
      return CustomerContactType.valueOf(text.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("接触类型只能是 " + String.join(" / ", names(CustomerContactType.values())) + "。");
    }
  }

  private static CustomerContactDirection contactDirection(String value) {
    String text = blankToNull(value);
    if (text == null) throw bad("必须指定接触方向（客户发起 / 我们发起）。");
    try {
      return CustomerContactDirection.valueOf(text.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("接触方向只能是 " + String.join(" / ", names(CustomerContactDirection.values())) + "。");
    }
  }

  private static CustomerOptOutScope optOutScope(String value) {
    String text = blankToNull(value);
    if (text == null) return CustomerOptOutScope.ALL;
    try {
      return CustomerOptOutScope.valueOf(text.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("退订范围只能是 " + String.join(" / ", names(CustomerOptOutScope.values())) + "。");
    }
  }

  private static CustomerContactCompliance compliance(String value) {
    String text = blankToNull(value);
    if (text == null) return CustomerContactCompliance.COMPLIANT;
    try {
      return CustomerContactCompliance.valueOf(text.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("合规状态只能是 " + String.join(" / ", names(CustomerContactCompliance.values())) + "。");
    }
  }

  /**
   * 营销触达与退订豁免的**前置判定**。
   *
   * <p>🔴 这是加粗句「接触记录是自动化的豁免依据」的**对外闸门**：
   * 客户在退订生效<b>之后</b>又发生了营销触达，要么显式承认违规（留痕），
   * 要么**落不了库**。
   *
   * <p>⚠️ 判定用 {@code occurred_at}（接触发生时刻）与退订登记时刻比较 ——
   * 退订之前的历史触达照常补录（补录历史不是补造违规）。
   *
   * <p>DB 层还有一道同样的闸（{@code guard_crm_customer_contact_marketing_opt_out}），
   * 这里是服务层的**同一口径**实现：它给出人能读的错误信息，
   * DB 那道保证「绕过服务层也拦得住」。
   */
  private void requireMarketingExempt(
      String customerRef,
      CustomerContactType type,
      OffsetDateTime occurredAt,
      CustomerContactCompliance compliance) {
    if (type != CustomerContactType.MARKETING) return;
    if (compliance == CustomerContactCompliance.VIOLATION) return;
    var row =
        sql.fetchOne(
            "select id, scope, recorded_at from crm_customer_opt_out"
                + " where tenant_id = ? and customer_ref = ? and state = 'ACTIVE'"
                + " and scope in ('ALL','MARKETING') and recorded_at <= ?::timestamptz"
                + " order by recorded_at desc limit 1",
            currentTenant(),
            customerRef,
            occurredAt);
    if (row == null) return;
    throw conflict(
        "客户 "
            + customerRef
            + " 已于 "
            + row.get("recorded_at", OffsetDateTime.class)
            + " 登记退订（范围 "
            + row.get("scope", String.class)
            + "）：退订生效之后的营销触达不能当作合规触达落库。"
            + "若确实发生了（要留痕），请显式标记为违规（complianceState=VIOLATION）；"
            + "若客户已经改主意，请先走显式的撤销退订动作。");
  }

  private static List<String> names(Enum<?>[] values) {
    List<String> out = new ArrayList<>();
    for (Enum<?> value : values) out.add(value.name());
    return out;
  }

  private static String contactTypeLabel(String value) {
    if (value == null) return "";
    try {
      return CustomerContactType.valueOf(value).label();
    } catch (IllegalArgumentException e) {
      return value;
    }
  }

  private static String directionLabel(String value) {
    if (value == null) return "";
    try {
      return CustomerContactDirection.valueOf(value).label();
    } catch (IllegalArgumentException e) {
      return value;
    }
  }

  private static String originLabel(String value) {
    if (value == null) return "";
    try {
      return CustomerContactOrigin.valueOf(value).label();
    } catch (IllegalArgumentException e) {
      return value;
    }
  }

  private static String complianceLabel(String value) {
    if (value == null) return "";
    try {
      return CustomerContactCompliance.valueOf(value).label();
    } catch (IllegalArgumentException e) {
      return value;
    }
  }

  private static String optOutScopeLabel(String value) {
    if (value == null) return "";
    try {
      return CustomerOptOutScope.valueOf(value).label();
    } catch (IllegalArgumentException e) {
      return value;
    }
  }

  private static String optOutStateLabel(String value) {
    if (value == null) return "";
    try {
      return CustomerOptOutState.valueOf(value).label();
    } catch (IllegalArgumentException e) {
      return value;
    }
  }

  private static String optOutEventLabel(String value) {
    if (value == null) return "";
    try {
      return CustomerOptOutEventType.valueOf(value).label();
    } catch (IllegalArgumentException e) {
      return value;
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
}
