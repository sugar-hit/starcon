import type {
  SellablePolicyDetail,
  SellablePolicyEffective,
  SellablePolicyInput,
  SellablePolicyPage,
  SellablePolicySaved,
  SellablePoolDetail,
  SellablePoolSummary,
  SellableQuantityResult,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 7.2.2.1 可售量口径前端模型。
 *
 * <p>界面上最容易出错的几处，这个模块都显式处理了：
 *
 * <ul>
 *   <li>**"没有口径"不能画成"可售量 0"**。没配口径时平台可售数没有依据：回写 0 是立刻断货，
 *       回写满是超卖，两条路都比拒绝更糟。所以未配置是一个**要占版面**的空态，
 *       而不是一个显示 0 的表格行。
 *   <li>**回退到兜底档必须显式标出来**。用了兜底档和用了店铺自己的档，风险面不一样 ——
 *       后者可以有店铺专属的缓冲比例，前者只有一个全局值。把 `usedFallback` 藏起来，
 *       等于让运营以为自己在看店铺口径。
 *   <li>**判定比数字优先**。余量可以是正数也可以是负数，而负数里还要分"有意超卖"与"配置错了"。
 *       界面上先给判定、再给数字，因为要做的动作（改缓冲 vs 接受超卖）取决于判定。
 *   <li>**缓冲比例显示成百分比**。库里存的是 0.2，界面上是 20% —— 存比例、显百分比，
 *     免得有人把 0.2 读成 0.2%。
 * </ul>
 */

// ------------------------------------------------------------ 接口

export interface PolicyListParams {
  scopeType?: string;
  referenceId?: string;
  poolMode?: string;
  effectiveOnly?: boolean;
  keyword?: string;
  page?: number;
  size?: number;
}

export function fetchSellablePolicies(params: PolicyListParams): Promise<SellablePolicyPage> {
  return api<SellablePolicyPage>(`warehouse/inventory/sellable-policies${query(params)}`);
}

export function fetchSellablePolicy(id: string): Promise<SellablePolicyDetail> {
  return api<SellablePolicyDetail>(`warehouse/inventory/sellable-policies/${id}`);
}

/** 解析某一时刻真正生效的口径。没配时返回的 policy 为 null —— 那是一个要显式处理的状态。 */
export function fetchEffective(
  scopeType: string,
  referenceId?: string,
): Promise<SellablePolicyEffective> {
  return api<SellablePolicyEffective>(
    `warehouse/inventory/sellable-policies/effective${query({ scopeType, referenceId })}`,
  );
}

export function fetchPools(): Promise<SellablePoolSummary[]> {
  return api<SellablePoolSummary[]>('warehouse/inventory/sellable-policies/pools');
}

export function fetchPool(poolKey: string): Promise<SellablePoolDetail> {
  return api<SellablePoolDetail>(
    `warehouse/inventory/sellable-policies/pools/${encodeURIComponent(poolKey)}`,
  );
}

/** 可售量计算。POST：请求体是一组 SKU，放 URL 里会撞长度限制。 */
export function calculate(input: {
  skuIds: string[];
  referenceId?: string;
  scopeType?: string;
}): Promise<SellableQuantityResult> {
  return api<SellableQuantityResult>(
    'warehouse/inventory/sellable-policies/calculation',
    'POST',
    input,
  );
}

export function createPolicy(input: SellablePolicyInput): Promise<SellablePolicySaved> {
  return api<SellablePolicySaved>('warehouse/inventory/sellable-policies', 'POST', input);
}

/** 换版 = 收口旧档 + 开新档。没有"就地改"的入口。 */
export function supersedePolicy(
  id: string,
  input: SellablePolicyInput,
): Promise<SellablePolicySaved> {
  return api<SellablePolicySaved>(
    `warehouse/inventory/sellable-policies/${id}/versions`,
    'POST',
    input,
  );
}

export function retirePolicy(id: string): Promise<SellablePolicySaved> {
  return api<SellablePolicySaved>(`warehouse/inventory/sellable-policies/${id}/retire`, 'POST', {});
}

export function fetchWarehouses(): Promise<WarehouseRef[]> {
  return api<WarehouseRef[]>('warehouse/warehouses');
}

function query(params: object): string {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ------------------------------------------------------------ 展示

/**
 * codegen 约定：只要 schema 里有任何一个可空字段，整个类型别名就会生成成 `T | null`
 * （仓库里既有的 SkuPackaging / EntryFormDependency 等 18 个类型都是这个形状）。
 * 所以凡是遍历接口返回的数组或取子对象，都要先过一道这个守卫 ——
 * 它同时也是 `noUncheckedIndexedAccess` 下的必需步骤。
 */
export function notNull<T>(value: T | null): value is NonNullable<T> {
  return value !== null && value !== undefined;
}

export const scopeTypeLabels: Record<string, string> = {
  DEFAULT: '全租户兜底',
  CHANNEL: '按渠道',
  SHOP: '按店铺',
};

export const poolModeLabels: Record<string, string> = {
  EXCLUSIVE: '独占池',
  SHARED: '共享池',
};

export const stockScopeLabels: Record<string, string> = {
  OWN_WAREHOUSE: '仅自有仓',
  OWN_PLUS_FBA: '自有仓 + FBA',
};

export const warehouseKindLabels: Record<string, string> = {
  OWN: '自有仓',
  PLATFORM_FBA: '平台仓 FBA',
};

/**
 * 池安全判定 → 视觉档位。
 *
 * 界面纪律：先看判定再看数字。判定决定要做的动作 —— OVERSOLD 要改缓冲，
 * TOLERATED 是有意超卖（不用改）、GUARDED 才是可以放心的状态。
 * 三个危险档刻意用同一族暖色，因为它们**都要人看一眼**；只有 GUARDED 用中性色。
 */
export const verdictTone: Record<string, 'ok' | 'warn' | 'bad'> = {
  GUARDED: 'ok',
  NO_HEADROOM: 'bad',
  TOLERATED: 'warn',
  OVERSOLD: 'bad',
};

/** 缓冲比例：库里是 0.2，界面上是 20%。 */
export function percent(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${(value * 100).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}%`;
}

/** 分配比：各成员回写额合起来占池容量的几成。100% 就是没有余量。 */
export function allocationText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${(value * 100).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}%`;
}

/** 余量：负数用「超出」措辞而不是「负」，因为两种情况的处置动作完全不同。 */
export function headroomText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  if (value < 0) return `超出 ${qty(-value)}`;
  return `余 ${qty(value)}`;
}

export function qty(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 4 });
}

/** 生效区间。左闭右开；未收口就是「至今」。 */
export function intervalText(
  from: string | null | undefined,
  to: string | null | undefined,
): string {
  if (!from) return '—';
  const start = formatMoment(from);
  return to ? `${start} ~ ${formatMoment(to)}（已收口）` : `${start} ~ 至今在生效`;
}

export function formatMoment(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * 把缓冲比例输入框（0~100）换算成接口要的比例（0~1）。
 *
 * <p>界面上用百分数输入是为了不让人去算小数，但**绝不能**把用户输入的百分数原样发给后端 ——
 * 填 20 发成 20 会被 DB 的 CHECK 当场拒掉（这是好事，比静默截断强），
 * 而这里做换算才是正确路径。
 */
export function toRatio(input: string): number | null {
  const trimmed = input.trim();
  if (trimmed === '') return null;
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed)) return null;
  if (parsed < 0 || parsed > 100) return null;
  return parsed / 100;
}

/** 反向：把接口的比例换算回输入框。 */
export function fromRatio(value: number | null | undefined): string {
  if (value === null || value === undefined) return '';
  return String(Math.round(value * 1e6) / 1e4);
}

export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return '当前角色没有可售量口径的维护权限';
    if (error.status === 0) return '正在连接仓库服务，请稍后重试';
    return error.message;
  }
  if (error instanceof Error) return error.message;
  return '请求失败，请稍后重试';
}
