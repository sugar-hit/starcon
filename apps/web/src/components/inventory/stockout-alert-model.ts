import type {
  AlertRecommendation,
  StarvationAlertPage,
  StarvationAlertRow,
  StockoutAlertActionResult,
  StockoutAlertCapabilities,
  StockoutAlertPage,
  StockoutAlertResolutionInput,
  StockoutAlertRow,
  StockoutEvaluationRequest,
  StockoutEvaluationResult,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 7.8.2.2 缺货与饥饿预警前端模型。
 *
 * <p>蓝图：「识别因分摊/优先级导致的店铺"饥饿"（长期分不到货、影响绩效），以及真正的全网缺货，区分两者给出不同处置建议。输入：分摊结果；店铺销售与绩效；总可用量。输出：饥饿店铺清单 + 缺货清单 + 建议（调优先级/调保留/补货）。」
 *
 * <p><b>界面纪律</b>：
 *
 * <ul>
 *   <li><b>两张清单分开渲染，绝不合并</b>。饥饿清单的处置是调优先级 / 调保底，
 *       缺货清单的处置是补货 / 调拨 —— 放在一起就会有人把该补货的当成该调保底的。
 *       所以前端也是两个端点、两套状态枚举、两套文案。
 *   <li><b>「不知道」必须一眼看得出，且绝不能显示成 0</b>。没有销量登记 ⇒
 *       {@code demandUnits} 为 null；账本里查不到库存记录 ⇒ {@code availableQuantity} 为 null。
 *       两者都渲染成「—」+ 灰字「尚未回填」，**不渲染成 0** ——
 *       0 意味着「一件都没有」，而「查不到」意味着「该去查」。
 *   <li><b>建议是结构，不是文案</b>：界面上把「目标对象 / 当前值 / 建议值 / 理由」
 *       分开显示，运营照着就能去 7.8.1.1 / 7.8.1.2 / 6.2.1.2 落地。
 *   <li><b>未交付的上游写在页面上</b>：店铺绩效（17）与补货（6.2.1.2）都没交付，
 *       所以「对绩效的影响」一律显示「不知道」，补货建议标着「尚未交付」。
 * </ul>
 */

// ------------------------------------------------------------ 接口

/** 饥饿清单查询条件。与后端 `StarvationAlertQuery` 一一对应。 */
export interface StarvationListParams {
  skuId?: string | undefined;
  referenceId?: string | undefined;
  policyId?: string | undefined;
  shopOperatingMode?: string | undefined;
  scope?: string | undefined;
  severity?: string | undefined;
  lifecycle?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 缺货清单查询条件。与后端 `StockoutAlertQuery` 一一对应。 */
export interface StockoutListParams {
  skuId?: string | undefined;
  warehouseId?: string | undefined;
  scope?: string | undefined;
  severity?: string | undefined;
  lifecycle?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/** 能力清单：两类判定的差别、「不知道」的语义、建议的边界、未交付的上游。 */
export function fetchAlertCapabilities(): Promise<StockoutAlertCapabilities> {
  return api<StockoutAlertCapabilities>('warehouse/inventory/stockout-alerts/capabilities');
}

/** 店铺饥饿清单（真分页）。 */
export function fetchStarvations(
  params: StarvationListParams,
): Promise<StarvationAlertPage> {
  return api<StarvationAlertPage>(
    `warehouse/inventory/stockout-alerts/starvations${query(params)}`,
  );
}

/** 缺货清单（真分页）。 */
export function fetchStockouts(params: StockoutListParams): Promise<StockoutAlertPage> {
  return api<StockoutAlertPage>(`warehouse/inventory/stockout-alerts/stockouts${query(params)}`);
}

/**
 * 评估一次：分别判定「缺货」与「饥饿」两类问题，各自落各自的表。
 *
 * <p>需要写权限 —— 它会改变「哪些问题还没人管」这个事实。
 */
export function evaluateAlerts(
  request: StockoutEvaluationRequest,
): Promise<StockoutEvaluationResult> {
  return api<StockoutEvaluationResult>(
    'warehouse/inventory/stockout-alerts/evaluate',
    'POST',
    request,
  );
}

/** 认领一条预警（有人接了这件事）。 */
export function acknowledgeAlert(
  kind: 'starvations' | 'stockouts',
  id: string,
): Promise<StockoutAlertActionResult> {
  return api<StockoutAlertActionResult>(
    `warehouse/inventory/stockout-alerts/${kind}/${id}/acknowledge`,
    'POST',
  );
}

/** 处置一条预警。处置说明必填 —— 没有说明的记录在复盘时与「没发生过」一模一样。 */
export function resolveAlert(
  kind: 'starvations' | 'stockouts',
  id: string,
  input: StockoutAlertResolutionInput,
): Promise<StockoutAlertActionResult> {
  return api<StockoutAlertActionResult>(
    `warehouse/inventory/stockout-alerts/${kind}/${id}/resolve`,
    'POST',
    input,
  );
}

function query(params: object): string {
  const parts = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== null && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

// ------------------------------------------------------------ 展示

/**
 * codegen 约定：只要 schema 里有任何一个可空字段，整个类型别名就会生成成 `T | null`，
 * 所以列表元素也要先过这道关。
 *
 * <p>刻意写成**类型谓词**：写成 `<T>(v: T | null) => T | null` 时
 * `Array.filter(notNull)` 不会收窄元素类型。
 */
export function notNull<T>(v: T | null | undefined): v is T {
  return v !== null && v !== undefined;
}

/**
 * 数量口径：DB 是 numeric(19,6)，而**件数必须是整件**，所以这里不补小数位。
 *
 * <p>null 显示成「—」而不是 0：null 的含义是「本地没有这个数」，
 * 渲染成 0 就是把「不知道」说成了「一件都没有」。
 */
export function qty(v: number | string | null | undefined): string {
  if (v === null || v === undefined) return '—';
  const n = typeof v === 'string' ? Number(v) : v;
  if (Number.isNaN(n)) return '—';
  return n.toLocaleString('zh-CN');
}

/** 比例：库里存 0.2，界面显示 20%。 */
export function percent(v: number | string | null | undefined): string {
  if (v === null || v === undefined) return '—';
  const n = typeof v === 'string' ? Number(v) : v;
  if (Number.isNaN(n)) return '—';
  return `${(n * 100).toLocaleString('zh-CN', { maximumFractionDigits: 4 })}%`;
}

/** 时刻文案。null 显示成「—」。 */
export function timeText(v: string | null | undefined): string {
  if (!v) return '—';
  const d = new Date(v);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString('zh-CN');
}

/** 统一的错误文案：把 ApiError 的状态码与后端给的原因都带出来。 */
export function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    return e.status ? `请求失败（${e.status}）：${e.message}` : e.message;
  }
  return e instanceof Error ? e.message : String(e);
}

/** 店铺饥饿的状态。UNKNOWN_DEMAND 是「不知道」，不是「不缺货」。 */
export const starvationStateLabels: Record<string, string> = {
  STARVED: '长期分不到货',
  UNDER_SUPPLIED: '供给不足',
  UNKNOWN_DEMAND: '不知道：没登记销量',
  HEALTHY: '供给正常',
};

/** 缺货的状态。仓级断货与全网缺货是两个取值 —— 处置不同。 */
export const stockoutStateLabels: Record<string, string> = {
  NO_LEDGER_RECORD: '查不到库存记录',
  NETWORK_STOCKED_OUT: '全网缺货',
  WAREHOUSE_STOCKED_OUT: '该仓断货',
  AT_RISK: '覆盖不足',
  UNKNOWN_DEMAND: '不知道：没登记销量',
  HEALTHY: '供给正常',
};

/** 严重度。 */
export const severityLabels: Record<string, string> = {
  CRITICAL: '严重',
  WARNING: '警告',
  INFO: '提示',
};

/** 处置状态。 */
export const lifecycleLabels: Record<string, string> = {
  OPEN: '待处理',
  ACKNOWLEDGED: '已认领',
  RESOLVED: '已处置',
};

/** 饥饿清单的筛选口径。 */
export const starvationScopeLabels: Record<string, string> = {
  ALL: '全部',
  PROBLEM: '只看有问题的',
  STARVED: '只看长期分不到货的',
  UNDER_SUPPLIED: '只看供给不足的',
  UNKNOWN: '只看判不出来的',
  OPEN: '只看还没处置的',
};

/** 缺货清单的筛选口径。 */
export const stockoutScopeLabels: Record<string, string> = {
  ALL: '全部',
  PROBLEM: '只看有问题的',
  STOCKED_OUT: '只看缺货的',
  AT_RISK: '只看覆盖不足的',
  UNKNOWN: '只看判不出来的',
  OPEN: '只看还没处置的',
};

/** 饥饿的根因。 */
export const rootCauseLabels: Record<string, string> = {
  FLOOR: '配了保底却没保住',
  PRIORITY: '优先级排在后面',
  SHARE_LIMIT: '触到份额上限',
  NO_SALES: '没有销量登记',
  UNKNOWN: '判不出来',
};

/** 建议的种类。 */
export const recommendationKindLabels: Record<string, string> = {
  RAISE_FLOOR: '调保底',
  RAISE_PRIORITY: '调优先级',
  REGISTER_SALES: '登记销量',
  REPLENISH: '补货',
  NONE: '无可执行建议',
};

/** 店铺经营模式。 */
export const operatingModeLabels: Record<string, string> = {
  SELF_FULFILLED: '自营',
  FULL_CONSIGNMENT: '全托管',
  SEMI_CONSIGNMENT: '半托管',
  OVERSEAS_WAREHOUSE: '海外仓',
  LOCAL: '本地',
};

/** 7.8.1.1 的分摊结论。 */
export const outcomeLabels: Record<string, string> = {
  OK: '正常',
  CAP_REACHED: '触到份额上限',
  DEMAND_EXHAUSTED: '货被前面的店分完',
  NO_SALES: '销量为 0',
  DISABLED: '成员已停用',
};

/**
 * 严重度的色调。只有「严重」才需要被一眼看见。
 *
 * <p>UNKNOWN 那一类（判不出来的）刻意用中性色：它不是「出错了」，
 * 是「我们还不知道」—— 把它渲染成红色会让人以为系统坏了，
 * 而它其实是在提示「该去登记销量 / 核对台账」。
 */
export function severityTone(severity: string | null | undefined): string {
  switch (severity) {
    case 'CRITICAL':
      return 'text-rose-600 dark:text-rose-400';
    case 'WARNING':
      return 'text-amber-600 dark:text-amber-400';
    default:
      return 'text-muted-foreground';
  }
}

/** 状态色调：缺货要红、供给不足要黄、判不出来要中性。 */
export function stateTone(state: string | null | undefined): string {
  switch (state) {
    case 'NETWORK_STOCKED_OUT':
    case 'WAREHOUSE_STOCKED_OUT':
    case 'STARVED':
      return 'text-rose-600 dark:text-rose-400';
    case 'AT_RISK':
    case 'UNDER_SUPPLIED':
      return 'text-amber-600 dark:text-amber-400';
    case 'HEALTHY':
      return 'text-emerald-600 dark:text-emerald-400';
    default:
      return 'text-muted-foreground';
  }
}

/**
 * 一行是不是「判不出来」。
 *
 * <p>这几行必须**单独呈现**：它们既不是「没问题」也不是「有问题」，
 * 而是「我们不知道」—— 处置是去补数据，不是去调优先级或补货。
 */
export function isUnknownRow(row: {
  alertState: string;
  demandState: string;
}): boolean {
  return row.demandState === 'UNKNOWN' || row.alertState === 'NO_LEDGER_RECORD';
}

/**
 * 建议的一句话：**目标对象 + 当前值 → 建议值 + 理由**。
 *
 * <p>刻意不把它压成一句静态文案：运营要照着去 7.8.1.2 开新一档保底、
 * 或去 7.8.1.1 改成员优先级，光说「建议多给点货」没法落地。
 */
export function recommendationText(recommendation: AlertRecommendation): string {
  const kind = recommendationKindLabels[recommendation.kind] ?? recommendation.kind;
  if (recommendation.kind === 'NONE') {
    return `${kind}：${recommendation.reason}`;
  }
  const target = recommendation.targetLabel || recommendation.target;
  const current =
    recommendation.currentValue === null || recommendation.currentValue === undefined
      ? '—'
      : qty(recommendation.currentValue);
  const suggested =
    recommendation.suggestedValue === null || recommendation.suggestedValue === undefined
      ? '—'
      : qty(recommendation.suggestedValue);
  return `${kind} → ${target}：${current} → ${suggested}。${recommendation.reason}`;
}

/**
 * 一行饥饿的「没分到多少」文案。
 *
 * <p>刻意把「分到 0 件的轮次数」与「总轮次数」放在一起 ——
 * 只给一个 0 会让人以为「这一次没分到」，而「多轮都分不到」才是饥饿。
 */
export function starvationCoverageText(row: {
  observedRunCount: number;
  zeroRoundCount: number;
  allocatedTotal: number | string | null;
  demandUnits: number | string | null;
  maxRunAvailableQuantity: number | string | null;
}): string {
  const demand =
    row.demandUnits === null || row.demandUnits === undefined
      ? '尚未回填'
      : `${qty(row.demandUnits)} 件`;
  const available =
    row.maxRunAvailableQuantity === null || row.maxRunAvailableQuantity === undefined
      ? '尚未回填'
      : `${qty(row.maxRunAvailableQuantity)} 件`;
  return `窗口内 ${row.observedRunCount} 轮有 ${row.zeroRoundCount} 轮分到 0 件；`
    + `累计分到 ${qty(row.allocatedTotal)} 件，登记需求 ${demand}；`
    + `同期总可用量最高 ${available}。`;
}

/**
 * 一行缺货的「还剩多少」文案。
 *
 * <p>刻意把「该仓的可用量」与「全网合计的可用量」并排给出：
 * 前者回答「这个仓发不发得出」，后者回答「全网到底还有没有货」——
 * 而蓝图里那个「真正的全网缺货」说的正是后者。
 */
export function stockoutCoverageText(row: {
  availableQuantity: number | string | null;
  networkAvailableQuantity: number | string | null;
  demandUnits: number | string | null;
  daysOfCover: number | string | null;
}): string {
  const available =
    row.availableQuantity === null || row.availableQuantity === undefined
      ? '尚未回填'
      : `${qty(row.availableQuantity)} 件`;
  const network =
    row.networkAvailableQuantity === null || row.networkAvailableQuantity === undefined
      ? '尚未回填'
      : `${qty(row.networkAvailableQuantity)} 件`;
  const demand =
    row.demandUnits === null || row.demandUnits === undefined
      ? '尚未回填'
      : `${qty(row.demandUnits)} 件`;
  const days =
    row.daysOfCover === null || row.daysOfCover === undefined
      ? '尚未回填'
      : `${qty(row.daysOfCover)} 天`;
  return `该仓可用 ${available}，全网合计 ${network}，登记需求 ${demand}，可售 ${days}。`;
}

/** 饥饿清单的汇总文案。 */
export function starvationSummaryText(summary: {
  rowCount: number;
  starvedCount: number;
  underSuppliedCount: number;
  unknownDemandCount: number;
  criticalCount: number;
  openCount: number;
  acknowledgedCount: number;
  resolvedCount: number;
  demandKnownCount: number;
}): string {
  return [
    `共 ${summary.rowCount} 行`,
    `长期分不到货 ${summary.starvedCount} 行`,
    `供给不足 ${summary.underSuppliedCount} 行`,
    `判不出来 ${summary.unknownDemandCount} 行`,
    `严重 ${summary.criticalCount} 行`,
    `待处理 ${summary.openCount} 行`,
    `已认领 ${summary.acknowledgedCount} 行`,
    `已处置 ${summary.resolvedCount} 行`,
    `需求已知 ${summary.demandKnownCount}/${summary.rowCount} 行`,
  ].join('；');
}

/** 缺货清单的汇总文案。 */
export function stockoutSummaryText(summary: {
  rowCount: number;
  networkStockedOutCount: number;
  networkStockedOutSkuCount: number;
  warehouseStockedOutCount: number;
  atRiskCount: number;
  noLedgerRecordCount: number;
  unknownDemandCount: number;
  criticalCount: number;
  openCount: number;
  acknowledgedCount: number;
  resolvedCount: number;
  availableKnownCount: number;
  demandKnownCount: number;
}): string {
  return [
    `共 ${summary.rowCount} 行`,
    `全网缺货 ${summary.networkStockedOutCount} 行（${summary.networkStockedOutSkuCount} 个 SKU）`,
    `仓级断货 ${summary.warehouseStockedOutCount} 行`,
    `覆盖不足 ${summary.atRiskCount} 行`,
    `查不到库存记录 ${summary.noLedgerRecordCount} 行`,
    `判不出来 ${summary.unknownDemandCount} 行`,
    `严重 ${summary.criticalCount} 行`,
    `待处理 ${summary.openCount} 行`,
    `已认领 ${summary.acknowledgedCount} 行`,
    `已处置 ${summary.resolvedCount} 行`,
    `可用量已知 ${summary.availableKnownCount}/${summary.rowCount} 行`,
    `需求已知 ${summary.demandKnownCount}/${summary.rowCount} 行`,
  ].join('；');
}

/** 店铺下拉的选项：从**当前结果集**里去重，避免「运营没有 channel 读权限 → 下拉是空的」。 */
export function shopOptionsOf(rows: StarvationAlertRow[]): { id: string; label: string }[] {
  const map = new Map<string, string>();
  for (const row of rows) map.set(row.referenceId, row.referenceLabel || row.referenceId);
  return [...map.entries()].map(([id, label]) => ({ id, label }));
}
