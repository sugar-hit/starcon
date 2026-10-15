import type {
  WaybillBatchCustomsInput,
  WaybillBatchResult,
  WaybillCapabilities,
  WaybillCarrierInput,
  WaybillCarrierPage,
  WaybillCarrierRow,
  WaybillCustomsItemInput,
  WaybillDispatchInput,
  WaybillDispatchResult,
  WaybillQueueRow,
  WaybillRequestDetail,
  WaybillRequestInput,
  WaybillRequestPage,
  WaybillRequestRow,
  WaybillRetryResult,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 10.2.1.2 运单申请前端模型。
 *
 * <p>蓝图：「向承运商申请运单号：传入收寄信息、包裹明细、申报信息，支持批量申请、
 * 失败重试、接口限流控制。」输出是「运单号 + 面单数据；失败原因」。
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>没有运单号要显示成「还没有」而不是空白。</b>未接通渠道的申请会一直停在
 *       FAILED，界面上必须显示失败原因与 ownerModule（10.1.1.1），
 *       否则运营会以为是系统坏了，反复点「重试」把配额白烧完。</li>
 *   <li><b>「已入队」不等于「已出号」。</b>队列里排第几位、轮到哪个窗口是**可见事实**
 *       （限流排队本来就是本模块的职责），所以 QUEUED 必须画出排队位次，
 *       而不是画成一个进行中的转圈 —— 后者会让现场以为已经在提交了。</li>
 *   <li><b>不可重试的失败不能给「重试」按钮。</b>承运商拒收（地址不可达、尺寸超限）
 *       原样重发一千次还是同样的结果，改单才有意义；按钮禁用并说明原因。</li>
 *   <li><b>「跳过」和「失败」分色。</b>批量里「三条成、一条跳过（重复提交）、一条失败
 *       （申报口径对不上）」是三种不同的事，合成一个红数字会让现场做错决定。</li>
 *   <li><b>幂等命中要说出来。</b>重复提交返回的是原来那一张，界面标「幂等命中」而不是
 *       当成新建成功 —— 否则运营以为要等两票。</li>
 * </ul>
 */

const base = 'trade/logistics/waybill-applications';

// ------------------------------------------------------------ 参数

export interface WaybillRequestParams {
  carrierId?: string;
  status?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export interface WaybillCarrierParams {
  carrierCode?: string;
  connected?: boolean;
  sort?: string;
  page?: number;
  size?: number;
}

/**
 * 把参数拼成 query string。
 *
 * <p>刻意把入参声明成 {@code Record<string, ...>} 而不是复用调用方的 interface：
 * TS 的 interface **没有**隐式索引签名，直接传进去会报
 * 「不能赋给 Record<string, string|number>」。
 */
function query(params: object): string {
  const parts = Object.entries(params as Record<string, string | number | boolean | undefined | null>)
    .filter(([, v]) => v !== undefined && v !== null && `${v}` !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ------------------------------------------------------------ 读

export function fetchWaybillCapabilities(): Promise<WaybillCapabilities> {
  return api<WaybillCapabilities>(`${base}/capabilities`);
}

export function fetchWaybillCarriers(params: WaybillCarrierParams): Promise<WaybillCarrierPage> {
  return api<WaybillCarrierPage>(`${base}/carriers${query(params)}`);
}

export function fetchWaybillRequests(params: WaybillRequestParams): Promise<WaybillRequestPage> {
  return api<WaybillRequestPage>(`${base}/requests${query(params)}`);
}

export function fetchWaybillRequest(id: string): Promise<WaybillRequestDetail> {
  return api<WaybillRequestDetail>(`${base}/requests/${id}`);
}

export function fetchWaybillQueue(carrierCode: string): Promise<WaybillQueueRow[]> {
  return api<WaybillQueueRow[]>(`${base}/queue${query({ carrierCode })}`);
}

// ------------------------------------------------------------ 写

export function registerWaybillCarrier(input: WaybillCarrierInput): Promise<WaybillCarrierRow> {
  return api<WaybillCarrierRow>(`${base}/carriers`, 'POST', input);
}

export function submitWaybillRequest(input: WaybillRequestInput): Promise<WaybillRequestRow> {
  return api<WaybillRequestRow>(`${base}/requests`, 'POST', input);
}

export function submitWaybillBatch(input: {
  idempotencyKeyPrefix: string;
  carrierCode: string;
  shipFromName: string;
  shipFromCountry: string;
  shipToName: string;
  shipToCountry: string;
  shipToAddressLine?: string;
  shipToCity?: string;
  shipToPostalCode?: string;
  declaredCurrency: string;
  labelTemplateRef?: string;
  pendingParcelIds: string[];
  customsItems: WaybillBatchCustomsInput[];
}): Promise<WaybillBatchResult> {
  return api<WaybillBatchResult>(`${base}/batches`, 'POST', input);
}

export function dispatchWaybillQueue(
  carrierCode: string,
  maxDispatches?: number,
): Promise<WaybillDispatchResult> {
  const input: WaybillDispatchInput = {
    carrierCode,
    maxDispatches: maxDispatches ?? 10,
  };
  return api<WaybillDispatchResult>(`${base}/requests/dispatch`, 'POST', input);
}

export function retryWaybillRequest(id: string, reason: string): Promise<WaybillRetryResult> {
  return api<WaybillRetryResult>(`${base}/requests/${id}/retry`, 'POST', { reason });
}

export function cancelWaybillRequest(id: string, reason: string): Promise<WaybillRequestRow> {
  return api<WaybillRequestRow>(`${base}/requests/${id}/cancel`, 'POST', { reason });
}

// ------------------------------------------------------------ 展示派生
//
// 下面的函数都是**纯函数**：把后端字段翻译成界面上要显示的话，
// 并且在「不可信 / 缺失」时给出安全回落，而不是静默吞掉。
// 它们的单测在 waybill-model.test.ts。

const STATUS_LABELS: Record<string, string> = {
  QUEUED: '已入队',
  SUBMITTING: '提交中',
  ISSUED: '已出号',
  FAILED: '申请失败',
  CANCELLED: '已作废',
};

export function requestStatusLabel(row: WaybillRequestRow): string {
  return STATUS_LABELS[row.status] ?? row.status;
}

/**
 * 状态分色。
 *
 * <p>FAILED 与 CANCELLED 是两种红：CANCELLED 是人主动作废的（正常），
 * FAILED 是系统没办成（要处理），画成同一个红会让人分不清该重试还是该忽略。
 */
export type Severity = 'ok' | 'warn' | 'danger' | 'muted';

export function statusSeverity(row: WaybillRequestRow): Severity {
  switch (row.status) {
    case 'ISSUED':
      return 'ok';
    case 'QUEUED':
    case 'SUBMITTING':
      return 'warn';
    case 'FAILED':
      return 'danger';
    case 'CANCELLED':
      return 'muted';
    default:
      return 'muted';
  }
}

/** 「还没拿到单号」有三种完全不同的原因，界面要分开说。 */
export function noWaybillHint(row: WaybillRequestRow): string {
  if (row.waybillNo) return `运单号 ${row.waybillNo}`;
  switch (row.status) {
    case 'QUEUED':
      return '还在渠道限流队列里等待放行 —— 还没向承运商提交';
    case 'SUBMITTING':
      return '正在向承运商提交，尚未收到回执';
    case 'FAILED':
      return row.failureReason || '申请失败，尚未拿到运单号';
    case 'CANCELLED':
      return '已作废，不产生运单号';
    default:
      return '尚未拿到运单号';
  }
}

/** 失败是否可重试：不可重试的（承运商拒收 / 渠道未接通）不该给「重试」按钮。 */
const NON_RETRYABLE = new Set(['CARRIER_REJECTED', 'ADAPTER_MISSING']);

export function canRetry(row: WaybillRequestRow): boolean {
  if (row.status === 'ISSUED') return false;
  if (row.status === 'CANCELLED') return false;
  return !NON_RETRYABLE.has(row.failureCode);
}

/** 不可重试时给出的、**可执行**的原因（不是一句「无法重试」）。 */
export function retryBlockedReason(row: WaybillRequestRow): string {
  if (row.status === 'ISSUED') return '已出号的运单不能重试申请（取消属 10.2.1.3，未交付）';
  if (row.status === 'CANCELLED') return '已作废，请重新提交一条新的申请';
  if (row.failureCode === 'CARRIER_REJECTED') {
    return '承运商拒收后原样重发仍会被拒：请先改地址或改包裹尺寸，再重新申请';
  }
  if (row.failureCode === 'ADAPTER_MISSING') {
    return '渠道尚未接通（10.1 物流渠道未交付），重试只会白烧一次接口配额 —— 等 10.1.1.1 接通后重试';
  }
  return '当前状态不能重试';
}

/** 队列视图里的位次文案：排队必须是看得见的事实。 */
export function queuePositionText(entry: WaybillQueueRow): string {
  if (entry.status === 'DONE') return '已完成';
  if (entry.status === 'DISPATCHING') return '正在提交';
  if (entry.status === 'FAILED') return '已失败';
  const position = entry.positionHint > 0 ? `第 ${entry.positionHint} 位` : '排队中';
  return `${position} · 预计 ${timeText(entry.scheduledAt)} 放行`;
}

/** 批量结果汇总：成功 / 跳过 / 失败分色，不合成一个红数字。 */
export interface BatchSummary {
  submittedText: string;
  skippedText: string;
  failedText: string;
  tone: Severity;
}

export function batchSummary(result: WaybillBatchResult): BatchSummary {
  return {
    submittedText: `成功入队 ${result.submitted} 票`,
    // 跳过 = 重复提交（幂等命中），不是问题
    skippedText: `跳过 ${result.skipped} 票（重复提交，幂等命中）`,
    // 失败 = 要处理（申报口径对不上 / 渠道不可达）
    failedText: `失败 ${result.failed} 票（需要处理）`,
    tone: result.failed > 0 ? 'danger' : result.skipped > 0 ? 'warn' : 'ok',
  };
}

/** 未接入的能力维度（capabilities 里 available=false 的）。 */
export function unavailableDimensions(
  capabilities: WaybillCapabilities,
): { dimension: string; label: string; ownerModule: string; impact: string }[] {
  return capabilities.dimensions
    .filter((d) => !d.available)
    .map((d) => ({
      dimension: d.dimension,
      label: d.description,
      ownerModule: d.ownerModule ?? '未标注',
      impact: d.impact,
    }));
}

// ------------------------------------------------------------ 格式化

/** 时间。空值显示「—」而不是空串，避免表格里出现一个看不出含义的空格。 */
export function timeText(value?: string | null): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** 申报额。**必须带币种**：裸数字在多币种场景里没有意义。 */
export function declaredValueText(row: WaybillRequestRow): string {
  return `${row.declaredValueTotal} ${row.declaredCurrency}`;
}

/** 申报额的对齐口径：DB 是 NUMERIC(19,4)，前端统一按 4 位显示，避免「20」与「20.0000」混排。 */

/** 重量（公斤）。AGENTS.md：禁浮点，所以后端给的是字符串。 */
export function weightText(row: WaybillRequestRow): string {
  return `${row.grossWeightKg} kg`;
}

/** 尺寸（毫米）。 */
export function dimensionText(row: WaybillRequestRow): string {
  return `${row.lengthMm} × ${row.widthMm} × ${row.heightMm} mm`;
}

/** 包裹 / 申报明细计数。0 条要显示成「0 条」而不是「—」—— 空明细是要拦的。 */
export function declarationCountText(row: WaybillRequestRow): string {
  return `${row.declaredItemCount} 条`;
}

/** 空申报明细 = 会被承运商与海关拒收，界面必须先说这句。 */
export function declarationWarning(row: WaybillRequestRow): string {
  return row.declaredItemCount === 0
    ? '这一票还没有申报明细：面单逐件打印，没有明细的申报单承运商与海关都会拒收'
    : '';
}

export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '请求失败，请稍后重试';
}

export type { WaybillCustomsItemInput };
