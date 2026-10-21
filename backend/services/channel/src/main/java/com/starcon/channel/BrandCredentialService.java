package com.starcon.channel;

import static com.starcon.channel.BrandCredentialModels.*;
import static com.starcon.channel.BrandCredentialRules.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.4.2.2 授权凭证。
 *
 * <p>蓝图：「集中存储与核验授权凭证文件：真伪验证记录、文件版本、签署方信息，支持一键核验。
 * 输入：凭证上传；核验操作。输出：凭证档案 + 核验记录。
 * 关系：上游 = 16.4.2.1；下游 = 16.4.1.2。<b>凭证需保留原始文件与元数据，争议时是唯一证据。</b>」
 *
 * <p>🔴🔴 本类里有五处<b>结构</b>上的不变量：
 *
 * <ol>
 *   <li><b>文件本体真在受管存储里</b>：上传时真写盘，核验时真读回来重算 sha256。
 *       DB 里只存句柄 + 元数据 + 摘要（16.4.2.1 只登记了引用，<b>原件的归宿是本模块的责任</b>）。
 *   <li><b>「真伪」的判据是摘要</b>：重算的 sha256 与登记的 sha256 逐字比对，
 *       不一致 ⇒ 文件在登记之后被改过 ⇒ 判定不通过。文件不在 ⇒ FILE_MISSING，<b>不是</b>「通过」。
 *   <li><b>核验记录是追加式凭据</b>：V255 的触发器钉住结论不可改写，
 *       规则版本变化只产生新行，<b>不追溯改变历史判定</b>。
 *   <li><b>「没核验过」≠「核验过但不通过」</b>：{@code UNVERIFIED} 与 {@code FAILED} 是两个状态，
 *       界面不许把前者显示成后者。
 *   <li><b>上游 16.4.2.1 同库只读</b>：{@code brand_authz_authorization} 在<b>同一个库</b>里，
 *       本模块只读它拿「授权档案候选」，<b>不写</b>；凭证通过<b>逻辑 id</b>（不建 FK）挂在它下面 ——
 *       凭证是争议时的唯一证据，必须活得比它引用的授权更久。
 * </ol>
 */
@Service
public class BrandCredentialService {

  private static final String VERIFICATION_SEMANTICS =
      "核验结论八态：VERIFIED = 文件在受管存储里、重算 sha256 与登记摘要逐字相等、"
          + "凭证在有效期内、且至少登记了一个签署方；"
          + "DIGEST_MISMATCH = 文件被改过（重算摘要对不上）；"
          + "FILE_MISSING = 文件本体不在受管存储里（不是「通过」）；"
          + "EXPIRED = 凭证已过期；NOT_YET_EFFECTIVE = 凭证尚未生效；"
          + "SIGNATORY_INCOMPLETE = 一个签署方都没登记；NO_DOCUMENT = 凭证还没有任何文件版本；"
          + "REVOKED = 凭证已被撤销。"
          + "🔴 只有 VERIFIED 才算通过 —— 「验不出问题」不等于「没问题」。";

  private static final String DIGEST_SEMANTICS =
      "🔴 本模块的「真伪」是**本地重算 sha256**：把受管存储里的文件本体读回来，"
          + "重算摘要，与登记时记下的摘要逐字比对。这不是「连品牌方服务器验签」——"
          + "后者需要品牌方提供接口，不在本模块范围内；"
          + "但它**本地可复算、可审计、争议时拿得出来**，且判定过程与结论都落成不可改写的凭据。";

  private static final String APPEND_ONLY_SEMANTICS =
      "追加式纪律：凭证文件版本**只追加**（续期 / 换件 = 上传新版本，不是覆盖原来那份）；"
          + "核验记录**只追加**且触发器钉住不可改写；"
          + "规则改了就是**新版本 + 新一行**，旧行原样留着 —— 「上次为什么这么判」永远答得出来。";

  private static final String BOUNDARY_NOTE =
      "🔴 与 16.4.2.1 品牌授权**不是同一件事**：16.4.2.1 管的是「哪一段范围、哪一段时间、谁可以用这个品牌」，"
          + "它的 brand_authz_document 只登记授权文件的**引用与元数据**（文件本体不入库）；"
          + "本模块管的是「凭证文件本体、文件版本、签署方信息、真伪核验记录」。"
          + "两边的写入所有权**各自独立**（本模块不移交 brand_authz_document 的写入所有权）："
          + "凭证必须活得比它引用的授权更久，所以凭证侧不建指向 brand_authz_* 的外键。";

  private static final String UPSTREAM_NOTE =
      "上游 = 16.4.2.1 品牌授权（**同属 channel 服务、同一个库**）："
          + "本模块只读 brand_authz_authorization 拿「授权档案候选」，凭证通过逻辑 id 挂在授权下。";

  private static final String DOWNSTREAM_NOTE =
      "下游 = 16.4.1.2 检测与预警（未交付）⇒ 本模块的核验结论目前只落在核验记录里，"
          + "不会自动推成预警工单。16.4.1.2 交付后接上。";

  private final DSLContext sql;
  private final ObjectMapper json;
  private final CredentialDocumentStorage storage;

  public BrandCredentialService(
      DSLContext sql, ObjectMapper json, CredentialDocumentStorage storage) {
    this.sql = sql;
    this.json = json;
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
   * 应用机与数据库机差半天的话，「凭证还剩 0 天」就变成了差半天。
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
      return "[]";
    }
    String value = raw instanceof org.jooq.JSONB j ? j.data() : String.valueOf(raw);
    return value.isBlank() ? "[]" : value;
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

  private static UUID requireUuid(String raw, String label) {
    UUID value = parseUuid(raw);
    if (value == null) {
      throw unprocessable(label + "不是合法的 uuid");
    }
    return value;
  }

  /**
   * 🔴 解析时间戳入参。
   *
   * <p>接受带偏移量的 ISO 串（{@code 2026-01-01T00:00:00Z}）与日期串（{@code 2026-01-01}）。
   * 日期串按 <b>UTC 当日 00:00</b> 解释 —— 明确写死 UTC，而不是让 JVM 的默认时区去猜
   * （那正是 {@code Timestamp.valueOf(LocalDateTime)} 静默偏 −28800 秒的根因）。
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
      LocalDate date = LocalDate.parse(value);
      return endOfDay
          ? date.atTime(23, 59, 59, 999_999_999).atOffset(ZoneOffset.UTC)
          : date.atStartOfDay().atOffset(ZoneOffset.UTC);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static LocalDate parseDate(String raw) {
    String value = text(raw);
    if (value.isEmpty()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (RuntimeException e) {
      throw unprocessable("签发日不是合法的日期（应为 YYYY-MM-DD）");
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
   * 能力声明：哪些下游还没交付、量不到归谁、结论口径是什么。
   */
  public BrandCredentialCapabilities capabilities() {
    List<BrandCredentialCapabilityGap> gaps =
        List.of(
            new BrandCredentialCapabilityGap(
                "16.4.1.2",
                "检测与预警",
                "16.4.1.2",
                false,
                "检测与预警未交付 ⇒ 本模块的核验结论不会自动推成预警工单，"
                    + "「这份凭证今天还成立吗」只在本模块的核验记录里看。"),
            new BrandCredentialCapabilityGap(
                "16.2.1.1",
                "合规规则库",
                "16.2.1.1",
                false,
                "合规规则库未交付 ⇒ 本模块的核验规则**不版本化治理**"
                    + "（规则版本号固定为 1），核验口径以硬编码实现，"
                    + "没有「哪一版规则、从何时生效」的来源。"),
            new BrandCredentialCapabilityGap(
                "16.4.2.1",
                "品牌授权",
                "16.4.2.1",
                true,
                "16.4.2.1 已交付（同库）⇒ 本模块能读到它的授权档案，"
                    + "凭证可挂在某条授权下（逻辑 id，不建外键）。"
                    + "🔴 但它**只登记授权文件的引用与元数据**，文件本体的存储与真伪核验归本模块。"));
    return new BrandCredentialCapabilities(
        gaps,
        CREDENTIAL_TYPES,
        CONCLUSIONS,
        SEVERITIES,
        VERIFICATION_STATES,
        EXPIRY_STATES,
        VERIFICATION_SEMANTICS,
        DIGEST_SEMANTICS,
        APPEND_ONLY_SEMANTICS,
        BOUNDARY_NOTE,
        UPSTREAM_NOTE,
        DOWNSTREAM_NOTE);
  }

  // ==================================================================
  // 凭证档案：列表 / 详情
  // ==================================================================

  /**
   * 凭证档案列表（**真分页 + 真筛选**）。
   *
   * @param q 关键词（凭证编号 / 授权方 / 被授权主体 / 品牌名 / 备注）
   * @param status ACTIVE / REVOKED
   * @param expiryState ACTIVE / EXPIRING / EXPIRED / NOT_YET_EFFECTIVE / REVOKED
   * @param verificationState UNVERIFIED / VERIFIED / FAILED
   * @param credentialType 凭证类型
   * @param authorizationId 上游 16.4.2.1 的授权档案 id
   */
  public BrandCredentialPage credentials(
      String q,
      String status,
      String expiryState,
      String verificationState,
      String credentialType,
      String authorizationId,
      int page,
      int size) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    int limit = Math.max(1, Math.min(size, 200));
    int offset = Math.max(0, page) * limit;

    StringBuilder where = new StringBuilder(" where c.tenant_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);

    if (text(q).length() > 0) {
      where.append(
          " and (c.credential_no ilike '%' || ? || '%'"
              + " or c.grantor ilike '%' || ? || '%'"
              + " or c.subject ilike '%' || ? || '%'"
              + " or c.brand_name ilike '%' || ? || '%'"
              + " or c.note ilike '%' || ? || '%')");
      String like = text(q);
      for (int i = 0; i < 5; i++) {
        binds.add(like);
      }
    }
    if (text(status).length() > 0) {
      where.append(" and c.status = ?");
      binds.add(text(status).toUpperCase(Locale.ROOT));
    }
    if (text(credentialType).length() > 0) {
      where.append(" and c.credential_type = ?");
      binds.add(normalizeCredentialType(credentialType));
    }
    if (text(authorizationId).length() > 0) {
      UUID authorization = parseUuid(authorizationId);
      if (authorization == null) {
        return new BrandCredentialPage(List.of(), 0, Math.max(0, page), limit);
      }
      where.append(" and c.authorization_id = ?");
      binds.add(authorization);
    }

    // 到期状态：按**数据库时钟**现算的语义在 SQL 侧表达（与读出口同一口径）
    String expiryClause = expiryClause(text(expiryState).toUpperCase(Locale.ROOT));
    if (!expiryClause.isEmpty()) {
      where.append(" and ").append(expiryClause);
      binds.addAll(expiryBinds(text(expiryState).toUpperCase(Locale.ROOT), now));
    }

    // 最近核验状态：靠 last_v 这个 lateral 子查询
    String verification = text(verificationState).toUpperCase(Locale.ROOT);
    if ("UNVERIFIED".equals(verification)) {
      where.append(" and last_v.verification_id is null");
    } else if ("VERIFIED".equals(verification)) {
      where.append(" and last_v.passed is true");
    } else if ("FAILED".equals(verification)) {
      where.append(" and last_v.passed is false");
    }

    String from =
        """
        from brand_cred_credential c
        left join lateral (
          select count(*) as document_count, coalesce(max(version_no),0) as current_version
            from brand_cred_document d
           where d.tenant_id = c.tenant_id and d.credential_id = c.id
        ) dv on true
        left join lateral (
          select count(*) as signatory_count
            from brand_cred_signatory s
           where s.tenant_id = c.tenant_id and s.credential_id = c.id
        ) sv on true
        left join lateral (
          select v.id as verification_id, v.conclusion, v.passed, v.evaluated_at
            from brand_cred_verification v
           where v.tenant_id = c.tenant_id and v.credential_id = c.id
           order by v.evaluated_at desc, v.id desc
           limit 1
        ) last_v on true
        """;

    long total =
        count(
            sql.fetchValue(
                "select count(*) from (select c.id " + from + where + ") t",
                binds.toArray()));

    List<Object> rows = new ArrayList<>();
    rows.add(now);
    rows.addAll(binds);
    rows.add(limit);
    rows.add(offset);

    List<Record> fetched =
        sql.fetch(
            """
            select c.id, c.authorization_id, c.grantor, c.credential_no, c.credential_type, c.subject,
                   c.brand_id, c.brand_name, c.valid_from, c.valid_until, c.issued_on, c.status,
                   c.revoked_at, c.revoked_reason, c.note, c.version, c.created_at, c.updated_at,
                   floor(extract(epoch from (c.valid_until - ?::timestamptz))/86400)::int as days_to_expiry,
                   coalesce(dv.current_version,0) as current_version,
                   coalesce(dv.document_count,0) as document_count,
                   coalesce(sv.signatory_count,0) as signatory_count,
                   last_v.verification_id, last_v.conclusion, last_v.passed, last_v.evaluated_at
            """
                + from
                + where
                + " order by c.valid_until desc, c.id limit ? offset ?",
            rows.toArray());

    List<BrandCredentialRecord> items = new ArrayList<>();
    for (Record row : fetched) {
      items.add(credentialRecord(row, now));
    }
    return new BrandCredentialPage(items, total, Math.max(0, page), limit);
  }

  /** 到期状态的 SQL 片段（按 DB 时钟现算，与 {@link BrandCredentialRules#classifyExpiry} 同口径）。 */
  private static String expiryClause(String state) {
    return switch (state) {
      case "ACTIVE" ->
          " and c.status = 'ACTIVE' and c.valid_from <= ?::timestamptz and c.valid_until > ?::timestamptz";
      case "EXPIRING" ->
          " and c.status = 'ACTIVE' and c.valid_from <= ?::timestamptz and c.valid_until > ?::timestamptz"
              + " and c.valid_until <= ?::timestamptz + (?::int * interval '1 day')";
      case "EXPIRED" -> " and c.status = 'ACTIVE' and c.valid_until <= ?::timestamptz";
      case "NOT_YET_EFFECTIVE" -> " and c.status = 'ACTIVE' and c.valid_from > ?::timestamptz";
      case "REVOKED" -> " and c.status = 'REVOKED'";
      default -> "";
    };
  }

  private static List<Object> expiryBinds(String state, OffsetDateTime now) {
    return switch (state) {
      case "ACTIVE" -> List.of(now, now);
      case "EXPIRING" -> List.of(now, now, now, DEFAULT_EXPIRY_WARNING_DAYS);
      case "EXPIRED", "NOT_YET_EFFECTIVE" -> List.of(now);
      default -> List.of();
    };
  }

  /** 凭证档案的一行（把 lateral 出来的派生列翻译成 DTO）。 */
  private BrandCredentialRecord credentialRecord(Record row, OffsetDateTime now) {
    String status = row.get("status", String.class);
    OffsetDateTime validFrom = row.get("valid_from", OffsetDateTime.class);
    OffsetDateTime validUntil = row.get("valid_until", OffsetDateTime.class);
    String expiryState =
        classifyExpiry(status, validFrom, validUntil, now, DEFAULT_EXPIRY_WARNING_DAYS);
    Boolean passed = row.get("passed", Boolean.class);
    String verificationState =
        passed == null ? "UNVERIFIED" : verificationState(passed.booleanValue());
    LocalDate issuedOn = row.get("issued_on", LocalDate.class);
    OffsetDateTime lastVerifiedAt = row.get("evaluated_at", OffsetDateTime.class);
    UUID authorizationId = row.get("authorization_id", UUID.class);
    UUID brandId = row.get("brand_id", UUID.class);

    return new BrandCredentialRecord(
        row.get("id", UUID.class).toString(),
        authorizationId == null ? null : authorizationId.toString(),
        row.get("grantor", String.class),
        row.get("credential_no", String.class),
        row.get("credential_type", String.class),
        credentialTypeLabel(row.get("credential_type", String.class)),
        row.get("subject", String.class),
        brandId == null ? null : brandId.toString(),
        row.get("brand_name", String.class),
        validFrom,
        validUntil,
        issuedOn,
        status,
        statusLabel(status),
        row.get("revoked_at", OffsetDateTime.class),
        row.get("revoked_reason", String.class),
        expiryState,
        expiryStateLabel(expiryState),
        daysRemaining(validUntil, now),
        intOf(row, "current_version"),
        intOf(row, "document_count"),
        intOf(row, "signatory_count"),
        verificationState,
        verificationStateLabel(verificationState),
        lastVerifiedAt,
        row.get("conclusion", String.class),
        row.get("note", String.class),
        longOf(row, "version"),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /** 凭证档案详情（档案 + 签署方 + 文件版本 + 最近的核验记录）。 */
  public BrandCredentialDetail credential(String credentialId) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    UUID id = requireUuid(credentialId, "凭证 id");
    Record row = credentialRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    return detail(row, now, 20);
  }

  /** 组装详情。 */
  private BrandCredentialDetail detail(Record row, OffsetDateTime now, int verificationLimit) {
    UUID tenant = currentTenant();
    UUID id = row.get("id", UUID.class);
    BrandCredentialRecord record = credentialRecord(row, now);
    return new BrandCredentialDetail(
        record,
        signatories(tenant, id),
        documents(tenant, id),
        verifications(tenant, id, null, 0, verificationLimit).items());
  }

  /** 凭证档案主行的 SELECT（详情与列表共用同一组派生列）。 */
  private Record credentialRow(UUID tenant, UUID id) {
    return sql.fetchOne(
        """
        select c.id, c.authorization_id, c.grantor, c.credential_no, c.credential_type, c.subject,
               c.brand_id, c.brand_name, c.valid_from, c.valid_until, c.issued_on, c.status,
               c.revoked_at, c.revoked_reason, c.note, c.version, c.created_at, c.updated_at,
               floor(extract(epoch from (c.valid_until - ?::timestamptz))/86400)::int as days_to_expiry,
               coalesce(dv.current_version,0) as current_version,
               coalesce(dv.document_count,0) as document_count,
               coalesce(sv.signatory_count,0) as signatory_count,
               last_v.verification_id, last_v.conclusion, last_v.passed, last_v.evaluated_at
          from brand_cred_credential c
          left join lateral (
            select count(*) as document_count, coalesce(max(version_no),0) as current_version
              from brand_cred_document d
             where d.tenant_id = c.tenant_id and d.credential_id = c.id
          ) dv on true
          left join lateral (
            select count(*) as signatory_count
              from brand_cred_signatory s
             where s.tenant_id = c.tenant_id and s.credential_id = c.id
          ) sv on true
          left join lateral (
            select v.id as verification_id, v.conclusion, v.passed, v.evaluated_at
              from brand_cred_verification v
             where v.tenant_id = c.tenant_id and v.credential_id = c.id
             order by v.evaluated_at desc, v.id desc
             limit 1
          ) last_v on true
         where c.tenant_id = ? and c.id = ?
        """,
        dbNow(),
        tenant,
        id);
  }

  // ==================================================================
  // 凭证档案：录入 / 修改 / 撤销
  // ==================================================================

  /** 登记一份授权凭证。 */
  public BrandCredentialDetail create(BrandCredentialRegistrationInput input) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    String grantor = text(input.grantor());
    String credentialNo = text(input.credentialNo());
    if (grantor.isEmpty()) {
      throw unprocessable("授权方不能为空");
    }
    if (credentialNo.isEmpty()) {
      throw unprocessable("凭证编号不能为空");
    }
    String credentialType = normalizeCredentialType(input.credentialType());
    if (!CREDENTIAL_TYPES.contains(credentialType)) {
      throw unprocessable(
          "凭证类型只能是 "
              + String.join(" / ", CREDENTIAL_TYPES)
              + " 之一");
    }
    OffsetDateTime validFrom = parseTimestamp(input.validFrom(), false);
    OffsetDateTime validUntil = parseTimestamp(input.validUntil(), true);
    if (validFrom == null || validUntil == null) {
      throw unprocessable("有效期起点 / 终点必须是 ISO 时间或 YYYY-MM-DD");
    }
    if (!validUntil.isAfter(validFrom)) {
      throw unprocessable("有效期终点必须晚于起点");
    }

    UUID id = UUID.randomUUID();
    UUID authorizationId = parseUuid(input.authorizationId());
    if (authorizationId != null) {
      // 上游授权必须真的存在（同库读；不存在就是不存在，不能挂一个空引用）
      Object found =
          sql.fetchValue(
              "select count(*) from brand_authz_authorization where tenant_id = ? and id = ?",
              tenant,
              authorizationId);
      if (count(found) == 0L) {
        throw unprocessable("上游授权档案不存在（16.4.2.1 的授权 id 无效）");
      }
    }

    sql.execute(
        """
        insert into brand_cred_credential
          (id,tenant_id,authorization_id,grantor,credential_no,credential_type,subject,
           brand_id,brand_name,valid_from,valid_until,issued_on,status,revoked_at,revoked_reason,
           note,version,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?::date,'ACTIVE',null,'',?,0,now(),now())
        """,
        id,
        tenant,
        authorizationId,
        grantor,
        credentialNo,
        credentialType,
        text(input.subject()),
        parseUuid(input.brandId()),
        text(input.brandName()),
        validFrom,
        validUntil,
        parseDate(input.issuedOn()),
        text(input.note()));

    return credential(id.toString());
  }

  /** 修改一份授权凭证（**不**碰文件版本与核验记录 —— 那是追加式凭据）。 */
  public BrandCredentialDetail update(String credentialId, BrandCredentialRegistrationInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(credentialId, "凭证 id");
    Record existing = credentialRow(tenant, id);
    if (existing == null) {
      throw notFound();
    }
    if ("REVOKED".equals(existing.get("status", String.class))) {
      throw unprocessable("已撤销的凭证不能再修改（撤销是终态，录错了请另建一份）");
    }
    String credentialType = normalizeCredentialType(input.credentialType());
    if (!CREDENTIAL_TYPES.contains(credentialType)) {
      throw unprocessable("凭证类型只能是 " + String.join(" / ", CREDENTIAL_TYPES) + " 之一");
    }
    OffsetDateTime validFrom = parseTimestamp(input.validFrom(), false);
    OffsetDateTime validUntil = parseTimestamp(input.validUntil(), true);
    if (validFrom == null || validUntil == null) {
      throw unprocessable("有效期起点 / 终点必须是 ISO 时间或 YYYY-MM-DD");
    }
    if (!validUntil.isAfter(validFrom)) {
      throw unprocessable("有效期终点必须晚于起点");
    }
    UUID authorizationId = parseUuid(input.authorizationId());
    if (authorizationId != null) {
      Object found =
          sql.fetchValue(
              "select count(*) from brand_authz_authorization where tenant_id = ? and id = ?",
              tenant,
              authorizationId);
      if (count(found) == 0L) {
        throw unprocessable("上游授权档案不存在（16.4.2.1 的授权 id 无效）");
      }
    }
    sql.execute(
        """
        update brand_cred_credential
           set authorization_id = ?, grantor = ?, credential_no = ?, credential_type = ?,
               subject = ?, brand_id = ?, brand_name = ?,
               valid_from = ?::timestamptz, valid_until = ?::timestamptz, issued_on = ?::date,
               note = ?, version = version + 1, updated_at = now()
         where tenant_id = ? and id = ?
        """,
        authorizationId,
        text(input.grantor()),
        text(input.credentialNo()),
        credentialType,
        text(input.subject()),
        parseUuid(input.brandId()),
        text(input.brandName()),
        validFrom,
        validUntil,
        parseDate(input.issuedOn()),
        text(input.note()),
        tenant,
        id);
    return credential(credentialId);
  }

  /** 撤销一份凭证（**必须写明理由**；撤销是终态，不提供「恢复」）。 */
  public BrandCredentialDetail revoke(String credentialId, String reason) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(credentialId, "凭证 id");
    Record existing = credentialRow(tenant, id);
    if (existing == null) {
      throw notFound();
    }
    String why = text(reason);
    if (why.isEmpty()) {
      throw unprocessable("撤销凭证必须写明理由");
    }
    sql.execute(
        """
        update brand_cred_credential
           set status = 'REVOKED', revoked_at = now(), revoked_reason = ?,
               version = version + 1, updated_at = now()
         where tenant_id = ? and id = ?
        """,
        why,
        tenant,
        id);
    return credential(credentialId);
  }

  // ==================================================================
  // 签署方
  // ==================================================================

  /** 签署方清单。 */
  public List<BrandCredentialSignatoryRow> signatories(String credentialId) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(credentialId, "凭证 id");
    requireCredential(tenant, id);
    return signatories(tenant, id);
  }

  private List<BrandCredentialSignatoryRow> signatories(UUID tenant, UUID credentialId) {
    List<Record> rows =
        sql.fetch(
            """
            select id, credential_id, signatory_name, signatory_title, signatory_org, contact,
                   identity_verified, note, created_at
              from brand_cred_signatory
             where tenant_id = ? and credential_id = ?
             order by created_at, id
            """,
            tenant,
            credentialId);
    List<BrandCredentialSignatoryRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(
          new BrandCredentialSignatoryRow(
              row.get("id", UUID.class).toString(),
              row.get("credential_id", UUID.class).toString(),
              row.get("signatory_name", String.class),
              row.get("signatory_title", String.class),
              row.get("signatory_org", String.class),
              row.get("contact", String.class),
              Boolean.TRUE.equals(row.get("identity_verified", Boolean.class)),
              row.get("note", String.class),
              row.get("created_at", OffsetDateTime.class)));
    }
    return items;
  }

  /** 登记一个签署方。 */
  public BrandCredentialDetail addSignatory(
      String credentialId, BrandCredentialSignatoryInput input) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(credentialId, "凭证 id");
    requireCredential(tenant, id);
    String name = text(input.signatoryName());
    if (name.isEmpty()) {
      throw unprocessable("签署人姓名不能为空");
    }
    sql.execute(
        """
        insert into brand_cred_signatory
          (id,tenant_id,credential_id,signatory_name,signatory_title,signatory_org,contact,
           identity_verified,note,created_at,updated_at)
        values (?,?,?,?,?,?,?,?,?,now(),now())
        """,
        UUID.randomUUID(),
        tenant,
        id,
        name,
        text(input.signatoryTitle()),
        text(input.signatoryOrg()),
        text(input.contact()),
        Boolean.TRUE.equals(input.identityVerified()),
        text(input.note()));
    return credential(credentialId);
  }

  /** 移除一个签署方（凭证本身与文件版本、核验记录都不受影响）。 */
  public BrandCredentialDetail removeSignatory(String credentialId, String signatoryId) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(credentialId, "凭证 id");
    UUID sid = requireUuid(signatoryId, "签署方 id");
    requireCredential(tenant, id);
    int removed =
        sql.execute(
            "delete from brand_cred_signatory where tenant_id = ? and credential_id = ? and id = ?",
            tenant,
            id,
            sid);
    if (removed == 0) {
      throw notFound();
    }
    return credential(credentialId);
  }

  // ==================================================================
  // 凭证文件版本（只追加）
  // ==================================================================

  /** 凭证文件版本清单（新版本在前）。 */
  public List<BrandCredentialDocumentRow> documents(String credentialId) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(credentialId, "凭证 id");
    requireCredential(tenant, id);
    return documents(tenant, id);
  }

  private List<BrandCredentialDocumentRow> documents(UUID tenant, UUID credentialId) {
    List<Record> rows =
        sql.fetch(
            """
            select id, credential_id, version_no, file_name, content_type, size_bytes, storage_key,
                   sha256, uploaded_by, uploaded_at, note, created_at
              from brand_cred_document
             where tenant_id = ? and credential_id = ?
             order by version_no desc
            """,
            tenant,
            credentialId);
    List<BrandCredentialDocumentRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(documentRow(row));
    }
    return items;
  }

  private BrandCredentialDocumentRow documentRow(Record row) {
    UUID uploadedBy = row.get("uploaded_by", UUID.class);
    return new BrandCredentialDocumentRow(
        row.get("id", UUID.class).toString(),
        row.get("credential_id", UUID.class).toString(),
        intOf(row, "version_no"),
        row.get("file_name", String.class),
        row.get("content_type", String.class),
        row.get("size_bytes", BigDecimal.class),
        row.get("storage_key", String.class),
        row.get("sha256", String.class),
        uploadedBy == null ? null : uploadedBy.toString(),
        row.get("uploaded_at", OffsetDateTime.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  /**
   * 上传一份凭证文件（**新版本**，只追加）。
   *
   * <p>🔴 文件本体<b>真写进受管存储</b>（不是只存一个文件名），元数据入库，
   * 文件版本行<b>只追加</b>。🔴 登记时算一次 sha256 —— 这是以后每次核验的对照锚点。
   */
  public BrandCredentialDocumentRow uploadDocument(
      String credentialId,
      byte[] content,
      String fileName,
      String contentType,
      String note,
      UUID userId) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(credentialId, "凭证 id");
    requireCredential(tenant, id);
    if (content == null || content.length == 0) {
      throw unprocessable("凭证文件内容为空");
    }
    if (content.length > MAX_DOCUMENT_BYTES) {
      throw unprocessable("凭证文件超过单份上限 " + (MAX_DOCUMENT_BYTES / 1024 / 1024) + " MB");
    }
    String name = sanitizeFileName(fileName);
    UUID documentId = UUID.randomUUID();
    String key = storageKey(tenant, id, documentId, name);
    try {
      storage.put(key, content);
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "凭证文件写入受管存储失败：" + e.getMessage());
    }
    String digest = sha256Hex(content);
    OffsetDateTime uploadedAt = dbNow();

    // 版本号 = 现有最大版本 + 1（同一凭证内唯一；并发上传撞号由 DB 的唯一约束挡住）
    Object maxVersion =
        sql.fetchValue(
            "select coalesce(max(version_no),0) from brand_cred_document where tenant_id = ? and credential_id = ?",
            tenant,
            id);
    int versionNo = (int) count(maxVersion) + 1;

    sql.execute(
        """
        insert into brand_cred_document
          (id,tenant_id,credential_id,version_no,file_name,content_type,size_bytes,storage_key,
           sha256,uploaded_by,uploaded_at,note,created_at)
        values (?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,now())
        """,
        documentId,
        tenant,
        id,
        versionNo,
        name,
        text(contentType),
        new BigDecimal(content.length),
        key,
        digest,
        userId,
        uploadedAt,
        text(note));

    Record row =
        sql.fetchOne(
            """
            select id, credential_id, version_no, file_name, content_type, size_bytes, storage_key,
                   sha256, uploaded_by, uploaded_at, note, created_at
              from brand_cred_document
             where tenant_id = ? and id = ?
            """,
            tenant,
        documentId);
    return documentRow(row);
  }

  /** 上传凭证文件（JSON 形状：base64 本体）。 */
  public BrandCredentialDocumentRow uploadDocument(
      String credentialId, BrandCredentialDocumentInput input, UUID userId) {
    byte[] content;
    try {
      content = Base64.getDecoder().decode(text(input.contentBase64()));
    } catch (IllegalArgumentException e) {
      throw unprocessable("凭证文件内容不是合法的 base64");
    }
    return uploadDocument(
        credentialId, content, input.fileName(), input.contentType(), input.note(), userId);
  }

  /** 读回一份凭证文件的元数据（跨租户与不存在都返回逐字相同的 404）。 */
  public BrandCredentialDocumentRow document(String documentId) {
    UUID tenant = currentTenant();
    UUID id = requireUuid(documentId, "凭证文件 id");
    Record row =
        sql.fetchOne(
            """
            select id, credential_id, version_no, file_name, content_type, size_bytes, storage_key,
                   sha256, uploaded_by, uploaded_at, note, created_at
              from brand_cred_document
             where tenant_id = ? and id = ?
            """,
            tenant,
            id);
    if (row == null) {
      throw notFound();
    }
    return documentRow(row);
  }

  /** 读回凭证文件的本体（真的从受管存储里读，不是造一份假内容）。 */
  public byte[] documentContent(String documentId) {
    BrandCredentialDocumentRow row = document(documentId);
    try {
      return storage.read(row.storageKey());
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "凭证文件已不在受管存储中：" + e.getMessage());
    }
  }

  // ==================================================================
  // 核验（一键核验）
  // ==================================================================

  /**
   * 🔴 一键核验：把凭证文件从受管存储里读回来，**重算 sha256**，与登记摘要逐字比对。
   *
   * <p>核验会落一条**不可改写**的核验记录（追加式凭据）。
   */
  public BrandCredentialVerificationResult verify(String credentialId, UUID userId) {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();
    UUID id = requireUuid(credentialId, "凭证 id");
    Record row = credentialRow(tenant, id);
    if (row == null) {
      throw notFound();
    }
    BrandCredentialRecord record = credentialRecord(row, now);

    // ---- 取当前文件版本（version_no 最大的那一个）
    Record document =
        sql.fetchOne(
            """
            select id, credential_id, version_no, file_name, content_type, size_bytes, storage_key,
                   sha256, uploaded_by, uploaded_at, note, created_at
              from brand_cred_document
             where tenant_id = ? and credential_id = ?
             order by version_no desc
             limit 1
            """,
            tenant,
            id);

    List<BrandCredentialVerificationReason> reasons = new ArrayList<>();
    List<String> conclusions = new ArrayList<>();
    Map<String, Object> snapshot = new LinkedHashMap<>();

    String expectedDigest = document == null ? null : document.get("sha256", String.class);
    String actualDigest = null;
    Integer versionNo = document == null ? null : intOf(document, "version_no");

    // ---- 1. 撤销
    if ("REVOKED".equals(record.status())) {
      reasons.add(failure("REVOKED", "凭证已被撤销（撤销理由：" + text(record.revokedReason()) + "）"));
      conclusions.add("REVOKED");
    }

    // ---- 2. 文件本体与摘要
    if (document == null) {
      reasons.add(failure("NO_DOCUMENT", "这份凭证还没有任何文件版本，无法核验真伪。"));
      conclusions.add("NO_DOCUMENT");
    } else {
      byte[] content = null;
      try {
        content = storage.read(document.get("storage_key", String.class));
      } catch (IOException | RuntimeException e) {
        content = null;
      }
      if (content == null) {
        reasons.add(
            failure(
                "FILE_MISSING",
                "文件本体不在受管存储里（可能被人删了，或存储卷掉了）—— "
                    + "这不是「通过」，请重新上传原件。"));
        conclusions.add("FILE_MISSING");
      } else {
        actualDigest = sha256Hex(content);
        if (!actualDigest.equals(expectedDigest)) {
          reasons.add(
              failure(
                  "DIGEST_MISMATCH",
                  "重算摘要 " + actualDigest + " 与登记摘要 " + expectedDigest
                      + " 不一致 —— 文件在登记之后被改过或换过内容。"));
          conclusions.add("DIGEST_MISMATCH");
        }
      }
    }

    // ---- 3. 有效期（DB 时钟）
    if (now.isBefore(record.validFrom())) {
      reasons.add(failure("NOT_YET_EFFECTIVE", "凭证尚未生效（有效期从 " + record.validFrom() + " 开始）。"));
      conclusions.add("NOT_YET_EFFECTIVE");
    } else if (!now.isBefore(record.validUntil())) {
      reasons.add(failure("EXPIRED", "凭证已过期（有效期到 " + record.validUntil() + " 结束）。"));
      conclusions.add("EXPIRED");
    }

    // ---- 4. 签署方
    List<BrandCredentialSignatoryRow> signatories = signatories(tenant, id);
    long verifiedIdentities =
        signatories.stream().filter(BrandCredentialSignatoryRow::identityVerified).count();
    if (signatories.isEmpty()) {
      reasons.add(failure("SIGNATORY_INCOMPLETE", "这份凭证没有登记任何签署方 —— 谁签的必须写清楚。"));
      conclusions.add("SIGNATORY_INCOMPLETE");
    } else if (verifiedIdentities == 0) {
      // 🔴 WARN 而不是 FAIL：「姓名职务都登记了但还没人工核过身份」是常态，
      //    把它算成不通过会让「一键核验」天天红。但它必须被看见。
      reasons.add(
          new BrandCredentialVerificationReason(
              "SIGNATORY_UNVERIFIED",
              reasonLabel("SIGNATORY_UNVERIFIED"),
              "已登记 " + signatories.size() + " 个签署方，但还没有任何一个的身份被人工核验过。",
              "WARN"));
    }

    String conclusion = mostSevere(conclusions);
    boolean passed = passed(conclusion);

    snapshot.put("credentialId", id.toString());
    snapshot.put("credentialNo", record.credentialNo());
    snapshot.put("grantor", record.grantor());
    snapshot.put("authorizationId", record.authorizationId());
    snapshot.put("status", record.status());
    snapshot.put("validFrom", String.valueOf(record.validFrom()));
    snapshot.put("validUntil", String.valueOf(record.validUntil()));
    snapshot.put("documentId", document == null ? null : document.get("id", UUID.class).toString());
    snapshot.put("documentVersionNo", versionNo == null ? 0 : versionNo);
    snapshot.put("expectedSha256", expectedDigest);
    snapshot.put("actualSha256", actualDigest);
    snapshot.put("signatoryCount", signatories.size());
    snapshot.put("identityVerifiedCount", verifiedIdentities);
    snapshot.put("evaluatedAt", now.toString());
    snapshot.put("ruleVersion", CURRENT_RULE_VERSION);

    UUID verificationId = UUID.randomUUID();
    sql.execute(
        """
        insert into brand_cred_verification
          (id,tenant_id,credential_id,document_id,document_version_no,evaluated_at,rule_version,
           conclusion,severity,passed,reasons,snapshot,checked_by,created_at)
        values (?,?,?,?,?,?::timestamptz,?,?,?,?,?::jsonb,?::jsonb,?,now())
        """,
        verificationId,
        tenant,
        id,
        document == null ? null : document.get("id", UUID.class),
        versionNo == null ? 0 : versionNo,
        now,
        CURRENT_RULE_VERSION,
        conclusion,
        severity(conclusion),
        passed,
        writeJson(reasons),
        writeJson(snapshot),
        userId);

    // 核验后重新读一遍档案：最近核验状态变了，界面拿到的就是最新的
    Record refreshed = credentialRow(tenant, id);
    return new BrandCredentialVerificationResult(
        verificationId.toString(),
        credentialRecord(refreshed, now),
        document == null ? null : document.get("id", UUID.class).toString(),
        versionNo == null ? 0 : versionNo,
        now,
        CURRENT_RULE_VERSION,
        conclusion,
        conclusionLabel(conclusion),
        severity(conclusion),
        severityLabel(severity(conclusion)),
        passed,
        reasons,
        snapshot,
        userId == null ? null : userId.toString());
  }

  /** 批量核验（**一次最多 50 个**，逐条落凭据）。 */
  public BrandCredentialVerificationBatchResult verifyBatch(
      List<String> credentialIds, UUID userId) {
    List<String> ids = credentialIds == null ? List.of() : credentialIds;
    if (ids.isEmpty()) {
      throw unprocessable("批量核验至少要指定一个凭证 id");
    }
    if (ids.size() > MAX_BATCH_VERIFY) {
      throw unprocessable("一次批量核验最多 " + MAX_BATCH_VERIFY + " 个凭证");
    }
    List<BrandCredentialVerificationResult> results = new ArrayList<>();
    int verified = 0;
    int failed = 0;
    for (String id : ids) {
      BrandCredentialVerificationResult result = verify(id, userId);
      results.add(result);
      if (result.passed()) {
        verified++;
      } else {
        failed++;
      }
    }
    return new BrandCredentialVerificationBatchResult(results, verified, failed);
  }

  private BrandCredentialVerificationReason failure(String code, String message) {
    return new BrandCredentialVerificationReason(code, reasonLabel(code), message, "FAIL");
  }

  // ==================================================================
  // 核验记录
  // ==================================================================

  /** 核验记录历史（**真分页**）。 */
  public BrandCredentialVerificationPage verifications(
      String credentialId, String conclusion, int page, int size) {
    UUID tenant = currentTenant();
    UUID id = null;
    if (text(credentialId).length() > 0) {
      id = parseUuid(credentialId);
      if (id == null) {
        int limit = Math.max(1, Math.min(size, 200));
        return new BrandCredentialVerificationPage(List.of(), 0, Math.max(0, page), limit);
      }
    }
    return verifications(tenant, id, conclusion, page, size);
  }

  private BrandCredentialVerificationPage verifications(
      UUID tenant, UUID credentialId, String conclusion, int page, int size) {
    int limit = Math.max(1, Math.min(size, 200));
    int offset = Math.max(0, page) * limit;

    StringBuilder where = new StringBuilder(" where v.tenant_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    if (credentialId != null) {
      where.append(" and v.credential_id = ?");
      binds.add(credentialId);
    }
    if (text(conclusion).length() > 0) {
      where.append(" and v.conclusion = ?");
      binds.add(text(conclusion).toUpperCase(Locale.ROOT));
    }

    long total =
        count(sql.fetchValue("select count(*) from brand_cred_verification v" + where, binds.toArray()));

    List<Object> rows = new ArrayList<>(binds);
    rows.add(limit);
    rows.add(offset);
    List<Record> fetched =
        sql.fetch(
            """
            select v.id, v.credential_id, v.document_id, v.document_version_no, v.evaluated_at,
                   v.rule_version, v.conclusion, v.severity, v.passed, v.reasons, v.checked_by,
                   v.created_at, c.credential_no, c.grantor
              from brand_cred_verification v
              left join brand_cred_credential c
                on c.tenant_id = v.tenant_id and c.id = v.credential_id
            """
                + where
                + " order by v.evaluated_at desc, v.id desc limit ? offset ?",
            rows.toArray());

    List<BrandCredentialVerificationRow> items = new ArrayList<>();
    for (Record row : fetched) {
      items.add(verificationRow(row));
    }
    return new BrandCredentialVerificationPage(items, total, Math.max(0, page), limit);
  }

  private BrandCredentialVerificationRow verificationRow(Record row) {
    String conclusion = row.get("conclusion", String.class);
    String severity = row.get("severity", String.class);
    List<BrandCredentialVerificationReason> reasons = readReasons(row.get("reasons"));
    UUID checkedBy = row.get("checked_by", UUID.class);
    UUID documentId = row.get("document_id", UUID.class);
    return new BrandCredentialVerificationRow(
        row.get("id", UUID.class).toString(),
        row.get("credential_id", UUID.class).toString(),
        row.get("credential_no", String.class),
        row.get("grantor", String.class),
        documentId == null ? null : documentId.toString(),
        intOf(row, "document_version_no"),
        row.get("evaluated_at", OffsetDateTime.class),
        longOf(row, "rule_version"),
        conclusion,
        conclusionLabel(conclusion),
        severity,
        severityLabel(severity),
        Boolean.TRUE.equals(row.get("passed", Boolean.class)),
        reasons,
        reasons.size(),
        checkedBy == null ? null : checkedBy.toString(),
        row.get("created_at", OffsetDateTime.class));
  }

  @SuppressWarnings("unchecked")
  private List<BrandCredentialVerificationReason> readReasons(Object raw) {
    if (raw == null) {
      return List.of();
    }
    try {
      List<BrandCredentialVerificationReason> parsed =
          json.readValue(jsonText(raw), List.class);
      List<BrandCredentialVerificationReason> out = new ArrayList<>();
      for (Object item : parsed) {
        if (item instanceof Map<?, ?> map) {
          out.add(
              new BrandCredentialVerificationReason(
                  String.valueOf(map.get("code")),
                  String.valueOf(map.get("label")),
                  String.valueOf(map.get("message")),
                  String.valueOf(map.get("severity"))));
        }
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  // ==================================================================
  // 上游候选（16.4.2.1，同库只读）
  // ==================================================================

  /**
   * 上游 16.4.2.1 的授权档案候选（**同库只读**）。
   *
   * <p>🔴 只读：本模块**不写** {@code brand_authz_*} 的任何一张表，
   * {@code brand_authz_document} 的写入所有权也**不移交**。
   */
  public BrandCredentialAuthorizationOptionPage authorizationOptions(String keyword) {
    UUID tenant = currentTenant();
    StringBuilder where = new StringBuilder(" where tenant_id = ?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenant);
    if (text(keyword).length() > 0) {
      where.append(" and (grantor ilike '%' || ? || '%' or licensee_name ilike '%' || ? || '%'"
          + " or brand_name ilike '%' || ? || '%' or note ilike '%' || ? || '%')");
      String like = text(keyword);
      for (int i = 0; i < 4; i++) {
        binds.add(like);
      }
    }
    long total =
        count(
            sql.fetchValue(
                "select count(*) from brand_authz_authorization" + where, binds.toArray()));
    List<Object> rows = new ArrayList<>(binds);
    rows.add(100);
    List<Record> fetched =
        sql.fetch(
            """
            select id, brand_name, grantor, licensee_name, effective_from, effective_to
              from brand_authz_authorization
            """
                + where
                + " order by effective_to desc, id limit ?",
            rows.toArray());
    List<BrandCredentialAuthorizationOption> items = new ArrayList<>();
    for (Record row : fetched) {
      items.add(
          new BrandCredentialAuthorizationOption(
              row.get("id", UUID.class).toString(),
              row.get("brand_name", String.class),
              row.get("grantor", String.class),
              row.get("licensee_name", String.class),
              row.get("effective_from", OffsetDateTime.class),
              row.get("effective_to", OffsetDateTime.class)));
    }
    return new BrandCredentialAuthorizationOptionPage(
        items,
        total,
        "上游 16.4.2.1 品牌授权（**同库只读**）：本模块只读它的授权档案，"
            + "不写它的任何一张表；凭证通过逻辑 id 挂在授权下，不建外键。");
  }

  // ==================================================================
  // 概览
  // ==================================================================

  /** 概览（页面顶部的卡片）。 */
  public BrandCredentialOverview overview() {
    UUID tenant = currentTenant();
    OffsetDateTime now = dbNow();

    long credentialCount = count(sql.fetchValue("select count(*) from brand_cred_credential where tenant_id = ?", tenant));
    long revokedCount = count(sql.fetchValue("select count(*) from brand_cred_credential where tenant_id = ? and status = 'REVOKED'", tenant));
    long expiredCount =
        count(
            sql.fetchValue(
                "select count(*) from brand_cred_credential where tenant_id = ? and status = 'ACTIVE' and valid_until <= ?::timestamptz",
                tenant,
                now));
    long notYetEffectiveCount =
        count(
            sql.fetchValue(
                "select count(*) from brand_cred_credential where tenant_id = ? and status = 'ACTIVE' and valid_from > ?::timestamptz",
                tenant,
                now));
    long expiringCount =
        count(
            sql.fetchValue(
                "select count(*) from brand_cred_credential where tenant_id = ? and status = 'ACTIVE'"
                    + " and valid_from <= ?::timestamptz and valid_until > ?::timestamptz"
                    + " and valid_until <= ?::timestamptz + (?::int * interval '1 day')",
                tenant,
                now,
                now,
                now,
                DEFAULT_EXPIRY_WARNING_DAYS));
    long activeCount = credentialCount - revokedCount - expiredCount - notYetEffectiveCount;
    long documentCount =
        count(sql.fetchValue("select count(*) from brand_cred_document where tenant_id = ?", tenant));

    long unverifiedCount =
        count(
            sql.fetchValue(
                """
                select count(*) from brand_cred_credential c
                 where c.tenant_id = ?
                   and not exists (select 1 from brand_cred_verification v
                                    where v.tenant_id = c.tenant_id and v.credential_id = c.id)
                """,
                tenant));
    long failedCount =
        count(
            sql.fetchValue(
                """
                select count(*) from brand_cred_credential c
                 where c.tenant_id = ?
                   and exists (select 1 from brand_cred_verification v
                                where v.tenant_id = c.tenant_id and v.credential_id = c.id)
                   and (select v2.passed from brand_cred_verification v2
                         where v2.tenant_id = c.tenant_id and v2.credential_id = c.id
                         order by v2.evaluated_at desc, v2.id desc limit 1) is false
                """,
                tenant));
    long verifiedCount =
        count(
            sql.fetchValue(
                """
                select count(*) from brand_cred_credential c
                 where c.tenant_id = ?
                   and exists (select 1 from brand_cred_verification v
                                where v.tenant_id = c.tenant_id and v.credential_id = c.id)
                   and (select v2.passed from brand_cred_verification v2
                         where v2.tenant_id = c.tenant_id and v2.credential_id = c.id
                         order by v2.evaluated_at desc, v2.id desc limit 1) is true
                """,
                tenant));

    return new BrandCredentialOverview(
        credentialCount,
        Math.max(0, activeCount),
        expiringCount,
        expiredCount,
        revokedCount,
        verifiedCount,
        failedCount,
        unverifiedCount,
        documentCount);
  }

  // ==================================================================
  // 内部
  // ==================================================================

  private void requireCredential(UUID tenant, UUID id) {
    Object found =
        sql.fetchValue(
            "select count(*) from brand_cred_credential where tenant_id = ? and id = ?", tenant, id);
    if (count(found) == 0L) {
      throw notFound();
    }
  }

  // ==================================================================
  // 文案
  // ==================================================================

  /** 凭证类型的人话。 */
  static String credentialTypeLabel(String type) {
    return switch (type == null ? "" : type) {
      case "BRAND_LICENSE" -> "品牌授权书";
      case "DISTRIBUTOR_AGREEMENT" -> "经销协议";
      case "TRADEMARK_CERTIFICATE" -> "商标注册证";
      case "CERTIFICATE_OF_ORIGIN" -> "原产地证";
      case "AUTHORIZATION_LETTER" -> "授权函";
      case "OTHER" -> "其它凭证";
      default -> type == null ? "" : type;
    };
  }

  /** 状态的人话。 */
  static String statusLabel(String status) {
    return "REVOKED".equals(status) ? "已撤销" : "有效";
  }

  /** 到期状态的人话。 */
  static String expiryStateLabel(String state) {
    return switch (state == null ? "" : state) {
      case "ACTIVE" -> "生效中";
      case "EXPIRING" -> "即将到期";
      case "EXPIRED" -> "已过期";
      case "NOT_YET_EFFECTIVE" -> "尚未生效";
      case "REVOKED" -> "已撤销";
      default -> "";
    };
  }

  /** 最近核验状态的人话。 */
  static String verificationStateLabel(String state) {
    return switch (state == null ? "" : state) {
      case "UNVERIFIED" -> "从未核验";
      case "VERIFIED" -> "核验通过";
      case "FAILED" -> "核验未通过";
      default -> "";
    };
  }

  /** 核验结论的人话。 */
  static String conclusionLabel(String conclusion) {
    return switch (conclusion == null ? "" : conclusion) {
      case "VERIFIED" -> "核验通过";
      case "DIGEST_MISMATCH" -> "文件摘要对不上";
      case "FILE_MISSING" -> "文件不在受管存储";
      case "EXPIRED" -> "凭证已过期";
      case "NOT_YET_EFFECTIVE" -> "凭证尚未生效";
      case "SIGNATORY_INCOMPLETE" -> "没有登记签署方";
      case "NO_DOCUMENT" -> "凭证还没有文件";
      case "REVOKED" -> "凭证已被撤销";
      default -> "";
    };
  }

  /** 核验分级的人话。 */
  static String severityLabel(String severity) {
    return "PASS".equals(severity) ? "通过" : "不通过";
  }
}
