import type {
  InventoryHealthGate,
  InventoryHealthIssue,
  InventoryHealthIssuePage,
  InventoryHealthMetric,
  InventoryHealthScorecard,
  InventoryHealthScorecardPage,
  InventoryHealthTrend,
} from '../../../../../packages/api-contracts/src/index';
import { api } from './api';

/**
 * 7.6.2.1 库存健康前端模型。
 *
 * <p>界面上最容易误读的一处，这个模块显式处理了：**不可用的指标不许显示数字**。
 *
 * <p>蓝图点名六个指标，其中四个（周转率 / 缺货率 / 超储率 / 呆滞占比）依赖尚未交付的
 * 13 销量与 7.6.1。界面上如果把这四行藏掉，剩下的两行看起来就像"全部指标都没问题"；
 * 如果给它们填一个数，那就是在拿假指标去调补货。所以这里三件事都摆到台面上：
 *
 * <ul>
 *   <li>六行恒定渲染，不可用的那几行显示**原因**与**阻塞模块**，不显示数值。
 *   <li>得分旁边永远跟着覆盖度 `2/6`，让人一眼看出这是"已覆盖维度上的分数"。
 *   <li>准确率没证据时评级是 UNVERIFIABLE 而不是"健康"——负库存恒为 0 会白送满分，
 *       照常评级就会出现"从没核过账但显示健康"这种最危险的结果。
 * </ul>
 */

/** 列表查询条件。与后端 `InventoryHealthQuery` 一一对应。 */
export interface HealthParams {
  // exactOptionalPropertyTypes：显式写 `| undefined`，否则调用方传 undefined 会被拒。
  warehouseId?: string | undefined;
  accuracyThreshold?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

function healthQuery(params: HealthParams): string {
  const parts: string[] = [];
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.accuracyThreshold)
    parts.push(`accuracyThreshold=${encodeURIComponent(params.accuracyThreshold)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchHealth(params: HealthParams) {
  return api<InventoryHealthScorecardPage>(`warehouse/inventory/health${healthQuery(params)}`);
}

export function fetchHealthTrend(warehouseId?: string) {
  const qs = warehouseId ? `?warehouseId=${encodeURIComponent(warehouseId)}` : '';
  return api<InventoryHealthTrend>(`warehouse/inventory/health/trend${qs}`);
}

export function fetchHealthGate(warehouseId?: string, accuracyThreshold?: string) {
  const params: string[] = [];
  if (warehouseId) params.push(`warehouseId=${encodeURIComponent(warehouseId)}`);
  if (accuracyThreshold) params.push(`accuracyThreshold=${encodeURIComponent(accuracyThreshold)}`);
  return api<InventoryHealthGate>(
    `warehouse/inventory/health/gate${params.length ? `?${params.join('&')}` : ''}`,
  );
}

export function fetchHealthIssues(page = 0, size = 50) {
  return api<InventoryHealthIssuePage>(
    `warehouse/inventory/health/issues?page=${page}&size=${size}`,
  );
}

/** 触发一次评估并落库。后端按仓库逐个打分，全租户范围是 TENANT 行。 */
export function assessHealth(warehouseId?: string, accuracyThreshold?: string) {
  return api<Record<string, unknown>>('warehouse/inventory/health/assess', 'POST', {
    ...(warehouseId ? { warehouseId } : {}),
    ...(accuracyThreshold ? { accuracyThreshold: Number(accuracyThreshold) } : {}),
  });
}

// ------------------------------------------------------------ 展示口径

export const healthGradeLabels: Record<string, string> = {
  HEALTHY: '健康',
  WATCH: '关注',
  RISK: '风险',
  UNVERIFIABLE: '无法判定',
};

export const availabilityLabels: Record<string, string> = {
  AVAILABLE: '可算',
  UNAVAILABLE: '上游未交付',
  NO_DATA: '窗口内无样本',
};

export const issueSeverityLabels: Record<string, string> = {
  INFO: '提示',
  WARN: '警告',
  CRITICAL: '严重',
};

export const metricSourceLabels: Record<string, string> = {
  RECONCILIATION: '账实核对',
  STOCKTAKE: '盘点',
  INVENTORY_BALANCE: '库存余额',
};

/**
 * 比率显示成百分比；null（不可算 / 无样本）必须显示「—」而不是 0%。
 *
 * <p>契约里的数值字段是 `number`（codegen 拍平后 BigDecimal 变 number），
 * 但保留 string 分支以免上游改成字符串时静默算出 NaN。
 */
export function percent(value?: number | string | null): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  if (Number.isNaN(n)) return '—';
  return `${(n * 100).toFixed(2)}%`;
}

/** 次数类指标（负库存发生次数）用整数显示。 */
export function count(value?: number | string | null): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  return Number.isNaN(n) ? '—' : String(n);
}

/**
 * 一行指标在界面上显示什么。
 *
 * <p>三种状态给出三种**结构上不同**的结果，不是同一段文案的三个变体：
 * 可算的给值 + 口径来源；不可用的给阻塞模块；无样本的给「上游在但这个窗口没数据」。
 * 界面据此决定渲染数值还是渲染灰字说明 —— 见 {@link InventoryHealthMetric} 的 `availability`。
 */
export interface MetricDisplay {
  /** 数值文本；不可算 / 无样本时为空串，界面不应渲染数值位。 */
  value: string;
  /** 该不该渲染数值位。false 时必须显示 {@link hint} 而不是 0。 */
  hasValue: boolean;
  /** 口径来源中文名；可算时给出。 */
  source: string;
  /** 不可算 / 无样本时的说明，含阻塞模块。 */
  hint: string;
  /** 分子的可读形式，如「95 / 100」；没有分母时为空串。 */
  fraction: string;
}

export function metricDisplay(metric: InventoryHealthMetric): MetricDisplay {
  const source = metric.source ? (metricSourceLabels[metric.source] ?? metric.source) : '';
  if (metric.availability === 'AVAILABLE') {
    const isRate = metric.metricType !== 'NEGATIVE_STOCK';
    const hasNumerator = metric.numerator !== null && metric.numerator !== undefined;
    return {
      value: isRate ? percent(metric.value) : count(metric.value),
      hasValue: true,
      source,
      hint: metric.basis,
      fraction: hasNumerator ? `${metric.numerator} / ${metric.denominator ?? '—'}` : '',
    };
  }
  const blocked = metric.blockedByModule ? `（待 ${metric.blockedByModule}）` : '';
  return {
    value: '',
    hasValue: false,
    source: '',
    hint: `${availabilityLabels[metric.availability] ?? metric.availability}${blocked}`,
    fraction: '',
  };
}

/** 覆盖度文案：`2/6`。这是防止"部分维度的分数被当成整体健康度"的那句话。 */
export function coverageText(card: InventoryHealthScorecard): string {
  return `${card.availableMetricCount}/${card.totalMetricCount}`;
}

/** 得分是不是"只覆盖了一部分指标"。是的话界面上必须加提示，不能只给数字。 */
export function isPartialScore(card: InventoryHealthScorecard): boolean {
  return card.availableMetricCount < card.totalMetricCount;
}

/**
 * 红线状态文案。
 *
 * <p>三种情况分开说，因为该做的事不同：低于阈值要修差异；无证据要先去核账；
 * 达标才是什么都不做。把后两者合并成"通过"会让"从没核过账"看起来像好消息。
 */
export function gateTone(gate: InventoryHealthGate): 'blocked' | 'unverified' | 'passed' {
  if (!gate.blocked) return 'passed';
  return gate.accuracyState === 'AVAILABLE' ? 'blocked' : 'unverified';
}

export function gateHeadline(gate: InventoryHealthGate): string {
  switch (gateTone(gate)) {
    case 'blocked':
      return `准确率 ${percent(gate.accuracyRate)} 低于阈值 ${percent(gate.accuracyThreshold)}，已阻断结算与对账`;
    case 'unverified':
      return '账目准确率无证据，已按最保守口径阻断结算与对账';
    default:
      return `准确率 ${percent(gate.accuracyRate)} 达标，未触发阻断`;
  }
}

/** 问题按严重度排：严重的在前，同级按范围再按指标，与后端排序一致。 */
export function sortIssues(issues: InventoryHealthIssue[]): InventoryHealthIssue[] {
  const rank: Record<string, number> = { CRITICAL: 0, WARN: 1, INFO: 2 };
  return [...issues].sort(
    (a, b) =>
      (rank[a.severity] ?? 3) - (rank[b.severity] ?? 3) ||
      a.scopeLabel.localeCompare(b.scopeLabel) ||
      a.metricType.localeCompare(b.metricType),
  );
}

/** 趋势点的准确率文本；样本为 0 时没有值可显示。 */
export function trendAccuracyText(point: InventoryHealthTrend['points'][number]): string {
  return percent(point.accuracy);
}

/** 趋势标签：`账实核对 · 全部余额行` / `盘点 · PD-1 上海仓`。 */
export function trendPointLabel(point: InventoryHealthTrend['points'][number]): string {
  return `${metricSourceLabels[point.source] ?? point.source} · ${point.sourceLabel}`;
}
