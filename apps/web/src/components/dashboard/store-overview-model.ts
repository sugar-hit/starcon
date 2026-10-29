import { api } from '../inventory/api';
import type {
  StoreOverviewCaliber,
  StoreOverviewCapabilities,
  StoreOverviewDimension,
  StoreOverviewDimensionCapability,
  StoreOverviewLaggard,
  StoreOverviewMetricValue,
  StoreOverviewResponse,
  StoreOverviewRow,
  StoreOverviewRule,
  StoreOverviewShop,
} from '../../../../../packages/api-contracts/src/index';

export type {
  StoreOverviewCaliber,
  StoreOverviewCapabilities,
  StoreOverviewDimension,
  StoreOverviewDimensionCapability,
  StoreOverviewLaggard,
  StoreOverviewMetricValue,
  StoreOverviewResponse,
  StoreOverviewRow,
  StoreOverviewRule,
  StoreOverviewShop,
};

/**
 * 1.1.2.2 店铺概览 前端模型（卷 01「工作台与门户」L3 1.1.2 关键指标下的第二格）。
 *
 * <p>蓝图原文：「按店铺横向对比当期表现（**销售额、订单量、利润率、库存天数、广告花费**），
 * 识别掉队店铺。……输出：店铺对比表 / 卡片；点击进入该店铺在 13 的分析页。」
 *
 * <p>🔴 **这个页面上最容易做丢的五件事，这里都挡住了**：
 *
 * <ol>
 *   <li><b>「销售额」不是一个数，是三个口径的并列</b>。店铺级口径表里登记的是
 *       **平台侧成交金额** 与 **还原口径成交金额** 两份，外加两者的**口径差异额**
 *       ⇒ 本页<b>三者并列呈现</b>。只取其一当「销售额」= 抹掉口径差异 = 破坏口径唯一。
 *       {@link salesLines} 在结构上保证这一点：它对每个币种**恒定返回三条**，
 *       少一条就是它自己坏了。</li>
 *   <li><b>「算不出来」与「等于 0」分开</b>。没有事实 ⇒ 显示「—」并写明原因，
 *       <b>不是 0</b>；口径未在本租户口径表登记 ⇒ 显示「未接通」并写明<b>归谁</b>，
 *       <b>不是 0、也不是 0%</b>。</li>
 *   <li><b>接不通的维度不给公式也不给分母</b>。利润率与库存天数在店铺口径表里没有指标码 ⇒
 *       页面显示「未接通 + 归属模块号」，让人知道<b>去哪维护</b>，
 *       而不是给一个 0 或一个自己编的假数。</li>
 *   <li><b>多币种绝不相加</b>。每个币种各占一行，界面上<b>永远没有跨币种的「总计」</b>。</li>
 *   <li><b>前端不重算任何数字</b>。口径差异额是后端按 DERIVED_DIFF 算好的
 *       （分子分母登记在口径表里），本页只显示，<b>一个减法都不做</b>。</li>
 * </ol>
 */

/** 本模块在 api-proxy 白名单里的路径前缀（reporting 服务，**独立一条 services[] 元素**）。 */
export const BASE = 'reporting/store-overview';

// ================================================================ 维度与口径码

/** 销售额（店铺级：两份成交金额口径 + 口径差异额）。 */
export const DIMENSION_SALES = 'store.sales-amount';

/** 订单量。 */
export const DIMENSION_ORDER_COUNT = 'store.order-count';

/** 利润率（🔴 尚未接通）。 */
export const DIMENSION_PROFIT_MARGIN = 'store.profit-margin';

/** 库存天数（🔴 尚未接通）。 */
export const DIMENSION_INVENTORY_DAYS = 'store.inventory-days';

/** 广告花费。 */
export const DIMENSION_AD_SPEND = 'store.ad-spend';

/**
 * 五个维度（顺序 = 页面上的列顺序，与蓝图原文同序）。
 *
 * <p>⚠️ 这个列表是**展示顺序**，不是口径真相 —— 口径真相在后端的
 * {@code reporting_store_metric_definition} 里。
 */
export const DIMENSION_KEYS = [
  DIMENSION_SALES,
  DIMENSION_ORDER_COUNT,
  DIMENSION_PROFIT_MARGIN,
  DIMENSION_INVENTORY_DAYS,
  DIMENSION_AD_SPEND,
] as const;

/** 店铺级口径码（13.1.1.2 多店对比登记的那一套，本页只引用）。 */
export const STORE_GMV_PLATFORM = 'STORE_GMV_PLATFORM';
export const STORE_GMV_RESTORED = 'STORE_GMV_RESTORED';
export const STORE_CALIBER_GAP = 'STORE_CALIBER_GAP';
export const STORE_ORDER_COUNT = 'STORE_ORDER_COUNT';
export const STORE_AD_SPEND = 'STORE_AD_SPEND';

/**
 * 销售额那一格的**三个口径**（固定顺序 + 固定短标签）。
 *
 * <p>🔴 **这三个必须同时出现**：平台侧 / 我方还原 / 口径差。
 * 少了任何一条，这一格就在说谎（把口径差异读成了经营差异）。
 */
export const SALES_CALIBERS = [
  { metricCode: STORE_GMV_PLATFORM, label: '平台侧' },
  { metricCode: STORE_GMV_RESTORED, label: '我方还原' },
  { metricCode: STORE_CALIBER_GAP, label: '口径差' },
] as const;

/** 口径码 → 页面上的短标签。 */
export const CALIBER_LABELS: Record<string, string> = {
  [STORE_GMV_PLATFORM]: '平台侧',
  [STORE_GMV_RESTORED]: '我方还原',
  [STORE_CALIBER_GAP]: '口径差',
  [STORE_ORDER_COUNT]: '订单量',
  [STORE_AD_SPEND]: '广告花费',
};

/** 口径码的短标签（查不到就退回口径码本身，不编一个名字）。 */
export function caliberLabel(metricCode: string): string {
  return CALIBER_LABELS[metricCode] ?? metricCode;
}

// ================================================================ 时间口径

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

// ================================================================ 取数

export async function fetchCapabilities(): Promise<StoreOverviewCapabilities> {
  return api<StoreOverviewCapabilities>(`${BASE}/capabilities`);
}

/**
 * 取一次店铺概览。
 *
 * <p>⚠️ **不下发租户号、不下发范围参数**：租户来自已认证的成员关系，
 * 可见店铺集合由服务端按 RLS 过滤，前端只读服务端已经过滤好的结果。
 */
export async function fetchOverview(
  window: WindowOption,
  from?: string,
  to?: string,
  platformCode?: string,
): Promise<StoreOverviewResponse> {
  const query = new URLSearchParams({ window });
  if (window === 'CUSTOM') {
    if (from) query.set('from', from);
    if (to) query.set('to', to);
  }
  if (platformCode) query.set('platformCode', platformCode);
  return api<StoreOverviewResponse>(`${BASE}?${query.toString()}`);
}

/**
 * 取可见店铺集合（筛选器用）。
 *
 * <p>⚠️ **不带平台过滤**：这一份是「本租户**全部**可见店铺」，
 * 平台筛选项要从它推出来 —— 否则一旦选了某个平台，
 * 平台下拉框里就只剩那一个平台了。
 */
export async function fetchShops(
  window: WindowOption,
  from?: string,
  to?: string,
): Promise<StoreOverviewShop[]> {
  const query = new URLSearchParams({ window });
  if (window === 'CUSTOM') {
    if (from) query.set('from', from);
    if (to) query.set('to', to);
  }
  return api<StoreOverviewShop[]>(`${BASE}/shops?${query.toString()}`);
}

// ================================================================ 十进制精确格式化

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
 *
 * <p>🔴 **HALF_UP 远离零**：`1.005 ⇒ 1.01`（浮点会算成 `1.00`），
 * `-1.005 ⇒ -1.01`。
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
function formatScaled(
  scaled: bigint,
  scale: number,
  minFractionDigits: number,
  trimFraction: boolean,
): string {
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

/**
 * 十进制精确的展示格式化。
 *
 * <p>🔴 **展示层走十进制字面串，不走浮点运算**：金额是 numeric(19,4)、数量是 numeric(19,6)，
 * 而 JS 的 `number` 是二进制浮点 —— 拿它做乘法/除法会出 `0.1 + 0.2 === 0.30000000000000004`
 * 这种尾巴。所以本模块**不做任何算术**，展示时把 number 还原成它的**十进制字面串**，
 * 再用 {@link BigInt} 在**缩放整数**上做四舍五入与千分位分组 —— 全程不碰浮点。
 */
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
 * 金额 / 数量的展示串。
 *
 * <p>⚠️ `null` ⇒ 「—」（**不是 0**）。后端把「算不出来」给成 null，
 * 这里原样显示「—」。
 */
export function formatValue(value: number | null | undefined, unit: string): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  if (unit === 'CURRENCY') return formatDecimal(value, CURRENCY_SCALE, CURRENCY_SCALE, false);
  if (unit === 'QUANTITY') return formatDecimal(value, QUANTITY_SCALE, 0, true);
  return formatDecimal(value, 0, 0, false);
}

/** 金额展示串（固定 2 位小数 + 千分位）。 */
export function formatAmount(value: number | null | undefined): string {
  return formatDecimal(value, CURRENCY_SCALE, CURRENCY_SCALE, false);
}

/** 计数展示串（整数 + 千分位；`null` ⇒ 「—」，**不是 0**）。 */
export function formatCount(value: number | null | undefined): string {
  return formatDecimal(value, 0, 0, false);
}

// ================================================================ 维度取值

/** 取某一行上的某个维度（取不到返回 undefined）。 */
export function dimensionOf(
  row: StoreOverviewRow,
  key: string,
): StoreOverviewDimension | undefined {
  return row.dimensions.find((dimension) => dimension.key === key);
}

/**
 * 这个维度涉及的币种（按出现顺序去重）。
 *
 * <p>⚠️ 取自**维度自己的取值**，不是行上的 `currencies` ——
 * 后端只在「这个店铺在这个窗口里有该口径的事实」时才给出取值，
 * 前端据此就不会为不存在的币种编一行空值。
 */
export function currenciesOf(dimension: StoreOverviewDimension | undefined): string[] {
  if (!dimension) return [];
  const seen: string[] = [];
  for (const value of dimension.values) {
    if (!seen.includes(value.currency)) seen.push(value.currency);
  }
  return seen;
}

/** 取某个口径码在某个币种下的取值记录（取不到返回 undefined）。 */
export function metricValueOf(
  dimension: StoreOverviewDimension | undefined,
  metricCode: string,
  currency: string,
): StoreOverviewMetricValue | undefined {
  if (!dimension) return undefined;
  return dimension.values.find(
    (value) => value.metricCode === metricCode && value.currency === currency,
  );
}

/** 取某个口径码在某个币种下的数值（没有取值 ⇒ `null`，**不是 0**）。 */
export function valueOf(
  dimension: StoreOverviewDimension | undefined,
  metricCode: string,
  currency: string,
): number | null {
  return metricValueOf(dimension, metricCode, currency)?.value ?? null;
}

/**
 * 销售额那一格的三条口径行。
 *
 * <p>🔴 **每个币种恒定三条**（平台侧 / 我方还原 / 口径差）：
 * 口径没登记 ⇒ 整个维度是「未接通」，这里返回空数组；
 * 口径登记了但这个店铺这个窗口没有事实 ⇒ 每条的值都是 `null`（显示「—」），
 * **不是 0**。
 */
export interface SalesLine {
  /** 口径码。 */
  metricCode: string;
  /** 短标签（平台侧 / 我方还原 / 口径差）。 */
  label: string;
  /** 币种。 */
  currency: string;
  /** 展示串（「1,234.56」或「—」）。 */
  text: string;
  /** 可用性：AVAILABLE / NO_DATA。 */
  availability: string;
  /** 没有值的原因（availability ≠ AVAILABLE 时有值）。 */
  note: string;
}

export function salesLines(dimension: StoreOverviewDimension | undefined): SalesLine[] {
  if (!dimension || !dimension.available) return [];
  const lines: SalesLine[] = [];
  for (const currency of currenciesOf(dimension)) {
    for (const caliber of SALES_CALIBERS) {
      const record = metricValueOf(dimension, caliber.metricCode, currency);
      lines.push({
        metricCode: caliber.metricCode,
        label: caliber.label,
        currency,
        text: formatAmount(record?.value ?? null),
        availability: record?.availability ?? 'NO_DATA',
        note:
          record?.availabilityNote ??
          '该店铺在这个窗口、这个币种下没有这个口径的事实数据（不是 0）。',
      });
    }
  }
  return lines;
}

/**
 * 某个维度在这个店铺上的取值行（**按币种分列，不跨币种相加**）。
 *
 * <p>销售额那一格请用 {@link salesLines} —— 它额外保证三个口径并列。
 */
export interface DimensionLine {
  currency: string;
  label: string;
  text: string;
  availability: string;
  note: string;
}

export function dimensionLines(dimension: StoreOverviewDimension | undefined): DimensionLine[] {
  if (!dimension || !dimension.available) return [];
  const lines: DimensionLine[] = [];
  for (const currency of currenciesOf(dimension)) {
    for (const record of dimension.values) {
      if (record.currency !== currency) continue;
      lines.push({
        currency,
        label: caliberLabel(record.metricCode),
        text: formatValue(record.value, record.unit),
        availability: record.availability,
        note: record.availabilityNote,
      });
    }
  }
  return lines;
}

// ================================================================ 状态与文案

/** 维度状态：AVAILABLE / NO_DATA / NOT_CONNECTED。 */
export function statusLabel(status: string): string {
  switch (status) {
    case 'AVAILABLE':
      return '有数据';
    case 'NO_DATA':
      return '该窗口没有事实';
    case 'NOT_CONNECTED':
      return '未接通';
    default:
      return status;
  }
}

/** 维度状态的色调。 */
export function statusTone(status: string): 'success' | 'warning' | 'neutral' {
  switch (status) {
    case 'AVAILABLE':
      return 'success';
    case 'NO_DATA':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 这个维度是不是「未接通」（前端要换一套独立样式）。 */
export function isNotConnected(dimension: StoreOverviewDimension | undefined): boolean {
  return !dimension || !dimension.available;
}

/** 店铺分类：GROWTH / LAGGARD / STABLE / UNCLASSIFIED。 */
export function classificationLabel(classification: string): string {
  switch (classification) {
    case 'GROWTH':
      return '增长';
    case 'LAGGARD':
      return '掉队';
    case 'STABLE':
      return '稳定';
    case 'UNCLASSIFIED':
      return '不可比';
    default:
      return classification;
  }
}

/**
 * 店铺分类的色调。
 *
 * <p>⚠️ **「掉队」用 warning 而不是 danger**：掉队是**环比口径**的判定，
 * 它的阈值（−20%）是 13.1.1.2 定的经营口径，不是安全告警。
 * 用红色会把它读成「出事了」。
 */
export function classificationTone(classification: string): 'success' | 'warning' | 'neutral' {
  switch (classification) {
    case 'GROWTH':
      return 'success';
    case 'LAGGARD':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** 口径说明 tooltip（口径说明 + 归属 + 为什么是这个状态）。 */
export function caliberTooltip(dimension: StoreOverviewDimension | undefined): string {
  if (!dimension) return '';
  const lines: string[] = [];
  if (dimension.caliber) lines.push(dimension.caliber);
  if (dimension.ownerModule) lines.push(`口径归属：${dimension.ownerModule}`);
  if (dimension.statusNote) lines.push(dimension.statusNote);
  return lines.join('\n');
}

/** 能力声明里某个维度的声明（取不到返回 undefined）。 */
export function capabilityOf(
  caps: StoreOverviewCapabilities | null,
  key: string,
): StoreOverviewDimensionCapability | undefined {
  if (!caps) return undefined;
  return caps.dimensions.find((dimension) => dimension.key === key);
}

/** 能力声明里**未接通**的维度（逐条列出来，而不是悄悄消失）。 */
export function notConnectedDimensions(
  caps: StoreOverviewCapabilities | null,
): StoreOverviewDimensionCapability[] {
  if (!caps) return [];
  return caps.dimensions.filter((dimension) => !dimension.available);
}

/**
 * 平台筛选项（从**全部**可见店铺推出来）。
 *
 * <p>⚠️ 同一个平台只出现一次（一个平台下有多家店铺）。
 */
export function platformOptions(
  shops: StoreOverviewShop[],
): { platformCode: string; platformName: string }[] {
  const seen = new Map<string, string>();
  for (const shop of shops) {
    if (!shop.platformCode) continue;
    if (!seen.has(shop.platformCode)) seen.set(shop.platformCode, shop.platformName);
  }
  return [...seen.entries()]
    .map(([platformCode, platformName]) => ({ platformCode, platformName }))
    .sort((a, b) => a.platformCode.localeCompare(b.platformCode));
}

/**
 * 空态文案。
 *
 * <p>🔴 **三种空态文案不同**：「没有店铺档案」/「有店铺但这个窗口没投影」/
 * 「服务不可达」—— 三件事的原因不一样，不能共用一句话。
 */
export function emptyState(
  loaded: boolean,
  response: StoreOverviewResponse | null,
): { title: string; description: string } {
  if (!loaded) {
    return {
      title: '店铺概览加载失败',
      description: '后端暂时不可达或身份校验未通过，请刷新重试。页面上已有的内容不会因此被清空。',
    };
  }
  if (!response) {
    return {
      title: '店铺概览加载失败',
      description: '后端暂时不可达或身份校验未通过，请刷新重试。页面上已有的内容不会因此被清空。',
    };
  }
  if (response.emptyReason === 'NO_STORES') {
    return {
      title: '这个租户还没有店铺档案',
      description:
        response.emptyNote ||
        '店铺归属来自 channel 的店铺清单，还没有投影过就无从对比 —— 这是「没有档案」，不是「销售额为 0」。',
    };
  }
  if (response.emptyReason === 'NOT_PROJECTED') {
    return {
      title: '这个时间窗还没有店铺维度投影',
      description:
        response.emptyNote ||
        '店铺维度事实还没有投影进 reporting —— 这是「还没有数据可读」，不是「表现不好」。',
    };
  }
  if (response.rows.length === 0) {
    return {
      title: '没有可对比的店铺',
      description: '换一个时间口径或平台筛选试试。',
    };
  }
  return {
    title: '没有可对比的店铺',
    description: '换一个时间口径或平台筛选试试。',
  };
}
