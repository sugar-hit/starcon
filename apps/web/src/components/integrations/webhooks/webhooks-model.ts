import type {
  EventSubscriptionAuditEventPage,
  EventSubscriptionCapabilities,
  EventSubscriptionCapability,
  EventSubscriptionDeliveryInput,
  EventSubscriptionDeliveryOutcome,
  EventSubscriptionDetail,
  EventSubscriptionEventTypeInput,
  EventSubscriptionEventTypePage,
  EventSubscriptionEventTypeRow,
  EventSubscriptionEventTypeStatusInput,
  EventSubscriptionFilterInput,
  EventSubscriptionFilterRow,
  EventSubscriptionInput,
  EventSubscriptionOverview,
  EventSubscriptionPage,
  EventSubscriptionPolicyInput,
  EventSubscriptionPolicyRow,
  EventSubscriptionRow,
  EventSubscriptionSecretIssued,
  EventSubscriptionSecretRow,
  EventSubscriptionSignaturePreview,
  EventSubscriptionSignaturePreviewInput,
} from '../../../../../../packages/api-contracts/src/index';
import { api } from '../../../lib/api';

/**
 * 18.1.2.1「事件订阅」前端模型。
 *
 * 蓝图：「**事件订阅**：对外提供事件订阅（webhook）：可订阅事件列表、订阅端点配置、签名密钥、
 * 事件过滤、重试与死信策略。输入：订阅配置；事件定义（schema 版本）。输出：订阅记录；
 * 事件投递（带签名）。关系：上游 = 业务事件（outbox）；下游 = 外部端点。
 * **事件 schema 必须版本化，破坏性变更要新增版本而非原地修改。**」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「可订阅事件列表」不能是一份写死的清单**。事件类型真查后端的注册表
 *    （`GET /platform/webhooks/event-types`），每条都带**出处**（哪个服务、哪一行代码）。
 *    前端**不**维护一份本地词表 —— 那份清单看起来会随着业务演进而变久，实际不会。
 * 2. **「投递未接入」显示成「未接入」，不是「投递成功」**。本仓没有事件投递执行器
 *    （归 18.1.2.2），所以页面**不提供**「投递」按钮 —— 一个点了必然 409 的按钮不如没有。
 * 3. **签名密钥明文只出现一次**。页面在生成 / 轮换之后**立刻**把明文摆出来，
 *    但**任何时候都不从列表接口拿明文**（后端也拿不到）。
 * 4. **「结构不可原地修改」要在界面上说清**。事件定义只显示「已登记第 N 版」，
 *    改结构的入口是「登记新版本」，不是「编辑」。
 * 5. **加载 / 空 / 错误三态齐全**：空态区分「还没登记过」与「筛选无结果」。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），
 * 前端**不手抄**字符串联合 —— 枚举值由后端的字典列给出。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/webhooks';

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
export function fetchCapabilities(): Promise<EventSubscriptionCapabilities> {
  return api<EventSubscriptionCapabilities>(`${base}/capabilities`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<EventSubscriptionOverview> {
  return api<EventSubscriptionOverview>(`${base}/overview`);
}

/**
 * 可订阅事件列表（**真分页 + 真筛选**，数据来自后端注册表）。
 *
 * 🔴 前端**不**维护任何本地事件清单 —— 清单是后端表里的行，每行带出处。
 */
export function fetchEventTypes(params: {
  keyword?: string | undefined;
  status?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<EventSubscriptionEventTypePage> {
  return api<EventSubscriptionEventTypePage>(`${base}/event-types?${query(params)}`);
}

/** 订阅列表（真分页 + 真筛选）。 */
export function fetchSubscriptions(params: {
  keyword?: string | undefined;
  status?: string | undefined;
  appId?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<EventSubscriptionPage> {
  return api<EventSubscriptionPage>(`${base}/subscriptions?${query(params)}`);
}

/** 订阅详情：订阅 + 事件过滤 + 签名密钥元数据 + 生效策略。 */
export function fetchSubscriptionDetail(id: string): Promise<EventSubscriptionDetail> {
  return api<EventSubscriptionDetail>(`${base}/subscriptions/${id}`);
}

/** 事件过滤列表。 */
export function fetchFilters(id: string): Promise<EventSubscriptionFilterRow[]> {
  return api<EventSubscriptionFilterRow[]>(`${base}/subscriptions/${id}/events`);
}

/** 签名密钥列表（**只有元数据，没有明文**）。 */
export function fetchSigningSecrets(id: string): Promise<EventSubscriptionSecretRow[]> {
  return api<EventSubscriptionSecretRow[]>(`${base}/subscriptions/${id}/signing-secrets`);
}

/** 策略列表（租户级默认 + 各订阅的专属策略）。 */
export function fetchPolicies(): Promise<EventSubscriptionPolicyRow[]> {
  return api<EventSubscriptionPolicyRow[]>(`${base}/policies`);
}

/** 审计事件列表（真分页，新到旧）。 */
export function fetchAuditEvents(params: {
  subscriptionId?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<EventSubscriptionAuditEventPage> {
  return api<EventSubscriptionAuditEventPage>(`${base}/audit-events?${query(params)}`);
}

// ================================================================ 写入

/** 登记一个事件定义（= 一个事件类型的一个 schema 版本）。 */
export function registerEventType(
  input: EventSubscriptionEventTypeInput,
): Promise<EventSubscriptionEventTypeRow> {
  return api<EventSubscriptionEventTypeRow>(`${base}/event-types`, 'POST', input);
}

/**
 * 改事件定义的**状态**（ACTIVE ↔ DEPRECATED）。
 *
 * 🔴 **只能改状态**：结构（event_type / schema_version / payload_schema）不可原地修改 ——
 * 后端触发器会直接拒掉。要改结构请登记一个更高的 schema 版本。
 */
export function changeEventTypeStatus(
  id: string,
  status: string,
): Promise<EventSubscriptionEventTypeRow> {
  return api<EventSubscriptionEventTypeRow>(
    `${base}/event-types/${id}/status`,
    'POST',
    { status } as EventSubscriptionEventTypeStatusInput,
  );
}

/** 创建订阅。 */
export function createSubscription(input: EventSubscriptionInput): Promise<EventSubscriptionRow> {
  return api<EventSubscriptionRow>(`${base}/subscriptions`, 'POST', input);
}

/** 改订阅（端点 / 名称 / 状态 / 说明）。 */
export function updateSubscription(
  id: string,
  input: EventSubscriptionInput,
): Promise<EventSubscriptionRow> {
  return api<EventSubscriptionRow>(`${base}/subscriptions/${id}`, 'POST', input);
}

/** 吊销订阅（**不删**：订阅与其审计痕迹都留着）。 */
export function revokeSubscription(id: string, reason?: string): Promise<EventSubscriptionRow> {
  return api<EventSubscriptionRow>(`${base}/subscriptions/${id}/revoke?${query({ reason })}`, 'POST');
}

/** 加一条事件过滤（只能订阅**已登记**的 schema）。 */
export function addFilter(
  id: string,
  input: EventSubscriptionFilterInput,
): Promise<EventSubscriptionFilterRow> {
  return api<EventSubscriptionFilterRow>(`${base}/subscriptions/${id}/events`, 'POST', input);
}

/** 删一条事件过滤。 */
export function removeFilter(id: string, filterId: string): Promise<void> {
  return api<void>(`${base}/subscriptions/${id}/events/${filterId}`, 'DELETE');
}

/**
 * 生成签名密钥 —— **明文只在这一次的响应里出现**。
 *
 * 🔴 页面**不**缓存它到任何长期存储：它只活在这一次的状态里，
 * 运营复制走之后刷新页面就没了（后端也再也拿不回来）。
 */
export function issueSigningSecret(id: string): Promise<EventSubscriptionSecretIssued> {
  return api<EventSubscriptionSecretIssued>(`${base}/subscriptions/${id}/signing-secrets`, 'POST');
}

/** 轮换签名密钥：生成新明文 + 旧密钥立即失效。 */
export function rotateSigningSecret(id: string): Promise<EventSubscriptionSecretIssued> {
  return api<EventSubscriptionSecretIssued>(
    `${base}/subscriptions/${id}/signing-secrets/rotate`,
    'POST',
  );
}

/** 吊销生效中的签名密钥（泄露时的一键止血）。 */
export function revokeSigningSecret(id: string): Promise<EventSubscriptionSecretRow> {
  return api<EventSubscriptionSecretRow>(
    `${base}/subscriptions/${id}/signing-secrets/revoke`,
    'POST',
  );
}

/** 新建一份策略（subscriptionId 为空 = 租户级默认策略）。 */
export function createPolicy(input: EventSubscriptionPolicyInput): Promise<EventSubscriptionPolicyRow> {
  return api<EventSubscriptionPolicyRow>(`${base}/policies`, 'POST', input);
}

/** 改策略。 */
export function updatePolicy(
  id: string,
  input: EventSubscriptionPolicyInput,
): Promise<EventSubscriptionPolicyRow> {
  return api<EventSubscriptionPolicyRow>(`${base}/policies/${id}`, 'POST', input);
}

/**
 * 签名试算：给定样例载荷，返回平台**会发**的签名头。
 *
 * 🔴 **不投递、不写任何投递记录** —— 只把「签名怎么算」变成对接方能当场核对的事实。
 */
export function previewSignature(
  id: string,
  input: EventSubscriptionSignaturePreviewInput,
): Promise<EventSubscriptionSignaturePreview> {
  return api<EventSubscriptionSignaturePreview>(
    `${base}/subscriptions/${id}/signature-preview`,
    'POST',
    input,
  );
}

/**
 * 投递一次事件。
 *
 * 🔴 **本仓当前恒 409，且一次包都不发**：事件投递执行器未接入（归 18.1.2.2「投递记录」）。
 * 这里**不**做任何本地兜底 —— 一条「看起来推过去了」的假回执会让
 * 「对方系统已经收到并处理了这个事件」在数据上看起来发生过，而现场一个包都没发出去。
 * 适配器交付之后，这个函数立刻变成真的。
 */
export function deliverEvent(
  id: string,
  input: EventSubscriptionDeliveryInput,
): Promise<EventSubscriptionDeliveryOutcome> {
  return api<EventSubscriptionDeliveryOutcome>(`${base}/subscriptions/${id}/deliver`, 'POST', input);
}

// ================================================================ 词表

/** 语气档位。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/** 事件定义状态。 */
export const EVENT_TYPE_STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '可用' },
  { value: 'DEPRECATED', label: '已废弃' },
] as const;

/** 订阅状态。 */
export const SUBSCRIPTION_STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '生效' },
  { value: 'PAUSED', label: '暂停' },
  { value: 'DISABLED', label: '已停用' },
] as const;

// ================================================================ 展示口径

/** 事件定义状态的语气：已废弃是灰的（还能收，但不该再选）。 */
export function eventTypeStatusTone(status: string): Tone {
  return status === 'ACTIVE' ? 'ok' : 'muted';
}

/** 订阅状态的语气：生效是绿的，暂停是黄的，已停用是灰的。 */
export function subscriptionStatusTone(status: string): Tone {
  if (status === 'ACTIVE') return 'ok';
  if (status === 'PAUSED') return 'warn';
  return 'muted';
}

/** 签名密钥状态的语气：生效是绿的，已轮换是灰的，已吊销是红的（验签会失败）。 */
export function secretStatusTone(status: string): Tone {
  if (status === 'ACTIVE') return 'ok';
  if (status === 'REVOKED') return 'danger';
  return 'muted';
}

/** 能力声明的语气。 */
export function capabilityTone(available: boolean): Tone {
  return available ? 'ok' : 'muted';
}

/**
 * 「事件实际投递」这一维的能力声明。
 *
 * 🔴 返回 {@code null} 表示后端**没有声明**这一维 —— 那与「声明了但未接入」不同：
 * 前者要显示成「未知」，不能显示成「已接入」。
 */
export function deliveryCapability(
  capabilities: EventSubscriptionCapabilities | null,
): EventSubscriptionCapability | null {
  const list = capabilities?.capabilities ?? [];
  return list.find((c) => c.name.includes('事件实际投递')) ?? null;
}

/**
 * 页面**要不要**提供「投递」这个动作。
 *
 * 🔴 只有后端明确声明 `available=true` 时才提供。未接入时页面**不**提供这个按钮 ——
 * 一个点了必然 409 的按钮不如没有：它会让运营以为「点一下就能推出去」。
 */
export function canDeliver(capabilities: EventSubscriptionCapabilities | null): boolean {
  return deliveryCapability(capabilities)?.available === true;
}

/**
 * 未接入的能力该怎么显示。
 *
 * 🔴 显示成「未接入（归 18.1.2.2）」+ **一句说清现在怎么办的话**，
 * 而不是把那一格藏起来 —— 藏起来等于「这个功能不存在」，而真相是
 * 「它缺在投递记录那一格身上，订阅配置这条线今天就能用」。
 */
export function unavailableHint(capability: EventSubscriptionCapability | null): string {
  if (!capability) return '后端没有声明这一维能力 —— 状态未知。';
  const owner = capability.ownerModule ? `（归 ${capability.ownerModule}）` : '';
  return `未接入${owner}：${capability.impact || capability.basis}`;
}

/**
 * 事件定义的标题口径：**带版本号**。
 *
 * 🔴 「SkuProfileSaved」和「SkuProfileSaved v2」是**两份不同的契约** ——
 * 显示时必须带上版本，否则运营会以为「改过了」，而对接方拿到的字段结构可能完全不同。
 */
export function eventTypeVersionLabel(row: {
  eventType: string;
  schemaVersion: number;
}): string {
  return `${row.eventType} v${row.schemaVersion}`;
}

/**
 * 事件过滤条件的显示口径。
 *
 * 🔴 空过滤 = 「该事件的全部实例都收」，显示成「不过滤」而不是空白 ——
 * 空白看着像「没配好」，而它其实是「不额外过滤」。
 */
export function filterSummary(filter: string | undefined): string {
  if (!filter) return '不过滤';
  const trimmed = filter.trim();
  if (trimmed === '' || trimmed === '{}') return '不过滤';
  return trimmed;
}

/**
 * 签名头的显示口径。
 *
 * 🔴 头值是 `t=<时间戳>,v1=<签名>` —— 时间戳在**前**是有意的：
 * 验签方要靠它拒绝重放。页面显示时**不**拆开丢掉时间戳。
 */
export function signatureHeaderLabel(preview: {
  signatureHeader: string;
  headerValue: string;
}): string {
  return `${preview.signatureHeader}: ${preview.headerValue}`;
}

/**
 * 对接方**怎么自己验一遍**的说明。
 *
 * 🔴 这段说明必须与后端真的算法**逐字一致**（HMAC-SHA256，盖「时间戳 + 点 + 原始字节」）。
 * 说明与实现对不上，对接方会照着说明写出一个永远验不过的实现。
 */
export function signatureVerificationSteps(preview: {
  signatureAlgorithm: string;
  signedPayload: string;
}): string[] {
  return [
    `按 ${preview.signatureAlgorithm} 取签名字节：把「${preview.signedPayload}」按 UTF-8 取字节。`,
    '用订阅的签名密钥做 HMAC 密钥，算出摘要，取十六进制小写。',
    '与签名头里的 v1 段逐字符比对（不一致就是签名不通过）。',
    '另外校验头里的时间戳 t：超出时间窗的签名一律拒收（防重放）。',
  ];
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
 * 密钥明文的展示口径。
 *
 * 🔴 **不截断**：对接方拿到的是要粘进自己系统的密钥，
 * 页面少给一位就会让对方验签失败 —— 而失败现场很难查。
 * 真正该防的是「把明文留在页面上太久」，所以页面只把它放在
 * 「刚生成」那一个状态里，刷新即消失。
 */
export function secretPlaintext(issued: EventSubscriptionSecretIssued | null): string {
  return issued?.plaintext ?? '';
}

/** 订阅是否还能改（已停用是终态）。 */
export function isEditable(row: { status: string }): boolean {
  return row.status !== 'DISABLED';
}

/** 审计动作的中文说法。 */
export function auditActionLabel(action: string): string {
  switch (action) {
    case 'EVENT_TYPE_REGISTERED':
      return '登记事件定义';
    case 'EVENT_TYPE_STATUS_CHANGED':
      return '改事件定义状态';
    case 'SUBSCRIPTION_CREATED':
      return '创建订阅';
    case 'SUBSCRIPTION_UPDATED':
      return '修改订阅';
    case 'SUBSCRIPTION_REVOKED':
      return '吊销订阅';
    case 'FILTER_ADDED':
      return '加事件过滤';
    case 'FILTER_REMOVED':
      return '删事件过滤';
    case 'SIGNING_SECRET_GENERATED':
      return '生成签名密钥';
    case 'SIGNING_SECRET_ROTATED':
      return '轮换签名密钥';
    case 'SIGNING_SECRET_REVOKED':
      return '吊销签名密钥';
    case 'SIGNATURE_PREVIEWED':
      return '签名试算';
    case 'POLICY_CREATED':
      return '新建策略';
    case 'POLICY_UPDATED':
      return '修改策略';
    default:
      return action;
  }
}
