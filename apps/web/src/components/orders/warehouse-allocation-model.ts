import type {
  WarehouseAllocationCandidateRow,
  WarehouseAllocationCapabilities,
  WarehouseAllocationDecisionDetail,
  WarehouseAllocationDecisionRequest,
  WarehouseAllocationDecisionPage,
  WarehouseAllocationDecisionRow,
  WarehouseAllocationDecisionSaved,
  WarehouseAllocationHoldPage,
  WarehouseAllocationHoldRow,
  WarehouseAllocationRuleInput,
  WarehouseAllocationRulePage,
  WarehouseAllocationRuleRow,
  WarehouseAllocationRuleSaved,
  WarehouseAllocationShippingInput,
  WarehouseAllocationShippingPage,
  WarehouseAllocationShippingRow,
  WarehouseAllocationShippingSaved,
  WarehouseAllocationSimulation,
  WarehouseAllocationSimulationRequest,
  WarehouseAllocationStatusInput,
  WarehouseAllocationStockInput,
  WarehouseAllocationStockPage,
  WarehouseAllocationStockRow,
  WarehouseAllocationStockSaved,
  WarehouseAllocationWarehouseInput,
  WarehouseAllocationWarehousePage,
  WarehouseAllocationWarehouseRow,
  WarehouseAllocationWarehouseSaved,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '../../lib/api';

/**
 * 9.3.2.1 分仓规则前端模型。
 *
 * <p>蓝图：「配置并执行仓库分配：按库存可用性、运费成本、时效承诺、货权归属、
 * 买家地区匹配度选择发货仓；支持优先级与兜底策略。」输出是
 * 「分配结果（发货仓 + 分配数量）」与「无法分配的异常挂起」。
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>货权是一等公民，不是备注。</b>候选表里每个仓都带自己的法人与「是否与订单主体一致」，
 *       选中仓跨法人时整行要显红并写明「收入记在谁名下」—— 加粗句说的就是这件事，
 *       页面上把它藏进 tooltip 就等于没有。</li>
 *   <li><b>挂起不是错误，是结论。</b>挂起清单有独立区块：展示的是
 *       「每个仓分别因为什么被排除」，而不是一句「无可行仓」。</li>
 *   <li><b>「没有库存估算」不等于「库存为 0」。</b>0 是确定结论，「没测过」是缺口，
 *       两者画成同一个 0 会让人以为这单确实没货。</li>
 *   <li><b>金额不在前端二次取整。</b>后端已按结算口径（HALF_UP 到分）落库，
 *       再 round 一次会让界面上比账面多出或少掉一分钱，而这两分钱在账上要对账。</li>
 * </ul>
 */

// ------------------------------------------------------------ 参数

export interface WarehouseAllocationRuleParams {
  ruleCode?: string;
  selectionPolicy?: string;
  fallbackPolicy?: string;
  effectiveOnly?: boolean;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface WarehouseAllocationWarehouseParams {
  legalEntityCode?: string;
  regionCode?: string;
  activeOnly?: boolean;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface WarehouseAllocationStockParams {
  warehouseRef?: string;
  skuRef?: string;
  stockSource?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface WarehouseAllocationShippingParams {
  warehouseRef?: string;
  skuRef?: string;
  destinationCountryCode?: string;
  costSource?: string;
  effectiveOnly?: boolean;
  page?: number;
  size?: number;
}

export interface WarehouseAllocationDecisionParams {
  orderRef?: string;
  skuRef?: string;
  chosenWarehouseRef?: string;
  chosenLegalEntityCode?: string;
  status?: string;
  reasonCode?: string;
  crossLegalEntity?: boolean;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface WarehouseAllocationHoldParams {
  orderRef?: string;
  decisionId?: string;
  holdReason?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

function query<T extends object>(params: T): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params as Record<string, unknown>)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

// ------------------------------------------------------------ 接口

const base = 'trade/fulfillment/allocation';

export function fetchAllocationCapabilities(): Promise<WarehouseAllocationCapabilities> {
  return api<WarehouseAllocationCapabilities>(`${base}/capabilities`);
}

export function fetchRules(
  params: WarehouseAllocationRuleParams,
): Promise<WarehouseAllocationRulePage> {
  return api<WarehouseAllocationRulePage>(`${base}/rules${query(params)}`);
}

export function saveRule(
  input: WarehouseAllocationRuleInput,
): Promise<WarehouseAllocationRuleSaved> {
  return api<WarehouseAllocationRuleSaved>(`${base}/rules`, 'POST', input);
}

export function fetchWarehouses(
  params: WarehouseAllocationWarehouseParams,
): Promise<WarehouseAllocationWarehousePage> {
  return api<WarehouseAllocationWarehousePage>(`${base}/warehouses${query(params)}`);
}

export function saveWarehouse(
  input: WarehouseAllocationWarehouseInput,
): Promise<WarehouseAllocationWarehouseSaved> {
  return api<WarehouseAllocationWarehouseSaved>(`${base}/warehouses`, 'POST', input);
}

export function fetchStockEstimates(
  params: WarehouseAllocationStockParams,
): Promise<WarehouseAllocationStockPage> {
  return api<WarehouseAllocationStockPage>(`${base}/stock-estimates${query(params)}`);
}

export function saveStockEstimate(
  input: WarehouseAllocationStockInput,
): Promise<WarehouseAllocationStockSaved> {
  return api<WarehouseAllocationStockSaved>(`${base}/stock-estimates`, 'POST', input);
}

export function fetchShippingEstimates(
  params: WarehouseAllocationShippingParams,
): Promise<WarehouseAllocationShippingPage> {
  return api<WarehouseAllocationShippingPage>(`${base}/shipping-estimates${query(params)}`);
}

export function saveShippingEstimate(
  input: WarehouseAllocationShippingInput,
): Promise<WarehouseAllocationShippingSaved> {
  return api<WarehouseAllocationShippingSaved>(`${base}/shipping-estimates`, 'POST', input);
}

/** 试算：只读，**不落库**。落库走 {@link createAllocation}。 */
export function simulateAllocation(
  input: WarehouseAllocationSimulationRequest,
): Promise<WarehouseAllocationSimulation> {
  return api<WarehouseAllocationSimulation>(`${base}/simulations`, 'POST', input);
}

export function fetchAllocations(
  params: WarehouseAllocationDecisionParams,
): Promise<WarehouseAllocationDecisionPage> {
  return api<WarehouseAllocationDecisionPage>(`${base}/allocations${query(params)}`);
}

export function fetchAllocation(allocationId: string): Promise<WarehouseAllocationDecisionDetail> {
  return api<WarehouseAllocationDecisionDetail>(`${base}/allocations/${allocationId}`);
}

export function createAllocation(
  input: WarehouseAllocationDecisionRequest,
): Promise<WarehouseAllocationDecisionSaved> {
  return api<WarehouseAllocationDecisionSaved>(`${base}/allocations`, 'POST', input);
}

export function advanceAllocationStatus(
  allocationId: string,
  input: WarehouseAllocationStatusInput,
): Promise<WarehouseAllocationDecisionSaved> {
  return api<WarehouseAllocationDecisionSaved>(
    `${base}/allocations/${allocationId}/status`,
    'POST',
    input,
  );
}

export function fetchHolds(
  params: WarehouseAllocationHoldParams,
): Promise<WarehouseAllocationHoldPage> {
  return api<WarehouseAllocationHoldPage>(`${base}/holds${query(params)}`);
}

// ------------------------------------------------------------ 展示

/** 选仓主排序口径。契约里它是内联联合，codegen 不为它单列 type，故从行类型反推。 */
export type SelectionPolicy = WarehouseAllocationRuleRow['selectionPolicy'];

/** 兜底策略。同上从 {@link WarehouseAllocationRuleRow.fallbackPolicy} 反推。 */
export type FallbackPolicy = WarehouseAllocationRuleRow['fallbackPolicy'];

/** 分配状态。 */
export type AllocationStatus = WarehouseAllocationDecisionRow['status'];

/** 挂起原因。 */
export type HoldReason = WarehouseAllocationHoldRow['holdReason'];

export const selectionPolicyLabels: Record<string, string> = {
  HIGHEST_SCORE: '综合分最高',
  LOWEST_COST: '运费最低',
  CLOSEST_LEAD_TIME: '时效最短',
};

export function selectionPolicyLabel(policy: string): string {
  return selectionPolicyLabels[policy] ?? policy;
}

/**
 * 兜底策略中文名。
 *
 * <p>{@code SUSPEND} 刻意写成「挂起不分配（不静默兜底）」而不是「失败」：
 * 它是本模块**默认**的兜底策略，也是加粗那句「不能只看运费便宜」在工程上的落点。
 */
export const fallbackPolicyLabels: Record<string, string> = {
  SUSPEND: '挂起不分配（不静默兜底）',
  ALLOW_PARTIAL: '允许部分分配（差额挂起）',
  CROSS_LEGAL_ENTITY: '允许跨法人发货',
};

export function fallbackPolicyLabel(policy: string): string {
  return fallbackPolicyLabels[policy] ?? policy;
}

export const legalEntityPolicyLabels: Record<string, string> = {
  NEUTRAL: '不偏好',
  ORDER_OWNER_FIRST: '订单货权主体优先',
  PREFERRED_FIRST: '指定法人优先',
};

export function legalEntityPolicyLabel(policy: string): string {
  return legalEntityPolicyLabels[policy] ?? policy;
}

export const allocationStatusLabels: Record<string, string> = {
  ALLOCATED: '已分配',
  PARTIAL: '部分分配',
  SUSPENDED: '已挂起',
  CONFIRMED: '已确认',
  RELEASED: '已下发',
};

export function allocationStatusLabel(status: string): string {
  return allocationStatusLabels[status] ?? status;
}

export const holdReasonLabels: Record<string, string> = {
  NO_ELIGIBLE_WAREHOUSE: '无可行仓',
  NO_STOCK_RECORD: '全部仓无库存估算',
  INSUFFICIENT_STOCK: '全部仓库存不足',
  NO_SHIPPING_ESTIMATE: '全部仓无运费估算',
  BLOCKED_BY_LEGAL_ENTITY: '全部仓货权法人不符',
  BLOCKED_BY_RULE: '全部仓被规则排除',
  PARTIAL_SHORTFALL: '部分分配差额未满足',
};

export function holdReasonLabel(reason: string): string {
  return holdReasonLabels[reason] ?? reason;
}

export const reasonCodeLabels: Record<string, string> = {
  SCORE_BASED: '按综合分选定',
  LOWEST_COST: '按运费最低选定',
  CLOSEST_LEAD_TIME: '按时效最短选定',
  PARTIAL_ALLOCATION: '部分分配',
  NO_ELIGIBLE_WAREHOUSE: '无可行仓，已挂起',
};

export function reasonCodeLabel(code: string): string {
  return reasonCodeLabels[code] ?? code;
}

/** 候选排除原因码的中文名。空串表示该候选可行。 */
export const blockReasonLabels: Record<string, string> = {
  WAREHOUSE_INACTIVE: '仓库已停用',
  WAREHOUSE_EXCLUDED: '规则排除该仓',
  LEGAL_ENTITY_MISMATCH: '货权法人不符',
  NO_STOCK_RECORD: '无库存估算',
  STOCK_ZERO: '库存为 0',
  INSUFFICIENT_STOCK: '库存不足',
  NO_SHIPPING_ESTIMATE: '无运费估算',
  LEAD_TIME_EXCEEDED: '时效超出承诺',
};

export function blockReasonLabel(code: string | null | undefined): string {
  if (!code) return '';
  return blockReasonLabels[code] ?? code;
}

/**
 * 候选是否因**货权**被排除。
 *
 * <p>单独抽出来是因为它是加粗句的核心：这类候选往往同时更便宜，
 * 页面上必须让运营一眼看到「它便宜但不能用」而不是混在「库存不足」里。
 */
export function blockedByLegalEntity(candidate: WarehouseAllocationCandidateRow): boolean {
  return candidate.infeasibleReasonCode === 'LEGAL_ENTITY_MISMATCH';
}

/**
 * 本次分配是否跨法人。
 *
 * <p>跨法人意味着收入记在了别的法人名下 —— 这是加粗句点名的那件事，
 * 页面上必须显形，不能只靠「选中仓那列的法人不一样」让人自己看出来。
 */
export function crossesLegalEntity(row: {
  crossLegalEntity: boolean;
  chosenLegalEntityCode: string | null;
  orderLegalEntityCode: string;
}): boolean {
  if (row.crossLegalEntity) return true;
  // 兜底：即使标记缺失，两个法人不同也是跨法人。
  return (
    row.chosenLegalEntityCode !== null &&
    row.chosenLegalEntityCode !== '' &&
    row.chosenLegalEntityCode !== row.orderLegalEntityCode
  );
}

/** 跨法人时给运营看的一句话（不跨法人时返回 null，便于条件渲染）。 */
export function crossLegalEntityWarning(decision: {
  chosenLegalEntityCode: string | null;
  orderLegalEntityCode: string;
}): string | null {
  if (decision.chosenLegalEntityCode === null || decision.chosenLegalEntityCode === '') return null;
  if (decision.chosenLegalEntityCode === decision.orderLegalEntityCode) return null;
  return `本次跨法人发货：收入将记在「${decision.chosenLegalEntityCode}」名下，而不是订单主体「${decision.orderLegalEntityCode}」`;
}

/** 挂起的排除清单是否提到了某个仓（用于把详情与挂起对上）。 */
export function holdMentions(
  exclusionDetail: string | null | undefined,
  warehouseRef: string,
): boolean {
  if (!exclusionDetail) return false;
  return exclusionDetail.includes(`${warehouseRef}=`);
}

/**
 * 数量格式化。null 显示成「—」而不是 0：
 * 「没有库存估算」与「库存为 0」在决策上不等价，界面上混成一个 0 就是撒谎。
 */
export function qtyText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/**
 * 金额格式化。**不在前端二次取整** —— 后端已按结算口径（HALF_UP 到分）落库。
 */
export function moneyText(value: number | null | undefined, currency = 'USD'): string {
  if (value === null || value === undefined) return '—';
  return `${value.toFixed(2)} ${currency}`;
}

/**
 * 时刻文本：按该时刻自带的偏移量渲染，不做时区换算。
 *
 * <p>形状不符合预期时返回原串：宁可显示一个能被原样报给后端的串，
 * 也不要显示一个错位的「看起来对」的时间。
 */
export function timeText(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${parsed.getUTCFullYear()}-${pad(parsed.getUTCMonth() + 1)}-${pad(parsed.getUTCDate())}` +
    ` ${pad(parsed.getUTCHours())}:${pad(parsed.getUTCMinutes())}` +
    ` ${value.endsWith('Z') ? 'UTC' : value.slice(-6)}`
  );
}

/** 候选是否被选中。 */
export function candidateChosen(candidate: WarehouseAllocationCandidateRow): boolean {
  return candidate.chosen === true;
}

/**
 * 五维得分合计与规则权重对得上吗。
 *
 * <p>界面上给出「综合分」这个数字之前先确认它确实是各维加权之和 ——
 * 分数算错而没人发现，会让「为什么选了 A 仓」这个问题从此答不对。
 */
export function scoreBreakdown(
  candidate: WarehouseAllocationCandidateRow,
  rule: WarehouseAllocationRuleRow,
): {
  stock: number;
  cost: number;
  leadTime: number;
  legalEntity: number;
  region: number;
  total: number;
} {
  const round4 = (n: number) => Math.round(n * 1e4) / 1e4;
  const stock = round4(rule.weightStock * candidate.scoreStock);
  const cost = round4(rule.weightCost * candidate.scoreCost);
  const leadTime = round4(rule.weightLeadTime * candidate.scoreLeadTime);
  const legalEntity = round4(rule.weightLegalEntity * candidate.scoreLegalEntity);
  const region = round4(rule.weightRegion * candidate.scoreRegion);
  const total = round4(stock + cost + leadTime + legalEntity + region);
  return { stock, cost, leadTime, legalEntity, region, total };
}

/** 分配是否还能推进状态（挂起与已下发是终态）。 */
export function canAdvance(status: string): boolean {
  return status === 'ALLOCATED' || status === 'PARTIAL';
}

/** 是否是部分分配（界面上要额外显示差额）。 */
export function isPartial(status: string): boolean {
  return status === 'PARTIAL';
}

/** 能力维度：不可用时把原因一并显示，而不是画成 0 或「—」。 */
export function dimensionUnavailableReason(
  dimensions: { code: string; available: boolean; unavailableReason: string | null }[],
  code: string,
): string | null {
  return dimensions.find((d) => d.code === code && !d.available)?.unavailableReason ?? null;
}

/** 规则权重合计是否为 1（前端也拦一道，省得白跑一趟后端）。 */
export function weightsSumToOne(weights: number[]): boolean {
  const sum = weights.reduce((acc, n) => acc + n, 0);
  return Math.round(sum * 1e4) === 1e4;
}

export type AllocationRow = WarehouseAllocationDecisionRow;
export type HoldRow = WarehouseAllocationHoldRow;
export type StockRow = WarehouseAllocationStockRow;
export type ShippingRow = WarehouseAllocationShippingRow;
export type WarehouseRow = WarehouseAllocationWarehouseRow;

export { ApiError };
