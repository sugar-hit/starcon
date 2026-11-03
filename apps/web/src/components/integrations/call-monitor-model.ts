import type {
  ApiCallMonitorAlertPage,
  ApiCallMonitorAlertRow,
  ApiCallMonitorAppRow,
  ApiCallMonitorCallPage,
  ApiCallMonitorCapabilities,
  ApiCallMonitorEndpointRow,
  ApiCallMonitorOptions,
  ApiCallMonitorOverview,
  ApiCallMonitorScanResult,
  ApiCallMonitorSlowCall,
  ApiCallMonitorTrend,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.1.1.3「调用监控」前端模型。
 *
 * 蓝图：「<strong>职责</strong>：监控 API 调用：QPS、错误率、限流命中、慢调用、按应用与接口维度分析，异常告警。
 * <strong>输入</strong>：调用埋点；配额配置。<strong>输出</strong>：调用统计 + 告警。
 * <strong>关系</strong>：上游 = API 网关；下游 = 1 工作台告警、18.1.1.1 配额调整。
 * <strong>外部系统调用错误要能被快速定位</strong>，否则对接方只会说"你们接口有问题"。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「限流命中」与「错误」不能合成一个数**。429 既是错误也是限流命中，
 *    但「有多少次调用被配额挡下来」与「有多少个 4xx」是两个问题，页面上并排显示。
 * 2. **阈值口径必须显示出来**。告警是平台固定口径判的，运营看到告警要知道是按什么判的
 *    （多少样本起判、错误率多少算高、p95 多少算慢）。
 * 3. **「不可用」要显式摆出来**。卷 01 的工作台告警至今未交付，页面必须写明这一格不可用、
 *    归谁 —— 把它混进空态等于告诉运营「告警已经推给工作台了」。
 * 4. **读不到 ≠ 没有**。某个端点的活契约读不到时，页面要显示「契约读不到」并写明原因，
 *    而不是把它从清单里悄悄删掉。
 * 5. **加载 / 空 / 错误三态齐全**：空态要区分「这个窗口没有调用」与「筛选条件不对」。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），前端不手抄字符串联合。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/call-monitor';

/**
 * 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。
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
export function fetchCapabilities(): Promise<ApiCallMonitorCapabilities> {
  return api<ApiCallMonitorCapabilities>(`${base}/capabilities`);
}

/** 筛选项 + 阈值口径 + 网关端点清单（接口维度的词表）。 */
export function fetchOptions(): Promise<ApiCallMonitorOptions> {
  return api<ApiCallMonitorOptions>(`${base}/options`);
}

/** 概览：几个数。 */
export function fetchOverview(windowSeconds: number): Promise<ApiCallMonitorOverview> {
  return api<ApiCallMonitorOverview>(`${base}/overview?${query({ windowSeconds })}`);
}

/** 趋势（按分钟或按小时分桶）。 */
export function fetchTrend(
  windowSeconds: number,
  bucket: 'MINUTE' | 'HOUR',
): Promise<ApiCallMonitorTrend> {
  return api<ApiCallMonitorTrend>(`${base}/trend?${query({ windowSeconds, bucket })}`);
}

/** 按应用维度分析。 */
export function fetchApps(windowSeconds: number): Promise<ApiCallMonitorAppRow[]> {
  return api<ApiCallMonitorAppRow[]>(`${base}/apps?${query({ windowSeconds })}`);
}

/** 按接口维度分析。 */
export function fetchEndpoints(windowSeconds: number): Promise<ApiCallMonitorEndpointRow[]> {
  return api<ApiCallMonitorEndpointRow[]>(`${base}/endpoints?${query({ windowSeconds })}`);
}

/** 慢调用 Top N。 */
export function fetchSlowCalls(
  windowSeconds: number,
  limit: number,
): Promise<ApiCallMonitorSlowCall[]> {
  return api<ApiCallMonitorSlowCall[]>(`${base}/slow-calls?${query({ windowSeconds, limit })}`);
}

/** 调用明细（真分页 + 真筛选）。 */
export function fetchCalls(params: {
  windowSeconds?: number | undefined;
  appId?: string | undefined;
  endpointCode?: string | undefined;
  outcome?: string | undefined;
  rateLimited?: boolean | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<ApiCallMonitorCallPage> {
  return api<ApiCallMonitorCallPage>(`${base}/calls?${query(params)}`);
}

/** 告警列表（真分页 + 真筛选）。 */
export function fetchAlerts(params: {
  status?: string | undefined;
  alertType?: string | undefined;
  appId?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<ApiCallMonitorAlertPage> {
  return api<ApiCallMonitorAlertPage>(`${base}/alerts?${query(params)}`);
}

// ================================================================ 动作

/** 扫一遍并开告警。 */
export function runScan(windowSeconds: number): Promise<ApiCallMonitorScanResult> {
  return api<ApiCallMonitorScanResult>(`${base}/alerts/scan`, 'POST', { windowSeconds });
}

/** 确认一条告警。 */
export function acknowledgeAlert(alertId: string): Promise<ApiCallMonitorAlertRow> {
  return api<ApiCallMonitorAlertRow>(`${base}/alerts/${alertId}/acknowledge`, 'POST');
}

/** 闭环一条告警。 */
export function resolveAlert(alertId: string): Promise<ApiCallMonitorAlertRow> {
  return api<ApiCallMonitorAlertRow>(`${base}/alerts/${alertId}/resolve`, 'POST');
}

// ================================================================ 词表

/** 语气 → Badge 的 tone。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/** 一次调用的结论码的显示口径。 */
export function outcomeLabel(outcome: string): string {
  switch (outcome) {
    case 'SUCCESS':
      return '成功';
    case 'AUTH_FAILED':
      return '凭证未通过';
    case 'FORBIDDEN':
      return '被拒绝';
    case 'RATE_LIMITED':
      return '被限流';
    case 'NOT_FOUND':
      return '接口不存在';
    case 'ERROR':
      return '服务端错误';
    default:
      return outcome || '—';
  }
}

/**
 * 结论码的语气。
 *
 * 🔴 「被限流」与「凭证未通过」是**两种黄**：前者是流量问题，后者是对接方自己的凭证问题 ——
 * 混成同一种黄，排查的人就得自己去猜是哪一类。
 */
export function outcomeTone(outcome: string): Tone {
  switch (outcome) {
    case 'SUCCESS':
      return 'ok';
    case 'AUTH_FAILED':
      return 'danger';
    case 'FORBIDDEN':
      return 'danger';
    case 'RATE_LIMITED':
      return 'warn';
    case 'NOT_FOUND':
      return 'muted';
    case 'ERROR':
      return 'danger';
    default:
      return 'muted';
  }
}

/** 告警类型的显示口径。 */
export function alertTypeLabel(alertType: string): string {
  switch (alertType) {
    case 'ERROR_RATE_HIGH':
      return '错误率越线';
    case 'SLOW_CALL':
      return '慢调用越线';
    case 'RATE_LIMIT_SPIKE':
      return '限流命中越线';
    default:
      return alertType || '—';
  }
}

/** 告警处置状态的显示口径。 */
export function alertStatusLabel(status: string): string {
  switch (status) {
    case 'OPEN':
      return '待处理';
    case 'ACKNOWLEDGED':
      return '已确认';
    case 'RESOLVED':
      return '已闭环';
    default:
      return status || '—';
  }
}

/** 告警状态的语气。 */
export function alertStatusTone(status: string): Tone {
  switch (status) {
    case 'OPEN':
      return 'danger';
    case 'ACKNOWLEDGED':
      return 'warn';
    case 'RESOLVED':
      return 'ok';
    default:
      return 'muted';
  }
}

/** 告警级别的语气。 */
export function severityTone(severity: string): Tone {
  return severity === 'CRITICAL' ? 'danger' : 'warn';
}

/** 错误码的显示口径（网关埋点里的细分失败原因）。 */
export function errorCodeLabel(errorCode: string): string {
  switch (errorCode) {
    case '':
      return '—';
    case 'SECRET_MISMATCH':
      return 'Secret 不匹配';
    case 'CREDENTIAL_ROTATED':
      return '凭证已被取代';
    case 'CREDENTIAL_REVOKED':
      return '凭证已被吊销';
    case 'APP_SUSPENDED':
      return '应用已暂停';
    case 'APP_REVOKED':
      return '应用已吊销';
    case 'SCOPE_DENIED':
      return '授权范围不足';
    case 'IP_NOT_ALLOWED':
      return '来源 IP 不在白名单';
    case 'QUOTA_EXCEEDED':
      return '超出配额';
    case 'NOT_FOUND':
      return '没有这条记录';
    case 'INTERNAL_ERROR':
      return '网关内部错误';
    default:
      return errorCode;
  }
}

// ================================================================ 展示口径

/** 比率 → 百分比文案。 */
export function formatRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '—';
  return `${(rate * 100).toFixed(2)}%`;
}

/** 毫秒 → 可读文案。 */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—';
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)} s`;
  return `${ms.toFixed(1)} ms`;
}

/** 秒 → 窗口文案。 */
export function formatWindow(seconds: number | null | undefined): string {
  switch (seconds) {
    case 300:
      return '近 5 分钟';
    case 900:
      return '近 15 分钟';
    case 3600:
      return '近 1 小时';
    case 86400:
      return '近 24 小时';
    default:
      return seconds ? `近 ${seconds} 秒` : '—';
  }
}

/**
 * 限流告警的「去哪儿调」文案。
 *
 * 🔴 这条必须显示出来 —— 告警指向 18.1.1.1 的配额调整，不显示等于让运营自己找。
 */
export function quotaTargetHint(alert: ApiCallMonitorAlertRow): string {
  if (!alert.quotaAdjustTarget) return '';
  const window = alert.suggestedQuotaWindow ? `（建议 ${alert.suggestedQuotaWindow} 窗口）` : '';
  const limit =
    alert.suggestedQuotaLimit === null || alert.suggestedQuotaLimit === undefined
      ? ''
      : `，建议上限 ${alert.suggestedQuotaLimit}`;
  return `到 18.1.1.1 的配额设置里调：${alert.quotaAdjustTarget}${window}${limit}`;
}

/**
 * 能力声明里**不可用**的那几格。
 *
 * 🔴 这些必须显式摆出来 —— 混进空态等于告诉运营「工作台已经收到告警了」。
 */
export function unavailableCapabilities(
  capabilities: ApiCallMonitorCapabilities | null | undefined,
): ApiCallMonitorCapabilities['capabilities'] {
  return (capabilities?.capabilities ?? []).filter((c) => !c.available);
}

/** 阈值口径的文案（名 + 值 + 规则）。 */
export function thresholdText(options: ApiCallMonitorOptions | null | undefined): string[] {
  return (options?.thresholds ?? []).map((t) => `${t.label}：${t.value} ${t.unit} —— ${t.rule}`);
}
