/**
 * 1.1.1.3 审批待办的前端展示口径。
 *
 * <p>蓝图原文：「待我审批的单据统一入口，支持按单据类型、业务域、金额阈值、超时时长筛选；
 * 展示审批链路当前节点与已耗时长。」
 *
 * <p>卷 01 的边界写死：「工作台只读 + 跳转；一切状态变更回到归属系统完成」。
 * 所以这个模型里最容易做错的，是把「跳转过去处理」做成「在这里处理」——
 * 审批动作（通过 / 驳回 / 转交）归 17.4.2.1，工作台只给入口。
 *
 * <p>本文件只做三件事：
 *
 * <ol>
 *   <li><b>口径</b>：停留时长、超时、金额阈值分支的展示口径，与后端的判定同式；</li>
 *   <li><b>能力</b>：哪些筛选维度真接通、哪些动作归别人、哪些维度**判不了** ——
 *       判不了的必须单列，不能悄悄按「没超时」「没有阈值」处理；</li>
 *   <li><b>导航</b>：动作只给出归属系统的入口，工作台自己不写业务数据。</li>
 * </ol>
 *
 * <p>⚠️ **「判不了」与「没有」是两件事**：
 * 一个节点没配超时升级规则时，它既不能算「已超时」也不能算「没超时」；
 * 一张单没落在任何金额阈值分支里时，它没有档位，但**不代表**它没有金额。
 * 这两种情况在本模型里都有独立的状态，不与「确实没有」混同。
 */

// 相对路径而不是 `@/lib/api`：根 vitest 配置没有 `@/*` 别名（那只在 Next 构建里解析），
// 用别名会让这个模型的单测在 vite 下加载失败。与 `anomaly-order-model` 保持同一套约定。
import type {
  ApprovalTodoAction,
  ApprovalTodoBucket,
  ApprovalTodoCapabilities,
  ApprovalTodoFilterCapability,
  ApprovalTodoItem,
} from '../../../../../packages/api-contracts/src/index';

/** 排序键白名单（与后端 `orderBy` 逐字同式；未知键后端直接 400）。 */
export const APPROVAL_SORT_KEYS = [
  'WAITING',
  'NEWEST',
  'OLDEST',
  'AMOUNT_DESC',
  'AMOUNT_ASC',
  'DOCUMENT',
  'OVERDUE',
] as const;

export type ApprovalSortKey = (typeof APPROVAL_SORT_KEYS)[number];

export const SORT_LABELS: Record<ApprovalSortKey, string> = {
  WAITING: '停留最久优先',
  NEWEST: '最新提交优先',
  OLDEST: '最早提交优先',
  AMOUNT_DESC: '金额从高到低',
  AMOUNT_ASC: '金额从低到高',
  DOCUMENT: '按单据类型',
  OVERDUE: '超时优先',
};

/** 审批人类型（`approval_flow_node.approver_type`）。 */
export const APPROVER_TYPE_LABELS: Record<string, string> = {
  USER: '指定人',
  ROLE: '按角色分配',
  POSITION: '按岗位',
};

/**
 * 17 审批详情页（下游）的前端路由。
 *
 * <p>工作台**不执行审批动作**，所以清单上的「处理」入口只能指向归属系统的页面。
 * 这里写死的是前端路由（后端 {@code capabilities.actions[].target} 也报同一处）。
 */
export const APPROVAL_DETAIL_ROUTE = '/org/my-approvals';

/** 停留时长档位（把「超时时长筛选」落成一组可选的停留下限，单位小时）。 */
export interface DwellPreset {
  code: string;
  label: string;
  minHours: number;
}

export const DWELL_PRESETS: readonly DwellPreset[] = [
  { code: 'ALL', label: '不限', minHours: 0 },
  { code: 'H1', label: '超过 1 小时', minHours: 1 },
  { code: 'H4', label: '超过 4 小时', minHours: 4 },
  { code: 'D1', label: '超过 1 天', minHours: 24 },
  { code: 'D3', label: '超过 3 天', minHours: 72 },
  { code: 'D7', label: '超过 7 天', minHours: 168 },
];

/**
 * 超时三态。
 *
 * <ul>
 *   <li>{@code OVERDUE} —— 节点配了时限，且停留已经超过它；</li>
 *   <li>{@code WITHIN} —— 节点配了时限，还没到；</li>
 *   <li>{@code UNKNOWN} —— **判不了**：节点没配超时升级规则。
 *       它既不是「没超时」，也不能算「已超时」—— 页面必须单列这一档。</li>
 * </ul>
 */
export type TimeoutState = 'OVERDUE' | 'WITHIN' | 'UNKNOWN';

/**
 * 这一单的超时三态。
 *
 * <p>判据是后端给的 {@code timeoutSeconds}（节点上配的最小时限）与
 * {@code overdueSeconds}（已超出多少）：{@code timeoutSeconds} 为 null 就是**没配规则**，
 * 判不了 —— 这里不拿任何默认小时数去凑。
 */
export function timeoutState(item: ApprovalTodoItem): TimeoutState {
  if (item.timeoutSeconds === null || item.timeoutSeconds === undefined) return 'UNKNOWN';
  return (item.overdueSeconds ?? 0) > 0 ? 'OVERDUE' : 'WITHIN';
}

/** 超时状态的文案。 */
export function timeoutLabel(item: ApprovalTodoItem): string {
  switch (timeoutState(item)) {
    case 'OVERDUE':
      return `已超时 ${formatDwell(item.overdueSeconds ?? 0)}`;
    case 'WITHIN':
      return `时限 ${formatDwell(item.timeoutSeconds ?? 0)}`;
    default:
      return '判不了超时（节点未配置时限）';
  }
}

/** 停留时长文案。 */
export function formatDwell(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return '—';
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

/** 金额文案。金额一律用 BigDecimal / NUMERIC 口径，前端不参与计算，只做展示。 */
export function formatAmount(currency: string | null, amount: number | null | undefined): string {
  if (amount === null || amount === undefined || !Number.isFinite(amount)) return '—';
  return `${currency ?? ''} ${amount.toFixed(2)}`.trim();
}

/**
 * 金额阈值区间的文案。
 *
 * <p>区间语义与后端逐字同式：上界是**开区间**（{@code amount < max_amount}），
 * 所以上界写「不含」而不是「≤」—— 写成「≤」会让人以为边界值落在这一档，
 * 而它其实落在下一档。
 *
 * <p>返回 null 表示**没有档位**（没配分支，或这张单没落在任何一档里），
 * 不返回「—」：空档位与「档位区间为 0」是两件事。
 */
export function formatThreshold(item: ApprovalTodoItem): string | null {
  if (!item.amountThresholdName) return null;
  const currency = item.amountThresholdCurrency ?? item.currencyCode ?? '';
  const min = item.amountThresholdMin;
  const max = item.amountThresholdMax;
  if (min === null && max === null) return `${item.amountThresholdName}（不限金额）`;
  if (max === null) return `${item.amountThresholdName} ≥ ${min} ${currency}`.trim();
  if (min === null) return `${item.amountThresholdName} < ${max} ${currency}`.trim();
  return `${item.amountThresholdName} ${min} ~ <${max} ${currency}`.trim();
}

/** 能力声明里取一个筛选维度；取不到返回 null。 */
export function filterCapability(
  capabilities: ApprovalTodoCapabilities | null,
  key: string,
): ApprovalTodoFilterCapability | null {
  if (!capabilities) return null;
  return (capabilities.filters ?? []).find((filter) => filter.key === key) ?? null;
}

/**
 * **判不了 / 未接通**的筛选维度。
 *
 * <p>这些维度必须在页面上带缺口说明显示出来，而不是静默消失 ——
 * 静默消失会让人以为「这个维度没有可选项」，而不是「这个维度在本租户还没配」。
 */
export function unavailableFilters(
  capabilities: ApprovalTodoCapabilities | null,
): ApprovalTodoFilterCapability[] {
  if (!capabilities) return [];
  return (capabilities.filters ?? []).filter((filter) => !filter.available);
}

/**
 * 可以真正使用的动作（{@code available=true}）。
 *
 * <p>只有这些才渲染成能点的入口。{@code available=false} 的动作
 * （审批通过 / 驳回 / 转交）归 17.4.2.1，工作台**不执行审批动作**，
 * 它们会被渲染成一条说明而不是第二个能改单子的按钮。
 */
export function availableActions(capabilities: ApprovalTodoCapabilities | null): ApprovalTodoAction[] {
  if (!capabilities) return [];
  return (capabilities.actions ?? []).filter((action) => action.available);
}

/** **不可用**的动作（归 17 执行，或当前没有归属入口）。 */
export function blockedActions(capabilities: ApprovalTodoCapabilities | null): ApprovalTodoAction[] {
  if (!capabilities) return [];
  return (capabilities.actions ?? []).filter((action) => !action.available);
}

/**
 * 动作的前端落点。
 *
 * <p>「跳到哪儿」是前端的责任，「这个动作归谁管」是后端的责任 —— 两者分开，
 * 才不会出现前端把一个后端说「归 17」的动作渲染成能点的按钮。
 *
 * <p>没有落点的动作（复制单号是页内动作）返回空串，由页面自己处理。
 */
export function actionTarget(action: ApprovalTodoAction): string {
  if (!action.available) return '';
  if (action.code === 'OPEN_APPROVAL_DETAIL') return action.target ?? APPROVAL_DETAIL_ROUTE;
  return action.target ?? '';
}

/** 清单筛选条件（全部映射到后端请求参数）。 */
export interface ApprovalFilters {
  documentTypeCode: string;
  sourceSystem: string;
  currencyCode: string;
  amountBranchName: string;
  minAmount: string;
  maxAmount: string;
  minDwellSeconds: string;
  overdueOnly: boolean;
  sort: string;
}

/** 空筛选条件。 */
export const EMPTY_FILTERS: ApprovalFilters = {
  documentTypeCode: '',
  sourceSystem: '',
  currencyCode: '',
  amountBranchName: '',
  minAmount: '',
  maxAmount: '',
  minDwellSeconds: '',
  overdueOnly: false,
  sort: 'WAITING',
};

/**
 * 筛选条件 → 查询串。
 *
 * <p>⚠️ **空值一律不下发**：空串会被后端当成「筛选这个空值」而不是「不筛」，
 * 所以这里把空值全部剔掉。
 */
export function buildQuery(filters: ApprovalFilters): string {
  const parts = new URLSearchParams();
  if (filters.documentTypeCode.trim()) parts.set('documentTypeCode', filters.documentTypeCode.trim());
  if (filters.sourceSystem.trim()) parts.set('sourceSystem', filters.sourceSystem.trim());
  if (filters.currencyCode.trim()) parts.set('currencyCode', filters.currencyCode.trim().toUpperCase());
  if (filters.amountBranchName.trim()) parts.set('amountBranchName', filters.amountBranchName.trim());
  const min = parseAmount(filters.minAmount);
  const max = parseAmount(filters.maxAmount);
  if (min !== null) parts.set('minAmount', String(min));
  if (max !== null) parts.set('maxAmount', String(max));
  const dwell = parseAmount(filters.minDwellSeconds);
  if (dwell !== null) parts.set('minDwellSeconds', String(dwell));
  if (filters.overdueOnly) parts.set('overdueOnly', 'true');
  if (filters.sort.trim()) parts.set('sort', filters.sort.trim());
  return parts.toString();
}

/**
 * 输入框里的数字 → 可下发的数字。
 *
 * <p>空串 / 非数字 / 负数一律返回 null（不筛）—— 前端不做「范围校验后改写」，
 * 那会让页面上显示的条件与真正生效的条件对不上；真正的不合法值由后端报 400。
 */
export function parseAmount(value: string): number | null {
  const trimmed = value.trim();
  if (!trimmed) return null;
  if (!/^\d+(\.\d+)?$/.test(trimmed)) return null;
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed) || parsed < 0) return null;
  return parsed;
}

/** 分档里某一档的条数；没有这一档时是 0（不是报错 —— 一档都没有是合法状态）。 */
export function bucketCount(buckets: readonly ApprovalTodoBucket[] | undefined, code: string): number {
  if (!buckets) return 0;
  return buckets.find((bucket) => bucket.code === code)?.count ?? 0;
}

/** 分档里某一档的名字；没有这一档时退回编码本身。 */
export function bucketLabel(
  buckets: readonly ApprovalTodoBucket[] | undefined,
  code: string,
): string {
  if (!buckets) return code;
  return buckets.find((bucket) => bucket.code === code)?.name ?? code;
}

/** 审批人类型的中文名。 */
export function approverTypeLabel(approverType: string): string {
  return APPROVER_TYPE_LABELS[approverType] ?? approverType;
}

/** 当前节点的展示文案（节点名 + 链路位置）。 */
export function nodeLabel(item: ApprovalTodoItem): string {
  const stage = item.currentStageIndex + 1;
  const order = item.currentNodeOrder + 1;
  return `${item.currentNodeName}（第 ${stage} 段 · 第 ${order} 个节点 · ${approverTypeLabel(
    item.approverType,
  )}）`;
}

/**
 * 把选中的审批单号拼成一段可交接的清单。
 *
 * <p>工作台不执行审批动作，所以「复制单号」是给跳到 17 之后用的交接物，
 * 不是给「批量审批」用的。
 */
export function instanceNoDigest(items: readonly ApprovalTodoItem[]): string {
  return items
    .map((item) => item.instanceNo)
    .filter((no) => Boolean(no))
    .join(', ');
}
