import type {
  AutomationRuleActionInput,
  AutomationRuleActionRow,
  AutomationRuleApplicableRow,
  AutomationRuleCapabilities,
  AutomationRuleConditionInput,
  AutomationRuleConditionRow,
  AutomationRuleDetail,
  AutomationRuleDryRunInput,
  AutomationRuleEventTypeRow,
  AutomationRuleExecutionInput,
  AutomationRuleExecutionRow,
  AutomationRuleInput,
  AutomationRuleOverview,
  AutomationRulePreview,
  AutomationRulePreviewInput,
  AutomationRuleReorderInput,
  AutomationRuleRow,
  AutomationRuleScopeInput,
  AutomationRuleScopeRow,
  AutomationRuleUpstreamShopRow,
  AutomationRuleUpstreamWarehouseRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.3.1.1「规则列表」前端模型。
 *
 * 蓝图：「**规则列表**：管理自动化规则：规则名称、触发事件、条件（过滤）、动作（多步）、
 * 优先级、启用范围（租户/店铺/仓库）、试运行开关、执行统计。
 * 输入：规则增删改；启用停用；排序。输出：规则档案 + 执行统计。
 * 关系：上游 = 各业务事件；下游 = 18.3.1.2 编辑器、18.3.2.1 执行日志。
 * **规则要能看出最后一次执行结果**，否则坏规则静默失败无人察觉。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **执行结果必须看得见**。规则列表上每一行都带「最后一次执行」的
 *    **时间 / 结果 / 失败原因**三件套 —— 不是一个「有没有执行过」的勾。
 *    失败原因**不许截断成「失败」两个字**。
 * 2. **坏规则要能被一眼挑出来**。连续失败达到阈值的规则带「连续失败」标记，
 *    页面有一个**只看坏规则**的筛选档 —— 页面**不提供**把坏规则悄悄显示成正常的入口。
 * 3. **试运行是独立状态**。页面上「试运行」与「启用 / 停用」是**两个**标记，
 *    试运行的规则明写「不产生真实副作用」。
 * 4. **执行引擎未交付就说未交付**。18.3.2 尚未交付 ⇒ 页面**不提供**
 *    「执行规则」的按钮（一个点了必然失败的按钮会让运营以为「点一下就能跑」），
 *    只提供「登记一次执行结果」（本模块自己的统计台账）。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），
 * 前端**不手抄**字符串联合 —— 枚举值由后端的字典列给出。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/automation-rules';

/**
 * 拼查询串：空值不拼（拼 `undefined=` 上游会当成「筛选了这个值」）。
 */
function query(params: Record<string, string | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, value);
  }
  return search.toString();
}

// ================================================================ 读取

/** 能力声明：哪些真交付、哪些还没交付、归谁。 */
export function fetchCapabilities(): Promise<AutomationRuleCapabilities> {
  return api<AutomationRuleCapabilities>(`${base}/capabilities`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<AutomationRuleOverview> {
  return api<AutomationRuleOverview>(`${base}/overview`);
}

/** 规则列表（真筛选 + 真关键词 + 全序排序）。 */
export function fetchRules(params?: {
  eventType?: string | undefined;
  status?: string | undefined;
  enabled?: string | undefined;
  dryRun?: string | undefined;
  failing?: string | undefined;
  scopeType?: string | undefined;
  keyword?: string | undefined;
}): Promise<AutomationRuleRow[]> {
  return api<AutomationRuleRow[]>(`${base}/rules?${query({ ...params })}`);
}

/** 一条规则档案（含条件 / 动作 / 启用范围 / 最近几次执行）。 */
export function fetchRule(id: string): Promise<AutomationRuleDetail> {
  return api<AutomationRuleDetail>(`${base}/rules/${id}`);
}

/** 🔴 **范围内生效的规则**（启用范围是真约束的落点）。 */
export function fetchApplicable(params?: {
  shopId?: string | undefined;
  warehouseId?: string | undefined;
}): Promise<AutomationRuleApplicableRow[]> {
  return api<AutomationRuleApplicableRow[]>(`${base}/applicable?${query({ ...params })}`);
}

/** 可用触发事件（来自平台事件注册表）。 */
export function fetchEventTypes(): Promise<AutomationRuleEventTypeRow[]> {
  return api<AutomationRuleEventTypeRow[]>(`${base}/event-types`);
}

/** 上游 2.3 店铺（**只读**；读不到 ⇒ 503）。 */
export function fetchUpstreamShops(): Promise<AutomationRuleUpstreamShopRow[]> {
  return api<AutomationRuleUpstreamShopRow[]>(`${base}/upstream/shops`);
}

/** 上游仓库（**只读**；读不到 ⇒ 503）。 */
export function fetchUpstreamWarehouses(): Promise<AutomationRuleUpstreamWarehouseRow[]> {
  return api<AutomationRuleUpstreamWarehouseRow[]>(`${base}/upstream/warehouses`);
}

/** 某条规则的执行历史（只追加台账）。 */
export function fetchExecutions(
  ruleId: string,
  limit?: number,
): Promise<AutomationRuleExecutionRow[]> {
  return api<AutomationRuleExecutionRow[]>(
    `${base}/rules/${ruleId}/executions?${query({ limit: limit === undefined ? undefined : String(limit) })}`,
  );
}

// ================================================================ 写入

/** 新增或修改一条规则（条件 / 动作 / 启用范围整体替换）。 */
export function saveRule(input: AutomationRuleInput): Promise<AutomationRuleDetail> {
  return api<AutomationRuleDetail>(`${base}/rules`, 'POST', input);
}

/** 启用一条规则。 */
export function enableRule(id: string): Promise<AutomationRuleRow> {
  return api<AutomationRuleRow>(`${base}/rules/${id}/enable`, 'POST');
}

/** 停用一条规则（**不删**：执行历史要能复现）。 */
export function disableRule(id: string): Promise<AutomationRuleRow> {
  return api<AutomationRuleRow>(`${base}/rules/${id}/disable`, 'POST');
}

/** 🔴 **开关试运行**（独立状态）。 */
export function setDryRun(id: string, dryRun: boolean): Promise<AutomationRuleRow> {
  return api<AutomationRuleRow>(`${base}/rules/${id}/dry-run`, 'POST', {
    dryRun,
  } satisfies AutomationRuleDryRunInput);
}

/** 🔴 **排序**（一次性给出若干条规则的优先级）。 */
export function reorderRules(input: AutomationRuleReorderInput): Promise<AutomationRuleRow[]> {
  return api<AutomationRuleRow[]>(`${base}/rules/reorder`, 'POST', input);
}

/** 🔴 **登记一次执行结果**（执行统计的唯一写入口）。 */
export function recordExecution(
  ruleId: string,
  input: AutomationRuleExecutionInput,
): Promise<AutomationRuleExecutionRow> {
  return api<AutomationRuleExecutionRow>(`${base}/rules/${ruleId}/executions`, 'POST', input);
}

/** 🔴 **预演**（求值条件 + 给动作计划；**不执行**任何动作）。 */
export function previewRule(
  ruleId: string,
  input: AutomationRulePreviewInput,
): Promise<AutomationRulePreview> {
  return api<AutomationRulePreview>(`${base}/rules/${ruleId}/preview`, 'POST', input);
}

// ================================================================ 展示辅助

/** 语气（徽章配色）。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

/** 数量格式化（空值显示「—」）。 */
export function formatCount(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—';
  const parsed = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(parsed)) return String(value);
  return parsed.toLocaleString('en-US');
}

/** 时间格式化（ISO-8601 → 本地时刻；空值显示「—」）。 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '—';
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/**
 * 🔴 **健康判定 → 语气**。
 *
 * 「连续失败」（FAILING）是 **danger**：这是**坏规则**，
 * 页面必须让它一眼可见，而不是混进「不稳定」里。
 */
export function healthTone(health: string): Tone {
  switch (health) {
    case 'HEALTHY':
      return 'ok';
    case 'DEGRADED':
      return 'warn';
    case 'FAILING':
      return 'danger';
    case 'SKIPPED':
      return 'muted';
    default:
      return 'muted';
  }
}

/**
 * 🔴 **最后一次执行的结果 → 语气**。
 *
 * 「还没跑过」是 muted —— 「没跑过」与「跑了但坏了」是两件事。
 */
export function runStatusTone(status: string): Tone {
  switch (status) {
    case 'SUCCEEDED':
      return 'ok';
    case 'FAILED':
    case 'TIMEOUT':
      return 'danger';
    case 'SKIPPED':
      return 'muted';
    default:
      return 'muted';
  }
}

/**
 * 🔴 **试运行 → 语气**。
 *
 * 试运行是 **warn**（不是 danger）：它是一条**故意不落副作用**的规则，
 * 不是坏了。
 */
export function dryRunTone(dryRun: boolean): Tone {
  return dryRun ? 'warn' : 'muted';
}

/**
 * 🔴 **「最后一次执行」那一格要显示什么**。
 *
 * 返回**三件套**：时间 / 结果 / 失败原因。
 * 失败原因**不许**被截断成「失败」两个字 —— 那正是蓝图加粗句要防的静默失败。
 */
export function lastRunText(rule: {
  lastRunAt?: string | null;
  lastRunStatus?: string | null;
  lastRunStatusLabel?: string | null;
  lastRunFailureReason?: string | null;
}): string {
  if (!rule.lastRunAt || !rule.lastRunStatus) return '尚未执行';
  const at = formatDateTime(rule.lastRunAt);
  const result = rule.lastRunStatusLabel || rule.lastRunStatus;
  const reason = rule.lastRunFailureReason?.trim();
  return reason ? `${at} · ${result} · ${reason}` : `${at} · ${result}`;
}

/** 试运行那一格的说明文案。 */
export function sideEffectText(rule: {
  dryRun?: boolean;
  sideEffectModeLabel?: string | null;
}): string {
  return rule.dryRun
    ? rule.sideEffectModeLabel || '试运行 · 不产生副作用'
    : rule.sideEffectModeLabel || '真实执行';
}

/** 空态文案：区分「还没登记过」与「筛选无结果」。 */
export function emptyHint(filtered: boolean): string {
  return filtered
    ? '当前筛选条件下没有规则 —— 清掉筛选再看看，或新建一条规则。'
    : '本租户还没有登记过自动化规则。新建一条规则：选触发事件 → 配条件 → 编排动作 → 选启用范围。';
}

/** 「执行引擎未交付」的说明（页面据此**不提供**「执行规则」按钮）。 */
export function engineUnavailableNote(
  capabilities: AutomationRuleCapabilities | undefined,
): string | null {
  const gap = capabilities?.capabilities?.find((c) => c.name.includes('规则执行'));
  if (!gap || gap.available) return null;
  return gap.note || '规则执行引擎（18.3.2）尚未交付 —— 本模块只登记执行结果，不真的跑规则。';
}

// ================================================================ 表单与校验

/** 规则表单（新建 / 编辑共用）。 */
export interface RuleForm {
  id: string | null;
  name: string;
  description: string;
  triggerEventType: string;
  priority: string;
  consecutiveFailureThreshold: string;
  dryRun: boolean;
  remark: string;
  conditions: ConditionForm[];
  actions: ActionForm[];
  scopes: ScopeForm[];
}

/** 条件表单。 */
export interface ConditionForm {
  fieldPath: string;
  operator: string;
  valueText: string;
  valueNumber: string;
  enabled: boolean;
}

/** 动作表单。 */
export interface ActionForm {
  actionType: string;
  targetRef: string;
  params: string;
  enabled: boolean;
}

/** 启用范围表单。 */
export interface ScopeForm {
  scopeType: string;
  scopeRef: string;
  scopeLabel: string;
}

/** 空表单。 */
export function emptyRuleForm(): RuleForm {
  return {
    id: null,
    name: '',
    description: '',
    triggerEventType: '',
    priority: '100',
    consecutiveFailureThreshold: '3',
    dryRun: false,
    remark: '',
    conditions: [],
    actions: [],
    scopes: [{ scopeType: 'TENANT', scopeRef: '', scopeLabel: '整个租户' }],
  };
}

/** 由规则档案回填表单（**编辑**用）。 */
export function formFromRule(detail: AutomationRuleDetail): RuleForm {
  const rule = detail.rule;
  return {
    id: rule.id,
    name: rule.name,
    description: rule.description,
    triggerEventType: rule.triggerEventType,
    priority: String(rule.priority),
    consecutiveFailureThreshold: String(rule.consecutiveFailureThreshold),
    dryRun: rule.dryRun,
    remark: rule.remark,
    conditions: detail.conditions.map((c) => ({
      fieldPath: c.fieldPath,
      operator: c.operator,
      valueText: c.valueText,
      valueNumber:
        c.valueNumber === null || c.valueNumber === undefined ? '' : String(c.valueNumber),
      enabled: c.enabled,
    })),
    actions: detail.actions.map((a) => ({
      actionType: a.actionType,
      targetRef: a.targetRef,
      params: a.params,
      enabled: a.enabled,
    })),
    scopes: detail.scopes.map((s) => ({
      scopeType: s.scopeType,
      scopeRef: s.scopeRef ?? '',
      scopeLabel: s.scopeLabel,
    })),
  };
}

/**
 * 表单校验（**真校验**，不靠后端兜底）。
 *
 * 返回错误文案数组；空数组表示通过。
 */
export function validateRule(form: RuleForm): string[] {
  const errors: string[] = [];
  if (!form.name.trim()) errors.push('规则名称不能为空');
  if (form.name.trim().length > 120) errors.push('规则名称长度不能超过 120 个字符');
  if (!form.triggerEventType.trim()) errors.push('必须选一个触发事件');
  const priority = Number(form.priority);
  if (!Number.isInteger(priority) || priority < 1 || priority > 1000) {
    errors.push('优先级只能是 1 到 1000 的整数（数字越小越先执行）');
  }
  const threshold = Number(form.consecutiveFailureThreshold);
  if (!Number.isInteger(threshold) || threshold < 1 || threshold > 100) {
    errors.push('连续失败阈值只能是 1 到 100 的整数');
  }
  form.conditions.forEach((condition, index) => {
    if (!condition.fieldPath.trim()) {
      errors.push(`第 ${index + 1} 个条件缺字段路径`);
    }
    if (['GT', 'GE', 'LT', 'LE'].includes(condition.operator) && !condition.valueNumber.trim()) {
      errors.push(`第 ${index + 1} 个条件是大小比较，必须给一个数值`);
    }
    if (condition.operator === 'BETWEEN' && !condition.valueText.includes('~')) {
      errors.push(`第 ${index + 1} 个条件是区间比较，两个值要用 ~ 分隔`);
    }
    if (condition.operator === 'IN' && !condition.valueText.trim()) {
      errors.push(`第 ${index + 1} 个条件是「属于」，至少要有一个候选值`);
    }
  });
  form.actions.forEach((action, index) => {
    if (!action.actionType) errors.push(`第 ${index + 1} 个动作缺动作类型`);
    if (action.params.trim()) {
      try {
        const parsed: unknown = JSON.parse(action.params);
        if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
          errors.push(`第 ${index + 1} 个动作的参数必须是一个 JSON 对象`);
        }
      } catch {
        errors.push(`第 ${index + 1} 个动作的参数不是合法的 JSON`);
      }
    }
  });
  form.scopes.forEach((scope, index) => {
    if (scope.scopeType === 'TENANT' && scope.scopeRef.trim()) {
      errors.push(`第 ${index + 1} 个范围是「整个租户」，不能带引用`);
    }
    if (scope.scopeType !== 'TENANT' && !scope.scopeRef.trim()) {
      errors.push(`第 ${index + 1} 个范围是「指定店铺 / 仓库」，必须选一个对象`);
    }
  });
  return errors;
}

/**
 * 把表单翻成后端入参。
 *
 * ⚠️ `triggerSchemaVersion` / `enabled` / `status` 传 **null** 而不是编一个值：
 * null = 「不改这一项」，后端会保留原有取值（触发事件版本则取注册表里可用的最高版本）。
 * 随手填一个版本号会让规则绑死在某个 schema 上 —— 那是**另一个模块**该管的事。
 */
export function toRuleInput(form: RuleForm): AutomationRuleInput {
  return {
    id: form.id ?? null,
    name: form.name.trim(),
    description: form.description.trim(),
    triggerEventType: form.triggerEventType.trim(),
    triggerSchemaVersion: null,
    priority: Number(form.priority),
    enabled: null,
    status: null,
    dryRun: form.dryRun,
    consecutiveFailureThreshold: Number(form.consecutiveFailureThreshold),
    remark: form.remark.trim(),
    conditions: form.conditions.map<AutomationRuleConditionInput>((c, index) => ({
      seq: index + 1,
      fieldPath: c.fieldPath.trim(),
      operator: c.operator,
      valueText: c.valueText.trim(),
      valueNumber: c.valueNumber.trim() ? Number(c.valueNumber) : null,
      valueBool: null,
      enabled: c.enabled,
      remark: null,
    })),
    actions: form.actions.map<AutomationRuleActionInput>((a, index) => ({
      seq: index + 1,
      actionType: a.actionType,
      targetRef: a.targetRef.trim(),
      params: a.params.trim() || '{}',
      enabled: a.enabled,
      remark: null,
    })),
    scopes: form.scopes.map<AutomationRuleScopeInput>((s) => ({
      scopeType: s.scopeType,
      scopeRef: s.scopeType === 'TENANT' ? null : s.scopeRef.trim(),
      scopeLabel: s.scopeLabel.trim(),
    })),
  };
}

/** 登记执行结果的表单。 */
export interface ExecutionForm {
  status: string;
  failureReason: string;
  failureClass: string;
  plannedActions: string;
  executedActions: string;
  sideEffectsApplied: boolean;
  triggeredBy: string;
}

/** 空执行结果表单。 */
export function emptyExecutionForm(): ExecutionForm {
  return {
    status: 'SUCCEEDED',
    failureReason: '',
    failureClass: '',
    plannedActions: '0',
    executedActions: '0',
    sideEffectsApplied: false,
    triggeredBy: '',
  };
}

/**
 * 执行结果表单校验。
 *
 * 🔴 **试运行规则不许登记真实副作用** —— 与后端 409 同一道纪律，
 * 页面先挡一道，给一句人话。
 */
export function validateExecution(form: ExecutionForm, dryRun: boolean): string[] {
  const errors: string[] = [];
  if (!form.status) errors.push('必须选一个执行结论');
  if (form.status === 'FAILED' && !form.failureReason.trim()) {
    errors.push('失败必须写清失败原因 —— 否则就是又一种静默失败');
  }
  if (form.failureClass.trim() && !form.failureReason.trim()) {
    errors.push('填了失败分类就必须写清失败原因');
  }
  if (form.failureReason.trim() && !form.failureClass.trim()) {
    errors.push('写了失败原因就必须给出失败分类');
  }
  const planned = Number(form.plannedActions || '0');
  const executed = Number(form.executedActions || '0');
  if (!Number.isFinite(planned) || planned < 0) errors.push('计划动作数不能为负');
  if (!Number.isFinite(executed) || executed < 0) errors.push('已执行动作数不能为负');
  if (executed > planned) errors.push('已执行动作数不能超过计划动作数');
  if (dryRun && form.sideEffectsApplied) {
    errors.push('这条规则处于试运行状态，不允许登记「产生了真实副作用」');
  }
  if (dryRun && executed > 0) {
    errors.push('这条规则处于试运行状态，不允许登记「已经执行了动作」');
  }
  if (!form.sideEffectsApplied && executed > 0) {
    errors.push('已执行了动作就必须如实标「产生了真实副作用」');
  }
  return errors;
}

/** 把执行结果表单翻成后端入参。 */
export function toExecutionInput(form: ExecutionForm): AutomationRuleExecutionInput {
  return {
    status: form.status,
    source: 'MANUAL',
    sideEffectsApplied: form.sideEffectsApplied,
    triggeredBy: form.triggeredBy.trim() || '人工登记',
    matchedConditions: 0,
    plannedActions: Number(form.plannedActions || '0'),
    executedActions: Number(form.executedActions || '0'),
    failureReason: form.failureReason.trim(),
    failureClass: form.failureClass.trim(),
    triggerEventType: null,
    startedAt: null,
    finishedAt: null,
  };
}

/** 条件操作符的可选项（**与后端受控词表一致**）。 */
export const CONDITION_OPERATORS = [
  { value: 'EQ', label: '等于' },
  { value: 'NE', label: '不等于' },
  { value: 'GT', label: '大于' },
  { value: 'GE', label: '大于等于' },
  { value: 'LT', label: '小于' },
  { value: 'LE', label: '小于等于' },
  { value: 'CONTAINS', label: '包含' },
  { value: 'STARTS_WITH', label: '开头是' },
  { value: 'ENDS_WITH', label: '结尾是' },
  { value: 'IN', label: '属于（逗号分隔）' },
  { value: 'BETWEEN', label: '介于（用 ~ 分隔）' },
  { value: 'IS_NULL', label: '为空' },
  { value: 'NOT_NULL', label: '不为空' },
  { value: 'EXISTS', label: '存在' },
] as const;

/** 动作类型的可选项（**与后端受控词表一致**）。 */
export const ACTION_TYPES = [
  { value: 'CALL_API', label: '调用接口' },
  { value: 'UPDATE_STATUS', label: '修改状态' },
  { value: 'CREATE_TASK', label: '创建任务' },
  { value: 'SEND_NOTIFICATION', label: '发送通知' },
  { value: 'CREATE_DOCUMENT', label: '生成单据' },
] as const;

/** 启用范围类别的可选项（**与后端受控词表一致**）。 */
export const SCOPE_TYPES = [
  { value: 'TENANT', label: '整个租户' },
  { value: 'SHOP', label: '指定店铺' },
  { value: 'WAREHOUSE', label: '指定仓库' },
] as const;

/** 执行结论的可选项（**与后端受控词表一致**）。 */
export const RUN_STATUSES = [
  { value: 'SUCCEEDED', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'SKIPPED', label: '跳过' },
  { value: 'TIMEOUT', label: '超时' },
] as const;

/** 失败分类的可选项（**与后端受控词表一致**）。 */
export const FAILURE_CLASSES = [
  { value: 'CONDITION_NOT_MATCHED', label: '条件未命中' },
  { value: 'ACTION_FAILED', label: '动作失败' },
  { value: 'TIMEOUT', label: '超时' },
  { value: 'RULE_DISABLED', label: '规则已停用' },
  { value: 'ENGINE_ERROR', label: '引擎错误' },
] as const;

/** 条件行 / 动作行 / 范围行的展示辅助（给工作区用）。 */
export type { AutomationRuleActionRow, AutomationRuleConditionRow, AutomationRuleScopeRow };
