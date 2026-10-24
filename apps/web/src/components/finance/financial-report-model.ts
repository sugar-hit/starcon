import type {
  FinanceReportCapabilities,
  FinanceReportCheck,
  FinanceReportDrill,
  FinanceReportLedgerEntry,
  FinanceReportOptions,
  FinanceReportResult,
  FinanceReportRow,
  FinanceReportTemplateInput,
  FinanceReportTemplateRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '../../lib/api';

/**
 * 12.5.2.2 财务报表的前端模型。
 *
 * <p>蓝图 12-finance.md：「<b>生成资产负债表、利润表、现金流量表、科目余额表、明细账</b>；支持期间对比、导出、钻取到凭证。」输出：「<b>报表 + 钻取路径 + 导出文件</b>。」关系：「<b>报表数字必须能钻到凭证</b>，"报表与明细对不上"是财务系统的致命问题。」
 *
 * <p><b>命名空间</b>：{@code finance/financial-reports}，与 12.5.2.1 的 {@code finance/vouchers}、
 * 12.4.2.1 的 {@code finance/cost-collections} <b>刻意不共用前缀</b> —— 那是别的模块的命名空间。
 *
 * <p><b>界面纪律</b>（与后端模型一一对应，页面据此渲染而不是各自解释）：
 *
 * <ul>
 *   <li><b>金额只原样展示</b>：所有合计、占比、变动额都由服务端算好，页面<b>不做任何业务算术</b>
 *       （USD 与 EUR 加起来不是任何一个金额）。</li>
 *   <li><b>币种分开</b>：每一处汇总都按币种分组渲染，绝不把不同币种摆进同一列相加。</li>
 *   <li><b>口径写在页面上</b>：草稿不进报表 / 红冲按净额抵消 / 多币种不相加 ——
 *       三条口径由服务端随报表一起返回，页面原样显示，不自己编一套说法。</li>
 *   <li><b>每一行都能钻到凭证</b>：行上的 {@code drillPath} 是服务端给的**真实路径**，
 *       页面把它显示出来，并按同一组参数发起钻取请求。</li>
 *   <li>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「这段时间没有任何已过账的凭证」。</li>
 * </ul>
 */

const base = 'finance/financial-reports';

/**
 * 报表查询条件。
 *
 * <p>⚠️ 「期间范围」是**一段期间序列**（起始期间 ~ 结束期间），不是两个日期：
 * 会计上「本月」「本季度」「年初至今」都是期间序列，日期区间会把跨期凭证算错。
 */
export interface FinancialReportParams {
  /** 起始期间 id（必填）。 */
  periodFromId: string;
  /** 结束期间 id（必填）。 */
  periodToId: string;
  /** 对比期起始期间 id（可选）。 */
  comparePeriodFromId?: string | undefined;
  /** 对比期结束期间 id（可选）。 */
  comparePeriodToId?: string | undefined;
  /** 币种（可选；不填 ⇒ 按币种分组返回全部）。 */
  currency?: string | undefined;
  /** 明细账专用（其它报表忽略）。 */
  subjectCode?: string | undefined;
}

/**
 * 把查询条件摊平成查询串。
 *
 * <p>⚠️ {@code undefined} 与空串**一律跳过**（不是拼成 {@code currency=undefined}）：
 * 未填的可选筛选项不能以「显式 undefined」的形式传给后端。
 */
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

/** 能力声明：哪些真接通、哪些还要租户自己配置（现金流量表的现金科目）、缺口归谁。 */
export function fetchFinancialReportCapabilities() {
  return api<FinanceReportCapabilities>(`${base}/capabilities`);
}

/** 筛选项下拉（全部来自真实数据）。 */
export function fetchFinancialReportOptions() {
  return api<FinanceReportOptions>(`${base}/options`);
}

/** 报表模板列表（系统不预置任何一行，行由租户维护）。 */
export function fetchFinancialReportTemplates(reportType?: string) {
  return api<FinanceReportTemplateRow[]>(`${base}/templates${query({ reportType })}`);
}

/** 登记一条报表模板。 */
export function createFinancialReportTemplate(input: FinanceReportTemplateInput) {
  return api<FinanceReportTemplateRow>(`${base}/templates`, 'POST', input);
}

/** 修改一条报表模板（乐观并发：必须带 expectedVersion）。 */
export function updateFinancialReportTemplate(id: string, input: FinanceReportTemplateInput) {
  return api<FinanceReportTemplateRow>(`${base}/templates/${id}`, 'PUT', input);
}

/**
 * 停用一条报表模板。
 *
 * <p>⚠️ **没有删除**：报表模板是口径配置，停用才是改口径的方式（历史报表口径要能复现）。
 */
export function disableFinancialReportTemplate(id: string) {
  return api<FinanceReportTemplateRow>(`${base}/templates/${id}/disable`, 'POST', {});
}

/** 资产负债表。 */
export function fetchBalanceSheet(params: FinancialReportParams) {
  return api<FinanceReportResult>(
    `${base}/balance-sheet${query({
      periodFromId: params.periodFromId,
      periodToId: params.periodToId,
      comparePeriodFromId: params.comparePeriodFromId,
      comparePeriodToId: params.comparePeriodToId,
      currency: params.currency,
      subjectCode: params.subjectCode,
    })}`,
  );
}

/** 利润表（本期发生额口径，与对比期逐行并排）。 */
export function fetchIncomeStatement(params: FinancialReportParams) {
  return api<FinanceReportResult>(
    `${base}/income-statement${query({
      periodFromId: params.periodFromId,
      periodToId: params.periodToId,
      comparePeriodFromId: params.comparePeriodFromId,
      comparePeriodToId: params.comparePeriodToId,
      currency: params.currency,
      subjectCode: params.subjectCode,
    })}`,
  );
}

/** 现金流量表（需先配置现金科目；未配置时返回空并说明原因）。 */
export function fetchCashFlow(params: FinancialReportParams) {
  return api<FinanceReportResult>(
    `${base}/cash-flow${query({
      periodFromId: params.periodFromId,
      periodToId: params.periodToId,
      comparePeriodFromId: params.comparePeriodFromId,
      comparePeriodToId: params.comparePeriodToId,
      currency: params.currency,
      subjectCode: params.subjectCode,
    })}`,
  );
}

/** 科目余额表（期初 / 本期 / 期末，按科目余额方向取正负）。 */
export function fetchTrialBalance(params: FinancialReportParams) {
  return api<FinanceReportResult>(
    `${base}/trial-balance${query({
      periodFromId: params.periodFromId,
      periodToId: params.periodToId,
      comparePeriodFromId: params.comparePeriodFromId,
      comparePeriodToId: params.comparePeriodToId,
      currency: params.currency,
      subjectCode: params.subjectCode,
    })}`,
  );
}

/** 明细账（逐笔展开**一个**科目）。 */
export function fetchGeneralLedger(params: FinancialReportParams) {
  return api<FinanceReportResult>(
    `${base}/general-ledger${query({
      periodFromId: params.periodFromId,
      periodToId: params.periodToId,
      comparePeriodFromId: params.comparePeriodFromId,
      comparePeriodToId: params.comparePeriodToId,
      currency: params.currency,
      subjectCode: params.subjectCode,
    })}`,
  );
}

/**
 * 钻取到凭证：给出构成某个报表行的**真实凭证 + 分录行**。
 *
 * <p>🔴 参数与报表行上的 {@code drillPath} 完全一致 ⇒ 钻出来的合计必然等于报表上的数字。
 */
export function fetchFinancialReportDrill(params: {
  reportType: string;
  lineCode: string;
  subjectCode?: string | undefined;
  currency?: string | undefined;
  periodFromId: string;
  periodToId: string;
}) {
  return api<FinanceReportDrill>(
    `${base}/drill${query({
      reportType: params.reportType,
      lineCode: params.lineCode,
      subjectCode: params.subjectCode,
      currency: params.currency,
      periodFromId: params.periodFromId,
      periodToId: params.periodToId,
    })}`,
  );
}

/**
 * 导出一张报表（CSV / JSON 真内容）。
 *
 * <p>🔴 导出返回的是**文件**（CSV / JSON 文本），不是 JSON 结构 ⇒ 不能走 {@link api}
 *   （它会把响应当 JSON 解析）。这里直接取文本并触发浏览器下载。
 */
export async function downloadFinancialReport(params: {
  reportType: string;
  format: 'CSV' | 'JSON';
  periodFromId: string;
  periodToId: string;
  comparePeriodFromId?: string | undefined;
  comparePeriodToId?: string | undefined;
  currency?: string | undefined;
  subjectCode?: string | undefined;
}): Promise<string> {
  const path = `${base}/export${query({
    reportType: params.reportType,
    format: params.format,
    periodFromId: params.periodFromId,
    periodToId: params.periodToId,
    comparePeriodFromId: params.comparePeriodFromId,
    comparePeriodToId: params.comparePeriodToId,
    currency: params.currency,
    subjectCode: params.subjectCode,
  })}`;
  const response = await fetch(`/api/finance/${path}`, {
    headers: { 'Content-Type': 'application/json' },
    cache: 'no-store',
  });
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || '导出失败，请稍后重试');
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `financial-report-${params.reportType}.${params.format.toLowerCase()}`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
  return response.headers.get('Content-Disposition') ?? '';
}

// ================================================================ 可读形态

/** 报表类型的可读名。 */
export function reportTypeLabel(reportType: string): string {
  switch (reportType) {
    case 'BALANCE_SHEET':
      return '资产负债表';
    case 'INCOME_STATEMENT':
      return '利润表';
    case 'CASH_FLOW':
      return '现金流量表';
    case 'TRIAL_BALANCE':
      return '科目余额表';
    case 'GENERAL_LEDGER':
      return '明细账';
    default:
      return reportType;
  }
}

/** 段落的可读名。 */
export function reportSectionLabel(section: string): string {
  switch (section) {
    case 'ASSET':
      return '资产';
    case 'LIABILITY':
      return '负债';
    case 'EQUITY':
      return '所有者权益';
    case 'RETAINED':
      return '损益结转';
    case 'UNCLASSIFIED':
      return '未分类';
    case 'REVENUE':
      return '收入';
    case 'EXPENSE':
      return '成本与费用';
    case 'OPERATING':
      return '经营活动';
    case 'INVESTING':
      return '投资活动';
    case 'FINANCING':
      return '筹资活动';
    case 'CASH_UNCLASSIFIED':
      return '未分类活动';
    case 'SUBJECT':
      return '科目';
    case 'TOTAL':
      return '合计';
    default:
      return section;
  }
}

/**
 * 金额的可读形态。
 *
 * <p>⚠️ **不在前端做算术**：数值原样透传（只做显示格式化）。
 */
export function formatReportAmount(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '—';
  return amount.toFixed(4);
}

/** 金额 + 币种的可读形态。 */
export function formatReportMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) return '—';
  return `${amount.toFixed(4)} ${currency ?? ''}`.trim();
}

/** 变动额的可读形态（正数带 + 号，零就是 0）。 */
export function formatReportDelta(amount: number | null | undefined): string {
  if (amount === null || amount === undefined) return '—';
  if (amount > 0) return `+${amount.toFixed(4)}`;
  return amount.toFixed(4);
}

/**
 * 自检项的可读**语气**。
 *
 * <p>🔴 差额不为 0 是**危险**语气，不是「警告」：报表与明细对不上是财务系统的致命问题。
 */
export function reportCheckTone(check: FinanceReportCheck): 'success' | 'danger' {
  return check.balanced ? 'success' : 'danger';
}

/** 自检项的一句话说明。 */
export function reportCheckSummary(check: FinanceReportCheck): string {
  return `${check.checkName}：左 ${formatReportAmount(check.leftValue)} / 右 ${formatReportAmount(
    check.rightValue,
  )} / 差额 ${formatReportAmount(check.difference)}`;
}

/**
 * 把服务端给的绝对钻取路径转成前端 {@link api} 能用的路径。
 *
 * <p>服务端给的是**后端真实路径**（{@code /finance/financial-reports/drill?...}）⇒
 * 去掉开头的斜杠就是 api() 需要的形状（它自己会补 {@code /api/finance/}）。
 */
export function toApiPath(drillPath: string): string {
  return drillPath.startsWith('/') ? drillPath.slice(1) : drillPath;
}

/**
 * 把一行报表按段落分组（**只做展示分组，不做任何算术**）。
 *
 * <p>分组顺序沿用服务端给的行序（资产 → 负债 → 权益 → 损益结转 → 合计），
 * 页面不自己重排，免得与打印口径对不上。
 */
export function groupRowsBySection(rows: FinanceReportRow[]): {
  section: string;
  label: string;
  rows: FinanceReportRow[];
}[] {
  const groups: { section: string; label: string; rows: FinanceReportRow[] }[] = [];
  for (const row of rows) {
    const last = groups[groups.length - 1];
    if (last && last.section === row.section) {
      last.rows.push(row);
      continue;
    }
    groups.push({ section: row.section, label: row.sectionLabel, rows: [row] });
  }
  return groups;
}

/** 一行是不是合计行。 */
export function isReportTotalRow(row: FinanceReportRow): boolean {
  return row.totalRow;
}

/** 明细账逐笔的可读形态：日期 + 凭证号 + 行号。 */
export function formatLedgerEntryRef(entry: FinanceReportLedgerEntry): string {
  return `${entry.entryDate} · ${entry.voucherNo} · 行 ${entry.lineNo}`;
}

/** 借贷方向的可读名。 */
export function reportLineTypeLabel(lineType: string): string {
  switch (lineType) {
    case 'DEBIT':
      return '借';
    case 'CREDIT':
      return '贷';
    default:
      return lineType;
  }
}

/** 科目类别的可读名。 */
export function reportCategoryLabel(category: string): string {
  switch (category) {
    case 'ASSET':
      return '资产';
    case 'LIABILITY':
      return '负债';
    case 'EQUITY':
      return '所有者权益';
    case 'COST':
      return '成本';
    case 'PROFIT_LOSS':
      return '损益';
    case '':
      return '未在科目表登记';
    default:
      return category;
  }
}
