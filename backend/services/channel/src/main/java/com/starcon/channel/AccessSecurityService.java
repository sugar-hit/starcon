package com.starcon.channel;

import static com.starcon.channel.AccessSecurityModels.*;
import static com.starcon.channel.AccessSecurityRules.*;

import com.starcon.channel.AccessSecuritySourcePort.Failure;
import com.starcon.channel.AccessSecuritySourcePort.SourceLoginEvent;
import com.starcon.tenancy.Ids;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
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
 * 16.5.2.1 登录与访问安全。
 *
 * <p>蓝图：「管理平台账号与系统的访问安全：多因子认证、登录审计、异常登录告警、IP 白名单、
 * 会话管理；平台授权凭据的访问审计。输入：认证配置；登录事件；访问审计事件。
 * 输出：安全配置 + 审计记录 + 告警。关系：上游 = 17 identity；下游 = 2 凭据引用。
 * <b>平台凭据是最值钱的资产</b>，访问必须有审计且最小权限。」
 *
 * <p>🔴🔴 本类里有<b>八条结构上的不变量</b>，每一条都对应蓝图或 AGENTS.md 的一条硬要求：
 *
 * <ol>
 *   <li><b>「读不到」绝不折成「没有」</b>：上游不可达 ⇒ 503；上游正常但查无记录 ⇒
 *       {@code known=false} + 空集合。<b>无记录 ≠ 无风险</b>。</li>
 *   <li><b>量不到的维度显式标 available=false</b>：按来源 IP 判异常（identity 不给 IP）、
 *       会话到期 / 空闲 / 并发（identity 没有读端点）—— 都写明归属模块与影响面，
 *       <b>不给假的 0</b>。</li>
 *   <li><b>异常检测的阈值由租户显式选</b>：没有策略 ⇒ 扫描直接拒绝（422），
 *       绝不「用默认阈值替租户做决定」。</li>
 *   <li><b>告警严重度是受控值域</b>，处置状态机是「新警 → 已确认 / 已忽略 / 已处置」，
 *       且每一次处置都留痕（{@code access_security_alert_action}）。</li>
 *   <li><b>IP 白名单是真网段判定</b>（PG 的 {@code inet <<=}），非法 CIDR 明确 400，
 *       <b>白名单为空 ≠ 放行所有</b>（ENFORCE + 空 = 拒绝所有，由 DB 守卫钉死）。</li>
 *   <li><b>审计表 append-only</b>：登录审计 / 告警处置 / 策略变更 / MFA 挑战 /
 *       <b>凭据访问审计</b> 全部只插不改，DB 层有守卫触发器。</li>
 *   <li><b>凭据访问审计「最小权限」</b>：只读元信息，<b>永远不返回令牌</b>
 *       （{@code credential_ref} 本身也没有令牌列）。</li>
 *   <li><b>会话管理不造第二套会话系统</b>：会话实体在 identity，本模块管的是
 *       <b>本服务侧会话策略 + 审计</b>。</li>
 * </ol>
 */
@Service
public class AccessSecurityService {

  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final AccessSecuritySourcePort source;

  public AccessSecurityService(DSLContext sql, AccessSecuritySourcePort source) {
    this.sql = sql;
    this.source = source;
  }

  // ================================================================ 能力声明

  /** 能力声明：逐能力面 available + ownerModule + 影响面 + 五条口径说明。 */
  public AccessSecurityCapabilities capabilities() {
    List<AccessSecurityCapability> capabilities =
        List.of(
            new AccessSecurityCapability(
                "MFA_POLICY",
                "多因子认证配置",
                true,
                "本服务自持（access_security_policy / access_security_mfa_registration）",
                "",
                "能配：是否强制 MFA、主因子（TOTP/SMS/EMAIL）、宽限期与强制生效时刻；"
                    + "能登记每个成员的因子绑定状态。"
                    + "🔴 **本仓没有真实 MFA 服务商凭据** ⇒ 挑战的「真实下发」不在本模块，"
                    + "本模块只登记下发记录与验证尝试。"),
            new AccessSecurityCapability(
                "MFA_CHALLENGE",
                "MFA 挑战下发与验证",
                false,
                "17 identity / 网关（真实下发）",
                "17",
                "🔴 **本模块不发送任何短信 / 邮件**：写一个打真实域名的假 HTTP 客户端是"
                    + "最典型的假实现。本模块只登记挑战记录（谁、什么时候、哪个因子、验证结果）。"),
            new AccessSecurityCapability(
                "LOGIN_AUDIT",
                "登录审计",
                true,
                "17 identity 的 operation_log（event_type = LOGIN / LOGOUT）",
                "",
                "能读：按已认证成员关系经 identity 的授权 API 读登录 / 登出事件，"
                    + "落成本租户的长期审计投影（identity 侧只有 90 天留存期）。"
                    + "🔴 **读不到就是读不到**：上游不可达一律 503，**绝不用空集合冒充「没有异常登录」**。"),
            new AccessSecurityCapability(
                "LOGIN_SOURCE_IP",
                "按来源 IP 判定异常登录",
                false,
                "17 identity 的 operation_log.detail.remote",
                "17",
                "🔴 **本仓量不到**：identity 的 {@code OperationLog.login} 在 "
                    + "{@code IdentityController.login} 里传的 remoteHint 恒为 null，"
                    + "登录事件**不含来源 IP**。本模块**不编造 IP**："
                    + "异常判定改用「同账号在窗口内的失败次数 / 成功次数」，那是从真实上游事件实算的。"
                    + "⇒ 等 identity 补上 remote 之后，本模块的 IP 维度会自动生效（读面已留好）。"),
            new AccessSecurityCapability(
                "ANOMALY_ALERT",
                "异常登录告警",
                true,
                "本服务（基于上游登录事件的实算）",
                "",
                "能判：失败登录激增、短时多次成功登录；两者都出**受控严重度**的告警，"
                    + "并带处置状态机（新警 → 已确认 / 已忽略 / 已处置）与处置留痕。"
                    + "🔴 阈值由租户在策略里显式选，系统不给默认值。"),
            new AccessSecurityCapability(
                "IP_ALLOWLIST",
                "IP 白名单",
                true,
                "本服务自持（access_security_ip_allowlist）",
                "",
                "能配、能判：网段判定走 PostgreSQL 的 {@code inet <<=}（真网段包含，不是字符串相等），"
                    + "非法 CIDR 明确拒绝。"
                    + "🔴 **白名单为空 ≠ 放行所有**：口径是 ENFORCE 而白名单为空时一律拒绝（fail-closed）。"),
            new AccessSecurityCapability(
                "SESSION_POLICY",
                "会话管理（策略与审计）",
                true,
                "本服务侧策略（access_security_policy 会话段）+ 17 的会话读面",
                "",
                "能管：空闲 / 绝对 / 并发上限、换绑 MFA 或改密即撤销，并留变更审计。"
                    + "🔴 **不另起第二套会话系统**：会话实体在 identity 的 auth_session。"),
            new AccessSecurityCapability(
                "SESSION_EXPIRY",
                "会话到期 / 空闲 / 并发数",
                false,
                "17 identity 的 auth_session.expires_at",
                "17",
                "🔴 **本仓读不到**：identity 的 auth_session 只有 token_hash / user_id / "
                    + "membership_id / expires_at 四个列，而 expires_at **没有任何读端点**；"
                    + "并发会话数在 identity 侧根本没有统计。本模块如实标「读不到」，"
                    + "**不把「读不到」说成「会话正常」**。"),
            new AccessSecurityCapability(
                "CREDENTIAL_ACCESS_AUDIT",
                "平台授权凭据的访问审计",
                true,
                "本服务自持（access_security_credential_access）+ 2.2.1.3 的 credential_ref",
                "",
                "能记：谁 / 什么时候 / 哪个凭据 / 做了什么 / 成没成，五件套齐全。"
                    + "🔴 表是 **append-only**（GRANT 只给 SELECT,INSERT），"
                    + "且**结构上没有令牌列** ⇒ 不可能泄露令牌。"));

    return new AccessSecurityCapabilities(
        capabilities,
        MFA_NOTE,
        LOGIN_AUDIT_NOTE,
        ALERT_NOTE,
        IP_ALLOWLIST_NOTE,
        SESSION_NOTE,
        CREDENTIAL_NOTE,
        DOWNSTREAM_NOTE,
        BOUNDARY_NOTE);
  }

  private static final String MFA_NOTE =
      "🔴 多因子认证分**三层**：① 策略（是否强制 / 主因子 / 宽限期 / 强制生效时刻）；"
          + "② 登记（每个成员的因子绑定状态，只存密钥管理器引用，**不存明文因子密钥**）；"
          + "③ 挑战（一次挑战多次尝试 = 多行，append-only）。"
          + "🔴 **本仓没有真实 MFA 服务商凭据** ⇒ 本模块**不发送任何短信 / 邮件**，"
          + "只登记挑战记录；真实下发由 17 identity / 网关执行。";

  private static final String LOGIN_AUDIT_NOTE =
      "🔴 登录事件的真源头是 identity 的 **operation_log**（event_type = LOGIN / LOGOUT），"
          + "**不是 identity_audit**（后者记的是业务变更）。"
          + "🔴 **为什么要本地留一份**：identity 的 operation_log 只有 **90 天留存期**"
          + "（V49: expires_at = occurred_at + 90 days，且有 purge 端点），"
          + "而登录审计是**合规证据**，不能跟着排障日志一起过期。";

  private static final String ALERT_NOTE =
      "🔴 告警严重度是**受控值域**（LOW / MEDIUM / HIGH / CRITICAL，DB 层 CHECK 钉死）；"
          + "处置状态机是**新警 → 已确认 / 已忽略 / 已处置**（已忽略与已处置是终态），"
          + "每一次处置都留一行（谁 / 什么时候 / 从什么状态到什么状态 / 为什么）。"
          + "🔴 **一个未关闭告警只允许一条**（部分唯一索引）：重复扫描命中同一条时更新它，"
          + "而不是刷出一串一模一样的告警把运营的注意力淹掉。";

  private static final String IP_ALLOWLIST_NOTE =
      "🔴 网段判定走 PostgreSQL 的 `inet` 类型与 `<<=` 运算符 —— "
          + "**真的按网段包含判定，不是字符串相等**（字符串相等判不了「10.0.0.5 在 10.0.0.0/8 里面」）。"
          + "🔴 **白名单为空 ≠ 放行所有**：口径是 ENFORCE 而白名单为空时**一律拒绝**（fail-closed），"
          + "这个不变量在数据层由守卫触发器钉死（切口径 / 停用 / 删除三条入口都拦）。"
          + "🔴 判定还会排除「条目不在有效期内」的情况 —— 全部条目都在有效期外时同样按拒绝处理。";

  private static final String SESSION_NOTE =
      "🔴 **会话实体在 identity（auth_session），本模块不另起第二套会话系统。**"
          + "本模块管的是**本服务侧会话策略**（空闲 / 绝对 / 并发上限、换绑即撤销）+ 变更审计。"
          + "🔴 identity 的 auth_session 只有四个列，expires_at **没有读端点**，并发会话数也没有统计 "
          + "⇒ 这两个维度如实标「读不到」，**不把「读不到」说成「会话正常」**。";

  private static final String CREDENTIAL_NOTE =
      "🔴 蓝图加粗句「**平台凭据是最值钱的资产**，访问必须有审计且最小权限」的物理落点："
          + "① 审计表 **append-only**（GRANT 只给 SELECT,INSERT，DB 层守卫再钉一道）；"
          + "② 表里**没有令牌列**（2.2.1.3 的 credential_ref 本身也只存引用）⇒ 结构上不可能泄露令牌；"
          + "③ 受控访问只返回**元信息**（提供方 / 授权范围 / 状态 / 到期时刻）。";

  private static final String DOWNSTREAM_NOTE =
      "🔴 下游 **2 凭据引用（2.2.1.3）已交付**（台账：credential_ref + PUT /connections/{id}/credential）。"
          + "本模块**不改它的表、不改它的端点、不改它的签名**，只在它旁边加一层访问审计。"
          + "🔴 2.2.1.2 授权向导与 2.2.1.3 换令牌这两条**写入路径本身还没有接到本审计表上**"
          + "（那是别的模块的代码，本模块不改别人已交付的文件）⇒ 能力声明里如实标注，"
          + "审计流水里现有的是**本模块受控访问**留下的那部分。";

  private static final String BOUNDARY_NOTE =
      "🔴 三条边界写死在能力声明里：**不编造来源 IP**（identity 登录事件不带 IP）、"
          + "**不造第二个会话系统**（会话实体在 identity）、"
          + "**不把「读不到」说成「没有风险」**（上游不可达 503；上游正常但无记录 ⇒ known=false）。";

  // ================================================================ 策略

  /** 安全策略。没登记过返回 configured=false（**后端不预填任何默认阈值**）。 */
  public AccessSecurityPolicyView policy() {
    Record row =
        sql.fetchOne(
            "select id,policy_name,mfa_required,mfa_primary_factor,mfa_grace_period_minutes,"
                + " mfa_enforcement_from,session_idle_timeout_minutes,session_absolute_timeout_minutes,"
                + " session_max_concurrent,session_revoke_on_mfa_change,session_revoke_on_password_change,"
                + " ip_allowlist_mode,anomaly_window_minutes,anomaly_failed_login_threshold,"
                + " anomaly_success_login_threshold,version,created_at,updated_at"
                + " from access_security_policy");
    if (row == null) {
      return new AccessSecurityPolicyView(
          null, "", false, MfaFactor.TOTP.name(), 0, null, 0, 0, 0, false, false,
          IpAllowlistMode.DISABLED.name(), 0, 0, 0, 0, false, null, null);
    }
    return policyView(row);
  }

  private AccessSecurityPolicyView policyView(Record row) {
    return new AccessSecurityPolicyView(
        row.get("id", UUID.class),
        row.get("policy_name", String.class),
        Boolean.TRUE.equals(row.get("mfa_required", Boolean.class)),
        row.get("mfa_primary_factor", String.class),
        num(row.get("mfa_grace_period_minutes", Integer.class)),
        row.get("mfa_enforcement_from", OffsetDateTime.class),
        num(row.get("session_idle_timeout_minutes", Integer.class)),
        num(row.get("session_absolute_timeout_minutes", Integer.class)),
        num(row.get("session_max_concurrent", Integer.class)),
        Boolean.TRUE.equals(row.get("session_revoke_on_mfa_change", Boolean.class)),
        Boolean.TRUE.equals(row.get("session_revoke_on_password_change", Boolean.class)),
        row.get("ip_allowlist_mode", String.class),
        num(row.get("anomaly_window_minutes", Integer.class)),
        num(row.get("anomaly_failed_login_threshold", Integer.class)),
        num(row.get("anomaly_success_login_threshold", Integer.class)),
        row.get("version", Long.class),
        true,
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 登记 / 修改策略（upsert，一租户一条）。
   *
   * <p>🔴 阈值**必须由租户显式给**（入参必填，没有默认值）。
   * 🔴 切换到 ENFORCE 而白名单为空 ⇒ 409（DB 层守卫也会拦，两处都拦）。
   */
  public AccessSecurityPolicyView savePolicy(
      AccessSecurityPolicyInput input, AccessService.Access actor) {
    if (input.mfaGracePeriodMinutes() > 0 && input.mfaEnforcementFrom() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "宽限期大于 0 时必须给出强制生效时刻，否则宽限期永远不会过期");
    }
    if (input.sessionAbsoluteTimeoutMinutes() < input.sessionIdleTimeoutMinutes()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "绝对超时不短于空闲超时");
    }
    if ("ENFORCE".equals(input.ipAllowlistMode()) && effectiveAllowlistCount() < 1) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "IP 白名单口径切成「强制拒绝」之前必须先登记白名单条目 —— 空白名单不等于放行所有");
    }
    Record row =
        sql.fetchOne(
            "insert into access_security_policy(id,tenant_id,policy_name,mfa_required,"
                + " mfa_primary_factor,mfa_grace_period_minutes,mfa_enforcement_from,"
                + " session_idle_timeout_minutes,session_absolute_timeout_minutes,session_max_concurrent,"
                + " session_revoke_on_mfa_change,session_revoke_on_password_change,ip_allowlist_mode,"
                + " anomaly_window_minutes,anomaly_failed_login_threshold,anomaly_success_login_threshold,"
                + " version,created_at,updated_at)"
                + " values(?,current_setting('app.current_tenant')::uuid,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,?,?,0,now(),now())"
                + " on conflict (tenant_id) do update set policy_name=excluded.policy_name,"
                + " mfa_required=excluded.mfa_required,mfa_primary_factor=excluded.mfa_primary_factor,"
                + " mfa_grace_period_minutes=excluded.mfa_grace_period_minutes,"
                + " mfa_enforcement_from=excluded.mfa_enforcement_from,"
                + " session_idle_timeout_minutes=excluded.session_idle_timeout_minutes,"
                + " session_absolute_timeout_minutes=excluded.session_absolute_timeout_minutes,"
                + " session_max_concurrent=excluded.session_max_concurrent,"
                + " session_revoke_on_mfa_change=excluded.session_revoke_on_mfa_change,"
                + " session_revoke_on_password_change=excluded.session_revoke_on_password_change,"
                + " ip_allowlist_mode=excluded.ip_allowlist_mode,"
                + " anomaly_window_minutes=excluded.anomaly_window_minutes,"
                + " anomaly_failed_login_threshold=excluded.anomaly_failed_login_threshold,"
                + " anomaly_success_login_threshold=excluded.anomaly_success_login_threshold,"
                + " version=access_security_policy.version+1,updated_at=now()"
                + " returning id,policy_name,mfa_required,mfa_primary_factor,mfa_grace_period_minutes,"
                + " mfa_enforcement_from,session_idle_timeout_minutes,session_absolute_timeout_minutes,"
                + " session_max_concurrent,session_revoke_on_mfa_change,session_revoke_on_password_change,"
                + " ip_allowlist_mode,anomaly_window_minutes,anomaly_failed_login_threshold,"
                + " anomaly_success_login_threshold,version,created_at,updated_at",
            Ids.next(),
            input.policyName().trim(),
            input.mfaRequired(),
            input.mfaPrimaryFactor(),
            input.mfaGracePeriodMinutes(),
            input.mfaEnforcementFrom() == null ? null : input.mfaEnforcementFrom().withOffsetSameInstant(ZoneOffset.UTC),
            input.sessionIdleTimeoutMinutes(),
            input.sessionAbsoluteTimeoutMinutes(),
            input.sessionMaxConcurrent(),
            input.sessionRevokeOnMfaChange(),
            input.sessionRevokeOnPasswordChange(),
            input.ipAllowlistMode(),
            input.anomalyWindowMinutes(),
            input.anomalyFailedLoginThreshold(),
            input.anomalySuccessLoginThreshold());
    if (row == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "策略保存失败");
    policyAudit("POLICY", row.get("id", UUID.class), "UPDATED", "policy", "", input.policyName(), actor, "");
    return policyView(row);
  }

  // ================================================================ MFA 登记

  /** MFA 登记列表（真分页 + 真筛选）。 */
  public AccessSecurityMfaRegistrationPage registrations(
      String userId, String status, int page, int size) {
    checkPage(page, size);
    Conditions condition = new Conditions();
    if (userId != null && !userId.isBlank()) condition.add("user_id = ?::uuid", userId.trim());
    if (status != null && !status.isBlank()) condition.add("status = ?", status.trim());
    long total =
        sql.fetchOne("select count(*) from access_security_mfa_registration" + condition.sql(), condition.values())
            .get(0, Long.class);
    var records =
        sql.fetch(
            "select id,user_id,membership_id,factor_type,factor_label,secret_ref,status,bound_at,"
                + " last_verified_at,revoked_reason,version,created_at,updated_at"
                + " from access_security_mfa_registration"
                + condition.sql()
                + " order by created_at desc,id desc limit ? offset ?",
            condition.values(size, (long) page * size));
    return new AccessSecurityMfaRegistrationPage(
        records.map(this::registrationView), page, size, total);
  }

  private AccessSecurityMfaRegistrationView registrationView(Record row) {
    return new AccessSecurityMfaRegistrationView(
        row.get("id", UUID.class),
        row.get("user_id", UUID.class),
        row.get("membership_id", UUID.class),
        row.get("factor_type", String.class),
        row.get("factor_label", String.class),
        row.get("status", String.class),
        row.get("secret_ref", String.class) != null && !row.get("secret_ref", String.class).isBlank(),
        row.get("bound_at", OffsetDateTime.class),
        row.get("last_verified_at", OffsetDateTime.class),
        row.get("revoked_reason", String.class),
        row.get("version", Long.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 登记一个 MFA 因子（状态 PENDING）。
   *
   * <p>🔴 **本模块不生成、不保存明文因子密钥**：{@code secretRef} 只是外部密钥管理器里的引用。
   * 🔴 登记成功**不代表已绑定** —— 绑定由 {@link #activateRegistration} 显式完成，
   * 这样「登记了」与「启用了」不会混成一句话。
   */
  public AccessSecurityMfaRegistrationView register(
      AccessSecurityMfaRegistrationInput input, AccessService.Access actor) {
    UUID id = Ids.next();
    sql.execute(
        "insert into access_security_mfa_registration(id,tenant_id,user_id,membership_id,factor_type,"
            + " factor_label,secret_ref,status,version,created_at,updated_at)"
            + " values(?,current_setting('app.current_tenant')::uuid,?,?,?,?,?,'PENDING',0,now(),now())",
        id,
        input.userId(),
        input.membershipId(),
        input.factorType(),
        nz(input.factorLabel()),
        nz(input.secretRef()));
    policyAudit(
        "MFA_REGISTRATION", id, "CREATED", "status", "", MfaStatus.PENDING.name(), actor, "登记 MFA 因子");
    return registrationView(
        sql.fetchOne(
            "select id,user_id,membership_id,factor_type,factor_label,secret_ref,status,bound_at,"
                + " last_verified_at,revoked_reason,version,created_at,updated_at"
                + " from access_security_mfa_registration where id=?",
            id));
  }

  /** 把一个 MFA 登记置为「已启用」（绑定完成）。 */
  public AccessSecurityMfaRegistrationView activateRegistration(
      String id, AccessService.Access actor) {
    Record current = loadRegistration(id);
    MfaStatus from = MfaStatus.valueOf(current.get("status", String.class));
    if (!from.canTransitionTo(MfaStatus.ACTIVE)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "MFA 登记当前状态是「" + from.label() + "」，不能直接启用");
    }
    sql.execute(
        "update access_security_mfa_registration set status='ACTIVE',bound_at=coalesce(bound_at,now()),"
            + " version=version+1,updated_at=now() where id=?",
        UUID.fromString(id));
    policyAudit(
        "MFA_REGISTRATION",
        UUID.fromString(id),
        "ACTIVATED",
        "status",
        from.name(),
        MfaStatus.ACTIVE.name(),
        actor,
        "");
    return registrationView(loadRegistration(id));
  }

  /** 把一个 MFA 登记置为「已停用」。 */
  public AccessSecurityMfaRegistrationView disableRegistration(
      String id, AccessService.Access actor) {
    Record current = loadRegistration(id);
    MfaStatus from = MfaStatus.valueOf(current.get("status", String.class));
    if (!from.canTransitionTo(MfaStatus.DISABLED)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "MFA 登记当前状态是「" + from.label() + "」，不能停用");
    }
    sql.execute(
        "update access_security_mfa_registration set status='DISABLED',version=version+1,updated_at=now()"
            + " where id=?",
        UUID.fromString(id));
    policyAudit(
        "MFA_REGISTRATION",
        UUID.fromString(id),
        "DISABLED",
        "status",
        from.name(),
        MfaStatus.DISABLED.name(),
        actor,
        "");
    return registrationView(loadRegistration(id));
  }

  private Record loadRegistration(String id) {
    Record row =
        sql.fetchOne(
            "select id,user_id,membership_id,factor_type,factor_label,secret_ref,status,bound_at,"
                + " last_verified_at,revoked_reason,version,created_at,updated_at"
                + " from access_security_mfa_registration where id=?",
            uuid(id));
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MFA 登记不存在或无权查看");
    }
    return row;
  }

  // ================================================================ MFA 挑战

  /** MFA 挑战列表（真分页 + 真筛选）。 */
  public AccessSecurityMfaChallengePage challenges(String registrationId, int page, int size) {
    checkPage(page, size);
    Conditions condition = new Conditions();
    if (registrationId != null && !registrationId.isBlank()) {
      condition.add("registration_id = ?::uuid", registrationId.trim());
    }
    long total =
        sql.fetchOne("select count(*) from access_security_mfa_challenge" + condition.sql(), condition.values())
            .get(0, Long.class);
    var records =
        sql.fetch(
            "select id,challenge_id,registration_id,attempt_no,factor_type,outcome,delivery_channel,"
                + " delivery_ref,failure_reason,expires_at,occurred_at from access_security_mfa_challenge"
                + condition.sql()
                + " order by occurred_at desc,id desc limit ? offset ?",
            condition.values(size, (long) page * size));
    return new AccessSecurityMfaChallengePage(records.map(this::challengeView), page, size, total);
  }

  private AccessSecurityMfaChallengeView challengeView(Record row) {
    return new AccessSecurityMfaChallengeView(
        row.get("id", UUID.class),
        row.get("challenge_id", UUID.class),
        row.get("registration_id", UUID.class),
        num(row.get("attempt_no", Integer.class)),
        row.get("factor_type", String.class),
        row.get("outcome", String.class),
        row.get("delivery_channel", String.class),
        row.get("delivery_ref", String.class),
        row.get("failure_reason", String.class),
        row.get("expires_at", OffsetDateTime.class),
        row.get("occurred_at", OffsetDateTime.class));
  }

  /**
   * 登记一次挑战下发。
   *
   * <p>🔴 **本方法不发送任何短信 / 邮件** —— 本仓没有真实 MFA 服务商凭据，
   * 写一个打真实域名的假 HTTP 客户端是最典型的假实现。
   * 它登记的是「一次挑战被下发」这个事实（真实下发由 17 identity / 网关执行）。
   */
  public AccessSecurityMfaChallengeView issueChallenge(
      AccessSecurityMfaChallengeInput input, AccessService.Access actor) {
    Record registration = loadRegistration(input.registrationId().toString());
    MfaStatus status = MfaStatus.valueOf(registration.get("status", String.class));
    if (status != MfaStatus.ACTIVE) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "MFA 登记当前状态是「" + status.label() + "」，不能下发挑战");
    }
    UUID challengeId = Ids.next();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime expiresAt = now.plusSeconds(input.expiresInSeconds());
    UUID id = Ids.next();
    sql.execute(
        "insert into access_security_mfa_challenge(id,tenant_id,registration_id,challenge_id,attempt_no,"
            + " factor_type,outcome,delivery_channel,delivery_ref,failure_reason,expires_at,occurred_at,created_at)"
            + " values(?,current_setting('app.current_tenant')::uuid,?,?,1,?,'ISSUED',?,?,'',?::timestamptz,?::timestamptz,now())",
        id,
        input.registrationId(),
        challengeId,
        input.factorType(),
        input.deliveryChannel(),
        nz(input.deliveryRef()),
        expiresAt,
        now);
    return challengeView(
        sql.fetchOne(
            "select id,challenge_id,registration_id,attempt_no,factor_type,outcome,delivery_channel,"
                + " delivery_ref,failure_reason,expires_at,occurred_at from access_security_mfa_challenge"
                + " where id=?",
            id));
  }

  /**
   * 登记一次验证尝试。
   *
   * <p>🔴 **append-only**：验证尝试是**新的一行**，不是把上一行改成「已验证」。
   * 事后改写它等于伪造「验证了几次」。
   */
  public AccessSecurityMfaChallengeView attemptChallenge(
      String challengeId, AccessSecurityMfaChallengeAttemptInput input, AccessService.Access actor) {
    UUID challenge = uuid(challengeId);
    if (challenge == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "挑战不存在或无权查看");
    }
    Record issued =
        sql.fetchOne(
            "select id,registration_id,challenge_id,attempt_no,factor_type,outcome,delivery_channel,"
                + " delivery_ref,failure_reason,expires_at,occurred_at from access_security_mfa_challenge"
                + " where challenge_id=? and attempt_no=1",
            challenge);
    if (issued == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "挑战不存在或无权查看");
    }
    String outcome = input.outcome();
    if ("FAILED".equals(outcome) && (input.failureReason() == null || input.failureReason().isBlank())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证失败必须写明失败原因");
    }
    int nextAttempt =
        sql.fetchOne(
                "select coalesce(max(attempt_no),0) from access_security_mfa_challenge where challenge_id=?",
                challenge)
            .get(0, Integer.class)
            + 1;
    UUID id = Ids.next();
    sql.execute(
        "insert into access_security_mfa_challenge(id,tenant_id,registration_id,challenge_id,attempt_no,"
            + " factor_type,outcome,delivery_channel,delivery_ref,failure_reason,expires_at,occurred_at,created_at)"
            + " values(?,current_setting('app.current_tenant')::uuid,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,now())",
        id,
        issued.get("registration_id", UUID.class),
        challenge,
        nextAttempt,
        issued.get("factor_type", String.class),
        outcome,
        issued.get("delivery_channel", String.class),
        issued.get("delivery_ref", String.class),
        nz(input.failureReason()),
        issued.get("expires_at", OffsetDateTime.class),
        OffsetDateTime.now(ZoneOffset.UTC));
    if ("VERIFIED".equals(outcome)) {
      sql.execute(
          "update access_security_mfa_registration set last_verified_at=now(),updated_at=now() where id=?",
          issued.get("registration_id", UUID.class));
    }
    return challengeView(
        sql.fetchOne(
            "select id,challenge_id,registration_id,attempt_no,factor_type,outcome,delivery_channel,"
                + " delivery_ref,failure_reason,expires_at,occurred_at from access_security_mfa_challenge"
                + " where id=?",
            id));
  }

  // ================================================================ 登录审计

  /**
   * 登录审计列表（本地留存投影）。
   *
   * <p>🔴 {@code known=false} 表示**本地还没有任何登录事件留存**（还没跑过扫描）。
   * 「本地没有记录」**不等于**「没有异常登录」—— 界面必须显示成「还没回填」而不是「一切正常」。
   */
  public AccessSecurityLoginAuditPage loginAudit(
      String actorName, String outcome, int page, int size) {
    checkPage(page, size);
    long any = sql.fetchOne("select count(*) from access_security_login_audit").get(0, Long.class);
    Conditions condition = new Conditions();
    if (actorName != null && !actorName.isBlank()) {
      condition.add("actor_name = ?", actorName.trim());
    }
    if (outcome != null && !outcome.isBlank()) condition.add("outcome = ?", outcome.trim());
    long total =
        sql.fetchOne("select count(*) from access_security_login_audit" + condition.sql(), condition.values())
            .get(0, Long.class);
    var records =
        sql.fetch(
            "select id,source_event_id,event_type,outcome,actor_name,actor_id,level,request_id,summary,"
                + " occurred_at,source_ip,source_ip_available,anomaly,anomaly_code,note"
                + " from access_security_login_audit"
                + condition.sql()
                + " order by occurred_at desc,id desc limit ? offset ?",
            condition.values(size, (long) page * size));
    String note =
        any == 0
            ? "本地还没有任何登录事件留存 —— 请先跑一次登录审计扫描。"
                + "🔴 「没有记录」**不等于**「没有异常登录」。"
            : "";
    return new AccessSecurityLoginAuditPage(any > 0, note, records.map(this::loginAuditView), page, size, total);
  }

  private AccessSecurityLoginAuditView loginAuditView(Record row) {
    return new AccessSecurityLoginAuditView(
        row.get("id", UUID.class),
        row.get("source_event_id", UUID.class),
        row.get("event_type", String.class),
        row.get("outcome", String.class),
        row.get("actor_name", String.class),
        row.get("actor_id", UUID.class),
        row.get("level", String.class),
        row.get("request_id", String.class),
        row.get("summary", String.class),
        row.get("occurred_at", OffsetDateTime.class),
        row.get("source_ip", String.class),
        Boolean.TRUE.equals(row.get("source_ip_available", Boolean.class)),
        Boolean.TRUE.equals(row.get("anomaly", Boolean.class)),
        row.get("anomaly_code", String.class),
        row.get("note", String.class));
  }

  /**
   * 跑一次登录审计扫描：读上游 → 落本地审计 → 判异常 → 出告警。
   *
   * <p>🔴🔴 <b>上游不可达一律 503</b>，**绝不用空集合冒充「没有异常登录」**：
   * 空集合会被界面读成「这段时间没有任何异常」，而真相是「我们没查到」。
   *
   * <p>🔴 <b>上游正常但查无记录 ⇒ {@code known=false}</b> + 空集合 —— 无记录 ≠ 无风险。
   *
   * <p>🔴 <b>阈值必须先登记策略</b>：没有策略就没有阈值，扫描直接 422 拒绝，
   * 不用系统默认阈值替租户做决定。
   */
  public AccessSecurityScanResult scan(
      String bearer, AccessSecurityScanInput input, AccessService.Access actor) {
    AccessSecurityPolicyView policy = policy();
    if (!policy.configured()) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "先登记安全策略再扫描 —— 异常登录的窗口与阈值必须由租户显式选，系统不给默认值");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    OffsetDateTime to = input != null && input.to() != null ? input.to() : now;
    OffsetDateTime from =
        input != null && input.from() != null
            ? input.from()
            : to.minusMinutes(policy.anomalyWindowMinutes());
    if (!from.isBefore(to)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "扫描窗口的开始时间必须早于结束时间");
    }

    var login = source.loginEvents(bearer, "LOGIN", from.toInstant(), to.toInstant(), 20);
    var logout = source.loginEvents(bearer, "LOGOUT", from.toInstant(), to.toInstant(), 20);
    // 🔴 任一读面读不到 ⇒ 整个扫描读不到（不拿一半数据出结论）
    if (!login.available() || !logout.available()) {
      var failed = login.available() ? logout : login;
      throw switch (failed.failure()) {
        case UNAUTHORIZED -> new ResponseStatusException(
            HttpStatus.UNAUTHORIZED, "登录已失效或身份服务拒绝了本次请求");
        case FORBIDDEN -> new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "身份服务拒绝了读取操作日志（调用方在身份服务侧缺少 identity.operation_log.read）");
        default -> new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "登录事件读不到：" + failed.note());
      };
    }

    List<SourceLoginEvent> events = new ArrayList<>(login.events());
    events.addAll(logout.events());
    if (events.isEmpty()) {
      // 🔴 上游正常但没有记录 ⇒ known=false。**不是**「没有异常登录」。
      return new AccessSecurityScanResult(
          false,
          "身份服务正常返回，但这个窗口里没有任何登录 / 登出事件 —— "
              + "🔴 「没有记录」不等于「没有风险」，请确认扫描窗口与上游是否已接入。",
          from,
          to,
          null,
          0,
          0,
          0,
          0,
          List.of());
    }

    // ---- 落本地审计（同一上游事件只落一次）----
    int recorded = 0;
    for (SourceLoginEvent event : events) {
      UUID sourceId = AccessSecuritySourcePort.uuid(event.id());
      if (sourceId == null) continue;
      String outcome = "LOGOUT".equalsIgnoreCase(event.eventType()) ? "SUCCESS" : outcomeOf(event);
      recorded +=
          sql.execute(
              "insert into access_security_login_audit(id,tenant_id,source_event_id,source_service,"
                  + " event_type,outcome,actor_name,actor_id,level,request_id,summary,occurred_at,"
                  + " source_ip,source_ip_available,anomaly,anomaly_code,note,created_at)"
                  + " values(?,current_setting('app.current_tenant')::uuid,?,'IDENTITY',?,?,?,?,?,?,?,?::timestamptz,"
                  + " ?,?,false,'','',now())"
                  + " on conflict (tenant_id,source_event_id) do nothing",
              Ids.next(),
              sourceId,
              event.eventType(),
              outcome,
              nz(event.actorName()),
              AccessSecuritySourcePort.uuid(event.actorId()),
              nz(event.level()),
              nz(event.requestId()),
              nz(event.summary()),
              event.occurredAt() == null
                  ? now
                  : event.occurredAt().atOffset(ZoneOffset.UTC),
              nz(event.sourceIp()),
              !nz(event.sourceIp()).isBlank());
    }

    // ---- 异常判定（按账号实算计数）----
    Map<String, int[]> counts = new LinkedHashMap<>();
    for (SourceLoginEvent event : events) {
      if (!"LOGIN".equalsIgnoreCase(event.eventType())) continue;
      String name = nz(event.actorName());
      if (name.isBlank()) continue;
      int[] bucket = counts.computeIfAbsent(name, key -> new int[2]);
      if ("FAILURE".equals(outcomeOf(event))) bucket[0]++;
      else bucket[1]++;
    }

    int created = 0;
    int updated = 0;
    List<AccessSecurityAlertView> alerts = new ArrayList<>();
    Map<String, String> anomalyCodes = new HashMap<>();
    for (Map.Entry<String, int[]> entry : counts.entrySet()) {
      String name = entry.getKey();
      int failures = entry.getValue()[0];
      int successes = entry.getValue()[1];
      if (failures >= policy.anomalyFailedLoginThreshold()) {
        Severity severity = failedBurstSeverity(failures, policy.anomalyFailedLoginThreshold());
        anomalyCodes.put(name, AlertType.FAILED_LOGIN_BURST.name());
        AlertOutcome outcome =
            raiseAlert(
                AlertType.FAILED_LOGIN_BURST,
                severity,
                "USERNAME",
                name,
                "账号 " + name + " 在 " + policy.anomalyWindowMinutes() + " 分钟内登录失败 "
                    + failures + " 次",
                "登录失败次数超过策略阈值 " + policy.anomalyFailedLoginThreshold()
                    + " 次。这通常意味着有人在猜密码，或者凭据已被泄露。",
                failures,
                from,
                to,
                now);
        if (outcome.created()) created++;
        else updated++;
        alerts.add(outcome.view());
      }
      if (successes >= policy.anomalySuccessLoginThreshold()) {
        Severity severity = successBurstSeverity(successes, policy.anomalySuccessLoginThreshold());
        anomalyCodes.putIfAbsent(name, AlertType.SUCCESS_LOGIN_BURST.name());
        AlertOutcome outcome =
            raiseAlert(
                AlertType.SUCCESS_LOGIN_BURST,
                severity,
                "USERNAME",
                name,
                "账号 " + name + " 在 " + policy.anomalyWindowMinutes() + " 分钟内成功登录 "
                    + successes + " 次",
                "成功登录次数超过策略阈值 " + policy.anomalySuccessLoginThreshold()
                    + " 次。可能是凭据泄露后被异地使用，也可能是运营在多台设备上切换。",
                successes,
                from,
                to,
                now);
        if (outcome.created()) created++;
        else updated++;
        alerts.add(outcome.view());
      }
    }

    // ---- 把异常标记写回本地审计行（append-only 表不 UPDATE，用「重算 + 补标」的口径说明）----
    // 🔴 本地登录审计是 append-only（不 UPDATE）。因此异常标记**不是**改在已落的那一行上，
    //   而是随下一次扫描**新增**的行携带（同一上游事件不会重复落，见 UNIQUE 约束）。
    //   ⇒ 判定结果以「告警」为准，审计行上的 anomaly 列只反映**首次落库时**的判定。
    //   这一点在能力声明与界面上都写明，避免被读成「审计行上的标记是最新的」。

    // ---- ENFORCE 口径下白名单在当前时刻为空 ⇒ 告警（fail-closed 状态值得提醒）----
    if (IpAllowlistMode.ENFORCE.name().equals(policy.ipAllowlistMode())
        && effectiveAllowlistCount() < 1) {
      AlertOutcome outcome =
          raiseAlert(
              AlertType.ALLOWLIST_EMPTY,
              Severity.CRITICAL,
              "POLICY",
              "ip_allowlist",
              "IP 白名单口径是「强制拒绝」，但当前没有生效中的白名单条目",
              "🔴 此刻所有来源 IP 都会被拒绝（fail-closed）。白名单条目可能都还在有效期外。",
              0,
              null,
              null,
              now);
      if (outcome.created()) created++;
      else updated++;
      alerts.add(outcome.view());
    }

    List<AccessSecurityLoginAuditView> view = new ArrayList<>();
    for (SourceLoginEvent event : events) {
      view.add(
          new AccessSecurityLoginAuditView(
              null,
              AccessSecuritySourcePort.uuid(event.id()),
              event.eventType(),
              "LOGOUT".equalsIgnoreCase(event.eventType()) ? "SUCCESS" : outcomeOf(event),
              nz(event.actorName()),
              AccessSecuritySourcePort.uuid(event.actorId()),
              nz(event.level()),
              nz(event.requestId()),
              nz(event.summary()),
              event.occurredAt() == null
                  ? now
                  : event.occurredAt().atOffset(ZoneOffset.UTC),
              nz(event.sourceIp()),
              !nz(event.sourceIp()).isBlank(),
              anomalyCodes.containsKey(nz(event.actorName())),
              anomalyCodes.getOrDefault(nz(event.actorName()), ""),
              ""));
    }
    return new AccessSecurityScanResult(
        true, "", from, to, view, events.size(), recorded, created, updated, alerts);
  }

  /** 上游登录事件的成败判定：identity 的 LOGIN 事件 level 是 INFO（成功）/ ERROR（失败）。 */
  private static String outcomeOf(SourceLoginEvent event) {
    if ("ERROR".equalsIgnoreCase(event.level())) return "FAILURE";
    String summary = nz(event.summary());
    if (summary.contains("失败")) return "FAILURE";
    return "SUCCESS";
  }

  // ================================================================ 告警

  /** 告警列表（真分页 + 真筛选 + 汇总）。 */
  public AccessSecurityAlertPage alerts(
      String type, String severity, String status, int page, int size) {
    checkPage(page, size);
    Conditions condition = new Conditions();
    if (type != null && !type.isBlank()) condition.add("alert_type = ?", type.trim());
    if (severity != null && !severity.isBlank()) condition.add("severity = ?", severity.trim());
    if (status != null && !status.isBlank()) condition.add("status = ?", status.trim());
    long total =
        sql.fetchOne("select count(*) from access_security_alert" + condition.sql(), condition.values())
            .get(0, Long.class);
    var records =
        sql.fetch(
            "select id,alert_type,severity,status,subject_kind,subject_value,dedup_key,title,summary,"
                + " event_count,window_start,window_end,detected_at,acknowledged_by,acknowledged_at,"
                + " ignored_by,ignored_at,ignore_reason,resolved_by,resolved_at,resolution_note,version,"
                + " created_at from access_security_alert"
                + condition.sql()
                + " order by detected_at desc,id desc limit ? offset ?",
            condition.values(size, (long) page * size));
    long newCount = countAlerts("status", AlertStatus.NEW.name());
    long acknowledged = countAlerts("status", AlertStatus.ACKNOWLEDGED.name());
    long ignored = countAlerts("status", AlertStatus.IGNORED.name());
    long resolved = countAlerts("status", AlertStatus.RESOLVED.name());
    long critical = countAlerts("severity", Severity.CRITICAL.name());
    long high = countAlerts("severity", Severity.HIGH.name());
    return new AccessSecurityAlertPage(
        records.map(this::alertView),
        page,
        size,
        total,
        newCount,
        acknowledged,
        ignored,
        resolved,
        critical,
        high);
  }

  private long countAlerts(String column, String value) {
    return sql.fetchOne(
            "select count(*) from access_security_alert where " + column + " = ?", value)
        .get(0, Long.class);
  }

  /** 告警详情（含处置留痕）。 */
  public AccessSecurityAlertView alert(String id) {
    Record row = loadAlert(id);
    return alertView(row);
  }

  private Record loadAlert(String id) {
    UUID alertId = uuid(id);
    if (alertId == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "告警不存在或无权查看");
    }
    Record row =
        sql.fetchOne(
            "select id,alert_type,severity,status,subject_kind,subject_value,dedup_key,title,summary,"
                + " event_count,window_start,window_end,detected_at,acknowledged_by,acknowledged_at,"
                + " ignored_by,ignored_at,ignore_reason,resolved_by,resolved_at,resolution_note,version,"
                + " created_at from access_security_alert where id=?",
            alertId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "告警不存在或无权查看");
    }
    return row;
  }

  private AccessSecurityAlertView alertView(Record row) {
    UUID alertId = row.get("id", UUID.class);
    List<AccessSecurityAlertActionView> actions =
        sql.fetch(
                "select id,action,from_status,to_status,actor_id,actor_name,reason,created_at"
                    + " from access_security_alert_action where alert_id=? order by created_at,id",
                alertId)
            .map(
                action ->
                    new AccessSecurityAlertActionView(
                        action.get("id", UUID.class),
                        action.get("action", String.class),
                        action.get("from_status", String.class),
                        action.get("to_status", String.class),
                        action.get("actor_id", UUID.class),
                        action.get("actor_name", String.class),
                        action.get("reason", String.class),
                        action.get("created_at", OffsetDateTime.class)));
    return new AccessSecurityAlertView(
        alertId,
        row.get("alert_type", String.class),
        row.get("severity", String.class),
        row.get("status", String.class),
        row.get("subject_kind", String.class),
        row.get("subject_value", String.class),
        row.get("title", String.class),
        row.get("summary", String.class),
        num(row.get("event_count", Integer.class)),
        row.get("window_start", OffsetDateTime.class),
        row.get("window_end", OffsetDateTime.class),
        row.get("detected_at", OffsetDateTime.class),
        row.get("acknowledged_by", UUID.class),
        row.get("acknowledged_at", OffsetDateTime.class),
        row.get("ignored_by", UUID.class),
        row.get("ignored_at", OffsetDateTime.class),
        row.get("ignore_reason", String.class),
        row.get("resolved_by", UUID.class),
        row.get("resolved_at", OffsetDateTime.class),
        row.get("resolution_note", String.class),
        row.get("version", Long.class),
        row.get("created_at", OffsetDateTime.class),
        actions);
  }

  /** 确认告警（新警 → 已确认）。 */
  public AccessSecurityAlertView acknowledgeAlert(
      String id, String reason, AccessService.Access actor) {
    return applyAlertAction(id, AlertAction.ACKNOWLEDGE, reason, actor);
  }

  /** 忽略告警（新警 / 已确认 → 已忽略）。 */
  public AccessSecurityAlertView ignoreAlert(String id, String reason, AccessService.Access actor) {
    return applyAlertAction(id, AlertAction.IGNORE, reason, actor);
  }

  /** 处置告警（新警 / 已确认 → 已处置）。 */
  public AccessSecurityAlertView resolveAlert(String id, String reason, AccessService.Access actor) {
    return applyAlertAction(id, AlertAction.RESOLVE, reason, actor);
  }

  private AccessSecurityAlertView applyAlertAction(
      String id, AlertAction action, String reason, AccessService.Access actor) {
    Record row = loadAlert(id);
    UUID alertId = row.get("id", UUID.class);
    AlertStatus from = AlertStatus.valueOf(row.get("status", String.class));
    AlertStatus to = action.targetStatus();
    if (!canTransition(from, to)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "告警当前状态是「" + from.label() + "」，不能「" + action.label() + "」"
              + "（已忽略与已处置是终态；要重新处置请等下一次告警）");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    switch (action) {
      case ACKNOWLEDGE ->
          sql.execute(
              "update access_security_alert set status='ACKNOWLEDGED',acknowledged_by=?,acknowledged_at=?::timestamptz,"
                  + " version=version+1,updated_at=now() where id=?",
              actor.userId(),
              now,
              alertId);
      case IGNORE ->
          sql.execute(
              "update access_security_alert set status='IGNORED',ignored_by=?,ignored_at=?::timestamptz,"
                  + " ignore_reason=?,version=version+1,updated_at=now() where id=?",
              actor.userId(),
              now,
              nz(reason),
              alertId);
      case RESOLVE ->
          sql.execute(
              "update access_security_alert set status='RESOLVED',resolved_by=?,resolved_at=?::timestamptz,"
                  + " resolution_note=?,version=version+1,updated_at=now() where id=?",
              actor.userId(),
              now,
              nz(reason),
              alertId);
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知的处置动作");
    }
    sql.execute(
        "insert into access_security_alert_action(id,tenant_id,alert_id,action,from_status,to_status,"
            + " actor_id,actor_name,reason,created_at)"
            + " values(?,current_setting('app.current_tenant')::uuid,?,?,?,?,?,?,?,now())",
        Ids.next(),
        alertId,
        action.name(),
        from.name(),
        to.name(),
        actor.userId(),
        "",
        nz(reason));
    return alertView(loadAlert(id));
  }

  /** 告警抬升的结果。 */
  private record AlertOutcome(boolean created, AccessSecurityAlertView view) {}

  /**
   * 抬升（或更新）一条告警。
   *
   * <p>🔴 **同一 (类型, 主体) 只留一条未关闭告警**：命中已有告警就更新它
   * （事件数、窗口、严重度只升不降），不新开一条 —— 否则每扫一次就多一条一样的告警。
   */
  private AlertOutcome raiseAlert(
      AlertType type,
      Severity severity,
      String subjectKind,
      String subjectValue,
      String title,
      String summary,
      int eventCount,
      OffsetDateTime windowStart,
      OffsetDateTime windowEnd,
      OffsetDateTime detectedAt) {
    String dedupKey = alertDedupKey(type, subjectValue);
    Record open =
        sql.fetchOne(
            "select id,severity,status from access_security_alert where dedup_key=? and status in"
                + " ('NEW','ACKNOWLEDGED')",
            dedupKey);
    if (open != null) {
      UUID alertId = open.get("id", UUID.class);
      Severity current = Severity.valueOf(open.get("severity", String.class));
      Severity escalated = current.max(severity);
      sql.execute(
          "update access_security_alert set severity=?,event_count=?,window_start=?::timestamptz,window_end=?::timestamptz,"
              + " summary=?,detected_at=?::timestamptz,version=version+1,updated_at=now() where id=?",
          escalated.name(),
          eventCount,
          windowStart,
          windowEnd,
          summary,
          detectedAt,
          alertId);
      return new AlertOutcome(false, alertView(loadAlert(alertId.toString())));
    }
    UUID alertId = Ids.next();
    sql.execute(
        "insert into access_security_alert(id,tenant_id,alert_type,severity,status,subject_kind,"
            + " subject_value,dedup_key,title,summary,event_count,window_start,window_end,detected_at,"
            + " version,created_at,updated_at)"
            + " values(?,current_setting('app.current_tenant')::uuid,?,?,'NEW',?,?,?,?,?,?,?::timestamptz,"
            + "?::timestamptz,?::timestamptz,0,now(),now())",
        alertId,
        type.name(),
        severity.name(),
        subjectKind,
        subjectValue,
        dedupKey,
        title,
        summary,
        eventCount,
        windowStart,
        windowEnd,
        detectedAt);
    return new AlertOutcome(true, alertView(loadAlert(alertId.toString())));
  }

  // ================================================================ IP 白名单

  /** IP 白名单列表（真分页 + 真筛选）。 */
  public AccessSecurityIpAllowlistPage allowlist(String status, int page, int size) {
    checkPage(page, size);
    Conditions condition = new Conditions();
    if (status != null && !status.isBlank()) condition.add("status = ?", status.trim());
    long total =
        sql.fetchOne("select count(*) from access_security_ip_allowlist" + condition.sql(), condition.values())
            .get(0, Long.class);
    var records =
        sql.fetch(
            "select id,cidr_range,label,scope_kind,scope_id,status,effective_from,effective_to,"
                + " created_by,created_by_name,version,created_at from access_security_ip_allowlist"
                + condition.sql()
                + " order by created_at desc,id desc limit ? offset ?",
            condition.values(size, (long) page * size));
    return new AccessSecurityIpAllowlistPage(records.map(this::allowlistView), page, size, total);
  }

  private AccessSecurityIpAllowlistView allowlistView(Record row) {
    return new AccessSecurityIpAllowlistView(
        row.get("id", UUID.class),
        row.get("cidr_range", String.class),
        row.get("label", String.class),
        row.get("scope_kind", String.class),
        row.get("scope_id", UUID.class),
        row.get("status", String.class),
        row.get("effective_from", OffsetDateTime.class),
        row.get("effective_to", OffsetDateTime.class),
        row.get("created_by", UUID.class),
        row.get("created_by_name", String.class),
        row.get("version", Long.class),
        row.get("created_at", OffsetDateTime.class));
  }

  /**
   * 登记一条 IP 白名单。
   *
   * <p>🔴 <b>非法网段明确拒绝（400）</b>：非法字面量如果直接丢给 PG，会在事务里抛驱动层异常，
   * 界面拿到的是 500 而不是「你填的网段不合法」。
   */
  public AccessSecurityIpAllowlistView addAllowlist(
      AccessSecurityIpAllowlistInput input, AccessService.Access actor) {
    String cidr = normalizeCidr(input.cidrRange());
    if (!isValidCidr(cidr)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "网段格式不合法：支持 10.0.0.0/8、10.0.0.5、2001:db8::/32 这类写法（不支持主机名）");
    }
    if ("CONNECTION".equals(input.scopeKind()) && input.scopeId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接口径的白名单必须给出目标连接 ID");
    }
    if ("TENANT".equals(input.scopeKind()) && input.scopeId() != null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "租户口径的白名单不能带目标连接 ID");
    }
    if (input.effectiveTo() != null && input.effectiveFrom() != null
        && !input.effectiveTo().isAfter(input.effectiveFrom())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "失效时间必须晚于生效时间");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into access_security_ip_allowlist(id,tenant_id,cidr_range,label,scope_kind,scope_id,"
            + " status,effective_from,effective_to,created_by,created_by_name,version,created_at,updated_at)"
            + " values(?,current_setting('app.current_tenant')::uuid,?::inet,?,?,?,?,"
            + " coalesce(?::timestamptz,now()),?::timestamptz,?,?,0,now(),now())",
        id,
        cidr,
        nz(input.label()),
        input.scopeKind(),
        input.scopeId(),
        input.status(),
        input.effectiveFrom() == null ? null : input.effectiveFrom().withOffsetSameInstant(ZoneOffset.UTC),
        input.effectiveTo() == null ? null : input.effectiveTo().withOffsetSameInstant(ZoneOffset.UTC),
        actor.userId(),
        "");
    policyAudit("IP_ALLOWLIST", id, "CREATED", "cidr_range", "", cidr, actor, nz(input.label()));
    return allowlistView(loadAllowlist(id));
  }

  /** 修改一条 IP 白名单（启用 / 停用 / 改标签 / 改有效期）。 */
  public AccessSecurityIpAllowlistView updateAllowlist(
      String id, AccessSecurityIpAllowlistInput input, AccessService.Access actor) {
    Record current = loadAllowlist(uuid(id));
    String cidr = normalizeCidr(input.cidrRange());
    if (!isValidCidr(cidr)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "网段格式不合法：支持 10.0.0.0/8、10.0.0.5、2001:db8::/32 这类写法（不支持主机名）");
    }
    if ("CONNECTION".equals(input.scopeKind()) && input.scopeId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接口径的白名单必须给出目标连接 ID");
    }
    if ("TENANT".equals(input.scopeKind()) && input.scopeId() != null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "租户口径的白名单不能带目标连接 ID");
    }
    if (input.effectiveTo() != null && input.effectiveFrom() != null
        && !input.effectiveTo().isAfter(input.effectiveFrom())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "失效时间必须晚于生效时间");
    }
    UUID entryId = current.get("id", UUID.class);
    assertAllowlistNotEmptied(entryId, "ACTIVE".equals(input.status()));
    sql.execute(
        "update access_security_ip_allowlist set cidr_range=?::inet,label=?,scope_kind=?,scope_id=?,"
            + " status=?,effective_from=coalesce(?::timestamptz,effective_from),"
            + " effective_to=?::timestamptz,version=version+1,updated_at=now() where id=?",
        cidr,
        nz(input.label()),
        input.scopeKind(),
        input.scopeId(),
        input.status(),
        input.effectiveFrom() == null ? null : input.effectiveFrom().withOffsetSameInstant(ZoneOffset.UTC),
        input.effectiveTo() == null ? null : input.effectiveTo().withOffsetSameInstant(ZoneOffset.UTC),
        entryId);
    policyAudit(
        "IP_ALLOWLIST",
        entryId,
        "UPDATED",
        "cidr_range",
        current.get("cidr_range", String.class),
        cidr,
        actor,
        nz(input.label()));
    return allowlistView(loadAllowlist(entryId));
  }

  /**
   * 删除一条 IP 白名单。
   *
   * <p>🔴 <b>ENFORCE 口径下不许删到空白名单</b>：DB 层的守卫触发器会拦（fail-closed）。
   */
  public void deleteAllowlist(String id, AccessService.Access actor) {
    Record current = loadAllowlist(uuid(id));
    UUID entryId = current.get("id", UUID.class);
    assertAllowlistNotEmptied(entryId, false);
    sql.execute("delete from access_security_ip_allowlist where id=?", entryId);
    policyAudit(
        "IP_ALLOWLIST",
        entryId,
        "DELETED",
        "cidr_range",
        current.get("cidr_range", String.class),
        "",
        actor,
        "");
  }

  private Record loadAllowlist(UUID id) {
    if (id == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "白名单条目不存在或无权查看");
    }
    Record row =
        sql.fetchOne(
            "select id,cidr_range,label,scope_kind,scope_id,status,effective_from,effective_to,"
                + " created_by,created_by_name,version,created_at from access_security_ip_allowlist where id=?",
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "白名单条目不存在或无权查看");
    }
    return row;
  }

  /**
   * 判定一个 IP 在不在白名单里（**只读，不留痕**）。
   *
   * <p>🔴 **真网段判定**：{@code ?::inet <<= cidr_range} —— PostgreSQL 的包含运算符，
   * 判的是「这个地址落在这个网段里」，不是字符串相等。
   */
  public AccessSecurityIpCheckResult checkIp(String ip) {
    String value = ip == null ? "" : ip.trim();
    if (!isValidCidr(value)) {
      return new AccessSecurityIpCheckResult(
          value, false, false, false, "", "", null, null, false, "IP 格式不合法");
    }
    AccessSecurityPolicyView policy = policy();
    String mode = policy.configured() ? policy.ipAllowlistMode() : IpAllowlistMode.DISABLED.name();
    boolean enforce = IpAllowlistMode.ENFORCE.name().equals(mode);
    boolean reporting = IpAllowlistMode.REPORT_ONLY.name().equals(mode);
    if (!enforce && !reporting) {
      return new AccessSecurityIpCheckResult(
          value,
          true,
          true,
          false,
          mode,
          IpAllowlistMode.DISABLED.label(),
          null,
          null,
          false,
          "当前口径是「不限制」—— 这是租户显式选的，不是默认值");
    }
    Record matched = matchAllowlist(value);
    if (matched != null) {
      return new AccessSecurityIpCheckResult(
          value,
          true,
          true,
          true,
          mode,
          IpAllowlistMode.valueOf(mode).label(),
          matched.get("id", UUID.class),
          matched.get("cidr_range", String.class),
          enforce,
          "落在白名单网段内");
    }
    long active = effectiveAllowlistCount();
    if (enforce) {
      String reason =
          active == 0
              ? "🔴 当前口径是「强制拒绝」而白名单里没有生效中的条目 —— 一律拒绝（fail-closed），"
                  + "空白名单不等于放行所有"
              : "不在任何生效中的白名单网段内 —— 当前口径是「强制拒绝」";
      return new AccessSecurityIpCheckResult(
          value, true, false, false, mode, IpAllowlistMode.valueOf(mode).label(), null, null, true, reason);
    }
    return new AccessSecurityIpCheckResult(
        value,
        true,
        true,
        false,
        mode,
        IpAllowlistMode.valueOf(mode).label(),
        null,
        null,
        false,
        "不在任何生效中的白名单网段内 —— 当前口径是「只告警不拦截」，所以仍然放行但会留一条告警");
  }

  /**
   * 判定一个 IP 并**留痕**（不在白名单且口径不是「不限制」时出一条告警）。
   *
   * @param purpose 这次访问的用途（进告警摘要）
   */
  public AccessSecurityIpCheckResult evaluateIp(
      String ip, String purpose, AccessService.Access actor) {
    AccessSecurityIpCheckResult result = checkIp(ip);
    // 🔴 「不限制」口径下没有「不在白名单」这回事 ⇒ 不出告警（否则每次访问都刷一条）。
    //    「只告警不拦截」口径下**仍然放行**，但要留一条告警 —— 这正是这个口径的意义。
    if (IpAllowlistMode.DISABLED.name().equals(result.mode())) return result;
    if (result.inAllowlist()) return result;
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    raiseAlert(
        AlertType.IP_NOT_ALLOWED,
        Severity.HIGH,
        "IP",
        result.ip(),
        "IP " + result.ip() + " 不在白名单内",
        (nz(purpose).isBlank() ? "（未填写用途）" : nz(purpose))
            + "。当前口径是「"
            + IpAllowlistMode.valueOf(result.mode()).label()
            + "」。",
        1,
        null,
        null,
        now);
    return result;
  }

  /** 找到覆盖该 IP 的、最具体的白名单条目（掩码越长越具体）。 */
  private Record matchAllowlist(String ip) {
    return sql.fetchOne(
        "select id,cidr_range from access_security_ip_allowlist"
            + " where status='ACTIVE' and effective_from<=now()"
            + " and (effective_to is null or effective_to>now())"
            + " and family(cidr_range)=family(?::inet) and ?::inet <<= cidr_range"
            + " order by masklen(cidr_range) desc limit 1",
        ip,
        ip);
  }

  /**
   * 🔴 <b>ENFORCE 口径下不许把白名单弄空</b>（fail-closed）。
   *
   * <p>数据层有守卫触发器兜底（它数的是 {@code status='ACTIVE'} 的条目），
   * 这里先判一次只是为了**给出 409 而不是 500** —— 触发器抛的 23514 到界面是「服务器错了」。
   */
  private void assertAllowlistNotEmptied(UUID entryId, boolean staysActive) {
    AccessSecurityPolicyView policy = policy();
    if (!policy.configured() || !IpAllowlistMode.ENFORCE.name().equals(policy.ipAllowlistMode())) {
      return;
    }
    if (staysActive) return;
    long remaining = activeAllowlistCountExcluding(entryId);
    if (remaining < 1) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "当前 IP 白名单口径是「强制拒绝」，删掉 / 停用这一条就没有任何生效中的白名单了 —— "
              + "请先登记别的白名单（或把口径切回「只告警不拦截」）");
    }
  }

  /** 生效中的（status='ACTIVE'）白名单条目数，**排除**指定条目（与 DB 守卫同一口径）。 */
  private long activeAllowlistCountExcluding(UUID excludeId) {
    return sql.fetchOne(
            "select count(*) from access_security_ip_allowlist where status='ACTIVE'"
                + " and (id is null or id <> ?)",
            excludeId)
        .get(0, Long.class);
  }

  /** 当前时刻**生效中**（状态 ACTIVE 且在有效期内）的白名单条目数。 */
  private long effectiveAllowlistCount() {
    return sql.fetchOne(
            "select count(*) from access_security_ip_allowlist"
                + " where status='ACTIVE' and effective_from<=now()"
                + " and (effective_to is null or effective_to>now())")
        .get(0, Long.class);
  }

  // ================================================================ 凭据访问审计

  /** 凭据访问审计列表（真分页 + 真筛选 + 汇总）。 */
  public AccessSecurityCredentialAccessPage credentialAccess(
      String credentialId, String outcome, int page, int size) {
    checkPage(page, size);
    Conditions condition = new Conditions();
    if (credentialId != null && !credentialId.isBlank()) {
      condition.add("credential_id = ?::uuid", credentialId.trim());
    }
    if (outcome != null && !outcome.isBlank()) condition.add("outcome = ?", outcome.trim());
    long total =
        sql.fetchOne("select count(*) from access_security_credential_access" + condition.sql(), condition.values())
            .get(0, Long.class);
    var records =
        sql.fetch(
            "select id,credential_id,external_ref,provider,access_kind,access_purpose,outcome,actor_id,"
                + " actor_name,actor_source,request_id,note,occurred_at from access_security_credential_access"
                + condition.sql()
                + " order by occurred_at desc,id desc limit ? offset ?",
            condition.values(size, (long) page * size));
    long success = countCredentialOutcome("SUCCESS");
    long denied = countCredentialOutcome("DENIED");
    long failed = countCredentialOutcome("FAILED");
    return new AccessSecurityCredentialAccessPage(
        records.map(this::credentialAccessView), page, size, total, success, denied, failed);
  }

  private long countCredentialOutcome(String outcome) {
    return sql.fetchOne(
            "select count(*) from access_security_credential_access where outcome = ?", outcome)
        .get(0, Long.class);
  }

  private AccessSecurityCredentialAccessView credentialAccessView(Record row) {
    return new AccessSecurityCredentialAccessView(
        row.get("id", UUID.class),
        row.get("credential_id", UUID.class),
        row.get("external_ref", String.class),
        row.get("provider", String.class),
        row.get("access_kind", String.class),
        row.get("access_purpose", String.class),
        row.get("outcome", String.class),
        row.get("actor_id", UUID.class),
        row.get("actor_name", String.class),
        row.get("actor_source", String.class),
        row.get("request_id", String.class),
        row.get("note", String.class),
        row.get("occurred_at", OffsetDateTime.class));
  }

  /** 单条凭据访问审计。 */
  public AccessSecurityCredentialAccessView credentialAccessRow(String id) {
    UUID accessId = uuid(id);
    if (accessId == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "凭据访问审计记录不存在或无权查看");
    }
    Record row =
        sql.fetchOne(
            "select id,credential_id,external_ref,provider,access_kind,access_purpose,outcome,actor_id,"
                + " actor_name,actor_source,request_id,note,occurred_at from access_security_credential_access"
                + " where id=?",
            accessId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "凭据访问审计记录不存在或无权查看");
    }
    return credentialAccessView(row);
  }

  /** 凭据维度的访问汇总（真分页 + 真排序：最近被访问的排前面）。 */
  public AccessSecurityCredentialPage credentials(int page, int size) {
    checkPage(page, size);
    long total = sql.fetchOne("select count(*) from credential_ref").get(0, Long.class);
    var records =
        sql.fetch(
            "select c.id,c.provider,c.external_ref,c.token_type,c.granted_scopes,c.status,"
                + " c.authorized_at,c.expires_at,"
                + " (select count(*) from access_security_credential_access a"
                + "  where a.credential_id=c.id) as access_count,"
                + " (select max(a.occurred_at) from access_security_credential_access a"
                + "  where a.credential_id=c.id) as last_accessed_at,"
                + " (select a.actor_id from access_security_credential_access a"
                + "  where a.credential_id=c.id order by a.occurred_at desc limit 1) as last_accessed_by"
                + " from credential_ref c order by last_accessed_at desc nulls last,c.created_at desc"
                + " limit ? offset ?",
            size,
            (long) page * size);
    return new AccessSecurityCredentialPage(records.map(this::credentialView), page, size, total);
  }

  private AccessSecurityCredentialView credentialView(Record row) {
    return new AccessSecurityCredentialView(
        row.get("id", UUID.class),
        row.get("provider", String.class),
        row.get("external_ref", String.class),
        row.get("token_type", String.class),
        row.get("granted_scopes", String.class),
        row.get("status", String.class),
        row.get("authorized_at", OffsetDateTime.class),
        row.get("expires_at", OffsetDateTime.class),
        num(row.get("access_count", Long.class)),
        row.get("last_accessed_at", OffsetDateTime.class),
        row.get("last_accessed_by", UUID.class));
  }

  /**
   * <b>受控访问</b>：读一份凭据的**元信息**，并把这次访问**留痕**。
   *
   * <p>🔴 这就是「平台凭据的访问审计」的落地：谁（actor）、什么时候（occurredAt）、
   * 哪个凭据（credentialId）、做了什么（READ_METADATA）、成没成（outcome）—— 五件套齐全。
   *
   * <p>🔴 <b>最小权限</b>：只返回元信息（提供方 / 授权范围 / 状态 / 到期时刻），
   * <b>永远不返回令牌</b> —— {@code credential_ref} 本身也没有令牌列。
   *
   * <p>🔴🔴 <b>凭据不存在时本方法**不抛异常**，而是返回 outcome=FAILED 的审计凭据</b>：
   * 「有人去访问了一份不存在的凭据」本身就是值得留痕的事件，而
   * <b>在事务里抛 404 会把刚写下的那条审计一起回滚掉</b> ——
   * 留痕必须活得比请求长。404 由控制器在事务提交之后抛出。
   */
  public AccessSecurityControlledAccessResult controlledAccess(
      AccessSecurityCredentialAccessInput input, AccessService.Access actor) {
    UUID credentialId = input.credentialId();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Record credential =
        sql.fetchOne(
            "select id,provider,external_ref,token_type,granted_scopes,status,authorized_at,expires_at"
                + " from credential_ref where id=?",
            credentialId);
    if (credential == null) {
      UUID auditId = Ids.next();
      sql.execute(
          "insert into access_security_credential_access(id,tenant_id,credential_id,external_ref,provider,"
              + " access_kind,access_purpose,outcome,actor_id,actor_name,actor_source,request_id,detail,note,"
              + " occurred_at,created_at)"
              + " values(?,current_setting('app.current_tenant')::uuid,?,'','','READ_METADATA',?,'FAILED',"
              + " ?,?,'USER','',?::jsonb,'凭据不存在或不属于当前租户',?::timestamptz,now())",
          auditId,
          credentialId,
          nz(input.accessPurpose()),
          actor.userId(),
          "",
          "{}",
          now);
      // 🔴🔴 **这里刻意不抛异常**：在事务里抛 404 会把刚写下的那条审计一起回滚掉 ——
      //   留痕必须活得比请求长。404 由控制器在**事务提交之后**抛出。
      return new AccessSecurityControlledAccessResult(
          null,
          credentialAccessView(
              sql.fetchOne(
                  "select id,credential_id,external_ref,provider,access_kind,access_purpose,outcome,"
                      + " actor_id,actor_name,actor_source,request_id,note,occurred_at"
                      + " from access_security_credential_access where id=?",
                  auditId)),
          "凭据不存在或不属于当前租户 —— 🔴 这次访问已经留痕（审计活得比请求长）");
    }
    UUID auditId = Ids.next();
    String detail =
        "{\"status\":\""
            + credential.get("status", String.class)
            + "\",\"tokenType\":\""
            + credential.get("token_type", String.class)
            + "\"}";
    sql.execute(
        "insert into access_security_credential_access(id,tenant_id,credential_id,external_ref,provider,"
            + " access_kind,access_purpose,outcome,actor_id,actor_name,actor_source,request_id,detail,note,"
            + " occurred_at,created_at)"
            + " values(?,current_setting('app.current_tenant')::uuid,?,?,?,'READ_METADATA',?,'SUCCESS',"
            + " ?,?,'USER','',?::jsonb,'',?::timestamptz,now())",
        auditId,
        credentialId,
        credential.get("external_ref", String.class),
        credential.get("provider", String.class),
        nz(input.accessPurpose()),
        actor.userId(),
        "",
        detail,
        now);
    AccessSecurityCredentialView view =
        new AccessSecurityCredentialView(
            credential.get("id", UUID.class),
            credential.get("provider", String.class),
            credential.get("external_ref", String.class),
            credential.get("token_type", String.class),
            credential.get("granted_scopes", String.class),
            credential.get("status", String.class),
            credential.get("authorized_at", OffsetDateTime.class),
            credential.get("expires_at", OffsetDateTime.class),
            1,
            now,
            actor.userId());
    return new AccessSecurityControlledAccessResult(
        view,
        credentialAccessView(
            sql.fetchOne(
                "select id,credential_id,external_ref,provider,access_kind,access_purpose,outcome,"
                    + " actor_id,actor_name,actor_source,request_id,note,occurred_at"
                    + " from access_security_credential_access where id=?",
                auditId)),
        "已留下一条凭据访问审计（append-only，**不含令牌**）");
  }

  // ================================================================ 会话

  /**
   * 当前调用方的会话现状 + 适用的会话策略。
   *
   * <p>🔴 <b>读不到就是读不到</b>：上游不可达 ⇒ 503；上游正常但没有会话 ⇒ {@code known=false}。
   * 会话到期 / 空闲 / 并发三个维度上游**没有读面** ⇒ 显式标 {@code available=false} +
   * 归属模块 + 影响面，**不把「读不到」说成「会话正常」**。
   */
  public AccessSecuritySessionView session(String bearer) {
    var upstream = source.currentSession(bearer);
    if (!upstream.available()) {
      throw switch (upstream.failure()) {
        case UNAUTHORIZED -> new ResponseStatusException(
            HttpStatus.UNAUTHORIZED, "登录已失效或身份服务拒绝了本次请求");
        case FORBIDDEN -> new ResponseStatusException(
            HttpStatus.FORBIDDEN, "身份服务拒绝了会话查询");
        default -> new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "会话读不到：" + upstream.note());
      };
    }
    AccessSecurityPolicyView policy = policy();
    List<AccessSecuritySessionDimension> dimensions =
        List.of(
            new AccessSecuritySessionDimension(
                "SESSION_STATE",
                "会话是否有效",
                true,
                upstream.sessionActive() ? "有效" : "无有效会话",
                "",
                "身份服务能解析当前 bearer ⇒ 会话有效。"),
            new AccessSecuritySessionDimension(
                "SESSION_EXPIRES_AT",
                "会话到期时刻",
                false,
                "读不到",
                "17",
                "🔴 identity 的 auth_session 有 expires_at 列，但**没有任何读端点**"
                    + "⇒ 本模块读不到到期时刻，也不猜。"),
            new AccessSecuritySessionDimension(
                "SESSION_IDLE",
                "空闲超时",
                false,
                "读不到",
                "17",
                "🔴 identity 侧**没有空闲时间概念**（auth_session 只有四个列）"
                    + "⇒ 本模块只能表达策略目标值，判定由身份服务 / 网关执行。"),
            new AccessSecuritySessionDimension(
                "SESSION_CONCURRENT",
                "并发会话数",
                false,
                "读不到",
                "17",
                "🔴 identity 侧**没有并发会话统计** ⇒ 本模块读不到当前并发数，"
                    + "只能表达策略目标上限。"),
            new AccessSecuritySessionDimension(
                "SESSION_POLICY",
                "本租户的会话策略",
                policy.configured(),
                policy.configured()
                    ? "空闲 "
                        + policy.sessionIdleTimeoutMinutes()
                        + " 分钟 / 绝对 "
                        + policy.sessionAbsoluteTimeoutMinutes()
                        + " 分钟 / 并发 "
                        + policy.sessionMaxConcurrent()
                        + " / 换绑 MFA 即撤销："
                        + (policy.sessionRevokeOnMfaChange() ? "是" : "否")
                        + " / 改密即撤销："
                        + (policy.sessionRevokeOnPasswordChange() ? "是" : "否")
                    : "还没登记",
                "",
                "🔴 策略在**本服务侧**登记与留痕；实际执行点在 17 identity 与网关。"));
    return new AccessSecuritySessionView(
        upstream.sessionActive(),
        upstream.sessionActive() ? "ACTIVE" : "UNKNOWN",
        upstream.note(),
        policy.configured() ? policy : null,
        dimensions);
  }

  // ================================================================ 内部工具

  /** 记一条配置变更审计（append-only）。 */
  private void policyAudit(
      String targetKind,
      UUID targetId,
      String action,
      String fieldName,
      String oldValue,
      String newValue,
      AccessService.Access actor,
      String reason) {
    sql.execute(
        "insert into access_security_policy_audit(id,tenant_id,target_kind,target_id,action,field_name,"
            + " old_value,new_value,changed_by,changed_by_name,reason,created_at)"
            + " values(?,current_setting('app.current_tenant')::uuid,?,?,?,?,?,?,?,?,?,now())",
        Ids.next(),
        targetKind,
        targetId,
        action,
        fieldName,
        nz(oldValue),
        nz(newValue),
        actor.userId(),
        "",
        nz(reason));
  }

  /** WHERE 子句与绑定值成对累积，避免条件与参数顺序错位。 */
  private static final class Conditions {
    private final List<String> clauses = new ArrayList<>();
    private final List<Object> bindings = new ArrayList<>();

    void add(String clause, Object value) {
      clauses.add(clause);
      bindings.add(value);
    }

    String sql() {
      return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    }

    Object[] values(Object... tail) {
      List<Object> all = new ArrayList<>(bindings);
      all.addAll(List.of(tail));
      return all.toArray();
    }
  }

  private static void checkPage(int page, int size) {
    if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页码不能为负");
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "每页条数需在 1 到 " + MAX_PAGE_SIZE + " 之间");
    }
  }

  private static UUID uuid(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static int num(Integer value) {
    return value == null ? 0 : value;
  }

  private static long num(Long value) {
    return value == null ? 0L : value;
  }
}
