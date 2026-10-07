import type {
  ExpiryAlertPage,
  ExpiryLotOption,
  ExpiryPolicy,
  ExpiryPolicyPage,
  ExpiryPolicySaved,
  ExpiryPolicyUpsertRequest,
  ExpiryStatusOption,
  ExpiryWarehouseOption,
  FefoAllocationPlan,
  FefoAllocationRequest,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from './api';

/**
 * 7.3.1.2 效期与先进先出前端模型。
 *
 * <p>界面上最容易误读的三处，这个模块都显式处理了：
 *
 * <ul>
 *   <li>**过期与临期分列**。过期是「不能再发」、临期是「还能发但该促销」，
 *       两者的处置路径完全不同（过期走 6.4.2 临期品处理），合成一个「效期状态」没法派工。
 *   <li>**临期阈值逐 SKU 不同**。行上的 `leadDaysSource` 必须显示：
 *       同样是 3 天到期，配 7 天窗口的 SKU 报「临期」、配 1 天窗口的报「正常」，
 *       不标来源的话用户会以为系统算错了。
 *   <li>**分配缺口要能解释**。只给一句「缺 20 件」时人只能回去翻库存，
 *       所以 `ineligible` 的各分项与 `notes` 必须一并显示。
 * </ul>
 */

/** 效期预警清单查询条件。与后端 `ExpiryAlertQuery` 一一对应。 */
export interface ExpiryAlertParams {
  skuId?: string;
  warehouseId?: string;
  expiryStatus?: string[];
  leadDays?: number;
  keyword?: string;
  inStock?: boolean;
  sort?: string;
  page?: number;
  size?: number;
}

function query(params: ExpiryAlertParams): string {
  const parts: string[] = [];
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  for (const s of params.expiryStatus ?? []) parts.push(`expiryStatus=${encodeURIComponent(s)}`);
  if (params.leadDays !== undefined) parts.push(`leadDays=${params.leadDays}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.inStock !== undefined) parts.push(`inStock=${params.inStock}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 效期预警清单（临期 / 过期 / 非效期品）。后端默认只返回需要处理的两档。 */
export function fetchExpiryAlerts(params: ExpiryAlertParams = {}) {
  return api<ExpiryAlertPage>(`warehouse/inventory/expiry/alerts${query(params)}`);
}

/** 分配策略列表 + 全量汇总 + 策略下拉词表。 */
export function fetchExpiryPolicies(
  params: {
    skuId?: string;
    strategy?: string[];
    enabled?: boolean;
    sort?: string;
    page?: number;
    size?: number;
  } = {},
) {
  const parts: string[] = [];
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  for (const s of params.strategy ?? []) parts.push(`strategy=${encodeURIComponent(s)}`);
  if (params.enabled !== undefined) parts.push(`enabled=${params.enabled}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return api<ExpiryPolicyPage>(
    `warehouse/inventory/expiry/policies${parts.length ? `?${parts.join('&')}` : ''}`,
  );
}

/**
 * 某个 SKU 的生效策略。
 *
 * <p>没配过也返回 200 且 `configured=false` —— 界面要靠它区分「配成 30 天」与「没人配、用的默认值」。
 */
export function fetchExpiryPolicy(skuId: string) {
  return api<ExpiryPolicy>(`warehouse/inventory/expiry/policy/${encodeURIComponent(skuId)}`);
}

/**
 * 建立或修改某 SKU 的分配策略（幂等 upsert）。
 *
 * <p>刻意没有删除函数：停用走 `enabled=false`，策略行永久保留。
 */
export function saveExpiryPolicy(input: ExpiryPolicyUpsertRequest) {
  return api<ExpiryPolicySaved>('warehouse/inventory/expiry/policy', 'POST', input);
}

/**
 * 出库分配推荐（FEFO / FIFO / 指定批次）。
 *
 * <p>`strategy` 与 `specifiedLotId` 不传时用该 SKU 的策略；传了就是**本次覆盖**，
 * 对应拣货员临时指定「这单走指定批次」的真实场景。
 */
export function recommendAllocation(input: FefoAllocationRequest) {
  return api<FefoAllocationPlan>('warehouse/inventory/expiry/allocation', 'POST', input);
}

/** 仓库清单。分配推荐限定单仓，所以要给仓库下拉。 */
export function fetchExpiryWarehouses() {
  return api<ExpiryWarehouseOption[]>('warehouse/inventory/expiry/warehouses');
}

/** 某 SKU 的批次下拉（指定批次策略用），后端按失效日期升序返回。 */
export function fetchExpiryLots(skuId: string) {
  return api<ExpiryLotOption[]>(`warehouse/inventory/expiry/lots/${encodeURIComponent(skuId)}`);
}

/** 出库分配策略标签。未知码原样显示而不是静默吞掉。 */
export const expiryStrategyLabels: Record<string, string> = {
  FEFO: '先到期先出',
  FIFO: '先进先出',
  SPECIFIED_LOT: '指定批次',
  // NONE 只出现在分配响应里：策略开关关闭时的实际取值。
  NONE: '未启用策略（按库位顺序）',
};

/** 出库分配策略下拉。值域来自后端白名单，非法值会 400。 */
export const expiryStrategyOptions = [
  { value: 'FEFO', label: '先到期先出（FEFO）' },
  { value: 'FIFO', label: '先进先出（FIFO）' },
  { value: 'SPECIFIED_LOT', label: '指定批次' },
];

/** 效期档位标签。 */
export const expiryStatusLabels: Record<string, string> = {
  EXPIRED: '已过期',
  EXPIRING: '临期',
  HEALTHY: '正常',
  NO_EXPIRY: '非效期品',
};

/** 效期档位下拉。后端默认只列「需要处理」的两档。 */
export const expiryStatusOptions = [
  { value: 'EXPIRED', label: '已过期' },
  { value: 'EXPIRING', label: '临期' },
  { value: 'HEALTHY', label: '正常' },
  { value: 'NO_EXPIRY', label: '非效期品' },
];

/** 预警排序键。后端有白名单，非法值返回 400，所以下拉只给这几个。 */
export const expiryAlertSortOptions = [
  { value: 'EXPIRY', label: '紧急度（最紧急的排最前）' },
  { value: 'ON_HAND', label: '在手数量' },
  { value: 'LOT_CODE', label: '批号' },
  { value: 'RECEIVED_ON', label: '收货日期' },
  { value: 'UPDATED_AT', label: '最近更新' },
  { value: 'SKU', label: 'SKU' },
];

/**
 * 剩余有效期的展示文案。
 *
 * <p>四种情况分开说：没有失效日期（不适用）、已过期（正数说「已过期 N 天」而不是
 * 「剩余 -N 天」，后者读起来别扭）、今天到期、还有 N 天。
 */
export function expiryText(expiresOn: string | null, daysToExpiry: number | null): string {
  if (!expiresOn) return '非效期品';
  if (daysToExpiry === null) return expiresOn;
  if (daysToExpiry < 0) return `${expiresOn}（已过期 ${Math.abs(daysToExpiry)} 天）`;
  if (daysToExpiry === 0) return `${expiresOn}（今天到期）`;
  return `${expiresOn}（还有 ${daysToExpiry} 天）`;
}

/** 档位的语气色：过期是最重的、临期次之。返回空串表示用中性色。 */
export function expiryStatusTone(status: string): 'danger' | 'warning' | 'muted' | '' {
  if (status === 'EXPIRED') return 'danger';
  if (status === 'EXPIRING') return 'warning';
  if (status === 'NO_EXPIRY') return 'muted';
  return '';
}

/**
 * 临期阈值来源的文案。
 *
 * <p>必须显示：同样是 3 天到期，配 7 天窗口的 SKU 报临期、配 1 天的报正常。
 * 不标来源，用户会以为系统算错了。
 */
export function leadDaysText(source: string, leadDays: number): string {
  return source === 'POLICY' ? `${leadDays} 天（本 SKU 配置）` : `${leadDays} 天（默认）`;
}

/**
 * 分配缺口的可读说明。
 *
 * <p>四个分项逐条说出来，是因为它们的处置动作各不相同：
 * 过期的要先走 6.4.2 临期品处理、待检的要先质检、不可拣的是位置问题、
 * 没有效期批次的要么补录效期要么改策略 —— 混成一句「缺 20 件」等于没说。
 */
export function shortfallText(ineligible: {
  expiredAvailable: number;
  noExpiryAvailable: number;
  nonSellableAvailable: number;
  nonPickableAvailable: number;
  noLotAvailable: number;
}): string[] {
  const out: string[] = [];
  const hit = (v: number) => v > 0;
  if (hit(ineligible.expiredAvailable))
    out.push(`已过期 ${ineligible.expiredAvailable}（不能再发，需先走临期品处理）`);
  if (hit(ineligible.nonSellableAvailable))
    out.push(`待检/残次等非可售 ${ineligible.nonSellableAvailable}（需先质检或报废）`);
  if (hit(ineligible.nonPickableAvailable))
    out.push(`不可拣库位 ${ineligible.nonPickableAvailable}（需先移库）`);
  if (hit(ineligible.noExpiryAvailable))
    out.push(`无失效日期 ${ineligible.noExpiryAvailable}（FEFO 不排序这类批次，需补录效期）`);
  if (hit(ineligible.noLotAvailable))
    out.push(`无批次 ${ineligible.noLotAvailable}（该 SKU 未启用批次管理）`);
  return out;
}

/** 分配是否全额满足。缺口为 0 才算能全额出库。 */
export function allocationComplete(plan: { allocatedQuantity: number; shortfallQuantity: number }) {
  return plan.shortfallQuantity <= 0;
}

/** 把分配请求的空字符串数量解析成 number；空或非法返回 null（交由调用方提示，不静默当 0）。 */
export function parseQuantity(raw: string): number | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : null;
}

/** 策略开关的文案：未配置与已配置但停用是两回事，界面上要能分开。 */
export function policyEnabledText(policy: { configured: boolean; enabled: boolean }): string {
  if (!policy.configured) return '未配置（出库按库位顺序，不做效期排序）';
  return policy.enabled ? '已启用' : '已停用（出库按库位顺序，不做效期排序）';
}

export type { ExpiryPolicy, ExpiryPolicySaved, ExpiryStatusOption, FefoAllocationPlan };
