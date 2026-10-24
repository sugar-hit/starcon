/**
 * 1.1.1.1 我的待办的前端聚合模型。
 *
 * <p>蓝图原文：「把散落在 12 个系统的『需要人处理』的动作收敛成一条**按紧急度排序**的队列，
 * 每项都有明确的下一步入口。覆盖：超期未发货、待审订单、库存缺口、待处理同步失败、
 * 待核对账差异、待回复站内信、待审批单据、库存低于安全线。」
 *
 * <p>上游 = 09 订单、07 库存、02 渠道、12 财务、15 客户、17 审批。
 * 输出 = 待办卡片列表（类型、单据引用、严重度、停留时长、下一步链接）+ 未读计数角标。
 *
 * <p>🔴 **本文件是纯函数层**：不碰网络、不碰 React、不重算业务口径。
 * 它做四件事：
 *
 * <ol>
 *   <li><b>归一</b>：把 8 个系统**形状完全不同**的投影行折成同一种待办卡片 ——
 *       上游各给各的字段名与语义（trade 给 {@code overdueKind}，warehouse 给
 *       {@code severity}，crm 给 {@code readStatus}），折算是**语义对齐**，不是改字段名；</li>
 *   <li><b>定级</b>：每类待办的严重度**从上游自己的字段推**，
 *       推不出来的**单列成「判不了」**，不拿一个写死的常量冒充；</li>
 *   <li><b>排序</b>：按紧急度（严重度 → 停留时长）排，紧急度相同的按类型名稳定排，
 *       保证同一批数据两次刷新顺序不跳；</li>
 *   <li><b>导航</b>：下一步入口只给**归属系统的处理页**（前端路由 + 定位参数），
 *       工作台自己不写业务数据。</li>
 * </ol>
 *
 * <p>⚠️ **「判不了」与「没有」是两件事**：
 * 一个待办推不出严重度（上游没给判定依据）时，它是**判不了**，不是「低优先级」。
 * 本模型把它单列成 {@link Severity.UNKNOWN}，页面上如实显示「判不了」，
 * 绝不用 LOW 兜底 —— 用 LOW 兜底会让「判不了」的事项被排到最后，然后永远没人处理。
 */

// 相对路径而不是 `@/lib/api`：根 vitest 配置没有 `@/*` 别名（那只在 Next 构建里解析），
// 用别名会让这个模型的单测在 vite 下加载失败。与 `anomaly-order-model` 保持同一套约定。
import type {
  Access,
  ApprovalTodoItem,
  CrmMessageRow,
  FinanceSupplierReconciliationRow,
  PendingReviewRow,
  SafetyStockAlertRow,
  StockoutAlertRow,
  SyncFailureView,
  TimelinessOverdueRow,
} from '../../../../../packages/api-contracts/src/index';

/** 会话里的一条角色授权（形状来自 {@code auth/session} 的响应）。 */
export type SessionGrant = {
  roleId: string;
  roleCode: string;
  scopeMode: string;
  permissions: string[];
};

/**
 * 八类待办（顺序 = 蓝图里「覆盖：」的顺序）。
 *
 * <p>这八类**一一对应**蓝图点名的八种「需要人处理」的动作，
 * 每一类都绑定**一个**上游投影端点 —— 不多不少，页面上不出现蓝图没写的类型。
 */
export const TODO_TYPES = [
  'ORDER_OVERDUE',
  'ORDER_PENDING_REVIEW',
  'STOCK_SHORTAGE',
  'SYNC_FAILED',
  'RECONCILIATION_DIFF',
  'MESSAGE_UNREAD',
  'APPROVAL_PENDING',
  'SAFETY_STOCK_BELOW',
] as const;

export type TodoType = (typeof TODO_TYPES)[number];

/** 类型 → 中文标签。 */
export const TODO_TYPE_LABELS: Record<TodoType, string> = {
  ORDER_OVERDUE: '超期未发货',
  ORDER_PENDING_REVIEW: '待审订单',
  STOCK_SHORTAGE: '库存缺口',
  SYNC_FAILED: '待处理同步失败',
  RECONCILIATION_DIFF: '待核对账差异',
  MESSAGE_UNREAD: '待回复站内信',
  APPROVAL_PENDING: '待审批单据',
  SAFETY_STOCK_BELOW: '库存低于安全线',
};

/**
 * 每类待办的**归属系统**（= 谁拥有那张事实表）。
 *
 * <p>蓝图写死「上游 = 09 订单、07 库存、02 渠道、12 财务、15 客户、17 审批」，
 * 这里逐条对上，让页面上每张卡片都能说清「这条待办归谁管、去哪儿处理」。
 */
export const TODO_TYPE_OWNER: Record<TodoType, string> = {
  ORDER_OVERDUE: '09 订单',
  ORDER_PENDING_REVIEW: '09 订单',
  STOCK_SHORTAGE: '07 库存',
  SYNC_FAILED: '02 渠道',
  RECONCILIATION_DIFF: '12 财务',
  MESSAGE_UNREAD: '15 客户',
  APPROVAL_PENDING: '17 审批',
  SAFETY_STOCK_BELOW: '07 库存',
};

/**
 * 每类待办读的上游投影端点（相对服务前缀）。
 *
 * <p>⚠️ 这些形状**全部已在 api-proxy 白名单里放行**（本车道实测：225 条既有元素逐条命中），
 * 所以本模块**不新增**白名单元素 —— k=0，既有的兄弟模块路由一条都没动。
 */
export const TODO_TYPE_SOURCE: Record<TodoType, string> = {
  ORDER_OVERDUE: 'trade/fulfillment/timeliness/overdue',
  ORDER_PENDING_REVIEW: 'trade/order-pending-review/queue',
  STOCK_SHORTAGE: 'warehouse/inventory/stockout-alerts/stockouts',
  SYNC_FAILED: 'channel/sync-failures',
  RECONCILIATION_DIFF: 'finance/supplier-reconciliation',
  MESSAGE_UNREAD: 'crm/message-inbox/messages',
  APPROVAL_PENDING: 'identity/approval-todos',
  SAFETY_STOCK_BELOW: 'warehouse/inventory/safety-stock/alerts',
};

/**
 * 每类待办**需要的读权限码**。
 *
 * <p>取自各服务控制器里 {@code access.require(bearer, "<码>")} 的**逐字**权限码。
 * 这些码**只用于判断「这个来源能不能读」**，
 * 前端**不把它当成授权凭据** —— 真正的授权判定永远发生在服务端
 * （{@code AccessService.require} → 身份服务 → RLS 租户上下文）。
 */
export const TODO_TYPE_PERMISSION: Record<TodoType, string> = {
  ORDER_OVERDUE: 'trade.fulfillment.read',
  ORDER_PENDING_REVIEW: 'trade.orders.read',
  STOCK_SHORTAGE: 'warehouse.inventory.read',
  SYNC_FAILED: 'channel.sync.read',
  RECONCILIATION_DIFF: 'finance.suppliereconciliation.read',
  MESSAGE_UNREAD: 'crm.message.read',
  APPROVAL_PENDING: 'identity.approval_inbox.read',
  SAFETY_STOCK_BELOW: 'warehouse.inventory.read',
};

// ---------------------------------------------------------------- 数据范围

/**
 * 数据范围模式。
 *
 * <p>与后端 {@code GrantPermissions} 的三个 scopeMode 逐字同式：
 * `ALL`（租户内全部）/ `DEPARTMENT`（本部门）/ `SELF`（仅本人）。
 */
export type ScopeMode = 'ALL' | 'DEPARTMENT' | 'SELF';

export const SCOPE_MODE_LABELS: Record<ScopeMode, string> = {
  ALL: '租户内全部',
  DEPARTMENT: '本部门',
  SELF: '仅本人',
};

/**
 * 会话在某权限码上**实际生效**的数据范围。
 *
 * <p>🔴 **判定规则与后端逐字同式**（{@code GrantPermissions.scopeMode}）：
 * 取**最宽**的一条授权（ALL &gt; DEPARTMENT &gt; SELF）——
 * 多条授权里只要有一条 ALL，返回的行就是全租户可见，
 * 报成 SELF 会与实际可见范围不符（界面就会说「只看到本人的」，实际看到的是全租户的）。
 * DEPARTMENT 需要会话确实带部门（{@code hasDepartment}），否则退化为 SELF ——
 * 往更严的一侧退化，与各服务既有口径一致。
 *
 * @param grants 会话的全部授权（来自服务端下发的 {@code auth/session}）
 * @param permission 形如 {@code trade.orders.read} 的权限码
 * @param hasDepartment 会话是否带部门
 */
export function scopeModeOf(
  grants: readonly SessionGrant[] | null | undefined,
  permission: string,
  hasDepartment: boolean,
): ScopeMode {
  if (!grants || grants.length === 0) return 'SELF';
  const dot = permission.lastIndexOf('.');
  const siblingRead = (dot < 0 ? permission : permission.substring(0, dot)) + '.read';
  let all = false;
  let department = false;
  for (const grant of grants) {
    if (!grant) continue;
    const permissions = grant.permissions ?? [];
    if (!permissions.includes(permission) || !permissions.includes(siblingRead)) continue;
    if (grant.scopeMode === 'ALL') all = true;
    else if (grant.scopeMode === 'DEPARTMENT') department = true;
  }
  if (all) return 'ALL';
  if (department && hasDepartment) return 'DEPARTMENT';
  return 'SELF';
}

/**
 * 会话在某权限码上**有没有读权限**。
 *
 * <p>判据与后端 {@code AccessService.require} **逐字同构**：
 * 命中某条授权时，同一条授权里必须**同时**有「动作权限」与「该动作所属页面的 {@code .read} 权限」，
 * 否则「能写不能读」这种组合会被静默放行。
 *
 * <p>🔴 这个判定只用来决定「**要不要去读这个来源**」——
 * 读不到时的提示文案才说得准（是「你没这个权限」还是「服务挂了」）。
 * 它**不是**授权：服务端会独立地再判一次，前端判过了也不代表服务端会放行。
 */
export function canRead(
  grants: readonly SessionGrant[] | null | undefined,
  permission: string,
  hasDepartment: boolean,
): boolean {
  if (!grants || grants.length === 0) return false;
  const dot = permission.lastIndexOf('.');
  const siblingRead = (dot < 0 ? permission : permission.substring(0, dot)) + '.read';
  return grants.some((grant) => {
    if (!grant) return false;
    const permissions = grant.permissions ?? [];
    if (!permissions.includes(permission) || !permissions.includes(siblingRead)) return false;
    if (grant.scopeMode === 'ALL' || grant.scopeMode === 'SELF') return true;
    return grant.scopeMode === 'DEPARTMENT' && hasDepartment;
  });
}

/**
 * 每个来源的**可达性判定**结果。
 *
 * <p>{@link permitted=false} 表示「你的角色没有这个来源的读权限」——
 * 它与「服务读不到」是两件不同的事，页面上必须分开显示：
 * 前者是「这一类待办对你不开放」，后者是「这一类待办本次判不了」。
 */
export interface SourceAvailability {
  permitted: boolean;
  scopeMode: ScopeMode;
}

/**
 * 逐类判定八个来源的可达性与生效数据范围。
 *
 * @param access 服务端下发的会话（{@code auth/session} 的响应）
 */
export function sourceAvailabilities(access: Access | null): Record<TodoType, SourceAvailability> {
  const grants = access?.grants ?? null;
  const hasDepartment = Boolean(access?.departmentId);
  const out = {} as Record<TodoType, SourceAvailability>;
  for (const type of TODO_TYPES) {
    const permission = TODO_TYPE_PERMISSION[type];
    out[type] = {
      permitted: canRead(grants, permission, hasDepartment),
      scopeMode: scopeModeOf(grants, permission, hasDepartment),
    };
  }
  return out;
}

/**
 * 严重度。
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

/** 一条待办卡片。 */
export interface MyTodo {
  /** 卡片唯一键（同类型下由上游主键组成）。 */
  key: string;
  type: TodoType;
  /** 单据引用（订单号 / 对账单号 / 消息号 …）。 */
  reference: string;
  /** 单据引用的补充说明（归属系统给的中文名等）。 */
  referenceLabel: string;
  /** 严重度；{@link Severity.UNKNOWN} = 判不了。 */
  severity: Severity;
  /**
   * 停留时长（秒）。
   *
   * <p>🔴 `null` = **判不了停留时长**（上游没给起点），
   * 不是 0 —— 0 会把「不知道停了多久」显示成「刚发生」。
   */
  dwellSeconds: number | null;
  /** 下一步入口（前端路由 + 定位参数）。 */
  href: string;
  /** 下一步入口的文案。 */
  actionLabel: string;
  /** 附加说明（上游给的读数，如缺口量 / 失败原因）。 */
  detail: string;
  /** 数据范围维度标签（法人 / 店铺 / 仓库 / 部门），用于说明这条待办落在哪个范围里。 */
  scopeLabel: string;
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

/**
 * 停留时长（秒）→ 中文文案。
 *
 * <p>`null`（判不了）显示成「停留时长判不了」而不是「—」，
 * 因为「—」看起来像「0 秒」，会被读成「刚发生」。
 */
export function formatDwell(seconds: number | null): string {
  if (seconds === null || !Number.isFinite(seconds) || seconds < 0) return '停留时长判不了';
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

/**
 * ISO 时刻 → 停留秒数（相对 {@link now}）。
 *
 * <p>返回 `null` 表示**判不了**：时刻缺失或格式不认，
 * 绝不用 0 顶替（0 = 刚发生，与「不知道」是两件事）。
 */
export function dwellFrom(iso: string | null | undefined, now: number): number | null {
  if (!iso) return null;
  const parsed = Date.parse(iso);
  if (!Number.isFinite(parsed)) return null;
  const seconds = Math.floor((now - parsed) / 1000);
  return seconds < 0 ? 0 : seconds;
}

/** 超期未发货的严重度：按超期时长分档（上游没给严重度字段）。 */
export function overdueSeverity(overdueSeconds: number | null | undefined): Severity {
  if (overdueSeconds === null || overdueSeconds === undefined) return 'UNKNOWN';
  if (!Number.isFinite(overdueSeconds)) return 'UNKNOWN';
  if (overdueSeconds >= 24 * 3600) return 'CRITICAL';
  if (overdueSeconds >= 4 * 3600) return 'HIGH';
  if (overdueSeconds > 0) return 'MEDIUM';
  return 'LOW';
}

/**
 * 待审订单的严重度：按**风险分**分档。
 *
 * <p>⚠️ 上游的 {@code riskScore} 是 `null` 时表示「**没评估过**」，不是 0 分 ——
 * 拿 0 去分档会把「没评估」显示成「零风险」。
 *
 * <p>⚠️ {@code riskManualReviewRequired} 是**风控明确要求人工复核**的判定，
 * 它比风险分更权威：风控说「必须人看」时，这一单就坐在待审队列里等人 ——
 * 所以它直接定到 {@link HIGH}，不再看风险分。
 */
export function pendingReviewSeverity(row: PendingReviewRow): Severity {
  if (row.riskScore === null || row.riskScore === undefined) return 'UNKNOWN';
  if (row.riskManualReviewRequired) return 'HIGH';
  if (row.riskScore >= 70) return 'HIGH';
  if (row.riskScore >= 40) return 'MEDIUM';
  return 'LOW';
}

/**
 * 库存缺口 / 低于安全线的严重度：上游**直接给了**严重度，原样用（不重算）。
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
      return 'MEDIUM';
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
 * <p>0 条未结差异 ⇒ 这一行**不是**待办（页面上不出现），
 * 有未结差异才是一条待办；条数越多越紧急。
 */
export function reconciliationSeverity(openDifferenceCount: number | null | undefined): Severity {
  if (openDifferenceCount === null || openDifferenceCount === undefined) return 'UNKNOWN';
  if (openDifferenceCount <= 0) return 'LOW';
  if (openDifferenceCount >= 10) return 'HIGH';
  return 'MEDIUM';
}

/**
 * 待审批单据的严重度：按**是否已超时**分档。
 *
 * <p>⚠️ 上游的 {@code timeoutSeconds} 为 `null` 表示「节点没配时限 ⇒ 判不了超时」，
 * 这种单据**既不算超时也不算没超时**，落在 {@link Severity.UNKNOWN}，
 * 不拿一个写死的 SLA 去冒充审批时限。
 */
export function approvalSeverity(item: ApprovalTodoItem): Severity {
  if (item.timeoutSeconds === null || item.timeoutSeconds === undefined) {
    // 没配时限 ⇒ 判不了超时；但节点本身仍在等人，停留越久越该看。
    if ((item.dwellSeconds ?? 0) >= 24 * 3600) return 'MEDIUM';
    return 'UNKNOWN';
  }
  if ((item.overdueSeconds ?? 0) > 0) return 'CRITICAL';
  if ((item.dwellSeconds ?? 0) >= 24 * 3600) return 'HIGH';
  return 'MEDIUM';
}

// ---------------------------------------------------------------- 各类型的归一

/** 超期未发货 → 待办卡片。 */
export function toOrderOverdue(row: TimelinessOverdueRow, now: number): MyTodo {
  const kind = row.overdueKind === 'PROMISE' ? '承诺未达' : '发货超期';
  return {
    key: `ORDER_OVERDUE:${row.factId}:${row.overdueKind}`,
    type: 'ORDER_OVERDUE',
    reference: row.salesOrderNo || row.factNo,
    referenceLabel: `${kind} · ${row.shopRef || '未标注店铺'}`,
    severity: overdueSeverity(row.overdueSeconds),
    // 停留时长 = 超期了多久（上游给的秒数），不是「订单创建至今」。
    dwellSeconds:
      row.overdueSeconds === null || row.overdueSeconds === undefined
        ? null
        : Math.max(0, row.overdueSeconds),
    href: `/orders/fulfillment/timeliness?factNo=${encodeURIComponent(row.factNo)}`,
    actionLabel: '去时效处理页',
    detail: `${row.carrierName || row.carrierCode || '未标注承运商'} · 超期 ${formatDwell(row.overdueSeconds ?? null)}`,
    scopeLabel: row.shopRef ? `店铺 ${row.shopRef}` : row.warehouseRef ? `仓库 ${row.warehouseRef}` : '未标注范围',
  };
}

/** 待审订单 → 待办卡片。 */
export function toPendingReview(row: PendingReviewRow, now: number): MyTodo {
  const hours = Number.isFinite(row.waitingHours) ? row.waitingHours : null;
  return {
    key: `ORDER_PENDING_REVIEW:${row.orderId}`,
    type: 'ORDER_PENDING_REVIEW',
    reference: row.orderNo || row.externalOrderNo,
    referenceLabel: `${row.queueStatusLabel || row.queueStatus} · ${row.orderSource}`,
    severity: pendingReviewSeverity(row),
    dwellSeconds: hours === null ? null : Math.round(hours * 3600),
    href: `/orders/pending-review?orderNo=${encodeURIComponent(row.orderNo || row.externalOrderNo)}`,
    actionLabel: '去审单队列',
    detail:
      row.riskScore === null || row.riskScore === undefined
        ? '风控未评估（没有风险分，判不了严重度）'
        : `风险分 ${row.riskScore} · 已等候 ${hours ?? '?'} 小时`,
    scopeLabel: row.shippingCountry ? `收货国家 ${row.shippingCountry}` : '未标注范围',
  };
}

/** 库存缺口 → 待办卡片。 */
export function toStockShortage(row: StockoutAlertRow, now: number): MyTodo {
  const known = row.availableQuantity !== null && row.availableQuantity !== undefined;
  return {
    key: `STOCK_SHORTAGE:${row.id}`,
    type: 'STOCK_SHORTAGE',
    reference: row.skuCode || row.skuId,
    referenceLabel: row.warehouseName || row.warehouseCode || '未标注仓库',
    severity: upstreamSeverity(row.severity),
    dwellSeconds: dwellFrom(row.detectedAt, now),
    href: `/inventory/stockout-alerts?skuId=${encodeURIComponent(row.skuId)}`,
    actionLabel: '去缺货预警页',
    detail: known
      ? `可用 ${row.availableQuantity} · 需求 ${row.demandUnits ?? '未登记'} · 状态 ${row.alertState}`
      : `可用量未登记 · 状态 ${row.alertState}`,
    scopeLabel: row.warehouseCode ? `仓库 ${row.warehouseCode}` : '未标注范围',
  };
}

/** 待处理同步失败 → 待办卡片。 */
export function toSyncFailure(row: SyncFailureView, now: number): MyTodo {
  return {
    key: `SYNC_FAILED:${row.id}`,
    type: 'SYNC_FAILED',
    reference: row.externalKey,
    referenceLabel: `${row.platformCode} · ${row.resourceType}`,
    severity: syncFailureSeverity(row.reason),
    dwellSeconds: dwellFrom(row.occurredAt, now),
    href: '/channels?keyword=' + encodeURIComponent(row.externalKey),
    actionLabel: '去失败队列',
    detail: `${row.reason} · 已尝试 ${row.attempts} 次 · ${row.lastError || row.detail}`,
    scopeLabel: row.marketplaceCode ? `站点 ${row.marketplaceCode}` : `平台 ${row.platformCode}`,
  };
}

/** 待核对账差异 → 待办卡片。 */
export function toReconciliationDiff(
  row: FinanceSupplierReconciliationRow,
  now: number,
): MyTodo {
  return {
    key: `RECONCILIATION_DIFF:${row.id}`,
    type: 'RECONCILIATION_DIFF',
    reference: row.reconNo,
    referenceLabel: `${row.supplierName || row.supplierCode} · ${row.statusLabel || row.status}`,
    severity: reconciliationSeverity(row.openDifferenceCount),
    dwellSeconds: dwellFrom(row.updatedAt || row.createdAt, now),
    href: `/finance/supplier-reconciliation?reconNo=${encodeURIComponent(row.reconNo)}`,
    actionLabel: '去对账处理页',
    detail: `未结差异 ${row.openDifferenceCount ?? '未知'} 条 / 共 ${row.differenceCount ?? '未知'} 条`,
    scopeLabel: row.statementCurrency ? `币种 ${row.statementCurrency}` : '未标注范围',
  };
}

/** 待回复站内信 → 待办卡片。 */
export function toUnreadMessage(row: CrmMessageRow, now: number): MyTodo {
  return {
    key: `MESSAGE_UNREAD:${row.id}`,
    type: 'MESSAGE_UNREAD',
    reference: row.messageNo,
    referenceLabel: `${row.shopName || '未标注店铺'} · ${row.messageType}`,
    severity: messageSeverity(row.priority),
    dwellSeconds: dwellFrom(row.createdAt, now),
    href: '/crm/messages?keyword=' + encodeURIComponent(row.messageNo),
    actionLabel: '去站内信',
    detail: `${row.direction === 'INBOUND' ? '买家来信' : '平台来信'} · ${row.subject || '（无主题）'}`,
    scopeLabel: row.shopName ? `店铺 ${row.shopName}` : '未标注范围',
  };
}

/** 待审批单据 → 待办卡片。 */
export function toApprovalPending(item: ApprovalTodoItem, now: number): MyTodo {
  const overdue =
    item.timeoutSeconds !== null &&
    item.timeoutSeconds !== undefined &&
    (item.overdueSeconds ?? 0) > 0;
  return {
    key: `APPROVAL_PENDING:${item.id}`,
    type: 'APPROVAL_PENDING',
    reference: item.instanceNo,
    referenceLabel: `${item.documentTypeName} · ${item.currentNodeName}`,
    severity: approvalSeverity(item),
    dwellSeconds: item.dwellSeconds ?? null,
    href: '/org/my-approvals?instanceNo=' + encodeURIComponent(item.instanceNo),
    actionLabel: '去审批',
    detail: overdue
      ? `已超时 ${formatDwell(item.overdueSeconds ?? null)} · 申请人 ${item.applicantName ?? item.applicantId.slice(0, 8)}`
      : `当前节点停留 ${formatDwell(item.dwellSeconds ?? null)} · 申请人 ${item.applicantName ?? item.applicantId.slice(0, 8)}`,
    scopeLabel: item.sourceSystem ? `业务域 ${item.sourceSystem}` : '未标注范围',
  };
}

/** 库存低于安全线 → 待办卡片。 */
export function toSafetyStockBelow(row: SafetyStockAlertRow, now: number): MyTodo {
  return {
    key: `SAFETY_STOCK_BELOW:${row.id}`,
    type: 'SAFETY_STOCK_BELOW',
    reference: row.skuCode || row.skuId,
    referenceLabel: `${row.warehouseName || row.warehouseCode} · ${row.alertType}`,
    severity: upstreamSeverity(row.effectiveSeverity ?? row.severity),
    dwellSeconds: dwellFrom(row.detectedAt, now),
    href: `/inventory/safety-stock?skuId=${encodeURIComponent(row.skuId)}&warehouseId=${encodeURIComponent(row.warehouseId)}`,
    actionLabel: '去安全库存页',
    detail: `可用 ${row.availableQuantity} / 阈值 ${row.thresholdQuantity} · 缺口 ${row.shortfallQuantity}`,
    scopeLabel: row.warehouseCode ? `仓库 ${row.warehouseCode}` : '未标注范围',
  };
}

// ---------------------------------------------------------------- 聚合

/**
 * 八个来源归一后的合并结果。
 *
 * <p>每类待办的**读不到**状态单独记（{@link unreadable}），
 * 页面上渲染成「本次判不了这一类」，而不是把它当成「0 条」。
 */
export interface MergedTodos {
  todos: MyTodo[];
  /** 读不到的来源 → 原因。 */
  unreadable: { type: TodoType; reason: string }[];
}

/**
 * 把八类待办合并成一条队列，并按紧急度排序。
 *
 * @param sources 八类待办各自的原始行（读失败的那类给 `error` 非空的项）
 * @param now 排序与停留时长用的「当前时刻」
 */
export function mergeTodos(
  sources: {
    orderOverdue: SourceResult<TimelinessOverdueRow>;
    pendingReview: SourceResult<PendingReviewRow>;
    stockShortage: SourceResult<StockoutAlertRow>;
    syncFailed: SourceResult<SyncFailureView>;
    reconciliationDiff: SourceResult<FinanceSupplierReconciliationRow>;
    messageUnread: SourceResult<CrmMessageRow>;
    approvalPending: SourceResult<ApprovalTodoItem>;
    safetyStockBelow: SourceResult<SafetyStockAlertRow>;
  },
  now: number,
): MergedTodos {
  const todos: MyTodo[] = [];
  const unreadable: { type: TodoType; reason: string }[] = [];

  const push = <T>(
    type: TodoType,
    result: SourceResult<T>,
    map: (row: T) => MyTodo | null,
  ) => {
    if (result.error) {
      unreadable.push({ type, reason: result.error });
      return;
    }
    for (const row of result.rows) {
      const todo = map(row);
      if (todo) todos.push(todo);
    }
  };

  push('ORDER_OVERDUE', sources.orderOverdue, (row) => toOrderOverdue(row, now));
  push('ORDER_PENDING_REVIEW', sources.pendingReview, (row) => {
    // 已通过 / 已驳回 / 已挂起的**不是**「待审」—— 上游默认就只回 PENDING，
    // 但显式再挡一次，免得将来上游改了默认口径而这里悄悄把已审的单子报成待办。
    if (row.queueStatus !== 'PENDING') return null;
    return toPendingReview(row, now);
  });
  push('STOCK_SHORTAGE', sources.stockShortage, (row) => {
    // HEALTHY 不是缺口；RESOLVED 已处置，不再是待办。
    if (row.alertState === 'HEALTHY') return null;
    if (row.lifecycleState === 'RESOLVED') return null;
    return toStockShortage(row, now);
  });
  push('SYNC_FAILED', sources.syncFailed, (row) => {
    // 已重放 / 已解决 / 已忽略的失败**不再是**待处理失败。
    if (row.status !== 'OPEN') return null;
    return toSyncFailure(row, now);
  });
  push('RECONCILIATION_DIFF', sources.reconciliationDiff, (row) => {
    // 没有未结差异 ⇒ 这张对账单**没有**待办，不进队列。
    if (!row.openDifferenceCount || row.openDifferenceCount <= 0) return null;
    return toReconciliationDiff(row, now);
  });
  push('MESSAGE_UNREAD', sources.messageUnread, (row) => {
    // 已读 / 已归档的站内信**不是**「待回复」；
    // 发出去的（OUTBOUND）也不是「待回复」—— 那是已经回过的。
    if (row.readStatus !== 'UNREAD') return null;
    if (row.direction === 'OUTBOUND') return null;
    return toUnreadMessage(row, now);
  });
  push('APPROVAL_PENDING', sources.approvalPending, (row) => {
    // 上游只回 PENDING 的实例，这里再挡一次已完成的。
    if (row.status !== 'PENDING') return null;
    return toApprovalPending(row, now);
  });
  push('SAFETY_STOCK_BELOW', sources.safetyStockBelow, (row) => {
    // 已解决的预警不再是待办。
    if (row.status === 'RESOLVED') return null;
    return toSafetyStockBelow(row, now);
  });

  return { todos: sortTodos(todos), unreadable };
}

/**
 * 按紧急度排序。
 *
 * <p>严重度降序 → 停留时长降序（停得久的先看）→ 类型名 → 引用（稳定）。
 * 最后两级保证**同一批数据两次刷新顺序不跳** —— 顺序乱跳会让人以为待办自己在动。
 *
 * <p>⚠️ 停留时长**判不了**（`null`）的排在**同级有读数的后面**：
 * 「停了多久不知道」不等于「停了很久」——
 * 把 `null` 当成无穷大会把一批判不了的条目全顶到最前面，
 * 而它们既不比「已经停了 3 天」更急，也不比它更不可疑。
 * 它的不可确定性由**单独的「判不了」计数**如实报出（见 {@link summarizeTodos}），
 * 不靠排序位置表达。
 */
export function sortTodos(todos: readonly MyTodo[]): MyTodo[] {
  return [...todos].sort((left, right) => {
    const severity = SEVERITY_RANK[right.severity] - SEVERITY_RANK[left.severity];
    if (severity !== 0) return severity;
    const leftDwell = left.dwellSeconds ?? -1;
    const rightDwell = right.dwellSeconds ?? -1;
    if (leftDwell !== rightDwell) return rightDwell - leftDwell;
    if (left.type !== right.type) return left.type.localeCompare(right.type);
    return left.reference.localeCompare(right.reference);
  });
}

/** 清单筛选条件（前端侧，全部基于**合并后**的同一份集合）。 */
export interface TodoFilters {
  type: string;
  severity: string;
  keyword: string;
  minDwellHours: number;
}

/** 空筛选条件。 */
export const EMPTY_FILTERS: TodoFilters = {
  type: '',
  severity: '',
  keyword: '',
  minDwellHours: 0,
};

/** 按筛选条件过滤合并后的集合。 */
export function filterTodos(todos: readonly MyTodo[], filters: TodoFilters): MyTodo[] {
  const keyword = filters.keyword.trim().toLowerCase();
  return todos.filter((todo) => {
    if (filters.type && todo.type !== filters.type) return false;
    if (filters.severity && todo.severity !== filters.severity) return false;
    if (filters.minDwellHours > 0) {
      // 停留时长判不了的，**不被**「停留超过 N 小时」这个筛选排除：
      // 排除掉就等于假装它没停够久，而它其实是「停多久不知道」。
      if (todo.dwellSeconds !== null && todo.dwellSeconds < filters.minDwellHours * 3600)
        return false;
    }
    if (keyword) {
      const haystack =
        `${todo.reference} ${todo.referenceLabel} ${todo.detail} ${todo.scopeLabel}`.toLowerCase();
      if (!haystack.includes(keyword)) return false;
    }
    return true;
  });
}

/**
 * 合并后集合的汇总计数（**与清单同一个集合**，翻页时它不变）。
 *
 * <p>{@link unreadableCount} 是**判不了的来源数**，
 * 与 {@link total} 分开报 —— 少读一个来源不等于「待办变少了」。
 */
export function summarizeTodos(
  todos: readonly MyTodo[],
  unreadableCount: number,
): {
  total: number;
  byType: Record<string, number>;
  bySeverity: Record<string, number>;
  unreadCount: number;
  unknownSeverityCount: number;
  longestDwellSeconds: number | null;
} {
  const byType: Record<string, number> = {};
  for (const type of TODO_TYPES) byType[type] = 0;
  const bySeverity: Record<string, number> = {};
  for (const severity of Object.keys(SEVERITY_LABELS)) bySeverity[severity] = 0;
  let longest: number | null = null;
  let unread = 0;
  for (const todo of todos) {
    byType[todo.type] = (byType[todo.type] ?? 0) + 1;
    bySeverity[todo.severity] = (bySeverity[todo.severity] ?? 0) + 1;
    if (todo.type === 'MESSAGE_UNREAD') unread += 1;
    if (todo.dwellSeconds !== null && (longest === null || todo.dwellSeconds > longest)) {
      longest = todo.dwellSeconds;
    }
  }
  return {
    total: todos.length,
    byType,
    bySeverity,
    unreadCount: unread,
    // ⚠️ 从 bySeverity 里取，**不要**在循环里再累加一次 ——
    // 那会把「判不了严重度」的条数算成两倍（同一个计数器被加了两轮）。
    unknownSeverityCount: bySeverity.UNKNOWN ?? 0,
    longestDwellSeconds: longest,
  };
}

/** 未读计数角标（站内信未读数；其余类型没有「未读」这个概念）。 */
export function unreadBadge(summary: { unreadCount: number }): string {
  if (summary.unreadCount <= 0) return '';
  return String(summary.unreadCount);
}

/** 严重度筛选的下拉选项（把「判不了」也列进去 —— 它是真实存在的一档）。 */
export const SEVERITY_FILTER_OPTIONS: readonly Severity[] = [
  'CRITICAL',
  'HIGH',
  'MEDIUM',
  'LOW',
  'UNKNOWN',
];
