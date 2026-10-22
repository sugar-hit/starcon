import { api } from '../../lib/api';

/**
 * 16.2.1.2 拦截执行 前端模型。
 *
 * 蓝图：「在关键业务节点执行合规校验并阻断：刊登发布前、发货前、结算提现前；
 * 展示拦截原因与解除方式（提交资质、申请豁免）。输入：合规规则集；业务动作触发的校验请求（商品/订单/店铺）。
 * 输出：校验结论（通过/警告/阻断）+ 原因 + 解除入口；豁免申请与审批。
 * 关系：上游 = 16.2.1.1；下游 = 4.3.1.1 发布、8.3.4.1 发货、12 提现。
 * **豁免必须有审批与理由**，否则阻断会被随意绕过。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「判不了」是一个独立状态**，页面上**不与「通过」混为一谈** ——
 *    结论只有 PASS / WARN / BLOCK / UNDECIDABLE 四个取值，前端**不重算**结论。
 * 2. **阻断在服务端强制**：页面显示的 `blocked` 与逐条原因都是**后端算好的**，
 *    前端只是显示；前端自己判断「有没有违禁词」是另一套口径，必然和后端对不上。
 * 3. **豁免要走审批**：申请理由与有效期**必填**，申请人**不能**审批自己；
 *    页面上把这条纪律的原文显示出来，并且「审批 / 驳回」按钮只有非申请人看得到。
 * 4. **因豁免放行必须留痕**：被豁免放行的原因**照样显示**，并指向豁免单 id。
 * 5. **未交付的下游灰掉**：8.3.4.1 发货（跨服务）与 12 提现（服务未落地）
 *    在能力声明里如实标出，**不编**「已拦截提现」这类说法。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有 channel 分支）。 */
export const BASE = 'channel/compliance-intercepts';

/** 🔴 拦截点值域（与后端 CHECK 逐字一致）：刊登发布前 / 发货前 / 结算提现前。 */
export const SCENES = ['LISTING_PUBLISH', 'SHIPMENT', 'WITHDRAWAL'] as const;

/** 拦截点的人话名。 */
export const SCENE_LABELS: Record<string, string> = {
  LISTING_PUBLISH: '刊登发布前',
  SHIPMENT: '发货前',
  WITHDRAWAL: '结算提现前',
};

/** 🔴 校验对象值域：商品 / 订单 / 店铺。 */
export const SUBJECT_TYPES = ['PRODUCT', 'ORDER', 'SHOP'] as const;

/** 校验对象的人话名。 */
export const SUBJECT_TYPE_LABELS: Record<string, string> = {
  PRODUCT: '商品',
  ORDER: '订单',
  SHOP: '店铺',
};

/** 🔴 结论值域：**三态 + 一个「判不了」**。 */
export const CONCLUSIONS = ['PASS', 'WARN', 'BLOCK', 'UNDECIDABLE'] as const;

/** 结论的人话名。 */
export const CONCLUSION_LABELS: Record<string, string> = {
  PASS: '通过',
  WARN: '警告',
  BLOCK: '阻断',
  UNDECIDABLE: '判不了',
};

/** 🔴 原因码值域（具名）。 */
export const REASON_CODES = [
  'WORD_TERM_HIT',
  'REQUIREMENT_NOT_SATISFIED',
  'RULE_SET_EMPTY',
  'RULE_VERSION_UNRESOLVED',
  'RULE_TERMS_MISSING',
  'REQUIREMENT_UNRESOLVED',
  'RULE_SCOPE_UNRESOLVED',
  'RULE_READ_FAILED',
] as const;

/** 原因码的人话名。 */
export const REASON_CODE_LABELS: Record<string, string> = {
  WORD_TERM_HIT: '命中词条',
  REQUIREMENT_NOT_SATISFIED: '要求未满足',
  RULE_SET_EMPTY: '规则集为空',
  RULE_VERSION_UNRESOLVED: '适用版本查不到',
  RULE_TERMS_MISSING: '规则词库为空',
  REQUIREMENT_UNRESOLVED: '要求状态缺失',
  RULE_SCOPE_UNRESOLVED: '适用范围无法确定',
  RULE_READ_FAILED: '规则读不到',
};

/** 豁免状态值域。 */
export const EXEMPTION_STATUSES = ['PENDING', 'APPROVED', 'REJECTED'] as const;

/** 豁免状态的人话名。 */
export const EXEMPTION_STATUS_LABELS: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已批准',
  REJECTED: '已驳回',
};

/** 拦截点筛选的可选项。 */
export const SCENE_OPTIONS = [
  { value: '', label: '全部拦截点' },
  { value: 'LISTING_PUBLISH', label: '刊登发布前' },
  { value: 'SHIPMENT', label: '发货前' },
  { value: 'WITHDRAWAL', label: '结算提现前' },
] as const;

/** 校验对象筛选的可选项。 */
export const SUBJECT_TYPE_OPTIONS = [
  { value: '', label: '全部对象' },
  { value: 'PRODUCT', label: '商品' },
  { value: 'ORDER', label: '订单' },
  { value: 'SHOP', label: '店铺' },
] as const;

/** 结论筛选的可选项。 */
export const CONCLUSION_OPTIONS = [
  { value: '', label: '全部结论' },
  { value: 'PASS', label: '通过' },
  { value: 'WARN', label: '警告' },
  { value: 'BLOCK', label: '阻断' },
  { value: 'UNDECIDABLE', label: '判不了' },
] as const;

/** 豁免状态筛选的可选项。 */
export const EXEMPTION_STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'PENDING', label: '待审批' },
  { value: 'APPROVED', label: '已批准' },
  { value: 'REJECTED', label: '已驳回' },
] as const;

// ==================================================================
// 类型
// ==================================================================

/** 一个下游（或维度）的交付状态。 */
export interface ComplianceInterceptCapabilityGap {
  moduleId: string;
  moduleName: string;
  ownerModule: string;
  available: boolean;
  impact: string;
}

/** 能力声明。 */
export interface ComplianceInterceptCapabilities {
  gaps: ComplianceInterceptCapabilityGap[];
  scenes: string[];
  sceneLabels: Record<string, string>;
  subjectTypes: string[];
  subjectTypeLabels: Record<string, string>;
  conclusions: string[];
  conclusionLabels: Record<string, string>;
  reasonCodes: string[];
  reasonCodeLabels: Record<string, string>;
  exemptionStatuses: string[];
  exemptionStatusLabels: Record<string, string>;
  conclusionNote: string;
  enforcementNote: string;
  exemptionNote: string;
  upstreamNote: string;
  downstreamNote: string;
}

/** 一个拦截点的策略。 */
export interface ComplianceInterceptPolicyRow {
  scene: string;
  sceneLabel: string;
  enabled: boolean;
  undecidableAction: string;
  note: string;
  updatedAt: string | null;
}

/** 策略清单。 */
export interface ComplianceInterceptPolicySet {
  items: ComplianceInterceptPolicyRow[];
  note: string;
}

/** 一条原因。 */
export interface ComplianceInterceptReasonRow {
  reasonId: string;
  reasonCode: string;
  reasonCodeLabel: string;
  reasonText: string;
  ruleId: string | null;
  ruleVersionId: string | null;
  ruleCode: string | null;
  ruleType: string | null;
  severity: string | null;
  fieldKey: string | null;
  matchedTerm: string | null;
  waived: boolean;
  exemptionId: string | null;
  undecidable: boolean;
}

/** 因豁免放行的指向。 */
export interface ComplianceInterceptExemptionRef {
  exemptionId: string;
  ruleId: string;
  ruleCode: string | null;
  validFrom: string | null;
  validTo: string | null;
  note: string;
}

/** 解除方式。 */
export interface ComplianceInterceptRemedy {
  reasonCode: string;
  remedy: string;
  canApplyExemption: boolean;
}

/** 一次判定的结论。 */
export interface ComplianceInterceptDecision {
  decisionId: string;
  scene: string;
  sceneLabel: string;
  subjectType: string;
  subjectTypeLabel: string;
  subjectId: string;
  shopId: string | null;
  platformId: string | null;
  marketplaceId: string | null;
  conclusion: string;
  conclusionLabel: string;
  blocked: boolean;
  releaseGranted: boolean;
  ruleCount: number;
  reasonCount: number;
  blockingReasonCount: number;
  waivedReasonCount: number;
  undecidableReasonCount: number;
  reasons: ComplianceInterceptReasonRow[];
  exemptions: ComplianceInterceptExemptionRef[];
  remedies: ComplianceInterceptRemedy[];
  decidedAt: string;
  decidedBy: string | null;
  note: string;
}

/** 判定历史。 */
export interface ComplianceInterceptDecisionPage {
  items: ComplianceInterceptDecision[];
  total: number;
  page: number;
  size: number;
  passCount: number;
  warnCount: number;
  blockCount: number;
  undecidableCount: number;
  note: string;
}

/** 一张豁免单。 */
export interface ComplianceInterceptExemptionRow {
  exemptionId: string;
  scene: string;
  sceneLabel: string;
  subjectType: string;
  subjectTypeLabel: string;
  subjectId: string;
  ruleId: string;
  ruleCode: string | null;
  ruleTitle: string | null;
  status: string;
  statusLabel: string;
  applicantId: string | null;
  applicantReason: string;
  approverId: string | null;
  decisionNote: string;
  validFrom: string;
  validTo: string;
  expired: boolean;
  effective: boolean;
  /** 🔴 当前登录人能不能审批这张单（**服务端算好**：申请人不能审批自己） */
  decidable: boolean;
  decidedAt: string | null;
  createdAt: string;
}

/** 豁免单清单。 */
export interface ComplianceInterceptExemptionPage {
  items: ComplianceInterceptExemptionRow[];
  total: number;
  page: number;
  size: number;
  pendingCount: number;
  approvedCount: number;
  rejectedCount: number;
  effectiveCount: number;
  note: string;
}

/** 被校验的一个文本字段。 */
export interface ComplianceInterceptFieldInput {
  key: string;
  value: string;
}

/** 一条要求状态。 */
export interface ComplianceInterceptRequirementInput {
  ruleCode: string;
  status: string;
  evidence: string;
}

// ==================================================================
// 接口
// ==================================================================

export async function fetchCapabilities(): Promise<ComplianceInterceptCapabilities> {
  return api<ComplianceInterceptCapabilities>(`${BASE}/capabilities`);
}

/** 拦截策略清单。 */
export async function fetchPolicy(): Promise<ComplianceInterceptPolicySet> {
  return api<ComplianceInterceptPolicySet>(`${BASE}/policy`);
}

/** 🔴 改拦截策略（**拦截默认不生效**，这里才开）。 */
export async function savePolicy(
  scene: string,
  input: { enabled: string; undecidableAction: string; note?: string },
): Promise<ComplianceInterceptPolicyRow> {
  return api<ComplianceInterceptPolicyRow>(`${BASE}/policy/${scene}`, 'PUT', input);
}

/** 🔴 判定端点：跑一次合规校验。 */
export async function evaluate(input: {
  scene: string;
  subjectType: string;
  subjectId: string;
  shopId?: string | null;
  platformId?: string | null;
  marketplaceId?: string | null;
  fields?: ComplianceInterceptFieldInput[];
  requirements?: ComplianceInterceptRequirementInput[];
}): Promise<ComplianceInterceptDecision> {
  return api<ComplianceInterceptDecision>(`${BASE}/decisions`, 'POST', input);
}

/** 判定历史。 */
export async function fetchDecisions(
  page = 0,
  size = 20,
  filters: {
    scene?: string;
    subjectType?: string;
    conclusion?: string;
    subjectId?: string;
    shopId?: string;
  } = {},
): Promise<ComplianceInterceptDecisionPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.scene) query.set('scene', filters.scene);
  if (filters.subjectType) query.set('subjectType', filters.subjectType);
  if (filters.conclusion) query.set('conclusion', filters.conclusion);
  if (filters.subjectId) query.set('subjectId', filters.subjectId);
  if (filters.shopId) query.set('shopId', filters.shopId);
  return api<ComplianceInterceptDecisionPage>(`${BASE}/decisions?${query.toString()}`);
}

/** 单次判定详情。 */
export async function fetchDecision(decisionId: string): Promise<ComplianceInterceptDecision> {
  return api<ComplianceInterceptDecision>(`${BASE}/decisions/${decisionId}`);
}

/** 🔴 申请豁免（**理由必填 + 有效期必填**）。 */
export async function applyExemption(input: {
  scene: string;
  subjectType: string;
  subjectId: string;
  ruleId: string;
  reason: string;
  validFrom?: string | null;
  validTo: string;
}): Promise<ComplianceInterceptExemptionRow> {
  return api<ComplianceInterceptExemptionRow>(`${BASE}/exemptions`, 'POST', input);
}

/** 豁免单清单。 */
export async function fetchExemptions(
  page = 0,
  size = 20,
  filters: {
    scene?: string;
    status?: string;
    subjectType?: string;
    subjectId?: string;
    ruleCode?: string;
  } = {},
): Promise<ComplianceInterceptExemptionPage> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.scene) query.set('scene', filters.scene);
  if (filters.status) query.set('status', filters.status);
  if (filters.subjectType) query.set('subjectType', filters.subjectType);
  if (filters.subjectId) query.set('subjectId', filters.subjectId);
  if (filters.ruleCode) query.set('ruleCode', filters.ruleCode);
  return api<ComplianceInterceptExemptionPage>(`${BASE}/exemptions?${query.toString()}`);
}

/** 豁免单详情。 */
export async function fetchExemption(
  exemptionId: string,
): Promise<ComplianceInterceptExemptionRow> {
  return api<ComplianceInterceptExemptionRow>(`${BASE}/exemptions/${exemptionId}`);
}

/** 🔴 审批豁免（**申请人 ≠ 审批人**）。 */
export async function approveExemption(
  exemptionId: string,
  note: string,
): Promise<ComplianceInterceptExemptionRow> {
  return api<ComplianceInterceptExemptionRow>(`${BASE}/exemptions/${exemptionId}/approve`, 'POST', {
    note,
  });
}

/** 🔴 驳回豁免（**申请人 ≠ 审批人**）。 */
export async function rejectExemption(
  exemptionId: string,
  note: string,
): Promise<ComplianceInterceptExemptionRow> {
  return api<ComplianceInterceptExemptionRow>(`${BASE}/exemptions/${exemptionId}/reject`, 'POST', {
    note,
  });
}

// ==================================================================
// 纯逻辑（可单测）
// ==================================================================

/** 结论的展示色调（**与 Badge 的 tone 同名**，免得页面再翻译一遍）。 */
export function conclusionTone(conclusion: string): 'success' | 'warning' | 'danger' | 'neutral' {
  if (conclusion === 'PASS') return 'success';
  if (conclusion === 'WARN') return 'warning';
  if (conclusion === 'BLOCK') return 'danger';
  // 🔴 「判不了」用**中性灰**：它既不是通过也不是违规 —— 用红色会冤枉规则，用绿色会放行。
  return 'neutral';
}

/** 结论的人话名（后端给了就用后端的，页面**不自己编**）。 */
export function conclusionLabel(decision: ComplianceInterceptDecision): string {
  return decision.conclusionLabel || CONCLUSION_LABELS[decision.conclusion] || decision.conclusion;
}

/** 🔴 判定里**真正拦住**的原因（未豁免的阻断级）。 */
export function blockingReasons(
  decision: ComplianceInterceptDecision,
): ComplianceInterceptReasonRow[] {
  return decision.reasons.filter((reason) => !reason.waived && reason.severity === 'BLOCKING');
}

/** 🔴 因豁免放行的原因（**照样显示**，并指向豁免单 id）。 */
export function waivedReasons(
  decision: ComplianceInterceptDecision,
): ComplianceInterceptReasonRow[] {
  return decision.reasons.filter((reason) => reason.waived);
}

/** 🔴 判不了的原因。 */
export function undecidableReasons(
  decision: ComplianceInterceptDecision,
): ComplianceInterceptReasonRow[] {
  return decision.reasons.filter((reason) => reason.undecidable);
}

/** 判定历史空态的种类。 */
export type DecisionEmptyKind = 'NO_DATA' | 'NO_MATCH' | 'NO_BLOCK';

/** 空态的种类。 */
export function decisionEmptyKind(
  total: number,
  filtered: boolean,
  blockCount: number,
): DecisionEmptyKind {
  if (total === 0 && !filtered) return 'NO_DATA';
  if (total === 0) return 'NO_MATCH';
  if (blockCount === 0) return 'NO_BLOCK';
  return 'NO_DATA';
}

/** 空态的标题与说明（**四态分开说**）。 */
export function decisionEmptyState(kind: DecisionEmptyKind): {
  title: string;
  description: string;
} {
  switch (kind) {
    case 'NO_DATA':
      return {
        title: '还没有任何判定记录',
        description:
          '判定会在业务动作触发校验时落库（刊登派发前会真的读服务端结论），也可以在页面上手动跑一次。',
      };
    case 'NO_MATCH':
      return {
        title: '筛选条件下没有判定记录',
        description: '当前筛选（拦截点 / 对象 / 结论 / 主体）没有命中任何一次判定，换个条件再试。',
      };
    default:
      return {
        title: '当前结果里没有阻断',
        description:
          '这段时间的判定没有出现过阻断 —— 但「判不了」仍然是独立的一类，别把它当成「通过」。',
      };
  }
}

/** 豁免单空态的种类。 */
export type ExemptionEmptyKind = 'NO_DATA' | 'NO_MATCH' | 'NO_PENDING';

/** 豁免单空态的种类。 */
export function exemptionEmptyKind(
  total: number,
  filtered: boolean,
  pendingCount: number,
): ExemptionEmptyKind {
  if (total === 0 && !filtered) return 'NO_DATA';
  if (total === 0) return 'NO_MATCH';
  if (pendingCount === 0) return 'NO_PENDING';
  return 'NO_DATA';
}

/** 豁免单空态的标题与说明。 */
export function exemptionEmptyState(kind: ExemptionEmptyKind): {
  title: string;
  description: string;
} {
  switch (kind) {
    case 'NO_DATA':
      return {
        title: '还没有任何豁免申请',
        description:
          '豁免必须走「申请 → 别人审批」，而且要有理由和有效期：被阻断时在判定详情里申请即可。',
      };
    case 'NO_MATCH':
      return {
        title: '筛选条件下没有豁免单',
        description: '当前筛选（拦截点 / 状态 / 对象 / 主体 / 规则编码）没有命中任何一张豁免单。',
      };
    default:
      return {
        title: '没有待审批的豁免',
        description: '当前结果里没有待审批的豁免单 —— 已批准与已驳回的单子仍在下面可查。',
      };
  }
}

/** 豁免有效期的可读文本。 */
export function validityText(exemption: ComplianceInterceptExemptionRow): string {
  const from = exemption.validFrom ? exemption.validFrom.slice(0, 10) : '—';
  const to = exemption.validTo ? exemption.validTo.slice(0, 10) : '—';
  return `${from} ~ ${to}`;
}

/** 主体标签（对象 + 逻辑 id）。 */
export function subjectText(decision: ComplianceInterceptDecision): string {
  return `${decision.subjectTypeLabel || decision.subjectType} · ${decision.subjectId}`;
}
