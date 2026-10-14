import type {
  SafetyStockAlertDetail,
  SafetyStockAlertPage,
  SafetyStockCapabilities,
  SafetyStockCapabilityOption,
  SafetyStockEvaluationPage,
  SafetyStockEvaluationRow,
  SafetyStockPolicyDetail,
  SafetyStockPolicyInput,
  SafetyStockPolicyPage,
  SafetyStockPolicyRow,
  SafetyStockPolicySaved,
  SafetyStockSalesPage,
  SafetyStockSalesRow,
  SafetyStockSalesSaved,
  SafetyStockSuggestionPage,
  SafetyStockSuggestionRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from './api';

/**
 * 7.2.2.2 安全库存前端模型。
 *
 * <p>界面上最容易出错的几处，这个模块都显式处理了：
 *
 * <ul>
 *   <li><b>「平台断货风险」必须真的比「自有仓耗尽」更显眼</b>。蓝图加粗写着「FBA 断货造成的
 *       损失远大于自有仓」，而 {@code effectiveSeverity} 是后端按这条规则提级后的结果 ——
 *       界面<b>显示提级后的级别并标注它是被提过的</b>，而不是显示配置里填的那个，
 *       否则同一条 INFO 的 FBA 预警和自有仓预警在页面上长得一模一样。
 *   <li><b>算不出来就显示「不可得 + 原因」，绝不显示 0</b>。没登记日均销量时，
 *       可售天数显示成 0 天会被读成「今天就断货」，建议量显示成 0 件会被读成「不用补」——
 *       后者会直接让一次采购决策消失。
 *   <li><b>「没配阈值」与「阈值很安全」长得不一样</b>。评估结果里 policiesEvaluated=0 时，
 *       0 条预警指的是压根没人受保护，不是库存健康；这条要在页面上占版面。
 *   <li><b>未扫描的档位要能看见</b>。分类档/兜底档没带 SKU 清单时评估不到，
 *       policiesNotScannable 单独显示，不能让它悄悄混进「扫过了没风险」。
 * </ul>
 */

// ------------------------------------------------------------ 接口与查询

/** 阈值分档列表查询条件。与后端 `SafetyStockQuery` 一一对应。 */
export interface PolicyListParams {
  scopeType?: string;
  categoryId?: string;
  skuId?: string;
  alertSeverity?: string;
  warehouseId?: string;
  effectiveOnly?: boolean;
  keyword?: string;
  page?: number;
  size?: number;
}

/** 预警列表查询条件。与后端 `SafetyStockAlertQuery` 一一对应。 */
export interface AlertListParams {
  warehouseId?: string;
  skuId?: string;
  alertType?: string;
  severity?: string;
  status?: string;
  policyId?: string;
  evaluationId?: string;
  platformRiskOnly?: boolean;
  keyword?: string;
  page?: number;
  size?: number;
}

/** 补货建议列表查询条件。与后端 `SafetyStockSuggestionQuery` 一一对应。 */
export interface SuggestionListParams {
  warehouseId?: string;
  skuId?: string;
  status?: string;
  unquantifiedOnly?: boolean;
  page?: number;
  size?: number;
}

/** 评估请求里的一个 SKU 及其分类上下文。分类在 catalog 服务，前端带过来。 */
export interface SkuContextInput {
  skuId: string;
  categoryId?: string | null;
}

/** 新建/换版阈值时的挂仓行。warehouseKind 决定产出哪一种预警，必须逐个声明。 */
export interface WarehouseBindingInput {
  warehouseId: string;
  warehouseKind: string;
}

function query(params: object): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    if (typeof value === 'boolean') {
      if (value) parts.push(`${key}=true`);
      continue;
    }
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

const BASE = 'warehouse/inventory/safety-stock';

// ------------------------------------------------------------ 读

export function fetchCapabilities(): Promise<SafetyStockCapabilities> {
  return api<SafetyStockCapabilities>(`${BASE}/capabilities`);
}

export function fetchPolicies(params: PolicyListParams = {}): Promise<SafetyStockPolicyPage> {
  return api<SafetyStockPolicyPage>(`${BASE}/policies${query(params)}`);
}

export function fetchPolicy(id: string): Promise<SafetyStockPolicyDetail> {
  return api<SafetyStockPolicyDetail>(`${BASE}/policies/${encodeURIComponent(id)}`);
}

/** 解析某一时刻真正生效的阈值。没配任何档时返回的 policy 为 null —— 要显式处理的状态。 */
export function fetchEffective(params: {
  scopeType?: string;
  categoryId?: string;
  skuId?: string;
}): Promise<{
  policy: SafetyStockPolicyRow | null;
  resolvedScopeType: string;
  usedFallback: boolean;
  at: string;
  note: string;
}> {
  return api(`${BASE}/policies/effective${query(params)}`);
}

export function fetchSales(
  params: {
    skuId?: string;
    keyword?: string;
    page?: number;
    size?: number;
  } = {},
): Promise<SafetyStockSalesPage> {
  return api<SafetyStockSalesPage>(`${BASE}/sales${query(params)}`);
}

export function fetchAlerts(params: AlertListParams = {}): Promise<SafetyStockAlertPage> {
  return api<SafetyStockAlertPage>(`${BASE}/alerts${query(params)}`);
}

export function fetchAlert(id: string): Promise<SafetyStockAlertDetail> {
  return api<SafetyStockAlertDetail>(`${BASE}/alerts/${encodeURIComponent(id)}`);
}

export function fetchSuggestions(
  params: SuggestionListParams = {},
): Promise<SafetyStockSuggestionPage> {
  return api<SafetyStockSuggestionPage>(`${BASE}/suggestions${query(params)}`);
}

export function fetchEvaluations(params: { page?: number; size?: number } = {}) {
  return api<SafetyStockEvaluationPage>(`${BASE}/evaluations${query(params)}`);
}

// ------------------------------------------------------------ 写

export function createPolicy(input: SafetyStockPolicyInput): Promise<SafetyStockPolicySaved> {
  return api<SafetyStockPolicySaved>(`${BASE}/policies`, 'POST', input);
}

/** 换版：收口当前在效的那一档再开新档。刻意没有「改这一档」的调用 —— DB 层不允许改。 */
export function versionPolicy(
  id: string,
  input: SafetyStockPolicyInput,
): Promise<SafetyStockPolicySaved> {
  return api<SafetyStockPolicySaved>(
    `${BASE}/policies/${encodeURIComponent(id)}/versions`,
    'POST',
    input,
  );
}

export function retirePolicy(id: string): Promise<SafetyStockPolicySaved> {
  return api<SafetyStockPolicySaved>(
    `${BASE}/policies/${encodeURIComponent(id)}/retire`,
    'POST',
    {},
  );
}

/** 登记一条 SKU 日均销量。销量无法自动派生，13 销量域交付后应由其回写。 */
export function recordSales(input: {
  sourceSystem: string;
  skuId: string;
  skuLabel?: string;
  periodStart: string;
  periodEnd: string;
  unitsSold: number;
}): Promise<SafetyStockSalesSaved> {
  return api<SafetyStockSalesSaved>(`${BASE}/sales`, 'POST', input);
}

/** 发起一次评估。skus 传 SKU 清单及分类上下文，分类档/兜底档靠它展开。 */
export function evaluate(payload: {
  skus?: SkuContextInput[];
  warehouseId?: string | null;
  note?: string | null;
}): Promise<SafetyStockEvaluationRow> {
  return api<SafetyStockEvaluationRow>(`${BASE}/evaluate`, 'POST', payload);
}

export function acknowledgeAlert(id: string) {
  return api(`${BASE}/alerts/${encodeURIComponent(id)}/acknowledge`, 'POST', {});
}

export function resolveAlert(id: string) {
  return api(`${BASE}/alerts/${encodeURIComponent(id)}/resolve`, 'POST', {});
}

export function acknowledgeSuggestion(id: string) {
  return api(`${BASE}/suggestions/${encodeURIComponent(id)}/acknowledge`, 'POST', {});
}

export function dismissSuggestion(id: string, reason: string) {
  return api(
    `${BASE}/suggestions/${encodeURIComponent(id)}/dismiss${query({ reason })}`,
    'POST',
    {},
  );
}

// ------------------------------------------------------------ 展示口径

/** 预警类型文案。两种类型必须在界面上用不同的说法，不能都叫「库存不足」。 */
export function alertTypeText(type?: string | null): string {
  switch (type) {
    case 'PLATFORM_STOCKOUT_RISK':
      return '平台断货风险（FBA）';
    case 'OWN_WAREHOUSE_DEPLETION':
      return '自有仓耗尽';
    default:
      return '未知预警类型';
  }
}

/** 是否平台侧风险。用于把「FBA 断货」那批单独染出来。 */
export function isPlatformRisk(type?: string | null): boolean {
  return type === 'PLATFORM_STOCKOUT_RISK';
}

export function severityLabel(severity?: string | null): string {
  switch (severity) {
    case 'CRITICAL':
      return '严重';
    case 'WARNING':
      return '警告';
    case 'INFO':
      return '提示';
    default:
      return '未知级别';
  }
}

export function severityRank(severity?: string | null): number {
  switch (severity) {
    case 'CRITICAL':
      return 0;
    case 'WARNING':
      return 1;
    case 'INFO':
      return 2;
    default:
      return 3;
  }
}

/**
 * 严重度的完整展示文案。
 *
 * <p>被提级过（平台断货风险）时两个级别一起显示：只显示提级后的会让人以为自己配了 CRITICAL，
 * 只显示配置值又会让 INFO 的 FBA 预警排在自有仓 WARNING 后面 —— 这条正是要避免的。
 */
export function severityText(configured?: string | null, effective?: string | null): string {
  const base = severityLabel(effective ?? configured);
  if (!configured || !effective || configured === effective) return base;
  return `${base}（配置为「${severityLabel(configured)}」，平台断货风险自动提级）`;
}

/** 是否这条预警的级别被后端提过（平台断货风险 → 上一级）。 */
export function severityEscalated(configured?: string | null, effective?: string | null): boolean {
  return Boolean(configured && effective && configured !== effective);
}

export function scopeTypeLabel(scope?: string | null): string {
  switch (scope) {
    case 'DEFAULT':
      return '全租户兜底';
    case 'CATEGORY':
      return '按分类';
    case 'SKU':
      return '按 SKU';
    default:
      return '未知范围';
  }
}

export function warehouseKindLabel(kind?: string | null): string {
  switch (kind) {
    case 'OWN':
      return '自有仓';
    case 'PLATFORM_FBA':
      return '平台仓（FBA）';
    default:
      return '未知仓类型';
  }
}

export function alertStatusLabel(status?: string | null): string {
  switch (status) {
    case 'OPEN':
      return '待处理';
    case 'ACKNOWLEDGED':
      return '已认领';
    case 'RESOLVED':
      return '已处置';
    default:
      return '未知状态';
  }
}

export function suggestionStatusLabel(status?: string | null): string {
  switch (status) {
    case 'PROPOSED':
      return '待处理';
    case 'ACKNOWLEDGED':
      return '已采纳';
    case 'DISMISSED':
      return '已忽略';
    default:
      return '未知状态';
  }
}

// ------------------------------------------------------------ 数量文案

function trim(value: number): string {
  return String(Number(value.toFixed(3)));
}

/**
 * 日均销量文案。
 *
 * <p>没登记时显示「不可得 + 原因」而不是 0 件/天：0 会被读成「这个 SKU 卖不动」，
 * 而真实状态是「没人告诉我们卖了多少」—— 两者的处置完全不同。
 */
export function dailyAverageText(value?: number | null): string {
  if (value === null || value === undefined) return '日均销量不可得（尚未登记销量）';
  if (value === 0) return '日均 0 件（登记为零，不是缺数据）';
  return `日均 ${trim(value)} 件`;
}

/** 可售天数文案。日均为 0 时除不出天数，也显示不可得而不是 0 天。 */
export function daysOfCoverText(days?: number | null, dailyAverage?: number | null): string {
  if (days === null || days === undefined) {
    if (dailyAverage === 0) return '可售天数不可得（日均销量为 0，除不出天数）';
    return '可售天数不可得（尚未登记销量）';
  }
  return `可售 ${trim(days)} 天`;
}

/** 缺口量文案。低于阈值才会生成预警，所以它恒为正。 */
export function shortfallText(value?: number | null): string {
  if (value === null || value === undefined) return '缺口不可得';
  return `缺口 ${trim(value)} 件`;
}

/**
 * 补货建议量文案。
 *
 * <p>算不出来时显示「不可得 + 原因」而不是 0 件 —— 这是本模块最贵的那个错误：
 * 0 会被读成「不用补货」，于是该补的那批就这么过去了。
 */
export function suggestedText(value?: number | null, unquantifiedReason?: string | null): string {
  if (value === null || value === undefined) {
    return unquantifiedReason
      ? `建议量不可得（${unquantifiedReason}）`
      : '建议量不可得（尚未登记销量，无法推算可售天数）';
  }
  return `建议补 ${trim(value)} 件`;
}

/** 目标库存 = 阈值 + 日均销量 × 覆盖天数。 */
export function targetStockText(value?: number | null): string {
  if (value === null || value === undefined) return '目标库存不可得';
  return `目标库存 ${trim(value)} 件`;
}

/** 阈值文案。0 是合法取值（允许卖到零），不是「没配阈值」，所以照常显示。 */
export function thresholdText(value?: number | null): string {
  if (value === null || value === undefined) return '阈值不可得';
  return `${trim(value)} 件`;
}

/** 覆盖天数文案。给天数而不是倍数：运营答得上「备 30 天」，答不上「备 1.5 倍」。 */
export function coverageDaysText(value?: number | null): string {
  if (value === null || value === undefined) return '未配覆盖天数';
  return `${trim(value)} 天`;
}

/** 已登记销量的覆盖天数（登记区间的天数总和，不是日历跨度）。 */
export function registeredDaysText(
  row: Pick<SafetyStockSalesRow, 'coveredDays' | 'records'>,
): string {
  const records = row.records === 1 ? '1 次登记' : `${row.records} 次登记`;
  return `${row.coveredDays} 天 / ${records}`;
}

// ------------------------------------------------------------ 评估结论

/**
 * 评估结论的一句话摘要。
 *
 * <p>三种「0 条预警」在这里必须被分成三种说法：压根没配阈值、配置了但没扫到、扫了确实没风险。
 * 混成一句「0 条预警」会让前两种看起来像第三种。
 */
export function evaluationSummary(row: SafetyStockEvaluationRow): string {
  if (row.policiesEvaluated === 0) {
    return '当前租户没有任何仍在生效的安全库存阈值：本次评估没有可比对的基准，0 条预警**不代表库存健康**。';
  }
  const parts = [
    `扫描在效阈值 ${row.policiesEvaluated} 档`,
    `命中 (仓 × SKU) ${row.skusEvaluated} 对`,
    `低于阈值 ${row.alertsOpened} 对`,
    `未触发 ${row.alertsSuppressed} 对`,
  ];
  if (row.policiesNotScannable > 0) {
    parts.push(`其中 ${row.policiesNotScannable} 档分类档/兜底档因未带 SKU 清单**未能评估**`);
  }
  if (row.skusWithoutSales > 0) {
    parts.push(`${row.skusWithoutSales} 个 SKU 未登记销量（可售天数与建议量算不出来，已如实留空）`);
  }
  return `${parts.join('；')}。`;
}

/** 评估批次列表里的「本次扫了什么」摘要，含未扫描与缺销量的计数。 */
export function evaluationCoverageText(row: SafetyStockEvaluationRow): string {
  const parts = [
    `阈值 ${row.policiesEvaluated} 档`,
    `仓 ${row.warehousesEvaluated} 个`,
    `SKU ${row.skusEvaluated} 个`,
  ];
  if (row.policiesNotScannable > 0) parts.push(`未扫描 ${row.policiesNotScannable} 档`);
  if (row.skusWithoutSales > 0) parts.push(`缺销量 ${row.skusWithoutSales} 个 SKU`);
  return parts.join(' · ');
}

// ------------------------------------------------------------ 能力声明

export interface UnavailableNotice {
  key: string;
  title: string;
  detail: string;
}

/**
 * 把 {@code /capabilities} 里那些 {@code *Available=false} 的边界整理成要占版面的提示。
 *
 * <p>本模块没有一环是自动的：销量要登记、分类展开要调用方给、6.2.1.2 未交付、工作台未交付。
 * 这些必须由前端**显式渲染出来**，而不是让人从 404 或空数组里猜哪一环没通。
 */
export function unavailableNotices(caps: SafetyStockCapabilities | null): UnavailableNotice[] {
  if (!caps) return [];
  const notices: UnavailableNotice[] = [];
  if (caps.salesSourceDerived === false) {
    notices.push({ key: 'sales', title: '日均销量需登记', detail: caps.salesSourceNote });
  }
  if (caps.skuCategoryExpansionAvailable === false) {
    notices.push({
      key: 'category',
      title: '分类展开需调用方提供',
      detail: caps.categoryExpansionNote,
    });
  }
  if (caps.replenishmentTargetDelivered === false) {
    notices.push({
      key: 'replenishment',
      title: '6.2.1.2 补货建议未交付',
      detail: caps.replenishmentNote,
    });
  }
  if (caps.workbenchAlertFeedDelivered === false) {
    notices.push({ key: 'workbench', title: '工作台预警未交付', detail: caps.workbenchNote });
  }
  return notices;
}

/**
 * 能力清单里的可选项取标签。
 *
 * <p>后端给了 label 就用它；label 为空串时按 value 兜底（`??` 不管空串，会把空标签原样显示出来）。
 */
export function capabilityLabel(
  options: SafetyStockCapabilityOption[] | null | undefined,
  value?: string | null,
): string {
  const hit = (options ?? []).find((option) => option.value === value);
  return hit?.label?.trim() ? hit.label : (value ?? '未知');
}

/** 能力清单里不可用的选项（available=false）。默认本模块没有，但前端要能显示。 */
export function unavailableCapabilities(
  options: SafetyStockCapabilityOption[] | null | undefined,
): SafetyStockCapabilityOption[] {
  return (options ?? []).filter((option) => option.available === false);
}

/** 平台断货提级规则说明，直接显示在预警工作台的标题下方。 */
export function platformEscalationNote(caps: SafetyStockCapabilities | null): string {
  return caps?.platformStockoutEscalation ?? '';
}
