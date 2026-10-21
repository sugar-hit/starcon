package com.starcon.channel;

import static com.starcon.channel.BrandAuthorizationModels.*;
import static com.starcon.channel.BrandAuthorizationRules.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.4.2.1 品牌授权。
 *
 * <p>蓝图：「管理品牌授权：授权方、授权范围（店铺/国家/类目/期限）、授权文件、被授权主体，
 * 校验刊登时的品牌使用是否在授权范围内。输入：授权录入；文件上传；有效期。输出：授权档案 + 校验结果。
 * 关系：上游 = 6.1.2.3 供应商资质；下游 = 3.3.2 品牌状态、4.2.3.2 预检。
 * <b>授权过期是「平时没事、上新时出事」的典型</b>，需到期提醒与刊登阻断。」
 *
 * <p>🔴🔴 本类里有五处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>期限是区间</b>，不是「到期日」单值；过期判定用<b>同源 DB 时钟</b>
 *       （{@code select now()} 取一次，同一事务内 Java 与 SQL 共用这一个时刻），
 *       <b>不</b>用 {@code LocalDate.now()} —— 应用机与数据库机差半天，
 *       「授权还剩 0 天」就变成了差半天。
 *   <li><b>校验结论必有原因</b>：每一次「不覆盖」都落到一条 {@link BrandAuthorizationReason} 上，
 *       DB 层还有 CHECK 兜着（{@code conclusion <> 'IN_SCOPE'} ⇒ {@code reasons} 非空）。
 *   <li><b>「判不了」不等于「合格」</b>：上下文缺维度时结论是 {@code UNKNOWN}，
 *       发布闸门<b>不放行</b>（{@code allowed} 只在 {@code IN_SCOPE} 时为真）。
 *   <li><b>只写本模块的三张档案表 + 一张判定表</b>；catalog 的品牌档案与 trade 的供应商资质
 *       <b>只读</b>（{@link BrandAuthorizationSourcePort}），不建它们的影子表。
 *   <li><b>判定是追加式凭据</b>：V248 的触发器钉住结论不可改写，
 *       规则版本变化只产生新行，<b>不追溯改变历史判定</b>。
 * </ol>
 */
@Service
public class BrandAuthorizationService {

  private static final String VERDICT_SEMANTICS =
      "结论五态：IN_SCOPE = 有至少一条授权在期限与范围上都覆盖这次刊登；"
          + "OUT_OF_SCOPE = 有授权但都不覆盖（逐条列出哪条授权不覆盖哪个维度）；"
          + "EXPIRED = 有授权但全部已过期；NO_AUTHORIZATION = 本租户下该品牌没有任何授权档案；"
          + "UNKNOWN = 授权存在但上下文缺维度，判不了。"
          + "🔴 只有 IN_SCOPE 才允许刊登 —— 「无法证明在授权范围内」不等于「已在授权范围内」。";

  private static final String UNKNOWN_SEMANTICS =
      "🔴「判不了」是一个独立状态，不是「合格」也不是「不合格」："
          + "上下文没指定店铺 / 国家 / 类目（或店铺没有可解析的国家）时，结论是 UNKNOWN + severity=REVIEW，"
          + "发布闸门仍然**不放行**。把 UNKNOWN 当成放行，等于把「没查」写成「合规」。";

  private static final String BLOCKING_SEMANTICS =
      "🔴 阻断在**服务端**强制：publish-gate 端点在服务端真算一遍授权范围覆盖，"
          + "回 allowed=false + 逐条原因。页面上的红字只是显示，不是防线 —— "
          + "4.2.3.2 预检与 4.3.1.1 一键刊登将来消费的是这个服务端结论。";

  private static final String EXPIRY_SEMANTICS =
      "到期口径：授权期限是**区间**（effective_from / effective_to），"
          + "判定时刻一律取**同源 DB 时钟**（select now()），剩余天数由 DB 按 effective_to - now() 算；"
          + "临期窗口默认 30 天（可按请求调整）。到期状态五态："
          + "ACTIVE 生效中 / EXPIRING 即将到期 / EXPIRED 已过期 / NOT_YET_EFFECTIVE 尚未生效 / DISABLED 已停用。";

  private static final String BOUNDARY_NOTE =
      "🔴 与 catalog 3.2.1.2「品牌授权」**不是同一件事**：catalog 那套是品牌档案自带的授权凭证"
          + "（证书号 / 授权方 / 有效期），回答「这个品牌有没有被授权过」；"
          + "本模块是**合规口径的授权档案**（授权方 / 授权范围（店铺·国家·类目·期限）/ 授权文件 / 被授权主体），"
          + "回答「这一段范围、这一段时间、谁可以用这个品牌」，并给出**刊登时的范围校验结论**。"
          + "catalog 侧的一切都只经授权只读端口读，本模块**不建它的影子表**。";

  private static final String UPSTREAM_NOTE =
      "上游 = 6.1.2.3 供应商资质（**trade 服务** /trade/supplier-qualifications/**，只读）"
          + "+ 03 品牌（catalog /catalog/brands，只读）"
          + "+ 本服务的 2.1.1.1 店铺 / 2.1.1.3 站点与国家（授权范围里的店铺与国家两维从这里还原）。";

  private static final String DOWNSTREAM_NOTE =
      "下游 = 3.3.2 品牌状态（catalog，只读不改）与 4.2.3.2 预检（消费本模块的 publish-gate 结论）。"
          + "⚠️ 4.3.1.1 一键刊登未交付 ⇒ 本模块交付**服务端算出来的阻断结论**，不代它执行刊登动作。";

  private final DSLContext sql;
  private final ObjectMapper json;
  private final BrandAuthorizationSourcePort source;

  public BrandAuthorizationService(
      DSLContext sql, ObjectMapper json, BrandAuthorizationSourcePort source) {
    this.sql = sql;
    this.json = json;
    this.source = source;
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

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(String.valueOf(raw));
  }

  /**
   * 🔴 <b>同源 DB 时钟</b>：判定时刻一律从这里取。
   *
   * <p>同一个事务里 Java 侧的比较与 SQL 侧的比较共用这一个时刻 ——
   * 应用机与数据库机差半天的话，「授权还剩 0 天」就变成了差半天。
   */
  private OffsetDateTime dbNow() {
    Object raw = sql.fetchValue("select now()");
    return raw instanceof OffsetDateTime value ? value : OffsetDateTime.now(ZoneOffset.UTC);
  }

  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("内容序列化失败", e);
    }
  }

  private String jsonText(Object raw) {
    if (raw == null) {
      return "{}";
    }
    String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
    return value.isBlank() ? "{}" : value;
  }

  /** 解析 uuid 字符串；非法一律当「不存在」。 */
  private static UUID parseUuid(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * 🔴 解析时间戳入参。
   *
   * <p>接受带偏移量的 ISO 串（{@code 2026-01-01T00:00:00Z}）与日期串（{@code 2026-01-01}）。
   * 日期串按 <b>UTC 当日 00:00</b> 解释（endOfDay 时取当日 23:59:59.999999999）——
   * 明确写死 UTC，而不是让 JVM 的默认时区去猜（那正是 {@code Timestamp.valueOf(LocalDateTime)}
   * 静默偏 −28800 秒的根因）。
   */
  static OffsetDateTime parseTimestamp(String raw, boolean endOfDay) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value);
    } catch (RuntimeException ignored) {
      // 继续按日期解析
    }
    try {
      java.time.LocalDate date = java.time.LocalDate.parse(value);
      return endOfDay
          ? date.atTime(23, 59, 59, 999_999_999).atOffset(ZoneOffset.UTC)
          : date.atStartOfDay().atOffset(ZoneOffset.UTC);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static int intOf(Record row, String column) {
    Number value = row.get(column, Number.class);
    return value == null ? 0 : value.intValue();
  }

  private static long longOf(Record row, String column) {
    Number value = row.get(column, Number.class);
    return value == null ? 0L : value.longValue();
  }

  private static long count(Object raw) {
    return raw == null ? 0L : ((Number) raw).longValue();
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明：哪些下游还没交付、量不到归谁、结论与阻断的口径是什么。
   *
   * <p>🔴 五个下游当前<b>都未交付</b>，如实报出 ownerModule 与影响：
   * 16.4.1.1 侵权库、16.4.1.2 检测与预警、16.4.2.2 授权凭证、
   * 16.2.1.1 合规规则库、16.2.1.2 拦截执行。
   */
  public BrandAuthorizationCapabilities capabilities() {
    List<BrandAuthorizationCapabilityGap> gaps =
        List.of(
            new BrandAuthorizationCapabilityGap(
                "16.4.1.1",
                "侵权库",
                "16.4.1.1",
                false,
                "侵权库未交付 ⇒ 本模块只做**授权范围**判定，不做侵权比对；"
                    + "「这个品牌本身是否被侵权」这个问题现在答不了。"),
            new BrandAuthorizationCapabilityGap(
                "16.4.1.2",
                "检测与预警",
                "16.4.1.2",
                false,
                "检测与预警未交付 ⇒ 本模块的校验结论不会自动推成预警工单，"
                    + "临期 / 过期只在本模块的到期提醒清单里看。"),
            new BrandAuthorizationCapabilityGap(
                "16.4.2.2",
                "授权凭证",
                "16.4.2.2",
                false,
                "授权凭证未交付 ⇒ 本模块只登记授权文件的**引用与元数据**"
                    + "（文件名 / 类型 / 大小 / 存储句柄 / 上传时刻），"
                    + "不做真伪验证记录、文件版本与签署方信息，也不支持一键核验。"),
            new BrandAuthorizationCapabilityGap(
                "16.2.1.1",
                "合规规则库",
                "16.2.1.1",
                false,
                "合规规则库未交付 ⇒ 本模块的判定规则**不版本化治理**（规则版本号固定为 1），"
                    + "平台细则以硬编码口径实现，没有「哪一版规则、从何时生效」的来源。"),
            new BrandAuthorizationCapabilityGap(
                "16.2.1.2",
                "拦截执行",
                "16.2.1.2",
                false,
                "拦截执行未交付 ⇒ 本模块只把**服务端算出来的阻断结论**给出去"
                    + "（publish-gate），拦截、豁免与审批不在这里发生。"));
    return new BrandAuthorizationCapabilities(
        gaps,
        SCOPE_KINDS,
        CONCLUSIONS,
        SEVERITIES,
        EXPIRY_STATES,
        VERDICT_SEMANTICS,
        UNKNOWN_SEMANTICS,
        BLOCKING_SEMANTICS,
        EXPIRY_SEMANTICS,
        BOUNDARY_NOTE,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE);
  }

  // ==================================================================
  // 授权档案：列表 / 详情
  // ==================================================================

  /** 授权档案列表（**真分页 + 真筛选**）。 */
  public BrandAuthorizationPage authorizations(
      String brandId, String keyword, String state, int page, int size) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    int limit = Math.max(1, Math.min(size, 200));
    int offset = Math.max(0, page) * limit;

    List<Object> params = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where a.tenant_id = ?");
    params.add(tenant);

    UUID brand = parseUuid(brandId);
    if (brandId != null && !brandId.isBlank() && brand == null) {
      return new BrandAuthorizationPage(List.of(), 0, Math.max(0, page), limit);
    }
    if (brand != null) {
      where.append(" and a.brand_id = ?");
      params.add(brand);
    }
    if (keyword != null && !keyword.isBlank()) {
      where.append(
          " and (a.grantor or a.licensee_name or a.brand_name or a.note) ilike '%' || ? || '%'");
      params.add(keyword.trim());
    }
    String clause = stateClause(state, now);
    if (!clause.isEmpty()) {
      where.append(" and ").append(clause);
      params.addAll(stateParams(state, now));
    }

    long total =
        count(sql.fetchValue("select count(*) from brand_authz_authorization a" + where, params.toArray()));

    List<Object> rows = new ArrayList<>();
    rows.add(now);
    rows.addAll(params);
    rows.add(limit);
    rows.add(offset);
    List<Record> fetched =
        sql.fetch(
            """
            select a.id, a.brand_id, a.brand_name, a.grantor, a.licensee_name, a.licensee_type,
                   a.qualification_id, a.effective_from, a.effective_to, a.enabled, a.rule_version,
                   a.note, a.created_at, a.updated_at,
                   floor(extract(epoch from (a.effective_to - ?::timestamptz))/86400)::int as days_to_expiry,
                   (select count(*) from brand_authz_scope s
                     where s.tenant_id = a.tenant_id and s.authorization_id = a.id) as scope_count,
                   (select count(*) from brand_authz_document d
                     where d.tenant_id = a.tenant_id and d.authorization_id = a.id) as document_count
            from brand_authz_authorization a
            """
                + where
                + " order by a.effective_to desc, a.id limit ? offset ?",
            rows.toArray());

    List<BrandAuthorizationRecord> items = new ArrayList<>();
    for (Record row : fetched) {
      items.add(record(row, now, DEFAULT_EXPIRING_WINDOW_DAYS));
    }
    return new BrandAuthorizationPage(items, total, Math.max(0, page), limit);
  }

  /** 单条授权档案（档案 + 范围 + 文件）。跨租户与不存在返回逐字相同的 404。 */
  public BrandAuthorizationDetail authorization(String authorizationId) {
    UUID tenant = currentTenant();
    UUID id = parseUuid(authorizationId);
    if (id == null) {
      throw notFound();
    }
    OffsetDateTime now = dbNow();
    Record row =
        sql.fetchOne(
            """
            select a.id, a.brand_id, a.brand_name, a.grantor, a.licensee_name, a.licensee_type,
                   a.qualification_id, a.effective_from, a.effective_to, a.enabled, a.rule_version,
                   a.note, a.created_at, a.updated_at,
                   floor(extract(epoch from (a.effective_to - ?::timestamptz))/86400)::int as days_to_expiry,
                   (select count(*) from brand_authz_scope s
                     where s.tenant_id = a.tenant_id and s.authorization_id = a.id) as scope_count,
                   (select count(*) from brand_authz_document d
                     where d.tenant_id = a.tenant_id and d.authorization_id = a.id) as document_count
            from brand_authz_authorization a
            where a.tenant_id = ? and a.id = ?
            """,
            now,
            tenant,
            id);
    if (row == null) {
      throw notFound();
    }
    return new BrandAuthorizationDetail(
        record(row, now, DEFAULT_EXPIRING_WINDOW_DAYS), scopes(tenant, id), documents(tenant, id));
  }

  private List<BrandAuthorizationScopeLine> scopes(UUID tenant, UUID authorizationId) {
    List<BrandAuthorizationScopeLine> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select scope_kind, scope_value, scope_label
            from brand_authz_scope
            where tenant_id = ? and authorization_id = ?
            order by scope_kind, scope_value
            """,
            tenant,
            authorizationId)) {
      items.add(
          new BrandAuthorizationScopeLine(
              row.get("scope_kind", String.class),
              row.get("scope_value", String.class),
              row.get("scope_label", String.class)));
    }
    return items;
  }

  private List<BrandAuthorizationDocumentRow> documents(UUID tenant, UUID authorizationId) {
    List<BrandAuthorizationDocumentRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select id, file_name, content_type, size_bytes, storage_key, uploaded_at, note
            from brand_authz_document
            where tenant_id = ? and authorization_id = ?
            order by uploaded_at desc, id
            """,
            tenant,
            authorizationId)) {
      items.add(
          new BrandAuthorizationDocumentRow(
              row.get("id", UUID.class).toString(),
              row.get("file_name", String.class),
              row.get("content_type", String.class),
              longOf(row, "size_bytes"),
              row.get("storage_key", String.class),
              timestamp(row.get("uploaded_at", OffsetDateTime.class)),
              row.get("note", String.class)));
    }
    return items;
  }

  private BrandAuthorizationRecord record(Record row, OffsetDateTime now, int windowDays) {
    OffsetDateTime from = row.get("effective_from", OffsetDateTime.class);
    OffsetDateTime to = row.get("effective_to", OffsetDateTime.class);
    int days = intOf(row, "days_to_expiry");
    boolean enabled = Boolean.TRUE.equals(row.get("enabled", Boolean.class));
    String state = expiryState(enabled, from, to, days, now, windowDays);
    return new BrandAuthorizationRecord(
        row.get("id", UUID.class).toString(),
        row.get("brand_id", UUID.class).toString(),
        row.get("brand_name", String.class),
        row.get("grantor", String.class),
        row.get("licensee_name", String.class),
        row.get("licensee_type", String.class),
        row.get("qualification_id", UUID.class) == null
            ? null
            : row.get("qualification_id", UUID.class).toString(),
        timestamp(from),
        timestamp(to),
        enabled,
        longOf(row, "rule_version"),
        state,
        expiryStateLabel(state),
        days,
        intOf(row, "scope_count"),
        intOf(row, "document_count"),
        row.get("note", String.class),
        timestamp(row.get("created_at", OffsetDateTime.class)),
        timestamp(row.get("updated_at", OffsetDateTime.class)));
  }

  private static String timestamp(OffsetDateTime value) {
    return value == null ? "" : value.toString();
  }

  // ==================================================================
  // 授权档案：录入 / 修改 / 停用 / 删除
  // ==================================================================

  /** 录入一条授权（授权方 + 范围 + 文件 + 被授权主体 + 期限）。 */
  public BrandAuthorizationDetail create(BrandAuthorizationRegistrationInput input) {
    UUID tenant = currentTenant();
    Validated validated = validate(input, tenant);
    UUID id = UUID.randomUUID();

    sql.execute(
        """
        insert into brand_authz_authorization
          (id,tenant_id,brand_id,brand_name,grantor,licensee_name,licensee_type,qualification_id,
           effective_from,effective_to,enabled,rule_version,note,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,now(),now())
        """,
        id,
        tenant,
        validated.brandId(),
        validated.brandName(),
        validated.grantor(),
        validated.licenseeName(),
        validated.licenseeType(),
        validated.qualificationId(),
        validated.effectiveFrom(),
        validated.effectiveTo(),
        validated.enabled(),
        CURRENT_RULE_VERSION,
        validated.note());

    writeScopes(tenant, id, validated.scopes());
    writeDocuments(tenant, id, validated.documents());
    return authorization(id.toString());
  }

  /** 修改一条授权（范围 / 文件整体替换）。历史判定不受影响（判定是追加式凭据）。 */
  public BrandAuthorizationDetail update(String authorizationId, BrandAuthorizationRegistrationInput input) {
    UUID tenant = currentTenant();
    UUID id = parseUuid(authorizationId);
    if (id == null) {
      throw notFound();
    }
    Record existing =
        sql.fetchOne("select id from brand_authz_authorization where tenant_id = ? and id = ?", tenant, id);
    if (existing == null) {
      throw notFound();
    }
    Validated validated = validate(input, tenant);
    sql.execute(
        """
        update brand_authz_authorization
           set brand_id = ?, brand_name = ?, grantor = ?, licensee_name = ?, licensee_type = ?,
               qualification_id = ?, effective_from = ?::timestamptz, effective_to = ?::timestamptz,
               enabled = ?, note = ?, updated_at = now(), version = version + 1
         where tenant_id = ? and id = ?
        """,
        validated.brandId(),
        validated.brandName(),
        validated.grantor(),
        validated.licenseeName(),
        validated.licenseeType(),
        validated.qualificationId(),
        validated.effectiveFrom(),
        validated.effectiveTo(),
        validated.enabled(),
        validated.note(),
        tenant,
        id);
    sql.execute("delete from brand_authz_scope where tenant_id = ? and authorization_id = ?", tenant, id);
    sql.execute("delete from brand_authz_document where tenant_id = ? and authorization_id = ?", tenant, id);
    writeScopes(tenant, id, validated.scopes());
    writeDocuments(tenant, id, validated.documents());
    return authorization(id.toString());
  }

  /** 停用 / 启用一条授权（人工动作，立即生效）。 */
  public BrandAuthorizationDetail setEnabled(String authorizationId, boolean enabled) {
    UUID tenant = currentTenant();
    UUID id = parseUuid(authorizationId);
    if (id == null) {
      throw notFound();
    }
    int updated =
        sql.execute(
            """
            update brand_authz_authorization
               set enabled = ?, updated_at = now(), version = version + 1
             where tenant_id = ? and id = ?
            """,
            enabled,
            tenant,
            id);
    if (updated == 0) {
      throw notFound();
    }
    return authorization(id.toString());
  }

  private void writeScopes(UUID tenant, UUID authorizationId, List<BrandAuthorizationScopeInput> scopes) {
    for (BrandAuthorizationScopeInput scope : scopes) {
      sql.execute(
          """
          insert into brand_authz_scope
            (id,tenant_id,authorization_id,scope_kind,scope_value,scope_label,created_at)
          values (?,?,?,?,?,?,now())
          """,
          UUID.randomUUID(),
          tenant,
          authorizationId,
          scope.scopeKind(),
          scope.scopeValue(),
          scope.scopeLabel());
    }
  }

  private void writeDocuments(
      UUID tenant, UUID authorizationId, List<BrandAuthorizationDocumentInput> documents) {
    for (BrandAuthorizationDocumentInput document : documents) {
      OffsetDateTime uploadedAt = parseTimestamp(document.uploadedAt(), false);
      sql.execute(
          """
          insert into brand_authz_document
            (id,tenant_id,authorization_id,file_name,content_type,size_bytes,storage_key,
             uploaded_at,uploaded_by,note,created_at)
          values (?,?,?,?,?,?,?,?::timestamptz,?,?,now())
          """,
          UUID.randomUUID(),
          tenant,
          authorizationId,
          document.fileName(),
          document.contentType(),
          document.sizeBytes(),
          document.storageKey(),
          uploadedAt == null ? dbNow() : uploadedAt,
          null,
          document.note());
    }
  }

  // ==================================================================
  // 录入校验（真校验，不是摆设）
  // ==================================================================

  /** 校验后的入参。 */
  private record Validated(
      UUID brandId,
      String brandName,
      String grantor,
      String licenseeName,
      String licenseeType,
      UUID qualificationId,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      boolean enabled,
      String note,
      List<BrandAuthorizationScopeInput> scopes,
      List<BrandAuthorizationDocumentInput> documents) {}

  private Validated validate(BrandAuthorizationRegistrationInput input, UUID tenant) {
    if (input == null) {
      throw unprocessable("入参不能为空");
    }
    UUID brandId = parseUuid(input.brandId());
    if (brandId == null) {
      throw unprocessable("品牌 id 必须是合法的 UUID");
    }
    String grantor = text(input.grantor());
    if (grantor.isEmpty()) {
      throw unprocessable("授权方不能为空");
    }
    String licenseeName = text(input.licenseeName());
    if (licenseeName.isEmpty()) {
      throw unprocessable("被授权主体不能为空");
    }
    String licenseeType = text(input.licenseeType());
    if (!LICENSEE_TYPES.contains(licenseeType)) {
      throw unprocessable("被授权主体类型只能是 " + String.join(" / ", LICENSEE_TYPES));
    }
    OffsetDateTime from = parseTimestamp(input.effectiveFrom(), false);
    OffsetDateTime to = parseTimestamp(input.effectiveTo(), true);
    if (from == null || to == null) {
      throw unprocessable("授权期限必须给有效的开始与结束时间");
    }
    if (!to.isAfter(from)) {
      throw unprocessable("授权期限的结束时间必须晚于开始时间");
    }
    UUID qualificationId = parseUuid(input.qualificationId());

    List<BrandAuthorizationScopeInput> scopes = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (BrandAuthorizationScopeInput scope :
        input.scopes() == null ? List.<BrandAuthorizationScopeInput>of() : input.scopes()) {
      String kind = text(scope.scopeKind());
      if (!SCOPE_KINDS.contains(kind)) {
        throw unprocessable("授权范围维度只能是 " + String.join(" / ", SCOPE_KINDS));
      }
      String value = text(scope.scopeValue());
      if (value.isEmpty()) {
        throw unprocessable("授权范围的取值不能为空");
      }
      if ("COUNTRY".equals(kind)) {
        value = value.toUpperCase();
        if (value.length() != 2 || !value.chars().allMatch(Character::isLetter)) {
          throw unprocessable("国家范围必须填两位国家码（如 DE / US）");
        }
      } else {
        UUID parsed = parseUuid(value);
        if (parsed == null) {
          throw unprocessable(("SHOP".equals(kind) ? "店铺" : "类目") + "范围必须填合法的 UUID");
        }
        value = parsed.toString();
        if ("SHOP".equals(kind) && !shopExists(tenant, parsed)) {
          throw unprocessable("店铺范围里的店铺不存在或不属于当前租户");
        }
      }
      String key = kind + "|" + value;
      if (!seen.add(key)) {
        throw unprocessable("授权范围里有重复项：" + kind + " / " + value);
      }
      scopes.add(new BrandAuthorizationScopeInput(kind, value, text(scope.scopeLabel())));
    }

    List<BrandAuthorizationDocumentInput> documents = new ArrayList<>();
    for (BrandAuthorizationDocumentInput document :
        input.documents() == null
            ? List.<BrandAuthorizationDocumentInput>of()
            : input.documents()) {
      if (text(document.fileName()).isEmpty()) {
        throw unprocessable("授权文件名不能为空");
      }
      if (text(document.contentType()).isEmpty()) {
        throw unprocessable("授权文件的内容类型不能为空");
      }
      if (text(document.storageKey()).isEmpty()) {
        throw unprocessable("授权文件的存储句柄不能为空");
      }
      if (document.sizeBytes() < 0) {
        throw unprocessable("授权文件的字节数不能为负");
      }
      documents.add(
          new BrandAuthorizationDocumentInput(
              document.fileName(),
              document.contentType(),
              document.sizeBytes(),
              document.storageKey(),
              document.uploadedAt(),
              text(document.note())));
    }

    return new Validated(
        brandId,
        text(input.brandName()),
        grantor,
        licenseeName,
        licenseeType,
        qualificationId,
        from,
        to,
        input.enabled() == null || input.enabled(),
        text(input.note()),
        scopes,
        documents);
  }

  private boolean shopExists(UUID tenant, UUID shopId) {
    return count(sql.fetchValue("select count(*) from shop where tenant_id = ? and id = ?", tenant, shopId))
        > 0;
  }

  // ==================================================================
  // 到期提醒（真筛选 + 真分页）
  // ==================================================================

  /**
   * 到期提醒清单：临期 / 已过期 / 全部（**真筛选 + 真分页**）。
   *
   * <p>🔴 「平时没事、上新时出事」的那一半在这里被提前摆出来。
   */
  public BrandAuthorizationExpiryPage expiryReminders(
      String state, Integer withinDays, int page, int size) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    int window =
        withinDays == null ? DEFAULT_EXPIRING_WINDOW_DAYS : Math.max(0, Math.min(withinDays, 3650));
    int limit = Math.max(1, Math.min(size, 200));
    int offset = Math.max(0, page) * limit;

    List<Object> params = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where a.tenant_id = ?");
    params.add(tenant);
    String clause = expiryStateClause(state, now, window);
    if (!clause.isEmpty()) {
      where.append(" and ").append(clause);
      params.addAll(expiryStateParams(state, now, window));
    }

    long total =
        count(sql.fetchValue("select count(*) from brand_authz_authorization a" + where, params.toArray()));

    long expiringCount =
        count(
            sql.fetchValue(
                """
                select count(*) from brand_authz_authorization a
                 where a.tenant_id = ?
                   and a.enabled
                   and a.effective_from <= ?::timestamptz
                   and a.effective_to >= ?::timestamptz
                   and floor(extract(epoch from (a.effective_to - ?::timestamptz))/86400)::int <= ?
                """,
                tenant,
                now,
                now,
                now,
                window));

    long expiredCount =
        count(
            sql.fetchValue(
                """
                select count(*) from brand_authz_authorization a
                 where a.tenant_id = ?
                   and a.enabled
                   and a.effective_to < ?::timestamptz
                """,
                tenant,
                now));

    List<Object> rows = new ArrayList<>();
    rows.add(now);
    rows.addAll(params);
    rows.add(limit);
    rows.add(offset);
    List<Record> fetched =
        sql.fetch(
            """
            select a.id, a.brand_id, a.brand_name, a.grantor, a.licensee_name, a.enabled,
                   a.effective_from, a.effective_to, a.note,
                   floor(extract(epoch from (a.effective_to - ?::timestamptz))/86400)::int as days_to_expiry,
                   (select count(*) from brand_authz_scope s
                     where s.tenant_id = a.tenant_id and s.authorization_id = a.id) as scope_count
            from brand_authz_authorization a
            """
                + where
                + " order by a.effective_to asc, a.id limit ? offset ?",
            rows.toArray());

    List<BrandAuthorizationExpiryRow> items = new ArrayList<>();
    for (Record row : fetched) {
      int days = intOf(row, "days_to_expiry");
      OffsetDateTime from = row.get("effective_from", OffsetDateTime.class);
      OffsetDateTime to = row.get("effective_to", OffsetDateTime.class);
      boolean enabled = Boolean.TRUE.equals(row.get("enabled", Boolean.class));
      String state0 = expiryState(enabled, from, to, days, now, window);
      items.add(
          new BrandAuthorizationExpiryRow(
              row.get("id", UUID.class).toString(),
              row.get("brand_id", UUID.class).toString(),
              row.get("brand_name", String.class),
              row.get("grantor", String.class),
              row.get("licensee_name", String.class),
              timestamp(to),
              state0,
              expiryStateLabel(state0),
              days,
              enabled,
              intOf(row, "scope_count") + " 条范围",
              row.get("note", String.class)));
    }
    return new BrandAuthorizationExpiryPage(
        items,
        total,
        Math.max(0, page),
        limit,
        expiringCount,
        expiredCount,
        "🔴 到期判定用同源 DB 时钟（select now()）；剩余天数由 DB 按 effective_to - now() 算，"
            + "不依赖应用机时钟。临期窗口默认 "
            + DEFAULT_EXPIRING_WINDOW_DAYS
            + " 天。");
  }

  // ==================================================================
  // 校验：范围覆盖 + 阻断判定
  // ==================================================================

  /** 跑一次范围校验并落一条凭据。 */
  public BrandAuthorizationCheckResult check(BrandAuthorizationCheckRequest request, String bearer) {
    return evaluateAndPersist(request).result();
  }

  /**
   * 🔴 刊登闸门：服务端算出来的阻断判定。
   *
   * <p>只有 {@code IN_SCOPE} 才 {@code allowed=true}。这才是「阻断类规则在服务端强制」的落点 ——
   * 页面上的红字只是显示，不是防线。
   */
  public BrandAuthorizationPublishGate publishGate(BrandAuthorizationCheckRequest request, String bearer) {
    BrandAuthorizationCheckResult result = evaluateAndPersist(request).result();
    return new BrandAuthorizationPublishGate(
        result.inScope(),
        result.blocking(),
        result.severity(),
        result.conclusion(),
        result.reasons(),
        result.evaluatedAt(),
        result.ruleVersion(),
        result.checkId(),
        result.brandId());
  }

  /** 校验历史（**真分页**）。 */
  public BrandAuthorizationCheckPage checks(String brandId, int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    int offset = Math.max(0, page) * limit;
    UUID brand = parseUuid(brandId);

    List<Object> params = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where c.tenant_id = ?");
    params.add(tenant);
    if (brand != null) {
      where.append(" and c.brand_id = ?");
      params.add(brand);
    }
    long total = count(sql.fetchValue("select count(*) from brand_authz_check c" + where, params.toArray()));

    List<Object> rows = new ArrayList<>(params);
    rows.add(limit);
    rows.add(offset);
    List<Record> fetched =
        sql.fetch(
            """
            select c.id, c.brand_id, c.brand_name, c.shop_id, c.country_code, c.category_id,
                   c.evaluated_at, c.rule_version, c.in_scope, c.blocking, c.severity, c.conclusion,
                   c.matched_authorization_id, c.reasons
            from brand_authz_check c
            """
                + where
                + " order by c.evaluated_at desc, c.id limit ? offset ?",
            rows.toArray());

    List<BrandAuthorizationCheckSummary> items = new ArrayList<>();
    for (Record row : fetched) {
      List<BrandAuthorizationReason> reasons = readReasons(row.get("reasons"));
      items.add(
          new BrandAuthorizationCheckSummary(
              row.get("id", UUID.class).toString(),
              row.get("brand_id", UUID.class).toString(),
              row.get("brand_name", String.class),
              row.get("shop_id", UUID.class) == null
                  ? null
                  : row.get("shop_id", UUID.class).toString(),
              row.get("country_code", String.class),
              row.get("category_id", UUID.class) == null
                  ? null
                  : row.get("category_id", UUID.class).toString(),
              timestamp(row.get("evaluated_at", OffsetDateTime.class)),
              longOf(row, "rule_version"),
              Boolean.TRUE.equals(row.get("in_scope", Boolean.class)),
              Boolean.TRUE.equals(row.get("blocking", Boolean.class)),
              row.get("severity", String.class),
              severityLabel(row.get("severity", String.class)),
              row.get("conclusion", String.class),
              conclusionLabel(row.get("conclusion", String.class)),
              row.get("matched_authorization_id", UUID.class) == null
                  ? null
                  : row.get("matched_authorization_id", UUID.class).toString(),
              reasons,
              reasons.size()));
    }
    return new BrandAuthorizationCheckPage(items, total, Math.max(0, page), limit);
  }

  /** 读回 jsonb 里的原因清单。 */
  private List<BrandAuthorizationReason> readReasons(Object raw) {
    List<BrandAuthorizationReason> reasons = new ArrayList<>();
    String payload = jsonText(raw);
    if (payload.isBlank() || "[]".equals(payload)) {
      return reasons;
    }
    try {
      List<Map<String, Object>> parsed =
          json.readValue(payload, new com.fasterxml.jackson.core.type.TypeReference<>() {});
      for (Map<String, Object> one : parsed) {
        reasons.add(
            new BrandAuthorizationReason(
                String.valueOf(one.get("code")),
                String.valueOf(one.get("label")),
                String.valueOf(one.get("message")),
                String.valueOf(one.get("severity")),
                one.get("authorizationId") == null ? null : String.valueOf(one.get("authorizationId")),
                one.get("authorizationLabel") == null ? "" : String.valueOf(one.get("authorizationLabel"))));
      }
    } catch (Exception e) {
      return reasons;
    }
    return reasons;
  }

  /** 一次校验的内部产物。 */
  private record Evaluated(BrandAuthorizationCheckResult result) {}

  private Evaluated evaluateAndPersist(BrandAuthorizationCheckRequest request) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    if (request == null) {
      throw unprocessable("校验请求不能为空");
    }
    UUID brandId = parseUuid(request.brandId());
    if (brandId == null) {
      throw unprocessable("品牌 id 必须是合法的 UUID");
    }
    UUID shopId = parseUuid(request.shopId());
    if (request.shopId() != null && !request.shopId().isBlank() && shopId == null) {
      throw unprocessable("店铺 id 必须是合法的 UUID");
    }
    UUID categoryId = parseUuid(request.categoryId());
    if (request.categoryId() != null && !request.categoryId().isBlank() && categoryId == null) {
      throw unprocessable("类目 id 必须是合法的 UUID");
    }
    String countryCode = text(request.countryCode()).toUpperCase();
    if (!countryCode.isEmpty()
        && (countryCode.length() != 2 || !countryCode.chars().allMatch(Character::isLetter))) {
      throw unprocessable("国家必须是两位国家码（如 DE / US）");
    }

    // 店铺覆盖的国家（从店铺的启用站点还原）。空 = 判不了（由规则落到 UNKNOWN，不是「不覆盖」）。
    Set<String> shopCountries =
        shopId == null ? Set.of() : new LinkedHashSet<>(shopCountries(tenant, shopId));

    // ---- 读本品牌的全部授权（含已停用 / 已过期的 —— 它们是「为什么不覆盖」的原因） ----
    List<Record> authorizationRows =
        sql.fetch(
            """
            select a.id, a.grantor, a.licensee_name, a.enabled, a.effective_from, a.effective_to
            from brand_authz_authorization a
            where a.tenant_id = ? and a.brand_id = ?
            order by a.effective_to desc, a.id
            """,
            tenant,
            brandId);

    List<AuthorizationFact> facts = new ArrayList<>();
    Map<String, Map<String, Set<String>>> scopesByAuthorization = new LinkedHashMap<>();
    for (Record row : authorizationRows) {
      UUID id = row.get("id", UUID.class);
      Map<String, Set<String>> scopeMap = new LinkedHashMap<>();
      for (Record scope :
          sql.fetch(
              """
              select scope_kind, scope_value
              from brand_authz_scope
              where tenant_id = ? and authorization_id = ?
              order by scope_kind, scope_value
              """,
              tenant,
              id)) {
        scopeMap
            .computeIfAbsent(scope.get("scope_kind", String.class), key -> new LinkedHashSet<>())
            .add(scope.get("scope_value", String.class));
      }
      scopesByAuthorization.put(id.toString(), scopeMap);
      facts.add(
          new AuthorizationFact(
              id.toString(),
              row.get("grantor", String.class) + " → " + row.get("licensee_name", String.class),
              Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
              row.get("effective_from", OffsetDateTime.class),
              row.get("effective_to", OffsetDateTime.class),
              scopeMap));
    }

    CheckContext context =
        new CheckContext(
            brandId.toString(),
            shopId == null ? null : shopId.toString(),
            countryCode.isEmpty() ? null : countryCode,
            categoryId == null ? null : categoryId.toString(),
            shopCountries);

    Evaluation evaluation = evaluate(facts, context, now);

    String brandName = brandName(tenant, brandId);

    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("brandId", brandId.toString());
    snapshot.put("brandName", brandName);
    snapshot.put("shopId", shopId == null ? null : shopId.toString());
    snapshot.put("countryCode", countryCode.isEmpty() ? null : countryCode);
    snapshot.put("categoryId", categoryId == null ? null : categoryId.toString());
    snapshot.put("shopCountries", List.copyOf(shopCountries));
    snapshot.put("ruleVersion", CURRENT_RULE_VERSION);
    List<Map<String, Object>> authorizationSnapshot = new ArrayList<>();
    for (Record row : authorizationRows) {
      UUID id = row.get("id", UUID.class);
      Map<String, Object> one = new LinkedHashMap<>();
      one.put("authorizationId", id.toString());
      one.put("grantor", row.get("grantor", String.class));
      one.put("licenseeName", row.get("licensee_name", String.class));
      one.put("enabled", row.get("enabled", Boolean.class));
      one.put("effectiveFrom", timestamp(row.get("effective_from", OffsetDateTime.class)));
      one.put("effectiveTo", timestamp(row.get("effective_to", OffsetDateTime.class)));
      one.put("scopes", scopesByAuthorization.get(id.toString()));
      authorizationSnapshot.add(one);
    }
    snapshot.put("authorizations", authorizationSnapshot);

    UUID checkId = UUID.randomUUID();
    sql.execute(
        """
        insert into brand_authz_check
          (id,tenant_id,brand_id,brand_name,shop_id,country_code,category_id,
           evaluated_at,rule_version,in_scope,blocking,severity,conclusion,
           matched_authorization_id,reasons,snapshot,created_at)
        values (?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?::jsonb,?::jsonb,now())
        """,
        checkId,
        tenant,
        brandId,
        brandName,
        shopId,
        countryCode.isEmpty() ? null : countryCode,
        categoryId,
        now,
        CURRENT_RULE_VERSION,
        evaluation.inScope(),
        "BLOCKING".equals(evaluation.severity()),
        evaluation.severity(),
        evaluation.conclusion(),
        evaluation.matchedAuthorizationId() == null
            ? null
            : parseUuid(evaluation.matchedAuthorizationId()),
        writeJson(reasonPayload(evaluation.reasons())),
        writeJson(snapshot));

    BrandAuthorizationCheckResult result =
        new BrandAuthorizationCheckResult(
            checkId.toString(),
            brandId.toString(),
            brandName,
            shopId == null ? null : shopId.toString(),
            countryCode.isEmpty() ? null : countryCode,
            categoryId == null ? null : categoryId.toString(),
            timestamp(now),
            CURRENT_RULE_VERSION,
            evaluation.inScope(),
            "BLOCKING".equals(evaluation.severity()),
            evaluation.severity(),
            evaluation.conclusion(),
            evaluation.matchedAuthorizationId(),
            evaluation.reasons(),
            snapshot);
    return new Evaluated(result);
  }

  private String brandName(UUID tenant, UUID brandId) {
    Object raw =
        sql.fetchValue(
            "select coalesce(max(a.brand_name),'') from brand_authz_authorization a where a.tenant_id = ? and a.brand_id = ?",
            tenant,
            brandId);
    return raw == null ? "" : String.valueOf(raw);
  }

  private List<Map<String, Object>> reasonPayload(List<BrandAuthorizationReason> reasons) {
    List<Map<String, Object>> payload = new ArrayList<>();
    for (BrandAuthorizationReason reason : reasons) {
      Map<String, Object> one = new LinkedHashMap<>();
      one.put("code", reason.code());
      one.put("label", reason.label());
      one.put("message", reason.message());
      one.put("severity", reason.severity());
      one.put("authorizationId", reason.authorizationId());
      one.put("authorizationLabel", reason.authorizationLabel());
      payload.add(one);
    }
    return payload;
  }

  /** 店铺覆盖的国家（从店铺的启用站点还原）。 */
  private List<String> shopCountries(UUID tenant, UUID shopId) {
    List<String> countries = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select distinct m.country_code
            from shop_marketplace sm
            join marketplace m on m.id = sm.marketplace_id
            where sm.tenant_id = ? and sm.shop_id = ? and sm.status = 'ACTIVE'
            order by m.country_code
            """,
            tenant,
            shopId)) {
      String code = row.get("country_code", String.class);
      if (code != null && !code.isBlank()) {
        countries.add(code.trim());
      }
    }
    return countries;
  }

  // ==================================================================
  // 候选（跨服务只读）
  // ==================================================================

  /** 03 品牌候选（catalog，只读）。 */
  public BrandAuthorizationCandidatePage brandCandidates(String keyword, String bearer) {
    BrandAuthorizationSourcePort.CandidateSet set = source.brands(keyword, bearer);
    return new BrandAuthorizationCandidatePage(
        set.items(),
        set.items().size(),
        set.available()
            ? "03 品牌档案（catalog，只读）。⚠️ catalog 的品牌授权凭证与本模块的授权档案**不是同一件事**。"
            : set.note());
  }

  /** 被授权主体候选（6.1.2.3 供应商资质，trade 服务，只读）。 */
  public BrandAuthorizationCandidatePage supplierCandidates(String keyword, String bearer) {
    BrandAuthorizationSourcePort.CandidateSet set = source.suppliers(keyword, bearer);
    return new BrandAuthorizationCandidatePage(
        set.items(), set.items().size(), set.available() ? "6.1.2.3 供应商资质（trade 服务，只读）。" : set.note());
  }

  /** 某个品牌下已登记的供应商资质（被授权主体该选谁）。 */
  public BrandAuthorizationCandidatePage qualificationCandidates(String brandId, String bearer) {
    UUID brand = parseUuid(brandId);
    if (brand == null) {
      throw unprocessable("品牌 id 必须是合法的 UUID");
    }
    BrandAuthorizationSourcePort.CandidateSet set = source.qualifications(brand.toString(), bearer);
    return new BrandAuthorizationCandidatePage(
        set.items(), set.items().size(), set.available() ? "6.1.2.3 供应商资质（trade 服务，只读）。" : set.note());
  }

  /** 可选店铺（连同它覆盖的国家）。 */
  public List<BrandAuthorizationShopOption> shops(int page, int size) {
    UUID tenant = currentTenant();
    int limit = Math.max(1, Math.min(size, 200));
    int offset = Math.max(0, page) * limit;
    List<BrandAuthorizationShopOption> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            """
            select s.id, s.code, s.name, p.code as platform_code,
                   (select array_agg(distinct m.country_code)
                      from shop_marketplace sm
                      join marketplace m on m.id = sm.marketplace_id
                     where sm.tenant_id = s.tenant_id and sm.shop_id = s.id and sm.status = 'ACTIVE')
                     as country_codes
            from shop s
            join platform p on p.id = s.platform_id
            where s.tenant_id = ? and s.status <> 'ARCHIVED'
            order by s.code
            limit ? offset ?
            """,
            tenant,
            limit,
            offset)) {
      String[] codes = row.get("country_codes", String[].class);
      List<String> countries = codes == null ? List.of() : List.of(codes);
      items.add(
          new BrandAuthorizationShopOption(
              row.get("id", UUID.class).toString(),
              row.get("code", String.class),
              row.get("name", String.class),
              row.get("platform_code", String.class),
              countries));
    }
    return items;
  }

  // ==================================================================
  // 筛选条件
  // ==================================================================

  private static String stateClause(String state, OffsetDateTime now) {
    String value = text(state).toUpperCase();
    if (value.isEmpty()) {
      return "";
    }
    if (!EXPIRY_STATES.contains(value)) {
      throw unprocessable("到期状态只能是 " + String.join(" / ", EXPIRY_STATES));
    }
    return expiryStateClause(value, now, DEFAULT_EXPIRING_WINDOW_DAYS);
  }

  private static List<Object> stateParams(String state, OffsetDateTime now) {
    return expiryStateParams(text(state).toUpperCase(), now, DEFAULT_EXPIRING_WINDOW_DAYS);
  }

  /**
   * 到期状态的 SQL 片段（用**同一个** DB 时钟参数）。
   *
   * <p>🔴 剩余天数在这里算（{@code floor(extract(epoch from (effective_to - ?))/86400)}），
   * 所以筛选与展示用的是同一个时刻 —— 不会出现「列表说剩 3 天、卡片说剩 4 天」。
   */
  private static String expiryStateClause(String state, OffsetDateTime now, int window) {
    String value = text(state).toUpperCase();
    return switch (value) {
      case "" -> "";
      case "DISABLED" -> "a.enabled = false";
      case "NOT_YET_EFFECTIVE" -> "a.enabled and a.effective_from > ?::timestamptz";
      case "EXPIRED" -> "a.enabled and a.effective_to < ?::timestamptz";
      case "EXPIRING" ->
          "a.enabled and a.effective_from <= ?::timestamptz and a.effective_to >= ?::timestamptz"
              + " and floor(extract(epoch from (a.effective_to - ?::timestamptz))/86400)::int <= ?";
      case "ACTIVE" ->
          "a.enabled and a.effective_from <= ?::timestamptz and a.effective_to >= ?::timestamptz"
              + " and floor(extract(epoch from (a.effective_to - ?::timestamptz))/86400)::int > ?";
      default -> throw unprocessable("到期状态只能是 " + String.join(" / ", EXPIRY_STATES));
    };
  }

  private static List<Object> expiryStateParams(String state, OffsetDateTime now, int window) {
    String value = text(state).toUpperCase();
    return switch (value) {
      case "NOT_YET_EFFECTIVE", "EXPIRED" -> List.of(now);
      case "EXPIRING", "ACTIVE" -> List.of(now, now, now, window);
      default -> List.of();
    };
  }
}
