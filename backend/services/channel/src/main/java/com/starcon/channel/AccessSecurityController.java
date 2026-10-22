package com.starcon.channel;

import static com.starcon.channel.AccessSecurityModels.*;

import jakarta.validation.Valid;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 16.5.2.1 登录与访问安全。
 *
 * <p>蓝图：「管理平台账号与系统的访问安全：多因子认证、登录审计、异常登录告警、IP 白名单、
 * 会话管理；平台授权凭据的访问审计。输入：认证配置；登录事件；访问审计事件。
 * 输出：安全配置 + 审计记录 + 告警。关系：上游 = 17 identity；下游 = 2 凭据引用。
 * <b>平台凭据是最值钱的资产</b>，访问必须有审计且最小权限。」
 *
 * <p>🔴 端点清单（命名空间 {@code /channel/access-security}，与既有的
 * {@code /channel/buyer-risk-scores}、{@code /channel/ip-risk-library}、
 * {@code /channel/compliance-precheck} 等<b>并列而不重叠</b>）：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：逐能力面 available + ownerModule + 影响面。</li>
 *   <li>{@code GET /policy} / {@code PUT /policy} —— 安全策略（MFA 段 + 会话段 + IP 口径 + 阈值）。
 *       <b>没登记过返回 configured=false</b>，而不是 404 —— 404 会让界面把「没登记」误读成「读失败」。</li>
 *   <li>{@code GET /mfa/registrations} —— MFA 登记列表（真分页 + 真筛选）。</li>
 *   <li>{@code POST /mfa/registrations} —— 登记 MFA 因子（状态 PENDING，<b>不生成明文因子密钥</b>）。</li>
 *   <li>{@code POST /mfa/registrations/{id}/activate} / {@code /disable} —— 绑定状态机。</li>
 *   <li>{@code GET /mfa/challenges} / {@code POST /mfa/challenges} ——
 *       挑战下发记录（<b>本模块不发送任何短信 / 邮件</b>）。</li>
 *   <li>{@code POST /mfa/challenges/{challengeId}/attempts} —— 验证尝试（<b>append-only</b>）。</li>
 *   <li>{@code GET /login-audit} —— 登录审计（本地留存投影，<b>无记录 ≠ 无风险</b>）。</li>
 *   <li>{@code POST /login-audit/scan} —— 🔴 <b>跑一次扫描</b>：真去上游读、真落库、真判异常、真出告警。
 *       <b>上游不可达一律 503</b>。</li>
 *   <li>{@code GET /alerts} / {@code GET /alerts/{id}} —— 告警（真分页 + 真筛选 + 处置留痕）。</li>
 *   <li>{@code POST /alerts/{id}/acknowledge} / {@code /ignore} / {@code /resolve} —— 处置状态机（必填理由）。</li>
 *   <li>{@code GET /ip-allowlist} / {@code POST /ip-allowlist} / {@code PUT|DELETE /ip-allowlist/{id}} ——
 *       IP 白名单（<b>真网段判定</b>，非法网段 400）。</li>
 *   <li>{@code GET /ip-allowlist/check} —— 只读判定；{@code POST /ip-allowlist/evaluate} —— 判定 + 留痕。</li>
 *   <li>{@code GET /credential-access} / {@code GET /credential-access/{id}} ——
 *       平台授权凭据的访问审计（<b>append-only</b>）。</li>
 *   <li>{@code GET /credential-access/credentials} —— 按凭据汇总（哪些凭据从没被访问过）。</li>
 *   <li>{@code POST /credential-access} —— 🔴 <b>受控访问</b>：读凭据**元信息**并留痕，
 *       <b>永远不返回令牌</b>。</li>
 *   <li>{@code GET /session} —— 当前调用方的会话现状 + 适用的会话策略。</li>
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「删除登录审计」端点（append-only）、
 * 没有「删除告警」端点（处置走状态机，不是删除）、
 * 没有「改写审计」的端点、<b>没有「另一套会话」端点</b>（会话实体在 identity）——
 * <b>放行一个不存在的入口比 404 更糟</b>（界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code identity.audit.read}（读审计与配置）、
 * {@code channel.operation.*}（管理安全配置）与 {@code channel.connection.read}（读凭据元信息），
 * <b>不新增任何权限码、也不改 identity 服务</b>。
 * 租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/channel/access-security")
public class AccessSecurityController {

  private final AccessService access;
  private final AccessSecurityService service;

  public AccessSecurityController(AccessService access, AccessSecurityService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：逐能力面 available + ownerModule + 影响面。 */
  @GetMapping("/capabilities")
  public AccessSecurityCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "identity.audit.read", a -> service.capabilities());
  }

  // ---- 策略（MFA 段 + 会话段 + IP 口径 + 阈值） ----

  /** 安全策略。没登记过返回 configured=false（**不预填任何默认阈值**）。 */
  @GetMapping("/policy")
  public AccessSecurityPolicyView policy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "identity.audit.read", a -> service.policy());
  }

  /** 登记 / 修改策略（upsert，一租户一条）。阈值必须由租户显式给。 */
  @PutMapping("/policy")
  public AccessSecurityPolicyView savePolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AccessSecurityPolicyInput input) {
    return call(auth, "channel.operation.update", a -> service.savePolicy(input, a));
  }

  // ---- MFA 登记 ----

  /** MFA 登记列表（真分页 + 真筛选）。 */
  @GetMapping("/mfa/registrations")
  public AccessSecurityMfaRegistrationPage registrations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String userId,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth, "identity.audit.read", a -> service.registrations(userId, status, page, size));
  }

  /** 登记一个 MFA 因子（状态 PENDING；**不生成、不保存明文因子密钥**）。 */
  @PostMapping("/mfa/registrations")
  public AccessSecurityMfaRegistrationView register(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AccessSecurityMfaRegistrationInput input) {
    return call(auth, "channel.operation.create", a -> service.register(input, a));
  }

  /** MFA 登记：绑定完成（→ 已启用）。 */
  @PostMapping("/mfa/registrations/{id}/activate")
  public AccessSecurityMfaRegistrationView activateRegistration(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.update", a -> service.activateRegistration(id, a));
  }

  /** MFA 登记：停用。 */
  @PostMapping("/mfa/registrations/{id}/disable")
  public AccessSecurityMfaRegistrationView disableRegistration(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.update", a -> service.disableRegistration(id, a));
  }

  // ---- MFA 挑战 ----

  /** MFA 挑战记录（真分页 + 真筛选）。 */
  @GetMapping("/mfa/challenges")
  public AccessSecurityMfaChallengePage challenges(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String registrationId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth, "identity.audit.read", a -> service.challenges(registrationId, page, size));
  }

  /**
   * 登记一次挑战下发。
   *
   * <p>🔴 <b>本端点不发送任何短信 / 邮件</b> —— 本仓没有真实 MFA 服务商凭据。
   */
  @PostMapping("/mfa/challenges")
  public AccessSecurityMfaChallengeView issueChallenge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AccessSecurityMfaChallengeInput input) {
    return call(auth, "channel.operation.create", a -> service.issueChallenge(input, a));
  }

  /** 登记一次验证尝试（**append-only：新的一行，不改上一行**）。 */
  @PostMapping("/mfa/challenges/{challengeId}/attempts")
  public AccessSecurityMfaChallengeView attemptChallenge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String challengeId,
      @Valid @RequestBody AccessSecurityMfaChallengeAttemptInput input) {
    return call(
        auth, "channel.operation.create", a -> service.attemptChallenge(challengeId, input, a));
  }

  // ---- 登录审计 ----

  /** 登录审计（本地留存投影）。**没有记录 ≠ 没有风险**，known=false 时界面要显示「还没回填」。 */
  @GetMapping("/login-audit")
  public AccessSecurityLoginAuditPage loginAudit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String actorName,
      @RequestParam(required = false) String outcome,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth, "identity.audit.read", a -> service.loginAudit(actorName, outcome, page, size));
  }

  /**
   * 🔴 跑一次登录审计扫描：真去 identity 读登录事件、真落本地审计、真判异常、真出告警。
   *
   * <p>🔴 <b>上游不可达一律 503</b>；<b>上游正常但查无记录 ⇒ known=false</b>。
   */
  @PostMapping("/login-audit/scan")
  public AccessSecurityScanResult scan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) AccessSecurityScanInput input) {
    return call(auth, "channel.operation.create", a -> service.scan(auth, input, a));
  }

  // ---- 告警 ----

  /** 告警列表（真分页 + 真筛选 + 汇总）。 */
  @GetMapping("/alerts")
  public AccessSecurityAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth, "identity.audit.read", a -> service.alerts(type, severity, status, page, size));
  }

  /** 告警详情（含处置留痕）。 */
  @GetMapping("/alerts/{id}")
  public AccessSecurityAlertView alert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "identity.audit.read", a -> service.alert(id));
  }

  /** 确认告警（新警 → 已确认）。 */
  @PostMapping("/alerts/{id}/acknowledge")
  public AccessSecurityAlertView acknowledge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody AccessSecurityAlertActionInput input) {
    return call(auth, "channel.operation.update", a -> service.acknowledgeAlert(id, input.reason(), a));
  }

  /** 忽略告警（新警 / 已确认 → 已忽略）。 */
  @PostMapping("/alerts/{id}/ignore")
  public AccessSecurityAlertView ignore(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody AccessSecurityAlertActionInput input) {
    return call(auth, "channel.operation.update", a -> service.ignoreAlert(id, input.reason(), a));
  }

  /** 处置告警（新警 / 已确认 → 已处置）。 */
  @PostMapping("/alerts/{id}/resolve")
  public AccessSecurityAlertView resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody AccessSecurityAlertActionInput input) {
    return call(auth, "channel.operation.update", a -> service.resolveAlert(id, input.reason(), a));
  }

  // ---- IP 白名单 ----

  /** IP 白名单列表（真分页 + 真筛选）。 */
  @GetMapping("/ip-allowlist")
  public AccessSecurityIpAllowlistPage allowlist(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "identity.audit.read", a -> service.allowlist(status, page, size));
  }

  /** 登记一条 IP 白名单（**非法网段 400**）。 */
  @PostMapping("/ip-allowlist")
  public AccessSecurityIpAllowlistView addAllowlist(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AccessSecurityIpAllowlistInput input) {
    return call(auth, "channel.operation.create", a -> service.addAllowlist(input, a));
  }

  /** 修改一条 IP 白名单。 */
  @PutMapping("/ip-allowlist/{id}")
  public AccessSecurityIpAllowlistView updateAllowlist(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody AccessSecurityIpAllowlistInput input) {
    return call(auth, "channel.operation.update", a -> service.updateAllowlist(id, input, a));
  }

  /** 删除一条 IP 白名单（**ENFORCE 口径下不许删到空白名单**，DB 守卫会拦）。 */
  @DeleteMapping("/ip-allowlist/{id}")
  public void deleteAllowlist(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    call(auth, "channel.operation.update", a -> {
      service.deleteAllowlist(id, a);
      return null;
    });
  }

  /** 只读判定一个 IP 在不在白名单（真网段判定，不留痕）。 */
  @GetMapping("/ip-allowlist/check")
  public AccessSecurityIpCheckResult checkIp(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String ip) {
    return call(auth, "identity.audit.read", a -> service.checkIp(ip));
  }

  /** 判定一个 IP 并**留痕**（不在白名单且口径不是「不限制」时出一条告警）。 */
  @PostMapping("/ip-allowlist/evaluate")
  public AccessSecurityIpCheckResult evaluateIp(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String ip,
      @RequestParam(required = false) String purpose) {
    return call(auth, "channel.operation.create", a -> service.evaluateIp(ip, purpose, a));
  }

  // ---- 平台授权凭据的访问审计 ----

  /** 凭据访问审计列表（真分页 + 真筛选 + 汇总）。 */
  @GetMapping("/credential-access")
  public AccessSecurityCredentialAccessPage credentialAccess(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String credentialId,
      @RequestParam(required = false) String outcome,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth, "identity.audit.read", a -> service.credentialAccess(credentialId, outcome, page, size));
  }

  /**
   * 按凭据汇总（哪些凭据从没被访问过、哪些被访问得最多）。
   *
   * <p>🔴 权限码用 {@code channel.connection.read} 而不是 {@code identity.audit.read}：
   * {@code credential_ref} 上有一条 RESTRICTIVE 策略（channel V1 的 {@code credential_scope}），
   * 要求 {@code app.permission} 本身就是一个 {@code channel.connection.*} 权限 ——
   * 用别的权限码读它会**读到零行**（不是报错，是静默的空）。
   */
  @GetMapping("/credential-access/credentials")
  public AccessSecurityCredentialPage credentials(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "channel.connection.read", a -> service.credentials(page, size));
  }

  /** 单条凭据访问审计。 */
  @GetMapping("/credential-access/{id}")
  public AccessSecurityCredentialAccessView credentialAccessRow(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "identity.audit.read", a -> service.credentialAccessRow(id));
  }

  /**
   * 🔴 <b>受控访问</b>：读一份凭据的<b>元信息</b>并把这次访问<b>留痕</b>。
   *
   * <p>🔴 <b>永远不返回令牌</b> —— {@code credential_ref} 本身也没有令牌列。
   *
   * <p>🔴 404 是在<b>事务提交之后</b>抛的：服务层把「凭据不存在」表达成一条
   * {@code outcome=FAILED} 的审计凭据并正常返回 —— 在事务里抛 404 会把
   * 刚写下那条审计一起回滚掉，<b>留痕必须活得比请求长</b>。
   */
  @PostMapping("/credential-access")
  public AccessSecurityControlledAccessResult controlledAccess(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AccessSecurityCredentialAccessInput input) {
    var result = call(auth, "channel.connection.read", a -> service.controlledAccess(input, a));
    if (result.credential() == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "凭据不存在或无权查看");
    }
    return result;
  }

  // ---- 会话 ----

  /** 当前调用方的会话现状 + 适用的会话策略（**读不到就是读不到，不猜**）。 */
  @GetMapping("/session")
  public AccessSecuritySessionView session(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "identity.audit.read", a -> service.session(auth));
  }
}
