import type {
  ExchangeAuthorizeRequest,
  ExchangeCancelRequest,
  ExchangeCapabilities,
  ExchangeCaseOption,
  ExchangeDetail,
  ExchangeFilterOptions,
  ExchangeLineView,
  ExchangePage,
  ExchangeRow,
  ExchangeSummary,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 11.3.2.1 换货授权前端模型。
 *
 * <p>蓝图：「<b>批准换货：确认可换数量与库存（要真实预占替换货）、生成新出库安排，
 * 保留原单追溯关系。</b>」
 * 输出：「换货授权 + 新发货需求 + 替换货预占。」
 * 加粗句：「<b>换货必须同时预占替换货并等待旧货回寄，否则会出现只发不收的损失。</b>」
 *
 * <p><b>本模型的唯一职责是让那句加粗句在界面上也成立</b>。后端已经用「预占失败即整笔失败」
 * 与两条迟到约束触发器挡住了只发不收，但界面若把「预占」显示成一个说不清状态的标签，
 * 运营就会以为「批了就行、货慢慢发」——<b>界面是最后一道会误导人的关口</b>。因此：
 *
 * <ul>
 *   <li>{@link reservationBadge} 把预占状态映射成**互不相同**的语气，
 *       并把「还没有预占记录」与「预占中」区分开 —— 后者意味着货真的被圈住了；</li>
 *   <li>{@link waitingReturnBadge} 单独显示旧货回寄状态，
 *       让「发了替换货、旧货还没回来」在列表上一眼可见；</li>
 *   <li>{@link hardRules} 固定把后端下发的硬规则原文显示在授权对话框里，
 *       运营点下「批准」之前先看到后果；</li>
 *   <li>{@link canCancel} / {@link cancelBlocking} 让「为什么现在不能撤销」有答案，
 *       而不是让按钮凭空消失。</li>
 * </ul>
 *
 * <p>路径前缀 {@code trade/aftersales/exchanges}，前端路由 {@code /aftersales/exchanges}。
 */

const base = 'trade/aftersales/exchanges';

// ================================================================ 调用点

export interface ExchangeListParams {
  caseId?: string;
  orderId?: string;
  skuId?: string;
  status?: string;
  decision?: string;
  keyword?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export function exchangeListQuery(params: ExchangeListParams): string {
  const parts: string[] = [];
  const put = (k: string, v?: string) => {
    if (v && v.trim()) parts.push(`${k}=${encodeURIComponent(v.trim())}`);
  };
  put('caseId', params.caseId);
  put('orderId', params.orderId);
  put('skuId', params.skuId);
  put('status', params.status);
  put('decision', params.decision);
  put('keyword', params.keyword);
  put('sort', params.sort);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchExchangeCapabilities() {
  return api<ExchangeCapabilities>(`${base}/capabilities`);
}

export function fetchExchangeFilters() {
  return api<ExchangeFilterOptions>(`${base}/filters`);
}

export function fetchExchangeList(params: ExchangeListParams) {
  return api<ExchangePage>(`${base}${exchangeListQuery(params)}`);
}

export function fetchExchangeDetail(id: string) {
  return api<ExchangeDetail>(`${base}/${id}`);
}

export function authorizeExchange(request: ExchangeAuthorizeRequest) {
  return api<ExchangeDetail>(base, 'POST', request);
}

/** 撤销换货：后端会**真去仓库释放预占**，所以必须带 expectedVersion（乐观锁）。 */
export function cancelExchange(id: string, request: ExchangeCancelRequest) {
  return api<ExchangeDetail>(`${base}/${id}/cancel`, 'POST', request);
}

// ================================================================ 语气与徽标

/** Badge 的真实 tone 词表（中性 / 品牌 / 成功 / 警告 / 危险）——前端**不另造一套**颜色词。 */
export type Tone = 'neutral' | 'brand' | 'success' | 'warning' | 'danger';

/**
 * 预占状态的视觉语气。
 *
 * <p>刻意把「还没有预占记录」单列成一档 {@code danger}：那不是「还没开始」，
 * 那是**这一行处于只发不收的危险状态** —— 替换货已经授权了，却没圈住货。
 */
export function reservationTone(status?: string | null): Tone {
  if (status === 'HELD') return 'success';
  if (status === 'RELEASED') return 'neutral';
  return 'danger';
}

export function reservationLabel(status?: string | null): string {
  if (status === 'HELD') return '已预占';
  if (status === 'RELEASED') return '已释放';
  return '未预占（危险）';
}

export function reservationBadge(line: ExchangeLineView): string {
  const suffix = line.reservationNo ? ` · ${line.reservationNo}` : '';
  return `${reservationLabel(line.reservationStatus)}${suffix}`;
}

/** 旧货回寄要求的状态语气。WAITING 才意味着「还在等买家寄回」。 */
export function waitingReturnTone(status?: string | null): Tone {
  if (status === 'WAITING') return 'warning';
  if (status === 'WAIVED') return 'neutral';
  return 'neutral';
}

export function waitingReturnLabel(status?: string | null): string {
  if (status === 'WAITING') return '等旧货回寄';
  if (status === 'WAIVED') return '已免回';
  return '未登记';
}

export function waitingReturnBadge(line: ExchangeLineView): string {
  const qty = line.waitingReturnQuantity;
  if (line.returnRequirementStatus === 'WAITING') {
    return qty ? `${waitingReturnLabel(line.returnRequirementStatus)} ${qty}` : waitingReturnLabel(line.returnRequirementStatus);
  }
  return waitingReturnLabel(line.returnRequirementStatus);
}

/** 授权结论语气：部分批准是**要人去处理**的状态，不能显示成成功。 */
export function decisionTone(decision?: string | null): Tone {
  return decision === 'PARTIALLY_APPROVED' ? 'warning' : 'success';
}

export function decisionBadge(row: ExchangeRow): string {
  return row.decisionLabel || (row.decision === 'PARTIALLY_APPROVED' ? '部分批准（库存不足）' : '整量批准');
}

export function statusBadge(row: ExchangeRow): string {
  return row.statusLabel || (row.status === 'CANCELLED' ? '已撤销' : '生效中');
}

export function statusTone(status?: string | null): Tone {
  return status === 'CANCELLED' ? 'neutral' : 'brand';
}

// ================================================================ 可执行动作

/**
 * 能不能撤销。
 *
 * <p>只认后端下发的 {@code detail.allowedActions}，<b>前端不自己推断</b>：
 * 「能不能撤销」取决于预占是否已释放、版本是否匹配，这些事实只有后端知道。
 */
export function canCancel(detail: ExchangeDetail | null): boolean {
  if (!detail) return false;
  return (detail.allowedActions ?? []).includes('CANCEL');
}

/** 按钮为什么灰 —— 灰掉不给理由，运营只会以为系统坏了。 */
export function cancelBlocking(detail: ExchangeDetail | null): string | null {
  if (!detail) return '先打开一张换货授权单';
  if (canCancel(detail)) return null;
  if (detail.authorization.status === 'CANCELLED') {
    return '这张换货授权单已撤销，预占已经释放，不需要也不能再撤一次';
  }
  return '当前状态不允许撤销（以后端下发的可执行动作为准）';
}

// ================================================================ 硬规则与下游

/**
 * 后端下发的硬规则原文，逐条显示。
 *
 * <p>刻意**不在前端另写一份**：前端硬编码一份就等于有两个真相，
 * 后端改了一句加粗句而前端没改，界面上就会继续讲着旧规则。
 */
export function hardRules(capabilities: ExchangeCapabilities | null): string[] {
  return capabilities?.hardRules ?? [];
}

/** 未交付的下游：如实写出归属模块，而不是留一个空白让人以为是加载失败。 */
export function downstreamNotices(capabilities: ExchangeCapabilities | null): string[] {
  if (!capabilities) return [];
  const out: string[] = [];
  if (!capabilities.outboundHandoffDelivered) {
    out.push(
      `新出库接手（${capabilities.outboundHandoffOwnerModule}）尚未交付：换货授权只立下发货需求，出库单要等该模块接手后才会真正生成。`,
    );
  }
  if (!capabilities.oldGoodsReturnDelivered) {
    out.push(
      `旧货回寄实收（${capabilities.oldGoodsReturnOwnerModule}）尚未交付：这里只登记「等旧货回寄」的要求，实收与质检归该模块。`,
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
 * 「申请 2 件、预占 1.999999 件」这种界面上看得出、账上对不上的显示。
 */
export function formatQty(value?: string | null): string {
  if (!value) return '0';
  const trimmed = value.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed === '' || trimmed === '-0' ? '0' : trimmed;
}

/** 拿不到的值统一显示为「—」，不用空白也不用 0 —— 0 是一个真实的数。 */
export function unavailable(value?: string | null): string {
  return value && value.trim() ? value : '—';
}

/** 汇总里最该被看见的两个数：等回寄的量与待发货的量。 */
export function attentionSummary(summary: ExchangeSummary | null | undefined): {
  waitingReturn: number;
  pendingOutbound: number;
} {
  return {
    waitingReturn: Number(summary?.totalWaitingReturnQuantity ?? 0),
    pendingOutbound: Number(summary?.totalPendingOutboundQuantity ?? 0),
  };
}

/** 空态文案要说清「为什么是空的」以及「怎么会有」。 */
export function emptyHint(capabilities: ExchangeCapabilities | null): string {
  if (capabilities && !capabilities.caseDelivered) {
    return '售后单（11.1）尚未接入，换货授权还没有可批准的来源。';
  }
  return '还没有换货授权。到「售后单」里挑一张类型为「换货」且未结案的售后单，在这里批准换货。';
}

export type { ExchangeCaseOption, ExchangeDetail, ExchangeLineView, ExchangePage, ExchangeRow, ExchangeSummary };
