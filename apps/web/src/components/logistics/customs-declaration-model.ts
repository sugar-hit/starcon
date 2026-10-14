import type {
  CustomsAmendRequest,
  CustomsCancelRequest,
  CustomsCapabilities,
  CustomsCreateRequest,
  CustomsDeclarationDetail,
  CustomsDeclarationPage,
  CustomsDocumentRow,
  CustomsEventRow,
  CustomsFinalizeDocumentRequest,
  CustomsGenerateDocumentRequest,
  CustomsRejectReviewRequest,
  CustomsResultRequest,
  CustomsResultRow,
  CustomsReviewRequest,
  CustomsSaved,
  CustomsSubmitForReviewRequest,
  CustomsSubmitRequest,
  CustomsUpdateRequest,
  CustomsWithdrawReviewRequest,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../inventory/api';
import { fetchFirstMileDetail, fetchFirstMileShipments } from './first-mile-shipment-model';

/**
 * 10.5.1.1 报关单管理前端模型。
 *
 * <p>蓝图：「管理报关单：按发货单生成申报资料（报关单、装箱单、发票），提交报关行/IOR，
 * 接收放行、退单、改单结果」。界面上最容易读错的三处，这里都显式处理：
 *
 * <ul>
 *   <li>**复核闸是真的闸**：只有「已复核」能提交报关行，所以「提交报关行」按钮不能
 *       常驻在工具栏上，否则界面会许诺一个注定被后端拒绝的动作。按钮由
 *       {@link declarationActions} 按当前状态算出来。
 *   <li>**提交痕迹是历史，不能抹**：退单改单退回草稿后 {@code submittedAt} 仍然留着，
 *       因为「这张单曾经交到报关行没有」是海关事后追责的举证。所以列表与详情都把
 *       提交时间显示成「提交于 …」，而不是在退回草稿后消失。
 *   <li>**三单据必须齐备且定稿**才能提交报关行：报关单 / 装箱单 / 商业发票缺一份都不行
 *       （少提交一份等于申报不完整）。所以详情里三份单据是一张状态表而不是三个链接。
 * </ul>
 */

/** 列表查询条件。与后端 `CustomsQuery` 一一对应（`status` 可多选）。 */
export interface CustomsDeclarationParams {
  status?: string[];
  tradeMode?: string;
  transportMode?: string;
  destinationCountryCode?: string;
  sourceShipmentId?: string;
  pendingReviewOnly?: boolean;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

/**
 * 拼查询串。
 *
 * <p>两条纪律：① 空条件一律不拼 —— 拼上 `status=` 会被后端当成「筛了这个值」；
 * ② 文本条件先 trim，纯空白的不拼 —— 否则等于拿一个空格去做 `ilike` 搜索。
 */
export function customsDeclarationQuery(params: CustomsDeclarationParams): string {
  const text = (value: string | undefined) => value?.trim() ?? '';
  const parts: string[] = [];
  for (const s of params.status ?? []) parts.push(`status=${encodeURIComponent(s)}`);
  if (params.tradeMode) parts.push(`tradeMode=${encodeURIComponent(params.tradeMode)}`);
  if (params.transportMode) parts.push(`transportMode=${encodeURIComponent(params.transportMode)}`);
  if (text(params.destinationCountryCode)) {
    parts.push(`destinationCountryCode=${encodeURIComponent(text(params.destinationCountryCode))}`);
  }
  if (params.sourceShipmentId) {
    parts.push(`sourceShipmentId=${encodeURIComponent(params.sourceShipmentId)}`);
  }
  if (params.pendingReviewOnly) parts.push('pendingReviewOnly=true');
  if (text(params.keyword)) parts.push(`keyword=${encodeURIComponent(text(params.keyword))}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 基础路径。端点命名空间 `/warehouse/customs-declarations`，浏览器只经 /api/warehouse 代理。 */
const BASE = 'warehouse/customs-declarations';

export function fetchCustomsDeclarations(params: CustomsDeclarationParams) {
  return api<CustomsDeclarationPage>(`${BASE}${customsDeclarationQuery(params)}`);
}

export function fetchCustomsCapabilities() {
  return api<CustomsCapabilities>(`${BASE}/capabilities`);
}

export function fetchCustomsDeclarationDetail(id: string) {
  return api<CustomsDeclarationDetail>(`${BASE}/${encodeURIComponent(id)}`);
}

export function fetchCustomsDocuments(id: string) {
  return api<CustomsDocumentRow[]>(`${BASE}/${encodeURIComponent(id)}/documents`);
}

export function fetchCustomsResults(id: string) {
  return api<CustomsResultRow[]>(`${BASE}/${encodeURIComponent(id)}/results`);
}

export function fetchCustomsEvents(id: string) {
  return api<CustomsEventRow[]>(`${BASE}/${encodeURIComponent(id)}/events`);
}

/**
 * 申报资料的来源：10.4.1.1 头程发运单（一票货只报一次关）。
 *
 * <p>这里刻意复用头程发运单自己的端点而不是在前端造一张「可报关发运单」列表：
 * 「哪些发运单能报关」是后端的判据（已发出、未取消），前端再抄一遍必然漂移。
 */
export function fetchCustomsSourceShipments(params: { keyword?: string; size?: number }) {
  const keyword = params.keyword?.trim();
  // exactOptionalPropertyTypes 下 undefined 不能直接塞进可选属性，所以按有没有关键字分两种形状。
  return keyword
    ? fetchFirstMileShipments({ keyword, size: params.size ?? 50 })
    : fetchFirstMileShipments({ size: params.size ?? 50 });
}

export function fetchCustomsSourceShipmentLines(shipmentId: string) {
  return fetchFirstMileDetail(shipmentId).then((detail) => detail.lines);
}

// ================================================================ 写动作

export function createCustomsDeclaration(input: CustomsCreateRequest) {
  return api<CustomsSaved>(BASE, 'POST', input);
}

export function updateCustomsDeclaration(id: string, input: CustomsUpdateRequest) {
  return api<CustomsSaved>(`${BASE}/${encodeURIComponent(id)}`, 'PUT', input);
}

export function submitCustomsForReview(id: string, input: CustomsSubmitForReviewRequest) {
  return api<CustomsSaved>(`${BASE}/${encodeURIComponent(id)}/submit-for-review`, 'POST', input);
}

export function reviewCustomsDeclaration(id: string, input: CustomsReviewRequest) {
  return api<CustomsSaved>(`${BASE}/${encodeURIComponent(id)}/review`, 'POST', input);
}

export function rejectCustomsReview(id: string, input: CustomsRejectReviewRequest) {
  return api<CustomsSaved>(`${BASE}/${encodeURIComponent(id)}/reject-review`, 'POST', input);
}

export function withdrawCustomsReview(id: string, input: CustomsWithdrawReviewRequest) {
  return api<CustomsSaved>(`${BASE}/${encodeURIComponent(id)}/withdraw-review`, 'POST', input);
}

export function generateCustomsDocument(id: string, input: CustomsGenerateDocumentRequest) {
  return api<CustomsSaved>(`${BASE}/${encodeURIComponent(id)}/documents`, 'POST', input);
}

export function finalizeCustomsDocument(
  id: string,
  documentId: string,
  input: CustomsFinalizeDocumentRequest,
) {
  return api<CustomsSaved>(
    `${BASE}/${encodeURIComponent(id)}/documents/${encodeURIComponent(documentId)}/finalize`,
    'POST',
    input,
  );
}

export function submitCustomsDeclaration(id: string, input: CustomsSubmitRequest) {
  return api<CustomsSaved>(`${BASE}/${encodeURIComponent(id)}/submit`, 'POST', input);
}

export function recordCustomsResult(id: string, input: CustomsResultRequest) {
  return api<CustomsSaved>(`${BASE}/${encodeURIComponent(id)}/results`, 'POST', input);
}

export function amendCustomsDeclaration(id: string, input: CustomsAmendRequest) {
  return api<CustomsSaved>(`${BASE}/${encodeURIComponent(id)}/amend`, 'POST', input);
}

export function cancelCustomsDeclaration(id: string, input: CustomsCancelRequest) {
  return api<CustomsSaved>(`${BASE}/${encodeURIComponent(id)}/cancel`, 'POST', input);
}

// ================================================================ 界面口径

/** 状态文案。与后端 `DeclarationStatus.label()` 逐条一致，不在前端另抄一份。 */
export const declarationStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待复核',
  REVIEWED: '已复核',
  REJECTED: '复核退回',
  SUBMITTED: '已提交',
  CLEARED: '已放行',
  CUSTOMS_REJECTED: '海关退单',
  AMENDED: '已改单',
  CANCELLED: '已取消',
};

export const documentTypeLabels: Record<string, string> = {
  CUSTOMS_DECLARATION: '报关单',
  PACKING_LIST: '装箱单',
  COMMERCIAL_INVOICE: '商业发票',
};

export const resultTypeLabels: Record<string, string> = {
  CLEARED: '放行',
  REJECTED: '退单',
  AMENDED: '改单',
};

export const elementStatusLabels: Record<string, string> = {
  MANUAL_PENDING: '人工录入·未齐备',
  MANUAL_COMPLETE: '人工录入·已齐备',
  MODULE_3_7_1_1_PENDING: '3.7.1.1 字典·未齐备',
  MODULE_3_7_1_1_COMPLETE: '3.7.1.1 字典·已齐备',
};

/** 事件时间线的 kind 文案。缺失时回落成原值，不吞掉未知事件。 */
export const eventKindLabels: Record<string, string> = {
  CREATED: '创建草稿',
  UPDATED: '修改草稿',
  PENDING_REVIEW: '送人工复核',
  REVIEWED: '复核通过',
  REVIEW_REJECTED: '复核退回',
  REVIEW_WITHDRAWN: '撤回复核',
  SUBMITTED: '提交报关行',
  CLEARED: '海关放行',
  CUSTOMS_REJECTED: '海关退单',
  AMENDED: '海关改单',
  CANCELLED: '取消',
  DOCUMENT_GENERATED: '生成单据',
};

/**
 * 当前状态下**合法**的动作按钮。
 *
 * <p>判据直接读后端给的合法转换（`/capabilities` 与详情里的 `transitions` 同一张表），
 * 所以前端不再抄一份状态机 —— 抄了会在后端改表时静默漂移，界面就会给出一个
 * 注定被拒的动作。这里是 action 词到 UI 文案的映射。
 */
const actionLabels: Record<string, string> = {
  submitForReview: '送人工复核',
  review: '复核通过',
  rejectReview: '复核退回',
  withdrawReview: '撤回复核',
  submit: '提交报关行',
  recordResult: '登记海关结果',
  amend: '登记改单',
  cancel: '取消报关单',
};

/**
 * 按状态给出动作入口。
 *
 * <p>三个业务硬约束在这里显式编码，避免界面许诺做不到的事：
 * 「提交报关行」只在「已复核」出现；「登记海关结果」只在已提交/已改单出现；
 * 「取消」在已放行/已取消（终态）不出现。
 */
export function declarationActions(status: string): string[] {
  switch (status) {
    case 'DRAFT':
      return ['submitForReview', 'cancel'];
    case 'PENDING_REVIEW':
      return ['review', 'rejectReview', 'withdrawReview', 'cancel'];
    case 'REVIEWED':
      return ['submit', 'withdrawReview', 'cancel'];
    case 'REJECTED':
      return ['submitForReview', 'cancel'];
    case 'SUBMITTED':
    case 'AMENDED':
      return ['recordResult', 'amend'];
    case 'CUSTOMS_REJECTED':
      return ['amend', 'cancel'];
    case 'CLEARED':
    case 'CANCELLED':
      // 终态：放行后要修正申报资料只能由海关发起改单，我方不能自己动。
      return [];
    default:
      return [];
  }
}

export function actionLabel(action: string): string {
  return actionLabels[action] ?? action;
}

/** 三单据是否齐备且都定稿。缺一份就不许提交报关行，页面据此把提交按钮置灰并说清缺哪份。 */
export function documentsReady(documents: CustomsDocumentRow[]): {
  ready: boolean;
  missing: string[];
  drafts: string[];
} {
  const missing: string[] = [];
  const drafts: string[] = [];
  for (const type of ['CUSTOMS_DECLARATION', 'PACKING_LIST', 'COMMERCIAL_INVOICE']) {
    const latest = documents
      .filter((d) => d.documentType === type && d.status !== 'SUPERSEDED')
      .sort((a, b) => b.documentVersion - a.documentVersion)[0];
    if (!latest) {
      missing.push(type);
      continue;
    }
    if (latest.status !== 'FINAL') drafts.push(type);
  }
  return { ready: missing.length === 0 && drafts.length === 0, missing, drafts };
}

/** 申报要素齐备性：只有「已齐备」的两种取值能过复核闸，其余会在复核时被 DB 拒。 */
export function elementComplete(status: string): boolean {
  return status === 'MANUAL_COMPLETE' || status === 'MODULE_3_7_1_1_COMPLETE';
}

export const declarationSortOptions = [
  { value: 'UPDATED_AT', label: '最近更新' },
  { value: 'CREATED_AT', label: '最近创建' },
  { value: 'DECLARED_VALUE', label: '申报货值' },
] as const;

/** 数值展示。数量与金额来自 NUMERIC，后端给的是 number，这里只做展示格式化。 */
export function fmt(value: number | null | undefined, digits = 2): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  return value.toLocaleString('zh-CN', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
}

export function money(value: number | null | undefined, currency?: string | null): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  return `${fmt(value)} ${currency ?? ''}`.trim();
}

/** 汇率快照：必须连刻度与日期一起显示，否则事后复核答不出「当时按哪个汇率申报的」。 */
export function rateText(
  rate: number | null | undefined,
  scale: number | null | undefined,
  date: string | null | undefined,
): string {
  if (rate === null || rate === undefined) return '—';
  return `${rate} / ${scale ?? '—'} @ ${date ?? '—'}`;
}

export function moment(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '—';
  return parsed.toLocaleString('zh-CN', { hour12: false });
}
