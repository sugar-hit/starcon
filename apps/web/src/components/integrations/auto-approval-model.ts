import type {
  AutoApprovalCapabilities,
  AutoApprovalCheck,
  AutoApprovalDecisionRow,
  AutoApprovalDecisionView,
  AutoApprovalEscalationRow,
  AutoApprovalEscalationScanView,
  AutoApprovalEvaluateInput,
  AutoApprovalOverview,
  AutoApprovalPolicyInput,
  AutoApprovalPolicyRow,
  AutoApprovalRoutingSnapshot,
  AutoApprovalSignalInput,
  AutoApprovalSignalRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.3.3.1「自动审批」前端模型。
 *
 * 蓝图：「**职责**：自动化审批场景：自动通过低风险小额单据、自动驳回明显不合规单据、
 * 金额分层路由审批人、超时自动升级。输入：审批规则配置；单据风险信号。
 * 输出：自动审批结果 + 决策依据记录。关系：上游 = 17.4 审批；下游 = 各业务系统。
 * **自动审批只处理"明确安全"的场景**（小额 + 供应商已验证 + 无风险信号），
 * 其余仍走人工——审批是内控底线。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「自动通过」必须有据可查**。每条决策都带**逐条判定**（checks）与**当时的规则快照**，
 *    页面把「哪一条不成立」原样显示出来 —— 只有一个「已自动通过」的绿标签，
 *    等于让人相信一台没人能解释的机器。
 * 2. **「自动驳回」与「走人工」必须一眼分得开**。驳回只发生在**确定性违规**上；
 *    主观风险（风险偏高）走人工，页面**不能**把它显示成「已驳回」。
 * 3. **「上游没有程序化决策通道」要写在页面上**。自动决策**不产生待办**，
 *    必须走人工的单据才是真的提交给了上游人工审批。
 * 4. **金额分层路由的区间口径与上游一致**（左闭右开），页面显示的是**命中哪一档**，
 *    不是「大概按金额算的」。
 * 5. **加载 / 空 / 错误三态齐全**：空态区分「还没登记过」与「筛选无结果」。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物）。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**）。 */
const base = 'platform/auto-approvals';

/** 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。 */
function query(params: Record<string, string | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, value);
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：这条模块能做什么、**不能**做什么、边界归谁。 */
export function fetchCapabilities(): Promise<AutoApprovalCapabilities> {
  return api<AutoApprovalCapabilities>(`${base}/capabilities`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<AutoApprovalOverview> {
  return api<AutoApprovalOverview>(`${base}/overview`);
}

/** 规则配置列表（真筛选 + 真关键词）。 */
export function fetchPolicies(params?: {
  documentTypeCode?: string | undefined;
  currencyCode?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
}): Promise<AutoApprovalPolicyRow[]> {
  return api<AutoApprovalPolicyRow[]>(`${base}/policies?${query({ ...params })}`);
}

/** 风险信号码表列表。 */
export function fetchSignals(params?: {
  signalKind?: string | undefined;
  status?: string | undefined;
  keyword?: string | undefined;
}): Promise<AutoApprovalSignalRow[]> {
  return api<AutoApprovalSignalRow[]>(`${base}/signals?${query({ ...params })}`);
}

/** 决策台账列表（真筛选 + 真关键词）。 */
export function fetchDecisions(params?: {
  documentTypeCode?: string | undefined;
  documentRef?: string | undefined;
  decision?: string | undefined;
  executionState?: string | undefined;
  keyword?: string | undefined;
}): Promise<AutoApprovalDecisionRow[]> {
  return api<AutoApprovalDecisionRow[]>(`${base}/decisions?${query({ ...params })}`);
}

/** 一条决策详情（含**决策依据快照**）。 */
export function fetchDecision(id: string): Promise<AutoApprovalDecisionView> {
  return api<AutoApprovalDecisionView>(`${base}/decisions/${id}`);
}

/** 超时升级台账列表。 */
export function fetchEscalations(params?: {
  status?: string | undefined;
  nodeKey?: string | undefined;
}): Promise<AutoApprovalEscalationRow[]> {
  return api<AutoApprovalEscalationRow[]>(`${base}/escalations?${query({ ...params })}`);
}

// ================================================================ 写入

/** 登记或修改一条自动审批规则。 */
export function savePolicy(input: AutoApprovalPolicyInput): Promise<AutoApprovalPolicyRow> {
  return api<AutoApprovalPolicyRow>(`${base}/policies`, 'POST', input);
}

/** 停用一条规则（**不删**：历史决策要能复现）。 */
export function disablePolicy(id: string): Promise<AutoApprovalPolicyRow> {
  return api<AutoApprovalPolicyRow>(`${base}/policies/${id}/disable`, 'POST');
}

/** 登记一个风险信号码。 */
export function createSignal(input: AutoApprovalSignalInput): Promise<AutoApprovalSignalRow> {
  return api<AutoApprovalSignalRow>(`${base}/signals`, 'POST', input);
}

/** 停用一个风险信号码（**不删**）。 */
export function disableSignal(id: string): Promise<AutoApprovalSignalRow> {
  return api<AutoApprovalSignalRow>(`${base}/signals/${id}/disable`, 'POST');
}

/** 发起一次自动审批判定（判定 + 记录）。 */
export function evaluateDecision(
  input: AutoApprovalEvaluateInput,
): Promise<AutoApprovalDecisionView> {
  return api<AutoApprovalDecisionView>(`${base}/decisions`, 'POST', input);
}

/** 扫描超时（上游只定义规则、不实现定时器 ⇒ 扫描是本模块的活）。 */
export function scanEscalations(): Promise<AutoApprovalEscalationScanView> {
  return api<AutoApprovalEscalationScanView>(`${base}/escalations/scan`, 'POST');
}

/** 执行一次超时升级（以**发起人自己**的身份在上游执行）。 */
export function executeEscalation(id: string): Promise<AutoApprovalEscalationRow> {
  return api<AutoApprovalEscalationRow>(`${base}/escalations/${id}/execute`, 'POST');
}

// ================================================================ 枚举与文案

export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/** 决策的三个取值 —— **没有第四个**。 */
export const DECISIONS = ['AUTO_APPROVED', 'AUTO_REJECTED', 'MANUAL_REQUIRED'] as const;

/** 风险信号的两类性质 —— 决定「能不能自动驳回」。 */
export const SIGNAL_KINDS = ['VIOLATION', 'RISK'] as const;

/** 决策的落点状态。 */
export const EXECUTION_STATES = ['NOT_REQUIRED', 'SUBMITTED_TO_APPROVAL', 'SUBMIT_FAILED'] as const;

/** 升级台账的状态。 */
export const ESCALATION_STATUSES = ['DETECTED', 'EXECUTED', 'FAILED', 'SUPERSEDED'] as const;

/** 上游定义的超时动作。 */
export const ESCALATION_ACTIONS = ['ESCALATE', 'AUTO_APPROVE'] as const;

/** 规则状态。 */
export const POLICY_STATUSES = ['ACTIVE', 'DRAFT', 'DISABLED'] as const;

/** 决策的中文说法。 */
export function decisionLabel(decision: string): string {
  switch (decision) {
    case 'AUTO_APPROVED':
      return '自动通过';
    case 'AUTO_REJECTED':
      return '自动驳回';
    case 'MANUAL_REQUIRED':
      return '走人工审批';
    default:
      return decision;
  }
}

/** 决策的语气：自动通过是绿的，自动驳回是红的，走人工是**中性**的。 */
export function decisionTone(decision: string): Tone {
  switch (decision) {
    case 'AUTO_APPROVED':
      return 'ok';
    case 'AUTO_REJECTED':
      return 'danger';
    case 'MANUAL_REQUIRED':
      return 'warn';
    default:
      return 'muted';
  }
}

/** 信号性质的中文说法。 */
export function signalKindLabel(kind: string): string {
  switch (kind) {
    case 'VIOLATION':
      return '确定性违规（可自动驳回）';
    case 'RISK':
      return '主观风险（只走人工）';
    default:
      return kind;
  }
}

/** 信号性质的语气：确定性违规是红的，主观风险是**黄**的（它不会驳回，但会拦下自动通过）。 */
export function signalKindTone(kind: string): Tone {
  switch (kind) {
    case 'VIOLATION':
      return 'danger';
    case 'RISK':
      return 'warn';
    default:
      return 'muted';
  }
}

/** 决策落点的中文说法。 */
export function executionStateLabel(state: string): string {
  switch (state) {
    case 'NOT_REQUIRED':
      return '不需要人工（自动决策）';
    case 'SUBMITTED_TO_APPROVAL':
      return '已提交上游人工审批';
    case 'SUBMIT_FAILED':
      return '提交上游失败（仍需人工）';
    default:
      return state;
  }
}

/** 升级台账状态的中文说法。 */
export function escalationStatusLabel(status: string): string {
  switch (status) {
    case 'DETECTED':
      return '已判定，待执行';
    case 'EXECUTED':
      return '已执行';
    case 'FAILED':
      return '执行失败';
    case 'SUPERSEDED':
      return '已被后一条取代';
    default:
      return status;
  }
}

/** 超时动作的中文说法。 */
export function escalationActionLabel(action: string): string {
  switch (action) {
    case 'ESCALATE':
      return '升级给另一类审批人';
    case 'AUTO_APPROVE':
      return '超时视同通过';
    default:
      return action;
  }
}

/** 规则状态的中文说法。 */
export function policyStatusLabel(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return '生效中';
    case 'DRAFT':
      return '草稿';
    case 'DISABLED':
      return '已停用';
    default:
      return status;
  }
}

/** 不成立的判定（页面用它指出「到底哪一条没过」）。 */
export function failedChecks(checks: AutoApprovalCheck[]): AutoApprovalCheck[] {
  return checks.filter((c) => !c.passed);
}

/**
 * 一句话说明「为什么是这个结论」。
 *
 * 🔴 自动通过时也要有话说：它是**三个条件都成立**才成立的，
 * 页面必须把这一点显示出来，而不是只给一个绿标签。
 */
export function checkSummary(checks: AutoApprovalCheck[]): string {
  const failed = failedChecks(checks);
  if (failed.length === 0) {
    const names = checks.map((c) => c.code).join(' + ');
    return `全部条件成立：${names}`;
  }
  return failed.map((c) => `${c.code}（${c.detail}）`).join('；');
}

/** 金额分层路由的一句话说明。 */
export function routingSummary(routing: AutoApprovalRoutingSnapshot | null): string {
  if (!routing) return '无路由信息';
  if (!routing.routed) return routing.note || '未命中任何金额分支';
  const range =
    routing.minAmount !== null || routing.maxAmount !== null
      ? `金额区间 [${routing.minAmount ?? '-∞'}, ${routing.maxAmount ?? '+∞'})`
      : '未限定区间';
  const approver =
    routing.approverType === 'ROLE'
      ? '角色审批人'
      : routing.approverType === 'POSITION'
        ? '岗位审批人'
        : routing.approverType === 'USER'
          ? '指定审批人'
          : '审批人未知';
  return `命中分支「${routing.branchName ?? '-'}」· ${range} · 目标节点 ${routing.targetNodeKey ?? '-'}${
    routing.targetNodeName ? `（${routing.targetNodeName}）` : ''
  } · ${approver}`;
}

/** 上游通道的一句话说明（能力声明里那两段话的可读版）。 */
export function upstreamChannelHint(capabilities: AutoApprovalCapabilities | null): string {
  if (!capabilities) return '';
  return `${capabilities.upstreamDecisionChannelNote} ${capabilities.upstreamIntakeChannel}`;
}

/** 格式化金额（**不**用 toLocaleString 拼币种，币种单独显示）。 */
export function formatMoney(amount: number | null | undefined, currency?: string | null): string {
  if (amount === null || amount === undefined) return '—';
  return currency ? `${amount} ${currency}` : String(amount);
}

/** 格式化时刻（**显示的是会话时区**，跨时区协作时要能一眼看出是本地时间）。 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString();
}

// ================================================================ 表单

export type PolicyForm = {
  ruleCode: string;
  ruleName: string;
  documentTypeCode: string;
  currencyCode: string;
  autoApproveMaxAmount: string;
  amountHardLimit: string;
  upstreamFlowId: string;
  status: string;
  priority: string;
  remark: string;
};

export type SignalForm = {
  signalCode: string;
  signalName: string;
  signalKind: string;
  description: string;
};

export type EvaluateForm = {
  documentTypeCode: string;
  documentRef: string;
  subjectTitle: string;
  sourceSystem: string;
  currencyCode: string;
  amount: string;
  supplierId: string;
  supplierVerified: boolean;
  riskSignals: string;
};

/** 空规则表单。 */
export const EMPTY_POLICY: PolicyForm = {
  ruleCode: '',
  ruleName: '',
  documentTypeCode: '',
  currencyCode: 'USD',
  autoApproveMaxAmount: '',
  amountHardLimit: '',
  upstreamFlowId: '',
  status: 'ACTIVE',
  priority: '0',
  remark: '',
};

/** 空信号表单。 */
export const EMPTY_SIGNAL: SignalForm = {
  signalCode: '',
  signalName: '',
  signalKind: 'RISK',
  description: '',
};

/** 空判定表单。 */
export const EMPTY_EVALUATE: EvaluateForm = {
  documentTypeCode: '',
  documentRef: '',
  subjectTitle: '',
  sourceSystem: '',
  currencyCode: 'USD',
  amount: '',
  supplierId: '',
  supplierVerified: true,
  riskSignals: '',
};

function isNumber(value: string): boolean {
  return value.trim() !== '' && Number.isFinite(Number(value));
}

/**
 * 规则表单校验（与服务层同一套口径）。
 *
 * 🔴 **小额上限不能大于硬上限**：反过来的话同一笔金额会同时「够小」又「越界」，
 * 判定自相矛盾 —— 这条在服务端也挡着，这里先给一次人话。
 */
export function validatePolicyForm(form: PolicyForm): string | null {
  if (!form.ruleCode.trim()) return '规则编码不能为空';
  if (!form.ruleName.trim()) return '规则名称不能为空';
  if (!form.documentTypeCode.trim()) return '单据类型不能为空';
  if (!/^[A-Z]{3}$/.test(form.currencyCode.trim())) return '币种必须是三个大写字母（如 USD）';
  if (form.autoApproveMaxAmount.trim() && !isNumber(form.autoApproveMaxAmount))
    return '小额上限必须是一个数字';
  if (form.amountHardLimit.trim() && !isNumber(form.amountHardLimit)) return '硬上限必须是一个数字';
  if (Number(form.autoApproveMaxAmount) < 0 || Number(form.amountHardLimit) < 0)
    return '金额上限不能为负';
  if (
    form.autoApproveMaxAmount.trim() &&
    form.amountHardLimit.trim() &&
    Number(form.autoApproveMaxAmount) > Number(form.amountHardLimit)
  )
    return '小额上限不能大于硬上限';
  if (form.priority.trim() && !Number.isInteger(Number(form.priority)))
    return '优先级必须是一个整数';
  return null;
}

/** 信号表单校验。 */
export function validateSignalForm(form: SignalForm): string | null {
  if (!form.signalCode.trim()) return '信号编码不能为空';
  if (!form.signalName.trim()) return '信号名称不能为空';
  if (!SIGNAL_KINDS.includes(form.signalKind as (typeof SIGNAL_KINDS)[number]))
    return '信号性质只能是确定性违规或主观风险';
  return null;
}

/**
 * 判定表单校验。
 *
 * 🔴 **金额与币种要么都有、要么都没有**：只有金额没有币种时，
 * 「小额」与「金额落在哪一档」都没有唯一答案。
 */
export function validateEvaluateForm(form: EvaluateForm): string | null {
  if (!form.documentTypeCode.trim()) return '单据类型不能为空';
  if (!form.documentRef.trim()) return '单据号不能为空';
  if (!form.sourceSystem.trim()) return '来源系统不能为空';
  if (form.currencyCode.trim() && !/^[A-Z]{3}$/.test(form.currencyCode.trim()))
    return '币种必须是三个大写字母（如 USD）';
  if (form.amount.trim() && !isNumber(form.amount)) return '金额必须是一个数字';
  if (Number(form.amount) < 0) return '金额不能为负';
  if (Boolean(form.amount.trim()) !== Boolean(form.currencyCode.trim()))
    return '金额与币种必须同时提供（只有一边时小额没有唯一答案）';
  return null;
}

/** 把风险信号输入（逗号 / 顿号 / 换行分隔）拆成码表。 */
export function parseSignalCodes(raw: string): string[] {
  return raw
    .split(/[,，、\n]/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}
