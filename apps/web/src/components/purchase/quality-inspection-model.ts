import type {
  IncomingCancelInput,
  IncomingCompleteInput,
  IncomingConcessionInput,
  IncomingConcessionRow,
  IncomingInspectionSummary,
  IncomingOrderDetail,
  IncomingOrderInput,
  IncomingOrderItemInput,
  IncomingOrderItemRow,
  IncomingOrderPage,
  IncomingOrderRow,
  IncomingQualityInspectionCapabilities,
  IncomingQualityInspectionOptions,
  IncomingTemplateDetail,
  IncomingTemplateInput,
  IncomingTemplatePage,
  IncomingTemplateRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 6.4.2.1 质检单前端模型。
 *
 * <p>蓝图：「来料/成品质检：抽样比例、检验项（外观、功能、规格、包装、认证标识）、检验结果记录、
 * 不合格判定与扣量。」输出「质检单（合格量/不合格量/让步接收量）；触发 7 库存的质量状态分流」。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>三量并排显示</b>：合格量 / 不合格量 / 让步接收量必须同时出现，
 *       并把「三者之和 = 本次质检覆盖的批量」直接写在页面上 ——
 *       少了任何一个数，运营就答不出「这批货到底去哪了」。
 *   <li><b>抽样不让人手填</b>：应抽数量由后端按模板算，页面只显示算式原文。
 *   <li><b>逐项结论不是一个总分</b>：外观 / 功能 / 规格 / 包装 / 认证标识逐项显示，
 *       每一项的扣量单列。
 *   <li><b>让步接收要原因与权限码</b>：表单里两者都是必填，页面明写「缺一会被后端拒」。
 *   <li><b>未交付的下游直接禁用并写明归属模块</b>，不给点下去 404 的按钮。
 *   <li>加载 / 空 / 错误三态齐全。
 * </ul>
 */

const base = 'trade/quality-inspections';

export interface QualityInspectionParams {
  keyword?: string;
  status?: string;
  result?: string;
  inspectionType?: string;
  receiptCode?: string;
  skuCode?: string;
  sort?: string;
  page?: number;
  size?: number;
}

/** 列表查询串。空值一律不拼进去 —— 拼一个空串等于「按空值筛」。 */
export function qualityInspectionQuery(params: QualityInspectionParams): string {
  const parts: string[] = [];
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.result) parts.push(`result=${encodeURIComponent(params.result)}`);
  if (params.inspectionType)
    parts.push(`inspectionType=${encodeURIComponent(params.inspectionType)}`);
  if (params.receiptCode) parts.push(`receiptCode=${encodeURIComponent(params.receiptCode)}`);
  if (params.skuCode) parts.push(`skuCode=${encodeURIComponent(params.skuCode)}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 读

export function fetchCapabilities() {
  return api<IncomingQualityInspectionCapabilities>(`${base}/capabilities`);
}

export function fetchOptions() {
  return api<IncomingQualityInspectionOptions>(`${base}/options`);
}

export function fetchTemplates(params: { keyword?: string; status?: string; page?: number; size?: number } = {}) {
  const parts: string[] = [];
  if (params.keyword?.trim()) parts.push(`keyword=${encodeURIComponent(params.keyword.trim())}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  const query = parts.length ? `?${parts.join('&')}` : '';
  return api<IncomingTemplatePage>(`${base}/templates${query}`);
}

export function fetchTemplate(templateId: string) {
  return api<IncomingTemplateDetail>(`${base}/templates/${encodeURIComponent(templateId)}`);
}

export function fetchOrders(params: QualityInspectionParams) {
  return api<IncomingOrderPage>(`${base}${qualityInspectionQuery(params)}`);
}

export function fetchOrder(orderId: string) {
  return api<IncomingOrderDetail>(`${base}/${encodeURIComponent(orderId)}`);
}

export function fetchSummary(currencyCode?: string) {
  const query = currencyCode ? `?currencyCode=${encodeURIComponent(currencyCode)}` : '';
  return api<IncomingInspectionSummary>(`${base}/summary${query}`);
}

// ================================================================ 写

export function createTemplate(input: IncomingTemplateInput) {
  return api<IncomingTemplateRow>(`${base}/templates`, 'POST', input);
}

export function updateTemplate(templateId: string, input: IncomingTemplateInput) {
  return api<IncomingTemplateRow>(
    `${base}/templates/${encodeURIComponent(templateId)}`,
    'PUT',
    input,
  );
}

export function activateTemplate(templateId: string) {
  return api<IncomingTemplateRow>(
    `${base}/templates/${encodeURIComponent(templateId)}/activate`,
    'POST',
  );
}

export function retireTemplate(templateId: string) {
  return api<IncomingTemplateRow>(
    `${base}/templates/${encodeURIComponent(templateId)}/retire`,
    'POST',
  );
}

export function createOrder(input: IncomingOrderInput) {
  return api<IncomingOrderRow>(`${base}`, 'POST', input);
}

/** 录入逐项检验结论。 */
export function recordItem(orderId: string, input: IncomingOrderItemInput) {
  return api<IncomingOrderItemRow>(
    `${base}/${encodeURIComponent(orderId)}/items`,
    'POST',
    input,
  );
}

/** 登记让步接收（原因与权限码缺一会被后端拒）。 */
export function addConcession(orderId: string, input: IncomingConcessionInput) {
  return api<IncomingConcessionRow>(
    `${base}/${encodeURIComponent(orderId)}/concessions`,
    'POST',
    input,
  );
}

export function completeOrder(orderId: string, input: IncomingCompleteInput) {
  return api<IncomingOrderRow>(`${base}/${encodeURIComponent(orderId)}/complete`, 'POST', input);
}

export function cancelOrder(orderId: string, input: IncomingCancelInput) {
  return api<IncomingOrderRow>(`${base}/${encodeURIComponent(orderId)}/cancel`, 'POST', input);
}

/**
 * 导出 CSV 的地址（浏览器直接下载）。
 *
 * <p>注意 {@code base} 已经含 `trade/` 前缀 —— 再拼一次就变成 `/api/trade/trade/...`，
 * 那种地址在浏览器里静默 404（typecheck / lint / vitest 全绿，只有页面点不出东西）。
 */
export function exportUrl(orderId: string) {
  return `/api/${base}/${encodeURIComponent(orderId)}/export`;
}

// ================================================================ 界面口径（纯函数，可单测）

/** 数量格式化：6 位小数去掉尾随零，但整数保留整数形态。 */
export function fmtQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  const rounded = Math.round(value * 1e6) / 1e6;
  return Number.isInteger(rounded) ? String(rounded) : String(rounded);
}

/**
 * 三量是否自洽：合格量 + 不合格量 + 让步接收量 = 本次质检覆盖的批量。
 *
 * <p>页面把这个等式当**断言**用 —— 不自洽就显红，而不是照样显示三个数。
 */
export function balanceIsBalanced(row: {
  qualifiedQuantity: number;
  defectiveQuantity: number;
  concessionQuantity: number;
  lotSize: number;
}): boolean {
  const sum =
    row.qualifiedQuantity + row.defectiveQuantity + row.concessionQuantity;
  return Math.abs(sum - row.lotSize) < 1e-6;
}

/** 三量等式的文字（页面上直接把算式写出来）。 */
export function balanceText(row: {
  qualifiedQuantity: number;
  defectiveQuantity: number;
  concessionQuantity: number;
  lotSize: number;
}): string {
  return `合格 ${fmtQuantity(row.qualifiedQuantity)} + 不合格 ${fmtQuantity(
    row.defectiveQuantity,
  )} + 让步接收 ${fmtQuantity(row.concessionQuantity)} = ${fmtQuantity(row.lotSize)}`;
}

/** 抽样口径的文字（应抽 / 实抽）。 */
export function samplingText(row: { sampleQuantity: number; sampledQuantity: number }): string {
  const shortfall = Math.round((row.sampleQuantity - row.sampledQuantity) * 1e6) / 1e6;
  if (shortfall > 0) {
    return `应抽 ${fmtQuantity(row.sampleQuantity)}，实抽 ${fmtQuantity(
      row.sampledQuantity,
    )}（还差 ${fmtQuantity(shortfall)}）`;
  }
  return `应抽 ${fmtQuantity(row.sampleQuantity)}，实抽 ${fmtQuantity(row.sampledQuantity)}`;
}

/** 终态（已完结 / 已作废）不再给动作。 */
export function isTerminal(status: string): boolean {
  return status === 'COMPLETED' || status === 'CANCELLED';
}

/** 还有检验项没结论 ⇒ 不能完结。 */
export function canComplete(detail: { missingItemCodes: string[] }): boolean {
  return detail.missingItemCodes.length === 0;
}

/** 缺项的中文说明（直接用后端给的标签，不在前端另造一套词表）。 */
export function missingItemsText(detail: {
  missingItemLabels: string[];
  missingItemCodes: string[];
}): string {
  if (detail.missingItemLabels.length === 0) return '';
  const labels = detail.missingItemLabels.join('、');
  return `还有检验项没有结论：${labels}（${detail.missingItemCodes.join('/')}）`;
}

/**
 * 分流状态的文字。
 *
 * <p>🔴 **绝不把「没分流」说成「已分流」**：warehouse 当前没有可用的授权写端点，
 * 所以 dispositionStatus 只可能是 NOT_DISPATCHED / UNAVAILABLE，
 * 页面照实显示并把归属模块与影响写出来。
 */
export function dispositionText(row: {
  disposition: string;
  dispositionLabel: string;
  dispositionStatus: string;
  dispositionStatusLabel: string;
}): string {
  return `去向 ${row.dispositionLabel} · ${row.dispositionStatusLabel}`;
}

/** 让步接收的必填提示（表单与提示共用一句）。 */
export const CONCESSION_REQUIRED_HINT =
  '让步接收必须给**原因**与**权限码**，且记录人必须真的持有该权限码 —— 缺一会被后端拒。';

/** 抽样规则的中文说明（与后端的 samplingRuleNote 同义，页面上给一句短的）。 */
export const SAMPLING_HINT =
  '应抽数量由后端按模板的抽样比例 × 本次批量 + 最小/最大夹逼 + 舍入口径算出，页面不让人手填。';

/** 状态词表（用于筛选下拉）。 */
export const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'INSPECTING', label: '检验中' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CANCELLED', label: '已作废' },
];

/** 结论词表。 */
export const RESULT_OPTIONS = [
  { value: '', label: '全部结论' },
  { value: 'PASSED', label: '合格' },
  { value: 'PARTIAL', label: '部分合格' },
  { value: 'FAILED', label: '整批不合格' },
  { value: 'CONCESSION_ACCEPTED', label: '让步接收' },
];

/** 检验项词表（蓝图点名的五项）。 */
export const ITEM_OPTIONS = [
  { value: 'APPEARANCE', label: '外观' },
  { value: 'FUNCTION', label: '功能' },
  { value: 'SPECIFICATION', label: '规格' },
  { value: 'PACKAGING', label: '包装' },
  { value: 'CERTIFICATION', label: '认证标识' },
];

/** 单项结论词表。 */
export const ITEM_RESULT_OPTIONS = [
  { value: 'PASS', label: '合格' },
  { value: 'FAIL', label: '不合格' },
  { value: 'CONCESSION', label: '让步接收' },
];

/** 检验项代码 → 中文（与后端 label 同口径；后端没给 label 时用这里的兜底）。 */
export const ITEM_LABELS: Record<string, string> = Object.fromEntries(
  ITEM_OPTIONS.map((o) => [o.value, o.label]),
);

/** 检验项代码 → 中文标签（优先用后端给的 label）。 */
export function itemLabel(code: string, serverLabel?: string | null): string {
  return serverLabel && serverLabel.trim() ? serverLabel : (ITEM_LABELS[code] ?? code);
}

/** 单项结论代码 → 中文。 */
export function itemResultLabel(code: string, serverLabel?: string | null): string {
  if (serverLabel && serverLabel.trim()) return serverLabel;
  return ITEM_RESULT_OPTIONS.find((o) => o.value === code)?.label ?? code;
}

/** 逐项结论的扣量汇总（页面上「已扣量」那一栏）。 */
export function totalDeducted(items: IncomingOrderItemRow[]): number {
  return Math.round(items.reduce((sum, i) => sum + (i.deductedQuantity ?? 0), 0) * 1e6) / 1e6;
}

/** 让步接收量合计。 */
export function totalConcession(concessions: IncomingConcessionRow[]): number {
  return (
    Math.round(concessions.reduce((sum, c) => sum + (c.quantity ?? 0), 0) * 1e6) / 1e6
  );
}
