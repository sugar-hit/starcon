import type {
  SkuMergeOverview,
  SkuMergePage,
  SkuMergePreview,
  SkuMergeReferenceRow,
  SkuMergeReport,
  SkuMergeRow,
  SkuMergeTrace,
} from '../../../../../packages/api-contracts/src/index';
import { api } from './api';

/**
 * 3.9.1.2 SKU 合并前端模型。
 *
 * <p>蓝图：「执行 SKU/SPU 合并：把重复记录归并到主记录，迁移关联……原记录标记合并并停用而非物理删除；
 * 可回溯合并来源。」界面纪律由这一条决定 —— <b>合并会改写十几张表</b>，所以：
 *
 * <ul>
 *   <li><b>先预演再执行。</b>{@link executeMerge} 之前必须先能拿到 {@link SkuMergePreview}，
 *       页面把「会搬多少行、哪几行搬不动、哪些是刻意不动的历史」摆在确认按钮上方。
 *       {@link canExecuteMerge} 是唯一的执行门禁，且它读的就是预演返回的 {@code executable} ——
 *       不在前端另算一遍版本比对，因为那样预演与执行就会有两套判定。
 *   <li><b>五种计数不能折叠成一个数。</b>{@link reportSummary} 把「已改指 / 已相加 / 已去重 /
 *       搬不动 / 刻意不动」分开报：它们的处置动作不同，
 *       「报价 3 条搬不动、素材 2 张已去重」和「共 5 处引用」不是同一个可操作信息。
 *   <li><b>「搬不动」与「原理上不能搬」分开。</b>前者（{@code conflictCount}）要人工去解冲突，
 *       后者（{@code blocked}）要换一个合并范围才能处理。页面用不同的样式分开显示。
 *   <li><b>历史冻结不是遗漏。</b>{@link reportNote} 必须在界面上说清
 *       「历史订单行冻结当时快照，不随合并改写」，否则运营会把「没搬」当成「搬漏了」。
 * </ul>
 */

/** 引用维度 → 中文名与处置提示。与后端 {@code SkuMergeRules} 同一词表。 */
export const DIMENSION_HINTS: Record<string, { label: string; hint: string }> = {
  BARCODE: { label: '条码与物料标识', hint: '条码是外部唯一标识，搬得动就跟着主记录走' },
  ATTRIBUTE: { label: '属性值', hint: '主记录已设过同一属性时不覆盖 —— 规格不能被另一条商品的值顶掉' },
  MEDIA: { label: '素材引用', hint: '两条重复 SKU 挂同一张图是重复的产物，冲突行按去重删除' },
  TRANSLATION: { label: '多语言标题', hint: '主记录已有该语种译文时不覆盖（译文是人工审过的）' },
  QUOTE: { label: '供应商报价', hint: '蓝图的「价格」：报价必须跟着主记录走，否则合并后少一个供应商的价' },
  BUNDLE: { label: '套装组件', hint: '同一套装版本里同时挂着两条时，用量相加（每套需要两条之和）' },
  BUNDLE_SKU: { label: '套装代表', hint: '一个 SKU 至多代表一个套装，主记录已是代表时不覆盖' },
  BOM_OUTPUT: { label: 'BOM 产出物', hint: '主记录已有 BOM 时不覆盖 —— 两份工艺文件不能自动合成一份' },
  BOM: { label: 'BOM 组件', hint: '同一 BOM 版本里同时挂着两条时，单位用量相加' },
  PACKAGE: { label: '包装参数', hint: '主记录已有包装参数时留在原处 —— 重量尺寸是报关与运费的事实' },
  SAFETY: { label: '安全属性', hint: '主记录已采集过时不覆盖 —— 电池/危险品是合规申报的输入' },
  UNIT_RULE: { label: '单位换算规则', hint: '活的换算表，必须跟着主记录走，否则 06 建采购单会按错比例算数量' },
  UNIT_CONVERSION_SNAPSHOT: {
    label: '单位换算快照',
    hint: '采购单/入库单的行冻结快照 —— 历史事实，刻意不搬',
  },
  STATUS_EVENT: {
    label: 'SKU 状态事件',
    hint: '生命周期历史事件流，合并只追加一条，不改已有的',
  },
  IMPORT_HISTORY: {
    label: '批量导入行',
    hint: '导入历史，导入报告要能回答「这行是哪次导入进来的」',
  },
  TITLE_HISTORY: { label: '标题生成记录', hint: '标题生成那一轮的历史与快照' },
  RENDER_ARTIFACT: {
    label: '详情渲染产物',
    hint: '渲染历史产物：那一版的结论，含当时发现的渲染问题',
  },
  TRANSLATION_HISTORY: {
    label: '翻译批次与建议',
    hint: '翻译处理历史：某条建议被采纳或拒绝过，是可回溯的处理痕迹',
  },
  BOM_EXPLOSION_HISTORY: {
    label: 'BOM 爆炸历史',
    hint: '一次展开的历史运行与结果；重跑会另起一次运行',
  },
  CODE_HISTORY: {
    label: '编码与批量操作历史',
    hint: '编码分配与批量操作的历史执行记录',
  },
  DUPLICATE_HISTORY: {
    label: '重复识别候选与决定',
    hint: '3.9.1.1 的历史记录，也就是本次合并的来源 —— 改了它就再也回溯不了',
  },
};

/** 版本比对六种结论的中文标签。未知值原样返回，不吞错：页面上出现陌生结论本身就是问题。 */
export const VERSION_CHECKS: Record<string, { label: string; tone: 'ok' | 'warn' | 'stop' }> = {
  MATCHED: { label: '决定仍然成立', tone: 'ok' },
  STALE: { label: '决定已过期', tone: 'stop' },
  ALREADY_EXECUTED: { label: '已执行过', tone: 'stop' },
  NOT_DECIDED: { label: '尚未登记合并决定', tone: 'stop' },
  ALREADY_MERGED: { label: '记录已被合并过', tone: 'stop' },
  KEEP: { label: '判为保留两条', tone: 'stop' },
};

export function versionCheckLabel(check: string): string {
  return VERSION_CHECKS[check]?.label ?? check;
}

export function versionCheckTone(check: string): 'ok' | 'warn' | 'stop' {
  return VERSION_CHECKS[check]?.tone ?? 'stop';
}

export function dimensionLabel(dimension: string): string {
  return DIMENSION_HINTS[dimension]?.label ?? dimension;
}

export function dimensionHint(dimension: string): string {
  return DIMENSION_HINTS[dimension]?.hint ?? '';
}

/**
 * 执行门禁：<b>只读预演返回的 {@code executable}</b>，前端不自己重算版本比对。
 *
 * <p>理由：预演与执行在后端共用同一套判定（{@code SkuMergeRules.versionCheck}）。
 * 前端若另算一遍，就会在「后端其实已经拦住了、但按钮是亮的」时让用户点一次必然失败的请求。
 */
export function canExecuteMerge(preview: SkuMergePreview | null): boolean {
  return preview != null && preview.executable === true;
}

/** 门禁不通过时给用户看的那句话；通过时返回 null（页面据此不渲染警告块）。 */
export function blockReason(preview: SkuMergePreview | null): string | null {
  if (preview == null) return null;
  if (preview.executable) return null;
  return preview.blockReason || preview.versionCheckLabel;
}

/**
 * 报告合计的一句话。
 *
 * <p>五种计数分开报而不是加总 —— 加总的数字答不了「哪几类引用在挡路」。
 * 没有可动项时给「不需要搬任何引用」而不是一排 0，那两种说法对运营的意义完全不同。
 */
export function reportSummary(report: SkuMergeReport | null): string {
  if (report == null) return '—';
  const parts: string[] = [];
  if (report.totalMigrated > 0) parts.push(`改指主记录 ${report.totalMigrated} 行`);
  if (report.totalSummed > 0) parts.push(`用量相加并入 ${report.totalSummed} 行`);
  if (report.totalDeduplicated > 0) parts.push(`去重删除 ${report.totalDeduplicated} 行`);
  if (report.totalConflict > 0) parts.push(`搬不动、留在原处 ${report.totalConflict} 行`);
  if (report.totalFrozen > 0) parts.push(`历史冻结 ${report.totalFrozen} 行`);
  if (parts.length === 0) return '不需要搬动任何引用（被合并记录上没有当前引用）';
  return parts.join('、');
}

/** 报告里「有东西要处理」的行（搬不动、被拒）。没这些行就只展示合计，不铺开 21 行。 */
export function actionableReferences(report: SkuMergeReport | null): SkuMergeReferenceRow[] {
  if (report == null) return [];
  return report.references.filter(
    (r) => r.conflictCount > 0 || r.blocked || r.deduplicatedCount > 0 || r.summedCount > 0,
  );
}

/** 报告里被整维度拒绝的维度名。页面要把它显示成一句警告，而不是混在 conflict 里。 */
export function blockedDimensions(report: SkuMergeReport | null): SkuMergeReferenceRow[] {
  if (report == null) return [];
  return report.references.filter((r) => r.blocked);
}

/** 报告的总体说明（历史冻结的纪律、跨 SPU 的边界）。 */
export function reportNote(report: SkuMergeReport | null): string {
  if (report == null) return '';
  return report.note;
}

/** 一条引用维度在这一行里发生了什么，写给人看。 */
export function referenceOutcome(row: SkuMergeReferenceRow): string {
  if (row.blocked) return '整维度不搬：这一类引用带源商品的标识，跨商品改指会指向不存在的组合';
  if (row.sourceCount === 0) return '没有引用';
  const parts: string[] = [];
  if (row.migratedCount > 0) parts.push(`改指 ${row.migratedCount}`);
  if (row.summedCount > 0) parts.push(`相加 ${row.summedCount}`);
  if (row.deduplicatedCount > 0) parts.push(`去重 ${row.deduplicatedCount}`);
  if (row.conflictCount > 0) parts.push(`搬不动 ${row.conflictCount}`);
  if (row.frozenCount > 0) parts.push(`冻结 ${row.frozenCount}`);
  return parts.length > 0 ? parts.join(' · ') : '没有引用';
}

/** 跨 SPU 时源商品上剩下的 SKU 数，页面据此提示「还有几条没被这次决定覆盖」。 */
export function remainingSpuNote(preview: SkuMergePreview | null): string | null {
  if (preview == null || preview.scope !== 'SPU') return null;
  if (preview.secondarySpuRemainingSkuCount <= 0) return null;
  return `源商品上还有 ${preview.secondarySpuRemainingSkuCount} 条 SKU 不在这次决定范围内，它们的引用一条都没动。`;
}

/** 一次合并的时间展示。 */
export function timeText(value: string | null | undefined): string {
  if (!value) return '—';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '—';
  return parsed.toLocaleString('zh-CN', { hour12: false });
}

/** 合并血缘的一句话：被并进的 / 吸收了谁的 / 从未参与。 */
export function traceSummary(trace: SkuMergeTrace | null): string {
  if (trace == null) return '';
  if (trace.mergedAway) {
    return `已被并进主记录 ${trace.mergedIntoCode ?? ''}（${timeText(trace.mergedAt)}）`;
  }
  if (trace.absorbed.length > 0) return `主记录，吸收了 ${trace.absorbed.length} 条重复记录`;
  return '从未参与合并';
}

// ================================================================ 接口

export function fetchMergeOverview(): Promise<SkuMergeOverview> {
  return api<SkuMergeOverview>('catalog/sku-merges/overview');
}

export function fetchMerges(page = 0, size = 20): Promise<SkuMergePage> {
  return api<SkuMergePage>(`catalog/sku-merges?page=${page}&size=${size}`);
}

export function fetchMergePreview(candidateId: string): Promise<SkuMergePreview> {
  return api<SkuMergePreview>(`catalog/duplicate-candidates/${candidateId}/merge-preview`);
}

/**
 * 执行合并。
 *
 * <p>{@code confirm} 必须为 true —— 后端把它当成显式确认，缺省时按预演处理（不写任何东西）。
 */
export function executeMerge(
  candidateId: string,
  note: string,
): Promise<SkuMergeRow> {
  return api<SkuMergeRow>(`catalog/duplicate-candidates/${candidateId}/merge`, 'POST', {
    note: note.trim(),
    confirm: true,
  });
}

export function fetchTrace(skuId: string): Promise<SkuMergeTrace> {
  return api<SkuMergeTrace>(`catalog/sku-merges/by-sku/${skuId}`);
}

/** 合并备注的本地校验（后端也会拒，但先拦一次能立刻告诉用户「填错了什么」）。 */
export function validateNote(note: string): string | null {
  if (note.length > 1000) return '合并备注最多 1000 字';
  return null;
}
