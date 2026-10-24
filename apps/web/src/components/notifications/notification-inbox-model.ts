/**
 * 1.3.1.1 通知列表的前端聚合模型。
 *
 * <p>蓝图原文：「系统级与业务级通知的统一收件箱，区分严重级别，支持已读/未读、批量处理、
 * 按类型订阅。……上游 = 各系统事件（06 缺货、09 超时、10 异常、12 对账差异、15 站内信）；
 * 下游 = 对应处理页。**通知是投影，允许丢弃后重算**，不作为业务事实来源。」
 *
 * <p>🔴 **本文件是纯函数层**：不碰网络、不碰 React、不重算业务口径。
 * 它做四件事：
 *
 * <ol>
 *   <li><b>归一</b>：把五个系统**形状完全不同**的投影行折成同一条通知 ——
 *       上游各给各的字段名与语义（trade 给 {@code overdueKind}，warehouse 给
 *       {@code severity}，crm 给 {@code priority}），折算是**语义对齐**，不是改字段名；</li>
 *   <li><b>定级</b>：每类通知的严重度**从上游自己的字段推**，
 *       推不出来的**单列成「判不了」**，不拿一个写死的常量冒充；</li>
 *   <li><b>排序</b>：按时间倒序（新的在前），同一时刻按严重度排，
 *       再按类型名与引用稳定排，保证同一批数据两次刷新顺序不跳；</li>
 *   <li><b>导航</b>：关联单据的跳转入口只给**归属系统的处理页**（前端路由 + 定位参数），
 *       通知本身**不携带**处置动作。</li>
 * </ol>
 *
 * <p>⚠️ **「判不了」与「没有」是两件事**：
 * 一条通知推不出严重度（上游没给判定依据）时，它是**判不了**，不是「低优先级」。
 * 本模型把它单列成 {@link Severity.UNKNOWN}，页面上如实显示「判不了」，
 * 绝不用 LOW 兜底 —— 用 LOW 兜底会让「判不了」的事项被排到最后，然后永远没人处理。
 *
 * <p>🔴 **通知键（{@code <类型>:<上游主键>}）是本模块与 identity 之间唯一的接口**：
 * 它同时是「已读状态」的键与「订阅过滤」的粒度。键的形状与类型词表由**服务端**钉死
 * （identity 的 {@code NotificationInboxRules} + 数据库 CHECK 约束），前端只负责按同样的
 * 形状**生成**它，不负责**判定**它合法 —— 判定了也不算数，服务端会再判一次。
 */

// 相对路径而不是 `@/lib/api`：根 vitest 配置没有 `@/*` 别名（那只在 Next 构建里解析），
// 用别名会让这个模型的单测在 vite 下加载失败。与 `my-todo-model` 保持同一套约定。
import type {
  CrmMessageRow,
  FinanceSupplierReconciliationRow,
  StockoutAlertRow,
  SyncFailureView,
  TimelinessOverdueRow,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 五种通知类型（顺序 = 蓝图里「上游 =」的顺序）。
 *
 * <p>🔴 **权威词表在服务端**（identity 的 {@code NotificationInboxCapabilities} + 数据库
 * CHECK 约束）。这里这份只是「前端要把哪几类投影折成通知」的清单，
 * 与服务端词表**逐字一致**；页面加载时会用服务端下发的词表校对，不一致就以服务端为准。
 */
export const NOTIFICATION_TYPES = [
  'STOCKOUT',
  'TIMELINESS_OVERDUE',
  'SYNC_FAILURE',
  'RECONCILIATION_DIFF',
  'MESSAGE',
] as const;

export type NotificationType = (typeof NOTIFICATION_TYPES)[number];

/** 类型 → 中文标签。 */
export const NOTIFICATION_TYPE_LABELS: Record<NotificationType, string> = {
  STOCKOUT: '库存缺货',
  TIMELINESS_OVERDUE: '履约超时',
  SYNC_FAILURE: '同步失败',
  RECONCILIATION_DIFF: '对账差异',
  MESSAGE: '站内信',
};

/**
 * 每类通知的**归属系统**（= 谁拥有那张事实表）。
 *
 * <p>蓝图写死「上游 = 各系统事件（06 缺货、09 超时、10 异常、12 对账差异、15 站内信）」，
 * 这里逐条对上，让页面上每条通知都能说清「这条归谁管、去哪儿处理」。
 */
export const NOTIFICATION_TYPE_OWNER: Record<NotificationType, string> = {
  STOCKOUT: '07 库存',
  TIMELINESS_OVERDUE: '09 订单',
  SYNC_FAILURE: '02 渠道',
  RECONCILIATION_DIFF: '12 财务',
  MESSAGE: '15 客户',
};

/**
 * 严重级别（**受控词表**，不是自由文本）。
 *
 * <p>{@link UNKNOWN} 是「**判不了**」—— 上游没给判定依据，
 * 既不是低优先级也不是没有严重度，必须与 {@link LOW} 分开。
 */
export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'UNKNOWN';

export const SEVERITY_LABELS: Record<Severity, string> = {
  CRITICAL: '严重',
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
  UNKNOWN: '判不了',
};

/** 严重度排序权重（越大越紧急）。{@link UNKNOWN} 排在 {@link LOW} **之前**。 */
const SEVERITY_RANK: Record<Severity, number> = {
  CRITICAL: 4,
  HIGH: 3,
  MEDIUM: 2,
  UNKNOWN: 1,
  LOW: 0,
};

/** 严重度筛选的下拉选项（把「判不了」也列进去 —— 它是真实存在的一档）。 */
export const SEVERITY_FILTER_OPTIONS: readonly Severity[] = [
  'CRITICAL',
  'HIGH',
  'MEDIUM',
  'LOW',
  'UNKNOWN',
];

/** 一条通知。 */
export interface NotificationItem {
  /** 通知键（`<类型>:<上游主键>`）—— 与 identity 的已读状态一一对应。 */
  key: string;
  type: NotificationType;
  /** 单据引用（SKU / 订单号 / 对账单号 / 消息号 …）。 */
  reference: string;
  /** 单据引用的补充说明（归属系统给的中文名等）。 */
  referenceLabel: string;
  /** 严重度；{@link Severity.UNKNOWN} = 判不了。 */
  severity: Severity;
  /** 事件发生时刻（ISO）；`null` = **判不了**（上游没给时刻），不是「刚刚」。 */
  occurredAt: string | null;
  /** 关联单据的跳转入口（前端路由 + 定位参数）。 */
  href: string;
  /** 跳转入口的文案。 */
  actionLabel: string;
  /** 附加说明（上游给的读数，如缺口量 / 失败原因）。 */
  detail: string;
  /** 数据范围维度标签（法人 / 店铺 / 仓库 / 部门），用于说明这条通知落在哪个范围里。 */
  scopeLabel: string;
  /** 我读过没有（个人状态，来自 identity）。 */
  read: boolean;
}

/** 一个数据源读回来的原始行 + 该源是否可读。 */
export interface SourceResult<T> {
  /** 上游返回的行。 */
  rows: T[];
  /** 上游报的总数（未读到的部分由调用方如实提示）。 */
  total: number;
  /** 读失败时的原因；空串 = 读到了。 */
  error: string;
}

// ---------------------------------------------------------------- 通知键

/**
 * 造一个通知键：`<类型>:<上游主键>`。
 *
 * <p>与 identity 的 {@code NotificationInboxRules.parseKey} 逐字同式：
 * 类型段取自受控词表，主键段允许字母 / 数字 / 点 / 下划线 / 连字符 / 冒号
 * （冒号留给「一条上游事实拆成多条通知」的后缀，如超时的「发货超期 / 承诺未达」）。
 */
export function notificationKey(type: NotificationType, ref: string): string {
  return `${type}:${ref}`;
}

// ---------------------------------------------------------------- 严重度折算

/**
 * 缺货预警的严重度：上游**直接给了**严重度，原样用（不重算）。
 *
 * <p>⚠️ warehouse 的严重度词表是 `CRITICAL` / `WARNING` / `INFO` ——
 * `WARNING` 不是本模型的 `HIGH`，但它落在「高」这一档，所以这里做一次**词表对齐**，
 * 而不是把 `WARNING` 原样塞进 {@link Severity}（那会让排序把它排到 `HIGH` 之下）。
 */
export function upstreamSeverity(value: string | null | undefined): Severity {
  switch (value) {
    case 'CRITICAL':
      return 'CRITICAL';
    case 'WARNING':
      return 'HIGH';
    case 'INFO':
      return 'LOW';
    default:
      return 'UNKNOWN';
  }
}

/**
 * 同步失败的严重度：按**失败原因**分档。
 *
 * <p>授权过期与限流是「换个授权 / 等一会就好」，字段解析失败与映射缺失是
 * 「要人去改规则」—— 后者更紧急，前者可重试。
 */
export function syncFailureSeverity(reason: string | null | undefined): Severity {
  switch (reason) {
    case 'FIELD_PARSE_FAILED':
    case 'MAPPING_MISSING':
      return 'HIGH';
    case 'AUTHORIZATION_EXPIRED':
    case 'RATE_LIMITED':
    case 'NETWORK_ERROR':
    case 'PLATFORM_ERROR':
      return 'MEDIUM';
    default:
      return 'UNKNOWN';
  }
}

/** 站内信的严重度：按**优先级**映射（上游给了四种优先级）。 */
export function messageSeverity(priority: string | null | undefined): Severity {
  switch (priority) {
    case 'URGENT':
      return 'CRITICAL';
    case 'HIGH':
      return 'HIGH';
    case 'NORMAL':
      return 'MEDIUM';
    case 'LOW':
      return 'LOW';
    default:
      return 'UNKNOWN';
  }
}

/**
 * 对账差异的严重度：按**未结差异条数**分档。
 *
 * <p>0 条未结差异 ⇒ 这一行**不是**差异通知（页面上不出现）；
 * 有未结差异才是一条通知；条数越多越紧急。
 */
export function reconciliationSeverity(openDifferenceCount: number | null | undefined): Severity {
  if (openDifferenceCount === null || openDifferenceCount === undefined) return 'UNKNOWN';
  if (openDifferenceCount <= 0) return 'LOW';
  if (openDifferenceCount >= 10) return 'HIGH';
  return 'MEDIUM';
}

/**
 * 履约超时的严重度：按**超期时长**分档（上游没给严重度字段）。
 *
 * <p>⚠️ {@code overdueSeconds} 为 `null` 表示「判不了超期多久」，落在
 * {@link Severity.UNKNOWN}，不拿一个写死的阈值冒充上游的判据。
 */
export function overdueSeverity(overdueSeconds: number | null | undefined): Severity {
  if (overdueSeconds === null || overdueSeconds === undefined) return 'UNKNOWN';
  if (!Number.isFinite(overdueSeconds)) return 'UNKNOWN';
  if (overdueSeconds >= 24 * 3600) return 'CRITICAL';
  if (overdueSeconds >= 4 * 3600) return 'HIGH';
  if (overdueSeconds > 0) return 'MEDIUM';
  return 'LOW';
}

// ---------------------------------------------------------------- 各类型的归一

/** 缺货预警 → 通知。 */
export function toStockoutNotification(row: StockoutAlertRow): NotificationItem {
  const known = row.availableQuantity !== null && row.availableQuantity !== undefined;
  return {
    key: notificationKey('STOCKOUT', row.id),
    type: 'STOCKOUT',
    reference: row.skuCode || row.skuId,
    referenceLabel: row.warehouseName || row.warehouseCode || '未标注仓库',
    severity: upstreamSeverity(row.severity),
    occurredAt: row.detectedAt || null,
    href: `/inventory/stockout-alerts?skuId=${encodeURIComponent(row.skuId)}`,
    actionLabel: '去缺货预警页',
    detail: known
      ? `可用 ${row.availableQuantity} · 需求 ${row.demandUnits ?? '未登记'} · 状态 ${row.alertState}`
      : `可用量未登记 · 状态 ${row.alertState}`,
    scopeLabel: row.warehouseCode ? `仓库 ${row.warehouseCode}` : '未标注范围',
    read: false,
  };
}

/** 同步失败 → 通知。 */
export function toSyncFailureNotification(row: SyncFailureView): NotificationItem {
  return {
    key: notificationKey('SYNC_FAILURE', row.id),
    type: 'SYNC_FAILURE',
    reference: row.externalKey,
    referenceLabel: `${row.platformCode} · ${row.resourceType}`,
    severity: syncFailureSeverity(row.reason),
    occurredAt: row.occurredAt || null,
    href: '/channels?keyword=' + encodeURIComponent(row.externalKey),
    actionLabel: '去失败队列',
    detail: `${row.reason} · 已尝试 ${row.attempts} 次 · ${row.lastError || row.detail}`,
    scopeLabel: row.marketplaceCode ? `站点 ${row.marketplaceCode}` : `平台 ${row.platformCode}`,
    read: false,
  };
}

/** 对账差异 → 通知。 */
export function toReconciliationNotification(
  row: FinanceSupplierReconciliationRow,
): NotificationItem {
  return {
    key: notificationKey('RECONCILIATION_DIFF', row.id),
    type: 'RECONCILIATION_DIFF',
    reference: row.reconNo,
    referenceLabel: `${row.supplierName || row.supplierCode} · ${row.statusLabel || row.status}`,
    severity: reconciliationSeverity(row.openDifferenceCount),
    occurredAt: row.updatedAt || row.createdAt || null,
    href: `/finance/supplier-reconciliation?reconNo=${encodeURIComponent(row.reconNo)}`,
    actionLabel: '去对账处理页',
    detail: `未结差异 ${row.openDifferenceCount ?? '未知'} 条 / 共 ${row.differenceCount ?? '未知'} 条`,
    scopeLabel: row.statementCurrency ? `币种 ${row.statementCurrency}` : '未标注范围',
    read: false,
  };
}

/** 站内信 → 通知（只报**收到的**信；发出去的是我们自己的回信，不是通知）。 */
export function toMessageNotification(row: CrmMessageRow): NotificationItem {
  return {
    key: notificationKey('MESSAGE', row.id),
    type: 'MESSAGE',
    reference: row.messageNo,
    referenceLabel: `${row.shopName || '未标注店铺'} · ${row.messageType}`,
    severity: messageSeverity(row.priority),
    occurredAt: row.platformTime || row.createdAt || null,
    href: '/crm/messages?keyword=' + encodeURIComponent(row.messageNo),
    actionLabel: '去站内信',
    detail: `${row.direction === 'INBOUND' ? '买家来信' : '平台来信'} · ${row.subject || '（无主题）'}`,
    scopeLabel: row.shopName ? `店铺 ${row.shopName}` : '未标注范围',
    read: false,
  };
}

/** 履约超时 → 通知（发货超期 / 承诺未达是两个来源，分开成两条）。 */
export function toOverdueNotification(row: TimelinessOverdueRow): NotificationItem {
  const kind = row.overdueKind === 'PROMISE' ? '承诺未达' : '发货超期';
  return {
    key: notificationKey('TIMELINESS_OVERDUE', `${row.factId}:${row.overdueKind}`),
    type: 'TIMELINESS_OVERDUE',
    reference: row.salesOrderNo || row.factNo,
    referenceLabel: `${kind} · ${row.shopRef || '未标注店铺'}`,
    severity: overdueSeverity(row.overdueSeconds),
    occurredAt: row.deadlineAt || null,
    href: `/orders/fulfillment/timeliness?factNo=${encodeURIComponent(row.factNo)}`,
    actionLabel: '去时效处理页',
    detail: `${row.carrierName || row.carrierCode || '未标注承运商'} · 超期 ${formatSpan(row.overdueSeconds)}`,
    scopeLabel: row.shopRef
      ? `店铺 ${row.shopRef}`
      : row.warehouseRef
        ? `仓库 ${row.warehouseRef}`
        : '未标注范围',
    read: false,
  };
}

// ---------------------------------------------------------------- 聚合

/**
 * 五个来源归一后的合并结果。
 *
 * <p>每类通知的**读不到**状态单独记（{@link unreadable}），
 * 页面上渲染成「本次判不了这一类」，而不是把它当成「0 条」。
 */
export interface MergedNotifications {
  items: NotificationItem[];
  /** 读不到的来源 → 原因。 */
  unreadable: { type: NotificationType; reason: string }[];
}

/**
 * 把五类通知合并成一条通知流，并按时间倒序排序。
 *
 * @param sources 五类通知各自的原始行（读失败的那类给 `error` 非空的项）
 */
export function mergeNotifications(sources: {
  stockout: SourceResult<StockoutAlertRow>;
  syncFailure: SourceResult<SyncFailureView>;
  reconciliationDiff: SourceResult<FinanceSupplierReconciliationRow>;
  message: SourceResult<CrmMessageRow>;
  timelinessOverdue: SourceResult<TimelinessOverdueRow>;
}): MergedNotifications {
  const items: NotificationItem[] = [];
  const unreadable: { type: NotificationType; reason: string }[] = [];

  const push = <T>(
    type: NotificationType,
    result: SourceResult<T>,
    map: (row: T) => NotificationItem | null,
  ) => {
    if (result.error) {
      unreadable.push({ type, reason: result.error });
      return;
    }
    for (const row of result.rows) {
      const item = map(row);
      if (item) items.push(item);
    }
  };

  push('STOCKOUT', sources.stockout, (row) => {
    // HEALTHY 不是缺货；RESOLVED 已处置，不再是一条待看的通知。
    if (row.alertState === 'HEALTHY') return null;
    if (row.lifecycleState === 'RESOLVED') return null;
    return toStockoutNotification(row);
  });
  push('TIMELINESS_OVERDUE', sources.timelinessOverdue, (row) => {
    // 没有超期秒数 ⇒ 判不了超期，不是一条超时通知。
    if (row.overdueSeconds === null || row.overdueSeconds === undefined) return null;
    if (row.overdueSeconds <= 0) return null;
    return toOverdueNotification(row);
  });
  push('SYNC_FAILURE', sources.syncFailure, (row) => {
    // 已重放 / 已解决 / 已忽略的失败**不再是**待处理的失败。
    if (row.status !== 'OPEN') return null;
    return toSyncFailureNotification(row);
  });
  push('RECONCILIATION_DIFF', sources.reconciliationDiff, (row) => {
    // 没有未结差异 ⇒ 这张对账单**没有**差异通知，不进收件箱。
    if (!row.openDifferenceCount || row.openDifferenceCount <= 0) return null;
    return toReconciliationNotification(row);
  });
  push('MESSAGE', sources.message, (row) => {
    // 发出去的（OUTBOUND）是我们自己的回信，不是通知。
    if (row.direction === 'OUTBOUND') return null;
    return toMessageNotification(row);
  });

  return { items: sortNotifications(items), unreadable };
}

/**
 * 把「我读过哪些」（identity 的个人状态）贴回通知流。
 *
 * <p>🔴 **已读是个人状态**：读没读过按**通知键**判，不按上游的字段判 ——
 * 上游的 {@code readStatus}（站内信自己的已读标记）是**另一回事**，两者不是同一个概念。
 *
 * @param items 合并后的通知流
 * @param readKeys 我已经读过的通知键
 */
export function applyReadState(
  items: readonly NotificationItem[],
  readKeys: ReadonlySet<string>,
): NotificationItem[] {
  return items.map((item) => ({ ...item, read: readKeys.has(item.key) }));
}

/**
 * 按时间倒序排序。
 *
 * <p>时间新的在前 → 同一时刻按严重度降序 → 类型名 → 引用（稳定）。
 * 最后两级保证**同一批数据两次刷新顺序不跳** —— 顺序乱跳会让人以为通知自己在动。
 *
 * <p>⚠️ 时刻**判不了**（`null`）的排在**有时刻的最后**：
 * 「什么时候发生的不知道」不等于「刚发生」——
 * 把 `null` 当成最新会让一批判不了的条目全顶到最前面，而它们其实连时间都没有。
 * 它的不可确定性由页面上的「时刻判不了」如实报出，不靠排序位置表达。
 */
export function sortNotifications(items: readonly NotificationItem[]): NotificationItem[] {
  return [...items].sort((left, right) => {
    const leftTime = timeOf(left.occurredAt);
    const rightTime = timeOf(right.occurredAt);
    if (leftTime !== rightTime) {
      if (leftTime === null) return 1;
      if (rightTime === null) return -1;
      return rightTime - leftTime;
    }
    const severity = SEVERITY_RANK[right.severity] - SEVERITY_RANK[left.severity];
    if (severity !== 0) return severity;
    if (left.type !== right.type) return left.type.localeCompare(right.type);
    return left.reference.localeCompare(right.reference);
  });
}

/** 时刻 → 毫秒时间戳；解析不出来或缺失时返回 `null`（**判不了**，不是 0）。 */
function timeOf(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const parsed = Date.parse(iso);
  return Number.isFinite(parsed) ? parsed : null;
}

// ---------------------------------------------------------------- 订阅与筛选

/**
 * 按订阅类型过滤收件箱。
 *
 * <p>「按类型订阅」是蓝图要求落在本模块的能力：订阅集合是**生效值**
 * （没自定义过时服务端返回全部类型）。
 */
export function filterBySubscription(
  items: readonly NotificationItem[],
  subscribed: readonly string[],
): NotificationItem[] {
  if (subscribed.length === 0) return [];
  const allowed = new Set(subscribed);
  return items.filter((item) => allowed.has(item.type));
}

/** 清单筛选条件（前端侧，全部基于**合并后**的同一份集合）。 */
export interface NotificationFilters {
  type: string;
  severity: string;
  readState: 'ALL' | 'UNREAD' | 'READ';
  keyword: string;
}

/** 空筛选条件。 */
export const EMPTY_FILTERS: NotificationFilters = {
  type: '',
  severity: '',
  readState: 'ALL',
  keyword: '',
};

/** 按筛选条件过滤收件箱。 */
export function filterNotifications(
  items: readonly NotificationItem[],
  filters: NotificationFilters,
): NotificationItem[] {
  const keyword = filters.keyword.trim().toLowerCase();
  return items.filter((item) => {
    if (filters.type && item.type !== filters.type) return false;
    if (filters.severity && item.severity !== filters.severity) return false;
    if (filters.readState === 'UNREAD' && item.read) return false;
    if (filters.readState === 'READ' && !item.read) return false;
    if (keyword) {
      const haystack =
        `${item.reference} ${item.referenceLabel} ${item.detail} ${item.scopeLabel}`.toLowerCase();
      if (!haystack.includes(keyword)) return false;
    }
    return true;
  });
}

/**
 * 收件箱集合的汇总计数（**与清单同一个集合**）。
 *
 * <p>🔴 **未读计数与清单同源**：这里的 `unreadCount` 由**清单渲染的那一批行**算出，
 * 不是另查一个 count 端点 —— 另查就会与清单口径漂移（清单改了、角标没改）。
 */
export function summarizeNotifications(items: readonly NotificationItem[]): {
  total: number;
  byType: Record<string, number>;
  bySeverity: Record<string, number>;
  unreadCount: number;
  readCount: number;
  unknownSeverityCount: number;
  unknownTimeCount: number;
} {
  const byType: Record<string, number> = {};
  for (const type of NOTIFICATION_TYPES) byType[type] = 0;
  const bySeverity: Record<string, number> = {};
  for (const severity of Object.keys(SEVERITY_LABELS)) bySeverity[severity] = 0;
  let unread = 0;
  let unknownTime = 0;
  for (const item of items) {
    byType[item.type] = (byType[item.type] ?? 0) + 1;
    bySeverity[item.severity] = (bySeverity[item.severity] ?? 0) + 1;
    if (!item.read) unread += 1;
    if (timeOf(item.occurredAt) === null) unknownTime += 1;
  }
  return {
    total: items.length,
    byType,
    bySeverity,
    unreadCount: unread,
    readCount: items.length - unread,
    // ⚠️ 从 bySeverity 里取，**不要**在循环里再累加一次 ——
    // 那会把「判不了严重度」的条数算成两倍（同一个计数器被加了两轮）。
    unknownSeverityCount: bySeverity.UNKNOWN ?? 0,
    unknownTimeCount: unknownTime,
  };
}

/**
 * 未读角标文案（未读数为 0 时不显示角标）。
 *
 * <p>刻意返回空串而不是「0」：角标上写个 0 会被读成「有一条但没数出来」。
 */
export function unreadBadge(summary: { unreadCount: number }): string {
  if (summary.unreadCount <= 0) return '';
  return String(summary.unreadCount);
}

// ---------------------------------------------------------------- 文案

/** 秒数 → 中文时长（`null` = 判不了）。 */
export function formatSpan(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) return '判不了';
  if (seconds < 60) return '不到 1 分钟';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  const restMinutes = minutes % 60;
  if (hours < 24) return restMinutes ? `${hours} 小时 ${restMinutes} 分钟` : `${hours} 小时`;
  const days = Math.floor(hours / 24);
  const restHours = hours % 24;
  return restHours ? `${days} 天 ${restHours} 小时` : `${days} 天`;
}

/** ISO 时刻 → 本地时间文案；缺失或解析不出来时显示「时刻判不了」。 */
export function formatOccurredAt(iso: string | null | undefined): string {
  const parsed = timeOf(iso);
  if (parsed === null) return '时刻判不了';
  const date = new Date(parsed);
  const pad = (value: number) => String(value).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}
