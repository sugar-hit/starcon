import { api } from '../inventory/api';
import type {
  MetricCardAmount,
  MetricCardCapability,
  MetricCardComparison,
  MetricCardDrilldown,
  MetricCardEntry,
  MetricCardsCapabilities,
  MetricCardsResponse,
} from '../../../../../packages/api-contracts/src/index';

export type {
  MetricCardAmount,
  MetricCardCapability,
  MetricCardComparison,
  MetricCardDrilldown,
  MetricCardEntry,
  MetricCardsCapabilities,
  MetricCardsResponse,
};

/**
 * 1.1.2.1 核心指标卡 前端模型（卷 01 工作台与门户 L3 1.1.2 关键指标下的第一格）。
 *
 * <p>蓝图原文：「一屏展示今日/昨日/本月的销售额、订单量、销量、库存总额、毛利、回款、
 * 在途金额，每张卡带环比与同比，点击下钻到对应报表。」
 *
 * <p>🔴 **这个页面上最容易做丢的五件事，这里都挡住了**：
 *
 * <ol>
 *   <li><b>前端不重算任何比率</b>。环比 / 同比的变化率与趋势箭头**全部由后端算好**
 *       （口径在 13 的 `reporting_metric_definition`，本模块只引用不重算）——
 *       这里一个减法都不做。</li>
 *   <li><b>「算不出来」与「等于 0」分开</b>。`changeRate === null` 渲染成「—」并带原因，
 *       **不是 0%、不是「持平」、也不是「-100%」**；未接通的指标渲染成灰格「未接通」，
 *       **不是 0**。</li>
 *   <li><b>多币种不相加</b>。主值只在后端给出本位币合计时才有；没有主值就显示
 *       「按币种分列」并把每个币种列出来 —— 界面上**永远没有「总计」那一列**。</li>
 *   <li><b>下钻只指向已存在的页面</b>。`drilldown.detailPath`（构成明细）与
 *       `drilldown.reportPath`（已交付的报表页）都是后端给的，指向未交付的页面是假承诺。</li>
 *   <li><b>数据范围只用于展示措辞</b>。范围由服务端判定，前端只是把「实际生效的数据范围」
 *       写出来给人看（取最宽的一条授权 ALL &gt; DEPARTMENT &gt; SELF）。</li>
 * </ol>
 */

/** 本模块在 api-proxy 白名单里的路径前缀（reporting 服务，**独立一条 services[] 元素**）。 */
export const BASE = 'reporting/metric-cards';

/**
 * 13.1.1.1 经营总览的下钻端点（**复用既有端点，不新造**）。
 *
 * <p>构成明细在 13.1.1.1 里已经交付（`GET /reporting/overview/drilldown`），
 * 本模块只按 metric + 期间定位调用它。
 */
export const DRILLDOWN_BASE = 'reporting/overview/drilldown';

/** 时间口径选择（后端按这四种解析窗口）。 */
export const WINDOW_OPTIONS = [
  { value: 'TODAY', label: '今日' },
  { value: 'YESTERDAY', label: '昨日' },
  { value: 'THIS_MONTH', label: '本月' },
  { value: 'CUSTOM', label: '自定义区间' },
] as const;

export type WindowOption = (typeof WINDOW_OPTIONS)[number]['value'];

/** 时间口径的中文标签。 */
export function windowLabel(preset: string): string {
  switch (preset) {
    case 'TODAY':
      return '今日';
    case 'YESTERDAY':
      return '昨日';
    case 'THIS_MONTH':
      return '本月';
    case 'CUSTOM':
      return '自定义区间';
    default:
      return preset;
  }
}

// ================================================================ 数据范围

/** 会话里的一条角色授权（形状来自 {@code auth/session} 的响应）。 */
export type SessionGrant = {
  roleId: string;
  roleCode: string;
  scopeMode: string;
  permissions: string[];
};

/**
 * 数据范围模式。
 *
 * <p>与后端 {@code GrantPermissions} 的三个 scopeMode 逐字同式：
 * `ALL`（租户内全部）/ `DEPARTMENT`（本部门）/ `SELF`（仅本人）。
 */
export type ScopeMode = 'ALL' | 'DEPARTMENT' | 'SELF';

export const SCOPE_MODE_LABELS: Record<ScopeMode, string> = {
  ALL: '租户内全部',
  DEPARTMENT: '本部门',
  SELF: '仅本人',
};

/**
 * 会话在某权限码上**实际生效**的数据范围。
 *
 * <p>🔴 **判定规则与后端逐字同式**（{@code GrantPermissions.scopeMode}）：
 * 取**最宽**的一条授权（ALL &gt; DEPARTMENT &gt; SELF）。
 * DEPARTMENT 需要会话确实带部门，否则退化为 SELF —— 往更严的一侧退化。
 *
 * <p>⚠️ 这个值**只用于展示措辞**，不是授权：服务端会独立地再判一次。
 */
export function scopeModeOf(
  grants: readonly SessionGrant[] | null | undefined,
  permission: string,
  hasDepartment: boolean,
): ScopeMode {
  if (!grants || grants.length === 0) return 'SELF';
  const dot = permission.lastIndexOf('.');
  const siblingRead = (dot < 0 ? permission : permission.substring(0, dot)) + '.read';
  let all = false;
  let department = false;
  for (const grant of grants) {
    if (!grant) continue;
    const permissions = grant.permissions ?? [];
    if (!permissions.includes(permission) || !permissions.includes(siblingRead)) continue;
    if (grant.scopeMode === 'ALL') all = true;
    else if (grant.scopeMode === 'DEPARTMENT') department = true;
  }
  if (all) return 'ALL';
  if (department && hasDepartment) return 'DEPARTMENT';
  return 'SELF';
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<MetricCardsCapabilities> {
  return api<MetricCardsCapabilities>(`${BASE}/capabilities`);
}

/**
 * 取一次卡片数据。
 *
 * <p>⚠️ **不下发租户号、不下发范围参数**：范围由服务端判定，前端只读服务端已经过滤好的投影。
 */
export async function fetchCards(
  window: WindowOption,
  from?: string,
  to?: string,
  baseCurrency?: string,
): Promise<MetricCardsResponse> {
  const query = new URLSearchParams({ window });
  if (window === 'CUSTOM') {
    if (from) query.set('from', from);
    if (to) query.set('to', to);
  }
  if (baseCurrency) query.set('baseCurrency', baseCurrency);
  return api<MetricCardsResponse>(`${BASE}?${query.toString()}`);
}

/** 取构成明细（复用 13.1.1.1 的下钻端点）。 */
export async function fetchDrilldown(
  metricCode: string,
  from: string,
  to: string,
): Promise<DrilldownPage> {
  const query = new URLSearchParams({ metricCode, from, to, scopeType: 'COMPANY' });
  return api<DrilldownPage>(`${DRILLDOWN_BASE}?${query.toString()}`);
}

/** 下钻构成明细的一页（形状来自 13.1.1.1 的契约）。 */
export interface DrilldownPage {
  items: {
    sourceKind: string;
    referenceId: string;
    referenceLabel: string;
    currency: string;
    quantity: number | null;
    value: number | null;
    bucketDate: string;
  }[];
  page: number;
  size: number;
  total: number;
  metricCode: string;
  metricName: string;
}

// ================================================================ 格式化

/** 单位 → 中文标签。 */
export function unitLabel(unit: string): string {
  switch (unit) {
    case 'CURRENCY':
      return '';
    case 'COUNT':
      return '笔';
    case 'QUANTITY':
      return '件';
    case 'PERCENT':
      return '%';
    default:
      return '';
  }
}

/**
 * 金额 / 数量的展示串。
 *
 * <p>⚠️ `null` ⇒ 「—」（**不是 0**）。后端把「算不出来」给成 null，
 * 这里原样显示「—」，并在 tooltip 里给出原因。
 */
export function formatValue(value: number | null | undefined, unit: string): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  if (unit === 'CURRENCY') {
    return value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
  if (unit === 'QUANTITY') {
    return value.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
  }
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

/** 主值展示串（带单位与币种）。 */
export function headlineText(card: MetricCardEntry): string {
  if (card.status === 'NOT_CONNECTED') return '未接通';
  const headline = card.headline;
  if (!headline || headline.value === null) return '—';
  const currency = headline.currency ? ` ${headline.currency}` : '';
  return `${formatValue(headline.value, card.unit)}${currency}`;
}

/** 变化率展示串（`null` ⇒ 「—」，**不是 0%**）。 */
export function changeRateText(comparison: MetricCardComparison | null | undefined): string {
  if (!comparison || comparison.changeRate === null) return '—';
  const sign = comparison.changeRate > 0 ? '+' : '';
  return `${sign}${comparison.changeRate.toFixed(2)}%`;
}

/** 趋势箭头的语义标签。 */
export function trendLabel(trend: string): string {
  switch (trend) {
    case 'UP':
      return '上升';
    case 'DOWN':
      return '下降';
    case 'FLAT':
      return '持平';
    default:
      return '无';
  }
}

/** 趋势箭头的色调（**涨跌是中性语义** —— 销售额涨不一定是好事）。 */
export function trendTone(trend: string): 'success' | 'destructive' | 'neutral' {
  switch (trend) {
    case 'UP':
      return 'success';
    case 'DOWN':
      return 'destructive';
    default:
      return 'neutral';
  }
}

/** 卡片状态的展示标签。 */
export function statusLabel(status: string): string {
  switch (status) {
    case 'AVAILABLE':
      return '有数据';
    case 'NO_DATA':
      return '该窗口没有投影数据';
    case 'NOT_CONNECTED':
      return '未接通';
    case 'UPSTREAM_UNAVAILABLE':
      return '上游读不到';
    default:
      return status;
  }
}

/** 卡片状态的色调。 */
export function statusTone(status: string): 'success' | 'warning' | 'neutral' {
  switch (status) {
    case 'AVAILABLE':
      return 'success';
    case 'NO_DATA':
    case 'UPSTREAM_UNAVAILABLE':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 这张卡是不是「没接通」（前端要换一套独立样式）。 */
export function isUnavailable(card: MetricCardEntry): boolean {
  return card.status === 'NOT_CONNECTED' || card.status === 'UPSTREAM_UNAVAILABLE';
}

/** 未接通的指标（能力声明里逐条列出来，而不是悄悄消失）。 */
export function unavailableCapabilities(
  caps: MetricCardsCapabilities | null,
): MetricCardCapability[] {
  if (!caps) return [];
  return caps.metrics.filter((metric) => !metric.available);
}

/** 能力声明里**已接通**的指标。 */
export function availableCapabilities(
  caps: MetricCardsCapabilities | null,
): MetricCardCapability[] {
  if (!caps) return [];
  return caps.metrics.filter((metric) => metric.available);
}

/**
 * 一张卡的 tooltip 文案（口径说明 + 归属 + 「为什么没有数」）。
 *
 * <p>⚠️ 口径说明**原样取后端的**：口径由 13 单点定义，前端自己编一句就是第二个真相。
 */
export function caliberTooltip(card: MetricCardEntry): string {
  const lines: string[] = [];
  if (card.caliber) lines.push(card.caliber);
  if (card.ownerModule) lines.push(`口径归属：${card.ownerModule}`);
  if (card.note) lines.push(card.note);
  if (card.previousPeriod && !card.previousPeriod.comparable && card.previousPeriod.reason) {
    lines.push(`环比不可判定：${card.previousPeriod.reason}`);
  }
  if (card.yearOverYear && !card.yearOverYear.comparable && card.yearOverYear.reason) {
    lines.push(`同比不可判定：${card.yearOverYear.reason}`);
  }
  return lines.join('\n');
}

/** 卡片上那一行的对比文案（环比 / 同比）。 */
export function comparisonLine(comparison: MetricCardComparison | null | undefined): string {
  if (!comparison) return '';
  if (!comparison.comparable) return '不可判定';
  return `${changeRateText(comparison)}（${comparison.from} → ${comparison.to}）`;
}

/** 每个币种一行（多币种时主值给不出时的兜底展示）。 */
export function currencyRows(card: MetricCardEntry): NonNullable<MetricCardAmount>[] {
  return (card.values ?? []).filter((value): value is NonNullable<MetricCardAmount> => value !== null);
}

/**
 * 空态文案。
 *
 * <p>「没有投影数据」与「这个租户还没有数据」是**两件事**，文案必须不同。
 */
export function emptyState(
  loaded: boolean,
  cards: MetricCardEntry[],
  hasFacts: boolean,
): { title: string; description: string } {
  if (!loaded) {
    return {
      title: '指标卡加载失败',
      description: '后端暂时不可达或身份校验未通过，请刷新重试。页面上已有的卡片不会因此被清空。',
    };
  }
  if (!cards.length) {
    return {
      title: '这个租户还没有核心指标卡',
      description: '指标卡清单由系统登记（蓝图 1.1.2.1 点名的七张），没有卡片说明本模块未交付。',
    };
  }
  if (!hasFacts) {
    return {
      title: '这个租户在这个时间窗还没有投影数据',
      description:
        '指标卡是**投影**：reporting 服务还没有把这个租户在这个窗口的事实投影进来。' +
        '这不是「业务上没有销售额」—— 是「还没有数据可读」。',
    };
  }
  return {
    title: '这个时间窗没有可展示的指标',
    description: '换一个时间口径试试（今日 / 昨日 / 本月 / 自定义区间）。',
  };
}
