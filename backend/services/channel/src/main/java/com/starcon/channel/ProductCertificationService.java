package com.starcon.channel;

import static com.starcon.channel.ProductCertificationModels.*;
import static com.starcon.channel.ProductCertificationRules.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.2.2.2 认证与资质。
 *
 * <p>蓝图：「管理产品认证与资质（CE、FCC、RoHS、PSE、FDA、CCC 等）：认证编号、有效期、适用 SKU、
 * 对应类目要求，支持上传扫描件与到期提醒。输入：认证录入；文件上传；有效期。输出：认证档案 +
 * 到期提醒 + 覆盖情况统计（哪些 SKU 缺认证）。关系：上游 = 6.1.2.3 供应商资质；下游 = 16.2.2.1 预检、
 * 4 刊登。<b>认证缺失是 listing 被抑制与下架的高频原因</b>，且属于"上架时不管、出事才补"的典型滞后项。」
 *
 * <p>🔴🔴 本类里有五处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>认证类型是受控词表</b>：内置类型在 {@code certification_type}（迁移种子数据），
 *       租户可新增 / 改名 / 停用（{@code tenant_certification_type}）。本类**没有**类型常量数组，
 *       连服务层的校验都查库 —— 校验不查库的话，DB 触发器就成了唯一一道闸。
 *   <li><b>到期状态不落库</b>：{@code expiryState} 由 {@code now()}（<b>数据库时钟</b>）现算，
 *       租户级 {@code warning_days} 决定「即将过期」的窗口。
 *   <li><b>覆盖统计是真的算出来的</b>：要求侧（{@code product_certification_requirement}）
 *       对上覆盖侧（有效且适用该 SKU 的认证），按 SKU / 类目 / 类型三个口径现算。
 *   <li><b>扫描件真存</b>：文件本体写进受管目录，元数据入库，文件行只追加。
 *   <li><b>只写自己的表，只读上游</b>：本类只写 V247 的七张租户表；
 *       SKU / 类目主数据在 catalog，<b>只经授权 HTTP API 读</b>，读不到就<b>显式报读不到</b>。
 * </ol>
 *
 * <p>🔴 <b>跨服务只经授权 HTTP API 读</b>（{@link ProductCertificationSourcePort}）：
 * 租户不由本服务传递 —— 转发的是调用方自己的 bearer，上游仍按已认证成员关系解析租户。
 */
@Service
public class ProductCertificationService {

  private static final String EXPIRY_SEMANTICS =
      "到期状态**不落库**，每次读按数据库时钟 now() 现算："
          + "REVOKED（已撤销）> FUTURE（尚未生效）> EXPIRED（已过期）> EXPIRING（落在租户级 "
          + "warning_days 窗口内）> VALID（有效）。剩余天数按 floor 计算（向下取整），"
          + "所以「还剩 12 小时」显示成「还剩 0 天」而不是「还剩 1 天」—— 那是同一天到期，"
          + "不该被当成还宽裕。";

  private static final String COVERAGE_SEMANTICS =
      "覆盖统计 = **要求侧**（这个 SKU 需要哪些认证类型）对上**覆盖侧**"
          + "（有没有一张当前有效、且适用该 SKU 的同类型认证）。"
          + "「当前有效」= 未撤销 + 已生效 + 未过期，且有效期按数据库时钟判定。"
          + "按 SKU / 按类目 / 按认证类型三个口径是同一套判定的三个切面，不是三个口径各算一遍。";

  private static final String VOCABULARY_NOTE =
      "认证类型是**受控词表**，不是自由文本：生效词表 = 内置类型 ∪ 本租户字典，**租户行优先**"
          + "（租户行存在即以租户行为准，可以改名、可以停用）。内置类型清单在数据库里"
          + "（certification_type），不在代码里 —— 否则「这个租户在用哪些认证类型」永远答不出来。"
          + "停用只影响**新登记**：已挂上去的认证仍然显示，只是不再允许新增。";

  private static final String UPSTREAM_NOTE =
      "SKU 与类目主数据在 **catalog**，本模块**只经授权 HTTP API 读**"
          + "（/catalog/skus、/catalog/categories/trees），不跨库查表。"
          + "🔴 上游读不到时**显式报「读不到」**（503 + 原因），**绝不**拿空列表冒充 —— "
          + "空列表会被读成「这个租户没有 SKU / 没有类目要求」，而真相是「我们没查到」。";

  private static final String BOUNDARY_NOTE =
      "🔴 与 6.1.2.3 供应商资质是**两件事**：那边是**供应商侧**的资质（营业执照 / 授权书），"
          + "在 trade 服务；这边是**产品侧**的认证（CE / FCC / RoHS），在 channel 服务。"
          + "🔴 与 catalog 的 sku_certification 也是两件事：那边是完整度评分的「认证**声明**」"
          + "（有没有这项要求），这边是**认证档案**（编号 / 发证机构 / 有效期 / 扫描件）。"
          + "🔴 与 16.2.2.1 合规预检：那边出体检报告，本模块出档案与提醒；"
          + "那边「认证缺失」维度的依据就是本模块的档案。";

  /** 剩余天数的 SQL 表达式（floor：向下取整，负数 = 已过期几天）。 */
  private static final String DAYS_REMAINING =
      "floor(extract(epoch from (c.valid_until - now()))/86400)::int";

  private final DSLContext sql;
  private final ProductCertificationSourcePort source;
  private final CertificationDocumentStorage storage;

  public ProductCertificationService(
      DSLContext sql,
      ProductCertificationSourcePort source,
      CertificationDocumentStorage storage) {
    this.sql = sql;
    this.source = source;
    this.storage = storage;
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

  private static ResponseStatusException unavailable(String message) {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  /** 解析一个 UUID 参数（畸形 id 拒掉，不折成 null 混进 SQL）。 */
  private static UUID uuid(String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw unprocessable(label + " 不能为空");
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (RuntimeException e) {
      throw unprocessable(label + " 不是合法的 UUID");
    }
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  public ProductCertificationCapabilities capabilities() {
    return new ProductCertificationCapabilities(
        STATUSES,
        EXPIRY_STATES,
        DOCUMENT_KINDS,
        SCOPES,
        DEFAULT_WARNING_DAYS,
        MAX_DOCUMENT_BYTES,
        BROWSER_PROXY_BASE64_LIMIT_BYTES,
        EXPIRY_SEMANTICS,
        COVERAGE_SEMANTICS,
        VOCABULARY_NOTE,
        UPSTREAM_NOTE,
        BOUNDARY_NOTE);
  }

  // ==================================================================
  // 认证类型词表
  // ==================================================================

  /** 生效词表：内置 ∪ 租户，租户行优先。 */
  public List<CertificationTypeRow> types() {
    UUID tenant = currentTenant();
    List<Record> rows =
        sql.fetch(
            """
            select v.code, v.name, v.scope, v.description, v.enabled, v.tenant_defined
              from product_certification_type_view v
             order by v.sort_order, v.code
            """);
    List<CertificationTypeRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(
          new CertificationTypeRow(
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("scope", String.class),
              row.get("description", String.class),
              Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
              Boolean.TRUE.equals(row.get("tenant_defined", Boolean.class))));
    }
    return items;
  }

  /** 词表里某个编码当前是否可用（新增认证前校验用）。 */
  private boolean typeEnabled(String code) {
    Object value =
        sql.fetchValue(
            """
            select v.enabled
              from product_certification_type_view v
             where v.code = ?
            """,
            code);
    return Boolean.TRUE.equals(value);
  }

  /**
   * 新增 / 覆盖租户字典行。
   *
   * <p>内置类型也能被覆盖（改名 / 停用）—— 租户行优先。覆盖内置类型时，描述留空就沿用内置的。
   */
  public CertificationTypeRow upsertType(String code, CertificationTypeInput input) {
    UUID tenant = currentTenant();
    String normalized = normalizeTypeCode(code);
    if (!validTypeCode(normalized)) {
      throw unprocessable("认证类型编码只能是大写字母、数字、下划线与短横线，且不超过 32 位");
    }
    if (!normalized.equals(normalizeTypeCode(input.code()))) {
      throw unprocessable("路径上的认证类型编码与请求体里的不一致");
    }
    String scope = normalizeScope(input.scope());
    if (!SCOPES.contains(scope)) {
      throw unprocessable("适用范围只能是 GLOBAL（全球通用）或 MARKETPLACE（平台/市场特定）");
    }
    String name = text(input.name());
    if (name.isEmpty()) {
      throw unprocessable("认证类型名称不能为空");
    }
    String description = text(input.description());
    if (description.isEmpty()) {
      Object builtin =
          sql.fetchValue("select description from certification_type where code = ?", normalized);
      description = builtin == null ? "" : String.valueOf(builtin);
    }
    boolean enabled = input.enabled() == null || input.enabled();
    int sortOrder = input.sortOrder() == null ? 100 : input.sortOrder();

    sql.execute(
        """
        insert into tenant_certification_type
          (id,tenant_id,code,name,scope,description,sort_order,enabled,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,now(),now())
        on conflict (tenant_id,code) do update
           set name = excluded.name,
               scope = excluded.scope,
               description = excluded.description,
               sort_order = excluded.sort_order,
               enabled = excluded.enabled,
               updated_at = now()
        """,
        UUID.randomUUID(),
        tenant,
        normalized,
        name,
        scope,
        description,
        sortOrder,
        enabled);

    return new CertificationTypeRow(normalized, name, scope, description, enabled, true);
  }

  /** 删除租户字典行（回到内置默认）。 */
  public void deleteType(String code) {
    UUID tenant = currentTenant();
    String normalized = normalizeTypeCode(code);
    int deleted;
    try {
      deleted =
          sql.execute(
              "delete from tenant_certification_type where tenant_id = ? and code = ?",
              tenant,
              normalized);
    } catch (org.springframework.dao.DataIntegrityViolationException
        | org.jooq.exception.DataAccessException e) {
      // DB 触发器 guard_tenant_certification_type_delete 挡住了「还有人用就删」。
      // ⚠️ jOOQ 走的是 Spring 的 SQLExceptionTranslator，抛出来的是 Spring 的
      //    DataIntegrityViolationException（不是 jOOQ 自己的 DataAccessException），两种都要接。
      throw conflict("这个认证类型已被认证档案或认证要求引用，不能删除（请改为停用）");
    }
    if (deleted == 0) {
      throw notFound();
    }
  }

  // ==================================================================
  // 租户级策略
  // ==================================================================

  public CertificationPolicyView policy() {
    UUID tenant = currentTenant();
    Record row =
        sql.fetchOne(
            "select warning_days, note, updated_at from product_certification_policy where tenant_id = ?",
            tenant);
    if (row == null) {
      return new CertificationPolicyView(DEFAULT_WARNING_DAYS, "", null);
    }
    return new CertificationPolicyView(
        row.get("warning_days", Integer.class),
        row.get("note", String.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  public CertificationPolicyView updatePolicy(CertificationPolicyInput input, UUID userId) {
    UUID tenant = currentTenant();
    if (input.warningDays() == null) {
      throw unprocessable("即将过期窗口必须给");
    }
    int warningDays = input.warningDays();
    if (warningDays < 1 || warningDays > 365) {
      throw unprocessable("即将过期窗口必须是 1..365 天");
    }
    sql.execute(
        """
        insert into product_certification_policy
          (tenant_id,warning_days,note,updated_by,updated_at,created_at)
        values (?,?,?,?,now(),now())
        on conflict (tenant_id) do update
           set warning_days = excluded.warning_days,
               note = excluded.note,
               updated_by = excluded.updated_by,
               updated_at = now()
        """,
        tenant,
        warningDays,
        text(input.note()),
        userId);
    return policy();
  }

  /** 租户的「即将过期」窗口天数（没有策略行时用默认值 30 天）。 */
  private int warningDays(UUID tenant) {
    Object value =
        sql.fetchValue(
            "select warning_days from product_certification_policy where tenant_id = ?", tenant);
    return value == null ? DEFAULT_WARNING_DAYS : ((Number) value).intValue();
  }

  // ==================================================================
  // 认证档案
  // ==================================================================

  /** 到期状态的 SQL 表达式（现算，warning_days 是校验过的整数，直接内联安全）。 */
  private static String expiryCase(int warningDays) {
    return "case when c.status='REVOKED' then 'REVOKED'"
        + " when c.valid_from > now() then 'FUTURE'"
        + " when c.valid_until <= now() then 'EXPIRED'"
        + " when c.valid_until <= now() + make_interval(days => "
        + warningDays
        + ") then 'EXPIRING'"
        + " else 'VALID' end";
  }

  /** 认证档案台账（真分页：筛选 / 排序 / LIMIT / OFFSET 全在 SQL 上）。 */
  public CertificationPage certifications(
      String typeCode,
      String status,
      String expiryState,
      String keyword,
      String skuId,
      String categoryId,
      int page,
      int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    int offset = Math.max(0, page) * limit;
    int days = warningDays(tenant);

    StringBuilder where = new StringBuilder(" where c.tenant_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    if (text(typeCode).length() > 0) {
      where.append(" and c.certification_type_code = ?");
      binds.add(normalizeTypeCode(typeCode));
    }
    if (text(status).length() > 0) {
      where.append(" and c.status = ?");
      binds.add(status.trim().toUpperCase());
    }
    if (text(expiryState).length() > 0) {
      where.append(" and (" + expiryCase(days) + ") = ?");
      binds.add(expiryState.trim().toUpperCase());
    }
    if (text(keyword).length() > 0) {
      where.append(" and (c.title ilike ? or c.cert_no ilike ? or c.code ilike ?)");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    if (text(skuId).length() > 0) {
      where.append(
          " and exists (select 1 from product_certification_sku s"
              + " where s.tenant_id = c.tenant_id and s.certification_id = c.id and s.sku_id = ?)");
      binds.add(uuid(skuId, "SKU id"));
    }
    if (text(categoryId).length() > 0) {
      where.append(
          " and exists (select 1 from product_certification_category cc"
              + " where cc.tenant_id = c.tenant_id and cc.certification_id = c.id and cc.category_id = ?)");
      binds.add(uuid(categoryId, "类目 id"));
    }

    long total =
        ((Number)
                sql.fetchValue(
                    "select count(*) from product_certification c" + where, binds.toArray()))
            .longValue();

    List<Record> rows =
        sql.fetch(
            """
            select c.id, c.code, c.certification_type_code, coalesce(v.name, c.certification_type_code) as certification_type_name,
                   c.title, c.cert_no, c.issuer, c.status,
                   c.valid_from, c.valid_until,
                   (%s) as expiry_state,
                   (%s) as days_remaining,
                   (select count(*) from product_certification_sku s
                     where s.tenant_id = c.tenant_id and s.certification_id = c.id) as sku_count,
                   (select count(*) from product_certification_category cc
                     where cc.tenant_id = c.tenant_id and cc.certification_id = c.id) as category_count,
                   (select count(*) from product_certification_document d
                     where d.tenant_id = c.tenant_id and d.certification_id = c.id) as document_count,
                   c.created_at, c.updated_at
              from product_certification c
              left join product_certification_type_view v on v.code = c.certification_type_code
            %s
             order by c.valid_until asc, c.id
             limit ? offset ?
            """
                .formatted(expiryCase(days), DAYS_REMAINING, where),
            concat(binds, limit, offset));

    List<CertificationSummary> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(
          new CertificationSummary(
              row.get("id", UUID.class).toString(),
              row.get("code", String.class),
              row.get("certification_type_code", String.class),
              row.get("certification_type_name", String.class),
              row.get("title", String.class),
              row.get("cert_no", String.class),
              row.get("issuer", String.class),
              row.get("status", String.class),
              row.get("expiry_state", String.class),
              row.get("valid_from", OffsetDateTime.class),
              row.get("valid_until", OffsetDateTime.class),
              ((Number) row.get("days_remaining", Integer.class)).intValue(),
              ((Number) row.get("sku_count", Long.class)).intValue(),
              ((Number) row.get("category_count", Long.class)).intValue(),
              ((Number) row.get("document_count", Long.class)).intValue(),
              row.get("created_at", OffsetDateTime.class),
              row.get("updated_at", OffsetDateTime.class)));
    }
    return new CertificationPage(items, total, Math.max(0, page), limit);
  }

  /** 概览（页面顶部四张卡）。 */
  public CertificationOverview overview() {
    UUID tenant = currentTenant();
    int days = warningDays(tenant);
    Record row =
        sql.fetchOne(
            """
            select count(*) as total,
                   count(*) filter (where c.status='ACTIVE') as active,
                   count(*) filter (where c.status='REVOKED') as revoked,
                   count(*) filter (where c.status='ACTIVE' and c.valid_until > now()
                                    and c.valid_until <= now() + make_interval(days => %d)) as expiring,
                   count(*) filter (where c.status='ACTIVE' and c.valid_until <= now()) as expired
              from product_certification c
             where c.tenant_id = ?
            """
                .formatted(days),
            tenant);
    CoverageReport coverage = coverage();
    return new CertificationOverview(
        longOf(row, "total"),
        longOf(row, "active"),
        longOf(row, "expiring"),
        longOf(row, "expired"),
        longOf(row, "revoked"),
        coverage.coveredSkuCount(),
        coverage.missingSkuCount(),
        days);
  }

  private static long longOf(Record row, String column) {
    Number value = row.get(column, Number.class);
    return value == null ? 0L : value.longValue();
  }

  /** 认证档案详情（含适用 SKU / 对应类目 / 扫描件）。 */
  public CertificationDetail certification(String id) {
    UUID tenant = currentTenant();
    UUID certificationId = uuid(id, "认证 id");
    int days = warningDays(tenant);
    Record row =
        sql.fetchOne(
            """
            select c.id, c.code, c.certification_type_code, coalesce(v.name, c.certification_type_code) as certification_type_name,
                   c.title, c.cert_no, c.issuer, c.issuing_country, c.status,
                   c.valid_from, c.valid_until,
                   (%s) as expiry_state,
                   (%s) as days_remaining,
                   c.revoked_at, c.revoked_reason, c.note, c.version,
                   c.created_at, c.updated_at
              from product_certification c
              left join product_certification_type_view v on v.code = c.certification_type_code
             where c.tenant_id = ? and c.id = ?
            """
                .formatted(expiryCase(days), DAYS_REMAINING),
            tenant,
            certificationId);
    if (row == null) {
      throw notFound();
    }
    return new CertificationDetail(
        row.get("id", UUID.class).toString(),
        row.get("code", String.class),
        row.get("certification_type_code", String.class),
        row.get("certification_type_name", String.class),
        row.get("title", String.class),
        row.get("cert_no", String.class),
        row.get("issuer", String.class),
        row.get("issuing_country", String.class),
        row.get("status", String.class),
        row.get("expiry_state", String.class),
        row.get("valid_from", OffsetDateTime.class),
        row.get("valid_until", OffsetDateTime.class),
        ((Number) row.get("days_remaining", Integer.class)).intValue(),
        row.get("revoked_at", OffsetDateTime.class),
        row.get("revoked_reason", String.class),
        row.get("note", String.class),
        ((Number) row.get("version", Long.class)).longValue(),
        skus(tenant, certificationId),
        categories(tenant, certificationId),
        documents(tenant, certificationId),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 登记一张认证。 */
  public CertificationDetail createCertification(ProductCertificationInput input) {
    UUID tenant = currentTenant();
    String code = text(input.code());
    if (!validCertificationCode(code)) {
      throw unprocessable(
          "认证编号只能包含字母、数字、下划线与短横线，且不超过 64 位（第一个字符必须是字母或数字）");
    }
    String typeCode = normalizeTypeCode(input.certificationTypeCode());
    requireType(typeCode);
    OffsetDateTime validFrom = input.validFrom();
    OffsetDateTime validUntil = input.validUntil();
    if (validFrom == null || validUntil == null) {
      throw unprocessable("有效期起止都必须给");
    }
    if (!validUntil.isAfter(validFrom)) {
      throw unprocessable("有效期截止必须晚于生效时间");
    }
    if (text(input.title()).isEmpty()) {
      throw unprocessable("证书名称不能为空");
    }

    UUID id = UUID.randomUUID();
    try {
      sql.execute(
          """
          insert into product_certification
            (id,tenant_id,code,certification_type_code,title,cert_no,issuer,issuing_country,
             status,valid_from,valid_until,note,version,created_at,updated_at)
          values (?,?,?,?,?,?,?,?, 'ACTIVE',?::timestamptz,?::timestamptz,?,0,now(),now())
          """,
          id,
          tenant,
          code,
          typeCode,
          text(input.title()),
          text(input.certNo()),
          text(input.issuer()),
          text(input.issuingCountry()).toUpperCase(),
          validFrom,
          validUntil,
          text(input.note()));
    } catch (org.springframework.dao.DataIntegrityViolationException
        | org.jooq.exception.DataAccessException e) {
      throw conflict("认证编号 " + code + " 在本租户已存在，或该认证类型不在受控词表内");
    }
    return certification(id.toString());
  }

  /** 修改一张认证（行级乐观锁）。 */
  public CertificationDetail updateCertification(String id, ProductCertificationInput input) {
    UUID tenant = currentTenant();
    UUID certificationId = uuid(id, "认证 id");
    requireType(normalizeTypeCode(input.certificationTypeCode()));
    if (input.validUntil() == null || input.validFrom() == null) {
      throw unprocessable("有效期起止都必须给");
    }
    if (!input.validUntil().isAfter(input.validFrom())) {
      throw unprocessable("有效期截止必须晚于生效时间");
    }
    long version = input.version() == null ? -1L : input.version();
    int updated =
        sql.execute(
            """
            update product_certification
               set certification_type_code = ?,
                   title = ?,
                   cert_no = ?,
                   issuer = ?,
                   issuing_country = ?,
                   valid_from = ?::timestamptz,
                   valid_until = ?::timestamptz,
                   note = ?,
                   version = version + 1,
                   updated_at = now()
             where tenant_id = ? and id = ? and version = ?
            """,
            normalizeTypeCode(input.certificationTypeCode()),
            text(input.title()),
            text(input.certNo()),
            text(input.issuer()),
            text(input.issuingCountry()).toUpperCase(),
            input.validFrom(),
            input.validUntil(),
            text(input.note()),
            tenant,
            certificationId,
            version);
    if (updated == 0) {
      Record current =
          sql.fetchOne(
              "select version from product_certification where tenant_id = ? and id = ?",
              tenant,
              certificationId);
      if (current == null) {
        throw notFound();
      }
      throw conflict("这条认证已被别人改过（乐观锁版本不符），请重新读取后再提交");
    }
    return certification(certificationId.toString());
  }

  /** 撤销一张认证（必须写明理由；撤销与过期是两件事）。 */
  public CertificationDetail revokeCertification(String id, CertificationRevokeInput input) {
    UUID tenant = currentTenant();
    UUID certificationId = uuid(id, "认证 id");
    String reason = text(input.reason());
    if (reason.isEmpty()) {
      throw unprocessable("撤销必须写明理由");
    }
    int updated =
        sql.execute(
            """
            update product_certification
               set status = 'REVOKED',
                   revoked_at = now(),
                   revoked_reason = ?,
                   version = version + 1,
                   updated_at = now()
             where tenant_id = ? and id = ? and status = 'ACTIVE'
            """,
            reason,
            tenant,
            certificationId);
    if (updated == 0) {
      Record current =
          sql.fetchOne(
              "select status from product_certification where tenant_id = ? and id = ?",
              tenant,
              certificationId);
      if (current == null) {
        throw notFound();
      }
      throw conflict("这张认证已经是撤销态，不能重复撤销");
    }
    return certification(certificationId.toString());
  }

  /** 认证类型必须在生效词表里（服务层第一道闸，DB 触发器是第二道）。 */
  private void requireType(String typeCode) {
    if (!typeEnabled(typeCode)) {
      throw unprocessable(
          "认证类型 " + typeCode + " 不在生效词表内（内置词表与本租户字典都没有启用它）");
    }
  }

  // ==================================================================
  // 适用 SKU
  // ==================================================================

  private List<CertificationSkuBinding> skus(UUID tenant, UUID certificationId) {
    List<Record> rows =
        sql.fetch(
            """
            select id, sku_id, sku_code_snapshot, sku_name_snapshot, note, created_at
              from product_certification_sku
             where tenant_id = ? and certification_id = ?
             order by sku_code_snapshot, id
            """,
            tenant,
            certificationId);
    List<CertificationSkuBinding> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(
          new CertificationSkuBinding(
              row.get("id", UUID.class).toString(),
              row.get("sku_id", UUID.class).toString(),
              row.get("sku_code_snapshot", String.class),
              row.get("sku_name_snapshot", String.class),
              row.get("note", String.class),
              row.get("created_at", OffsetDateTime.class)));
    }
    return items;
  }

  /** 给认证挂一个适用 SKU。 */
  public CertificationSkuBinding attachSku(
      String certificationId, CertificationSkuInput input, String bearer) {
    UUID tenant = currentTenant();
    UUID id = uuid(certificationId, "认证 id");
    UUID skuId = uuid(input.skuId(), "SKU id");
    requireCertification(tenant, id);
    String code = text(input.skuCode());
    String name = text(input.skuName());
    if (code.isEmpty() || name.isEmpty()) {
      // 快照缺了就现查 catalog —— 名称快照是「当时交的是什么」的凭据，不能空着。
      ProductCertificationSourcePort.SkuRow upstream = source.sku(skuId.toString(), bearer);
      if (upstream == null) {
        throw unprocessable("读不到 catalog 的 SKU " + skuId + "，请确认该 SKU 存在后再挂");
      }
      code = text(upstream.code());
      name = text(upstream.name());
    }
    UUID rowId = UUID.randomUUID();
    sql.execute(
        """
        insert into product_certification_sku
          (id,tenant_id,certification_id,sku_id,sku_code_snapshot,sku_name_snapshot,note,created_at)
        values (?,?,?,?,?,?,?,now())
        on conflict (tenant_id,certification_id,sku_id) do update
           set sku_code_snapshot = excluded.sku_code_snapshot,
               sku_name_snapshot = excluded.sku_name_snapshot,
               note = excluded.note
        """,
        rowId,
        tenant,
        id,
        skuId,
        code,
        name,
        text(input.note()));
    return new CertificationSkuBinding(
        rowId.toString(), skuId.toString(), code, name, text(input.note()), OffsetDateTime.now());
  }

  /** 解挂一个适用 SKU。 */
  public void detachSku(String certificationId, String skuId) {
    UUID tenant = currentTenant();
    UUID id = uuid(certificationId, "认证 id");
    UUID sku = uuid(skuId, "SKU id");
    int deleted =
        sql.execute(
            "delete from product_certification_sku where tenant_id = ? and certification_id = ? and sku_id = ?",
            tenant,
            id,
            sku);
    if (deleted == 0) {
      throw notFound();
    }
  }

  // ==================================================================
  // 对应类目要求
  // ==================================================================

  private List<CertificationCategoryBinding> categories(UUID tenant, UUID certificationId) {
    List<Record> rows =
        sql.fetch(
            """
            select id, category_id, category_code_snapshot, category_name_snapshot,
                   requirement_note, created_at
              from product_certification_category
             where tenant_id = ? and certification_id = ?
             order by category_code_snapshot, id
            """,
            tenant,
            certificationId);
    List<CertificationCategoryBinding> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(
          new CertificationCategoryBinding(
              row.get("id", UUID.class).toString(),
              row.get("category_id", UUID.class).toString(),
              row.get("category_code_snapshot", String.class),
              row.get("category_name_snapshot", String.class),
              row.get("requirement_note", String.class),
              row.get("created_at", OffsetDateTime.class)));
    }
    return items;
  }

  /** 给认证挂一个对应类目要求。 */
  public CertificationCategoryBinding attachCategory(
      String certificationId, CertificationCategoryInput input) {
    UUID tenant = currentTenant();
    UUID id = uuid(certificationId, "认证 id");
    UUID categoryId = uuid(input.categoryId(), "类目 id");
    requireCertification(tenant, id);
    String code = text(input.categoryCode());
    String name = text(input.categoryName());
    if (code.isEmpty() || name.isEmpty()) {
      throw unprocessable(
          "类目编码与名称不能为空（类目主数据在 catalog，请从候选清单里选一个再挂）");
    }
    UUID rowId = UUID.randomUUID();
    sql.execute(
        """
        insert into product_certification_category
          (id,tenant_id,certification_id,category_id,category_code_snapshot,category_name_snapshot,
           requirement_note,created_at)
        values (?,?,?,?,?,?,?,now())
        on conflict (tenant_id,certification_id,category_id) do update
           set category_code_snapshot = excluded.category_code_snapshot,
               category_name_snapshot = excluded.category_name_snapshot,
               requirement_note = excluded.requirement_note
        """,
        rowId,
        tenant,
        id,
        categoryId,
        code,
        name,
        text(input.requirementNote()));
    return new CertificationCategoryBinding(
        rowId.toString(),
        categoryId.toString(),
        code,
        name,
        text(input.requirementNote()),
        OffsetDateTime.now());
  }

  /** 解挂一个对应类目要求。 */
  public void detachCategory(String certificationId, String categoryId) {
    UUID tenant = currentTenant();
    UUID id = uuid(certificationId, "认证 id");
    UUID category = uuid(categoryId, "类目 id");
    int deleted =
        sql.execute(
            "delete from product_certification_category where tenant_id = ? and certification_id = ? and category_id = ?",
            tenant,
            id,
            category);
    if (deleted == 0) {
      throw notFound();
    }
  }

  private void requireCertification(UUID tenant, UUID certificationId) {
    Object exists =
        sql.fetchValue(
            "select 1 from product_certification where tenant_id = ? and id = ?",
            tenant,
            certificationId);
    if (exists == null) {
      throw notFound();
    }
  }

  // ==================================================================
  // 扫描件
  // ==================================================================

  private List<CertificationDocumentRow> documents(UUID tenant, UUID certificationId) {
    List<Record> rows =
        sql.fetch(
            """
            select id, certification_id, document_kind, document_name, mime_type, size_bytes,
                   document_key, uploaded_by, uploaded_at, note, created_at
              from product_certification_document
             where tenant_id = ? and certification_id = ?
             order by uploaded_at desc, id
            """,
            tenant,
            certificationId);
    List<CertificationDocumentRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(documentRow(row));
    }
    return items;
  }

  private CertificationDocumentRow documentRow(Record row) {
    return new CertificationDocumentRow(
        row.get("id", UUID.class).toString(),
        row.get("certification_id", UUID.class).toString(),
        row.get("document_kind", String.class),
        row.get("document_name", String.class),
        row.get("mime_type", String.class),
        row.get("size_bytes", BigDecimal.class),
        row.get("document_key", String.class),
        row.get("uploaded_by", UUID.class) == null
            ? null
            : row.get("uploaded_by", UUID.class).toString(),
        row.get("uploaded_at", OffsetDateTime.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  /**
   * 上传一份扫描件。
   *
   * <p>🔴 文件本体**真写进受管存储**（不是只存一个文件名），元数据入库，文件行只追加。
   */
  public CertificationDocumentRow uploadDocument(
      String certificationId,
      byte[] content,
      String fileName,
      String mimeType,
      String kind,
      String note,
      UUID userId) {
    UUID tenant = currentTenant();
    UUID id = uuid(certificationId, "认证 id");
    requireCertification(tenant, id);
    String documentKind = kind == null ? "" : kind.trim().toUpperCase();
    if (!DOCUMENT_KINDS.contains(documentKind)) {
      throw unprocessable("扫描件类别只能是 ORIGINAL（原件）/ RENEWAL（续期件）/ ATTACHMENT（补充材料）");
    }
    if (content == null || content.length == 0) {
      throw unprocessable("扫描件内容为空");
    }
    if (content.length > MAX_DOCUMENT_BYTES) {
      throw unprocessable("扫描件超过单份上限 " + (MAX_DOCUMENT_BYTES / 1024 / 1024) + " MB");
    }
    String name = text(fileName);
    if (name.isEmpty()) {
      name = "document";
    }
    UUID documentId = UUID.randomUUID();
    String key = storageKey(tenant, id, documentId, name);
    try {
      storage.put(key, content);
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "扫描件写入受管存储失败：" + e.getMessage());
    }
    OffsetDateTime uploadedAt = OffsetDateTime.now();
    sql.execute(
        """
        insert into product_certification_document
          (id,tenant_id,certification_id,document_kind,document_key,document_name,mime_type,
           size_bytes,uploaded_by,uploaded_at,note,created_at)
        values (?,?,?,?,?,?,?,?,?,?::timestamptz,?,now())
        """,
        documentId,
        tenant,
        id,
        documentKind,
        key,
        name,
        text(mimeType),
        new BigDecimal(content.length),
        userId,
        uploadedAt,
        text(note));
    return new CertificationDocumentRow(
        documentId.toString(),
        id.toString(),
        documentKind,
        name,
        text(mimeType),
        new BigDecimal(content.length),
        key,
        userId == null ? null : userId.toString(),
        uploadedAt,
        text(note),
        uploadedAt);
  }

  /** 上传扫描件（JSON 形状：base64 本体）。 */
  public CertificationDocumentRow uploadDocument(
      String certificationId, CertificationDocumentInput input, UUID userId) {
    byte[] content;
    try {
      content = Base64.getDecoder().decode(input.contentBase64().trim());
    } catch (IllegalArgumentException e) {
      throw unprocessable("扫描件内容不是合法的 base64");
    }
    return uploadDocument(
        certificationId,
        content,
        input.fileName(),
        input.contentType(),
        input.documentKind(),
        input.note(),
        userId);
  }

  /** 读回一份扫描件的元数据（跨租户与不存在都返回逐字相同的 404）。 */
  public CertificationDocumentRow document(String documentId) {
    UUID tenant = currentTenant();
    UUID id = uuid(documentId, "扫描件 id");
    Record row =
        sql.fetchOne(
            """
            select id, certification_id, document_kind, document_name, mime_type, size_bytes,
                   document_key, uploaded_by, uploaded_at, note, created_at
              from product_certification_document
             where tenant_id = ? and id = ?
            """,
            tenant,
            id);
    if (row == null) {
      throw notFound();
    }
    return documentRow(row);
  }

  /** 读回扫描件本体（真的从受管存储里读，不是造一份假内容）。 */
  public byte[] documentContent(String documentId) {
    CertificationDocumentRow row = document(documentId);
    try {
      return storage.read(row.storageKey());
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "扫描件已不在受管存储中：" + e.getMessage());
    }
  }

  /** 存储键：租户 / 认证 / 文档 id + 净化后的扩展名（**不接受调用方原样透传的文件名**）。 */
  private static String storageKey(UUID tenant, UUID certificationId, UUID documentId, String fileName) {
    String extension = "";
    int dot = fileName.lastIndexOf('.');
    if (dot >= 0 && dot < fileName.length() - 1) {
      String candidate = fileName.substring(dot + 1).toLowerCase();
      if (candidate.matches("[a-z0-9]{1,10}")) {
        extension = "." + candidate;
      }
    }
    return "certifications/" + tenant + "/" + certificationId + "/" + documentId + extension;
  }

  // ==================================================================
  // 要求侧登记
  // ==================================================================

  /** 要求侧登记清单（真分页）。 */
  public RequirementPage requirements(
      String skuId, String typeCode, String categoryId, String keyword, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    int offset = Math.max(0, page) * limit;

    StringBuilder where = new StringBuilder(" where r.tenant_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    if (text(skuId).length() > 0) {
      where.append(" and r.sku_id = ?");
      binds.add(uuid(skuId, "SKU id"));
    }
    if (text(typeCode).length() > 0) {
      where.append(" and r.certification_type_code = ?");
      binds.add(normalizeTypeCode(typeCode));
    }
    if (text(categoryId).length() > 0) {
      where.append(" and r.category_id = ?");
      binds.add(uuid(categoryId, "类目 id"));
    }
    if (text(keyword).length() > 0) {
      where.append(" and (r.sku_code_snapshot ilike ? or r.sku_name_snapshot ilike ?)");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
    }

    long total =
        ((Number)
                sql.fetchValue(
                    "select count(*) from product_certification_requirement r" + where,
                    binds.toArray()))
            .longValue();

    List<Record> rows =
        sql.fetch(
            """
            select r.id, r.sku_id, r.sku_code_snapshot, r.sku_name_snapshot,
                   r.certification_type_code, coalesce(v.name, r.certification_type_code) as certification_type_name,
                   r.category_id, r.category_code_snapshot, r.category_name_snapshot,
                   r.note, r.version, r.created_at, r.updated_at
              from product_certification_requirement r
              left join product_certification_type_view v on v.code = r.certification_type_code
            %s
             order by r.sku_code_snapshot, r.certification_type_code, r.id
             limit ? offset ?
            """
                .formatted(where),
            concat(binds, limit, offset));

    List<RequirementRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(
          new RequirementRow(
              row.get("id", UUID.class).toString(),
              row.get("sku_id", UUID.class).toString(),
              row.get("sku_code_snapshot", String.class),
              row.get("sku_name_snapshot", String.class),
              row.get("certification_type_code", String.class),
              row.get("certification_type_name", String.class),
              row.get("category_id", UUID.class) == null
                  ? null
                  : row.get("category_id", UUID.class).toString(),
              row.get("category_code_snapshot", String.class),
              row.get("category_name_snapshot", String.class),
              row.get("note", String.class),
              ((Number) row.get("version", Long.class)).longValue(),
              row.get("created_at", OffsetDateTime.class),
              row.get("updated_at", OffsetDateTime.class)));
    }
    return new RequirementPage(items, total, Math.max(0, page), limit);
  }

  /** 登记一条 SKU 的认证要求。 */
  public RequirementRow createRequirement(RequirementInput input, String bearer) {
    UUID tenant = currentTenant();
    UUID skuId = uuid(input.skuId(), "SKU id");
    String typeCode = normalizeTypeCode(input.certificationTypeCode());
    requireType(typeCode);
    String code = text(input.skuCode());
    String name = text(input.skuName());
    if (code.isEmpty() || name.isEmpty()) {
      ProductCertificationSourcePort.SkuRow upstream = source.sku(skuId.toString(), bearer);
      if (upstream == null) {
        throw unprocessable("读不到 catalog 的 SKU " + skuId + "，请确认该 SKU 存在后再登记要求");
      }
      code = text(upstream.code());
      name = text(upstream.name());
    }
    UUID categoryId =
        text(input.categoryId()).isEmpty() ? null : uuid(input.categoryId(), "类目 id");
    UUID id = UUID.randomUUID();
    try {
      sql.execute(
          """
          insert into product_certification_requirement
            (id,tenant_id,sku_id,sku_code_snapshot,sku_name_snapshot,certification_type_code,
             category_id,category_code_snapshot,category_name_snapshot,note,version,created_at,updated_at)
          values (?,?,?,?,?,?,?,?,?,?,0,now(),now())
          """,
          id,
          tenant,
          skuId,
          code,
          name,
          typeCode,
          categoryId,
          text(input.categoryCode()),
          text(input.categoryName()),
          text(input.note()));
    } catch (org.springframework.dao.DataIntegrityViolationException
        | org.jooq.exception.DataAccessException e) {
      throw conflict("该 SKU 的这项认证要求已经登记过了");
    }
    return new RequirementRow(
        id.toString(),
        skuId.toString(),
        code,
        name,
        typeCode,
        typeName(typeCode),
        categoryId == null ? null : categoryId.toString(),
        text(input.categoryCode()),
        text(input.categoryName()),
        text(input.note()),
        0L,
        OffsetDateTime.now(),
        OffsetDateTime.now());
  }

  /** 撤销一条要求登记。 */
  public void deleteRequirement(String requirementId) {
    UUID tenant = currentTenant();
    UUID id = uuid(requirementId, "要求 id");
    int deleted =
        sql.execute(
            "delete from product_certification_requirement where tenant_id = ? and id = ?",
            tenant,
        id);
    if (deleted == 0) {
      throw notFound();
    }
  }

  private String typeName(String typeCode) {
    Object value =
        sql.fetchValue(
            "select name from product_certification_type_view where code = ?", typeCode);
    return value == null ? typeCode : String.valueOf(value);
  }

  // ==================================================================
  // 覆盖情况统计（蓝图点名的输出之一）
  // ==================================================================

  /** 要求侧的 CTE：每条 (sku, type) 要求配上「是否已被当前有效的认证覆盖」。 */
  private static final String REQUIREMENT_CTE =
      """
      with req as (
        select r.sku_id, r.certification_type_code, r.category_id,
               r.sku_code_snapshot, r.sku_name_snapshot,
               r.category_code_snapshot, r.category_name_snapshot,
               (exists (
                  select 1
                    from product_certification c
                    join product_certification_sku s
                      on s.tenant_id = c.tenant_id and s.certification_id = c.id
                   where c.tenant_id = r.tenant_id
                     and c.certification_type_code = r.certification_type_code
                     and c.status = 'ACTIVE'
                     and c.valid_from <= now()
                     and c.valid_until > now()
                     and s.sku_id = r.sku_id
               )) as covered
          from product_certification_requirement r
         where r.tenant_id = ?
      )
      """;

  /** 覆盖情况统计（按 SKU / 按类目 / 按认证类型三个口径）。 */
  public CoverageReport coverage() {
    UUID tenant = currentTenant();

    long required =
        ((Number)
                sql.fetchValue(
                    "select count(*) from product_certification_requirement where tenant_id = ?",
                    tenant))
            .longValue();
    long missingRequirement =
        ((Number)
                sql.fetchValue(
                    REQUIREMENT_CTE + " select count(*) from req where not covered", tenant))
            .longValue();
    long coveredRequirement = required - missingRequirement;

    long requiredSku =
        ((Number)
                sql.fetchValue(
                    REQUIREMENT_CTE + " select count(distinct sku_id) from req", tenant))
            .longValue();
    long missingSku =
        ((Number)
                sql.fetchValue(
                    REQUIREMENT_CTE + " select count(*) from (select sku_id from req group by sku_id having count(*) filter (where not covered) > 0) t",
                    tenant))
            .longValue();
    long coveredSku = requiredSku - missingSku;

    // ---- 按认证类型 ----
    List<Record> typeRows =
        sql.fetch(
            REQUIREMENT_CTE
                + """
                select certification_type_code,
                       count(*) as required_count,
                       count(*) filter (where covered) as covered_count,
                       count(*) filter (where not covered) as missing_count
                  from req
                 group by certification_type_code
                 order by certification_type_code
                """,
            tenant);
    List<CoverageByType> byType = new ArrayList<>();
    for (Record row : typeRows) {
      String typeCode = row.get("certification_type_code", String.class);
      long missing = longOf(row, "missing_count");
      byType.add(
          new CoverageByType(
              typeCode,
              typeName(typeCode),
              longOf(row, "required_count"),
              longOf(row, "covered_count"),
              missing,
              ((Number)
                      sql.fetchValue(
                          REQUIREMENT_CTE
                              + " select count(*) from (select sku_id from req where certification_type_code = ? group by sku_id having count(*) filter (where not covered) > 0) t",
                          tenant,
                          typeCode))
                  .longValue()));
    }

    // ---- 按类目 ----
    List<Record> categoryRows =
        sql.fetch(
            REQUIREMENT_CTE
                + """
                select category_id, category_code_snapshot, category_name_snapshot,
                       count(*) as required_count,
                       count(*) filter (where covered) as covered_count,
                       count(*) filter (where not covered) as missing_count
                  from req
                 group by category_id, category_code_snapshot, category_name_snapshot
                 order by category_code_snapshot, category_id
                """,
            tenant);
    List<CoverageByCategory> byCategory = new ArrayList<>();
    for (Record row : categoryRows) {
      UUID categoryId = row.get("category_id", UUID.class);
      List<String> missingSkus = new ArrayList<>();
      if (categoryId != null) {
        for (Record sku :
            sql.fetch(
                REQUIREMENT_CTE
                    + """
                    select distinct sku_id
                      from req
                     where category_id = ? and not covered
                     order by sku_id
                     limit 200
                    """,
                tenant,
                categoryId)) {
          missingSkus.add(sku.get("sku_id", UUID.class).toString());
        }
      }
      byCategory.add(
          new CoverageByCategory(
              categoryId == null ? null : categoryId.toString(),
              row.get("category_code_snapshot", String.class),
              row.get("category_name_snapshot", String.class),
              longOf(row, "required_count"),
              longOf(row, "covered_count"),
              longOf(row, "missing_count"),
              categoryId == null
                  ? 0L
                  : ((Number)
                          sql.fetchValue(
                              REQUIREMENT_CTE
                                  + " select count(*) from (select sku_id from req where category_id = ? and not covered group by sku_id) t",
                              tenant,
                              categoryId))
                      .longValue(),
              missingSkus));
    }

    // ---- 缺认证的 SKU 明细 ----
    List<Record> missingSkuRows =
        sql.fetch(
            REQUIREMENT_CTE
                + """
                select sku_id,
                       min(sku_code_snapshot) as sku_code,
                       min(sku_name_snapshot) as sku_name,
                       array_agg(certification_type_code order by certification_type_code)
                         filter (where not covered) as missing_types
                  from req
                 group by sku_id
                having count(*) filter (where not covered) > 0
                 order by sku_code, sku_id
                 limit 200
                """,
            tenant);
    List<CoverageMissingSku> missing = new ArrayList<>();
    for (Record row : missingSkuRows) {
      Object types = row.get("missing_types");
      List<String> typeList = new ArrayList<>();
      if (types instanceof String[] array) {
        for (String value : array) {
          typeList.add(value);
        }
      }
      missing.add(
          new CoverageMissingSku(
              row.get("sku_id", UUID.class).toString(),
              row.get("sku_code", String.class),
              row.get("sku_name", String.class),
              typeList));
    }

    return new CoverageReport(
        required,
        coveredRequirement,
        missingRequirement,
        requiredSku,
        coveredSku,
        missingSku,
        byType,
        byCategory,
        missing);
  }

  /** 单个 SKU 的覆盖详情。 */
  public CoverageSkuDetail coverageForSku(String skuId) {
    UUID tenant = currentTenant();
    UUID sku = uuid(skuId, "SKU id");
    List<Record> rows =
        sql.fetch(
            REQUIREMENT_CTE
                + """
                select certification_type_code, category_id, category_name_snapshot,
                       category_code_snapshot
                  from req
                 where sku_id = ?
                 order by certification_type_code
                """,
            tenant,
            sku);
    List<CoverageRequirement> requirements = new ArrayList<>();
    List<String> covered = new ArrayList<>();
    List<String> missingTypes = new ArrayList<>();
    List<String> certificationIds = new ArrayList<>();
    for (Record row : rows) {
      String typeCode = row.get("certification_type_code", String.class);
      Record certification =
          sql.fetchOne(
              """
              select c.id, c.code, c.status, c.valid_from, c.valid_until
                from product_certification c
                join product_certification_sku s
                  on s.tenant_id = c.tenant_id and s.certification_id = c.id
               where c.tenant_id = ? and c.certification_type_code = ? and s.sku_id = ?
               order by c.valid_until desc
               limit 1
              """,
              tenant,
              typeCode,
              sku);
      boolean isCovered = false;
      String certificationId = null;
      String certificationCode = null;
      String expiryState = null;
      if (certification != null) {
        OffsetDateTime now = OffsetDateTime.now();
        expiryState =
            classifyExpiry(
                certification.get("status", String.class),
                certification.get("valid_from", OffsetDateTime.class),
                certification.get("valid_until", OffsetDateTime.class),
                now,
                warningDays(tenant));
        isCovered = "VALID".equals(expiryState);
        certificationId = certification.get("id", UUID.class).toString();
        certificationCode = certification.get("code", String.class);
        if (isCovered && !certificationIds.contains(certificationId)) {
          certificationIds.add(certificationId);
        }
      }
      if (isCovered) {
        covered.add(typeCode);
      } else {
        missingTypes.add(typeCode);
      }
      UUID categoryId = row.get("category_id", UUID.class);
      requirements.add(
          new CoverageRequirement(
              typeCode,
              typeName(typeCode),
              isCovered,
              certificationId,
              certificationCode,
              expiryState,
              categoryId == null ? null : categoryId.toString(),
              row.get("category_name_snapshot", String.class)));
    }
    Record skuRow =
        sql.fetchOne(
            "select sku_code_snapshot, sku_name_snapshot from product_certification_requirement"
                + " where tenant_id = ? and sku_id = ? limit 1",
            tenant,
            sku);
    return new CoverageSkuDetail(
        sku.toString(),
        skuRow == null ? "" : skuRow.get("sku_code_snapshot", String.class),
        skuRow == null ? "" : skuRow.get("sku_name_snapshot", String.class),
        requirements,
        covered,
        missingTypes,
        certificationIds);
  }

  // ==================================================================
  // 到期提醒
  // ==================================================================

  /** 到期提醒（临近到期 / 已过期，按**剩余天数**升序：剩得少的排前面）。 */
  public ExpiryReminderPage expiryReminders(int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    int offset = Math.max(0, page) * limit;
    int days = warningDays(tenant);

    long total =
        ((Number)
                sql.fetchValue(
                    """
                    select count(*)
                      from product_certification c
                     where c.tenant_id = ?
                       and c.status = 'ACTIVE'
                       and c.valid_until <= now() + make_interval(days => %d)
                    """
                        .formatted(days),
                    tenant))
            .longValue();

    List<Record> rows =
        sql.fetch(
            """
            select c.id, c.code, c.certification_type_code,
                   coalesce(v.name, c.certification_type_code) as certification_type_name,
                   c.title, c.cert_no, c.valid_until,
                   (%s) as expiry_state,
                   (%s) as days_remaining,
                   (select count(*) from product_certification_sku s
                     where s.tenant_id = c.tenant_id and s.certification_id = c.id) as sku_count,
                   (select count(*) from product_certification_document d
                     where d.tenant_id = c.tenant_id and d.certification_id = c.id) as document_count
              from product_certification c
              left join product_certification_type_view v on v.code = c.certification_type_code
             where c.tenant_id = ?
               and c.status = 'ACTIVE'
               and c.valid_until <= now() + make_interval(days => %d)
             order by c.valid_until asc, c.id
             limit ? offset ?
            """
                .formatted(expiryCase(days), DAYS_REMAINING, days),
            tenant,
            limit,
            offset);

    List<ExpiryReminderRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(
          new ExpiryReminderRow(
              row.get("id", UUID.class).toString(),
              row.get("code", String.class),
              row.get("certification_type_code", String.class),
              row.get("certification_type_name", String.class),
              row.get("title", String.class),
              row.get("cert_no", String.class),
              row.get("expiry_state", String.class),
              row.get("valid_until", OffsetDateTime.class),
              ((Number) row.get("days_remaining", Integer.class)).intValue(),
              days,
              ((Number) row.get("sku_count", Long.class)).intValue(),
              ((Number) row.get("document_count", Long.class)).intValue()));
    }
    return new ExpiryReminderPage(items, total, Math.max(0, page), limit, days);
  }

  // ==================================================================
  // 上游候选（只读；读不到就显式报读不到）
  // ==================================================================

  /** catalog 的 SKU 候选。🔴 catalog 不可达 ⇒ 503（**绝不**折成空列表）。 */
  public CandidatePage<ProductCertificationSkuCandidate> skuCandidates(
      String keyword, int page, int size, String bearer) {
    int limit = Math.max(1, Math.min(size, 100));
    int current = Math.max(0, page);
    ProductCertificationSourcePort.SkuCandidates result =
        source.skus(keyword, null, current, limit, bearer);
    if (!result.available()) {
      throw unavailable("读不到 catalog 的 SKU 清单：" + result.note());
    }
    List<ProductCertificationSkuCandidate> items = new ArrayList<>();
    for (ProductCertificationSourcePort.SkuRow row : result.items()) {
      items.add(
          new ProductCertificationSkuCandidate(
              row.id(), row.code(), row.name(), row.category(), row.spuId(), "catalog"));
    }
    return new CandidatePage<>(items, result.total(), current, limit, "catalog");
  }

  /** catalog 的类目候选（树 + 节点）。🔴 catalog 不可达 ⇒ 503。 */
  public CandidatePage<CategoryCandidate> categoryCandidates(String treeId, String bearer) {
    ProductCertificationSourcePort.CategoryTrees trees = source.categoryTrees(bearer);
    if (!trees.available()) {
      throw unavailable("读不到 catalog 的类目树：" + trees.note());
    }
    List<CategoryCandidate> items = new ArrayList<>();
    for (ProductCertificationSourcePort.CategoryTreeRow tree : trees.items()) {
      if (!"INTERNAL".equals(tree.treeKind())) {
        // 平台类目树是 4.2.3.1 平台类目映射的领地；本模块的「对应类目要求」指内部类目。
        continue;
      }
      if (text(treeId).length() > 0 && !treeId.trim().equals(tree.id())) {
        continue;
      }
      ProductCertificationSourcePort.CategoryNodes nodes = source.categoryNodes(tree.id(), bearer);
      if (!nodes.available()) {
        throw unavailable("读不到 catalog 的类目节点：" + nodes.note());
      }
      for (ProductCertificationSourcePort.CategoryNodeRow node : nodes.items()) {
        items.add(
            new CategoryCandidate(
                node.id(),
                node.code(),
                node.name(),
                node.path(),
                tree.id(),
                node.treeKind(),
                "catalog"));
      }
    }
    return new CandidatePage<>(items, items.size(), 0, items.size(), "catalog");
  }

  // ==================================================================
  // 内部
  // ==================================================================

  /** 把 binds 与尾巴参数拼成一个数组（jOOQ 的可变参数调用）。 */
  private static Object[] concat(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    int index = 0;
    for (Object bind : binds) {
      all[index++] = bind;
    }
    for (Object value : tail) {
      all[index++] = value;
    }
    return all;
  }
}
