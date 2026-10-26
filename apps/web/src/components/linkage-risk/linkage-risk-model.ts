import { api } from '../inventory/api';

/**
 * 16.5.2.2 关联风险 前端模型。
 *
 * 蓝图：「识别账号关联风险：同一支付方式/收款账户/设备/IP/地址/法人关联的店铺，
 * 输出关联图谱与风险提示，提示避免触发平台关联处罚。输入：店铺与支付/设备/地址/法人关系数据；
 * 平台政策要求。输出：关联图谱 + 风险提示 + 整改建议。关系：上游 = 2 店铺、12.7.1 资金账户；
 * 下游 = 1 工作台预警。**关联封号是跨境多店经营的头号风险**：官方 API 授权是规避关联的关键，
 * 本系统只做官方 API，绝不提供任何模拟登录/指纹浏览器能力。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「读不到」显示成「读不到」，不显示成「没有关联」**。后端给 `known=false` 就是没判定，
 *    这里渲染成「还没登记策略」并把原因一起显示，绝不显示空图谱冒充一切正常。
 * 2. **量不到的维度显式标「读不到」+ 归属模块**（支付方式 / 收款账户归 12.7.1.1，未交付），
 *    **不显示成「该维度无风险」**，也不显示 0。
 * 3. **前端不重算后端的判定**：严重度 / 风险等级 / 关联组全部原样显示后端算好的值。
 * 4. **未交付的下游不编造**：1 工作台预警未交付，页面**没有**「推送到工作台」的按钮。
 * 5. **不提供任何绕关联的入口**：本页只有「登记已观测到的共享事实」，没有模拟登录 / 指纹浏览器。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**）。 */
export const BASE = 'channel/linkage-risk';

/** 徽章色调（与 @starcon/ui 的 Badge tone 同一套取值）。 */
export type Tone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'brand';

/** 关联维度（**可登记**的只有设备 / 网络 IP / 地址 / 其他；法人是自动发现的，不可登记）。 */
export const REGISTRABLE_DIMENSIONS = [
  { value: 'DEVICE', label: '设备' },
  { value: 'NETWORK_IP', label: '网络 IP' },
  { value: 'ADDRESS', label: '地址' },
  { value: 'OTHER', label: '其它共享因子' },
];

/** 全部维度（含不可登记的，用于展示能力声明）。 */
export const DIMENSIONS = [
  { value: 'LEGAL_ENTITY', label: '法人' },
  ...REGISTRABLE_DIMENSIONS,
  { value: 'PAYMENT_METHOD', label: '支付方式' },
  { value: 'PAYOUT_ACCOUNT', label: '收款账户' },
];

/** 维度敏感度（平台政策口径）。 */
export const SENSITIVITIES = [
  { value: 'HIGH', label: '高' },
  { value: 'MEDIUM', label: '中' },
  { value: 'LOW', label: '低' },
];

/** 判定范围。 */
export const EVALUATION_SCOPES = [
  { value: 'TENANT', label: '全部店铺之间' },
  { value: 'PLATFORM', label: '仅同一平台之间' },
];

/** 严重度（受控值域，后端 DB 层 CHECK 钉死）。 */
export const SEVERITIES = [
  { value: 'LOW', label: '低', tone: 'neutral' as Tone },
  { value: 'MEDIUM', label: '中', tone: 'info' as Tone },
  { value: 'HIGH', label: '高', tone: 'warning' as Tone },
  { value: 'CRITICAL', label: '严重', tone: 'danger' as Tone },
];

/** 提示状态机。 */
export const ALERT_STATUSES = [
  { value: 'OPEN', label: '待处置', tone: 'danger' as Tone },
  { value: 'ACKNOWLEDGED', label: '已确认', tone: 'warning' as Tone },
  { value: 'REMEDIATED', label: '已整改', tone: 'success' as Tone },
  { value: 'DISMISSED', label: '已忽略', tone: 'neutral' as Tone },
];

/** 整改建议状态机。 */
export const REMEDIATION_STATUSES = [
  { value: 'PROPOSED', label: '待处理', tone: 'warning' as Tone },
  { value: 'ACCEPTED', label: '已采纳', tone: 'info' as Tone },
  { value: 'DONE', label: '已完成', tone: 'success' as Tone },
  { value: 'DISMISSED', label: '已忽略', tone: 'neutral' as Tone },
];

/** 关联事实来源。 */
export const SOURCES = [
  { value: 'MANUAL', label: '手工登记' },
  { value: 'PLATFORM_NOTICE', label: '平台通知' },
  { value: 'OPS_REPORT', label: '运营上报' },
];

// ================================================================ 类型

export interface LinkageRiskCapability {
  code: string;
  label: string;
  /** 现在做得到吗（false ⇒ 该面在本仓没有事实来源）。 */
  available: boolean;
  /** 事实能自动发现吗（false ⇒ 事实靠租户登记）。 */
  autoDiscoverable: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface LinkageRiskCapabilities {
  capabilities: LinkageRiskCapability[];
  shopSourceNote: string;
  dimensionNote: string;
  registerNote: string;
  graphNote: string;
  alertNote: string;
  remediationNote: string;
  officialApiNote: string;
  downstreamNote: string;
  financeNote: string;
}

export interface LinkageRiskPolicyDimension {
  dimensionId: string;
  dimension: string;
  dimensionLabel: string;
  sensitivity: string;
  minShopCount: number;
  policyBasis: string;
  enabled: boolean;
}

export interface LinkageRiskPolicy {
  policyId: string | null;
  policyName: string;
  status: string;
  minSharedShopCount: number;
  evaluationScope: string;
  dimensions: LinkageRiskPolicyDimension[];
  version: number;
  /** 后端没登记过策略时是 false。 */
  configured: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface LinkageRiskPolicyDimensionInput {
  dimension: string;
  sensitivity: string;
  minShopCount: number;
  policyBasis: string;
  enabled: boolean;
}

export interface LinkageRiskPolicyInput {
  policyName: string;
  status: string;
  minSharedShopCount: number;
  evaluationScope: string;
  dimensions: LinkageRiskPolicyDimensionInput[];
}

export interface LinkageRiskShop {
  shopId: string;
  code: string;
  name: string;
  platformCode: string;
  platformName: string;
  status: string;
  legalEntityId: string | null;
  /** 法人名称（读不到时是 null）。 */
  legalEntityName: string | null;
  /** 法人档案**读到了没有**（false ⇒ 显示「读不到」，不是「没有法人」）。 */
  legalEntityNameAvailable: boolean;
  linkCount: number;
  dimensions: string[];
}

export interface LinkageRiskShopPage {
  items: LinkageRiskShop[];
  page: number;
  size: number;
  total: number;
}

export interface LinkageRiskIdentifierShop {
  shopId: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
}

export interface LinkageRiskIdentifier {
  identifierId: string;
  dimension: string;
  dimensionLabel: string;
  identifierKey: string;
  displayLabel: string;
  status: string;
  shopCount: number;
  shops: LinkageRiskIdentifierShop[];
  note: string;
  version: number;
  firstObservedAt: string;
  lastObservedAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface LinkageRiskIdentifierPage {
  items: LinkageRiskIdentifier[];
  page: number;
  size: number;
  total: number;
}

export interface LinkageRiskIdentifierInput {
  dimension: string;
  identifierKey: string;
  displayLabel: string;
  note: string;
  firstObservedAt?: string | null;
}

export interface LinkageRiskShopLinkInput {
  shopId: string;
  source: string;
  note: string;
  observedAt?: string | null;
}

export interface LinkageRiskGraphNode {
  shopId: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
  linkCount: number;
  dimensions: string[];
}

export interface LinkageRiskGraphLink {
  dimension: string;
  dimensionLabel: string;
  factorKey: string;
  factorLabel: string;
  platformCode: string;
  shopCount: number;
  shopIds: string[];
  autoDiscovered: boolean;
  sensitivity: string;
}

export interface LinkageRiskGraphCluster {
  clusterKey: string;
  shopIds: string[];
  shopCount: number;
  dimensions: string[];
  severity: string;
  severityLabel: string;
  riskLevel: string;
  riskLevelLabel: string;
  crossPlatform: boolean;
  summary: string;
}

export interface LinkageRiskGraph {
  /** 图谱**算出来没有**（false ⇒ 还没登记策略，**不是**「没有关联」）。 */
  known: boolean;
  note: string;
  shopCount: number;
  linkCount: number;
  clusterCount: number;
  nodes: LinkageRiskGraphNode[];
  links: LinkageRiskGraphLink[];
  clusters: LinkageRiskGraphCluster[];
}

export interface LinkageRiskAlertShop {
  shopId: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
}

export interface LinkageRiskAlertAction {
  actionId: string;
  action: string;
  fromStatus: string;
  toStatus: string;
  actorName: string;
  reason: string;
  occurredAt: string;
}

export interface LinkageRiskRemediation {
  remediationId: string;
  recommendationCode: string;
  recommendationLabel: string;
  dimension: string;
  title: string;
  detail: string;
  priority: string;
  status: string;
  decisionNote: string;
  decidedAt: string | null;
  version: number;
}

export interface LinkageRiskAlert {
  alertId: string;
  clusterId: string;
  clusterKey: string;
  alertType: string;
  severity: string;
  severityLabel: string;
  status: string;
  statusLabel: string;
  shopCount: number;
  dimensions: string[];
  title: string;
  summary: string;
  detectedAt: string;
  acknowledgedBy: string | null;
  acknowledgedAt: string | null;
  acknowledgeReason: string;
  resolvedBy: string | null;
  resolvedAt: string | null;
  resolutionNote: string;
  dismissedBy: string | null;
  dismissedAt: string | null;
  dismissReason: string;
  version: number;
  createdAt: string;
  updatedAt: string;
  shops: LinkageRiskAlertShop[];
  actions: LinkageRiskAlertAction[];
  remediations: LinkageRiskRemediation[];
}

export interface LinkageRiskAlertPage {
  items: LinkageRiskAlert[];
  page: number;
  size: number;
  total: number;
  openCount: number;
  acknowledgedCount: number;
  remediatedCount: number;
  dismissedCount: number;
  highCount: number;
}

export interface LinkageRiskRemediationPage {
  items: LinkageRiskRemediation[];
  page: number;
  size: number;
  total: number;
}

export interface LinkageRiskScanResult {
  known: boolean;
  note: string;
  shopsScanned: number;
  linksFound: number;
  clustersFound: number;
  clustersCreated: number;
  clustersUpdated: number;
  alertsCreated: number;
  alertsUpdated: number;
  remediationsCreated: number;
  clusters: LinkageRiskGraphCluster[];
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<LinkageRiskCapabilities> {
  return api<LinkageRiskCapabilities>(`${BASE}/capabilities`);
}

export async function fetchPolicy(): Promise<LinkageRiskPolicy> {
  return api<LinkageRiskPolicy>(`${BASE}/policy`);
}

export async function savePolicy(input: LinkageRiskPolicyInput): Promise<LinkageRiskPolicy> {
  return api<LinkageRiskPolicy>(`${BASE}/policy`, 'PUT', input);
}

export async function fetchShops(
  keyword = '',
  page = 0,
  size = 20,
): Promise<LinkageRiskShopPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (keyword.trim()) query.set('keyword', keyword.trim());
  return api<LinkageRiskShopPage>(`${BASE}/shops?${query}`);
}

export async function fetchIdentifiers(
  dimension = '',
  status = '',
  page = 0,
  size = 20,
): Promise<LinkageRiskIdentifierPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (dimension.trim()) query.set('dimension', dimension.trim());
  if (status.trim()) query.set('status', status.trim());
  return api<LinkageRiskIdentifierPage>(`${BASE}/identifiers?${query}`);
}

export async function createIdentifier(
  input: LinkageRiskIdentifierInput,
): Promise<LinkageRiskIdentifier> {
  return api<LinkageRiskIdentifier>(`${BASE}/identifiers`, 'POST', input);
}

export async function updateIdentifier(
  identifierId: string,
  input: { displayLabel: string; status: string; note: string; version: number },
): Promise<LinkageRiskIdentifier> {
  return api<LinkageRiskIdentifier>(`${BASE}/identifiers/${identifierId}`, 'PUT', input);
}

export async function deleteIdentifier(identifierId: string): Promise<void> {
  await api<void>(`${BASE}/identifiers/${identifierId}`, 'DELETE');
}

export async function linkShop(
  identifierId: string,
  input: LinkageRiskShopLinkInput,
): Promise<LinkageRiskIdentifier> {
  return api<LinkageRiskIdentifier>(`${BASE}/identifiers/${identifierId}/shops`, 'POST', input);
}

export async function unlinkShop(identifierId: string, shopId: string): Promise<void> {
  await api<void>(`${BASE}/identifiers/${identifierId}/shops/${shopId}`, 'DELETE');
}

export async function fetchGraph(): Promise<LinkageRiskGraph> {
  return api<LinkageRiskGraph>(`${BASE}/graph`);
}

export async function runScan(): Promise<LinkageRiskScanResult> {
  return api<LinkageRiskScanResult>(`${BASE}/scan`, 'POST', {});
}

export async function fetchAlerts(
  status = '',
  severity = '',
  page = 0,
  size = 20,
): Promise<LinkageRiskAlertPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (status.trim()) query.set('status', status.trim());
  if (severity.trim()) query.set('severity', severity.trim());
  return api<LinkageRiskAlertPage>(`${BASE}/alerts?${query}`);
}

export async function fetchAlert(alertId: string): Promise<LinkageRiskAlert> {
  return api<LinkageRiskAlert>(`${BASE}/alerts/${alertId}`);
}

export async function acknowledgeAlert(alertId: string, reason: string): Promise<LinkageRiskAlert> {
  return api<LinkageRiskAlert>(`${BASE}/alerts/${alertId}/acknowledge`, 'POST', { reason });
}

export async function resolveAlert(alertId: string, reason: string): Promise<LinkageRiskAlert> {
  return api<LinkageRiskAlert>(`${BASE}/alerts/${alertId}/resolve`, 'POST', { reason });
}

export async function dismissAlert(alertId: string, reason: string): Promise<LinkageRiskAlert> {
  return api<LinkageRiskAlert>(`${BASE}/alerts/${alertId}/dismiss`, 'POST', { reason });
}

export async function fetchRemediations(
  status = '',
  page = 0,
  size = 20,
): Promise<LinkageRiskRemediationPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (status.trim()) query.set('status', status.trim());
  return api<LinkageRiskRemediationPage>(`${BASE}/remediations?${query}`);
}

export async function updateRemediationStatus(
  remediationId: string,
  status: string,
  note: string,
): Promise<LinkageRiskRemediation> {
  return api<LinkageRiskRemediation>(`${BASE}/remediations/${remediationId}/status`, 'PUT', {
    status,
    note,
  });
}

// ================================================================ 展示映射

/** 维度标签。 */
export function dimensionLabel(dimension: string): string {
  return DIMENSIONS.find((d) => d.value === dimension)?.label ?? dimension;
}

/** 严重度标签。 */
export function severityLabel(severity: string): string {
  return SEVERITIES.find((s) => s.value === severity)?.label ?? severity;
}

/** 严重度色调。 */
export function severityTone(severity: string): Tone {
  return SEVERITIES.find((s) => s.value === severity)?.tone ?? 'neutral';
}

/** 提示状态标签。 */
export function alertStatusLabel(status: string): string {
  return ALERT_STATUSES.find((s) => s.value === status)?.label ?? status;
}

/** 提示状态色调。 */
export function alertStatusTone(status: string): Tone {
  return ALERT_STATUSES.find((s) => s.value === status)?.tone ?? 'neutral';
}

/** 建议状态标签。 */
export function remediationStatusLabel(status: string): string {
  return REMEDIATION_STATUSES.find((s) => s.value === status)?.label ?? status;
}

/** 建议状态色调。 */
export function remediationStatusTone(status: string): Tone {
  return REMEDIATION_STATUSES.find((s) => s.value === status)?.tone ?? 'neutral';
}

/** 事实来源标签。 */
export function sourceLabel(source: string): string {
  return SOURCES.find((s) => s.value === source)?.label ?? source;
}

/** 处置动作标签（处置留痕流水用）。 */
export function actionLabel(action: string): string {
  if (action === 'ACKNOWLEDGE') return '确认';
  if (action === 'RESOLVE') return '处置';
  if (action === 'DISMISS') return '忽略';
  if (action === 'RESCAN') return '重扫发现';
  return action;
}

/**
 * 能力声明里**读不到**的能力面。
 *
 * 🔴 页面必须把它们显示成「读不到 + 归属模块」，**绝不能**显示成「该维度无风险」。
 */
export function unavailableCapabilities(caps: LinkageRiskCapabilities | null): LinkageRiskCapability[] {
  if (!caps) return [];
  return caps.capabilities.filter((c) => !c.available);
}

/**
 * 能力声明里**可登记但不自动发现**的能力面。
 *
 * 🔴 设备 / 网络 IP / 地址属于这一类：事实要靠租户登记，本模块**不自动发现**。
 */
export function registeredOnlyCapabilities(
  caps: LinkageRiskCapabilities | null,
): LinkageRiskCapability[] {
  if (!caps) return [];
  return caps.capabilities.filter((c) => c.available && !c.autoDiscoverable);
}

/**
 * 店铺的法人绑定文案 —— **三种情况文案各不相同**。
 *
 * 🔴 「未绑定法人」（事实：这家店没绑法人）、「法人档案读不到」（读面坏了）、
 *   「法人档案里没有这一条」（上游查无此人）是三件不同的事，
 *   折成一句话就是把「我们没查到」说成「它没有」。
 */
export function legalEntityText(shop: LinkageRiskShop | null): string {
  if (!shop) return '读不到店铺';
  if (!shop.legalEntityId) return '未绑定法人';
  if (!shop.legalEntityNameAvailable) return '法人名称读不到（identity 不可达）';
  if (!shop.legalEntityName) return '法人档案里没有这一条法人';
  return shop.legalEntityName;
}

/** 关联边的因子文案。 */
export function factorText(link: LinkageRiskGraphLink | null): string {
  if (!link) return '';
  const source = link.autoDiscovered ? '自动发现' : '登记事实';
  return `${link.dimensionLabel}「${link.factorLabel}」· ${link.shopCount} 家店铺 · ${source}`;
}

/** 关联组的维度文案。 */
export function clusterDimensionText(cluster: LinkageRiskGraphCluster | null): string {
  if (!cluster) return '';
  return cluster.dimensions.map(dimensionLabel).join(' / ');
}

/**
 * 提示能不能再处置。
 *
 * 🔴 已整改 / 已忽略是**终态**（后端状态机 + DB 守卫双重钉死），
 * 界面不提供入口，免得点了只换来一个 409。
 */
export function canActOnAlert(alert: LinkageRiskAlert | null): boolean {
  if (!alert) return false;
  return alert.status === 'OPEN' || alert.status === 'ACKNOWLEDGED';
}

/** 建议还能不能改状态。 */
export function canActOnRemediation(remediation: LinkageRiskRemediation | null): boolean {
  if (!remediation) return false;
  return remediation.status === 'PROPOSED' || remediation.status === 'ACCEPTED';
}

/**
 * 四种空态的文案 —— **各不相同**。
 *
 * 🔴 「还没判定」「读不到」「确实没有」「出错」必须能被一眼分开，
 *   否则「没登记策略」会被读成「没有关联风险」，那是最贵的一种误导。
 */
export function emptyState(
  kind: 'NO_POLICY' | 'NO_SHOPS' | 'NO_FACTS' | 'NO_CLUSTER' | 'ERROR',
): { title: string; description: string } {
  if (kind === 'NO_POLICY') {
    return {
      title: '还没登记关联风险策略',
      description:
        '关联图谱尚未启用 —— 没有阈值与各维度的政策依据就没有判定依据。' +
        '🔴 这**不等于「没有关联」**，请先在策略里显式选择阈值并写明政策依据。',
    };
  }
  if (kind === 'NO_SHOPS') {
    return {
      title: '读不到店铺',
      description:
        '这个租户当前读到 0 家店铺 —— 图谱的节点来源是真实的店铺表。' +
        '请确认店铺已录入（2 店铺），以及调用方有 channel.operation.read 权限' +
        '（店铺表上有数据范围策略，权限不对会静默读到零行）。',
    };
  }
  if (kind === 'NO_FACTS') {
    return {
      title: '还没登记共享事实',
      description:
        '设备 / 网络 IP / 地址这三个维度在本仓没有上游数据表，本模块**不自动发现**它们。' +
        '请登记你已经观测到的共享事实，登记后本模块据此实算图谱与风险。',
    };
  }
  if (kind === 'NO_CLUSTER') {
    return {
      title: '没有发现关联组',
      description:
        '已按当前事实实算过图谱，没有发现共享同一因子的店铺。' +
        '🔴 这只说明**已登记的事实**里没有关联，不代表平台侧没有 —— ' +
        '没登记的事实本模块看不到。',
    };
  }
  return {
    title: '加载失败',
    description: '关联风险数据加载失败，请重试。若持续失败，请确认身份服务与 channel 服务可用。',
  };
}

/** 图谱摘要（节点 / 边 / 组）。 */
export function graphSummary(graph: LinkageRiskGraph | null): string {
  if (!graph) return '';
  if (!graph.known) return '关联图谱尚未启用';
  return `${graph.shopCount} 家店铺 · ${graph.linkCount} 条关联边 · ${graph.clusterCount} 个关联组`;
}
