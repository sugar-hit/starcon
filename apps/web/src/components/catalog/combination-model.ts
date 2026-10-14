import type {
  AxisOption,
  BoundAxis,
  CombinationCheckInput,
  CombinationCheckResult,
  CombinationConflict,
  CombinationInput,
  CombinationValue,
  CombinationValueInput,
  ExistingCombination,
  SkuMatrixDraft,
  SkuMatrixDraftSummary,
  VariantCombinationOverview,
  VariantCombinationPanel,
  VariantDraftInput,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 3.1.3.2 组合唯一性校验前端模型。
 *
 * <p>页面上最容易出错的三处：
 *
 * <ul>
 *   <li>**「校验通过」不等于「可以生成」**。矩阵里已经被占用的组合会让生成整份失败
 *       （本模块是全有或全无），所以界面必须把「矩阵共 N 条 / 已占 M 条 / 还可生成 K 条」
 *       三个数分开写，不能只画一个"N 个组合"。
 *   <li>**冲突要逐条显示，不能压成一句提示**。运营要靠 `reasonCode` 判断下一步：
 *       撞历史 SKU 和撞上一份草稿，处理方式完全不同。
 *   <li>**草稿不是 SKU**。生成之后 `sku` 表里一行都没有，下游 3.1.2.1 才真正落库。
 *       界面上必须说清"这是待建草稿"，否则会有人以为 SKU 已经建好了。
 * </ul>
 *
 * <p>签名算法与后端 {@code CombinationRules} 同口径：轴编码升序、`编码=取值`、`|` 连接。
 * 前端算一遍只是为了**提交前即时提示**，真判定永远以服务端为准——蓝图明确要求
 * 唯一性由数据库层保证，前端校验只负责友好提示。
 */

// ------------------------------------------------------------ 接口

export function fetchOverview() {
  return api<VariantCombinationOverview>('catalog/variant-combination/overview');
}

export function fetchPanel(spuId: string) {
  return api<VariantCombinationPanel>(`catalog/variant-combination/spus/${spuId}`);
}

export function fetchCombinations(spuId: string) {
  return api<ExistingCombination[]>(`catalog/variant-combination/spus/${spuId}/combinations`);
}

export function fetchDrafts(spuId: string) {
  return api<SkuMatrixDraftSummary[]>(`catalog/variant-combination/spus/${spuId}/drafts`);
}

export function fetchDraft(spuId: string, draftId: string) {
  return api<SkuMatrixDraft>(
    `catalog/variant-combination/spus/${spuId}/drafts/${draftId}`,
  );
}

/** 预校验：不写库，返回结构化冲突清单。 */
export function checkCombinations(spuId: string, input: CombinationCheckInput) {
  return api<CombinationCheckResult>(`catalog/variant-combination/spus/${spuId}/check`, 'POST', input);
}

/** 生成待建 SKU 草稿。校验不通过时后端回 409，冲突清单在 body.conflicts 里。 */
export function generateDraft(spuId: string, input: VariantDraftInput) {
  return api<SkuMatrixDraft>(`catalog/variant-combination/spus/${spuId}/drafts`, 'POST', input);
}

/** 作废草稿并释放它占住的组合。 */
export function discardDraft(spuId: string, draftId: string) {
  return api<{ discarded: boolean; releasedCombinationCount: number }>(
    `catalog/variant-combination/spus/${spuId}/drafts/${draftId}`,
    'DELETE',
  );
}

// ------------------------------------------------------------ 错误语义

/** 404：SPU 或草稿不存在，或当前角色/租户看不到它。RLS 已过滤，不区分两者。 */
export function isNotFound(error: unknown) {
  return error instanceof ApiError && error.status === 404;
}

/** 409：组合校验未通过（body 里带 conflicts），或唯一约束/乐观锁冲突。 */
export function isConflict(error: unknown) {
  return error instanceof ApiError && error.status === 409;
}

/**
 * 从 409 响应里取出结构化冲突清单。
 *
 * <p>后端把清单挂在 ProblemDetail 的 `conflicts` 属性上。取不到就返回空数组——
 * 调用方据此区分"有清单要显示"与"只是一句通用提示"，不能把两者混为一谈。
 */
export function conflictsFromError(error: unknown): CombinationConflict[] {
  const body = (error as { body?: { conflicts?: unknown } } | null)?.body;
  const conflicts = body?.conflicts;
  return Array.isArray(conflicts) ? (conflicts as CombinationConflict[]) : [];
}

/** 有结构化清单的错误：界面要按条渲染，而不是只显示一句 message。 */
export function hasConflictList(error: unknown) {
  return conflictsFromError(error).length > 0;
}

export function errorText(error: unknown, fallback = '操作失败') {
  return error instanceof Error && error.message ? error.message : fallback;
}

// ------------------------------------------------------------ 签名与本地预校验（与后端 CombinationRules 同口径）

export const PAIR_SEPARATOR = '|';
export const ASSIGN = '=';

/** 组合里的一对「轴编码 + 取值」。 */
export type Part = { axisCode: string; optionValue: string };

/**
 * 规范化签名：轴编码升序 + `编码=取值` + `|` 连接。
 *
 * <p>排序而不是按填写顺序：同一个组合先填尺码再填颜色，与反过来必须是同一个值，
 * 否则唯一性只在"按固定顺序提交"时成立。
 *
 * <p>轴编码被后端限死为 `[A-Z0-9_-]`（纯 ASCII），所以 JS 的 `<` 与数据库的
 * `COLLATE "C"` 是同序；后端的延迟约束触发器会用同一格式重算签名，两边不会分叉。
 */
export function signature(parts: Part[]): string | null {
  if (parts.length === 0) return null;
  if (parts.some((part) => part.axisCode === '' || part.optionValue === '')) return null;
  return [...parts]
    .sort((a, b) => (a.axisCode < b.axisCode ? -1 : a.axisCode > b.axisCode ? 1 : 0))
    .map((part) => `${part.axisCode}${ASSIGN}${part.optionValue}`)
    .join(PAIR_SEPARATOR);
}

/** 组合的展示文案：按轴编码排成「颜色 红色 × 尺码 大」。 */
export function combinationText(
  values: Pick<CombinationValue, 'axisName' | 'axisCode' | 'value'>[],
) {
  if (values.length === 0) return '（没有可解析的规格取值）';
  return [...values]
    .sort((a, b) => (a.axisCode < b.axisCode ? -1 : a.axisCode > b.axisCode ? 1 : 0))
    .map((value) => `${value.axisName || value.axisCode} ${value.value}`)
    .join(' × ');
}

/** 冲突原因的展示分组：同一类要挨在一起，让人一眼看出该怎么改。 */
export const CONFLICT_REASON_LABELS: Record<string, string> = {
  DUPLICATE_IN_BATCH: '本次提交内重复',
  ALREADY_RESERVED: '已被矩阵草稿占用',
  ALREADY_SKU: '与已存在的 SKU 相同',
  INCOMPLETE_COMBINATION: '组合不完整',
  COMBINATION_EMPTY: '组合为空',
  COMBINATION_INCOMPLETE: '组合不完整',
  DUPLICATE_AXIS: '同一组合内规格轴重复',
  UNKNOWN_AXIS: '规格轴不属于该 SPU',
  AXIS_DISABLED: '规格轴已停用',
  AXIS_MISSING: '规格轴缺失',
  OPTION_UNKNOWN: '取值不属于该规格轴',
  OPTION_DISABLED: '取值已停用',
  VALUE_INVALID: '取值不合法',
  TOO_MANY_AXES: '组合轴数超限',
  NO_AXIS_BOUND: '该 SPU 没有绑定规格轴',
};

export function conflictLabel(conflict: CombinationConflict) {
  return CONFLICT_REASON_LABELS[conflict.reasonCode] ?? conflict.reasonCode;
}

/** 冲突落在提交的第几条（1 起，界面上说"第几条"比说下标友好）。 */
export function conflictPositionText(conflict: CombinationConflict) {
  if (conflict.occurrenceIndexes.length === 0) return '';
  return conflict.occurrenceIndexes.map((index) => `第 ${index + 1} 条`).join('、');
}

/** 撞的是谁：草稿占用、真实 SKU，还是只撞了自己。 */
export function conflictTargetText(conflict: CombinationConflict) {
  if (conflict.existingSkuId) return '已存在的 SKU';
  if (conflict.existingDraftId) return '另一份矩阵草稿';
  if (conflict.existingCombinationId) return '已被占用的组合';
  return '';
}

// ------------------------------------------------------------ 提交前的本地预校验

/** 提交前能自己判掉的问题（不查库的那些），与后端 reasonCode 同名以便统一显示。 */
export function localConflictReasons(
  combinations: { specAxisId: string; value: string }[][],
  axes: BoundAxis[],
): string[] {
  const reasons: string[] = [];
  if (axes.length === 0) return ['该 SPU 还没有绑定任何规格轴，无法生成组合'];
  const bound = new Map(axes.map((axis) => [axis.specAxisId, axis]));
  const seen = new Set<string>();

  combinations.forEach((values, index) => {
    const label = `第 ${index + 1} 条组合`;
    if (values.length === 0) {
      reasons.push(`${label}：没有任何规格取值`);
      return;
    }
    const axisIds = values.map((value) => value.specAxisId);
    if (new Set(axisIds).size !== axisIds.length) {
      reasons.push(`${label}：同一条规格轴出现了多次`);
    }
    const present = new Set<string>();
    for (const value of values) {
      const axis = bound.get(value.specAxisId);
      if (!axis) {
        reasons.push(`${label}：规格轴不属于该 SPU`);
        continue;
      }
      present.add(axis.code);
      if (!axis.enabled) {
        reasons.push(`${label}：规格轴「${axis.name}」已停用`);
        continue;
      }
      const option = axis.options.find((candidate) => candidate.value === value.value);
      if (!option) {
        reasons.push(`${label}：规格轴「${axis.name}」没有取值「${value.value}」`);
      } else if (!option.enabled) {
        reasons.push(`${label}：取值「${value.value}」已停用`);
      }
    }
    for (const axis of axes)
      if (!present.has(axis.code)) reasons.push(`${label}：缺少规格轴「${axis.name}」的取值`);
    const local = signature(values.map((value) => ({
      axisCode: bound.get(value.specAxisId)?.code ?? '',
      optionValue: value.value.trim(),
    })));
    if (local && seen.has(local)) reasons.push(`${label}：与本次提交里的其它组合重复`);
    if (local) seen.add(local);
  });
  return reasons;
}

// ------------------------------------------------------------ 矩阵与草稿的展示

/**
 * 矩阵可用性的提示文案。
 *
 * <p>把三个数分开讲清楚：矩阵多大、占了多少、还能生成多少。只画"N 个组合"会让人
 * 以为生成按钮一按就能建出 N 个 SKU，而其中一部分其实早就被占了。
 */
export function matrixHint(panel: VariantCombinationPanel): string {
  if (panel.axes.length === 0)
    return '该 SPU 还没有绑定任何规格轴，矩阵为空。先到「规格轴定义」绑定轴并登记取值。';
  if (panel.matrixRowCount === 0)
    return '已绑定规格轴，但至少一条轴没有任何启用取值，矩阵展开为 0 行。先补齐每条轴的启用取值。';
  if (panel.matrixTooLarge)
    return `矩阵按当前取值展开共 ${panel.matrixRowCount} 条，超过单次草稿上限 ${panel.matrixCap}。请停用用不到的取值或减少规格轴后再生成。`;
  return `矩阵共 ${panel.matrixRowCount} 条组合，已被占用 ${panel.conflictMatrixCount} 条（草稿占用 ${panel.reservedCombinationCount} 条、已存在 SKU ${panel.existingSkuCount} 条），还可生成 ${panel.availableMatrixCount} 条。`;
}

/** 草稿状态的展示。DISCARDED 与 PENDING 的下一步动作完全不同，必须分开写。 */
export function draftStatusText(draft: SkuMatrixDraftSummary) {
  if (draft.status === 'DISCARDED')
    return `已于 ${timestamp(draft.discardedAt)} 作废，占用的组合已释放，可以重新生成`;
  return `待兑现：${draft.combinationCount} 条组合已占位，下游落 SKU 后才算真正建成`;
}

export function draftModeText(draft: SkuMatrixDraftSummary) {
  return draft.mode === 'MATRIX' ? '矩阵展开' : '显式提交';
}

/** 已存在组合的来源标签。 */
export function combinationSourceText(combination: ExistingCombination) {
  if (combination.source === 'RESERVED') return '矩阵草稿占用';
  if (combination.source === 'SKU') return '已存在的 SKU';
  // 解析不出轴值的老数据：只能按签名文本比对，必须说清"没比到取值"。
  return '历史 SKU（仅签名文本可比对）';
}

export function enabledOptions(axis: BoundAxis): AxisOption[] {
  return axis.options.filter((option) => option.enabled);
}

export function timestamp(value: string | null | undefined) {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN');
}

// ------------------------------------------------------------ 表单草稿

/** 矩阵模式下按各轴勾选取值展开；显式模式下按行手工录入。 */
export type DraftMode = 'MATRIX' | 'EXPLICIT';

export type SelectionDraft = {
  /** axisId → 勾选的取值数组 */
  picks: Record<string, string[]>;
  notes: string;
  occurredAt: string;
};

export function emptySelectionDraft(axes: BoundAxis[]): SelectionDraft {
  const picks: Record<string, string[]> = {};
  for (const axis of axes) picks[axis.specAxisId] = [];
  return { picks, notes: '', occurredAt: '' };
}

/** 同步轴集合：新增的轴补一个空数组，删掉的轴从草稿里去掉。 */
export function syncPicks(picks: Record<string, string[]>, axes: BoundAxis[]) {
  const next: Record<string, string[]> = {};
  for (const axis of axes) next[axis.specAxisId] = picks[axis.specAxisId] ?? [];
  return next;
}

export function togglePick(draft: SelectionDraft, axisId: string, value: string) {
  const current = draft.picks[axisId] ?? [];
  return {
    ...draft,
    picks: {
      ...draft.picks,
      [axisId]: current.includes(value)
        ? current.filter((candidate) => candidate !== value)
        : [...current, value],
    },
  };
}

/** 矩阵模式下的组合数：各轴勾选数相乘，有一条轴一个没选就是 0（不按 1 假装有值）。 */
export function selectionRowCount(axes: BoundAxis[], picks: Record<string, string[]>) {
  if (axes.length === 0) return 0;
  let rows = 1;
  for (const axis of axes) {
    const size = (picks[axis.specAxisId] ?? []).length;
    if (size === 0) return 0;
    rows *= size;
  }
  return rows;
}

/**
 * 矩阵模式的提交体。
 *
 * <p>矩阵模式不需要前端拼组合：各轴勾选取值由后端展开笛卡尔积。前端只负责把勾选状态
 * 传过去（{@link VariantDraftInput.mode=MATRIX} 时 combinations 留空），
 * 这样"矩阵有多大"永远只有一个口径——服务端的 {@code CombinationRules.matrixSize}。
 */
export function matrixDraftInput(draft: SelectionDraft): VariantDraftInput {
  return {
    mode: 'MATRIX',
    combinations: null,
    notes: draft.notes.trim() === '' ? null : draft.notes.trim(),
    occurredAt: draft.occurredAt.trim() === '' ? null : draft.occurredAt.trim(),
  };
}

export type ExplicitDraft = {
  rows: { specAxisId: string; value: string }[][];
  notes: string;
  occurredAt: string;
};

export function emptyExplicitDraft(): ExplicitDraft {
  return { rows: [[]], notes: '', occurredAt: '' };
}

export function explicitToInput(draft: ExplicitDraft): VariantDraftInput {
  const combinations: CombinationInput[] = draft.rows
    .map((row) => ({
      values: row
        .filter((cell) => cell.specAxisId !== '' && cell.value.trim() !== '')
        .map((cell) => ({ specAxisId: cell.specAxisId, value: cell.value.trim() })),
    }))
    .filter((combination) => (combination.values?.length ?? 0) > 0);
  return {
    mode: 'EXPLICIT',
    combinations,
    notes: draft.notes.trim() === '' ? null : draft.notes.trim(),
    occurredAt: draft.occurredAt.trim() === '' ? null : draft.occurredAt.trim(),
  };
}

/** 显式模式的本地预校验输入。 */
export function explicitCombinations(draft: ExplicitDraft): CombinationValueInput[][] {
  return draft.rows.map((row) =>
    row
      .filter((cell) => cell.specAxisId !== '' && cell.value.trim() !== '')
      .map((cell) => ({ specAxisId: cell.specAxisId, value: cell.value.trim() })),
  );
}

/** 业务时间必须是 ISO 串（后端 OffsetDateTime.parse 只吃这一种），留空表示"就用现在"。 */
export function occurredAtProblem(value: string) {
  const trimmed = value.trim();
  if (trimmed === '') return null;
  if (Number.isNaN(new Date(trimmed).getTime()))
    return '业务时间请填 ISO 格式（如 2026-01-02T03:04:05Z），留空表示使用当前时间';
  return null;
}