/**
 * 11.5.1.1 退货分析的前端数据层。
 *
 * 蓝图 11-aftersales-and-returns.md：「统计退货情况：退货率（按店铺/SKU/类目/原因/批次）、
 * 退货金额趋势、拒退率、处理时效、平均退款金额与原因分布。」
 * 输出：「<b>退货分析报表 + 维度下钻 + 异常识别</b>。」
 * 加粗句：「<b>退货率必须与销售额同口径比较</b>，只看绝对数会误判（退货率从 3% 升到 5% 严重，
 * 但绝对数可能没变）。」
 *
 * <b>这一层对那条加粗句负三件事</b>：
 *
 * <ol>
 *   <li><b>退货率是「比值」不是「百分数」</b>：后端给的是标度 4 的比值（0.0500 = 5%），
 *       界面用 {@link formatRate} 渲染成百分比，但**换算只在这一处发生** ——
 *       在别处再乘一次 100 就会出现「5% 显示成 500%」这种只在某一张卡片上出现的错。</li>
 *   <li><b>空值不许渲染成 0</b>：{@link formatRate} / {@link formatMoney} / {@link formatHours}
 *       对 null 一律显示「—」（或「无口径」），因为后端的 null 语义是
 *       「<b>算不出来</b>」（没有销售额、没有样本），而 0 的语义是「算出来了，确实是 0」。
 *       把两者都渲染成 0，「这个维度退货率 0%」就会被读成「这个维度很健康」。</li>
 *   <li><b>跨币种不许合并</b>：{@link crossCurrencyTotal} 刻意**不提供** ——
 *       界面上每个币种一行，合计区只放与币种无关的计数。
 *       把 100 元和 80 欧加成一个「退货金额」在报表上看着顺，在对账时是错的。</li>
 * </ol>
 */
import type {
  ReturnAnalysisBasisCreate,
  ReturnAnalysisBasisPage,
  ReturnAnalysisBasisRow,
  ReturnAnalysisBasisSupersede,
  ReturnAnalysisCapabilities,
  ReturnAnalysisOverview,
  ReturnAnalysisSnapshotPage,
  ReturnAnalysisSnapshotRequest,
  ReturnAnomalyReport,
  ReturnBreakdownReport,
  ReturnTrendReport,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';

/** 与后端 {@code /trade/return-analysis} 同前缀。 */
const base = 'trade/return-analysis';

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  // ⚠️ exactOptionalPropertyTypes 开着（AGENTS.md）：不能把 `headers: undefined`
  //   直接塞进 RequestInit —— 那不是「没传」，而是「传了个 undefined」。
  const response = init
    ? await fetch(`/api/${path}`, {
        credentials: 'include',
        ...init,
        ...(init.body ? { headers: { 'Content-Type': 'application/json' } } : {}),
      })
    : await fetch(`/api/${path}`, { credentials: 'include' });
  if (!response.ok) {
    throw new ApiError(response.status, await response.text());
  }
  return (await response.json()) as T;
}

/**
 * 把筛选对象编成 query string。
 *
 * ⚠️ 空串与 undefined 都要跳过：传一个空 `dimension=` 会让后端收到
 * 「按空维度筛选」而不是「不筛维度」，两种读法在页面上表现完全不同。
 */
function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '' || value === null) continue;
    search.set(key, String(value));
  }
  const suffix = search.toString();
  return suffix ? `?${suffix}` : '';
}

// ================================================================ 枚举标签

/** 五个下钻维度（逐字对齐后端的 ReturnDimension）。 */
export const DIMENSION_LABELS: Record<string, string> = {
  SHOP: '店铺',
  SKU: 'SKU',
  CATEGORY: '类目',
  REASON: '原因',
  BATCH: '批次',
};

/**
 * 口径标记 —— 界面上<b>每一行</b>都要带。
 *
 * ⚠️ 三种口径的分子分母**不是同一个东西**：
 * 单头口径是「售后单申请的金额 ÷ 订单总额」，行级口径是「售后商品行申请的金额 ÷ 订单行金额」，
 * 件数口径是「实收件数 ÷ 关联 SKU 销量件数」。
 * 不标出来的话，读的人会把 3% 和 30% 直接比大小。
 */
export const CALIBER_LABELS: Record<string, string> = {
  CASE_LEVEL: '单头口径',
  LINE_LEVEL: '行级口径',
  QTY_LEVEL: '件数口径',
};

/** 趋势粒度。 */
export const GRANULARITY_LABELS: Record<string, string> = {
  DAY: '日',
  WEEK: '周',
  MONTH: '月',
};

/** 异常规则 —— 每条都要说清「按什么判出来的」。 */
export const ANOMALY_LABELS: Record<string, string> = {
  RETURN_RATE_SPIKE: '退货率倍数异常',
  REJECTION_RATE_SPIKE: '拒退率异常',
  BATCH_VOLUME_SPIKE: '批次件数异常',
  REASON_CONCENTRATION: '退货原因过度集中',
  TREND_DETERIORATION: '退货率环比恶化',
};

/**
 * 口径版本状态。
 *
 * ⚠️ 「已换版」不是「已删除」：口径换版后老版本仍在库里（留痕），
 * 报表里引用的仍是当时的阈值。把它渲染成「停用」会让人以为那份数据作废了。
 */
export const BASIS_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '生效中',
  SUPERSEDED: '已换版',
};

/**
 * 文档化的默认阈值 —— 租户没有登记口径时后端用的就是这一套。
 *
 * ⚠️ 与后端 {@code ReturnAnalyticsService} 的常量**逐个对齐**：
 * 口径登记表单用它们预填，前端展示时也能说出「未登记口径时用的是哪套阈值」。
 */
export const DEFAULT_THRESHOLDS = {
  rateAlertMultiplier: 2,
  rateAlertMinSalesShare: 0.01,
  rejectionAlertGapPp: 20,
  rejectionAlertMinCases: 5,
  batchAlertMultiplier: 3,
  batchAlertMinQty: 10,
  reasonAlertShare: 0.6,
  reasonAlertMinCases: 10,
  trendAlertGapPp: 5,
} as const;

/** 五个下钻维度的取值顺序（与 Segmented 控件的选项一致）。 */
export const DIMENSION_VALUES = ['SHOP', 'SKU', 'CATEGORY', 'REASON', 'BATCH'] as const;

/** 趋势粒度取值。 */
export const GRANULARITY_VALUES = ['DAY', 'WEEK', 'MONTH'] as const;

export type Tone = 'success' | 'warning' | 'danger' | 'neutral' | 'info';

// ================================================================ 纯展示派生

/**
 * 退货率（比值）→ 百分比字符串。
 *
 * ⚠️ **null 显示「—」而不是 0%**：null 的语义是「算不出来」（没有销售额），
 * 0% 的语义是「有销售额且一件没退」。两者渲染成同一个东西时，
 * 「这个维度很健康」会被读出来，而真相是「这个维度没有分母」。
 */
export function formatRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '—';
  return `${(Number(rate) * 100).toFixed(2)}%`;
}

/** 比率（拒退率等，同样是比值）→ 百分比字符串。 */
export function formatRatio(ratio: number | null | undefined): string {
  return formatRate(ratio);
}

/** 金额格式化：保留两位小数；null 显示「—」（算不出来）而不是 0。 */
export function formatMoney(amount: number | string | null | undefined): string {
  if (amount === null || amount === undefined || amount === '') return '—';
  return Number(amount).toFixed(2);
}

/** 数量格式化：去掉无意义的小数尾巴（0.000000 → 0）。 */
export function formatQty(qty: number | string | null | undefined): string {
  if (qty === null || qty === undefined || qty === '') return '—';
  const value = Number(qty);
  return Number.isInteger(value) ? String(value) : value.toFixed(2);
}

/** 时长格式化（小时）：null 显示「无样本」而不是 0 小时。 */
export function formatHours(hours: number | string | null | undefined): string {
  if (hours === null || hours === undefined || hours === '') return '无样本';
  return `${Number(hours).toFixed(2)} 小时`;
}

/**
 * 退货率的语气。
 *
 * ⚠️ 阈值与后端一致（≥ 10% 危险、≥ 5% 警告），null 一律 neutral ——
 * 「算不出来」不该被渲染成「很健康」。
 */
export function rateTone(rate: number | null | undefined): Tone {
  if (rate === null || rate === undefined) return 'neutral';
  const value = Number(rate);
  if (value >= 0.1) return 'danger';
  if (value >= 0.05) return 'warning';
  return 'success';
}

/** 拒退率的语气：≥ 20% 危险、≥ 10% 警告。 */
export function rejectionTone(rate: number | null | undefined): Tone {
  if (rate === null || rate === undefined) return 'neutral';
  const value = Number(rate);
  if (value >= 0.2) return 'danger';
  if (value >= 0.1) return 'warning';
  return 'success';
}

/** 异常等级 → 语气。 */
export function severityTone(severity: string | null | undefined): Tone {
  if (severity === 'HIGH') return 'danger';
  if (severity === 'MEDIUM') return 'warning';
  return 'neutral';
}

/** 口径来源 → 中文标签。 */
export function caliberSourceLabel(source: string | null | undefined): string {
  if (source === 'TENANT_BASIS') return '租户口径';
  if (source === 'DEFAULT') return '默认口径';
  return '未知口径';
}

/**
 * 退货率的读法 —— 一句话把「分子/分母/口径」说清。
 *
 * ⚠️ 只给一个百分比数字是不够的：读的人必须同时知道
 * 「这个率的分子是什么、分母是什么、是不是同一个币种」。
 */
export function returnRateReading(input: {
  rate: number | null | undefined;
  returnAmount: number | string | null | undefined;
  salesAmount: number | string | null | undefined;
  caliber?: string | null;
}): { text: string; tone: Tone } {
  const rate = input.rate;
  if (rate === null || rate === undefined) {
    return {
      text: '**算不出来**：同期没有销售额（分母为 0）—— 这不是「退货率 0%」。',
      tone: 'neutral',
    };
  }
  const caliber = input.caliber ? (CALIBER_LABELS[input.caliber] ?? input.caliber) : '';
  return {
    text: `退货率 ${formatRate(rate)}（${caliber}：退货金额 ${formatMoney(input.returnAmount)} ÷ 同期销售额 ${formatMoney(input.salesAmount)}）`,
    tone: rateTone(rate),
  };
}

/**
 * 跨币种合计 —— **刻意不存在**。
 *
 * ⚠️ 这个函数存在的唯一目的是让调用方**在编译期**发现「想跨币种相加」的意图：
 * 它抛错而不是返回一个数 —— 返回一个数会让「100 元 + 80 欧 = 180」这种加法
 * 在界面上悄无声息地出现。
 */
export function crossCurrencyTotal(): never {
  throw new Error(
    '跨币种不允许合计：金额与比率必须按币种分组 —— 请逐个币种渲染，不要合并。',
  );
}

/** 币种摘要：把一个币种的退货概况拼成一句话（给卡片与提示行用）。 */
export function currencySummaryText(input: {
  currency: string;
  caseCount: number;
  returnAmount: number | string | null | undefined;
  salesAmount: number | string | null | undefined;
  rate: number | null | undefined;
}): string {
  return (
    `${input.currency}：${input.caseCount} 张退货售后单，退货金额 ${formatMoney(input.returnAmount)}` +
    ` ÷ 同期销售额 ${formatMoney(input.salesAmount)} ⇒ 退货率 ${formatRate(input.rate)}`
  );
}

/** 异常行的一句话读数（把判据说清，不只给一个数）。 */
export function anomalyReading(row: {
  code: string;
  metricValue: number | null | undefined;
  baselineValue: number | null | undefined;
}): string {
  const label = ANOMALY_LABELS[row.code] ?? row.code;
  const metric = row.metricValue === null || row.metricValue === undefined ? '—' : row.metricValue;
  const baseline =
    row.baselineValue === null || row.baselineValue === undefined ? '—' : row.baselineValue;
  return `${label}：实测 ${metric} · 基准 ${baseline}`;
}

/** 快照行的维度标签（总体行与维度行分别叫法不同）。 */
export function snapshotDimensionLabel(dimension: string | null | undefined): string {
  if (dimension === 'OVERVIEW') return '总体';
  return DIMENSION_LABELS[dimension ?? ''] ?? dimension ?? '未知';
}

// ================================================================ 端点

export function fetchCapabilities(): Promise<ReturnAnalysisCapabilities> {
  return call(`${base}/capabilities`);
}

export function fetchOverview(params: {
  from?: string;
  to?: string;
  currency?: string;
}): Promise<ReturnAnalysisOverview> {
  return call(`${base}/overview${query(params)}`);
}

export function fetchBreakdown(params: {
  from?: string;
  to?: string;
  currency?: string;
  dimension: string;
  limit?: number;
}): Promise<ReturnBreakdownReport> {
  return call(`${base}/breakdown${query(params)}`);
}

export function fetchTrend(params: {
  from?: string;
  to?: string;
  currency?: string;
  granularity: string;
}): Promise<ReturnTrendReport> {
  return call(`${base}/trend${query(params)}`);
}

export function fetchAnomalies(params: {
  from?: string;
  to?: string;
  currency?: string;
}): Promise<ReturnAnomalyReport> {
  return call(`${base}/anomalies${query(params)}`);
}

export function fetchSnapshots(params: {
  dimension?: string;
  currency?: string;
  snapshotNo?: string;
  page?: number;
  size?: number;
}): Promise<ReturnAnalysisSnapshotPage> {
  return call(`${base}/snapshots${query(params)}`);
}

/** 保存一份报表快照（追加式：改错了重存一份，老的那份留着）。 */
export function createSnapshot(
  request: ReturnAnalysisSnapshotRequest,
): Promise<ReturnAnalysisSnapshotPage> {
  return call(`${base}/snapshots`, {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

export function fetchBasis(params: {
  currency?: string;
  status?: string;
  page?: number;
  size?: number;
}): Promise<ReturnAnalysisBasisPage> {
  return call(`${base}/basis${query(params)}`);
}

/** 登记一份口径。 */
export function createBasis(request: ReturnAnalysisBasisCreate): Promise<ReturnAnalysisBasisRow> {
  return call(`${base}/basis`, {
    method: 'POST',
    body: JSON.stringify(request),
  });
}

/** 口径换版（老版本保留为已换版，必须写理由）。 */
export function supersedeBasis(
  id: string,
  request: ReturnAnalysisBasisSupersede,
): Promise<ReturnAnalysisBasisRow> {
  return call(`${base}/basis/${id}/supersede`, {
    method: 'POST',
    body: JSON.stringify(request),
  });
}
