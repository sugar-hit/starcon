import type {
  AllocationCapabilities,
  AllocationLine,
  AllocationPolicyDetail,
  AllocationPolicyEffective,
  AllocationPolicyInput,
  AllocationPolicyPage,
  AllocationPolicySaved,
  AllocationResult,
  AllocationRunDetail,
  AllocationRunPage,
  AllocationShopSalesSaved,
  SaleRow,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 7.8.1.1 分配策略配置前端模型。
 *
 * <p>界面上最容易出错的几处，这个模块都显式处理了：
 *
 * <ul>
 *   <li>**守恒式常驻显示**。可用 = 缓冲 + 可分配，可分配 = Σ分摊 + 未分配。
 *       三个数摆在一起且分母写清楚，是因为蓝图红线要防的是超卖 —— 而超卖在界面上
 *       表现为「几家店加起来超过了货」。把等式摆在结果头部，用户不用自己加一遍。
 *   <li>**「分到 0 件」必须带原因**。蓝图说的「某个店铺一直抢不到货」，
 *       界面上不能只显示一个 0，要显示是触顶 / 被前面的分完 / 销量为 0 / 已停用。
 *       没有原因，那个 0 就只是一个看起来正常的数字。
 *   <li>**未配置策略是一个要占版面的空态**。没有统一分摊算法时结果不是「平均分」，
 *       而是「拒绝」—— 平均分会让每家店都以为有货，那正是超卖。
 *   <li>**试算与落库读同一份返回**。两个端点共用同一个渲染函数，
 *       所以「试算看到的」和「落库记下的」永远是同一套口径。
 *   <li>**比例显示成百分比**。库里存 0.5，界面上是 50% —— 存比例、显百分比，
 *       免得有人把 0.5 读成 0.5%。
 * </ul>
 */

// ------------------------------------------------------------ 接口

export interface PolicyListParams {
  scopeType?: string;
  referenceId?: string;
  method?: string;
  effectiveOnly?: boolean;
  keyword?: string;
  page?: number;
  size?: number;
}

export function fetchCapabilities(): Promise<AllocationCapabilities> {
  return api<AllocationCapabilities>('warehouse/inventory/allocation-policies/capabilities');
}

export function fetchPolicies(params: PolicyListParams): Promise<AllocationPolicyPage> {
  return api<AllocationPolicyPage>(
    `warehouse/inventory/allocation-policies${query(params)}`,
  );
}

export function fetchPolicy(id: string): Promise<AllocationPolicyDetail> {
  return api<AllocationPolicyDetail>(`warehouse/inventory/allocation-policies/${id}`);
}

/** 解析某一时刻真正生效的策略。没配时返回的 policy 为 null —— 那是一个要显式处理的状态。 */
export function fetchEffective(params: {
  scopeType?: string;
  referenceId?: string;
  skuId?: string;
}): Promise<AllocationPolicyEffective> {
  return api<AllocationPolicyEffective>(
    `warehouse/inventory/allocation-policies/effective${query(params)}`,
  );
}

export function createPolicy(input: AllocationPolicyInput): Promise<AllocationPolicySaved> {
  return api<AllocationPolicySaved>('warehouse/inventory/allocation-policies', 'POST', input);
}

/** 换版 = 收口旧档 + 开新档。没有「就地改」的入口。 */
export function supersedePolicy(
  id: string,
  input: AllocationPolicyInput,
): Promise<AllocationPolicySaved> {
  return api<AllocationPolicySaved>(
    `warehouse/inventory/allocation-policies/${id}/versions`,
    'POST',
    input,
  );
}

export function retirePolicy(id: string): Promise<AllocationPolicySaved> {
  return api<AllocationPolicySaved>(`warehouse/inventory/allocation-policies/${id}/retire`, 'POST', {});
}

// ------------------------------------------------------------ 销量登记

export function recordSales(input: {
  sourceSystem: string;
  referenceId: string;
  referenceLabel?: string;
  skuId: string;
  periodStart: string;
  periodEnd: string;
  unitsSold: number;
}): Promise<AllocationShopSalesSaved> {
  return api<AllocationShopSalesSaved>('warehouse/inventory/allocation-sales', 'POST', input);
}

export function fetchSales(params: {
  skuId: string;
  from: string;
  to: string;
}): Promise<SaleRow[]> {
  return api<SaleRow[]>(`warehouse/inventory/allocation-sales${query(params)}`);
}

// ------------------------------------------------------------ 分摊

/**
 * 试算。POST：请求体是一组 SKU，放 URL 里会撞长度限制。
 *
 * <p>试算**一行都不写**：配策略时先看结果，确认无误再落库。
 */
export function previewAllocation(input: {
  skuIds: string[];
  availableQuantity?: number;
  referenceId?: string;
  scopeType?: string;
}): Promise<AllocationResult[]> {
  return api<AllocationResult[]>(
    'warehouse/inventory/allocation-policies/calculation',
    'POST',
    { ...input, persist: false },
  );
}

/** 分摊并落库。走**另一个端点**而不是靠 persist 布尔切换：落库写的是不可改的批次，要 update 权限。 */
export function runAllocation(input: {
  skuIds: string[];
  availableQuantity?: number;
  referenceId?: string;
  scopeType?: string;
}): Promise<AllocationResult[]> {
  return api<AllocationResult[]>('warehouse/inventory/allocation-policies/runs', 'POST', {
    ...input,
    persist: true,
  });
}

export function fetchRuns(params: {
  skuId?: string;
  method?: string;
  page?: number;
  size?: number;
}): Promise<AllocationRunPage> {
  return api<AllocationRunPage>(`warehouse/inventory/allocation-policies/runs${query(params)}`);
}

export function fetchRun(runId: string): Promise<AllocationRunDetail> {
  return api<AllocationRunDetail>(`warehouse/inventory/allocation-policies/runs/${runId}`);
}

function query(params: object): string {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ------------------------------------------------------------ 展示

/**
 * codegen 约定：只要 schema 里有任何一个可空字段，整个类型别名就会生成成 `T | null`，
 * 所以列表元素也要先过这道关。
 *
 * <p>刻意写成**类型谓词**而不是普通函数：写成 `<T>(v: T | null) => T | null` 时
 * `Array.filter(notNull)` 不会收窄元素类型，于是 map 里每个字段访问都要再补一次判空，
 * 而漏一处就是一个「可能为 null」的运行时崩溃点。
 */
export function notNull<T>(v: T | null | undefined): v is T {
  return v !== null && v !== undefined;
}

/** 数量口径：DB 是 numeric(19,6)，而**分摊额必须是整件**，所以这里不补小数位。 */
export function qty(v: number | string | null | undefined): string {
  if (v === null || v === undefined) return '—';
  const n = typeof v === 'string' ? Number(v) : v;
  if (Number.isNaN(n)) return '—';
  return n.toLocaleString('zh-CN');
}

/** 比例：库里存 0.5，界面显示 50%。 */
export function percent(v: number | string | null | undefined): string {
  if (v === null || v === undefined) return '—';
  const n = typeof v === 'string' ? Number(v) : v;
  if (Number.isNaN(n)) return '—';
  return `${(n * 100).toLocaleString('zh-CN', { maximumFractionDigits: 4 })}%`;
}

export const scopeTypeLabels: Record<string, string> = {
  DEFAULT: '全租户兜底',
  SHOP: '按店铺',
  SKU: '按 SKU',
};

export const methodLabels: Record<string, string> = {
  PROPORTIONAL_SALES: '按销量占比比例分配',
  FIXED_PRIORITY: '按固定优先级先到先得',
  SHOP_TYPE_PRIORITY: '按店铺类型优先级',
};

export const bufferModeLabels: Record<string, string> = {
  RATIO: '按比例留',
  QUANTITY: '按件数留',
};

export const outcomeLabels: Record<string, string> = {
  OK: '正常',
  CAP_REACHED: '触到份额上限',
  DEMAND_EXHAUSTED: '货被前面的分完',
  NO_SALES: '销量为 0',
  DISABLED: '成员已停用',
};

export const operatingModeLabels: Record<string, string> = {
  SELF_FULFILLED: '自营',
  FULL_CONSIGNMENT: '全托管',
  SEMI_CONSIGNMENT: '半托管',
  OVERSEAS_WAREHOUSE: '海外仓',
  LOCAL: '本地',
};

/** 分摊结果里每行的色调：只有「分到 0 件」才需要被看见。 */
// outcome 是 codegen 内联的字符串联合（枚举不单独生成类型），所以这里收 string。
export function outcomeTone(outcome: string | null | undefined): string {
  switch (outcome) {
    case 'OK':
      return 'text-emerald-600 dark:text-emerald-400';
    case 'CAP_REACHED':
      return 'text-amber-600 dark:text-amber-400';
    case 'DEMAND_EXHAUSTED':
    case 'NO_SALES':
      return 'text-rose-600 dark:text-rose-400';
    case 'DISABLED':
      return 'text-muted-foreground';
    default:
      return 'text-muted-foreground';
  }
}

/**
 * 守恒式的三段文案。
 *
 * <p>把等式写进界面而不是让用户自己加：蓝图红线要防的就是「几家加起来超过货」，
 * 而这个等式摆在最上面时，超卖在界面上是**看得见的**。
 */
export function conservationText(result: AllocationResult): string[] {
  return [
    `可用 ${qty(result.availableQuantity)} = 缓冲 ${qty(result.bufferQuantity)} + 可分配 ${qty(result.allocatableQuantity)}`,
    `可分配 ${qty(result.allocatableQuantity)} = Σ分摊 ${qty(result.allocatedTotal)} + 未分配 ${qty(result.unallocatedQuantity)}`,
  ];
}

/** 分摊额合计（前端自算一遍，用来和后端报的 allocatedTotal 对照）。 */
export function sumAllocated(lines: AllocationLine[] | null | undefined): number {
  return (lines ?? []).reduce((acc, l) => acc + Number(l.allocatedQty ?? 0), 0);
}

/** 区间（左闭右开）文案。与后端 effective_from/effective_to 同一套约定。 */
export function intervalText(from: string | null, to: string | null): string {
  const start = from ? new Date(from).toLocaleString('zh-CN') : '—';
  if (!to) return `${start} 起至今仍在生效`;
  return `${start} ~ ${new Date(to).toLocaleString('zh-CN')}（已收口）`;
}

/** 统一的错误文案：把 ApiError 的状态码与后端给的原因都带出来。 */
export function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    return e.status ? `请求失败（${e.status}）：${e.message}` : e.message;
  }
  return e instanceof Error ? e.message : String(e);
}

export type { AllocationOutcome };