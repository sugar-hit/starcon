import type {
  FactoryInput,
  FactoryPage,
  FactorySaved,
  InboundRequestView,
  IssueInput,
  ProcessingCapabilities,
  ProcessingComponentLine,
  ProcessingOrderDetail,
  ProcessingOrderInput,
  ProcessingOrderPage,
  ProcessingOrderSaved,
  ProcessingProgressInput,
  ProcessingStatusInput,
  RequirementPreview,
  RequirementPreviewInput,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from '../../lib/api';

/**
 * 6.4.1.1 加工单前端模型。
 *
 * <p>蓝图：「发起委外/自有工厂加工：选择成品 SKU 与 BOM 版本、指定组件领用量与加工厂、
 * 约定完工数量与交期，跟踪加工进度与入库。」输出「加工单（组件领用记录 + 完工数量）；
 * 入库需求通知 8」。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>组件领用量不是让人手填的</b>：新建时只填 BOM 版本 + 约定完工数量，
 *       组件与需求量由后端按 catalog 3.5.2.1 的规则 R7 算出并冻结。
 *       页面在提交前先 {@link previewRequirement} 试算一遍并显示，
 *       让操作员在下单**之前**看到「按这一版 BOM，每个组件要多少」——
 *       而不是下完单才知道料不对。
 *   <li><b>进度不是一个状态字段</b>：每个组件的「已领 / 需求 / 还欠」三个数并排显示，
 *       差额就是真正要盯的东西。
 *   <li><b>完工数量与实收数量分开</b>：报完工 ≠ 货已进仓，两列两处显示。
 *   <li><b>未交付的下游直接禁用并写明归属模块</b>，而不是给一个点下去 404 的按钮。
 *   <li>加载 / 空 / 错误三态齐全。
 * </ul>
 */

const base = 'trade/processing';

export interface ProcessingOrderParams {
  factoryId?: string;
  outputSkuId?: string;
  status?: string;
  keyword?: string;
  overdueOnly?: boolean;
  page?: number;
  size?: number;
}

export function processingOrderQuery(params: ProcessingOrderParams): string {
  const parts: string[] = [];
  if (params.factoryId) parts.push(`factoryId=${encodeURIComponent(params.factoryId)}`);
  if (params.outputSkuId) parts.push(`outputSkuId=${encodeURIComponent(params.outputSkuId)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.overdueOnly) parts.push('overdueOnly=true');
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchCapabilities() {
  return api<ProcessingCapabilities>(`${base}/capabilities`);
}

export function fetchOrders(params: ProcessingOrderParams) {
  return api<ProcessingOrderPage>(`${base}/orders${processingOrderQuery(params)}`);
}

export function fetchOrder(orderId: string) {
  return api<ProcessingOrderDetail>(`${base}/orders/${encodeURIComponent(orderId)}`);
}

export function createOrder(input: ProcessingOrderInput) {
  return api<ProcessingOrderSaved>(`${base}/orders`, 'POST', input);
}

export function advanceStatus(orderId: string, input: ProcessingStatusInput) {
  return api<ProcessingOrderSaved>(
    `${base}/orders/${encodeURIComponent(orderId)}/status`,
    'POST',
    input,
  );
}

export function registerProgress(orderId: string, input: ProcessingProgressInput) {
  return api<ProcessingOrderSaved>(
    `${base}/orders/${encodeURIComponent(orderId)}/progress`,
    'POST',
    input,
  );
}

/** 登记组件领用进度（记「已领多少」，**不改库存**）。 */
export function issueComponent(orderId: string, input: IssueInput) {
  return api<ProcessingOrderSaved>(
    `${base}/orders/${encodeURIComponent(orderId)}/components/issue`,
    'POST',
    input,
  );
}

/** 按 BOM 版本试算组件需求量（catalog 规则 R7；只读，不落库）。 */
export function previewRequirement(input: RequirementPreviewInput) {
  return api<RequirementPreview>(`${base}/requirements`, 'POST', input);
}

export interface FactoryParams {
  factoryKind?: string;
  status?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export function fetchFactories(params: FactoryParams) {
  const parts: string[] = [];
  if (params.factoryKind) parts.push(`factoryKind=${encodeURIComponent(params.factoryKind)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return api<FactoryPage>(`${base}/factories${parts.length ? `?${parts.join('&')}` : ''}`);
}

export function saveFactory(input: FactoryInput) {
  return api<FactorySaved>(`${base}/factories`, 'POST', input);
}

export function updateFactory(factoryId: string, input: FactoryInput) {
  return api<FactorySaved>(`${base}/factories/${encodeURIComponent(factoryId)}`, 'PUT', input);
}

// ================================================================ 展示口径

/** 加工单状态标签（与后端 ProcessingStatus 一一对应）。 */
export const processingStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  RELEASED: '已下达',
  IN_PROCESS: '加工中',
  COMPLETED: '已完工',
  RECEIVED: '已入库',
  CANCELLED: '已作废',
};

/** 入库需求通知状态标签（与后端 InboundRequestStatus 一一对应）。 */
export const inboundRequestStatusLabels: Record<string, string> = {
  PENDING: '待入库',
  PARTIAL: '部分入库',
  RECEIVED: '已入库',
  CANCELLED: '已作废',
};

export const factoryKindLabels: Record<string, string> = {
  OUTSOURCED: '委外工厂',
  IN_HOUSE: '自有工厂',
};

export const factoryStatusLabels: Record<string, string> = {
  ACTIVE: '启用',
  SUSPENDED: '停用',
};

export function statusLabel(status: string): string {
  return processingStatusLabels[status] ?? status;
}

export function inboundStatusLabel(status: string): string {
  return inboundRequestStatusLabels[status] ?? status;
}

/**
 * 状态徽章的视觉分级。
 *
 * <p>草稿/已下达是「还没动」，加工中与已完工是「在途」，已入库与已作废是终态 ——
 * 三档颜色让列表一眼能分出「哪些还欠着」。
 */
export function statusTone(status: string): 'neutral' | 'active' | 'done' | 'danger' {
  switch (status) {
    case 'RELEASED':
    case 'IN_PROCESS':
      return 'active';
    case 'COMPLETED':
    case 'RECEIVED':
      return 'done';
    case 'CANCELLED':
      return 'danger';
    default:
      return 'neutral';
  }
}

/** 组件领用进度是否领满（差额为 0）。 */
export function isFullyIssued(line: ProcessingComponentLine): boolean {
  return Number(line.outstandingQuantity) <= 0;
}

/** 还欠料（未领满）的组件条数 —— 这是进度页真正要盯的数字。 */
export function outstandingCount(lines: ProcessingComponentLine[]): number {
  return lines.filter((line) => !isFullyIssued(line)).length;
}

/**
 * 组件领用进度文案。
 *
 * <p>「已领 / 需求（还欠）」三个数并排，让「这批料为什么是这个数」在界面上自解释：
 * 已领 + 欠 = 需求，与 BOM 规则 R7 算出的需求量对得上。
 */
export function issueProgressText(line: ProcessingComponentLine): string {
  return `已领 ${fmt(line.issuedQuantity)} / 需求 ${fmt(line.requiredQuantity)}（还欠 ${fmt(
    line.outstandingQuantity,
  )}）`;
}

/**
 * 完工数量与实收数量的差异提示。
 *
 * <p>报完工不等于货已进仓：报 10 实收 8 时，仓库账上少 2 件，界面必须把这个差额
 * 摆在明面上，而不是合成一个「已完成」。
 */
export function receiptGapText(
  order: ProcessingOrderSaved,
  request: InboundRequestView | null,
): string | null {
  if (!request) return null;
  const gap = Number(request.outstandingQuantity);
  if (gap <= 0) return null;
  return `还有 ${fmt(request.outstandingQuantity)} 件未入库（待入库 ${fmt(
    request.requestedQuantity,
  )}，实收 ${fmt(request.receivedQuantity)}）`;
}

/** 交期是否已过且尚未入库 / 未作废。 */
export function isOverdue(order: ProcessingOrderSaved): boolean {
  if (order.status === 'RECEIVED' || order.status === 'CANCELLED') return false;
  return new Date(order.dueOn).getTime() < startOfToday();
}

function startOfToday(): number {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
}

/**
 * 当前状态下可执行的动作。
 *
 * <p>「已入库」与「已作废」是终态，一个动作都不给 —— 与后端 canMoveTo 同源，
 * 不给点下去注定 409 的按钮。
 */
export function statusActions(status: string): Array<{ status: string; label: string }> {
  switch (status) {
    case 'DRAFT':
      return [
        { status: 'RELEASED', label: '下达' },
        { status: 'CANCELLED', label: '作废' },
      ];
    case 'RELEASED':
      return [
        { status: 'IN_PROCESS', label: '登记开工' },
        { status: 'CANCELLED', label: '作废' },
      ];
    case 'IN_PROCESS':
      return [
        { status: 'COMPLETED', label: '报完工' },
        { status: 'CANCELLED', label: '作废' },
      ];
    case 'COMPLETED':
      return [{ status: 'RECEIVED', label: '登记入库' }];
    default:
      return [];
  }
}

/** 该状态推进是否需要填数量（完工报数、入库实收）。 */
export function needsQuantity(status: string): boolean {
  return status === 'COMPLETED' || status === 'RECEIVED';
}

/** 未交付下游的说明文案（禁用按钮时写清归属模块，而不是让用户猜）。 */
export function downstreamGapText(moduleId: string, available: boolean, effect: string): string {
  if (available) return '';
  return `${moduleId} 尚未交付：${effect}`;
}

/** 数量格式化：去掉无意义的尾零，并把 6 位小数的零头也显示出来。 */
export function fmt(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  const rounded = Number(value.toFixed(6));
  return rounded.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 时间格式化（timestamptz 带偏移的 ISO 串）。 */
export function moment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** 交期格式化（后端返回 yyyy-MM-dd）。 */
export function dateOnly(value: string | null | undefined): string {
  return value ?? '—';
}
