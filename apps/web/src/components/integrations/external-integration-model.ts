import type {
  ExternalAdapterAvailability,
  ExternalConnectionHealth,
  ExternalConnectionInput,
  ExternalConnectionRow,
  ExternalIntegrationCapabilities,
  ExternalIntegrationOverview,
  ExternalSyncRecordInput,
  ExternalSyncRecordRow,
  ExternalSyncTaskInput,
  ExternalSyncTaskRow,
  UpstreamChannelPlatformRow,
  UpstreamChannelShopRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.2.3.1「其他对接」前端模型。
 *
 * 蓝图：「**其他对接**：对接其他外部系统：1688/淘宝货源平台、海外仓服务商、物流商、
 * 聚合认证服务、自建 BI。统一管理连接配置与同步健康。输入：连接配置；同步任务配置。
 * 输出：连接状态 + 同步记录。关系：上游 = 2 渠道机制；下游 = 外部服务。
 * **每个对接都有独立连接与独立健康状态**，一个外部服务故障不应影响其他集成。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「未接入」显示成「未接入」，不是「同步成功」**。本仓没有任何真实的外部系统
 *    凭据与网络通道，所以六个适配器 `connected=false`。页面**不提供**必然 409 的
 *    「发起同步」按钮（一个点了必然失败的按钮会让运营以为「点一下就能同步」）。
 *    与外部系统有没有直连通道，**不影响**下面这些**本系统内部能力**的真实生效：
 *    对接连接配置（密钥只存引用）、同步任务配置、每个对接**独立**的健康状态、
 *    同步记录台账、以及「外部侧已完成」的同步**人工登记**（今天就能用）。
 * 2. **「本地台账成功」≠「外部侧已登记」**。人工登记的行标着
 *    「外部侧已完成 · 人工登记」+「未与外部系统核对过」，
 *    页面上**必须**把这两格显示出来，否则会被读成「对方系统里也有这一笔」。
 * 3. **每个对接的健康状态是独立的**。页面上**没有**「整体健康」这一格 ——
 *    一个对接挂了不能显示成「全部都不健康」。
 * 4. **密钥只存引用**。配置表单里只有「密钥引用」与「末位提示」，
 *    没有任何「粘贴 API Key」的地方。
 * 5. **加载 / 空 / 错误三态齐全**：空态区分「还没登记过」与「筛选无结果」。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），
 * 前端**不手抄**字符串联合 —— 枚举值由后端的字典列给出。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/external-integrations';

/**
 * 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。
 */
function query(params: Record<string, string | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, value);
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：哪些真接通、哪些还没交付、归谁。 */
export function fetchCapabilities(): Promise<ExternalIntegrationCapabilities> {
  return api<ExternalIntegrationCapabilities>(`${base}/capabilities`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<ExternalIntegrationOverview> {
  return api<ExternalIntegrationOverview>(`${base}/overview`);
}

/** 逐个外部系统的通道可用性（**逐个如实答**）。 */
export function fetchAvailability(): Promise<ExternalAdapterAvailability[]> {
  return api<ExternalAdapterAvailability[]>(`${base}/adapters/availability`);
}

/** 对接连接配置列表（真筛选 + 真关键词）。 */
export function fetchConnections(params?: {
  category?: string | undefined;
  adapterCode?: string | undefined;
  status?: string | undefined;
  healthStatus?: string | undefined;
  keyword?: string | undefined;
}): Promise<ExternalConnectionRow[]> {
  return api<ExternalConnectionRow[]>(`${base}/connections?${query({ ...params })}`);
}

/** 一条对接连接配置。 */
export function fetchConnection(id: string): Promise<ExternalConnectionRow> {
  return api<ExternalConnectionRow>(`${base}/connections/${id}`);
}

/**
 * 🔴 **一个对接自己**的健康状态。
 *
 * 响应里出现的每一个数都来自**同一个 connection_id** —— 别的对接挂了几个不在这里。
 */
export function fetchHealth(id: string): Promise<ExternalConnectionHealth> {
  return api<ExternalConnectionHealth>(`${base}/connections/${id}/health`);
}

/** 同步任务列表（真筛选 + 真关键词）。 */
export function fetchSyncTasks(params?: {
  connectionId?: string | undefined;
  taskType?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
}): Promise<ExternalSyncTaskRow[]> {
  return api<ExternalSyncTaskRow[]>(`${base}/sync-tasks?${query({ ...params })}`);
}

/** 一条同步任务。 */
export function fetchSyncTask(id: string): Promise<ExternalSyncTaskRow> {
  return api<ExternalSyncTaskRow>(`${base}/sync-tasks/${id}`);
}

/** 同步记录台账（真筛选 + 真关键词）。 */
export function fetchSyncRecords(params?: {
  connectionId?: string | undefined;
  taskId?: string | undefined;
  status?: string | undefined;
  source?: string | undefined;
  keyword?: string | undefined;
}): Promise<ExternalSyncRecordRow[]> {
  return api<ExternalSyncRecordRow[]>(`${base}/sync-records?${query({ ...params })}`);
}

/** 一条同步记录。 */
export function fetchSyncRecord(id: string): Promise<ExternalSyncRecordRow> {
  return api<ExternalSyncRecordRow>(`${base}/sync-records/${id}`);
}

/** 上游 2 渠道机制 · 平台目录（**只读**；读不到 ⇒ 503）。 */
export function fetchUpstreamPlatforms(): Promise<UpstreamChannelPlatformRow[]> {
  return api<UpstreamChannelPlatformRow[]>(`${base}/upstream/platforms`);
}

/** 上游 2 渠道机制 · 店铺（**只读**；读不到 ⇒ 503）。 */
export function fetchUpstreamShops(): Promise<UpstreamChannelShopRow[]> {
  return api<UpstreamChannelShopRow[]>(`${base}/upstream/shops`);
}

// ================================================================ 写入

/** 新增或修改一个对接连接配置（**密钥只存引用**）。 */
export function saveConnection(input: ExternalConnectionInput): Promise<ExternalConnectionRow> {
  return api<ExternalConnectionRow>(`${base}/connections`, 'POST', input);
}

/** 停用一个对接连接（**不删**：历史同步记录要能复现）。 */
export function disableConnection(id: string): Promise<ExternalConnectionRow> {
  return api<ExternalConnectionRow>(`${base}/connections/${id}/disable`, 'POST');
}

/** 🔴 重算**某一个**连接的健康状态（只改这一行）。 */
export function refreshHealth(id: string): Promise<ExternalConnectionHealth> {
  return api<ExternalConnectionHealth>(`${base}/connections/${id}/health/refresh`, 'POST');
}

/** 新增或修改一个同步任务配置。 */
export function saveSyncTask(input: ExternalSyncTaskInput): Promise<ExternalSyncTaskRow> {
  return api<ExternalSyncTaskRow>(`${base}/sync-tasks`, 'POST', input);
}

/** 停用一个同步任务（**不删**：历史同步记录要能复现）。 */
export function disableSyncTask(id: string): Promise<ExternalSyncTaskRow> {
  return api<ExternalSyncTaskRow>(`${base}/sync-tasks/${id}/disable`, 'POST');
}

/**
 * **经由外部通道发起一次同步**（未接入 ⇒ 409）。
 *
 * ⚠️ 页面**只在适配器 `connected === true` 时**才提供这个入口 ——
 * 本仓六个适配器全是「未接入」，所以这个按钮今天不会出现。
 */
export function runSync(taskId: string): Promise<ExternalSyncRecordRow> {
  return api<ExternalSyncRecordRow>(`${base}/sync-tasks/${taskId}/run`, 'POST');
}

/**
 * 🔴 **登记一条「外部侧已完成」的同步**（人工登记；**没有**经过本系统的外部通道）。
 *
 * 记进去的行 `source=EXTERNAL` 且 `provider_notified=false` ——
 * 它记的是「本系统没有与外部系统核对过」，不能被读成「已登记」。
 */
export function recordExternalSync(input: ExternalSyncRecordInput): Promise<ExternalSyncRecordRow> {
  return api<ExternalSyncRecordRow>(`${base}/sync-records`, 'POST', input);
}

// ================================================================ 字典

/** 对接类别（蓝图点名的五类外部系统）。 */
export const CATEGORIES = [
  { code: 'SUPPLY_SOURCE', label: '1688 / 淘宝货源平台' },
  { code: 'OVERSEAS_WAREHOUSE', label: '海外仓服务商' },
  { code: 'LOGISTICS_CARRIER', label: '物流商' },
  { code: 'CERTIFICATION_AGGREGATOR', label: '聚合认证服务' },
  { code: 'SELF_HOSTED_BI', label: '自建 BI' },
] as const;

/** 适配器 → 类别（货源平台有两个，其余一一对应）。 */
export const ADAPTERS = [
  { code: 'ALIBABA_1688', label: '1688 开放平台', category: 'SUPPLY_SOURCE' },
  { code: 'TAOBAO', label: '淘宝开放平台', category: 'SUPPLY_SOURCE' },
  { code: 'OVERSEAS_WAREHOUSE', label: '海外仓服务商', category: 'OVERSEAS_WAREHOUSE' },
  { code: 'LOGISTICS_CARRIER', label: '物流商', category: 'LOGISTICS_CARRIER' },
  { code: 'CERTIFICATION_AGGREGATOR', label: '聚合认证服务', category: 'CERTIFICATION_AGGREGATOR' },
  { code: 'SELF_HOSTED_BI', label: '自建 BI', category: 'SELF_HOSTED_BI' },
] as const;

/** 同步类型 → 它**只**属于哪一类对接。 */
export const TASK_TYPES = [
  { code: 'CATALOG_SYNC', label: '货源清单同步', category: 'SUPPLY_SOURCE' },
  { code: 'INVENTORY_SYNC', label: '库存观测同步', category: 'OVERSEAS_WAREHOUSE' },
  { code: 'SHIPMENT_TRACKING_SYNC', label: '运单轨迹同步', category: 'LOGISTICS_CARRIER' },
  { code: 'CERTIFICATE_SYNC', label: '认证状态同步', category: 'CERTIFICATION_AGGREGATOR' },
  { code: 'BI_DATASET_SYNC', label: '数据集同步', category: 'SELF_HOSTED_BI' },
] as const;

/** 同步方向。 */
export const DIRECTIONS = [
  { code: 'PULL', label: '拉取' },
  { code: 'PUSH', label: '推送' },
] as const;

/** 人工登记可用的结论（NOT_CONNECTED 的意思是「根本没发出去」，人工登记不了）。 */
export const MANUAL_RECORD_STATUSES = [
  { code: 'SUCCEEDED', label: '成功' },
  { code: 'FAILED', label: '失败' },
  { code: 'TIMEOUT', label: '超时' },
] as const;

/** 失败分类。 */
export const FAILURE_CLASSES = [
  { code: 'REJECTED', label: '外部系统明确拒绝' },
  { code: 'TIMEOUT', label: '超时' },
  { code: 'UNAVAILABLE', label: '不可达' },
  { code: 'NOT_CONFIGURED', label: '未接入' },
  { code: 'DATA_ERROR', label: '数据错误' },
] as const;

// ================================================================ 纯函数（可单测）

/** 语气。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/**
 * 健康状态 → 语气。
 *
 * 🔴 **NOT_CONNECTED 是 muted 而不是 danger**：「没接通」与「接通了但坏了」是两回事，
 * 混成红色会让人以为外部系统挂了 —— 而它其实只是**我们还没接上**。
 */
export function healthTone(status: string): Tone {
  switch (status) {
    case 'HEALTHY':
      return 'ok';
    case 'DEGRADED':
      return 'warn';
    case 'UNHEALTHY':
      return 'danger';
    case 'NOT_CONNECTED':
    case 'UNKNOWN':
    default:
      return 'muted';
  }
}

/** 健康状态的中文说法。 */
export function healthLabel(status: string): string {
  switch (status) {
    case 'HEALTHY':
      return '健康';
    case 'DEGRADED':
      return '亚健康';
    case 'UNHEALTHY':
      return '不健康';
    case 'UNKNOWN':
      return '未验证';
    case 'NOT_CONNECTED':
      return '未接入';
    default:
      return status;
  }
}

/** 一次同步的结论 → 语气。 */
export function syncStatusTone(status: string): Tone {
  switch (status) {
    case 'SUCCEEDED':
      return 'ok';
    case 'FAILED':
      return 'danger';
    case 'TIMEOUT':
      return 'warn';
    case 'NOT_CONNECTED':
    default:
      return 'muted';
  }
}

/** 一次同步的结论的中文说法。 */
export function syncStatusLabel(status: string): string {
  switch (status) {
    case 'SUCCEEDED':
      return '成功';
    case 'FAILED':
      return '失败';
    case 'TIMEOUT':
      return '超时';
    case 'NOT_CONNECTED':
      return '外部通道未接入';
    default:
      return status;
  }
}

/** 失败分类的中文说法。 */
export function failureClassLabel(failureClass: string): string {
  switch (failureClass) {
    case 'REJECTED':
      return '外部系统明确拒绝';
    case 'TIMEOUT':
      return '超时';
    case 'UNAVAILABLE':
      return '不可达';
    case 'NOT_CONFIGURED':
      return '未接入';
    case 'DATA_ERROR':
      return '数据错误';
    default:
      return failureClass;
  }
}

/** 适配器代码 → 类别（一对一，货源平台有两个）。取不到返回 {@code null}。 */
export function categoryForAdapter(adapterCode: string): string | null {
  return ADAPTERS.find((a) => a.code === adapterCode)?.category ?? null;
}

/** 某类别下**允许**的适配器（选类别时只列这几个）。 */
export function adaptersForCategory(category: string): (typeof ADAPTERS)[number][] {
  return ADAPTERS.filter((a) => a.category === category);
}

/** 某类别下**允许**的同步类型（一个类型只属于一个类别）。 */
export function taskTypesForCategory(category: string): (typeof TASK_TYPES)[number][] {
  return TASK_TYPES.filter((t) => t.category === category);
}

/** 条数格式化（后端是 NUMERIC(19,6)，页面上按整数显示）。 */
export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return value.toLocaleString('en-US');
}

/** 时间格式化（ISO-8601 → 本地时刻；空值显示「—」）。 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '—';
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 🔴 这个适配器的**直连**通道通不通。
 *
 * 页面据此决定**要不要给「发起同步」按钮** —— 一个点了必然 409 的按钮不如没有。
 */
export function directSyncAvailable(
  availability: ExternalAdapterAvailability[] | undefined,
  adapterCode: string,
): boolean {
  return availability?.some((a) => a.adapterCode === adapterCode && a.connected) ?? false;
}

/** 没接通时的说明（写清缺什么、归谁）。 */
export function unavailableHint(availability: ExternalAdapterAvailability[] | undefined): string {
  const notConnected = (availability ?? []).filter((a) => !a.connected);
  if (notConnected.length === 0) return '';
  return notConnected.map((a) => `${a.adapterLabel}：${a.reason || '外部通道未接入'}`).join('；');
}

/**
 * 🔴 同步记录的「有没有与外部系统核对过」这一格怎么说。
 *
 * <p>「本地台账成功」**永远不等于**「外部侧已登记」——
 * 人工登记的行 `providerNotified=false`，页面上必须把这句写出来。
 */
export function providerNoticeText(record: ExternalSyncRecordRow): string {
  if (record.providerNotified) return '已与外部系统核对过';
  return record.providerNotice || '本系统未与外部系统核对过';
}

/** 一条记录是不是「本地台账里有、外部侧没登记过」。 */
export function unnotified(record: ExternalSyncRecordRow): boolean {
  return !record.providerNotified;
}

// ================================================================ 表单校验

/** 连接配置表单。 */
export interface ConnectionForm {
  category: string;
  adapterCode: string;
  name: string;
  baseUrl: string;
  credentialRef: string;
  credentialHint: string;
  channelPlatformCode: string;
  channelShopId: string;
  status: string;
  timeoutSeconds: string;
  maxAttempts: string;
  remark: string;
}

/** 连接配置表单校验。返回一句人话，**通过**返回 {@code null}。 */
export function validateConnection(form: ConnectionForm): string | null {
  if (!form.category.trim()) return '请选对接类别';
  if (!form.adapterCode.trim()) return '请选适配器';
  const category = categoryForAdapter(form.adapterCode);
  if (category !== form.category)
    return '适配器与对接类别不相容 —— 货源平台的适配器不能配到海外仓上';
  if (!form.name.trim()) return '请填配置名';
  if (form.name.trim().length > 120) return '配置名不能超过 120 个字符';
  if (form.baseUrl.trim() && !/^https?:\/\//i.test(form.baseUrl.trim()))
    return '接口地址必须是 http:// 或 https:// 开头的完整地址';
  const hasRef = form.credentialRef.trim().length > 0;
  const hasHint = form.credentialHint.trim().length > 0;
  if (hasRef !== hasHint)
    return '密钥引用与末位提示必须同时填或同时不填 —— 三者缺一个就说不清配的是哪条';
  if (form.credentialHint.trim().length > 16) return '密钥末位提示不能超过 16 个字符';
  const timeout = Number(form.timeoutSeconds);
  if (!Number.isInteger(timeout) || timeout < 1 || timeout > 120)
    return '超时秒数只能在 1 到 120 之间';
  const attempts = Number(form.maxAttempts);
  if (!Number.isInteger(attempts) || attempts < 1 || attempts > 10)
    return '最大重试次数只能在 1 到 10 之间';
  return null;
}

/** 同步任务表单。 */
export interface SyncTaskForm {
  connectionId: string;
  taskType: string;
  direction: string;
  name: string;
  resourceRef: string;
  scheduleKind: string;
  scheduleExpression: string;
  intervalSeconds: string;
  enabled: boolean;
  remark: string;
}

/** 同步任务表单校验。返回一句人话，**通过**返回 {@code null}。 */
export function validateSyncTask(form: SyncTaskForm): string | null {
  if (!form.connectionId.trim()) return '请先选一个对接连接';
  if (!form.taskType.trim()) return '请选同步类型';
  if (!form.name.trim()) return '请填任务名';
  if (form.name.trim().length > 120) return '任务名不能超过 120 个字符';
  if (form.scheduleKind === 'INTERVAL') {
    const interval = Number(form.intervalSeconds);
    if (!Number.isInteger(interval) || interval < 1 || interval > 86400)
      return '调度方式选了固定间隔就必须给间隔秒数（1 到 86400 之间）';
  }
  if (form.scheduleKind === 'CRON' && !form.scheduleExpression.trim())
    return '调度方式选了 cron 就必须给调度表达式';
  return null;
}

/** 人工登记表单。 */
export interface ExternalRecordForm {
  taskId: string;
  status: string;
  recordsTotal: string;
  recordsSucceeded: string;
  recordsFailed: string;
  externalRef: string;
  failureReason: string;
  failureClass: string;
  startedAt: string;
  finishedAt: string;
}

/** 人工登记表单校验。返回一句人话，**通过**返回 {@code null}。 */
export function validateExternalRecord(form: ExternalRecordForm): string | null {
  if (!form.taskId.trim()) return '请先选一个同步任务';
  if (!form.status.trim()) return '请选外部侧的结论';
  const total = Number(form.recordsTotal || '0');
  const succeeded = Number(form.recordsSucceeded || '0');
  const failed = Number(form.recordsFailed || '0');
  if (!Number.isFinite(total) || total < 0) return '处理条数不能为负';
  if (!Number.isFinite(succeeded) || succeeded < 0) return '成功条数不能为负';
  if (!Number.isFinite(failed) || failed < 0) return '失败条数不能为负';
  if (succeeded > total) return '成功条数不能超过处理条数';
  if (failed > total) return '失败条数不能超过处理条数';
  if (form.status === 'FAILED' && !form.failureReason.trim())
    return '结论是失败时请写清失败原因 —— 没有原因的失败没法复现';
  if (form.startedAt && form.finishedAt) {
    const start = new Date(form.startedAt).getTime();
    const finish = new Date(form.finishedAt).getTime();
    if (Number.isFinite(start) && Number.isFinite(finish) && finish < start)
      return '结束时间不能早于开始时间';
  }
  return null;
}

/** 空态文案：区分「还没登记过」与「筛选无结果」。 */
export function emptyHint(kind: 'connections' | 'tasks' | 'records', filtered: boolean): string {
  if (filtered) {
    return kind === 'connections'
      ? '没有符合条件的对接连接 —— 换个筛选条件试试。'
      : kind === 'tasks'
        ? '没有符合条件的同步任务 —— 换个筛选条件试试。'
        : '没有符合条件的同步记录 —— 换个筛选条件试试。';
  }
  return kind === 'connections'
    ? '还没有登记任何对接连接 —— 外部系统要在这一格登记后才管得住。'
    : kind === 'tasks'
      ? '还没有登记任何同步任务 —— 登记任务后才能跑同步。'
      : '还没有任何同步记录 —— 跑一次同步，或登记一条「外部侧已完成」的记录。';
}
