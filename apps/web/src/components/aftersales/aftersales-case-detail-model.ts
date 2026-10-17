import type {
  AftersalesActionEntry,
  AftersalesAttachment,
  AftersalesCaseDetail,
  AftersalesCaseLine,
  AftersalesCaseNote,
  AftersalesDetailCapabilities,
  AftersalesMessage,
  AftersalesSectionCapability,
  AftersalesTimelineEvent,
  AftersalesTimelinePage,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 11.1.1.2 售后单详情前端模型。
 *
 * <p>蓝图：「单售后全景：原订单信息、申请内容与买家诉求、商品行与数量、沟通记录、附件与凭证、
 * 处置流程状态、退款与退货关联、平台侧状态、责任判定。」
 * 输出句：「详情 + 操作入口 + 时间线。」
 * 加粗句：「<b>详情页要把"买家说了什么"和"我们做了什么"按时间线对齐</b>，这是纠纷举证的基础。」
 *
 * <p><b>本模型对那条加粗句负三件事</b>：
 *
 * <ul>
 *   <li>{@link timelineTone} 把说话方映射成**视觉语气**（买家 / 我方 / 平台 / 系统四档）。
 *       时间线是详情页的主干，如果两边画成同一个颜色，那条「对齐」的加粗句在界面上就不成立。</li>
 *   <li>排序**完全信任后端的 occurredAt**：前端不再按自己的时间戳重排一遍
 *       —— 补录的历史消息必须落在它真实发生的位置上。</li>
 *   <li>{@link undeliveredBlock} 给未交付的下游渲染**明确的「该能力未交付 + 归属模块」**，
 *       而不是空白或假数据。退货 / 退款 / 纠纷三块当前全部未交付。</li>
 * </ul>
 *
 * <p>路径前缀与 11.1.1.1 同为 {@code trade/aftersales}，前端路由为 {@code /aftersales/cases/{id}}。
 */

const base = 'trade/aftersales';

export function fetchAftersalesDetailCapabilities() {
  return api<AftersalesDetailCapabilities>(`${base}/case-detail/capabilities`);
}

export function fetchAftersalesCaseDetail(caseId: string) {
  return api<AftersalesCaseDetail>(`${base}/cases/${caseId}`);
}

export function fetchAftersalesTimeline(caseId: string, page = 0, size = 50) {
  return api<AftersalesTimelinePage>(`${base}/cases/${caseId}/timeline?page=${page}&size=${size}`);
}

export interface AftersalesLineInput {
  orderLineId: string;
  quantity: string;
  requestedAmount?: string | undefined;
  currency?: string | undefined;
  buyerNote?: string | undefined;
}

export function addAftersalesCaseLine(caseId: string, payload: AftersalesLineInput) {
  return api<AftersalesCaseLine>(`${base}/cases/${caseId}/lines`, 'POST', payload);
}

export interface AftersalesMessageInput {
  direction: 'BUYER_TO_US' | 'US_TO_BUYER';
  channel: 'PLATFORM' | 'EMAIL' | 'CHAT' | 'PHONE' | 'INTERNAL_NOTE';
  messageBody: string;
  externalMessageId?: string | undefined;
  messageSource?: 'TENANT_MAINTAINED' | 'MODULE_2_PLATFORM_MESSAGE' | undefined;
  sourceReference?: string | undefined;
  /** 消息**发生**的时刻（不是录入时刻）。 */
  sentAt: string;
  capturedAt?: string | undefined;
}

export function addAftersalesMessage(caseId: string, payload: AftersalesMessageInput) {
  return api<AftersalesMessage>(`${base}/cases/${caseId}/messages`, 'POST', payload);
}

export interface AftersalesAttachmentInput {
  evidenceKind:
    | 'BUYER_EVIDENCE'
    | 'OUR_EVIDENCE'
    | 'PRODUCT_PHOTO'
    | 'DELIVERY_PROOF'
    | 'PAYMENT_PROOF'
    | 'INSPECTION_REPORT'
    | 'OTHER';
  fileName: string;
  contentType?: string | undefined;
  /** 对象存储定位符，**不是**文件内容也不是 URL 全文。 */
  storageRef: string;
  fileSizeBytes?: number | undefined;
  description?: string | undefined;
  attachedBySide: 'BUYER' | 'US';
  evidenceSource?: 'TENANT_MAINTAINED' | 'MODULE_2_PLATFORM_MESSAGE' | undefined;
  sourceReference?: string | undefined;
  capturedAt?: string | undefined;
}

export function addAftersalesAttachment(caseId: string, payload: AftersalesAttachmentInput) {
  return api<AftersalesAttachment>(`${base}/cases/${caseId}/attachments`, 'POST', payload);
}

export function addAftersalesNote(caseId: string, noteBody: string) {
  return api<AftersalesCaseNote>(`${base}/cases/${caseId}/notes`, 'POST', { noteBody });
}

// ================================================================ 时间线口径

/**
 * 时间线说话方的视觉语气。
 *
 * <p>四档必须**视觉上分得开**：买家与平台一侧、我方一侧、系统自动事件。
 * 举证时要回答的是「谁先说的」，而这个判断靠的是颜色分层而不是逐行读文字。
 */
export type TimelineTone = 'buyer' | 'ours' | 'platform' | 'system';

export interface TimelinePresentation {
  tone: TimelineTone;
  label: string;
  /** CSS 类名片段，四档的颜色与边框都不同。 */
  badge: string;
}

const TIMELINE_SIDE = {
  BUYER: { tone: 'buyer', label: '买家', badge: 'side-buyer' },
  US: { tone: 'ours', label: '我方', badge: 'side-ours' },
  PLATFORM: { tone: 'platform', label: '平台', badge: 'side-platform' },
  SYSTEM: { tone: 'system', label: '系统', badge: 'side-system' },
} satisfies Record<string, TimelinePresentation>;

// 用 satisfies 而不是直接声明成 Record<string, ...>：
// 后者是一个**索引签名**，在 noUncheckedIndexedAccess 下取值会被判成 `| undefined`。
// satisfies 保留字面量键；再导出一份显式加宽的常量供「按运行时字符串取值」用
// （照抄 11.1.1.1 的 TIMELINESS_PRESENTATION 同一手法）。
export const TIMELINE_SIDES: Readonly<Record<string, TimelinePresentation>> = TIMELINE_SIDE;

/**
 * 取说话方语气。
 *
 * <p>未知说话方**刻意**回落到 `system`（中性）而不是买家侧或我方侧：
 * 后端加了一个本界面还没接的说话方时，把它算进任何一侧都是**替平台编话**。
 */
export function timelineTone(side: string | null | undefined): TimelinePresentation {
  if (!side) return TIMELINE_SIDE.SYSTEM;
  return TIMELINE_SIDES[side] ?? TIMELINE_SIDE.SYSTEM;
}

/**
 * 一条时间线事件的展示文案。
 *
 * <p>⚠️ `occurredAt` 与后端返回的**完全一致**：前端不重排、不补时间戳、不做时区换算后再排。
 *   补录的历史消息必须落在它真实发生的位置上，否则时间线与事实相反。
 */
export function timelineEventText(event: AftersalesTimelineEvent): string {
  const when = formatTime(event.occurredAt);
  const side = timelineTone(event.actorSide).label;
  return `${when} · ${side} · ${event.kindLabel}${event.summary ? `：${event.summary}` : ''}`;
}

/** 时间线按后端给定的顺序返回时，把「买家的」与「我们的」各取一段，供界面对齐展示。 */
export function timelineSides(page: AftersalesTimelinePage): {
  buyer: AftersalesTimelineEvent[];
  ours: AftersalesTimelineEvent[];
} {
  return {
    buyer: page.events.filter((e) => e.actorSide === 'BUYER'),
    ours: page.events.filter((e) => e.actorSide === 'US'),
  };
}

// ================================================================ 未交付区块

/**
 * 未交付模块的区块文案。
 *
 * <p>职责句里的「退款与退货关联」当前**拿不到真数据**（11.2 / 11.3 / 11.4 均未交付）。
 * 界面必须显示「该能力未交付 + 归属模块 + 原因」，
 * **而不是**空白（会被读成「这张单没有退款也没有纠纷」）或假数据。
 */
export function undeliveredBlock(module: {
  delivered: boolean;
  label: string;
  ownerModule: string;
  reason: string;
}): { title: string; detail: string; available: boolean } {
  if (module.delivered) {
    return { title: module.label, detail: '', available: true };
  }
  return {
    title: '该能力未交付',
    detail: `${module.label}归属 ${module.ownerModule}，当前未交付：${module.reason}`,
    available: false,
  };
}

/** 区块能力表按 section 取，供每个区块自己决定「显示内容」还是「显示未交付」。 */
export function sectionByKey(
  caps: AftersalesDetailCapabilities | null | undefined,
  section: string,
): AftersalesSectionCapability | undefined {
  const found = caps?.sections?.find((s) => s.section === section);
  return found;
}

/**
 * 操作入口是否可点。
 *
 * <p>未交付的动作（受理 / 退货 / 退款 / 申诉）{@code available=false}：
 * 界面**禁用**并显示归属模块，而不是渲染一个点了没反应的按钮。
 */
export function actionUsable(action: AftersalesActionEntry): boolean {
  return action.available && !!action.path;
}

// ================================================================ 展示工具

/** 金额格式化（复用列表模块口径：codegen 把 number 展平，这里只做展示）。 */
export function formatAmount(value: string | number | null | undefined, currency?: string | null): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  return currency ? `${n.toFixed(2)} ${currency}` : n.toFixed(2);
}

/** 数量格式化：numeric(19,6) 展示时去掉多余尾零，至少留 1 位小数。 */
export function formatQuantity(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  return String(Number(n.toFixed(3)));
}

/** 时间展示：带时区的 ISO 串转本地可读格式；解析失败时原样返回而不是显示 Invalid Date。 */
export function formatTime(value: string | null | undefined): string {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}`;
}

/** 文件大小：只做可读化，不假装能预览（本服务不托管文件内容）。 */
export function formatFileSize(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return '—';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/** 凭证按类别分组：纠纷举证要按类目组织材料，所以分组比平铺更有用。 */
export function groupAttachments(
  attachments: AftersalesAttachment[],
): { side: 'BUYER' | 'US'; items: AftersalesAttachment[] }[] {
  return (['BUYER', 'US'] as const).map((side) => ({
    side,
    items: attachments.filter((a) => a.attachedBySide === side),
  }));
}

/** 详情页顶部的九宫格概览：对应职责句里的九个区块（缺失的区块不出现，而不是补一条假数据）。 */
export function detailSections(detail: AftersalesCaseDetail): {
  key: string;
  label: string;
  value: string;
}[] {
  return [
    { key: 'order', label: '原订单', value: detail.order?.orderNo ?? '关联订单缺失' },
    { key: 'buyer', label: '买家诉求', value: detail.header.buyerRequest ?? '未记录诉求原文' },
    {
      key: 'amount',
      label: '申请金额',
      value: formatAmount(detail.header.requestedAmount, detail.header.currency),
    },
    {
      key: 'lines',
      label: '涉及数量',
      value: `${formatQuantity(detail.header.requestedQuantity)}（${detail.lines.length} 行）`,
    },
    { key: 'messages', label: '沟通记录', value: `${detail.messages.length} 条` },
    { key: 'attachments', label: '附件与凭证', value: `${detail.attachments.length} 份` },
    { key: 'notes', label: '备注', value: `${detail.notes.length} 条` },
    { key: 'ticket', label: '工单', value: detail.ticket?.ticketNo ?? '未转工单' },
    { key: 'liability', label: '责任判定', value: detail.liability.decisionLabel },
  ];
}
