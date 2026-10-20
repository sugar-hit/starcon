import { api, ApiError } from '../../../lib/api';

/**
 * 10.6.2.1 时效分析前端模型。
 *
 * <p>蓝图：「分析时效表现：各渠道各国家的<b>妥投率</b>、<b>超时率</b>、<b>平均时效波动</b>、
 * <b>旺季变化</b>，用于渠道评分与时效承诺设置。」输出「时效报表 + 渠道评分 + 异常集中分析」，
 * 并加粗「<b>时效数据要按渠道和国家交叉分析，总体平均会掩盖某些渠道在某些国家的系统性延误</b>」。
 *
 * <p>🔴 这个界面上最容易做成假活的一处，是把报表做成「一个总平均 + 一根柱状图」。
 * 那个形状恰好就是加粗句点名要避免的东西 —— 总体平均会把「某渠道在某国系统性延误」
 * 稀释掉，界面上看起来一切正常。
 *
 * <p>所以本模型有三个约定：
 *
 * <ol>
 *   <li><b>交叉维度进数据形状</b>：{@link TimelinessMatrixRow} 的一行 = <b>一个渠道 ×
 *       一个国家</b>；{@link pivotMatrix} 把它摆成真正的矩阵（渠道为行、国家为列），
 *       而不是让页面自己从一张平表里猜。</li>
 *   <li><b>不可比不是 0</b>：{@link rateText} / {@link scoreText} 在值为 null 时显示
 *       「不可比」，<b>绝不</b>显示 0.00% / 0 分 —— 0 是「比过了，确实最差」，
 *       null 是「压根没资格比」。</li>
 *   <li><b>页面不重算任何指标</b>：比率、时长、评分、贡献率一律取服务端算好的值。
 *       浏览器里再算一遍，界面上的名次就会和落库的名次有两个算法。</li>
 * </ol>
 */

// ================================================================ 枚举词表

export type TimelinessStatus = 'ON_TIME' | 'OVERDUE' | 'NO_PROMISE' | 'IN_TRANSIT';

export type ScoreGrade = 'EXCELLENT' | 'GOOD' | 'FAIR' | 'POOR' | 'INSUFFICIENT';

/** 展示顺序：把「有问题」的状态排在前面。 */
export const TIMELINESS_STATUSES: readonly TimelinessStatus[] = [
  'OVERDUE',
  'NO_PROMISE',
  'IN_TRANSIT',
  'ON_TIME',
];

export const SCORE_GRADES: readonly ScoreGrade[] = [
  'EXCELLENT',
  'GOOD',
  'FAIR',
  'POOR',
  'INSUFFICIENT',
];

// ================================================================ 形状

export interface TimelinessCapabilityDimension {
  dimension: string;
  description: string;
  available: boolean;
  source: string;
  ownerModule: string;
  impact: string;
}

export interface LogisticsTimelinessCapabilities {
  dimensions: TimelinessCapabilityDimension[];
  timelinessStatuses: string[];
  scoreGrades: string[];
  defaultWindowDays: number;
  defaultMinSampleThreshold: number;
  windowTimeZone: string;
  boundaryNote: string;
  highlight: string;
  note: string;
}

export interface TimelinessWindow {
  from: string;
  to: string;
  days: number;
  timeZone: string;
  note: string;
}

export interface TimelinessTotals {
  shippedCount: number;
  deliveredCount: number;
  comparableCount: number;
  onTimeCount: number;
  overdueCount: number;
  noPromiseCount: number;
  pendingCount: number;
  returnedCount: number;
  deliveryRate: number | null;
  onTimeRate: number | null;
  overdueRate: number | null;
  avgTransitHours: number | null;
  avgDeviationHours: number | null;
  stddevTransitHours: number | null;
  p90TransitHours: number | null;
  maxOverdueHours: number | null;
}

export interface TimelinessMatrixRow {
  channelId: string;
  channelCode: string;
  channelName: string;
  countryCode: string;
  shippedCount: number;
  deliveredCount: number;
  comparableCount: number;
  onTimeCount: number;
  overdueCount: number;
  noPromiseCount: number;
  pendingCount: number;
  returnedCount: number;
  deliveryRate: number | null;
  onTimeRate: number | null;
  overdueRate: number | null;
  avgTransitHours: number | null;
  avgDeviationHours: number | null;
  stddevTransitHours: number | null;
  p90TransitHours: number | null;
  maxOverdueHours: number | null;
  score: number | null;
  scoreGrade: string;
  sampleSufficient: boolean;
}

export interface TimelinessMatrixPage {
  rows: TimelinessMatrixRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  window: TimelinessWindow;
  totals: TimelinessTotals;
  note: string;
}

export interface TimelinessMonthlyRow {
  monthKey: string;
  shippedCount: number;
  deliveredCount: number;
  comparableCount: number;
  onTimeCount: number;
  overdueCount: number;
  noPromiseCount: number;
  pendingCount: number;
  returnedCount: number;
  deliveryRate: number | null;
  onTimeRate: number | null;
  overdueRate: number | null;
  avgTransitHours: number | null;
  avgDeviationHours: number | null;
  stddevTransitHours: number | null;
  p90TransitHours: number | null;
  vsBaselineTransitHours: number | null;
  peakMonth: boolean;
}

export interface TimelinessMonthlyReport {
  rows: TimelinessMonthlyRow[];
  window: TimelinessWindow;
  baseline: TimelinessTotals;
  peakMonthCount: number;
  note: string;
}

export interface TimelinessAnomalyCell {
  rank: number;
  channelId: string;
  channelCode: string;
  channelName: string;
  countryCode: string;
  overdueCount: number;
  deliveredCount: number;
  overdueRate: number | null;
  avgTransitHours: number | null;
  avgDeviationHours: number | null;
  maxOverdueHours: number | null;
  contributionRate: number | null;
  cumulativeRate: number | null;
  inPareto: boolean;
}

export interface TimelinessAnomalyGroup {
  key: string;
  label: string;
  overdueCount: number;
  share: number | null;
}

export interface TimelinessAnomalyReport {
  cells: TimelinessAnomalyCell[];
  byChannel: TimelinessAnomalyGroup[];
  byCountry: TimelinessAnomalyGroup[];
  totalOverdue: number;
  topOneShare: number | null;
  topThreeShare: number | null;
  paretoThreshold: number | null;
  paretoCellCount: number;
  paretoOverdueCount: number;
  window: TimelinessWindow;
  note: string;
}

export interface TimelinessScoreRow {
  channelId: string;
  channelCode: string;
  channelName: string;
  countryCode: string;
  shippedCount: number;
  deliveredCount: number;
  comparableCount: number;
  onTimeCount: number;
  overdueCount: number;
  deliveryRate: number | null;
  onTimeRate: number | null;
  overdueRate: number | null;
  avgTransitHours: number | null;
  avgDeviationHours: number | null;
  score: number | null;
  scoreGrade: string;
  sampleSufficient: boolean;
  minSampleThreshold: number;
  snapshotComputedAt: string | null;
}

export interface TimelinessScorePage {
  rows: TimelinessScoreRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  window: TimelinessWindow;
  snapshotCount: number;
  note: string;
}

export interface TimelinessScoreSnapshotRow {
  id: string;
  channelId: string;
  channelCode: string;
  channelName: string;
  countryCode: string;
  windowStart: string;
  windowEnd: string;
  windowDays: number;
  shippedCount: number;
  deliveredCount: number;
  comparableCount: number;
  onTimeCount: number;
  overdueCount: number;
  noPromiseCount: number;
  pendingCount: number;
  returnedCount: number;
  deliveryRate: number | null;
  onTimeRate: number | null;
  overdueRate: number | null;
  avgTransitHours: number | null;
  avgDeviationHours: number | null;
  stddevTransitHours: number | null;
  p90TransitHours: number | null;
  maxOverdueHours: number | null;
  score: number | null;
  scoreGrade: string;
  sampleSufficient: boolean;
  minSampleThreshold: number;
  computedAt: string;
}

export interface TimelinessScoreSnapshotPage {
  rows: TimelinessScoreSnapshotRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface TimelinessScoreSnapshotResult {
  window: TimelinessWindow;
  computed: number;
  written: number;
  insufficient: number;
  removed: number;
  minSampleThreshold: number;
  notes: string[];
}

export interface TimelinessSubjectRow {
  id: string;
  masterWaybillNo: string;
  carrierCode: string;
  channelId: string | null;
  channelCode: string | null;
  channelName: string | null;
  countryCode: string;
  serviceLevel: string;
  shippedAt: string;
  deliveredAt: string | null;
  promisedEtaAt: string | null;
  transitHours: number | null;
  deviationHours: number | null;
  timelinessStatus: string;
  timelinessStatusLabel: string;
}

export interface TimelinessSubjectPage {
  rows: TimelinessSubjectRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  window: TimelinessWindow;
  note: string;
}

export interface LogisticsTimelinessOverview {
  window: TimelinessWindow;
  totals: TimelinessTotals;
  channelCount: number;
  countryCount: number;
  cellCount: number;
  unattributedShippedCount: number;
  unclassifiedShippedCount: number;
  activeWindowSubjectCount: number;
  worstCellChannelCode: string;
  worstCellCountryCode: string;
  worstCellOverdueRate: number | null;
  note: string;
  upstreamNote: string;
  downstreamNote: string;
}

// ================================================================ 取数

const BASE = 'channel/logistics-timeliness';

export interface TimelinessQuery {
  from?: string;
  to?: string;
  channelId?: string;
  countryCode?: string;
  page?: number;
  size?: number;
}

export interface TimelinessMatrixQuery extends TimelinessQuery {
  sort?: string;
}

export function fetchCapabilities(): Promise<LogisticsTimelinessCapabilities> {
  return api<LogisticsTimelinessCapabilities>(`${BASE}/capabilities`);
}

export function fetchOverview(from?: string, to?: string): Promise<LogisticsTimelinessOverview> {
  const params = new URLSearchParams();
  if (from) params.set('from', from);
  if (to) params.set('to', to);
  return api<LogisticsTimelinessOverview>(`${BASE}/overview?${params.toString()}`);
}

export function fetchMatrix(query: TimelinessMatrixQuery = {}): Promise<TimelinessMatrixPage> {
  const params = new URLSearchParams();
  if (query.from) params.set('from', query.from);
  if (query.to) params.set('to', query.to);
  if (query.channelId) params.set('channelId', query.channelId);
  if (query.countryCode) params.set('countryCode', query.countryCode);
  if (query.sort) params.set('sort', query.sort);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<TimelinessMatrixPage>(`${BASE}/reports/matrix?${params.toString()}`);
}

export function fetchMonthly(query: TimelinessQuery = {}): Promise<TimelinessMonthlyReport> {
  const params = new URLSearchParams();
  if (query.from) params.set('from', query.from);
  if (query.to) params.set('to', query.to);
  if (query.channelId) params.set('channelId', query.channelId);
  if (query.countryCode) params.set('countryCode', query.countryCode);
  return api<TimelinessMonthlyReport>(`${BASE}/reports/monthly?${params.toString()}`);
}

export function fetchAnomalies(from?: string, to?: string): Promise<TimelinessAnomalyReport> {
  const params = new URLSearchParams();
  if (from) params.set('from', from);
  if (to) params.set('to', to);
  return api<TimelinessAnomalyReport>(`${BASE}/reports/anomalies?${params.toString()}`);
}

export function fetchScores(query: TimelinessQuery = {}): Promise<TimelinessScorePage> {
  const params = new URLSearchParams();
  if (query.from) params.set('from', query.from);
  if (query.to) params.set('to', query.to);
  if (query.channelId) params.set('channelId', query.channelId);
  if (query.countryCode) params.set('countryCode', query.countryCode);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<TimelinessScorePage>(`${BASE}/reports/scores?${params.toString()}`);
}

export function fetchScoreSnapshots(
  query: TimelinessQuery = {},
): Promise<TimelinessScoreSnapshotPage> {
  const params = new URLSearchParams();
  if (query.from) params.set('from', query.from);
  if (query.to) params.set('to', query.to);
  if (query.channelId) params.set('channelId', query.channelId);
  if (query.countryCode) params.set('countryCode', query.countryCode);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<TimelinessScoreSnapshotPage>(`${BASE}/reports/score-snapshots?${params.toString()}`);
}

export interface TimelinessSnapshotPayload {
  // ⚠️ `| undefined` 是必须的：仓库开了 `exactOptionalPropertyTypes`，
  // 不写它就没法显式传 `from: undefined`（「这一项不传」与「传一个空值」是两件事）。
  from?: string | undefined;
  to?: string | undefined;
  minSampleThreshold?: number | undefined;
}

export function snapshotScores(
  payload: TimelinessSnapshotPayload,
): Promise<TimelinessScoreSnapshotResult> {
  return api<TimelinessScoreSnapshotResult>(`${BASE}/reports/score-snapshots`, 'POST', payload);
}

export interface TimelinessSubjectQuery extends TimelinessQuery {
  status?: string;
}

export function fetchSubjects(query: TimelinessSubjectQuery = {}): Promise<TimelinessSubjectPage> {
  const params = new URLSearchParams();
  if (query.from) params.set('from', query.from);
  if (query.to) params.set('to', query.to);
  if (query.channelId) params.set('channelId', query.channelId);
  if (query.countryCode) params.set('countryCode', query.countryCode);
  if (query.status) params.set('status', query.status);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 50));
  return api<TimelinessSubjectPage>(`${BASE}/subjects?${params.toString()}`);
}

// ================================================================ 展示辅助

/**
 * 比率文案。
 *
 * <p>🔴 <b>null ⇒ 「不可比」，绝不是 0.00%</b>。
 */
export function rateText(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '不可比';
  return `${(rate * 100).toFixed(2)}%`;
}

/** 时长文案（小时）。null ⇒ 「—」（不是 0 小时）。 */
export function hoursText(hours: number | null | undefined): string {
  if (hours === null || hours === undefined) return '—';
  return `${hours.toFixed(2)} 小时`;
}

/** 带符号的偏差文案：正数 = 晚于承诺。 */
export function deviationText(hours: number | null | undefined): string {
  if (hours === null || hours === undefined) return '—';
  const sign = hours > 0 ? '+' : '';
  return `${sign}${hours.toFixed(2)} 小时`;
}

/** 评分文案：null ⇒ 「不可比（样本不足）」，不是 0 分。 */
export function scoreText(score: number | null | undefined): string {
  if (score === null || score === undefined) return '不可比';
  return score.toFixed(2);
}

export function timelinessStatusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'ON_TIME':
      return '准点送达';
    case 'OVERDUE':
      return '超时送达';
    case 'NO_PROMISE':
      return '无承诺可比';
    case 'IN_TRANSIT':
      return '在途未签收';
    default:
      return '未知状态';
  }
}

export function scoreGradeLabel(grade: string | null | undefined): string {
  switch (grade) {
    case 'EXCELLENT':
      return '优秀';
    case 'GOOD':
      return '良好';
    case 'FAIR':
      return '一般';
    case 'POOR':
      return '较差';
    case 'INSUFFICIENT':
      return '样本不足';
    default:
      return '未评级';
  }
}

/** 贡献率文案（0~1 的比例）。 */
export function shareText(share: number | null | undefined): string {
  if (share === null || share === undefined) return '不可比';
  return `${(share * 100).toFixed(2)}%`;
}

// ================================================================ 交叉矩阵

/** 一格 = 一个「渠道 × 国家」。 */
export interface TimelinessPivot {
  channels: string[];
  countries: string[];
  /** `${channel}|${country}` → 那一格。 */
  cells: Record<string, TimelinessMatrixRow | undefined>;
  /** 观察到的格子数（= 矩阵里非空的格子数）。 */
  observed: number;
}

/**
 * 把「渠道 × 国家」的平表摆成真正的交叉矩阵（渠道为行、国家为列）。
 *
 * <p>🔴 只摆<b>观察到的</b>格子：没有数据的「渠道 × 国家」组合**不补 0**，
 * 也不进坐标轴 —— 补 0 会让「这个组合没有货」看起来像「这个组合超时率 0%」。
 * 那一格在界面上就是**不存在**，不是 0。
 */
export function pivotMatrix(rows: TimelinessMatrixRow[]): TimelinessPivot {
  const channels = [...new Set(rows.map((r) => r.channelCode))].sort();
  const countries = [...new Set(rows.map((r) => r.countryCode))].sort();
  const cells: Record<string, TimelinessMatrixRow | undefined> = {};
  for (const row of rows) {
    cells[`${row.channelCode}|${row.countryCode}`] = row;
  }
  return { channels, countries, cells, observed: rows.length };
}

/** 取交叉矩阵里的一格（不存在 ⇒ undefined，不是「零值格」）。 */
export function pivotCell(
  pivot: TimelinessPivot,
  channel: string,
  country: string,
): TimelinessMatrixRow | undefined {
  return pivot.cells[`${channel}|${country}`];
}

// ================================================================ 错误文案

/** 错误文案：400 的理由原样透出（服务端写清了「凭什么不行」）。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 400) return error.message;
    if (error.status === 403) return '当前角色没有查看时效报表的权限';
    if (error.status === 503) return '轨迹数据暂时读不到，请稍后重试（不是「没有数据」）';
    if (error.status === 0) return '正在连接渠道服务，请稍后重试';
    return error.message;
  }
  return '加载失败，请稍后重试';
}

/** 最近 N 天的日期区间（UTC 自然日，yyyy-MM-dd）。 */
export function recentWindow(days: number, today = new Date()): { from: string; to: string } {
  const end = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate()));
  const start = new Date(end.getTime() - (days - 1) * 24 * 3600 * 1000);
  return { from: start.toISOString().slice(0, 10), to: end.toISOString().slice(0, 10) };
}

/** 日期区间是否合法（from 不晚于 to）。空串表示「用服务端默认窗口」。 */
export function windowValid(from: string, to: string): boolean {
  if (!from && !to) return true;
  if (!from || !to) return true;
  return from <= to;
}

/** 页码文案。 */
export function pageText(page: number, size: number, total: number): string {
  if (total <= 0) return '共 0 条';
  const first = page * size + 1;
  const last = Math.min((page + 1) * size, total);
  return `第 ${first}-${last} 条 / 共 ${total} 条`;
}
