import type {
  DevelopmentProjectPage,
  DevelopmentSampleCapabilities,
  DevelopmentSampleConfirmationInput,
  DevelopmentSampleConfirmationRow,
  DevelopmentSampleCostLineInput,
  DevelopmentSampleCostView,
  DevelopmentSampleCreateInput,
  DevelopmentSampleDetail,
  DevelopmentSampleMediaCandidateRow,
  DevelopmentSampleMediaInput,
  DevelopmentSampleMediaRow,
  DevelopmentSampleOptions,
  DevelopmentSampleOrderCreateInput,
  DevelopmentSampleOrderDetail,
  DevelopmentSampleOrderPage,
  DevelopmentSampleOrderRow,
  DevelopmentSampleOrderStatusOption,
  DevelopmentSampleOrderTransitionInput,
  DevelopmentSampleOrderTransitionRow,
  DevelopmentSampleOrderUpdateInput,
  DevelopmentSamplePage,
  DevelopmentSampleRow,
  DevelopmentSampleShipmentInput,
  DevelopmentSampleShipmentRow,
  DevelopmentSampleStatusOption,
  DevelopmentSampleSummary,
  DevelopmentSampleTransitionInput,
  DevelopmentSampleTransitionRow,
  DevelopmentSampleUpdateInput,
  StallPage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.6.1.2 打样与样品管理前端模型。
 *
 * <p>蓝图：「管理打样与样品：样品申请、样品来源（供应商/自有产）、样品物流、确认结果（通过/修改/放弃）、
 * 样品成本；与达人寄样（14.5.1.2）区分打样与营销样品。」输出「打样单 + 样品台账 + 确认记录；样品成本」。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>打样与达人寄样要在界面上分得开</b>：来源下拉只有「供应商打样 / 自有产打样 / 档口打样」，
 *       用途只有「开发打样」一个值。页面**不提供「其它」**——留一个「其它」桶，
 *       营销寄样就会被塞进来，营销费用会污染开发成本。</li>
 *   <li><b>「未进费用归集」就写「未进费用归集」</b>：12 费用（finance 服务）不在本仓，
 *       页面显示「尚未进费用归集（归 12 费用管）」，**不显示「已入账」**，
 *       也**不把不同币种的金额加成一个总数**。</li>
 *   <li><b>物流轨迹取不到就说取不到</b>：本模块只记物流快照，
 *       页面显示「轨迹不可查（归 10 物流管）」，不假装已接通物流平台。</li>
 *   <li><b>状态只显示后端给的合法去向</b>：下拉里的目标状态来自 {@code allowedTargets}，
 *       页面不自己维护一份状态机。</li>
 *   <li><b>确认结果走确认端点</b>：页面把「通过 / 修改 / 放弃」放在确认表单里，
 *       「修改」必填修改意见；制作进度（打样中 / 已发出 / 已到样）走另一个入口。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const base = 'trade/development-samples';

// ================================================================ 枚举（本地字面量联合）
//
// 🔴 契约里 enum 被 codegen **内联**成字符串联合（`"SUPPLIER" | "OWN_FACTORY" | "STALL"`），
// 不会导出成具名类型。所以这几个联合在这里**显式声明**，
// 而不是从 api-contracts 里 import 一个并不存在的名字。

/** 样品来源：供应商打样 / 自有产打样 / 档口打样。**没有「其它」**。 */
export type DevelopmentSampleSourceType = 'SUPPLIER' | 'OWN_FACTORY' | 'STALL';

/**
 * 样品用途：**只有开发打样一个值**。
 *
 * 🔴 达人寄样（14.5.1.2）是营销样品，属 14 卷，不在本模块 ——
 * 词表里**没有它，也没有「其它」**。
 */
export type DevelopmentSampleUsageType = 'DEVELOPMENT';

/** 打样单状态。 */
export type DevelopmentSampleOrderStatus =
  | 'REQUESTED'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CLOSED'
  | 'CANCELLED';

/** 样品状态。 */
export type DevelopmentSampleStatus =
  | 'REQUESTED'
  | 'MAKING'
  | 'SHIPPED'
  | 'RECEIVED'
  | 'CONFIRMED'
  | 'REVISING'
  | 'ABANDONED';

/** 确认结果：通过 / 修改 / 放弃。 */
export type DevelopmentSampleConfirmationResult = 'PASSED' | 'MODIFY' | 'ABANDONED';

/** 成本类型（封闭五值，无「其它」）。 */
export type DevelopmentSampleCostType =
  | 'SAMPLE_FEE'
  | 'MATERIAL'
  | 'TOOLING'
  | 'FREIGHT'
  | 'INSPECTION';

/** 物流状态（人工登记的快照口径）。 */
export type DevelopmentSampleLogisticsStatus =
  | 'SHIPPED'
  | 'IN_TRANSIT'
  | 'DELIVERED'
  | 'EXCEPTION'
  | 'RETURNED';

// ================================================================ 查询参数

export interface DevelopmentSampleOrderParams {
  status?: string;
  sourceType?: string;
  developmentProjectId?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface DevelopmentSampleLedgerParams {
  status?: string;
  sourceType?: string;
  developmentProjectId?: string;
  orderId?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export function developmentSampleOrderQuery(params: DevelopmentSampleOrderParams): string {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.sourceType) parts.push(`sourceType=${encodeURIComponent(params.sourceType)}`);
  if (params.developmentProjectId)
    parts.push(`developmentProjectId=${encodeURIComponent(params.developmentProjectId)}`);
  if (params.keyword?.trim())
    parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function developmentSampleLedgerQuery(params: DevelopmentSampleLedgerParams): string {
  const parts: string[] = [];
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.sourceType) parts.push(`sourceType=${encodeURIComponent(params.sourceType)}`);
  if (params.developmentProjectId)
    parts.push(`developmentProjectId=${encodeURIComponent(params.developmentProjectId)}`);
  if (params.orderId) parts.push(`orderId=${encodeURIComponent(params.orderId)}`);
  if (params.keyword?.trim())
    parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 媒体候选的查询串。 */
export function developmentSampleMediaQuery(keyword?: string): string {
  return keyword?.trim() ? `?keyword=${encodeURIComponent(keyword.trim())}` : '';
}

// ================================================================ 调用点

export function fetchDevelopmentSampleCapabilities() {
  return api<DevelopmentSampleCapabilities>(`${base}/capabilities`);
}

export function fetchDevelopmentSampleOptions() {
  return api<DevelopmentSampleOptions>(`${base}/options`);
}

export function fetchDevelopmentSampleSummary() {
  return api<DevelopmentSampleSummary>(`${base}/summary`);
}

export function fetchDevelopmentSampleOrders(params: DevelopmentSampleOrderParams) {
  return api<DevelopmentSampleOrderPage>(`${base}${developmentSampleOrderQuery(params)}`);
}

export function fetchDevelopmentSampleLedger(params: DevelopmentSampleLedgerParams) {
  return api<DevelopmentSamplePage>(`${base}/samples${developmentSampleLedgerQuery(params)}`);
}

export function createDevelopmentSampleOrder(input: DevelopmentSampleOrderCreateInput) {
  return api<DevelopmentSampleOrderDetail>(`${base}`, 'POST', input);
}

export function fetchDevelopmentSampleOrder(orderId: string) {
  return api<DevelopmentSampleOrderDetail>(`${base}/${encodeURIComponent(orderId)}`);
}

export function updateDevelopmentSampleOrder(
  orderId: string,
  input: DevelopmentSampleOrderUpdateInput,
) {
  return api<DevelopmentSampleOrderDetail>(
    `${base}/${encodeURIComponent(orderId)}`,
    'PUT',
    input,
  );
}

export function transitionDevelopmentSampleOrder(
  orderId: string,
  input: DevelopmentSampleOrderTransitionInput,
) {
  return api<DevelopmentSampleOrderDetail>(
    `${base}/${encodeURIComponent(orderId)}/transitions`,
    'POST',
    input,
  );
}

export function createDevelopmentSample(orderId: string, input: DevelopmentSampleCreateInput) {
  return api<DevelopmentSampleDetail>(
    `${base}/${encodeURIComponent(orderId)}/samples`,
    'POST',
    input,
  );
}

export function fetchDevelopmentSample(orderId: string, sampleId: string) {
  return api<DevelopmentSampleDetail>(
    `${base}/${encodeURIComponent(orderId)}/samples/${encodeURIComponent(sampleId)}`,
  );
}

export function updateDevelopmentSample(
  orderId: string,
  sampleId: string,
  input: DevelopmentSampleUpdateInput,
) {
  return api<DevelopmentSampleDetail>(
    `${base}/${encodeURIComponent(orderId)}/samples/${encodeURIComponent(sampleId)}`,
    'PUT',
    input,
  );
}

export function transitionDevelopmentSample(
  orderId: string,
  sampleId: string,
  input: DevelopmentSampleTransitionInput,
) {
  return api<DevelopmentSampleDetail>(
    `${base}/${encodeURIComponent(orderId)}/samples/${encodeURIComponent(sampleId)}/transitions`,
    'POST',
    input,
  );
}

export function fetchDevelopmentSampleConfirmations(orderId: string, sampleId: string) {
  return api<DevelopmentSampleConfirmationRow[]>(
    `${base}/${encodeURIComponent(orderId)}/samples/${encodeURIComponent(sampleId)}/confirmations`,
  );
}

export function confirmDevelopmentSample(
  orderId: string,
  sampleId: string,
  input: DevelopmentSampleConfirmationInput,
) {
  return api<DevelopmentSampleDetail>(
    `${base}/${encodeURIComponent(orderId)}/samples/${encodeURIComponent(sampleId)}/confirmations`,
    'POST',
    input,
  );
}

export function fetchDevelopmentSampleShipments(orderId: string, sampleId: string) {
  return api<DevelopmentSampleShipmentRow[]>(
    `${base}/${encodeURIComponent(orderId)}/samples/${encodeURIComponent(sampleId)}/shipments`,
  );
}

export function createDevelopmentSampleShipment(
  orderId: string,
  sampleId: string,
  input: DevelopmentSampleShipmentInput,
) {
  return api<DevelopmentSampleShipmentRow[]>(
    `${base}/${encodeURIComponent(orderId)}/samples/${encodeURIComponent(sampleId)}/shipments`,
    'POST',
    input,
  );
}

export function fetchDevelopmentSampleMedia(orderId: string, sampleId: string) {
  return api<DevelopmentSampleMediaRow[]>(
    `${base}/${encodeURIComponent(orderId)}/samples/${encodeURIComponent(sampleId)}/media`,
  );
}

export function createDevelopmentSampleMedia(
  orderId: string,
  sampleId: string,
  input: DevelopmentSampleMediaInput,
) {
  return api<DevelopmentSampleMediaRow[]>(
    `${base}/${encodeURIComponent(orderId)}/samples/${encodeURIComponent(sampleId)}/media`,
    'POST',
    input,
  );
}

export function fetchDevelopmentSampleMediaCandidates(keyword?: string) {
  return api<DevelopmentSampleMediaCandidateRow[]>(
    `${base}/media-candidates${developmentSampleMediaQuery(keyword)}`,
  );
}

export function fetchDevelopmentSampleCost(orderId: string) {
  return api<DevelopmentSampleCostView>(`${base}/${encodeURIComponent(orderId)}/cost`);
}

export function addDevelopmentSampleCostLine(
  orderId: string,
  input: DevelopmentSampleCostLineInput,
) {
  return api<DevelopmentSampleCostView>(
    `${base}/${encodeURIComponent(orderId)}/cost/lines`,
    'POST',
    input,
  );
}

export function deleteDevelopmentSampleCostLine(orderId: string, lineId: string) {
  return api<DevelopmentSampleCostView>(
    `${base}/${encodeURIComponent(orderId)}/cost/lines/${encodeURIComponent(lineId)}`,
    'DELETE',
  );
}

// ================================================================ 参照下拉（上游）

/**
 * 开发立项候选（6.6.1.1，同服务已交付）。
 *
 * 🔴 打样单**必须**挂在开发立项上 ⇒ 建单表单要给一个真实的立项下拉，
 *    而不是让运营去别处复制一个 UUID 粘进来。
 */
export function fetchDevelopmentSampleProjectOptions(keyword?: string) {
  const parts = ['size=100'];
  if (keyword?.trim()) parts.push(`keyword=${encodeURIComponent(keyword.trim())}`);
  return api<DevelopmentProjectPage>(`trade/development-projects?${parts.join('&')}`);
}

/** 供应商候选（catalog 6.1.1.x，跨服务只读）。 */
export interface DevelopmentSampleVendorOption {
  roleId: string;
  partyCode: string;
  partyName: string;
  partyStatus: string;
}

/** 供应商候选页（只声明本模块用到的字段）。 */
export interface DevelopmentSampleVendorPage {
  items: DevelopmentSampleVendorOption[];
  total: number;
  page: number;
  size: number;
}

export function fetchDevelopmentSampleVendorOptions(keyword?: string) {
  const parts = ['page=0', 'size=100'];
  if (keyword?.trim()) parts.push(`keyword=${encodeURIComponent(keyword.trim())}`);
  return api<DevelopmentSampleVendorPage>(`catalog/supply/vendors?${parts.join('&')}`);
}

/** 档口候选（6.1.2.1，同服务已交付）。 */
export function fetchDevelopmentSampleStallOptions(keyword?: string) {
  const parts = ['page=0', 'size=100'];
  if (keyword?.trim()) parts.push(`keyword=${encodeURIComponent(keyword.trim())}`);
  return api<StallPage>(`trade/sources/stalls?${parts.join('&')}`);
}

// ================================================================ 展示格式化

/** 金额：null 显示「无数据」，**绝不显示 0**。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return '无数据';
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 4 });
}

/** 带币种的金额。币种为空时只显示数字（不补一个猜出来的币种）。 */
export function money(value: number | null | undefined, currency?: string | null): string {
  if (value === null || value === undefined) return '无数据';
  const text = value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 });
  return currency ? `${text} ${currency}` : text;
}

/** 时间显示：null 显示「未安排」。 */
export function timeText(value: string | null | undefined): string {
  if (!value) return '未安排';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toISOString().slice(0, 16).replace('T', ' ');
}

/** 打样单状态的色调（与 @starcon/ui 的 Badge tone 对齐）。 */
export function orderStatusTone(
  status: string,
): 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'REQUESTED':
      return 'neutral';
    case 'IN_PROGRESS':
      return 'primary';
    case 'COMPLETED':
      return 'success';
    case 'CLOSED':
      return 'info';
    case 'CANCELLED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 样品状态的色调。 */
export function sampleStatusTone(
  status: string,
): 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'REQUESTED':
      return 'neutral';
    case 'MAKING':
      return 'primary';
    case 'SHIPPED':
      return 'info';
    case 'RECEIVED':
      return 'warning';
    case 'CONFIRMED':
      return 'success';
    case 'REVISING':
      return 'warning';
    case 'ABANDONED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 确认结果的色调。 */
export function confirmationTone(
  result: string,
): 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  switch (result) {
    case 'PASSED':
      return 'success';
    case 'MODIFY':
      return 'warning';
    case 'ABANDONED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/**
 * 样品成本的「是否已入账」文案。
 *
 * 🔴 12 费用（finance 服务）不在本仓 ⇒ 恒为「尚未进费用归集」。
 * 页面**不显示「已入账」**，也不自己判断 —— 后端给什么就显示什么。
 */
export function expensePostedText(posted: boolean, ownerModule: string): string {
  if (posted) return '已进费用归集';
  return `尚未进费用归集（归 ${ownerModule} 管）`;
}

/**
 * 物流轨迹的文案。
 *
 * 🔴 本模块只记物流快照，真实轨迹取不到 ⇒ 显示「轨迹不可查（归 10 物流管）」。
 */
export function trackingText(available: boolean, ownerModule: string): string {
  if (available) return '可查物流轨迹';
  return `轨迹不可查（归 ${ownerModule} 管）`;
}

/**
 * 样品的合法目标状态（来自后端的 allowedTargets，页面不自己维护状态机）。
 *
 * @param options 后端下拉
 * @param current 当前状态
 */
export function allowedOrderTargetOptions(
  options: DevelopmentSampleOptions | null,
  current: string,
): DevelopmentSampleOrderStatusOption[] {
  const found = options?.orderStatuses.find((item) => item.value === current);
  if (!found) return [];
  return options?.orderStatuses.filter((item) => found.allowedTargets.includes(item.value)) ?? [];
}

/** 样品的合法目标状态。 */
export function allowedSampleTargetOptions(
  options: DevelopmentSampleOptions | null,
  current: string,
): DevelopmentSampleStatusOption[] {
  const found = options?.sampleStatuses.find((item) => item.value === current);
  if (!found) return [];
  return options?.sampleStatuses.filter((item) => found.allowedTargets.includes(item.value)) ?? [];
}

/**
 * 这个目标状态是不是**确认类**（通过 / 修改 / 放弃）。
 *
 * 🔴 确认类状态必须走确认端点留一条不可改写的确认记录，不能当作制作进度推进 ——
 * 页面据此把「推进进度」与「确认结果」分成两个入口。
 */
export function isConfirmationTarget(target: string): boolean {
  return target === 'CONFIRMED' || target === 'REVISING' || target === 'ABANDONED';
}

/** 目标状态对应的确认结果（与后端同构）。 */
export function confirmationResultForTarget(target: string): DevelopmentSampleConfirmationResult {
  if (target === 'CONFIRMED') return 'PASSED';
  if (target === 'REVISING') return 'MODIFY';
  return 'ABANDONED';
}

/**
 * 「修改」结果必须带修改意见。
 *
 * 🔴 只说「要改」不说改什么，下一轮打样的人还是得猜 —— 页面据此**禁用**提交。
 */
export function confirmationNeedsIssueNote(result: string): boolean {
  return result === 'MODIFY';
}

/**
 * 能力缺口摘要（页面顶部常驻）。
 *
 * 🔴 哪一件事做不到必须由页面自己说清楚：进费用归集（12 费用）、
 * 真实物流轨迹（10 物流）、试产衔接（6.6.2.1）三件都做不到。
 */
export function capabilityGaps(caps: DevelopmentSampleCapabilities | null): string[] {
  if (!caps) return [];
  const gaps: string[] = [];
  if (!caps.expenseAggregationAvailable)
    gaps.push(`样品成本${caps.expenseAggregationOwnerModule}归集：${caps.expenseAggregationNote}`);
  if (!caps.logisticsTrackingAvailable)
    gaps.push(`真实物流轨迹（${caps.logisticsTrackingOwnerModule}）：${caps.logisticsTrackingNote}`);
  if (!caps.pilotOrderAvailable)
    gaps.push(`试产衔接（${caps.pilotOrderOwnerModule}）：${caps.pilotOrderNote}`);
  if (!caps.marketingSampleDelivered)
    gaps.push(`达人寄样（${caps.marketingSampleOwnerModule}）不归本模块：${caps.marketingSampleNote}`);
  return gaps;
}

/** 成本合计的显示：不同币种绝不相加 ⇒ 逐币种列出。 */
export function costByCurrencyText(
  rows: { currency: string; totalAmount: number; orderCount: number }[],
): string {
  if (!rows.length) return '暂无成本';
  return rows
    .map((row) => `${money(row.totalAmount, row.currency)}（${row.orderCount} 张单）`)
    .join(' / ');
}

/** 样品确认记录的一行摘要。 */
export function confirmationSummary(row: DevelopmentSampleConfirmationRow): string {
  // 🔴 局部变量**不能**叫 base：模块级的 base 是后端路径前缀，
  //    同名遮蔽会让「从源码反向抽取真实调用点」的白名单守卫抽到假形状。
  const headline = `第 ${row.confirmSeq} 次确认 · ${row.resultLabel} · ${timeText(row.confirmedAt)}`;
  return row.issueNote ? `${headline} · ${row.issueNote}` : headline;
}

/** 迁移事件的一行摘要。 */
export function transitionSummary(row: DevelopmentSampleTransitionRow): string {
  return `${row.fromStatusLabel} → ${row.toStatusLabel} · ${timeText(row.occurredAt)} · ${row.reason}`;
}

/** 打样单迁移事件的一行摘要。 */
export function orderTransitionSummary(row: DevelopmentSampleOrderTransitionRow): string {
  return `${row.fromStatusLabel} → ${row.toStatusLabel} · ${timeText(row.occurredAt)} · ${row.reason}`;
}

/** 成本明细行的一行摘要。 */
export function costLineSummary(row: {
  costTypeLabel: string;
  amount: number;
  currency: string;
  description: string;
  incurredAt: string;
}): string {
  const desc = row.description ? ` · ${row.description}` : '';
  return `${row.costTypeLabel} ${money(row.amount, row.currency)}${desc} · ${timeText(row.incurredAt)}`;
}
