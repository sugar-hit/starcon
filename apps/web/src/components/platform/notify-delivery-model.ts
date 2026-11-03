/**
 * 18.5.2.1「投递记录」前端模型（**纯函数层** + API 调用）。
 *
 * <p>蓝图原文：「**投递记录**：记录通知投递结果：模板、收件范围、渠道、状态、失败原因、重试；
 * 统计触达率与退订情况。输入：投递记录；重试请求。输出：投递日志 + 统计。
 * 关系：上游 = 18.5.1；下游 = 1.3.2.2 外部推送、15.5.2 触达。
 * **退订必须被尊重并生效**，反复触达退订用户是投诉与合规风险的主要来源。」
 *
 * <p>🔴 <b>本文件是纯函数层</b>：不碰 React、不碰样式。它做四件事：
 *
 * <ol>
 *   <li><b>调后端</b>（唯一的网络出口，路径全部经 {@link base} 拼出）；</li>
 *   <li><b>构造入参</b>：把表单草稿折成请求体（形状与服务端契约逐字一致）；</li>
 *   <li><b>校验形状</b>（服务端再拒一次；前端先挡一道是为了让用户当场看到原因）；</li>
 *   <li><b>措辞与汇总</b>：把受控码折成人话，把投递清单折成页面上要显示的几个数字。</li>
 * </ol>
 *
 * <p>🔴 <b>页面上最容易做丢的五件事，这里都挡住了</b>：
 *
 * <ol>
 *   <li><b>「投过」不等于「送达了」</b>。投递状态有五态（待出结果 / 已送达 / 部分送达 /
 *       全部失败 / 全部被退订拦截），页面必须分开显示 —— 把「待出结果」显示成「失败」，
 *       运营会对着一个还在等结果的任务去手工重投。</li>
 *   <li><b>触达率的分母不含被退订拦下的人</b>。页面显示触达率时必须**同时**显示
 *       「实际投递 N 人 / 被退订拦截 M 人」，否则一个 100% 的触达率会掩盖
 *       「其实有 300 个人因为退订没收到」这件事 —— 那正是合规风险所在。</li>
 *   <li><b>退订拦截不是静默丢弃</b>。被拦下的收件人进「拦截留痕」清单，
 *       页面必须能点开看「为什么这个收件人没收到」。</li>
 *   <li><b>重试会重查退订</b>。第一次投递之后才退订的，重试会被拦下并返回
 *       「状态 = 全部被退订拦截」—— 页面要如实显示，不能显示成「重试成功」。</li>
 *   <li><b>加载 / 空 / 错误三态齐全</b>：空态区分「还没有投递过」与「筛选无结果」。</li>
 * </ol>
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），
 * 前端**不手抄**字符串联合。
 */

import type {
  NotifyAttemptRow,
  NotifyBlockRow,
  NotifyDeliveryCapabilities,
  NotifyDeliveryDetail,
  NotifyDeliveryInput,
  NotifyDeliveryRow,
  NotifyDeliveryStatistics,
  NotifyRecipientInput,
  NotifyRetryInput,
  NotifyUnsubscribeInput,
  NotifyUnsubscribeRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。
 *
 * 🔴 与 18.1.2.2 的 `platform/deliveries`（**webhook** 投递：事件 → 订阅方）
 * **不是一件事**：那边记的是事件投递，本模块记的是**通知**投递（模板 → 收件人）。
 */
const base = 'platform/notifications';

/**
 * 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。
 *
 * 🔴 参数类型用 **type 别名而不是 interface**：TypeScript 只给**类型别名**与对象字面量
 * 隐式索引签名，`interface` 没有 ⇒ 传进来会报 TS2345「Index signature for type
 * 'string' is missing」。
 */
function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchCapabilities(): Promise<NotifyDeliveryCapabilities> {
  return api<NotifyDeliveryCapabilities>(`${base}/capabilities`);
}

/** 投递记录列表（真筛选 + 真关键词 + 全序排序）。 */
export function fetchDeliveries(filters: DeliveryFilters = {}): Promise<NotifyDeliveryRow[]> {
  return api<NotifyDeliveryRow[]>(`${base}/deliveries?${query(filters)}`);
}

/** 投递统计（触达率 / 退订率 / 拦截数）。 */
export function fetchStatistics(): Promise<NotifyDeliveryStatistics> {
  return api<NotifyDeliveryStatistics>(`${base}/deliveries/statistics`);
}

/** 一条投递记录详情（记录 + 逐次尝试 + 拦截留痕）。 */
export function fetchDeliveryDetail(id: string): Promise<NotifyDeliveryDetail> {
  return api<NotifyDeliveryDetail>(`${base}/deliveries/${id}`);
}

/** 退订清单。 */
export function fetchUnsubscribes(
  filters: UnsubscribeFilters = {},
): Promise<NotifyUnsubscribeRow[]> {
  return api<NotifyUnsubscribeRow[]>(`${base}/unsubscribes?${query(filters)}`);
}

/** 退订拦截留痕清单（「为什么这个收件人没收到」）。 */
export function fetchBlocks(filters: BlockFilters = {}): Promise<NotifyBlockRow[]> {
  return api<NotifyBlockRow[]>(`${base}/unsubscribes/blocks?${query(filters)}`);
}

// ================================================================ 写入

/** 登记一次投递（**唯一**的投递写入口）。 */
export function recordDelivery(input: NotifyDeliveryInput): Promise<NotifyDeliveryDetail> {
  return api<NotifyDeliveryDetail>(`${base}/deliveries`, 'POST', input);
}

/** 发起一次重试（服务端会**重查退订** —— 投递之后才退订的会被拦下）。 */
export function retryDelivery(id: string, input: NotifyRetryInput): Promise<NotifyDeliveryDetail> {
  return api<NotifyDeliveryDetail>(`${base}/deliveries/${id}/retry`, 'POST', input);
}

/** 登记一条退订（**先查后登记** —— 已生效的直接返回原记录）。 */
export function registerUnsubscribe(input: NotifyUnsubscribeInput): Promise<NotifyUnsubscribeRow> {
  return api<NotifyUnsubscribeRow>(`${base}/unsubscribes`, 'POST', input);
}

/** 撤销一条退订（**软撤销**：历史区间留着，只是不再命中拦截）。 */
export function revokeUnsubscribe(id: string): Promise<NotifyUnsubscribeRow> {
  return api<NotifyUnsubscribeRow>(`${base}/unsubscribes/${id}/revoke`, 'POST', {});
}

// ================================================================ 筛选条件

/** 投递记录列表的筛选条件（**类型别名**：要能直接传给 {@link query}）。 */
export type DeliveryFilters = {
  templateId?: string;
  channel?: string;
  status?: string;
  scopeType?: string;
  keyword?: string;
  from?: string;
  to?: string;
  limit?: number;
  offset?: number;
};

/** 退订清单的筛选条件（**类型别名**：要能直接传给 {@link query}）。 */
export type UnsubscribeFilters = {
  recipientRef?: string;
  channel?: string;
  scopeType?: string;
  active?: boolean;
  limit?: number;
  offset?: number;
};

/** 拦截留痕清单的筛选条件（**类型别名**：要能直接传给 {@link query}）。 */
export type BlockFilters = {
  recipientRef?: string;
  deliveryId?: string;
  limit?: number;
  offset?: number;
};

// ================================================================ 词表

/** 投递状态 → 中文名。 */
export const DELIVERY_STATUS_LABELS: Record<string, string> = {
  PENDING: '待出结果',
  DELIVERED: '已送达',
  PARTIAL: '部分送达',
  FAILED: '全部失败',
  BLOCKED: '全部被退订拦截',
};

/** 渠道 → 中文名。 */
export const CHANNEL_LABELS: Record<string, string> = {
  EMAIL: '邮件',
  SMS: '短信',
  PUSH: '移动推送',
  IN_APP: '站内信',
  WEBHOOK: 'Webhook',
};

/** 收件范围类型 → 中文名。 */
export const SCOPE_TYPE_LABELS: Record<string, string> = {
  SINGLE: '单个收件人',
  SEGMENT: '指定分群',
  SUBSCRIBERS: '订阅者全集',
};

/** 单次尝试的结果 → 中文名。 */
export const OUTCOME_LABELS: Record<string, string> = {
  DELIVERED: '已送达',
  FAILED: '失败',
  PENDING: '待出结果',
};

/** 尝试来源 → 中文名。 */
export const ATTEMPT_SOURCE_LABELS: Record<string, string> = {
  INITIAL: '首次投递',
  RETRY: '重试',
  MANUAL: '人工重投',
};

/** 退订范围 → 中文名。 */
export const UNSUBSCRIBE_SCOPE_LABELS: Record<string, string> = {
  TEMPLATE: '单个模板',
  SCENE: '整个场景',
  GLOBAL: '全部通知',
};

/** 退订来源 → 中文名。 */
export const UNSUBSCRIBE_SOURCE_LABELS: Record<string, string> = {
  USER_REQUEST: '收件人要求',
  COMPLAINT: '投诉',
  ADMIN: '运营登记',
};

/** 状态的视觉语气（页面着色用）。 */
export type Tone = 'ok' | 'warn' | 'bad' | 'idle';

/** 投递状态的视觉语气。 */
export function deliveryStatusTone(status: string): Tone {
  switch (status) {
    case 'DELIVERED':
      return 'ok';
    case 'PARTIAL':
    case 'PENDING':
      return 'warn';
    case 'FAILED':
      return 'bad';
    case 'BLOCKED':
      // 🔴 全部被退订拦下**不是**「失败」—— 它是合规动作生效，要显示成中性偏警示而不是报错色。
      return 'warn';
    default:
      return 'idle';
  }
}

/** 投递状态的一句话说明（页面直接显示，别让用户去猜）。 */
export function deliveryStatusHint(delivery: NotifyDeliveryRow): string {
  switch (delivery.status) {
    case 'PENDING':
      return '还有尝试没出结果，先别急着重投。';
    case 'DELIVERED':
      return '实际投递的收件人全部触达成功。';
    case 'PARTIAL':
      return `部分触达成功：送达 ${delivery.deliveredCount} 人、失败 ${delivery.failedCount} 人。`;
    case 'FAILED':
      return `全部失败：${delivery.failedCount} 人未送达${delivery.failureReason ? `（${delivery.failureReason}）` : ''}。`;
    case 'BLOCKED':
      return `全部 ${delivery.blockedCount} 人命中退订，一次都没发出去。`;
    default:
      return '';
  }
}

/** 比率显示成百分比（库里是 NUMERIC(7,4)，0..1）。 */
export function rateText(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '—';
  return `${(rate * 100).toFixed(1)}%`;
}

/** 渠道显示名（未知码就原样回显，别显示成空白）。 */
export function channelLabel(channel: string): string {
  return CHANNEL_LABELS[channel] ?? channel;
}

/** 投递状态显示名。 */
export function deliveryStatusLabel(status: string): string {
  return DELIVERY_STATUS_LABELS[status] ?? status;
}

/** 收件范围显示名。 */
export function scopeTypeLabel(scopeType: string): string {
  return SCOPE_TYPE_LABELS[scopeType] ?? scopeType;
}

/** 退订范围显示名。 */
export function unsubscribeScopeLabel(scopeType: string): string {
  return UNSUBSCRIBE_SCOPE_LABELS[scopeType] ?? scopeType;
}

/** 退订来源显示名。 */
export function unsubscribeSourceLabel(source: string): string {
  return UNSUBSCRIBE_SOURCE_LABELS[source] ?? source;
}

/** 尝试结果显示名。 */
export function outcomeLabel(outcome: string): string {
  return OUTCOME_LABELS[outcome] ?? outcome;
}

/** 尝试来源显示名。 */
export function attemptSourceLabel(source: string): string {
  return ATTEMPT_SOURCE_LABELS[source] ?? source;
}

/** 响应码显示名：0 = 根本没拿到响应（不是「0」也不是「未知」）。 */
export function statusCodeLabel(statusCode: number): string {
  if (statusCode === 0) return '没有响应';
  return String(statusCode);
}

/** 🔴 被退订拦下的一句说明（拦截清单与详情都用它）。 */
export function blockedPhrase(block: NotifyBlockRow): string {
  const scope = unsubscribeScopeLabel(block.unsubscribeScopeType);
  const key = block.unsubscribeScopeKey === '*' ? '' : `（${block.unsubscribeScopeKey}）`;
  return `命中${scope}退订${key}，未发出`;
}

/** 退订是否仍然生效（撤销后不再拦截）。 */
export function unsubscribeActive(unsubscribe: NotifyUnsubscribeRow): boolean {
  return !unsubscribe.revokedAt;
}

/** 退订状态的一句话。 */
export function unsubscribePhrase(unsubscribe: NotifyUnsubscribeRow): string {
  return unsubscribeActive(unsubscribe) ? '生效中（投递时会被拦下）' : '已撤销（不再拦截）';
}

// ================================================================ 汇总

/** 投递清单的汇总数字（页面上那几个角标）。 */
export interface DeliverySummary {
  total: number;
  pending: number;
  delivered: number;
  partial: number;
  failed: number;
  blocked: number;
  recipients: number;
  blockedRecipients: number;
  attempts: number;
}

/** 汇总一份投递清单（**与列表同源**：不另查 count 端点）。 */
export function summarizeDeliveries(rows: NotifyDeliveryRow[] | null): DeliverySummary {
  const list = rows ?? [];
  const summary: DeliverySummary = {
    total: list.length,
    pending: 0,
    delivered: 0,
    partial: 0,
    failed: 0,
    blocked: 0,
    recipients: 0,
    blockedRecipients: 0,
    attempts: 0,
  };
  for (const row of list) {
    if (row.status === 'PENDING') summary.pending++;
    if (row.status === 'DELIVERED') summary.delivered++;
    if (row.status === 'PARTIAL') summary.partial++;
    if (row.status === 'FAILED') summary.failed++;
    if (row.status === 'BLOCKED') summary.blocked++;
    summary.recipients += row.recipientCount;
    summary.blockedRecipients += row.blockedCount;
    summary.attempts += row.attemptCount;
  }
  return summary;
}

/** 统计卡片的文案（触达率 / 退订率 / 拦截数）。 */
export interface StatisticsSummary {
  deliveryRate: string;
  unsubscribeRate: string;
  blockedAttempts: string;
  targeted: string;
  blocked: string;
  distinctRecipients: string;
  unsubscribedRecipients: string;
}

/** 把统计折成页面要显示的几行字。 */
export function summarizeStatistics(
  stats: NotifyDeliveryStatistics | null,
): StatisticsSummary | null {
  if (!stats) return null;
  return {
    deliveryRate: rateText(stats.deliveryRate),
    unsubscribeRate: rateText(stats.unsubscribeRate),
    blockedAttempts: String(stats.blockedAttempts),
    targeted: String(stats.totalTargeted),
    blocked: String(stats.totalBlocked),
    distinctRecipients: String(stats.distinctRecipients),
    unsubscribedRecipients: String(stats.unsubscribedRecipients),
  };
}

/** 🔴 触达率的可读解释（分母口径要说清楚，别让人拿它跟别处的数字比）。 */
export function deliveryRateExplanation(stats: NotifyDeliveryStatistics | null): string {
  if (!stats) return '';
  const rate = rateText(stats.deliveryRate);
  return `触达率 ${rate} = 已送达 ${stats.totalDelivered} 人 ÷ 实际投递 ${stats.totalTargeted} 人（分母不含被退订拦截的 ${stats.totalBlocked} 人）`;
}

/** 🔴 退订率的可读解释。 */
export function unsubscribeRateExplanation(stats: NotifyDeliveryStatistics | null): string {
  if (!stats) return '';
  const rate = rateText(stats.unsubscribeRate);
  return `退订率 ${rate} = 已退订 ${stats.unsubscribedRecipients} 人 ÷ 投递过的 ${stats.distinctRecipients} 人`;
}

// ================================================================ 投递登记草稿

/** 一个收件人及其结果的草稿。 */
export interface RecipientDraft {
  ref: string;
  outcome: string;
  statusCode: string;
  failureReason: string;
  durationMs: string;
}

/** 登记一次投递的草稿。 */
export interface DeliveryDraft {
  templateId: string;
  channel: string;
  scopeType: string;
  scopeRef: string;
  recipients: RecipientDraft[];
}

/** 新建登记时的空白草稿。 */
export function emptyDeliveryDraft(): DeliveryDraft {
  return {
    templateId: '',
    channel: 'EMAIL',
    scopeType: 'SUBSCRIBERS',
    scopeRef: '',
    recipients: [emptyRecipientDraft()],
  };
}

/** 一个空白收件人草稿。 */
export function emptyRecipientDraft(): RecipientDraft {
  return { ref: '', outcome: 'DELIVERED', statusCode: '200', failureReason: '', durationMs: '' };
}

/** 投递草稿校验：返回**原因**（null = 合法）。 */
export function validateDeliveryDraft(draft: DeliveryDraft): string | null {
  if (!draft.templateId.trim()) return '请选择通知模板';
  if (!draft.channel) return '请选择渠道';
  if (!draft.scopeType) return '请选择收件范围类型';
  if (draft.scopeType === 'SINGLE' && !draft.scopeRef.trim()) {
    return '收件范围是「单个收件人」时必须填写收件人标识';
  }
  if (!draft.recipients.length) return '至少要有一个收件人';
  for (const recipient of draft.recipients) {
    if (!recipient.ref.trim()) return '每个收件人都要有标识';
    if (!['DELIVERED', 'FAILED', 'PENDING'].includes(recipient.outcome)) {
      return `收件人 ${recipient.ref} 的结果只能是 DELIVERED / FAILED / PENDING`;
    }
    const code = recipient.statusCode.trim();
    if (code && !/^\d{1,3}$/.test(code)) {
      return `收件人 ${recipient.ref} 的响应码只能是 0～999 的整数（0 表示没有响应）`;
    }
    if (recipient.outcome === 'DELIVERED' && code && code !== '0') {
      const numeric = Number(code);
      if (numeric < 200 || numeric > 299) {
        return `收件人 ${recipient.ref} 标记为已送达时，响应码只能是 2xx（或 0 表示无 HTTP 码）`;
      }
    }
    if (recipient.outcome === 'FAILED' && code && code !== '0') {
      const numeric = Number(code);
      if (numeric >= 200 && numeric <= 299) {
        return `收件人 ${recipient.ref} 标记为失败时，响应码不能是 2xx`;
      }
    }
    if (recipient.outcome === 'FAILED' && !recipient.failureReason.trim()) {
      return `收件人 ${recipient.ref} 标记为失败时必须写清失败原因`;
    }
    if (recipient.durationMs.trim() && !/^\d{1,12}$/.test(recipient.durationMs.trim())) {
      return `收件人 ${recipient.ref} 的耗时只能是毫秒整数`;
    }
  }
  return null;
}

/** 投递草稿 → 请求体（形状与服务端契约逐字一致）。 */
export function buildDeliveryInput(draft: DeliveryDraft): NotifyDeliveryInput {
  const recipients: NotifyRecipientInput[] = draft.recipients
    .map((recipient) => ({
      ref: recipient.ref.trim(),
      outcome: recipient.outcome,
      statusCode: recipient.statusCode.trim() ? Number(recipient.statusCode.trim()) : 0,
      failureReason: recipient.failureReason.trim(),
      durationMs: recipient.durationMs.trim() ? Number(recipient.durationMs.trim()) : 0,
    }))
    .filter((recipient) => recipient.ref.length > 0);
  return {
    templateId: draft.templateId.trim(),
    // 模板版本没指定时服务端按 activation 表解析当前生效的那一版。
    templateVersionId: '',
    channel: draft.channel,
    scopeType: draft.scopeType,
    scopeRef: draft.scopeRef.trim(),
    recipients,
  };
}

// ================================================================ 退订草稿

/** 登记一条退订的草稿。 */
export interface UnsubscribeDraft {
  recipientRef: string;
  channel: string;
  scopeType: string;
  scopeKey: string;
  templateId: string;
  reason: string;
  source: string;
}

/** 新建退订时的空白草稿。 */
export function emptyUnsubscribeDraft(): UnsubscribeDraft {
  return {
    recipientRef: '',
    channel: 'ALL',
    scopeType: 'GLOBAL',
    scopeKey: '',
    templateId: '',
    reason: '',
    source: 'USER_REQUEST',
  };
}

/** 退订草稿校验：返回**原因**（null = 合法）。 */
export function validateUnsubscribeDraft(draft: UnsubscribeDraft): string | null {
  if (!draft.recipientRef.trim()) return '请填写收件人标识';
  if (!draft.channel) return '请选择渠道';
  if (!draft.scopeType) return '请选择退订范围';
  if (draft.scopeType === 'TEMPLATE' && !draft.templateId.trim()) {
    return '退订范围是「单个模板」时必须选择模板';
  }
  if (draft.scopeType === 'SCENE' && !draft.scopeKey.trim()) {
    return '退订范围是「整个场景」时必须填写场景标识';
  }
  if (!['USER_REQUEST', 'COMPLAINT', 'ADMIN'].includes(draft.source)) {
    return '退订来源只能是 USER_REQUEST / COMPLAINT / ADMIN';
  }
  if (draft.reason.length > 500) return '退订原因不能超过 500 个字符';
  return null;
}

/** 退订草稿 → 请求体（形状与服务端契约逐字一致）。 */
export function buildUnsubscribeInput(draft: UnsubscribeDraft): NotifyUnsubscribeInput {
  return {
    recipientRef: draft.recipientRef.trim(),
    channel: draft.channel,
    scopeType: draft.scopeType,
    scopeKey: draft.scopeType === 'SCENE' ? draft.scopeKey.trim() : '',
    templateId: draft.scopeType === 'TEMPLATE' ? draft.templateId.trim() : '',
    reason: draft.reason.trim(),
    source: draft.source,
  };
}

// ================================================================ 重试草稿

/** 发起重试的草稿。 */
export interface RetryDraft {
  recipients: string;
  outcomeKnown: boolean;
  success: boolean;
  statusCode: string;
  failureReason: string;
  durationMs: string;
  reason: string;
}

/** 新建重试时的空白草稿。 */
export function emptyRetryDraft(): RetryDraft {
  return {
    recipients: '',
    outcomeKnown: true,
    success: true,
    statusCode: '200',
    failureReason: '',
    durationMs: '',
    reason: '',
  };
}

/** 重试草稿校验：返回**原因**（null = 合法）。 */
export function validateRetryDraft(draft: RetryDraft): string | null {
  const code = draft.statusCode.trim();
  if (code && !/^\d{1,3}$/.test(code)) {
    return '响应码只能是 0～999 的整数（0 表示没有响应）';
  }
  if (draft.outcomeKnown && draft.success && code && code !== '0') {
    const numeric = Number(code);
    if (numeric < 200 || numeric > 299) {
      return '标记为成功时，响应码只能是 2xx（或 0 表示无 HTTP 码）';
    }
  }
  if (draft.outcomeKnown && !draft.success && code && code !== '0') {
    const numeric = Number(code);
    if (numeric >= 200 && numeric <= 299) {
      return '标记为失败时，响应码不能是 2xx';
    }
  }
  if (draft.outcomeKnown && !draft.success && !draft.failureReason.trim()) {
    return '标记为失败时必须写清失败原因';
  }
  if (draft.durationMs.trim() && !/^\d{1,12}$/.test(draft.durationMs.trim())) {
    return '耗时只能是毫秒整数';
  }
  return null;
}

/** 重试草稿 → 请求体（形状与服务端契约逐字一致）。 */
export function buildRetryInput(draft: RetryDraft): NotifyRetryInput {
  const recipients = draft.recipients
    .split(/[,，\s]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
  return {
    recipients,
    outcomeKnown: draft.outcomeKnown,
    success: draft.success,
    statusCode: draft.statusCode.trim() ? Number(draft.statusCode.trim()) : 0,
    failureReason: draft.failureReason.trim(),
    durationMs: draft.durationMs.trim() ? Number(draft.durationMs.trim()) : 0,
    nextRetryAt: '',
    reason: draft.reason.trim(),
  };
}

/** 逐次尝试按收件人分组（详情页按收件人看历史更直观）。 */
export function groupAttemptsByRecipient(
  attempts: NotifyAttemptRow[],
): { recipientRef: string; attempts: NotifyAttemptRow[] }[] {
  const groups = new Map<string, NotifyAttemptRow[]>();
  for (const attempt of attempts) {
    const bucket = groups.get(attempt.recipientRef);
    if (bucket) bucket.push(attempt);
    else groups.set(attempt.recipientRef, [attempt]);
  }
  return [...groups.entries()].map(([recipientRef, list]) => ({
    recipientRef,
    attempts: list.sort((a, b) => a.attemptNo - b.attemptNo),
  }));
}
