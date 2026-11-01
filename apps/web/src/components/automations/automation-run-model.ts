import type {
  AutomationRuleRunCapabilities,
  AutomationRuleRunDetail,
  AutomationRuleRunOverview,
  AutomationRuleRunPage,
  AutomationRuleRunRerunInput,
  AutomationRuleRunRerunResult,
  AutomationRuleRunStepRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 18.3.2.1「执行日志」前端模型。
 *
 * 蓝图：「<strong>记录每次规则执行</strong>：触发事件、条件命中情况、执行的动作、每步结果、
 * 耗时、错误；支持查看上下文与手动重跑。
 * 输入：执行记录；重跑请求。 输出：执行明细 + 失败原因定位。
 * 关系：上游 = 18.3.1.2；下游 = 1 工作台告警。
 * <strong>规则必须幂等</strong>：同一事件重复触发不能重复执行副作用（如重复改价、重复建单）。」
 *
 * <p>🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **「重跑」不等于「已经重跑」**。本仓当前**没有**规则执行引擎 ⇒ 重跑请求落成
 *    一条 {@code outcome = NOT_ATTEMPTED} 的待执行记录。页面把它写成
 *    「**未执行 · 执行器未接通**」并展示未接通原因，**绝不**显示成「已重跑成功」。
 * 2. **「重跑」是「新的一次执行」**。页面显示它与原 run 的关联（第几次重跑），
 *    原 run 的结论**不会**被改写 —— 那是「只追加台账」在数据层的保证。
 * 3. **幂等是真的**。同一事件重复触发会被后端 409 拒掉；页面把这件事说清楚，
 *    而不是让运营以为「点两次就跑了两次」。
 * 4. **失败要能定位到步**。详情页把逐步明细逐条摊开（命中了哪条 condition、
 *    错误码与错误信息、每步耗时），并把**第一处失败**单独拎出来。
 *
 * <p><b>类型全部从契约派生</b>（{@code packages/api-contracts} 的 codegen 产物），
 * 前端**不手抄**字符串联合 —— 枚举值由后端的字典给出。
 */

/** 本模块在 api-proxy 白名单里的路径前缀（**独立一条 services[] 元素**，不是并进既有分支）。 */
const base = 'platform/automation/runs';

/** 一次执行的结论（与后端 `AutomationRuleRunOutcome` 逐字一致）。 */
export const OUTCOMES = ['SUCCEEDED', 'FAILED', 'SKIPPED', 'TIMEOUT', 'NOT_ATTEMPTED'] as const;

export type Outcome = (typeof OUTCOMES)[number];

/** 一步的结果（与后端 `AutomationRuleRunStepResult` 逐字一致）。 */
export const STEP_RESULTS = [
  'SUCCEEDED',
  'FAILED',
  'SKIPPED',
  'NOT_MATCHED',
  'NOT_ATTEMPTED',
] as const;

export type StepResult = (typeof STEP_RESULTS)[number];

/** 步骤的种类。 */
export const STEP_KINDS = ['CONDITION', 'ACTION'] as const;

export type StepKind = (typeof STEP_KINDS)[number];

/** 徽章只有 7 种 tone，这里收敛成 4 种语义。 */
export type Tone = 'ok' | 'warn' | 'danger' | 'muted';

// ================================================================ 读取

/** 能力声明：哪些真交付、哪些还没交付、归谁。 */
export function fetchCapabilities(): Promise<AutomationRuleRunCapabilities> {
  return api<AutomationRuleRunCapabilities>(`${base}/capabilities`);
}

/** 概览：几个数。 */
export function fetchOverview(): Promise<AutomationRuleRunOverview> {
  return api<AutomationRuleRunOverview>(`${base}/overview`);
}

/** 执行日志列表（**真分页 + 真筛选**，后端 SQL 过滤）。 */
export function fetchRuns(params?: {
  ruleId?: string | undefined;
  outcome?: string | undefined;
  source?: string | undefined;
  eventType?: string | undefined;
  eventId?: string | undefined;
  dryRun?: boolean | undefined;
  keyword?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}): Promise<AutomationRuleRunPage> {
  const search = new URLSearchParams();
  if (params?.ruleId) search.set('ruleId', params.ruleId);
  if (params?.outcome) search.set('outcome', params.outcome);
  if (params?.source) search.set('source', params.source);
  if (params?.eventType) search.set('eventType', params.eventType);
  if (params?.eventId) search.set('eventId', params.eventId);
  if (params?.dryRun !== undefined) search.set('dryRun', String(params.dryRun));
  if (params?.keyword) search.set('keyword', params.keyword);
  if (params?.from) search.set('from', params.from);
  if (params?.to) search.set('to', params.to);
  if (params?.page !== undefined) search.set('page', String(params.page));
  if (params?.size !== undefined) search.set('size', String(params.size));
  const query = search.toString();
  // ⚠️ 查询串**不拼在模板串里**：嵌套模板会让白名单守卫的反向抽取把 `${suffix` 当成路径的一部分。
  return api<AutomationRuleRunPage>(query ? `${base}?${query}` : `${base}`);
}

/** 一次执行的详情（含逐步明细 ⇒ 失败原因可以从 run 直接下钻到 step）。 */
export function fetchRunDetail(id: string): Promise<AutomationRuleRunDetail> {
  return api<AutomationRuleRunDetail>(`${base}/${encodeURIComponent(id)}`);
}

// ================================================================ 写入

/**
 * 🔴 **手动重跑** —— 落成**新的一条**执行记录。
 *
 * <p>⚠️ 本仓当前**没有**规则执行引擎 ⇒ 后端返回的是
 * {@code outcome = NOT_ATTEMPTED} 的**待执行**记录（不是「已重跑成功」）。
 *
 * @param idempotencyKey **同一个键重复提交只会产生一条重跑记录**（双击按钮不会跑两次）。
 */
export function requestRerun(
  id: string,
  input: AutomationRuleRunRerunInput,
): Promise<AutomationRuleRunRerunResult> {
  return api<AutomationRuleRunRerunResult>(
    `${base}/${encodeURIComponent(id)}/rerun`,
    'POST',
    input,
  );
}

// ================================================================ 纯函数（可单测）

/** 结论 → 徽章语气。 */
export function outcomeTone(outcome: string): Tone {
  switch (outcome) {
    case 'SUCCEEDED':
      return 'ok';
    case 'FAILED':
      return 'danger';
    case 'TIMEOUT':
      return 'warn';
    case 'NOT_ATTEMPTED':
      return 'warn';
    default:
      return 'muted';
  }
}

/** 步骤结果 → 徽章语气。 */
export function stepResultTone(result: string): Tone {
  switch (result) {
    case 'SUCCEEDED':
      return 'ok';
    case 'FAILED':
      return 'danger';
    case 'NOT_MATCHED':
      return 'muted';
    case 'NOT_ATTEMPTED':
      return 'warn';
    default:
      return 'muted';
  }
}

/** 结论 → 人话（页面上的中文标签；后端也给了 label，这里做兜底）。 */
export function outcomeLabel(outcome: string, fallback?: string): string {
  switch (outcome) {
    case 'SUCCEEDED':
      return '成功';
    case 'FAILED':
      return '失败';
    case 'SKIPPED':
      return '跳过';
    case 'TIMEOUT':
      return '超时';
    case 'NOT_ATTEMPTED':
      return '未执行';
    default:
      return fallback || outcome || '未知';
  }
}

/**
 * 耗时（**整数毫秒**）→ 人话。
 *
 * <p>⚠️ 前端**不碰浮点**：毫秒是整数，这里只用整数除法，绝不出现 `0.1 + 0.2` 那种误差。
 */
export function describeDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '—';
  if (ms < 1000) return `${Math.trunc(ms)} 毫秒`;
  const seconds = Math.trunc(ms / 1000);
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.trunc(seconds / 60);
  const restSeconds = seconds % 60;
  if (minutes < 60) return restSeconds ? `${minutes} 分 ${restSeconds} 秒` : `${minutes} 分`;
  const hours = Math.trunc(minutes / 60);
  const restMinutes = minutes % 60;
  return restMinutes ? `${hours} 小时 ${restMinutes} 分` : `${hours} 小时`;
}

/** 第一处失败的那一步（**失败原因定位**：从 run 直接下钻到 step）。 */
export function firstFailedStep(
  steps: AutomationRuleRunStepRow[],
): AutomationRuleRunStepRow | null {
  for (const step of steps) {
    if (step.result === 'FAILED') return step;
  }
  return null;
}

/** 逐步明细的汇总（成功 / 失败 / 未命中 各几步）。 */
export function summarizeSteps(steps: AutomationRuleRunStepRow[]): {
  total: number;
  succeeded: number;
  failed: number;
  notMatched: number;
  notAttempted: number;
} {
  var summary = { total: steps.length, succeeded: 0, failed: 0, notMatched: 0, notAttempted: 0 };
  for (const step of steps) {
    if (step.result === 'SUCCEEDED') summary.succeeded += 1;
    else if (step.result === 'FAILED') summary.failed += 1;
    else if (step.result === 'NOT_MATCHED') summary.notMatched += 1;
    else if (step.result === 'NOT_ATTEMPTED') summary.notAttempted += 1;
  }
  return summary;
}

/** 逐步明细里**条件判定**的那几步（条件命中情况）。 */
export function conditionSteps(steps: AutomationRuleRunStepRow[]): AutomationRuleRunStepRow[] {
  return steps.filter((step) => step.stepKind === 'CONDITION');
}

/** 逐步明细里**动作执行**的那几步。 */
export function actionSteps(steps: AutomationRuleRunStepRow[]): AutomationRuleRunStepRow[] {
  return steps.filter((step) => step.stepKind === 'ACTION');
}

/**
 * 解析触发上下文（JSON 对象）。
 *
 * <p>🔴 解析不了就**点名报错** —— 不把「载荷坏了」伪装成「上下文为空」。
 */
export function parseEventPayload(
  raw: string,
): { ok: true; value: string } | { ok: false; error: string } {
  const trimmed = raw.trim();
  if (!trimmed) return { ok: true, value: '' };
  try {
    const parsed: unknown = JSON.parse(trimmed);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return { ok: false, error: '触发上下文必须是一个 JSON 对象（如 {"orderNo":"SO-1"}）' };
    }
    return { ok: true, value: trimmed };
  } catch {
    return { ok: false, error: '触发上下文不是合法的 JSON' };
  }
}

/**
 * 生成一个重跑**幂等键**。
 *
 * <p>⚠️ 同一个键重复提交 ⇒ 后端返回**同一条**重跑记录（双击按钮不会跑两次）。
 * 所以这个键在「打开重跑表单时」生成一次，重试时**复用**同一个。
 */
export function newIdempotencyKey(): string {
  const random =
    typeof globalThis.crypto?.randomUUID === 'function'
      ? globalThis.crypto.randomUUID()
      : `${Date.now()}-${Math.trunc(Math.random() * 1e9)}`;
  return `rerun-${random}`;
}

/** 重跑入参。 */
export function buildRerunInput(
  reason: string,
  idempotencyKey: string,
): AutomationRuleRunRerunInput {
  return { reason: reason.trim(), idempotencyKey };
}

/**
 * 「这一行到底意味着什么」—— 一句人话，写在列表行的副标题上。
 *
 * <p>🔴 尤其要把 {@code NOT_ATTEMPTED} 说清楚：**没执行** ≠ **失败** ≠ **成功**。
 */
export function describeOutcome(run: {
  outcome: string;
  sideEffectsApplied: boolean;
  dryRun: boolean;
}): string {
  if (run.outcome === 'NOT_ATTEMPTED') return '未执行 · 执行器未接通（没有真的跑）';
  if (run.dryRun) return '试运行 · 不产生真实副作用';
  if (run.outcome === 'FAILED') return '执行失败';
  if (run.outcome === 'SKIPPED') return '已跳过';
  if (run.outcome === 'TIMEOUT') return '执行超时';
  return run.sideEffectsApplied ? '执行成功 · 已产生副作用' : '执行成功 · 未产生副作用';
}

/** 事件载荷（JSON 文本）→ 试着摊平成一行可读摘要；摊不平就原样给。 */
export function summarizePayload(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed || trimmed === '{}') return '（无上下文）';
  try {
    const parsed: unknown = JSON.parse(trimmed);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return trimmed;
    const entries = Object.entries(parsed as Record<string, unknown>);
    if (entries.length === 0) return '（无上下文）';
    return entries
      .slice(0, 6)
      .map(
        ([key, value]) =>
          `${key}=${typeof value === 'object' ? JSON.stringify(value) : String(value)}`,
      )
      .join('  ·  ');
  } catch {
    return trimmed;
  }
}
