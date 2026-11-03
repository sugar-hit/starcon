import type {
  DeliveryRecordAttemptRow,
  DeliveryRecordCapabilities,
  DeliveryRecordCapability,
  DeliveryRecordDeadLetterPage,
  DeliveryRecordDeadLetterRow,
  DeliveryRecordDetail,
  DeliveryRecordOverview,
  DeliveryRecordPage,
  DeliveryRecordPublishInput,
  DeliveryRecordPublishResult,
  DeliveryRecordReplayInput,
  DeliveryRecordReplayResult,
  DeliveryRecordReplayTaskPage,
  DeliveryRecordReplayTaskRow,
  DeliveryRecordRetrySweep,
} from '../../../../../../packages/api-contracts/src/index';
import { api } from '../../../lib/api';

/**
 * 18.1.2.2「投递记录」前端模型。
 *
 * 蓝图：「**投递记录**：记录事件投递明细：事件 ID、订阅方、投递状态、响应码、重试次数；
 * 提供手动重投与死信重放。输入：投递记录；重放请求。输出：投递日志 + 重放任务。
 * 关系：上游 = 18.1.2.1；下游 = 外部端点。
 * **接收方应按 event_id 幂等去重**——投递保证是"至少一次"，不是"恰好一次"。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「投递过」不等于「送达了」**。响应码 0 表示**根本没拿到响应**
 *    （连接被拒 / 超时 / 没发出去）—— 页面必须显示成「没有响应」，
 *    不能显示成「0」或「未知」，更不能当成成功。
 * 2. **投递失败不是终局**。还在自动重试队列里的那些，显示成「待重试」并给出**下次重试时间**；
 *    只有重试耗尽（已转死信）才显示成终态。把两者混成一种「失败」，
 *    运营会对着一个正在重试的投递去手工重放。
 * 3. **事件 ID 是幂等的依据**，页面上必须**显眼地显示出来** —— 排查重复投递时，
 *    运营第一件事就是要拿这个 ID 去和对方对账。
 * 4. **重投发出去的是同一份报文**（事件 ID 不变）。页面要说明这一点，
 *    否则运营会以为「重投 = 发一个新事件」，给下游造成重复业务。
 * 5. **加载 / 空 / 错误三态齐全**：空态区分「还没有投递过」与「筛选无结果」。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），
 * 前端**不手抄**字符串联合 —— 枚举值由后端的字典列给出。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/deliveries';

/**
 * 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。
 */
function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchCapabilities(): Promise<DeliveryRecordCapabilities> {
  return api<DeliveryRecordCapabilities>(`${base}/capabilities`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<DeliveryRecordOverview> {
  return api<DeliveryRecordOverview>(`${base}/overview`);
}

/** 投递记录列表（**真分页 + 真筛选**，数据来自后端表）。 */
export function fetchDeliveries(params: {
  keyword?: string | undefined;
  status?: string | undefined;
  subscriptionId?: string | undefined;
  eventType?: string | undefined;
  eventId?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<DeliveryRecordPage> {
  return api<DeliveryRecordPage>(`${base}?${query(params)}`);
}

/** 投递详情：记录 + 投递日志 + 死信 + 重放任务。 */
export function fetchDeliveryDetail(id: string): Promise<DeliveryRecordDetail> {
  return api<DeliveryRecordDetail>(`${base}/${id}`);
}

/** 投递日志（每一次尝试一行）。 */
export function fetchAttempts(id: string): Promise<DeliveryRecordAttemptRow[]> {
  return api<DeliveryRecordAttemptRow[]>(`${base}/${id}/attempts`);
}

/** 死信列表（真分页 + 真筛选）。 */
export function fetchDeadLetters(params: {
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<DeliveryRecordDeadLetterPage> {
  return api<DeliveryRecordDeadLetterPage>(`${base}/dead-letters?${query(params)}`);
}

/** 重放任务列表（真分页）。 */
export function fetchReplayTasks(params: {
  page?: number | undefined;
  size?: number | undefined;
}): Promise<DeliveryRecordReplayTaskPage> {
  return api<DeliveryRecordReplayTaskPage>(`${base}/replay-tasks?${query(params)}`);
}

// ================================================================ 写入

/**
 * 发布一个事件（**真的**投出去，并落投递明细）。
 *
 * 🔴 {@code payload} 是**一段 JSON 文本**（平台服务既有契约口径：JSON 字段一律 String），
 * 所以这里要把对象 stringify —— 直接把对象丢过去，后端会答 400。
 */
export function publishEvent(input: {
  eventId?: string | undefined;
  eventType: string;
  schemaVersion?: number | undefined;
  payload: string;
}): Promise<DeliveryRecordPublishResult> {
  const body: DeliveryRecordPublishInput = {
    eventId: input.eventId ?? '',
    eventType: input.eventType,
    schemaVersion: input.schemaVersion ?? 1,
    payload: input.payload,
  };
  return api<DeliveryRecordPublishResult>(`${base}/publish`, 'POST', body);
}

/** 手动重投：重发**同一份报文**，事件 ID 不变。 */
export function replayDelivery(
  id: string,
  input: DeliveryRecordReplayInput = { reason: '' },
): Promise<DeliveryRecordReplayResult> {
  return api<DeliveryRecordReplayResult>(`${base}/${id}/replay`, 'POST', input);
}

/** 死信重放。 */
export function replayDeadLetter(
  id: string,
  input: DeliveryRecordReplayInput = { reason: '' },
): Promise<DeliveryRecordReplayResult> {
  return api<DeliveryRecordReplayResult>(`${base}/dead-letters/${id}/replay`, 'POST', input);
}

/**
 * 扫一轮到期重试（**外部调度**触发的一次入口；本服务不装定时器）。
 *
 * 🔴 页面提供这个入口是给「运营手工救场」用的；正常情况下由 K8s CronJob 调。
 */
export function sweepRetryDue(limit = 50): Promise<DeliveryRecordRetrySweep> {
  return api<DeliveryRecordRetrySweep>(`${base}/retry-due?${query({ limit })}`, 'POST');
}

// ================================================================ 词表

/** 语气档位。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/** 投递状态的可选值（与后端的字典列一一对应）。 */
export const DELIVERY_STATUS_OPTIONS = [
  { value: 'PENDING', label: '待重试' },
  { value: 'DELIVERED', label: '已送达' },
  { value: 'FAILED', label: '失败' },
  { value: 'DEAD_LETTER', label: '已转死信' },
] as const;

/** 死信状态的可选值。 */
export const DEAD_LETTER_STATUS_OPTIONS = [
  { value: 'PENDING', label: '待处理' },
  { value: 'REPLAYED', label: '已重放' },
  { value: 'EXPIRED', label: '已过期' },
] as const;

// ================================================================ 展示口径

/** 投递状态的语气：送达是绿的，待重试是黄的，失败是红的，已转死信是灰的。 */
export function deliveryStatusTone(status: string): Tone {
  if (status === 'DELIVERED') return 'ok';
  if (status === 'PENDING') return 'warn';
  if (status === 'FAILED') return 'danger';
  return 'muted';
}

/** 死信状态的语气。 */
export function deadLetterStatusTone(status: string): Tone {
  if (status === 'PENDING') return 'warn';
  if (status === 'REPLAYED') return 'ok';
  return 'muted';
}

/** 能力声明的语气。 */
export function capabilityTone(available: boolean): Tone {
  return available ? 'ok' : 'muted';
}

/**
 * 响应码的显示口径。
 *
 * 🔴 **0 不是「未知」也不是「成功」** —— 它表示「**根本没拿到响应**」
 * （连接被拒 / 超时 / 没有发出去）。显示成「没有响应」才不误导人：
 * 运营看到「0」会以为是接口返回了 0，而看到「没有响应」才知道是网络层没打通。
 */
export function statusCodeLabel(statusCode: number): string {
  if (statusCode === 0) return '没有响应';
  return String(statusCode);
}

/** 这次投递**有没有送达**。 */
export function isDelivered(row: { status: string; statusCode: number }): boolean {
  return row.status === 'DELIVERED' && row.statusCode >= 200 && row.statusCode <= 299;
}

/**
 * 投递状态的**一句话解释**。
 *
 * 🔴 「待重试」与「已转死信」是**两件事**：前者还在自动重试队列里（别去手工重放），
 * 后者重试耗尽了（该人工处置）。混成一句「失败」会让人对着正在重试的投递重复操作。
 */
export function deliveryStatusHint(row: {
  status: string;
  statusCode: number;
  nextRetryAt?: string | null;
  attemptCount: number;
}): string {
  switch (row.status) {
    case 'DELIVERED':
      return `对方已接受（HTTP ${row.statusCode}），共尝试 ${row.attemptCount} 次。`;
    case 'PENDING':
      return row.nextRetryAt
        ? `这次没送达（HTTP ${row.statusCode}），还在自动重试队列里，下次重试 ${row.nextRetryAt}。`
        : `这次没送达（HTTP ${row.statusCode}），还在自动重试队列里。`;
    case 'FAILED':
      return '这次没送达，而且是**不会再自动重试**的失败 —— 需要先修好订阅再手工重投。';
    case 'DEAD_LETTER':
      return `重试次数已用尽（共 ${row.attemptCount} 次），已进死信箱，可以人工重放。`;
    default:
      return '';
  }
}

/** 尝试来源的显示口径。 */
export function attemptSourceLabel(source: string): string {
  switch (source) {
    case 'INITIAL':
      return '首次';
    case 'RETRY':
      return '自动重试';
    case 'MANUAL':
      return '手动重投';
    case 'DEAD_LETTER_REPLAY':
      return '死信重放';
    default:
      return source;
  }
}

/** 重放触发方式的显示口径。 */
export function replayTriggerLabel(trigger: string): string {
  return trigger === 'DEAD_LETTER' ? '死信重放' : '手动重投';
}

/**
 * 事件 ID 的显示口径。
 *
 * 🔴 **完整显示，不截断**：事件 ID 是接收方幂等去重的依据，
 * 运营要拿它去和对方对账 —— 少显示几位，对账就做不成。
 */
export function eventIdLabel(eventId: string): string {
  return eventId;
}

/**
 * 分页口径的一句话。
 *
 * 🔴 用 `total` 而不是「本页有几行」—— 前者才是「一共多少」，
 * 后者只说明这一页装满了。
 */
export function paginationLabel(page: { page: number; size: number; total: number }): string {
  const first = page.total === 0 ? 0 : page.page * page.size + 1;
  const last = Math.min((page.page + 1) * page.size, page.total);
  return `第 ${first}-${last} 条 / 共 ${page.total} 条`;
}

/**
 * 「投递保证是至少一次」这句话在界面上的落地文案。
 *
 * 🔴 重投**不是**发一个新事件：事件 ID 不变、报文字节不变，
 * 对方按 event_id 去重即可。界面必须说清，否则运营会以为重投会造成下游重复业务。
 */
export function replayNotice(): string {
  return '重投发出去的是**同一份报文**（事件 ID 不变）—— 接收方按 event_id 幂等去重即可，不会重复处理。';
}

/**
 * 「事件实际投递」这一维的能力声明。
 *
 * 🔴 返回 {@code null} 表示后端**没有声明**这一维 —— 那与「声明了但未接入」不同：
 * 前者要显示成「未知」，不能显示成「已接入」。
 */
export function deliveryCapability(
  capabilities: DeliveryRecordCapabilities | null,
): DeliveryRecordCapability | null {
  const list = capabilities?.capabilities ?? [];
  return list.find((c) => c.name.includes('事件实际投递')) ?? null;
}

/**
 * 未接入的能力该怎么显示。
 *
 * 🔴 显示成「未接入」+ **一句说清现在怎么办的话**，
 * 而不是把那一格藏起来 —— 藏起来等于「这个功能不存在」。
 */
export function unavailableHint(capability: DeliveryRecordCapability | null): string {
  if (!capability) return '后端没有声明这一维能力 —— 状态未知。';
  const owner = capability.ownerModule ? `（归 ${capability.ownerModule}）` : '';
  return `未接入${owner}：${capability.impact || capability.basis}`;
}
