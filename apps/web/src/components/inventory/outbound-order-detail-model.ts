import type {
  OutboundDetailAction,
  OutboundDetailEvent,
  OutboundOrderDetail,
  OutboundOrderDetailCapabilities,
  OutboundQuantityAvailability,
  OutboundUnavailableLink,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 8.3.1.2 出库单详情前端模型。
 *
 * <p>界面上最容易出错、本模块显式处理了几处：
 *
 * <ul>
 *   <li>**已拣量是「待 8.3.2.1 交付」，不是 0。** 后端逐行返回 null，因为没有 picking_task
 *       可查。界面上把它渲染成一个灰块并写清归属模块 —— 渲染成 0 会让作业员以为一件都没拣，
 *       然后去重复拣一遍。
 *   <li>**已发量与已拣量口径不同。** 已发货的单上「已发量 = 计划件数」是成立的（终态 + 明细
 *       不可改）；未到已发货时后端返回 null。界面**不能**在 null 时回退成「0 件已发」，
 *       那是在编一个数字。
 *   <li>**四段拿不到的能力要画成灰块而不是空列表。** 空的包裹列表会被读成「这单没有包裹」，
 *       真相是「8.3.4.1 还没交付」。灰块上写归属模块与原因。
 *   <li>**状态机只有一处。** 作业操作入口由后端按转换矩阵给出，界面只渲染它返回的动作，
 *       不自己写一套「哪些按钮该禁用」—— 那会与库层触发器分叉。
 * </ul>
 */

/** 详情基址。与 8.3.1.1 同一个命名空间，详情挂在集合之下。 */
const base = 'warehouse/inventory/outbound-orders';

export function fetchOutboundDetailCapabilities(): Promise<OutboundOrderDetailCapabilities> {
  return api<OutboundOrderDetailCapabilities>(`${base}/detail-capabilities`);
}

export function fetchOutboundDetail(orderId: string): Promise<OutboundOrderDetail> {
  return api<OutboundOrderDetail>(`${base}/${orderId}`);
}

// ------------------------------------------------------------ 展示口径

/**
 * 数量展示：保留六位小数口径但去掉多余的尾随零。
 *
 * <p>后端按 numeric(19,6) 返回（禁浮点），所以「5.500000」与「5.5」是同一个数。
 * 直接把 5.500000 显示出来会让作业员以为系统存了个奇怪的小数。
 */
export function pieceText(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const text = String(value);
  if (!text.includes('.')) return text;
  const trimmed = text.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed === '' || trimmed === '-' ? '0' : trimmed;
}

/**
 * 已拣 / 已发两列的展示值。
 *
 * <p>返回 `null` 表示**这一格没有事实**，界面必须显示成归属模块而不是 0。
 * 刻意不在这里做 `value ?? 0` —— 那正是本模块最容易犯的错。
 */
export function quantityCell(q: OutboundQuantityAvailability): string | null {
  return q.value === null || q.value === undefined ? null : pieceText(q.value);
}

/** 数量不可得时的提示：归属模块 + 为什么。不编造数字。 */
export function quantityHint(q: OutboundQuantityAvailability): string {
  return q.available
    ? ''
    : `${q.ownerModule ?? '下游模块'} 尚未交付，${q.label}暂不可统计（不是 0）。`;
}

/** 不可得能力的灰块文案：归属模块 + href（可跳转时）。 */
export function unavailableHint(link: OutboundUnavailableLink): string {
  const owner = link.ownerModule ? `归 ${link.ownerModule}` : '';
  return owner ? `${owner} · ${link.reason}` : link.reason;
}

/** 作业时间线的一行文案：谁在什么时候把单子从哪儿推到了哪儿。 */
export function eventText(e: OutboundDetailEvent): string {
  if (e.fromStatus && e.toStatus) {
    return `${e.fromStatusLabel ?? e.fromStatus} → ${e.toStatusLabel ?? e.toStatus}`;
  }
  if (e.toStatus) return `进入 ${e.toStatusLabel ?? e.toStatus}`;
  return e.eventTypeLabel;
}

/** 时间戳格式化：后端给的是带偏移量的 ISO 串，这里只做展示，不重新解释时区。 */
export function timeText(value: string | null | undefined): string {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}`;
}

/**
 * 作业操作入口的按钮文案。
 *
 * <p>终态没有合法下一步（后端返回空数组），界面显示「已结束」而不是把按钮画成禁用。
 */
export function actionButtonText(a: OutboundDetailAction): string {
  const base = `推进到「${a.toStatusLabel}」`;
  return a.reasonRequired ? `${base}（需填原因）` : base;
}

/** 推进到已发货但单上还没有承运渠道时，界面要提前说明而不是等提交后吃报错。 */
export function carrierWarning(detail: OutboundOrderDetail): string | null {
  if (!detail.carrierCode.trim()) return '这张单还没有承运渠道，发货前必须先指定。';
  return null;
}

/** 列表跳详情：拼一个稳定的工作台链接。 */
export function detailHref(orderId: string): string {
  return `/warehouse/outbound/${orderId}`;
}

/** 把后端的中文错误翻成界面能直接显示的一句话；取不到就如实回退，不编造。 */
export function detailErrorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '出库单详情暂时不可用，请稍后重试。';
}