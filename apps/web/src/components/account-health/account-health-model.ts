import { api } from '../../lib/api';

/**
 * 16.1.1.1 账号健康总览 前端模型。
 *
 * 蓝图：「按店铺展示账号健康状态：绩效指标（ODR、迟发率、取消率、有效追踪率）、政策违规计数、
 * 申诉状态、风险等级，支持跨店铺对比与风险排序。输入：平台绩效数据同步；风险等级计算规则；
 * 用户数据范围（店铺维度）。输出：店铺健康卡片 + 风险排序 + 需处理项跳转。
 * 关系：上游 = 2 同步；下游 = 16.1.1.2 指标明细、16.1.2.1 违规。
 * **风险排序比明细更重要**：先处理最可能掉号的店铺。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「量不到」显示成「量不到」，不显示成「0%」**。后端给 `null` 就是 `null`，
 *    前端**不重算**、不把 `null` 渲染成 `0.00%`。
 * 2. **「判不了」是第三种状态**：显示成「判不了（指标不全）」，而且**不排在健康后面** ——
 *    量不全的店铺还不知道会不会掉号。
 * 3. **每条等级都带依据**：命中了哪一项指标、什么值、命中哪条阈值，后端逐条给，
 *    前端原样显示，**不自己编一句「账号有风险」**。
 * 4. **手工录入的指标一眼看得出**（`MANUAL` 标签），不冒充平台同步来的。
 * 5. **排序位置有解释**：每一家都带 `rankReason`，不只给一个名次。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/account-health';

/** 四项绩效指标（顺序与后端一致）。 */
export const METRIC_CODES = [
  'ODR',
  'LATE_SHIPMENT_RATE',
  'CANCELLATION_RATE',
  'VALID_TRACKING_RATE',
] as const;

export type MetricCode = (typeof METRIC_CODES)[number];

/** 风险等级筛选的可选项。 */
export const RISK_LEVELS = [
  { value: '', label: '全部等级' },
  { value: 'CRITICAL', label: '严重（先处理）' },
  { value: 'WARNING', label: '预警' },
  { value: 'UNKNOWN', label: '判不了（指标不全）' },
  { value: 'HEALTHY', label: '健康' },
] as const;

/** 数据状态筛选的可选项。 */
export const DATA_STATES = [
  { value: '', label: '全部' },
  { value: 'HAS_DATA', label: '已采集到指标' },
  { value: 'NO_DATA', label: '还没有任何指标' },
] as const;

// ==================================================================
// 类型
// ==================================================================

/** 一个未交付 / 已交付的下游或维度。 */
export interface AccountHealthCapabilityGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 能力声明。 */
export interface AccountHealthCapabilities {
  gaps: AccountHealthCapabilityGap[];
  metricCodes: string[];
  riskLevels: string[];
  metricSources: string[];
  metricValueSemantics: string;
  thresholdSemantics: string;
  unknownSemantics: string;
  rankingSemantics: string;
  platformSyncNote: string;
  upstreamNote: string;
  downstreamNote: string;
}

/** 一项绩效指标的当前值（`metricValue` 为 null = 量不到）。 */
export interface AccountHealthMetricCard {
  metricCode: string;
  metricLabel: string;
  metricValue: number | null;
  source: string | null;
  sourceLabel: string | null;
  periodStart: string | null;
  periodEnd: string | null;
  capturedAt: string | null;
  note: string | null;
}

/** 一条判定依据。 */
export interface AccountHealthReason {
  metricCode: string;
  metricLabel: string;
  level: string;
  metricValue: number | null;
  thresholdValue: number | null;
  thresholdKind: string;
  direction: string | null;
  message: string;
}

/** 一家店的账号健康卡片。 */
export interface AccountHealthShopCard {
  shopId: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
  platformName: string;
  riskLevel: string;
  riskLevelLabel: string;
  rank: number;
  rankReason: string;
  knownMetricCount: number;
  breachCount: number;
  partial: boolean;
  periodStart: string | null;
  periodEnd: string | null;
  metrics: AccountHealthMetricCard[];
  reasons: AccountHealthReason[];
  unknownMetricCodes: string[];
  ruleSource: string;
  ruleVersion: number;
  platformSyncAvailable: boolean;
}

/** 健康卡片列表（真分页）。 */
export interface AccountHealthShopPage {
  items: AccountHealthShopCard[];
  total: number;
  page: number;
  size: number;
}

/** 风险排序里的一行。 */
export interface AccountHealthRankingRow {
  rank: number;
  shopId: string;
  shopCode: string;
  shopName: string;
  platformCode: string;
  riskLevel: string;
  riskLevelLabel: string;
  knownMetricCount: number;
  breachCount: number;
  partial: boolean;
  rankReason: string;
}

/** 风险排序清单（真分页）。 */
export interface AccountHealthRankingPage {
  items: AccountHealthRankingRow[];
  total: number;
  page: number;
  size: number;
  criticalCount: number;
  warningCount: number;
  unknownCount: number;
  healthyCount: number;
  note: string;
}

/** 一次判定的结论（追加式凭据）。 */
export interface AccountHealthAssessmentResult {
  assessmentId: string;
  shopId: string;
  assessedAt: string;
  periodStart: string;
  periodEnd: string;
  riskLevel: string;
  riskLevelLabel: string;
  knownMetricCount: number;
  breachCount: number;
  ruleVersion: number;
  ruleSource: string;
  thresholds: Record<string, unknown>;
  reasons: AccountHealthReason[];
  summary: string;
}

/** 判定历史里的一行。 */
export interface AccountHealthAssessmentSummary {
  assessmentId: string;
  shopId: string;
  assessedAt: string;
  periodStart: string;
  periodEnd: string;
  riskLevel: string;
  riskLevelLabel: string;
  knownMetricCount: number;
  breachCount: number;
  ruleVersion: number;
  ruleSource: string;
  summary: string;
  reasonCount: number;
}

/** 判定历史（真分页）。 */
export interface AccountHealthAssessmentPage {
  items: AccountHealthAssessmentSummary[];
  total: number;
  page: number;
  size: number;
}

/** 一条生效中的阈值规则。 */
export interface AccountHealthThresholdView {
  metricCode: string;
  metricLabel: string;
  direction: string;
  warnValue: number;
  criticalValue: number;
  ruleVersion: number;
  ruleSource: string;
  ruleSourceLabel: string;
  builtin: boolean;
  editable: boolean;
  effectiveFrom: string | null;
  note: string;
}

/** 阈值清单。 */
export interface AccountHealthThresholdList {
  items: AccountHealthThresholdView[];
  note: string;
}

/** 阈值修改的入参。 */
export interface AccountHealthThresholdInput {
  direction: string;
  warnValue: number;
  criticalValue: number;
  note: string;
}

/** 手工录入一条绩效指标的入参。 */
export interface AccountHealthMetricInput {
  metricCode: string;
  metricValue: number | null;
  periodStart: string;
  periodEnd: string;
  note: string;
}

/** 一条需处理项。 */
export interface AccountHealthPendingItem {
  kind: string;
  label: string;
  description: string;
  shopId: string | null;
  shopCode: string | null;
  shopName: string | null;
  targetModule: string;
  targetPath: string;
  targetLabel: string;
}

/** 需处理项清单。 */
export interface AccountHealthPendingList {
  items: AccountHealthPendingItem[];
  total: number;
  note: string;
}

// ==================================================================
// API
// ==================================================================

export async function fetchCapabilities(): Promise<AccountHealthCapabilities> {
  return api<AccountHealthCapabilities>(`${BASE}/capabilities`);
}

export interface ShopFilters {
  platformCode?: string;
  keyword?: string;
  riskLevel?: string;
  dataState?: string;
  shopId?: string;
}

export async function fetchShops(
  page = 0,
  size = 10,
  filters: ShopFilters = {},
): Promise<AccountHealthShopPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.riskLevel) query.set('riskLevel', filters.riskLevel);
  if (filters.dataState) query.set('dataState', filters.dataState);
  if (filters.shopId) query.set('shopId', filters.shopId);
  return api<AccountHealthShopPage>(`${BASE}/shops?${query.toString()}`);
}

export async function fetchShop(shopId: string): Promise<AccountHealthShopCard> {
  return api<AccountHealthShopCard>(`${BASE}/shops/${shopId}`);
}

export async function fetchRanking(
  page = 0,
  size = 10,
  filters: ShopFilters = {},
): Promise<AccountHealthRankingPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.platformCode) query.set('platformCode', filters.platformCode);
  if (filters.keyword) query.set('keyword', filters.keyword);
  if (filters.riskLevel) query.set('riskLevel', filters.riskLevel);
  if (filters.shopId) query.set('shopId', filters.shopId);
  return api<AccountHealthRankingPage>(`${BASE}/ranking?${query.toString()}`);
}

export async function fetchPendingItems(
  platformCode?: string,
  keyword?: string,
): Promise<AccountHealthPendingList> {
  const query = new URLSearchParams();
  if (platformCode) query.set('platformCode', platformCode);
  if (keyword) query.set('keyword', keyword);
  const suffix = query.toString();
  return api<AccountHealthPendingList>(
    suffix ? `${BASE}/pending-items?${suffix}` : `${BASE}/pending-items`,
  );
}

export async function fetchThresholds(): Promise<AccountHealthThresholdList> {
  return api<AccountHealthThresholdList>(`${BASE}/thresholds`);
}

export async function saveThreshold(
  metricCode: string,
  input: AccountHealthThresholdInput,
): Promise<AccountHealthThresholdView> {
  return api<AccountHealthThresholdView>(`${BASE}/thresholds/${metricCode}`, 'PUT', input);
}

export async function recordMetric(
  shopId: string,
  input: AccountHealthMetricInput,
): Promise<AccountHealthShopCard> {
  return api<AccountHealthShopCard>(`${BASE}/shops/${shopId}/metrics`, 'POST', input);
}

export async function assessShop(shopId: string): Promise<AccountHealthAssessmentResult> {
  return api<AccountHealthAssessmentResult>(`${BASE}/shops/${shopId}/assess`, 'POST', {});
}

export async function fetchAssessments(
  shopId?: string,
  page = 0,
  size = 20,
): Promise<AccountHealthAssessmentPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (shopId) query.set('shopId', shopId);
  return api<AccountHealthAssessmentPage>(`${BASE}/assessments?${query.toString()}`);
}

export async function syncShop(shopId: string): Promise<AccountHealthShopCard> {
  return api<AccountHealthShopCard>(`${BASE}/shops/${shopId}/sync`, 'POST', {});
}

// ==================================================================
// 展示口径（**不重算后端的结论**）
// ==================================================================

/** 风险等级的人话。 */
export function riskLevelLabel(level: string): string {
  switch (level) {
    case 'CRITICAL':
      return '严重（先处理）';
    case 'WARNING':
      return '预警';
    case 'HEALTHY':
      return '健康';
    case 'UNKNOWN':
      return '判不了（指标不全）';
    default:
      return level;
  }
}

/** 风险等级的徽章色调。 */
export function riskLevelTone(level: string): 'danger' | 'warning' | 'success' | 'neutral' {
  switch (level) {
    case 'CRITICAL':
      return 'danger';
    case 'WARNING':
      return 'warning';
    case 'HEALTHY':
      return 'success';
    case 'UNKNOWN':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 指标的人话名（后端已给一份，这里给一份本地兜底）。 */
export function metricLabel(code: string): string {
  switch (code) {
    case 'ODR':
      return '订单缺陷率（ODR）';
    case 'LATE_SHIPMENT_RATE':
      return '迟发率';
    case 'CANCELLATION_RATE':
      return '取消率';
    case 'VALID_TRACKING_RATE':
      return '有效追踪率';
    default:
      return code;
  }
}

/**
 * 🔴 指标值的展示串。
 *
 * <p>**量不到显示成「未知」，绝不显示成 0.00%** —— 后端给 `null` 就是「没测到」，
 * 渲染成 0% 等于把「没查」写成「这项是 0」。
 */
export function metricValueText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '未知';
  return `${(value * 100).toFixed(2)}%`;
}

/** 指标来源的人话 + 是否手工。 */
export function sourceLabel(source: string | null | undefined): string {
  if (!source) return '未采集';
  if (source === 'MANUAL') return '人工录入';
  if (source === 'PLATFORM_SYNC') return '平台同步';
  return source;
}

/** 阈值来源的人话。 */
export function ruleSourceLabel(source: string | null | undefined): string {
  switch (source) {
    case 'BUILTIN_DEFAULT':
      return '内置默认阈值';
    case 'TENANT_CUSTOM':
      return '租户自定义阈值';
    case 'MIXED':
      return '部分自定义 / 部分默认';
    default:
      return source ?? '未声明';
  }
}

/** 依据档位的人话。 */
export function thresholdKindLabel(kind: string): string {
  switch (kind) {
    case 'CRITICAL':
      return '命中严重阈值';
    case 'WARN':
      return '命中警告阈值';
    case 'NONE':
      return '未命中阈值';
    case 'UNKNOWN':
      return '量不到';
    default:
      return kind;
  }
}

/**
 * 🔴 需处理项的类型的人话。
 *
 * <p>三种类型对应三种不同的事实，**不能混成一个「待办」**。
 */
export function pendingKindLabel(kind: string): string {
  switch (kind) {
    case 'RISK_BREACH':
      return '命中阈值';
    case 'DATA_MISSING':
      return '指标不全';
    case 'PLATFORM_SYNC_UNAVAILABLE':
      return '同步未接入';
    default:
      return kind;
  }
}

/**
 * 🔴 空态文案：**三态不同**。
 *
 * @param kind 'NO_SHOP' 租户下还没有店铺 /
 *             'NO_FILTER_MATCH' 当前筛选条件下没有匹配 /
 *             'NO_METRIC' 有店铺但**还没有采集到任何店铺指标**
 */
export function emptyState(
  kind: 'NO_SHOP' | 'NO_FILTER_MATCH' | 'NO_METRIC',
): { title: string; description: string } {
  switch (kind) {
    case 'NO_SHOP':
      return {
        title: '这个租户下还没有店铺',
        description:
          '账号健康总览是**按店铺**展示的。先在「渠道与店铺」里建好店铺，账号健康卡片才会有内容。',
      };
    case 'NO_FILTER_MATCH':
      return {
        title: '当前筛选条件下没有匹配的店铺',
        description:
          '把筛选条件放宽再看看。**这不等于「没有店铺」** —— 换个关键词或清掉等级筛选就能看到别的店铺。',
      };
    case 'NO_METRIC':
      return {
        title: '还没有采集到任何店铺指标',
        description:
          '平台账号绩效同步**未接入**（归卷 02 同步运行），所以现在一个真实指标都没有。'
          + '可以先手工录入你从平台后台看到的 ODR / 迟发率 / 取消率 / 有效追踪率 —— '
          + '录入的条目会标成「人工录入」，不会冒充平台同步。',
      };
    default:
      return { title: '', description: '' };
  }
}

/**
 * 🔴 空态的判定：**「筛选无结果」与「还没有指标」必须分开**。
 *
 * <p>把两者混成一个空态，运营会以为「这个租户没有店铺」，
 * 而真相可能只是「平台绩效还没同步进来」。
 */
export function emptyStateKind(
  totalShops: number,
  filteredTotal: number,
  anyMetricKnown: boolean,
): 'NO_SHOP' | 'NO_FILTER_MATCH' | 'NO_METRIC' {
  if (totalShops === 0) return 'NO_SHOP';
  if (filteredTotal === 0) return 'NO_FILTER_MATCH';
  if (!anyMetricKnown) return 'NO_METRIC';
  return 'NO_FILTER_MATCH';
}
