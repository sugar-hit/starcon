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
 *
 * <p>🔴 **展示层走十进制字面串，不走浮点运算**：金额是 numeric(19,4)、数量是 numeric(19,6)，
 * 而 JS 的 `number` 是二进制浮点 —— 拿它做乘法/除法会出 `0.1 + 0.2 === 0.30000000000000004`
 * 这种尾巴。所以本模块**不做任何算术**（数值、变化率、趋势箭头全部由后端算好），
 * 展示时把 number 还原成它的**十进制字面串**，再用 {@link BigInt} 在**缩放整数**上做
 * 四舍五入与千分位分组 —— 全程不碰浮点。
 *
 * <p>⚠️ 精度天花板在**契约**上（`value: number`）：JSON 把 `1234.5650` 解析成双精度浮点后，
 * 前端拿到的是 `1234.565` 这个 double。后端用 BigDecimal 算出的十进制语义由它自己的
 * `setScale` 保证；前端只保证**不把这个语义再弄坏**（不再乘除、不再二次舍入）。
 */
export function formatValue(value: number | null | undefined, unit: string): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  if (unit === 'CURRENCY') return formatDecimal(value, CURRENCY_SCALE, CURRENCY_SCALE, false);
  if (unit === 'QUANTITY') return formatDecimal(value, QUANTITY_SCALE, 0, true);
  return formatDecimal(value, 0, 0, false);
}

/** 金额展示精度（后端 numeric(19,4) ⇒ 展示 2 位小数）。 */
export const CURRENCY_SCALE = 2;

/** 数量展示精度（后端 numeric(19,6) ⇒ 展示最多 6 位小数）。 */
export const QUANTITY_SCALE = 6;

/**
 * 把一个 number 还原成**十进制字面串**（**不做任何算术**）。
 *
 * <p>{@code String(number)} 给的是该 double 的**最短往返表示** —— 也就是后端
 * {@code BigDecimal} 写进 JSON 的那串十进制（`1234.5650` ⇒ `"1234.565"`）。
 */
function decimalText(value: number): string | null {
  if (!Number.isFinite(value)) return null;
  const text = String(value);
  // 科学计数法（|value| >= 1e21）在这个模块的量级里不会出现；
  // 真出现了就老实返回 null —— 宁可显示「—」，也不给一个悄悄错的串。
  if (text.includes('e') || text.includes('E')) return null;
  return text;
}

/**
 * 十进制字面串 → 缩放整数（{@code scale} 位小数），**HALF_UP** 四舍五入。
 *
 * <p>全程 BigInt 整数运算，不碰浮点。
 */
function toScaled(text: string, scale: number): bigint | null {
  const match = /^([+-]?)(\d+)(?:\.(\d*))?$/.exec(text);
  if (!match) return null;
  const negative = match[1] === '-';
  const integer = match[2] ?? '0';
  const raw = match[3] ?? '';
  const kept = raw.padEnd(scale, '0').slice(0, scale);
  // 第 scale+1 位：≥5 就进位（HALF_UP，**远离零**）。
  const nextDigit = scale < raw.length ? raw.charCodeAt(scale) - 48 : 0;
  const base = BigInt(integer) * 10n ** BigInt(scale) + BigInt(kept || '0');
  const rounded = nextDigit >= 5 ? base + 1n : base;
  return negative ? -rounded : rounded;
}

/** 千分位分组（**字符串操作**，不经过浮点）。 */
function groupThousands(text: string): string {
  return text.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/**
 * 缩放整数 → 展示串。
 *
 * @param scale 隐含小数位数
 * @param minFractionDigits 至少保留的小数位数（金额固定 2 位）
 * @param trimFraction 是否去掉多余的尾随零（数量用）
 */
function formatScaled(scaled: bigint, scale: number, minFractionDigits: number, trimFraction: boolean): string {
  const negative = scaled < 0n;
  const abs = negative ? -scaled : scaled;
  const divisor = 10n ** BigInt(scale);
  const integerText = groupThousands((abs / divisor).toString());
  // scale 为 0（计数）时**没有**小数部分 —— 不能让 `abs % 1n` 的 0 变成 "0.0"。
  let fractionText = scale === 0 ? '' : (abs % divisor).toString().padStart(scale, '0');
  if (trimFraction) fractionText = fractionText.replace(/0+$/, '');
  while (fractionText.length < minFractionDigits) fractionText += '0';
  const body = fractionText ? `${integerText}.${fractionText}` : integerText;
  return negative ? `-${body}` : body;
}

/** 十进制精确的展示格式化（见 {@link formatValue} 的说明）。 */
export function formatDecimal(
  value: number | null | undefined,
  scale: number,
  minFractionDigits: number,
  trimFraction: boolean,
): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  const text = decimalText(value);
  if (text === null) return '—';
  const scaled = toScaled(text, scale);
  if (scaled === null) return '—';
  return formatScaled(scaled, scale, minFractionDigits, trimFraction);
}

/**
 * 主值展示串（带单位与币种）。
 *
 * <p>🔴 **多币种没有主值时显示「按币种分列」，不是「—」也不是随便挑一个币种充数**：
 * 本位币合计要么由后端按汇率快照折算出来（{@code headline}），要么没有。
 */
export function headlineText(card: MetricCardEntry): string {
  if (card.status === 'NOT_CONNECTED') return '未接通';
  const headline = card.headline;
  if (!headline || headline.value === null) {
    // 多币种且折不出本位币合计 ⇒ 按币种分列（界面上永远没有跨币种的「总计」）。
    if (currencyRows(card).length > 1) return '按币种分列';
    return '—';
  }
  const currency = headline.currency ? ` ${headline.currency}` : '';
  return `${formatValue(headline.value, card.unit)}${currency}`;
}

/** 变化率展示串（`null` ⇒ 「—」，**不是 0%**）。 */
export function changeRateText(comparison: MetricCardComparison | null | undefined): string {
  if (!comparison || comparison.changeRate === null) return '—';
  const sign = comparison.changeRate > 0 ? '+' : '';
  return `${sign}${formatDecimal(comparison.changeRate, 2, 2, false)}%`;
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

/**
 * 环比 / 同比那一行的**短标签**。
 *
 * <p>🔴 **「基期没数据」显示「无同期基期」，不是 0%、不是「持平」、也不是「-100%」**。
 * 后端把不可判定的原因写在 {@code reason} 里，这里按原因分类给出人能读懂的短标签 ——
 * 原因原文仍然完整出现在 tooltip 里。
 */
export function comparisonLabel(comparison: MetricCardComparison | null | undefined): string {
  if (!comparison) return '—';
  if (comparison.comparable && comparison.changeRate !== null) {
    const sign = comparison.changeRate > 0 ? '+' : '';
    return `${sign}${formatDecimal(comparison.changeRate, 2, 2, false)}%`;
  }
  const reason = comparison.reason ?? '';
  if (reason.includes('对比期没有事实数据')) return '无同期基期';
  if (reason.includes('缺少这个币种')) return '基期缺该币种';
  if (reason.includes('为 0')) return '基期为 0';
  if (reason.includes('缺少汇率快照')) return '缺汇率快照';
  if (reason.includes('多币种')) return '多币种不可比';
  return '不可判定';
}

/** 卡片上那一行的对比文案（环比 / 同比）。 */
export function comparisonLine(comparison: MetricCardComparison | null | undefined): string {
  if (!comparison) return '';
  if (!comparison.comparable) return comparisonLabel(comparison);
  return `${comparisonLabel(comparison)}（${comparison.from} → ${comparison.to}）`;
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
