import type {
  HandoverCancelInput,
  HandoverConfirmInput,
  HandoverOpenInput,
  HandoverWaybillInput,
  ShipmentHandoverCapabilities,
  HandoverConfirmed,
  HandoverDetail,
  HandoverPage,
  ParcelWaybillEntry,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 8.3.4.1 发货交接前端模型。
 *
 * <p>蓝图 08 卷 8.3.4.1：「<b>发货交接：确认承运商与运单号、登记交接时间与交接人、扣减库存完成出库、
 * 触发物流轨迹与平台回传。</b>输入：包裹与运单号；承运渠道；交接确认。
 * 输出：出库完成分录（预占转实扣）+ 发货事件 → 9 订单状态、10 轨迹、2 平台回写。」
 * 关系：「上游 = 8.3.3.2；下游 = 7 库存、9 订单、10 物流、2 渠道回写。
 * <b>实发扣减与预占消费在同一事务</b>，避免预占悬空或重复扣减。」
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>承运商与运单号是两个输入，不是一个。</b>只有承运商没有号 = 车来了但没人知道
 *       运的是哪一单；只有号没有渠道 = 不知道谁运的。所以表单里两者分开必填，
 *       页面上也不把它们并成一个「物流信息」字段。</li>
 *   <li><b>「确认交接」不是「确认信息」，它会真的扣库存。</b>按钮文案与二次确认都写明
 *       「将从本仓出库并消费预占」，避免作业员把它当成普通的保存。</li>
 *   <li><b>两个「预占量」是<b>两本账</b>，页面分开显示。</b>{@code reservedDeductedQuantity}
 *       是<b>实物预占侧</b>被释放的量（7.1.1.1），{@code reservations} 是<b>承诺侧</b>
 *       被消费的分配（7.2.1.2）。合成一个数字就答不出「这批货有没有人订走」。</li>
 *   <li><b>「未预占出库」如实显示。</b>没有可消费的预占时显示「本次未预占出库」，
 *       <b>不显示</b>「预占已全部消耗」—— 后者是对没发生过的事的说法。</li>
 *   <li><b>按钮可点性由后端说了算。</b>{@code HandoverCompletionReport.handableOver} 与
 *       {@code blockingReasons} 是唯一判据，页面不自己算可用量、不自己数行。</li>
 *   <li><b>已交接 ≠ 已送达。</b>状态词表里没有「已签收」：轨迹与签收归 10 物流（未交付），
 *       在这里加一个会让作业员以为货已经到买家手上。</li>
 *   <li><b>明细永远跟着包裹展示。</b>「交出去的是哪几箱、每箱装了什么」是这个模块存在的理由，
 *       界面不允许折叠成「共 3 件」了事。</li>
 *   <li>加载 / 空 / 错误三态分开渲染。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code warehouse/shipment-handovers}，刻意不挂在 {@code outbound-orders}
 * 之下 —— 出库单是履约单据，交接是现场作业凭据，要自带冻结的包裹、实测重量体积、
 * 交接人与交接时刻。
 */

const base = 'warehouse/shipment-handovers';

export type HandoverStatus = 'OPEN' | 'HANDED_OVER' | 'CANCELLED';

export interface HandoverParams {
  warehouseId?: string;
  outboundOrderId?: string;
  packingOrderId?: string;
  status?: string;
  carrierCode?: string;
  handoverUserId?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function handoverQuery(params: HandoverParams): string {
  const parts: string[] = [];
  if (params.warehouseId) parts.push(`warehouseId=${encodeURIComponent(params.warehouseId)}`);
  if (params.outboundOrderId)
    parts.push(`outboundOrderId=${encodeURIComponent(params.outboundOrderId)}`);
  if (params.packingOrderId)
    parts.push(`packingOrderId=${encodeURIComponent(params.packingOrderId)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.carrierCode) parts.push(`carrierCode=${encodeURIComponent(params.carrierCode)}`);
  if (params.handoverUserId)
    parts.push(`handoverUserId=${encodeURIComponent(params.handoverUserId)}`);
  if (params.keyword) parts.push(`keyword=${encodeURIComponent(params.keyword)}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function handoverCapabilities() {
  return api<ShipmentHandoverCapabilities>(`${base}/capabilities`);
}

export function handoverPage(params: HandoverParams) {
  return api<HandoverPage>(`${base}${handoverQuery(params)}`);
}

export function handoverDetail(id: string) {
  return api<HandoverDetail>(`${base}/${id}`);
}

export function handoverCompletion(id: string) {
  return api(`${base}/${id}/completion`);
}

export function openHandover(input: HandoverOpenInput) {
  return api(`${base}`, 'POST', input);
}

export function registerWaybills(id: string, input: HandoverWaybillInput) {
  return api(`${base}/${id}/waybills`, 'POST', input);
}

export function confirmHandover(id: string, input: HandoverConfirmInput) {
  return api<HandoverConfirmed>(`${base}/${id}/confirm`, 'POST', input);
}

export function cancelHandover(id: string, input: HandoverCancelInput) {
  return api(`${base}/${id}/cancel`, 'POST', input);
}

/** 运单号与承运渠道**分开**校验：两者缺一不可，页面不替后端合并成一个字段。 */
export function waybillInputOf(form: {
  carrierCode: string;
  carrierName: string;
  masterWaybillNo: string;
  receiverName: string;
  receiverContact: string;
  parcelWaybills?: Record<string, string>;
}): { ok: true; body: HandoverWaybillInput } | { ok: false; message: string } {
  const carrier = form.carrierCode.trim();
  const waybill = form.masterWaybillNo.trim();
  if (!carrier) return { ok: false, message: '承运渠道不能为空：确认承运商是交接的前提' };
  if (!waybill)
    return { ok: false, message: '运单号不能为空：只有承运商没有运单号，就不知道运的是哪一单' };
  const entries: ParcelWaybillEntry[] = Object.entries(form.parcelWaybills ?? {})
    .filter(([, v]) => v.trim().length > 0)
    .map(([parcelId, v]) => ({ parcelId, parcelWaybillNo: v.trim() }));
  return {
    ok: true,
    body: {
      carrierCode: carrier,
      carrierName: blankToNull(form.carrierName),
      masterWaybillNo: waybill,
      receiverName: blankToNull(form.receiverName),
      receiverContact: blankToNull(form.receiverContact),
      parcelWaybillNos: entries.length ? entries : null,
      note: null,
    },
  };
}

/**
 * 每箱的**有效运单号**：逐箱登记了就用逐箱的，没登记就跟随单头主运单号。
 *
 * <p>「跟随主运单号」在界面上必须**显示出来**而不是留空 —— 留空会被读成
 * 「这一箱没运单号」，而真实含义是「这一箱随整车走同一个号」。
 */
export function effectiveWaybill(
  own: string | null | undefined,
  masterWaybillNo: string | null | undefined,
): { text: string; inherited: boolean; missing: boolean } {
  const o = (own ?? '').trim();
  if (o) return { text: o, inherited: false, missing: false };
  const m = (masterWaybillNo ?? '').trim();
  if (m) return { text: `${m}（随主运单号）`, inherited: true, missing: false };
  return { text: '未登记运单号', inherited: false, missing: true };
}

/**
 * 空串归一成 null。
 *
 * <p>刻意**不是** `undefined`：codegen 产出的契约字段是 `string | null`，而本仓库开着
 * {@code exactOptionalPropertyTypes} —— 传 `undefined` 过不了 tsc，而传 `''` 会让后端
 * 收到一个「已登记的空值」。
 */
function blankToNull(value: string): string | null {
  const t = value.trim();
  return t.length > 0 ? t : null;
}

/** 未登记的量显示「未登记」，**不显示空字符串也不显示 0** —— 0 会被读成「登记了一个 0」。 */
export function registeredCell(value: string | null | undefined, fallback = '未登记'): string {
  if (value === null || value === undefined || value.trim() === '') return fallback;
  return value;
}

/** 「确认交接」按钮的可点性只认后端的 handableOver；页面不自己算可用量。 */
export function handoverVerdict(report: HandoverDetail['completion'] | null | undefined): {
  handable: boolean;
  reasons: string[];
  warnings: string[];
} {
  if (!report) return { handable: false, reasons: ['尚未加载交接判据'], warnings: [] };
  return {
    handable: report.handableOver === true,
    reasons: report.blockingReasons ?? [],
    warnings: report.warnings ?? [],
  };
}

/**
 * 交接单状态的中文标签。**刻意没有「已签收」「已送达」** —— 轨迹与签收归 10 物流，
 * 在这里加一个会让作业员以为货已经到买家手上。
 */
export function handoverStatusLabel(status: HandoverStatus | string | null | undefined): string {
  switch (status) {
    case 'OPEN':
      return '交接中';
    case 'HANDED_OVER':
      return '已交接';
    case 'CANCELLED':
      return '已作废';
    default:
      return status ?? '—';
  }
}

/**
 * 出库分录摘要：本次交接到底从哪些库存行扣了多少。
 *
 * <p>「负号 + 负号」读起来别扭，所以这里转成「出库 X 件、释放预占 Y 件」的措辞 ——
 * 但**数字仍来自后端**，页面不自己把两个 delta 加减一遍。
 */
export function ledgerDigest(
  entries: ReadonlyArray<{
    onHandDelta: string | null | undefined;
    reservedDelta: string | null | undefined;
  }>,
): string {
  if (!entries.length) return '尚未产生出库分录';
  return entries
    .map((e) => {
      const out = String(e.onHandDelta ?? '0');
      const released = String(e.reservedDelta ?? '0');
      const releasedPart = released === '0' ? '' : `，释放预占 ${released}`;
      return `出库 ${out}${releasedPart}`;
    })
    .join('；');
}

/**
 * 交接后的下一步说明：只说「交给 10 轨迹 / 2 平台回传消费本单流水」，
 * **不承诺**它们已经做了（本服务不跨服务调用它们）。
 */
export function handedOverNote(ledgerCount: number): string {
  return (
    `交接已完成：出库 ${ledgerCount} 条分录，出库单已置为已发货。` +
    '物流轨迹与平台回传由 10 物流与 2 渠道消费本单流水 —— 它们尚未在本页发生。'
  );
}
