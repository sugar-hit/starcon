import type {
  FinanceFilingCapabilities,
  FinanceFilingDraftDetail,
  FinanceFilingDraftInput,
  FinanceFilingDraftLine,
  FinanceFilingDraftPage,
  FinanceFilingExportPage,
  FinanceFilingOptions,
  FinanceFilingSource,
  FinanceFilingSummary,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.6.1.2 VAT/IOSS 申报数据的前端模型。
 *
 * <p>蓝图 12-finance.md：「按辖区与周期汇总申报所需数据： taxable 销售额、已收 VAT、IOSS 订单与
 * 代收金额、可抵扣进项，导出申报文件或对接申报接口。」输出：「申报底稿 + 汇总报表 + 可导出文件。」
 * 关系：「<b>本系统提供申报底稿，不替代税务申报动作</b>——申报主体与责任在用户，
 * 系统保证数据完整可核。」
 *
 * <p><b>命名空间</b>：{@code finance/filing-draft}，与 12.6.1.1 的
 * {@code finance/tax-rates} 刻意不共用前缀 —— 首 token 也不同
 * （{@code filing-draft} ≠ {@code tax-rates}）。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>「读不到」不是「零」</b>：IOSS 两列 / 可抵扣进项 / 汇率 / 折算值 / 实际到账
 *       在读不到时是 {@code null}，页面显示「读不到」而不是 0 —— 0 会被读成
 *       「这个数是零」这个业务结论。</li>
 *   <li><b>多币种绝不相加</b>：汇总按币种分组显示，页面上**没有**一个跨币种总额。</li>
 *   <li><b>产品边界写在页面上</b>：本页产出的是底稿与文件，<b>不提交申报</b>，
 *       也不产生任何「已申报 / 已受理」的结论。</li>
 *   <li><b>辖区不是自由文本</b>：辖区选项来自后端实时读到的受控码表；美国辖区
 *       （联邦与 50 州）在本模块录不进去，页面照着后端的话显示原因。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没取过数」。</li>
 * </ul>
 */

const base = 'finance/filing-draft';

/** 底稿列表的筛选条件。 */
export interface FilingDraftParams {
  jurisdictionCode?: string | undefined;
  taxType?: string | undefined;
  status?: string | undefined;
  periodFrom?: string | undefined;
  periodTo?: string | undefined;
  keyword?: string | undefined;
}

/** 导出留痕列表的筛选条件。 */
export interface FilingExportParams {
  draftId?: string | undefined;
  format?: string | undefined;
}

function query(params: Record<string, string | number | boolean | undefined>): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined) continue;
    if (typeof value === 'string' && value === '') continue;
    parts.push(`${key}=${encodeURIComponent(String(value))}`);
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

// ================================================================ 端点

/** 能力声明：哪些上游真接通、哪些读不到、职责怎么切、哪些还没交付、归谁。 */
export function fetchFilingCapabilities() {
  return api<FinanceFilingCapabilities>(`${base}/capabilities`);
}

/** 筛选项：申报线 / 申报周期 / 状态 / 币种（来自真实底稿）+ 辖区选项（实时受控码表）。 */
export function fetchFilingOptions() {
  return api<FinanceFilingOptions>(`${base}/options`);
}

/** 底稿列表（真分页 + 真筛选）。 */
export function fetchFilingDrafts(params: FilingDraftParams = {}) {
  return api<FinanceFilingDraftPage>(
    `${base}/drafts${query({
      jurisdictionCode: params.jurisdictionCode,
      taxType: params.taxType,
      status: params.status,
      periodFrom: params.periodFrom,
      periodTo: params.periodTo,
      keyword: params.keyword,
    })}`,
  );
}

/** 生成底稿（**取数 + 落库**，服务端一次做完）。 */
export function generateFilingDraft(input: FinanceFilingDraftInput) {
  return api<FinanceFilingDraftDetail>(`${base}/drafts`, 'POST', input);
}

/** 底稿详情（头 + 汇总行 + 数据来源可用性 + 口径）。 */
export function fetchFilingDraft(id: string) {
  return api<FinanceFilingDraftDetail>(`${base}/drafts/${id}`);
}

/** 汇总报表（**与底稿行是同一份数据**的另一种呈现）。 */
export function fetchFilingSummary(id: string) {
  return api<FinanceFilingSummary>(`${base}/drafts/${id}/summary`);
}

/** 导出留痕列表（真分页 + 真筛选）。 */
export function fetchFilingExports(params: FilingExportParams = {}) {
  return api<FinanceFilingExportPage>(
    `${base}/exports${query({ draftId: params.draftId, format: params.format })}`,
  );
}

/**
 * 下载申报底稿文件（**真文件**：CSV / JSON 的真实字节，不是占位文件）。
 *
 * <p>🔴 走浏览器下载（同源代理对 `text/csv` 原样透传）。每次下载都会在服务端留下一条
 * 导出留痕（行数 / 字节数 / 内容哈希）。
 */
export async function downloadFilingExport(id: string, format: 'CSV' | 'JSON'): Promise<void> {
  const response = await fetch(`/api/finance/${base}/drafts/${id}/export?format=${format}`, {
    headers: { Accept: format === 'CSV' ? 'text/csv' : 'application/json' },
    credentials: 'same-origin',
  });
  if (!response.ok) {
    throw new Error(`导出失败（HTTP ${response.status}）`);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `filing-draft-${id}.${format.toLowerCase()}`;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

// ================================================================ 可读名

/** 申报线的可读名（不认识的原样返回 —— 新增取值时页面不会显示 undefined）。 */
export function filingTaxTypeLabel(value: string | null | undefined): string {
  switch (value) {
    case 'VAT':
      return '增值税申报';
    case 'IOSS':
      return 'IOSS 申报';
    default:
      return value ?? '';
  }
}

/** 申报周期的可读名。 */
export function filingCycleLabel(value: string | null | undefined): string {
  switch (value) {
    case 'MONTHLY':
      return '月度';
    case 'QUARTERLY':
      return '季度';
    default:
      return value ?? '';
  }
}

/** 底稿状态的可读名。 */
export function draftStatusLabel(value: string | null | undefined): string {
  switch (value) {
    case 'GENERATED':
      return '生效中';
    case 'SUPERSEDED':
      return '已被取代';
    default:
      return value ?? '';
  }
}

// ================================================================ 展示口径

/**
 * 「读不到」的统一文案。
 *
 * <p>🔴 null 一律用它 —— 显示 0 会被读成「这个数是零」这个业务结论，
 * 而「读不到」和「是零」是两件完全不同的事。
 */
export const UNAVAILABLE = '读不到';

/**
 * 金额的展示。
 *
 * <p>🔴 {@code null} ⇒ 「读不到」，**不是** 0。
 */
export function formatAmount(value: number | null | undefined): string {
  if (value === null || value === undefined) return UNAVAILABLE;
  return value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 });
}

/**
 * 数量的展示（订单数、笔数）。
 *
 * <p>🔴 与金额同理：null ⇒ 「读不到」。
 */
export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined) return UNAVAILABLE;
  return String(value);
}

/**
 * 税率的展示：百分比 + 原始小数。
 *
 * <p>🔴 只做单位换算，不做任何业务算术 —— 税额由服务端算。
 */
export function formatRate(value: number | null | undefined): string {
  if (value === null || value === undefined) return UNAVAILABLE;
  return `${(value * 100).toFixed(2)}%`;
}

/** 汇率的展示（1 本币 = ? 基准币）。 */
export function formatExchangeRate(value: number | null | undefined): string {
  if (value === null || value === undefined) return UNAVAILABLE;
  return value.toFixed(8).replace(/0+$/, '').replace(/\.$/, '');
}

/** 覆盖期间的展示（两个日历日）。 */
export function formatPeriod(
  start: string | null | undefined,
  end: string | null | undefined,
): string {
  if (!start || !end) return '—';
  return `${start.slice(0, 10)} ~ ${end.slice(0, 10)}`;
}

/** 生成时间的展示（到分钟）。 */
export function formatMoment(value: string | null | undefined): string {
  if (!value) return '—';
  return value.slice(0, 16).replace('T', ' ');
}

/** 生效区间的展示。⚠️ 结束时间为空 = **一直生效**，不是「没填」。 */
export function formatEffectiveWindow(
  from: string | null | undefined,
  to: string | null | undefined,
): string {
  const start = from ? from.slice(0, 10) : '—';
  if (!to) return `${start} 起（一直生效）`;
  return `${start} → ${to.slice(0, 10)}`;
}

/**
 * 订单覆盖情况的一句话说明。
 *
 * <p>🔴 **被截断时必须说出来** —— 否则底稿会被当成「这个期间全部的销售」。
 */
export function describeCoverage(draft: {
  orderCount: number;
  includedCount: number;
  outOfScopeCount: number;
  unmappedCount: number;
  orderTruncated: boolean;
}): string {
  const parts = [
    `候选 ${draft.orderCount} 单`,
    `纳入本辖区 ${draft.includedCount} 单`,
    `归属到其它辖区 ${draft.outOfScopeCount} 单`,
    `归属不到国家一级辖区 ${draft.unmappedCount} 单`,
  ];
  if (draft.orderTruncated) {
    parts.push('🔴 本次取数被上限截断，**这不是该期间的全部销售**');
  }
  return parts.join('；');
}

/**
 * 读不到的来源清单（界面据此显示「读不到」而不是「没有数据」）。
 */
export function unavailableSources(sources: FinanceFilingSource[]): FinanceFilingSource[] {
  return sources.filter((source) => !source.available);
}

/**
 * 一份底稿的可读标题（辖区 + 申报线 + 期间）。
 */
export function draftTitle(draft: {
  jurisdictionCode: string;
  jurisdictionName: string;
  taxType: string;
  periodStart: string;
  periodEnd: string;
}): string {
  const name = draft.jurisdictionName?.trim() ? draft.jurisdictionName : draft.jurisdictionCode;
  return `${name} · ${filingTaxTypeLabel(draft.taxType)} · ${formatPeriod(
    draft.periodStart,
    draft.periodEnd,
  )}`;
}

/**
 * 汇总行上「本行是否拿到了可抵扣进项」的说明。
 */
export function deductibleNote(line: FinanceFilingDraftLine): string {
  return line.deductibleAvailable ? '已取到可抵扣进项' : line.deductibleNote;
}
