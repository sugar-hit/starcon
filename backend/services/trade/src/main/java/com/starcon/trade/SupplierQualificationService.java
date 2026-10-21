package com.starcon.trade;

import static com.starcon.trade.SupplierQualificationModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.1.2.3 供应商资质。
 *
 * <p>蓝图原话：「登记与跟踪供应商资质：营业执照、质检报告、认证证书（CE/ROHS/ISO 等）、授权书，
 * 明确有效期与即将过期提醒。品牌授权需关联 03 品牌。」「**资质过期必须阻断相关商品的刊登与发货**，
 * 不能只做提醒。」
 *
 * <p><b>本类的四条纪律</b>：
 *
 * <ol>
 *   <li><b>到期状态不落库</b>：{@code expiry_state} 由 SQL 按<b>数据库时钟</b>与
 *       {@code valid_until} 现算（V239 第四节）。存一列就要一个每日任务去维护它，而维护出来的那天
 *       与读出口算出的那天差一天时，「这张证到底过期没有」会答错 —— 而这个答案直接决定能不能下单、
 *       能不能发货。
 *   <li><b>「即将过期」与「已过期」是两个状态</b>：前者仍在有效期内（只是落在提醒窗口里），
 *       后者已经越过有效期终点。<b>只有后者阻断</b>；前者只提醒。把两者揉成一个「快过期」标签，
 *       就会出现「还在有效期内却已经被拦下发货」的事故。
 *   <li><b>阻断是一等公民</b>：{@link BlockingDecision} 直接给 {@code blocked} + 原因码 + 人话，
 *       供 04 刊登与发货链路消费。提醒是给人看的，阻断是给机器执行的，两者分开。
 *   <li><b>跨服务只经授权 API</b>：供应商（catalog）与 03 品牌（catalog）都只存逻辑 ID + 名称快照，
 *       校验走 {@link SupplierPartyQueryPort} / {@link BrandQueryPort} /
 *       {@link QualificationSkuQueryPort}，不跨库 SQL、不跨库 FK、不跨库视图。
 * </ol>
 */
@Service
public class SupplierQualificationService {

  /** 单页最大条数。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 默认「即将过期」窗口（天）。与 6.1.1.2 的 EXPIRING_SOON_DAYS=30 同口径。 */
  private static final int DEFAULT_WARNING_DAYS = 30;

  /** 文件大小刻度：与 DB 的 numeric(19,6) 逐字对齐。 */
  private static final int SIZE_SCALE = 6;

  /**
   * 到期状态表达式（**不落库**，读出口现算）。
   *
   * <p>三段判定顺序是刻意的：
   *
   * <ul>
   *   <li>先看 {@code valid_from > now()} → 未生效：一张还没到起点的证与一张快过期的证，
   *       运营要做的动作完全不同（等它生效 vs 赶紧续期），不能共用一个标签；
   *   <li>再看 {@code valid_until <= now()} → 已过期；
   *   <li>最后看落在提醒窗口内 → 即将过期（窗口来自租户策略 {@code warning_days}）。
   * </ul>
   *
   * <p>「距到期天数」用 {@code floor(extract(epoch from ...)/86400)}：
   * 它是<b>精确的天数</b>（不是浮点近似），过期时为负 —— 界面照实显示「已过期 N 天」。
   */
  private static final String EXPIRY_STATE_EXPR =
      "case"
          + " when q.valid_from > now() then 'NOT_EFFECTIVE'"
          + " when q.valid_until <= now() then 'EXPIRED'"
          + " when floor(extract(epoch from (q.valid_until - now()))/86400)"
          + "       <= coalesce(w.warning_days, "
          + DEFAULT_WARNING_DAYS
          + ") then 'EXPIRING'"
          + " else 'VALID' end";

  /** 距到期天数（负 = 已过期 N 天）。 */
  private static final String DAYS_TO_EXPIRY_EXPR =
      "floor(extract(epoch from (q.valid_until - now()))/86400)::int";

  /** 资质档案的读出口字段（列表与详情共用一份定义，免得两处漂移）。 */
  private static final String QUALIFICATION_COLUMNS =
      "q.id,q.code,q.supplier_role_id,q.supplier_name_snapshot,q.qualification_type,q.title,q.cert_no,"
          + "q.issuer,q.brand_id,q.brand_name_snapshot,q.review_status,q.valid_from,q.valid_until,"
          + "q.submitted_at,q.reviewed_at,q.review_note,q.note,q.version,"
          + EXPIRY_STATE_EXPR
          + " as expiry_state,"
          + DAYS_TO_EXPIRY_EXPR
          + " as days_to_expiry,"
          + "(select count(*) from supplier_qualification_document d"
          + " where d.tenant_id=q.tenant_id and d.qualification_id=q.id) as document_count,"
          + "(select count(*) from supplier_qualification_reminder r"
          + " where r.tenant_id=q.tenant_id and r.qualification_id=q.id and r.status='OPEN')"
          + " as open_reminder_count";

  /** 资质档案的读出口来源（列表与详情共用一份定义）。 */
  private static final String QUALIFICATION_SOURCE =
      " from supplier_qualification q"
          + " left join supplier_qualification_policy w on w.tenant_id = q.tenant_id";

  private final DSLContext sql;
  private final TransactionTemplate tx;
  private final SupplierPartyQueryPort parties;
  private final BrandQueryPort brands;
  private final QualificationSkuQueryPort skus;

  public SupplierQualificationService(
      DSLContext sql,
      PlatformTransactionManager manager,
      SupplierPartyQueryPort parties,
      BrandQueryPort brands,
      QualificationSkuQueryPort skus) {
    this.sql = sql;
    this.tx = new TransactionTemplate(manager);
    this.parties = parties;
    this.brands = brands;
    this.skus = skus;
  }

  // ================================================================ 能力声明 / 词表 / 概览

  /**
   * 能力与上下游可用性。
   *
   * <p>存在的理由与 9.3.2.2 一致：把「哪些输入真有依据」「哪些下游还没交付」「阻断的口径是什么」
   * 显式报出来，而不是让调用方从 404 或空数组里去猜哪一环没通。
   */
  public SupplierQualificationCapabilities capabilities() {
    return new SupplierQualificationCapabilities(
        qualificationTypeOptions(),
        reviewStatusOptions(),
        expiryStateOptions(),
        List.of(
            new QualificationGap(
                "16.2.2.2",
                "认证与资质",
                "channel",
                false,
                "16 合规侧的认证缺失预警未交付：缺失类阻断（必备资质缺失 / 尚未生效）在本模块的阻断判定里"
                    + "已经落地，16 合规交付后直接消费同一个判定即可，不必再实现一遍。"),
            new QualificationGap(
                "16.2.1.2",
                "拦截执行",
                "channel",
                false,
                "拦截 / 豁免 / 审批归 16 合规，未交付：本模块只给出 blocked + 原因码，"
                    + "**不执行拦截**，也不替它决定豁免。"),
            new QualificationGap(
                "8.2.2.2",
                "待检处理",
                "warehouse",
                false,
                "8 收货的质检依据未交付：本模块提供「质检报告」类资质"
                    + "（QUALITY_INSPECTION_REPORT）与其有效期，供待检处理交付后取用；"
                    + "本模块**不**代替收货做质检结论。"),
            new QualificationGap(
                "6.1.1.2",
                "供应商档案（附件资质）",
                "catalog",
                true,
                "catalog 的供应商档案里另有一份「附件资质」列表（无审核状态、无品牌关联、不参与阻断）。"
                    + "**本模块是参与阻断判定的唯一资质来源**：只有它有审核状态与有效期政策。"
                    + "两份列表不自动同步，重复登记时以本模块的审核状态为准。")),
        "「资质过期必须阻断相关商品的刊登与发货，不能只做提醒」——本模块把这件事落成三件东西："
            + "（1）到期状态按数据库时钟现算，与策略里的提醒窗口一起判定；"
            + "（2）即将过期只提醒、已过期才阻断，两者不是一个标签；"
            + "（3）阻断判定端点直接给 blocked + 原因码 + 人话，供 04 刊登与发货链路调用，"
            + "「相关商品」按 6.1.2.1 已交付的货源关联解析出该商品背后的供应商。",
        "供应商与 03 品牌都在 catalog，本服务只存逻辑 ID 与名称 / 编码快照，"
            + "核对一律经 catalog 的授权只读接口，取不到就显式失败，绝不凭空生成供应商或品牌。");
  }

  /** 词表选项：前端下拉的数据源，与后端同源。 */
  public SupplierQualificationOptions options() {
    var policy = policy();
    return new SupplierQualificationOptions(
        qualificationTypeOptions(),
        reviewStatusOptions(),
        expiryStateOptions(),
        List.of(
            new QualificationOption(
                QualificationReminderKind.EXPIRING.name(),
                QualificationReminderKind.EXPIRING.label(),
                QualificationReminderKind.EXPIRING.hint()),
            new QualificationOption(
                QualificationReminderKind.EXPIRED.name(), QualificationReminderKind.EXPIRED.label(), QualificationReminderKind.EXPIRED.hint())),
        List.of(
            new QualificationOption(
                QualificationReminderStatus.OPEN.name(), QualificationReminderStatus.OPEN.label(), QualificationReminderStatus.OPEN.hint()),
            new QualificationOption(
                QualificationReminderStatus.ACKNOWLEDGED.name(),
                QualificationReminderStatus.ACKNOWLEDGED.label(),
                QualificationReminderStatus.ACKNOWLEDGED.hint()),
            new QualificationOption(
                QualificationReminderStatus.RESOLVED.name(),
                QualificationReminderStatus.RESOLVED.label(),
                QualificationReminderStatus.RESOLVED.hint())),
        List.of(
            new QualificationSortOption("validUntil", "到期日（近的在前）", true),
            new QualificationSortOption("code", "资质编码", false),
            new QualificationSortOption("type", "资质类型", false),
            new QualificationSortOption("status", "审核状态", false),
            new QualificationSortOption("supplier", "供应商名", false)),
        policy.warningDays(),
        policy.requiredTypes(),
        "「即将过期」窗口是租户级策略（当前 "
            + policy.warningDays()
            + " 天），到期状态按数据库时钟现算，界面上不要自己按客户端时钟重算。");
  }

  /** 概览统计（不受筛选与分页影响）。 */
  public QualificationOverview overview() {
    Record summary =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where q.review_status='DRAFT') as draft,"
                + " count(*) filter (where q.review_status='PENDING') as pending,"
                + " count(*) filter (where q.review_status='APPROVED') as approved,"
                + " count(*) filter (where q.review_status='REJECTED') as rejected,"
                + " count(*) filter (where q.review_status='REVOKED') as revoked,"
                + " count(*) filter (where "
                + EXPIRY_STATE_EXPR
                + " = 'EXPIRING') as expiring,"
                + " count(*) filter (where "
                + EXPIRY_STATE_EXPR
                + " = 'EXPIRED') as expired,"
                + " count(distinct q.supplier_role_id) as suppliers,"
                + " count(*) filter (where q.qualification_type='AUTHORIZATION_LETTER') as brand_auth"
                + QUALIFICATION_SOURCE);
    Record reminders =
        sql.fetchOne(
            "select count(*) as open_reminders, count(*) filter (where reminder_kind='EXPIRED') as expired_reminders"
                + " from supplier_qualification_reminder where status='OPEN'");
    Record blocking =
        sql.fetchOne(
            "select count(*) as blocking from supplier_qualification q"
                + " left join supplier_qualification_policy w on w.tenant_id=q.tenant_id"
                + " where q.review_status='REVOKED' or (q.review_status='APPROVED' and "
                + EXPIRY_STATE_EXPR
                + " = 'EXPIRED')");
    OffsetDateTime nearest =
        sql.fetchOne(
                "select min(q.valid_until) as nearest from supplier_qualification q"
                    + " where q.review_status='APPROVED'")
            .get("nearest", OffsetDateTime.class);
    return new QualificationOverview(
        count(summary, "total"),
        count(summary, "draft"),
        count(summary, "pending"),
        count(summary, "approved"),
        count(summary, "rejected"),
        count(summary, "revoked"),
        count(summary, "expiring"),
        count(summary, "expired"),
        count(blocking, "blocking"),
        count(reminders, "open_reminders"),
        count(summary, "suppliers"),
        count(summary, "brand_auth"),
        nearest == null ? null : nearest.toString(),
        "「即将过期」与「已过期」是两个不同的数：前者仍在有效期内（只提醒），"
            + "后者已越过有效期终点（**阻断**刊登与发货）。");
  }

  // ================================================================ 策略

  /** 租户级策略（缺失时用默认值：30 天窗口 + 必备营业执照）。 */
  public QualificationPolicyRow policy() {
    Record r =
        sql.fetchOne(
            "select warning_days, array_to_string(required_types, ',') as required_types_csv,"
                + " note, updated_at from supplier_qualification_policy where tenant_id = ?",
            currentTenant());
    if (r == null) {
      return new QualificationPolicyRow(
          DEFAULT_WARNING_DAYS,
          List.of(QualificationType.BUSINESS_LICENSE.name()),
          "",
          null);
    }
    return new QualificationPolicyRow(
        r.get("warning_days", Integer.class),
        splitTypes(r.get("required_types_csv", String.class)),
        nullToEmpty(r.get("note", String.class)),
        instant(r, "updated_at"));
  }

  /** 修改租户级策略（提醒窗口 + 必备资质类型）。 */
  public QualificationPolicyRow putPolicy(QualificationPolicyInput input) {
    List<String> types = new ArrayList<>(new LinkedHashSet<>(input.requiredTypes()));
    for (String type : types) {
      qualificationType(type);
    }
    if (types.isEmpty()) {
      throw bad("必备资质类型不能为空：没有必备资质，「资质缺失」这条阻断就永远不可能发生");
    }
    OffsetDateTime now = now();
    sql.execute(
        "insert into supplier_qualification_policy"
            + "(tenant_id,warning_days,required_types,note,updated_by,updated_at,created_at)"
            + " values(?,?,string_to_array(?,',')::text[],?,?,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id) do update set warning_days=excluded.warning_days,"
            + " required_types=excluded.required_types, note=excluded.note,"
            + " updated_by=excluded.updated_by, updated_at=excluded.updated_at",
        currentTenant(),
        input.warningDays(),
        String.join(",", types),
        fit(input.note(), 2000, "备注"),
        null,
        now,
        now);
    return policy();
  }

  // ================================================================ 资质档案

  /** 资质列表（真分页 + 真筛选 + 真排序；汇总与筛选一致但不受分页影响）。 */
  public QualificationPage pageQualifications(QualificationQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (query.supplierRoleId() != null) {
      where.add("q.supplier_role_id = ?");
      binds.add(query.supplierRoleId());
    }
    if (query.qualificationType() != null && !query.qualificationType().isBlank()) {
      where.add("q.qualification_type = ?");
      binds.add(qualificationType(query.qualificationType()).name());
    }
    if (query.reviewStatus() != null && !query.reviewStatus().isBlank()) {
      where.add("q.review_status = ?");
      binds.add(reviewStatus(query.reviewStatus()).name());
    }
    if (query.brandId() != null) {
      where.add("q.brand_id = ?");
      binds.add(query.brandId());
    }
    if (query.expiryState() != null && !query.expiryState().isBlank()) {
      where.add("(" + EXPIRY_STATE_EXPR + ") = ?");
      binds.add(expiryState(query.expiryState()).name());
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      // 真实模糊检索：命中编码 / 证件名称 / 证书编号 / 供应商名（LIKE 通配符已转义）。
      where.add(
          "(q.code ilike ? or q.title ilike ? or q.cert_no ilike ? or q.supplier_name_snapshot ilike ?)");
      String pattern = likePattern(query.keyword());
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    String sort = sortColumn(query.sort());
    boolean desc = query.desc();

    Record summary =
        sql.fetchOne(
            "select count(distinct q.id) as total,"
                + " count(distinct q.id) filter (where "
                + EXPIRY_STATE_EXPR
                + " = 'EXPIRING') as expiring,"
                + " count(distinct q.id) filter (where "
                + EXPIRY_STATE_EXPR
                + " = 'EXPIRED') as expired,"
                + " count(distinct q.id) filter (where q.review_status='APPROVED') as approved,"
                + " count(distinct r.qualification_id) filter (where r.status='OPEN') as open_reminders"
                + " from supplier_qualification q"
                + " left join supplier_qualification_policy w on w.tenant_id = q.tenant_id"
                + " left join supplier_qualification_reminder r on r.tenant_id = q.tenant_id"
                + " and r.qualification_id = q.id"
                + clause,
            binds.toArray());

    List<QualificationRow> items =
        sql.fetch(
                "select "
                    + QUALIFICATION_COLUMNS
                    + QUALIFICATION_SOURCE
                    + clause
                    + " order by "
                    + sort
                    + (desc ? " desc" : " asc")
                    + ", q.id limit ? offset ?",
                append(binds, size, page * size).toArray())
            .map(SupplierQualificationService::qualificationRow);
    return new QualificationPage(
        items,
        page,
        size,
        count(summary, "total"),
        count(summary, "expiring"),
        count(summary, "expired"),
        count(summary, "approved"),
        count(summary, "open_reminders"),
        "到期状态按数据库时钟现算，汇总与当前筛选一致（只是不受分页影响）。");
  }

  /** 资质详情：档案 + 全部文件 + 全部审核流转。 */
  public QualificationDetail qualification(UUID id) {
    Record head = sql.fetchOne("select " + QUALIFICATION_COLUMNS + QUALIFICATION_SOURCE + " where q.id = ?", id);
    if (head == null) {
      throw notFound("供应商资质 " + id + " 不存在或无权查看");
    }
    List<QualificationDocumentRow> documents =
        sql.fetch(
                "select id,qualification_id,document_kind,document_key,document_name,mime_type,size_bytes,"
                    + " uploaded_at,note from supplier_qualification_document"
                    + " where qualification_id = ? order by uploaded_at desc, id desc",
                id)
            .map(SupplierQualificationService::documentRow);
    List<QualificationReviewEventRow> reviews =
        sql.fetch(
                "select id,qualification_id,seq,action,from_status,to_status,reason,operated_at"
                    + " from supplier_qualification_review where qualification_id = ? order by seq",
                id)
            .map(SupplierQualificationService::reviewRow);
    return new QualificationDetail(
        qualificationRow(head),
        documents,
        reviews,
        "文件与审核流转都是**只追加**的：当时交的是什么、为什么被驳回，改不掉了 —— "
            + "审核留痕被抹掉等于没有审核。");
  }

  /** 登记资质。供应商与品牌都经 catalog 核对；本库只存逻辑 ID 与名称快照。 */
  public QualificationRow createQualification(QualificationInput input, String bearer) {
    QualificationType type = qualificationType(input.qualificationType());
    String code = fit(input.code(), 64, "资质编码");
    if (code.isEmpty()) {
      throw bad("资质编码不能为空");
    }
    if (exists("select 1 from supplier_qualification where code = ?", code)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "资质编码「" + code + "」已被本租户其它资质占用");
    }
    // 供应商事实在 catalog：核对 + 取名快照，本库只存逻辑 ID 与快照字段。
    var supplier = parties.require(input.supplierRoleId(), bearer);
    String brandName = "";
    if (input.brandId() != null) {
      var brand = brands.require(input.brandId(), bearer);
      brandName = brand.name();
    }
    if (type == QualificationType.AUTHORIZATION_LETTER && input.brandId() == null) {
      throw bad("授权书是品牌授权，必须关联 03 品牌：没有品牌就答不出「这个供应商能不能卖这个牌子」");
    }
    OffsetDateTime validFrom = parseInstant(input.validFrom(), false, "有效期起点");
    OffsetDateTime validUntil = parseInstant(input.validUntil(), true, "有效期终点");
    if (!validUntil.isAfter(validFrom)) {
      throw bad("有效期终点必须晚于起点");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime now = now();
    sql.execute(
        "insert into supplier_qualification(id,tenant_id,code,supplier_role_id,supplier_name_snapshot,"
            + "qualification_type,title,cert_no,issuer,brand_id,brand_name_snapshot,review_status,"
            + "valid_from,valid_until,note,version,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?::timestamptz,?::timestamptz,?,0,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        code,
        input.supplierRoleId(),
        supplier.partyName(),
        type.name(),
        fit(input.title(), 200, "证件名称"),
        fit(input.certNo(), 64, "证书编号"),
        fit(input.issuer(), 120, "发证机构"),
        input.brandId(),
        brandName,
        validFrom,
        validUntil,
        fit(input.note(), 2000, "备注"),
        now,
        now);
    return rowOf(id);
  }

  /**
   * 修改资质（证件名称 / 编号 / 发证机构 / 品牌 / 有效期 / 备注）。
   *
   * <p>只允许改**尚未生效结论**的资质：DRAFT / REJECTED / REVOKED。
   * 已经 APPROVED 的资质要改内容必须先撤销（REVOKE）—— 否则「审核通过的到底是什么」就说不清了。
   */
  public QualificationRow updateQualification(UUID id, QualificationUpdateInput input, String bearer) {
    Record head = sql.fetchOne("select * from supplier_qualification where id = ?", id);
    if (head == null) {
      throw notFound("供应商资质 " + id + " 不存在或无权修改");
    }
    QualificationReviewStatus status = QualificationReviewStatus.valueOf(head.get("review_status", String.class));
    if (status == QualificationReviewStatus.APPROVED || status == QualificationReviewStatus.PENDING) {
      throw bad(
          "资质当前是「"
              + status.label()
              + "」，不能直接改内容：已通过的资质要先撤销，已提交的资质要先撤回审核（等待审核结论）");
    }
    requireVersion(head.get("version", Long.class), input.version());
    String brandName = "";
    if (input.brandId() != null) {
      var brand = brands.require(input.brandId(), bearer);
      brandName = brand.name();
    }
    if ("AUTHORIZATION_LETTER".equals(head.get("qualification_type", String.class))
        && input.brandId() == null) {
      throw bad("授权书是品牌授权，必须关联 03 品牌");
    }
    OffsetDateTime validFrom = parseInstant(input.validFrom(), false, "有效期起点");
    OffsetDateTime validUntil = parseInstant(input.validUntil(), true, "有效期终点");
    if (!validUntil.isAfter(validFrom)) {
      throw bad("有效期终点必须晚于起点");
    }
    OffsetDateTime now = now();
    int updated =
        sql.execute(
            "update supplier_qualification set title=?,cert_no=?,issuer=?,brand_id=?,brand_name_snapshot=?,"
                + "valid_from=?::timestamptz,valid_until=?::timestamptz,note=?,version=version+1,"
                + "updated_at=?::timestamptz where id=? and version=?",
            fit(input.title(), 200, "证件名称"),
            fit(input.certNo(), 64, "证书编号"),
            fit(input.issuer(), 120, "发证机构"),
            input.brandId(),
            brandName,
            validFrom,
            validUntil,
            fit(input.note(), 2000, "备注"),
            now,
            id,
            input.version());
    if (updated == 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "资质已被他人修改，请刷新后重试，绝不静默覆盖");
    }
    return rowOf(id);
  }

  /**
   * 审核流转（状态机）。
   *
   * <p>允许的转换只有六条（与 V239 触发器同一张图）：DRAFT→PENDING、PENDING→APPROVED、
   * PENDING→REJECTED、REJECTED→PENDING、APPROVED→REVOKED、REVOKED→PENDING。
   * 驳回与撤销**必须**写明理由 —— 没有理由的驳回，供应商永远不知道自己该改什么。
   */
  public QualificationReviewSaved review(UUID id, QualificationReviewInput input) {
    QualificationReviewAction action = reviewAction(input.action());
    String reason = fit(input.reason(), 2000, "理由");
    if ((action == QualificationReviewAction.REJECT || action == QualificationReviewAction.REVOKE) && reason.isEmpty()) {
      throw bad((action == QualificationReviewAction.REJECT ? "驳回" : "撤销") + "必须写明理由");
    }
    return tx.execute(
        status -> {
          Record head =
              sql.fetchOne(
                  "select id,review_status,version from supplier_qualification where id = ? for update", id);
          if (head == null) {
            throw notFound("供应商资质 " + id + " 不存在或无权审核");
          }
          QualificationReviewStatus from = QualificationReviewStatus.valueOf(head.get("review_status", String.class));
          QualificationReviewStatus to = targetOf(action, from);
          OffsetDateTime now = now();
          boolean isConclusion =
              to == QualificationReviewStatus.APPROVED || to == QualificationReviewStatus.REJECTED || to == QualificationReviewStatus.REVOKED;
          sql.execute(
              "update supplier_qualification set review_status=?,"
                  + "reviewed_by=case when ? then ? else reviewed_by end,"
                  + "reviewed_at=case when ? then ?::timestamptz else reviewed_at end,"
                  + "review_note=case when ? then ? else review_note end,"
                  + "version=version+1,updated_at=?::timestamptz where id=?",
              to.name(),
              isConclusion,
              null,
              isConclusion,
              now,
              isConclusion,
              reason,
              now,
              id);
          int seq =
              sql.fetchOne(
                      "select coalesce(max(seq),0) as seq from supplier_qualification_review"
                          + " where qualification_id = ?",
                      id)
                  .get("seq", Integer.class);
          sql.execute(
              "insert into supplier_qualification_review(id,tenant_id,qualification_id,seq,action,"
                  + "from_status,to_status,reason,operated_by,operated_at,created_at)"
                  + " values(?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
              UUID.randomUUID(),
              currentTenant(),
              id,
              seq + 1,
              action.name(),
              from.name(),
              to.name(),
              reason,
              null,
              now,
              now);
          return new QualificationReviewSaved(id, action.name(), from.name(), to.name(), seq + 1, now.toString(), reason);
        });
  }

  /** 上传资质文件（文件本体不入库，只存对象存储的 key）。 */
  public QualificationDocumentSaved uploadDocument(UUID id, QualificationDocumentInput input) {
    if (!exists("select 1 from supplier_qualification where id = ?", id)) {
      throw notFound("供应商资质 " + id + " 不存在或无权上传文件");
    }
    QualificationDocumentKind kind = documentKind(input.documentKind());
    OffsetDateTime now = now();
    UUID docId = UUID.randomUUID();
    sql.execute(
        "insert into supplier_qualification_document(id,tenant_id,qualification_id,document_kind,"
            + "document_key,document_name,mime_type,size_bytes,uploaded_by,uploaded_at,note,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?::timestamptz,?,?::timestamptz)",
        docId,
        currentTenant(),
        id,
        kind.name(),
        fit(input.documentKey(), 500, "文件 key"),
        fit(input.documentName(), 200, "文件名"),
        fit(input.mimeType(), 120, "MIME 类型"),
        size(input.sizeBytes()),
        null,
        now,
        fit(input.note(), 2000, "备注"),
        now);
    return new QualificationDocumentSaved(docId, id, kind.name(), now.toString());
  }

  // ================================================================ 过期提醒

  /**
   * 生成 / 刷新过期提醒。
   *
   * <p>幂等：同一只资质、同一类提醒、同一到期日只生成一条（重复扫描不刷屏）。
   * 「即将过期」与「已过期」是**两条不同的提醒** —— 前者还在有效期内（该去续期），
   * 后者已经越界（该停下来）。
   */
  public QualificationReminderScanResult scanReminders() {
    OffsetDateTime now = now();
    int warningDays = policy().warningDays();
    List<Record> candidates =
        sql.fetch(
            "select q.id, q.valid_until::date as due_date,"
                + " case when q.valid_until <= now() then 'EXPIRED' else 'EXPIRING' end as reminder_kind"
                + " from supplier_qualification q"
                + " left join supplier_qualification_policy w on w.tenant_id = q.tenant_id"
                + " where q.review_status='APPROVED'"
                + " and q.valid_until <= now() + make_interval(days => coalesce(w.warning_days, "
                + DEFAULT_WARNING_DAYS
                + "))");
    int created = 0;
    int expiring = 0;
    int expired = 0;
    for (Record candidate : candidates) {
      String kind = candidate.get("reminder_kind", String.class);
      if ("EXPIRED".equals(kind)) {
        expired++;
      } else {
        expiring++;
      }
      created +=
          sql.execute(
              "insert into supplier_qualification_reminder(id,tenant_id,qualification_id,reminder_kind,"
                  + "due_date,warning_days,status,generated_at,note,version,created_at,updated_at)"
                  + " values(?,?,?,?,?,?,'OPEN',?::timestamptz,'',0,?::timestamptz,?::timestamptz)"
                  + " on conflict (tenant_id,qualification_id,reminder_kind,due_date) do nothing",
              UUID.randomUUID(),
              currentTenant(),
              candidate.get("id", UUID.class),
              kind,
              candidate.get("due_date", LocalDate.class),
              warningDays,
              now,
              now,
              now);
    }
    return new QualificationReminderScanResult(
        created,
        expiring,
        expired,
        now.toString(),
        "提醒是**给人看的**（谁被提醒了、有没有人处理）；"
            + "**阻断**是另一件事，由阻断判定端点给出 —— 一条未处理的提醒不等于已经阻断。");
  }

  /** 提醒清单（真分页 + 真筛选）。 */
  public QualificationReminderPage pageReminders(QualificationReminderQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (query.supplierRoleId() != null) {
      where.add("q.supplier_role_id = ?");
      binds.add(query.supplierRoleId());
    }
    if (query.reminderKind() != null && !query.reminderKind().isBlank()) {
      where.add("r.reminder_kind = ?");
      binds.add(reminderKind(query.reminderKind()).name());
    }
    if (query.status() != null && !query.status().isBlank()) {
      where.add("r.status = ?");
      binds.add(reminderStatus(query.status()).name());
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) from supplier_qualification_reminder r"
                    + " join supplier_qualification q on q.tenant_id=r.tenant_id and q.id=r.qualification_id"
                    + clause,
                binds.toArray())
            .get(0, Long.class);
    Record summary =
        sql.fetchOne(
            "select count(*) filter (where status='OPEN') as open_count,"
                + " count(*) filter (where reminder_kind='EXPIRING') as expiring,"
                + " count(*) filter (where reminder_kind='EXPIRED') as expired"
                + " from supplier_qualification_reminder");
    List<QualificationReminderRow> items =
        sql.fetch(
                "select r.id,r.qualification_id,r.reminder_kind,r.due_date,r.warning_days,r.status,"
                    + " r.generated_at,r.acknowledged_at,r.note,q.code,q.title,q.supplier_role_id,"
                    + " q.supplier_name_snapshot,q.qualification_type"
                    + " from supplier_qualification_reminder r"
                    + " join supplier_qualification q on q.tenant_id=r.tenant_id and q.id=r.qualification_id"
                    + clause
                    + " order by r.due_date, r.id limit ? offset ?",
                append(binds, size, page * size).toArray())
            .map(SupplierQualificationService::reminderRow);
    return new QualificationReminderPage(
        items,
        page,
        size,
        total,
        count(summary, "open_count"),
        count(summary, "expiring"),
        count(summary, "expired"),
        "「即将过期」与「已过期」分列显示：前者还在有效期内（该去续期），后者已经越界（该停下来）。");
  }

  /** 处理一条提醒（确认 / 关闭）。 */
  public QualificationReminderRow handleReminder(UUID id, QualificationReminderHandleInput input) {
    QualificationReminderStatus to = reminderStatus(input.status());
    Record head =
        sql.fetchOne("select id,status from supplier_qualification_reminder where id = ?", id);
    if (head == null) {
      throw notFound("过期提醒 " + id + " 不存在或无权处理");
    }
    QualificationReminderStatus from = QualificationReminderStatus.valueOf(head.get("status", String.class));
    if (!allowedReminderTransition(from, to)) {
      throw bad("提醒状态不可从「" + from.label() + "」变更为「" + to.label() + "」");
    }
    OffsetDateTime now = now();
    sql.execute(
        "update supplier_qualification_reminder set status=?,note=?,acknowledged_by=?,"
            + "acknowledged_at=?::timestamptz,version=version+1,updated_at=?::timestamptz where id=?",
        to.name(),
        fit(input.note(), 2000, "备注"),
        null,
        now,
        now,
        id);
    return reminderRow(
        sql.fetchOne(
            "select r.id,r.qualification_id,r.reminder_kind,r.due_date,r.warning_days,r.status,"
                + " r.generated_at,r.acknowledged_at,r.note,q.code,q.title,q.supplier_role_id,"
                + " q.supplier_name_snapshot,q.qualification_type"
                + " from supplier_qualification_reminder r"
                + " join supplier_qualification q on q.tenant_id=r.tenant_id and q.id=r.qualification_id"
                + " where r.id = ?",
            id));
  }

  // ================================================================ 阻断判定

  /**
   * 供应商维度的阻断判定（可带品牌维度）。
   *
   * <p>蓝图加粗句的落点：过期 / 撤销 / 必备资质缺失 → {@code blocked=true} + 原因码 + 人话。
   * 「即将过期」**不**阻断 —— 它还在有效期内，拦下来是误伤。
   */
  public BlockingDecision supplierBlocking(UUID supplierRoleId, UUID brandId, String bearer) {
    var supplier = parties.require(supplierRoleId, bearer);
    var policy = policy();
    List<Record> quals =
        sql.fetch(
            "select " + QUALIFICATION_COLUMNS + QUALIFICATION_SOURCE + " where q.supplier_role_id = ?"
                + " order by q.valid_until, q.id",
            supplierRoleId);
    String brandName = "";
    if (brandId != null) {
      brandName = brands.require(brandId, bearer).name();
    }
    List<BlockingReason> reasons =
        evaluate(quals, policy.requiredTypes(), brandId, supplierRoleId, supplier.partyName());
    String scope = brandId == null ? "SUPPLIER" : "BRAND";
    String subject =
        brandId == null
            ? "供应商「" + supplier.partyName() + "」"
            : "供应商「" + supplier.partyName() + "」的品牌「" + brandName + "」";
    return decision(
        scope,
        subject,
        reasons,
        "阻断判定基于**本模块的资质档案**（唯一有审核状态与有效期政策的那一份）；"
            + "「即将过期」只提醒不阻断，「已过期」与「已撤销」才阻断。");
  }

  /**
   * 商品维度的阻断判定。
   *
   * <p>「相关商品」怎么来的：经 6.1.2.1 已交付的货源关联
   * （{@code supplier_source_sku_link → supplier_source_item → supplier_source_stall}，
   * **同库同服务的只读**）解析出这个商品背后的供应商，再逐个判资质。
   * 没有另建一套「商品 ↔ 供应商」关系 —— 那会造出第二个写入所有者。
   *
   * <p>品牌维度在这一层**不**自动判定：catalog 的 SKU 读出口只给品牌**名**（{@code SkuDetail.brand}），
   * 没有品牌 id，按名字反查品牌是编事实。品牌维度改由
   * {@code /suppliers/{supplierRoleId}/blocking-decision?brandId=} 精确判定。
   */
  public BlockingDecision skuBlocking(UUID skuId, String bearer) {
    var sku = skus.require(skuId, bearer);
    List<UUID> suppliers =
        sql.fetch(
                "select distinct s.supplier_party_id as supplier_party_id from supplier_source_sku_link l"
                    + " join supplier_source_item i on i.tenant_id=l.tenant_id and i.id=l.item_id"
                    + " join supplier_source_stall s on s.tenant_id=i.tenant_id and s.id=i.stall_id"
                    + " where l.sku_id = ? and s.supplier_party_id is not null",
                skuId)
            .map(r -> r.get("supplier_party_id", UUID.class));
    var policy = policy();
    List<BlockingReason> reasons = new ArrayList<>();
    for (UUID supplierRoleId : suppliers) {
      var supplier = parties.require(supplierRoleId, bearer);
      List<Record> quals =
          sql.fetch(
              "select " + QUALIFICATION_COLUMNS + QUALIFICATION_SOURCE + " where q.supplier_role_id = ?"
                  + " order by q.valid_until, q.id",
              supplierRoleId);
      reasons.addAll(
          evaluate(quals, policy.requiredTypes(), null, supplierRoleId, supplier.partyName()));
    }
    String subject = "商品「" + sku.code() + "」";
    return decision(
        "SKU",
        subject,
        reasons,
        suppliers.isEmpty()
            ? "这个商品还没有关联任何供应商（6.1.2.1 的货源关联），因此没有可判定的资质；"
                + "关联建立后本端点会立刻给出结论。"
            : "该商品背后的供应商取自 6.1.2.1 已交付的货源关联（只读），逐个判资质后汇总。");
  }

  /**
   * 逐份资质 + 必备类型 + 品牌授权，算出阻断原因。
   *
   * <p><b>判定单位是「要求」而不是「单份证件」</b>：同一个供应商、同一种类型（品牌授权再加上同一个品牌）
   * 归成一组。组里有任意一份**当前有效**的，整组就满足要求 —— 这一条是为了续期：
   * 续期会新增一份，旧的那份自然过期，但「这个供应商现在有没有有效资质」 answered 仍然是「有」，
   * 不能因为旧件过期就把他拦下。
   *
   * <p>组的判定口径（与 {@code capabilities()} 里那份说明一一对应）：
   *
   * <ul>
   *   <li>组内有当前有效的一份 → <b>不</b>阻断（续期场景）；
   *   <li>否则组内有过期的已通过证件 → 阻断「资质已过期」；
   *   <li>否则组内有已撤销的证件 → 阻断「资质已撤销」；
   *   <li>否则组内什么都没有（草稿 / 待审 / 驳回）→
   *       若该类型是<b>必备</b>类型则阻断「资质缺失」（已通过但尚未生效的算「尚未生效」），
   *       非必备类型不阻断（它只是还没准备好，不该拦发货）；
   *   <li><b>即将过期</b> → <b>不</b>阻断（仍在有效期内），只提醒。
   * </ul>
   */
  private List<BlockingReason> evaluate(
      List<Record> quals,
      List<String> requiredTypes,
      UUID brandId,
      UUID supplierRoleId,
      String supplierName) {
    List<BlockingReason> reasons = new ArrayList<>();

    // ---- 按「类型 + 品牌」归组。品牌授权那一组在品牌维度下走品牌口径，
    //      不重复报一遍「资质已过期」（一条事实只说一次）。
    var groups = new LinkedHashMap<String, List<Record>>();
    for (Record qual : quals) {
      String type = qual.get("qualification_type", String.class);
      UUID qualBrand = qual.get("brand_id", UUID.class);
      boolean isBrandAuth = "AUTHORIZATION_LETTER".equals(type) && qualBrand != null;
      if (brandId != null && isBrandAuth) {
        // 品牌维度下：别的品牌的授权书与本判定无关（判的是「这个供应商能不能卖这个牌子」）。
        if (!qualBrand.equals(brandId)) {
          continue;
        }
      }
      groups
          .computeIfAbsent(type + "|" + (qualBrand == null ? "-" : qualBrand), k -> new ArrayList<>())
          .add(qual);
    }

    List<String> groupKeys = new ArrayList<>(groups.keySet());
    // 过期 / 撤销先报（更具体），缺失类后报；组内按到期日升序，最近过期的排前面。
    List<Record> expiredRecords = new ArrayList<>();
    List<Record> revokedRecords = new ArrayList<>();
    // 已经满足要求的类型（组内有当前有效的一份）。
    var satisfiedTypes = new java.util.LinkedHashSet<String>();
    // 已经报过原因的类型（更具体的原因优先，不再补一条「缺失」—— 那会把一条事实说成两条）。
    var reportedTypes = new java.util.LinkedHashSet<String>();
    for (String key : groupKeys) {
      List<Record> group = groups.get(key);
      boolean hasValid = false;
      for (Record qual : group) {
        String reviewStatus = qual.get("review_status", String.class);
        String expiryState = qual.get("expiry_state", String.class);
        if ("APPROVED".equals(reviewStatus)
            && ("VALID".equals(expiryState) || "EXPIRING".equals(expiryState))) {
          hasValid = true;
          break;
        }
      }
      String type = group.get(0).get("qualification_type", String.class);
      if (hasValid) {
        // 组内已有一份当前有效的（续期场景）→ 整组满足要求。
        satisfiedTypes.add(type);
        continue;
      }
      boolean brandScoped = brandId != null && "AUTHORIZATION_LETTER".equals(type);
      boolean pendingStart = false;
      boolean reported = false;
      for (Record qual : group) {
        String reviewStatus = qual.get("review_status", String.class);
        String expiryState = qual.get("expiry_state", String.class);
        if ("REVOKED".equals(reviewStatus)) {
          if (!brandScoped) {
            revokedRecords.add(qual);
            reported = true;
          }
        } else if ("APPROVED".equals(reviewStatus) && "EXPIRED".equals(expiryState)) {
          if (!brandScoped) {
            expiredRecords.add(qual);
            reported = true;
          }
        } else if ("APPROVED".equals(reviewStatus) && "NOT_EFFECTIVE".equals(expiryState)) {
          pendingStart = true;
        }
      }
      if (brandScoped) {
        // 品牌维度下，这一组由下面的品牌口径统一报，避免同一条事实报两遍。
        continue;
      }
      if (reported) {
        reportedTypes.add(type);
        continue;
      }
      if (!requiredTypes.contains(type)) {
        // 非必备类型：没有有效件也不阻断（它只是还没准备好，不该拦发货）。
        continue;
      }
      String label = qualificationType(type).label();
      if (pendingStart) {
        reasons.add(
            reason(
                "QUALIFICATION_NOT_YET_EFFECTIVE",
                "必备资质尚未生效",
                "必备资质「"
                    + label
                    + "」已通过审核但还没到有效期起点，当前没有一份有效的「"
                    + label
                    + "」，阻断刊登与发货。",
                null,
                supplierRoleId,
                supplierName));
      } else {
        reasons.add(
            reason(
                "QUALIFICATION_MISSING",
                "必备资质缺失",
                "没有一份当前有效的「" + label + "」，资质缺失阻断刊登与发货。",
                null,
                supplierRoleId,
                supplierName));
      }
      reportedTypes.add(type);
    }

    // ---- 必备类型里**一份都没登记**的：缺失阻断。
    for (String required : requiredTypes) {
      if (satisfiedTypes.contains(required) || reportedTypes.contains(required)) {
        continue;
      }
      String label = qualificationType(required).label();
      reasons.add(
          reason(
              "QUALIFICATION_MISSING",
              "必备资质缺失",
              "没有一份当前有效的「" + label + "」，资质缺失阻断刊登与发货。",
              null,
              supplierRoleId,
              supplierName));
    }

    // ---- 已过期的证件（按到期日升序，最近过期的排前面）
    expiredRecords.sort(
        java.util.Comparator.comparing(r -> r.get("valid_until", OffsetDateTime.class)));
    for (Record qual : expiredRecords) {
      String type = qual.get("qualification_type", String.class);
      String title = qual.get("title", String.class);
      reasons.add(
          reason(
              "QUALIFICATION_EXPIRED",
              "资质已过期",
              "「"
                  + title
                  + "」（"
                  + qualificationType(type).label()
                  + "）已于 "
                  + qual.get("valid_until", OffsetDateTime.class).toLocalDate()
                  + " 过期（已过期 "
                  + Math.abs(qual.get("days_to_expiry", Integer.class))
                  + " 天），资质过期必须阻断刊登与发货。",
              qual,
              supplierRoleId,
              supplierName));
    }

    // ---- 已撤销的证件
    for (Record qual : revokedRecords) {
      String type = qual.get("qualification_type", String.class);
      String title = qual.get("title", String.class);
      reasons.add(
          reason(
              "QUALIFICATION_REVOKED",
              "资质已撤销",
              "「"
                  + title
                  + "」（"
                  + qualificationType(type).label()
                  + "）已被撤销，不再是有效资质，阻断刊登与发货。",
              qual,
              supplierRoleId,
              supplierName));
    }

    // ---- 品牌授权（给了 brandId 才判）
    if (brandId != null) {
      List<Record> brandGroup = groups.get("AUTHORIZATION_LETTER|" + brandId);
      boolean valid = false;
      boolean revoked = false;
      Record expiredRecord = null;
      if (brandGroup != null) {
        for (Record qual : brandGroup) {
          String reviewStatus = qual.get("review_status", String.class);
          String expiryState = qual.get("expiry_state", String.class);
          if ("APPROVED".equals(reviewStatus)
              && ("VALID".equals(expiryState) || "EXPIRING".equals(expiryState))) {
            valid = true;
            break;
          }
          if ("REVOKED".equals(reviewStatus)) {
            revoked = true;
          }
          if ("APPROVED".equals(reviewStatus) && "EXPIRED".equals(expiryState) && expiredRecord == null) {
            expiredRecord = qual;
          }
        }
      }
      if (!valid) {
        if (revoked) {
          reasons.add(
              reason(
                  "BRAND_AUTHORIZATION_REVOKED",
                  "品牌授权已撤销",
                  "该品牌的授权书已被撤销，不能再按这个牌子销售，阻断刊登与发货。",
                  null,
                  supplierRoleId,
                  supplierName));
        } else if (expiredRecord != null) {
          reasons.add(
              reason(
                  "BRAND_AUTHORIZATION_EXPIRED",
                  "品牌授权已过期",
                  "该品牌的授权书已于 "
                      + expiredRecord.get("valid_until", OffsetDateTime.class).toLocalDate()
                      + " 过期，品牌授权失效，阻断刊登与发货。",
                  expiredRecord,
                  supplierRoleId,
                  supplierName));
        } else {
          reasons.add(
              reason(
                  "BRAND_AUTHORIZATION_MISSING",
                  "品牌授权缺失",
                  "没有一份当前有效的品牌授权书，不能按这个牌子销售，阻断刊登与发货。",
                  null,
                  supplierRoleId,
                  supplierName));
        }
      }
    }
    return reasons;
  }

  /** 把原因列表拼成阻断结论。 */
  private BlockingDecision decision(
      String scope, String subject, List<BlockingReason> reasons, String note) {
    boolean blocked = !reasons.isEmpty();
    String code = blocked ? reasons.get(0).code() : null;
    String label = blocked ? reasons.get(0).label() : null;
    String message;
    if (blocked) {
      var details = new ArrayList<String>();
      for (var reason : reasons) {
        details.add(reason.label() + "：" + reason.message());
      }
      message =
          subject
              + "存在 "
              + reasons.size()
              + " 项资质问题，按蓝图要求**阻断**刊登与发货 —— "
              + String.join("；", details);
    } else {
      message = subject + "的资质齐备且在有效期内，不阻断刊登与发货。";
    }
    return new BlockingDecision(
        blocked, code, label, message, scope, now().toString(), reasons, note);
  }

  /** 由资质行构造一条阻断原因。 */
  private static BlockingReason reason(
      String code,
      String label,
      String message,
      Record qual,
      UUID supplierRoleId,
      String supplierName) {
    return new BlockingReason(
        code,
        label,
        message,
        qual == null ? null : qual.get("id", UUID.class),
        qual == null ? null : qual.get("code", String.class),
        qual == null ? null : qual.get("qualification_type", String.class),
        qual == null ? null : qualificationType(qual.get("qualification_type", String.class)).label(),
        qual == null ? null : qual.get("review_status", String.class),
        qual == null ? null : QualificationReviewStatus.valueOf(qual.get("review_status", String.class)).label(),
        qual == null ? null : qual.get("expiry_state", String.class),
        qual == null ? null : expiryState(qual.get("expiry_state", String.class)).label(),
        qual == null || qual.get("valid_until", OffsetDateTime.class) == null
            ? null
            : qual.get("valid_until", OffsetDateTime.class).toString(),
        qual == null ? null : qual.get("days_to_expiry", Integer.class),
        supplierRoleId,
        supplierName);
  }

  // ================================================================ 候选（跨服务只读）

  /** 供应商候选（catalog 的 SUPPLIER 角色，只读）。 */
  public List<QualificationSupplierCandidateRow> supplierCandidates(String keyword, String bearer) {
    return parties.suppliers(keyword, bearer).stream()
        .map(row -> new QualificationSupplierCandidateRow(row.roleId(), row.partyCode(), row.partyName(), row.partyStatus()))
        .toList();
  }

  /** 品牌候选（03 品牌，只读）。 */
  public List<QualificationBrandCandidateRow> brandCandidates(String keyword, String bearer) {
    return brands.brands(keyword, bearer).stream()
        .map(
            row ->
                new QualificationBrandCandidateRow(
                    row.id(), row.code(), row.name(), row.authorizationStatus(), row.enabled()))
        .toList();
  }

  // ================================================================ 行映射与工具

  private QualificationRow rowOf(UUID id) {
    Record r =
        sql.fetchOne("select " + QUALIFICATION_COLUMNS + QUALIFICATION_SOURCE + " where q.id = ?", id);
    if (r == null) {
      throw notFound("供应商资质 " + id + " 不存在或无权查看");
    }
    return qualificationRow(r);
  }

  private static QualificationRow qualificationRow(Record r) {
    String type = r.get("qualification_type", String.class);
    String reviewStatus = r.get("review_status", String.class);
    String expiry = r.get("expiry_state", String.class);
    return new QualificationRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("supplier_role_id", UUID.class),
        nullToEmpty(r.get("supplier_name_snapshot", String.class)),
        type,
        qualificationType(type).label(),
        nullToEmpty(r.get("title", String.class)),
        nullToEmpty(r.get("cert_no", String.class)),
        nullToEmpty(r.get("issuer", String.class)),
        r.get("brand_id", UUID.class),
        nullToEmpty(r.get("brand_name_snapshot", String.class)),
        reviewStatus,
        QualificationReviewStatus.valueOf(reviewStatus).label(),
        instant(r, "valid_from"),
        instant(r, "valid_until"),
        expiry,
        expiryState(expiry).label(),
        r.get("days_to_expiry", Integer.class),
        instant(r, "submitted_at"),
        instant(r, "reviewed_at"),
        nullToEmpty(r.get("review_note", String.class)),
        count(r, "document_count"),
        count(r, "open_reminder_count"),
        nullToEmpty(r.get("note", String.class)),
        r.get("version", Long.class));
  }

  private static QualificationDocumentRow documentRow(Record r) {
    String kind = r.get("document_kind", String.class);
    return new QualificationDocumentRow(
        r.get("id", UUID.class),
        r.get("qualification_id", UUID.class),
        kind,
        documentKind(kind).label(),
        nullToEmpty(r.get("document_key", String.class)),
        nullToEmpty(r.get("document_name", String.class)),
        nullToEmpty(r.get("mime_type", String.class)),
        decimal(r, "size_bytes"),
        instant(r, "uploaded_at"),
        nullToEmpty(r.get("note", String.class)));
  }

  private static QualificationReviewEventRow reviewRow(Record r) {
    String action = r.get("action", String.class);
    String from = r.get("from_status", String.class);
    String to = r.get("to_status", String.class);
    return new QualificationReviewEventRow(
        r.get("id", UUID.class),
        r.get("qualification_id", UUID.class),
        r.get("seq", Integer.class),
        action,
        reviewAction(action).label(),
        from,
        QualificationReviewStatus.valueOf(from).label(),
        to,
        QualificationReviewStatus.valueOf(to).label(),
        nullToEmpty(r.get("reason", String.class)),
        instant(r, "operated_at"));
  }

  private static QualificationReminderRow reminderRow(Record r) {
    String kind = r.get("reminder_kind", String.class);
    String status = r.get("status", String.class);
    String type = r.get("qualification_type", String.class);
    LocalDate due = r.get("due_date", LocalDate.class);
    return new QualificationReminderRow(
        r.get("id", UUID.class),
        r.get("qualification_id", UUID.class),
        nullToEmpty(r.get("code", String.class)),
        nullToEmpty(r.get("title", String.class)),
        r.get("supplier_role_id", UUID.class),
        nullToEmpty(r.get("supplier_name_snapshot", String.class)),
        type,
        qualificationType(type).label(),
        kind,
        reminderKind(kind).label(),
        due == null ? null : due.toString(),
        r.get("warning_days", Integer.class),
        status,
        reminderStatus(status).label(),
        instant(r, "generated_at"),
        instant(r, "acknowledged_at"),
        nullToEmpty(r.get("note", String.class)));
  }

  private static List<QualificationOption> qualificationTypeOptions() {
    List<QualificationOption> options = new ArrayList<>();
    for (QualificationType type : QualificationType.values()) {
      options.add(new QualificationOption(type.name(), type.label(), type.hint()));
    }
    return options;
  }

  private static List<QualificationOption> reviewStatusOptions() {
    List<QualificationOption> options = new ArrayList<>();
    for (QualificationReviewStatus status : QualificationReviewStatus.values()) {
      options.add(new QualificationOption(status.name(), status.label(), status.hint()));
    }
    return options;
  }

  private static List<QualificationOption> expiryStateOptions() {
    List<QualificationOption> options = new ArrayList<>();
    for (QualificationExpiryState state : QualificationExpiryState.values()) {
      options.add(new QualificationOption(state.name(), state.label(), state.hint()));
    }
    return options;
  }

  /** 排序键白名单（写死，不接受任意列名）。 */
  private static String sortColumn(String key) {
    if (key == null || key.isBlank()) {
      return "q.valid_until";
    }
    return switch (key.trim()) {
      case "validUntil" -> "q.valid_until";
      case "code" -> "q.code";
      case "type" -> "q.qualification_type";
      case "status" -> "q.review_status";
      case "supplier" -> "q.supplier_name_snapshot";
      default -> throw bad("未知的排序键「" + key + "」：只能是 validUntil / code / type / status / supplier");
    };
  }

  /** 状态机的目标状态：只允许 V239 触发器里那六条边。 */
  private static QualificationReviewStatus targetOf(QualificationReviewAction action, QualificationReviewStatus from) {
    QualificationReviewStatus to =
        switch (action) {
          case SUBMIT -> from == QualificationReviewStatus.DRAFT ? QualificationReviewStatus.PENDING : null;
          case APPROVE -> from == QualificationReviewStatus.PENDING ? QualificationReviewStatus.APPROVED : null;
          case REJECT -> from == QualificationReviewStatus.PENDING ? QualificationReviewStatus.REJECTED : null;
          case REVOKE -> from == QualificationReviewStatus.APPROVED ? QualificationReviewStatus.REVOKED : null;
          case RESUBMIT ->
              (from == QualificationReviewStatus.REJECTED || from == QualificationReviewStatus.REVOKED) ? QualificationReviewStatus.PENDING : null;
        };
    if (to == null) {
      throw bad(
          "「"
              + action.label()
              + "」在当前状态「"
              + from.label()
              + "」下不合法：允许的流转是 DRAFT→PENDING、PENDING→APPROVED、PENDING→REJECTED、"
              + "REJECTED→PENDING、APPROVED→REVOKED、REVOKED→PENDING");
    }
    return to;
  }

  private static boolean allowedReminderTransition(QualificationReminderStatus from, QualificationReminderStatus to) {
    return (from == QualificationReminderStatus.OPEN && to == QualificationReminderStatus.ACKNOWLEDGED)
        || (from == QualificationReminderStatus.OPEN && to == QualificationReminderStatus.RESOLVED)
        || (from == QualificationReminderStatus.ACKNOWLEDGED && to == QualificationReminderStatus.RESOLVED);
  }

  private static QualificationType qualificationType(String value) {
    try {
      return QualificationType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad(
          "未知的资质类型「"
              + value
              + "」：只能是 "
              + Arrays.stream(QualificationType.values()).map(Enum::name).reduce((a, b) -> a + " / " + b)
                  .orElseThrow());
    }
  }

  private static QualificationReviewStatus reviewStatus(String value) {
    try {
      return QualificationReviewStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad(
          "未知的审核状态「"
              + value
              + "」：只能是 "
              + Arrays.stream(QualificationReviewStatus.values()).map(Enum::name).reduce((a, b) -> a + " / " + b)
                  .orElseThrow());
    }
  }

  private static QualificationReviewAction reviewAction(String value) {
    try {
      return QualificationReviewAction.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad(
          "未知的审核动作「"
              + value
              + "」：只能是 "
              + Arrays.stream(QualificationReviewAction.values()).map(Enum::name).reduce((a, b) -> a + " / " + b)
                  .orElseThrow());
    }
  }

  private static QualificationExpiryState expiryState(String value) {
    try {
      return QualificationExpiryState.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad(
          "未知的到期状态「"
              + value
              + "」：只能是 "
              + Arrays.stream(QualificationExpiryState.values()).map(Enum::name).reduce((a, b) -> a + " / " + b)
                  .orElseThrow());
    }
  }

  private static QualificationDocumentKind documentKind(String value) {
    try {
      return QualificationDocumentKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad(
          "未知的文件类别「"
              + value
              + "」：只能是 "
              + Arrays.stream(QualificationDocumentKind.values()).map(Enum::name).reduce((a, b) -> a + " / " + b)
                  .orElseThrow());
    }
  }

  private static QualificationReminderKind reminderKind(String value) {
    try {
      return QualificationReminderKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的提醒类别「" + value + "」：只能是 EXPIRING / EXPIRED");
    }
  }

  private static QualificationReminderStatus reminderStatus(String value) {
    try {
      return QualificationReminderStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的提醒状态「" + value + "」：只能是 OPEN / ACKNOWLEDGED / RESOLVED");
    }
  }

  /**
   * 解析有效期时刻。
   *
   * <p>带时区的 ISO-8601（{@code 2026-12-31T00:00:00Z} / {@code 2026-12-31T08:00+08:00}）按原样取用；
   * 只给日期（{@code 2026-12-31}）时：起点取当日 00:00、终点取当日 23:59:59.999999999 ——
   * 「有效期到 2026-12-31」的自然含义是「12 月 31 日这一整天还算有效」。
   */
  private static OffsetDateTime parseInstant(String value, boolean endOfDay, String label) {
    String raw = value == null ? "" : value.trim();
    if (raw.isEmpty()) {
      throw bad(label + "不能为空");
    }
    try {
      return OffsetDateTime.parse(raw);
    } catch (DateTimeParseException ignored) {
      // 落到下面按日期解析。
    }
    try {
      var day = LocalDate.parse(raw);
      // 终点取当日最后一**微秒**（PostgreSQL 的 timestamptz 精度到微秒，
      // 写成 999999999 纳秒会被四舍五入到第二天 00:00，界面上就变成了「有效期多了一天」）。
      return endOfDay
          ? day.atTime(23, 59, 59, 999_999).atOffset(ZoneOffset.UTC)
          : day.atStartOfDay().atOffset(ZoneOffset.UTC);
    } catch (DateTimeParseException e) {
      throw bad(label + "不是合法的时间：需要 ISO-8601（如 2026-12-31T00:00:00Z）或日期（2026-12-31）");
    }
  }

  /** 文件大小：数量刻度与全仓一致（numeric(19,6)），禁浮点。 */
  private static BigDecimal size(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(SIZE_SCALE, RoundingMode.HALF_UP)
        : value.setScale(SIZE_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal decimal(Record r, String field) {
    Object value = r.get(field);
    if (value == null) {
      return BigDecimal.ZERO.setScale(SIZE_SCALE, RoundingMode.HALF_UP);
    }
    return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
  }

  private static List<String> splitTypes(String csv) {
    if (csv == null || csv.isBlank()) {
      return List.of();
    }
    List<String> types = new ArrayList<>();
    for (String part : csv.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        types.add(trimmed);
      }
    }
    return types;
  }

  private static String likePattern(String keyword) {
    String escaped = keyword.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static String instant(Record r, String column) {
    OffsetDateTime value = r.get(column, OffsetDateTime.class);
    // 统一折算到 UTC 再输出：timestamptz 存的是**绝对时刻**，但驱动按**会话时区**渲染
    // （服务器在 +08:00 时，同一个时刻会渲染成 "+08:00" 而不是 "Z"）。
    // 契约里必须给一个与服务器时区无关的稳定写法，否则同一份数据在不同部署上长得不一样。
    return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toString();
  }

  private static long count(Record r, String column) {
    Object value = r.get(column);
    return value == null ? 0L : ((Number) value).longValue();
  }

  private static String fit(String value, int max, String label) {
    if (value != null && value.length() > max) {
      throw new IllegalArgumentException(label + "长度不能超过 " + max + " 个字符");
    }
    return value == null ? "" : value.trim();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private boolean exists(String statement, Object... binds) {
    return sql.fetchOne(statement, binds) != null;
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    all.addAll(Arrays.asList(more));
    return all;
  }

  private static void requireVersion(long actual, long expected) {
    if (actual != expected) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "资质已被他人修改（当前版本 " + actual + "，你的版本 " + expected + "）。请刷新后重试，绝不静默覆盖");
    }
  }

  /**
   * 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。
   *
   * <p>表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
   * **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错（与 V92 同一句写法）。
   */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
