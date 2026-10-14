import type {
  AgingBucketReport,
  AgingPolicyEffective,
  AgingPolicyPage,
  AgingPolicyUpsertRequest,
  SlowMovingPage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from './api';

/**
 * 7.6.1.1 库龄与呆滞前端模型。
 *
 * <p>这个页面上最容易把数字读错的四处，都在这里显式处理：
 *
 * <ul>
 *   <li><b>「90+ 天」的下界是 91</b>。60~90 归「61-90」桶，所以 90 天的货不进「90+」。
 *       界面上写死这个说明，否则有人看到 90 天的货不在 90+ 桶里会以为算错了。
 *   <li><b>库龄口径要显示</b>。有批次的按收货日算，没有批次的退回库位建档日（代理值）——
 *       一个全部由代理值算出来的库龄必须能被看见，否则用户会当成真实收货日。
 *   <li><b>占用资金显示「不可用 + 原因」，不显示 0 元</b>。仓库服务没有成本列，
 *       显示 0 会被读成「这些呆滞货不占资金」，那比报错危险得多。
 *   <li><b>处置建议只是依据</b>。调价（5.4.1）与退仓/报废（6.4.2.2）都未交付，
 *       所以界面上不放任何「一键处置」按钮，只显示建议与依据。
 * </ul>
 */

/** 库龄分桶报表查询条件。与后端 `AgingBucketQuery` 一一对应。 */
export interface AgingBucketParams {
  skuId?: string;
  warehouseId?: string;
  legalEntityId?: string;
  agingBasis?: string;
  keyword?: string;
}

/** 呆滞清单查询条件。与后端 `SlowMovingQuery` 一一对应。 */
export interface SlowMovingParams {
  skuId?: string;
  warehouseId?: string;
  legalEntityId?: string;
  slowMovingStatus?: string[];
  disposition?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

/** 库龄分桶报表。后端默认返回全部有在手量的批次行。 */
export function fetchAgingBuckets(params: AgingBucketParams = {}) {
  const parts: string[] = [];
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.legalEntityId) parts.push(`legalEntityId=${encodeURIComponent(params.legalEntityId)}`);
  if (params.agingBasis) parts.push(`agingBasis=${encodeURIComponent(params.agingBasis)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  return api<AgingBucketReport>(
    `warehouse/inventory/aging/buckets${parts.length ? `?${parts.join('&')}` : ''}`,
  );
}

/** 呆滞清单。后端默认只看「需要处理」的行（滞销/呆滞 + 临期/过期 + 残次）。 */
export function fetchSlowMoving(params: SlowMovingParams = {}) {
  const parts: string[] = [];
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.legalEntityId) parts.push(`legalEntityId=${encodeURIComponent(params.legalEntityId)}`);
  for (const s of params.slowMovingStatus ?? [])
    parts.push(`slowMovingStatus=${encodeURIComponent(s)}`);
  if (params.disposition) parts.push(`disposition=${encodeURIComponent(params.disposition)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return api<SlowMovingPage>(
    `warehouse/inventory/aging/slow-moving${parts.length ? `?${parts.join('&')}` : ''}`,
  );
}

/** 阈值策略列表 + 兜底默认值。 */
export function fetchAgingPolicies(
  params: { skuId?: string; enabled?: boolean; sort?: string; page?: number; size?: number } = {},
) {
  const parts: string[] = [];
  if (params.skuId) parts.push(`skuId=${encodeURIComponent(params.skuId)}`);
  if (params.enabled !== undefined) parts.push(`enabled=${params.enabled}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return api<AgingPolicyPage>(
    `warehouse/inventory/aging/policies${parts.length ? `?${parts.join('&')}` : ''}`,
  );
}

/**
 * 某个 SKU 的生效策略。
 *
 * <p>没配过也返回 200 且 `configured=false` —— 界面要靠它区分「配成 60 天」与「没人配、用的默认值」。
 */
export function fetchAgingPolicy(skuId: string) {
  return api<AgingPolicyEffective>(`warehouse/inventory/aging/policy/${encodeURIComponent(skuId)}`);
}

/** 建立或修改某 SKU 的阈值策略（幂等 upsert）。停用走 `enabled=false`，策略行永久保留。 */
export function saveAgingPolicy(input: AgingPolicyUpsertRequest) {
  return api<AgingPolicyEffective>('warehouse/inventory/aging/policy', 'POST', input);
}

// ================================================================ 展示口径

/** 库龄桶标签。缺失时退回档位名，绝不静默显示空白。 */
export function agingBucketLabel(bucket: string | undefined, label?: string): string {
  if (label) return label;
  switch (bucket) {
    case 'AGE_0_30':
      return '0-30 天';
    case 'AGE_31_60':
      return '31-60 天';
    case 'AGE_61_90':
      return '61-90 天';
    case 'AGE_90_PLUS':
      return '90+ 天';
    case 'NO_INBOUND_DATE':
      return '无入库日期';
    default:
      return bucket ?? '未知档位';
  }
}

/** 呆滞档位标签。 */
export function slowMovingStatusLabel(value: string | undefined, label?: string): string {
  if (label) return label;
  switch (value) {
    case 'OBSOLETE':
      return '呆滞';
    case 'SLOW_MOVING':
      return '滞销';
    case 'NORMAL':
      return '正常';
    default:
      return value ?? '未知';
  }
}

/** 处置建议标签。 */
export function dispositionLabel(value: string | undefined, label?: string): string {
  if (label) return label;
  switch (value) {
    case 'SCRAP':
      return '报废';
    case 'RETURN_TO_VENDOR':
      return '退仓';
    case 'MARKDOWN':
      return '降价促销';
    case 'CLEARANCE':
      return '清仓';
    case 'HOLD':
      return '暂不处置';
    default:
      return value ?? '未知';
  }
}

/**
 * 库龄天数文案。
 *
 * <p>没有入库日期时显示「不可得」而**不是 0 天** —— 0 天会被读成「刚入库」，
 * 与「算不出来」是两件完全不同的事。
 */
export function agingDaysText(days: number | null | undefined): string {
  if (days === null || days === undefined) return '库龄不可得（无入库日期）';
  if (days === 0) return '今天入库';
  return `已存放 ${days} 天`;
}

/**
 * 库龄口径文案。
 *
 * <p>代理口径必须显式标出：非批次管理品只能用库位建档日代替收货日，
 * 它的库龄不是「入库至今」，少算或多数都可能。
 */
export function agingBasisText(
  basis: string | undefined,
  label?: string,
  inboundDate?: string | null,
): string {
  const name = label ?? (basis === 'LOT_RECEIVED_ON' ? '批次收货日' : '库位建档日（代理）');
  const date = inboundDate ? `（${inboundDate}）` : '';
  return basis === 'LOT_RECEIVED_ON' ? `${name}${date}` : `${name}${date}，属代理值`;
}

/**
 * 占用资金文案 —— **不可用时显示原因，绝不显示 0 元**。
 *
 * <p>仓库服务没有成本列，显示 0 会被读成「这些呆滞货不占资金」，
 * 那正是蓝图里说的「最容易被忽略的利润黑洞」被反向掩盖。
 */
export function capitalTieText(
  available: boolean | undefined,
  reason?: string | null,
): string {
  if (available) return '占用资金已可估算';
  return reason?.trim() ? `占用资金暂不可估算：${reason.trim()}` : '占用资金暂不可估算（缺少成本口径）';
}

/** 阈值来源文案：让「配成 60 天」与「没人配」在界面上分得开。 */
export function thresholdSourceText(source: string | undefined): string {
  return source === 'POLICY' ? '本 SKU 配置' : '默认兜底（该 SKU 未配置）';
}

/** 排序键下拉。 */
export const agingSortOptions = [
  { value: 'AGING_DAYS', label: '库龄从高到低' },
  { value: 'AVAILABLE', label: '可用量从高到低' },
  { value: 'ON_HAND', label: '在手量从高到低' },
  { value: 'SKU', label: '按 SKU 编码' },
  { value: 'WAREHOUSE', label: '按仓库' },
  { value: 'RECEIVED_ON', label: '按入库日期' },
];

/** 库龄口径下拉。 */
export const agingBasisOptions = [
  { value: '', label: '全部口径' },
  { value: 'LOT_RECEIVED_ON', label: '仅批次收货日（真实入库日）' },
  { value: 'BUCKET_CREATED_AT', label: '仅库位建档日（代理口径）' },
];

/** 呆滞档位下拉。 */
export const slowMovingStatusOptions = [
  { value: '', label: '默认：只看需要处理（滞销/呆滞 + 临期/过期 + 残次）' },
  { value: 'OBSOLETE', label: '呆滞' },
  { value: 'SLOW_MOVING', label: '滞销' },
  { value: 'NORMAL', label: '正常（含未命中任何处置条件）' },
];

/**
 * 处置说明文案。
 *
 * <p>调价与退仓/报废都未交付，所以这句话必须把「这是建议不是动作」讲清楚，
 * 否则用户会以为点一下就会改价。
 */
export function dispositionNoteText(): string {
  return '处置建议仅供参考：调价（5.4.1）与退仓/报废（6.4.2.2）尚未交付，本模块不触发任何动作。';
}
