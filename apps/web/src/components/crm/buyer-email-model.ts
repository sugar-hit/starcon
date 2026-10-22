import type {
  CrmBuyerEmailAttachment,
  CrmBuyerEmailAttachmentInput,
  CrmBuyerEmailCapabilities,
  CrmBuyerEmailConversation,
  CrmBuyerEmailCreateInput,
  CrmBuyerEmailDetail,
  CrmBuyerEmailPage,
  CrmBuyerEmailRow,
  CrmBuyerEmailSendInput,
  CrmBuyerEmailSendRecord,
  CrmBuyerEmailSendResult,
  CrmBuyerEmailShopView,
  CrmBuyerEmailSyncInput,
  CrmBuyerEmailSyncResult,
  CrmBuyerEmailTemplateInput,
  CrmBuyerEmailTemplatePage,
  CrmBuyerEmailTemplateRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.1.2.1 邮件收发（买家邮件）前端模型。
 *
 * <p>蓝图：「管理买家邮件（Amazon Buyer-Seller Messaging、eBay 邮件等）：收发、附件、模板、
 * 与站内信的双向同步或标记为同源。」输出「邮件列表与详情 + 发送记录」。
 * 关系：上游 = 2 同步；下游 = 15.1.1.2 发送。
 * **部分平台要求邮件与站内信在同一会话处理**，分开展示会导致重复回复买家。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>会话是一等公民</b>：页面必须能按会话键看到「这封问的是这个单子，我们回过没有」。
 *       收到的与发出的**在同一个会话视图里**，否则客服照样会重复回复。</li>
 *   <li><b>发送失败保留原状态</b>：失败的那一封邮件**仍然是「已收到」**，
 *       失败原因在发送记录里。页面必须把这两件事分开显示 ——
 *       「没回过」与「回过但失败了」是两件完全不同的事，混成一句「操作失败」会让人再回一次。</li>
 *   <li><b>未接入的平台能力要显式灰掉</b>：平台买家邮件同步与平台邮件发送接口都未接入
 *       （capabilities 里 available=false），页面**不提供**「发到平台」的按钮 ——
 *       一个点了就返回成功的假按钮会让运营以为买家已经收到回复了。</li>
 *   <li><b>站内信那一半不可见</b>：15.1.1.1 未交付，页面**不假装**能看到站内信原文，
 *       只说明「同源已按会话键标记」。</li>
 *   <li><b>金额与数量只原样展示</b>：附件字节数由后端给出，页面**不做任何前端算术**。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「收件箱还是空的」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/buyer-emails}，与同服务的 {@code crm/knowledge}、
 * {@code crm/review-requests}、{@code crm/review-rules} **刻意不共用**。
 */

const base = 'crm/buyer-emails';

/**
 * 邮件列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface BuyerEmailParams {
  shopId?: string | undefined;
  platformCode?: string | undefined;
  direction?: string | undefined;
  status?: string | undefined;
  buyerRef?: string | undefined;
  salesOrderNo?: string | undefined;
  conversationKey?: string | undefined;
  subject?: string | undefined;
  receivedFrom?: string | undefined;
  receivedTo?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function buyerEmailQuery(params: BuyerEmailParams = {}): string {
  const parts: string[] = [];
  if (params.shopId) parts.push(`shopId=${encodeURIComponent(params.shopId)}`);
  if (params.platformCode) parts.push(`platformCode=${encodeURIComponent(params.platformCode)}`);
  if (params.direction) parts.push(`direction=${encodeURIComponent(params.direction)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.buyerRef) parts.push(`buyerRef=${encodeURIComponent(params.buyerRef)}`);
  if (params.salesOrderNo) parts.push(`salesOrderNo=${encodeURIComponent(params.salesOrderNo)}`);
  if (params.conversationKey)
    parts.push(`conversationKey=${encodeURIComponent(params.conversationKey)}`);
  if (params.subject) parts.push(`subject=${encodeURIComponent(params.subject)}`);
  if (params.receivedFrom) parts.push(`receivedFrom=${encodeURIComponent(params.receivedFrom)}`);
  if (params.receivedTo) parts.push(`receivedTo=${encodeURIComponent(params.receivedTo)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。 */
export function fetchBuyerEmailCapabilities() {
  return api<CrmBuyerEmailCapabilities>(`${base}/capabilities`);
}

/** 店铺目录（来自上游 channel 的真实清单）+ 每个平台的买家邮件接入状态。 */
export function fetchBuyerEmailShops() {
  return api<CrmBuyerEmailShopView[]>(`${base}/shops`);
}

/** 邮件列表（真分页 + 真筛选）。 */
export function fetchBuyerEmails(params: BuyerEmailParams = {}) {
  return api<CrmBuyerEmailPage>(`${base}${buyerEmailQuery(params)}`);
}

/** 单封邮件详情 = 邮件本体 + 附件 + 发送记录 + 同会话邮件。 */
export function fetchBuyerEmail(emailId: string) {
  return api<CrmBuyerEmailDetail>(`${base}/${encodeURIComponent(emailId)}`);
}

/**
 * 会话视图（加粗句「同一会话处理」的落点）。
 *
 * <p>⚠️ 站内信那一半**不可见**（15.1.1.1 未交付）：响应里的 {@code inboxThread.available}
 * 恒为 false，页面据此说明「同源已按会话键标记，但站内信原文还看不到」。
 */
export function fetchBuyerEmailConversation(emailId: string) {
  return api<CrmBuyerEmailConversation>(`${base}/${encodeURIComponent(emailId)}/conversation`);
}

/** 登记一封**收到的**买家邮件。 */
export function createBuyerEmail(input: CrmBuyerEmailCreateInput) {
  return api<CrmBuyerEmailRow>(`${base}`, 'POST', input);
}

/** 登记一个附件的**元数据**。 */
export function createBuyerEmailAttachment(emailId: string, input: CrmBuyerEmailAttachmentInput) {
  return api<CrmBuyerEmailAttachment>(
    `${base}/${encodeURIComponent(emailId)}/attachments`,
    'POST',
    input,
  );
}

/** 一封邮件的发送记录（时间线）。 */
export function fetchBuyerEmailSendRecords(emailId: string) {
  return api<CrmBuyerEmailSendRecord[]>(`${base}/${encodeURIComponent(emailId)}/send-records`);
}

/**
 * 登记一次发送。
 *
 * <p>⚠️ 只允许 {@code transport=MANUAL_HANDOFF}：平台侧邮件发送接口未接入，
 * 后端会拒绝 {@code PLATFORM_API}。页面不提供「发到平台」的入口。
 */
export function createBuyerEmailSendRecord(emailId: string, input: CrmBuyerEmailSendInput) {
  return api<CrmBuyerEmailSendResult>(
    `${base}/${encodeURIComponent(emailId)}/send-records`,
    'POST',
    input,
  );
}

/** 平台买家邮件同步（当前恒为 409：适配器未交付）。 */
export function syncBuyerEmails(input: CrmBuyerEmailSyncInput) {
  return api<CrmBuyerEmailSyncResult>(`${base}/sync`, 'POST', input);
}

/** 邮件模板列表。 */
export interface BuyerEmailTemplateParams {
  shopId?: string | undefined;
  locale?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

export function buyerEmailTemplateQuery(params: BuyerEmailTemplateParams = {}): string {
  const parts: string[] = [];
  if (params.shopId) parts.push(`shopId=${encodeURIComponent(params.shopId)}`);
  if (params.locale) parts.push(`locale=${encodeURIComponent(params.locale)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.keyword) parts.push(`keyword=${encodeURIComponent(params.keyword)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 邮件模板列表（真分页 + 真筛选）。 */
export function fetchBuyerEmailTemplates(params: BuyerEmailTemplateParams = {}) {
  return api<CrmBuyerEmailTemplatePage>(`${base}/templates${buyerEmailTemplateQuery(params)}`);
}

/** 新建邮件模板。 */
export function createBuyerEmailTemplate(input: CrmBuyerEmailTemplateInput) {
  return api<CrmBuyerEmailTemplateRow>(`${base}/templates`, 'POST', input);
}

/** 单条邮件模板详情。 */
export function fetchBuyerEmailTemplate(templateId: string) {
  return api<CrmBuyerEmailTemplateRow>(`${base}/templates/${encodeURIComponent(templateId)}`);
}

/** 改邮件模板（模板编码建后不可改）。 */
export function updateBuyerEmailTemplate(templateId: string, input: CrmBuyerEmailTemplateInput) {
  return api<CrmBuyerEmailTemplateRow>(
    `${base}/templates/${encodeURIComponent(templateId)}`,
    'PUT',
    input,
  );
}

/**
 * 删邮件模板。
 *
 * <p>⚠️ 已被发送记录引用过的模板删不掉（后端 409）：删掉它会让「这次回复用的哪套话术」
 * 在历史里只剩一个编码快照、没有正文可查。要停用请改状态。
 */
export function deleteBuyerEmailTemplate(templateId: string) {
  return api<void>(`${base}/templates/${encodeURIComponent(templateId)}`, 'DELETE');
}

// ================================================================ 可读形态

/** 邮件方向的可读名。 */
export function emailDirectionLabel(direction: string): string {
  switch (direction) {
    case 'INBOUND':
      return '收到的';
    case 'OUTBOUND':
      return '发出的';
    default:
      return direction;
  }
}

/**
 * 邮件状态的可读名。
 *
 * <p>「已收到」与「已回复」是**收到的**邮件的状态；「已发出 / 发送失败」是
 * **发出的**那一行的状态。两者混在一起看会误判「回过没有」。
 */
export function emailStatusLabel(status: string): string {
  switch (status) {
    case 'RECEIVED':
      return '已收到（尚未回复）';
    case 'REPLIED':
      return '已回复';
    case 'QUEUED':
      return '已登记待发';
    case 'SENT':
      return '已发出';
    case 'FAILED':
      return '发送失败';
    default:
      return status;
  }
}

/** 发送记录状态的可读名。 */
export function sendStatusLabel(status: string): string {
  switch (status) {
    case 'QUEUED':
      return '已登记待发';
    case 'SENT':
      return '已发出';
    case 'FAILED':
      return '发送失败';
    case 'CANCELLED':
      return '已作废';
    default:
      return status;
  }
}

/** 触达通道的可读名。 */
export function transportLabel(transport: string): string {
  switch (transport) {
    case 'PLATFORM_API':
      return '平台接口';
    case 'MANUAL_HANDOFF':
      return '人工交接';
    default:
      return transport;
  }
}

/** 邮件来源的可读名。 */
export function emailSourceLabel(source: string): string {
  switch (source) {
    case 'MANUAL_ENTRY':
      return '人工登记';
    case 'PLATFORM_SYNC':
      return '平台同步';
    default:
      return source;
  }
}

/** 模板状态的可读名。 */
export function templateStatusLabel(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return '启用';
    case 'DISABLED':
      return '停用';
    default:
      return status;
  }
}

/**
 * 这封收到的邮件**回过没有**。
 *
 * <p>「没回过」与「回过但失败了」是两件完全不同的事，页面必须分开显示 ——
 * 混成一句「操作失败」会让人再回一次，而重复回复的代价正是平台违规。
 */
export function repliedStateLabel(email: { status: string; sendRecordCount: number }): string {
  if (email.status === 'REPLIED') return '已成功回复过';
  if (email.sendRecordCount > 0) return '尝试过但没有成功发出（状态仍为「已收到」）';
  return '还没有回过';
}
