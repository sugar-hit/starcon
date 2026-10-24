/**
 * 1.1.3.2 资金与回款风险的前端聚合模型（卷 01 工作台与门户）。
 *
 * <p>蓝图原文：「展示应收账龄、平台待结算金额、逾期应收、即将到期的供应商应付、在途资金占用。」
 * 输入「12 财务的应收应付账龄投影；02 同步的结算报告；07 库存货值。」
 * 输出「账龄分布图 + 逾期清单 + 待结算金额；点击进入 12 对应收款。」
 *
 * <p><b>为什么是纯前端聚合</b>：卷 01 的定位写死了「后端归属：前端聚合层 + 各服务的只读投影
 * （<b>不新增业务服务</b>）」与「不产生新的业务事实，只聚合各系统的投影」。
 * 本模块要的五类事实**全部**已在归属服务的只读投影端点里就位（见 {@link RISK_SOURCE_SPECS}），
 * 所以本模块**不新建表、不加迁移、不加端点** —— 派生值落库只会比它描述的资金更早过期。
 *
 * <p><b>六条实现纪律</b>（每条都对应本模块最容易做错的一件事）：
 *
 * <ol>
 *   <li><b>多币种绝不相加</b>：应收 / 应付 / 结算都按币种分组，
 *       本模型<b>不产出任何跨币种总额</b>。汇率不可得时也不按 1:1 兜底 ——
 *       1:1 加出来的「总额」是假的。</li>
 *   <li><b>「不可判定」≠ 0</b>：分母为 0、来源没登记、字段缺失 ⇒ {@code null}，
 *       页面上显示「—」并说明为什么，<b>绝不显示成 0</b>（假 0 会被读成「没有逾期」）。</li>
 *   <li><b>精确十进制</b>：金额加总与占比一律在缩放整数（BigInt）上做。
 *       {@code 0.3 ÷ 0.1} 在二进制浮点上是 2.9999999999999996，
 *       直接 {@code Math.floor} 会得到 2 而不是 3。</li>
 *   <li><b>计数与清单同源</b>：概览的每个数字都由清单<b>同一份集合</b>算出，
 *       不存在「概览说 12 笔、清单只有 10 笔」。</li>
 *   <li><b>数据范围服务端判定</b>：前端<b>不</b>把法人 / 店铺 / 仓库范围当成可信输入往下传，
 *       只读各服务<b>已经按范围过滤好</b>的投影。本地算出的 {@link ScopeMode}
 *       只用于「要不要去读」与「怎么措辞」，<b>不是授权</b>（服务端会独立再判一次）。</li>
 *   <li><b>只读 + 跳转</b>：工作台<b>没有</b>确认 / 催收 / 调整 / 核销任何写动作，
 *       每个数字的出口是<b>跳到归属系统</b>去处理。</li>
 * </ol>
 *
 * <p>🔴 **本文件是纯函数层**：不碰网络、不碰 React、不重算业务口径。
 */

import type {
  Access,
  FinanceAgingBucketAmount,
  FinanceAgingCurrencyTotal,
  FinanceDeferredSettlementBasisNote,
  FinanceDeferredSettlementCurrencySummary,
  FinanceDeferredSettlementSummary,
  FinancePayableCurrencySummary,
  FinancePayableUnpaidItem,
  FinancePayableUnpaidList,
  FinanceReceivableAgingDistribution,
  FinanceReceivableAgingOverduePage,
  FinanceReceivableAgingOverdueRow,
  FinanceSettlementCurrencySummary,
  FinanceSettlementPage,
  RiskProjectionPage,
  RiskProjectionRow,
} from '../../../../../packages/api-contracts/src/index';

export type {
  Access,
  FinanceAgingBucketAmount,
  FinanceAgingCurrencyTotal,
  FinanceDeferredSettlementBasisNote,
  FinanceDeferredSettlementCurrencySummary,
  FinanceDeferredSettlementSummary,
  FinancePayableCurrencySummary,
  FinancePayableUnpaidItem,
  FinancePayableUnpaidList,
  FinanceReceivableAgingDistribution,
  FinanceReceivableAgingOverduePage,
  FinanceReceivableAgingOverdueRow,
  FinanceSettlementCurrencySummary,
  FinanceSettlementPage,
  RiskProjectionPage,
  RiskProjectionRow,
};

// ================================================================ 精确十进制

/** 金额刻度（与 finance 的 {@code MONEY_SCALE} 一致）。 */
export const MONEY_SCALE = 4;

/** 数量刻度（与 DB 的 numeric(19,6) 对齐）。 */
export const QUANTITY_SCALE = 6;

/** 占比刻度（与 finance 的 {@code RATIO_SCALE} 一致）。 */
export const RATIO_SCALE = 4;

/**
 * 把一个十进制数转成**缩放整数**（BigInt）。
 *
 * <p>为什么不用 {@code Number} 直接相加：金额是 numeric(19,4)，
 * 二进制浮点存不下 0.1，`0.1 + 0.2 === 0.30000000000000004`。
 * 缩放整数上没有这个问题 —— {@code 0.3 ÷ 0.1} 在 scale 6 上正好是 3。
 *
 * @returns 缩放后的整数；输入为 null / undefined / 非数字时返回 null（「没有这个数」）
 */
export function toScaled(
  value: number | string | null | undefined,
  scale: number,
): bigint | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'number' && !Number.isFinite(value)) return null;
  // toFixed 不会产出科学计数法（1e21 以下），正好避开 JSON 里 "1e+21" 那种形态。
  const text = (typeof value === 'number' ? value.toFixed(scale) : value.trim()).trim();
  if (!text) return null;
  const match = /^([+-]?)(\d+)(?:\.(\d*))?$/.exec(text);
  if (!match) return null;
  const sign = match[1] === '-' ? -1n : 1n;
  const integer = match[2] ?? '0';
  const fraction = (match[3] ?? '').padEnd(scale, '0').slice(0, scale);
  return sign * (BigInt(integer) * 10n ** BigInt(scale) + BigInt(fraction || '0'));
}

/** 缩放整数还原成十进制数（仅用于界面展示与再参与展示层运算）。 */
export function fromScaled(value: bigint, scale: number): number {
  return Number(value) / 10 ** scale;
}

/**
 * 精确十进制求和。
 *
 * <p><b>逐项跳过 null</b>：null 是「没有这个数」，不是 0。调用方要区分
 * 「全部为 0」与「一项都取不到」时，用 {@link sumMoneyOrNull} 而不是它。
 */
export function sumScaled(
  values: readonly (number | null | undefined)[],
  scale: number,
): bigint {
  let total = 0n;
  for (const value of values) {
    const scaled = toScaled(value, scale);
    if (scaled !== null) total += scaled;
  }
  return total;
}

/**
 * 精确十进制求和，<b>全空时返回 null</b>（不可判定，不是 0）。
 *
 * <p>区别于 {@link sumMoney}：那个把「一项都取不到」当成 0，
 * 这个把它当成<b>不可判定</b> —— 页面上必须能分开。
 */
export function sumMoneyOrNull(
  values: readonly (number | null | undefined)[],
): number | null {
  const usable = values.filter(
    (value): value is number => value !== null && value !== undefined && Number.isFinite(value),
  );
  if (usable.length === 0) return null;
  return fromScaled(sumScaled(usable, MONEY_SCALE), MONEY_SCALE);
}

/** 精确十进制求和（取不到的全当 0）。 */
export function sumMoney(values: readonly (number | null | undefined)[]): number {
  return fromScaled(sumScaled(values, MONEY_SCALE), MONEY_SCALE);
}

/** 精确十进制数量求和。 */
export function sumQuantity(values: readonly (number | null | undefined)[]): number {
  return fromScaled(sumScaled(values, QUANTITY_SCALE), QUANTITY_SCALE);
}

/**
 * 精确十进制占比（金额 ÷ 金额），四舍五入到 {@link RATIO_SCALE} 位。
 *
 * @returns 占比；<b>分母为 0 或缺失时返回 null</b> —— 「没有逾期」与「占比 0」是两件事，
 *     前者答不出比例，后者答得出「0%」。分母为 0 时答不出 ⇒ null（不可判定）。
 */
export function ratioOf(
  numerator: number | null | undefined,
  denominator: number | null | undefined,
): number | null {
  const a = toScaled(numerator, MONEY_SCALE);
  const b = toScaled(denominator, MONEY_SCALE);
  if (a === null || b === null || b === 0n) return null;
  const negative = (a < 0n) !== (b < 0n);
  const absA = a < 0n ? -a : a;
  const absB = b < 0n ? -b : b;
  const factor = 10n ** BigInt(RATIO_SCALE);
  const scaledNumerator = absA * factor;
  let quotient = scaledNumerator / absB;
  const remainder = scaledNumerator % absB;
  if (remainder * 2n >= absB) quotient += 1n;
  return fromScaled(negative ? -quotient : quotient, RATIO_SCALE);
}

/** 金额格式化：去掉多余的尾随零；null 显示「—」。 */
export function formatMoney(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  const rounded = Math.round(value * 10 ** MONEY_SCALE) / 10 ** MONEY_SCALE;
  return rounded.toLocaleString('zh-CN', {
    minimumFractionDigits: 0,
    maximumFractionDigits: MONEY_SCALE,
  });
}

/** 数量格式化：去掉多余的尾随零；null 显示「—」。 */
export function formatQuantity(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  const rounded = Math.round(value * 10 ** QUANTITY_SCALE) / 10 ** QUANTITY_SCALE;
  return String(rounded);
}

/** 占比格式化：null 显示「—」（不可判定），否则按百分数展示。 */
export function formatRatio(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  return `${(value * 100).toFixed(1)}%`;
}

/** 两个 ISO 日期（YYYY-MM-DD）之间相差的天数。解析不了返回 null。 */
export function daysBetween(fromIsoDate: string, toIsoDate: string): number | null {
  const from = Date.parse(`${fromIsoDate}T00:00:00Z`);
  const to = Date.parse(`${toIsoDate}T00:00:00Z`);
  if (Number.isNaN(from) || Number.isNaN(to)) return null;
  return Math.round((to - from) / 86_400_000);
}

// ================================================================ 来源声明

/**
 * 本模块读的六个上游来源。
 *
 * <p>⚠️ **这六个端点全部已在 api-proxy 白名单里放行**（本车道实测逐条命中：
 * {@code receivable-aging/(capabilities|options|distribution|overdue|buckets)}、
 * {@code settlement(...)}、{@code payables(...|unpaid|...)}、
 * {@code deferred-settlement(...|summary|...)}、{@code inventory-risk/(capabilities|projection)}），
 * 所以本模块<b>不新增</b>白名单元素 —— 既有的兄弟模块路由一条都没动。
 */
export const RISK_SOURCE_KEYS = [
  'AGING',
  'OVERDUE',
  'SETTLEMENT',
  'PAYABLE',
  'DEFERRED',
  'INVENTORY',
] as const;

export type RiskSourceKey = (typeof RISK_SOURCE_KEYS)[number];

/** 一个上游来源的声明。 */
export interface RiskSourceSpec {
  key: RiskSourceKey;
  /** 界面上显示的名字。 */
  label: string;
  /** 归属系统（谁拥有那张事实表）。 */
  owner: string;
  /** 归属模块（蓝图 L4 编号）。 */
  ownerModule: string;
  /** 上游只读端点（相对服务前缀，查询串另计）。 */
  endpoint: string;
  /** 读权限码（取自各控制器 {@code access.require} 的逐字权限码）。 */
  permission: string;
  /** 这个来源读不到时，本模块哪一格会判不了。 */
  impact: string;
}

/** 六个来源的逐条声明。 */
export const RISK_SOURCE_SPECS: Record<RiskSourceKey, RiskSourceSpec> = {
  AGING: {
    key: 'AGING',
    label: '应收账龄',
    owner: '12 财务',
    ownerModule: '12.1.1.2 应收账龄',
    endpoint: 'finance/receivable-aging/distribution',
    permission: 'finance.receivable.read',
    impact: '账龄分布图与「应收未核销」判不了（分档金额、逾期集中度都没有）。',
  },
  OVERDUE: {
    key: 'OVERDUE',
    label: '逾期应收',
    owner: '12 财务',
    ownerModule: '12.1.1.2 应收账龄',
    endpoint: 'finance/receivable-aging/overdue',
    permission: 'finance.receivable.read',
    impact: '逾期清单与「逾期应收额」判不了。',
  },
  SETTLEMENT: {
    key: 'SETTLEMENT',
    label: '平台待结算',
    owner: '12 财务',
    ownerModule: '12.3.1.1 结算单管理',
    endpoint: 'finance/settlement',
    permission: 'finance.settlement.read',
    impact: '平台待结算金额与待到账笔数判不了。',
  },
  PAYABLE: {
    key: 'PAYABLE',
    label: '供应商应付',
    owner: '12 财务',
    ownerModule: '12.1.2.1 应付单管理',
    endpoint: 'finance/payables/unpaid',
    permission: 'finance.payable.read',
    impact: '即将到期的供应商应付与未付余额判不了。',
  },
  DEFERRED: {
    key: 'DEFERRED',
    label: '在途未结算资金',
    owner: '12 财务',
    ownerModule: '12.3.1.3 延迟结算',
    endpoint: 'finance/deferred-settlement/summary',
    permission: 'finance.deferred_settlement.read',
    impact: '在途未结算金额判不了（订单口径 / 已结算口径两套都拿不到）。',
  },
  INVENTORY: {
    key: 'INVENTORY',
    label: '在途库存',
    owner: '07 库存',
    ownerModule: '7.5.1.2 在途清单',
    endpoint: 'warehouse/inventory-risk/projection',
    permission: 'warehouse.inventory.read',
    impact: '在途库存数量判不了；库存货值本来就不可判定（见 {@link INVENTORY_VALUE_NOTE}）。',
  },
};

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
 * 取**最宽**的一条授权（ALL &gt; DEPARTMENT &gt; SELF）。
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

/** 逐个判定六个来源的可达性与生效数据范围。 */
export function sourceAvailabilities(
  access: Access | null,
): Record<RiskSourceKey, SourceAvailability> {
  const grants = access?.grants ?? null;
  const hasDepartment = Boolean(access?.departmentId);
  const out = {} as Record<RiskSourceKey, SourceAvailability>;
  for (const key of RISK_SOURCE_KEYS) {
    const permission = RISK_SOURCE_SPECS[key].permission;
    out[key] = {
      permitted: canRead(grants, permission, hasDepartment),
      scopeMode: scopeModeOf(grants, permission, hasDepartment),
    };
  }
  return out;
}

// ================================================================ 账龄分布

/** 账龄分布图的一个格子 = 一个币种 × 一个账龄档。 */
export interface AgingBucketCell {
  currency: string;
  bucketCode: string;
  bucketLabel: string;
  /** 该档未核销金额（服务端算好）。 */
  amount: number;
  /** 该档应收单张数（服务端算好）。 */
  count: number;
  /**
   * 该档占**同币种**未核销额的比例。
   *
   * <p>分母为 0（该币种一笔未核销都没有）时为 {@code null} ——
   * 「占比算不出来」与「占比 0」是两件事。
   */
  share: number | null;
}

/** 一个币种的账龄分布。 */
export interface AgingCurrency {
  currency: string;
  count: number;
  openAmount: number;
  overdueAmount: number;
  disputeAmount: number;
  disputeOverdueAmount: number;
  /** 集中度：逾期额最多的那个维度值占该币种逾期总额的比例（服务端算好）。 */
  overdueShareTop: number | null;
  topDimensionKey: string | null;
  topDimensionName: string | null;
  buckets: AgingBucketCell[];
}

/**
 * 由账龄分布报表构造**按币种分组**的账龄分布。
 *
 * <p>⚠️ **不产出跨币种合计**：{@code distribution.totals} 本身就是按币种分组的，
 * 这里只做「把服务端给的每币种档位金额对齐到生效的档位定义」这一件事。
 *
 * <p><b>缺档 = 0 是对的</b>：服务端只对**有数据**的档位发一行
 * （{@code group by currency, bucket}），所以某币种缺某一档就是「这一档没有应收」。
 * 这与「来源读不到」（整段不可判定）是两件事 —— 后者由调用方用 {@code null} 表达。
 */
export function buildAgingCurrencies(
  distribution: FinanceReceivableAgingDistribution | null | undefined,
): AgingCurrency[] {
  if (!distribution) return [];
  const defs = distribution.buckets ?? [];
  return (distribution.totals ?? []).map((total) => {
    const byCode = new Map<string, { amount: number; count: number }>();
    for (const bucket of total.buckets ?? []) {
      byCode.set(bucket.code, { amount: bucket.amount, count: bucket.count });
    }
    const buckets: AgingBucketCell[] = defs.map((def) => {
      const hit = byCode.get(def.code);
      const amount = hit ? hit.amount : 0;
      return {
        currency: total.currency,
        bucketCode: def.code,
        bucketLabel: def.label,
        amount,
        count: hit ? hit.count : 0,
        share: ratioOf(amount, total.openAmount),
      };
    });
    return {
      currency: total.currency,
      count: total.count,
      openAmount: total.openAmount,
      overdueAmount: total.overdueAmount,
      disputeAmount: total.disputeAmount,
      disputeOverdueAmount: total.disputeOverdueAmount,
      overdueShareTop: total.overdueShareTop,
      topDimensionKey: total.topDimensionKey,
      topDimensionName: total.topDimensionName,
      buckets,
    };
  });
}

// ================================================================ 逾期应收

/** 逾期清单的一行。 */
export interface OverdueRow {
  key: string;
  id: string;
  receivableNo: string;
  currency: string;
  counterpartyType: string;
  counterpartyName: string;
  shopName: string;
  dueDate: string;
  overdueDays: number;
  bucketCode: string;
  bucketLabel: string;
  amount: number;
  settledAmount: number;
  openAmount: number;
  disputeFlag: boolean;
  disputeNote: string;
  /** 出口：跳到 12 财务的应收账龄页。 */
  href: string;
}

/** 逾期清单的按币种汇总（**服务端算好**，覆盖整个筛选结果集）。 */
export interface OverdueCurrencySummary {
  currency: string;
  count: number;
  openAmount: number;
  overdueAmount: number;
  disputeAmount: number;
  disputeCount: number;
}

/** 逾期清单（按币种汇总 + 明细行）。 */
export interface OverdueView {
  rows: OverdueRow[];
  summaryByCurrency: OverdueCurrencySummary[];
  /** 逾期单总张数（服务端算好，覆盖整个筛选结果集，不是当前页）。 */
  total: number;
  /** 争议中张数（跨境应收最大的风险是平台争议冻结，单列）。 */
  disputeCount: number;
  asOfDate: string;
  /** 本页取回的张数 —— 与 {@link total} 不同时说明清单被分页截断。 */
  fetchedCount: number;
  truncated: boolean;
}

/**
 * 构造逾期清单视图。
 *
 * <p>排序：逾期天数降序 → 币种 → 单号，保证同一批数据两次刷新顺序不跳。
 */
export function buildOverdueView(
  page: FinanceReceivableAgingOverduePage | null | undefined,
): OverdueView {
  if (!page) {
    return {
      rows: [],
      summaryByCurrency: [],
      total: 0,
      disputeCount: 0,
      asOfDate: '',
      fetchedCount: 0,
      truncated: false,
    };
  }
  const rows: OverdueRow[] = (page.items ?? []).map((item) => ({
    key: item.id,
    id: item.id,
    receivableNo: item.receivableNo,
    currency: item.currency,
    counterpartyType: item.counterpartyType,
    counterpartyName: item.counterpartyName,
    shopName: item.shopName,
    dueDate: item.dueDate,
    overdueDays: item.overdueDays,
    bucketCode: item.bucketCode,
    bucketLabel: item.bucketLabel,
    amount: item.amount,
    settledAmount: item.settledAmount,
    openAmount: item.openAmount,
    disputeFlag: item.disputeFlag,
    disputeNote: item.disputeNote,
    href: `/finance/aging?receivableNo=${encodeURIComponent(item.receivableNo)}`,
  }));
  rows.sort((a, b) => {
    if (b.overdueDays !== a.overdueDays) return b.overdueDays - a.overdueDays;
    if (a.currency !== b.currency) return a.currency < b.currency ? -1 : 1;
    return a.receivableNo < b.receivableNo ? -1 : a.receivableNo > b.receivableNo ? 1 : 0;
  });
  const fetchedCount = rows.length;
  return {
    rows,
    summaryByCurrency: (page.summaryByCurrency ?? []).map((summary) => ({
      currency: summary.currency,
      count: summary.count,
      openAmount: summary.openAmount,
      overdueAmount: summary.overdueAmount,
      disputeAmount: summary.disputeAmount,
      disputeCount: summary.disputeCount,
    })),
    total: page.total,
    disputeCount: page.disputeCount,
    asOfDate: page.asOfDate,
    fetchedCount,
    truncated: fetchedCount < page.total,
  };
}

// ================================================================ 平台待结算

/** 一个币种的待结算情况。 */
export interface PendingSettlementCurrency {
  currency: string;
  /** 结算金额合计（待到账 + 部分到账两档，服务端算好）。 */
  settlementAmount: number;
  /** 已到账金额合计（服务端算好）。 */
  receivedAmount: number;
  /** <b>未到账金额</b> = 结算金额 − 已到账金额（精确十进制）。 */
  unsettledAmount: number;
  /** 待到账（整笔未到）张数。 */
  pendingCount: number;
  /** 部分到账张数。 */
  partialCount: number;
  /** 两档合计张数。 */
  count: number;
}

/**
 * 平台待结算的按币种视图。
 *
 * <p>⚠️ **为什么是两次请求**：结算单的待结算口径是「待到账（{@code PENDING}）+
 * 部分到账（{@code PARTIAL}）」两档，而结算单列表的 {@code status} 过滤只接受<b>一个</b>取值。
 * 所以本页读两次（各带一个 status），再<b>按币种</b>合并 ——
 * 同一个币种内相加是合法的，<b>跨币种相加不是</b>。
 *
 * <p>合并时只加**同币种**的数；两档里的结算金额都是服务端算好的 scale 4 金额，
 * 相加在缩放整数上做，不丢精度。
 */
export function buildPendingSettlement(
  pending: FinanceSettlementPage | null | undefined,
  partial: FinanceSettlementPage | null | undefined,
): PendingSettlementCurrency[] {
  const byCurrency = new Map<
    string,
    { settlementAmount: number; receivedAmount: number; pendingCount: number; partialCount: number }
  >();
  const add = (
    summary: FinanceSettlementCurrencySummary,
    pendingCount: number,
    partialCount: number,
  ) => {
    const bucket = byCurrency.get(summary.currency) ?? {
      settlementAmount: 0,
      receivedAmount: 0,
      pendingCount: 0,
      partialCount: 0,
    };
    bucket.settlementAmount = sumMoney([
      bucket.settlementAmount,
      summary.settlementAmount,
    ]);
    bucket.receivedAmount = sumMoney([bucket.receivedAmount, summary.receivedAmount]);
    bucket.pendingCount += pendingCount;
    bucket.partialCount += partialCount;
    byCurrency.set(summary.currency, bucket);
  };
  for (const summary of pending?.summaryByCurrency ?? []) {
    add(summary, summary.pendingCount, 0);
  }
  for (const summary of partial?.summaryByCurrency ?? []) {
    add(summary, 0, summary.pendingCount);
  }
  return [...byCurrency.entries()]
    .sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))
    .map(([currency, bucket]) => ({
      currency,
      settlementAmount: bucket.settlementAmount,
      receivedAmount: bucket.receivedAmount,
      unsettledAmount: sumMoney([
        bucket.settlementAmount,
        -bucket.receivedAmount,
      ]),
      pendingCount: bucket.pendingCount,
      partialCount: bucket.partialCount,
      count: bucket.pendingCount + bucket.partialCount,
    }));
}

// ================================================================ 即将到期的供应商应付

/** 应付的紧急档。 */
export type PayableUrgency = 'OVERDUE' | 'DUE_SOON' | 'LATER';

/** 应付紧急档的中文标签。 */
export const PAYABLE_URGENCY_LABELS: Record<PayableUrgency, string> = {
  OVERDUE: '已逾期',
  DUE_SOON: '即将到期',
  LATER: '尚未到期',
};

/** 默认的「即将到期」窗口（天）。界面上可调，且**始终显式展示**。 */
export const DEFAULT_DUE_HORIZON_DAYS = 7;

/** 界面上可选的「即将到期」窗口。 */
export const DUE_HORIZON_OPTIONS = [3, 7, 15, 30] as const;

/** 未付清单里的一行（归一后）。 */
export interface UpcomingPayableRow {
  key: string;
  payableId: string;
  payableNo: string;
  source: string;
  counterpartyType: string;
  counterpartyName: string;
  currency: string;
  amount: number;
  paidAmount: number;
  unpaidAmount: number;
  dueDate: string;
  /** 已逾期天数（> 0 即已逾期）。 */
  overdueDays: number;
  /** 距到期天数：今天到期 = 0，未来为正，已过期为负。 */
  daysToDue: number | null;
  status: string;
  urgency: PayableUrgency;
  /** 出口：跳到 12 财务的应付单页。 */
  href: string;
}

/** 应付视图（按币种汇总 + 明细行）。 */
export interface UpcomingPayableView {
  rows: UpcomingPayableRow[];
  summaryByCurrency: {
    currency: string;
    count: number;
    unpaidAmount: number;
    overdueCount: number;
    overdueAmount: number;
  }[];
  /** 已逾期张数（**无论窗口多长都计入** —— 逾期的永远是风险）。 */
  overdueCount: number;
  /** 落在窗口内的「即将到期」张数。 */
  dueSoonCount: number;
  /** 未付清单总张数（服务端算好，整份未付清单，不是本页筛选后的）。 */
  totalOpenCount: number;
  asOfDate: string;
}

/**
 * 构造「即将到期的供应商应付」视图。
 *
 * <p>⚠️ **计数与清单同源**：按币种的未付余额是**本页这份集合**算出来的，
 * 不是服务端 {@code summaryByCurrency}（那个覆盖全部未付单，含「尚未到期」的）。
 * 服务端的汇总另存在 {@link UpcomingPayableView.totalOpenCount} 里做对照。
 *
 * <p>⚠️ **逾期恒计入**：{@code daysOverdue > 0} 的单子无论窗口设多少天都在集合里 ——
 * 窗口只管「未来多久之内到期」，管不了「已经晚了多久」。
 */
export function buildUpcomingPayables(
  list: FinancePayableUnpaidList | null | undefined,
  horizonDays: number = DEFAULT_DUE_HORIZON_DAYS,
  counterpartyType: string = 'SUPPLIER',
): UpcomingPayableView {
  if (!list) {
    return {
      rows: [],
      summaryByCurrency: [],
      overdueCount: 0,
      dueSoonCount: 0,
      totalOpenCount: 0,
      asOfDate: '',
    };
  }
  const asOfDate = list.asOfDate;
  const all = list.items ?? [];
  const rows: UpcomingPayableRow[] = [];
  for (const item of all) {
    if (counterpartyType && item.counterpartyType !== counterpartyType) continue;
    const daysToDue = daysBetween(asOfDate, item.dueDate);
    const urgency: PayableUrgency =
      item.daysOverdue > 0
        ? 'OVERDUE'
        : daysToDue !== null && daysToDue <= horizonDays
          ? 'DUE_SOON'
          : 'LATER';
    if (urgency === 'LATER') continue;
    rows.push({
      key: item.payableId,
      payableId: item.payableId,
      payableNo: item.payableNo,
      source: item.source,
      counterpartyType: item.counterpartyType,
      counterpartyName: item.counterpartyName,
      currency: item.currency,
      amount: item.amount,
      paidAmount: item.paidAmount,
      unpaidAmount: item.unpaidAmount,
      dueDate: item.dueDate,
      overdueDays: item.daysOverdue,
      daysToDue,
      status: item.status,
      urgency,
      href: `/finance/payables?payableNo=${encodeURIComponent(item.payableNo)}`,
    });
  }
  // 排序：逾期最久的在前 → 即将到期的按到期日近的在前 → 币种 → 单号。
  rows.sort((a, b) => {
    if (a.urgency !== b.urgency) return a.urgency === 'OVERDUE' ? -1 : 1;
    const aDays = a.daysToDue ?? Number.MAX_SAFE_INTEGER;
    const bDays = b.daysToDue ?? Number.MAX_SAFE_INTEGER;
    if (aDays !== bDays) return aDays - bDays;
    if (a.currency !== b.currency) return a.currency < b.currency ? -1 : 1;
    return a.payableNo < b.payableNo ? -1 : a.payableNo > b.payableNo ? 1 : 0;
  });

  const byCurrency = new Map<
    string,
    { count: number; unpaidAmount: number; overdueCount: number; overdueAmount: number }
  >();
  let overdueCount = 0;
  let dueSoonCount = 0;
  for (const row of rows) {
    const bucket = byCurrency.get(row.currency) ?? {
      count: 0,
      unpaidAmount: 0,
      overdueCount: 0,
      overdueAmount: 0,
    };
    bucket.count += 1;
    bucket.unpaidAmount = sumMoney([bucket.unpaidAmount, row.unpaidAmount]);
    if (row.urgency === 'OVERDUE') {
      bucket.overdueCount += 1;
      bucket.overdueAmount = sumMoney([bucket.overdueAmount, row.unpaidAmount]);
      overdueCount += 1;
    } else {
      dueSoonCount += 1;
    }
    byCurrency.set(row.currency, bucket);
  }

  return {
    rows,
    summaryByCurrency: [...byCurrency.entries()]
      .sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))
      .map(([currency, bucket]) => ({ currency, ...bucket })),
    overdueCount,
    dueSoonCount,
    totalOpenCount: list.itemCount,
    asOfDate,
  };
}

// ================================================================ 在途资金占用

/**
 * 库存货值为什么恒为「不可判定」。
 *
 * <p>🔴 **全库没有库存成本列**：warehouse 与 catalog 两侧的迁移与 {@code db/schemas}
 * 里都只有<b>报价</b>（{@code supplier_quote_price_tier}），那是采购报价不是库存计价，
 * 而且属另一个服务的跨服务引用。12 财务自己的资金分析模块（12.7.2.1）也把
 * 「存货周转天数」恒置为 {@code null} 并写明「存货不在本服务」。
 *
 * <p>用采购报价冒充库存成本会把账面资产算错，比不给更糟 ——
 * 所以这里给<b>数量</b>口径的完整拆分，<b>金额口径显式报不可用并说明原因</b>。
 */
export const INVENTORY_VALUE_NOTE =
  '库存货值不可判定：warehouse 与 catalog 全库都没有库存成本列（只有采购报价，不是库存计价），' +
  '12 财务的资金分析也把存货周转天数恒置为 null。在途资金占用这里只给数量口径，金额口径如实报不可用。';

/** 在途库存的数量口径。 */
export interface InTransitGoods {
  /** 在途总数量。**已截断时为 null**（只取回了一部分，和不是「总量」）。 */
  totalQuantity: number | null;
  firstMileQuantity: number | null;
  purchaseQuantity: number | null;
  transferQuantity: number | null;
  /** 在途腿数。已截断时为 null。 */
  legCount: number | null;
  /** 取回的行数。 */
  fetchedRows: number;
  /** 全量行数（服务端算好）。 */
  totalRows: number;
  /** 是否被翻页上限截断。 */
  truncated: boolean;
  /** 截断说明（未截断时为空串）。 */
  truncationNote: string;
  /**
   * 库存货值：**恒为 null（不可判定）**。
   *
   * <p>类型写成 {@code null} 而不是 {@code number | null} 是刻意的 ——
   * 让任何试图给它填数的代码在编译期就过不去。
   */
  amount: null;
  /** 金额口径为什么不可判定。 */
  amountNote: string;
}

/**
 * 构造在途库存的数量口径。
 *
 * <p>⚠️ **截断时总量为 null**：投影端点是分页的（每页上限 500 行）。
 * 只取回了一部分却报一个「总量」，就是在报一个假的数 ——
 * 所以截断时总量、分类量、腿数**一律 null（不可判定）**，并如实报出取回了多少。
 */
export function buildInTransitGoods(
  page: RiskProjectionPage | null | undefined,
): InTransitGoods {
  if (!page) {
    return {
      totalQuantity: null,
      firstMileQuantity: null,
      purchaseQuantity: null,
      transferQuantity: null,
      legCount: null,
      fetchedRows: 0,
      totalRows: 0,
      truncated: false,
      truncationNote: '',
      amount: null,
      amountNote: INVENTORY_VALUE_NOTE,
    };
  }
  const rows = page.rows ?? [];
  const truncated = rows.length < page.total;
  const quantity = (pick: (row: RiskProjectionRow) => number): number | null =>
    truncated ? null : sumQuantity(rows.map(pick));
  return {
    totalQuantity: quantity((row) => row.inTransitQuantity),
    firstMileQuantity: quantity((row) => row.inTransitFirstMileQuantity),
    purchaseQuantity: quantity((row) => row.inTransitPurchaseQuantity),
    transferQuantity: quantity((row) => row.inTransitTransferQuantity),
    legCount: truncated ? null : rows.reduce((acc, row) => acc + row.inTransitLegCount, 0),
    fetchedRows: rows.length,
    totalRows: page.total,
    truncated,
    truncationNote: truncated
      ? `只取回前 ${rows.length} 条（共 ${page.total} 条），数量口径不可判定 —— 报一个部分和会让人以为那是总量。`
      : '',
    amount: null,
    amountNote: INVENTORY_VALUE_NOTE,
  };
}

/** 在途未结算资金（钱）的按币种口径 —— 服务端算好，本模块不重算。 */
export interface InTransitMoneyCurrency {
  currency: string;
  /** 订单口径（平台订单总额，毛额）。 */
  orderBasisAmount: number;
  /** 已结算口径（已到账结算单里的销售额行合计）。 */
  settledBasisAmount: number;
  /** 在途未结算金额 = max(订单口径 − 已结算口径, 0)。 */
  inTransitAmount: number;
  /** 口径基数差（服务端如实报出，不藏）。 */
  basisGapAmount: number;
  count: number;
  pendingCount: number;
  settledCount: number;
  overdueCount: number;
}

/**
 * 构造在途未结算资金的按币种视图。
 *
 * <p>⚠️ **两套口径原样透传，不合并成一个数**：订单口径与已结算口径的基数不同
 * （毛额 vs 结算销售额行），差额里含税与运费等尚未单独结算的部分。
 * 本模块<b>不替下游做口径取舍</b>，只把两套口径分币种同时给出。
 */
export function buildInTransitMoney(
  summary: FinanceDeferredSettlementSummary | null | undefined,
): InTransitMoneyCurrency[] {
  if (!summary) return [];
  return (summary.summaryByCurrency ?? []).map((entry) => ({
    currency: entry.currency,
    orderBasisAmount: entry.orderBasisAmount,
    settledBasisAmount: entry.settledBasisAmount,
    inTransitAmount: entry.inTransitAmount,
    basisGapAmount: entry.basisGapAmount,
    count: entry.count,
    pendingCount: entry.pendingCount,
    settledCount: entry.settledCount,
    overdueCount: entry.overdueCount,
  }));
}

/** 在途资金占用的口径定义（随数据一起返回 —— 脱离口径的金额没人说得清是什么）。 */
export interface InTransitBasisNote {
  orderBasis: string;
  settledBasis: string;
  inTransitBasis: string;
  basisDifference: string;
  currencyPolicy: string;
}

/** 取在途资金的口径定义。 */
export function inTransitBasisNote(
  summary: FinanceDeferredSettlementSummary | null | undefined,
): InTransitBasisNote | null {
  if (!summary?.basis) return null;
  return summary.basis;
}

// ================================================================ 汇总概览

/**
 * 一个币种的资金与回款风险概览。
 *
 * <p>⚠️ **本页不产出跨币种总额**：这个类型里<b>没有</b>任何「总计」字段。
 * 想看总额的人必须自己在同一币种内加 —— 加错币种是本模块最贵的错误，
 * 所以类型层面就不给它出口。
 */
export interface CurrencyRiskSummary {
  currency: string;
  /** 应收未核销额（12 财务账龄投影）。来源不可达时为 null。 */
  receivableOpenAmount: number | null;
  /** 逾期应收额（12 财务账龄投影）。来源不可达时为 null。 */
  receivableOverdueAmount: number | null;
  /** 逾期应收张数（12 财务账龄投影）。来源不可达时为 null。 */
  overdueCount: number | null;
  /** 平台待结算（未到账）金额。来源不可达时为 null。 */
  pendingSettlementAmount: number | null;
  /** 即将到期 + 已逾期的供应商应付未付余额。来源不可达时为 null。 */
  payableUnpaidAmount: number | null;
  /** 在途未结算资金（钱）。来源不可达时为 null。 */
  inTransitMoneyAmount: number | null;
}

/**
 * 按币种汇总本模块的五类数字。
 *
 * <p>⚠️ **每一格都来自它自己的那一份清单**（账龄分布 / 逾期清单 / 待结算 / 应付未付清单 /
 * 在途资金统计），所以概览与清单天然同源。
 *
 * <p>⚠️ **来源不可达时是 null，不是 0**：0 会被读成「这个币种没有逾期应收」。
 */
export function buildCurrencySummaries(
  aging: AgingCurrency[],
  overdue: OverdueView,
  pendingSettlement: PendingSettlementCurrency[],
  payables: UpcomingPayableView,
  inTransitMoney: InTransitMoneyCurrency[],
  available: Record<RiskSourceKey, boolean>,
): CurrencyRiskSummary[] {
  const currencies = new Set<string>();
  for (const item of aging) currencies.add(item.currency);
  for (const item of overdue.summaryByCurrency) currencies.add(item.currency);
  for (const item of pendingSettlement) currencies.add(item.currency);
  for (const item of payables.summaryByCurrency) currencies.add(item.currency);
  for (const item of inTransitMoney) currencies.add(item.currency);

  return [...currencies].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).map((currency) => {
    const agingHit = aging.find((item) => item.currency === currency) ?? null;
    const overdueHit =
      overdue.summaryByCurrency.find((item) => item.currency === currency) ?? null;
    const settlementHit =
      pendingSettlement.find((item) => item.currency === currency) ?? null;
    const payableHit =
      payables.summaryByCurrency.find((item) => item.currency === currency) ?? null;
    const transitHit = inTransitMoney.find((item) => item.currency === currency) ?? null;

    return {
      currency,
      receivableOpenAmount: available.AGING
        ? (agingHit?.openAmount ?? 0)
        : null,
      receivableOverdueAmount: available.OVERDUE
        ? (overdueHit?.overdueAmount ?? 0)
        : null,
      overdueCount: available.OVERDUE ? (overdueHit?.count ?? 0) : null,
      pendingSettlementAmount: available.SETTLEMENT
        ? (settlementHit?.unsettledAmount ?? 0)
        : null,
      payableUnpaidAmount: available.PAYABLE
        ? (payableHit?.unpaidAmount ?? 0)
        : null,
      inTransitMoneyAmount: available.DEFERRED
        ? (transitHit?.inTransitAmount ?? 0)
        : null,
    };
  });
}

// ================================================================ 明细文案

/**
 * 账龄分布图的口径说明。
 *
 * <p>蓝图要求「任何在此处出现的指标都必须能点进去看到明细，且明细口径与此处一致」——
 * 所以说明里写的每一句都能在清单的同一行找到对应的数。
 */
export function describeAging(currency: AgingCurrency): string[] {
  return [
    `未核销应收 ${formatMoney(currency.openAmount)}（${currency.count} 笔）`,
    `其中逾期 ${formatMoney(currency.overdueAmount)}，争议中 ${formatMoney(currency.disputeAmount)}（其中已逾期 ${formatMoney(currency.disputeOverdueAmount)}）`,
    currency.overdueShareTop !== null && currency.topDimensionName
      ? `逾期集中度 ${formatRatio(currency.overdueShareTop)}（集中在 ${currency.topDimensionName}）`
      : '逾期集中度：不可判定（该币种没有逾期额，占比的分母是 0）',
    ...currency.buckets.map(
      (bucket) =>
        `${bucket.bucketLabel} ${formatMoney(bucket.amount)}（${bucket.count} 笔，占同币种未核销额 ${formatRatio(bucket.share)}）`,
    ),
  ];
}

/** 逾期清单一行的口径说明。 */
export function describeOverdue(row: OverdueRow): string[] {
  return [
    `未核销 ${formatMoney(row.openAmount)}（原额 ${formatMoney(row.amount)} − 已核销 ${formatMoney(row.settledAmount)}）`,
    `到期日 ${row.dueDate}，已逾期 ${row.overdueDays} 天（${row.bucketLabel}）`,
    `往来对象 ${row.counterpartyName}（${row.counterpartyType}）· 店铺 ${row.shopName || '—'}`,
    row.disputeFlag
      ? `争议中：${row.disputeNote || '上游标记了争议，未给说明'} —— 争议冻结的金额单列，不混在普通逾期里。`
      : '未标记争议',
  ];
}

/** 应付一行的口径说明。 */
export function describePayable(row: UpcomingPayableRow, horizonDays: number): string[] {
  return [
    `未付余额 ${formatMoney(row.unpaidAmount)}（原额 ${formatMoney(row.amount)} − 已付 ${formatMoney(row.paidAmount)}）`,
    `到期日 ${row.dueDate}，${
      row.urgency === 'OVERDUE'
        ? `已逾期 ${row.overdueDays} 天`
        : row.daysToDue === null
          ? '距到期天数判不了'
          : row.daysToDue === 0
            ? '今天到期'
            : `还有 ${row.daysToDue} 天到期`
    }`,
    `应付对象 ${row.counterpartyName}（${row.counterpartyType}）· 来源 ${row.source} · 状态 ${row.status}`,
    `归入「即将到期」的口径：到期日在 ${horizonDays} 天以内；已逾期的恒计入，不受窗口影响。`,
  ];
}

/** 在途资金的币种口径说明。 */
export function describeInTransitMoney(
  entry: InTransitMoneyCurrency,
  basis: InTransitBasisNote | null,
): string[] {
  const lines = [
    `在途未结算 ${formatMoney(entry.inTransitAmount)}（订单口径 ${formatMoney(entry.orderBasisAmount)} − 已结算口径 ${formatMoney(entry.settledBasisAmount)}）`,
    `共 ${entry.count} 笔：钱未到账 ${entry.pendingCount} 笔，已到账 ${entry.settledCount} 笔，预计到账时间已过仍未到账 ${entry.overdueCount} 笔`,
  ];
  if (entry.basisGapAmount !== 0) {
    lines.push(
      `口径基数差 ${formatMoney(entry.basisGapAmount)}（订单口径 − 已结算口径 − 在途未结算）—— 这一格如实报出，不藏。`,
    );
  }
  if (basis) {
    lines.push(`币种政策：${basis.currencyPolicy}`);
  }
  return lines;
}

/** 在途库存的口径说明。 */
export function describeInTransitGoods(goods: InTransitGoods): string[] {
  const lines: string[] = [];
  if (goods.truncated) {
    lines.push(goods.truncationNote);
  } else {
    lines.push(
      `在途数量合计 ${formatQuantity(goods.totalQuantity)}（头程 ${formatQuantity(goods.firstMileQuantity)} / 采购 ${formatQuantity(goods.purchaseQuantity)} / 调拨 ${formatQuantity(goods.transferQuantity)}，${goods.legCount ?? 0} 条腿）`,
    );
  }
  lines.push(`库存货值：${goods.amountNote}`);
  return lines;
}
