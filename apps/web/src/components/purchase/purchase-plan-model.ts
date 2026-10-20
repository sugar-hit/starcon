import type {
  PurchasePlanAggregateInput,
  PurchasePlanAggregateResult,
  PurchasePlanCampaignInput,
  PurchasePlanCampaignPage,
  PurchasePlanCampaignRow,
  PurchasePlanCapabilities,
  PurchasePlanCreateInput,
  PurchasePlanDetail,
  PurchasePlanLineAdjustInput,
  PurchasePlanLineDetail,
  PurchasePlanLinePage,
  PurchasePlanLineRow,
  PurchasePlanLockInput,
  PurchasePlanLockPolicyInput,
  PurchasePlanLockPolicyPage,
  PurchasePlanLockResult,
  PurchasePlanOptions,
  PurchasePlanPage,
  PurchasePlanRow,
  PurchasePlanSummary,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.2.1.3 采购计划前端模型。
 *
 * <p>蓝图：「按周期（月/季）、按店铺大促节奏、按仓库制定采购计划，把补货建议汇总成计划行，
 * 支持调整、锁定、导出给采购员。」输出「采购计划（计划行：SKU、数量、目标仓、期望到货日、
 * 优先级）」。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>日常与大促分开看</b>：汇总视图**两段独立**显示（日常备货量 / 大促备货量），
 *       页面**不给**一个把两者加在一起的总数字 —— 那个数字会让日常补货失焦，
 *       正是蓝图加粗句「大促备货与日常补货要分开」要防的事。</li>
 *   <li><b>算式随行返回</b>：计划量旁边显示后端给的 {@code quantityBasis} 原文，
 *       「这个数为什么比建议量大」不用点进去再猜。</li>
 *   <li><b>「答不出来」不是 0</b>：建议量取不到时显示「无数据」，绝不显示 0 ——
 *       0 会被读成「不用备」。</li>
 *   <li><b>锁定状态如实显示</b>：只有 warehouse 真的确认了预占才是「已确认」；
 *       失败时显示失败原因原文，不显示成「已锁定」。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const base = 'trade/purchase-plans';

// ================================================================ 查询参数

export interface PurchasePlanParams {
  planType?: string;
  status?: string;
  campaignId?: string;
  targetWarehouseId?: string;
  priority?: string;
  periodStart?: string;
  periodEnd?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function purchasePlanQuery(params: PurchasePlanParams): string {
  const parts: string[] = [];
  if (params.planType) parts.push(`planType=${encodeURIComponent(params.planType)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.campaignId) parts.push(`campaignId=${encodeURIComponent(params.campaignId)}`);
  if (params.targetWarehouseId)
    parts.push(`targetWarehouseId=${encodeURIComponent(params.targetWarehouseId)}`);
  if (params.priority) parts.push(`priority=${encodeURIComponent(params.priority)}`);
  if (params.periodStart) parts.push(`periodStart=${encodeURIComponent(params.periodStart)}`);
  if (params.periodEnd) parts.push(`periodEnd=${encodeURIComponent(params.periodEnd)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export interface PurchasePlanLineParams {
  skuCode?: string;
  keyword?: string;
  status?: string;
  lockStatus?: string;
  page?: number;
  size?: number;
}

export function purchasePlanLineQuery(params: PurchasePlanLineParams): string {
  const parts: string[] = [];
  if (params.skuCode) parts.push(`skuCode=${encodeURIComponent(params.skuCode)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.lockStatus) parts.push(`lockStatus=${encodeURIComponent(params.lockStatus)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export interface PurchasePlanSummaryParams {
  // `| undefined` 是 `exactOptionalPropertyTypes` 下的必需写法：
  // 调用方常常把「可能没有」的值直接透传（`string | undefined`），
  // 不显式声明 undefined 的话 TS 会拒绝「显式传 undefined」。
  periodStart?: string | undefined;
  periodEnd?: string | undefined;
  targetWarehouseId?: string | undefined;
}

export function purchasePlanSummaryQuery(params: PurchasePlanSummaryParams): string {
  const parts: string[] = [];
  if (params.periodStart) parts.push(`periodStart=${encodeURIComponent(params.periodStart)}`);
  if (params.periodEnd) parts.push(`periodEnd=${encodeURIComponent(params.periodEnd)}`);
  if (params.targetWarehouseId)
    parts.push(`targetWarehouseId=${encodeURIComponent(params.targetWarehouseId)}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export interface PurchasePlanCampaignParams {
  code?: string;
  status?: string;
  shopCode?: string;
  page?: number;
  size?: number;
}

export function purchasePlanCampaignQuery(params: PurchasePlanCampaignParams): string {
  const parts: string[] = [];
  if (params.code) parts.push(`code=${encodeURIComponent(params.code)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.shopCode) parts.push(`shopCode=${encodeURIComponent(params.shopCode)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export interface PurchasePlanLockPolicyParams {
  planType?: string;
  page?: number;
  size?: number;
}

export function purchasePlanLockPolicyQuery(params: PurchasePlanLockPolicyParams): string {
  const parts: string[] = [];
  if (params.planType) parts.push(`planType=${encodeURIComponent(params.planType)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 调用点

export function fetchPurchasePlanCapabilities() {
  return api<PurchasePlanCapabilities>(`${base}/capabilities`);
}

export function fetchPurchasePlanOptions() {
  return api<PurchasePlanOptions>(`${base}/options`);
}

export function fetchPurchasePlanSummary(params: PurchasePlanSummaryParams) {
  return api<PurchasePlanSummary>(`${base}/summary${purchasePlanSummaryQuery(params)}`);
}

export function fetchPurchasePlans(params: PurchasePlanParams) {
  return api<PurchasePlanPage>(`${base}${purchasePlanQuery(params)}`);
}

export function fetchPurchasePlan(id: string) {
  return api<PurchasePlanDetail>(`${base}/${encodeURIComponent(id)}`);
}

export function createPurchasePlan(input: PurchasePlanCreateInput) {
  return api<PurchasePlanRow>(`${base}`, 'POST', input);
}

export function confirmPurchasePlan(id: string) {
  return api<PurchasePlanRow>(`${base}/${encodeURIComponent(id)}/confirm`, 'POST', {});
}

export function aggregatePurchasePlan(id: string, input: PurchasePlanAggregateInput) {
  return api<PurchasePlanAggregateResult>(
    `${base}/${encodeURIComponent(id)}/aggregate`,
    'POST',
    input,
  );
}

export function fetchPurchasePlanLines(planId: string, params: PurchasePlanLineParams) {
  return api<PurchasePlanLinePage>(
    `${base}/${encodeURIComponent(planId)}/lines${purchasePlanLineQuery(params)}`,
  );
}

export function fetchPurchasePlanLine(planId: string, lineId: string) {
  return api<PurchasePlanLineDetail>(
    `${base}/${encodeURIComponent(planId)}/lines/${encodeURIComponent(lineId)}`,
  );
}

export function adjustPurchasePlanLine(
  planId: string,
  lineId: string,
  input: PurchasePlanLineAdjustInput,
) {
  return api<PurchasePlanLineRow>(
    `${base}/${encodeURIComponent(planId)}/lines/${encodeURIComponent(lineId)}/adjust`,
    'POST',
    input,
  );
}

export function lockPurchasePlanLine(planId: string, lineId: string, input: PurchasePlanLockInput) {
  return api<PurchasePlanLockResult>(
    `${base}/${encodeURIComponent(planId)}/lines/${encodeURIComponent(lineId)}/lock`,
    'POST',
    input,
  );
}

/** 导出 CSV 给采购员：走浏览器下载（同源代理原样透传 text/csv）。 */
export async function downloadPurchasePlanCsv(planId: string, planNo: string): Promise<void> {
  const response = await fetch(`/api/trade/${base}/${encodeURIComponent(planId)}/export`, {
    headers: { Accept: 'text/csv' },
    credentials: 'same-origin',
  });
  if (!response.ok) {
    throw new Error(`导出失败（HTTP ${response.status}）`);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `${planNo || planId}.csv`;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

export function fetchPurchasePlanCampaigns(params: PurchasePlanCampaignParams) {
  return api<PurchasePlanCampaignPage>(`${base}/campaigns${purchasePlanCampaignQuery(params)}`);
}

export function createPurchasePlanCampaign(input: PurchasePlanCampaignInput) {
  return api<PurchasePlanCampaignRow>(`${base}/campaigns`, 'POST', input);
}

export function updatePurchasePlanCampaign(id: string, input: PurchasePlanCampaignInput) {
  return api<PurchasePlanCampaignRow>(`${base}/campaigns/${encodeURIComponent(id)}`, 'PUT', input);
}

export function deletePurchasePlanCampaign(id: string) {
  return api<void>(`${base}/campaigns/${encodeURIComponent(id)}`, 'DELETE');
}

export function fetchPurchasePlanLockPolicies(params: PurchasePlanLockPolicyParams) {
  return api<PurchasePlanLockPolicyPage>(
    `${base}/lock-policies${purchasePlanLockPolicyQuery(params)}`,
  );
}

export function upsertPurchasePlanLockPolicy(input: PurchasePlanLockPolicyInput) {
  return api<PurchasePlanLockPolicyPage['rows'][number]>(`${base}/lock-policies`, 'POST', input);
}

// ================================================================ 展示口径

/** 计划类型的标签（与后端 PurchasePlanType 一一对应）。 */
export const purchasePlanTypeLabels: Record<string, string> = {
  ROUTINE: '日常补货',
  CAMPAIGN: '大促备货',
};

export function planTypeLabel(planType: string): string {
  return purchasePlanTypeLabels[planType] ?? planType;
}

/** 计划状态的标签（与后端 PurchasePlanStatus 一一对应）。 */
export const purchasePlanStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  LOCKING: '锁定中',
  LOCKED: '已锁定',
  PARTIALLY_LOCKED: '部分锁定',
  LOCK_FAILED: '锁定失败',
};

export function planStatusLabel(status: string): string {
  return purchasePlanStatusLabels[status] ?? status;
}

/** 计划行状态的标签（与后端 PurchasePlanLineStatus 一一对应）。 */
export const purchasePlanLineStatusLabels: Record<string, string> = {
  PLANNED: '待锁定',
  ADJUSTED: '已调整',
  LOCK_REQUESTED: '已提交锁定请求',
  LOCKED: '已确认锁定',
  LOCK_FAILED: '锁定失败',
};

export function lineStatusLabel(status: string): string {
  return purchasePlanLineStatusLabels[status] ?? status;
}

/** 锁定状态的标签（与后端 PurchasePlanLockStatus 一一对应）。 */
export const purchasePlanLockStatusLabels: Record<string, string> = {
  NOT_REQUESTED: '未请求',
  REQUESTED: '已提交请求',
  CONFIRMED: '已确认',
  FAILED: '锁定失败',
};

export function lockStatusLabel(lockStatus: string): string {
  return purchasePlanLockStatusLabels[lockStatus] ?? lockStatus;
}

/** 优先级的标签（与后端 PurchasePlanPriority 一一对应）。 */
export const purchasePlanPriorityLabels: Record<string, string> = {
  CRITICAL: '紧急',
  HIGH: '高',
  NORMAL: '中',
  LOW: '低',
};

export function priorityLabel(priority: string): string {
  return purchasePlanPriorityLabels[priority] ?? priority;
}

/** 计划周期的标签（与后端 PurchasePlanPeriodType 一一对应）。 */
export const purchasePlanPeriodTypeLabels: Record<string, string> = {
  MONTHLY: '月度',
  QUARTERLY: '季度',
};

export function periodTypeLabel(periodType: string): string {
  return purchasePlanPeriodTypeLabels[periodType] ?? periodType;
}

/** 大促档期状态的标签（与后端 PurchasePlanCampaignStatus 一一对应）。 */
export const purchasePlanCampaignStatusLabels: Record<string, string> = {
  PLANNED: '筹备中',
  ACTIVE: '进行中',
  CLOSED: '已结束',
};

export function campaignStatusLabel(status: string): string {
  return purchasePlanCampaignStatusLabels[status] ?? status;
}

/** 「无数据」的统一文案。null 一律用它 —— 0 会被读成「不用备」。 */
export const NO_DATA_TEXT = '无数据';

/** 数量的展示。null 一律给「无数据」。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return NO_DATA_TEXT;
  const rounded = Number(value.toFixed(6));
  return rounded.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 建议量的展示。null = 上游给不出量，**不是 0**。 */
export function suggestedQuantityText(value: number | null | undefined): string {
  return fmt(value);
}

/** 锁定的视觉分级：失败是危险色，其余中性。 */
export function lockTone(lockStatus: string): 'danger' | 'warning' | 'info' | 'neutral' {
  switch (lockStatus) {
    case 'FAILED':
      return 'danger';
    case 'REQUESTED':
      return 'warning';
    case 'CONFIRMED':
      return 'info';
    default:
      return 'neutral';
  }
}

/** 计划类型决定视觉分级：日常与��促一眼可分。 */
export function planTypeTone(planType: string): 'warning' | 'info' | 'neutral' {
  if (planType === 'CAMPAIGN') return 'warning';
  if (planType === 'ROUTINE') return 'info';
  return 'neutral';
}

/**
 * 这一行能不能锁定。
 *
 * <p>已提交请求或已确认锁定的行**不再**提供锁定入口 —— 预占的数量是按当时的计划量登记的，
 * 重复发起会造出一笔「只发不收」的重复预占。
 */
export function isLockable(lockStatus: string): boolean {
  return lockStatus === 'NOT_REQUESTED' || lockStatus === 'FAILED';
}

/**
 * 这一行能不能调整。
 *
 * <p>已提交或已确认锁定的行**不许**调整：改了计划量却不动预占，账上会出现两个不一致的数。
 */
export function isAdjustable(lockStatus: string): boolean {
  return lockStatus === 'NOT_REQUESTED' || lockStatus === 'FAILED';
}
