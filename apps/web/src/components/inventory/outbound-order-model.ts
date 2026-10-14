import type {
  OutboundBulkFailure,
  OutboundCancelInput,
  OutboundCancelResult,
  OutboundEnumOption,
  OutboundOrderCapabilities,
  OutboundOrderCreateInput,
  OutboundOrderPage,
  OutboundOrderSummary,
  OutboundPickRequestInput,
  OutboundPickRequestResult,
  OutboundPrintInput,
  OutboundPrintResult,
  OutboundTransitionInput,
  OutboundTransitionView,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 8.3.1.1 出库单列表前端模型。
 *
 * <p>界面上最容易出错、本模块显式处理了几处：
 *
 * <ul>
 *   <li>**出库单 ≠ 销售订单**。一订单可多出库单（分仓/分批），所以列表里的外部单号
 *       会重复。看到同一个外部单号出现多行时，那不是脏数据，是分仓/分批。
 *   <li>**件数与行数不是一个数**。行数是 SKU 条数，件数是数量合计，
 *       两者都在服务端现算，界面上**不要再乘一遍**。
 *   <li>**「生成拣货任务」不等于拣货已经发生**。点了只是把单子交给 8.3.2.1，
 *       界面上不能显示成「已拣货」，否则作业员会以为货已经离库位。
 *   <li>**终态要看得见**。已发货/已取消的单不能再操作，按钮必须真的禁用，
 *       而不是点了以后靠一个 409 报错来告诉用户。
 *   <li>**批量操作可能部分成功**。逐单结果必须分开显示：哪些成功了、哪些没成、
 *       没成的**为什么**没成。一句「操作完成」在这个界面上是危险的。
 * </ul>
 */

export interface OutboundListParams {
  warehouseIds?: string[] | undefined;
  sourceTypes?: string[] | undefined;
  statuses?: string[] | undefined;
  createdFrom?: string | undefined;
  createdTo?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 时间筛选必须带偏移量：后端拒绝裸本地时间串，这里也不该发出那种请求。 */
export function offsetIso(value: Date | undefined): string | undefined {
  if (!value) return undefined;
  // toISOString() 带 Z（UTC 偏移），满足后端「带时区的 ISO-8601」要求。
  return value.toISOString();
}

function query(params: OutboundListParams): string {
  const search = new URLSearchParams();
  const push = (key: string, values?: string[]) => {
    if (!values?.length) return;
    for (const v of values) search.append(key, v);
  };
  push('warehouseIds', params.warehouseIds);
  push('sourceTypes', params.sourceTypes);
  push('statuses', params.statuses);
  if (params.createdFrom) search.set('createdFrom', params.createdFrom);
  if (params.createdTo) search.set('createdTo', params.createdTo);
  if (params.keyword) search.set('keyword', params.keyword);
  if (params.page !== undefined) search.set('page', String(params.page));
  if (params.size !== undefined) search.set('size', String(params.size));
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ------------------------------------------------------------ 接口

const base = 'warehouse/inventory/outbound-orders';

export function fetchOutboundCapabilities(): Promise<OutboundOrderCapabilities> {
  return api<OutboundOrderCapabilities>(`${base}/capabilities`);
}

export function fetchOutboundOrders(params: OutboundListParams): Promise<OutboundOrderPage> {
  return api<OutboundOrderPage>(`${base}${query(params)}`);
}

export function createOutboundOrder(input: OutboundOrderCreateInput): Promise<OutboundOrderSummary> {
  return api<OutboundOrderSummary>(base, 'POST', input);
}

export function requestPicking(
  orderIds: string[],
  note?: string,
): Promise<OutboundPickRequestResult> {
  const body: OutboundPickRequestInput = { orderIds, note: note ?? null };
  return api<OutboundPickRequestResult>(`${base}/pick-requests`, 'POST', body);
}

export function printOutboundOrders(
  orderIds: string[],
  document: string,
): Promise<OutboundPrintResult> {
  const body: OutboundPrintInput = { orderIds, document };
  return api<OutboundPrintResult>(`${base}/print`, 'POST', body);
}

export function cancelOutboundOrders(orderIds: string[], reason: string): Promise<OutboundCancelResult> {
  const body: OutboundCancelInput = { orderIds, reason };
  return api<OutboundCancelResult>(`${base}/cancel`, 'POST', body);
}

export function transitionOutboundOrder(
  orderId: string,
  targetStatus: string,
  note?: string,
): Promise<OutboundOrderSummary> {
  const body: OutboundTransitionInput = { targetStatus, note: note ?? null };
  return api<OutboundOrderSummary>(`${base}/${orderId}/transition`, 'POST', body);
}

// ------------------------------------------------------------ 展示口径

const TERMINAL = new Set(['SHIPPED', 'CANCELLED']);

/** 终态（已发货 / 已取消）不能生成拣货任务。 */
export function isTerminal(order: { status: string }): boolean {
  return TERMINAL.has(order.status);
}

/** 只有待拣能生成拣货任务；已经交接过的不行（一张单只交接一次）。 */
export function canRequestPicking(order: OutboundOrderSummary): boolean {
  return order.status === 'PENDING_PICKING' && !order.pickRequested;
}

/** 取消对所有非终态都开放（能否取消交给后端状态机判定，界面不自己猜一套规则）。 */
export function canCancel(order: { status: string }): boolean {
  return !isTerminal(order);
}

/**
 * 件数展示：保留六位小数口径但去掉多余的尾随零。
 *
 * <p>后端按 numeric(19,6) 返回（禁浮点），所以「5.500000」与「5.5」是同一个数。
 * 直接把 5.500000 显示出来会让作业员以为系统存了个奇怪的小数。
 */
export function pieceText(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const text = String(value);
  if (!text.includes('.')) return text;
  const trimmed = text.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed === '' || trimmed === '-' ? '0' : trimmed;
}

/** 行数与件数拼成一句话，避免表格里两列含义被混读。 */
export function volumeText(order: OutboundOrderSummary): string {
  return `${order.skuLineCount} 个 SKU / ${pieceText(order.totalPieceCount)} 件`;
}

/**
 * 来源展示：调拨要显示调拨单号（真外键），其余显示外部单号（字符串）。
 *
 * <p>把两者混成同一个「单号」列会让人以为它们可以互相查找 —— 它们一个可跳转、
 * 一个不可。
 */
export function sourceText(order: OutboundOrderSummary): string {
  if (order.sourceType === 'TRANSFER') {
    return order.sourceTransferCode ?? `调拨单 ${order.sourceTransferOrderId ?? '未知'}`;
  }
  return order.sourceExternalRef ?? '—';
}

/** 按 (from,to) 找一条状态跃迁说明；找不到说明后端矩阵变了，界面要显示出来而不是静默。 */
export function transitionOf(
  capabilities: OutboundOrderCapabilities | null,
  from: string,
  to: string,
): OutboundTransitionView | undefined {
  return capabilities?.transitions.find((t) => t.from === from && t.to === to);
}

/** 某张单当前可去的全部去向，供状态推进下拉渲染。 */
export function nextStatuses(
  capabilities: OutboundOrderCapabilities | null,
  from: string,
): OutboundTransitionView[] {
  return (capabilities?.transitions ?? []).filter((t) => t.from === from);
}

/** 该跃迁是否强制要求说明；界面据此在提交前就禁用按钮，而不是提交后吃一个 400。 */
export function reasonRequired(
  capabilities: OutboundOrderCapabilities | null,
  from: string,
  to: string,
): boolean {
  return transitionOf(capabilities, from, to)?.reasonRequired ?? false;
}

/** 承运渠道提示：已发货必须先有承运商，界面上提前说明比提交后报错友好。 */
export function carrierWarning(order: OutboundOrderSummary): string | null {
  if (!order.carrierCode.trim()) return '这张单还没有承运渠道，发货前必须先指定。';
  return null;
}

// ------------------------------------------------------------ 选项与状态

export function optionLabel(options: OutboundEnumOption[], name: string): string {
  return options.find((o) => o.name === name)?.label ?? name;
}

/** 承运渠道缺省提示用：库里没有承运商字典（那是物流服务的表），这里如实说明。 */
export const CARRIER_HINT =
  '承运渠道是出库单上登记的自由文本，承运商字典归物流服务，本模块不复制一份。';

export function failureText(failure: OutboundBulkFailure): string {
  return failure.orderCode ? `${failure.orderCode}：${failure.reason}` : failure.reason;
}

export function failureList(failures: OutboundBulkFailure[]): string[] {
  return failures.map(failureText);
}

/** 把后端的中文错误翻成界面能直接显示的一句话；取不到就如实回退，不编造。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '出库单工作台暂时不可用，请稍后重试。';
}

/** 分页摘要：把「共 N 条 / 第 p 页」说清楚，避免把 total 画成「0 条」误导。 */
export function pageSummary(page: OutboundOrderPage): string {
  if (page.total === 0) return '共 0 条';
  const from = page.page * page.size + 1;
  const to = Math.min(page.total, from + page.items.length - 1);
  return `共 ${page.total} 条 · 第 ${page.page + 1} 页 · 显示 ${from}-${to}`;
}