package com.starcon.platform;

import static com.starcon.platform.AppCredentialModels.*;

import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.1.1.1 应用与凭证的服务层。
 *
 * <p>数据访问一律走 **jOOQ 显式 SQL**（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）。
 * 租户上下文由 {@link AccessService#run} 绑定 —— 本类**不**读任何请求头里的租户。
 *
 * <p><b>三条纪律：</b>
 *
 * <ol>
 *   <li><b>明文只在生成那一次出现</b>：{@link #issueCredential} / {@link #rotateCredential} 生成后
 *       把明文放进响应返回，之后库里只剩摘要，**任何读路径都取不回明文**。
 *   <li><b>写入归属会话租户</b>：所有 INSERT 的 {@code tenant_id} 取自已认证会话，
 *       不接受请求参数传入；RLS 的 WITH CHECK 再兜一层。
 *   <li><b>审计只追加</b>：每次写动作都追加一行 {@code platform_app_audit_event}。
 * </ol>
 */
@Service
public class AppCredentialService {

  /** 配额窗口类型（枚举序与迁移里的 CHECK 同口径）。 */
  static final List<QuotaWindow> WINDOWS =
      List.of(QuotaWindow.SECOND, QuotaWindow.MINUTE, QuotaWindow.HOUR, QuotaWindow.DAY, QuotaWindow.MONTH);

  private static final int MAX_PAGE_SIZE = 100;
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder();

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final String internalKey;

  public AppCredentialService(
      DSLContext sql,
      TenantTransactions tenants,
      @Value("${INTERNAL_KEY}") String internalKey) {
    this.sql = sql;
    this.tenants = tenants;
    this.internalKey = internalKey;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static String required(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(!trimmed.isEmpty(), label + "不能为空");
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /** 可选字段：空串合法，超长不合法（超长要在服务层拦下来，而不是等数据库报 409）。 */
  private static String optional(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /** 32 位十六进制随机串（128 bit 熵）—— API Key 与 Secret 的随机部分。 */
  private static String randomHex(int bytes) {
    var buffer = new byte[bytes];
    RANDOM.nextBytes(buffer);
    return HexFormat.of().formatHex(buffer);
  }

  /** SHA-256 十六进制摘要：**只**用于按值定位 API Key（BCrypt 加盐，无法定位）。 */
  static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  // ================================================================ 能力声明

  /** 18.1.1.1 的能力声明：逐维说清「有没有、依据是什么、缺了会怎样」。 */
  public IntegrationAppCapabilities capabilities() {
    var capabilities =
        List.of(
            new IntegrationAppCapability(
                "18.1.1.1",
                "应用注册与档案",
                true,
                "POST /platform/apps（真分页 + 真筛选的列表端点，jOOQ 显式 SQL）",
                "",
                ""),
            new IntegrationAppCapability(
                "18.1.1.1",
                "API Key / Secret 生成与轮换",
                true,
                "POST /platform/apps/{appId}/credentials 与 .../credentials/rotate；明文只返回一次，库里只存 BCrypt 摘要",
                "",
                ""),
            new IntegrationAppCapability(
                "18.1.1.1",
                "授权范围（资源 × 操作）",
                true,
                "platform_app_scope 表 + 三个端点（列表 / 授权 / 撤销授权）",
                "",
                ""),
            new IntegrationAppCapability(
                "18.1.1.1",
                "IP 白名单",
                true,
                "platform_app_ip_allowlist 表 + 三个端点（列表 / 新增 / 删除），CIDR 在服务层校验",
                "",
                ""),
            new IntegrationAppCapability(
                "18.1.1.1",
                "调用配额",
                true,
                "platform_app_quota（配置）+ platform_app_quota_usage（窗口用量）+ 额度判定端点",
                "",
                ""),
            new IntegrationAppCapability(
                "18.1.1.1",
                "访问审计与一键吊销",
                true,
                "platform_app_audit_event（只追加，触发器挡 UPDATE/DELETE）+ 吊销端点",
                "",
                ""),
            new IntegrationAppCapability(
                "18.1.1.1",
                "调用用量的**采集器**（谁在替应用记账）",
                false,
                "本仓没有 API 网关服务（卷 18 的网关尚未交付）",
                "18.1.1.3",
                "用量只能由内部密钥路径（POST /platform/app-quota-usages）手工回报；没有采集器时窗口用量不会自己长"),
            new IntegrationAppCapability(
                "18.1.1.1",
                "**跨租户**按 API Key 定位的网关入口",
                false,
                "跨租户定位需要平台级只读通道；本模块的读路径一律租户内",
                "18.1.1.3",
                "网关拿到一个 API Key 后自己定位租户的能力尚未提供；本模块提供的是租户内的凭证自检端点"));
    var gaps =
        List.of(
            new IntegrationAppGap(
                "18.1.1.2",
                "接口目录",
                "platform",
                false,
                "下游",
                "对外接口的目录与文档由本模块下游提供；本模块只管「谁能调」，不管「有哪些接口」"),
            new IntegrationAppGap(
                "18.1.1.3",
                "调用监控",
                "platform",
                false,
                "下游",
                "QPS / 错误率 / 限流命中 / 慢调用的统计与告警归 18.1.1.3；本模块只提供配额配置与窗口用量"),
            new IntegrationAppGap(
                "18.2.1.1",
                "财务软件对接",
                "platform + finance",
                false,
                "跨卷",
                "外部系统对接走对方官方 API；本模块**不提供**任何模拟登录能力（卷 18 合规红线）"));
    return new IntegrationAppCapabilities(capabilities, gaps);
  }

  /** 筛选项下拉（词表来自本模块自己；资源码来自**真实数据**）。 */
  public IntegrationAppOptions options() {
    var appTypes =
        List.of(
            new IntegrationAppOption(
                "INTERNAL", "平台自研", "我们自己写的那一套客户端，走内部网络与内部身份"),
            new IntegrationAppOption("THIRD_PARTY", "第三方系统", "对方提供 SDK 或 HTTP 客户端"),
            new IntegrationAppOption("PARTNER", "生态伙伴", "ISV / 服务商代客接入"));
    var statuses =
        List.of(
            new IntegrationAppOption("ACTIVE", "正常", "可以签发凭证，也可以调用"),
            new IntegrationAppOption("SUSPENDED", "暂停", "档案保留，签发与调用都应被拒绝"),
            new IntegrationAppOption("REVOKED", "已吊销", "该应用全部凭证同时失效，终态"));
    var actions =
        List.of(
            new IntegrationAppOption("READ", "读", "可访问该资源的读接口"),
            new IntegrationAppOption("WRITE", "写", "可访问该资源的写接口"));
    var windows =
        List.of(
            new IntegrationAppOption("SECOND", "秒", "按 UTC 整秒切窗"),
            new IntegrationAppOption("MINUTE", "分钟", "按 UTC 整分切窗"),
            new IntegrationAppOption("HOUR", "小时", "按 UTC 整点切窗"),
            new IntegrationAppOption("DAY", "天", "按 UTC 零点切窗"),
            new IntegrationAppOption("MONTH", "月", "按 UTC 月初切窗"));
    var resources =
        sql.fetch("select distinct resource_code from platform_app_scope order by resource_code")
            .map(r -> r.get(0, String.class));
    return new IntegrationAppOptions(appTypes, statuses, actions, windows, resources);
  }

  // ================================================================ 应用档案

  /** 应用列表（真分页 + 真筛选）。 */
  public IntegrationAppPage apps(
      String keyword, String appType, String status, int page, int size) {
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (a.app_code ilike ? or a.app_name ilike ?)");
      var like = "%" + keyword.trim() + "%";
      params.add(like);
      params.add(like);
    }
    if (appType != null && !appType.isBlank()) {
      where.append(" and a.app_type = ?");
      params.add(appType.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and a.status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    var total =
        sql.fetchOne("select count(*) from platform_app a" + where, params.toArray())
            .get(0, Long.class);
    var active =
        sql.fetchOne(
                "select count(*) from platform_app a" + where + " and a.status = 'ACTIVE'",
                params.toArray())
            .get(0, Long.class);
    var revoked =
        sql.fetchOne(
                "select count(*) from platform_app a" + where + " and a.status = 'REVOKED'",
                params.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            appSelect() + where + " order by a.created_at desc, a.id limit ? offset ?",
            params.toArray().length == 0
                ? new Object[] {size2, (long) page2 * size2}
                : append(params, size2, (long) page2 * size2));
    return new IntegrationAppPage(
        rows.map(AppCredentialService::toAppRow), page2, size2, total, active, revoked);
  }

  /** 应用概览（几个数）。 */
  public IntegrationAppOverview overview() {
    long total = count("select count(*) from platform_app");
    long active = count("select count(*) from platform_app where status = 'ACTIVE'");
    long revoked = count("select count(*) from platform_app where status = 'REVOKED'");
    long credentials =
        count("select count(*) from platform_app_credential where status = 'ACTIVE'");
    long withIp =
        count(
            "select count(distinct app_id) from platform_app_ip_allowlist");
    long withQuota = count("select count(distinct app_id) from platform_app_quota");
    return new IntegrationAppOverview(
        total, active, revoked, credentials, withIp, withQuota, OffsetDateTime.now(ZoneOffset.UTC));
  }

  /** 应用档案详情（五块拼起来）。 */
  public IntegrationAppDetail detail(UUID appId, boolean auditAccess) {
    var app = requireApp(appId);
    if (auditAccess) {
      // 「访问有审计」：读到凭证元数据这件事本身要留痕。
      audit(appId, null, "CREDENTIAL_ACCESSED", app.ownerUserId(), "USER", "查看了应用凭证档案");
    }
    return new IntegrationAppDetail(
        app,
        credentials(appId),
        scopes(appId),
        ipAllowlist(appId),
        quotas(appId),
        quotaStatus(appId, OffsetDateTime.now(ZoneOffset.UTC)));
  }

  /** 注册应用。 */
  public IntegrationAppRow register(IntegrationAppInput input, AccessService.Access actor) {
    var code = required(input.appCode(), "应用标识", 64);
    var name = required(input.appName(), "应用名称", 120);
    var type = input.appType() == null || input.appType().isBlank() ? "THIRD_PARTY" : input.appType().trim().toUpperCase(Locale.ROOT);
    check(
        List.of("INTERNAL", "THIRD_PARTY", "PARTNER").contains(type),
        "应用类型只能是 INTERNAL / THIRD_PARTY / PARTNER");
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = UUID.randomUUID();
    var description = optional(input.description(), "应用说明", 500);
    var contactName = optional(input.contactName(), "联系人", 80);
    var contactEmail = optional(input.contactEmail(), "联系邮箱", 160);
    sql.execute(
        "insert into platform_app(id,tenant_id,app_code,app_name,app_type,description,"
            + " owner_user_id,contact_name,contact_email,status,created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,'ACTIVE',?,?::timestamptz,?::timestamptz)",
        id,
        actor.tenantId(),
        code,
        name,
        type,
        description,
        actor.userId(),
        contactName,
        contactEmail,
        actor.userId(),
        now,
        now);
    audit(id, null, "APP_REGISTERED", actor.userId(), "USER", "注册应用 " + code);
    return requireApp(id);
  }

  /**
   * 吊销应用（**一键吊销**：该应用全部凭证同时失效）。
   *
   * <p>幂等：重复吊销不报错，状态与时间戳不漂移。
   */
  public IntegrationAppRow revokeApp(UUID appId, String reason, AccessService.Access actor) {
    var app = requireApp(appId);
    if ("REVOKED".equals(app.status())) {
      // 已经吊销过：不重复写时间戳（重复吊销不该让审计看起来像发生了两次）。
      return app;
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var text = reason == null ? "" : reason.trim();
    sql.execute(
        "update platform_app set status = 'REVOKED', revoked_at = ?::timestamptz,"
            + " revoke_reason = ?, updated_at = ?::timestamptz where id = ?",
        now,
        text,
        now,
        appId);
    sql.execute(
        "update platform_app_credential set status = 'REVOKED', revoked_at = ?::timestamptz,"
            + " revoke_reason = ? where app_id = ? and status = 'ACTIVE'",
        now,
        text.isBlank() ? "应用已吊销" : text,
        appId);
    audit(appId, null, "APP_REVOKED", actor.userId(), "USER", text);
    return requireApp(appId);
  }

  // ================================================================ 凭证

  /** 凭证列表（新到旧）。 */
  public List<IntegrationCredentialRow> credentials(UUID appId) {
    requireApp(appId);
    return sql.fetch(
            "select c.id,c.app_id,c.version,c.status,c.api_key_prefix,c.secret_hint,c.issued_at,"
                + " c.expires_at,c.rotated_from_id,c.revoked_at,c.revoke_reason,c.last_used_at,c.created_at,"
                + " (select max(c2.issued_at) from platform_app_credential c2"
                + "   where c2.tenant_id = c.tenant_id and c2.rotated_from_id = c.id) as rotated_at"
                + " from platform_app_credential c where c.app_id = ? order by c.version desc",
            appId)
        .map(AppCredentialService::toCredentialRow);
  }

  /**
   * 生成凭证（**明文只在这里返回一次**）。
   *
   * <p>🔴 同一应用**已有生效中的凭证时不允许再生成**：那是「轮换」的语义。
   * 把两件事合成一件，会出现「以为在生成、其实把旧的顶掉了」—— 旧摘要失效而没人知道。
   */
  public IntegrationCredentialIssued issueCredential(UUID appId, AccessService.Access actor) {
    var app = requireApp(appId);
    check(
        !"REVOKED".equals(app.status()),
        "应用已吊销，不能再签发凭证。请先注册新的应用。");
    var active =
        sql.fetchOne(
                "select count(*) from platform_app_credential where app_id = ? and status = 'ACTIVE'",
                appId)
            .get(0, Long.class);
    check(
        active == 0,
        "该应用已有生效中的凭证。要换一套请用「轮换」—— 轮换会让旧摘要立即失效。");
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var version =
        sql.fetchOne(
                "select coalesce(max(version),0) from platform_app_credential where app_id = ?", appId)
            .get(0, Integer.class)
            + 1;
    var issued = insertCredential(appId, version, now, actor.userId(), null);
    audit(appId, issued.credential().id(), "CREDENTIAL_GENERATED", actor.userId(), "USER", "签发第 " + version + " 版凭证");
    return issued;
  }

  /**
   * 轮换凭证：生成新明文 + 旧摘要失效。
   *
   * <p>旧凭证**不删**：状态收口成 ROTATED，留作「哪一版在什么时间点有效过」的痕迹。
   */
  public IntegrationCredentialIssued rotateCredential(UUID appId, AccessService.Access actor) {
    var app = requireApp(appId);
    check(!"REVOKED".equals(app.status()), "应用已吊销，不能轮换凭证。");
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var current =
        sql.fetchOne(
            "select id from platform_app_credential where app_id = ? and status = 'ACTIVE'", appId);
    var version =
        sql.fetchOne(
                "select coalesce(max(version),0) from platform_app_credential where app_id = ?", appId)
            .get(0, Integer.class)
            + 1;
    var issued =
        insertCredential(
            appId, version, now, actor.userId(), current == null ? null : current.get("id", UUID.class));
    if (current != null) {
      sql.execute(
          "update platform_app_credential set status = 'ROTATED' where id = ?",
          current.get("id", UUID.class));
    }
    audit(appId, issued.credential().id(), "CREDENTIAL_ROTATED", actor.userId(), "USER", "轮换为第 " + version + " 版凭证，旧版摘要已失效");
    return issued;
  }

  /** 吊销一个凭证（泄露时的一键止血）。幂等。 */
  public IntegrationCredentialRow revokeCredential(
      UUID appId, UUID credentialId, String reason, AccessService.Access actor) {
    requireApp(appId);
    var row =
        sql.fetchOne(
            "select id, status, revoked_at, revoke_reason from platform_app_credential where id = ?",
            credentialId);
    check(row != null, "凭证不存在");
    if ("REVOKED".equals(row.get("status", String.class))) {
      return credential(credentialId);
    }
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var text = reason == null ? "" : reason.trim();
    sql.execute(
        "update platform_app_credential set status = 'REVOKED', revoked_at = ?::timestamptz,"
            + " revoke_reason = ? where id = ?",
        now,
        text,
        credentialId);
    audit(appId, credentialId, "CREDENTIAL_REVOKED", actor.userId(), "USER", text);
    return credential(credentialId);
  }

  /** 插入一行凭证（明文在这里生成，明文只往响应里走）。 */
  private IntegrationCredentialIssued insertCredential(
      UUID appId, int version, OffsetDateTime now, UUID actorUserId, UUID rotatedFromId) {
    var apiKey = "sk_" + randomHex(20);
    var secret = "ss_" + randomHex(20);
    var id = UUID.randomUUID();
    sql.execute(
        "insert into platform_app_credential(id,tenant_id,app_id,version,status,api_key_prefix,"
            + " api_key_digest,api_key_lookup,secret_hint,secret_digest,issued_at,rotated_from_id,"
            + " created_by,created_at)"
            + " values(?,?,?,?,'ACTIVE',?,?,?,?,?,?::timestamptz,?,?,?::timestamptz)",
        id,
        currentTenant(),
        appId,
        version,
        apiKey.substring(0, 11),
        PASSWORDS.encode(apiKey),
        sha256Hex(apiKey),
        secret.substring(secret.length() - 4),
        PASSWORDS.encode(secret),
        now,
        rotatedFromId,
        actorUserId,
        now);
    return new IntegrationCredentialIssued(
        credential(id),
        apiKey,
        secret,
        "这是唯一一次显示 API Key 与 Secret 的机会。离开这个页面之后无法再次查看，"
            + "请立刻保存到对接方的密钥库里；泄露后请用「吊销」立即止血。");
  }

  // ================================================================ 授权范围

  /** 授权范围列表。 */
  public List<IntegrationScopeRow> scopes(UUID appId) {
    requireApp(appId);
    return sql.fetch(
            "select id, app_id, resource_code, action_code, granted_at, granted_by"
                + " from platform_app_scope where app_id = ? order by resource_code, action_code",
            appId)
        .map(
            r ->
                new IntegrationScopeRow(
                    r.get("id", UUID.class),
                    r.get("app_id", UUID.class),
                    r.get("resource_code", String.class),
                    r.get("action_code", String.class),
                    actionLabel(r.get("action_code", String.class)),
                    r.get("granted_at", OffsetDateTime.class),
                    r.get("granted_by", UUID.class)));
  }

  /**
   * 授权（幂等）：同一 (应用, 资源, 操作) 重复授权**不叠加**，只是把时间刷新。
   */
  public IntegrationScopeRow grantScope(
      UUID appId, IntegrationScopeInput input, AccessService.Access actor) {
    requireApp(appId);
    var resource = required(input.resourceCode(), "资源码", 64);
    var action = ScopeAction.parse(input.actionCode());
    check(action != null, "操作只能是 READ 或 WRITE");
    var existing =
        sql.fetchOne(
            "select id from platform_app_scope where app_id = ? and resource_code = ? and action_code = ?",
            appId,
            resource,
            action.name());
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    if (existing != null) {
      sql.execute(
          "update platform_app_scope set granted_at = ?::timestamptz where id = ?", now, existing.get("id", UUID.class));
      audit(appId, null, "SCOPE_CHANGED", actor.userId(), "USER", "重新授权 " + resource + ":" + action.name());
      return scope(existing.get("id", UUID.class));
    }
    var id = UUID.randomUUID();
    sql.execute(
        "insert into platform_app_scope(id,tenant_id,app_id,resource_code,action_code,granted_at,granted_by)"
            + " values(?,?,?,?,?,?::timestamptz,?)",
        id,
        currentTenant(),
        appId,
        resource,
        action.name(),
        now,
        actor.userId());
    audit(appId, null, "SCOPE_CHANGED", actor.userId(), "USER", "授权 " + resource + ":" + action.name());
    return scope(id);
  }

  /** 撤销一条授权。 */
  public void revokeScope(UUID appId, UUID scopeId, AccessService.Access actor) {
    requireApp(appId);
    var row =
        sql.fetchOne(
            "select resource_code, action_code from platform_app_scope where id = ?", scopeId);
    check(row != null, "授权不存在");
    sql.execute("delete from platform_app_scope where id = ?", scopeId);
    audit(
        appId,
        null,
        "SCOPE_CHANGED",
        actor.userId(),
        "USER",
        "撤销授权 " + row.get("resource_code", String.class) + ":" + row.get("action_code", String.class));
  }

  // ================================================================ IP 白名单

  /** IP 白名单列表。 */
  public List<IntegrationIpAllowlistRow> ipAllowlist(UUID appId) {
    requireApp(appId);
    return sql.fetch(
            "select id, app_id, cidr, note, created_at, created_by from platform_app_ip_allowlist"
                + " where app_id = ? order by cidr",
            appId)
        .map(
            r ->
                new IntegrationIpAllowlistRow(
                    r.get("id", UUID.class),
                    r.get("app_id", UUID.class),
                    r.get("cidr", String.class),
                    r.get("note", String.class),
                    r.get("created_at", OffsetDateTime.class),
                    r.get("created_by", UUID.class)));
  }

  /** 新增一条白名单。 */
  public IntegrationIpAllowlistRow addIpAllowlist(
      UUID appId, IntegrationIpAllowlistInput input, AccessService.Access actor) {
    requireApp(appId);
    var cidr = required(input.cidr(), "IP 白名单", 64);
    check(Cidrs.isValidCidr(cidr), "IP 白名单必须是合法的 CIDR，例如 203.0.113.0/24 或 2001:db8::/32");
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var id = UUID.randomUUID();
    sql.execute(
        "insert into platform_app_ip_allowlist(id,tenant_id,app_id,cidr,note,created_at,created_by)"
            + " values(?,?,?,?,?,?::timestamptz,?)",
        id,
        currentTenant(),
        appId,
        cidr,
        input.note() == null ? "" : input.note().trim(),
        now,
        actor.userId());
    audit(appId, null, "IP_ALLOWLIST_CHANGED", actor.userId(), "USER", "新增白名单 " + cidr);
    return ipEntry(id);
  }

  /** 删除一条白名单。 */
  public void removeIpAllowlist(UUID appId, UUID entryId, AccessService.Access actor) {
    requireApp(appId);
    var row = sql.fetchOne("select cidr from platform_app_ip_allowlist where id = ?", entryId);
    check(row != null, "白名单条目不存在");
    sql.execute("delete from platform_app_ip_allowlist where id = ?", entryId);
    audit(appId, null, "IP_ALLOWLIST_CHANGED", actor.userId(), "USER", "删除白名单 " + row.get("cidr", String.class));
  }

  // ================================================================ 调用配额

  /** 配额配置列表。 */
  public List<IntegrationQuotaRow> quotas(UUID appId) {
    requireApp(appId);
    return sql.fetch(
            "select id, app_id, window_type, limit_count, enabled, created_at, updated_at"
                + " from platform_app_quota where app_id = ? order by window_type",
            appId)
        .map(
            r ->
                new IntegrationQuotaRow(
                    r.get("id", UUID.class),
                    r.get("app_id", UUID.class),
                    r.get("window_type", String.class),
                    windowLabel(r.get("window_type", String.class)),
                    r.get("limit_count", BigDecimal.class),
                    r.get("enabled", Boolean.class),
                    r.get("created_at", OffsetDateTime.class),
                    r.get("updated_at", OffsetDateTime.class)));
  }

  /** 设置配额（同一窗口类型重复设置 = 覆盖，不是叠加）。 */
  public IntegrationQuotaRow setQuota(UUID appId, IntegrationQuotaInput input, AccessService.Access actor) {
    requireApp(appId);
    var window = QuotaWindow.parse(input.windowType());
    check(window != null, "配额窗口只能是 SECOND / MINUTE / HOUR / DAY / MONTH");
    check(input.limitCount() != null, "配额上限不能为空");
    check(input.limitCount().signum() >= 0, "配额上限不能是负数");
    check(input.limitCount().scale() <= 6, "配额上限最多 6 位小数");
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    var enabled = input.enabled() == null || input.enabled();
    var existing =
        sql.fetchOne(
            "select id from platform_app_quota where app_id = ? and window_type = ?", appId, window.name());
    if (existing != null) {
      sql.execute(
          "update platform_app_quota set limit_count = ?, enabled = ?, updated_at = ?::timestamptz where id = ?",
          input.limitCount(),
          enabled,
          now,
          existing.get("id", UUID.class));
      audit(appId, null, "QUOTA_CHANGED", actor.userId(), "USER", "调整 " + window.name() + " 配额为 " + input.limitCount());
      return quota(existing.get("id", UUID.class));
    }
    var id = UUID.randomUUID();
    sql.execute(
        "insert into platform_app_quota(id,tenant_id,app_id,window_type,limit_count,enabled,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        appId,
        window.name(),
        input.limitCount(),
        enabled,
        now,
        now);
    audit(appId, null, "QUOTA_CHANGED", actor.userId(), "USER", "设置 " + window.name() + " 配额为 " + input.limitCount());
    return quota(id);
  }

  /**
   * 配额判定（每个窗口类型一行）。
   *
   * <p>🔴 **没配过的窗口 = 不限**（{@code configured=false}、{@code decision=NOT_CONFIGURED}），
   * 不是「限 0 次」。系统**零预置**：不替用户默认一个「1000 次/天」。
   */
  public List<IntegrationQuotaStatusRow> quotaStatus(UUID appId, OffsetDateTime at) {
    requireApp(appId);
    var configured = new java.util.HashMap<String, IntegrationQuotaRow>();
    for (var q : quotas(appId)) configured.put(q.windowType(), q);
    var result = new ArrayList<IntegrationQuotaStatusRow>();
    for (var window : WINDOWS) {
      var row = configured.get(window.name());
      var start = windowStart(window, at);
      var end = windowEnd(window, start);
      if (row == null) {
        result.add(
            new IntegrationQuotaStatusRow(
                window.name(),
                window.label(),
                null,
                false,
                false,
                BigDecimal.ZERO,
                null,
                false,
                start,
                end,
                "NOT_CONFIGURED",
                "未配置该窗口的配额 = 不限，不是限 0 次"));
        continue;
      }
      var usage =
          sql.fetchOne(
              "select used_count from platform_app_quota_usage where app_id = ? and window_type = ?"
                  + " and window_started_at = ?::timestamptz",
              appId,
              window.name(),
              start);
      var used = usage == null ? BigDecimal.ZERO : usage.get("used_count", BigDecimal.class);
      var exceeded = row.enabled() && used.compareTo(row.limitCount()) >= 0;
      var remaining =
          row.limitCount().subtract(used).max(BigDecimal.ZERO).setScale(6, java.math.RoundingMode.HALF_UP);
      result.add(
          new IntegrationQuotaStatusRow(
              window.name(),
              window.label(),
              row.limitCount(),
              true,
              row.enabled(),
              used,
              remaining,
              exceeded,
              start,
              end,
              !row.enabled() ? "DISABLED" : (exceeded ? "EXCEEDED" : "ALLOW"),
              !row.enabled()
                  ? "该窗口的配额已停用"
                  : (exceeded ? "已超出该窗口的配额上限" : "该窗口仍有额度")));
    }
    return result;
  }

  // ================================================================ 审计

  /** 审计留痕（真分页，新到旧）。 */
  public IntegrationAuditEventPage auditEvents(UUID appId, int page, int size) {
    requireApp(appId);
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var total =
        sql.fetchOne("select count(*) from platform_app_audit_event where app_id = ?", appId)
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select id, app_id, credential_id, event_type, actor_user_id, actor_kind, detail, created_at"
                + " from platform_app_audit_event where app_id = ?"
                + " order by created_at desc, id limit ? offset ?",
            appId,
            size2,
            (long) page2 * size2);
    return new IntegrationAuditEventPage(
        rows.map(
            r ->
                new IntegrationAuditEventRow(
                    r.get("id", UUID.class),
                    r.get("app_id", UUID.class),
                    r.get("credential_id", UUID.class),
                    r.get("event_type", String.class),
                    eventLabel(r.get("event_type", String.class)),
                    r.get("actor_user_id", UUID.class),
                    r.get("actor_kind", String.class),
                    r.get("detail", String.class),
                    r.get("created_at", OffsetDateTime.class))),
        page2,
        size2,
        total);
  }

  // ================================================================ 凭证自检

  /**
   * 凭证自检（**租户内**）。
   *
   * <p>四个判定分开给：凭证对不对、授权范围够不够、IP 白名单允不允许、配额够不够。
   * 「凭证对但没授权」和「凭证错」要修的地方完全不同，合成一个布尔值就没法用。
   */
  public IntegrationCredentialVerifyResult verify(
      UUID appId, IntegrationCredentialVerifyInput input, AccessService.Access actor) {
    var app = requireApp(appId);
    var result = new StringBuilder();
    var matched = false;
    var credentialStatus = "NOT_FOUND";
    var candidate =
        sql.fetchOne(
            "select id, status, api_key_digest, secret_digest from platform_app_credential"
                + " where app_id = ? and api_key_lookup = ?",
            appId,
            sha256Hex(input.apiKey() == null ? "" : input.apiKey()));
    if (candidate != null) {
      credentialStatus = candidate.get("status", String.class);
      var apiOk =
          PASSWORDS.matches(
              input.apiKey() == null ? "" : input.apiKey(), candidate.get("api_key_digest", String.class));
      var secretOk =
          PASSWORDS.matches(
              input.secret() == null ? "" : input.secret(), candidate.get("secret_digest", String.class));
      matched = apiOk && secretOk && "ACTIVE".equals(credentialStatus);
      if (matched) {
        sql.execute(
            "update platform_app_credential set last_used_at = ?::timestamptz where id = ?",
            OffsetDateTime.now(ZoneOffset.UTC),
            candidate.get("id", UUID.class));
        audit(appId, candidate.get("id", UUID.class), "CREDENTIAL_ACCESSED", actor.userId(), "USER", "凭证自检通过");
      }
    }

    var scopeList = scopes(appId);
    var scopeChecked = input.resourceCode() != null && !input.resourceCode().isBlank();
    var scopeGranted = false;
    if (scopeChecked) {
      var action = ScopeAction.parse(input.actionCode());
      check(action != null, "操作只能是 READ 或 WRITE");
      scopeGranted =
          scopeList.stream()
              .anyMatch(
                  s ->
                      s.resourceCode().equals(input.resourceCode().trim())
                          && s.actionCode().equals(action.name()));
      result
          .append(scopeGranted ? "授权范围允许该操作。" : "授权范围不包含该资源与操作。");
    } else if (scopeList.isEmpty()) {
      result.append("该应用未配置任何授权范围（未配置 = 不限制）。");
    } else {
      result.append("未指定要访问的资源与操作，授权范围未判定。");
    }

    var ipList = ipAllowlist(appId);
    var ipChecked = input.clientIp() != null && !input.clientIp().isBlank();
    var ipAllowed = false;
    if (ipList.isEmpty()) {
      result.append("未配置 IP 白名单（未配置 = 不限制来源）。");
    } else if (ipChecked) {
      ipAllowed = ipList.stream().anyMatch(e -> Cidrs.contains(e.cidr(), input.clientIp().trim()));
      result.append(ipAllowed ? "来源 IP 在白名单内。" : "来源 IP 不在白名单内。");
    } else {
      result.append("未提供来源 IP，IP 白名单未判定。");
    }

    var status = quotaStatus(appId, OffsetDateTime.now(ZoneOffset.UTC));
    var exceeded = status.stream().anyMatch(IntegrationQuotaStatusRow::exceeded);

    var conclusion =
        !matched
            ? "凭证不匹配或已失效"
            : (exceeded ? "凭证有效，但配额已超限" : (scopeChecked && !scopeGranted ? "凭证有效，但授权范围不足" : "凭证有效"));
    return new IntegrationCredentialVerifyResult(
        matched,
        credentialStatus,
        app.status(),
        scopeGranted,
        scopeChecked,
        ipAllowed,
        ipChecked,
        exceeded,
        status,
        conclusion,
        result.toString());
  }

  // ================================================================ 用量回报（服务端到服务端）

  /**
   * 网关回报一次调用用量（**内部密钥**，不进浏览器白名单）。
   *
   * <p>租户由调用方给出：这是**服务端到服务端**的路径（浏览器路径一律用会话租户）。
   * 用量是**累加**：并发回报不会互相覆盖。
   */
  public IntegrationQuotaUsageResult recordUsage(IntegrationQuotaUsageInput input, String key) {
    check(
        internalKey != null && internalKey.equals(key),
        "内部密钥不正确");
    check(input.tenantId() != null, "缺少租户");
    check(input.appId() != null, "缺少应用");
    var window = QuotaWindow.parse(input.windowType());
    check(window != null, "配额窗口只能是 SECOND / MINUTE / HOUR / DAY / MONTH");
    check(input.count() != null, "用量不能为空");
    check(input.count().signum() > 0, "用量必须是正数");
    check(input.count().scale() <= 6, "用量最多 6 位小数");
    var at = input.at() == null ? OffsetDateTime.now(ZoneOffset.UTC) : input.at();
    return tenants.run(
        input.tenantId(),
        () -> {
          var app = requireApp(input.appId());
          var start = windowStart(window, at);
          var now = OffsetDateTime.now(ZoneOffset.UTC);
          sql.execute(
              "insert into platform_app_quota_usage(id,tenant_id,app_id,window_type,window_started_at,"
                  + " used_count,updated_at) values(?,?,?,?,?::timestamptz,?,?::timestamptz)"
                  + " on conflict (tenant_id,app_id,window_type,window_started_at)"
                  + " do update set used_count = platform_app_quota_usage.used_count + excluded.used_count,"
                  + " updated_at = excluded.updated_at",
              UUID.randomUUID(),
              input.tenantId(),
              input.appId(),
              window.name(),
              start,
              input.count(),
              now);
          var used =
              sql.fetchOne(
                      "select used_count from platform_app_quota_usage where app_id = ? and window_type = ?"
                          + " and window_started_at = ?::timestamptz",
                      input.appId(),
                      window.name(),
                      start)
                  .get("used_count", BigDecimal.class);
          var limit =
              sql.fetchOne(
                      "select limit_count from platform_app_quota where app_id = ? and window_type = ?",
                      input.appId(),
                      window.name());
          var limitValue = limit == null ? null : limit.get("limit_count", BigDecimal.class);
          var over = limitValue != null && used.compareTo(limitValue) >= 0;
          audit(input.appId(), null, "QUOTA_USAGE_RECORDED", null, "GATEWAY", window.name() + " 用量 +" + input.count());
          return new IntegrationQuotaUsageResult(
              input.appId(), window.name(), used, limitValue, start, over);
        });
  }

  // ================================================================ 内部

  private void audit(
      UUID appId, UUID credentialId, String eventType, UUID actorUserId, String actorKind, String detail) {
    sql.execute(
        "insert into platform_app_audit_event(id,tenant_id,app_id,credential_id,event_type,actor_user_id,"
            + " actor_kind,detail,created_at) values(?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        appId,
        credentialId,
        eventType,
        actorUserId,
        actorKind,
        detail == null ? "" : detail,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  private UUID currentTenant() {
    // 事务局部 GUC：租户由 AccessService 绑定，这里读的是**同一个**绑定。
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    check(raw != null && !raw.isBlank(), "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  private long count(String sqlText) {
    return sql.fetchOne(sqlText).get(0, Long.class);
  }

  private IntegrationAppRow requireApp(UUID appId) {
    var row = sql.fetchOne(appSelect() + " where a.id = ?", appId);
    check(row != null, "应用不存在");
    return toAppRow(row);
  }

  private IntegrationCredentialRow credential(UUID credentialId) {
    var row =
        sql.fetchOne(
            "select c.id,c.app_id,c.version,c.status,c.api_key_prefix,c.secret_hint,c.issued_at,"
                + " c.expires_at,c.rotated_from_id,c.revoked_at,c.revoke_reason,c.last_used_at,c.created_at,"
                + " (select max(c2.issued_at) from platform_app_credential c2"
                + "   where c2.tenant_id = c.tenant_id and c2.rotated_from_id = c.id) as rotated_at"
                + " from platform_app_credential c where c.id = ?",
            credentialId);
    check(row != null, "凭证不存在");
    return toCredentialRow(row);
  }

  private IntegrationScopeRow scope(UUID scopeId) {
    var row =
        sql.fetchOne(
            "select id, app_id, resource_code, action_code, granted_at, granted_by"
                + " from platform_app_scope where id = ?",
            scopeId);
    check(row != null, "授权不存在");
    return new IntegrationScopeRow(
        row.get("id", UUID.class),
        row.get("app_id", UUID.class),
        row.get("resource_code", String.class),
        row.get("action_code", String.class),
        actionLabel(row.get("action_code", String.class)),
        row.get("granted_at", OffsetDateTime.class),
        row.get("granted_by", UUID.class));
  }

  private IntegrationIpAllowlistRow ipEntry(UUID entryId) {
    var row =
        sql.fetchOne(
            "select id, app_id, cidr, note, created_at, created_by from platform_app_ip_allowlist"
                + " where id = ?",
            entryId);
    check(row != null, "白名单条目不存在");
    return new IntegrationIpAllowlistRow(
        row.get("id", UUID.class),
        row.get("app_id", UUID.class),
        row.get("cidr", String.class),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("created_by", UUID.class));
  }

  private IntegrationQuotaRow quota(UUID quotaId) {
    var row =
        sql.fetchOne(
            "select id, app_id, window_type, limit_count, enabled, created_at, updated_at"
                + " from platform_app_quota where id = ?",
            quotaId);
    check(row != null, "配额不存在");
    return new IntegrationQuotaRow(
        row.get("id", UUID.class),
        row.get("app_id", UUID.class),
        row.get("window_type", String.class),
        windowLabel(row.get("window_type", String.class)),
        row.get("limit_count", BigDecimal.class),
        row.get("enabled", Boolean.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private static String appSelect() {
    return "select a.id,a.app_code,a.app_name,a.app_type,a.description,a.status,a.owner_user_id,"
        + " a.contact_name,a.contact_email,a.revoked_at,a.revoke_reason,a.created_at,a.updated_at,"
        + " (select count(*) from platform_app_credential c where c.tenant_id = a.tenant_id"
        + "   and c.app_id = a.id and c.status = 'ACTIVE') as active_credentials,"
        + " (select count(*) from platform_app_scope s where s.tenant_id = a.tenant_id"
        + "   and s.app_id = a.id) as scope_count,"
        + " (select count(*) from platform_app_ip_allowlist i where i.tenant_id = a.tenant_id"
        + "   and i.app_id = a.id) as ip_count,"
        + " (select count(*) from platform_app_quota q where q.tenant_id = a.tenant_id"
        + "   and q.app_id = a.id) as quota_count"
        + " from platform_app a";
  }

  private static IntegrationAppRow toAppRow(org.jooq.Record r) {
    return new IntegrationAppRow(
        r.get("id", UUID.class),
        r.get("app_code", String.class),
        r.get("app_name", String.class),
        r.get("app_type", String.class),
        appTypeLabel(r.get("app_type", String.class)),
        r.get("description", String.class),
        r.get("status", String.class),
        appStatusLabel(r.get("status", String.class)),
        r.get("owner_user_id", UUID.class),
        r.get("contact_name", String.class),
        r.get("contact_email", String.class),
        r.get("active_credentials", Long.class),
        r.get("scope_count", Long.class),
        r.get("ip_count", Long.class),
        r.get("quota_count", Long.class),
        r.get("revoked_at", OffsetDateTime.class),
        r.get("revoke_reason", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static IntegrationCredentialRow toCredentialRow(org.jooq.Record r) {
    return new IntegrationCredentialRow(
        r.get("id", UUID.class),
        r.get("app_id", UUID.class),
        r.get("version", Integer.class),
        r.get("status", String.class),
        credentialStatusLabel(r.get("status", String.class)),
        r.get("api_key_prefix", String.class),
        r.get("secret_hint", String.class),
        r.get("issued_at", OffsetDateTime.class),
        r.get("expires_at", OffsetDateTime.class),
        r.get("rotated_from_id", UUID.class),
        r.get("rotated_at", OffsetDateTime.class),
        r.get("revoked_at", OffsetDateTime.class),
        r.get("revoke_reason", String.class),
        r.get("last_used_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static Object[] append(List<Object> params, Object... extra) {
    var all = new ArrayList<>(params);
    all.addAll(List.of(extra));
    return all.toArray();
  }

  static OffsetDateTime windowStart(QuotaWindow window, OffsetDateTime at) {
    var utc = at.withOffsetSameInstant(ZoneOffset.UTC);
    return switch (window) {
      case SECOND -> utc.truncatedTo(ChronoUnit.SECONDS);
      case MINUTE -> utc.truncatedTo(ChronoUnit.MINUTES);
      case HOUR -> utc.truncatedTo(ChronoUnit.HOURS);
      case DAY -> utc.truncatedTo(ChronoUnit.DAYS);
      case MONTH -> utc.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
    };
  }

  static OffsetDateTime windowEnd(QuotaWindow window, OffsetDateTime start) {
    return switch (window) {
      case SECOND -> start.plusSeconds(1);
      case MINUTE -> start.plusMinutes(1);
      case HOUR -> start.plusHours(1);
      case DAY -> start.plusDays(1);
      case MONTH -> start.plusMonths(1);
    };
  }

  static String appTypeLabel(String code) {
    return switch (code == null ? "" : code) {
      case "INTERNAL" -> "平台自研";
      case "PARTNER" -> "生态伙伴";
      case "THIRD_PARTY" -> "第三方系统";
      default -> code;
    };
  }

  static String appStatusLabel(String code) {
    return switch (code == null ? "" : code) {
      case "ACTIVE" -> "正常";
      case "SUSPENDED" -> "暂停";
      case "REVOKED" -> "已吊销";
      default -> code;
    };
  }

  static String credentialStatusLabel(String code) {
    return switch (code == null ? "" : code) {
      case "ACTIVE" -> "生效中";
      case "ROTATED" -> "已被取代";
      case "REVOKED" -> "已吊销";
      default -> code;
    };
  }

  static String actionLabel(String code) {
    return switch (code == null ? "" : code) {
      case "READ" -> "读";
      case "WRITE" -> "写";
      default -> code;
    };
  }

  static String windowLabel(String code) {
    for (var w : WINDOWS) {
      if (w.name().equals(code)) return w.label();
    }
    return code;
  }

  static String eventLabel(String code) {
    return switch (code == null ? "" : code) {
      case "APP_REGISTERED" -> "注册应用";
      case "APP_UPDATED" -> "修改应用";
      case "APP_REVOKED" -> "吊销应用";
      case "CREDENTIAL_GENERATED" -> "签发凭证";
      case "CREDENTIAL_ROTATED" -> "轮换凭证";
      case "CREDENTIAL_REVOKED" -> "吊销凭证";
      case "CREDENTIAL_ACCESSED" -> "访问凭证";
      case "SCOPE_CHANGED" -> "变更授权范围";
      case "IP_ALLOWLIST_CHANGED" -> "变更 IP 白名单";
      case "QUOTA_CHANGED" -> "变更调用配额";
      case "QUOTA_USAGE_RECORDED" -> "记录调用用量";
      default -> code;
    };
  }

  /** CIDR 的解析与包含判定（服务层校验，不把格式问题留给数据库）。 */
  static final class Cidrs {
    private Cidrs() {}

    /** 合法 CIDR？IPv4 与 IPv6 都支持。 */
    static boolean isValidCidr(String raw) {
      if (raw == null || !raw.contains("/")) return false;
      var parts = raw.split("/", -1);
      if (parts.length != 2) return false;
      var address = parts[0];
      var prefixText = parts[1];
      if (prefixText.isEmpty() || !prefixText.chars().allMatch(Character::isDigit)) return false;
      int prefix;
      try {
        prefix = Integer.parseInt(prefixText);
      } catch (NumberFormatException e) {
        return false;
      }
      if (address.contains(":")) {
        if (prefix < 0 || prefix > 128) return false;
        return isIpv6Literal(address);
      }
      if (prefix < 0 || prefix > 32) return false;
      return isIpv4Literal(address);
    }

    /** {@code cidr} 是否包含 {@code ip}。 */
    static boolean contains(String cidr, String ip) {
      try {
        var parts = cidr.split("/", -1);
        var network = InetAddress.getByName(parts[0]);
        var candidate = InetAddress.getByName(ip);
        var prefix = Integer.parseInt(parts[1]);
        var networkBytes = network.getAddress();
        var candidateBytes = candidate.getAddress();
        if (networkBytes.length != candidateBytes.length) return false;
        var bits = prefix;
        var fullBytes = bits / 8;
        for (var i = 0; i < fullBytes; i++) {
          if (networkBytes[i] != candidateBytes[i]) return false;
        }
        var remaining = bits % 8;
        if (remaining == 0) return true;
        var mask = (byte) (0xFF << (8 - remaining));
        return (networkBytes[fullBytes] & mask) == (candidateBytes[fullBytes] & mask);
      } catch (Exception e) {
        return false;
      }
    }

    private static boolean isIpv4Literal(String address) {
      var parts = address.split("\\.", -1);
      if (parts.length != 4) return false;
      for (var part : parts) {
        if (part.isEmpty() || part.length() > 3) return false;
        for (var c : part.toCharArray()) {
          if (!Character.isDigit(c)) return false;
        }
        if (Integer.parseInt(part) > 255) return false;
      }
      return true;
    }

    private static boolean isIpv6Literal(String address) {
      if (address.isEmpty() || address.length() > 45) return false;
      if (!address.contains(":")) return false;
      var doubleColon = address.indexOf("::");
      if (doubleColon >= 0 && address.indexOf("::", doubleColon + 1) >= 0) return false;
      for (var c : address.toCharArray()) {
        var ok =
            Character.isDigit(c)
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F')
                || c == ':'
                || c == '.';
        if (!ok) return false;
      }
      var groups = address.split(":", -1);
      if (groups.length > 8) return false;
      for (var group : groups) {
        if (group.isEmpty()) continue;
        if (group.contains(".")) {
          if (!isIpv4Literal(group)) return false;
          continue;
        }
        if (group.length() > 4) return false;
        for (var c : group.toCharArray()) {
          if (!Character.isDigit(c) && !(c >= 'a' && c <= 'f') && !(c >= 'A' && c <= 'F')) return false;
        }
      }
      return true;
    }
  }
}
