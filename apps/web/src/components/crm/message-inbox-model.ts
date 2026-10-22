import type {
  CrmMessageBatchInput,
  CrmMessageBatchResult,
  CrmMessageCapabilities,
  CrmMessageCreateInput,
  CrmMessageDetail,
  CrmMessageOptions,
  CrmMessageOrderContext,
  CrmMessagePage,
  CrmMessageRow,
  CrmMessageShopView,
  CrmMessageSummary,
  CrmMessageSyncAttempt,
  PlatformMessageAvailability,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.1.1.1 消息收件箱前端模型。
 *
 * <p>蓝图：「集中展示各店铺的平台站内信与买家消息：店铺、买家、主题、正文、附件、平台时间、
 * 收发方向、已读状态、关联订单，支持按店铺/类型/优先级/未读筛选与批量处理。」
 * 输出「消息列表 + 详情视图 + 关联订单上下文」。
 * 关系：上游 = 2 渠道与店铺（同步）；下游 = 15.1.1.2 回复、11 售后（转售后）、15.4 工单。
 * **消息必须关联到买家与订单**，否则客服看到消息还要去猜是哪一单。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>买家与订单不是「建议填」而是「必填」</b>：后端在 DB 层就是 NOT NULL + CHECK，
 *       页面必须把这两个输入框标成必填，并写清「为什么不能留空」。</li>
 *   <li><b>订单上下文不可用 ≠ 没有订单</b>：后端区分「上游没有这一单」与「读不到上游」，
 *       页面把两者**都**显示成「不可用 + 原因」，**绝不显示一片空白** ——
 *       空白会被客服读成「这一单没有信息」，而不是「我们没查到」。</li>
 *   <li><b>未接入的平台能力要显式灰掉</b>：平台侧买家站内信适配器未接入
 *       （capabilities 里 {@code platform_message_sync} 维度 available=false），
 *       同步入口会返回 <b>409</b>，页面**不提供**「同步平台消息」这种看起来能用的按钮。</li>
 *   <li><b>下游未交付就不给假入口</b>：15.1.1.2 发送、15.1.1.3 分类、11 售后、15.4 工单
 *       都没有可调的下游 API，页面只提供「登记处置意图」，并说明它**不会**建任何单据。</li>
 *   <li><b>金额与数量原样展示</b>：服务端算好的数字直接显示，JS 侧<b>不做任何算术</b>
 *       （AGENTS.md：金额与数量在 JS 侧只原样展示）。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「收件箱还是空的」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/message-inbox}，与同服务的 {@code crm/knowledge}、
 * {@code crm/review-requests}、{@code crm/review-rules} **刻意不共用**。
 *
 * <p>消息集合挂在 {@code crm/message-inbox/messages}（列表 / 登记 / 单条详情），
 * 目录与动作挂在收件箱命名空间下（{@code /shops} {@code /options} {@code /platforms}
 * {@code /capabilities} {@code /batch} {@code /sync}）——
 * 裸路径 {@code crm/message-inbox} **刻意不是**一个端点，所以白名单里不放行它。
 */

const base = 'crm/message-inbox';

/**
 * 消息列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface MessageParams {
  shopId?: string | undefined;
  platformCode?: string | undefined;
  messageType?: string | undefined;
  priority?: string | undefined;
  direction?: string | undefined;
  readStatus?: string | undefined;
  buyerReference?: string | undefined;
  orderReference?: string | undefined;
  keyword?: string | undefined;
  platformTimeFrom?: string | undefined;
  platformTimeTo?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 拼装查询串（空值一律不拼，省得后端收到 `?shopId=`）。 */
export function messageQuery(params: MessageParams = {}): string {
  const parts: string[] = [];
  if (params.shopId) parts.push(`shopId=${encodeURIComponent(params.shopId)}`);
  if (params.platformCode) parts.push(`platformCode=${encodeURIComponent(params.platformCode)}`);
  if (params.messageType) parts.push(`messageType=${encodeURIComponent(params.messageType)}`);
  if (params.priority) parts.push(`priority=${encodeURIComponent(params.priority)}`);
  if (params.direction) parts.push(`direction=${encodeURIComponent(params.direction)}`);
  if (params.readStatus) parts.push(`readStatus=${encodeURIComponent(params.readStatus)}`);
  if (params.buyerReference)
    parts.push(`buyerReference=${encodeURIComponent(params.buyerReference)}`);
  if (params.orderReference)
    parts.push(`orderReference=${encodeURIComponent(params.orderReference)}`);
  if (params.keyword) parts.push(`keyword=${encodeURIComponent(params.keyword)}`);
  if (params.platformTimeFrom)
    parts.push(`platformTimeFrom=${encodeURIComponent(params.platformTimeFrom)}`);
  if (params.platformTimeTo) parts.push(`platformTimeTo=${encodeURIComponent(params.platformTimeTo)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。 */
export function fetchMessageCapabilities() {
  return api<CrmMessageCapabilities>(`${base}/capabilities`);
}

/** 店铺目录（来自上游 channel 的真实清单，不是前端写死的常量）。 */
export function fetchMessageShops() {
  return api<CrmMessageShopView[]>(`${base}/shops`);
}

/** 筛选词表（平台与店铺来自本租户真实数据）。 */
export function fetchMessageOptions() {
  return api<CrmMessageOptions>(`${base}/options`);
}

/** 逐平台的站内信接入状态（平台清单来自真实店铺目录）。 */
export function fetchMessagePlatforms() {
  return api<PlatformMessageAvailability[]>(`${base}/platforms`);
}

/** 消息列表（真分页 + 真筛选 + 与分页无关的全量汇总）。 */
export function fetchMessages(params: MessageParams = {}) {
  return api<CrmMessagePage>(`${base}/messages${messageQuery(params)}`);
}

/** 单条消息详情 = 消息本体 + 附件 + 关联订单上下文 + 处置意图。 */
export function fetchMessage(messageId: string) {
  return api<CrmMessageDetail>(`${base}/messages/${encodeURIComponent(messageId)}`);
}

/**
 * 登记一条平台消息 / 买家消息。
 *
 * <p>⚠️ 买家标识与订单标识**必填**（蓝图加粗句，后端 DB 层同款约束）。
 */
export function createMessage(input: CrmMessageCreateInput) {
  return api<CrmMessageRow>(`${base}/messages`, 'POST', input);
}

/** 批量处理：逐条给出结论与原因，失败的条目不会被悄悄跳过。 */
export function runMessageBatch(input: CrmMessageBatchInput) {
  return api<CrmMessageBatchResult>(`${base}/batch`, 'POST', input);
}

/** 平台消息同步（当前逐平台未接入 ⇒ 后端返回 409）。 */
export function syncMessages(input: { platformCode: string; shopId: string; since?: string }) {
  return api<CrmMessageSyncAttempt>(`${base}/sync`, 'POST', input);
}

// ================================================================ 可读形态

/** 已读状态的可读名（与后端 DB 词表一一对应）。 */
export function readStatusLabel(status: string): string {
  switch (status) {
    case 'UNREAD':
      return '未读';
    case 'READ':
      return '已读';
    case 'ARCHIVED':
      return '已归档';
    default:
      return status;
  }
}

/** 收发方向的可读名。 */
export function directionLabel(direction: string): string {
  switch (direction) {
    case 'INBOUND':
      return '收到';
    case 'OUTBOUND':
      return '发出';
    default:
      return direction;
  }
}

/** 消息类型的可读名。 */
export function messageTypeLabel(type: string): string {
  switch (type) {
    case 'PLATFORM_INBOX':
      return '平台站内信';
    case 'BUYER_MESSAGE':
      return '买家消息';
    case 'SYSTEM_NOTICE':
      return '平台通知';
    case 'COMPLIANCE':
      return '合规通知';
    default:
      return type;
  }
}

/** 优先级的可读名。 */
export function priorityLabel(priority: string): string {
  switch (priority) {
    case 'LOW':
      return '低';
    case 'NORMAL':
      return '普通';
    case 'HIGH':
      return '高';
    case 'URGENT':
      return '紧急';
    default:
      return priority;
  }
}

/** 处置意图类型的可读名。 */
export function intentTypeLabel(intentType: string): string {
  switch (intentType) {
    case 'AFTERSALE_CASE':
      return '转售后';
    case 'SERVICE_TICKET':
      return '建工单';
    default:
      return intentType;
  }
}

/** 处置意图状态的可读名。 */
export function intentStatusLabel(status: string): string {
  switch (status) {
    case 'RECORDED':
      return '已登记';
    case 'CANCELLED':
      return '已撤销';
    default:
      return status;
  }
}

/** 批量动作的可读名。 */
export function batchActionLabel(action: string): string {
  switch (action) {
    case 'MARK_READ':
      return '标记已读';
    case 'MARK_UNREAD':
      return '标记未读';
    case 'ARCHIVE':
      return '归档';
    case 'SET_PRIORITY':
      return '改优先级';
    case 'RECORD_INTENT':
      return '登记处置意图';
    case 'CANCEL_INTENT':
      return '撤销处置意图';
    default:
      return action;
  }
}

/** 来源的可读名（平台同步未接入，所以只有人工登记是「当前可用」的来源）。 */
export function messageSourceLabel(source: string): string {
  switch (source) {
    case 'MANUAL_ENTRY':
      return '人工登记';
    case 'PLATFORM_SYNC':
      return '平台同步（未接入）';
    default:
      return source;
  }
}

/**
 * 当前是否**有筛选条件**。
 *
 * <p>空态要区分两件完全不同的事：
 * 「**筛选无结果**」（条件太窄，换个条件就有了）与「**收件箱还是空的**」
 * （平台消息同步未接入，消息只能人工登记）。
 */
export function hasActiveFilter(params: MessageParams = {}): boolean {
  return Boolean(
    params.shopId ||
      params.platformCode ||
      params.messageType ||
      params.priority ||
      params.direction ||
      params.readStatus ||
      params.buyerReference ||
      params.orderReference ||
      params.keyword ||
      params.platformTimeFrom ||
      params.platformTimeTo,
  );
}

/**
 * 关联订单上下文的可读形态。
 *
 * <p>🔴 查不到时返回「订单上下文不可用 + 原因」，**绝不返回空串** ——
 * 空串会被客服读成「这一单没有信息」，而不是「我们没查到」。
 */
export function orderContextText(context: CrmMessageOrderContext | null | undefined): string {
  if (!context) return '订单上下文不可用：还没有读取订单上下文。';
  if (!context.available) {
    return context.reason
      ? `订单上下文不可用：${context.reason}`
      : '订单上下文不可用：后端没有给出原因，请重新读取。';
  }
  const parts: string[] = [];
  parts.push(context.orderNo ? `订单号 ${context.orderNo}` : '订单号未知');
  if (context.externalOrderNo) parts.push(`平台单号 ${context.externalOrderNo}`);
  if (context.orderSource) parts.push(`来源 ${context.orderSource}`);
  if (context.currency && context.grandTotalAmount !== null && context.grandTotalAmount !== undefined) {
    // ⚠️ 金额**原样展示**，JS 侧不做任何算术（AGENTS.md）。
    parts.push(`金额 ${context.currency} ${context.grandTotalAmount}`);
  }
  if (context.documentStatus) parts.push(`单据状态 ${context.documentStatus}`);
  if (context.platformStatus) parts.push(`平台状态 ${context.platformStatus}`);
  if (context.paymentStatus) parts.push(`支付状态 ${context.paymentStatus}`);
  if (context.fulfillmentStatus) parts.push(`履约状态 ${context.fulfillmentStatus}`);
  if (context.buyerName) parts.push(`买家 ${context.buyerName}`);
  return parts.join(' · ');
}

/**
 * 收件箱汇总的可读形态。
 *
 * <p>⚠️ 计数全部来自**服务端算好的** {@link CrmMessageSummary}，
 * 这里只做拼接，**不做任何 JS 侧算术**。
 */
export function summaryText(summary: CrmMessageSummary | null | undefined): string {
  if (!summary) return '暂无汇总';
  const parts = [
    `共 ${summary.totalCount} 条`,
    `未读 ${summary.unreadCount} 条`,
    `已读 ${summary.readCount} 条`,
    `已归档 ${summary.archivedCount} 条`,
    `带附件 ${summary.withAttachmentCount} 条`,
  ];
  if (summary.byPriority.length > 0) {
    parts.push(
      `优先级 ${summary.byPriority.map((b) => `${b.label}×${b.count}`).join(' · ')}`,
    );
  }
  if (summary.byType.length > 0) {
    parts.push(`类型 ${summary.byType.map((b) => `${b.label}×${b.count}`).join(' · ')}`);
  }
  return parts.join(' · ');
}

/** 附件草稿（登记消息时填的一行）。 */
export interface AttachmentDraft {
  fileName: string;
  fileUrl: string;
}

/**
 * 解析附件草稿：一行一个附件，格式 {@code 文件名} 或 {@code 文件名 | 链接}。
 *
 * <p>空行与只有空白的行直接跳过；文件名缺失的行**不静默丢弃**，
 * 而是原样交给后端去报「文件名不能为空」——
 * 前端悄悄丢一条附件，用户会以为文件已经登记上去了。
 */
export function parseAttachmentDraft(text: string): AttachmentDraft[] {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map((line) => {
      const [name, url] = line.split('|');
      return { fileName: (name ?? '').trim(), fileUrl: (url ?? '').trim() };
    });
}
