import type {
  ConsignmentAgreement,
  ConsignmentAgreementDetail,
  ConsignmentAgreementPage,
  ConsignmentAuditEntry,
  ConsignmentCapabilities,
  ConsignmentLine,
  ConsignmentReturn,
  ConsignmentReturnDetail,
  ConsignmentReturnPage,
  ConsignmentSale,
  ConsignmentSalePage,
  ConsignmentSettlement,
  ConsignmentSettlementDetail,
  ConsignmentSettlementPage,
  ConsignmentStockView,
  SettlementWindow,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from './api';

/**
 * 7.7.1.2 寄售与代销前端模型。
 *
 * <p>界面上最容易出错、本模块显式处理了几处：
 *
 * <ul>
 *   <li>**寄售量不是成本、不是应付**。界面上不能把「寄售 140 件」显示成「占用资金 140×单价」，
 *       那会让使用者以为供应商已经把货买给我方了。寄售货主是供应商。
 *   <li>**未售出不产生应付要看得见**。结算单为空时界面要说「这期没卖出，不产生应付」，
 *       而不是显示成一个错误或一片空白 —— 空结算单是寄售的正常结果。
 *   <li>**转入采购是唯一例外，而且要显眼的警示**。它会产生真金白银的应付，
 *       不能和「退回供应商」用同样的语气呈现。
 *   <li>**自有货值是 null 不是 0**。显示 0 会被读成「这些货值 0 元」。
 * </ul>
 */

export interface AgreementParams {
  status?: string;
  mode?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

function query<T extends object>(params: T): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ------------------------------------------------------------ 接口

const base = 'warehouse/inventory/consignments';

export const fetchConsignmentCapabilities = () =>
  api<ConsignmentCapabilities>(`${base}/capabilities`);

export const fetchConsignmentAgreements = (params: AgreementParams = {}) =>
  api<ConsignmentAgreementPage>(`${base}${query(params)}`);

export const fetchConsignmentAgreement = (agreementId: string) =>
  api<ConsignmentAgreementDetail>(`${base}/${agreementId}`);

export const createConsignmentAgreement = (body: unknown) =>
  api<ConsignmentAgreementDetail>(base, 'POST', body);

export const activateConsignmentAgreement = (agreementId: string, note?: string) =>
  api<ConsignmentAgreement>(`${base}/${agreementId}/activate`, 'POST', { note: note ?? '' });

export const suspendConsignmentAgreement = (agreementId: string, note?: string) =>
  api<ConsignmentAgreement>(`${base}/${agreementId}/suspend`, 'POST', { note: note ?? '' });

export const closeConsignmentAgreement = (agreementId: string, note?: string) =>
  api<ConsignmentAgreement>(`${base}/${agreementId}/close`, 'POST', { note: note ?? '' });

export const fetchConsignmentStockView = (agreementId: string) =>
  api<ConsignmentStockView>(`${base}/${agreementId}/stock-view`);

export const fetchConsignmentSales = (
  agreementId: string,
  params: { unsettleableOnly?: boolean; page?: number; size?: number } = {},
) => api<ConsignmentSalePage>(`${base}/${agreementId}/sales${query(params)}`);

export const recordConsignmentSale = (
  agreementId: string,
  lineId: string,
  body: unknown,
) => api<ConsignmentSale>(`${base}/${agreementId}/lines/${lineId}/sales`, 'POST', body);

export const fetchSettlementWindow = (agreementId: string) =>
  api<SettlementWindow>(`${base}/${agreementId}/settlement-window`);

export const createConsignmentSettlement = (agreementId: string, body: unknown) =>
  api<ConsignmentSettlementDetail>(`${base}/${agreementId}/settlements`, 'POST', body);

export const fetchConsignmentSettlements = (
  params: { agreementId?: string; status?: string; page?: number; size?: number } = {},
) => api<ConsignmentSettlementPage>(`${base}/settlements${query(params)}`);

export const fetchConsignmentSettlement = (settlementId: string) =>
  api<ConsignmentSettlementDetail>(`${base}/settlements/${settlementId}`);

export const confirmConsignmentSettlement = (settlementId: string, note?: string) =>
  api<ConsignmentSettlement>(`${base}/settlements/${settlementId}/confirm`, 'POST', {
    note: note ?? '',
  });

export const fetchConsignmentReturns = (
  params: { agreementId?: string; status?: string; page?: number; size?: number } = {},
) => api<ConsignmentReturnPage>(`${base}/returns${query(params)}`);

export const fetchConsignmentReturn = (returnId: string) =>
  api<ConsignmentReturnDetail>(`${base}/returns/${returnId}`);

export const createConsignmentReturn = (agreementId: string, body: unknown) =>
  api<ConsignmentReturnDetail>(`${base}/${agreementId}/returns`, 'POST', body);

export const executeConsignmentReturn = (returnId: string, note?: string) =>
  api<ConsignmentReturn>(`${base}/returns/${returnId}/execute`, 'POST', { note: note ?? '' });

export const cancelConsignmentReturn = (returnId: string, note?: string) =>
  api<ConsignmentReturn>(`${base}/returns/${returnId}/cancel`, 'POST', { note: note ?? '' });

export const fetchConsignmentAudit = (agreementId: string, limit = 100) =>
  api<ConsignmentAuditEntry[]>(`${base}/${agreementId}/audit?limit=${limit}`);

// ------------------------------------------------------------ 纯函数（可单测）

/** 数量显示：去掉尾随 0，但保留整数形态，避免 "100.000000" 淹没界面。 */
export function formatQty(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'string' ? Number(value) : value;
  if (!Number.isFinite(n)) return '—';
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/**
 * 金额显示。
 *
 * <p>币种必须跟着数字一起出现：结算价没有币种就是一个无意义的数字。
 * `ownedAssetValue` 这类**不存在**的口径传 null 时显示「不适用」而不是 0 ——
 * 0 会被读成「这些货值 0 元」。
 */
export function formatAmount(
  value: number | string | null | undefined,
  currency: string,
  unavailableText = '不适用',
): string {
  if (value === null || value === undefined) return unavailableText;
  const n = typeof value === 'string' ? Number(value) : value;
  if (!Number.isFinite(n)) return unavailableText;
  return `${n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 6 })} ${currency}`;
}

/** 售罄率 = 已售 ÷ 寄售量。分母为 0 返回 null（不是 0 —— 那会被读成「一件没卖出去」）。 */
export function soldRate(line: Pick<ConsignmentLine, 'soldQuantity' | 'consignedQuantity'>): number | null {
  const consigned = Number(line.consignedQuantity);
  const sold = Number(line.soldQuantity);
  if (!Number.isFinite(consigned) || consigned === 0) return null;
  return sold / consigned;
}

/** 售罄率的展示文本。 */
export function formatRate(rate: number | null): string {
  return rate === null ? '—' : `${(rate * 100).toFixed(2)}%`;
}

/** 该行是否还有可结算的已售量（已售 − 已进结算单的量）。 */
export function unsettleable(line: ConsignmentLine): number {
  return Math.max(0, Number(line.unsettleableQuantity ?? 0));
}

/**
 * 这份协议的财务口径一句话。
 *
 * <p>界面上必须显式说清「未售出不产生应付」，否则使用者会把寄售量当成负债。
 */
export function payableBasisNote(view: ConsignmentStockView): string {
  const unsettled = Number(view.unsettledQuantity ?? 0);
  const total = Number(view.totalConsigned ?? 0);
  if (unsettled <= 0) {
    return `寄售 ${formatQty(total)} 件，当前没有待结算的已售数量 —— 未售出不产生应付。`;
  }
  return `已售未结算 ${formatQty(unsettled)} 件，预计应付 ${formatAmount(view.unsettledAmount, view.currency)}；其余 ${formatQty(Number(view.totalRemaining ?? 0) - unsettled)} 件未售出，不产生应付。`;
}

/** 退货/清理单的性质标签：转采购要显眼，因为它产生真金白银的应付。 */
export function dispositionWarning(
  disposition: string,
  payable: number | string | null | undefined,
  currency: string,
): { tone: 'muted' | 'warn'; text: string } {
  if (disposition === 'CONVERT_TO_PURCHASE') {
    return {
      tone: 'warn',
      text: `期末转我方采购，将产生应付 ${formatAmount(payable, currency)}（协议约定的唯一例外）`,
    };
  }
  return { tone: 'muted', text: '退回供应商，不产生应付' };
}

/** 结算单是否已被确认（确认后明细被数据库冻结，界面不该再给改数量的入口）。 */
export function isSettlementLocked(settlement: Pick<ConsignmentSettlement, 'status'>): boolean {
  return settlement.status === 'CONFIRMED' || settlement.status === 'CANCELLED';
}

/** 协议是否还能记销售 / 出结算单。 */
export function isAgreementActive(agreement: Pick<ConsignmentAgreement, 'status'>): boolean {
  return agreement.status === 'ACTIVE';
}

/** 卖空/未动用的行数，用来提示「这批货已经卖不动了」。 */
export function zeroRemainingLines(view: ConsignmentStockView): number {
  return Number(view.zeroRemainingLineCount ?? 0);
}

/** 审计时间线的事件中文标签。 */
export function auditLabel(eventType: string): string {
  const labels: Record<string, string> = {
    AGREEMENT_CREATED: '建立协议',
    LINE_ADDED: '新增明细行',
    AGREEMENT_ACTIVATED: '启用协议',
    AGREEMENT_SUSPENDED: '暂停协议',
    AGREEMENT_CLOSED: '关闭协议',
    SALE_RECORDED: '记录售出',
    SETTLEMENT_TRIGGERED: '出结算触发单',
    SETTLEMENT_CONFIRMED: '确认结算（交 12 应付）',
    RETURN_CREATED: '建立退货/清理单',
    RETURN_EXECUTED: '执行退货/清理',
    RETURN_CANCELLED: '作废退货/清理单',
  };
  return labels[eventType] ?? eventType;
}

/** 把 ApiError 变成一句能直接展示的话。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '操作失败，请重试。';
}
