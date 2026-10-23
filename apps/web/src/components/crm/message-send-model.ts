import type {
  CrmMessageSendAttachmentInput,
  CrmMessageSendBatchInput,
  CrmMessageSendBatchResult,
  CrmMessageSendCapabilities,
  CrmMessageSendDetail,
  CrmMessageSendInput,
  CrmMessageSendKnowledgeTemplate,
  CrmMessageSendOutcome,
  CrmMessageSendPage,
  CrmMessageSendResultInput,
  CrmMessageSendRetryInput,
  CrmMessageSendTargetPage,
  CrmMessageSendTemplateInput,
  CrmMessageSendTemplatePage,
  CrmMessageSendTemplateRow,
  CrmMessageTargetState,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 15.1.1.2 消息发送前端模型。
 *
 * <p>蓝图：「回复买家消息：调用模板、手工编辑、附件支持、发送记录、失败重试；
 * 支持批量相同回复（同类问题）。」
 * 输出「发送记录 + 平台回执 + 失败原因」。
 * 关系：上游 = 15.1.1.1、15.4.2 知识库；下游 = 平台消息接口。
 * **发送失败要保留原消息状态**，不能让客服以为已回复。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>🔴 「已回复」与「回过但失败了」是两件完全不同的事</b>：
 *       后端的「已回复」是<b>推导</b>出来的（存在一条 SENT 的发送记录），
 *       页面必须把「没回过」与「回过但失败了」分开显示 —— 混成一句「操作失败」
 *       会让人再回一次，而重复回复买家的代价是平台违规与买家投诉。</li>
 *   <li><b>未接入的平台能力要显式灰掉</b>：平台侧站内信发送接口未接入
 *       （capabilities 里 {@code platform_message_api} 维度 available=false），
 *       投递只允许<b>人工交接</b>；页面**不提供**「发到平台」这种看起来能用的按钮 ——
 *       一个点了就成功的假按钮会让运营以为买家已经收到回复了，而现场一条都没发出去。</li>
 *   <li><b>下游未交付就不给假入口</b>：15.1.1.3 分类、15.5.1 自动回复、11 售后、
 *       15.4 工单都没有可调的下游 API，页面不提供它们的入口。</li>
 *   <li><b>金额与数量原样展示</b>：服务端算好的数字直接显示，JS 侧<b>不做任何算术</b>
 *       （AGENTS.md：金额与数量在 JS 侧只原样展示）。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态要区分「筛选无结果」与「还没有可回复的消息」。</li>
 * </ul>
 *
 * <p><b>命名空间</b>：{@code crm/message-sends}，与同服务的 {@code crm/message-inbox}
 * （15.1.1.1 收件箱）、{@code crm/buyer-emails}（15.1.2.1 邮件）**刻意不共用**。
 */

const base = 'crm/message-sends';

/**
 * 发送记录列表的筛选条件。
 *
 * <p>可选属性显式带 `| undefined`：仓库启用了 `exactOptionalPropertyTypes`，
 * 少了它的话「省略」与「显式传 undefined」在类型上不是一回事。
 */
export interface MessageSendParams {
  shopId?: string | undefined;
  platformCode?: string | undefined;
  status?: string | undefined;
  targetMessageId?: string | undefined;
  batchId?: string | undefined;
  keyword?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 可回复目标的筛选条件。 */
export interface MessageSendTargetParams {
  shopId?: string | undefined;
  keyword?: string | undefined;
  buyerReference?: string | undefined;
  orderReference?: string | undefined;
  onlyUnreplied?: boolean | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 拼装查询串（空值一律不拼，省得后端收到 `?shopId=`）。 */
export function messageSendQuery(params: MessageSendParams = {}): string {
  const parts: string[] = [];
  if (params.shopId) parts.push(`shopId=${encodeURIComponent(params.shopId)}`);
  if (params.platformCode) parts.push(`platformCode=${encodeURIComponent(params.platformCode)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.targetMessageId)
    parts.push(`targetMessageId=${encodeURIComponent(params.targetMessageId)}`);
  if (params.batchId) parts.push(`batchId=${encodeURIComponent(params.batchId)}`);
  if (params.keyword) parts.push(`keyword=${encodeURIComponent(params.keyword)}`);
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 拼装目标列表的查询串。 */
export function messageSendTargetQuery(params: MessageSendTargetParams = {}): string {
  const parts: string[] = [];
  if (params.shopId) parts.push(`shopId=${encodeURIComponent(params.shopId)}`);
  if (params.keyword) parts.push(`keyword=${encodeURIComponent(params.keyword)}`);
  if (params.buyerReference)
    parts.push(`buyerReference=${encodeURIComponent(params.buyerReference)}`);
  if (params.orderReference)
    parts.push(`orderReference=${encodeURIComponent(params.orderReference)}`);
  if (params.onlyUnreplied) parts.push('onlyUnreplied=true');
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 回复模板列表的筛选条件。 */
export interface MessageSendTemplateParams {
  shopId?: string | undefined;
  locale?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
}

/** 拼装模板列表的查询串。 */
export function messageSendTemplateQuery(params: MessageSendTemplateParams = {}): string {
  const parts: string[] = [];
  if (params.shopId) parts.push(`shopId=${encodeURIComponent(params.shopId)}`);
  if (params.locale) parts.push(`locale=${encodeURIComponent(params.locale)}`);
  if (params.status) parts.push(`status=${encodeURIComponent(params.status)}`);
  if (params.keyword) parts.push(`keyword=${encodeURIComponent(params.keyword)}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/** 知识库话术来源的筛选条件。 */
export interface MessageSendKnowledgeParams {
  shopId?: string | undefined;
  locale?: string | undefined;
}

/** 拼装知识库话术来源的查询串。 */
export function messageSendKnowledgeQuery(params: MessageSendKnowledgeParams = {}): string {
  const parts: string[] = [];
  if (params.shopId) parts.push(`shopId=${encodeURIComponent(params.shopId)}`);
  if (params.locale) parts.push(`locale=${encodeURIComponent(params.locale)}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 能力与目录

/** 能力声明：每一维说清「有没有、依据是什么、缺了会怎样」。 */
export function fetchMessageSendCapabilities() {
  return api<CrmMessageSendCapabilities>(`${base}/capabilities`);
}

/** 回复模板列表（真分页 + 真筛选）。 */
export function fetchMessageSendTemplates(params: MessageSendTemplateParams = {}) {
  return api<CrmMessageSendTemplatePage>(`${base}/templates${messageSendTemplateQuery(params)}`);
}

/** 单条模板详情。 */
export function fetchMessageSendTemplate(templateId: string) {
  return api<CrmMessageSendTemplateRow>(`${base}/templates/${encodeURIComponent(templateId)}`);
}

/** 新建模板。 */
export function createMessageSendTemplate(input: CrmMessageSendTemplateInput) {
  return api<CrmMessageSendTemplateRow>(`${base}/templates`, 'POST', input);
}

/** 改模板（历史发送记录不受影响 —— 它们快照了当时的话术）。 */
export function updateMessageSendTemplate(templateId: string, input: CrmMessageSendTemplateInput) {
  return api<CrmMessageSendTemplateRow>(
    `${base}/templates/${encodeURIComponent(templateId)}`,
    'PUT',
    input,
  );
}

/**
 * 删除模板。
 *
 * <p>⚠️ 已被发送记录引用时后端会改成**停用**（不真删）——
 * 删掉之后历史发送记录上的 templateId 会指向一个不存在的模板，
 * 「当时用的哪套话术」就再也读不出来了。
 */
export function deleteMessageSendTemplate(templateId: string) {
  return api<void>(`${base}/templates/${encodeURIComponent(templateId)}`, 'DELETE');
}

/** 知识库话术来源（15.4.2 上游，只列此刻生效的版本）。 */
export function fetchMessageSendKnowledgeTemplates(params: MessageSendKnowledgeParams = {}) {
  return api<CrmMessageSendKnowledgeTemplate[]>(
    `${base}/knowledge-templates${messageSendKnowledgeQuery(params)}`,
  );
}

/** 可回复的目标消息（来自 15.1.1.1 收件箱，带推导出来的已回复状态）。 */
export function fetchMessageSendTargets(params: MessageSendTargetParams = {}) {
  return api<CrmMessageSendTargetPage>(`${base}/targets${messageSendTargetQuery(params)}`);
}

// ================================================================ 发送

/** 发送记录列表（真分页 + 真筛选 + 与分页无关的全量汇总）。 */
export function fetchMessageSends(params: MessageSendParams = {}) {
  return api<CrmMessageSendPage>(`${base}/sends${messageSendQuery(params)}`);
}

/** 发送记录详情 = 发送本体 + 附件 + 尝试留痕 + 目标消息状态。 */
export function fetchMessageSend(sendId: string) {
  return api<CrmMessageSendDetail>(`${base}/sends/${encodeURIComponent(sendId)}`);
}

/** 登记一次发送。 */
export function createMessageSend(input: CrmMessageSendInput) {
  return api<CrmMessageSendOutcome>(`${base}/sends`, 'POST', input);
}

/** 批量相同回复（同类问题）：逐条给出结论与原因。 */
export function runMessageSendBatch(input: CrmMessageSendBatchInput) {
  return api<CrmMessageSendBatchResult>(`${base}/batch`, 'POST', input);
}

/** 登记一次投递的结果（人工交接：运营在平台侧实际发出或失败之后回登记）。 */
export function registerMessageSendResult(sendId: string, input: CrmMessageSendResultInput) {
  return api<CrmMessageSendOutcome>(
    `${base}/sends/${encodeURIComponent(sendId)}/result`,
    'POST',
    input,
  );
}

/** 失败重试：开一次新的尝试（不改正文）。 */
export function retryMessageSend(sendId: string, input: CrmMessageSendRetryInput) {
  return api<CrmMessageSendOutcome>(
    `${base}/sends/${encodeURIComponent(sendId)}/retry`,
    'POST',
    input,
  );
}

// ================================================================ 可读形态

/** 发送状态的可读名。 */
export function sendStatusLabel(status: string): string {
  switch (status) {
    case 'QUEUED':
      return '待发';
    case 'SENT':
      return '已发出';
    case 'FAILED':
      return '失败';
    case 'CANCELLED':
      return '已作废';
    default:
      return status;
  }
}

/** 触达通道的可读名（平台接口未接入，所以「人工交接」是当前唯一可用的通道）。 */
export function transportLabel(transport: string): string {
  switch (transport) {
    case 'MANUAL_HANDOFF':
      return '人工交接';
    case 'PLATFORM_API':
      return '平台接口（未接入）';
    default:
      return transport;
  }
}

/** 正文来源的可读名。 */
export function bodySourceLabel(source: string): string {
  switch (source) {
    case 'MANUAL':
      return '手工编写';
    case 'TEMPLATE':
      return '模板原文';
    case 'TEMPLATE_EDITED':
      return '模板改过';
    case 'KNOWLEDGE':
      return '知识库原文';
    case 'KNOWLEDGE_EDITED':
      return '知识库改过';
    default:
      return source;
  }
}

/**
 * 🔴 目标消息的「已回复状态」可读形态。
 *
 * <p>三件事分得清清楚楚：
 *
 * <ul>
 *   <li><b>REPLIED</b>：有一条「已发出」的发送记录 —— 真的回出去了。</li>
 *   <li><b>UNREPLIED</b>：没有「已发出」的发送记录 —— <b>还没回出去</b>。
 *       ⚠️ 哪怕最近一次尝试失败了，它也仍然是「未回复」：失败不改变已回复状态。</li>
 * </ul>
 *
 * <p>把「回过但失败了」显示成「已回复」会让客服以为不用再回；
 * 显示成「没回过」又会让客服重复回复 —— 所以要**连着最近一次尝试一起说**。
 */
export function targetStateText(target: CrmMessageTargetState | null | undefined): string {
  if (!target) return '已回复状态未知：还没有读取这条消息的发送记录。';
  const parts: string[] = [];
  if (target.repliedState === 'REPLIED') {
    parts.push(`已回复（发送记录 ${target.repliedSendNo ?? '未知'}）`);
  } else {
    parts.push('**未回复**');
  }
  if (target.lastSendStatus === 'FAILED') {
    parts.push(`最近一次尝试失败：${target.lastFailureReason || '未写明原因'}`);
  } else if (target.lastSendStatus === 'QUEUED') {
    parts.push('最近一次尝试待发（还没回登记结果）');
  }
  parts.push(target.note ?? '');
  return parts.filter((p) => p.length > 0).join(' · ');
}

/** 「已回复 / 未回复」的短标签（徽标用）。 */
export function repliedStateLabel(repliedState: string): string {
  return repliedState === 'REPLIED' ? '已回复' : '未回复';
}

/**
 * 发送结果的可读形态。
 *
 * <p>⚠️ 幂等重放必须**说清楚**：界面不能让人以为「又发了一次」。
 */
export function sendOutcomeText(outcome: CrmMessageSendOutcome | null | undefined): string {
  if (!outcome) return '暂无结果';
  if (outcome.replayed) {
    return `幂等键此前已登记过（发送记录 ${outcome.send.sendNo}），本次**没有**重复发送。`;
  }
  return outcome.message;
}

/**
 * 发送记录汇总的可读形态。
 *
 * <p>⚠️ 计数全部来自**服务端算好的**汇总，这里只做拼接，**不做任何 JS 侧算术**。
 */
export function sendSummaryText(summary: CrmMessageSendPage['summary'] | null | undefined): string {
  if (!summary) return '暂无汇总';
  const parts = [
    `共 ${summary.totalCount} 条`,
    `已发出 ${summary.sentCount} 条`,
    `失败 ${summary.failedCount} 条`,
    `待发 ${summary.queuedCount} 条`,
    `已作废 ${summary.cancelledCount} 条`,
  ];
  if (summary.byStatus.length > 0) {
    parts.push(summary.byStatus.map((b) => `${b.label}×${b.count}`).join(' · '));
  }
  return parts.join(' · ');
}

/**
 * 当前是否**有筛选条件**。
 *
 * <p>空态要区分两件完全不同的事：
 * 「**筛选无结果**」（条件太窄，换个条件就有了）与「**还没有可回复的消息**」。
 */
export function hasActiveSendFilter(params: MessageSendParams = {}): boolean {
  return Boolean(
    params.shopId ||
    params.platformCode ||
    params.status ||
    params.targetMessageId ||
    params.batchId ||
    params.keyword,
  );
}

/**
 * 生成一个幂等键。
 *
 * <p>为什么页面也要带幂等键：蓝图输入句里点名了 {@code idempotency_key}，
 * 而「连点两次回复按钮」是客服台面上最常见的误操作 ——
 * 重复回复买家的代价是平台违规与买家投诉。
 *
 * <p>⚠️ 同一份表单**重试提交**时必须复用同一个键（那才叫幂等），
 * 换表单才换键。
 */
export function newIdempotencyKey(prefix = 'send'): string {
  const random = Math.random().toString(36).slice(2, 10);
  return `${prefix}-${Date.now().toString(36)}-${random}`;
}

/** 附件草稿（登记发送时填的一行）。 */
export interface SendAttachmentDraft {
  fileName: string;
  fileUrl: string;
}

/**
 * 解析附件草稿：一行一个附件，格式 {@code 文件名} 或 {@code 文件名 | 链接}。
 *
 * <p>空行与只有空白的行直接跳过；文件名缺失的行**不静默丢弃**，
 * 而是原样交给后端去报「文件名不能为空」——
 * 前端悄悄丢一条附件，用户会以为文件已经附上了。
 */
export function parseSendAttachmentDraft(text: string): SendAttachmentDraft[] {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map((line) => {
      const [name, url] = line.split('|');
      return { fileName: (name ?? '').trim(), fileUrl: (url ?? '').trim() };
    });
}

/** 把附件草稿转成后端要的输入形状（大小未知就留 null —— 后端记 0 = 未登记）。 */
export function toAttachmentInputs(drafts: SendAttachmentDraft[]): CrmMessageSendAttachmentInput[] {
  return drafts.map((draft) => ({
    fileName: draft.fileName,
    fileUrl: draft.fileUrl,
    contentType: '',
    sizeBytes: null,
    note: '',
  }));
}

/**
 * 模板变量清单的可读形态。
 *
 * <p>页面在编辑模板时把可用变量列出来 —— 写了没定义的变量，
 * 后端会**拒绝发送**（正文里留着 {@code {{xxx}}} 的信没人看得懂）。
 */
export function templateVariablesText(variables: string[] | null | undefined): string {
  if (!variables || variables.length === 0) return '暂无可用模板变量。';
  return `可用变量：${variables.map((v) => `{{${v}}}`).join(' · ')}`;
}
