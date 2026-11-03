import type {
  AutomationRuleDetail,
  AutomationRuleEditorActionCatalog,
  AutomationRuleEditorApiCatalog,
  AutomationRuleEditorCapabilities,
  AutomationRuleEditorEventSchema,
  AutomationRuleEditorField,
  AutomationRuleEditorTestInput,
  AutomationRuleEditorTestResult,
  AutomationRuleEditorTestRunRow,
  AutomationRuleInput,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.3.1.2「规则编辑器」前端模型。
 *
 * 蓝图：「<strong>可视化编辑规则</strong>：选择触发事件 → 配置条件（字段/操作符/值）→
 * 编排动作（调接口/改状态/建任务/发通知/生成单据）→ 测试与保存。
 * 输入：规则配置；测试样本数据。输出：规则定义；测试运行结果。
 * 关系：上游 = 18.3.1.1；下游 = 18.3.2 执行。
 * <strong>规则必须支持用样本测试</strong>，否则只能在生产上试错。」
 *
 * <p>🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **「保存」不是本模块自己的表**。编辑器编出来的规则**委托 18.3.1.1 登记**
 *    （`POST /save` → 18.3.1.1 的登记入口）。本模块**不另建规则表** ——
 *    条件 / 动作 / 启用范围三张表的写入所有者始终是 18.3.1.1。
 * 2. **「测试」不执行任何动作**。18.3.2 执行引擎尚未交付 ⇒ `POST /test` 只**求值**
 *    并给出动作计划，`sideEffectsApplied` **恒为 false**。
 *    页面上写明这一点，而不是让运营以为「点一下测试就等于跑了一次规则」。
 * 3. **字段目录不是写死的清单**。触发事件的字段来自事件注册表登记的 `payload_schema`
 *    （JSON Schema 摊平）—— 事件换了 schema，目录跟着变。
 *    所以「条件里的字段是不是合法」这件事**由目录判**，不由前端猜。
 * 4. **上游没交付就说没交付**。18.3.2 执行引擎、18.4.x 智能能力没交付时，
 *    能力声明里逐条点名归谁，页面**不提供**「执行规则」这类点了必然失败的按钮。
 *
 * <p><b>类型全部从契约派生</b>（`packages/api-contracts` 的 codegen 产物），
 * 前端**不手抄**字符串联合 —— 枚举值由后端的字典列给出。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/automation-rule-editor';

/** 动作类型：与后端 `AutomationRuleActionType` 逐字一致（同一个受控枚举）。 */
export const ACTION_TYPES = [
  'CALL_API',
  'UPDATE_STATUS',
  'CREATE_TASK',
  'SEND_NOTIFICATION',
  'CREATE_DOCUMENT',
] as const;

export type ActionType = (typeof ACTION_TYPES)[number];

/**
 * 「调接口」这一类动作**必须**给目标接口（`targetRef`）——
 * 它指的是接口目录里真实存在的那个接口，其余四类的 `targetRef` 是业务对象引用（可空）。
 */
export const TARGET_REQUIRED_ACTION_TYPES: readonly ActionType[] = ['CALL_API'];

/** 语气：徽章只有 7 种 tone，这里收敛成 4 种语义。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

// ================================================================ 读取

/** 能力声明：哪些真交付、哪些还没交付、归谁。 */
export function fetchCapabilities(): Promise<AutomationRuleEditorCapabilities> {
  return api<AutomationRuleEditorCapabilities>(`${base}/capabilities`);
}

/** 已登记触发事件的字段目录（每个事件一份）。 */
export function fetchEventSchemas(): Promise<AutomationRuleEditorEventSchema[]> {
  return api<AutomationRuleEditorEventSchema[]>(`${base}/event-schemas`);
}

/** 一个触发事件的字段目录（按事件类型 + 版本）。 */
export function fetchEventSchema(
  eventType: string,
  schemaVersion?: number | undefined,
): Promise<AutomationRuleEditorEventSchema> {
  const path = `${base}/event-schemas/${encodeURIComponent(eventType)}`;
  return api<AutomationRuleEditorEventSchema>(
    schemaVersion === undefined ? path : `${path}?schemaVersion=${schemaVersion}`,
  );
}

/** 动作类型目录（五类动作 + 参数规格）。 */
export function fetchActionCatalog(): Promise<AutomationRuleEditorActionCatalog> {
  return api<AutomationRuleEditorActionCatalog>(`${base}/action-catalog`);
}

/** 「调接口」动作可选的目标接口（各服务活 spec 的投影）。 */
export function fetchApiCatalog(): Promise<AutomationRuleEditorApiCatalog> {
  return api<AutomationRuleEditorApiCatalog>(`${base}/api-catalog`);
}

/** 样本测试历史（只追加台账）。 */
export function fetchTestRuns(params?: {
  ruleId?: string | undefined;
  limit?: number | undefined;
}): Promise<AutomationRuleEditorTestRunRow[]> {
  const search = new URLSearchParams();
  if (params?.ruleId) search.set('ruleId', params.ruleId);
  if (params?.limit !== undefined) search.set('limit', String(params.limit));
  const query = search.toString();
  // ⚠️ 查询串**不拼在模板串里**：嵌套模板会让白名单守卫的反向抽取把 `${suffix` 当成路径的一部分。
  return api<AutomationRuleEditorTestRunRow[]>(
    query ? `${base}/test-runs?${query}` : `${base}/test-runs`,
  );
}

// ================================================================ 写入

/**
 * 保存规则定义 —— **委托 18.3.1.1 的登记入口**（本模块不另建规则表）。
 */
export function saveRule(input: AutomationRuleInput): Promise<AutomationRuleDetail> {
  return api<AutomationRuleDetail>(`${base}/save`, 'POST', input);
}

/**
 * 样本测试 —— 🔴 **只求值、只给动作计划，不执行任何动作**（18.3.2 执行引擎尚未交付）。
 */
export function runSampleTest(
  input: AutomationRuleEditorTestInput,
): Promise<AutomationRuleEditorTestResult> {
  return api<AutomationRuleEditorTestResult>(`${base}/test`, 'POST', input);
}

// ================================================================ 草稿

export type ConditionDraft = {
  seq: number;
  fieldPath: string;
  operator: string;
  valueText: string;
  valueNumber: number | null;
  valueBool: boolean | null;
  enabled: boolean;
  remark: string;
};

export type ActionDraft = {
  seq: number;
  actionType: string;
  targetRef: string;
  params: string;
  enabled: boolean;
  remark: string;
};

export type EditorDraft = {
  id: string | null;
  name: string;
  description: string;
  triggerEventType: string;
  triggerSchemaVersion: number | null;
  priority: number;
  enabled: boolean;
  dryRun: boolean;
  consecutiveFailureThreshold: number | null;
  conditions: ConditionDraft[];
  actions: ActionDraft[];
};

/** 空白草稿：新建规则时的起点。 */
export function emptyDraft(): EditorDraft {
  return {
    id: null,
    name: '',
    description: '',
    triggerEventType: '',
    triggerSchemaVersion: null,
    priority: 100,
    enabled: true,
    dryRun: true,
    consecutiveFailureThreshold: null,
    conditions: [emptyCondition(1)],
    actions: [emptyAction(1)],
  };
}

export function emptyCondition(seq: number): ConditionDraft {
  return {
    seq,
    fieldPath: '',
    operator: '',
    valueText: '',
    valueNumber: null,
    valueBool: null,
    enabled: true,
    remark: '',
  };
}

export function emptyAction(seq: number): ActionDraft {
  return {
    seq,
    actionType: '',
    targetRef: '',
    params: '{}',
    enabled: true,
    remark: '',
  };
}

/** 从 18.3.1.1 的规则档案回填草稿（改已有规则时用）。 */
export function draftFromDetail(detail: AutomationRuleDetail): EditorDraft {
  const rule = detail.rule;
  return {
    id: rule.id,
    name: rule.name,
    description: rule.description,
    triggerEventType: rule.triggerEventType,
    triggerSchemaVersion: rule.triggerSchemaVersion,
    priority: rule.priority,
    enabled: rule.enabled,
    dryRun: rule.dryRun,
    consecutiveFailureThreshold: rule.consecutiveFailureThreshold,
    conditions: detail.conditions.map((c, i) => ({
      seq: c.seq || i + 1,
      fieldPath: c.fieldPath,
      operator: c.operator,
      valueText: c.valueText,
      valueNumber: c.valueNumber,
      valueBool: c.valueBool,
      enabled: c.enabled,
      remark: c.remark,
    })),
    actions: detail.actions.map((a, i) => ({
      seq: a.seq || i + 1,
      actionType: a.actionType,
      targetRef: a.targetRef,
      params: a.params,
      enabled: a.enabled,
      remark: a.remark,
    })),
  };
}

/** 草稿 → 18.3.1.1 的登记入参（保存与测试用的是**同一种形状**）。 */
export function buildRuleInput(draft: EditorDraft): AutomationRuleInput {
  return {
    id: draft.id,
    name: draft.name.trim(),
    description: draft.description.trim() || null,
    triggerEventType: draft.triggerEventType.trim() || null,
    triggerSchemaVersion: draft.triggerSchemaVersion,
    priority: draft.priority,
    enabled: draft.enabled,
    status: draft.enabled ? 'ACTIVE' : 'DISABLED',
    dryRun: draft.dryRun,
    consecutiveFailureThreshold: draft.consecutiveFailureThreshold,
    remark: null,
    conditions: draft.conditions.map((c) => ({
      seq: c.seq,
      fieldPath: c.fieldPath.trim() || null,
      operator: c.operator.trim() || null,
      valueText: c.valueText.trim() || null,
      valueNumber: c.valueNumber,
      valueBool: c.valueBool,
      enabled: c.enabled,
      remark: c.remark.trim() || null,
    })),
    actions: draft.actions.map((a) => ({
      seq: a.seq,
      actionType: a.actionType.trim() || null,
      targetRef: a.targetRef.trim() || null,
      params: a.params.trim() || null,
      enabled: a.enabled,
      remark: a.remark.trim() || null,
    })),
    scopes: [],
  };
}

/** 样本测试入参：规则配置 + 测试样本数据（蓝图的两个输入）。 */
export function buildTestInput(
  draft: EditorDraft,
  payloadText: string,
): AutomationRuleEditorTestInput {
  return {
    rule: buildRuleInput(draft),
    payload: payloadText.trim() || null,
    shopId: null,
    warehouseId: null,
  };
}

// ================================================================ 样本解析

export type SampleParse = { ok: true; payload: string } | { ok: false; reason: string };

/**
 * 解析样本载荷（JSON）。
 *
 * ⚠️ **不许静默吞掉解析错误**：载荷写错却当成空载荷去测，测出来的「不匹配」会被误读成
 * 「规则不对」—— 那是在拿假结论指挥人。所以这里把**行号与原因**都如实说出来。
 */
export function parseSamplePayload(text: string): SampleParse {
  const trimmed = text.trim();
  if (!trimmed) return { ok: true, payload: '' };
  try {
    const parsed: unknown = JSON.parse(trimmed);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return { ok: false, reason: '样本必须是一个 JSON 对象（{...}），不是数组或标量' };
    }
    return { ok: true, payload: JSON.stringify(parsed) };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return { ok: false, reason: `样本不是合法 JSON：${message}` };
  }
}

// ================================================================ 目录查询

/** 按路径取事件字段目录里的一条。 */
export function fieldByPath(
  schema: AutomationRuleEditorEventSchema | null | undefined,
  path: string,
): AutomationRuleEditorField | null {
  if (!schema) return null;
  return schema.fields.find((f) => f.path === path) ?? null;
}

/** 事件目录里是否有这个事件（按事件类型 + 版本）。 */
export function hasEvent(
  schemas: AutomationRuleEditorEventSchema[] | null | undefined,
  eventType: string,
  schemaVersion: number | null,
): boolean {
  if (!schemas) return false;
  return schemas.some(
    (s) =>
      s.eventType === eventType && (schemaVersion === null || s.schemaVersion === schemaVersion),
  );
}

/** 某动作类型的参数规格。 */
export function actionSpec(
  catalog: AutomationRuleEditorActionCatalog | null | undefined,
  actionType: string,
): AutomationRuleEditorActionCatalog['actionTypes'][number] | null {
  if (!catalog) return null;
  return catalog.actionTypes.find((t) => t.actionType === actionType) ?? null;
}

// ================================================================ 校验

export type ValidationContext = {
  /** 当前触发事件的字段目录（取不到时字段合法性**不判**，只提示「未校验」）。 */
  schema?: AutomationRuleEditorEventSchema | null | undefined;
  /** 动作类型目录（取不到时必填参数**不判**）。 */
  actionCatalog?: AutomationRuleEditorActionCatalog | null | undefined;
  /** 接口目录（取不到时「调接口」的目标**不判**）。 */
  apiCatalog?: AutomationRuleEditorApiCatalog | null | undefined;
};

/**
 * 校验草稿，返回**人话**的问题清单（空数组 = 通过）。
 *
 * ⚠️ 判据一律**由后端给的目录驱动**：字段合法不合法看事件字段目录，
 * 动作必填参数看动作参数规格，「调接口」的目标在接口目录里挑。
 * 前端**不自己猜**一套枚举 —— 猜出来的枚举会与后端漂移，然后给出「本地看着对、上线就红」的错觉。
 */
export function validateDraft(draft: EditorDraft, ctx: ValidationContext = {}): string[] {
  const problems: string[] = [];

  if (!draft.name.trim()) {
    problems.push('规则名不能为空 —— 规则列表上就靠它认出这条规则');
  }
  if (!draft.triggerEventType.trim()) {
    problems.push('还没选触发事件 —— 没有触发事件，规则永远不会跑');
  }
  if (draft.triggerEventType.trim() && draft.triggerSchemaVersion === null) {
    problems.push('触发事件没选 schema 版本 —— 同一个事件可能有多个版本，判定要对着哪一个版本算');
  }
  if (!Number.isInteger(draft.priority) || draft.priority <= 0) {
    problems.push('优先级必须是正整数');
  }
  if (
    draft.consecutiveFailureThreshold !== null &&
    (!Number.isInteger(draft.consecutiveFailureThreshold) || draft.consecutiveFailureThreshold <= 0)
  ) {
    problems.push('连续失败阈值要么留空，要么是正整数');
  }

  const enabledConditions = draft.conditions.filter((c) => c.enabled);
  if (enabledConditions.length === 0) {
    problems.push('至少要有一条启用的条件 —— 没有条件的规则等于「全部命中」');
  }
  enabledConditions.forEach((c, i) => {
    const label = `第 ${i + 1} 条条件`;
    if (!c.fieldPath.trim()) {
      problems.push(`${label}：没选字段`);
      return;
    }
    const field = fieldByPath(ctx.schema, c.fieldPath);
    if (ctx.schema && !field) {
      problems.push(
        `${label}：字段「${c.fieldPath}」不在这个事件的字段目录里 —— 事件换了 schema，字段也会跟着变`,
      );
      return;
    }
    if (!c.operator.trim()) {
      problems.push(`${label}：没选操作符`);
    } else if (
      field &&
      field.allowedOperators.length > 0 &&
      !field.allowedOperators.includes(c.operator)
    ) {
      problems.push(
        `${label}：操作符「${c.operator}」不适用于字段「${c.fieldPath}」（可用：${field.allowedOperators.join('、')}）`,
      );
    }
    if (field && field.required && !hasValue(c)) {
      problems.push(`${label}：字段「${field.label || c.fieldPath}」是必填，值不能空`);
    }
    if (!field && !hasValue(c)) {
      problems.push(`${label}：值不能空`);
    }
  });

  const enabledActions = draft.actions.filter((a) => a.enabled);
  if (enabledActions.length === 0) {
    problems.push('至少要有一条启用的动作 —— 只判条件不做事，命中了也没有结果');
  }
  enabledActions.forEach((a, i) => {
    const label = `第 ${i + 1} 条动作`;
    if (!a.actionType.trim()) {
      problems.push(`${label}：没选动作类型`);
      return;
    }
    const spec = actionSpec(ctx.actionCatalog, a.actionType);
    if (ctx.actionCatalog && !spec) {
      problems.push(`${label}：动作类型「${a.actionType}」不在动作目录里`);
      return;
    }
    if (TARGET_REQUIRED_ACTION_TYPES.includes(a.actionType as ActionType) && !a.targetRef.trim()) {
      problems.push(`${label}：「${a.actionType}」必须指定目标接口`);
    }
    if (spec) {
      for (const param of spec.params) {
        if (!param.required) continue;
        const value = readParam(a.params, param.name);
        if (value === undefined || value === null || value === '') {
          problems.push(`${label}：缺少必填参数「${param.label}」（${param.name}）`);
        }
      }
    }
    const paramsProblem = validateParamsJson(a.params);
    if (paramsProblem) {
      problems.push(`${label}：${paramsProblem}`);
    }
  });

  return problems;
}

/** 条件的值是否填了（按字段类型：数字看 valueNumber，布尔看 valueBool，其余看文本）。 */
export function hasValue(condition: ConditionDraft): boolean {
  if (condition.valueBool !== null && condition.valueBool !== undefined) return true;
  if (condition.valueNumber !== null && condition.valueNumber !== undefined) return true;
  return condition.valueText.trim().length > 0;
}

/**
 * 动作参数必须是**合法 JSON 对象**。
 *
 * ⚠️ 不合法时**不许**把原文直接发给后端 —— 后端会把它当成字符串存进台账，
 * 下次执行时才发现「参数是坏的」，而那时规则已经在生产上了。
 */
export function validateParamsJson(params: string): string | null {
  const trimmed = params.trim();
  if (!trimmed) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return `参数不是合法 JSON：${message}`;
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return '参数必须是一个 JSON 对象（{...}）';
  }
  return null;
}

/** 从动作参数 JSON 里读一个键（读不出来返回 undefined，不抛）。 */
export function readParam(params: string, key: string): unknown {
  const trimmed = params.trim();
  if (!trimmed) return undefined;
  try {
    const parsed: unknown = JSON.parse(trimmed);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return undefined;
    return (parsed as Record<string, unknown>)[key];
  } catch {
    return undefined;
  }
}

// ================================================================ 结果呈现

export type TestSummary = {
  headline: string;
  tone: Tone;
  notes: string[];
};

/**
 * 把样本测试结果讲成**人话**，并且**永远带上「没有产生副作用」这句**。
 *
 * ⚠️ 这一句是硬要求：18.3.2 执行引擎未交付 ⇒ 测试只求值。
 * 页面上不写清，运营会以为「测试通过 = 规则会照跑」，于是把没验证过的规则直接启用。
 */
export function summarizeTestResult(result: AutomationRuleEditorTestResult): TestSummary {
  const notes: string[] = [];
  const matchedText = `命中 ${result.matchedConditions}/${result.totalConditions} 条条件`;
  if (result.failureReason) {
    notes.push(`失败原因：${result.failureReason}`);
  }
  if (result.scopeNote) {
    notes.push(`启用范围：${result.scopeNote}`);
  }
  if (result.note) {
    notes.push(result.note);
  }
  notes.push(
    result.sideEffectsApplied
      ? '⚠️ 本次测试**产生了副作用** —— 与 18.3.2 未交付的声明不符，请先查执行引擎'
      : '本次测试**没有产生任何副作用**：18.3.2 执行引擎尚未交付，测试只求值条件并给出动作计划',
  );
  if (!result.scopeApplicable) {
    notes.push('这条规则对当前样本的启用范围不适用 —— 样本缺店铺/仓库上下文，或范围不匹配');
  }
  return {
    headline: result.matched
      ? `条件命中（${matchedText}），计划 ${result.plannedActions} 个动作`
      : `条件未命中（${matchedText}）`,
    tone: result.matched ? 'ok' : 'warn',
    notes,
  };
}

/** 下一条样本测试的序号（历史里最大的 runNo + 1；没有历史就是 1）。 */
export function nextRunNo(runs: AutomationRuleEditorTestRunRow[]): number {
  const max = runs.reduce((acc, r) => (r.runNo > acc ? r.runNo : acc), 0);
  return max + 1;
}

/** 条件的人话描述（字段目录可用时带上字段标签）。 */
export function describeCondition(
  condition: ConditionDraft,
  schema?: AutomationRuleEditorEventSchema | null | undefined,
): string {
  const field = fieldByPath(schema, condition.fieldPath);
  const name = field?.label || condition.fieldPath || '（未选字段）';
  const operator = condition.operator || '（未选操作符）';
  const value =
    condition.valueBool !== null && condition.valueBool !== undefined
      ? String(condition.valueBool)
      : condition.valueNumber !== null && condition.valueNumber !== undefined
        ? String(condition.valueNumber)
        : condition.valueText || '（未填值）';
  return `${name} ${operator} ${value}`;
}
