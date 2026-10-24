import type {
  IntegrationAppCapabilities,
  IntegrationAppDetail,
  IntegrationAppInput,
  IntegrationAppOptions,
  IntegrationAppOverview,
  IntegrationAppPage,
  IntegrationAuditEventPage,
  IntegrationCredentialIssued,
  IntegrationCredentialRow,
  IntegrationCredentialVerifyInput,
  IntegrationCredentialVerifyResult,
  IntegrationIpAllowlistInput,
  IntegrationIpAllowlistRow,
  IntegrationQuotaInput,
  IntegrationQuotaRow,
  IntegrationQuotaStatusRow,
  IntegrationScopeInput,
  IntegrationScopeRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.1.1.1 应用与凭证 前端模型。
 *
 * 蓝图：「管理第三方接入的应用：应用注册、API Key/Secret 生成与轮换、授权范围（可访问的资源与操作）、
 * IP 白名单、调用配额。输入：应用注册；凭证轮换；授权范围配置。输出：应用档案 + 凭证
 * （**明文仅在生成时展示一次**，之后只存摘要）。关系：上游 = 17 权限；下游 = 18.1.1.2 接口目录。
 * **对外凭证与平台凭据同等对待**：不落库明文、访问有审计、泄露可一键吊销。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **明文只在生成那一次出现**。后端返回 `apiKey` / `secret` 的**只有**签发与轮换那两个响应；
 *    之后所有列表与详情里只有前缀与末四位。页面**绝不**把明文缓存进 state 之外的任何地方，
 *    也不在「复制」按钮之外再存一份。
 * 2. **「没配过配额」显示成「没配过配额」，不是「限 0 次」**。`NOT_CONFIGURED` 就是
 *    `NOT_CONFIGURED`，前端**不重算**。把「不限」显示成「已用尽」是误伤。
 * 3. **凭证自检的四个判定分开显示**（凭证 / 授权范围 / IP 白名单 / 配额）。
 *    「凭证对但没授权」和「凭证错」要修的地方完全不同，合成一个布尔值就没法用。
 * 4. **凭证明文只显示一次**：页面明确告诉运营「这是唯一一次」，并要求立刻保存。
 * 5. **上游读不到 ≠ 没有**：能力声明里 `available=false` 的项按原样显示，
 *    不显示成「暂无」。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），
 * 前端**不手抄**字符串联合 —— 枚举值由后端的 options 端点给出。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/apps';

/** 应用列表的筛选条件。 */
export interface AppParams {
  keyword?: string | undefined;
  appType?: string | undefined;
  status?: string | undefined;
}

/** 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。 */
function query(params: Record<string, string | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value) search.set(key, value);
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
export function fetchCapabilities(): Promise<IntegrationAppCapabilities> {
  return api<IntegrationAppCapabilities>(`${base}/capabilities`);
}

/** 筛选项（应用类型 / 状态 / 操作 / 配额窗口 / 已授权过的资源码）。 */
export function fetchOptions(): Promise<IntegrationAppOptions> {
  return api<IntegrationAppOptions>(`${base}/options`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<IntegrationAppOverview> {
  return api<IntegrationAppOverview>(`${base}/overview`);
}

/** 应用列表（真分页 + 真筛选）。 */
export function fetchApps(
  page: number,
  size: number,
  params: AppParams,
): Promise<IntegrationAppPage> {
  return api<IntegrationAppPage>(`${base}?${query({ ...params, page: String(page), size: String(size) })}`);
}

/** 应用档案详情（凭证元数据 + 授权范围 + 白名单 + 配额 + 额度判定）。 */
export function fetchAppDetail(appId: string): Promise<IntegrationAppDetail> {
  return api<IntegrationAppDetail>(`${base}/${appId}`);
}

/** 凭证列表（**只显示摘要前缀与末四位**）。 */
export function fetchCredentials(appId: string): Promise<IntegrationCredentialRow[]> {
  return api<IntegrationCredentialRow[]>(`${base}/${appId}/credentials`);
}

/** 授权范围列表。 */
export function fetchScopes(appId: string): Promise<IntegrationScopeRow[]> {
  return api<IntegrationScopeRow[]>(`${base}/${appId}/scopes`);
}

/** IP 白名单列表。 */
export function fetchIpAllowlist(appId: string): Promise<IntegrationIpAllowlistRow[]> {
  return api<IntegrationIpAllowlistRow[]>(`${base}/${appId}/ip-allowlist`);
}

/** 配额配置列表。 */
export function fetchQuotas(appId: string): Promise<IntegrationQuotaRow[]> {
  return api<IntegrationQuotaRow[]>(`${base}/${appId}/quotas`);
}

/** 额度判定（每个窗口类型一行；未配置的窗口 = 不限）。 */
export function fetchQuotaStatus(appId: string): Promise<IntegrationQuotaStatusRow[]> {
  return api<IntegrationQuotaStatusRow[]>(`${base}/${appId}/quota-status`);
}

/** 审计留痕（真分页，新到旧）。 */
export function fetchAuditEvents(appId: string, page: number, size: number) {
  return api<IntegrationAuditEventPage>(
    `${base}/${appId}/audit-events?${query({ page: String(page), size: String(size) })}`,
  );
}

// ================================================================ 写入

/** 注册应用。 */
export function registerApp(input: IntegrationAppInput): Promise<IntegrationAppPage['items'][number]> {
  return api(`${base}`, 'POST', input);
}

/** 签发凭证 —— **明文只在这一次的响应里出现**。 */
export function issueCredential(appId: string): Promise<IntegrationCredentialIssued> {
  return api<IntegrationCredentialIssued>(`${base}/${appId}/credentials`, 'POST');
}

/** 轮换凭证：新明文 + 旧摘要立即失效。 */
export function rotateCredential(appId: string): Promise<IntegrationCredentialIssued> {
  return api<IntegrationCredentialIssued>(`${base}/${appId}/credentials/rotate`, 'POST');
}

/** 吊销单个凭证（泄露时的一键止血）。 */
export function revokeCredential(appId: string, credentialId: string, reason: string) {
  return api(
    `${base}/${appId}/credentials/${credentialId}/revoke?${query({ reason })}`,
    'POST',
  );
}

/** 吊销应用（**一键吊销**：该应用全部凭证同时失效）。 */
export function revokeApp(appId: string, reason: string) {
  return api(`${base}/${appId}/revoke?${query({ reason })}`, 'POST');
}

/** 授权（幂等：同一 资源 × 操作 重复授权不叠加）。 */
export function grantScope(appId: string, input: IntegrationScopeInput) {
  return api(`${base}/${appId}/scopes`, 'POST', input);
}

/** 撤销一条授权。 */
export function revokeScope(appId: string, scopeId: string): Promise<void> {
  return api<void>(`${base}/${appId}/scopes/${scopeId}`, 'DELETE');
}

/** 新增一条 IP 白名单。 */
export function addIpAllowlist(appId: string, input: IntegrationIpAllowlistInput) {
  return api(`${base}/${appId}/ip-allowlist`, 'POST', input);
}

/** 删除一条 IP 白名单。 */
export function removeIpAllowlist(appId: string, entryId: string): Promise<void> {
  return api<void>(`${base}/${appId}/ip-allowlist/${entryId}`, 'DELETE');
}

/** 设置配额（同一窗口类型重复设置 = 覆盖，不是叠加）。 */
export function setQuota(appId: string, input: IntegrationQuotaInput) {
  return api(`${base}/${appId}/quotas`, 'POST', input);
}

/** 凭证自检（凭证 / 授权范围 / IP 白名单 / 配额四个判定分开给）。 */
export function verifyCredential(
  appId: string,
  input: IntegrationCredentialVerifyInput,
): Promise<IntegrationCredentialVerifyResult> {
  return api<IntegrationCredentialVerifyResult>(`${base}/${appId}/credentials/verify`, 'POST', input);
}

// ================================================================ 展示口径

/**
 * 配额判定的展示口径。
 *
 * 🔴 **`NOT_CONFIGURED` 不是「已超限」**：没配过的窗口 = 不限。
 * 把「不限」显示成红色「已用尽」，会让运营去查一个根本不存在的限制。
 */
export function quotaTone(status: IntegrationQuotaStatusRow): 'ok' | 'warn' | 'danger' | 'muted' {
  if (!status.configured) return 'muted';
  if (!status.enabled) return 'muted';
  if (status.exceeded) return 'danger';
  return 'ok';
}

/** 配额判定的中文说明。 */
export function quotaLabel(status: IntegrationQuotaStatusRow): string {
  if (!status.configured) return '不限（未配置）';
  if (!status.enabled) return '已停用';
  if (status.exceeded) return '已超限';
  return '额度充足';
}

/**
 * 凭证自检结论的展示口径。
 *
 * 🔴 四个判定分开说 —— 「凭证有效但授权范围不足」和「凭证不匹配」要修的地方完全不同。
 */
export function verifyConclusion(result: IntegrationCredentialVerifyResult): string {
  if (!result.credentialMatched) return '凭证不匹配或已失效';
  if (result.quotaExceeded) return '凭证有效，但配额已超限';
  if (result.scopeChecked && !result.scopeGranted) return '凭证有效，但授权范围不足';
  if (result.ipChecked && !result.ipAllowed) return '凭证有效，但来源 IP 不在白名单内';
  return '凭证有效';
}

/** 掩码：把明文凭证显示成「前 6 位 + 掩码」，避免截图时泄露。 */
export function maskSecret(value: string): string {
  if (value.length <= 8) return '••••';
  return `${value.slice(0, 6)}${'•'.repeat(Math.min(value.length - 6, 24))}`;
}
