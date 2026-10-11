import type {
  ZombieCandidate,
  ZombieCandidateOverview,
  ZombieCandidatePage,
  ZombieCleanupRecordPage,
  ZombieCleanupRuleInput,
  ZombieCleanupRuleView,
  ZombieDimensionStatus,
  ZombieOptions,
  ZombieReferenceEvidence,
  ZombieDeactivateResult,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 3.9.2.2 僵尸主数据清理前端模型。
 *
 * <p>界面上**最容易出错的三件事**，都在这里被钉死：
 *
 * <ul>
 *   <li><b>「无法判定」必须与「在用」分开显示，且都不可停用</b>。
 *       两者都意味着"别停它"，但下一步动作完全不同：前者是「上游还没接 / 快照太旧，
 *       去看覆盖率并接事件」，后者是「这条有人在用，永远不会变成候选」。
 *       混成一句话的话，运营只会当成"系统不让我停"。
 *   <li><b>界面不重算判定</b>。verdict 与逐维 dimensions 都由后端下发，
 *       这里只把原因码翻成话 —— 与 {@code ZombieCandidateRules} 同一纪律：
 *       两处各判一次的话，改了阈值只有一端跟着变，而症状是"界面上说候选、点了却 409"。
 *   <li><b>数量按字符串展示</b>。inventory / cost 是 numeric(19,6)，
 *       0.000000 在 JS 里是 number，显示时用后端给的字符串，不要在前端重算格式化。
 * </ul>
 *
 * <p>本文件的校验与 {@code ZombieCandidateRules} 逐字同口径。抄一份到前端是为了少一次往返，
 * 服务层那份才是权威。
 */

export type Verdict = 'CANDIDATE' | 'NOT_ZOMBIE' | 'INCOMPLETE';

export const VERDICT_LABELS: Record<Verdict, string> = {
  CANDIDATE: '僵尸候选',
  NOT_ZOMBIE: '在用',
  INCOMPLETE: '无法判定',
};

/**
 * 判定状态的**下一步动作**，界面上跟在徽标旁边显示。
 *
 * <p>刻意与 VERDICT_LABELS 分开：标签回答"这是什么"，动作回答"我该干什么"，
 * 混在一起会写成「无法判定：请稍后重试」这种没人能执行的提示。
 */
export const VERDICT_ACTIONS: Record<Verdict, string> = {
  CANDIDATE: '确认后可以批量停用（停用不删除，历史单据仍可展示）',
  NOT_ZOMBIE: '这条主数据有人在用，不会进入停用范围',
  INCOMPLETE:
    '缺少有效的上游观测，未知不等于零：先接入该维度的推送，或刷新过期的快照',
};

export const ENTITY_TYPE_LABELS: Record<string, string> = {
  SKU: 'SKU',
  SUPPLIER: '供应商',
  CUSTOMER: '客户',
};

/** 维度判定状态 → 中文。与后端 ZombieDimensionStatus 的四个常量同口径。 */
export const DIMENSION_STATE_LABELS: Record<string, string> = {
  SATISFIED: '已确认为零',
  VIOLATED: '有引用',
  UNKNOWN: '未知',
  NOT_REQUIRED: '未启用',
};

export type DimensionState = keyof typeof DIMENSION_STATE_LABELS;

/**
 * 引用维度行（metric 非空）。
 *
 * <p>判定明细里还有两类**前提条件**行（当前状态不是活跃、仍在维护期内），
 * 它们不带 metric。`.filter((d) => d.metric)` 不会让 TypeScript 收窄类型，
 * 所以这里给一个显式类型守卫 —— 否则渲染处就得处处写 `?? ''`，
 * 而那种兜底会让"前提条件行"被误画成一条空维度。
 */
export function isDimensionRow(
  dimension: ZombieDimensionStatus,
): dimension is ZombieDimensionStatus & { metric: string } {
  return dimension.metric !== null;
}

/** 该维度的判定是否让它成为"无法判定"（界面上要单独提示，别混进"在用"）。 */
export function isUnknown(dimension: ZombieDimensionStatus): boolean {
  return dimension.state === 'UNKNOWN';
}

/** 维度是否可判定（NOT_REQUIRED 也算已知 —— 它只是不参与判定）。 */
export function isDecidable(dimension: ZombieDimensionStatus): boolean {
  return dimension.state !== 'UNKNOWN';
}

/** 维度取值展示。NULL 观测与"观测到 0"必须能区分（规则 Z1）。 */
export function dimensionValueText(dimension: ZombieDimensionStatus): string {
  if (dimension.value === null || dimension.value === undefined) return '—';
  return dimension.value === 0 ? '0' : String(dimension.value);
}

/** 观测时刻展示。带"已过期"标记 —— 运营要分清"从没观测过"与"太旧了"。 */
export function dimensionObservedText(dimension: ZombieDimensionStatus): string {
  if (!dimension.observedAt) return '未观测';
  const at = new Date(dimension.observedAt);
  const text = Number.isNaN(at.getTime()) ? dimension.observedAt : at.toLocaleString('zh-CN');
  return dimension.stale ? `${text}（已过期）` : text;
}

// ================================================================ 列表查询

export type CandidateListQuery = {
  entityType: string;
  keyword: string;
  verdict: string;
  page: number;
  size: number;
};

export function emptyCandidateListQuery(): CandidateListQuery {
  return { entityType: 'SKU', keyword: '', verdict: '', page: 0, size: 20 };
}

export function candidateQueryParams(query: CandidateListQuery): URLSearchParams {
  const params = new URLSearchParams();
  params.set('entityType', query.entityType);
  if (query.keyword.trim()) params.set('keyword', query.keyword.trim());
  if (query.verdict) params.set('verdict', query.verdict);
  params.set('page', String(query.page));
  params.set('size', String(query.size));
  return params;
}

// ================================================================ 表单校验

/** 维护阈值：30~3650，与 V39 的 DB CHECK 和后端 @Min/@Max 同口径。 */
export function inactiveDaysProblem(value: number): string | null {
  if (!Number.isInteger(value)) return '必须是整数天';
  if (value < 30) return '至少 30 天（低于一个补货周期，误停用风险极高）';
  if (value > 3650) return '最多 3650 天';
  return null;
}

/** 快照过期阈值：1~3650。0 会让任何非本秒写入的快照立刻失效，等于关掉这个维度。 */
export function staleDaysProblem(value: number): string | null {
  if (!Number.isInteger(value)) return '必须是整数天';
  if (value < 1) return '至少 1 天（设成 0 等于永远失效）';
  if (value > 3650) return '最多 3650 天';
  return null;
}

export function deactivateNoteProblem(value: string): string | null {
  if (!value.trim()) return '停用记录要能说明依据，不能为空';
  if (value.length > 500) return '最多 500 字';
  return null;
}

export function ruleProblem(input: ZombieCleanupRuleInput): string | null {
  return (
    inactiveDaysProblem(input.inactiveDays) ??
    staleDaysProblem(input.snapshotStaleDays) ??
    deactivateNoteProblem(input.deactivateNote)
  );
}

/** 表单初值：以服务端规则为准（没有配置过的类型服务端会给默认值）。 */
export function toRuleForm(view: ZombieCleanupRuleView): ZombieCleanupRuleInput {
  return {
    inactiveDays: view.inactiveDays,
    snapshotStaleDays: view.snapshotStaleDays,
    requireZeroOrderReference: view.requireZeroOrderReference,
    requireZeroInventory: view.requireZeroInventory,
    requireZeroListing: view.requireZeroListing,
    requireZeroCost: view.requireZeroCost,
    enabled: view.enabled,
    deactivateNote: view.deactivateNote,
    version: view.version,
  };
}

// ================================================================ 展示辅助

/** 候选行的主标题：编码 + 名称。名称空时也不塌成空白。 */
export function candidateHeadline(candidate: ZombieCandidate): string {
  return candidate.name ? `${candidate.code} ${candidate.name}` : candidate.code;
}

/**
 * 「距今多少天没维护」文案。
 *
 * <p>null（从未维护）与 0（今天刚维护）必须能区分 —— 前者是治理信号，后者不是。
 */
export function maintenanceText(candidate: ZombieCandidate): string {
  if (candidate.daysSinceMaintained === null || candidate.daysSinceMaintained === undefined)
    return '从未维护';
  if (candidate.daysSinceMaintained === 0) return '今天维护过';
  return `${candidate.daysSinceMaintained} 天未维护`;
}

/**
 * 规则启用了几个引用维度。
 *
 * <p>后端的 {@code ZombieCleanupRuleView.requiredDimensionCount()} 是 record 之外的
 * 便利方法，SpringDoc **不会**导出它（生成物里没有，前端会 TS2339）。所以这里按
 * 四个布尔列现算 —— 它们就是契约里的全部事实。
 */
export function requiredDimensionCount(rule: {
  requireZeroOrderReference: boolean;
  requireZeroInventory: boolean;
  requireZeroListing: boolean;
  requireZeroCost: boolean;
}): number {
  return (
    (rule.requireZeroOrderReference ? 1 : 0) +
    (rule.requireZeroInventory ? 1 : 0) +
    (rule.requireZeroListing ? 1 : 0) +
    (rule.requireZeroCost ? 1 : 0)
  );
}

/** 三个判定计数相加是否等于 total（分页漏算时为 false）。 */
export function countsConsistent(page: {
  total: number;
  candidateCount: number;
  inUseCount: number;
  incompleteCount: number;
}): boolean {
  return page.candidateCount + page.inUseCount + page.incompleteCount === page.total;
}

/** 概览里"这条规则依赖几个上游"的人话。 */
export function requiredDimensionText(count: number): string {
  if (count === 0) return '未依赖引用维度（只看维护时间）';
  return `依赖 ${count} 个引用维度`;
}

/** 批量停用按钮的禁用原因。返回 null 表示可以停。 */
export function deactivateBlockReason(
  candidate: ZombieCandidate,
): string | null {
  switch (candidate.verdict as Verdict) {
    case 'CANDIDATE':
      return null;
    case 'NOT_ZOMBIE':
      return '判定为在用，不属于僵尸';
    case 'INCOMPLETE':
      return '无法判定（缺少有效观测），不能停用';
    default:
      return '未知判定状态';
  }
}

/** 是否可以勾选这一行参与批量停用。 */
export function selectable(candidate: ZombieCandidate): boolean {
  return deactivateBlockReason(candidate) === null;
}

/** 批量停用前置校验：一条都不可停用时按钮直接禁用并说明原因。 */
export function batchBlockReason(selected: ZombieCandidate[]): string | null {
  if (selected.length === 0) return '先勾选要停用的主数据';
  return null;
}

// ================================================================ 错误文案

export function zombieErrorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) {
      const body = error.body as { detail?: string; message?: string } | null;
      const detail = body?.detail ?? body?.message;
      if (detail) return detail;
    }
    return error.message;
  }
  return error instanceof Error ? error.message : '加载失败，请重试';
}

/**
 * 批量停用被整批拒绝时（规则 Z4）的逐条原因。
 *
 * <p>必须逐条显示而不是只显示第一句：整批拒收的原因往往有好几条不同的原因
 * （有的在用、有的是观测缺失），只给一句的话运营会以为"全都有问题"而放弃处理。
 */
export function blockedLines(error: unknown): string[] {
  if (!(error instanceof ApiError) || error.status !== 409) return [];
  const body = error.body as { blocked?: { code?: string; name?: string; reason?: string }[] } | null;
  if (!body?.blocked?.length) return [];
  return body.blocked.map(
    (item) => `${item.code ?? ''} ${item.name ?? ''} —— ${item.reason ?? '不满足停用条件'}`.trim(),
  );
}

// ================================================================ 接口

export async function fetchZombieOptions(): Promise<ZombieOptions> {
  return api<ZombieOptions>('catalog/zombie-candidates/options');
}

export async function fetchZombieOverview(): Promise<ZombieCandidateOverview> {
  return api<ZombieCandidateOverview>('catalog/zombie-candidates/overview');
}

export async function fetchCleanupRules(): Promise<ZombieCleanupRuleView[]> {
  return api<ZombieCleanupRuleView[]>('catalog/zombie-candidates/rules');
}

export async function saveCleanupRule(
  entityType: string,
  input: ZombieCleanupRuleInput,
): Promise<ZombieCleanupRuleView> {
  return api<ZombieCleanupRuleView>(
    `catalog/zombie-candidates/rules/${entityType}`,
    'PUT',
    input,
  );
}

export async function fetchZombieCandidates(
  query: CandidateListQuery,
): Promise<ZombieCandidatePage> {
  return api<ZombieCandidatePage>(
    `catalog/zombie-candidates?${candidateQueryParams(query).toString()}`,
  );
}

export async function fetchZombieEvidence(
  entityType: string,
  entityId: string,
): Promise<ZombieReferenceEvidence> {
  const params = new URLSearchParams({ entityType, entityId });
  return api<ZombieReferenceEvidence>(`catalog/zombie-candidates/evidence?${params.toString()}`);
}

export async function deactivateZombies(
  entityType: string,
  entityIds: string[],
  reason: string,
): Promise<ZombieDeactivateResult> {
  return api<ZombieDeactivateResult>(
    `catalog/zombie-candidates/deactivate?entityType=${encodeURIComponent(entityType)}`,
    'POST',
    { entityIds, reason },
  );
}

export async function fetchCleanupRecords(params: {
  entityType?: string;
  entityId?: string;
  page?: number;
  size?: number;
}): Promise<ZombieCleanupRecordPage> {
  const search = new URLSearchParams();
  if (params.entityType) search.set('entityType', params.entityType);
  if (params.entityId) search.set('entityId', params.entityId);
  search.set('page', String(params.page ?? 0));
  search.set('size', String(params.size ?? 20));
  return api<ZombieCleanupRecordPage>(`catalog/zombie-candidates/records?${search.toString()}`);
}