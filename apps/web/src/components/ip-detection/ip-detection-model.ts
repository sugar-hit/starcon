import { api } from '../../lib/api';

/**
 * 16.4.1.2 检测与预警 前端模型。
 *
 * 蓝图：「对商品内容做侵权检测：标题/描述/图片/品牌字段比对侵权库，输出风险等级与命中项；
 * 接入外部商标/专利查询；已上架商品的定期巡检。输入：商品内容与图片；侵权库；外部查询结果。
 * 输出：检测报告 + 预警 + 整改建议。关系：上游 = 16.4.1.1、4.2.1.1；下游 = 16.2.1.2 拦截、16.4.2 授权核验。
 * **图片侵权检测难度高于文本**，误判与漏判都要留人工复核环节。」
 *
 * 🔴 这个页面上最容易做丢的六件事，这里都挡住了：
 *
 * 1. **风险等级沿用上游口径**（高 / 中 / 低，另加「无命中」）—— 不是 0..100 的裸数字。
 * 2. **命中必须可解释** —— 界面上每条命中都点名命中了**哪一条目**、**哪些匹配特征**、
 *    **在哪个字段**、**命中处原文**；没有这些，运营没法复核也没法申诉。
 * 3. **图片命中一律进人工复核** —— 界面上标「待人工复核」，**不标**「已判定侵权」。
 * 4. **结论三态** —— 「无命中」/「待人工复核」/「需整改」是**三件不同的事**，
 *    页面不把「待复核」显示成「没问题」。
 * 5. **没有拦截 / 放行入口** —— 拦截归 16.2.1.2，本页只显示**建议**。
 * 6. **未交付的外部边界灰掉** —— 外部商标库自动查询、以图搜图都**没交付**，
 *    界面上如实标出，不编「已接入外部商标库」。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/ip-detections';

/** 🔴 风险等级值域（与后端 CHECK 逐字一致）：沿用上游 HIGH / MEDIUM / LOW，另有 NONE。 */
export const RISK_LEVELS = ['HIGH', 'MEDIUM', 'LOW', 'NONE'] as const;

/** 🔴 结论三态（与后端 CHECK 逐字一致）。 */
export const CONCLUSIONS = ['CLEAR', 'REVIEW_REQUIRED', 'ACTION_REQUIRED'] as const;

/** 建议的下游动作值域。 */
export const RECOMMENDED_ACTIONS = [
  'NONE',
  'NOTIFY',
  'REMEDIATE',
  'INTERCEPT',
  'VERIFY_AUTHORIZATION',
] as const;

/** 检测对象类型值域。 */
export const SUBJECT_TYPES = ['LISTING', 'DRAFT', 'MANUAL'] as const;

/** 🔴 命中字段值域。 */
export const FIELDS = ['TITLE', 'DESCRIPTION', 'BULLET', 'IMAGE', 'BRAND'] as const;

/** 命中特征分类值域。 */
export const FEATURE_KINDS = ['TERM', 'BRAND_NAME', 'PATTERN', 'EXTERNAL_TERM'] as const;

/** 预警状态值域。 */
export const ALERT_STATUSES = ['OPEN', 'ACKNOWLEDGED', 'RESOLVED'] as const;

/** 整改动作值域。 */
export const REMEDIATION_ACTIONS = [
  'REMOVE_TERM',
  'REWRITE_TITLE',
  'REPLACE_IMAGE',
  'VERIFY_BRAND_AUTH',
  'APPLY_EXEMPTION',
  'CONTACT_RIGHTS_HOLDER',
] as const;

/** 整改状态值域。 */
export const REMEDIATION_STATUSES = ['OPEN', 'IN_PROGRESS', 'DONE', 'DISMISSED'] as const;

/** 复核原因值域。 */
export const REVIEW_REASONS = ['IMAGE_MATCH', 'TEXT_MISMATCH', 'APPEAL'] as const;

/** 复核状态值域。 */
export const REVIEW_STATUSES = ['PENDING', 'CONFIRMED', 'DISMISSED'] as const;

/** 外部查询状态值域。 */
export const QUERY_STATUSES = ['REGISTERED', 'RETURNED', 'NO_RESULT', 'FAILED'] as const;

/** 巡检范围值域。 */
export const PLAN_SCOPES = ['ALL', 'SHOP'] as const;

/** 巡检运行状态值域（**只有终态** —— 巡检运行是 append-only 的回执）。 */
export const RUN_STATUSES = ['COMPLETED', 'PARTIAL', 'FAILED'] as const;

/** 风险等级筛选的可选项。 */
export const RISK_LEVEL_OPTIONS = [
  { value: '', label: '全部风险等级' },
  { value: 'HIGH', label: '高风险' },
  { value: 'MEDIUM', label: '中风险' },
  { value: 'LOW', label: '低风险' },
  { value: 'NONE', label: '无命中' },
] as const;

/** 结论筛选的可选项。 */
export const CONCLUSION_OPTIONS = [
  { value: '', label: '全部结论' },
  { value: 'CLEAR', label: '无命中' },
  { value: 'REVIEW_REQUIRED', label: '待人工复核' },
  { value: 'ACTION_REQUIRED', label: '需整改' },
] as const;

/** 检测对象类型筛选的可选项。 */
export const SUBJECT_TYPE_OPTIONS = [
  { value: '', label: '全部对象' },
  { value: 'LISTING', label: '已上架商品' },
  { value: 'DRAFT', label: '刊登前草稿' },
  { value: 'MANUAL', label: '手工提交内容' },
] as const;

/** 预警状态筛选的可选项。 */
export const ALERT_STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'OPEN', label: '待处理' },
  { value: 'ACKNOWLEDGED', label: '已确认' },
  { value: 'RESOLVED', label: '已处置' },
] as const;

/** 复核状态筛选的可选项。 */
export const REVIEW_STATUS_OPTIONS = [
  { value: '', label: '全部复核状态' },
  { value: 'PENDING', label: '待复核' },
  { value: 'CONFIRMED', label: '确认侵权' },
  { value: 'DISMISSED', label: '判定误报' },
] as const;

// ==================================================================
// 类型
// ==================================================================

/** 一个下游（或外部边界）的交付状态。 */
export interface IpDetectionCapabilityGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 能力声明。 */
export interface IpDetectionCapabilities {
  gaps: IpDetectionCapabilityGap[];
  riskLevels: string[];
  riskLevelLabels: Record<string, string>;
  conclusions: string[];
  conclusionLabels: Record<string, string>;
  recommendedActions: string[];
  recommendedActionLabels: Record<string, string>;
  subjectTypes: string[];
  subjectTypeLabels: Record<string, string>;
  fields: string[];
  fieldLabels: Record<string, string>;
  featureKinds: string[];
  featureKindLabels: Record<string, string>;
  alertTypes: string[];
  alertTypeLabels: Record<string, string>;
  alertStatuses: string[];
  alertStatusLabels: Record<string, string>;
  remediationActions: string[];
  remediationActionLabels: Record<string, string>;
  remediationStatuses: string[];
  remediationStatusLabels: Record<string, string>;
  reviewReasons: string[];
  reviewReasonLabels: Record<string, string>;
  reviewStatuses: string[];
  reviewStatusLabels: Record<string, string>;
  queryStatuses: string[];
  queryStatusLabels: Record<string, string>;
  planScopes: string[];
  planScopeLabels: Record<string, string>;
  riskLevelNote: string;
  matchNote: string;
  imageNote: string;
  reviewNote: string;
  externalNote: string;
  inspectionNote: string;
  upstreamNote: string;
  downstreamNote: string;
}

/** 发起一次检测的入参。 */
export interface IpDetectionSubjectInput {
  subjectType: string;
  listingId?: string | null;
  draftId?: string | null;
  title?: string | null;
  description?: string | null;
  bulletPoints?: string[];
  imageUrls?: string[];
  brandName?: string | null;
  includeImage?: boolean;
}

/** 一份检测报告。 */
export interface IpDetectionReport {
  reportId: string;
  subjectType: string;
  subjectTypeLabel: string;
  subjectId: string | null;
  subjectLabel: string;
  shopId: string | null;
  platformCode: string | null;
  triggerType: string;
  inspectionRunId: string | null;
  riskLevel: string;
  riskLevelLabel: string;
  conclusion: string;
  conclusionLabel: string;
  recommendedAction: string;
  recommendedActionLabel: string;
  textHitCount: number;
  imageHitCount: number;
  entryHitCount: number;
  pendingReviewCount: number;
  scannedFieldCount: number;
  sourceNote: string;
  createdAt: string;
}

/** 检测报告清单。 */
export interface IpDetectionReportPage {
  items: IpDetectionReport[];
  total: number;
  page: number;
  size: number;
  riskLevelCounts: Record<string, number>;
  conclusionCounts: Record<string, number>;
  pendingReviewTotal: number;
  note: string;
}

/** 一条命中项。 */
export interface IpDetectionHit {
  hitId: string;
  reportId: string;
  entryId: string | null;
  externalHitId: string | null;
  entryCode: string;
  rightType: string;
  riskLevel: string;
  field: string;
  fieldLabel: string;
  featureKind: string;
  featureKindLabel: string;
  matchedFeature: string;
  matchedText: string;
  /** 🔴 图片命中恒为 true。 */
  needsManualReview: boolean;
  /** 该命中的复核状态（还没进复核队列时为空）。 */
  reviewStatus: string | null;
  createdAt: string;
}

/** 一条预警。 */
export interface IpDetectionAlert {
  alertId: string;
  reportId: string;
  alertLevel: string;
  alertLevelLabel: string;
  alertType: string;
  alertTypeLabel: string;
  title: string;
  detail: string;
  status: string;
  statusLabel: string;
  acknowledgedBy: string | null;
  acknowledgedAt: string | null;
  resolutionNote: string;
  createdAt: string;
}

/** 一条整改建议。 */
export interface IpDetectionRemediation {
  remediationId: string;
  reportId: string;
  hitId: string | null;
  actionCode: string;
  actionLabel: string;
  suggestion: string;
  priority: string;
  status: string;
  statusLabel: string;
  assigneeId: string | null;
  dueAt: string | null;
  completedAt: string | null;
  createdAt: string;
}

/** 一条复核任务。 */
export interface IpDetectionReviewTask {
  reviewTaskId: string;
  reportId: string;
  hitId: string | null;
  reason: string;
  reasonLabel: string;
  status: string;
  statusLabel: string;
  reviewerId: string | null;
  reviewedAt: string | null;
  reviewComment: string;
  createdAt: string;
}

/** 报告详情。 */
export interface IpDetectionReportDetail {
  report: IpDetectionReport;
  hits: IpDetectionHit[];
  alerts: IpDetectionAlert[];
  remediations: IpDetectionRemediation[];
  reviewTasks: IpDetectionReviewTask[];
  note: string;
}

/** 预警清单。 */
export interface IpDetectionAlertPage {
  items: IpDetectionAlert[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 整改建议清单。 */
export interface IpDetectionRemediationPage {
  items: IpDetectionRemediation[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 复核队列清单。 */
export interface IpDetectionReviewTaskPage {
  items: IpDetectionReviewTask[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 一条外部商标 / 专利查询登记。 */
export interface IpDetectionExternalQuery {
  queryId: string;
  queryCode: string;
  rightType: string;
  rightTypeLabel: string;
  keyword: string;
  platformCode: string | null;
  sourceName: string;
  queryStatus: string;
  queryStatusLabel: string;
  resultCount: number;
  errorMessage: string;
  queriedAt: string | null;
  note: string;
  createdAt: string;
}

/** 一条外部查询命中。 */
export interface IpDetectionExternalHit {
  externalHitId: string;
  queryId: string;
  matchedKey: string;
  rightType: string;
  riskLevel: string;
  registrationNo: string | null;
  rightsHolder: string;
  sourceName: string;
  createdAt: string;
}

/** 外部查询清单。 */
export interface IpDetectionExternalQueryPage {
  items: IpDetectionExternalQuery[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 外部查询详情。 */
export interface IpDetectionExternalQueryDetail {
  query: IpDetectionExternalQuery;
  hits: IpDetectionExternalHit[];
  note: string;
}

/** 登记外部查询结果的入参。 */
export interface IpDetectionExternalHitInput {
  matchedKey: string;
  rightType: string;
  riskLevel: string;
  registrationNo?: string | null;
  rightsHolder?: string | null;
  sourceName?: string | null;
}

/** 一条巡检计划。 */
export interface IpDetectionInspectionPlan {
  planId: string;
  planCode: string;
  scope: string;
  scopeLabel: string;
  shopId: string | null;
  intervalDays: number;
  includeImage: boolean;
  enabled: boolean;
  lastRunAt: string | null;
  nextRunAt: string | null;
  note: string;
  createdAt: string;
}

/** 一次巡检运行。 */
export interface IpDetectionInspectionRun {
  runId: string;
  planId: string;
  planCode: string;
  status: string;
  statusLabel: string;
  listingScanned: number;
  reportCount: number;
  hitListingCount: number;
  errorMessage: string;
  startedAt: string;
  finishedAt: string | null;
}

/** 巡检运行清单。 */
export interface IpDetectionInspectionRunPage {
  items: IpDetectionInspectionRun[];
  total: number;
  page: number;
  size: number;
  note: string;
}

/** 巡检运行详情。 */
export interface IpDetectionInspectionRunDetail {
  run: IpDetectionInspectionRun;
  reports: IpDetectionReport[];
  note: string;
}

// ==================================================================
// 读取
// ==================================================================

export async function fetchCapabilities(): Promise<IpDetectionCapabilities> {
  return api<IpDetectionCapabilities>(`${BASE}/capabilities`);
}

export async function fetchReports(
  page = 0,
  size = 20,
  filters: {
    riskLevel?: string;
    conclusion?: string;
    subjectType?: string;
    pendingOnly?: boolean;
    inspectionRunId?: string;
    keyword?: string;
  } = {},
): Promise<IpDetectionReportPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.riskLevel) query.set('riskLevel', filters.riskLevel);
  if (filters.conclusion) query.set('conclusion', filters.conclusion);
  if (filters.subjectType) query.set('subjectType', filters.subjectType);
  if (filters.pendingOnly) query.set('pendingOnly', 'true');
  if (filters.inspectionRunId) query.set('inspectionRunId', filters.inspectionRunId);
  if (filters.keyword) query.set('keyword', filters.keyword);
  return api<IpDetectionReportPage>(`${BASE}/detections?${query.toString()}`);
}

export async function fetchReport(reportId: string): Promise<IpDetectionReportDetail> {
  return api<IpDetectionReportDetail>(`${BASE}/detections/${reportId}`);
}

export async function detectSubject(
  input: IpDetectionSubjectInput,
): Promise<IpDetectionReportDetail> {
  return api<IpDetectionReportDetail>(`${BASE}/detections`, 'POST', input);
}

export async function fetchAlerts(
  page = 0,
  size = 20,
  status?: string,
): Promise<IpDetectionAlertPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) query.set('status', status);
  return api<IpDetectionAlertPage>(`${BASE}/alerts?${query.toString()}`);
}

export async function setAlertStatus(
  alertId: string,
  status: string,
  resolutionNote?: string,
): Promise<IpDetectionAlert> {
  return api<IpDetectionAlert>(`${BASE}/alerts/${alertId}/status`, 'PUT', {
    status,
    resolutionNote: resolutionNote ?? '',
  });
}

export async function fetchRemediations(
  page = 0,
  size = 20,
  status?: string,
): Promise<IpDetectionRemediationPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) query.set('status', status);
  return api<IpDetectionRemediationPage>(`${BASE}/remediations?${query.toString()}`);
}

export async function setRemediationStatus(
  remediationId: string,
  status: string,
): Promise<IpDetectionRemediation> {
  return api<IpDetectionRemediation>(`${BASE}/remediations/${remediationId}/status`, 'PUT', {
    status,
  });
}

export async function fetchReviews(
  page = 0,
  size = 20,
  status?: string,
): Promise<IpDetectionReviewTaskPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) query.set('status', status);
  return api<IpDetectionReviewTaskPage>(`${BASE}/reviews?${query.toString()}`);
}

export async function decideReview(
  reviewTaskId: string,
  status: string,
  comment: string,
): Promise<IpDetectionReviewTask> {
  return api<IpDetectionReviewTask>(`${BASE}/reviews/${reviewTaskId}`, 'PUT', { status, comment });
}

export async function fetchExternalQueries(
  page = 0,
  size = 20,
  status?: string,
): Promise<IpDetectionExternalQueryPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (status) query.set('status', status);
  return api<IpDetectionExternalQueryPage>(`${BASE}/external-queries?${query.toString()}`);
}

export async function fetchExternalQuery(
  queryId: string,
): Promise<IpDetectionExternalQueryDetail> {
  return api<IpDetectionExternalQueryDetail>(`${BASE}/external-queries/${queryId}`);
}

export async function createExternalQuery(input: {
  queryCode: string;
  rightType: string;
  keyword: string;
  platformCode?: string | null;
  sourceName?: string;
  note?: string;
}): Promise<IpDetectionExternalQuery> {
  return api<IpDetectionExternalQuery>(`${BASE}/external-queries`, 'POST', input);
}

export async function registerExternalHits(
  queryId: string,
  hits: IpDetectionExternalHitInput[],
): Promise<IpDetectionExternalQueryDetail> {
  return api<IpDetectionExternalQueryDetail>(`${BASE}/external-queries/${queryId}/hits`, 'POST', hits);
}

export async function setExternalQueryStatus(
  queryId: string,
  status: string,
  errorMessage?: string,
): Promise<IpDetectionExternalQuery> {
  const query = new URLSearchParams({ status });
  if (errorMessage) query.set('errorMessage', errorMessage);
  return api<IpDetectionExternalQuery>(
    `${BASE}/external-queries/${queryId}/status?${query.toString()}`,
    'PUT',
  );
}

export async function fetchInspectionPlans(): Promise<IpDetectionInspectionPlan[]> {
  return api<IpDetectionInspectionPlan[]>(`${BASE}/inspection-plans`);
}

export async function createInspectionPlan(input: {
  planCode: string;
  scope: string;
  shopId?: string | null;
  intervalDays?: number;
  includeImage?: boolean;
  enabled?: boolean;
  note?: string;
}): Promise<IpDetectionInspectionPlan> {
  return api<IpDetectionInspectionPlan>(`${BASE}/inspection-plans`, 'POST', input);
}

export async function updateInspectionPlan(
  planId: string,
  input: {
    planCode: string;
    scope: string;
    shopId?: string | null;
    intervalDays?: number;
    includeImage?: boolean;
    enabled?: boolean;
    note?: string;
  },
): Promise<IpDetectionInspectionPlan> {
  return api<IpDetectionInspectionPlan>(`${BASE}/inspection-plans/${planId}`, 'PUT', input);
}

export async function fetchInspectionRuns(page = 0, size = 20): Promise<IpDetectionInspectionRunPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return api<IpDetectionInspectionRunPage>(`${BASE}/inspection-runs?${query.toString()}`);
}

export async function fetchInspectionRun(runId: string): Promise<IpDetectionInspectionRunDetail> {
  return api<IpDetectionInspectionRunDetail>(`${BASE}/inspection-runs/${runId}`);
}

export async function runInspection(planId: string): Promise<IpDetectionInspectionRunDetail> {
  const query = new URLSearchParams({ planId });
  return api<IpDetectionInspectionRunDetail>(`${BASE}/inspection-runs?${query.toString()}`, 'POST');
}

// ==================================================================
// 展示口径
// ==================================================================

export function riskLevelLabel(code: string | null | undefined): string {
  const map: Record<string, string> = {
    HIGH: '高风险',
    MEDIUM: '中风险',
    LOW: '低风险',
    NONE: '无命中',
  };
  if (!code) return '未声明';
  return map[code] ?? code;
}

/** 🔴 风险等级的色调：高=红、中=黄、低=中性、无命中=中性。 */
export function riskLevelTone(code: string | null | undefined): 'danger' | 'warning' | 'neutral' {
  if (code === 'HIGH') return 'danger';
  if (code === 'MEDIUM') return 'warning';
  return 'neutral';
}

export function conclusionLabel(code: string | null | undefined): string {
  const map: Record<string, string> = {
    CLEAR: '无命中',
    REVIEW_REQUIRED: '待人工复核',
    ACTION_REQUIRED: '需整改',
  };
  if (!code) return '未声明';
  return map[code] ?? code;
}

/**
 * 🔴 结论的色调：**待人工复核**用中性色而不是绿色。
 *
 * 绿色会让人以为「没问题」—— 而「还没判」正是最需要人看一眼的状态。
 */
export function conclusionTone(
  code: string | null | undefined,
): 'success' | 'warning' | 'danger' | 'neutral' {
  if (code === 'CLEAR') return 'success';
  if (code === 'ACTION_REQUIRED') return 'danger';
  if (code === 'REVIEW_REQUIRED') return 'warning';
  return 'neutral';
}

export function recommendedActionLabel(code: string | null | undefined): string {
  const map: Record<string, string> = {
    NONE: '无需动作',
    NOTIFY: '通知运营',
    REMEDIATE: '整改后重检',
    INTERCEPT: '建议下游拦截',
    VERIFY_AUTHORIZATION: '建议核验品牌授权',
  };
  if (!code) return '未声明';
  return map[code] ?? code;
}

export function fieldLabel(code: string | null | undefined): string {
  const map: Record<string, string> = {
    TITLE: '标题',
    DESCRIPTION: '描述',
    BULLET: '卖点',
    IMAGE: '图片',
    BRAND: '品牌',
  };
  if (!code) return '未声明';
  return map[code] ?? code;
}

export function featureKindLabel(code: string | null | undefined): string {
  const map: Record<string, string> = {
    TERM: '侵权词 / 短语',
    BRAND_NAME: '品牌名',
    PATTERN: '标识符特征',
    EXTERNAL_TERM: '外部查询关键词',
  };
  if (!code) return '未声明';
  return map[code] ?? code;
}

export function alertStatusLabel(code: string | null | undefined): string {
  const map: Record<string, string> = {
    OPEN: '待处理',
    ACKNOWLEDGED: '已确认',
    RESOLVED: '已处置',
  };
  if (!code) return '未声明';
  return map[code] ?? code;
}

export function remediationActionLabel(code: string | null | undefined): string {
  const map: Record<string, string> = {
    REMOVE_TERM: '删除侵权词 / 短语',
    REWRITE_TITLE: '改写标题',
    REPLACE_IMAGE: '替换图片',
    VERIFY_BRAND_AUTH: '核验品牌授权',
    APPLY_EXEMPTION: '申请豁免',
    CONTACT_RIGHTS_HOLDER: '联系权利人协商',
  };
  if (!code) return '未声明';
  return map[code] ?? code;
}

export function remediationStatusLabel(code: string | null | undefined): string {
  const map: Record<string, string> = {
    OPEN: '待处理',
    IN_PROGRESS: '处理中',
    DONE: '已办完',
    DISMISSED: '已放弃',
  };
  if (!code) return '未声明';
  return map[code] ?? code;
}

export function reviewStatusLabel(code: string | null | undefined): string {
  const map: Record<string, string> = {
    PENDING: '待复核',
    CONFIRMED: '确认侵权',
    DISMISSED: '判定误报',
  };
  if (!code) return '未声明';
  return map[code] ?? code;
}

export function queryStatusLabel(code: string | null | undefined): string {
  const map: Record<string, string> = {
    REGISTERED: '已登记',
    RETURNED: '已查回结果',
    NO_RESULT: '查无结果',
    FAILED: '查询失败',
  };
  if (!code) return '未声明';
  return map[code] ?? code;
}

export function runStatusLabel(code: string | null | undefined): string {
  const map: Record<string, string> = {
    COMPLETED: '已完成',
    PARTIAL: '部分完成',
    FAILED: '失败',
  };
  if (!code) return '未声明';
  return map[code] ?? code;
}

/**
 * 🔴 命中来源的展示：本地侵权库条目还是外部查询结果。
 *
 * 两者是**不同的来源**，混成一句「命中了某条目」会让人分不清
 * 「我们自己登记的风险」和「外部库查回来的结果」。
 */
export function hitSourceLabel(hit: IpDetectionHit): string {
  if (hit.externalHitId) return '外部查询结果';
  return `侵权库条目 ${hit.entryCode || '(未登记编码)'}`;
}

/** 空态：两种（还没有数据 / 筛选无结果）。 */
export type EmptyKind = 'NO_DATA' | 'NO_MATCH';

export function emptyStateKind(total: number, filtered: boolean): EmptyKind {
  if (filtered) return 'NO_MATCH';
  if (total === 0) return 'NO_DATA';
  return 'NO_MATCH';
}

export function emptyState(kind: EmptyKind): { title: string; description: string } {
  if (kind === 'NO_DATA') {
    return {
      title: '还没有任何检测报告',
      description:
        '发起一次检测（刊登前草稿 / 已上架商品 / 手工提交内容），或配一条巡检计划让系统定期跑已上架商品。',
    };
  }
  return {
    title: '筛选条件下没有报告',
    description: '当前筛选（风险等级 / 结论 / 对象类型 / 关键字）下没有匹配的检测报告。',
  };
}

/** 🔴 复核队列为空时的提示：**说清楚「为什么空」**，别让人以为是加载失败。 */
export function reviewEmptyState(): { title: string; description: string } {
  return {
    title: '复核队列是空的',
    description:
      '🔴 图片命中会自动进这里；文本命中若被判误判，也可由运营提复核。当前没有待复核的命中。',
  };
}
