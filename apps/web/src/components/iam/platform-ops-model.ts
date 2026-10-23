/**
 * 17.7.2.2 平台运维视图的展示口径与前端预校验。
 *
 * <p>后端 {@code PlatformOpsController} 是最终口径（跨租户读只走 SECURITY DEFINER 函数，
 * 判据全在服务端），这里的函数只做**展示**与**提交前的预校验**：同一套口径写在前端，
 * 是为了让运营在点保存之前就看到「为什么不行」，而不是提交后吃一个 400。
 * 后端仍然是权威 —— 即使前端校验放过，后端也会拒。
 *
 * <p>🔴 本模块最要紧的一条纪律是<b>「没数据」与「指标是 0」必须分开说</b>。
 * 后端在没有采样时返回 {@code null} 而不是 0（把「没数据」说成「指标是 0」是把缺口
 * 说成了事实），所以这里所有的格式化函数都把 {@code null} 显示成「—」，
 * {@link healthLevel} 也把「不知道」单列成 {@code UNKNOWN}，不并进「正常」。
 */

/** 指标码 → 人话。全部是**技术指标**，没有任何业务对象。 */
export const METRIC_LABELS: Record<string, string> = {
  ONLINE_STATUS: '在线状态',
  ERROR_RATE: '请求错误率',
  ERROR_COUNT: '错误请求数',
  REQUEST_COUNT: '请求数',
  REQUEST_LATENCY_MS: '响应延迟',
  AVAILABILITY: '可用性',
  TASK_BACKLOG: '任务积压',
  QUEUE_DEPTH: '队列深度',
  CPU_UTILIZATION: 'CPU 占用',
  MEMORY_UTILIZATION: '内存占用',
  STORAGE_UTILIZATION: '存储占用',
  STORAGE_USED_BYTES: '存储用量',
  API_CALL_COUNT: 'API 调用量',
  AI_CALL_COUNT: 'AI 调用量',
};

/** 告警码 → 人话。 */
export const ALERT_LABELS: Record<string, string> = {
  ERROR_RATE_HIGH: '错误率超阈值',
  TASK_BACKLOG_HIGH: '任务积压超阈值',
  QUEUE_DEPTH_HIGH: '队列深度超阈值',
  CPU_PRESSURE: 'CPU 占用超阈值',
  MEMORY_PRESSURE: '内存占用超阈值',
  STORAGE_PRESSURE: '存储占用超阈值',
  LATENCY_HIGH: '响应延迟超阈值',
  AVAILABILITY_LOW: '可用性低于阈值',
  TENANT_OFFLINE: '租户离线',
};

/** 严重级别。 */
export const SEVERITY_LABELS: Record<string, string> = {
  OK: '正常',
  WARNING: '警告',
  CRITICAL: '严重',
};

/** 告警状态。 */
export const ALERT_STATUS_LABELS: Record<string, string> = {
  OPEN: '待处理',
  ACKNOWLEDGED: '已确认',
  RESOLVED: '已闭环',
};

/** 量纲。 */
export const UNIT_LABELS: Record<string, string> = {
  RATIO: '比率',
  COUNT: '次',
  MILLISECOND: '毫秒',
  BYTE: '字节',
  STATE: '状态',
};

/** 采集方服务。 */
export const SERVICE_LABELS: Record<string, string> = {
  identity: '身份与权限',
  catalog: '商品主数据',
  channel: '渠道与店铺',
  trade: '订单履约',
  warehouse: '库存仓储',
  finance: '财务',
  web: '前端',
};

/** 看板一行的最小形状。 */
export type HealthLike = {
  tenantId: string;
  tenantCode: string;
  tenantName: string;
  tenantStatus: string;
  online: boolean | null;
  errorRate: number | null;
  taskBacklog: number | null;
  queueDepth: number | null;
  storageUtilization: number | null;
  availability: number | null;
  latencyMs: number | null;
  costEstimate: number | null;
  openAlertCount: number;
  criticalAlertCount: number;
  lastSampleAt: string | null;
};

/** 告警一行的最小形状。 */
export type AlertLike = {
  id: string;
  tenantId: string;
  alertCode: string;
  metricCode: string;
  serviceCode: string;
  severity: string;
  observedValue: number;
  thresholdValue: number;
  status: string;
  raisedAt: string;
  acknowledgedAt: string | null;
  resolvedAt: string | null;
};

/** 指标一行的最小形状。 */
export type MetricLike = {
  id: string;
  tenantId: string;
  serviceCode: string;
  metricCode: string;
  sampleValue: number;
  unit: string;
  severity: string;
  windowStart: string;
  windowEnd: string;
  source: string;
  createdAt: string;
};

/** 把后端的数字（可能是 number 也可能是字符串）安全转成 number；转不了返回 null。 */
function toNumber(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}

/**
 * 数量格式化。**没有值就是「—」**，不编造 0。
 *
 * <p>整数不带小数点（任务积压 12 而不是 12.000000），带小数的保留两位。
 */
export function formatQuantity(value: number | string | null | undefined): string {
  const n = toNumber(value);
  if (n === null) return '—';
  if (Number.isInteger(n)) return String(n);
  return n.toFixed(2);
}

/** 比率格式化：0.0123 → 「1.23%」。没有值是「—」。 */
export function formatRatio(value: number | string | null | undefined): string {
  const n = toNumber(value);
  if (n === null) return '—';
  return `${(n * 100).toFixed(2)}%`;
}

/** 毫秒格式化：1250 → 「1250 ms」。没有值是「—」。 */
export function formatMilliseconds(value: number | string | null | undefined): string {
  const n = toNumber(value);
  if (n === null) return '—';
  return `${formatQuantity(n)} ms`;
}

/** 按量纲选择格式化口径。 */
export function formatByUnit(
  value: number | string | null | undefined,
  unit: string | null | undefined,
): string {
  if (unit === 'RATIO') return formatRatio(value);
  if (unit === 'MILLISECOND') return formatMilliseconds(value);
  if (unit === 'STATE') {
    const n = toNumber(value);
    if (n === null) return '—';
    return n >= 1 ? '在线' : '离线';
  }
  return formatQuantity(value);
}

/** 看板一行的健康档位。`UNKNOWN` = 没有采样，不知道 —— 不并进「正常」。 */
export function healthLevel(row: HealthLike): 'OK' | 'WARNING' | 'CRITICAL' | 'UNKNOWN' {
  if (row.criticalAlertCount > 0) return 'CRITICAL';
  if (row.openAlertCount > 0) return 'WARNING';
  if (row.online === null && row.lastSampleAt === null) return 'UNKNOWN';
  if (row.online === false) return 'CRITICAL';
  if (row.errorRate !== null && row.errorRate >= 0.05) return 'CRITICAL';
  if (row.errorRate !== null && row.errorRate >= 0.01) return 'WARNING';
  if (row.availability !== null && row.availability < 0.99) return 'WARNING';
  return 'OK';
}

/** 健康档位的人话。 */
export const HEALTH_LEVEL_LABELS: Record<string, string> = {
  OK: '正常',
  WARNING: '有告警',
  CRITICAL: '严重',
  UNKNOWN: '无数据',
};

/**
 * 看板一行的可读摘要：一句话把「在线状态 / 错误率 / 积压 / 容量」串起来。
 *
 * <p>🔴 没有采样的项显示「—」而不是 0：把「没采到」写成「积压 0」会把
 * 「不知道」说成「一切正常」。
 */
export function describeHealth(row: HealthLike): string {
  const online =
    row.online === null ? '在线状态未知' : row.online ? '在线' : '离线';
  const parts = [
    online,
    `错误率 ${formatRatio(row.errorRate)}`,
    `积压 ${formatQuantity(row.taskBacklog)}`,
    `队列 ${formatQuantity(row.queueDepth)}`,
    `存储 ${formatRatio(row.storageUtilization)}`,
  ];
  if (row.criticalAlertCount > 0) parts.push(`${row.criticalAlertCount} 条严重告警`);
  else if (row.openAlertCount > 0) parts.push(`${row.openAlertCount} 条未闭环告警`);
  return parts.join(' · ');
}

/** 指标一行的可读摘要。 */
export function describeMetric(row: MetricLike): string {
  const metric = METRIC_LABELS[row.metricCode] ?? row.metricCode;
  const service = SERVICE_LABELS[row.serviceCode] ?? row.serviceCode;
  return `${metric} · ${service} · ${formatByUnit(row.sampleValue, row.unit)}`;
}

/** 告警一行的可读摘要。 */
export function describeAlert(row: AlertLike): string {
  const alert = ALERT_LABELS[row.alertCode] ?? row.alertCode;
  const service = SERVICE_LABELS[row.serviceCode] ?? row.serviceCode;
  return `${alert} · ${service} · 观测 ${formatQuantity(row.observedValue)} / 阈值 ${formatQuantity(
    row.thresholdValue,
  )}`;
}

/** 告警现在还能做什么动作。已闭环的告警没有后续动作。 */
export function alertAction(row: AlertLike): 'acknowledge' | 'resolve' | null {
  if (row.status === 'RESOLVED') return null;
  if (row.status === 'OPEN') return 'acknowledge';
  return 'resolve';
}

/** 告警动作的人话。 */
export const ALERT_ACTION_LABELS: Record<string, string> = {
  acknowledge: '确认',
  resolve: '闭环',
};

/** 概览卡片。空值显示「—」，绝不补 0。 */
export function overviewCards(overview: {
  tenantCount: number;
  onlineTenantCount: number;
  offlineTenantCount: number;
  unknownTenantCount: number;
  avgErrorRate: number | null;
  totalTaskBacklog: number | null;
  totalCostEstimate: number | null;
  openAlertCount: number;
  criticalAlertCount: number;
  tenantWithSamples: number;
}): { label: string; value: string; hint: string }[] {
  return [
    {
      label: '租户在线',
      value: `${overview.onlineTenantCount} / ${overview.tenantCount}`,
      hint: `离线 ${overview.offlineTenantCount} · 状态未知 ${overview.unknownTenantCount}`,
    },
    {
      label: '平均错误率',
      value: formatRatio(overview.avgErrorRate),
      hint: `有采样的租户 ${overview.tenantWithSamples} 个`,
    },
    {
      label: '任务积压',
      value: formatQuantity(overview.totalTaskBacklog),
      hint: '全平台累计（只有上报的才计入）',
    },
    {
      label: '成本估算',
      value: formatQuantity(overview.totalCostEstimate),
      hint: '容量 / 调用量口径的估算单位数量，不是金额',
    },
    {
      label: '未闭环告警',
      value: String(overview.openAlertCount),
      hint: `其中严重 ${overview.criticalAlertCount} 条`,
    },
  ];
}

/** 阈值草稿。 */
export type ThresholdDraft = {
  warnThreshold: string;
  criticalThreshold: string;
  comparison: string;
  enabled: boolean;
};

/**
 * 阈值写入前的预校验，与后端 {@code PlatformOpsController.updateThreshold} 同口径同顺序。
 *
 * <p>返回可读原因；通过校验时返回空串。
 */
export function validateThresholdInput(draft: ThresholdDraft): string {
  const warn = Number(draft.warnThreshold);
  const critical = Number(draft.criticalThreshold);
  if (draft.warnThreshold.trim() === '') return '告警阈值不能为空';
  if (draft.criticalThreshold.trim() === '') return '严重阈值不能为空';
  if (!Number.isFinite(warn)) return '告警阈值需要是一个数值';
  if (!Number.isFinite(critical)) return '严重阈值需要是一个数值';
  if (warn < 0 || critical < 0) return '阈值不能是负数';
  if (draft.comparison === 'GT') {
    if (warn >= critical) return 'GT 方向（越大越糟）下告警阈值必须小于严重阈值';
  } else if (draft.comparison === 'LT') {
    if (critical >= warn) return 'LT 方向（越小越糟）下严重阈值必须小于告警阈值';
  } else {
    return '比较方向只能是 GT 或 LT';
  }
  return '';
}

/** 阈值草稿的初值（从后端一行填出来）。 */
export function toThresholdDraft(row: {
  warnThreshold: number;
  criticalThreshold: number;
  comparison: string;
  enabled: boolean;
}): ThresholdDraft {
  return {
    warnThreshold: String(row.warnThreshold),
    criticalThreshold: String(row.criticalThreshold),
    comparison: row.comparison,
    enabled: row.enabled,
  };
}

/** 采集时间的人话（本地时刻）。 */
export function formatTimestamp(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

/** 告警按「未闭环优先、再按严重度、再按时间」排序，顺序固定不随返回顺序漂移。 */
export function sortAlerts(rows: readonly AlertLike[]): AlertLike[] {
  const rank = (row: AlertLike) => (row.status === 'RESOLVED' ? 2 : row.status === 'ACKNOWLEDGED' ? 1 : 0);
  return [...rows].sort((a, b) => {
    const byStatus = rank(a) - rank(b);
    if (byStatus !== 0) return byStatus;
    const bySeverity = (a.severity === 'CRITICAL' ? 0 : 1) - (b.severity === 'CRITICAL' ? 0 : 1);
    if (bySeverity !== 0) return bySeverity;
    return b.raisedAt.localeCompare(a.raisedAt);
  });
}
