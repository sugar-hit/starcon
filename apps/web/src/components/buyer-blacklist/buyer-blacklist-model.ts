import { api } from '../inventory/api';

/**
 * 16.5.1.1 黑名单 前端模型。
 *
 * 蓝图：「维护买家黑名单：买家标识、店铺、原因、风险等级、生效范围（仅本店铺/全店铺），
 * 下单时自动标记并可拦截。输入：黑名单录入（手工/自动，来自风控信号）；解除操作。
 * 输出：黑名单记录；订单拦截标记。关系：上游 = 16.5.1.2 评分、9.2 审单；下游 = 9.2.1.3 拦截、11 售后。
 * **黑名单应支持申诉解除**，永久不解除会让误判的正常买家永久损失。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「解除」不显示成「删除」**。黑名单只能解除、不能删除 —— 解除是**追加一条留痕**，
 *    历史一条不少。界面上的按钮叫「解除（留痕）」，提示里写清「谁、何时、为什么」都会被记下。
 * 2. **「建议拦截」不显示成「已拦截」**。本模块只出建议动作，真的拦不拦单是 9.2.1.3 的事。
 * 3. **「读不到」不显示成「没有风险」**。9.2 审单不可达时后端回 503，界面显示的是
 *    「读不到」而不是一个空的名单。
 * 4. **「9.2 里查无此人」不显示成「这个人干净」** —— 显示「9.2 审单里没有这个人的画像」。
 * 5. **未交付的下游不编造、不跳转**：9.2.1.3 拦截与 11 售后只显示能力声明里的
 *    「量不到 + 归属模块」，页面上没有「去改订单」「去开售后单」这种点了会 404 的入口。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**）。 */
export const BASE = 'channel/buyer-blacklist';

/** 生效范围（受控枚举，后端钉死，前端只做展示映射）。 */
export const SCOPES = [
  { value: 'SHOP_ONLY', label: '仅本店铺' },
  { value: 'ALL_SHOPS', label: '全店铺' },
];

/** 风险等级（受控枚举）。 */
export const RISK_LEVELS = [
  { value: 'LOW', label: '低', tone: 'neutral' as const },
  { value: 'MEDIUM', label: '中', tone: 'warning' as const },
  { value: 'HIGH', label: '高', tone: 'danger' as const },
  { value: 'CRITICAL', label: '极高', tone: 'danger' as const },
];

/** 建议拦截动作（受控枚举；**只是建议**，不是「已拦截」）。 */
export const FLAG_ACTIONS = [
  { value: 'BLOCK', label: '建议拦截', tone: 'danger' as const },
  { value: 'REVIEW', label: '建议人工复核', tone: 'warning' as const },
  { value: 'WATCH', label: '建议观察', tone: 'neutral' as const },
];

/** 逐项能力声明。 */
export interface BuyerBlacklistCapability {
  capabilityCode: string;
  capabilityLabel: string;
  /** 该能力现在**可用**吗（false ⇒ 本模块不代劳，归 ownerModule）。 */
  available: boolean;
  /** 不可用时归谁。 */
  ownerModule: string;
  /** 不可用时的影响说明。 */
  impact: string;
}

/** 逐个上游读面的可用性。 */
export interface BuyerBlacklistSourceState {
  sourceCode: string;
  sourceLabel: string;
  available: boolean;
  note: string;
}

/** 能力声明。 */
export interface BuyerBlacklistCapabilities {
  capabilities: BuyerBlacklistCapability[];
  sources: BuyerBlacklistSourceState[];
  subjectKeySemantics: string;
  scopeSemantics: string;
  releaseSemantics: string;
  appealSemantics: string;
  flagSemantics: string;
  downstreamNote: string;
  boundaryNote: string;
}

/** 一条黑名单登记。 */
export interface BuyerBlacklistEntry {
  entryId: string;
  buyerKey: string;
  buyerName: string;
  buyerPhone: string;
  buyerEmail: string;
  scope: string;
  scopeLabel: string;
  shopCode: string;
  riskLevel: string;
  riskLevelLabel: string;
  reason: string;
  source: string;
  sourceLabel: string;
  sourceRef: string;
  status: string;
  statusLabel: string;
  statusEventType: string;
  statusEventLabel: string;
  statusActor: string;
  statusReason: string;
  statusAt: string;
  registeredAt: string;
  registeredBy: string;
  note: string;
  createdAt: string;
  appealCount: number;
  openAppealCount: number;
  flagCount: number;
}

/** 汇总计数（不受分页影响）。 */
export interface BuyerBlacklistCounts {
  total: number;
  active: number;
  released: number;
  shopOnly: number;
  allShops: number;
  critical: number;
  high: number;
}

/** 黑名单清单。 */
export interface BuyerBlacklistEntryPage {
  items: BuyerBlacklistEntry[];
  page: number;
  size: number;
  total: number;
  counts: BuyerBlacklistCounts;
}

/** 一条状态流转留痕。 */
export interface BuyerBlacklistStatusEvent {
  eventId: string;
  eventSeq: number;
  eventType: string;
  eventLabel: string;
  fromStatus: string;
  toStatus: string;
  riskLevel: string;
  actor: string;
  reason: string;
  appealId: string | null;
  occurredAt: string;
}

/** 申诉单。 */
export interface BuyerBlacklistAppeal {
  appealId: string;
  entryId: string;
  appealReason: string;
  submittedBy: string;
  submittedAt: string;
  decision: string;
  decidedBy: string;
  decidedAt: string | null;
  decisionReason: string;
}

/** 登记详情。 */
export interface BuyerBlacklistEntryDetail {
  entry: BuyerBlacklistEntry;
  events: BuyerBlacklistStatusEvent[];
  appeals: BuyerBlacklistAppeal[];
}

/** 一条订单拦截标记。 */
export interface BuyerBlacklistOrderFlag {
  flagId: string;
  buyerKey: string;
  buyerName: string;
  shopCode: string;
  orderRef: string;
  entryId: string;
  matchedScope: string;
  riskLevel: string;
  recommendedAction: string;
  recommendedActionLabel: string;
  flaggedAt: string;
}

/** 订单拦截标记清单。 */
export interface BuyerBlacklistFlagPage {
  items: BuyerBlacklistOrderFlag[];
  page: number;
  size: number;
  total: number;
  counts: { total: number; block: number; review: number; watch: number };
}

/** 手工录入入参。 */
export interface BuyerBlacklistRegisterInput {
  buyerName: string;
  buyerPhone: string;
  buyerEmail: string;
  scope: string;
  shopCode: string;
  riskLevel: string;
  reason: string;
  note: string;
}

/** 自动录入入参（来自风控信号）。 */
export interface BuyerBlacklistAutoInput {
  buyerKey: string;
  scope: string;
  shopCode: string;
  riskLevel: string;
  reason: string;
  signalRef: string;
  note: string;
}

/** 下单检查入参。 */
export interface BuyerBlacklistCheckInput {
  buyerName: string;
  buyerPhone: string;
  buyerEmail: string;
  shopCode: string;
  orderRef: string;
}

/** 下单检查结果。 */
export interface BuyerBlacklistCheckResult {
  blacklisted: boolean;
  buyerKey: string;
  buyerName: string;
  shopCode: string;
  entryId: string | null;
  matchedScope: string | null;
  matchedScopeLabel: string | null;
  matchedShopCode: string | null;
  riskLevel: string | null;
  riskLevelLabel: string | null;
  reason: string | null;
  recommendedAction: string | null;
  recommendedActionLabel: string | null;
  matchedAt: string | null;
  flagId: string | null;
  flagWritten: boolean;
  upstreamAvailable: boolean;
  upstreamNote: string;
  buyerKnown: boolean;
  latestScoreId: string | null;
  /** 最近一次风险评分；**没评过是 null，不是 0 分**。 */
  latestRiskScore: number | null;
  latestEvaluationStatus: string | null;
  latestRecommendedAction: string | null;
}

// ================================================================ 取数

export async function fetchCapabilities(): Promise<BuyerBlacklistCapabilities> {
  return api<BuyerBlacklistCapabilities>(`${BASE}/capabilities`);
}

export async function fetchEntries(
  page = 0,
  size = 20,
  filters: {
    status?: string;
    scope?: string;
    riskLevel?: string;
    source?: string;
    keyword?: string;
  } = {},
): Promise<BuyerBlacklistEntryPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.status) query.set('status', filters.status);
  if (filters.scope) query.set('scope', filters.scope);
  if (filters.riskLevel) query.set('riskLevel', filters.riskLevel);
  if (filters.source) query.set('source', filters.source);
  if (filters.keyword?.trim()) query.set('keyword', filters.keyword.trim());
  return api<BuyerBlacklistEntryPage>(`${BASE}/entries?${query}`);
}

export async function fetchEntry(entryId: string): Promise<BuyerBlacklistEntryDetail> {
  return api<BuyerBlacklistEntryDetail>(`${BASE}/entries/${entryId}`);
}

export async function register(input: BuyerBlacklistRegisterInput): Promise<BuyerBlacklistEntry> {
  return api<BuyerBlacklistEntry>(`${BASE}/entries`, 'POST', input);
}

export async function registerAuto(input: BuyerBlacklistAutoInput): Promise<BuyerBlacklistEntry> {
  return api<BuyerBlacklistEntry>(`${BASE}/entries/auto`, 'POST', input);
}

export async function release(entryId: string, reason: string): Promise<BuyerBlacklistEntry> {
  return api<BuyerBlacklistEntry>(`${BASE}/entries/${entryId}/release`, 'POST', { reason });
}

export async function submitAppeal(
  entryId: string,
  appealReason: string,
): Promise<BuyerBlacklistAppeal> {
  return api<BuyerBlacklistAppeal>(`${BASE}/entries/${entryId}/appeals`, 'POST', { appealReason });
}

export async function decideAppeal(
  appealId: string,
  decision: string,
  decisionReason: string,
): Promise<BuyerBlacklistAppeal> {
  return api<BuyerBlacklistAppeal>(`${BASE}/appeals/${appealId}/decision`, 'POST', {
    decision,
    decisionReason,
  });
}

/** 下单时检查（命中就落一条订单拦截标记）。 */
export async function check(input: BuyerBlacklistCheckInput): Promise<BuyerBlacklistCheckResult> {
  return api<BuyerBlacklistCheckResult>(`${BASE}/check`, 'POST', input);
}

export async function fetchFlags(
  page = 0,
  size = 20,
  action = '',
): Promise<BuyerBlacklistFlagPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (action) query.set('action', action);
  return api<BuyerBlacklistFlagPage>(`${BASE}/flags?${query}`);
}

// ================================================================ 展示映射（纯函数）

/** 生效范围标签。 */
export function scopeLabel(scope: string): string {
  return SCOPES.find((s) => s.value === scope)?.label ?? scope;
}

/** 风险等级标签。 */
export function riskLevelLabel(level: string): string {
  return RISK_LEVELS.find((l) => l.value === level)?.label ?? level;
}

/** 风险等级的徽章色调。 */
export function riskLevelTone(level: string): 'neutral' | 'warning' | 'danger' {
  return RISK_LEVELS.find((l) => l.value === level)?.tone ?? 'neutral';
}

/** 状态标签。 */
export function statusLabel(status: string): string {
  if (status === 'ACTIVE') return '生效中';
  if (status === 'RELEASED') return '已解除';
  return status;
}

/** 状态的徽章色调。 */
export function statusTone(status: string): 'success' | 'neutral' {
  return status === 'ACTIVE' ? 'success' : 'neutral';
}

/** 建议动作标签。 */
export function actionLabel(action: string): string {
  return FLAG_ACTIONS.find((a) => a.value === action)?.label ?? action;
}

/** 建议动作的徽章色调。 */
export function actionTone(action: string): 'danger' | 'warning' | 'neutral' {
  return FLAG_ACTIONS.find((a) => a.value === action)?.tone ?? 'neutral';
}

/** 申诉裁决的标签。 */
export function decisionLabel(decision: string): string {
  if (decision === 'PENDING') return '待裁决';
  if (decision === 'APPROVED') return '已通过（并解除）';
  if (decision === 'REJECTED') return '已驳回（维持）';
  return decision;
}

/**
 * 风险评分展示：**null 显示「未知」，绝不显示 0**。
 *
 * <p>0 分会被读成「这个买家很干净」，而真相是「我们还没给他评过分」。
 */
export function scoreText(score: number | null): string {
  if (score === null || score === undefined) return '未知';
  return score.toFixed(2);
}

/**
 * 生效范围是否覆盖给定店铺（前端展示用；判定以后端为准）。
 *
 * <p>🔴 这里**只用于提示**，不用于判定 —— 前端算一遍判定就会出现「界面说拦、后端说放」。
 */
export function scopeCovers(scope: string, entryShopCode: string, orderShopCode: string): boolean {
  if (scope === 'ALL_SHOPS') return true;
  const entry = entryShopCode.trim();
  const order = orderShopCode.trim();
  return entry.length > 0 && entry === order;
}

/** 三态文案各不相同（不是同一个空态换个图标）。 */
export function emptyState(
  kind: 'NO_ENTRY' | 'NO_FLAG' | 'NOT_HIT' | 'UPSTREAM_UNKNOWN',
): { title: string; description: string } {
  if (kind === 'NO_ENTRY') {
    return {
      title: '还没有黑名单记录',
      description:
        '黑名单不会凭空出现：手工录入、或者由风控信号自动拉黑。录入之后才会出现在这份清单里。',
    };
  }
  if (kind === 'NO_FLAG') {
    return {
      title: '还没有订单拦截标记',
      description:
        '拦截标记是下单那一刻命中黑名单才落的时点事实 —— 没有命中过的订单不会出现在这里。',
    };
  }
  if (kind === 'NOT_HIT') {
    return {
      title: '这个买家没有被拉黑',
      description:
        '本次判定没有命中生效中的黑名单。🔴 这**只说明这个买家此刻不在名单上**，不代表他没有任何风险。',
    };
  }
  return {
    title: '9.2 审单里没有这个人的画像',
    description:
      '上游正常但查无此人 —— 这是「未知」，不是「干净」。没有画像不等于没有风险，请按需人工判断。',
  };
}
