import type {
  PackingCapabilities,
  PackingCompletionReport,
  PackingDetail,
  PackingMaterialInput,
  PackingMaterialPage,
  PackingPage,
  ParcelAddInput,
  ParcelItemInput,
  ParcelLabelInput,
  ParcelMeasureInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 8.3.3.2 打包与称重前端模型。
 *
 * <p>蓝图 08 卷 8.3.3.2：「<b>打包作业：选择包材、填充物、打印面单、称重与体积测量、记录包装尺寸
 * （用于体积重与平台申报）。</b>输入：复核结果；包材选择；称重数据；申报信息（3.7.2 物理属性）。
 * 输出：包裹记录（parcel + parcel_item）+ 实测重量体积。」
 * 关系：「上游 = 8.3.3.1；下游 = 8.3.4.1 发货、10 物流（取重算费）。
 * <b>包裹必须含明细项</b>（哪些 SKU、多少件），只有重量和运单号无法追溯与对账。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>没量 ≠ 量出来是 0。</b>{@code measuredGrossWeightG} / {@code volumeCbm} 未测时是
 *       {@code null}，页面显示「未称重 / 未量尺寸」而不是「0 g / 0 cbm」——
 *       后者会被读成「这个箱子重 0 克」。</li>
 *   <li><b>净重与体积不自己算。</b>{@code netContentWeightG} / {@code volumeCbm} 由后端触发器
 *       从毛重与三边派生；页面展示即可，在前端再乘一遍会与账上的数差一位。</li>
 *   <li><b>体积重不显示、也不试算。</b>除数（6000/5000/…）归 10 物流，界面只展示实测三边与
 *       实测体积，并把「体积重由 10 物流按渠道除数算」写在口径行上。</li>
 *   <li><b>「完成」按钮的可点性由后端说了算。</b>{@code PackingCompletionReport.packable} 与
 *       {@code blockingReasons} 是唯一判据，页面不自己数行、不自己比件数。</li>
 *   <li><b>完成 ≠ 已发货。</b>文案说「交给 8.3.4.1 待发货」，状态词表里没有 SHIPPED。</li>
 *   <li><b>明细行永远跟着包裹展示。</b>「哪些 SKU、多少件」是这个模块存在的理由，
 *       界面不允许折叠成「3 件」了事。</li>
 *   <li><b>申报信息读不到就说读不到。</b>{@code declarationSource === 'NOT_FOUND'} 时显示
 *       「本服务无报关明细」，<b>不显示空白的 HS 编码输入框</b>冒充已带出。</li>
 *   <li>加载 / 空 / 错误三态分开渲染。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code warehouse/packing-orders}，刻意不挂在 {@code outbound-orders}
 * 之下 —— 一张出库单可以先打包失败退回复核、再重打包，每次是一张独立的打包单。
 */

const base = 'warehouse/packing-orders';

export type PackingStatus = 'OPEN' | 'PACKED' | 'FAILED' | 'CANCELLED';
export type MaterialRole = 'PACKAGING' | 'CUSHIONING';
export type MaterialType =
  | 'CARTON'
  | 'ENVELOPE'
  | 'BAG'
  | 'BOX'
  | 'BUBBLE'
  | 'PAPER'
  | 'FOAM'
  | 'OTHER';
export type DeclarationSource = 'CUSTOMS_DECLARATION_LINE' | 'NOT_FOUND';

export interface PackingParams {
  warehouseId?: string;
  outboundOrderId?: string;
  recountSessionId?: string;
  status?: string;
  packerUserId?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function packingQuery(params: PackingParams): string {
  const parts: string[] = [];
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.outboundOrderId)
    parts.push(`outboundOrderId=${encodeURIComponent(params.outboundOrderId)}`);
  if (params.recountSessionId)
    parts.push(`recountSessionId=${encodeURIComponent(params.recountSessionId)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.packerUserId) parts.push(`packerUserId=${encodeURIComponent(params.packerUserId)}`);
  if (params.keyword) parts.push(`keyword=${encodeURIComponent(params.keyword)}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function packingCapabilities() {
  return api<PackingCapabilities>(`${base}/capabilities`);
}

export function packingPage(params: PackingParams) {
  return api<PackingPage>(`${base}${packingQuery(params)}`);
}

export function packingDetail(id: string) {
  return api<PackingDetail>(`${base}/${id}`);
}

export function openPackingOrder(recountSessionId: string, packerUserId?: string, note?: string) {
  return api(`${base}`, 'POST', { recountSessionId, packerUserId, note } as PackingOpenBody);
}

export function createMaterial(input: PackingMaterialInput) {
  return api(`${base}/materials`, 'POST', input);
}

export function materialPage(params: {
  warehouseId?: string;
  role?: string;
  keyword?: string;
  page?: number;
  size?: number;
}) {
  const parts: string[] = [];
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.role) parts.push(`role=${encodeURIComponent(params.role)}`);
  if (params.keyword) parts.push(`keyword=${encodeURIComponent(params.keyword)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  const qs = parts.length ? `?${parts.join('&')}` : '';
  return api<PackingMaterialPage>(`${base}/materials${qs}`);
}

export function retireMaterial(id: string, reason: string) {
  return api(`${base}/materials/${id}/retire`, 'POST', { reason });
}

export function addParcel(id: string, input: ParcelAddInput) {
  return api(`${base}/${id}/parcels`, 'POST', input);
}

export function addParcelItem(id: string, parcelId: string, input: ParcelItemInput) {
  return api(`${base}/${id}/parcels/${parcelId}/items`, 'POST', input);
}

export function measureParcel(id: string, parcelId: string, input: ParcelMeasureInput) {
  return api(`${base}/${id}/parcels/${parcelId}/measure`, 'POST', input);
}

export function recordParcelLabel(id: string, parcelId: string, input: ParcelLabelInput) {
  return api(`${base}/${id}/parcels/${parcelId}/label`, 'POST', input);
}

export function completePackingOrder(id: string, packerUserId?: string, note?: string) {
  return api(`${base}/${id}/complete`, 'POST', { packerUserId, note });
}

export function failPackingOrder(id: string, reason: string) {
  return api(`${base}/${id}/fail`, 'POST', { reason });
}

export function cancelPackingOrder(id: string, reason: string) {
  return api(`${base}/${id}/cancel`, 'POST', { reason });
}

interface PackingOpenBody {
  recountSessionId: string;
  packerUserId?: string;
  note?: string;
}

/** 未测的量显示「未称重 / 未量尺寸」，**不显示 0** —— 0 会被读成「量出来是 0」。 */
export function measuredCell(value: string | null | undefined, fallback = '未量'): string {
  if (value === null || value === undefined || value === '') return fallback;
  return value;
}

/** 净重/体积是后端派生量；缺失就是没测，页面不自己算。 */
export function derivedCell(value: string | null | undefined, fallback = '—'): string {
  if (value === null || value === undefined || value === '') return fallback;
  return value;
}

/**
 * 申报信息读不到时**不显示空白输入框**：显示归属方与原因，
 * 而不是给一个看着能填、实际没人能填的 HS 编码框（照 8.3.1.2 的灰块约定）。
 */
export function declarationCell(
  source: DeclarationSource | null | undefined,
  hsCode: string | null | undefined,
): { text: string; missing: boolean } {
  if (source === 'CUSTOMS_DECLARATION_LINE' && hsCode) {
    return { text: hsCode, missing: false };
  }
  return {
    text: '本服务无报关明细（3.7.2 物理属性属 catalog，未接入）',
    missing: true,
  };
}

/** 「完成」按钮的可点性只认后端的 packable；页面不自己数行、不自己比件数。 */
export function completionVerdict(report: PackingCompletionReport | null | undefined): {
  packable: boolean;
  reasons: string[];
  warnings: string[];
} {
  if (!report) return { packable: false, reasons: ['尚未加载完成判据'], warnings: [] };
  return {
    packable: report.packable === true,
    reasons: report.blockingReasons ?? [],
    warnings: report.warnings ?? [],
  };
}

/**
 * 打包单状态的中文标签。**刻意没有「已发货」** —— 那是 8.3.4.1 的，
 * 在这里加一个会让作业员以为货已经发出去了。
 */
export function packingStatusLabel(status: PackingStatus | string | null | undefined): string {
  switch (status) {
    case 'OPEN':
      return '打包中';
    case 'PACKED':
      return '已打包';
    case 'FAILED':
      return '打包失败';
    case 'CANCELLED':
      return '已取消';
    default:
      return status ?? '—';
  }
}

export function materialRoleLabel(role: MaterialRole | string | null | undefined): string {
  return role === 'PACKAGING' ? '包材' : role === 'CUSHIONING' ? '填充物' : (role ?? '—');
}

export function declarationSourceLabel(
  source: DeclarationSource | string | null | undefined,
): string {
  return source === 'CUSTOMS_DECLARATION_LINE'
    ? '取自报关单明细'
    : '本服务无报关明细（3.7.2 物理属性属 catalog，未接入）';
}

/**
 * 完成后的下一步提示：只说「交到待发货」，**不说已发货**（运单号与承运渠道是 8.3.4.1 的）。
 */
export function shippedHandoffNote(): string {
  return '打包完成，出库单已交到「待发货」（8.3.4.1 发货交接）—— 运单号与承运渠道由下游登记。';
}

/** 明细行摘要：「哪些 SKU、多少件」—— 这个模块存在的理由，界面上不许折叠掉。 */
export function itemsDigest(
  items: ReadonlyArray<{ skuCode: string; quantity: string | null | undefined }>,
): string {
  if (!items.length) return '未装任何明细';
  return items.map((i) => `${i.skuCode} × ${i.quantity ?? '—'}`).join('、');
}