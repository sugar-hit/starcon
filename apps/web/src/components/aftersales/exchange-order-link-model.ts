import type {
  ExchangeOrderAuthorizationOption,
  ExchangeOrderCancelRequest,
  ExchangeOrderCapabilities,
  ExchangeOrderCreateRequest,
  ExchangeOrderDetail,
  ExchangeOrderFilterOptions,
  ExchangeOrderLineView,
  ExchangeOrderPage,
  ExchangeOrderRow,
  ExchangeOrderSubmitRequest,
  ExchangeOrderSummary,
  ExchangeOrderSyncRequest,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 11.3.2.2 换货新单前端模型。
 *
 * <p>蓝图：「<b>建立换货关联（原售后 ↔ 新销售单/发货单），跟踪换货闭环状态，防止重复换货。</b>」
 * 输出：「换货关联记录（exchange_order_link）+ 新单状态跟踪。」
 * 关系：「<b>换货新单不修改原出库事实，原单保持完整可追溯。</b>」
 *
 * <p><b>本模型的唯一职责是不让界面替后端编状态</b>。后端已经做到「没有真实发货记录
 * 就绝不置为已发出」，但界面如果把「已建立」显示成一个说不清的灰状态，
 * 运营就会以为「系统正在处理」—— 于是「8 出库根本没接手」这件事永远没人知道。
 * 灰状态是最会骗人的状态。因此：
 *
 * <ul>
 *   <li>{@link closureTone} 把四个闭环状态映射成**互不相同**的语气，
 *       「已建立」是 warning（要人推），不是 neutral（看起来一切正常）；</li>
 *   <li>{@link closureBlockers} 固定把后端下发的「没闭环的真实原因」显示出来，
 *       而不是让页面自己编一句「处理中」；</li>
 *   <li>{@link shipmentBadge} 只在真的有发货记录时显示单号，
 *       没有记录时显示「尚无发货记录」而不是留空 —— 空白会被读成「还没显示」；</li>
 *   <li>{@link syncExplainer} 说清「同步」读的是哪些真实表，
 *       让操作员知道点它不会凭空把状态改成已发出。</li>
 * </ul>
 *
 * <p>路径前缀 {@code trade/aftersales/exchange-orders}，前端路由 {@code /aftersales/exchange-orders}。
 */

const base = 'trade/aftersales/exchange-orders';

// ================================================================ 调用点

export interface ExchangeOrderListParams {
  authorizationId?: string;
  caseId?: string;
  orderId?: string;
  status?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function exchangeOrderListQuery(params: ExchangeOrderListParams): string {
  const parts: string[] = [];
  const put = (k: string, v?: string) => {
    if (v && v.trim()) parts.push(`${k}=${encodeURIComponent(v.trim())}`);
  };
  put('authorizationId', params.authorizationId);
  put('caseId', params.caseId);
  put('orderId', params.orderId);
  put('status', params.status);
  put('keyword', params.keyword);
  put('sort', params.sort);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchExchangeOrderCapabilities() {
  return api<ExchangeOrderCapabilities>(`${base}/capabilities`);
}

export function fetchExchangeOrderFilters() {
  return api<ExchangeOrderFilterOptions>(`${base}/filters`);
}

export function fetchExchangeOrderList(params: ExchangeOrderListParams) {
  return api<ExchangeOrderPage>(`${base}${exchangeOrderListQuery(params)}`);
}

export function fetchExchangeOrderDetail(id: string) {
  return api<ExchangeOrderDetail>(`${base}/${id}`);
}

/** 建换货新单：后端会在 sales_order 里**真建一张单**，所以不能重复提交同一张授权。 */
export function createExchangeOrder(request: ExchangeOrderCreateRequest) {
  return api<ExchangeOrderDetail>(base, 'POST', request);
}

/** 提交进审单队列：带 expectedVersion（乐观锁），两个人同时点时后到的会被 409 拒掉。 */
export function submitExchangeOrder(id: string, request: ExchangeOrderSubmitRequest) {
  return api<ExchangeOrderDetail>(`${base}/${id}/submit`, 'POST', request);
}

/**
 * 同步闭环状态。
 *
 * <p>刻意**不接受任何状态值**：它只去读真实发货记录与旧货回寄要求，然后据此推进。
 * 请求里写「请把状态置成已发货」这种形状在这里不存在 —— 那正是假交付的入口。
 */
export function syncExchangeOrder(id: string, request: ExchangeOrderSyncRequest) {
  return api<ExchangeOrderDetail>(`${base}/${id}/sync`, 'POST', request);
}

/** 撤销换货新单：只在**仍是草稿且从未发货**时允许（作废已提交的单走 9.2 审单驳回）。 */
export function cancelExchangeOrder(id: string, request: ExchangeOrderCancelRequest) {
  return api<ExchangeOrderDetail>(`${base}/${id}/cancel`, 'POST', request);
}

// ================================================================ 语气与徽标

/** Badge 的真实 tone 词表（中性 / 品牌 / 成功 / 警告 / 危险）——前端**不另造一套**颜色词。 */
export type Tone = 'neutral' | 'brand' | 'success' | 'warning' | 'danger';

/**
 * 闭环状态的视觉语气。
 *
 * <p>刻意把「已建立」标成 {@code warning}：它意味着「替换货还没发出去」，
 * 是一张**要人推**的单。用 neutral 会让它看起来和「一切正常」一样，
 * 于是没人管它 —— 而没发出去的换货就是买家在等。
 */
export function closureTone(status?: string | null): Tone {
  switch (status) {
    case 'CLOSED':
      return 'success';
    case 'DISPATCHED':
      return 'brand';
    case 'CANCELLED':
      return 'neutral';
    default:
      return 'warning';
  }
}

export function closureLabel(row: ExchangeOrderRow): string {
  return row.closureStatusLabel || closureLabelOf(row.closureStatus);
}

export function closureLabelOf(status?: string | null): string {
  switch (status) {
    case 'DISPATCHED':
      return '替换货已发出';
    case 'CLOSED':
      return '换货已闭环';
    case 'CANCELLED':
      return '已撤销';
    default:
      return '已建立（待提交履约）';
  }
}

/**
 * 发货情况。
 *
 * <p>没有发货记录时显示「尚无发货记录」而不是留空：空白会被读成「还没显示」，
 * 而真相是「8 出库还没接手」。有记录时连单号一起显示 ——
 * 「已发出」这句话必须能追到一个具体运单，否则无法核实。
 */
export function shipmentBadge(row: ExchangeOrderRow): string {
  if (!row.shipmentCount || row.shipmentCount < 1) return '尚无发货记录';
  const suffix = row.shipmentRecordNo ? ` · ${row.shipmentRecordNo}` : '';
  return `${row.shipmentCount} 条发货记录${suffix}`;
}

export function shipmentTone(row: ExchangeOrderRow): Tone {
  return row.shipmentCount && row.shipmentCount > 0 ? 'success' : 'warning';
}

/**
 * 旧货回寄状态语气：{@code WAITING} 才意味着「还在等买家寄回」，
 * 而那正是「只发不收」敞口还开着。
 */
export function oldGoodsTone(status?: string | null): Tone {
  return status === 'WAITING' ? 'warning' : 'neutral';
}

export function oldGoodsLabel(row: ExchangeOrderRow): string {
  return row.oldGoodsReturnStatusLabel || (row.oldGoodsReturnStatus === 'WAIVED' ? '已结清（免回）' : '等买家寄回');
}

/** 新单单据状态语气：草稿 = 还没进审单队列，是要人推的一步。 */
export function newOrderTone(status?: string | null): Tone {
  return status === 'SUBMITTED' ? 'brand' : 'warning';
}

export function newOrderLabel(row: ExchangeOrderRow): string {
  return row.newOrderStatusLabel || (row.newOrderStatus === 'SUBMITTED' ? '已提交待审' : '草稿');
}

// ================================================================ 没闭环的真实原因

/**
 * 后端下发的「没闭环的真实原因」，逐条显示。
 *
 * <p>刻意**不在前端另编一句「处理中」**：那句话在只发不收的场景里是有害的 ——
 * 它会让「8 出库没接手」看起来像「已经在路上」。
 */
export function closureBlockers(detail: ExchangeOrderDetail | null): string[] {
  return detail?.closureBlockers ?? [];
}

/** 一句话结论：闭环了 / 还差什么。 */
export function closureHeadline(detail: ExchangeOrderDetail | null): string {
  if (!detail) return '';
  if (detail.link.closureStatus === 'CLOSED') return '换货闭环：替换货已发出，旧货已结清。';
  if (detail.link.closureStatus === 'CANCELLED') return '换货新单已撤销。';
  const blockers = detail.closureBlockers;
  if (blockers.length === 0) return '尚未闭环：正在等待下游事实。';
  return `尚未闭环，还差 ${blockers.length} 项：${blockers.join('；')}`;
}

// ================================================================ 可执行动作

/**
 * 能不能提交 / 撤销。
 *
 * <p>只认后端下发的 {@code detail.allowedActions}，<b>前端不自己推断</b>：
 * 「能不能撤」取决于新单是不是草稿、有没有真实发货记录，这些事实只有后端知道。
 */
export function canSubmit(detail: ExchangeOrderDetail | null): boolean {
  if (!detail) return false;
  return (detail.allowedActions ?? []).includes('SUBMIT');
}

export function canCancel(detail: ExchangeOrderDetail | null): boolean {
  if (!detail) return false;
  return (detail.allowedActions ?? []).includes('CANCEL');
}

export function canSync(detail: ExchangeOrderDetail | null): boolean {
  if (!detail) return false;
  return (detail.allowedActions ?? []).includes('SYNC');
}

/** 按钮为什么灰 —— 灰掉不给理由，运营只会以为系统坏了。 */
export function cancelBlocking(detail: ExchangeOrderDetail | null): string | null {
  if (!detail) return '先打开一张换货新单';
  if (canCancel(detail)) return null;
  if (detail.link.closureStatus === 'CANCELLED') return '这张换货新单已撤销，不需要也不能再撤一次';
  if (detail.link.newOrderStatus === 'SUBMITTED') {
    return '新单已提交进审单队列，作废要走 9.2 审单的驳回动作，不能在这里撤销';
  }
  return '当前状态不允许撤销（以后端下发的可执行动作为准）';
}

export function submitBlocking(detail: ExchangeOrderDetail | null): string | null {
  if (!detail) return '先打开一张换货新单';
  if (canSubmit(detail)) return null;
  return '只有「已建立」的换货新单能提交进审单队列';
}

/** 同步按钮旁必须写清它读什么，否则会被当成「手动改状态」。 */
export function syncExplainer(): string {
  return '同步只读取真实发货记录（9.4.1.1）与旧货回寄要求（11.3.2.1）后据此推进状态，不接受任何手工指定的状态。';
}

// ================================================================ 硬规则与下游

/**
 * 后端下发的硬规则原文，逐条显示。
 *
 * <p>刻意**不在前端另写一份**：前端硬编码一份就等于有两个真相。
 */
export function hardRules(capabilities: ExchangeOrderCapabilities | null): string[] {
  return capabilities?.hardRules ?? [];
}

/**
 * 未交付的下游：如实写出归属模块，而不是留一个空白让人以为是加载失败。
 *
 * <p>这一条对本模块尤其重要：「已闭环」当前只可能来自「已发出 + 旧货经批准免回」，
 * 旧货的**实收与质检**还没有状态机 —— 不写清楚就会被读成「旧货已入库」。
 */
export function downstreamNotices(capabilities: ExchangeOrderCapabilities | null): string[] {
  if (!capabilities) return [];
  const out: string[] = [];
  if (!capabilities.outboundHandoffDelivered) {
    out.push(
      `新出库接手（${capabilities.outboundHandoffOwnerModule}）尚未交付：换货新单已建，但出库单要等该模块接手后才会真正生成，页面上的「已发出」只承认真实的发货记录。`,
    );
  }
  if (!capabilities.oldGoodsReturnReceivedDelivered) {
    out.push(
      `旧货实收与质检（${capabilities.oldGoodsReturnOwnerModule}）尚未交付：「已闭环」当前只能来自「已发出 + 旧货经批准免回」，不代表旧货已入库验收。`,
    );
  }
  return out;
}

// ================================================================ 展示工具

export function formatTime(value?: string | null): string {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * 数量格式化：后端一律给字符串（BigDecimal，全程禁浮点），前端**不**转成 number 再算。
 *
 * <p>把 6 位小数的数量塞进 JS number 再四舍五入，换货这种要精确到件的场景会出现
 * 「授权 2 件、界面显示 2.000001 件」这种看得出、账上对不上的显示。
 */
export function formatQty(value?: string | null): string {
  if (!value) return '0';
  const trimmed = value.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed === '' || trimmed === '-0' ? '0' : trimmed;
}

/** 金额格式化（scale 4）。换货新单金额恒为 0 —— 显示出来是为了让人看见「不二次收费」。 */
export function formatAmount(value?: string | null): string {
  if (!value) return '0.0000';
  const trimmed = value.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed === '' ? '0' : trimmed;
}

/** 拿不到的值统一显示为「—」，不用空白也不用 0 —— 0 是一个真实的数。 */
export function unavailable(value?: string | null): string {
  return value && value.trim() ? value : '—';
}

/** 汇总里最该被看见的数：还没发出的单（要人推）。 */
export function attentionSummary(summary: ExchangeOrderSummary | null | undefined): {
  pendingDispatch: number;
  totalQuantity: string;
} {
  return {
    pendingDispatch: Number(summary?.pendingDispatchCount ?? 0),
    totalQuantity: formatQty(summary?.totalQuantity),
  };
}

/** 空态文案要说清「为什么是空的」以及「怎么会有」。 */
export function emptyHint(capabilities: ExchangeOrderCapabilities | null): string {
  if (capabilities && !capabilities.exchangeAuthorizationDelivered) {
    return '换货授权（11.3.2.1）尚未接入，换货新单还没有可用的来源。';
  }
  return '还没有换货新单。先到「换货授权」里批准一张换货授权，然后在这里为它建立新的销售单。';
}

/** 下拉项的可用性提示：原单没有收货地址快照时建单必然被拒，提前说清而不是让人填完再收 422。 */
export function authorizationBlocker(option?: ExchangeOrderAuthorizationOption | null): string | null {
  if (!option) return null;
  if (!option.hasShippingSnapshot) {
    return '原订单没有收货地址快照，无法建立换货新单：换货新单的收货地址只能照抄原单。';
  }
  return null;
}

export type {
  ExchangeOrderAuthorizationOption,
  ExchangeOrderDetail,
  ExchangeOrderFilterOptions,
  ExchangeOrderLineView,
  ExchangeOrderPage,
  ExchangeOrderRow,
  ExchangeOrderSummary,
};
