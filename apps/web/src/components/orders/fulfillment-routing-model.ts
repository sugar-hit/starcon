import type {
  RoutingSwitchRow,
  CostProfileInput,
  CostProfilePage,
  CostProfileRow,
  CostProfileSaved,
  DecisionPage,
  DecisionStatusInput,
  FulfillmentRoutingCapabilities,
  McfAuthorizationInput,
  McfPage,
  McfAuthorizationRow,
  McfSaved,
  RoutingCandidateRow,
  RoutingDecisionDetail,
  RoutingDecisionRequest,
  RoutingDecisionRow,
  RoutingDecisionSaved,
  RoutingEvaluation,
  RoutingEvaluationRequest,
  StockSnapshotInput,
  StockSnapshotPage,
  StockSnapshotRow,
  StockSnapshotSaved,
  SwitchPage,
} from '../../../../../packages/api-contracts/src/catalog';
import { ApiError, api } from '../../lib/api';

/**
 * 9.3.2.2 FBA 与自发货选择前端模型。
 *
 * <p>蓝图：「在平台仓（FBA/MFN）与自有/第三方仓之间选择履约路径：优先用平台仓（买家体验好）、
 * 平台仓缺货时切自发货（需符合平台的多渠道配送要求）、或按成本优先。」输出是
 * 「履约路径决策 + 缺货时切换记录」。
 *
 * <p><b>界面上最容易做错、本模型显式处理的几处</b>：
 *
 * <ul>
 *   <li><b>缺货切换记录是一等公民，不是日志。</b>「为什么这单没走平台仓」是本页最常被问的问题，
 *       所以切换记录有自己的区块与独立查询入口，而不是塞进决策详情里让人自己找。</li>
 *   <li><b>「无资质」不能显示成「有资质」。</b>MCF 资质状态里的 UNKNOWN 是「没核验」，
 *       与 AUTHORIZED 差一个「过期」字段也要分开显示：两者都不许切自发货，
 *       但需要人去做的动作不同（去核验 vs 去续期）。</li>
 *   <li><b>「没有库存快照」不等于「库存为 0」。</b>0 是一个确定的结论，「没测过」是缺口，
 *       页面上必须显示成不同的话 —— 把两者都画成 0 会让人以为这单确实没货。</li>
 *   <li><b>金额按后端给的结算口径显示。</b>后端已统一 HALF_UP 到分（numeric(19,4) 落库、
 *       结算列按分取整），前端**不再二次四舍五入**，否则界面上会比账面多出或少掉一分钱。</li>
 * </ul>
 */

// ------------------------------------------------------------ 参数

export interface McfParams {
  platformCode?: string;
  shopCode?: string;
  status?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface CostProfileParams {
  platformCode?: string;
  pathKind?: string;
  destinationCountryCode?: string;
  effectiveOnly?: boolean;
  page?: number;
  size?: number;
}

export interface StockSnapshotParams {
  platformCode?: string;
  shopCode?: string;
  skuRef?: string;
  pathKind?: string;
  stockSource?: string;
  page?: number;
  size?: number;
}

export interface DecisionParams {
  orderRef?: string;
  platformCode?: string;
  shopCode?: string;
  skuRef?: string;
  chosenPath?: string;
  reasonCode?: string;
  status?: string;
  keyword?: string;
  page?: number;
  size?: number;
}

export interface SwitchParams {
  orderRef?: string;
  decisionId?: string;
  platformCode?: string;
  triggerReason?: string;
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

const base = 'trade/fulfillment/routing';

export function fetchCapabilities(): Promise<FulfillmentRoutingCapabilities> {
  return api<FulfillmentRoutingCapabilities>(`${base}/capabilities`);
}

export function fetchMcfAuthorizations(params: McfParams): Promise<McfPage> {
  return api<McfPage>(`${base}/mcf-authorizations${query(params)}`);
}

export function saveMcfAuthorization(input: McfAuthorizationInput): Promise<McfSaved> {
  return api<McfSaved>(`${base}/mcf-authorizations`, 'POST', input);
}

export function fetchCostProfiles(params: CostProfileParams): Promise<CostProfilePage> {
  return api<CostProfilePage>(`${base}/cost-profiles${query(params)}`);
}

export function saveCostProfile(input: CostProfileInput): Promise<CostProfileSaved> {
  return api<CostProfileSaved>(`${base}/cost-profiles`, 'POST', input);
}

export function fetchStockSnapshots(params: StockSnapshotParams): Promise<StockSnapshotPage> {
  return api<StockSnapshotPage>(`${base}/stock-snapshots${query(params)}`);
}

export function saveStockSnapshot(input: StockSnapshotInput): Promise<StockSnapshotSaved> {
  return api<StockSnapshotSaved>(`${base}/stock-snapshots`, 'POST', input);
}

export function evaluateRouting(input: RoutingEvaluationRequest): Promise<RoutingEvaluation> {
  return api<RoutingEvaluation>(`${base}/evaluations`, 'POST', input);
}

export function fetchDecisions(params: DecisionParams): Promise<DecisionPage> {
  return api<DecisionPage>(`${base}/decisions${query(params)}`);
}

export function fetchDecision(decisionId: string): Promise<RoutingDecisionDetail> {
  return api<RoutingDecisionDetail>(`${base}/decisions/${decisionId}`);
}

export function createRoutingDecision(input: RoutingDecisionRequest): Promise<RoutingDecisionSaved> {
  return api<RoutingDecisionSaved>(`${base}/decisions`, 'POST', input);
}

export function advanceDecisionStatus(
  decisionId: string,
  input: DecisionStatusInput,
): Promise<RoutingDecisionSaved> {
  return api<RoutingDecisionSaved>(`${base}/decisions/${decisionId}/status`, 'POST', input);
}

export function fetchSwitches(params: SwitchParams): Promise<SwitchPage> {
  return api<SwitchPage>(`${base}/switches${query(params)}`);
}

// ------------------------------------------------------------ 展示

/** 履约路径。契约里它是 {@code RoutingCandidateRow.pathKind} 的内联联合，
 * codegen 不为它单列一个 type，所以从行类型反推（与 warehouse 的 WarehouseKind 同一做法）。 */
export type PathKind = RoutingCandidateRow['pathKind'];

/** 缺货切换触发原因。同样从 {@code RoutingSwitchRow.triggerReason} 反推。 */
export type SwitchTriggerReason = RoutingSwitchRow['triggerReason'];

/** 履约路径中文名。 */
export const pathLabels: Record<PathKind, string> = {
  PLATFORM_FBA: '平台仓 FBA',
  MERCHANT_OWN: '自有仓自发货',
  THIRD_PARTY: '第三方仓自发货',
};

export function pathLabel(path: PathKind | string): string {
  return pathLabels[path as PathKind] ?? String(path);
}

/** 缺货切换触发原因的中文名。 */
export const switchTriggerLabels: Record<SwitchTriggerReason, string> = {
  PLATFORM_STOCK_SHORTAGE: '平台仓可用量不足',
  PLATFORM_STOCK_MISSING: '平台仓无库存快照',
  MCF_NOT_AUTHORIZED: '无多渠道配送资质',
  COST_ADVANTAGE: '自发货成本更优',
  LEAD_TIME_ADVANTAGE: '自发货时效更优',
  MANUAL_OVERRIDE: '人工改判',
};

export function switchTriggerLabel(reason: SwitchTriggerReason | string): string {
  return switchTriggerLabels[reason as SwitchTriggerReason] ?? String(reason);
}

/** 决策原因码的中文名（与后端 reasonCode 词表一一对应）。 */
export const reasonLabels: Record<string, string> = {
  PLATFORM_STOCK_SUFFICIENT: '平台仓库存充足',
  PLATFORM_STOCK_INSUFFICIENT: '平台仓库存不足',
  COST_ADVANTAGE: '成本更优',
  MCF_NOT_AUTHORIZED: '无多渠道配送资质',
  POLICY_OVERRIDE: '人工改判',
  NO_PLATFORM_STOCK_RECORD: '平台仓无库存记录',
};

export function reasonLabel(code: string): string {
  return reasonLabels[code] ?? code;
}

/** 资质状态中文名。UNKNOWN 是「未核验」，刻意与「已授权」分开显示。 */
export const mcfStatusLabels: Record<string, string> = {
  AUTHORIZED: '已授权',
  DENIED: '平台无权限',
  PENDING: '审核中',
  UNKNOWN: '未核验',
};

export function mcfStatusLabel(status: string): string {
  return mcfStatusLabels[status] ?? status;
}

/**
 * 资质是否**此刻**真的可用。
 *
 * <p>「有资质但已过期」与「没核验」在页面上必须能被一眼分开：
 * 前者要人去**续期**，后者要人去**核验**，都显示成「无资质」会让运维做错动作。
 */
export function mcfUsable(row: McfAuthorizationRow): boolean {
  return row.status === 'AUTHORIZED' && !row.expired;
}

/**
 * 数量格式化。null 显示成「—」而不是 0：
 * 「没有库存快照」与「库存为 0」在决策上不等价，界面上混成一个 0 就是撒谎。
 */
export function qtyText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 6 });
}

/**
 * 金额格式化。**不在前端二次取整** —— 后端已按结算口径（HALF_UP 到分）落库，
 * 这里再 round 一次会让界面上比账面多出或少掉一分钱，而这两分钱在账上是要对账的。
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
  return `${parsed.getUTCFullYear()}-${pad(parsed.getUTCMonth() + 1)}-${pad(parsed.getUTCDate())}`
    + ` ${pad(parsed.getUTCHours())}:${pad(parsed.getUTCMinutes())}`
    + ` ${value.endsWith('Z') ? 'UTC' : value.slice(-6)}`;
}

/** 候选是否被选中。契约里 chosen 是布尔，null（理论上不该出现）按未选中处理。 */
export function candidateChosen(candidate: RoutingCandidateRow): boolean {
  return candidate.chosen === true;
}

/** 决策是否还在待确认状态（可推进状态流转）。 */
export function canAdvance(status: string): boolean {
  return status === 'DECIDED';
}

/** 能力维度：不可用时把原因一并显示，而不是画成 0 或「—」。 */
export function dimensionUnavailableReason(
  dimensions: { code: string; available: boolean; unavailableReason: string | null }[],
  code: string,
): string | null {
  return dimensions.find((d) => d.code === code && !d.available)?.unavailableReason ?? null;
}

/** 决策行类型别名，页面上多处复用。 */
export type DecisionRow = RoutingDecisionRow;

export { ApiError };