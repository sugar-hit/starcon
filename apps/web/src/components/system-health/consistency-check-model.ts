/**
 * 1.5.1.2 数据一致性体检 —— 前端聚合模型（卷 01 工作台与门户）。
 *
 * <p>蓝图原文（`docs/product/capability-blueprint/01-dashboard-and-portal.md` 第 222–227 行）：
 * 「跨域一致性自检：库存账实核对结果、待处理对账差异率、库存准确率、订单/履约/财务三方
 * 金额差异、待处理映射缺失数，给出健康结论。输出：一致性体检报告（分项健康 + 异常清单 +
 * 处置入口）。**一致性指标是账可信的底线**，低于阈值应阻断结算与对账出结论。」
 *
 * <p><b>为什么是纯前端聚合层</b>：卷 01 卷首写死了两条 ——
 * ①「后端归属：前端聚合层 + 各服务的只读投影（<b>不新增业务服务</b>）」；
 * ②「不产生新的业务事实，只聚合各系统的投影」。
 * 本模块要的五类事实**没有一类**需要新表：其中三类已在归属服务的只读端点里就位
 * （见 {@link CONSISTENCY_SOURCE_SPECS}），另外两类上游**尚未交付**或**在本仓不可比**
 * （见 {@link buildDiffRateCheck} / {@link buildAmountParityCheck}）。
 * ⇒ 本模块<b>零迁移、不加后端端点、不改 api-proxy 白名单</b>。
 *
 * <p><b>六条实现纪律</b>（每条都对应本模块最容易做错的一件事）：
 *
 * <ol>
 *   <li><b>「判不了」≠ 0 ≠ 健康</b>：上游未交付 / 无权限 / 服务读不到 / 没有样本 /
 *       不可比 —— 五种都判不了，页面显示「—」并说明原因，<b>绝不显示成 0 或绿色</b>。
 *       整体结论只要有一项判不了，就<b>不能</b>给「整体健康」。</li>
 *   <li><b>不重算上游口径</b>：账实核对的结论、库存准确率与阈值、映射缺口计数**全部**
 *       由归属服务算好，本模型只做展示与分类；前端不重新相减、不重新算比例
 *       —— 「计数与清单同源」，不存在「概览说 12 笔、清单只有 10 笔」。</li>
 *   <li><b>金额绝不跨币种相减</b>：本模块**不产出任何金额差异数字**。
 *       订单侧与财务侧各有钱，但履约侧没有金额事实、且两侧币种之间没有可读汇率 ——
 *       1:1 加出来的「差异」是假的，所以这一项如实报不可比。</li>
 *   <li><b>阈值是本模块的默认政策</b>，不是平台下发的口径（见 {@link MAPPING_GAP_STALE_DAYS}），
 *       规则表逐条公布在页面上。</li>
 *   <li><b>数据范围服务端判定</b>：前端<b>不</b>把法人 / 店铺 / 仓库范围当成可信输入往下传，
 *       只读各服务<b>已经按范围过滤好</b>的投影。本地算出的 {@link ScopeMode}
 *       只用于「要不要去读」与「怎么措辞」，<b>不是授权</b>（服务端会独立再判一次）。</li>
 *   <li><b>只读 + 跳转</b>：工作台<b>没有</b>触发核对 / 评估 / 映射 / 差异处理任何写动作，
 *       每个数字的出口是<b>跳到归属系统</b>去处理。</li>
 * </ol>
 *
 * <p>🔴 **本文件是纯函数层**：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  Access,
  InventoryHealthGate,
  MappingGapSummary,
  ReconciliationFinding,
  ReconciliationFindingPage,
  ReconciliationResult,
  ReconciliationRun,
  ReconciliationRunPage,
} from '../../../../../packages/api-contracts/src/index';

export type {
  Access,
  InventoryHealthGate,
  MappingGapSummary,
  ReconciliationFinding,
  ReconciliationFindingPage,
  ReconciliationResult,
  ReconciliationRun,
  ReconciliationRunPage,
};

// ================================================================ 分项与结论

/** 本模块体检的五个分项（对应蓝图点名的五类输入）。 */
export const CONSISTENCY_CHECK_KEYS = [
  'RECONCILIATION',
  'ACCURACY',
  'DIFF_RATE',
  'AMOUNT_PARITY',
  'MAPPING_GAPS',
] as const;

export type ConsistencyCheckKey = (typeof CONSISTENCY_CHECK_KEYS)[number];

/** 结论：红（必须处理）。 */
export const RED = 'RED';

/** 结论：黄（预警）。 */
export const YELLOW = 'YELLOW';

/** 结论：绿（正常）。 */
export const GREEN = 'GREEN';

/**
 * 结论：**判不了**。
 *
 * <p>🔴 这**不是**第四种颜色，是「没有结论」的独立一档 ——
 * 它与绿色在语义上完全相反：「查出来没问题」与「没查出来」不是一回事。
 */
export const UNDETERMINED = 'UNDETERMINED';

export type CheckVerdict = typeof RED | typeof YELLOW | typeof GREEN | typeof UNDETERMINED;

/** 结论排序（红 → 黄 → 判不了 → 绿）。 */
export const VERDICT_ORDER: readonly CheckVerdict[] = [RED, YELLOW, UNDETERMINED, GREEN];

/** 结论的人话。 */
export const VERDICT_LABELS: Record<CheckVerdict, string> = {
  RED: '红 · 必须处理',
  YELLOW: '黄 · 预警',
  UNDETERMINED: '判不了 · 无结论',
  GREEN: '绿 · 正常',
};

/** 结论 → Badge 色调。 */
export const VERDICT_TONES: Record<CheckVerdict, 'danger' | 'warning' | 'success' | 'neutral'> = {
  RED: 'danger',
  YELLOW: 'warning',
  UNDETERMINED: 'neutral',
  GREEN: 'success',
};

/** 结论排序权重（数字越小越靠前）。 */
export function verdictRank(verdict: CheckVerdict): number {
  const index = VERDICT_ORDER.indexOf(verdict);
  return index < 0 ? VERDICT_ORDER.length : index;
}

/**
 * 一格的可得性。
 *
 * <p>🔴 五种「拿不到」必须分开 —— 处置动作完全不同：
 * 无权限（连请求都不发）/ 服务读不到（可重试）/ 上游未交付（等别的车道）/ 不可比（本仓缺事实）/ 没有数据（读到了，这一档是空的）。
 */
export type CheckAvailability =
  'AVAILABLE' | 'NOT_PERMITTED' | 'UNREACHABLE' | 'NOT_DELIVERED' | 'NOT_COMPARABLE' | 'NO_DATA';

/** 可得性的人话。 */
export const AVAILABILITY_LABELS: Record<CheckAvailability, string> = {
  AVAILABLE: '读到了',
  NOT_PERMITTED: '无权限 · 没有去读',
  UNREACHABLE: '读不到 · 服务不可达',
  NOT_DELIVERED: '上游未交付',
  NOT_COMPARABLE: '不可比 · 本仓缺事实',
  NO_DATA: '没有数据',
};

/** 一个处置入口（跳到归属系统的页面）。 */
export interface CheckAction {
  label: string;
  href: string;
}

/** 上游来源的状态。 */
export type SourceState = 'READABLE' | 'NOT_DELIVERED' | 'NOT_COMPARABLE';

/** 一个上游来源的声明。 */
export interface ConsistencySourceSpec {
  key: ConsistencyCheckKey;
  /** 界面上显示的名字。 */
  label: string;
  /** 归属系统（谁拥有那张事实表）。 */
  owner: string;
  /** 归属模块（蓝图 L4 编号）。 */
  ownerModule: string;
  /** 蓝图点名的输入。 */
  blueprint: string;
  /** 上游只读端点（相对服务前缀）；不可读时为 null。 */
  endpoint: string | null;
  /** 读权限码（取自各控制器 {@code access.require} 的逐字权限码）；不可读时为 null。 */
  permission: string | null;
  /** 上游状态。 */
  state: SourceState;
  /** 判不了时：差什么、归谁。 */
  missing: string | null;
  /** 处置入口；没有真实可跳的页面时为 null。 */
  action: CheckAction | null;
}

/** 五个来源的逐条声明。 */
export const CONSISTENCY_SOURCE_SPECS: Record<ConsistencyCheckKey, ConsistencySourceSpec> = {
  RECONCILIATION: {
    key: 'RECONCILIATION',
    label: '库存账实核对',
    owner: '07 库存',
    ownerModule: '7.1.2.2 账实核对',
    blueprint: '库存账实核对结果',
    endpoint: 'warehouse/inventory/reconciliation',
    permission: 'warehouse.inventory.read',
    state: 'READABLE',
    missing: null,
    action: { label: '去 07 库存看账实核对', href: '/inventory' },
  },
  ACCURACY: {
    key: 'ACCURACY',
    label: '库存准确率',
    owner: '07 库存',
    ownerModule: '7.6.2.1 库存健康',
    blueprint: '库存准确率',
    endpoint: 'warehouse/inventory/health/gate',
    permission: 'warehouse.inventory.read',
    state: 'READABLE',
    missing: null,
    action: { label: '去 07 库存看库存健康', href: '/inventory/health' },
  },
  DIFF_RATE: {
    key: 'DIFF_RATE',
    label: '待处理对账差异率',
    owner: '12 财务',
    ownerModule: '12.3.2.1 对账任务 / 12.3.2.2 差异处理',
    blueprint: '待处理对账差异率',
    endpoint: null,
    permission: null,
    state: 'NOT_DELIVERED',
    missing: '12.3.2.1 对账任务 / 12.3.2.2 差异处理 / 12.3.2.3 自动匹配规则（均未交付）',
    action: null,
  },
  AMOUNT_PARITY: {
    key: 'AMOUNT_PARITY',
    label: '订单/履约/财务三方金额差异',
    owner: '09 订单 / 08 仓储作业 / 12 财务',
    ownerModule: '9.1.1.1 订单列表 · 8.1.1.1 仓库列表 · 12.3.1.1 结算单管理',
    blueprint: '订单/履约/财务三方金额差异',
    endpoint: null,
    permission: null,
    state: 'NOT_COMPARABLE',
    missing: '履约侧的订单金额事实（08 仓储作业）＋ 订单币种与结算币种之间的汇率',
    action: null,
  },
  MAPPING_GAPS: {
    key: 'MAPPING_GAPS',
    label: '待处理映射缺失',
    owner: '02 渠道',
    ownerModule: '2.4.2.3 映射缺失队列',
    blueprint: '待处理映射缺失数',
    endpoint: 'channel/mapping-gaps/summary',
    permission: 'channel.operation.read',
    state: 'READABLE',
    missing: null,
    action: { label: '去 02 渠道处理映射缺失', href: '/channels/mapping-gaps' },
  },
};

/** 一个分项的体检结果。 */
export interface ConsistencyCheck {
  key: ConsistencyCheckKey;
  label: string;
  owner: string;
  ownerModule: string;
  /** 结论。{@link UNDETERMINED} = 判不了，**不是**「好」。 */
  verdict: CheckVerdict;
  /** 这一格的可得性。 */
  availability: CheckAvailability;
  /** 人话结论。 */
  reason: string;
  /** 证据（口径与数字）。 */
  evidence: string[];
  /** 处置入口；null = 没有真实可跳的页面。 */
  action: CheckAction | null;
  /** 判不了时：差什么、归谁。 */
  missing: string | null;
}

/** 一个上游读取的结果。 */
export interface SourceOutcome<T> {
  /** 可得性。 */
  availability: CheckAvailability;
  /** 读到了就是值；不可用时是 null。 */
  value: T | null;
  /** 读不到时的人话原因（availability === 'UNREACHABLE' 时有值）。 */
  failure: string | null;
}

/** 构造一个「读到了」的读取结果。 */
export function available<T>(value: T): SourceOutcome<T> {
  return { availability: 'AVAILABLE', value, failure: null };
}

/** 构造一个「读不到」的读取结果。 */
export function unavailable<T>(
  availability: Exclude<CheckAvailability, 'AVAILABLE'>,
  failure: string | null = null,
): SourceOutcome<T> {
  return { availability, value: null, failure };
}

// ================================================================ 阈值（默认政策）

/**
 * 映射缺口的「陈旧」阈值（天）—— <b>本模块的默认政策</b>，不是平台下发的口径。
 *
 * <p>为什么要有：待处理映射缺失数只要 > 0 就是黄（缺口存在）；
 * 但「压了一周还没人处理」是另一档问题 —— 缺口会让对应平台变体的订单建不出来。
 */
export const MAPPING_GAP_STALE_DAYS = 7;

// ================================================================ 数据范围

/** 会话里的一条角色授权（形状来自 {@code auth/session} 的响应）。 */
export type SessionGrant = {
  roleId: string;
  roleCode: string;
  scopeMode: string;
  permissions: string[];
};

/**
 * 数据范围模式。
 *
 * <p>与后端 {@code GrantPermissions} 的 scopeMode 逐字同式：
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
 * 取**最宽**的一条授权（ALL > DEPARTMENT > SELF）。
 * DEPARTMENT 需要会话确实带部门，否则退化为 SELF —— 往更严的一侧退化。
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
 * <p>⚠️ 这个判定只用来决定「<b>要不要去读这个来源</b>」——
 * 读不到时的提示文案才说得准（是「你没这个权限」还是「服务挂了」）。
 * 它<b>不是授权</b>：服务端会独立地再判一次。
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

/** 一个来源的可达性判定结果。 */
export interface SourceAvailability {
  /** 「你的角色没有这个来源的读权限」—— 与「服务读不到」是两件事，页面上必须分开显示。 */
  permitted: boolean;
  /** 实际生效的数据范围。 */
  scopeMode: ScopeMode;
}

/** 逐个判定各来源的可达性与生效数据范围。 */
export function sourceAvailabilities(
  access: Access | null,
): Record<ConsistencyCheckKey, SourceAvailability> {
  const grants = access?.grants ?? null;
  const hasDepartment = Boolean(access?.departmentId);
  const out = {} as Record<ConsistencyCheckKey, SourceAvailability>;
  for (const key of CONSISTENCY_CHECK_KEYS) {
    const permission = CONSISTENCY_SOURCE_SPECS[key].permission;
    if (!permission) {
      // 上游不可读（未交付 / 不可比）：没有权限码，也就没有「要不要去读」这件事。
      out[key] = { permitted: false, scopeMode: 'SELF' };
      continue;
    }
    out[key] = {
      permitted: canRead(grants, permission, hasDepartment),
      scopeMode: scopeModeOf(grants, permission, hasDepartment),
    };
  }
  return out;
}

// ================================================================ 格式化

/** 比率格式化（0.99 → 99.00%）；null 显示「—」。 */
export function formatPercent(value: number | null | undefined, digits = 2): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  return `${(value * 100).toFixed(digits)}%`;
}

/** 数量格式化（去掉多余的尾随零，最多 6 位小数）；null 显示「—」。 */
export function formatQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  const rounded = Math.round(value * 10 ** 6) / 10 ** 6;
  return String(rounded);
}

/** 时间戳格式化；null 显示「—」。 */
export function formatTimestamp(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = Date.parse(value);
  if (Number.isNaN(parsed)) return value;
  return new Date(parsed).toLocaleString('zh-CN', { hour12: false });
}

/**
 * 距今多少天（向下取整）。
 *
 * @param iso 起始时刻（ISO 串）
 * @param now 当前时刻
 * @returns 天数；解析不了返回 null（「多久了」都答不出来，不是 0）
 */
export function ageDays(iso: string | null | undefined, now: Date): number | null {
  if (!iso) return null;
  const parsed = Date.parse(iso);
  if (Number.isNaN(parsed)) return null;
  return Math.floor((now.getTime() - parsed) / 86_400_000);
}

// ================================================================ 分项：账实核对

/** 取最近一次核对批次（按落库时刻倒序；服务端已经排好序，这里再排一次是防御性的）。 */
export function latestRun(
  runs: readonly ReconciliationRun[] | null | undefined,
): ReconciliationRun | null {
  if (!runs || runs.length === 0) return null;
  return (
    [...runs].sort((left, right) => {
      const a = Date.parse(left.createdAt);
      const b = Date.parse(right.createdAt);
      if (Number.isNaN(a) && Number.isNaN(b)) return 0;
      if (Number.isNaN(a)) return 1;
      if (Number.isNaN(b)) return -1;
      return b - a;
    })[0] ?? null
  );
}

/**
 * 分项 1：库存账实核对结果（7.1.2.2）。
 *
 * <p>结论**由上游给**（{@code status} = CONSISTENT / DISCREPANT），前端不重算差额。
 */
export function buildReconciliationCheck(
  outcome: SourceOutcome<ReconciliationRunPage>,
): ConsistencyCheck {
  const spec = CONSISTENCY_SOURCE_SPECS.RECONCILIATION;
  const base = {
    key: spec.key,
    label: spec.label,
    owner: spec.owner,
    ownerModule: spec.ownerModule,
    action: spec.action,
    missing: spec.missing,
  } as const;

  if (outcome.availability === 'NOT_PERMITTED') {
    return {
      ...base,
      verdict: UNDETERMINED,
      availability: 'NOT_PERMITTED',
      reason: '你的角色没有 07 库存的读权限，这一项**没有去读** —— 不是「账实一致」。',
      evidence: [],
    };
  }
  if (outcome.availability === 'UNREACHABLE') {
    return {
      ...base,
      verdict: UNDETERMINED,
      availability: 'UNREACHABLE',
      reason: `07 库存读不到账实核对结论（${outcome.failure ?? '服务不可达'}）—— 这一项**判不了**，不是「账实一致」。`,
      evidence: [],
    };
  }
  if (outcome.availability !== 'AVAILABLE') {
    return {
      ...base,
      verdict: UNDETERMINED,
      availability: outcome.availability,
      reason: '账实核对结论不可得 —— 这一项**判不了**。',
      evidence: [],
    };
  }

  const run = latestRun(outcome.value?.runs);
  if (!run) {
    return {
      ...base,
      verdict: UNDETERMINED,
      availability: 'NO_DATA',
      reason:
        '这个租户**还没有跑过一次账实核对** —— 「没核过」与「核过了、账实一致」是两件事，这里是前者。',
      evidence: ['账实核对批次总数：0'],
    };
  }

  const evidence = [
    `核对范围：${run.scopeLabel}（${run.scopeType}）`,
    `核对区间：${formatTimestamp(run.windowFrom)} ~ ${formatTimestamp(run.windowTo)}`,
    `结论落库时刻：${formatTimestamp(run.createdAt)}`,
    `核对规模：余额行 ${run.checkedBalanceCount} 条 / 分录 ${run.checkedEntryCount} 笔 / 业务单 ${run.checkedTransactionCount} 张`,
    `在手：期初 ${formatQuantity(run.openingOnHand)} → 期间变动 ${formatQuantity(run.periodOnHand)} → 期末 ${formatQuantity(run.afterWindowOnHand)}；快照 ${formatQuantity(run.snapshotOnHand)}；差额 ${formatQuantity(run.onHandDiff)}`,
    `预占：期初 ${formatQuantity(run.openingReserved)} → 期间变动 ${formatQuantity(run.periodReserved)} → 期末 ${formatQuantity(run.afterWindowReserved)}；快照 ${formatQuantity(run.snapshotReserved)}；差额 ${formatQuantity(run.reservedDiff)}`,
    `结论说明：${run.verdictNote}`,
  ];
  if (run.firstSuspectAt || run.lastSuspectAt) {
    evidence.push(
      `可疑区间：${formatTimestamp(run.firstSuspectAt)} ~ ${formatTimestamp(run.lastSuspectAt)}`,
    );
  }

  if (run.status === 'CONSISTENT') {
    return {
      ...base,
      verdict: GREEN,
      availability: 'AVAILABLE',
      reason: `最近一次核对结论：**账实一致** —— 核对了 ${run.checkedBalanceCount} 条余额行、${run.checkedEntryCount} 笔分录。`,
      evidence,
    };
  }
  return {
    ...base,
    verdict: RED,
    availability: 'AVAILABLE',
    reason: `最近一次核对结论：**存在差异** —— ${run.discrepancyCount} 条余额行对不上、${run.suspectCount} 条可疑流水；在手差额 ${formatQuantity(run.onHandDiff)}、预占差额 ${formatQuantity(run.reservedDiff)}。`,
    evidence,
  };
}

// ================================================================ 分项：库存准确率

/**
 * 分项 2：库存准确率（7 库存准确率 / 7.6.2.1 的红线判定）。
 *
 * <p>🔴 **本项的结论来自 07 库存的 {@code gate} 端点**，不是前端算的：
 * 准确率、阈值、是否阻断、阻断理由、口径依据全部是归属服务算好的，
 * 本模块只把 {@code blocked} 与 {@code blockReason} 原样暴露出来。
 */
export function buildAccuracyCheck(outcome: SourceOutcome<InventoryHealthGate>): ConsistencyCheck {
  const spec = CONSISTENCY_SOURCE_SPECS.ACCURACY;
  const base = {
    key: spec.key,
    label: spec.label,
    owner: spec.owner,
    ownerModule: spec.ownerModule,
    action: spec.action,
    missing: spec.missing,
  } as const;

  if (outcome.availability === 'NOT_PERMITTED') {
    return {
      ...base,
      verdict: UNDETERMINED,
      availability: 'NOT_PERMITTED',
      reason: '你的角色没有 07 库存的读权限，这一项**没有去读** —— 不是「准确率达标」。',
      evidence: [],
    };
  }
  if (outcome.availability === 'UNREACHABLE') {
    return {
      ...base,
      verdict: UNDETERMINED,
      availability: 'UNREACHABLE',
      reason: `07 库存读不到准确率判定（${outcome.failure ?? '服务不可达'}）—— 这一项**判不了**，不是「准确率达标」。`,
      evidence: [],
    };
  }
  if (outcome.availability !== 'AVAILABLE') {
    return {
      ...base,
      verdict: UNDETERMINED,
      availability: outcome.availability,
      reason: '库存准确率判定不可得 —— 这一项**判不了**。',
      evidence: [],
    };
  }

  const gate = outcome.value;
  if (!gate) {
    return {
      ...base,
      verdict: UNDETERMINED,
      availability: 'NO_DATA',
      reason: '没有取到准确率判定 —— 这一项**判不了**。',
      evidence: [],
    };
  }

  const evidence = [
    `判定口径：${gate.basis}`,
    `阈值：${formatPercent(gate.accuracyThreshold)}`,
    `最近一次账实核对：${formatTimestamp(gate.lastReconciledAt)}`,
    `最近一次盘点：${formatTimestamp(gate.lastStocktakenAt)}`,
    `判定时刻：${formatTimestamp(gate.evaluatedAt)}`,
  ];

  if (gate.accuracyState === 'UNAVAILABLE') {
    return {
      ...base,
      verdict: UNDETERMINED,
      availability: 'NOT_DELIVERED',
      reason:
        '库存准确率这一指标**上游未交付**（7.6.2.1 的准确率口径依赖未交付的上游），判不了 —— 不是「准确率为 0」。',
      evidence,
    };
  }
  if (gate.accuracyState === 'NO_DATA') {
    return {
      ...base,
      verdict: UNDETERMINED,
      availability: 'NO_DATA',
      reason:
        '**没有任何已落库的评估结论**，准确率判不了 —— 没有证据不等于准。归属服务已按最保守口径把结算与对账标成阻断。',
      evidence,
    };
  }

  if (gate.blocked) {
    return {
      ...base,
      verdict: RED,
      availability: 'AVAILABLE',
      reason: `准确率 ${formatPercent(gate.accuracyRate)} 低于阈值 ${formatPercent(gate.accuracyThreshold)}，账目不可信，**阻断结算与对账出结论**。${gate.blockReason}`,
      evidence,
    };
  }
  if (gate.accuracyRate === null) {
    return {
      ...base,
      verdict: UNDETERMINED,
      availability: 'AVAILABLE',
      reason: '准确率判定说「未触发阻断」，但**拿不到准确率数值** —— 这一项**判不了**。',
      evidence,
    };
  }
  return {
    ...base,
    verdict: GREEN,
    availability: 'AVAILABLE',
    reason: `准确率 ${formatPercent(gate.accuracyRate)} ≥ 阈值 ${formatPercent(gate.accuracyThreshold)}，未触发阻断。`,
    evidence,
  };
}

// ================================================================ 分项：待处理对账差异率

/**
 * 分项 3：待处理对账差异率（12.3.2 对账差异）。
 *
 * <p>🔴 **12.3.2.1 对账任务 / 12.3.2.2 差异处理 / 12.3.2.3 自动匹配规则都还没交付**
 * （台账里三行都是「未开发」），所以本仓**没有**「待处理对账差异」这个事实可读。
 *
 * <p>本分项如实报「上游未交付」，**绝不报成 0 差异、也绝不报成健康** ——
 * 「还没人做对账」与「对过了、没有差异」是两件完全不同的事。
 */
export function buildDiffRateCheck(): ConsistencyCheck {
  const spec = CONSISTENCY_SOURCE_SPECS.DIFF_RATE;
  return {
    key: spec.key,
    label: spec.label,
    owner: spec.owner,
    ownerModule: spec.ownerModule,
    verdict: UNDETERMINED,
    availability: 'NOT_DELIVERED',
    reason:
      '12.3.2 对账差异**未交付**（12.3.2.1 对账任务 / 12.3.2.2 差异处理 / 12.3.2.3 自动匹配规则都还没开发）—— 仓库里**没有**「待处理对账差异」这个事实可读，所以这一项既不是 0、也不是健康。',
    evidence: [
      '邻近但**不同口径**的事实：12.7.1.2 银行对账（银行流水 vs 我方账）—— 它不替代 12.3.2 的平台账单对账，本模块不拿它冒充。',
    ],
    action: null,
    missing: spec.missing,
  };
}

// ================================================================ 分项：三方金额差异

/**
 * 分项 4：订单/履约/财务三方金额差异。
 *
 * <p>🔴 **本仓当前答不出这个差异**，三条取证如下（都不是「读不到」，是「事实不存在 / 不可比」）：
 *
 * <ol>
 *   <li><b>订单侧可读</b>：{@code sales_order.grand_total_amount} + {@code currency_code}（09 订单）。</li>
 *   <li><b>履约侧没有金额事实</b>：{@code warehouse_outbound_order} 与
 *       {@code warehouse_outbound_order_line}（08 仓储作业）**只有数量，没有金额列、没有币种列** ——
 *       履约侧唯一带钱的列是 {@code trade_fulfillment_request.estimated_handling_amount}
 *       （作业估值，且币种列默认值写死 'USD'），它**不是**订单金额，不能拿来比。</li>
 *   <li><b>财务侧可读但接不上</b>：{@code finance_settlement_line.amount} + {@code currency}（12 财务），
 *       但它与订单之间**没有外键**，只有一个可空的 {@code external_reference}；
 *       而且订单币种与结算币种之间本仓**没有可读汇率** ——
 *       不同币种的金额直接相减，得到的「差异」是假的（1:1 兜底加出来的差异更是假的）。</li>
 * </ol>
 *
 * <p>⇒ 本分项报「不可比」，<b>不产出任何金额差异数字</b>。
 */
export function buildAmountParityCheck(): ConsistencyCheck {
  const spec = CONSISTENCY_SOURCE_SPECS.AMOUNT_PARITY;
  return {
    key: spec.key,
    label: spec.label,
    owner: spec.owner,
    ownerModule: spec.ownerModule,
    verdict: UNDETERMINED,
    availability: 'NOT_COMPARABLE',
    reason:
      '三方金额差异**判不了**：订单侧（09）与财务侧（12）各自有金额事实，但**履约侧没有订单金额** —— 出库单与出库明细只有数量、没有金额与币种；且订单币种与结算币种之间本仓没有可读汇率，**不同币种的金额绝不能直接相减**（1:1 加出来的「差异」是假的）。',
    evidence: [
      '订单侧（09 订单）：`sales_order.grand_total_amount` + `currency_code` —— 可读',
      '履约侧（08 仓储作业）：`warehouse_outbound_order` / `warehouse_outbound_order_line` —— **无金额列、无币种列**，只有数量',
      '财务侧（12 财务）：`finance_settlement_line.amount` + `currency` —— 可读，但与订单之间**没有外键**，只有可空的 `external_reference`',
      '汇率：订单币种 → 结算币种之间本仓**没有**可读汇率事实',
    ],
    action: null,
    missing: spec.missing,
  };
}

// ================================================================ 分项：映射缺失

/**
 * 分项 5：待处理映射缺失数（2.4.2.3）。
 *
 * <p>计数由归属服务给（{@code pendingTotal}），本模块不重新数一遍。
 */
export function buildMappingGapCheck(
  outcome: SourceOutcome<MappingGapSummary>,
  now: Date,
): ConsistencyCheck {
  const spec = CONSISTENCY_SOURCE_SPECS.MAPPING_GAPS;
  const base = {
    key: spec.key,
    label: spec.label,
    owner: spec.owner,
    ownerModule: spec.ownerModule,
    action: spec.action,
    missing: spec.missing,
  } as const;

  if (outcome.availability === 'NOT_PERMITTED') {
    return {
      ...base,
      verdict: UNDETERMINED,
      availability: 'NOT_PERMITTED',
      reason: '你的角色没有 02 渠道的读权限，这一项**没有去读** —— 不是「没有映射缺口」。',
      evidence: [],
    };
  }
  if (outcome.availability === 'UNREACHABLE') {
    return {
      ...base,
      verdict: UNDETERMINED,
      availability: 'UNREACHABLE',
      reason: `02 渠道读不到映射缺口队列（${outcome.failure ?? '服务不可达'}）—— 这一项**判不了**。`,
      evidence: [],
    };
  }
  if (outcome.availability !== 'AVAILABLE') {
    return {
      ...base,
      verdict: UNDETERMINED,
      availability: outcome.availability,
      reason: '映射缺口队列不可得 —— 这一项**判不了**。',
      evidence: [],
    };
  }

  const summary = outcome.value;
  if (!summary) {
    return {
      ...base,
      verdict: UNDETERMINED,
      availability: 'NO_DATA',
      reason: '没有取到映射缺口概览 —— 这一项**判不了**。',
      evidence: [],
    };
  }

  const oldest = ageDays(summary.oldestPendingAt, now);
  const evidence = [
    `在队总数：${summary.pendingTotal} 条`,
    `最老在队时刻：${formatTimestamp(summary.oldestPendingAt)}（${oldest === null ? '多久了答不出来' : `${oldest} 天前`}）`,
    `状态分布：${formatCounts(summary.byStatus)}`,
    `入队原因分布：${formatCounts(summary.byReason)}`,
    `店铺分布：${formatShopCounts(summary.byShop)}`,
    `带平台价快照：${summary.priceSnapshotCount} 条`,
    `09 订单缺映射事件接线：${summary.orderSourceWired ? '已接线' : '**未接线** —— 队列可能不完整，待处理数是下限'}`,
  ];

  if (summary.pendingTotal === 0) {
    return {
      ...base,
      verdict: GREEN,
      availability: 'AVAILABLE',
      reason: '没有待处理的映射缺口。',
      evidence,
    };
  }
  if (oldest !== null && oldest >= MAPPING_GAP_STALE_DAYS) {
    return {
      ...base,
      verdict: RED,
      availability: 'AVAILABLE',
      reason: `${summary.pendingTotal} 条待处理映射缺口，最老的一条已经压了 ${oldest} 天（本模块默认阈值 ${MAPPING_GAP_STALE_DAYS} 天）—— 缺口会让对应平台变体的订单建不出来。`,
      evidence,
    };
  }
  return {
    ...base,
    verdict: YELLOW,
    availability: 'AVAILABLE',
    reason: `${summary.pendingTotal} 条待处理映射缺口，最老的一条压了 ${oldest === null ? '（多久了答不出来）' : `${oldest} 天`}。`,
    evidence,
  };
}

/** 把 `{key,total}[]` 格式化成「A 3 条、B 1 条」。 */
export function formatCounts(
  counts: readonly { key: string; total: number }[] | null | undefined,
): string {
  if (!counts || counts.length === 0) return '（无）';
  return counts.map((entry) => `${entry.key} ${entry.total} 条`).join('，');
}

/** 把店铺分布格式化成「店铺（平台）N 条」。 */
export function formatShopCounts(
  counts:
    | readonly { shopCode: string; shopName: string; platformCode: string; total: number }[]
    | null
    | undefined,
): string {
  if (!counts || counts.length === 0) return '（无）';
  return counts
    .map((entry) => `${entry.shopName || entry.shopCode}（${entry.platformCode}）${entry.total} 条`)
    .join('，');
}

// ================================================================ 整体结论

/** 整体体检结论。 */
export interface ConsistencyConclusion {
  /** 整体结论。{@link UNDETERMINED} = **判不出**整体健康。 */
  verdict: CheckVerdict;
  /** 一句话结论。 */
  title: string;
  /** 结论的展开说明。 */
  detail: string;
  /** 结算与对账闸门（来自 07 库存的 gate 判定）：true = 不可出结论。 */
  blocked: boolean;
  /** 闸门理由（原样引用归属服务的 {@code blockReason}）。 */
  blockReason: string | null;
  /** 分项计数。 */
  counts: {
    red: number;
    yellow: number;
    green: number;
    undetermined: number;
    total: number;
  };
}

/**
 * 由分项结果与闸门判定合成**整体结论**。
 *
 * <p>🔴 **合成规则（本模块最容易做错的地方）**：
 *
 * <ol>
 *   <li>闸门（{@code gate.blocked}）优先 —— 它是归属服务自己下的「不可出结论」判定，
 *       本模块只暴露、不代它执行。</li>
 *   <li>只要有一项红，整体就是红。</li>
 *   <li>只要有一项黄，整体就是黄。</li>
 *   <li><b>只要有一项判不了，整体就判不了</b> —— 不能因为「剩下的都是绿的」就报整体健康。
 *       「三项绿 + 两项判不了」和「五项全绿」是两件事。</li>
 * </ol>
 */
export function buildConclusion(
  checks: readonly ConsistencyCheck[],
  gate: InventoryHealthGate | null,
): ConsistencyConclusion {
  const counts = {
    red: checks.filter((check) => check.verdict === RED).length,
    yellow: checks.filter((check) => check.verdict === YELLOW).length,
    green: checks.filter((check) => check.verdict === GREEN).length,
    undetermined: checks.filter((check) => check.verdict === UNDETERMINED).length,
    total: checks.length,
  };
  const undetermined = checks.filter((check) => check.verdict === UNDETERMINED);
  const red = checks.filter((check) => check.verdict === RED);
  const yellow = checks.filter((check) => check.verdict === YELLOW);

  if (gate && gate.blocked) {
    const noEvidence = gate.accuracyState !== 'AVAILABLE';
    return {
      verdict: RED,
      title: noEvidence
        ? '账不可信：**没有证据**证明账准，结算与对账不可出结论'
        : '账不可信：库存准确率低于阈值，结算与对账不可出结论',
      detail: gate.blockReason,
      blocked: true,
      blockReason: gate.blockReason,
      counts,
    };
  }
  if (red.length > 0) {
    return {
      verdict: RED,
      title: '一致性体检未通过',
      detail: `${red.length} 项不通过：${red.map((check) => check.label).join('、')}。`,
      blocked: false,
      blockReason: null,
      counts,
    };
  }
  if (yellow.length > 0) {
    return {
      verdict: YELLOW,
      title: '一致性体检有预警',
      detail: `${yellow.length} 项预警：${yellow.map((check) => check.label).join('、')}。`,
      blocked: false,
      blockReason: null,
      counts,
    };
  }
  if (undetermined.length > 0) {
    return {
      verdict: UNDETERMINED,
      title: '判不出整体结论',
      detail: `${undetermined.length} 项判不了：${undetermined.map((check) => check.label).join('、')}。判不了**不是**「没问题」—— 剩下的分项绿，只能说明剩下的分项没问题。`,
      blocked: false,
      blockReason: null,
      counts,
    };
  }
  return {
    verdict: GREEN,
    title: '一致性体检通过',
    detail: `${counts.green} 项分项全部在阈值内，未触发结算与对账的阻断。`,
    blocked: false,
    blockReason: null,
    counts,
  };
}

/** 异常清单 = 所有**不是绿**的分项（红 + 黄 + 判不了）。 */
export function buildAnomalyList(checks: readonly ConsistencyCheck[]): ConsistencyCheck[] {
  return checks
    .filter((check) => check.verdict !== GREEN)
    .sort((left, right) => verdictRank(left.verdict) - verdictRank(right.verdict));
}

/** 规则表（把阈值与判定口径逐条公布在页面上）。 */
export interface ConsistencyRuleDoc {
  code: string;
  condition: string;
  reason: string;
  source: string;
}

/** 本模块的判定规则表 —— 页面上逐条公布，让每个结论都能追到一条规则。 */
export const CONSISTENCY_RULES: readonly ConsistencyRuleDoc[] = [
  {
    code: 'RECONCILIATION.CONSISTENT',
    condition: '最近一次 7.1.2.2 账实核对结论 = CONSISTENT',
    reason: '账实一致 —— 这一项绿。',
    source: 'warehouse `inventory_reconciliation_run.status`',
  },
  {
    code: 'RECONCILIATION.DISCREPANT',
    condition: '最近一次账实核对结论 = DISCREPANT',
    reason: '账实对不上 —— 这一项红，账不可信的底线破了。',
    source: 'warehouse `inventory_reconciliation_run.status` / `discrepancy_count`',
  },
  {
    code: 'RECONCILIATION.NO_RUN',
    condition: '这个租户一笔账实核对都没有',
    reason: '判不了 —— 「没核过」不是「核过了、账实一致」。',
    source: 'warehouse `inventory_reconciliation_run`（0 行）',
  },
  {
    code: 'ACCURACY.BELOW_THRESHOLD',
    condition: '准确率 < 阈值（默认 99%）',
    reason: '红 —— 账目不可信，阻断结算与对账出结论。',
    source: 'warehouse `inventory_health_verdict.accuracy_rate` / `accuracy_threshold`',
  },
  {
    code: 'ACCURACY.NO_EVIDENCE',
    condition: '准确率状态 = NO_DATA / UNAVAILABLE（没有评估结论或上游未交付）',
    reason: '判不了 —— 没有证据不等于准；归属服务按最保守口径标成阻断。',
    source: 'warehouse `inventory_health_verdict.accuracy_state`',
  },
  {
    code: 'MAPPING_GAP.PENDING',
    condition: '待处理映射缺失数 > 0',
    reason: '黄 —— 有缺口要处理。',
    source: 'channel `listing_mapping_gap`（status = PENDING）',
  },
  {
    code: 'MAPPING_GAP.STALE',
    condition: `待处理映射缺失数 > 0 且最老一条压了 ≥ ${MAPPING_GAP_STALE_DAYS} 天`,
    reason: '红 —— 陈旧缺口会让对应平台变体的订单建不出来。',
    source: 'channel `listing_mapping_gap.enqueued_at`',
  },
  {
    code: 'DIFF_RATE.NOT_DELIVERED',
    condition: '12.3.2 对账差异未交付',
    reason: '判不了 —— 既不是 0 差异，也不是健康。',
    source: '台账 12.3.2.1 / 12.3.2.2 / 12.3.2.3 = 未开发',
  },
  {
    code: 'AMOUNT_PARITY.NOT_COMPARABLE',
    condition: '履约侧没有订单金额事实，且订单币种与结算币种之间没有可读汇率',
    reason: '判不了 —— 不同币种的金额绝不能直接相减，也不按 1:1 兜底。',
    source:
      '08 `warehouse_outbound_order`（无金额列）/ 09 `sales_order` / 12 `finance_settlement_line`',
  },
];

/** 口径说明（页面上逐条公布）。 */
export const CONSISTENCY_SEMANTICS: readonly { label: string; text: string }[] = [
  {
    label: '阈值口径',
    text: `映射缺口的「陈旧」阈值（${MAPPING_GAP_STALE_DAYS} 天）是**本模块的默认政策**，不是平台下发的口径；库存准确率的阈值（默认 99%）由 07 库存的 7.6.2.1 持有，本模块原样引用。`,
  },
  {
    label: '阻断口径',
    text: '「低于阈值应阻断结算与对账出结论」是**业务底线**。本模块是只读体检屏，只把 07 库存的判定原样暴露出来并给处置入口，**不代 12 财务结算与 7.1.2.2 对账执行阻断**（卷 01 边界：工作台只读 + 跳转）。',
  },
  {
    label: '计数口径',
    text: '概览的每个数字都由**清单的同一份集合**或归属服务给的合计算出，不存在「概览说 12 笔、清单只有 10 笔」。',
  },
  {
    label: '精度口径',
    text: '金额与数量**全部来自归属服务**（数据库 numeric(19,6) → JSON 数字），本模块只做展示，**不做任何金额或数量的再计算**，所以不存在浮点累加误差。',
  },
  {
    label: '数据范围口径',
    text: '租户来自**已认证成员关系**（`auth/session` → 各服务 `AccessService.require`），**不信任任何客户端租户头**；法人 / 店铺 / 仓库的范围由归属服务在服务端判定，前端不把范围当成可信输入往下传。',
  },
];
