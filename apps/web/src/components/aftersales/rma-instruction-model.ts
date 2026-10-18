/**
 * 11.2.1.2 退货指令下发的前端数据层。
 *
 * 蓝图 11-aftersales-and-returns.md：「向买家发出退货指引（地址、要求、时限），
 * 生成退货标签，跟踪买家回寄物流。」
 * 输出：「退货指令 + 标签文件 + 回寄物流轨迹跟踪。」
 * 加粗句：「<b>多数平台退货由买家寄回</b>，本系统只负责指引与跟踪，<b>不控制实物</b>。」
 *
 * <p><b>这一层对那条加粗句负三件事</b>：
 *
 * <ol>
 *   <li><b>页面第一屏是「已送达 / 已超时」而不是「一共多少条」</b>：已送达的那些才是要天天盯的
 *       —— 买家已经拿到指引，货可能在路上，也可能压根没寄。</li>
 *   <li><b>不渲染任何「已收到」的控件</b>：{@code goodsReceived} 恒为 false 且带归属模块
 *       （11.2.2.1 未交付）。前端<b>不自己推断</b>能不能登记实收。</li>
 *   <li><b>标签完整性校验失败时显示红色警示</b>，不照常渲染那份地址 ——
 *       买家照着它寄，货寄到谁家由篡改者决定。</li>
 * </ol>
 */
import type {
  RmaInstructionAction,
  RmaInstructionBoundary,
  RmaInstructionCapabilities,
  RmaInstructionDetail,
  RmaInstructionEvent,
  RmaInstructionHead,
  RmaInstructionPage,
  RmaLabelContent,
  RmaTrackingEvent,
  RmaTrackingTimeline,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';

/** 与后端 {@code /trade/rma-instructions} 同前缀。 */
const base = 'trade/rma-instructions';

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

export const fetchInstructionCapabilities = () => call<RmaInstructionCapabilities>(`${base}/capabilities`);

export const fetchInstructionList = (params: {
  page?: number;
  size?: number;
  status?: string;
  channel?: string;
  rmaId?: string;
  overdueOnly?: boolean;
  carrier?: string;
  trackingNo?: string;
  keyword?: string;
  sort?: string;
}) => {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '' || value === null) continue;
    query.set(key, String(value));
  }
  const suffix = query.toString();
  return call<RmaInstructionPage>(suffix ? `${base}?${suffix}` : base);
};

export const fetchInstructionDetail = (id: string) => call<RmaInstructionDetail>(`${base}/${id}`);

export const fetchInstructionTracking = (id: string) =>
  call<RmaTrackingTimeline>(`${base}/${id}/tracking`);

export const fetchLabelContent = (id: string) => call<RmaLabelContent>(`${base}/${id}/label`);

export const createInstruction = (body: unknown) =>
  call<RmaInstructionDetail>(base, { method: 'POST', body: JSON.stringify(body) });

export const updateInstruction = (id: string, body: unknown) =>
  call<RmaInstructionDetail>(`${base}/${id}`, { method: 'PUT', body: JSON.stringify(body) });

export const issueInstruction = (id: string, body: unknown) =>
  call<RmaInstructionDetail>(`${base}/${id}/issue`, { method: 'POST', body: JSON.stringify(body) });

export const acknowledgeInstruction = (id: string, body: unknown) =>
  call<RmaInstructionDetail>(`${base}/${id}/acknowledge`, { method: 'POST', body: JSON.stringify(body) });

export const cancelInstruction = (id: string, body: unknown) =>
  call<RmaInstructionDetail>(`${base}/${id}/cancel`, { method: 'POST', body: JSON.stringify(body) });

export const expireInstructions = () =>
  call<import('../../../../../packages/api-contracts/src/index').RmaInstructionExpireResult>(
    `${base}/expire-overdue`,
    { method: 'POST' },
  );

export const generateLabel = (id: string, body: unknown) =>
  call<RmaInstructionDetail>(`${base}/${id}/label`, { method: 'POST', body: JSON.stringify(body) });

export const deliverLabel = (id: string, body: unknown) =>
  call<RmaInstructionDetail>(`${base}/${id}/label/deliver`, { method: 'POST', body: JSON.stringify(body) });

export const voidLabel = (id: string, body: unknown) =>
  call<RmaInstructionDetail>(`${base}/${id}/label/void`, { method: 'POST', body: JSON.stringify(body) });

export const appendTracking = (id: string, body: unknown) =>
  call<RmaInstructionDetail>(`${base}/${id}/tracking`, { method: 'POST', body: JSON.stringify(body) });

// ================================================================ 展示口径

/** 状态 → 徽标色调。终态与在途必须一眼分得开（在途=还要管，终态=办完了）。 */
export function stateTone(status: string): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  switch (status) {
    case 'ISSUED':
      return 'info';
    case 'ACKNOWLEDGED':
      return 'success';
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

/** 数量去掉无意义的尾零（3.000000 → 3），但保留最多六位小数的精度。 */
export function quantity(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/**
 * 轨迹当前状态 → 徽标色调。
 *
 * <p>⚠️ 「已签收」用 success 而不是 neutral，但页面上**必须紧挨着**一句
 * 「这不等于我们收到货了」—— 承运商口径的送达与实收登记是两件事。
 */
export function trackingTone(eventCode: string | null | undefined): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  switch (eventCode) {
    case 'DELIVERED':
      return 'success';
    case 'OUT_FOR_DELIVERY':
      return 'info';
    case 'EXCEPTION':
    case 'CUSTOMS_HOLD':
    case 'RETURNED_TO_SENDER':
      return 'danger';
    case 'CANCELLED':
      return 'warning';
    case 'ACCEPTED':
    case 'PICKED_UP':
    case 'IN_TRANSIT':
    case 'ARRIVED_AT_ORIGIN':
      return 'info';
    default:
      return 'neutral';
  }
}

/** 轨迹信息延迟（承运商晚回传）→ 人话。 */
export function lagText(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || seconds < 0) return '—';
  const hours = Math.floor(seconds / 3600);
  if (hours >= 24) return `晚回传 ${Math.floor(hours / 24)} 天`;
  if (hours >= 1) return `晚回传 ${hours} 小时`;
  return `晚回传 ${Math.max(1, Math.floor(seconds / 60))} 分钟`;
}

/** 退货地址拼成一行。空段不参与拼接，免得地址中间出现两个连续空格。 */
export function addressText(head: RmaInstructionHead): string {
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

/**
 * 「已下发 ≠ 已寄回」在页面上的固定说明。
 *
 * <p>蓝图加粗句：「<b>多数平台退货由买家寄回</b>，本系统只负责指引与跟踪，<b>不控制实物</b>。」
 * 它决定了页面**没有**什么，所以是横幅而不是脚注。
 */
export const goodsReceivedHint =
  '本页面**没有**「登记已收到」：登记退回实物属 11.2.2.1，未交付。' +
  '蓝图加粗句「本系统只负责指引与跟踪，**不控制实物**」—— ' +
  '指引已下发不等于货已到手，所以这里只有寄回时限倒计时与时限到期自动失效。' +
  '轨迹里的「已签收」也只表示**承运商说**送到了，不等于我们收到货。';

/** 标签完整性校验失败时的固定警示。 */
export const labelIntegrityHint =
  '标签文件是真字节落库 + SHA-256 校验和，服务端读取时会重算比对。' +
  '校验失败说明入库之后内容被改过 —— 页面据此**不照常渲染那份地址**，' +
  '因为买家照着它寄，货寄到谁家由篡改者决定。';

/** 未交付模块的说明文字（capabilities 里逐条给了 reason，直接显示，不要自己重写）。 */
export function unavailableText(module: { ownerModule: string; reason: string }): string {
  return `未交付（归属 ${module.ownerModule}）：${module.reason}`;
}

/** 动作按钮：不可用时把理由原样带出来（按钮变灰而不给理由会让人以为系统坏了）。 */
export function actionButton(action: RmaInstructionAction | null | undefined): {
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
export function boundaryRows(boundaries: RmaInstructionBoundary[]): {
  scope: string;
  owner: string;
  reason: string;
}[] {
  return boundaries.map((b) => ({ scope: b.boundary, owner: b.ownerModule, reason: b.reason }));
}

/** 指令状态留痕的一行文案。 */
export function eventText(event: RmaInstructionEvent): string {
  const from = event.fromStatus ? `${event.fromStatus} → ` : '';
  return `${event.eventTypeLabel}（${from}${event.toStatus}）`;
}

/** 轨迹事件的一行文案。 */
export function trackingEventText(event: RmaTrackingEvent): string {
  const where = event.eventLocation ? ` @ ${event.eventLocation}` : '';
  return `${event.carrierCode} ${event.trackingNo} · ${event.eventCodeLabel}${where}`;
}
