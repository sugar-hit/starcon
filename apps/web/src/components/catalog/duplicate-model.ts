import type {
  DuplicateCandidateDetail,
  DuplicateCandidatePage,
  DuplicateCandidateRow,
  DuplicateDecisionRow,
  DuplicateOverview,
  DuplicateScanPage,
  DuplicateScanRow,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from './api';

/**
 * 3.9.1.1 重复 SKU 识别前端模型。
 *
 * <p>蓝图说「重复 SKU 会污染销量、采购、库存与利润统计（同一商品被拆成两条，各算各的）」——
 * 所以这一页的全部目的就是**让人把该合的合掉**。界面纪律只有一条，但它决定所有细节：
 *
 * <ul>
 *   <li><b>相似度与重叠维度必须一起看。</b>相似度高但只中一条线，和相似度略低但四条线全中，
 *       处理优先级不同。所以 {@link signalSummary} 把「命中了几条线」排在分数前面。
 *   <li><b>影响引用数是「合并合不合得动」的依据</b>，不是装饰。{@link referenceSummary}
 *       只列非零项：九项全列出来没人看得下去，而「报价 3 条」正是运营要先看的。
 *   <li><b>范围收窄过的扫描，0 个候选不等于干净。</b>{@link scopeWarning} 把这件事摆在概览顶部，
 *       而不是藏在一个灰色小字里 —— 分片扫描后拿「0 个候选」当治理完成的结论，
 *       会让报表继续错下去。
 *   <li><b>本模块只登记决定，不执行合并。</b>{@link decisionButtonLabel} 与
 *       {@link executionHint} 必须把这条说死，否则用户会以为点完就合并了。
 * </ul>
 */

/** 四个检测维度：与 V36 的 CHECK 约束、后端 {@code DuplicateRules.Dimension} 同一词表。 */
export const DIMENSIONS = [
  {
    value: 'BARCODE',
    label: '同条码多 SKU',
    hint: '条码是外部唯一标识，撞了就是最硬的一条：扫码拣货必然有一个拿错',
  },
  {
    value: 'VARIANT_COMBINATION',
    label: '同 SPU 重复规格组合',
    hint: '按轴值现算的组合相同。批量导入写的是占位签名，唯一约束拦不住这种重复',
  },
  {
    value: 'CROSS_SPU',
    label: '同商品不同 SPU 重复建档',
    hint: '名称（归一化后）、品牌、类目三者全同，而编码不同',
  },
  {
    value: 'SIMILAR_PRODUCT',
    label: '近似标题+相似图片疑似同品',
    hint: '标题近似**且**至少共用一张素材，两条证据同时成立才算',
  },
] as const;

export type DimensionValue = (typeof DIMENSIONS)[number]['value'];

/** 决定筛选的三态：PENDING 是「还没人判」，与 MERGE / KEEP 并列。 */
export const DECISION_FILTERS = [
  { value: '', label: '全部' },
  { value: 'PENDING', label: '待确认' },
  { value: 'MERGE', label: '确认合并' },
  { value: 'KEEP', label: '保留两条' },
] as const;

/** 默认判定阈值：与后端 {@code DuplicateRules.DEFAULT_MIN_SIMILARITY} 同一个数。 */
export const DEFAULT_MIN_SIMILARITY = '0.8000';

/** 相似度展示：四位小数转百分比，保留一位。分数之间的差别很小，四舍五入到整数会看不出来。 */
export function similarityText(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return `${(value * 100).toFixed(1)}%`;
}

/** 维度名（未知词表原样返回，不吞错：页面上出现陌生维度名本身就是问题）。 */
export function dimensionLabel(dimension: string): string {
  return DIMENSIONS.find((d) => d.value === dimension)?.label ?? dimension;
}

/**
 * 候选行的判读摘要：**先说命中几条线，再说最高分**。
 *
 * <p>顺序是刻意的：蓝图的输出把「相似度、重叠维度」并列，但决定优先级的是后者 ——
 * 只中一条线的 0.95 与四条全中的 0.83，前者更可能是巧合，后者更可能是真的重复。
 */
export function signalSummary(
  row: Pick<DuplicateCandidateRow, 'dimensions' | 'overlapDimensionCount'>,
): string {
  const count = row.dimensions.length;
  if (count === 0) return '无命中';
  const labels = row.dimensions.map((d) => dimensionLabel(d.dimension)).join(' + ');
  return count === row.overlapDimensionCount
    ? `命中 ${count} 条线：${labels}`
    : `命中 ${row.overlapDimensionCount} 条线（逐维度明细 ${count} 条）：${labels}`;
}

/** 全部命中里的最高分（候选清单上那一栏相似度，与详情逐维度得分对得上）。 */
export function topScore(row: Pick<DuplicateCandidateRow, 'dimensions' | 'similarity'>): number {
  const top = row.dimensions.reduce((max, d) => Math.max(max, d.score ?? 0), 0);
  // 详情里逐维度得分齐全时以它为准；只有整体分（不该发生）才退回整体值。
  return top > 0 ? top : row.similarity;
}

/**
 * 引用数摘要：**只列非零项**。
 *
 * <p>九类全列出来没人看得下去，而真正决定「合并合不合得动」的就是那几项非零的
 * （报价 3 条 vs 素材 1 张，处理成本完全不同）。全为 0 时明确说「无 catalog 侧引用」，
 * 而不是留一段空白让人猜是不是没查。
 */
export function referenceSummary(
  references: DuplicateCandidateDetail['leftReferences'] | undefined,
): string {
  if (!references || references.length === 0) return '未查到引用';
  const nonZero = references.filter((r) => r.count > 0);
  if (nonZero.length === 0) return '无 catalog 侧引用';
  return nonZero.map((r) => `${r.label} ${r.count}`).join(' · ');
}

/** 引用总数（候选行上的 leftReferenceCount / rightReferenceCount）。 */
export function referenceTotal(count: number | null | undefined): number {
  return typeof count === 'number' && count > 0 ? count : 0;
}

/**
 * 范围收窄的警示：只在<b>真的</b>收窄过且候选很少时说话。
 *
 * <p>无条件显示「可能漏检」是噪音，会让人对每次扫描都脱敏；只在「收窄过」时显示才是重点。
 * 文案里必须带上「0 个候选不等于干净」—— 这是分片扫描最容易被误用的地方。
 */
export function scopeWarning(scan: DuplicateScanRow | null | undefined): string | null {
  if (!scan || !scan.scopeApplied) return null;
  const parts = [
    scan.scopeCategory ? `类目 ${scan.scopeCategory}` : '',
    scan.scopeBrand ? `品牌 ${scan.scopeBrand}` : '',
  ].filter(Boolean);
  return `本次扫描按「${parts.join(' / ')}」收窄过，跨范围的重复不会被发现 —— ${
    scan.candidateCount === 0 ? '0 个候选不等于干净' : `本次只覆盖范围内的一部分商品`
  }。要确认全量是否干净，请按全量再扫一次。`;
}

/** 概览页顶部那一句（后端给了 note，这里只做「有没有可说的」的分流）。 */
export function overviewNote(overview: DuplicateOverview | null | undefined): string {
  if (!overview) return '概览还没加载出来。';
  return overview.note ?? '';
}

/** 扫描耗时：毫秒在页面上读不出来，翻译成「不到 1 秒 / 若干秒」。 */
export function durationText(ms: number | null | undefined): string {
  if (typeof ms !== 'number' || ms < 0) return '—';
  if (ms < 1000) return `${ms} 毫秒`;
  return `${(ms / 1000).toFixed(1)} 秒`;
}

/** 扫描时刻：空值说「未记录」，不显示 Invalid Date。 */
export function timeText(value: string | null | undefined): string {
  if (!value) return '未记录';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '未记录';
  return parsed.toLocaleString('zh-CN');
}

// ================================================================ 决定

/** 决定表单的形状。 */
export interface DecisionDraft {
  decision: 'MERGE' | 'KEEP';
  primarySkuId: string;
  note: string;
}

/**
 * 决定按钮的文案。
 *
 * <p>点合并键时不能写成「合并」—— 用户会以为这一步就归并了数据。
 * 「登记合并决定」明确它只是登记，执行由 3.9.1.2 做。
 */
export function decisionButtonLabel(decision: 'MERGE' | 'KEEP'): string {
  return decision === 'MERGE' ? '登记合并决定' : '登记保留决定';
}

/**
 * 决定能不能提交。
 *
 * <p>MERGE 必须指主记录且必须在候选两侧之内 —— 后端也会挡，但前端先拦一次是让用户
 * 立刻看到「为什么不能提交」，而不是提交完吃一个 400。
 */
export function canSubmitDecision(
  draft: DecisionDraft | null,
  detail: DuplicateCandidateDetail | null | undefined,
): { ok: boolean; reason: string } {
  if (!draft) return { ok: false, reason: '请先选择决定' };
  if (draft.decision === 'KEEP') {
    return draft.note.length > 1000
      ? { ok: false, reason: '理由最多 1000 字' }
      : { ok: true, reason: '' };
  }
  if (!detail) return { ok: false, reason: '候选详情还没加载出来' };
  if (!draft.primarySkuId) return { ok: false, reason: '判成合并必须指明保留哪一条' };
  const pair = [detail.candidate.leftSkuId, detail.candidate.rightSkuId];
  if (!pair.includes(draft.primarySkuId))
    return { ok: false, reason: '主记录必须是这个候选里的两条之一' };
  return draft.note.length > 1000
    ? { ok: false, reason: '理由最多 1000 字' }
    : { ok: true, reason: '' };
}

/** 决定请求体（expectedVersion 必带：0 = 首次登记，带值 = 重选，后端据此做乐观锁）。 */
export function decisionPayload(
  draft: DecisionDraft,
  current: DuplicateDecisionRow | null | undefined,
): { decision: string; primarySkuId?: string; note: string; expectedVersion: number } {
  const expectedVersion = current?.version ?? 0;
  if (draft.decision === 'KEEP') return { decision: 'KEEP', note: draft.note, expectedVersion };
  return {
    decision: 'MERGE',
    primarySkuId: draft.primarySkuId,
    note: draft.note,
    expectedVersion,
  };
}

/** 主记录选项：只能是候选的两侧，按「保留 A」/「保留 B」的说法给出。 */
export function primaryOptions(
  detail: DuplicateCandidateDetail | null | undefined,
): { value: string; label: string }[] {
  if (!detail) return [];
  return [
    {
      value: detail.candidate.leftSkuId,
      label: `保留 ${detail.candidate.leftSkuCode}（${detail.candidate.leftSkuName}）`,
    },
    {
      value: detail.candidate.rightSkuId,
      label: `保留 ${detail.candidate.rightSkuCode}（${detail.candidate.rightSkuName}）`,
    },
  ];
}

/**
 * 交接提示：把「登记 ≠ 执行」与 expected_version 的用法说清。
 *
 * <p>后端也会给一句 executionHint，但用户在登记<b>之前</b>就需要知道点完会发生什么，
 * 所以前端在决定区固定显示这句，而不是等登记完才解释。
 */
export function executionHint(decision: 'MERGE' | 'KEEP'): string {
  return decision === 'MERGE'
    ? '这里只登记决定，不改任何商品数据。执行合并的是 3.9.1.2：它会拿本决定里的 expected_version 回来比对，对不上说明登记之后商品被人改过，合并前提已不成立，需要重新确认。'
    : '这里只登记「保留两条」这条决定，不改任何商品数据。若后来发现判错，在这个候选上重新登记即可（带版本乐观锁，不会覆盖别人的改动）。';
}

// ================================================================ 数据访问

export function fetchOverview(): Promise<DuplicateOverview> {
  return api<DuplicateOverview>('catalog/duplicate-scans/overview');
}

export function fetchScans(page = 0, size = 20): Promise<DuplicateScanPage> {
  return api<DuplicateScanPage>(`catalog/duplicate-scans?page=${page}&size=${size}`);
}

export function runScan(
  minSimilarity: string,
  category: string,
  brand: string,
): Promise<DuplicateScanRow> {
  return api<DuplicateScanRow>('catalog/duplicate-scans', 'POST', {
    minSimilarity: Number(minSimilarity),
    category,
    brand,
  });
}

export function fetchCandidates(
  scanId: string,
  options: {
    dimension?: string;
    decision?: string;
    minSimilarity?: string;
    page?: number;
    size?: number;
  },
): Promise<DuplicateCandidatePage> {
  const params = new URLSearchParams();
  if (options.dimension) params.set('dimension', options.dimension);
  if (options.decision) params.set('decision', options.decision);
  if (options.minSimilarity) params.set('minSimilarity', options.minSimilarity);
  params.set('page', String(options.page ?? 0));
  params.set('size', String(options.size ?? 20));
  return api<DuplicateCandidatePage>(
    `catalog/duplicate-scans/${scanId}/candidates?${params.toString()}`,
  );
}

export function fetchCandidateDetail(candidateId: string): Promise<DuplicateCandidateDetail> {
  return api<DuplicateCandidateDetail>(`catalog/duplicate-candidates/${candidateId}`);
}

export function submitDecision(
  candidateId: string,
  payload: ReturnType<typeof decisionPayload>,
): Promise<DuplicateDecisionRow> {
  return api<DuplicateDecisionRow>(
    `catalog/duplicate-candidates/${candidateId}/decision`,
    'POST',
    payload,
  );
}

/**
 * 阈值输入的本地校验（后端也会拒，但先拦一次能立刻告诉用户「填错了什么」）。
 *
 * <p>刻意不接受静默夹取：填 2 不该悄悄变成 1，填 -1 也不该变成 0 ——
 * 夹取会让「阈值生效了吗」这个问题变得无法回答，而这一页的结论完全取决于阈值。
 */
export function validateMinSimilarity(raw: string): { ok: boolean; reason: string; value: number } {
  const trimmed = raw.trim();
  if (trimmed === '')
    return { ok: false, reason: '请填写相似度阈值', value: Number(DEFAULT_MIN_SIMILARITY) };
  const value = Number(trimmed);
  if (!Number.isFinite(value))
    return { ok: false, reason: '阈值必须是数字', value: Number(DEFAULT_MIN_SIMILARITY) };
  if (value < 0 || value > 1) return { ok: false, reason: '阈值必须在 0 到 1 之间', value };
  return { ok: true, reason: '', value };
}

/** 错误文案：409 冲突要单独说（多半是别人改过决定），其余给后端原文。 */
export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) return `${error.message}（可能是别人刚改过这条决定，刷新后再试）`;
    return error.message || `请求失败（${error.status}）`;
  }
  if (error instanceof Error) return error.message;
  return '请求失败，请稍后重试';
}
