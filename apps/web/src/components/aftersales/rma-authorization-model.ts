/**
 * 11.2.1.1 退货授权单的前端数据层。
 *
 * 蓝图 11-aftersales-and-returns.md：「审批退货申请：确认可退数量（原订单行已退/已发数量约束）、
 * 退货地址、买家回寄物流、目标处理方式；部分退货需说明理由。」
 * 加粗句：「<b>授权不等于已收到</b>——授权后货可能在路上或根本没寄回，需要超时未寄自动关闭。」
 *
 * <p><b>这一层对那条加粗句负三件事</b>：
 *
 * <ol>
 *   <li><b>页面顶部固定有一张「在途 / 已超时」统计卡</b>：授权已发出、货还在路上或没寄回的单，
 *       是这份工作面真正的待办队列 —— 运营打开页面先看到的应该是「有几张单在等买家寄回」。</li>
 *   <li><b>不渲染任何「已收到」的控件</b>：{@code received} 恒为 false 且带归属模块
 *       （11.2.2.1 未交付）。前端<b>不自己推断</b>能不能登记实收。</li>
 *   <li><b>已退数量显示为「不可得」而不是 0</b>：填 0 会让运营以为「这行从来没退过」，
 *       从而在重复退货面前失去唯一的提醒。</li>
 * </ol>
 */
import type {
  RmaActionEntry,
  RmaBoundary,
  RmaCapabilities,
  RmaDecisionEvent,
  RmaDetail,
  RmaExpireResult,
  RmaHead,
  RmaLine,
  RmaPage,
  RmaReturnableLine,
  RmaReturnableReport,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';

/** 与后端 {@code /trade/rma-authorizations} 同前缀。 */
const base = 'trade/rma-authorizations';

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  // ⚠️ exactOptionalPropertyTypes 开着（AGENTS.md）：不能把 `headers: undefined`
  //   直接塞进 RequestInit —— 那不是「没传」，而是「传了个 undefined」。
  //   所以这里按有没有 body 分成两种完整对象，而不是合并一个可能半空的。
  const response = init
    ? await fetch(`/api/${path}`, {
        credentials: 'include',
        ...init,
        ...(init.body ? { headers: { 'Content-Type': 'application/json' } } : {}),
      })
    : await fetch(`/api/${path}`, { credentials: 'include' });
  if (!response.ok) {
    // ApiError 的签名是 (status, message, body)。
    throw new ApiError(response.status, await response.text());
  }
  return (await response.json()) as T;
}

export const fetchRmaCapabilities = () => call<RmaCapabilities>(`${base}/capabilities`);

export const fetchRmaList = (params: {
  page?: number;
  size?: number;
  status?: string;
  decision?: string;
  disposition?: string;
  overdueOnly?: boolean;
  keyword?: string;
  sort?: string;
}) => {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '' || value === null) continue;
    query.set(key, String(value));
  }
  const suffix = query.toString();
  return call<RmaPage>(suffix ? `${base}?${suffix}` : base);
};

export const fetchRmaDetail = (id: string) => call<RmaDetail>(`${base}/${id}`);

export const fetchReturnableQuantities = (caseId: string) =>
  call<RmaReturnableReport>(`${base}/returnable-quantities?caseId=${caseId}`);

export const createRma = (body: unknown) =>
  call<RmaDetail>(base, { method: 'POST', body: JSON.stringify(body) });

export const updateRma = (id: string, body: unknown) =>
  call<RmaDetail>(`${base}/${id}`, { method: 'PUT', body: JSON.stringify(body) });

export const decideRma = (id: string, body: unknown) =>
  call<RmaDetail>(`${base}/${id}/decide`, { method: 'POST', body: JSON.stringify(body) });

export const recordReturnLogistics = (id: string, body: unknown) =>
  call<RmaDetail>(`${base}/${id}/return-logistics`, { method: 'POST', body: JSON.stringify(body) });

export const cancelRma = (id: string, body: unknown) =>
  call<RmaDetail>(`${base}/${id}/cancel`, { method: 'POST', body: JSON.stringify(body) });

export const expireOverdue = () => call<RmaExpireResult>(`${base}/expire-overdue`, { method: 'POST' });

// ================================================================ 展示口径

/** 状态 → 徽标色调。终态与在途必须一眼分得开（在途=还要管，终态=办完了）。 */
export function stateTone(status: string): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  switch (status) {
    case 'AUTHORIZED':
    case 'PARTIALLY_AUTHORIZED':
      return 'info';
    case 'REJECTED':
      return 'danger';
    case 'EXPIRED':
      return 'warning';
    case 'CANCELLED':
      return 'neutral';
    default:
      return 'neutral';
  }
}

/** 时效状态 → 徽标色调。 */
export function timelinessTone(state: string): 'neutral' | 'success' | 'warning' | 'danger' {
  switch (state) {
    case 'OVERDUE':
      return 'danger';
    case 'DUE_SOON':
      return 'warning';
    case 'ON_TRACK':
      return 'success';
    default:
      return 'neutral';
  }
}

/** 剩余秒数 → 人话。负数不显示成「-3 天」，直接说「已超时」。 */
export function remainingText(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '—';
  if (seconds < 0) return '已超时';
  const days = Math.floor(seconds / 86400);
  if (days > 0) return `剩 ${days} 天`;
  const hours = Math.floor(seconds / 3600);
  if (hours > 0) return `剩 ${hours} 小时`;
  return `剩 ${Math.max(1, Math.floor(seconds / 60))} 分钟`;
}

/** 数量去掉无意义的尾零（3.000000 → 3），但保留至少一位小数以外的整数形态。 */
export function quantity(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/** 指示性金额。⚠️ 它**不是**可退余额校验的结果（11.3.1.1 未交付），更不是实际退款额（12 财务）。 */
export function amount(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  return Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/**
 * 可退量那一格的读法。
 *
 * <p>⚠️ 「不可退」和「可退 0 件」**必须分开显示**：前者是没发出去 / 没投影，
 * 后者是额度被别人占满 —— 运营对这两种情况的处理完全不同，
 * 合成一个「可退 0」会让「货根本没发出去」被误当成「额度用完了」。
 */
export function returnableText(line: RmaReturnableLine): { value: string; tone: 'neutral' | 'success' | 'warning' | 'danger' } {
  if (line.blockingReasons.length > 0) {
    return { value: '不可退', tone: 'danger' };
  }
  if (line.returnableQty === null || line.returnableQty === undefined) return { value: '—', tone: 'neutral' };
  const n = Number(line.returnableQty);
  if (n <= 0) return { value: '额度已占满', tone: 'warning' };
  return { value: quantity(n), tone: 'success' };
}

/** 「已退数量」那一列的读法：不可得就是不可得，**不显示 0**。 */
export function receivedQuantityText(line: RmaReturnableLine): string {
  return line.receivedQuantitySource === 'NOT_DELIVERED_11_2_2_2'
    ? '不可得（11.2.2.2 未交付）'
    : quantity(line.receivedQty ?? null);
}

/** 已发数量的来源注解，鼠标悬停用。 */
export function shippedSourceText(line: RmaReturnableLine): string {
  return line.shippedQuantitySource === 'NO_FULFILLMENT_PROGRESS'
    ? '没有履约进度投影，已发按 0 处理'
    : '来自 9.3.3.1 履约进度';
}

/** 退货地址拼成一行。空段不参与拼接，免得地址中间出现两个连续空格。 */
export function addressText(head: RmaHead): string {
  return [
    head.returnRecipientName,
    head.returnAddressLine1,
    head.returnAddressLine2,
    [head.returnCity, head.returnProvince, head.returnCountryCode].filter(Boolean).join(' '),
    head.returnPostcode,
  ]
    .filter((part) => part && part.trim().length > 0)
    .join('，');
}

/** 目标处理方式：**意图**，不是处置结论 —— 页面上必须带着这句话。 */
export const dispositionHint =
  '目标处理方式是**审批当时的意图**，不是处置结论：货还在路上（授权 ≠ 收到），' +
  '真正的处置决定属 11.2.3.2（未交付），且必须等实收到货（11.2.2.1）。';

/** 「授权不等于已收到」在页面上的固定说明。 */
export const receivedHint =
  '本页面**没有**「登记已收到」：登记退回实物属 11.2.2.1，未交付。' +
  '授权后货可能在路上，也可能根本没寄回 —— 所以这里只有寄回时限倒计时与超时自动关闭。';

/** 未交付模块的说明文字（capabilities 里逐条给了 reason，直接显示，不要自己重写）。 */
export function unavailableText(module: { ownerModule: string; reason: string }): string {
  return `未交付（归属 ${module.ownerModule}）：${module.reason}`;
}

/** 动作按钮：不可用时把理由原样带出来（按钮变灰而不给理由会让人以为系统坏了）。 */
export function actionButton(action: RmaActionEntry | null | undefined): {
  label: string;
  variant: 'default' | 'secondary' | 'ghost';
  disabled: boolean;
  reason: string | null;
} {
  if (!action) return { label: '—', variant: 'ghost', disabled: true, reason: '后端未给出动作入口' };
  return {
    label: action.label,
    variant: action.allowed ? 'default' : 'ghost',
    disabled: !action.allowed,
    reason: action.blockingReason ?? action.unavailableReason ?? null,
  };
}

/** 「刻意不做的事」清单：页面照着显示，而不是让用户自己猜为什么找不到按钮。 */
export function boundaryRows(boundaries: RmaBoundary[]): { scope: string; owner: string; reason: string }[] {
  return boundaries.map((b) => ({ scope: b.boundary, owner: b.ownerModule, reason: b.reason }));
}

/** 审批留痕的一行文案。 */
export function eventText(event: RmaDecisionEvent): string {
  const from = event.fromStatus ? `${event.fromStatus} → ` : '';
  return `${event.eventTypeLabel}（${from}${event.toStatus}）`;
}
