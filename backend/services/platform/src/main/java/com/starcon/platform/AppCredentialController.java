package com.starcon.platform;

import static com.starcon.platform.AppCredentialModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 18.1.1.1 应用与凭证的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /platform/apps}</b>：与卷 18 的下游（18.1.2 Webhook / 18.1.3 数据交换 /
 * 18.2 外部系统对接）刻意不共用前缀 —— 首 token 也刻意不同，
 * 免得「应用与凭证」被别的模块的前缀吃掉。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code platform.app.read / .create / .update / .revoke}：
 * 吊销是<b>独立</b>的一个码 —— 能改档案的人不该顺手把别人的凭证掐了。
 *
 * <p>⚠️ {@code POST /platform/app-quota-usages} 是**服务端到服务端**的路径（内部密钥），
 * <b>刻意不进浏览器的 api-proxy 白名单</b>：放行它等于让任意浏览器往别人的租户写用量。
 */
@RestController
@RequestMapping("/platform")
public class AppCredentialController {

  private final AccessService access;
  private final AppCredentialService apps;

  public AppCredentialController(AccessService access, AppCredentialService apps) {
    this.access = access;
    this.apps = apps;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.app." + permission);
    return access.run(actor, "platform.app." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与选项

  /** 能力声明：哪些上游真接通、职责怎么切、哪些还没交付、归谁。 */
  @GetMapping("/apps/capabilities")
  public IntegrationAppCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> apps.capabilities());
  }

  /** 筛选项下拉（资源码来自**真实数据**）。 */
  @GetMapping("/apps/options")
  public IntegrationAppOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> apps.options());
  }

  /** 概览：几个数。 */
  @GetMapping("/apps/overview")
  public IntegrationAppOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> apps.overview());
  }

  // ================================================================ 应用档案

  /** 应用列表（真分页 + 真筛选）。 */
  @GetMapping("/apps")
  public IntegrationAppPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String appType,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> apps.apps(keyword, appType, status, page, size));
  }

  /** 注册应用。 */
  @PostMapping("/apps")
  public IntegrationAppRow register(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody IntegrationAppInput input) {
    return call(auth, "create", actor -> apps.register(input, actor));
  }

  /** 应用档案详情（凭证元数据 + 授权范围 + 白名单 + 配额 + 额度判定）。 */
  @GetMapping("/apps/{appId}")
  public IntegrationAppDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId) {
    return call(auth, "read", actor -> apps.detail(appId, true));
  }

  /** 吊销应用（**一键吊销**：该应用全部凭证同时失效）。 */
  @PostMapping("/apps/{appId}/revoke")
  public IntegrationAppRow revokeApp(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId,
      @RequestParam(required = false) String reason) {
    return call(auth, "revoke", actor -> apps.revokeApp(appId, reason, actor));
  }

  // ================================================================ 凭证

  /** 凭证列表（**只显示摘要前缀与末四位**，没有明文）。 */
  @GetMapping("/apps/{appId}/credentials")
  public List<IntegrationCredentialRow> credentials(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId) {
    return call(auth, "read", actor -> apps.credentials(appId));
  }

  /**
   * 生成凭证 —— **明文只在这一次的响应里出现**。
   *
   * <p>之后任何读路径都取不回明文（库里只有 BCrypt 摘要）。
   */
  @PostMapping("/apps/{appId}/credentials")
  public IntegrationCredentialIssued issueCredential(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId) {
    return call(auth, "update", actor -> apps.issueCredential(appId, actor));
  }

  /** 轮换凭证：生成新明文 + 旧摘要立即失效。 */
  @PostMapping("/apps/{appId}/credentials/rotate")
  public IntegrationCredentialIssued rotateCredential(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId) {
    return call(auth, "update", actor -> apps.rotateCredential(appId, actor));
  }

  /** 吊销一个凭证（泄露时的一键止血）。 */
  @PostMapping("/apps/{appId}/credentials/{credentialId}/revoke")
  public IntegrationCredentialRow revokeCredential(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId,
      @PathVariable UUID credentialId,
      @RequestParam(required = false) String reason) {
    return call(auth, "revoke", actor -> apps.revokeCredential(appId, credentialId, reason, actor));
  }

  /**
   * 凭证自检：对接方来问「到底哪里不通」时，运营当场验一遍。
   *
   * <p>🔴 四个判定**分开**给（凭证 / 授权范围 / IP 白名单 / 配额）——
   * 「凭证对但没授权」和「凭证错」要修的地方完全不同。
   */
  @PostMapping("/apps/{appId}/credentials/verify")
  public IntegrationCredentialVerifyResult verify(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId,
      @Valid @RequestBody IntegrationCredentialVerifyInput input) {
    return call(auth, "read", actor -> apps.verify(appId, input, actor));
  }

  // ================================================================ 授权范围

  /** 授权范围列表。 */
  @GetMapping("/apps/{appId}/scopes")
  public List<IntegrationScopeRow> scopes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId) {
    return call(auth, "read", actor -> apps.scopes(appId));
  }

  /** 授权（幂等：同一 资源 × 操作 重复授权不叠加）。 */
  @PostMapping("/apps/{appId}/scopes")
  public IntegrationScopeRow grantScope(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId,
      @Valid @RequestBody IntegrationScopeInput input) {
    return call(auth, "update", actor -> apps.grantScope(appId, input, actor));
  }

  /** 撤销一条授权。 */
  @DeleteMapping("/apps/{appId}/scopes/{scopeId}")
  public void revokeScope(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId,
      @PathVariable UUID scopeId) {
    call(auth, "update", actor -> {
      apps.revokeScope(appId, scopeId, actor);
      return null;
    });
  }

  // ================================================================ IP 白名单

  /** IP 白名单列表。 */
  @GetMapping("/apps/{appId}/ip-allowlist")
  public List<IntegrationIpAllowlistRow> ipAllowlist(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId) {
    return call(auth, "read", actor -> apps.ipAllowlist(appId));
  }

  /** 新增一条白名单（CIDR 在服务层校验）。 */
  @PostMapping("/apps/{appId}/ip-allowlist")
  public IntegrationIpAllowlistRow addIpAllowlist(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId,
      @Valid @RequestBody IntegrationIpAllowlistInput input) {
    return call(auth, "update", actor -> apps.addIpAllowlist(appId, input, actor));
  }

  /** 删除一条白名单。 */
  @DeleteMapping("/apps/{appId}/ip-allowlist/{entryId}")
  public void removeIpAllowlist(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId,
      @PathVariable UUID entryId) {
    call(auth, "update", actor -> {
      apps.removeIpAllowlist(appId, entryId, actor);
      return null;
    });
  }

  // ================================================================ 调用配额

  /** 配额配置列表。 */
  @GetMapping("/apps/{appId}/quotas")
  public List<IntegrationQuotaRow> quotas(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId) {
    return call(auth, "read", actor -> apps.quotas(appId));
  }

  /** 设置配额（同一窗口类型重复设置 = 覆盖，不是叠加）。 */
  @PostMapping("/apps/{appId}/quotas")
  public IntegrationQuotaRow setQuota(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId,
      @Valid @RequestBody IntegrationQuotaInput input) {
    return call(auth, "update", actor -> apps.setQuota(appId, input, actor));
  }

  /** 额度判定：每个窗口类型一行（未配置的窗口 = 不限，不是限 0 次）。 */
  @GetMapping("/apps/{appId}/quota-status")
  public List<IntegrationQuotaStatusRow> quotaStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId) {
    return call(auth, "read", actor -> apps.quotaStatus(appId, java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)));
  }

  // ================================================================ 审计

  /** 审计留痕（真分页，新到旧）。 */
  @GetMapping("/apps/{appId}/audit-events")
  public IntegrationAuditEventPage auditEvents(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID appId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> apps.auditEvents(appId, page, size));
  }

  // ================================================================ 用量回报（服务端到服务端）

  /**
   * 网关回报一次调用用量（**内部密钥**，不进浏览器白名单）。
   *
   * <p>⚠️ 租户由调用方给出 —— 这是**服务端到服务端**的路径，不是浏览器路径。
   */
  @PostMapping("/app-quota-usages")
  public IntegrationQuotaUsageResult recordUsage(
      @RequestHeader(value = "X-Internal-Key", defaultValue = "") String key,
      @Valid @RequestBody IntegrationQuotaUsageInput input) {
    return apps.recordUsage(input, key);
  }
}
