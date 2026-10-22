import { api } from '../inventory/api';

/**
 * 16.5.2.1 登录与访问安全 前端模型。
 *
 * 蓝图：「管理平台账号与系统的访问安全：多因子认证、登录审计、异常登录告警、IP 白名单、
 * 会话管理；平台授权凭据的访问审计。输入：认证配置；登录事件；访问审计事件。
 * 输出：安全配置 + 审计记录 + 告警。关系：上游 = 17 identity；下游 = 2 凭据引用。
 * **平台凭据是最值钱的资产**，访问必须有审计且最小权限。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「读不到」显示成「读不到」，不显示成「没有异常」**。后端给 `known=false` 就是
 *    `known=false`，这里渲染成「还没回填 / 读不到」并把原因一起显示，绝不显示空列表冒充正常。
 * 2. **量不到的维度显示成「量不到」并写清归属模块**，不显示成「正常」——
 *    例如「按来源 IP 判异常」「会话到期时刻」在本仓**读不到**。
 * 3. **前端不重算后端的判定**：IP 是否在白名单内、告警严重度、状态机结果全部原样显示后端算好的值。
 * 4. **未交付的下游不编造、不跳转**：本模块只展示真实存在的形状。
 * 5. **凭据访问只显示元信息**：页面**不显示、也不请求**任何令牌值。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**）。 */
export const BASE = 'channel/access-security';

/** 徽章色调（与 @starcon/ui 的 Badge tone 同一套取值）。 */
export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'brand';

/** 告警严重度（受控值域，后端 DB 层 CHECK 钉死）。 */
export const SEVERITIES: { value: string; label: string; tone: Tone }[] = [
  { value: 'LOW', label: '低', tone: 'neutral' },
  { value: 'MEDIUM', label: '中', tone: 'info' },
  { value: 'HIGH', label: '高', tone: 'warning' },
  { value: 'CRITICAL', label: '严重', tone: 'danger' },
];

/** 告警状态机（新警 → 已确认 / 已忽略 / 已处置）。 */
export const ALERT_STATUSES: { value: string; label: string; tone: Tone }[] = [
  { value: 'NEW', label: '新警', tone: 'danger' },
  { value: 'ACKNOWLEDGED', label: '已确认', tone: 'warning' },
  { value: 'IGNORED', label: '已忽略', tone: 'neutral' },
  { value: 'RESOLVED', label: '已处置', tone: 'success' },
];

/** 告警类型。 */
export const ALERT_TYPES = [
  { value: 'FAILED_LOGIN_BURST', label: '登录失败激增' },
  { value: 'SUCCESS_LOGIN_BURST', label: '短时多次成功登录' },
  { value: 'IP_NOT_ALLOWED', label: 'IP 不在白名单' },
  { value: 'ALLOWLIST_EMPTY', label: '白名单为空但口径是强制' },
];

/** IP 白名单执行口径。 */
export const IP_MODES = [
  { value: 'DISABLED', label: '不限制', hint: '租户显式选的「不限制」，不是默认值' },
  { value: 'REPORT_ONLY', label: '只告警不拦截', hint: '不在白名单的 IP 仍然放行，但会留一条告警' },
  {
    value: 'ENFORCE',
    label: '强制拒绝',
    hint: '不在白名单的 IP 一律拒绝（白名单为空 ⇒ 全部拒绝）',
  },
];

/** MFA 因子类型。 */
export const MFA_FACTORS = [
  { value: 'TOTP', label: '认证器应用' },
  { value: 'SMS', label: '短信验证码' },
  { value: 'EMAIL', label: '邮件验证码' },
];

/** MFA 登记状态。 */
export const MFA_STATUSES = [
  { value: 'PENDING', label: '待绑定' },
  { value: 'ACTIVE', label: '已启用' },
  { value: 'DISABLED', label: '已停用' },
  { value: 'REVOKED', label: '已撤销' },
];

/** 逐能力面可用性。 */
export interface AccessSecurityCapability {
  code: string;
  label: string;
  /** 该能力面现在**做得到**吗（false ⇒ 界面上要显示「量不到」而不是「正常」）。 */
  available: boolean;
  source: string;
  /** 做得到 / 缺的那部分归哪个模块。 */
  ownerModule: string;
  impact: string;
}

/** 能力声明。 */
export interface AccessSecurityCapabilities {
  capabilities: AccessSecurityCapability[];
  mfaNote: string;
  loginAuditNote: string;
  alertNote: string;
  ipAllowlistNote: string;
  sessionNote: string;
  credentialNote: string;
  downstreamNote: string;
  boundaryNote: string;
}

/** 安全策略（一租户一条）。 */
export interface AccessSecurityPolicy {
  policyId: string | null;
  policyName: string;
  mfaRequired: boolean;
  mfaPrimaryFactor: string;
  mfaGracePeriodMinutes: number;
  mfaEnforcementFrom: string | null;
  sessionIdleTimeoutMinutes: number;
  sessionAbsoluteTimeoutMinutes: number;
  sessionMaxConcurrent: number;
  sessionRevokeOnMfaChange: boolean;
  sessionRevokeOnPasswordChange: boolean;
  ipAllowlistMode: string;
  anomalyWindowMinutes: number;
  anomalyFailedLoginThreshold: number;
  anomalySuccessLoginThreshold: number;
  version: number;
  /** 后端没登记过策略时是 false（**后端不预填任何默认阈值**）。 */
  configured: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

/** 策略登记入参。 */
export interface AccessSecurityPolicyInput {
  policyName: string;
  mfaRequired: boolean;
  mfaPrimaryFactor: string;
  mfaGracePeriodMinutes: number;
  mfaEnforcementFrom: string | null;
  sessionIdleTimeoutMinutes: number;
  sessionAbsoluteTimeoutMinutes: number;
  sessionMaxConcurrent: number;
  sessionRevokeOnMfaChange: boolean;
  sessionRevokeOnPasswordChange: boolean;
  ipAllowlistMode: string;
  anomalyWindowMinutes: number;
  anomalyFailedLoginThreshold: number;
  anomalySuccessLoginThreshold: number;
  version: number | null;
}

/** MFA 因子登记。 */
export interface AccessSecurityMfaRegistration {
  registrationId: string;
  userId: string;
  membershipId: string | null;
  factorType: string;
  factorLabel: string;
  status: string;
  /** 外部密钥管理器引用是否已登记（**只有引用，没有明文因子密钥**）。 */
  secretRefPresent: boolean;
  boundAt: string | null;
  lastVerifiedAt: string | null;
  revokedReason: string;
  version: number;
  createdAt: string;
  updatedAt: string;
}

/** MFA 登记列表。 */
export interface AccessSecurityMfaRegistrationPage {
  items: AccessSecurityMfaRegistration[];
  page: number;
  size: number;
  total: number;
}

/** MFA 挑战的一次尝试。 */
export interface AccessSecurityMfaChallenge {
  recordId: string;
  challengeId: string;
  registrationId: string;
  attemptNo: number;
  factorType: string;
  outcome: string;
  deliveryChannel: string;
  deliveryRef: string;
  failureReason: string;
  expiresAt: string | null;
  occurredAt: string;
}

/** MFA 挑战列表。 */
export interface AccessSecurityMfaChallengePage {
  items: AccessSecurityMfaChallenge[];
  page: number;
  size: number;
  total: number;
}

/** 一条登录审计。 */
export interface AccessSecurityLoginAudit {
  auditId: string;
  sourceEventId: string;
  eventType: string;
  outcome: string;
  actorName: string;
  actorId: string | null;
  level: string;
  requestId: string;
  summary: string;
  occurredAt: string;
  /** 来源 IP；**identity 的登录事件不带 IP ⇒ 恒为空**。 */
  sourceIp: string;
  sourceIpAvailable: boolean;
  anomaly: boolean;
  anomalyCode: string;
  note: string;
}

/**
 * 登录审计列表。
 *
 * 🔴 `known=false` ⇒ **本地还没有任何登录事件留存**（还没跑过扫描）。
 * 「本地没有记录」**不等于**「没有异常登录」。
 */
export interface AccessSecurityLoginAuditPage {
  known: boolean;
  note: string;
  items: AccessSecurityLoginAudit[];
  page: number;
  size: number;
  total: number;
}

/** 一次扫描的结果。 */
export interface AccessSecurityScanResult {
  known: boolean;
  note: string;
  windowStart: string;
  windowEnd: string;
  /** 上游事件（`known=false` 时是 **null**，不是空数组）。 */
  events: AccessSecurityLoginAudit[] | null;
  eventsScanned: number;
  eventsRecorded: number;
  alertsCreated: number;
  alertsUpdated: number;
  alerts: AccessSecurityAlert[];
}

/** 告警处置留痕。 */
export interface AccessSecurityAlertAction {
  actionId: string;
  action: string;
  fromStatus: string;
  toStatus: string;
  actorId: string | null;
  actorName: string;
  reason: string;
  createdAt: string;
}

/** 一条告警。 */
export interface AccessSecurityAlert {
  alertId: string;
  alertType: string;
  severity: string;
  status: string;
  subjectKind: string;
  subjectValue: string;
  title: string;
  summary: string;
  eventCount: number;
  windowStart: string | null;
  windowEnd: string | null;
  detectedAt: string;
  acknowledgedBy: string | null;
  acknowledgedAt: string | null;
  ignoredBy: string | null;
  ignoredAt: string | null;
  ignoreReason: string;
  resolvedBy: string | null;
  resolvedAt: string | null;
  resolutionNote: string;
  version: number;
  createdAt: string;
  actions: AccessSecurityAlertAction[];
}

/** 告警列表。 */
export interface AccessSecurityAlertPage {
  items: AccessSecurityAlert[];
  page: number;
  size: number;
  total: number;
  newCount: number;
  acknowledgedCount: number;
  ignoredCount: number;
  resolvedCount: number;
  criticalCount: number;
  highCount: number;
}

/** IP 白名单条目。 */
export interface AccessSecurityIpAllowlistEntry {
  entryId: string;
  cidrRange: string;
  label: string;
  scopeKind: string;
  scopeId: string | null;
  status: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  createdBy: string | null;
  createdByName: string;
  version: number;
  createdAt: string;
}

/** IP 白名单列表。 */
export interface AccessSecurityIpAllowlistPage {
  items: AccessSecurityIpAllowlistEntry[];
  page: number;
  size: number;
  total: number;
}

/**
 * IP 判定结果。
 *
 * 🔴 `inAllowlist`（在不在白名单网段内）与 `allowed`（能不能进）**是两个字段**：
 * 「只告警不拦截」口径下不在白名单**仍然能进**。
 */
export interface AccessSecurityIpCheck {
  ip: string;
  valid: boolean;
  allowed: boolean;
  inAllowlist: boolean;
  mode: string;
  modeLabel: string;
  matchedEntryId: string | null;
  matchedCidr: string | null;
  enforce: boolean;
  reason: string;
}

/** 一条凭据访问审计。 */
export interface AccessSecurityCredentialAccess {
  accessId: string;
  credentialId: string;
  externalRef: string;
  provider: string;
  accessKind: string;
  accessPurpose: string;
  outcome: string;
  actorId: string | null;
  actorName: string;
  actorSource: string;
  requestId: string;
  note: string;
  occurredAt: string;
}

/** 凭据访问审计列表。 */
export interface AccessSecurityCredentialAccessPage {
  items: AccessSecurityCredentialAccess[];
  page: number;
  size: number;
  total: number;
  successCount: number;
  deniedCount: number;
  failedCount: number;
}

/**
 * 一份凭据的元信息 + 它的访问统计。
 *
 * 🔴 **这里永远没有令牌**：2.2.1.3 的 `credential_ref` 本身就只存引用。
 */
export interface AccessSecurityCredential {
  credentialId: string;
  provider: string;
  externalRef: string;
  tokenType: string;
  grantedScopes: string;
  status: string;
  authorizedAt: string | null;
  expiresAt: string | null;
  accessCount: number;
  lastAccessedAt: string | null;
  lastAccessedBy: string | null;
}

/** 凭据维度的访问汇总。 */
export interface AccessSecurityCredentialPage {
  items: AccessSecurityCredential[];
  page: number;
  size: number;
  total: number;
}

/** 受控访问的结果。 */
export interface AccessSecurityControlledAccess {
  credential: AccessSecurityCredential | null;
  audit: AccessSecurityCredentialAccess;
  message: string;
}

/** 会话策略的单个维度。 */
export interface AccessSecuritySessionDimension {
  code: string;
  label: string;
  /** 这个维度现在**读得到**吗。 */
  available: boolean;
  value: string;
  ownerModule: string;
  impact: string;
}

/** 当前调用方的会话现状 + 适用的会话策略。 */
export interface AccessSecuritySession {
  known: boolean;
  sessionState: string;
  note: string;
  policy: AccessSecurityPolicy | null;
  dimensions: AccessSecuritySessionDimension[];
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<AccessSecurityCapabilities> {
  return api<AccessSecurityCapabilities>(`${BASE}/capabilities`);
}

export async function fetchPolicy(): Promise<AccessSecurityPolicy> {
  return api<AccessSecurityPolicy>(`${BASE}/policy`);
}

export async function savePolicy(input: AccessSecurityPolicyInput): Promise<AccessSecurityPolicy> {
  return api<AccessSecurityPolicy>(`${BASE}/policy`, 'PUT', input);
}

export async function fetchMfaRegistrations(
  userId = '',
  status = '',
  page = 0,
  size = 20,
): Promise<AccessSecurityMfaRegistrationPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (userId.trim()) query.set('userId', userId.trim());
  if (status.trim()) query.set('status', status.trim());
  return api<AccessSecurityMfaRegistrationPage>(`${BASE}/mfa/registrations?${query}`);
}

export async function fetchMfaChallenges(
  registrationId = '',
  page = 0,
  size = 20,
): Promise<AccessSecurityMfaChallengePage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (registrationId.trim()) query.set('registrationId', registrationId.trim());
  return api<AccessSecurityMfaChallengePage>(`${BASE}/mfa/challenges?${query}`);
}

export async function fetchLoginAudit(
  actorName = '',
  outcome = '',
  page = 0,
  size = 20,
): Promise<AccessSecurityLoginAuditPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (actorName.trim()) query.set('actorName', actorName.trim());
  if (outcome.trim()) query.set('outcome', outcome.trim());
  return api<AccessSecurityLoginAuditPage>(`${BASE}/login-audit?${query}`);
}

export async function runScan(): Promise<AccessSecurityScanResult> {
  return api<AccessSecurityScanResult>(`${BASE}/login-audit/scan`, 'POST', {});
}

export async function fetchAlerts(
  type = '',
  severity = '',
  status = '',
  page = 0,
  size = 20,
): Promise<AccessSecurityAlertPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (type.trim()) query.set('type', type.trim());
  if (severity.trim()) query.set('severity', severity.trim());
  if (status.trim()) query.set('status', status.trim());
  return api<AccessSecurityAlertPage>(`${BASE}/alerts?${query}`);
}

export async function fetchAlert(alertId: string): Promise<AccessSecurityAlert> {
  return api<AccessSecurityAlert>(`${BASE}/alerts/${alertId}`);
}

export async function acknowledgeAlert(
  alertId: string,
  reason: string,
): Promise<AccessSecurityAlert> {
  return api<AccessSecurityAlert>(`${BASE}/alerts/${alertId}/acknowledge`, 'POST', { reason });
}

export async function ignoreAlert(alertId: string, reason: string): Promise<AccessSecurityAlert> {
  return api<AccessSecurityAlert>(`${BASE}/alerts/${alertId}/ignore`, 'POST', { reason });
}

export async function resolveAlert(alertId: string, reason: string): Promise<AccessSecurityAlert> {
  return api<AccessSecurityAlert>(`${BASE}/alerts/${alertId}/resolve`, 'POST', { reason });
}

export async function fetchIpAllowlist(
  status = '',
  page = 0,
  size = 20,
): Promise<AccessSecurityIpAllowlistPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (status.trim()) query.set('status', status.trim());
  return api<AccessSecurityIpAllowlistPage>(`${BASE}/ip-allowlist?${query}`);
}

export async function checkIp(ip: string): Promise<AccessSecurityIpCheck> {
  return api<AccessSecurityIpCheck>(`${BASE}/ip-allowlist/check?ip=${encodeURIComponent(ip)}`);
}

export async function fetchCredentialAccess(
  credentialId = '',
  outcome = '',
  page = 0,
  size = 20,
): Promise<AccessSecurityCredentialAccessPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (credentialId.trim()) query.set('credentialId', credentialId.trim());
  if (outcome.trim()) query.set('outcome', outcome.trim());
  return api<AccessSecurityCredentialAccessPage>(`${BASE}/credential-access?${query}`);
}

export async function fetchCredentials(page = 0, size = 20): Promise<AccessSecurityCredentialPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return api<AccessSecurityCredentialPage>(`${BASE}/credential-access/credentials?${query}`);
}

export async function fetchCredentialAccessRow(
  accessId: string,
): Promise<AccessSecurityCredentialAccess> {
  return api<AccessSecurityCredentialAccess>(`${BASE}/credential-access/${accessId}`);
}

/** 🔴 受控访问：读一份凭据的**元信息**并留痕（后端**永不返回令牌**）。 */
export async function controlledAccess(
  credentialId: string,
  accessPurpose: string,
): Promise<AccessSecurityControlledAccess> {
  return api<AccessSecurityControlledAccess>(`${BASE}/credential-access`, 'POST', {
    credentialId,
    accessPurpose,
  });
}

export async function fetchSession(): Promise<AccessSecuritySession> {
  return api<AccessSecuritySession>(`${BASE}/session`);
}

// ================================================================ 展示映射（纯函数）

/** 严重度标签。 */
export function severityLabel(severity: string): string {
  return SEVERITIES.find((s) => s.value === severity)?.label ?? severity;
}

/** 严重度色调：严重=红、高=黄、中=蓝、低=中性（🔴 低风险**不与**「没事」共用绿色）。 */
export function severityTone(severity: string): Tone {
  return SEVERITIES.find((s) => s.value === severity)?.tone ?? 'neutral';
}

/** 告警状态标签。 */
export function alertStatusLabel(status: string): string {
  return ALERT_STATUSES.find((s) => s.value === status)?.label ?? status;
}

/** 告警状态色调。 */
export function alertStatusTone(status: string): Tone {
  return ALERT_STATUSES.find((s) => s.value === status)?.tone ?? 'neutral';
}

/** 告警类型标签。 */
export function alertTypeLabel(type: string): string {
  return ALERT_TYPES.find((t) => t.value === type)?.label ?? type;
}

/** IP 口径标签。 */
export function ipModeLabel(mode: string): string {
  return IP_MODES.find((m) => m.value === mode)?.label ?? mode;
}

/** IP 口径提示。 */
export function ipModeHint(mode: string): string {
  return IP_MODES.find((m) => m.value === mode)?.hint ?? '';
}

/** MFA 因子标签。 */
export function mfaFactorLabel(factor: string): string {
  return MFA_FACTORS.find((f) => f.value === factor)?.label ?? factor;
}

/** MFA 登记状态标签。 */
export function mfaStatusLabel(status: string): string {
  return MFA_STATUSES.find((s) => s.value === status)?.label ?? status;
}

/**
 * IP 判定结论（一句话，不含糊）。
 *
 * 🔴 **「在不在白名单」与「能不能进」是两回事** —— 「只告警不拦截」口径下不在白名单仍然能进。
 */
export function ipCheckText(check: AccessSecurityIpCheck | null): string {
  if (!check) return '尚未判定';
  if (!check.valid) return `IP 格式不合法：${check.ip}`;
  if (check.inAllowlist) return `在白名单网段内（${check.matchedCidr ?? ''}）`;
  if (check.enforce) return '不在白名单内，当前口径是「强制拒绝」⇒ 不能进';
  if (check.mode === 'REPORT_ONLY')
    return '不在白名单内，当前口径是「只告警不拦截」⇒ 仍能进，但会留一条告警';
  return '当前口径是「不限制」⇒ 能进';
}

/** 🔴 量不到的维度（**它们也在清单里，不是悄悄消失**）。 */
export function unavailableCapabilities(
  capabilities: AccessSecurityCapabilities | null,
): AccessSecurityCapability[] {
  if (!capabilities) return [];
  return capabilities.capabilities.filter((c) => !c.available);
}

/** 终态告警（已忽略 / 已处置）—— 不能再改，只能等下一次告警。 */
export function isTerminalAlert(alert: AccessSecurityAlert | null): boolean {
  if (!alert) return false;
  return alert.status === 'IGNORED' || alert.status === 'RESOLVED';
}

/** 能否对这条告警做处置（终态不行）。 */
export function canActOnAlert(alert: AccessSecurityAlert | null): boolean {
  return !!alert && !isTerminalAlert(alert);
}

/** 凭据是否**从未**被访问过（值得提醒运营：这条凭据到底有人在用吗）。 */
export function neverAccessed(credential: AccessSecurityCredential | null): boolean {
  if (!credential) return false;
  return credential.accessCount === 0;
}

/** 访问次数展示。 */
export function accessCountText(credential: AccessSecurityCredential | null): string {
  if (!credential) return '—';
  if (credential.accessCount === 0) return '从未被访问';
  return `被访问 ${credential.accessCount} 次`;
}

/** 登录事件的成败文案。 */
export function loginOutcomeLabel(outcome: string): string {
  if (outcome === 'SUCCESS') return '成功';
  if (outcome === 'FAILURE') return '失败';
  return outcome;
}

/** 登录事件的成败色调。 */
export function loginOutcomeTone(outcome: string): Tone {
  if (outcome === 'SUCCESS') return 'success';
  if (outcome === 'FAILURE') return 'danger';
  return 'neutral';
}

/** MFA 挑战结果标签。 */
export function challengeOutcomeLabel(outcome: string): string {
  if (outcome === 'ISSUED') return '已下发';
  if (outcome === 'VERIFIED') return '已验证';
  if (outcome === 'FAILED') return '验证失败';
  if (outcome === 'EXPIRED') return '已过期';
  return outcome;
}

/** 凭据访问动作标签。 */
export function credentialAccessKindLabel(kind: string): string {
  if (kind === 'READ_METADATA') return '读取凭据元信息';
  if (kind === 'READ_SECRET') return '读取凭据明文';
  if (kind === 'WRITE_SECRET') return '写入凭据';
  if (kind === 'ROTATE') return '轮换凭据';
  if (kind === 'REVOKE') return '吊销凭据';
  if (kind === 'HEALTH_CHECK') return '健康诊断';
  return kind;
}

/** 凭据访问结果标签。 */
export function credentialOutcomeLabel(outcome: string): string {
  if (outcome === 'SUCCESS') return '成功';
  if (outcome === 'DENIED') return '被拒';
  if (outcome === 'FAILED') return '失败';
  return outcome;
}

/** 凭据访问结果色调。 */
export function credentialOutcomeTone(outcome: string): Tone {
  if (outcome === 'SUCCESS') return 'success';
  if (outcome === 'DENIED') return 'warning';
  if (outcome === 'FAILED') return 'danger';
  return 'neutral';
}

/**
 * 三态文案**各不相同**（不是同一个空态换个图标）。
 *
 * 🔴 「上游读不到」与「没有异常」是**两件事** —— 页面绝不能把它们说成同一个。
 */
export function emptyState(
  kind: 'NO_POLICY' | 'NO_LOGIN_AUDIT' | 'NO_ALERT' | 'NO_ALLOWLIST' | 'NO_CREDENTIAL_ACCESS',
): { title: string; description: string } {
  if (kind === 'NO_POLICY') {
    return {
      title: '还没有登记安全策略',
      description:
        '异常登录的窗口与阈值必须由租户显式选 —— 系统不给默认值。登记之后才能跑登录审计扫描。',
    };
  }
  if (kind === 'NO_LOGIN_AUDIT') {
    return {
      title: '本地还没有登录事件留存',
      description:
        '登录事件来自身份服务的操作日志，需要跑一次扫描才会回填。🔴 「没有记录」不等于「没有异常登录」。',
    };
  }
  if (kind === 'NO_ALERT') {
    return {
      title: '当前没有告警',
      description:
        '告警由登录审计扫描按策略阈值判定。窗口内没有异常就不会有告警 —— 但这不等于「查过了」。',
    };
  }
  if (kind === 'NO_ALLOWLIST') {
    return {
      title: '还没有登记任何 IP 白名单',
      description:
        '口径是「强制拒绝」而白名单为空时，**所有来源 IP 都会被拒绝**（fail-closed）。空白名单不等于放行所有。',
    };
  }
  return {
    title: '还没有凭据访问记录',
    description:
      '凭据访问审计是 append-only 的：每次受控访问都留一行，且**只记元信息、永不记令牌**。',
  };
}
